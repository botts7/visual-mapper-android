package com.visualmapper.companion.explorer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.visualmapper.companion.explorer.ml.TFLiteQNetwork
import com.visualmapper.companion.storage.QTableRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Q-Learning implementation for Smart Explorer
 *
 * Learns optimal exploration strategies from experience:
 * - Which elements lead to new screens (high reward)
 * - Which elements cause crashes/closes (negative reward)
 * - Optimal exploration order for each app
 *
 * Architecture:
 * - DEVELOPMENT: Logs exploration data via MQTT for training on Surface Laptop
 * - PRODUCTION: Loads pre-trained Q-table from assets, on-device inference only
 * - HYBRID MODE: Uses TFLite neural network for prediction, falls back to Q-table
 *
 * Phase 3 ML Stability:
 * - Persistence migrated from SharedPreferences to Room via QTableRepository
 * - Automatic pruning when Q-table exceeds MAX_Q_TABLE_SIZE
 * - Atomic writes prevent data corruption on process kill
 */
class ExplorationQLearning(private val context: Context) {

    companion object {
        private const val TAG = "ExplorationQLearning"
        private const val PREFS_NAME = "exploration_q_table"
        private const val KEY_TOTAL_ACTIONS = "total_actions"

        // Q-learning hyperparameters
        const val ALPHA = 0.15f     // Learning rate - slightly higher for faster adaptation
        const val GAMMA = 0.9f      // Discount factor - importance of future rewards
        const val BETA = 0.25f      // Human influence factor (H(s,a) weighting)

        // Epsilon decay parameters (exploration vs exploitation)
        const val EPSILON_START = 0.30f    // Start with 30% exploration
        const val EPSILON_MIN = 0.05f      // Minimum 5% exploration (always some randomness)
        const val EPSILON_DECAY = 0.995f   // Decay rate per action

        // Reward values - ENHANCED with depth and novelty bonuses
        const val REWARD_NEW_SCREEN = 1.0f
        const val REWARD_NEW_ELEMENTS = 0.5f
        const val REWARD_NAVIGATE_BACK = 0.2f
        const val REWARD_NO_CHANGE = -0.1f
        const val REWARD_CLOSED_APP = -1.5f
        const val REWARD_CRASH = -2.0f

        // Depth bonuses (deeper exploration = higher reward)
        const val DEPTH_BONUS_PER_LEVEL = 0.15f   // +0.15 reward per navigation depth
        const val MAX_DEPTH_BONUS = 0.6f          // Cap at +0.6

        // Novelty bonuses
        const val NOVELTY_BONUS_FIRST_VISIT = 0.3f  // Bonus for first time tapping an element
        const val SCREEN_REVISIT_PENALTY = -0.05f   // Small penalty for revisiting same screen

        // UCB (Upper Confidence Bound) for exploration bonus
        const val UCB_COEFFICIENT = 1.5f  // Exploration bonus coefficient

        // Hybrid mode thresholds
        const val MIN_EXPERIENCES_FOR_NEURAL = 500  // Use NN only after this many Q-table entries
        const val NEURAL_WEIGHT = 0.7f              // Weight for neural network (vs Q-table) in hybrid mode
    }

    // Phase 3: Room-based persistence via QTableRepository
    private val repository = QTableRepository.getInstance(context)

    // Legacy prefs for total_actions only (will be migrated in future)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Coroutine scope for async operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Initialization state
    private var initialized = false

    // Exploration log for MQTT training (development mode)
    private val explorationLog: MutableList<ExplorationLogEntry> = mutableListOf()

    // Total actions taken (for epsilon decay)
    private var totalActions: Int = prefs.getInt(KEY_TOTAL_ACTIONS, 0)

    // === RESTART RECOVERY TRACKING ===
    // Track outcomes of clean restart recovery attempts
    private val restartRecoveryOutcomes: MutableList<RestartRecoveryOutcome> = mutableListOf()
    private var totalRestartAttempts: Int = 0
    private var successfulRestartRecoveries: Int = 0

    // Current navigation depth (set by AppExplorerService)
    var currentDepth: Int = 0

    // Screen height for position calculation (set during exploration)
    var screenHeight: Int = 2400 // Default, will be updated

    // =====================================================================
    // Initialization (Phase 3: Room Migration)
    // =====================================================================

    /**
     * Initialize the Q-learning system with Room-based persistence.
     * Call this before starting exploration to ensure data is loaded.
     *
     * This handles:
     * - Migration from SharedPreferences to Room (one-time)
     * - Loading Q-table cache from Room
     * - Setting up async write pipeline
     */
    suspend fun initialize() {
        if (initialized) return

        repository.initialize()
        initialized = true
        Log.i(TAG, "ExplorationQLearning initialized (Q-table size: ${repository.qTableSize()})")
    }

    /**
     * Check if initialization is complete.
     */
    fun isInitialized(): Boolean = initialized

    /**
     * Get current epsilon with decay based on experience
     * More experience = less random exploration = more exploitation of learned values
     */
    fun getCurrentEpsilon(): Float {
        val decayedEpsilon = EPSILON_START * Math.pow(EPSILON_DECAY.toDouble(), totalActions.toDouble()).toFloat()
        return maxOf(EPSILON_MIN, decayedEpsilon)
    }

    /**
     * Get a copy of the Q-table for analysis (read-only)
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun getQTable(): Map<String, Float> = repository.getAllQValues()

    /**
     * Get the number of Q-values stored
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun qTableSize(): Int = repository.qTableSize()

    // ====== HYBRID MODE: Neural Network Support ======

    // TFLite neural network for Q-value prediction
    private var tfLiteNetwork: TFLiteQNetwork? = null
    private var useNeuralNetwork = false

    /**
     * Enable neural network for hybrid Q-value prediction
     * Falls back to Q-table if network fails or has low confidence
     */
    fun enableNeuralNetwork(): Boolean {
        try {
            tfLiteNetwork = TFLiteQNetwork.getInstance(context)
            val loaded = tfLiteNetwork?.loadBestAvailableModel() ?: false

            if (loaded) {
                useNeuralNetwork = true
                Log.i(TAG, "Neural network enabled: ${tfLiteNetwork?.modelSource}")
            } else {
                Log.w(TAG, "No TFLite model available, using Q-table only")
            }

            return loaded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable neural network", e)
            return false
        }
    }

    /**
     * Disable neural network (use Q-table only)
     */
    fun disableNeuralNetwork() {
        useNeuralNetwork = false
        tfLiteNetwork?.close()
        tfLiteNetwork = null
        Log.i(TAG, "Neural network disabled, using Q-table only")
    }

    /**
     * Check if neural network is currently active
     */
    fun isNeuralNetworkActive(): Boolean {
        return useNeuralNetwork && tfLiteNetwork?.isReady() == true
    }

    /**
     * Called when a new model is received via MQTT
     */
    fun onModelUpdated() {
        tfLiteNetwork?.loadBestAvailableModel()
        Log.i(TAG, "Model hot-reloaded")
    }

    /**
     * Check if we have enough experience to benefit from neural network
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun hasEnoughExperienceForNeural(): Boolean {
        return repository.qTableSize() >= MIN_EXPERIENCES_FOR_NEURAL
    }

    /**
     * Get the Q-value for a state-action pair
     * Phase 3: Now backed by Room via QTableRepository (from cache)
     */
    fun getQValue(screenHash: String, actionKey: String): Float {
        val key = "$screenHash|$actionKey"
        return repository.getQValue(key) ?: 0f
    }

    /**
     * Update Q-value using modified Q-learning update rule with human feedback:
     * Q(s,a) = Q(s,a) + α * (reward + γ * max(Q(s',a')) + β * H(s,a) - Q(s,a))
     *
     * Where H(s,a) is human feedback: +1 for imitation, -1 for veto
     *
     * Phase 3: Now persisted to Room via QTableRepository with automatic pruning
     */
    fun updateQ(
        screenHash: String,
        actionKey: String,
        reward: Float,
        nextScreenHash: String?,
        packageName: String? = null
    ) {
        val key = "$screenHash|$actionKey"
        val currentQ = repository.getQValue(key) ?: 0f

        // Get max Q-value for next state (if we have one)
        val nextMaxQ = if (nextScreenHash != null) {
            getMaxQForScreen(nextScreenHash)
        } else {
            0f
        }

        // Get human feedback signal if available
        val humanSignal = repository.getHumanFeedback(key) ?: 0
        val humanTerm = BETA * humanSignal.toFloat()

        // Modified Q-learning update rule with human feedback term
        val newQ = currentQ + ALPHA * (reward + GAMMA * nextMaxQ + humanTerm - currentQ)

        // Update Q-value in repository (async write to Room)
        repository.updateQValue(key, newQ, packageName)

        // Clear human feedback after it's been applied (one-time boost/penalty)
        if (humanSignal != 0) {
            repository.setHumanFeedback(key, 0, packageName)  // Clear by setting to 0
            Log.d(TAG, "Applied human feedback H=$humanSignal for $actionKey")
        }

        Log.d(TAG, "Q-update: $key | reward=$reward, oldQ=$currentQ, newQ=$newQ")

        // Log for MQTT training
        explorationLog.add(ExplorationLogEntry(
            screenHash = screenHash,
            actionKey = actionKey,
            reward = reward,
            nextScreenHash = nextScreenHash,
            timestamp = System.currentTimeMillis()
        ))

        // Note: Persistence is now handled by repository (async writes)
        // No need for periodic saveQTable() - repository handles this
    }

    /**
     * Get max Q-value for all known actions in a screen
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun getMaxQForScreen(screenHash: String): Float {
        return repository.getAllQValues().entries
            .filter { it.key.startsWith("$screenHash|") }
            .maxOfOrNull { it.value } ?: 0f
    }

    // ====== HUMAN-IN-THE-LOOP: Feedback Recording ======

    /**
     * Record human feedback for a state-action pair.
     * This implements the H(s,a) term in the modified Bellman update.
     *
     * @param screenHash The screen state hash
     * @param actionKey The action identifier
     * @param signal The feedback signal: +1 for imitation (user did this), -1 for veto (user rejected)
     * @param packageName Optional package name for app-specific filtering
     *
     * Phase 3: Now persisted to Room via QTableRepository
     */
    fun recordHumanFeedback(screenHash: String, actionKey: String, signal: Int, packageName: String? = null) {
        val key = "$screenHash|$actionKey"
        val clampedSignal = signal.coerceIn(-1, 1)

        // Accumulate feedback signals (multiple vetoes = stronger signal)
        val existing = repository.getHumanFeedback(key) ?: 0
        val newValue = (existing + clampedSignal).coerceIn(-3, 3)
        repository.setHumanFeedback(key, newValue, packageName)

        Log.i(TAG, "Human feedback recorded: $actionKey -> H=$newValue (signal=$clampedSignal)")
    }

    /**
     * Get current human feedback signal for a state-action pair.
     * Returns 0 if no feedback has been recorded.
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun getHumanFeedback(screenHash: String, actionKey: String): Int {
        val key = "$screenHash|$actionKey"
        return repository.getHumanFeedback(key) ?: 0
    }

    /**
     * Check if an action has been vetoed by the user.
     * Returns true if the action has accumulated negative feedback.
     */
    fun isVetoedAction(screenHash: String, actionKey: String): Boolean {
        val feedback = getHumanFeedback(screenHash, actionKey)
        return feedback < 0
    }

    /**
     * Clear all human feedback (for reset/testing)
     * Phase 3: This now clears from Room database
     */
    fun clearHumanFeedback() {
        scope.launch {
            repository.clearAll()  // Will clear all data - use cautiously
        }
        Log.i(TAG, "Human feedback cleared")
    }

    /**
     * Select an element using ε-greedy policy with decay and UCB bonus
     * Returns the recommended element or null if random exploration
     *
     * Features:
     * - Epsilon decay: Less random exploration as we gain experience
     * - UCB bonus: Encourage exploring less-visited elements
     * - HYBRID MODE: Uses neural network if available and has enough experience
     */
    fun selectElement(
        screen: ExploredScreen,
        unexploredOnly: Boolean = false
    ): ClickableElement? {
        val candidates = if (unexploredOnly) {
            screen.clickableElements.filter { !it.explored }
        } else {
            screen.clickableElements
        }

        if (candidates.isEmpty()) return null

        // Increment action counter for epsilon decay
        totalActions++

        // ε-greedy with decay: random exploration with decaying probability
        val currentEpsilon = getCurrentEpsilon()
        if (Random.nextFloat() < currentEpsilon) {
            Log.d(TAG, "ε-greedy (ε=${"%.3f".format(currentEpsilon)}): Random exploration")
            return candidates.random()
        }

        // HYBRID MODE: Use neural network if available and trained
        if (isNeuralNetworkActive() && hasEnoughExperienceForNeural()) {
            return selectElementHybrid(screen, candidates)
        }

        // Use Q-table with UCB bonus
        return selectElementWithUCB(screen, candidates)
    }

    /**
     * Select element using Q-table with UCB (Upper Confidence Bound) bonus
     * UCB encourages exploring less-visited actions to reduce uncertainty
     * Phase 3: Now backed by Room via QTableRepository
     */
    private fun selectElementWithUCB(
        screen: ExploredScreen,
        candidates: List<ClickableElement>
    ): ClickableElement? {
        val screenHash = computeScreenHash(screen)
        val totalScreenVisits = repository.getScreenVisitCount(screenHash).coerceAtLeast(1)

        val bestElement = candidates.maxByOrNull { element ->
            val actionKey = getActionKey(element)
            val key = "$screenHash|$actionKey"
            val qValue = repository.getQValue(key) ?: 0f
            val visits = repository.getVisitCount(key)

            // UCB formula: Q(s,a) + c * sqrt(ln(N) / n)
            // N = total visits to this screen, n = visits to this action
            val ucbBonus = if (visits > 0) {
                UCB_COEFFICIENT * kotlin.math.sqrt(kotlin.math.ln(totalScreenVisits.toFloat() + 1) / visits)
            } else {
                UCB_COEFFICIENT * 2.0f  // High bonus for never-visited actions
            }

            val combinedScore = qValue + ucbBonus
            combinedScore
        }

        Log.d(TAG, "UCB: Selected ${bestElement?.elementId} (ε=${"%.3f".format(getCurrentEpsilon())})")
        return bestElement
    }

    /**
     * Select element using Q-table only
     */
    private fun selectElementFromQTable(
        screen: ExploredScreen,
        candidates: List<ClickableElement>
    ): ClickableElement? {
        val screenHash = computeScreenHash(screen)

        val bestElement = candidates.maxByOrNull { element ->
            val actionKey = getActionKey(element)
            getQValue(screenHash, actionKey)
        }

        Log.d(TAG, "Q-table: Selected ${bestElement?.elementId}")
        return bestElement
    }

    /**
     * Select element using hybrid neural + Q-table approach
     *
     * Strategy:
     * 1. Get Q-values from neural network for all candidates
     * 2. Get Q-values from Q-table for known state-actions
     * 3. Blend predictions: combined = (1-α)*table + α*neural
     * 4. Select element with highest combined score
     */
    private fun selectElementHybrid(
        screen: ExploredScreen,
        candidates: List<ClickableElement>
    ): ClickableElement? {
        val screenHash = computeScreenHash(screen)

        // Get neural network predictions
        val neuralQValues = tfLiteNetwork?.predictQValues(screen) ?: emptyMap()

        // Calculate combined scores
        val scores = candidates.map { element ->
            val actionKey = getActionKey(element)
            val tableQ = getQValue(screenHash, actionKey)
            val neuralQ = neuralQValues[element] ?: 0f

            // Check if we have Q-table knowledge for this state-action
            val hasTableKnowledge = repository.getQValue("$screenHash|$actionKey") != null

            // Blend scores: prioritize neural when we have less table knowledge
            val combinedQ = if (hasTableKnowledge) {
                // Known state-action: blend neural and table
                (1 - NEURAL_WEIGHT) * tableQ + NEURAL_WEIGHT * neuralQ
            } else {
                // Unknown state-action: rely more on neural network
                0.3f * tableQ + 0.7f * neuralQ
            }

            element to combinedQ
        }

        val bestPair = scores.maxByOrNull { it.second }
        val bestElement = bestPair?.first

        Log.d(TAG, "Hybrid: Selected ${bestElement?.elementId} (score=${bestPair?.second})")
        return bestElement
    }

    /**
     * Get priority boost for an element based on learned Q-values AND exploration bonuses
     * Used to enhance the existing priority calculation
     *
     * Includes:
     * - Q-value based boost (learned from experience)
     * - UCB exploration bonus (for less-visited elements)
     * - Novelty bonus (for never-visited elements)
     * - Penalty for confirmed dead-ends
     *
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun getElementPriorityBoost(screen: ExploredScreen, element: ClickableElement): Int {
        val screenHash = computeScreenHash(screen)
        val actionKey = getActionKey(element)
        val key = "$screenHash|$actionKey"
        val qValue = getQValue(screenHash, actionKey)
        val visits = repository.getVisitCount(key)

        // If this is a confirmed dead-end, apply strong penalty
        if (isConfirmedDeadEnd(screenHash, actionKey)) {
            return -80  // Strong penalty for confirmed dead-ends
        }

        // Base Q-value boost (scale to -40 to +40)
        var boost = (qValue * 40).toInt().coerceIn(-40, 40)

        // === NOVELTY BONUS ===
        // Never-visited elements get a significant boost
        if (visits == 0) {
            boost += 25  // Strong bonus for unexplored elements
        } else if (visits <= 2) {
            boost += 10  // Smaller bonus for rarely-visited elements
        }

        // === UCB EXPLORATION BONUS ===
        // Add uncertainty bonus for less-visited elements
        val totalScreenVisits = repository.getScreenVisitCount(screenHash).coerceAtLeast(1)
        if (visits > 0 && totalScreenVisits > 1) {
            val ucbBonus = (UCB_COEFFICIENT * kotlin.math.sqrt(
                kotlin.math.ln(totalScreenVisits.toFloat()) / visits
            ) * 10).toInt()
            boost += ucbBonus.coerceAtMost(15)
        }

        return boost.coerceIn(-80, 60)
    }

    /**
     * Check if an element is a confirmed dead-end
     * (consistently negative Q-value with multiple visits)
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun isConfirmedDeadEnd(screenHash: String, actionKey: String): Boolean {
        val key = "$screenHash|$actionKey"
        val qValue = repository.getQValue(key) ?: return false
        val visits = repository.getVisitCount(key)

        // Element is a confirmed dead-end if:
        // - Q-value is negative (never led to new screen)
        // - Visited at least 3 times (consistent behavior)
        return qValue < -0.05f && visits >= 3
    }

    /**
     * Check if an element should be skipped entirely
     * (confirmed dead-end with many visits)
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun shouldSkipElement(screen: ExploredScreen, element: ClickableElement): Boolean {
        val screenHash = computeScreenHash(screen)
        val actionKey = getActionKey(element)
        val key = "$screenHash|$actionKey"
        val qValue = repository.getQValue(key) ?: return false
        val visits = repository.getVisitCount(key)

        // Skip if very negative Q-value and visited many times
        val shouldSkip = qValue < -0.08f && visits >= 5
        if (shouldSkip) {
            Log.d(TAG, "Skipping confirmed dead-end: $actionKey (Q=$qValue, visits=$visits)")
        }
        return shouldSkip
    }

    /**
     * Check if an element pattern is known to be dangerous
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun isDangerousPattern(element: ClickableElement): Boolean {
        val pattern = getActionKey(element)
        return repository.isDangerousPattern(pattern)
    }

    /**
     * Mark a pattern as dangerous (caused app to close)
     * Phase 3: Now persisted to Room via QTableRepository
     */
    fun markPatternDangerous(element: ClickableElement, packageName: String? = null) {
        val pattern = getActionKey(element)
        repository.addDangerousPattern(pattern, packageName)
        Log.w(TAG, "Marked pattern as dangerous: $pattern")
    }

    /**
     * Mark a screen as a dead-end (exploration got stuck there).
     * Applies strong negative reward to all actions leading TO this screen
     * so future explorations will avoid it.
     *
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun markScreenAsDeadEnd(screenId: String, packageName: String? = null) {
        Log.w(TAG, "Marking screen as dead-end: $screenId")

        // Find all transitions that led TO this screen and penalize them
        var penalized = 0
        val qTable = repository.getAllQValues()
        for ((key, currentQ) in qTable.entries) {
            // Key format is "screenHash|actionKey"
            // We want to find actions that led to this dead-end screen
            // Since we don't directly track transitions in qTable, we'll penalize
            // actions taken FROM this screen (so we don't waste time there)
            if (key.startsWith("$screenId|")) {
                val newQ = minOf(currentQ, -0.5f)  // Ensure strongly negative
                repository.updateQValue(key, newQ, packageName)
                penalized++
            }
        }

        // Also add this screen to a dead-end list for future reference
        deadEndScreens.add(screenId)

        Log.i(TAG, "Dead-end: Penalized $penalized actions on screen $screenId")
    }

    /**
     * Check if a screen is known to be a dead-end
     */
    fun isDeadEndScreen(screenId: String): Boolean {
        return deadEndScreens.contains(screenId)
    }

    // Track dead-end screens (in-memory only for now)
    private val deadEndScreens = mutableSetOf<String>()

    /**
     * Calculate reward for a tap result (basic version for compatibility)
     */
    fun calculateReward(result: TapResult): Float {
        return calculateEnhancedReward(result, null, false)
    }

    /**
     * Calculate enhanced reward with depth bonus and novelty tracking
     *
     * @param result The outcome of the action
     * @param screenHash The target screen hash (for novelty tracking)
     * @param isFirstVisitToElement Whether this is the first time tapping this element
     * @param packageName Optional package name for app-specific tracking
     *
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun calculateEnhancedReward(
        result: TapResult,
        screenHash: String?,
        isFirstVisitToElement: Boolean = false,
        packageName: String? = null
    ): Float {
        var reward = when (result) {
            TapResult.NEW_SCREEN -> REWARD_NEW_SCREEN
            TapResult.NEW_ELEMENTS -> REWARD_NEW_ELEMENTS
            TapResult.NAVIGATE_BACK -> REWARD_NAVIGATE_BACK
            TapResult.NO_CHANGE -> REWARD_NO_CHANGE
            TapResult.CLOSED_APP -> REWARD_CLOSED_APP
            TapResult.CRASH -> REWARD_CRASH
        }

        // === DEPTH BONUS ===
        // Reward deeper exploration (navigating further into app hierarchy)
        if (result == TapResult.NEW_SCREEN) {
            val depthBonus = (currentDepth * DEPTH_BONUS_PER_LEVEL).coerceAtMost(MAX_DEPTH_BONUS)
            reward += depthBonus
            if (depthBonus > 0) {
                Log.d(TAG, "Depth bonus: +$depthBonus (depth=$currentDepth)")
            }
        }

        // === NOVELTY BONUS ===
        // Reward first-time interactions with elements
        if (isFirstVisitToElement && result != TapResult.CLOSED_APP && result != TapResult.CRASH) {
            reward += NOVELTY_BONUS_FIRST_VISIT
            Log.d(TAG, "Novelty bonus: +$NOVELTY_BONUS_FIRST_VISIT (first visit)")
        }

        // === SCREEN REVISIT PENALTY ===
        // Small penalty for returning to already-visited screens (encourages forward progress)
        if (result == TapResult.NEW_SCREEN && screenHash != null) {
            val previousVisits = repository.getScreenVisitCount(screenHash)
            if (previousVisits > 0) {
                val penalty = SCREEN_REVISIT_PENALTY * previousVisits.coerceAtMost(5)
                reward += penalty
                Log.d(TAG, "Revisit penalty: $penalty (visited $previousVisits times)")
            }
            // Update screen visit count (async write to Room)
            repository.incrementScreenVisit(screenHash, packageName)
        }

        return reward
    }

    /**
     * Record that we visited a screen (for novelty tracking)
     * Phase 3: Now persisted to Room via QTableRepository
     */
    fun recordScreenVisit(screenHash: String, packageName: String? = null) {
        repository.incrementScreenVisit(screenHash, packageName)
    }

    /**
     * Check if this is the first visit to a state-action pair
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun isFirstVisit(screenHash: String, actionKey: String): Boolean {
        val key = "$screenHash|$actionKey"
        return repository.getVisitCount(key) == 0
    }

    /**
     * Compute a stable screen hash for state representation
     * Uses activity + sorted element IDs
     */
    fun computeScreenHash(screen: ExploredScreen): String {
        val elements = screen.clickableElements.map { it.elementId }.sorted()
        val input = "${screen.activity}|${elements.joinToString(",")}"
        return sha256(input).take(16)
    }

    /**
     * Get action key for an element
     * Format: "ElementType|resourceIdPattern|position"
     */
    fun getActionKey(element: ClickableElement): String {
        val type = element.className.substringAfterLast('.')

        // Extract resource ID pattern (replace numbers with *)
        val pattern = element.resourceId?.let {
            it.substringAfterLast('/').replace(Regex("\\d+"), "*")
        } ?: "none"

        // Determine position (top, center, bottom)
        val pos = when {
            element.centerY < screenHeight / 3 -> "top"
            element.centerY > screenHeight * 2 / 3 -> "bottom"
            else -> "center"
        }

        return "$type|$pattern|$pos"
    }

    /**
     * Get exploration log for MQTT publishing (development mode)
     */
    fun getExplorationLog(): List<ExplorationLogEntry> {
        return explorationLog.toList()
    }

    /**
     * Clear exploration log after MQTT publish
     */
    fun clearExplorationLog() {
        explorationLog.clear()
    }

    /**
     * Get exploration statistics
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun getStatistics(): ExplorationStatistics {
        val qTableValues = repository.getAllQValues()
        val dangerousPatternsSet = repository.getDangerousPatterns()
        return ExplorationStatistics(
            qTableSize = qTableValues.size,
            totalVisits = qTableValues.keys.sumOf { repository.getVisitCount(it) },
            dangerousPatterns = dangerousPatternsSet.size,
            averageQValue = if (qTableValues.isEmpty()) 0f else qTableValues.values.average().toFloat(),
            maxQValue = qTableValues.values.maxOrNull() ?: 0f,
            minQValue = qTableValues.values.minOrNull() ?: 0f,
            currentEpsilon = getCurrentEpsilon(),
            totalActions = totalActions,
            screenVisitCount = qTableValues.keys.map { it.split("|").firstOrNull() }.distinct().count(),
            isNeuralNetworkActive = isNeuralNetworkActive(),
            modelSource = tfLiteNetwork?.modelSource?.name ?: "NONE",
            modelVersion = tfLiteNetwork?.getModelVersion() ?: "",
            // Restart recovery metrics
            restartAttempts = totalRestartAttempts,
            restartSuccessRate = if (totalRestartAttempts > 0)
                successfulRestartRecoveries.toFloat() / totalRestartAttempts else 0f
        )
    }

    // =========================================================================
    // Restart Recovery Tracking
    // =========================================================================

    /**
     * Record outcome of a clean restart recovery attempt.
     * Used by ADAPTIVE mode to track whether restarts help exploration coverage.
     *
     * @param success Whether the restart successfully resumed exploration
     * @param reason Why restart was triggered ("STUCK_THRESHOLD", "COVERAGE_PLATEAU", "RESTART_FAILED")
     */
    fun recordRestartRecovery(success: Boolean, reason: String) {
        totalRestartAttempts++
        if (success) {
            successfulRestartRecoveries++
        }

        val outcome = RestartRecoveryOutcome(
            timestamp = System.currentTimeMillis(),
            success = success,
            reason = reason,
            qTableSizeAtRestart = repository.qTableSize(),
            screensExploredAtRestart = repository.getAllQValues().keys
                .map { it.split("|").firstOrNull() }.distinct().count()
        )
        restartRecoveryOutcomes.add(outcome)

        Log.i(TAG, "[RestartRecovery] Recorded: success=$success, reason=$reason " +
            "(total: $totalRestartAttempts, success rate: ${if (totalRestartAttempts > 0)
                "%.1f%%".format(successfulRestartRecoveries * 100f / totalRestartAttempts) else "N/A"})")
    }

    /**
     * Get restart recovery outcomes for analysis
     */
    fun getRestartRecoveryOutcomes(): List<RestartRecoveryOutcome> {
        return restartRecoveryOutcomes.toList()
    }

    /**
     * Load Q-table from pre-trained JSON asset (production mode)
     * Phase 3: Loads into Room via QTableRepository
     */
    fun loadFromAsset(assetName: String, packageName: String? = null): Boolean {
        return try {
            val json = context.assets.open(assetName).bufferedReader().readText()
            val jsonObject = JSONObject(json)
            var loadedCount = 0
            jsonObject.keys().forEach { key ->
                val value = jsonObject.getDouble(key).toFloat()
                repository.updateQValue(key, value, packageName)
                loadedCount++
            }
            Log.i(TAG, "Loaded $loadedCount Q-values from asset: $assetName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load Q-table from asset: $assetName", e)
            false
        }
    }

    /**
     * Merge Q-table updates from ML training server
     * Server values are blended with local values (server has more training data)
     * Phase 3: Now persisted to Room via QTableRepository
     */
    fun mergeServerQTable(serverQTableJson: String, packageName: String? = null): Int {
        return try {
            val jsonObject = JSONObject(serverQTableJson)
            val serverQTable = jsonObject.optJSONObject("q_table") ?: jsonObject
            var mergedCount = 0

            serverQTable.keys().forEach { key ->
                val serverValue = serverQTable.getDouble(key).toFloat()
                val localValue = repository.getQValue(key)

                val newValue = if (localValue == null) {
                    // New entry from server - add it
                    serverValue
                } else {
                    // Blend server and local values (server weight: 0.7, local: 0.3)
                    // Server has more training data, so trust it more
                    serverValue * 0.7f + localValue * 0.3f
                }

                repository.updateQValue(key, newValue, packageName)
                mergedCount++
            }

            Log.i(TAG, "Merged $mergedCount Q-values from server (total: ${repository.qTableSize()})")
            mergedCount
        } catch (e: Exception) {
            Log.e(TAG, "Failed to merge server Q-table", e)
            0
        }
    }

    /**
     * Export Q-table as JSON string for training server
     * Phase 3: Now backed by Room via QTableRepository
     */
    fun exportQTableJson(): String {
        val jsonObject = JSONObject()
        repository.getAllQValues().forEach { (key, value) ->
            jsonObject.put(key, value.toDouble())
        }
        return jsonObject.toString()
    }

    /**
     * Reset all learned data (for testing)
     * Phase 3: Now clears from Room via QTableRepository
     */
    fun reset() {
        scope.launch {
            repository.clearAll()
        }
        explorationLog.clear()
        deadEndScreens.clear()
        totalActions = 0
        prefs.edit().putInt(KEY_TOTAL_ACTIONS, 0).apply()
        Log.i(TAG, "Q-learning state reset (including human feedback)")
    }

    // === Persistence ===
    // Phase 3: All persistence is now handled by QTableRepository
    // The old SharedPreferences methods have been removed

    /**
     * Save all state (call on exploration end)
     * Phase 3: Now syncs to Room via QTableRepository
     */
    fun saveAll() {
        scope.launch {
            repository.syncToRoom()
        }
        prefs.edit().putInt(KEY_TOTAL_ACTIONS, totalActions).apply()
        Log.i(TAG, "Saved Q-learning state: ${repository.qTableSize()} Q-values, ${repository.getDangerousPatterns().size} dangerous patterns, ε=${"%.3f".format(getCurrentEpsilon())}")
    }

    /**
     * Cleanup resources when no longer needed.
     * Call this when the exploration service is destroyed.
     *
     * IMPORTANT: This MUST be called from AppExplorerService.onDestroy()
     * to prevent coroutine leaks and ensure final Q-table sync.
     */
    fun destroy() {
        // Sync to Room before canceling scope
        runBlocking {
            try {
                repository.syncToRoom()
                Log.i(TAG, "Final Q-table sync completed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync Q-table on destroy", e)
            }
        }
        scope.cancel()
        repository.destroy()
        Log.i(TAG, "ExplorationQLearning destroyed")
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Result of a tap action for reward calculation
 */
enum class TapResult {
    NEW_SCREEN,      // Discovered a new screen (+1.0)
    NEW_ELEMENTS,    // Revealed new elements via scroll (+0.5)
    NAVIGATE_BACK,   // Successfully navigated back (+0.2)
    NO_CHANGE,       // No visible effect (-0.1)
    CLOSED_APP,      // App was minimized/closed (-1.5)
    CRASH            // App crashed (-2.0)
}

/**
 * Log entry for MQTT training data
 */
data class ExplorationLogEntry(
    val screenHash: String,
    val actionKey: String,
    val reward: Float,
    val nextScreenHash: String?,
    val timestamp: Long
)

/**
 * Exploration statistics for monitoring
 */
data class ExplorationStatistics(
    val qTableSize: Int,
    val totalVisits: Int,
    val dangerousPatterns: Int,
    val averageQValue: Float,
    val maxQValue: Float,
    val minQValue: Float,
    // Epsilon decay tracking
    val currentEpsilon: Float = 0.3f,
    val totalActions: Int = 0,
    val screenVisitCount: Int = 0,
    // Neural network status
    val isNeuralNetworkActive: Boolean = false,
    val modelSource: String = "NONE",
    val modelVersion: String = "",
    // Restart recovery metrics
    val restartAttempts: Int = 0,
    val restartSuccessRate: Float = 0f
)

/**
 * Outcome of a clean restart recovery attempt
 * Used to track whether restarts help exploration coverage
 */
data class RestartRecoveryOutcome(
    val timestamp: Long,
    val success: Boolean,
    val reason: String,  // "STUCK_THRESHOLD", "COVERAGE_PLATEAU", "RESTART_FAILED"
    val qTableSizeAtRestart: Int,
    val screensExploredAtRestart: Int
)

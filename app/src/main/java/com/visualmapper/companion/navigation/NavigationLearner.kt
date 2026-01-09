package com.visualmapper.companion.navigation

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.visualmapper.companion.accessibility.UIElement
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.security.ConsentManager
import com.visualmapper.companion.security.SecurePreferences
import com.visualmapper.companion.storage.PendingTransition
import com.visualmapper.companion.storage.PendingTransitionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Navigation Learner
 *
 * Passively learns navigation patterns from user interactions with apps.
 * When a screen transition is detected, it captures the before/after state
 * and publishes it to the server via MQTT for building navigation graphs.
 *
 * Phase 1 Refactor: Added PendingTransitionDao for guaranteed delivery.
 * Transitions are now stored in Room database first, then flushed to MQTT.
 * This ensures no data loss during network outages.
 *
 * Privacy safeguards:
 * - Only learns from whitelisted apps
 * - Respects ConsentManager consent levels
 * - Off by default, must be enabled in settings
 * - Supports "execution only" mode that only learns during flow execution
 */
class NavigationLearner(
    private val mqttManager: MqttManager,
    private val consentManager: ConsentManager,
    private val securePrefs: SecurePreferences,
    private val transitionDao: PendingTransitionDao? = null,  // Phase 1: Optional DAO for persistence
    private val context: Context? = null  // Phase 3: Optional context for battery checks
) {
    companion object {
        private const val TAG = "NavigationLearner"
        private const val TRANSITION_DEBOUNCE_MS = 500L  // Prevent duplicate transitions
        private const val ACTION_EXPIRE_MS = 3000L      // Actions expire after 3 seconds
        private const val MAX_UI_ELEMENTS = 50          // Limit UI elements to reduce payload size
        private const val MAX_RETRY_COUNT = 10          // Max retries before dropping transition
        private const val RETRY_BACKOFF_MS = 60_000L    // 1 minute backoff between retries
        private const val LOW_BATTERY_THRESHOLD = 15    // Phase 3: Skip learning below this %
    }

    // Coroutine scope for DB operations (Phase 1)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // State tracking
    private var previousPackage: String? = null
    private var previousActivity: String? = null
    private var previousUIElements: List<UIElement> = emptyList()
    private var lastTransitionTime: Long = 0
    private var screenChangeTime: Long = 0

    // Pending action (captured when gesture is performed)
    private var pendingAction: TransitionAction? = null
    private var pendingActionTime: Long = 0

    // Flow execution tracking (for EXECUTION_ONLY mode)
    private var _isFlowExecuting = MutableStateFlow(false)
    val isFlowExecuting: StateFlow<Boolean> = _isFlowExecuting

    // Learning statistics
    private var transitionsLearned: Int = 0
    private var transitionsSkipped: Int = 0

    /**
     * Check if navigation learning is enabled
     */
    val isEnabled: Boolean
        get() = securePrefs.navigationLearningEnabled

    /**
     * Phase 3 Optimization: Fast pre-check before expensive UI tree parsing.
     *
     * Call this BEFORE parsing the accessibility UI tree to avoid wasted work.
     * Returns false if:
     * - Learning is disabled
     * - Within debounce window
     * - Battery is low (< 15%) and not charging
     *
     * This is a lightweight check that avoids the expensive getUITreeForLearning() call.
     */
    fun shouldProcessUpdate(): Boolean {
        // 1. Check if learning is enabled at all
        if (!isEnabled) {
            return false
        }

        // 2. Check debounce - fail fast for rapid updates
        val now = System.currentTimeMillis()
        if (now - lastTransitionTime < TRANSITION_DEBOUNCE_MS) {
            return false
        }

        // 3. Check battery level (Phase 3 optimization)
        if (!isBatteryOkForLearning()) {
            return false
        }

        return true
    }

    /**
     * Check if battery level allows learning.
     * Skips learning when battery < 15% and not charging to save power.
     *
     * Returns true (allow learning) if:
     * - Context not available (graceful fallback)
     * - Battery >= 15%
     * - Device is charging
     */
    private fun isBatteryOkForLearning(): Boolean {
        // Graceful fallback if context not provided
        if (context == null) {
            return true
        }

        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return true  // Fallback if service unavailable

            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            // Check if charging (API 23+)
            val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                batteryManager.isCharging
            } else {
                true  // Assume charging on older devices (conservative)
            }

            // Allow learning if charging OR battery is above threshold
            if (!isCharging && batteryLevel < LOW_BATTERY_THRESHOLD) {
                Log.v(TAG, "Skipping learning: Low battery ($batteryLevel%) and not charging")
                return false
            }

            true
        } catch (e: Exception) {
            Log.w(TAG, "Battery check failed, allowing learning: ${e.message}")
            true  // Fail open - allow learning if check fails
        }
    }

    /**
     * Check if we should learn based on current mode
     */
    private fun shouldLearn(): Boolean {
        if (!isEnabled) return false

        return when (securePrefs.navigationLearningMode) {
            NavigationLearningMode.PASSIVE -> true
            NavigationLearningMode.EXECUTION_ONLY -> _isFlowExecuting.value
        }
    }

    /**
     * Called when screen changes (from AccessibilityService)
     *
     * @param newPackage Package name of the current app
     * @param newActivity Activity class name of the current screen
     * @param newUIElements Current UI elements on screen
     */
    fun onScreenChanged(
        newPackage: String,
        newActivity: String,
        newUIElements: List<UIElement>
    ) {
        val now = System.currentTimeMillis()
        screenChangeTime = now

        // Check if learning is enabled and mode allows it
        if (!shouldLearn()) {
            updateState(newPackage, newActivity, newUIElements)
            return
        }

        // Check whitelist - only learn for allowed apps
        if (!securePrefs.isAppWhitelisted(newPackage)) {
            Log.v(TAG, "Skipping non-whitelisted app: $newPackage")
            updateState(newPackage, newActivity, newUIElements)
            return
        }

        // Check consent level
        val consentLevel = consentManager.getConsentLevel(newPackage)
        if (consentLevel == ConsentManager.ConsentLevel.NONE) {
            Log.v(TAG, "Skipping app without consent: $newPackage")
            updateState(newPackage, newActivity, newUIElements)
            return
        }

        // Debounce rapid transitions
        if (now - lastTransitionTime < TRANSITION_DEBOUNCE_MS) {
            Log.v(TAG, "Debouncing rapid transition")
            updateState(newPackage, newActivity, newUIElements)
            return
        }

        // Detect actual transition (package or activity changed)
        val isActivityChanged = previousActivity != null && previousActivity != newActivity
        val isSamePackage = previousPackage == newPackage

        if (isActivityChanged && isSamePackage) {
            // Only learn intra-app transitions (same package, different activity)
            val transitionTimeMs = now - (pendingActionTime.takeIf { it > 0 } ?: now)

            // Check if pending action is still valid (not expired)
            val action = if (pendingAction != null &&
                            (now - pendingActionTime) < ACTION_EXPIRE_MS) {
                pendingAction
            } else {
                null
            }

            publishTransition(
                beforePackage = previousPackage!!,
                beforeActivity = previousActivity!!,
                beforeUI = previousUIElements,
                afterActivity = newActivity,
                afterUI = newUIElements,
                action = action,
                transitionTimeMs = transitionTimeMs.toInt()
            )

            lastTransitionTime = now
            pendingAction = null
            pendingActionTime = 0
        }

        // Update state
        updateState(newPackage, newActivity, newUIElements)
    }

    /**
     * Called when an action is performed (tap, swipe, etc.)
     * This captures the action before the resulting screen transition.
     */
    fun onActionPerformed(action: TransitionAction) {
        if (!shouldLearn()) return

        pendingAction = action
        pendingActionTime = System.currentTimeMillis()
        Log.d(TAG, "Captured pending action: ${action.actionType}")
    }

    /**
     * Set flow execution state (for EXECUTION_ONLY mode)
     */
    fun setFlowExecuting(executing: Boolean) {
        _isFlowExecuting.value = executing
        Log.d(TAG, "Flow executing: $executing")
    }

    /**
     * Reset learning state (e.g., when switching apps)
     */
    fun reset() {
        previousPackage = null
        previousActivity = null
        previousUIElements = emptyList()
        pendingAction = null
        pendingActionTime = 0
        Log.d(TAG, "Learning state reset")
    }

    /**
     * Get learning statistics
     */
    fun getStats(): LearningStats {
        return LearningStats(
            transitionsLearned = transitionsLearned,
            transitionsSkipped = transitionsSkipped,
            isEnabled = isEnabled,
            mode = securePrefs.navigationLearningMode
        )
    }

    // =========================================================================
    // Private Methods
    // =========================================================================

    private fun updateState(
        newPackage: String,
        newActivity: String,
        newUIElements: List<UIElement>
    ) {
        previousPackage = newPackage
        previousActivity = newActivity
        // Limit UI elements to reduce memory usage
        previousUIElements = newUIElements.take(MAX_UI_ELEMENTS)
    }

    private fun publishTransition(
        beforePackage: String,
        beforeActivity: String,
        beforeUI: List<UIElement>,
        afterActivity: String,
        afterUI: List<UIElement>,
        action: TransitionAction?,
        transitionTimeMs: Int
    ) {
        // Phase 2 Stability: Move ALL heavy work off main thread
        // - JSON serialization is CPU-intensive -> Dispatchers.Default
        // - Database I/O -> Dispatchers.IO (Room handles this internally)
        // - MQTT publish -> Already async internally

        // Capture immutable copies for thread safety
        val beforeUICopy = beforeUI.toList()
        val afterUICopy = afterUI.toList()

        scope.launch(Dispatchers.Default) {
            try {
                // 1. Build JSON payload (CPU-intensive work on Default dispatcher)
                val payload = buildTransitionPayload(
                    beforePackage = beforePackage,
                    beforeActivity = beforeActivity,
                    beforeUI = beforeUICopy,
                    afterPackage = beforePackage,  // Same package for intra-app transitions
                    afterActivity = afterActivity,
                    afterUI = afterUICopy,
                    action = action,
                    transitionTimeMs = transitionTimeMs
                )

                // 2. Publish or queue (Phase 1 + Phase 2 combined)
                if (transitionDao != null) {
                    // Check MQTT connection state
                    val mqttConnected = mqttManager.connectionState.value ==
                        MqttManager.ConnectionState.CONNECTED

                    if (mqttConnected) {
                        try {
                            // Attempt immediate publish
                            mqttManager.publishNavigationTransition(payload)
                            transitionsLearned++
                            Log.i(TAG, "Published transition immediately: $beforeActivity -> $afterActivity")
                        } catch (e: Exception) {
                            // Publish failed - queue to database
                            queueTransitionToDb(payload, beforeActivity, afterActivity, e.message)
                        }
                    } else {
                        // MQTT not connected - queue to database
                        queueTransitionToDb(payload, beforeActivity, afterActivity, null)
                    }
                } else {
                    // No DAO available - fall back to direct MQTT (original behavior)
                    mqttManager.publishNavigationTransition(payload)
                    transitionsLearned++
                    Log.i(TAG, "Published transition (no DB): $beforeActivity -> $afterActivity " +
                               "(action: ${action?.actionType ?: "unknown"})")
                }

            } catch (e: Exception) {
                transitionsSkipped++
                Log.e(TAG, "Failed to process transition", e)
            }
        }
    }

    /**
     * Queue a transition to the database for later sync.
     * Uses Dispatchers.IO for database operations.
     */
    private suspend fun queueTransitionToDb(
        payload: String,
        beforeActivity: String,
        afterActivity: String,
        errorReason: String?
    ) {
        try {
            withContext(Dispatchers.IO) {
                val transition = PendingTransition(payload = payload)
                val id = transitionDao?.insert(transition) ?: -1
                transitionsLearned++
                if (errorReason != null) {
                    Log.w(TAG, "MQTT failed ($errorReason), queued to DB (id=$id): $beforeActivity -> $afterActivity")
                } else {
                    Log.i(TAG, "Queued transition to DB (id=$id): $beforeActivity -> $afterActivity")
                }
            }
        } catch (dbError: Exception) {
            transitionsSkipped++
            Log.e(TAG, "Failed to save transition to DB", dbError)
        }
    }

    /**
     * Flush pending transitions from database to MQTT.
     *
     * Phase 1 Refactor: Call this when MQTT connection is established
     * to deliver any queued transitions.
     */
    fun flushPendingTransitions() {
        if (transitionDao == null) {
            Log.d(TAG, "No DAO available, skipping flush")
            return
        }

        scope.launch {
            try {
                val cutoffTime = System.currentTimeMillis() - RETRY_BACKOFF_MS
                val pending = transitionDao.getRetryableTransitions(cutoffTime, limit = 20)

                if (pending.isEmpty()) {
                    Log.d(TAG, "No pending transitions to flush")
                    return@launch
                }

                Log.i(TAG, "Flushing ${pending.size} pending transitions")

                val mqttConnected = mqttManager.connectionState.value ==
                    MqttManager.ConnectionState.CONNECTED

                if (!mqttConnected) {
                    Log.w(TAG, "MQTT not connected, skipping flush")
                    return@launch
                }

                val delivered = mutableListOf<PendingTransition>()
                val failed = mutableListOf<PendingTransition>()

                for (transition in pending) {
                    try {
                        mqttManager.publishNavigationTransition(transition.payload)
                        delivered.add(transition)
                        Log.d(TAG, "Delivered pending transition id=${transition.id}")
                    } catch (e: Exception) {
                        // Update retry count
                        val updated = transition.copy(
                            retryCount = transition.retryCount + 1,
                            lastRetryTime = System.currentTimeMillis()
                        )
                        failed.add(updated)
                        Log.w(TAG, "Failed to deliver transition id=${transition.id}: ${e.message}")
                    }
                }

                // Remove delivered transitions
                if (delivered.isNotEmpty()) {
                    transitionDao.deleteAll(delivered)
                    Log.i(TAG, "Removed ${delivered.size} delivered transitions")
                }

                // Update failed transitions
                for (transition in failed) {
                    if (transition.retryCount >= MAX_RETRY_COUNT) {
                        transitionDao.delete(transition)
                        Log.w(TAG, "Dropped transition id=${transition.id} after max retries")
                    } else {
                        transitionDao.update(transition)
                    }
                }

                // Clean up old expired transitions
                transitionDao.deleteExpiredTransitions(MAX_RETRY_COUNT)

            } catch (e: Exception) {
                Log.e(TAG, "Error flushing pending transitions", e)
            }
        }
    }

    /**
     * Get count of pending transitions in database.
     */
    suspend fun getPendingCount(): Int {
        return transitionDao?.getCount() ?: 0
    }

    /**
     * Cancel coroutine scope when service is destroyed.
     */
    fun destroy() {
        scope.cancel()
        Log.d(TAG, "NavigationLearner destroyed")
    }

    private fun buildTransitionPayload(
        beforePackage: String,
        beforeActivity: String,
        beforeUI: List<UIElement>,
        afterPackage: String,
        afterActivity: String,
        afterUI: List<UIElement>,
        action: TransitionAction?,
        transitionTimeMs: Int
    ): String {
        val beforeUIJson = buildUIElementsJson(beforeUI)
        val afterUIJson = buildUIElementsJson(afterUI)
        val actionJson = action?.toJson() ?: "{}"

        return buildString {
            append("{")
            append("\"before_package\":\"${escapeJson(beforePackage)}\",")
            append("\"before_activity\":\"${escapeJson(beforeActivity)}\",")
            append("\"before_ui_elements\":$beforeUIJson,")
            append("\"after_package\":\"${escapeJson(afterPackage)}\",")
            append("\"after_activity\":\"${escapeJson(afterActivity)}\",")
            append("\"after_ui_elements\":$afterUIJson,")
            append("\"action\":$actionJson,")
            append("\"transition_time_ms\":$transitionTimeMs,")
            append("\"timestamp\":${System.currentTimeMillis()}")
            append("}")
        }
    }

    private fun buildUIElementsJson(elements: List<UIElement>): String {
        if (elements.isEmpty()) return "[]"

        // Only include elements with useful identifying information
        val filteredElements = elements
            .filter { !it.isPassword && !it.isSensitive }
            .take(MAX_UI_ELEMENTS)

        return "[" + filteredElements.joinToString(",") { it.toJson() } + "]"
    }

    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * Navigation learning mode
 */
enum class NavigationLearningMode {
    PASSIVE,          // Learn while user interacts with apps normally
    EXECUTION_ONLY    // Only learn during flow execution
}

/**
 * Learning statistics
 */
data class LearningStats(
    val transitionsLearned: Int,
    val transitionsSkipped: Int,
    val isEnabled: Boolean,
    val mode: NavigationLearningMode
)

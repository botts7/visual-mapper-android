package com.visualmapper.companion.explorer

import android.content.Context
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Human-in-the-Loop Manager
 *
 * Centralizes all human-in-the-loop functionality:
 * - Intent visualization (show target before action)
 * - Veto mechanism (user can cancel pending actions)
 * - Yield protocol (pause when user takes control)
 * - Re-sync (resume after user interaction)
 * - Imitation learning (record what user does via hit-testing)
 *
 * This module is extracted from AppExplorerService to maintain modularity.
 */
class HumanInLoopManager(
    private val context: Context,
    private val qLearning: ExplorationQLearning?,
    private val diagnostics: SelfDiagnostics
) : UserInteractionDetector.YieldListener {

    /**
     * Result of a hit-test operation
     */
    data class HitTestResult(
        val element: ClickableElement?,
        val screenHash: String?,
        val actionKey: String?,
        val confidence: Float  // How confident we are this is the right element (0.0-1.0)
    )

    companion object {
        private const val TAG = "HumanInLoopManager"
    }

    // Components
    private val intentOverlay = IntentVisualizationOverlay(context)
    private val userInteractionDetector = UserInteractionDetector()

    // State
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled

    private val _isYielding = MutableStateFlow(false)
    val isYielding: StateFlow<Boolean> = _isYielding

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Callbacks for AppExplorerService
    var onYieldStarted: ((Rect, String?) -> Unit)? = null
    var onYieldEnded: (() -> Unit)? = null

    // Current screen for hit-testing (set by AppExplorerService)
    private var currentScreen: ExploredScreen? = null
    private var currentScreenHash: String? = null

    // Track user actions for imitation learning
    private val userActionHistory = mutableListOf<UserAction>()
    private val maxActionHistory = 50

    init {
        userInteractionDetector.setYieldListener(this)
    }

    /**
     * Update the current screen for hit-testing.
     * Should be called by AppExplorerService when screen changes.
     */
    fun setCurrentScreen(screen: ExploredScreen?) {
        currentScreen = screen
        currentScreenHash = screen?.let { qLearning?.computeScreenHash(it) }
    }

    /**
     * Enable or disable human-in-the-loop features
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        intentOverlay.setEnabled(enabled)
        if (!enabled) {
            userInteractionDetector.reset()
        }
        Log.i(TAG, "Human-in-the-loop ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Show intent visualization and wait for possible veto.
     *
     * @param bounds Element bounds to highlight
     * @param confidence Q-value confidence (0.0 to 1.0+)
     * @param elementDescription Description for logging
     * @param vetoWindowMs Time to wait for veto
     * @return true if user vetoed, false if action approved
     */
    suspend fun showIntentAndWaitForVeto(
        bounds: Rect,
        confidence: Float,
        elementDescription: String? = null,
        vetoWindowMs: Long = IntentVisualizationOverlay.DEFAULT_VETO_WINDOW_MS
    ): Boolean {
        if (!_isEnabled.value) return false

        return intentOverlay.showIntentAndWaitForVeto(
            bounds = bounds,
            confidence = confidence,
            elementDescription = elementDescription,
            vetoWindowMs = vetoWindowMs
        )
    }

    /**
     * Register a bot tap intent before performing the gesture.
     * This allows distinguishing bot clicks from user clicks.
     */
    fun registerBotTapIntent(x: Int, y: Int, elementId: String? = null) {
        if (!_isEnabled.value) return
        userInteractionDetector.registerBotTapIntent(x, y, elementId)
    }

    /**
     * Clear bot tap intent after gesture completes.
     */
    fun clearBotTapIntent() {
        userInteractionDetector.clearBotTapIntent()
    }

    /**
     * Record a veto event for Q-learning.
     * Applies negative human feedback signal.
     */
    fun recordVeto(screenHash: String, actionKey: String) {
        Log.i(TAG, "Recording veto: $actionKey")

        // Apply immediate penalty
        qLearning?.updateQ(screenHash, actionKey, -1.0f, screenHash)

        // Record human feedback signal H(s,a) = -1
        qLearning?.recordHumanFeedback(screenHash, actionKey, -1)

        // Track in diagnostics
        scope.launch {
            diagnostics.onExplorationEvent(
                SelfDiagnostics.DiagnosticEvent.ACTION_VETOED,
                "User vetoed action: $actionKey",
                mapOf("screen_hash" to screenHash, "action" to actionKey)
            )
        }
    }

    /**
     * Record an imitation event for Q-learning.
     * Applies positive human feedback signal when user demonstrates an action.
     */
    fun recordImitation(screenHash: String, actionKey: String, element: ClickableElement?) {
        Log.i(TAG, "Recording imitation: $actionKey")

        // Apply positive reward for user-demonstrated action
        qLearning?.updateQ(screenHash, actionKey, 1.0f, null)

        // Record human feedback signal H(s,a) = +1
        qLearning?.recordHumanFeedback(screenHash, actionKey, +1)

        // Track in diagnostics
        scope.launch {
            diagnostics.onExplorationEvent(
                SelfDiagnostics.DiagnosticEvent.USER_TEACHING,
                "User demonstrated action: $actionKey",
                mapOf(
                    "screen_hash" to screenHash,
                    "action" to actionKey,
                    "element_text" to (element?.text?.take(20) ?: "unknown")
                )
            )
        }
    }

    /**
     * Check if we're currently yielding to user control.
     */
    fun isCurrentlyYielding(): Boolean = _isYielding.value

    /**
     * Hide the intent overlay (e.g., when yielding).
     */
    fun hideIntentOverlay() {
        intentOverlay.hide()
    }

    /**
     * Request veto of current pending action.
     * Called when user presses Stop button.
     */
    fun requestVeto() {
        intentOverlay.veto()
    }

    /**
     * Check if there's a pending action that can be vetoed.
     */
    fun hasPendingAction(): Boolean = intentOverlay.hasPendingAction()

    /**
     * Get user interaction statistics.
     */
    fun getInteractionStats(): UserInteractionDetector.UserInteractionStats {
        return userInteractionDetector.getStatistics()
    }

    // =========================================================================
    // Phase C: Hit-Test for Imitation Learning
    // =========================================================================

    /**
     * Perform hit-test to find which element the user clicked.
     * Returns the best matching element and confidence score.
     *
     * @param clickBounds The bounds of the clicked area
     * @return HitTestResult with matched element and confidence
     */
    fun performHitTest(clickBounds: Rect): HitTestResult {
        val screen = currentScreen ?: return HitTestResult(null, null, null, 0f)
        val screenHash = currentScreenHash ?: return HitTestResult(null, null, null, 0f)

        val clickCenterX = clickBounds.centerX()
        val clickCenterY = clickBounds.centerY()

        // Find elements that contain or overlap with the click point
        val candidates = screen.clickableElements.mapNotNull { element ->
            val elementRect = Rect(
                element.bounds.x,
                element.bounds.y,
                element.bounds.x + element.bounds.width,
                element.bounds.y + element.bounds.height
            )

            // Check if click is inside element bounds
            if (elementRect.contains(clickCenterX, clickCenterY)) {
                // Calculate confidence based on how centered the click is
                val elementCenterX = elementRect.centerX()
                val elementCenterY = elementRect.centerY()
                val distance = kotlin.math.sqrt(
                    ((clickCenterX - elementCenterX) * (clickCenterX - elementCenterX) +
                     (clickCenterY - elementCenterY) * (clickCenterY - elementCenterY)).toDouble()
                ).toFloat()

                // Smaller distance = higher confidence (normalized by element size)
                val maxDist = kotlin.math.max(elementRect.width(), elementRect.height()) / 2f
                val confidence = if (maxDist > 0) 1f - (distance / maxDist).coerceIn(0f, 1f) else 1f

                element to confidence
            } else {
                null
            }
        }

        // Return the element with highest confidence
        val bestMatch = candidates.maxByOrNull { it.second }

        return if (bestMatch != null) {
            val actionKey = qLearning?.getActionKey(bestMatch.first)
            HitTestResult(
                element = bestMatch.first,
                screenHash = screenHash,
                actionKey = actionKey,
                confidence = bestMatch.second
            )
        } else {
            HitTestResult(null, screenHash, null, 0f)
        }
    }

    /**
     * Process a user click for imitation learning.
     * Performs hit-test and records positive feedback if element is found.
     *
     * @param clickBounds The bounds of the clicked area
     * @param clickInfo Optional description of the click
     * @return true if an element was matched and recorded
     */
    fun processUserClickForImitation(clickBounds: Rect, clickInfo: String?): Boolean {
        if (!_isEnabled.value) return false

        val hitResult = performHitTest(clickBounds)

        if (hitResult.element != null && hitResult.screenHash != null && hitResult.actionKey != null) {
            // Only record if confidence is above threshold
            if (hitResult.confidence >= 0.5f) {
                Log.i(TAG, "Hit-test matched: ${hitResult.element.elementId} (confidence=${hitResult.confidence})")

                // Record imitation (positive feedback)
                recordImitation(hitResult.screenHash, hitResult.actionKey, hitResult.element)

                // Track in action history
                trackUserAction(UserAction(
                    timestamp = System.currentTimeMillis(),
                    screenHash = hitResult.screenHash,
                    actionKey = hitResult.actionKey,
                    elementId = hitResult.element.elementId,
                    elementText = hitResult.element.text,
                    confidence = hitResult.confidence
                ))

                return true
            } else {
                Log.d(TAG, "Hit-test match too low confidence: ${hitResult.confidence}")
            }
        } else {
            Log.d(TAG, "Hit-test: No element matched for click at $clickBounds")
        }

        return false
    }

    /**
     * Track a user action in history for analysis.
     */
    private fun trackUserAction(action: UserAction) {
        userActionHistory.add(action)
        if (userActionHistory.size > maxActionHistory) {
            userActionHistory.removeAt(0)
        }
    }

    /**
     * Get recent user action history.
     */
    fun getUserActionHistory(): List<UserAction> = userActionHistory.toList()

    /**
     * Clear user action history.
     */
    fun clearUserActionHistory() {
        userActionHistory.clear()
    }

    /**
     * Data class for tracking user actions
     */
    data class UserAction(
        val timestamp: Long,
        val screenHash: String,
        val actionKey: String,
        val elementId: String,
        val elementText: String?,
        val confidence: Float
    )

    /**
     * Reset state (e.g., when exploration stops).
     */
    fun reset() {
        _isYielding.value = false
        userInteractionDetector.reset()
        intentOverlay.hide()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        reset()
        userInteractionDetector.destroy()
    }

    // =========================================================================
    // UserInteractionDetector.YieldListener Implementation
    // =========================================================================

    override fun onUserTakeover(clickBounds: Rect, clickInfo: String?) {
        if (!_isEnabled.value) return
        if (_isYielding.value) return  // Already yielding

        Log.i(TAG, "=== USER TAKEOVER DETECTED ===")
        Log.i(TAG, "User clicked: $clickInfo at $clickBounds")

        // If there's a pending action, VETO IT (user tap = cancel current action)
        if (intentOverlay.hasPendingAction()) {
            Log.i(TAG, "User tap during pending action - triggering VETO")
            intentOverlay.veto()
            // Don't set yielding - the veto handles it and exploration will continue
            return
        }

        _isYielding.value = true

        // Hide any pending intent overlay
        intentOverlay.hide()

        // === PHASE C: Imitation Learning via Hit-Test ===
        // Try to identify which element the user clicked and record positive feedback
        val imitationRecorded = processUserClickForImitation(clickBounds, clickInfo)
        if (imitationRecorded) {
            Log.i(TAG, "Imitation learning: Recorded positive feedback for user action")
        }

        // Notify callback
        onYieldStarted?.invoke(clickBounds, clickInfo)

        // Track in diagnostics
        scope.launch {
            diagnostics.onExplorationEvent(
                SelfDiagnostics.DiagnosticEvent.USER_INTERVENTION,
                "User took control during exploration",
                mapOf(
                    "click_info" to (clickInfo ?: "unknown"),
                    "click_bounds" to clickBounds.toString(),
                    "imitation_recorded" to imitationRecorded.toString()
                )
            )
        }
    }

    override fun onUserInactivityTimeout() {
        if (!_isEnabled.value) return
        if (!_isYielding.value) return  // Not currently yielding

        Log.i(TAG, "=== USER INACTIVITY TIMEOUT ===")

        _isYielding.value = false

        // Notify callback for re-sync
        onYieldEnded?.invoke()
    }

    /**
     * Get the user interaction detector for external access
     * (e.g., AccessibilityService needs to report clicks)
     */
    fun getUserInteractionDetector(): UserInteractionDetector = userInteractionDetector
}

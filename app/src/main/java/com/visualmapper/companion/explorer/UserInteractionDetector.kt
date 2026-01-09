package com.visualmapper.companion.explorer

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User Interaction Detector for Human-in-the-Loop
 *
 * Detects when the user takes control during exploration and triggers yield protocol.
 *
 * Strategy:
 * 1. Bot registers its tap intentions before performing them
 * 2. When a click event is detected, check if it matches a pending bot tap
 * 3. If no match, it's a user interaction → trigger yield
 * 4. After user inactivity timeout, signal ready to re-sync
 */
class UserInteractionDetector {

    companion object {
        private const val TAG = "UserInteractionDetector"

        // Time window to match bot tap with accessibility click event
        private const val BOT_TAP_WINDOW_MS = 800L

        // How close a click must be to bot's intended tap location (pixels)
        private const val TAP_MATCH_THRESHOLD_PX = 100

        // How long to wait after last user interaction before re-sync
        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 3000L
    }

    // Listener interface for yield/resume notifications
    interface YieldListener {
        fun onUserTakeover(clickBounds: Rect, clickInfo: String?)
        fun onUserInactivityTimeout()
    }

    private var listener: YieldListener? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Pending bot tap (registered before performing gesture)
    private var pendingBotTap: BotTapIntent? = null
    private val pendingBotTapLock = Object()

    // User interaction state
    private val _isUserInControl = MutableStateFlow(false)
    val isUserInControl: StateFlow<Boolean> = _isUserInControl

    private var inactivityJob: Job? = null
    private var inactivityTimeoutMs = DEFAULT_INACTIVITY_TIMEOUT_MS

    // Track last user interaction for statistics
    private var lastUserInteractionTime: Long = 0
    private var userInteractionCount: Int = 0

    /**
     * Register a listener for yield/resume events
     */
    fun setYieldListener(listener: YieldListener?) {
        this.listener = listener
    }

    /**
     * Set inactivity timeout (how long to wait after user stops interacting)
     */
    fun setInactivityTimeout(timeoutMs: Long) {
        inactivityTimeoutMs = timeoutMs
    }

    /**
     * Called by bot BEFORE performing a tap gesture.
     * Registers the intended tap location so we can match it with accessibility events.
     */
    fun registerBotTapIntent(x: Int, y: Int, elementId: String? = null) {
        synchronized(pendingBotTapLock) {
            pendingBotTap = BotTapIntent(
                x = x,
                y = y,
                elementId = elementId,
                timestamp = System.currentTimeMillis()
            )
            Log.d(TAG, "Bot tap registered: ($x, $y) for $elementId")
        }
    }

    /**
     * Called by bot AFTER tap gesture completes.
     * Clears the pending tap after a delay to allow for click event matching.
     */
    fun clearBotTapIntent() {
        scope.launch {
            delay(BOT_TAP_WINDOW_MS)
            synchronized(pendingBotTapLock) {
                pendingBotTap = null
            }
        }
    }

    /**
     * Called when accessibility service detects a click event.
     * Determines if it's a bot click or user click.
     *
     * @param bounds The bounds of the clicked element
     * @param elementInfo Optional description of clicked element
     * @return true if this was detected as a user click
     */
    fun onClickDetected(bounds: Rect, elementInfo: String? = null): Boolean {
        val clickCenterX = bounds.centerX()
        val clickCenterY = bounds.centerY()
        val now = System.currentTimeMillis()

        synchronized(pendingBotTapLock) {
            val botTap = pendingBotTap

            // Check if this click matches a pending bot tap
            if (botTap != null) {
                val timeDelta = now - botTap.timestamp
                val distance = kotlin.math.sqrt(
                    ((clickCenterX - botTap.x) * (clickCenterX - botTap.x) +
                     (clickCenterY - botTap.y) * (clickCenterY - botTap.y)).toDouble()
                ).toInt()

                if (timeDelta < BOT_TAP_WINDOW_MS && distance < TAP_MATCH_THRESHOLD_PX) {
                    // This is a bot-initiated click
                    Log.d(TAG, "Click matched bot tap (distance=$distance, time=$timeDelta ms)")
                    pendingBotTap = null
                    return false
                }
            }
        }

        // This is a USER click!
        Log.i(TAG, "USER CLICK DETECTED at ($clickCenterX, $clickCenterY): $elementInfo")
        handleUserInteraction(bounds, elementInfo)
        return true
    }

    /**
     * Handle detected user interaction - trigger yield protocol
     */
    private fun handleUserInteraction(bounds: Rect, elementInfo: String?) {
        lastUserInteractionTime = System.currentTimeMillis()
        userInteractionCount++

        val wasAlreadyInControl = _isUserInControl.value
        _isUserInControl.value = true

        // Cancel any existing inactivity timer
        inactivityJob?.cancel()

        // Notify listener of user takeover (only on first interaction in sequence)
        if (!wasAlreadyInControl) {
            Log.i(TAG, "User takeover - exploration should yield")
            listener?.onUserTakeover(bounds, elementInfo)
        }

        // Start inactivity timer
        inactivityJob = scope.launch {
            delay(inactivityTimeoutMs)
            Log.i(TAG, "User inactivity timeout - ready to re-sync")
            _isUserInControl.value = false
            listener?.onUserInactivityTimeout()
        }
    }

    /**
     * Called when user manually signals they're done (e.g., pressing a "Resume" button)
     */
    fun userFinishedInteracting() {
        inactivityJob?.cancel()
        _isUserInControl.value = false
        Log.i(TAG, "User manually signaled completion")
        listener?.onUserInactivityTimeout()
    }

    /**
     * Force reset state (e.g., when exploration stops)
     */
    fun reset() {
        inactivityJob?.cancel()
        _isUserInControl.value = false
        synchronized(pendingBotTapLock) {
            pendingBotTap = null
        }
        Log.d(TAG, "State reset")
    }

    /**
     * Get statistics about user interactions
     */
    fun getStatistics(): UserInteractionStats {
        return UserInteractionStats(
            totalUserInteractions = userInteractionCount,
            lastInteractionTime = lastUserInteractionTime,
            isCurrentlyInControl = _isUserInControl.value
        )
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        inactivityJob?.cancel()
        scope.cancel()
        listener = null
    }

    /**
     * Represents a pending bot tap that we're waiting to match with click events
     */
    private data class BotTapIntent(
        val x: Int,
        val y: Int,
        val elementId: String?,
        val timestamp: Long
    )

    /**
     * Statistics about user interactions during exploration
     */
    data class UserInteractionStats(
        val totalUserInteractions: Int,
        val lastInteractionTime: Long,
        val isCurrentlyInControl: Boolean
    )
}

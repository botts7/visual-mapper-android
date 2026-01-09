package com.visualmapper.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import com.visualmapper.companion.navigation.TransitionAction
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gesture Dispatcher
 *
 * Dispatches gestures via the Accessibility Service API.
 * Replaces ADB input commands with native gesture dispatch.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "GestureDispatcher"

        // Default durations
        const val TAP_DURATION_MS = 100L
        const val LONG_PRESS_DURATION_MS = 1000L
        const val SWIPE_DURATION_MS = 300L
    }

    // =========================================================================
    // Tap Gestures
    // =========================================================================

    /**
     * Perform a tap at coordinates
     */
    suspend fun tap(x: Float, y: Float, durationMs: Long = TAP_DURATION_MS): Boolean {
        Log.d(TAG, "Tap at ($x, $y)")

        // Notify navigation learner before performing the action
        notifyNavigationAction(TransitionAction.tap(x.toInt(), y.toInt()))

        val path = Path().apply {
            moveTo(x, y)
        }

        return dispatchGesture(path, 0, durationMs)
    }

    /**
     * Perform a tap at integer coordinates
     */
    suspend fun tap(x: Int, y: Int): Boolean = tap(x.toFloat(), y.toFloat())

    /**
     * Perform a long press at coordinates
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = LONG_PRESS_DURATION_MS): Boolean {
        Log.d(TAG, "Long press at ($x, $y) for ${durationMs}ms")

        val path = Path().apply {
            moveTo(x, y)
        }

        return dispatchGesture(path, 0, durationMs)
    }

    /**
     * Perform a double tap at coordinates
     */
    suspend fun doubleTap(x: Float, y: Float): Boolean {
        Log.d(TAG, "Double tap at ($x, $y)")

        // First tap
        if (!tap(x, y)) return false

        // Wait 100ms between taps
        kotlinx.coroutines.delay(100)

        // Second tap
        return tap(x, y)
    }

    // =========================================================================
    // Swipe Gestures
    // =========================================================================

    /**
     * Perform a swipe from start to end coordinates
     */
    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ): Boolean {
        Log.d(TAG, "Swipe from ($startX, $startY) to ($endX, $endY) in ${durationMs}ms")

        // === BOUNDS VALIDATION - Prevent "Path bounds must not be negative" crash ===
        val validStartX = startX.coerceIn(0f, 10000f)
        val validStartY = startY.coerceIn(0f, 10000f)
        val validEndX = endX.coerceIn(0f, 10000f)
        val validEndY = endY.coerceIn(0f, 10000f)

        // Check for invalid/negative coordinates
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0) {
            Log.w(TAG, "Swipe: Invalid negative coordinates detected, clamping to valid range")
        }

        // Check if swipe would be too small (no movement)
        val distanceX = kotlin.math.abs(validEndX - validStartX)
        val distanceY = kotlin.math.abs(validEndY - validStartY)
        if (distanceX < 5 && distanceY < 5) {
            Log.w(TAG, "Swipe: Distance too small ($distanceX, $distanceY), skipping")
            return false
        }

        // Determine swipe direction for navigation learning
        val direction = when {
            validEndY < validStartY - 100 -> "up"
            validEndY > validStartY + 100 -> "down"
            validEndX < validStartX - 100 -> "left"
            validEndX > validStartX + 100 -> "right"
            else -> null
        }

        // Notify navigation learner before performing the action
        notifyNavigationAction(TransitionAction.swipe(
            validStartX.toInt(), validStartY.toInt(),
            validEndX.toInt(), validEndY.toInt(),
            direction
        ))

        val path = Path().apply {
            moveTo(validStartX, validStartY)
            lineTo(validEndX, validEndY)
        }

        return dispatchGesture(path, 0, durationMs)
    }

    /**
     * Perform a swipe with integer coordinates
     */
    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = SWIPE_DURATION_MS
    ): Boolean = swipe(
        startX.toFloat(),
        startY.toFloat(),
        endX.toFloat(),
        endY.toFloat(),
        durationMs
    )

    // =========================================================================
    // Scroll Gestures
    // =========================================================================

    /**
     * Scroll down on screen
     */
    suspend fun scrollDown(
        centerX: Float = 540f,
        startY: Float = 1500f,
        endY: Float = 500f,
        durationMs: Long = 300L
    ): Boolean {
        Log.d(TAG, "Scroll down")
        return swipe(centerX, startY, centerX, endY, durationMs)
    }

    /**
     * Scroll up on screen
     */
    suspend fun scrollUp(
        centerX: Float = 540f,
        startY: Float = 500f,
        endY: Float = 1500f,
        durationMs: Long = 300L
    ): Boolean {
        Log.d(TAG, "Scroll up")
        return swipe(centerX, startY, centerX, endY, durationMs)
    }

    /**
     * Scroll left (swipe right to left)
     */
    suspend fun scrollLeft(
        centerY: Float = 1000f,
        startX: Float = 900f,
        endX: Float = 100f,
        durationMs: Long = 300L
    ): Boolean {
        Log.d(TAG, "Scroll left")
        return swipe(startX, centerY, endX, centerY, durationMs)
    }

    /**
     * Scroll right (swipe left to right)
     */
    suspend fun scrollRight(
        centerY: Float = 1000f,
        startX: Float = 100f,
        endX: Float = 900f,
        durationMs: Long = 300L
    ): Boolean {
        Log.d(TAG, "Scroll right")
        return swipe(startX, centerY, endX, centerY, durationMs)
    }

    /**
     * Pull to refresh gesture (swipe down from top)
     */
    suspend fun pullToRefresh(
        centerX: Float = 540f,
        startY: Float = 300f,
        endY: Float = 1200f,
        durationMs: Long = 500L
    ): Boolean {
        Log.d(TAG, "Pull to refresh")
        return swipe(centerX, startY, centerX, endY, durationMs)
    }

    // =========================================================================
    // Complex Gestures
    // =========================================================================

    /**
     * Pinch gesture (zoom out)
     * Note: Multi-touch gestures require API 24+
     */
    suspend fun pinchIn(
        centerX: Float,
        centerY: Float,
        startDistance: Float = 400f,
        endDistance: Float = 100f,
        durationMs: Long = 500L
    ): Boolean {
        Log.d(TAG, "Pinch in at ($centerX, $centerY)")

        // Create two paths moving toward each other
        val path1 = Path().apply {
            moveTo(centerX - startDistance / 2, centerY)
            lineTo(centerX - endDistance / 2, centerY)
        }

        val path2 = Path().apply {
            moveTo(centerX + startDistance / 2, centerY)
            lineTo(centerX + endDistance / 2, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()

        return dispatchGestureDescription(gesture)
    }

    /**
     * Pinch gesture (zoom in)
     */
    suspend fun pinchOut(
        centerX: Float,
        centerY: Float,
        startDistance: Float = 100f,
        endDistance: Float = 400f,
        durationMs: Long = 500L
    ): Boolean {
        Log.d(TAG, "Pinch out at ($centerX, $centerY)")

        val path1 = Path().apply {
            moveTo(centerX - startDistance / 2, centerY)
            lineTo(centerX - endDistance / 2, centerY)
        }

        val path2 = Path().apply {
            moveTo(centerX + startDistance / 2, centerY)
            lineTo(centerX + endDistance / 2, centerY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
            .build()

        return dispatchGestureDescription(gesture)
    }

    // =========================================================================
    // Core Gesture Dispatch
    // =========================================================================

    private suspend fun dispatchGesture(
        path: Path,
        startTime: Long,
        duration: Long
    ): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, startTime, duration))
            .build()

        return dispatchGestureDescription(gesture)
    }

    private suspend fun dispatchGestureDescription(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Gesture completed")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "Gesture cancelled")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = service.dispatchGesture(gesture, callback, null)

            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch gesture")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    // =========================================================================
    // Text Input
    // =========================================================================

    /**
     * Type text using clipboard and paste.
     * This is more reliable than simulating key presses.
     */
    fun typeText(text: String): Boolean {
        return try {
            // Use clipboard to paste text
            val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)

            // Find focused node and perform paste
            val focusedNode = service.rootInActiveWindow?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null) {
                // Try to paste
                val pasteResult = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                if (pasteResult) {
                    Log.d(TAG, "Pasted text: $text")
                    return true
                }

                // If paste doesn't work, try setting text directly
                val arguments = android.os.Bundle()
                arguments.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val setTextResult = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (setTextResult) {
                    Log.d(TAG, "Set text directly: $text")
                    return true
                }
            }

            Log.w(TAG, "Could not find focused input node")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error typing text", e)
            false
        }
    }

    // =========================================================================
    // Navigation Learning
    // =========================================================================

    /**
     * Notify navigation learner of an action about to be performed.
     * This is called before the gesture is dispatched so the learner
     * knows what action caused the subsequent screen transition.
     */
    private fun notifyNavigationAction(action: TransitionAction) {
        try {
            (service as? VisualMapperAccessibilityService)?.notifyActionPerformed(action)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to notify navigation action", e)
        }
    }
}

package com.visualmapper.companion.explorer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Human-in-the-Loop: Intent Visualization Overlay
 *
 * Shows a colored bounding box around the target element BEFORE the bot acts,
 * giving the user time to veto the action.
 *
 * Colors:
 * - GREEN: High confidence action (Q-value above threshold)
 * - YELLOW: Low confidence / exploration mode
 * - RED: Veto requested (briefly shown when user cancels)
 *
 * The overlay supports a non-blocking suspension pattern:
 * 1. Show intent box around target
 * 2. Wait for configurable delay (default 500ms)
 * 3. User can veto during this window
 * 4. Returns whether action was vetoed
 */
class IntentVisualizationOverlay(private val context: Context) {

    companion object {
        private const val TAG = "IntentOverlay"

        // Confidence thresholds
        const val HIGH_CONFIDENCE_THRESHOLD = 0.5f

        // Colors
        val COLOR_HIGH_CONFIDENCE = Color.parseColor("#4CAF50")  // Green
        val COLOR_LOW_CONFIDENCE = Color.parseColor("#FFC107")   // Yellow/Amber
        val COLOR_VETOED = Color.parseColor("#F44336")           // Red

        // Timing
        const val DEFAULT_VETO_WINDOW_MS = 5000L  // 5 seconds for comfortable user reaction
        const val BORDER_WIDTH = 8f
    }

    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentView: IntentBoxView? = null
    private var isEnabled = true

    // Veto signal - completed when user requests veto
    @Volatile
    private var vetoSignal: CompletableDeferred<Boolean>? = null

    init {
        windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Enable or disable intent visualization.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            hide()
        }
    }

    /**
     * Show intent visualization and wait for veto window.
     *
     * This is a SUSPEND function that:
     * 1. Shows the intent box immediately
     * 2. Waits for vetoWindowMs or until veto() is called
     * 3. Returns true if user vetoed, false if timeout (approved)
     *
     * @param bounds The bounds of the target element
     * @param confidence The Q-value confidence (0.0 to 1.0+)
     * @param elementDescription Optional description for logging
     * @param vetoWindowMs Time to wait for user veto (default 500ms)
     * @return true if vetoed, false if approved (timeout)
     */
    suspend fun showIntentAndWaitForVeto(
        bounds: Rect,
        confidence: Float,
        elementDescription: String? = null,
        vetoWindowMs: Long = DEFAULT_VETO_WINDOW_MS
    ): Boolean {
        if (!isEnabled) {
            return false  // Not vetoed, proceed
        }

        // Create new veto signal
        val signal = CompletableDeferred<Boolean>()
        vetoSignal = signal

        // Determine color based on confidence
        val color = if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            COLOR_HIGH_CONFIDENCE
        } else {
            COLOR_LOW_CONFIDENCE
        }

        Log.d(TAG, "Showing intent: ${elementDescription ?: "element"} @ $bounds, confidence=$confidence")

        // Show the intent box on main thread
        mainHandler.post {
            try {
                showIntentBox(bounds, color)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show intent box", e)
            }
        }

        // Wait for veto or timeout
        val vetoed = withTimeoutOrNull(vetoWindowMs) {
            signal.await()
        } ?: false  // null means timeout = not vetoed

        // Hide or change color based on result
        mainHandler.post {
            if (vetoed) {
                // Briefly show red to indicate veto
                currentView?.setColor(COLOR_VETOED)
                mainHandler.postDelayed({ hide() }, 200)
            } else {
                hide()
            }
        }

        if (vetoed) {
            Log.i(TAG, "Action VETOED by user: ${elementDescription ?: "element"}")
        } else {
            Log.d(TAG, "Action approved (timeout): ${elementDescription ?: "element"}")
        }

        vetoSignal = null
        return vetoed
    }

    /**
     * Request veto of the current pending action.
     * Called when user presses the Stop/Veto button.
     */
    fun veto() {
        Log.i(TAG, "Veto requested by user")
        vetoSignal?.complete(true)
    }

    /**
     * Check if there's a pending action that can be vetoed.
     */
    fun hasPendingAction(): Boolean {
        return vetoSignal?.isActive == true
    }

    /**
     * Show the intent box without waiting (for preview purposes).
     */
    fun showPreview(bounds: Rect, confidence: Float) {
        if (!isEnabled) return

        val color = if (confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            COLOR_HIGH_CONFIDENCE
        } else {
            COLOR_LOW_CONFIDENCE
        }

        mainHandler.post {
            try {
                showIntentBox(bounds, color)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show preview", e)
            }
        }
    }

    /**
     * Hide the intent visualization.
     */
    fun hide() {
        mainHandler.post {
            try {
                currentView?.let { view ->
                    windowManager?.removeView(view)
                    Log.d(TAG, "Intent box hidden")
                }
                currentView = null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide intent box", e)
            }
        }
    }

    private fun showIntentBox(bounds: Rect, color: Int) {
        // Remove existing view if any
        currentView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                // Ignore
            }
        }

        val intentView = IntentBoxView(context.applicationContext, bounds, color)
        currentView = intentView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(intentView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add intent box view", e)
            currentView = null
        }
    }

    /**
     * Custom view that draws a colored border around the target bounds.
     */
    private class IntentBoxView(
        context: Context,
        private val targetBounds: Rect,
        initialColor: Int
    ) : View(context) {

        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
            color = initialColor
            isAntiAlias = true
        }

        private val fillPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(30, Color.red(initialColor), Color.green(initialColor), Color.blue(initialColor))
            isAntiAlias = true
        }

        fun setColor(color: Int) {
            paint.color = color
            fillPaint.color = Color.argb(30, Color.red(color), Color.green(color), Color.blue(color))
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw semi-transparent fill
            canvas.drawRect(
                targetBounds.left.toFloat(),
                targetBounds.top.toFloat(),
                targetBounds.right.toFloat(),
                targetBounds.bottom.toFloat(),
                fillPaint
            )

            // Draw border
            canvas.drawRect(
                targetBounds.left.toFloat() + BORDER_WIDTH / 2,
                targetBounds.top.toFloat() + BORDER_WIDTH / 2,
                targetBounds.right.toFloat() - BORDER_WIDTH / 2,
                targetBounds.bottom.toFloat() - BORDER_WIDTH / 2,
                paint
            )
        }
    }
}

package com.visualmapper.companion.explorer

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Flow Protection Overlay
 *
 * Shows a persistent overlay during automated flow execution that:
 * - Displays "Flow in progress" banner at top of screen
 * - Allows touch-through for the flow to interact with apps
 * - Detects user touches (not bot touches) and pauses flow
 * - Shows "Paused - user active" when user interacts
 * - Resumes after configurable inactivity timeout
 *
 * This is different from exploration mode:
 * - No intent boxes (we know the flow)
 * - No learning (just executing)
 * - Simpler pause/resume (no imitation recording)
 */
class FlowProtectionOverlay(private val context: Context) {

    companion object {
        private const val TAG = "FlowProtectionOverlay"

        // Timing
        const val DEFAULT_RESUME_DELAY_MS = 3000L  // 3 seconds after user stops touching

        // Colors
        val COLOR_RUNNING = Color.parseColor("#2196F3")     // Blue - flow running
        val COLOR_PAUSED = Color.parseColor("#FF9800")      // Orange - paused
        val COLOR_COMPLETE = Color.parseColor("#4CAF50")    // Green - complete
        val COLOR_ERROR = Color.parseColor("#F44336")       // Red - error

        // Banner height
        const val BANNER_HEIGHT_DP = 48
        const val BANNER_PADDING_DP = 12
    }

    /**
     * Overlay state
     */
    enum class OverlayState {
        HIDDEN,         // Overlay not showing
        RUNNING,        // Flow is executing
        PAUSED,         // User interacted, flow paused
        COMPLETING,     // Flow finishing up
        ERROR           // Flow encountered error
    }

    /**
     * Callback interface for overlay events
     */
    interface FlowProtectionListener {
        fun onUserInteractionDetected()
        fun onResumeAfterInactivity()
        fun onStopRequested()
    }

    // Window manager
    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Views
    private var bannerView: View? = null
    private var touchDetectorView: View? = null

    // State
    private val _state = MutableStateFlow(OverlayState.HIDDEN)
    val state: StateFlow<OverlayState> = _state.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    // Configuration
    var resumeDelayMs: Long = DEFAULT_RESUME_DELAY_MS
    var pauseOnUserTouch: Boolean = true
    var showBanner: Boolean = true

    // Listener
    var listener: FlowProtectionListener? = null

    // Bot touch tracking (to distinguish from user touches)
    private var pendingBotTouch: Pair<Int, Int>? = null
    private var lastBotTouchTime: Long = 0
    private val botTouchWindowMs = 500L  // 500ms window to match bot touch

    // Resume timer
    private var resumeRunnable: Runnable? = null

    // Flow info
    private var currentFlowName: String = ""
    private var currentStepIndex: Int = 0
    private var totalSteps: Int = 0

    init {
        windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Show the protection overlay and start monitoring.
     *
     * @param flowName Name of the flow being executed
     * @param totalSteps Total number of steps in the flow
     */
    fun show(flowName: String, totalSteps: Int) {
        if (_state.value != OverlayState.HIDDEN) {
            Log.w(TAG, "Overlay already showing")
            return
        }

        currentFlowName = flowName
        currentStepIndex = 0
        this.totalSteps = totalSteps

        mainHandler.post {
            try {
                if (showBanner) {
                    showBanner()
                }
                if (pauseOnUserTouch) {
                    showTouchDetector()
                }
                _state.value = OverlayState.RUNNING
                _isPaused.value = false
                Log.i(TAG, "Flow protection overlay shown for: $flowName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show overlay", e)
            }
        }
    }

    /**
     * Update progress during flow execution.
     */
    fun updateProgress(stepIndex: Int, stepDescription: String? = null) {
        currentStepIndex = stepIndex
        mainHandler.post {
            updateBannerUI()
        }
    }

    /**
     * Mark flow as complete.
     */
    fun markComplete() {
        _state.value = OverlayState.COMPLETING
        mainHandler.post {
            updateBannerUI()
            // Auto-hide after a moment
            mainHandler.postDelayed({ hide() }, 2000)
        }
    }

    /**
     * Mark flow as error.
     */
    fun markError(message: String? = null) {
        _state.value = OverlayState.ERROR
        mainHandler.post {
            updateBannerUI(message)
            // Auto-hide after showing error
            mainHandler.postDelayed({ hide() }, 3000)
        }
    }

    /**
     * Hide the overlay.
     */
    fun hide() {
        cancelResumeTimer()

        mainHandler.post {
            try {
                bannerView?.let { view ->
                    windowManager?.removeView(view)
                }
                bannerView = null

                touchDetectorView?.let { view ->
                    windowManager?.removeView(view)
                }
                touchDetectorView = null

                _state.value = OverlayState.HIDDEN
                _isPaused.value = false
                Log.i(TAG, "Flow protection overlay hidden")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide overlay", e)
            }
        }
    }

    /**
     * Register a bot touch that's about to happen.
     * This allows distinguishing bot touches from user touches.
     */
    fun registerBotTouch(x: Int, y: Int) {
        pendingBotTouch = Pair(x, y)
        lastBotTouchTime = System.currentTimeMillis()
    }

    /**
     * Clear bot touch registration.
     */
    fun clearBotTouch() {
        pendingBotTouch = null
    }

    /**
     * Pause flow execution (called when user touches).
     */
    fun pause() {
        if (_state.value != OverlayState.RUNNING) return

        _isPaused.value = true
        _state.value = OverlayState.PAUSED

        mainHandler.post {
            updateBannerUI()
        }

        listener?.onUserInteractionDetected()
        Log.i(TAG, "Flow paused due to user interaction")
    }

    /**
     * Resume flow execution.
     */
    fun resume() {
        if (_state.value != OverlayState.PAUSED) return

        cancelResumeTimer()

        _isPaused.value = false
        _state.value = OverlayState.RUNNING

        mainHandler.post {
            updateBannerUI()
        }

        listener?.onResumeAfterInactivity()
        Log.i(TAG, "Flow resumed")
    }

    /**
     * Check if currently paused.
     */
    fun isPaused(): Boolean = _isPaused.value

    /**
     * Check if overlay is showing.
     */
    fun isShowing(): Boolean = _state.value != OverlayState.HIDDEN

    // =========================================================================
    // Private Methods - Banner
    // =========================================================================

    private fun showBanner() {
        val density = context.resources.displayMetrics.density
        val bannerHeight = (BANNER_HEIGHT_DP * density).toInt()
        val padding = (BANNER_PADDING_DP * density).toInt()

        // Create banner layout
        val bannerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(COLOR_RUNNING)
            setPadding(padding, padding / 2, padding, padding / 2)
            gravity = Gravity.CENTER_VERTICAL
        }

        // Icon
        val icon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_media_play)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                (24 * density).toInt(),
                (24 * density).toInt()
            ).apply {
                marginEnd = padding / 2
            }
        }
        bannerLayout.addView(icon)

        // Text container
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Title
        val titleView = TextView(context).apply {
            text = "Flow: $currentFlowName"
            setTextColor(Color.WHITE)
            textSize = 14f
            tag = "title"
        }
        textContainer.addView(titleView)

        // Status
        val statusView = TextView(context).apply {
            text = "Step ${currentStepIndex + 1} of $totalSteps"
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 12f
            tag = "status"
        }
        textContainer.addView(statusView)

        bannerLayout.addView(textContainer)

        // Progress indicator
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            layoutParams = LinearLayout.LayoutParams(
                (20 * density).toInt(),
                (20 * density).toInt()
            ).apply {
                marginStart = padding / 2
                marginEnd = padding / 2
            }
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            tag = "progress"
        }
        bannerLayout.addView(progress)

        // Stop button
        val stopButton = TextView(context).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(padding, padding / 2, padding, padding / 2)
            setBackgroundResource(android.R.drawable.btn_default_small)
            background.setTint(Color.argb(80, 255, 255, 255))
            setOnClickListener {
                listener?.onStopRequested()
            }
        }
        bannerLayout.addView(stopButton)

        bannerView = bannerLayout

        // Window params
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            bannerHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        try {
            windowManager?.addView(bannerLayout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add banner view", e)
        }
    }

    private fun updateBannerUI(errorMessage: String? = null) {
        val banner = bannerView as? LinearLayout ?: return

        val color = when (_state.value) {
            OverlayState.RUNNING -> COLOR_RUNNING
            OverlayState.PAUSED -> COLOR_PAUSED
            OverlayState.COMPLETING -> COLOR_COMPLETE
            OverlayState.ERROR -> COLOR_ERROR
            else -> COLOR_RUNNING
        }
        banner.setBackgroundColor(color)

        // Update status text
        val statusView = banner.findViewWithTag<TextView>("status")
        statusView?.text = when (_state.value) {
            OverlayState.RUNNING -> "Step ${currentStepIndex + 1} of $totalSteps"
            OverlayState.PAUSED -> "Paused - waiting for user..."
            OverlayState.COMPLETING -> "Complete!"
            OverlayState.ERROR -> errorMessage ?: "Error occurred"
            else -> ""
        }

        // Show/hide progress
        val progress = banner.findViewWithTag<ProgressBar>("progress")
        progress?.visibility = when (_state.value) {
            OverlayState.RUNNING -> View.VISIBLE
            else -> View.GONE
        }
    }

    // =========================================================================
    // Private Methods - Touch Detection
    // =========================================================================

    private fun showTouchDetector() {
        // Create a transparent full-screen view that detects touches
        // but passes them through
        val detectorView = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    handleTouch(event.x.toInt(), event.y.toInt())
                }
                // Return false to pass touch through
                return false
            }
        }

        touchDetectorView = detectorView

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            // Key flags: not focusable, watch outside touch, let touches through
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(detectorView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add touch detector view", e)
        }
    }

    private fun handleTouch(x: Int, y: Int) {
        // Check if this is a bot touch
        val botTouch = pendingBotTouch
        val timeSinceBot = System.currentTimeMillis() - lastBotTouchTime

        if (botTouch != null && timeSinceBot < botTouchWindowMs) {
            // Check if touch is near the registered bot touch location
            val dx = kotlin.math.abs(x - botTouch.first)
            val dy = kotlin.math.abs(y - botTouch.second)
            val density = context.resources.displayMetrics.density
            val threshold = (50 * density).toInt()  // 50dp threshold

            if (dx < threshold && dy < threshold) {
                Log.d(TAG, "Touch matches bot touch - ignoring")
                return
            }
        }

        // This is a user touch
        Log.i(TAG, "User touch detected at ($x, $y)")

        if (_state.value == OverlayState.RUNNING) {
            pause()
        }

        // Reset/extend the resume timer
        scheduleResume()
    }

    private fun scheduleResume() {
        cancelResumeTimer()

        resumeRunnable = Runnable {
            if (_state.value == OverlayState.PAUSED) {
                resume()
            }
        }

        mainHandler.postDelayed(resumeRunnable!!, resumeDelayMs)
        Log.d(TAG, "Resume scheduled in ${resumeDelayMs}ms")
    }

    private fun cancelResumeTimer() {
        resumeRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        resumeRunnable = null
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    fun destroy() {
        hide()
        listener = null
    }
}

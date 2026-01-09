package com.visualmapper.companion.explorer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.visualmapper.companion.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Floating overlay that shows exploration progress
 * Supports drag to move and has a stop button
 */
class ExplorationOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ExplorationOverlay"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var progressJob: Job? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var textStatus: TextView? = null
    private var textScreens: TextView? = null
    private var textElements: TextView? = null
    private var textQueue: TextView? = null

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Pending hide runnable to cancel on restart
    private var pendingHideRunnable: Runnable? = null
    private var hideScheduled = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        Log.d(TAG, "show() called, overlayView=${overlayView != null}")

        // Cancel any pending hide from previous exploration
        pendingHideRunnable?.let { overlayView?.removeCallbacks(it) }
        pendingHideRunnable = null
        hideScheduled = false

        if (overlayView != null) {
            // Already visible - just ensure it stays visible (for multi-pass)
            Log.d(TAG, "Overlay already shown - keeping visible for continued exploration")
            overlayView?.visibility = android.view.View.VISIBLE
            return
        }

        try {
            // Use application context to avoid lifecycle issues
            val appContext = context.applicationContext
            windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // Wrap context with Material theme to support MaterialCardView
            val themedContext = ContextThemeWrapper(appContext, R.style.Theme_VisualMapperCompanion)

            // Inflate the overlay layout with themed context
            overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_exploration, null)
            Log.d(TAG, "Overlay view inflated: ${overlayView != null}")

            // Get references to text views
            textStatus = overlayView?.findViewById(R.id.textStatus)
            textScreens = overlayView?.findViewById(R.id.textScreens)
            textElements = overlayView?.findViewById(R.id.textElements)
            textQueue = overlayView?.findViewById(R.id.textQueue)

            // Set up window parameters
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            // Position in bottom-right corner to avoid accidental taps during exploration
            // (exploration taps elements on screen; if overlay is in top-left, it may get hit)
            layoutParams?.gravity = Gravity.BOTTOM or Gravity.END
            layoutParams?.x = 50
            layoutParams?.y = 150

            // Set up stop button FIRST (before the drag listener)
            val btnStop = overlayView?.findViewById<Button>(R.id.btnStop)
            btnStop?.setOnClickListener {
                Log.i(TAG, "Stop button clicked on overlay")
                stopExploration()
            }

            // Set up drag listener on a drag handle area (not the button)
            // Find the status text view to use as drag handle
            val dragHandle = overlayView?.findViewById<View>(R.id.textStatus)
            dragHandle?.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams?.x ?: 0
                        initialY = layoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        // FIX: Don't consume ACTION_DOWN - only consume when actually dragging
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = kotlin.math.abs(event.rawX - initialTouchX)
                        val deltaY = kotlin.math.abs(event.rawY - initialTouchY)
                        // Only consume if significant movement (drag threshold)
                        if (deltaX > 10 || deltaY > 10) {
                            layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                            layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(overlayView, layoutParams)
                            true  // Consume actual drag events
                        } else {
                            false  // Let small movements pass through
                        }
                    }
                    else -> false
                }
            }

            // Also allow dragging from the card itself (but not button area)
            overlayView?.setOnTouchListener { view, event ->
                // Only handle drag if not touching the button
                val btnStopView = overlayView?.findViewById<View>(R.id.btnStop)
                val buttonRect = android.graphics.Rect()
                btnStopView?.getGlobalVisibleRect(buttonRect)

                val touchX = event.rawX.toInt()
                val touchY = event.rawY.toInt()

                // If touch is on button, don't intercept
                if (buttonRect.contains(touchX, touchY)) {
                    return@setOnTouchListener false
                }

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams?.x ?: 0
                        initialY = layoutParams?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        // FIX: Don't consume ACTION_DOWN - only consume when actually dragging
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = kotlin.math.abs(event.rawX - initialTouchX)
                        val deltaY = kotlin.math.abs(event.rawY - initialTouchY)
                        // Only consume if significant movement (drag threshold)
                        if (deltaX > 10 || deltaY > 10) {
                            layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                            layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(overlayView, layoutParams)
                            true  // Consume actual drag events
                        } else {
                            false  // Let small movements pass through
                        }
                    }
                    else -> false
                }
            }

            windowManager?.addView(overlayView, layoutParams)
            Log.i(TAG, "Exploration overlay added to window manager")

            // Start observing progress
            startObservingProgress()

            Log.i(TAG, "Exploration overlay shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}", e)
        }
    }

    private fun stopExploration() {
        try {
            Log.i(TAG, "Sending stop intent from overlay")
            val appContext = context.applicationContext
            val stopIntent = Intent(appContext, AppExplorerService::class.java).apply {
                action = AppExplorerService.ACTION_STOP
            }
            appContext.startService(stopIntent)
            Log.i(TAG, "Stop intent sent from overlay successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stop intent", e)
        }
    }

    fun hide() {
        Log.d(TAG, "hide() called")

        // Cancel pending hide callback
        pendingHideRunnable?.let { overlayView?.removeCallbacks(it) }
        pendingHideRunnable = null
        hideScheduled = false

        progressJob?.cancel()
        progressJob = null

        overlayView?.let {
            try {
                windowManager?.removeView(it)
                Log.i(TAG, "Exploration overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay", e)
            }
        }
        overlayView = null
        windowManager = null
    }

    // Track if we're showing a help request
    private var showingHelpRequest = false
    private var originalStatusText: String? = null

    /**
     * Show a help request message on the overlay when stuck
     */
    fun showHelpRequest(title: String, message: String) {
        if (overlayView == null) {
            Log.w(TAG, "Cannot show help request - overlay not visible")
            return
        }

        showingHelpRequest = true
        originalStatusText = textStatus?.text?.toString()

        // Update UI on main thread
        overlayView?.post {
            textStatus?.text = title
            textStatus?.setTextColor(android.graphics.Color.parseColor("#FF5722"))  // Orange/red
            textScreens?.text = message
            textElements?.text = "Waiting for user..."
            textQueue?.text = ""
        }

        Log.i(TAG, "Showing help request: $title - $message")
    }

    /**
     * Hide the help request and restore normal progress display
     */
    fun hideHelpRequest() {
        if (!showingHelpRequest) return

        showingHelpRequest = false

        overlayView?.post {
            textStatus?.setTextColor(android.graphics.Color.WHITE)
            // Let the progress observer restore the proper values
        }

        Log.i(TAG, "Help request hidden")
    }

    private fun startObservingProgress() {
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            AppExplorerService.progress.collectLatest { progress ->
                val isManualMode = AppExplorerService.explorationState.value?.config?.mode == ExplorationMode.MANUAL
                updateUI(progress, isManualMode)
            }
        }
    }

    private fun updateUI(progress: ExplorationProgress, isManualMode: Boolean = false) {
        // Don't update if showing help request - let the help UI stay visible
        if (showingHelpRequest) {
            return
        }

        textStatus?.text = when (progress.status) {
            ExplorationStatus.IN_PROGRESS -> if (isManualMode) "Manual Mode" else "Exploring..."
            ExplorationStatus.PAUSED -> "Paused"
            ExplorationStatus.COMPLETED -> "Complete!"
            ExplorationStatus.STOPPED -> "Stopped Early"
            ExplorationStatus.CANCELLED -> "Cancelled"
            ExplorationStatus.ERROR -> "Error"
            else -> "Starting..."
        }
        textScreens?.text = "Screens: ${progress.screensExplored}"
        textElements?.text = if (isManualMode) "Clickable: ${progress.elementsExplored}" else "Elements: ${progress.elementsExplored}"
        textQueue?.text = if (isManualMode) "Paths: ${progress.queueSize}" else "Queue: ${progress.queueSize}"

        // Auto-hide when completed/stopped/cancelled (only once)
        if (!hideScheduled && (
            progress.status == ExplorationStatus.COMPLETED ||
            progress.status == ExplorationStatus.STOPPED ||
            progress.status == ExplorationStatus.CANCELLED ||
            progress.status == ExplorationStatus.ERROR)) {
            hideScheduled = true
            // Delay hide to let user see final status
            pendingHideRunnable = Runnable { hide() }
            overlayView?.postDelayed(pendingHideRunnable!!, 2000)
            Log.d(TAG, "Scheduled hide in 2 seconds for status: ${progress.status}")
        }
    }
}

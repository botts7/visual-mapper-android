package com.visualmapper.companion.explorer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Shows a visual ripple animation at tap locations during exploration.
 * Helps users see where the exploration is clicking.
 */
class TapAnimationOverlay(private val context: Context) {

    companion object {
        private const val TAG = "TapAnimationOverlay"
        private const val ANIMATION_DURATION_MS = 400L
        private const val CIRCLE_START_RADIUS = 20f
        private const val CIRCLE_END_RADIUS = 60f
    }

    private var windowManager: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isEnabled = true

    init {
        windowManager = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Enable or disable tap animations.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    /**
     * Show a ripple animation at the specified coordinates.
     *
     * @param x The x coordinate of the tap
     * @param y The y coordinate of the tap
     */
    fun showTapAnimation(x: Float, y: Float) {
        if (!isEnabled) return

        mainHandler.post {
            try {
                showTapAnimationOnMainThread(x, y)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show tap animation", e)
            }
        }
    }

    private fun showTapAnimationOnMainThread(x: Float, y: Float) {
        val rippleView = RippleView(context.applicationContext, x, y)

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
            windowManager?.addView(rippleView, params)
            Log.d(TAG, "Tap animation shown at ($x, $y)")

            // Start animation
            rippleView.startAnimation {
                // Remove view after animation completes
                mainHandler.post {
                    try {
                        windowManager?.removeView(rippleView)
                        Log.d(TAG, "Tap animation removed")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to remove tap animation view", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add tap animation view", e)
        }
    }

    /**
     * Custom view that draws and animates the ripple effect.
     */
    private inner class RippleView(
        context: Context,
        private val tapX: Float,
        private val tapY: Float
    ) : View(context) {

        private val circlePaint = Paint().apply {
            color = Color.argb(180, 33, 150, 243) // Semi-transparent blue (#2196F3)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val strokePaint = Paint().apply {
            color = Color.argb(255, 33, 150, 243) // Solid blue stroke
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        private var currentRadius = CIRCLE_START_RADIUS
        private var currentAlpha = 180

        fun setRadius(radius: Float) {
            currentRadius = radius
            invalidate()
        }

        fun setCircleAlpha(alpha: Int) {
            currentAlpha = alpha
            circlePaint.alpha = alpha
            strokePaint.alpha = minOf(255, alpha + 75)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // Draw filled circle
            canvas.drawCircle(tapX, tapY, currentRadius, circlePaint)

            // Draw stroke
            canvas.drawCircle(tapX, tapY, currentRadius, strokePaint)

            // Draw center dot
            val dotPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(tapX, tapY, 8f, dotPaint)
        }

        fun startAnimation(onComplete: () -> Unit) {
            val radiusAnimator = ObjectAnimator.ofFloat(
                this,
                "radius",
                CIRCLE_START_RADIUS,
                CIRCLE_END_RADIUS
            ).apply {
                duration = ANIMATION_DURATION_MS
            }

            val alphaAnimator = ObjectAnimator.ofInt(
                this,
                "circleAlpha",
                180,
                0
            ).apply {
                duration = ANIMATION_DURATION_MS
            }

            val animatorSet = AnimatorSet().apply {
                playTogether(radiusAnimator, alphaAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        onComplete()
                    }
                })
            }

            animatorSet.start()
        }
    }
}

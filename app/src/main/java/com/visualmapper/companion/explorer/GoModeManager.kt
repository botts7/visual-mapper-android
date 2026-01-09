package com.visualmapper.companion.explorer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.visualmapper.companion.R
import com.visualmapper.companion.security.SecurePreferences

/**
 * Manages "Go Mode" - time-limited sensitive access for exploration.
 *
 * Go Mode allows the explorer to perform sensitive actions (password entry, etc.)
 * for a limited time after user confirmation.
 *
 * Features:
 * - Time-limited activation (30s to 5min)
 * - Auto-deactivation on timeout
 * - Optional auto-deactivation on app switch
 * - Visual notification while active
 * - Audit logging of all Go Mode sessions
 */
class GoModeManager(private val context: Context) {

    companion object {
        private const val TAG = "GoModeManager"
        private const val NOTIFICATION_CHANNEL_ID = "go_mode_channel"
        private const val NOTIFICATION_ID = 9001

        // Duration presets in milliseconds
        const val DURATION_30_SECONDS = 30 * 1000L
        const val DURATION_1_MINUTE = 60 * 1000L
        const val DURATION_2_MINUTES = 2 * 60 * 1000L
        const val DURATION_5_MINUTES = 5 * 60 * 1000L
        const val DEFAULT_DURATION = DURATION_30_SECONDS
        const val MAX_DURATION = DURATION_5_MINUTES
    }

    interface GoModeListener {
        fun onGoModeActivated(durationMs: Long)
        fun onGoModeDeactivated(reason: DeactivationReason)
        fun onGoModeTick(remainingMs: Long)
    }

    enum class DeactivationReason {
        TIMEOUT,
        USER_CANCELLED,
        APP_SWITCH,
        SECURITY_TRIGGER,
        SESSION_END
    }

    private val securePrefs = SecurePreferences(context)
    private val handler = Handler(Looper.getMainLooper())
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var countDownTimer: CountDownTimer? = null
    private var goModeEndTime: Long = 0
    private var isGoModeActive: Boolean = false
    private var currentDuration: Long = 0
    private var activationTimestamp: Long = 0
    private var targetPackage: String? = null

    private val listeners = mutableListOf<GoModeListener>()

    init {
        createNotificationChannel()
    }

    fun addListener(listener: GoModeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: GoModeListener) {
        listeners.remove(listener)
    }

    // =========================================================================
    // Activation / Deactivation
    // =========================================================================

    /**
     * Activate Go Mode for a specified duration.
     * @param durationMs Duration in milliseconds (capped at MAX_DURATION)
     * @param packageName Optional: Target package for this Go Mode session
     * @param onComplete Callback when Go Mode expires or is cancelled
     */
    fun activate(
        durationMs: Long = DEFAULT_DURATION,
        packageName: String? = null,
        onComplete: ((DeactivationReason) -> Unit)? = null
    ) {
        // Cap duration at max
        val safeDuration = durationMs.coerceIn(DURATION_30_SECONDS, MAX_DURATION)

        // Deactivate any existing session
        if (isGoModeActive) {
            deactivate(DeactivationReason.SESSION_END)
        }

        isGoModeActive = true
        currentDuration = safeDuration
        goModeEndTime = System.currentTimeMillis() + safeDuration
        activationTimestamp = System.currentTimeMillis()
        targetPackage = packageName

        Log.i(TAG, "=== GO MODE ACTIVATED ===")
        Log.i(TAG, "Duration: ${safeDuration / 1000}s, Target: ${packageName ?: "any"}")

        // Show notification
        showGoModeNotification(safeDuration)

        // Start countdown timer
        countDownTimer = object : CountDownTimer(safeDuration, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                listeners.forEach { it.onGoModeTick(millisUntilFinished) }
                updateNotification(millisUntilFinished)
            }

            override fun onFinish() {
                Log.i(TAG, "Go Mode timer expired")
                deactivate(DeactivationReason.TIMEOUT)
                onComplete?.invoke(DeactivationReason.TIMEOUT)
            }
        }.start()

        listeners.forEach { it.onGoModeActivated(safeDuration) }
    }

    /**
     * Deactivate Go Mode.
     * @param reason The reason for deactivation
     */
    fun deactivate(reason: DeactivationReason = DeactivationReason.USER_CANCELLED) {
        if (!isGoModeActive) return

        countDownTimer?.cancel()
        countDownTimer = null

        val sessionDuration = System.currentTimeMillis() - activationTimestamp

        Log.i(TAG, "=== GO MODE DEACTIVATED ===")
        Log.i(TAG, "Reason: $reason, Session duration: ${sessionDuration / 1000}s")

        isGoModeActive = false
        goModeEndTime = 0
        currentDuration = 0
        activationTimestamp = 0

        // Clear notification
        notificationManager.cancel(NOTIFICATION_ID)

        listeners.forEach { it.onGoModeDeactivated(reason) }
    }

    /**
     * Called when the user switches to a different app.
     * Will deactivate Go Mode if auto-deactivate is enabled.
     */
    fun onAppSwitched(newPackage: String) {
        if (!isGoModeActive) return

        // If Go Mode was for a specific package, deactivate when leaving that package
        if (targetPackage != null && targetPackage != newPackage) {
            Log.i(TAG, "App switched from $targetPackage to $newPackage - deactivating Go Mode")
            deactivate(DeactivationReason.APP_SWITCH)
        }
    }

    // =========================================================================
    // State Queries
    // =========================================================================

    /**
     * Check if Go Mode is currently active.
     */
    fun isActive(): Boolean = isGoModeActive

    /**
     * Get remaining time in milliseconds.
     */
    fun getRemainingTime(): Long {
        if (!isGoModeActive) return 0
        val remaining = goModeEndTime - System.currentTimeMillis()
        return if (remaining > 0) remaining else 0
    }

    /**
     * Get remaining time as formatted string (MM:SS).
     */
    fun getRemainingTimeFormatted(): String {
        val remaining = getRemainingTime()
        val seconds = (remaining / 1000) % 60
        val minutes = (remaining / 1000) / 60
        return "%d:%02d".format(minutes, seconds)
    }

    /**
     * Get the target package for this Go Mode session, if set.
     */
    fun getTargetPackage(): String? = targetPackage

    /**
     * Check if this Go Mode session applies to a specific package.
     */
    fun appliesTo(packageName: String): Boolean {
        if (!isGoModeActive) return false
        return targetPackage == null || targetPackage == packageName
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Go Mode Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when sensitive exploration mode is active"
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showGoModeNotification(durationMs: Long) {
        val notification = buildNotification(durationMs)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(remainingMs: Long) {
        val notification = buildNotification(remainingMs)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(remainingMs: Long): Notification {
        val seconds = (remainingMs / 1000) % 60
        val minutes = (remainingMs / 1000) / 60
        val timeText = "%d:%02d".format(minutes, seconds)

        // Intent to cancel Go Mode
        val cancelIntent = Intent(context, GoModeBroadcastReceiver::class.java).apply {
            action = "com.visualmapper.companion.CANCEL_GO_MODE"
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Go Mode Active")
            .setContentText("Sensitive access enabled - $timeText remaining")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )
            .build()
    }

    // =========================================================================
    // Duration Helpers
    // =========================================================================

    data class DurationOption(
        val label: String,
        val durationMs: Long
    )

    /**
     * Get available duration options.
     */
    fun getDurationOptions(): List<DurationOption> = listOf(
        DurationOption("30 seconds", DURATION_30_SECONDS),
        DurationOption("1 minute", DURATION_1_MINUTE),
        DurationOption("2 minutes", DURATION_2_MINUTES),
        DurationOption("5 minutes", DURATION_5_MINUTES)
    )
}

/**
 * Broadcast receiver for cancelling Go Mode from notification.
 */
class GoModeBroadcastReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.visualmapper.companion.CANCEL_GO_MODE") {
            Log.i("GoModeBroadcastReceiver", "Go Mode cancelled from notification")
            // The GoModeManager instance should be obtained from the app or service
            // For now, we'll rely on the service to handle this
        }
    }
}

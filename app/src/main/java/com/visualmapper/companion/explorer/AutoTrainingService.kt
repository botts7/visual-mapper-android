package com.visualmapper.companion.explorer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.visualmapper.companion.R
import com.visualmapper.companion.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * AutoTrainingService - Foreground service for batch ML training
 *
 * Features:
 * - Runs exploration training as a foreground service
 * - Shows progress notification with pause/stop buttons
 * - Holds partial wake lock to prevent sleep during training
 * - Integrates with TrainingOrchestrator for actual training logic
 *
 * Triggers:
 * - Opportunistic: IdleChargingReceiver detects idle + charging
 * - Scheduled: WorkManager daily training job
 * - Manual: User starts training from UI
 */
class AutoTrainingService : Service() {

    companion object {
        private const val TAG = "AutoTrainingService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "auto_training_channel"

        const val ACTION_START = "com.visualmapper.companion.START_AUTO_TRAINING"
        const val ACTION_STOP = "com.visualmapper.companion.STOP_AUTO_TRAINING"
        const val ACTION_PAUSE = "com.visualmapper.companion.PAUSE_AUTO_TRAINING"
        const val ACTION_RESUME = "com.visualmapper.companion.RESUME_AUTO_TRAINING"

        const val EXTRA_CONFIG = "training_config"
        const val EXTRA_TRIGGER = "training_trigger"

        // Trigger types for logging
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_OPPORTUNISTIC = "opportunistic"
        const val TRIGGER_SCHEDULED = "scheduled"

        private var instance: AutoTrainingService? = null
        fun getInstance(): AutoTrainingService? = instance

        /**
         * Start training service with configuration
         */
        fun start(context: Context, config: TrainingOrchestrator.TrainingConfig? = null, trigger: String = TRIGGER_MANUAL) {
            val intent = Intent(context, AutoTrainingService::class.java).apply {
                action = ACTION_START
                config?.let { putExtra(EXTRA_CONFIG, it.toJson()) }
                putExtra(EXTRA_TRIGGER, trigger)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop training service
         */
        fun stop(context: Context) {
            val intent = Intent(context, AutoTrainingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var currentTrigger: String = TRIGGER_MANUAL

    // Cached PendingIntents
    private var stopPendingIntent: PendingIntent? = null
    private var pausePendingIntent: PendingIntent? = null
    private var resumePendingIntent: PendingIntent? = null
    private var contentPendingIntent: PendingIntent? = null

    // Persistent batch training overlay
    private var batchOverlay: android.view.View? = null
    private var batchOverlayText: android.widget.TextView? = null
    private var windowManager: android.view.WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        createPendingIntents()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        Log.i(TAG, "AutoTrainingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                currentTrigger = intent.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_MANUAL

                // Check POST_NOTIFICATIONS permission on Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "POST_NOTIFICATIONS permission not granted - cannot start foreground service")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                }

                // Start foreground immediately
                startForeground(NOTIFICATION_ID, createNotification("Starting batch training..."))

                // Acquire wake lock
                acquireWakeLock()

                // Parse config if provided
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                val config = if (!configJson.isNullOrEmpty()) {
                    try {
                        TrainingOrchestrator.TrainingConfig.fromJson(configJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse config: ${e.message}")
                        TrainingOrchestrator.TrainingConfig()
                    }
                } else {
                    TrainingOrchestrator.TrainingConfig()
                }

                // Show persistent overlay
                showBatchOverlay()

                // Start observing training state
                startStateObserver()

                // Start training
                val orchestrator = TrainingOrchestrator.getInstance(this)
                orchestrator.startTraining(config)

                Log.i(TAG, "=== AUTO TRAINING STARTED (trigger: $currentTrigger) ===")
            }

            ACTION_STOP -> {
                Log.i(TAG, "Stopping auto training")
                hideBatchOverlay()
                TrainingOrchestrator.getInstance(this).stopTraining()
                stopSelf()
            }

            ACTION_PAUSE -> {
                Log.i(TAG, "Pausing auto training")
                // Pause not fully implemented - just stop for now
                TrainingOrchestrator.getInstance(this).stopTraining()
                updateNotification("Training paused")
            }

            ACTION_RESUME -> {
                Log.i(TAG, "Resuming auto training")
                TrainingOrchestrator.getInstance(this).startTraining()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AutoTrainingService destroyed")
        stateObserverJob?.cancel()
        hideBatchOverlay()
        releaseWakeLock()
        scope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Start observing training state for notification updates
     */
    private fun startStateObserver() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            TrainingOrchestrator.getInstance(this@AutoTrainingService)
                .trainingState
                .collectLatest { state ->
                    if (state.isRunning) {
                        val text = buildString {
                            append("App ${state.currentAppIndex}/${state.totalApps}")
                            state.currentApp?.let { app ->
                                val shortName = app.substringAfterLast(".")
                                append(" - $shortName")
                            }
                            if (state.appsCompleted > 0) {
                                append(" (${state.appsCompleted} done)")
                            }
                        }
                        updateNotification(text)
                        updateBatchOverlay(text)
                    } else if (state.appsCompleted > 0 || state.appsFailed > 0) {
                        // Training finished
                        val completionText = "Complete: ${state.appsCompleted} trained, ${state.appsFailed} failed"
                        updateNotification(completionText)
                        updateBatchOverlay(completionText)
                        delay(5000)  // Show completion for 5 seconds
                        hideBatchOverlay()
                        stopSelf()
                    }
                }
        }
    }

    /**
     * Acquire partial wake lock to prevent device sleep during training
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VisualMapper:AutoTraining"
            ).apply {
                acquire(60 * 60 * 1000L)  // Max 1 hour
            }
            Log.d(TAG, "Wake lock acquired")
        }
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    /**
     * Show persistent batch training overlay
     */
    private fun showBatchOverlay() {
        if (batchOverlay != null) return  // Already showing

        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show batch overlay - no overlay permission")
            return
        }

        try {
            // Create overlay layout
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setBackgroundColor(0xDD2196F3.toInt())  // Blue with alpha
                setPadding(24, 16, 24, 16)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            // Add icon
            val icon = android.widget.ImageView(this).apply {
                setImageResource(android.R.drawable.ic_menu_rotate)
                setColorFilter(0xFFFFFFFF.toInt())
                val size = (24 * resources.displayMetrics.density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                }
            }
            layout.addView(icon)

            // Add text
            val text = android.widget.TextView(this).apply {
                text = "Batch Training..."
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            layout.addView(text)
            batchOverlayText = text

            // Window params - top of screen, non-focusable
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    android.view.WindowManager.LayoutParams.TYPE_PHONE,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                y = (48 * resources.displayMetrics.density).toInt()  // Below status bar
            }

            windowManager?.addView(layout, params)
            batchOverlay = layout
            Log.i(TAG, "Batch training overlay shown")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show batch overlay", e)
        }
    }

    /**
     * Update batch training overlay text
     */
    private fun updateBatchOverlay(text: String) {
        batchOverlayText?.text = text
    }

    /**
     * Hide batch training overlay
     */
    private fun hideBatchOverlay() {
        try {
            batchOverlay?.let {
                windowManager?.removeView(it)
                Log.i(TAG, "Batch training overlay hidden")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error hiding batch overlay", e)
        }
        batchOverlay = null
        batchOverlayText = null
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Training",
                NotificationManager.IMPORTANCE_LOW  // Low to avoid sound/vibration
            ).apply {
                description = "Shows batch ML training progress"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create cached PendingIntents for notification actions
     */
    private fun createPendingIntents() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // Stop action
        stopPendingIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AutoTrainingService::class.java).apply { action = ACTION_STOP },
            flags
        )

        // Pause action
        pausePendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoTrainingService::class.java).apply { action = ACTION_PAUSE },
            flags
        )

        // Resume action
        resumePendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, AutoTrainingService::class.java).apply { action = ACTION_RESUME },
            flags
        )

        // Content (tap to open app)
        contentPendingIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            flags
        )
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ML Training")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    /**
     * Update notification text
     */
    private fun updateNotification(text: String) {
        // Check permission before updating notification on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Cannot update notification - POST_NOTIFICATIONS permission not granted")
                return
            }
        }
        val notification = createNotification(text)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

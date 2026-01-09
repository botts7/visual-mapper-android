package com.visualmapper.companion.explorer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.work.*
import com.visualmapper.companion.security.SecurePreferences
import java.util.concurrent.TimeUnit

/**
 * IdleChargingReceiver - Detects when device is idle and charging to trigger training
 *
 * Listens for:
 * - ACTION_POWER_CONNECTED - Device plugged in
 * - ACTION_SCREEN_OFF - Screen turned off (idle proxy)
 * - ACTION_DEVICE_IDLE_MODE_CHANGED - Doze mode changes
 *
 * Training starts when:
 * - Device is charging
 * - Screen has been off for 5+ minutes
 * - Auto-training is enabled in settings
 */
class IdleChargingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "IdleChargingReceiver"
        private const val IDLE_DELAY_MINUTES = 5L  // Wait 5 min after screen off
        private const val PREFS_NAME = "training_settings"
        private const val KEY_AUTO_TRAINING_ENABLED = "auto_training_enabled"
        private const val KEY_REQUIRE_CHARGING = "require_charging"
        private const val KEY_LAST_SCREEN_OFF_TIME = "last_screen_off_time"

        // Work tags for cancellation
        private const val WORK_TAG_IDLE_CHECK = "idle_training_check"
        private const val WORK_TAG_SCHEDULED_TRAINING = "scheduled_training"

        /**
         * Register this receiver programmatically
         */
        fun register(context: Context): IdleChargingReceiver {
            val receiver = IdleChargingReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }
            context.registerReceiver(receiver, filter)
            Log.i(TAG, "IdleChargingReceiver registered")
            return receiver
        }

        /**
         * Schedule daily training using WorkManager
         */
        fun scheduleDailyTraining(context: Context, hour: Int = 2) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()

            // Calculate delay until target hour
            val currentTime = System.currentTimeMillis()
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = currentTime
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (timeInMillis <= currentTime) {
                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                }
            }
            val initialDelay = calendar.timeInMillis - currentTime

            val workRequest = PeriodicWorkRequestBuilder<TrainingWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG_SCHEDULED_TRAINING)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG_SCHEDULED_TRAINING,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

            Log.i(TAG, "Scheduled daily training at $hour:00")
        }

        /**
         * Cancel scheduled training
         */
        fun cancelScheduledTraining(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG_SCHEDULED_TRAINING)
            Log.i(TAG, "Cancelled scheduled training")
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoTrainingEnabled = prefs.getBoolean(KEY_AUTO_TRAINING_ENABLED, false)

        if (!autoTrainingEnabled) {
            Log.d(TAG, "Auto-training disabled, ignoring event")
            return
        }

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.i(TAG, "Power connected - checking idle state")
                scheduleIdleCheck(context)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.i(TAG, "Power disconnected - cancelling pending training")
                cancelPendingTrainingCheck(context)
            }

            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "Screen off - recording time")
                prefs.edit().putLong(KEY_LAST_SCREEN_OFF_TIME, System.currentTimeMillis()).apply()

                // If charging, schedule idle check
                if (isDeviceCharging(context)) {
                    scheduleIdleCheck(context)
                }
            }

            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen on - cancelling pending training")
                cancelPendingTrainingCheck(context)
            }

            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (powerManager.isDeviceIdleMode && isDeviceCharging(context)) {
                        Log.i(TAG, "Doze mode active + charging - starting training")
                        startTrainingIfReady(context)
                    }
                }
            }
        }
    }

    /**
     * Schedule a check after idle delay
     */
    private fun scheduleIdleCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<IdleCheckWorker>()
            .setConstraints(constraints)
            .setInitialDelay(IDLE_DELAY_MINUTES, TimeUnit.MINUTES)
            .addTag(WORK_TAG_IDLE_CHECK)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_TAG_IDLE_CHECK,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        Log.d(TAG, "Scheduled idle check in $IDLE_DELAY_MINUTES minutes")
    }

    /**
     * Cancel any pending idle training check
     */
    private fun cancelPendingTrainingCheck(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_TAG_IDLE_CHECK)
    }

    /**
     * Check if device is charging
     */
    private fun isDeviceCharging(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.isCharging == true
    }

    /**
     * Start training if all conditions are met
     */
    private fun startTrainingIfReady(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if auto-training is enabled
        if (!prefs.getBoolean(KEY_AUTO_TRAINING_ENABLED, false)) {
            Log.d(TAG, "Auto-training disabled")
            return
        }

        // Check if already running
        val orchestrator = TrainingOrchestrator.getInstance(context)
        if (orchestrator.trainingState.value.isRunning) {
            Log.d(TAG, "Training already running")
            return
        }

        // Check whitelisted apps
        val securePrefs = SecurePreferences(context)
        val whitelistedApps = securePrefs.whitelistedApps
        if (whitelistedApps.isEmpty()) {
            Log.d(TAG, "No whitelisted apps for training")
            return
        }

        // Start training
        val config = TrainingOrchestrator.TrainingConfig(
            enabled = true,
            requireCharging = prefs.getBoolean(KEY_REQUIRE_CHARGING, true),
            requireIdle = true,
            minutesPerApp = prefs.getInt("minutes_per_app", TrainingOrchestrator.DEFAULT_MINUTES_PER_APP),
            maxAppsPerSession = prefs.getInt("max_apps_per_session", TrainingOrchestrator.DEFAULT_MAX_APPS_PER_SESSION)
        )

        Log.i(TAG, "=== STARTING OPPORTUNISTIC TRAINING ===")
        orchestrator.startTraining(config)
    }
}

/**
 * Worker for checking idle state after delay
 */
class IdleCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "IdleCheckWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "Idle check triggered")

        // Verify conditions are still met
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val isCharging = batteryManager?.isCharging == true

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOff = powerManager?.isInteractive == false

        if (!isCharging) {
            Log.d(TAG, "No longer charging - skipping training")
            return Result.success()
        }

        if (!isScreenOff) {
            Log.d(TAG, "Screen is on - skipping training")
            return Result.success()
        }

        // Start training
        val orchestrator = TrainingOrchestrator.getInstance(context)
        if (!orchestrator.trainingState.value.isRunning) {
            val prefs = context.getSharedPreferences("training_settings", Context.MODE_PRIVATE)
            val config = TrainingOrchestrator.TrainingConfig(
                enabled = true,
                requireCharging = true,
                requireIdle = true,
                minutesPerApp = prefs.getInt("minutes_per_app", TrainingOrchestrator.DEFAULT_MINUTES_PER_APP),
                maxAppsPerSession = prefs.getInt("max_apps_per_session", TrainingOrchestrator.DEFAULT_MAX_APPS_PER_SESSION)
            )

            Log.i(TAG, "=== STARTING IDLE TRAINING ===")
            orchestrator.startTraining(config)
        }

        return Result.success()
    }
}

/**
 * Worker for scheduled daily training
 */
class TrainingWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "TrainingWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "Scheduled training triggered")

        val orchestrator = TrainingOrchestrator.getInstance(context)
        if (!orchestrator.trainingState.value.isRunning) {
            val prefs = context.getSharedPreferences("training_settings", Context.MODE_PRIVATE)
            val config = TrainingOrchestrator.TrainingConfig(
                enabled = true,
                requireCharging = true,
                requireIdle = false,  // Scheduled training doesn't require idle
                minutesPerApp = prefs.getInt("minutes_per_app", TrainingOrchestrator.DEFAULT_MINUTES_PER_APP),
                maxAppsPerSession = prefs.getInt("max_apps_per_session", TrainingOrchestrator.DEFAULT_MAX_APPS_PER_SESSION)
            )

            Log.i(TAG, "=== STARTING SCHEDULED TRAINING ===")
            orchestrator.startTraining(config)
        }

        return Result.success()
    }
}

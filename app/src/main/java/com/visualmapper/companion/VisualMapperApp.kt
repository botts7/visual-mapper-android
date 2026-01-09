package com.visualmapper.companion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.content.Intent
import com.visualmapper.companion.explorer.AppExplorerService
import com.visualmapper.companion.mqtt.ActionCommandHandler
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.service.FlowExecutorService
import com.visualmapper.companion.service.SyncWorker

/**
 * Visual Mapper Companion Application
 *
 * Application class that initializes core components.
 */
class VisualMapperApp : Application() {

    companion object {
        private const val TAG = "VisualMapperApp"
        const val NOTIFICATION_CHANNEL_ID = "visual_mapper_service"

        lateinit var instance: VisualMapperApp
            private set
    }

    // Core managers
    lateinit var mqttManager: MqttManager
        private set

    lateinit var serverSyncManager: ServerSyncManager
        private set

    // Action command handler (initialized after MQTT connection)
    var actionCommandHandler: ActionCommandHandler? = null
        private set

    // Android ID (stable across app reinstalls, used as ADB identifier)
    val androidId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
    }

    // Stable device ID from server (composite ID based on serial/properties)
    // This is the ID used in flows and sensors
    private var _stableDeviceId: String? = null
    val stableDeviceId: String
        get() = _stableDeviceId ?: androidId

    fun setStableDeviceId(id: String) {
        _stableDeviceId = id
        Log.i(TAG, "Stable device ID set to: $id")

        // Save to preferences
        getSharedPreferences("visual_mapper", MODE_PRIVATE).edit()
            .putString("stable_device_id", id)
            .apply()
    }

    fun loadStableDeviceId() {
        _stableDeviceId = getSharedPreferences("visual_mapper", MODE_PRIVATE)
            .getString("stable_device_id", null)
        if (_stableDeviceId != null) {
            Log.i(TAG, "Loaded stable device ID: $_stableDeviceId")
        }
    }

    // Legacy deviceId property for backwards compatibility
    @Deprecated("Use stableDeviceId for flows/sensors, androidId for ADB operations")
    val deviceId: String
        get() = stableDeviceId

    override fun onCreate() {
        super.onCreate()
        instance = this

        Log.i(TAG, "Visual Mapper Companion starting...")
        Log.i(TAG, "Android ID: $androidId")

        // Load stable device ID from preferences
        loadStableDeviceId()
        Log.i(TAG, "Stable Device ID: $stableDeviceId")

        // Initialize managers
        mqttManager = MqttManager(this)
        mqttManager.initMlTrainingFromPrefs()  // Load ML training setting from SharedPreferences
        serverSyncManager = ServerSyncManager(this)

        // Phase 1: Offline Resilience - Wire up reconnect callback for flushing pending results
        setupOfflineResilienceCallbacks()

        // Initialize action command handler (wires up MQTT callbacks)
        actionCommandHandler = ActionCommandHandler(this, mqttManager, stableDeviceId)
        Log.i(TAG, "Action command handler initialized")

        // Setup exploration callbacks (for ML training automation)
        setupExplorationCallbacks()

        // Load cached flows from storage (for offline execution)
        val loadedCount = FlowExecutorService.loadFromStorage(this)
        if (loadedCount > 0) {
            Log.i(TAG, "Loaded $loadedCount flows from storage for offline execution")
        }

        // Create notification channel (must be done before starting foreground service)
        createNotificationChannel()

        // Start FlowExecutorService so getInstance() works from any Activity
        startFlowExecutorService()

        // Phase 2: Schedule background sync for pending transitions
        scheduleSyncWorker()
    }

    /**
     * Schedule the SyncWorker for periodic background sync.
     * Phase 2 Stability: Ensures queued transitions are eventually delivered.
     */
    private fun scheduleSyncWorker() {
        try {
            SyncWorker.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule SyncWorker: ${e.message}")
        }
    }

    /**
     * Start FlowExecutorService as a foreground service.
     * This ensures getInstance() returns non-null from any Activity/Fragment.
     */
    private fun startFlowExecutorService() {
        try {
            val intent = android.content.Intent(this, FlowExecutorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "FlowExecutorService started")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start FlowExecutorService: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)

            Log.d(TAG, "Notification channel created")
        }
    }

    /**
     * Phase 1: Offline Resilience - Setup callbacks for flushing pending results
     *
     * When MQTT reconnects, this triggers flushing of any queued execution results
     * that were saved while offline.
     */
    private fun setupOfflineResilienceCallbacks() {
        mqttManager.onReconnect = {
            Log.i(TAG, "MQTT reconnected - flushing pending execution results")
            try {
                val flowExecutor = FlowExecutorService.getInstance()
                if (flowExecutor != null) {
                    val flushedCount = flowExecutor.flushPendingResults()
                    if (flushedCount > 0) {
                        Log.i(TAG, "Flushed $flushedCount pending execution results")
                    }
                } else {
                    Log.w(TAG, "FlowExecutorService not available for flushing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush pending results: ${e.message}")
            }
        }
        Log.i(TAG, "Offline resilience callbacks configured")
    }

    /**
     * Setup MQTT callbacks for exploration commands (ML training)
     */
    private fun setupExplorationCallbacks() {
        // Handle explore start command
        mqttManager.onExploreStart = { packageName, configJson ->
            Log.i(TAG, "Received explore start command for: $packageName")

            // Start AppExplorerService with the package
            val intent = Intent(this, AppExplorerService::class.java).apply {
                action = AppExplorerService.ACTION_START
                putExtra(AppExplorerService.EXTRA_PACKAGE_NAME, packageName)
                putExtra(AppExplorerService.EXTRA_CONFIG, configJson)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        // Handle explore stop command
        mqttManager.onExploreStop = {
            Log.i(TAG, "Received explore stop command")

            val intent = Intent(this, AppExplorerService::class.java).apply {
                action = AppExplorerService.ACTION_STOP
            }
            startService(intent)
        }

        Log.i(TAG, "Exploration callbacks configured")
    }
}

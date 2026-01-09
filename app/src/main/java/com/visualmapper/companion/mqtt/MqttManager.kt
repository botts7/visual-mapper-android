package com.visualmapper.companion.mqtt

import android.content.Context
import android.util.Log
import com.visualmapper.companion.storage.OfflineTelemetryQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * MQTT Manager
 *
 * Handles MQTT connection to Home Assistant broker.
 * Publishes sensor values and subscribes to action commands.
 *
 * Phase 3 ML Stability:
 * - OfflineTelemetryQueue for exploration logs when MQTT is offline
 * - Automatic flush on reconnect
 */
class MqttManager(private val context: Context) {

    companion object {
        private const val TAG = "MqttManager"
        private const val CLIENT_ID_PREFIX = "visual_mapper_companion_"
        private const val QOS = 1
        private const val RETAIN = true
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private var client: MqttAsyncClient? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // Error state
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // Retry tracking
    private var retryAttempt = 0

    // Connection guard to prevent multiple simultaneous connections
    @Volatile
    private var isConnecting = false

    // Current device ID (set after connection)
    private var deviceId: String = ""

    // ML Training mode (development)
    private var mlTrainingEnabled: Boolean = false

    // Phase 3: Offline telemetry queue
    private val telemetryQueue = OfflineTelemetryQueue.getInstance(context)

    // Phase 3: Mutex to prevent double-flush race condition
    private val flushMutex = Mutex()

    // Callbacks for MQTT messages
    var onActionCommand: ((actionId: String, payload: String) -> Unit)? = null
    var onFlowExecute: ((flowId: String, payload: String) -> Unit)? = null
    var onConfigUpdate: ((config: String) -> Unit)? = null
    var onConnectionError: ((error: String) -> Unit)? = null
    var onModelUpdate: ((modelBytes: ByteArray, version: String) -> Unit)? = null

    // Phase 1: Offline Resilience - Callback for when MQTT reconnects
    // This triggers flushing of queued execution results
    var onReconnect: (suspend () -> Unit)? = null

    // =========================================================================
    // Connection Management
    // =========================================================================

    /**
     * Connect to MQTT broker with optional SSL/TLS support.
     *
     * Phase 1 Refactor: Added SSL support for secure connections.
     * Maintains backward compatibility via default parameter.
     *
     * @param brokerHost MQTT broker hostname or IP
     * @param brokerPort MQTT broker port (1883 for TCP, 8883 for SSL)
     * @param username Optional username for authentication
     * @param password Optional password for authentication
     * @param deviceId Device identifier for client ID
     * @param useSsl Enable SSL/TLS encryption (default: false for backward compatibility)
     */
    fun connect(
        brokerHost: String,
        brokerPort: Int = 1883,
        username: String? = null,
        password: String? = null,
        deviceId: String,
        useSsl: Boolean = false  // Phase 1: SSL support with backward compatibility
    ) {
        // Guard against multiple simultaneous connections
        if (isConnecting) {
            Log.w(TAG, "Connection already in progress, ignoring duplicate connect request")
            return
        }

        // If already connected, don't reconnect
        if (client?.isConnected == true) {
            Log.i(TAG, "Already connected to MQTT broker")
            _connectionState.value = ConnectionState.CONNECTED
            return
        }

        isConnecting = true
        this.deviceId = deviceId

        // Phase 1: Protocol selection based on SSL setting
        val protocol = if (useSsl) "ssl" else "tcp"
        val brokerUrl = "$protocol://$brokerHost:$brokerPort"
        val clientId = CLIENT_ID_PREFIX + deviceId

        Log.i(TAG, "Connecting to MQTT broker: $brokerUrl (SSL=$useSsl, attempt ${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)")
        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null

        try {
            client = MqttAsyncClient(brokerUrl, clientId, MemoryPersistence())

            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "MQTT connected (reconnect=$reconnect)")
                    isConnecting = false  // Clear connection guard
                    _connectionState.value = ConnectionState.CONNECTED
                    _lastError.value = null
                    retryAttempt = 0  // Reset retry counter on success

                    // Delay subscriptions slightly to let connection stabilize
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (client?.isConnected == true) {
                            // Subscribe to all command topics
                            subscribeToActions()
                            subscribeToFlows()
                            subscribeToConfig()
                            subscribeToExploration()

                            // Publish availability
                            publishAvailability(true)

                            // Publish device status
                            publishDeviceStatus()

                            // Phase 1: Offline Resilience - Flush pending results on reconnect
                            // Trigger flush of queued execution results when MQTT reconnects
                            if (reconnect) {
                                Log.i(TAG, "Reconnected - triggering flush of pending execution results")
                            }
                            // Always call onReconnect (also on initial connect to flush any offline results)
                            onReconnect?.let { callback ->
                                scope.launch {
                                    try {
                                        callback()
                                        Log.i(TAG, "Reconnect callback completed")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Reconnect callback failed: ${e.message}")
                                    }
                                }
                            }

                            // Phase 3: Flush offline telemetry queue (with mutex to prevent double-flush)
                            scope.launch {
                                flushMutex.withLock {
                                    try {
                                        val flushed = telemetryQueue.flush { topic, payload ->
                                            publishSync(topic, payload)
                                        }
                                        if (flushed > 0) {
                                            Log.i(TAG, "Flushed $flushed queued telemetry entries on reconnect")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to flush telemetry queue: ${e.message}")
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "Client disconnected before subscriptions could complete")
                        }
                    }, 500) // 500ms delay for connection stabilization
                }

                override fun connectionLost(cause: Throwable?) {
                    val errorMsg = cause?.message ?: "Unknown error"
                    Log.w(TAG, "MQTT connection lost: $errorMsg")
                    isConnecting = false  // Clear connection guard
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _lastError.value = "Connection lost: $errorMsg"
                    onConnectionError?.invoke("Connection lost: $errorMsg")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    handleMessage(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Delivery confirmed
                }
            })

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 60
                keepAliveInterval = 30
                maxInflight = 100

                if (!username.isNullOrBlank()) {
                    this.userName = username
                    this.password = password?.toCharArray() ?: charArrayOf()
                }

                // Phase 1: Configure SSL/TLS if enabled
                if (useSsl) {
                    try {
                        socketFactory = createTrustAllSocketFactory()
                        // Disable hostname verification for self-signed certs (local HA)
                        isHttpsHostnameVerificationEnabled = false
                        Log.i(TAG, "SSL socket factory configured (trust-all for local HA)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create SSL socket factory", e)
                        throw e
                    }
                }

                // Set Last Will Testament (LWT) for availability
                setWill(
                    "visual_mapper/$deviceId/availability",
                    "offline".toByteArray(),
                    QOS,
                    RETAIN
                )
            }

            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "MQTT connection initiated")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    isConnecting = false  // Clear connection guard
                    val errorMsg = getSslAwareErrorMessage(exception, brokerHost, brokerPort, useSsl)

                    Log.e(TAG, "MQTT connection failed: $errorMsg", exception)
                    _connectionState.value = ConnectionState.ERROR
                    _lastError.value = errorMsg
                    onConnectionError?.invoke(errorMsg)
                }
            })

        } catch (e: Exception) {
            isConnecting = false  // Clear connection guard
            val errorMsg = "Failed to create MQTT client: ${e.message}"
            Log.e(TAG, errorMsg, e)
            _connectionState.value = ConnectionState.ERROR
            _lastError.value = errorMsg
            onConnectionError?.invoke(errorMsg)
        }
    }

    /**
     * Disconnect from MQTT broker
     */
    fun disconnect() {
        try {
            // Only publish availability if actually connected
            if (client?.isConnected == true) {
                publishAvailability(false)
            }
            client?.disconnect()
            client?.close()
            client = null
            _connectionState.value = ConnectionState.DISCONNECTED
            Log.i(TAG, "MQTT disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting: ${e.message}")
            // Force state update even on error
            _connectionState.value = ConnectionState.DISCONNECTED
            client = null
        }
    }

    // =========================================================================
    // Publishing
    // =========================================================================

    /**
     * Publish sensor value to Home Assistant
     */
    fun publishSensorValue(sensorId: String, value: String) {
        val topic = "visual_mapper/$deviceId/$sensorId/state"
        publish(topic, value, retain = true)
    }

    /**
     * Publish sensor with attributes (JSON)
     */
    fun publishSensorWithAttributes(sensorId: String, value: String, attributes: Map<String, Any>) {
        // Publish state
        publishSensorValue(sensorId, value)

        // Publish attributes as JSON
        val attributesTopic = "visual_mapper/$deviceId/$sensorId/attributes"
        val attributesJson = attributes.entries.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${formatJsonValue(v)}"
        }
        publish(attributesTopic, attributesJson, retain = true)
    }

    /**
     * Publish availability status
     */
    private fun publishAvailability(online: Boolean) {
        val topic = "visual_mapper/$deviceId/availability"
        val payload = if (online) "online" else "offline"
        publish(topic, payload, retain = true)
    }

    /**
     * Announce device for ADB connection discovery
     *
     * Publishes the device's network info to allow the server to connect
     * without manual network scanning. This solves the Android 11+ wireless
     * debugging discovery problem (dynamic ports).
     *
     * @param adbPort The wireless debugging port (from Settings > Developer Options > Wireless debugging)
     * @param pairingPort Optional pairing port if device needs pairing
     * @param pairingCode Optional pairing code if device needs pairing
     * @param currentApp Optional current foreground app package name
     */
    fun announceDeviceForConnection(
        adbPort: Int,
        pairingPort: Int? = null,
        pairingCode: String? = null,
        currentApp: String? = null
    ) {
        val ipAddress = getDeviceIpAddress()
        if (ipAddress == null) {
            Log.e(TAG, "Cannot announce device: Unable to get IP address")
            return
        }

        val topic = "visualmapper/devices/announce"

        // Try to get current foreground app from accessibility service if not provided
        val foregroundApp = currentApp ?: try {
            com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
                .getInstance()?.getCurrentPackageName()
        } catch (e: Exception) {
            Log.d(TAG, "Could not get foreground app: ${e.message}")
            null
        }

        val payload = org.json.JSONObject().apply {
            put("device_id", deviceId)
            put("ip", ipAddress)
            put("adb_port", adbPort)
            put("model", android.os.Build.MODEL)
            put("manufacturer", android.os.Build.MANUFACTURER)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("sdk_version", android.os.Build.VERSION.SDK_INT)
            put("already_paired", pairingPort == null)
            put("timestamp", System.currentTimeMillis())
            put("source", "VMC")  // Visual Mapper Companion source indicator

            // Include current foreground app if available
            foregroundApp?.let { put("current_app", it) }

            // Include pairing info if provided
            pairingPort?.let { put("pairing_port", it) }
            pairingCode?.let { put("pairing_code", it) }
        }.toString()

        Log.i(TAG, "Announcing device for connection: $ipAddress:$adbPort (app: $foregroundApp)")
        publish(topic, payload, retain = true)
    }

    /**
     * Get the device's WiFi IP address
     */
    private fun getDeviceIpAddress(): String? {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: return null

            if (ipInt == 0) return null

            // Convert int to IP string (little-endian)
            return String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address: ${e.message}")
            return null
        }
    }

    /**
     * Withdraw device announcement (when disconnecting or disabling)
     */
    fun withdrawDeviceAnnouncement() {
        val topic = "visualmapper/devices/announce"
        // Publish empty retained message to clear the announcement
        publish(topic, "", retain = true)
        Log.i(TAG, "Withdrew device announcement")
    }

    /**
     * Publish device status (capabilities, version, etc.)
     */
    private fun publishDeviceStatus() {
        val topic = "visual_mapper/$deviceId/status"

        // Check actual capabilities dynamically
        val capabilities = mutableListOf<String>()

        // Check if accessibility service is running
        val accessibilityService = com.visualmapper.companion.accessibility.VisualMapperAccessibilityService.getInstance()
        if (accessibilityService != null) {
            capabilities.add("accessibility")
            capabilities.add("gestures")
            capabilities.add("ui_reading")
            capabilities.add("flow_execution")

            // Check gesture dispatcher
            if (accessibilityService.gestureDispatcher != null) {
                capabilities.add("tap")
                capabilities.add("swipe")
                capabilities.add("scroll")
                capabilities.add("long_press")
            }
        }

        // Always available capabilities
        capabilities.add("mqtt")
        capabilities.add("status_reporting")

        val capabilitiesJson = capabilities.joinToString(",") { "\"$it\"" }

        val status = buildString {
            append("{")
            append("\"device_id\":\"$deviceId\",")
            append("\"platform\":\"android\",")
            append("\"app_version\":\"1.0.0\",")
            append("\"accessibility_enabled\":${accessibilityService != null},")
            append("\"capabilities\":[$capabilitiesJson],")
            append("\"timestamp\":${System.currentTimeMillis()}")
            append("}")
        }
        publish(topic, status, retain = true)
    }

    /**
     * Force republish device status (call after capability changes)
     */
    fun refreshDeviceStatus() {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            publishDeviceStatus()
        }
    }

    /**
     * Publish flow execution result
     *
     * @param flowId The flow ID
     * @param success Whether execution succeeded
     * @param error Error message if failed
     * @param duration Execution duration in milliseconds
     * @param triggeredBy Who triggered the execution (android, scheduler, manual, api)
     * @param executedSteps Number of steps executed
     * @param totalSteps Total steps in flow
     */
    fun publishFlowResult(
        flowId: String,
        success: Boolean,
        error: String? = null,
        duration: Long = 0,
        triggeredBy: String = "android",
        executedSteps: Int = 0,
        totalSteps: Int = 0
    ) {
        val topic = "visual_mapper/$deviceId/flow/$flowId/result"
        val result = buildString {
            append("{")
            append("\"success\":$success,")
            append("\"duration\":$duration,")
            append("\"triggered_by\":\"$triggeredBy\",")
            append("\"executed_steps\":$executedSteps,")
            append("\"total_steps\":$totalSteps,")
            if (error != null) {
                // Escape quotes and special chars in error message
                val escapedError = error.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                append("\"error\":\"$escapedError\",")
            }
            append("\"timestamp\":${System.currentTimeMillis()}")
            append("}")
        }
        publish(topic, result, retain = false)
    }

    /**
     * Publish gesture execution result
     */
    fun publishGestureResult(gestureType: String, success: Boolean, error: String? = null) {
        val topic = "visual_mapper/$deviceId/gesture/result"
        val result = buildString {
            append("{")
            append("\"type\":\"$gestureType\",")
            append("\"success\":$success,")
            if (error != null) {
                append("\"error\":\"$error\",")
            }
            append("\"timestamp\":${System.currentTimeMillis()}")
            append("}")
        }
        publish(topic, result, retain = false)
    }

    /**
     * Publish navigation transition for learning
     *
     * Sends screen transition data to the server for building navigation graphs.
     * The server uses this to learn app navigation patterns for Smart Flow generation.
     *
     * @param payload JSON payload containing transition data (from NavigationLearner)
     */
    fun publishNavigationTransition(payload: String) {
        if (deviceId.isEmpty()) {
            Log.w(TAG, "Cannot publish navigation transition: deviceId not set")
            return
        }

        val topic = "visual_mapper/$deviceId/navigation/learn"
        Log.d(TAG, "Publishing navigation transition to $topic")
        publish(topic, payload, retain = false)
    }

    /**
     * Publish exploration logs for ML training (development mode)
     *
     * Sends exploration experience data (state, action, reward) to the ML training server
     * running on the development laptop. The server uses this data to train Q-learning models
     * that improve exploration efficiency.
     *
     * Phase 3 ML Stability: Queues telemetry when MQTT is offline for later delivery.
     *
     * Topic: visualmapper/exploration/logs
     *
     * @param logsJson JSON array of exploration log entries
     */
    fun publishExplorationLogs(logsJson: String) {
        if (deviceId.isEmpty()) {
            Log.w(TAG, "Cannot publish exploration logs: deviceId not set")
            return
        }

        val topic = "visualmapper/exploration/logs"

        // Phase 3: Queue if offline for later delivery
        if (client == null || !client!!.isConnected) {
            Log.d(TAG, "MQTT offline, queuing exploration logs (${logsJson.length} bytes)")
            scope.launch {
                telemetryQueue.queueExplorationLog(logsJson, topic)
            }
            return
        }

        Log.d(TAG, "Publishing ${logsJson.length} bytes of exploration logs to $topic")
        publish(topic, logsJson, retain = false)
    }

    /**
     * Publish exploration status to MQTT
     *
     * Status values:
     * - "started": Exploration just began
     * - "exploring": Actively exploring (with progress info)
     * - "completed": Exploration finished successfully
     * - "failed": Exploration stopped due to error
     *
     * This allows the Python ML training script to know when
     * exploration is done and move to the next app immediately.
     *
     * Topic: visualmapper/exploration/status/{deviceId}
     */
    fun publishExplorationStatus(
        status: String,
        packageName: String,
        screensExplored: Int = 0,
        elementsExplored: Int = 0,
        queueSize: Int = 0,
        message: String? = null
    ) {
        val topic = "visualmapper/exploration/status/$deviceId"

        val payload = org.json.JSONObject().apply {
            put("status", status)
            put("package", packageName)
            put("device_id", deviceId)
            put("screens", screensExplored)
            put("elements", elementsExplored)
            put("queue_size", queueSize)
            put("timestamp", System.currentTimeMillis())
            message?.let { put("message", it) }
        }.toString()

        Log.i(TAG, "Publishing exploration status: $status for $packageName (screens=$screensExplored, elements=$elementsExplored)")
        publish(topic, payload, retain = false)
    }

    /**
     * Enable/disable ML training mode
     *
     * When enabled, exploration logs will be published to the ML training server.
     * This is a development-only feature for training Q-learning models.
     */
    fun setMlTrainingEnabled(enabled: Boolean) {
        mlTrainingEnabled = enabled
        // Persist to SharedPreferences
        context.getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("ml_training_enabled", enabled)
            .apply()
        Log.i(TAG, "ML Training mode: ${if (enabled) "enabled" else "disabled"} (persisted)")
    }

    /**
     * Initialize ML training mode from SharedPreferences
     */
    fun initMlTrainingFromPrefs() {
        mlTrainingEnabled = context.getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            .getBoolean("ml_training_enabled", false)
        Log.i(TAG, "ML Training mode initialized from prefs: ${if (mlTrainingEnabled) "enabled" else "disabled"}")
    }

    /**
     * Check if ML training mode is enabled
     */
    fun isMlTrainingEnabled(): Boolean = mlTrainingEnabled

    /**
     * Publish a command to a topic
     *
     * Generic method for publishing JSON commands to any topic.
     * Used for ML training commands, etc.
     */
    fun publishCommand(topic: String, payload: String) {
        if (client == null || !client!!.isConnected) {
            Log.w(TAG, "Cannot publish command to $topic: Client not connected")
            return
        }
        publish(topic, payload, retain = false)
    }

    /**
     * Subscribe to Q-table updates from ML training server
     *
     * The ML training server publishes updated Q-values after training.
     * The Android app uses these to improve exploration decisions.
     *
     * Topic: visualmapper/exploration/qtable
     */
    fun subscribeToQTableUpdates(callback: (qTableJson: String) -> Unit) {
        val topic = "visualmapper/exploration/qtable"

        scope.launch {
            try {
                if (client == null || !client!!.isConnected) {
                    Log.w(TAG, "Cannot subscribe to Q-table: Client not connected")
                    return@launch
                }

                client?.subscribe(topic, QOS) { _, message ->
                    val payload = String(message.payload)
                    Log.i(TAG, "Received Q-table update (${payload.length} bytes)")
                    callback(payload)
                }

                Log.i(TAG, "Subscribed to Q-table updates: $topic")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe to Q-table updates", e)
            }
        }
    }

    /**
     * Publish Home Assistant MQTT discovery config
     */
    fun publishDiscovery(sensorId: String, name: String, deviceClass: String? = null, unit: String? = null) {
        val discoveryTopic = "homeassistant/sensor/$deviceId/$sensorId/config"

        val config = buildString {
            append("{")
            append("\"name\":\"$name\",")
            append("\"unique_id\":\"${deviceId}_$sensorId\",")
            append("\"state_topic\":\"visual_mapper/$deviceId/$sensorId/state\",")
            append("\"availability_topic\":\"visual_mapper/$deviceId/availability\",")

            if (!deviceClass.isNullOrEmpty()) {
                append("\"device_class\":\"$deviceClass\",")
            }
            if (!unit.isNullOrEmpty()) {
                append("\"unit_of_measurement\":\"$unit\",")
            }

            append("\"device\":{")
            append("\"identifiers\":[\"visual_mapper_$deviceId\"],")
            append("\"name\":\"Visual Mapper Companion\",")
            append("\"model\":\"Android Companion\",")
            append("\"manufacturer\":\"Visual Mapper\"")
            append("}}")
        }

        publish(discoveryTopic, config, retain = true)
    }

    private fun publish(topic: String, payload: String, retain: Boolean = false) {
        scope.launch {
            try {
                // Validate client is connected before publishing
                if (client == null || !client!!.isConnected) {
                    Log.w(TAG, "Cannot publish to $topic: Client not connected")
                    return@launch
                }

                val message = MqttMessage(payload.toByteArray()).apply {
                    qos = QOS
                    isRetained = retain
                }
                client?.publish(topic, message)
                Log.d(TAG, "Published to $topic: $payload")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish to $topic: ${e.message}", e)
            }
        }
    }

    /**
     * Phase 3: Synchronous publish for flush operations.
     * Returns true if publish succeeded, false otherwise.
     */
    private suspend fun publishSync(topic: String, payload: String): Boolean {
        return try {
            if (client == null || !client!!.isConnected) {
                Log.w(TAG, "Cannot publish sync to $topic: Client not connected")
                return false
            }

            val message = MqttMessage(payload.toByteArray()).apply {
                qos = QOS
                isRetained = false
            }

            // Use suspendCoroutine to wait for publish result
            suspendCoroutine { continuation ->
                try {
                    client?.publish(topic, message, null, object : IMqttActionListener {
                        override fun onSuccess(asyncActionToken: IMqttToken?) {
                            Log.d(TAG, "Published sync to $topic (${payload.length} bytes)")
                            continuation.resume(true)
                        }

                        override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                            Log.w(TAG, "Failed to publish sync to $topic: ${exception?.message}")
                            continuation.resume(false)
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Exception during sync publish to $topic", e)
                    continuation.resume(false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish sync to $topic: ${e.message}", e)
            false
        }
    }

    // =========================================================================
    // Subscriptions
    // =========================================================================

    /**
     * Subscribe to action commands
     */
    private fun subscribeToActions() {
        if (client?.isConnected != true) {
            Log.w(TAG, "Skipping action subscription - client not connected")
            return
        }
        val topic = "visual_mapper/$deviceId/action/+/execute"

        try {
            client?.subscribe(topic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to action commands: $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to actions: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to actions: ${e.message}")
        }
    }

    /**
     * Subscribe to flow execution commands
     */
    private fun subscribeToFlows() {
        if (client?.isConnected != true) {
            Log.w(TAG, "Skipping flow subscription - client not connected")
            return
        }
        val topic = "visual_mapper/$deviceId/flow/+/execute"

        try {
            client?.subscribe(topic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to flow commands: $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to flows: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to flows: ${e.message}")
        }
    }

    /**
     * Subscribe to configuration updates
     */
    private fun subscribeToConfig() {
        if (client?.isConnected != true) {
            Log.w(TAG, "Skipping config subscription - client not connected")
            return
        }
        val topic = "visual_mapper/$deviceId/config"

        try {
            client?.subscribe(topic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to config updates: $topic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to config: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to config: ${e.message}")
        }
    }

    /**
     * Subscribe to exploration commands (for ML training)
     * Topics:
     *   - visual_mapper/{device_id}/explore/start - Start exploration
     *   - visual_mapper/{device_id}/explore/stop - Stop exploration
     */
    private fun subscribeToExploration() {
        if (client?.isConnected != true) {
            Log.w(TAG, "Skipping exploration subscription - client not connected")
            return
        }
        val startTopic = "visual_mapper/$deviceId/explore/start"
        val stopTopic = "visual_mapper/$deviceId/explore/stop"

        try {
            client?.subscribe(startTopic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to exploration start: $startTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to exploration start: ${exception?.message}")
                }
            })

            client?.subscribe(stopTopic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to exploration stop: $stopTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to exploration stop: ${exception?.message}")
                }
            })

            // Also subscribe to ML training server status (auto-stop when server goes offline)
            val mlStatusTopic = "visualmapper/exploration/status"
            client?.subscribe(mlStatusTopic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to ML server status: $mlStatusTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to ML server status: ${exception?.message}")
                }
            })

            // Subscribe to ML training mode command
            val mlTrainingTopic = "visual_mapper/$deviceId/ml_training"
            client?.subscribe(mlTrainingTopic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to ML training mode: $mlTrainingTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to ML training mode: ${exception?.message}")
                }
            })

            // Subscribe to TFLite model updates from training server
            val modelTopic = "visualmapper/exploration/model"
            client?.subscribe(modelTopic, QOS, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.i(TAG, "Subscribed to TFLite model updates: $modelTopic")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to subscribe to model updates: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error subscribing to exploration: ${e.message}")
        }
    }

    // Callback for exploration commands
    var onExploreStart: ((packageName: String, configJson: String) -> Unit)? = null
    var onExploreStop: (() -> Unit)? = null

    private fun handleMessage(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return

        val payload = message.payload.decodeToString()
        Log.d(TAG, "Received message on $topic: $payload")

        when {
            // Action command: visual_mapper/{device_id}/action/{action_id}/execute
            topic.matches(Regex("visual_mapper/$deviceId/action/([^/]+)/execute")) -> {
                val actionRegex = Regex("visual_mapper/$deviceId/action/([^/]+)/execute")
                val match = actionRegex.find(topic)
                if (match != null) {
                    val actionId = match.groupValues[1]
                    Log.i(TAG, "Action command received: $actionId")
                    onActionCommand?.invoke(actionId, payload)
                }
            }

            // Flow execution: visual_mapper/{device_id}/flow/{flow_id}/execute
            topic.matches(Regex("visual_mapper/$deviceId/flow/([^/]+)/execute")) -> {
                val flowRegex = Regex("visual_mapper/$deviceId/flow/([^/]+)/execute")
                val match = flowRegex.find(topic)
                if (match != null) {
                    val flowId = match.groupValues[1]
                    Log.i(TAG, "Flow execution command received: $flowId")
                    onFlowExecute?.invoke(flowId, payload)
                }
            }

            // Configuration update: visual_mapper/{device_id}/config
            topic == "visual_mapper/$deviceId/config" -> {
                Log.i(TAG, "Configuration update received")
                onConfigUpdate?.invoke(payload)
            }

            // ML Training mode: visual_mapper/{device_id}/ml_training
            topic == "visual_mapper/$deviceId/ml_training" -> {
                Log.i(TAG, "ML Training mode command received")
                try {
                    val json = org.json.JSONObject(payload)
                    val enabled = json.optBoolean("enabled", false)
                    setMlTrainingEnabled(enabled)
                    Log.i(TAG, "ML Training mode set to: $enabled via MQTT")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse ML training command: ${e.message}")
                }
            }

            // Exploration start: visual_mapper/{device_id}/explore/start
            topic == "visual_mapper/$deviceId/explore/start" -> {
                Log.i(TAG, "Exploration start command received")
                try {
                    val json = org.json.JSONObject(payload)
                    val packageName = json.optString("package", "")
                    val config = json.optJSONObject("config")?.toString() ?: "{}"
                    if (packageName.isNotEmpty()) {
                        onExploreStart?.invoke(packageName, config)
                    } else {
                        Log.w(TAG, "Exploration start missing package name")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse exploration start: ${e.message}")
                }
            }

            // Exploration stop: visual_mapper/{device_id}/explore/stop
            topic == "visual_mapper/$deviceId/explore/stop" -> {
                Log.i(TAG, "Exploration stop command received")
                onExploreStop?.invoke()
            }

            // ML training server status (auto-stop exploration when server goes offline)
            topic == "visualmapper/exploration/status" -> {
                try {
                    val json = org.json.JSONObject(payload)
                    val status = json.optString("status", "")
                    Log.d(TAG, "ML server status: $status")

                    if (status == "offline" || status == "shutdown") {
                        Log.i(TAG, "ML training server went offline - stopping exploration")
                        onExploreStop?.invoke()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse ML server status: ${e.message}")
                }
            }

            // TFLite model update from training server
            topic == "visualmapper/exploration/model" -> {
                Log.i(TAG, "TFLite model update received (${payload.length} chars)")
                try {
                    val json = org.json.JSONObject(payload)
                    val type = json.optString("type", "")

                    if (type == "model_update") {
                        val modelBase64 = json.getString("model")
                        val version = json.optString("version", System.currentTimeMillis().toString())

                        // Decode base64 model
                        val modelBytes = android.util.Base64.decode(
                            modelBase64,
                            android.util.Base64.DEFAULT
                        )

                        Log.i(TAG, "Decoded TFLite model: ${modelBytes.size} bytes, version=$version")
                        onModelUpdate?.invoke(modelBytes, version)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process model update: ${e.message}")
                }
            }

            else -> {
                Log.d(TAG, "Unhandled topic: $topic")
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun formatJsonValue(value: Any): String {
        return when (value) {
            is String -> "\"$value\""
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> "\"$value\""
        }
    }

    // =========================================================================
    // SSL/TLS Support (Phase 1 Refactor)
    // =========================================================================

    /**
     * Create an SSL socket factory that trusts all certificates.
     *
     * SECURITY NOTE: This is intentionally permissive for local Home Assistant
     * installations that commonly use self-signed certificates. For production
     * deployments over public internet, consider using proper CA-signed certs
     * and standard system trust.
     *
     * The guide suggested SSLContext.getInstance("SSL"), but we use "TLSv1.2"
     * for better security - TLS 1.2 is the minimum recommended version.
     */
    private fun createTrustAllSocketFactory(): SSLSocketFactory {
        // Trust manager that accepts all certificates (for self-signed certs)
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept all client certs (not typically used for MQTT clients)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Accept all server certs (allows self-signed HA certs)
                Log.d(TAG, "Accepting server certificate (trust-all mode)")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        // Use TLS to allow version negotiation with the server
        // This supports TLS 1.2 and 1.3 depending on what the server offers
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        return sslContext.socketFactory
    }

    /**
     * Get user-friendly error message based on exception type and SSL status.
     * Helps users understand what went wrong and how to fix it.
     */
    private fun getSslAwareErrorMessage(
        exception: Throwable?,
        host: String,
        port: Int,
        useSsl: Boolean
    ): String {
        val exMsg = exception?.message?.lowercase() ?: ""
        val cause = exception?.cause?.message?.lowercase() ?: ""
        val fullError = "$exMsg $cause"

        return when {
            // SSL-specific errors
            useSsl && fullError.contains("ssl") && fullError.contains("handshake") ->
                "SSL handshake failed - ensure broker has SSL enabled on port $port"

            useSsl && (fullError.contains("certificate") || fullError.contains("cert")) ->
                "SSL certificate error - broker may not have valid SSL certificates configured"

            useSsl && fullError.contains("unknown ca") ->
                "SSL certificate authority unknown - this is normal for self-signed certs, but connection should still work"

            useSsl && fullError.contains("eof") ->
                "SSL connection closed unexpectedly - check broker SSL configuration"

            useSsl && fullError.contains("protocol") ->
                "SSL protocol mismatch - broker may require different TLS version"

            // Connection timeout with SSL hint
            fullError.contains("timed out") || fullError.contains("timeout") ->
                if (useSsl) {
                    "Connection timeout on port $port.\n\nFor SSL connections:\n• Ensure firewall allows port $port\n• Verify broker has SSL listener on port $port\n• Standard SSL port is 8883"
                } else {
                    "Connection timeout - broker may be unreachable or firewall blocking port $port"
                }

            // Connection refused with SSL hint
            fullError.contains("connection refused") || fullError.contains("refused") ->
                if (useSsl) {
                    "Connection refused on port $port.\n\nFor SSL:\n• Ensure broker has SSL enabled\n• Check SSL listener is on port $port\n• Verify certificates are configured"
                } else {
                    "Connection refused - check broker is running on $host:$port"
                }

            // Network unreachable
            fullError.contains("network") && fullError.contains("unreachable") ->
                "Network unreachable - check device has internet/WiFi connection"

            fullError.contains("host") && fullError.contains("unreachable") ->
                "Host unreachable - verify broker IP address $host is correct"

            // Authentication errors
            fullError.contains("not authorized") || fullError.contains("unauthorized") ->
                "Authentication failed - check MQTT username and password"

            fullError.contains("bad user") || fullError.contains("bad password") ->
                "Invalid credentials - verify username and password"

            // Generic errors with context
            fullError.contains("failed to connect") ->
                "Failed to connect to $host:$port" + if (useSsl) " (SSL enabled)" else ""

            // Fallback
            exception?.message != null -> exception.message!!
            else -> "Connection failed - unknown error"
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}

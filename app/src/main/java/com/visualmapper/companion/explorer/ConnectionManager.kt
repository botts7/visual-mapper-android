package com.visualmapper.companion.explorer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages server connectivity and operation mode for Visual Mapper.
 * Supports auto-detection, server-only, and standalone modes.
 */
class ConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ConnectionManager"
        private const val PREFS_NAME = "connection_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_LEARNING_MODE = "learning_mode"
        private const val KEY_LAST_CONNECTED = "last_connected"
        private const val KEY_CONNECTION_FAILURES = "connection_failures"
        private const val HEALTH_CHECK_TIMEOUT = 5000 // 5 seconds
        private const val MAX_FAILURES_BEFORE_STANDALONE = 3

        @Volatile
        private var instance: ConnectionManager? = null

        fun getInstance(context: Context): ConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Observable server status
    private val _serverStatus = MutableStateFlow(ServerStatus.UNKNOWN)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    // Effective operation mode (calculated based on settings and connectivity)
    private val _effectiveMode = MutableStateFlow(OperationMode.STANDALONE)
    val effectiveMode: StateFlow<OperationMode> = _effectiveMode.asStateFlow()

    // Last server response time for diagnostics
    private var lastResponseTimeMs: Long = 0

    init {
        // Start initial connectivity check
        scope.launch {
            checkServerConnection()
        }
    }

    // ==================== Server URL Management ====================

    /**
     * Get configured server URL
     */
    fun getServerUrl(): String? {
        return prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * Set server URL
     */
    fun setServerUrl(url: String) {
        val normalizedUrl = normalizeUrl(url)
        prefs.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putInt(KEY_CONNECTION_FAILURES, 0) // Reset failure count
            .apply()
        Log.d(TAG, "Server URL set to: $normalizedUrl")

        // Check connectivity with new URL
        scope.launch {
            checkServerConnection()
        }
    }

    /**
     * Normalize URL (ensure http/https, remove trailing slash)
     */
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        return normalized.trimEnd('/')
    }

    // ==================== Connection Mode ====================

    /**
     * Get user-configured connection mode
     */
    fun getConnectionMode(): ConnectionMode {
        val modeStr = prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.AUTO.name)
        return try {
            ConnectionMode.valueOf(modeStr ?: ConnectionMode.AUTO.name)
        } catch (e: Exception) {
            ConnectionMode.AUTO
        }
    }

    /**
     * Set connection mode
     */
    fun setConnectionMode(mode: ConnectionMode) {
        prefs.edit().putString(KEY_CONNECTION_MODE, mode.name).apply()
        Log.d(TAG, "Connection mode set to: $mode")
        updateEffectiveMode()
    }

    /**
     * Get user-configured learning mode
     */
    fun getLearningMode(): LearningMode {
        val modeStr = prefs.getString(KEY_LEARNING_MODE, LearningMode.ON_DEVICE.name)
        return try {
            LearningMode.valueOf(modeStr ?: LearningMode.ON_DEVICE.name)
        } catch (e: Exception) {
            LearningMode.ON_DEVICE
        }
    }

    /**
     * Set learning mode
     */
    fun setLearningMode(mode: LearningMode) {
        prefs.edit().putString(KEY_LEARNING_MODE, mode.name).apply()
        Log.d(TAG, "Learning mode set to: $mode")
    }

    // ==================== Server Connection ====================

    /**
     * Check server connectivity
     */
    suspend fun checkServerConnection(): Boolean {
        val url = getServerUrl()
        if (url.isNullOrBlank()) {
            _serverStatus.value = ServerStatus.NO_URL_CONFIGURED
            updateEffectiveMode()
            return false
        }

        _serverStatus.value = ServerStatus.CHECKING

        return try {
            val startTime = System.currentTimeMillis()
            val connected = performHealthCheck(url)
            lastResponseTimeMs = System.currentTimeMillis() - startTime

            if (connected) {
                _serverStatus.value = ServerStatus.CONNECTED
                prefs.edit()
                    .putLong(KEY_LAST_CONNECTED, System.currentTimeMillis())
                    .putInt(KEY_CONNECTION_FAILURES, 0)
                    .apply()
                Log.d(TAG, "Server connected (${lastResponseTimeMs}ms)")
            } else {
                recordConnectionFailure()
                _serverStatus.value = ServerStatus.DISCONNECTED
                Log.w(TAG, "Server health check failed")
            }

            updateEffectiveMode()
            connected
        } catch (e: Exception) {
            recordConnectionFailure()
            _serverStatus.value = ServerStatus.ERROR
            Log.e(TAG, "Server connection error: ${e.message}")
            updateEffectiveMode()
            false
        }
    }

    /**
     * Perform HTTP health check
     */
    private suspend fun performHealthCheck(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = HEALTH_CHECK_TIMEOUT
            connection.readTimeout = HEALTH_CHECK_TIMEOUT
            connection.setRequestProperty("Accept", "application/json")

            try {
                val responseCode = connection.responseCode
                responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    /**
     * Record a connection failure
     */
    private fun recordConnectionFailure() {
        val failures = prefs.getInt(KEY_CONNECTION_FAILURES, 0) + 1
        prefs.edit().putInt(KEY_CONNECTION_FAILURES, failures).apply()

        if (failures >= MAX_FAILURES_BEFORE_STANDALONE) {
            Log.w(TAG, "Multiple connection failures ($failures), switching to standalone mode")
        }
    }

    /**
     * Get number of consecutive connection failures
     */
    fun getConnectionFailures(): Int {
        return prefs.getInt(KEY_CONNECTION_FAILURES, 0)
    }

    // ==================== Effective Mode Calculation ====================

    /**
     * Update effective operation mode based on settings and connectivity
     */
    private fun updateEffectiveMode() {
        val configuredMode = getConnectionMode()
        val status = _serverStatus.value

        _effectiveMode.value = when (configuredMode) {
            ConnectionMode.SERVER_ONLY -> {
                if (status == ServerStatus.CONNECTED) {
                    OperationMode.SERVER
                } else {
                    OperationMode.SERVER_UNAVAILABLE
                }
            }
            ConnectionMode.STANDALONE -> {
                OperationMode.STANDALONE
            }
            ConnectionMode.AUTO -> {
                when (status) {
                    ServerStatus.CONNECTED -> OperationMode.SERVER
                    ServerStatus.CHECKING -> OperationMode.CHECKING
                    else -> OperationMode.STANDALONE
                }
            }
        }

        Log.d(TAG, "Effective mode: ${_effectiveMode.value} (config: $configuredMode, status: $status)")
    }

    // ==================== Convenience Methods ====================

    /**
     * Check if server is currently available
     */
    fun isServerAvailable(): Boolean {
        return _serverStatus.value == ServerStatus.CONNECTED
    }

    /**
     * Check if we should use server for operations
     */
    fun shouldUseServer(): Boolean {
        return _effectiveMode.value == OperationMode.SERVER
    }

    /**
     * Check if we're in standalone mode
     */
    fun isStandalone(): Boolean {
        return _effectiveMode.value == OperationMode.STANDALONE
    }

    /**
     * Check if learning sync should be attempted
     */
    fun shouldSyncLearning(): Boolean {
        val learningMode = getLearningMode()
        return learningMode == LearningMode.SERVER_SYNC && isServerAvailable()
    }

    /**
     * Check if learning is enabled
     */
    fun isLearningEnabled(): Boolean {
        return getLearningMode() != LearningMode.DISABLED
    }

    /**
     * Get API base URL for server requests
     */
    fun getApiBaseUrl(): String? {
        return getServerUrl()?.let { "$it/api" }
    }

    /**
     * Get last known connection time
     */
    fun getLastConnectedTime(): Long {
        return prefs.getLong(KEY_LAST_CONNECTED, 0)
    }

    /**
     * Get diagnostic info
     */
    fun getDiagnostics(): ConnectionDiagnostics {
        return ConnectionDiagnostics(
            serverUrl = getServerUrl(),
            connectionMode = getConnectionMode(),
            learningMode = getLearningMode(),
            serverStatus = _serverStatus.value,
            effectiveMode = _effectiveMode.value,
            lastConnected = getLastConnectedTime(),
            consecutiveFailures = getConnectionFailures(),
            lastResponseTimeMs = lastResponseTimeMs
        )
    }

    /**
     * Force refresh connection status
     */
    fun refreshConnection() {
        scope.launch {
            checkServerConnection()
        }
    }

    // ==================== MQTT Command Sending ====================

    /**
     * Send a command to the server via MQTT.
     *
     * This uses the MqttManager to publish a command that the server will receive.
     *
     * @param topic MQTT topic to publish to
     * @param payload Map of key-value pairs to send
     * @param callback Called with true if command was sent successfully
     */
    fun sendCommand(topic: String, payload: Map<String, Any>, callback: ((Boolean) -> Unit)? = null) {
        if (!isServerAvailable()) {
            Log.w(TAG, "Cannot send command - server not available")
            callback?.invoke(false)
            return
        }

        try {
            // Convert payload to JSON
            val jsonPayload = org.json.JSONObject(payload).toString()

            // Get MQTT manager from app and publish
            val app = context.applicationContext as? com.visualmapper.companion.VisualMapperApp
            val mqttManager = app?.mqttManager
            if (mqttManager != null &&
                mqttManager.connectionState.value == com.visualmapper.companion.mqtt.MqttManager.ConnectionState.CONNECTED) {
                mqttManager.publishCommand(topic, jsonPayload)
                Log.d(TAG, "Command sent to $topic: $jsonPayload")
                callback?.invoke(true)
            } else {
                Log.w(TAG, "MQTT not connected - cannot send command")
                callback?.invoke(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: ${e.message}")
            callback?.invoke(false)
        }
    }

    /**
     * Send a command and wait for response via MQTT.
     *
     * @param requestTopic Topic to publish request to
     * @param responseTopic Topic to subscribe for response
     * @param payload Request payload
     * @param timeoutMs How long to wait for response
     * @param callback Called with response payload or null on timeout/error
     */
    fun sendCommandWithResponse(
        requestTopic: String,
        responseTopic: String,
        payload: Map<String, Any>,
        timeoutMs: Long = 5000,
        callback: (String?) -> Unit
    ) {
        if (!isServerAvailable()) {
            Log.w(TAG, "Cannot send command - server not available")
            callback(null)
            return
        }

        scope.launch {
            try {
                val app = context.applicationContext as? com.visualmapper.companion.VisualMapperApp
                val mqttManager = app?.mqttManager
                if (mqttManager == null ||
                    mqttManager.connectionState.value != com.visualmapper.companion.mqtt.MqttManager.ConnectionState.CONNECTED) {
                    withContext(Dispatchers.Main) {
                        callback(null)
                    }
                    return@launch
                }

                // Send request
                val jsonPayload = org.json.JSONObject(payload).toString()
                mqttManager.publishCommand(requestTopic, jsonPayload)

                // Wait for response (simplified - in practice would need callback subscription)
                delay(timeoutMs)

                withContext(Dispatchers.Main) {
                    callback(null) // Response handling would need MqttManager subscription support
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendCommandWithResponse: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        scope.cancel()
    }
}

// ==================== Enums and Data Classes ====================

/**
 * User-configurable connection mode
 */
enum class ConnectionMode {
    AUTO,           // Smart detect server availability
    SERVER_ONLY,    // Require server connection
    STANDALONE      // Work entirely offline
}

/**
 * User-configurable learning mode
 */
enum class LearningMode {
    ON_DEVICE,      // All learning stays on device
    SERVER_SYNC,    // Sync learning to server for backup/sharing
    DISABLED        // No learning (privacy mode)
}

/**
 * Current server connection status
 */
enum class ServerStatus {
    UNKNOWN,            // Initial state
    NO_URL_CONFIGURED,  // No server URL set
    CHECKING,           // Currently checking connection
    CONNECTED,          // Server is reachable
    DISCONNECTED,       // Server not responding
    ERROR               // Connection error occurred
}

/**
 * Effective operation mode (computed from settings + connectivity)
 */
enum class OperationMode {
    SERVER,             // Using server for full functionality
    STANDALONE,         // Operating without server
    SERVER_UNAVAILABLE, // Server required but not available
    CHECKING            // Checking server connectivity
}

/**
 * Diagnostic information for troubleshooting
 */
data class ConnectionDiagnostics(
    val serverUrl: String?,
    val connectionMode: ConnectionMode,
    val learningMode: LearningMode,
    val serverStatus: ServerStatus,
    val effectiveMode: OperationMode,
    val lastConnected: Long,
    val consecutiveFailures: Int,
    val lastResponseTimeMs: Long
)

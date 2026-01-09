package com.visualmapper.companion.server

import android.content.Context
import android.util.Log
import com.visualmapper.companion.service.Flow
import com.visualmapper.companion.service.FlowExecutorService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Server Sync Manager
 *
 * Handles communication with Visual Mapper server:
 * - Check server health
 * - Register device
 * - Sync flows
 * - Report execution results
 * - Receive action commands
 */
class ServerSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "ServerSync"
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        MQTT_ONLY,  // HTTP unavailable but MQTT may work
        ERROR
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val httpClient = HttpClient(Android) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            connectTimeout = 10000
            socketTimeout = 30000
        }
    }

    private var serverUrl: String? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _syncedFlows = MutableStateFlow<List<Flow>>(emptyList())
    val syncedFlows: StateFlow<List<Flow>> = _syncedFlows

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    // =========================================================================
    // Connection Management
    // =========================================================================

    /**
     * Check if server is reachable
     */
    suspend fun checkHealth(url: String): Boolean {
        return try {
            val response = httpClient.get("$url/api/health")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed: ${e.message}")
            false
        }
    }

    /**
     * Get stable device ID from server (survives IP/port changes)
     *
     * @param url Server URL
     * @param adbDeviceId ADB device ID (e.g., "192.168.86.2:46747")
     * @return Stable device ID or null if not found
     */
    suspend fun getStableDeviceId(url: String, adbDeviceId: String): String? {
        return try {
            val normalizedUrl = url.trimEnd('/')
            val response = httpClient.get("$normalizedUrl/api/adb/stable-id/$adbDeviceId")
            if (response.status == HttpStatusCode.OK) {
                // Parse JSON manually to avoid Kotlin serialization polymorphic map issues
                val jsonString: String = response.body()
                Log.d(TAG, "Stable ID response: $jsonString")

                // Extract "stable_device_id" value using simple regex
                val regex = "\"stable_device_id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                val match = regex.find(jsonString)
                val stableId = match?.groupValues?.get(1)

                if (stableId != null) {
                    Log.i(TAG, "Extracted stable device ID: $stableId")
                } else {
                    Log.w(TAG, "Could not extract stable_device_id from response")
                }

                stableId
            } else {
                Log.w(TAG, "Stable ID request failed with status: ${response.status}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get stable device ID: ${e.message}", e)
            null
        }
    }

    /**
     * Connect to Visual Mapper server
     *
     * @param url Server URL (e.g., "http://192.168.86.129:8080")
     * @param deviceId Device ID
     * @param mqttOnly If true, skip HTTP health check and allow MQTT-only mode
     * @return True if connected (or MQTT-only mode enabled), false if HTTP connection required but failed
     */
    suspend fun connect(url: String, deviceId: String, mqttOnly: Boolean = false): Boolean {
        _connectionState.value = ConnectionState.CONNECTING
        _lastError.value = null

        try {
            // Normalize URL
            val normalizedUrl = url.trimEnd('/')

            // In MQTT-only mode, skip health check and registration
            if (mqttOnly) {
                Log.i(TAG, "MQTT-only mode: Skipping HTTP server checks")
                serverUrl = normalizedUrl  // Store URL in case HTTP becomes available later
                _connectionState.value = ConnectionState.MQTT_ONLY
                Log.i(TAG, "MQTT-only mode enabled (HTTP features disabled)")
                return true
            }

            // Check health
            if (!checkHealth(normalizedUrl)) {
                _lastError.value = "Server not reachable at $normalizedUrl"
                Log.w(TAG, "HTTP server not reachable")
                serverUrl = normalizedUrl  // Store for potential retry
                _connectionState.value = ConnectionState.ERROR
                return false
            }

            // Register device (optional - don't fail if it doesn't work)
            registerDevice(normalizedUrl, deviceId)

            serverUrl = normalizedUrl
            _connectionState.value = ConnectionState.CONNECTED
            Log.i(TAG, "Connected to server: $normalizedUrl")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _lastError.value = e.message ?: "Connection failed"
            _connectionState.value = ConnectionState.ERROR
            return false
        }
    }

    /**
     * Disconnect from server
     */
    fun disconnect() {
        serverUrl = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _syncedFlows.value = emptyList()
        Log.i(TAG, "Disconnected from server")
    }

    // =========================================================================
    // Device Registration
    // =========================================================================

    /**
     * Register this device with the server
     */
    private suspend fun registerDevice(url: String, deviceId: String): Boolean {
        return try {
            val deviceInfo = DeviceRegistration(
                deviceId = deviceId,
                deviceName = android.os.Build.MODEL,
                platform = "android",
                appVersion = "1.0.0",
                capabilities = listOf("accessibility", "gestures", "mqtt", "flows")
            )

            val response = httpClient.post("$url/api/devices/register") {
                contentType(ContentType.Application.Json)
                setBody(deviceInfo)
            }

            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created) {
                Log.i(TAG, "Device registered: $deviceId")
                true
            } else {
                Log.w(TAG, "Device registration returned: ${response.status}")
                // Still allow connection if registration endpoint doesn't exist
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed (continuing anyway): ${e.message}")
            // Don't fail connection if registration endpoint doesn't exist
            true
        }
    }

    // =========================================================================
    // Flow Sync
    // =========================================================================

    /**
     * Sync flows from server for this device
     * Uses the android-sync endpoint which returns flows with embedded sensor definitions
     * for Android-side CAPTURE_SENSORS execution
     *
     * @param stableDeviceId Android stable device ID (from VisualMapperApp)
     * @param adbDeviceId ADB device ID (IP:port format, e.g., "192.168.86.2:46747")
     */
    suspend fun syncFlows(stableDeviceId: String, adbDeviceId: String? = null): List<Flow> {
        val url = serverUrl ?: return emptyList()

        return try {
            // Use the android-sync endpoint which returns flows with embedded sensors
            Log.i(TAG, "Syncing flows via android-sync endpoint...")
            val response = httpClient.get("$url/api/flows/android-sync") {
                parameter("stable_device_id", stableDeviceId)
                if (adbDeviceId != null) {
                    parameter("adb_device_id", adbDeviceId)
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val flows: List<Flow> = response.body()

                _syncedFlows.value = flows
                // Sync to FlowExecutorService for UI access and execution
                // Also persist to storage for offline execution
                FlowExecutorService.syncFlows(flows, context, stableDeviceId)

                // Log embedded sensor counts for debugging
                var totalEmbeddedSensors = 0
                flows.forEach { flow ->
                    flow.steps.forEach { step ->
                        val embeddedCount = step.embeddedSensors?.size ?: 0
                        if (embeddedCount > 0) {
                            totalEmbeddedSensors += embeddedCount
                        }
                    }
                }

                Log.i(TAG, "Synced ${flows.size} flows with $totalEmbeddedSensors embedded sensors (persisted)")
                flows
            } else {
                Log.w(TAG, "Flow sync returned: ${response.status}")

                // Fallback to legacy endpoint if android-sync not available
                Log.i(TAG, "Falling back to legacy /api/flows endpoint...")
                syncFlowsLegacy(stableDeviceId, adbDeviceId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flow sync failed: ${e.message}", e)
            // Try legacy endpoint on failure
            syncFlowsLegacy(stableDeviceId, adbDeviceId)
        }
    }

    /**
     * Legacy flow sync - uses /api/flows without embedded sensors
     * Flows synced this way won't have embedded sensors, so CAPTURE_SENSORS
     * will fall back to basic text extraction
     */
    private suspend fun syncFlowsLegacy(deviceId: String, adbDeviceId: String?): List<Flow> {
        val url = serverUrl ?: return emptyList()

        return try {
            var response = httpClient.get("$url/api/flows") {
                parameter("device_id", deviceId)
            }

            if (response.status == HttpStatusCode.OK) {
                val flows: List<Flow> = response.body()

                // If no flows found and we have an ADB device ID, try that
                if (flows.isEmpty() && adbDeviceId != null && adbDeviceId != deviceId) {
                    Log.i(TAG, "No flows for stable ID, trying ADB device ID: $adbDeviceId")
                    response = httpClient.get("$url/api/flows") {
                        parameter("device_id", adbDeviceId)
                    }

                    if (response.status == HttpStatusCode.OK) {
                        val adbFlows: List<Flow> = response.body()
                        _syncedFlows.value = adbFlows
                        FlowExecutorService.syncFlows(adbFlows, context, adbDeviceId)
                        Log.i(TAG, "Synced ${adbFlows.size} flows (legacy, ADB device ID, persisted)")
                        return adbFlows
                    }
                }

                _syncedFlows.value = flows
                FlowExecutorService.syncFlows(flows, context, deviceId)
                Log.i(TAG, "Synced ${flows.size} flows (legacy, persisted)")
                flows
            } else {
                Log.w(TAG, "Legacy flow sync returned: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Legacy flow sync failed", e)
            emptyList()
        }
    }

    /**
     * Get a specific flow by ID
     */
    suspend fun getFlow(flowId: String): Flow? {
        val url = serverUrl ?: return null

        return try {
            val response = httpClient.get("$url/api/flows/$flowId")
            if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get flow failed", e)
            null
        }
    }

    // =========================================================================
    // Execution Reporting
    // =========================================================================

    /**
     * Report flow execution result to server
     */
    suspend fun reportExecution(result: FlowExecutionResult): Boolean {
        val url = serverUrl ?: return false

        return try {
            val response = httpClient.post("$url/api/flows/${result.flowId}/executions") {
                contentType(ContentType.Application.Json)
                setBody(result)
            }
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created
        } catch (e: Exception) {
            Log.e(TAG, "Report execution failed", e)
            false
        }
    }

    /**
     * Report sensor value to server
     */
    suspend fun reportSensorValue(
        deviceId: String,
        sensorId: String,
        value: String,
        timestamp: Long = System.currentTimeMillis()
    ): Boolean {
        val url = serverUrl ?: return false

        return try {
            val payload = SensorValueReport(
                deviceId = deviceId,
                sensorId = sensorId,
                value = value,
                timestamp = timestamp
            )

            val response = httpClient.post("$url/api/sensors/report") {
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            Log.e(TAG, "Report sensor value failed", e)
            false
        }
    }

    // =========================================================================
    // Screen/Status APIs
    // =========================================================================

    /**
     * Get current screen info from server (for this device via ADB)
     */
    suspend fun getScreenInfo(deviceId: String): ScreenInfo? {
        val url = serverUrl ?: return null

        return try {
            val response = httpClient.get("$url/api/adb/screen-state/$deviceId")
            if (response.status == HttpStatusCode.OK) {
                response.body()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get screen info failed", e)
            null
        }
    }

    /**
     * Notify server that app is active (heartbeat)
     */
    suspend fun sendHeartbeat(deviceId: String): Boolean {
        val url = serverUrl ?: return false

        return try {
            val response = httpClient.post("$url/api/devices/$deviceId/heartbeat") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("timestamp" to System.currentTimeMillis()))
            }
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            // Heartbeat failures are expected sometimes
            false
        }
    }

    // =========================================================================
    // Flow Pause/Resume (App Lifecycle)
    // =========================================================================

    /**
     * Pause flows on server when app opens.
     * Uses the wizard/active mechanism which:
     * - Pauses the flow scheduler
     * - Cancels any queued flows for this device
     * - Prevents device from being locked during user interaction
     */
    suspend fun pauseFlowsForDevice(deviceId: String, adbDeviceId: String? = null): Boolean {
        val url = serverUrl ?: return false

        return try {
            // Use the wizard/active endpoint which handles device ID resolution
            val targetDeviceId = adbDeviceId ?: deviceId
            Log.i(TAG, "Pausing flows for device: $targetDeviceId (app opened)")

            val response = httpClient.post("$url/api/flows/wizard/active/$targetDeviceId") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("reason" to "companion_app_opened"))
            }

            if (response.status == HttpStatusCode.OK) {
                Log.i(TAG, "Flows paused successfully for device: $targetDeviceId")
                true
            } else {
                Log.w(TAG, "Pause flows returned: ${response.status}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pause flows failed", e)
            false
        }
    }

    /**
     * Resume flows on server when app closes/goes to background.
     * Removes device from wizard active list, allowing scheduler to resume.
     */
    suspend fun resumeFlowsForDevice(deviceId: String, adbDeviceId: String? = null): Boolean {
        val url = serverUrl ?: return false

        return try {
            val targetDeviceId = adbDeviceId ?: deviceId
            Log.i(TAG, "Resuming flows for device: $targetDeviceId (app closed)")

            val response = httpClient.delete("$url/api/flows/wizard/active/$targetDeviceId")

            if (response.status == HttpStatusCode.OK) {
                Log.i(TAG, "Flows resumed successfully for device: $targetDeviceId")
                true
            } else {
                Log.w(TAG, "Resume flows returned: ${response.status}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Resume flows failed", e)
            false
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    fun close() {
        httpClient.close()
    }
}

// =========================================================================
// Data Classes
// =========================================================================

@Serializable
data class DeviceRegistration(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val capabilities: List<String>
)

@Serializable
data class FlowExecutionResult(
    @kotlinx.serialization.SerialName("flow_id") val flowId: String,
    val success: Boolean,
    @kotlinx.serialization.SerialName("executed_steps") val executedSteps: Int,
    @kotlinx.serialization.SerialName("failed_step") val failedStep: Int? = null,
    @kotlinx.serialization.SerialName("error_message") val errorMessage: String? = null,
    @kotlinx.serialization.SerialName("captured_sensors") val capturedSensors: Map<String, String> = emptyMap(),
    @kotlinx.serialization.SerialName("execution_time_ms") val executionTimeMs: Int,
    val timestamp: String? = null // ISO datetime string from server
)

@Serializable
data class SensorValueReport(
    val deviceId: String,
    val sensorId: String,
    val value: String,
    val timestamp: Long
)

@Serializable
data class ScreenInfo(
    val isOn: Boolean,
    val isLocked: Boolean,
    val currentApp: String? = null,
    val currentActivity: String? = null
)

package com.visualmapper.companion.explorer

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.mqtt.MqttManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles MQTT publishing for exploration data.
 *
 * Responsibilities:
 * - Publishing exploration logs to ML training server
 * - Publishing exploration status updates
 * - Subscribing to Q-table updates from server
 * - Publishing generated flows
 *
 * Extracted from AppExplorerService for modularity.
 */
class ExplorationPublisher(private val context: Context) {

    companion object {
        private const val TAG = "ExplorationPublisher"
        private const val PUBLISH_EVERY_N_UPDATES = 10
    }

    private var qUpdatesSinceLastPublish = 0

    /**
     * Get the MqttManager from the application context.
     */
    private fun getMqttManager(): MqttManager? {
        val app = context.applicationContext as? VisualMapperApp
        return app?.mqttManager
    }

    /**
     * Get the stable device ID from the application.
     */
    private fun getDeviceId(): String {
        return try {
            val app = context.applicationContext as? VisualMapperApp
            app?.stableDeviceId ?: android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown_device"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device ID: ${e.message}")
            "unknown_device"
        }
    }

    /**
     * Publish exploration logs to the ML training server via MQTT.
     *
     * @param qLearning The Q-learning instance to get logs from
     */
    fun publishExplorationLogs(qLearning: ExplorationQLearning?) {
        try {
            val mqttManager = getMqttManager()
            if (mqttManager == null) {
                Log.w(TAG, "Cannot publish exploration logs: MqttManager not available")
                return
            }

            // Check if ML training mode is enabled
            if (!mqttManager.isMlTrainingEnabled()) {
                Log.d(TAG, "ML Training mode disabled - skipping log publish")
                return
            }

            // Check if MQTT is connected
            if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) {
                Log.w(TAG, "MQTT not connected - cannot publish exploration logs")
                return
            }

            // Get exploration logs from Q-learning
            val logs = qLearning?.getExplorationLog() ?: emptyList()
            if (logs.isEmpty()) {
                Log.d(TAG, "No exploration logs to publish")
                return
            }

            // Convert to JSON
            val jsonArray = JSONArray()
            val deviceId = getDeviceId()
            for (log in logs) {
                val jsonObj = JSONObject().apply {
                    put("screenHash", log.screenHash)
                    put("actionKey", log.actionKey)
                    put("reward", log.reward)
                    put("nextScreenHash", log.nextScreenHash ?: JSONObject.NULL)
                    put("timestamp", log.timestamp)
                    put("deviceId", deviceId)
                }
                jsonArray.put(jsonObj)
            }

            // Publish
            val logsJson = jsonArray.toString()
            mqttManager.publishExplorationLogs(logsJson)
            Log.i(TAG, "Published ${logs.size} exploration logs to ML training server (${logsJson.length} bytes)")

            // Clear logs after successful publish
            qLearning?.clearExplorationLog()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish exploration logs to MQTT", e)
        }
    }

    /**
     * Called after each Q-learning update to periodically publish logs to server.
     * This ensures data reaches the server even if exploration gets stuck or crashes.
     *
     * @param qLearning The Q-learning instance to get logs from
     */
    fun checkPeriodicPublish(qLearning: ExplorationQLearning?) {
        qUpdatesSinceLastPublish++
        if (qUpdatesSinceLastPublish >= PUBLISH_EVERY_N_UPDATES) {
            Log.i(TAG, "Periodic publish: $qUpdatesSinceLastPublish Q-updates since last publish")
            publishExplorationLogs(qLearning)
            qUpdatesSinceLastPublish = 0
        }
    }

    /**
     * Reset the periodic publish counter (e.g., when starting new exploration).
     */
    fun resetPeriodicCounter() {
        qUpdatesSinceLastPublish = 0
    }

    /**
     * Subscribe to Q-table updates from ML training server.
     * This allows real-time learning feedback during exploration.
     *
     * @param qLearning The Q-learning instance to merge updates into
     * @param showToast Callback to show toast messages
     */
    fun subscribeToQTableUpdates(
        qLearning: ExplorationQLearning?,
        showToast: (String) -> Unit
    ) {
        try {
            val mqttManager = getMqttManager()
            if (mqttManager == null) {
                Log.w(TAG, "Cannot subscribe to Q-table updates: MqttManager not available")
                return
            }

            // Only subscribe if ML training is enabled and MQTT is connected
            if (!mqttManager.isMlTrainingEnabled()) {
                Log.d(TAG, "ML Training mode disabled - skipping Q-table subscription")
                return
            }

            if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) {
                Log.w(TAG, "MQTT not connected - cannot subscribe to Q-table updates")
                return
            }

            // Subscribe to Q-table updates
            mqttManager.subscribeToQTableUpdates { qTableJson ->
                Log.i(TAG, "Received Q-table update from ML server (${qTableJson.length} bytes)")

                // Merge server Q-table with local Q-table
                val mergedCount = qLearning?.mergeServerQTable(qTableJson) ?: 0
                Log.i(TAG, "Merged $mergedCount Q-values from server into local Q-table")

                // Show feedback
                if (mergedCount > 0) {
                    showToast("ML Update: $mergedCount Q-values learned")
                }
            }

            Log.i(TAG, "Subscribed to Q-table updates from ML training server")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to Q-table updates", e)
        }
    }

    /**
     * Publish exploration status to MQTT for Python script coordination.
     *
     * Status values:
     * - "started": Exploration just began
     * - "exploring": Actively exploring (with progress info)
     * - "completed": Exploration finished successfully
     * - "failed": Exploration stopped due to error
     *
     * @param status The status string
     * @param packageName The app being explored
     * @param screensExplored Number of screens explored
     * @param elementsExplored Number of elements explored
     * @param queueSize Current queue size
     * @param message Optional message
     */
    fun publishStatus(
        status: String,
        packageName: String,
        screensExplored: Int,
        elementsExplored: Int,
        queueSize: Int,
        message: String? = null
    ) {
        try {
            val mqttManager = getMqttManager() ?: return

            // Only publish if ML training is enabled and MQTT is connected
            if (!mqttManager.isMlTrainingEnabled()) return
            if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) return

            mqttManager.publishExplorationStatus(
                status = status,
                packageName = packageName,
                screensExplored = screensExplored,
                elementsExplored = elementsExplored,
                queueSize = queueSize,
                message = message
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to publish exploration status: ${e.message}")
        }
    }

    /**
     * Publish generated flow to MQTT for the server to save.
     *
     * @param flowJson The flow JSON string
     */
    fun publishGeneratedFlow(flowJson: String) {
        try {
            val mqttManager = getMqttManager()
            if (mqttManager == null) {
                Log.w(TAG, "Cannot publish flow: MqttManager not available")
                return
            }

            if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) {
                Log.w(TAG, "Cannot publish flow: MQTT not connected")
                return
            }

            val topic = "visualmapper/flows/generated"
            mqttManager.publishCommand(topic, flowJson)
            Log.i(TAG, "Published generated flow to $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish generated flow", e)
        }
    }

    /**
     * Get the device ID for flow generation.
     */
    fun getDeviceIdForFlows(): String = getDeviceId()
}

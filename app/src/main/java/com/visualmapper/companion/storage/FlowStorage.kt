package com.visualmapper.companion.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.visualmapper.companion.service.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Flow Storage - Persists flows to SharedPreferences for offline execution
 *
 * Features:
 * - Save synced flows to local storage
 * - Load flows on app start (before server sync)
 * - Survives app restarts and server downtime
 * - Automatic cleanup on fresh sync
 */
class FlowStorage(context: Context) {

    companion object {
        private const val TAG = "FlowStorage"
        private const val PREFS_NAME = "visual_mapper_flows"
        private const val KEY_FLOWS = "cached_flows"
        private const val KEY_LAST_SYNC = "last_sync_timestamp"
        private const val KEY_DEVICE_ID = "synced_device_id"

        // Singleton for easy access
        private var instance: FlowStorage? = null

        fun getInstance(context: Context): FlowStorage {
            return instance ?: FlowStorage(context.applicationContext).also { instance = it }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    /**
     * Save flows to local storage
     * Called after successful server sync
     */
    fun saveFlows(flows: List<Flow>, deviceId: String) {
        try {
            val flowsJson = json.encodeToString(flows)
            prefs.edit()
                .putString(KEY_FLOWS, flowsJson)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(KEY_DEVICE_ID, deviceId)
                .apply()

            Log.i(TAG, "Saved ${flows.size} flows to local storage")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save flows: ${e.message}", e)
        }
    }

    /**
     * Load flows from local storage
     * Returns empty list if no flows cached or on error
     */
    fun loadFlows(): List<Flow> {
        return try {
            val flowsJson = prefs.getString(KEY_FLOWS, null)
            if (flowsJson.isNullOrBlank()) {
                Log.d(TAG, "No cached flows found")
                return emptyList()
            }

            val flows = json.decodeFromString<List<Flow>>(flowsJson)
            val lastSync = getLastSyncTime()
            Log.i(TAG, "Loaded ${flows.size} flows from storage (synced: ${formatTime(lastSync)})")
            flows
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load flows: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Clear all cached flows
     */
    fun clearFlows() {
        prefs.edit()
            .remove(KEY_FLOWS)
            .remove(KEY_LAST_SYNC)
            .remove(KEY_DEVICE_ID)
            .apply()
        Log.i(TAG, "Cleared cached flows")
    }

    /**
     * Get last sync timestamp (millis)
     */
    fun getLastSyncTime(): Long {
        return prefs.getLong(KEY_LAST_SYNC, 0)
    }

    /**
     * Get device ID that flows were synced for
     */
    fun getSyncedDeviceId(): String? {
        return prefs.getString(KEY_DEVICE_ID, null)
    }

    /**
     * Check if we have cached flows
     */
    fun hasFlows(): Boolean {
        return !prefs.getString(KEY_FLOWS, null).isNullOrBlank()
    }

    /**
     * Get flow count without loading all flows
     */
    fun getFlowCount(): Int {
        return try {
            val flowsJson = prefs.getString(KEY_FLOWS, null)
            if (flowsJson.isNullOrBlank()) return 0

            // Quick count by counting "flow_id" occurrences
            // Not exact but faster than full deserialization
            "\"flow_id\"".toRegex().findAll(flowsJson).count()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Get a specific flow by ID from cache
     */
    fun getFlow(flowId: String): Flow? {
        return loadFlows().find { it.id == flowId }
    }

    /**
     * Add or update a single flow in storage
     * Used for locally created flows that need to persist
     */
    fun addFlow(flow: Flow, deviceId: String) {
        try {
            val existingFlows = loadFlows().toMutableList()

            // Remove existing flow with same ID
            existingFlows.removeAll { it.id == flow.id }

            // Add the new flow
            existingFlows.add(flow)

            // Save all flows
            saveFlows(existingFlows, deviceId)

            Log.i(TAG, "Added flow '${flow.name}' to storage (total: ${existingFlows.size})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add flow: ${e.message}", e)
        }
    }

    /**
     * Remove a flow from storage
     */
    fun removeFlow(flowId: String) {
        try {
            val existingFlows = loadFlows().toMutableList()
            val removed = existingFlows.removeAll { it.id == flowId }

            if (removed) {
                val deviceId = getSyncedDeviceId() ?: "unknown"
                saveFlows(existingFlows, deviceId)
                Log.i(TAG, "Removed flow $flowId from storage (remaining: ${existingFlows.size})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove flow: ${e.message}", e)
        }
    }

    /**
     * Check if cached flows are stale (older than threshold)
     * Default: 24 hours
     */
    fun isStale(maxAgeMs: Long = 24 * 60 * 60 * 1000): Boolean {
        val lastSync = getLastSyncTime()
        if (lastSync == 0L) return true
        return System.currentTimeMillis() - lastSync > maxAgeMs
    }

    /**
     * Get human-readable cache status
     */
    fun getStatus(): String {
        val count = getFlowCount()
        val lastSync = getLastSyncTime()
        val deviceId = getSyncedDeviceId()

        return if (count == 0) {
            "No cached flows"
        } else {
            "$count flows cached (${formatTime(lastSync)}) for $deviceId"
        }
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp == 0L) return "never"
        val ago = System.currentTimeMillis() - timestamp
        return when {
            ago < 60_000 -> "just now"
            ago < 3600_000 -> "${ago / 60_000}m ago"
            ago < 86400_000 -> "${ago / 3600_000}h ago"
            else -> "${ago / 86400_000}d ago"
        }
    }
}

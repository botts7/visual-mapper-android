package com.visualmapper.companion.explorer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit log for exploration actions.
 * Records all actions performed by the explorer for transparency and debugging.
 *
 * Features:
 * - Per-session and persistent logging
 * - Automatic log rotation
 * - Export to JSON
 * - Filtering by app, action type, or time range
 */
class ExplorationAuditLog(private val context: Context) {

    companion object {
        private const val TAG = "ExplorationAuditLog"
        private const val LOG_FILE_NAME = "exploration_audit.json"
        private const val MAX_ENTRIES = 1000
        private const val EXPORT_DATE_FORMAT = "yyyy-MM-dd HH:mm:ss"
    }

    private val logFile: File = File(context.filesDir, LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat(EXPORT_DATE_FORMAT, Locale.US)

    // In-memory cache of recent entries
    private val recentEntries = mutableListOf<AuditEntry>()
    private var entriesLoaded = false

    /**
     * Represents a single audit log entry.
     */
    data class AuditEntry(
        val timestamp: Long,
        val action: String,
        val targetApp: String,
        val targetElement: String?,
        val screenId: String?,
        val accessLevel: ExplorationAccessLevel,
        val wasBlocked: Boolean,
        val blockReason: String?,
        val goModeActive: Boolean,
        val sessionId: String,
        val success: Boolean,
        val metadata: Map<String, String> = emptyMap()
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("timestamp", timestamp)
                put("action", action)
                put("targetApp", targetApp)
                put("targetElement", targetElement ?: "")
                put("screenId", screenId ?: "")
                put("accessLevel", accessLevel.name)
                put("wasBlocked", wasBlocked)
                put("blockReason", blockReason ?: "")
                put("goModeActive", goModeActive)
                put("sessionId", sessionId)
                put("success", success)
                if (metadata.isNotEmpty()) {
                    put("metadata", JSONObject(metadata))
                }
            }
        }

        companion object {
            fun fromJson(json: JSONObject): AuditEntry {
                val metadataJson = json.optJSONObject("metadata")
                val metadata = if (metadataJson != null) {
                    val map = mutableMapOf<String, String>()
                    metadataJson.keys().forEach { key ->
                        map[key] = metadataJson.optString(key, "")
                    }
                    map
                } else emptyMap()

                return AuditEntry(
                    timestamp = json.optLong("timestamp"),
                    action = json.optString("action"),
                    targetApp = json.optString("targetApp"),
                    targetElement = json.optString("targetElement").ifEmpty { null },
                    screenId = json.optString("screenId").ifEmpty { null },
                    accessLevel = try {
                        ExplorationAccessLevel.valueOf(json.optString("accessLevel"))
                    } catch (e: Exception) {
                        ExplorationAccessLevel.STANDARD
                    },
                    wasBlocked = json.optBoolean("wasBlocked"),
                    blockReason = json.optString("blockReason").ifEmpty { null },
                    goModeActive = json.optBoolean("goModeActive"),
                    sessionId = json.optString("sessionId"),
                    success = json.optBoolean("success"),
                    metadata = metadata
                )
            }
        }
    }

    // Current session ID
    private var currentSessionId: String = generateSessionId()

    private fun generateSessionId(): String {
        return "session_${System.currentTimeMillis()}"
    }

    /**
     * Start a new logging session.
     */
    fun startNewSession(): String {
        currentSessionId = generateSessionId()
        Log.i(TAG, "Started new audit session: $currentSessionId")
        return currentSessionId
    }

    /**
     * Log an exploration action.
     */
    fun logAction(
        action: ExplorationAction,
        targetApp: String,
        targetElement: String? = null,
        screenId: String? = null,
        accessLevel: ExplorationAccessLevel,
        wasBlocked: Boolean = false,
        blockReason: String? = null,
        goModeActive: Boolean = false,
        success: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            action = action.actionName,
            targetApp = targetApp,
            targetElement = targetElement,
            screenId = screenId,
            accessLevel = accessLevel,
            wasBlocked = wasBlocked,
            blockReason = blockReason,
            goModeActive = goModeActive,
            sessionId = currentSessionId,
            success = success,
            metadata = metadata
        )

        logEntry(entry)
    }

    /**
     * Log a custom action (not from ExplorationAction enum).
     */
    fun logCustomAction(
        actionName: String,
        targetApp: String,
        targetElement: String? = null,
        screenId: String? = null,
        accessLevel: ExplorationAccessLevel,
        wasBlocked: Boolean = false,
        blockReason: String? = null,
        goModeActive: Boolean = false,
        success: Boolean = true,
        metadata: Map<String, String> = emptyMap()
    ) {
        val entry = AuditEntry(
            timestamp = System.currentTimeMillis(),
            action = actionName,
            targetApp = targetApp,
            targetElement = targetElement,
            screenId = screenId,
            accessLevel = accessLevel,
            wasBlocked = wasBlocked,
            blockReason = blockReason,
            goModeActive = goModeActive,
            sessionId = currentSessionId,
            success = success,
            metadata = metadata
        )

        logEntry(entry)
    }

    private fun logEntry(entry: AuditEntry) {
        synchronized(recentEntries) {
            recentEntries.add(entry)

            // Trim if too many entries
            if (recentEntries.size > MAX_ENTRIES) {
                recentEntries.removeAt(0)
            }
        }

        // Log to Android log
        val status = if (entry.wasBlocked) "BLOCKED" else if (entry.success) "OK" else "FAILED"
        Log.d(TAG, "[AUDIT] ${entry.action} on ${entry.targetApp} - $status" +
                (if (entry.goModeActive) " [GO MODE]" else ""))
    }

    /**
     * Get recent entries.
     */
    fun getRecentEntries(limit: Int = 50): List<AuditEntry> {
        synchronized(recentEntries) {
            return recentEntries.takeLast(limit)
        }
    }

    /**
     * Get entries for a specific app.
     */
    fun getEntriesForApp(packageName: String, limit: Int = 100): List<AuditEntry> {
        synchronized(recentEntries) {
            return recentEntries
                .filter { it.targetApp == packageName }
                .takeLast(limit)
        }
    }

    /**
     * Get entries for the current session.
     */
    fun getCurrentSessionEntries(): List<AuditEntry> {
        synchronized(recentEntries) {
            return recentEntries.filter { it.sessionId == currentSessionId }
        }
    }

    /**
     * Get blocked actions.
     */
    fun getBlockedActions(limit: Int = 50): List<AuditEntry> {
        synchronized(recentEntries) {
            return recentEntries
                .filter { it.wasBlocked }
                .takeLast(limit)
        }
    }

    /**
     * Get Go Mode actions.
     */
    fun getGoModeActions(limit: Int = 50): List<AuditEntry> {
        synchronized(recentEntries) {
            return recentEntries
                .filter { it.goModeActive }
                .takeLast(limit)
        }
    }

    /**
     * Get statistics for the current session.
     */
    fun getSessionStats(): SessionStats {
        synchronized(recentEntries) {
            val sessionEntries = recentEntries.filter { it.sessionId == currentSessionId }
            return SessionStats(
                sessionId = currentSessionId,
                totalActions = sessionEntries.size,
                successfulActions = sessionEntries.count { it.success && !it.wasBlocked },
                blockedActions = sessionEntries.count { it.wasBlocked },
                failedActions = sessionEntries.count { !it.success && !it.wasBlocked },
                goModeActions = sessionEntries.count { it.goModeActive },
                uniqueApps = sessionEntries.map { it.targetApp }.distinct().size,
                startTime = sessionEntries.minOfOrNull { it.timestamp } ?: 0,
                endTime = sessionEntries.maxOfOrNull { it.timestamp } ?: 0
            )
        }
    }

    data class SessionStats(
        val sessionId: String,
        val totalActions: Int,
        val successfulActions: Int,
        val blockedActions: Int,
        val failedActions: Int,
        val goModeActions: Int,
        val uniqueApps: Int,
        val startTime: Long,
        val endTime: Long
    ) {
        val durationMs: Long get() = if (endTime > startTime) endTime - startTime else 0
    }

    /**
     * Export log to JSON string.
     */
    fun exportToJson(): String {
        synchronized(recentEntries) {
            val jsonArray = JSONArray()
            recentEntries.forEach { entry ->
                jsonArray.put(entry.toJson())
            }
            return jsonArray.toString(2)
        }
    }

    /**
     * Export log formatted for display.
     */
    fun exportFormatted(): String {
        synchronized(recentEntries) {
            val sb = StringBuilder()
            sb.appendLine("=== Exploration Audit Log ===")
            sb.appendLine("Exported: ${dateFormat.format(Date())}")
            sb.appendLine("Entries: ${recentEntries.size}")
            sb.appendLine()

            recentEntries.forEach { entry ->
                val time = dateFormat.format(Date(entry.timestamp))
                val status = when {
                    entry.wasBlocked -> "[BLOCKED]"
                    entry.success -> "[OK]"
                    else -> "[FAILED]"
                }
                val goMode = if (entry.goModeActive) " [GO MODE]" else ""

                sb.appendLine("$time | ${entry.action} | ${entry.targetApp} | $status$goMode")
                if (entry.targetElement != null) {
                    sb.appendLine("         Element: ${entry.targetElement}")
                }
                if (entry.blockReason != null) {
                    sb.appendLine("         Reason: ${entry.blockReason}")
                }
            }

            return sb.toString()
        }
    }

    /**
     * Save log to file.
     */
    suspend fun saveToFile() = withContext(Dispatchers.IO) {
        try {
            val json = exportToJson()
            logFile.writeText(json)
            Log.i(TAG, "Audit log saved to file (${recentEntries.size} entries)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving audit log", e)
        }
    }

    /**
     * Load log from file.
     */
    suspend fun loadFromFile() = withContext(Dispatchers.IO) {
        if (!logFile.exists()) {
            entriesLoaded = true
            return@withContext
        }

        try {
            val json = logFile.readText()
            val jsonArray = JSONArray(json)

            synchronized(recentEntries) {
                recentEntries.clear()
                for (i in 0 until jsonArray.length()) {
                    val entry = AuditEntry.fromJson(jsonArray.getJSONObject(i))
                    recentEntries.add(entry)
                }
            }

            Log.i(TAG, "Loaded ${recentEntries.size} entries from audit log file")
            entriesLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audit log", e)
            entriesLoaded = true
        }
    }

    /**
     * Clear all entries.
     */
    fun clearAll() {
        synchronized(recentEntries) {
            recentEntries.clear()
        }
        try {
            if (logFile.exists()) {
                logFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audit log file", e)
        }
        Log.i(TAG, "Audit log cleared")
    }

    /**
     * Get entry count.
     */
    fun getEntryCount(): Int {
        synchronized(recentEntries) {
            return recentEntries.size
        }
    }
}

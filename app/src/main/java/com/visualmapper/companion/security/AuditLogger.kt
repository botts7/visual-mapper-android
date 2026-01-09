package com.visualmapper.companion.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audit Logger
 *
 * Provides transparent logging of all data access operations.
 * Users can review exactly what data was accessed and when.
 *
 * Features:
 * - Log all UI read operations
 * - Log consent changes
 * - Log data transmissions (MQTT publishes)
 * - Log sensitive data blocks
 * - Export logs for user review
 * - Auto-cleanup based on retention policy
 *
 * Privacy-first:
 * - Logs NEVER contain actual sensitive data
 * - Only metadata about access (app, time, element count)
 * - User can disable logging
 * - User can delete all logs
 */
class AuditLogger(private val context: Context) {

    companion object {
        private const val TAG = "AuditLogger"
        private const val LOG_FILE = "audit_log.json"
        private const val MAX_ENTRIES = 10000 // Keep last 10k entries
    }

    @Serializable
    data class AuditEntry(
        val timestamp: Long,
        val eventType: String,
        val packageName: String?,
        val action: String,
        val details: String,
        val elementCount: Int = 0,
        val sensitiveBlocked: Int = 0,
        val consentLevel: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val _entries = MutableStateFlow<List<AuditEntry>>(emptyList())
    val entries: StateFlow<List<AuditEntry>> = _entries

    private var isEnabled = true
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    init {
        loadEntries()
    }

    // =========================================================================
    // Logging Methods
    // =========================================================================

    /**
     * Log a UI read operation
     */
    fun logUIRead(
        packageName: String,
        elementCount: Int,
        sensitiveBlocked: Int,
        consentLevel: String
    ) {
        if (!isEnabled) return

        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "UI_READ",
            packageName = packageName,
            action = "READ",
            details = "Read $elementCount UI elements",
            elementCount = elementCount,
            sensitiveBlocked = sensitiveBlocked,
            consentLevel = consentLevel
        ))
    }

    /**
     * Log a sensitive data detection and block
     */
    fun logSensitiveBlock(
        packageName: String,
        category: String,
        reason: String
    ) {
        if (!isEnabled) return

        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "SENSITIVE_BLOCKED",
            packageName = packageName,
            action = "BLOCKED",
            details = "Blocked $category: $reason"
        ))
    }

    /**
     * Log a consent change
     */
    fun logConsentChange(
        packageName: String,
        action: String, // GRANTED, REVOKED, UPDATED
        level: String,
        details: String
    ) {
        // Always log consent changes, even if logging disabled
        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "CONSENT_CHANGE",
            packageName = packageName,
            action = action,
            details = details,
            consentLevel = level
        ))
    }

    /**
     * Log MQTT data transmission
     */
    fun logDataTransmit(
        packageName: String?,
        topic: String,
        dataType: String,
        elementCount: Int
    ) {
        if (!isEnabled) return

        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "DATA_TRANSMIT",
            packageName = packageName,
            action = "MQTT_PUBLISH",
            details = "Published to $topic: $dataType",
            elementCount = elementCount
        ))
    }

    /**
     * Log a gesture action
     */
    fun logGesture(
        packageName: String?,
        gestureType: String,
        x: Float,
        y: Float
    ) {
        if (!isEnabled) return

        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "GESTURE",
            packageName = packageName,
            action = gestureType,
            details = "Performed at ($x, $y)"
        ))
    }

    /**
     * Log access denied (no consent)
     */
    fun logAccessDenied(
        packageName: String,
        reason: String
    ) {
        if (!isEnabled) return

        addEntry(AuditEntry(
            timestamp = System.currentTimeMillis(),
            eventType = "ACCESS_DENIED",
            packageName = packageName,
            action = "DENIED",
            details = reason
        ))
    }

    // =========================================================================
    // Entry Management
    // =========================================================================

    private fun addEntry(entry: AuditEntry) {
        val updated = (_entries.value + entry).takeLast(MAX_ENTRIES)
        _entries.value = updated

        // Log to Android logcat as well (without sensitive details)
        Log.d(TAG, "[${entry.eventType}] ${entry.packageName ?: "system"}: ${entry.action}")

        // Persist asynchronously
        scope.launch {
            saveEntries()
        }
    }

    private fun loadEntries() {
        try {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) {
                val content = file.readText()
                val list: List<AuditEntry> = json.decodeFromString(content)
                _entries.value = list.takeLast(MAX_ENTRIES)
                Log.d(TAG, "Loaded ${_entries.value.size} audit entries")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load audit log", e)
        }
    }

    private fun saveEntries() {
        try {
            val file = File(context.filesDir, LOG_FILE)
            val content = json.encodeToString(_entries.value)
            file.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audit log", e)
        }
    }

    // =========================================================================
    // User Controls
    // =========================================================================

    /**
     * Enable or disable logging
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        Log.i(TAG, "Audit logging ${if (enabled) "enabled" else "disabled"}")
    }

    fun isLoggingEnabled(): Boolean = isEnabled

    /**
     * Clear all audit logs
     */
    fun clearLogs() {
        _entries.value = emptyList()
        scope.launch {
            val file = File(context.filesDir, LOG_FILE)
            if (file.exists()) {
                file.delete()
            }
        }
        Log.i(TAG, "Audit logs cleared")
    }

    /**
     * Apply retention policy - delete entries older than X days
     */
    fun applyRetention(retentionDays: Int) {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        val filtered = _entries.value.filter { it.timestamp >= cutoff }
        val removed = _entries.value.size - filtered.size

        if (removed > 0) {
            _entries.value = filtered
            scope.launch { saveEntries() }
            Log.i(TAG, "Removed $removed audit entries older than $retentionDays days")
        }
    }

    // =========================================================================
    // Export & Reporting
    // =========================================================================

    /**
     * Export human-readable audit report
     */
    fun exportReport(): String {
        val report = StringBuilder()
        report.appendLine("╔════════════════════════════════════════════════════════════╗")
        report.appendLine("║          VISUAL MAPPER AUDIT REPORT                        ║")
        report.appendLine("╠════════════════════════════════════════════════════════════╣")
        report.appendLine("║ Generated: ${dateFormat.format(Date())}")
        report.appendLine("║ Total Entries: ${_entries.value.size}")
        report.appendLine("╚════════════════════════════════════════════════════════════╝")
        report.appendLine()

        // Summary statistics
        val byType = _entries.value.groupBy { it.eventType }
        report.appendLine("=== SUMMARY ===")
        byType.forEach { (type, entries) ->
            report.appendLine("  $type: ${entries.size} events")
        }
        report.appendLine()

        // Sensitive data blocks
        val blocks = _entries.value.filter { it.eventType == "SENSITIVE_BLOCKED" }
        if (blocks.isNotEmpty()) {
            report.appendLine("=== SENSITIVE DATA BLOCKS (${blocks.size}) ===")
            blocks.takeLast(10).forEach { entry ->
                report.appendLine("  [${dateFormat.format(Date(entry.timestamp))}]")
                report.appendLine("    App: ${entry.packageName}")
                report.appendLine("    Reason: ${entry.details}")
            }
            report.appendLine()
        }

        // Consent changes
        val consents = _entries.value.filter { it.eventType == "CONSENT_CHANGE" }
        if (consents.isNotEmpty()) {
            report.appendLine("=== CONSENT HISTORY (${consents.size}) ===")
            consents.forEach { entry ->
                report.appendLine("  [${dateFormat.format(Date(entry.timestamp))}]")
                report.appendLine("    App: ${entry.packageName}")
                report.appendLine("    Action: ${entry.action} (Level: ${entry.consentLevel})")
                report.appendLine("    Details: ${entry.details}")
            }
            report.appendLine()
        }

        // Recent activity (last 50 entries)
        report.appendLine("=== RECENT ACTIVITY (Last 50) ===")
        _entries.value.takeLast(50).reversed().forEach { entry ->
            report.appendLine("[${dateFormat.format(Date(entry.timestamp))}] ${entry.eventType}")
            report.appendLine("  App: ${entry.packageName ?: "system"}")
            report.appendLine("  Action: ${entry.action}")
            if (entry.elementCount > 0) {
                report.appendLine("  Elements: ${entry.elementCount}, Blocked: ${entry.sensitiveBlocked}")
            }
            report.appendLine("  Details: ${entry.details}")
            report.appendLine()
        }

        return report.toString()
    }

    /**
     * Get entries for a specific app
     */
    fun getEntriesForApp(packageName: String): List<AuditEntry> {
        return _entries.value.filter { it.packageName == packageName }
    }

    /**
     * Get entries by type
     */
    fun getEntriesByType(eventType: String): List<AuditEntry> {
        return _entries.value.filter { it.eventType == eventType }
    }

    /**
     * Get entries in date range
     */
    fun getEntriesInRange(startTime: Long, endTime: Long): List<AuditEntry> {
        return _entries.value.filter { it.timestamp in startTime..endTime }
    }
}

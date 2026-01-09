package com.visualmapper.companion.explorer

import android.content.Context
import android.util.Log
import com.visualmapper.companion.explorer.ml.TFLiteQNetwork
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Self-Diagnostic System for Visual Mapper
 *
 * Provides real-time feedback about:
 * - ML model status and performance
 * - Exploration health and issues
 * - Accessibility service status
 * - Connection status
 * - Pattern recognition failures
 *
 * Helps users understand why the app may not be performing as expected.
 */
class SelfDiagnostics(private val context: Context) {

    companion object {
        private const val TAG = "SelfDiagnostics"
        private const val MAX_ISSUES = 50

        @Volatile
        private var instance: SelfDiagnostics? = null

        fun getInstance(context: Context): SelfDiagnostics {
            return instance ?: synchronized(this) {
                instance ?: SelfDiagnostics(context.applicationContext).also { instance = it }
            }
        }
    }

    // Current diagnostic state
    private val _diagnosticState = MutableStateFlow(DiagnosticState())
    val diagnosticState: StateFlow<DiagnosticState> = _diagnosticState.asStateFlow()

    // Issue history
    private val issueHistory = mutableListOf<DiagnosticIssue>()

    // Performance tracking
    private var explorationStartTime: Long = 0
    private var elementsExplored: Int = 0
    private var screensDiscovered: Int = 0
    private var recoveryAttempts: Int = 0
    private var stuckEvents: Int = 0

    /**
     * Run full system diagnostics
     */
    fun runDiagnostics(): DiagnosticState {
        val issues = mutableListOf<DiagnosticIssue>()

        // 1. Check Accessibility Service
        val accessibilityStatus = checkAccessibilityService()
        if (accessibilityStatus != null) issues.add(accessibilityStatus)

        // 2. Check ML Model
        val mlStatus = checkMLModel()
        if (mlStatus != null) issues.add(mlStatus)

        // 3. Check Connection
        val connectionStatus = checkConnection()
        if (connectionStatus != null) issues.add(connectionStatus)

        // 4. Check Exploration Health
        val explorationIssues = checkExplorationHealth()
        issues.addAll(explorationIssues)

        // 5. Overall system health
        val overallHealth = calculateOverallHealth(issues)

        val state = DiagnosticState(
            overallHealth = overallHealth,
            issues = issues,
            mlAcceleration = getMlAccelerationType(),
            accessibilityEnabled = isAccessibilityEnabled(),
            serverConnected = isServerConnected(),
            explorationStats = getExplorationStats(),
            timestamp = System.currentTimeMillis()
        )

        _diagnosticState.value = state
        return state
    }

    /**
     * Check accessibility service status
     */
    private fun checkAccessibilityService(): DiagnosticIssue? {
        val service = VisualMapperAccessibilityService.getInstance()

        return if (service == null) {
            DiagnosticIssue(
                severity = DiagnosticSeverity.CRITICAL,
                category = IssueCategory.ACCESSIBILITY,
                title = "Accessibility Service Disabled",
                description = "The accessibility service is not running. Exploration and gesture execution won't work.",
                suggestion = "Enable the Visual Mapper accessibility service in Settings > Accessibility",
                canAutoFix = false
            )
        } else {
            null
        }
    }

    /**
     * Check ML model status
     */
    private fun checkMLModel(): DiagnosticIssue? {
        try {
            val tfLite = TFLiteQNetwork.getInstance(context)

            if (!tfLite.isReady()) {
                return DiagnosticIssue(
                    severity = DiagnosticSeverity.WARNING,
                    category = IssueCategory.ML_MODEL,
                    title = "ML Model Not Loaded",
                    description = "No ML model is loaded. Q-learning will use table-based values only.",
                    suggestion = "The app will still work but may make less optimal exploration decisions.",
                    canAutoFix = true
                )
            }

            // Check performance
            val stats = tfLite.getPerformanceStats()
            if (stats.inferenceCount > 100 && stats.avgTimeMs > 50) {
                return DiagnosticIssue(
                    severity = DiagnosticSeverity.INFO,
                    category = IssueCategory.ML_MODEL,
                    title = "ML Inference Running Slow",
                    description = "Average inference time is ${String.format("%.1f", stats.avgTimeMs)}ms. Using ${stats.accelerationType}.",
                    suggestion = if (stats.accelerationType == TFLiteQNetwork.AccelerationType.CPU) {
                        "GPU/NPU acceleration not available on this device."
                    } else {
                        "This is normal for complex models."
                    },
                    canAutoFix = false
                )
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ML model", e)
            return DiagnosticIssue(
                severity = DiagnosticSeverity.WARNING,
                category = IssueCategory.ML_MODEL,
                title = "ML System Error",
                description = "Error checking ML model: ${e.message}",
                suggestion = "The app will continue using Q-table based learning.",
                canAutoFix = false
            )
        }
    }

    /**
     * Check connection status
     */
    private fun checkConnection(): DiagnosticIssue? {
        val connectionManager = ConnectionManager.getInstance(context)
        val effectiveMode = connectionManager.effectiveMode.value

        return when (effectiveMode) {
            OperationMode.SERVER_UNAVAILABLE -> DiagnosticIssue(
                severity = DiagnosticSeverity.WARNING,
                category = IssueCategory.CONNECTION,
                title = "Server Required But Unavailable",
                description = "Connection mode is set to 'Server Required' but the server is unreachable.",
                suggestion = "Check server URL in settings, or switch to 'Auto-detect' or 'Standalone' mode.",
                canAutoFix = false
            )
            OperationMode.CHECKING -> DiagnosticIssue(
                severity = DiagnosticSeverity.INFO,
                category = IssueCategory.CONNECTION,
                title = "Checking Server Connection",
                description = "Currently checking server connectivity...",
                suggestion = "Please wait for connection check to complete.",
                canAutoFix = false
            )
            else -> null
        }
    }

    /**
     * Check exploration health
     */
    private fun checkExplorationHealth(): List<DiagnosticIssue> {
        val issues = mutableListOf<DiagnosticIssue>()

        // Check for high stuck rate
        if (stuckEvents > 5 && elementsExplored > 0) {
            val stuckRate = stuckEvents.toFloat() / elementsExplored
            if (stuckRate > 0.3) {
                issues.add(DiagnosticIssue(
                    severity = DiagnosticSeverity.WARNING,
                    category = IssueCategory.EXPLORATION,
                    title = "Frequent Stuck Events",
                    description = "Exploration is getting stuck frequently ($stuckEvents times). This may indicate navigation issues.",
                    suggestion = "Try a different exploration strategy, or the app may have complex navigation that's hard to automate.",
                    canAutoFix = false
                ))
            }
        }

        // Check for high recovery attempts
        if (recoveryAttempts > 10) {
            issues.add(DiagnosticIssue(
                severity = DiagnosticSeverity.WARNING,
                category = IssueCategory.EXPLORATION,
                title = "Many Recovery Attempts",
                description = "Exploration had to recover $recoveryAttempts times. The target app may be unstable.",
                suggestion = "Consider using 'Non-destructive' mode to avoid changing app settings.",
                canAutoFix = false
            ))
        }

        return issues
    }

    /**
     * Calculate overall system health
     */
    private fun calculateOverallHealth(issues: List<DiagnosticIssue>): HealthStatus {
        val criticalCount = issues.count { it.severity == DiagnosticSeverity.CRITICAL }
        val warningCount = issues.count { it.severity == DiagnosticSeverity.WARNING }

        return when {
            criticalCount > 0 -> HealthStatus.CRITICAL
            warningCount > 2 -> HealthStatus.DEGRADED
            warningCount > 0 -> HealthStatus.MINOR_ISSUES
            else -> HealthStatus.HEALTHY
        }
    }

    // === Tracking Methods ===

    fun recordExplorationStart() {
        explorationStartTime = System.currentTimeMillis()
        elementsExplored = 0
        screensDiscovered = 0
        recoveryAttempts = 0
        stuckEvents = 0
    }

    fun recordElementExplored() {
        elementsExplored++
    }

    fun recordScreenDiscovered() {
        screensDiscovered++
    }

    fun recordRecoveryAttempt() {
        recoveryAttempts++
    }

    fun recordStuckEvent() {
        stuckEvents++
        addIssue(DiagnosticIssue(
            severity = DiagnosticSeverity.INFO,
            category = IssueCategory.EXPLORATION,
            title = "Exploration Got Stuck",
            description = "Exploration couldn't find new elements to explore.",
            suggestion = "Switching to a different strategy or backtracking.",
            canAutoFix = true
        ))
    }

    fun recordPatternNotRecognized(elementText: String) {
        addIssue(DiagnosticIssue(
            severity = DiagnosticSeverity.INFO,
            category = IssueCategory.PATTERN_RECOGNITION,
            title = "Pattern Not Recognized",
            description = "Couldn't determine sensor type for: '$elementText'",
            suggestion = "You can manually edit the sensor after exploration.",
            canAutoFix = false
        ))
    }

    fun recordAppLeftUnexpectedly(currentPackage: String?, targetPackage: String) {
        addIssue(DiagnosticIssue(
            severity = DiagnosticSeverity.WARNING,
            category = IssueCategory.EXPLORATION,
            title = "Left Target App",
            description = "Exploration left $targetPackage and went to $currentPackage",
            suggestion = "Returning to target app...",
            canAutoFix = true
        ))
    }

    private fun addIssue(issue: DiagnosticIssue) {
        issueHistory.add(issue)
        if (issueHistory.size > MAX_ISSUES) {
            issueHistory.removeAt(0)
        }
    }

    // === Helper Methods ===

    private fun isAccessibilityEnabled(): Boolean {
        return VisualMapperAccessibilityService.getInstance() != null
    }

    private fun isServerConnected(): Boolean {
        return try {
            ConnectionManager.getInstance(context).isServerAvailable()
        } catch (e: Exception) {
            false
        }
    }

    private fun getMlAccelerationType(): String {
        return try {
            TFLiteQNetwork.getInstance(context).accelerationType.name
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    private fun getExplorationStats(): ExplorationStats {
        val duration = if (explorationStartTime > 0) {
            System.currentTimeMillis() - explorationStartTime
        } else 0

        return ExplorationStats(
            durationMs = duration,
            elementsExplored = elementsExplored,
            screensDiscovered = screensDiscovered,
            recoveryAttempts = recoveryAttempts,
            stuckEvents = stuckEvents
        )
    }

    /**
     * Get a user-friendly summary of current status
     */
    fun getStatusSummary(): String {
        val state = runDiagnostics()

        return buildString {
            appendLine("=== Visual Mapper Status ===")
            appendLine("Overall Health: ${state.overallHealth}")
            appendLine()
            appendLine("Accessibility: ${if (state.accessibilityEnabled) "Enabled" else "DISABLED"}")
            appendLine("ML Acceleration: ${state.mlAcceleration}")
            appendLine("Server: ${if (state.serverConnected) "Connected" else "Not connected"}")

            if (state.issues.isNotEmpty()) {
                appendLine()
                appendLine("Issues (${state.issues.size}):")
                state.issues.take(5).forEach { issue ->
                    appendLine("  [${issue.severity}] ${issue.title}")
                }
            }

            if (state.explorationStats.elementsExplored > 0) {
                appendLine()
                appendLine("Exploration Stats:")
                appendLine("  Elements: ${state.explorationStats.elementsExplored}")
                appendLine("  Screens: ${state.explorationStats.screensDiscovered}")
                appendLine("  Recoveries: ${state.explorationStats.recoveryAttempts}")
            }
        }
    }

    /**
     * Clear diagnostic history
     */
    fun clear() {
        issueHistory.clear()
        explorationStartTime = 0
        elementsExplored = 0
        screensDiscovered = 0
        recoveryAttempts = 0
        stuckEvents = 0
        _diagnosticState.value = DiagnosticState()
    }

    // =========================================================================
    // Human-in-the-Loop: Event Tracking
    // =========================================================================

    /**
     * Record an exploration event for analytics and debugging.
     * Used to track human-in-the-loop interactions like vetos and teaching moments.
     */
    suspend fun onExplorationEvent(
        event: DiagnosticEvent,
        message: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        Log.i(TAG, "Event: ${event.name} - $message | $metadata")

        // Track specific event types
        when (event) {
            DiagnosticEvent.USER_INTERVENTION -> {
                recoveryAttempts++ // User intervention counts as a form of recovery
            }
            DiagnosticEvent.USER_TEACHING -> {
                screensDiscovered++ // User showed us a new screen
            }
            DiagnosticEvent.ACTION_VETOED -> {
                stuckEvents++ // Veto suggests we were about to do something wrong
            }
            else -> { /* Other events tracked for logging only */ }
        }
    }

    /**
     * Types of exploration events for tracking
     */
    enum class DiagnosticEvent {
        // Exploration events
        EXPLORATION_STARTED,
        EXPLORATION_COMPLETED,
        EXPLORATION_STUCK,

        // Human-in-the-Loop events
        USER_INTERVENTION,      // User took control during exploration
        USER_TEACHING,          // User showed the bot something new
        ACTION_VETOED,          // User vetoed a planned action
        ACTION_APPROVED,        // User let an action proceed

        // Recovery events
        RECOVERY_ATTEMPTED,
        RECOVERY_SUCCEEDED,
        RECOVERY_FAILED
    }
}

// === Data Classes ===

data class DiagnosticState(
    val overallHealth: HealthStatus = HealthStatus.UNKNOWN,
    val issues: List<DiagnosticIssue> = emptyList(),
    val mlAcceleration: String = "UNKNOWN",
    val accessibilityEnabled: Boolean = false,
    val serverConnected: Boolean = false,
    val explorationStats: ExplorationStats = ExplorationStats(),
    val timestamp: Long = 0
)

data class DiagnosticIssue(
    val severity: DiagnosticSeverity,
    val category: IssueCategory,
    val title: String,
    val description: String,
    val suggestion: String,
    val canAutoFix: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ExplorationStats(
    val durationMs: Long = 0,
    val elementsExplored: Int = 0,
    val screensDiscovered: Int = 0,
    val recoveryAttempts: Int = 0,
    val stuckEvents: Int = 0
)

enum class HealthStatus {
    HEALTHY,        // Everything working
    MINOR_ISSUES,   // Some warnings but functional
    DEGRADED,       // Multiple issues affecting performance
    CRITICAL,       // Major issues preventing operation
    UNKNOWN         // Status not yet determined
}

enum class DiagnosticSeverity {
    INFO,       // Informational, no action needed
    WARNING,    // Something may need attention
    CRITICAL    // Requires immediate attention
}

enum class IssueCategory {
    ACCESSIBILITY,
    ML_MODEL,
    CONNECTION,
    EXPLORATION,
    PATTERN_RECOGNITION,
    PERFORMANCE
}

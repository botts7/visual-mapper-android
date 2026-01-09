package com.visualmapper.companion.explorer

import java.util.UUID

/**
 * Manages a guided correction session where users teach the app
 * the correct actions for problematic elements/screens.
 */

/**
 * Represents an active correction session.
 */
data class CorrectionSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val packageName: String,
    val issues: List<ExplorationIssue>,
    var currentIssueIndex: Int = 0,
    val corrections: MutableList<AppliedCorrection> = mutableListOf(),
    var status: CorrectionSessionStatus = CorrectionSessionStatus.NOT_STARTED,
    val startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null
) {
    val currentIssue: ExplorationIssue?
        get() = issues.getOrNull(currentIssueIndex)

    val progress: Float
        get() = if (issues.isEmpty()) 1f else currentIssueIndex.toFloat() / issues.size

    val remainingIssues: Int
        get() = (issues.size - currentIssueIndex).coerceAtLeast(0)

    val isComplete: Boolean
        get() = currentIssueIndex >= issues.size || status == CorrectionSessionStatus.COMPLETED
}

/**
 * Status of the correction session.
 */
enum class CorrectionSessionStatus {
    NOT_STARTED,      // Session created but not yet active
    NAVIGATING,       // Auto-navigating to problematic screen
    AWAITING_ACTION,  // Waiting for user to demonstrate correct action
    VERIFYING,        // Verifying the correction worked
    COMPLETED,        // All issues processed
    CANCELLED         // User cancelled the session
}

/**
 * Records a correction applied by the user.
 */
data class AppliedCorrection(
    val issue: ExplorationIssue,
    val correctionType: GuidedCorrectionType,
    val demonstratedAction: DemonstratedAction? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val verified: Boolean = false
)

/**
 * Action demonstrated by the user during correction.
 */
data class DemonstratedAction(
    val type: DemonstratedActionType,
    val x: Int? = null,
    val y: Int? = null,
    val elementId: String? = null,
    val scrollDirection: String? = null,  // "up", "down", "left", "right"
    val description: String? = null
)

/**
 * Type of action the user demonstrated.
 */
enum class DemonstratedActionType {
    TAP,           // User tapped a specific location
    SCROLL,        // User performed a scroll gesture
    LONG_PRESS,    // User performed a long press
    SWIPE,         // User performed a swipe
    BACK,          // User pressed back
    NONE           // No action (marked as ignore/skip)
}

/**
 * Summary of a completed correction session.
 */
data class CorrectionSummary(
    val sessionId: String,
    val packageName: String,
    val totalIssues: Int,
    val corrected: Int,
    val skipped: Int,
    val markedIgnore: Int,
    val markedDangerous: Int,
    val verified: Int,
    val duration: Long,
    val corrections: List<AppliedCorrection>
) {
    val successRate: Float
        get() = if (totalIssues > 0) corrected.toFloat() / totalIssues else 0f
}

/**
 * Result of attempting to navigate to a screen.
 */
sealed class NavigationResult {
    object Success : NavigationResult()
    data class PartialSuccess(val stepsCompleted: Int, val totalSteps: Int, val failedAt: String) : NavigationResult()
    object NoPath : NavigationResult()
    data class Failed(val reason: String) : NavigationResult()
    object AlreadyOnScreen : NavigationResult()
}

/**
 * A step in the navigation path.
 */
data class NavigationStep(
    val fromScreenId: String,
    val toScreenId: String,
    val elementId: String,
    val elementText: String? = null,
    val elementResourceId: String? = null,
    val x: Int,
    val y: Int
)

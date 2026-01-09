package com.visualmapper.companion.explorer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GuidedCorrectionManager - Orchestrates guided correction sessions.
 *
 * Manages the state of correction sessions, coordinates between
 * the UI (GuidedCorrectionActivity), navigation (NavigationGuide),
 * and learning (ExplorationQLearning).
 */
class GuidedCorrectionManager(private val context: Context) {

    companion object {
        private const val TAG = "GuidedCorrectionManager"

        @Volatile
        private var instance: GuidedCorrectionManager? = null

        fun getInstance(context: Context): GuidedCorrectionManager {
            return instance ?: synchronized(this) {
                instance ?: GuidedCorrectionManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // Current session state
    private val _session = MutableStateFlow<CorrectionSession?>(null)
    val session: StateFlow<CorrectionSession?> = _session.asStateFlow()

    // Navigation guide (initialized when session starts)
    private var navigationGuide: NavigationGuide? = null

    // Q-Learning for applying corrections
    private var qLearning: ExplorationQLearning? = null

    /**
     * Start a new correction session.
     *
     * @param packageName The package being corrected
     * @param issues List of issues to correct
     * @return The created session
     */
    fun startSession(packageName: String, issues: List<ExplorationIssue>): CorrectionSession {
        Log.i(TAG, "Starting correction session for $packageName with ${issues.size} issues")

        // End any existing session
        endSession()

        // Initialize navigation guide
        val service = AppExplorerService.getInstance()
        val state = service?.getExplorationResult()?.state
        if (state != null) {
            val gestureExecutor = service.getGestureExecutor()
            if (gestureExecutor != null) {
                navigationGuide = NavigationGuide(state.navigationGraph, gestureExecutor)
            }
        }

        // Initialize Q-Learning
        qLearning = ExplorationQLearning(context)

        // Create session
        val newSession = CorrectionSession(
            packageName = packageName,
            issues = issues,
            status = CorrectionSessionStatus.NOT_STARTED
        )

        _session.value = newSession
        return newSession
    }

    /**
     * Get the current issue being corrected.
     */
    fun getCurrentIssue(): ExplorationIssue? {
        return _session.value?.currentIssue
    }

    /**
     * Get navigation path to current issue's screen.
     */
    fun getNavigationPath(currentScreenId: String): List<NavigationStep>? {
        val issue = getCurrentIssue() ?: return null
        return navigationGuide?.getNavigationPath(currentScreenId, issue.screenId)
    }

    /**
     * Navigate to the current issue's screen.
     */
    suspend fun navigateToCurrentIssue(currentScreenId: String): NavigationResult {
        val issue = getCurrentIssue() ?: return NavigationResult.Failed("No current issue")
        val guide = navigationGuide ?: return NavigationResult.Failed("Navigation not available")

        _session.value = _session.value?.copy(status = CorrectionSessionStatus.NAVIGATING)

        return guide.navigateWithRetry(currentScreenId, issue.screenId)
    }

    /**
     * Record a correction for the current issue.
     *
     * @param type The type of correction
     * @param demonstratedAction The action demonstrated by the user (if applicable)
     */
    fun recordCorrection(type: GuidedCorrectionType, demonstratedAction: DemonstratedAction?) {
        val currentSession = _session.value ?: return
        val issue = currentSession.currentIssue ?: return

        Log.i(TAG, "Recording correction: ${type.name} for issue at ${issue.screenId}")

        // Create correction record
        val correction = AppliedCorrection(
            issue = issue,
            correctionType = type,
            demonstratedAction = demonstratedAction,
            verified = false
        )

        // Add to session
        currentSession.corrections.add(correction)

        // Apply learning
        applyLearning(issue, type, demonstratedAction)

        // Update session status
        _session.value = currentSession.copy(status = CorrectionSessionStatus.AWAITING_ACTION)
    }

    /**
     * Apply learning from a correction to the Q-learning model.
     */
    private fun applyLearning(
        issue: ExplorationIssue,
        type: GuidedCorrectionType,
        action: DemonstratedAction?
    ) {
        val ql = qLearning ?: return

        when (type) {
            GuidedCorrectionType.TAP_DEMONSTRATED -> {
                // Strong positive reward for correct element
                if (action?.elementId != null) {
                    ql.updateQ(
                        screenHash = issue.screenId,
                        actionKey = action.elementId,
                        reward = 2.0f,
                        nextScreenHash = null
                    )
                    Log.d(TAG, "Applied TAP reward for ${action.elementId}")
                }

                // Penalize the original problematic element
                issue.elementId?.let { originalId ->
                    if (originalId != action?.elementId) {
                        ql.updateQ(
                            screenHash = issue.screenId,
                            actionKey = originalId,
                            reward = -0.5f,
                            nextScreenHash = null
                        )
                        Log.d(TAG, "Applied penalty for original element $originalId")
                    }
                }
            }

            GuidedCorrectionType.SCROLL_DEMONSTRATED -> {
                // Record that scroll is needed before this screen's elements
                Log.d(TAG, "Applied SCROLL learning for ${issue.screenId}")
                // TODO: Store scroll-first pattern for this screen
            }

            GuidedCorrectionType.MARK_IGNORE -> {
                // Moderate negative reward - element should be skipped
                issue.elementId?.let { elementId ->
                    ql.updateQ(
                        screenHash = issue.screenId,
                        actionKey = elementId,
                        reward = -0.8f,
                        nextScreenHash = null
                    )
                    Log.d(TAG, "Applied IGNORE penalty for $elementId")
                }
            }

            GuidedCorrectionType.MARK_DANGEROUS -> {
                // Strong negative reward - element causes problems
                issue.elementId?.let { elementId ->
                    ql.updateQ(
                        screenHash = issue.screenId,
                        actionKey = elementId,
                        reward = -2.0f,
                        nextScreenHash = null
                    )
                    // The strong negative Q-value will cause the element to be avoided
                    Log.d(TAG, "Applied DANGEROUS penalty for $elementId")
                }
            }

            GuidedCorrectionType.SKIP_ISSUE -> {
                // No learning applied for skips
                Log.d(TAG, "Skipped issue - no learning applied")
            }
        }

        // Save all Q-learning state
        ql.saveAll()
    }

    /**
     * Skip the current issue and move to the next.
     */
    fun skipCurrentIssue() {
        recordCorrection(GuidedCorrectionType.SKIP_ISSUE, null)
        moveToNextIssue()
    }

    /**
     * Move to the next issue in the session.
     */
    fun moveToNextIssue() {
        val currentSession = _session.value ?: return

        val nextIndex = currentSession.currentIssueIndex + 1
        if (nextIndex >= currentSession.issues.size) {
            // Session complete
            _session.value = currentSession.copy(
                currentIssueIndex = nextIndex,
                status = CorrectionSessionStatus.COMPLETED,
                endTime = System.currentTimeMillis()
            )
            Log.i(TAG, "Session complete - processed ${currentSession.corrections.size} corrections")
        } else {
            _session.value = currentSession.copy(
                currentIssueIndex = nextIndex,
                status = CorrectionSessionStatus.AWAITING_ACTION
            )
            Log.i(TAG, "Moved to issue ${nextIndex + 1}/${currentSession.issues.size}")
        }
    }

    /**
     * Verify that the current fix worked by re-exploring.
     *
     * @return true if the fix appears to work
     */
    suspend fun verifyFix(): Boolean {
        val issue = getCurrentIssue() ?: return false

        Log.i(TAG, "Verifying fix for issue at ${issue.screenId}")

        // TODO: Implement verification by:
        // 1. Navigate back to the problematic screen
        // 2. Try the corrected action
        // 3. Check if the same issue occurs

        // For now, assume success
        _session.value?.corrections?.lastOrNull()?.let {
            // Mark as verified (would need to update the correction object properly)
            Log.i(TAG, "Fix verified (simulated)")
        }

        return true
    }

    /**
     * End the current session and return a summary.
     */
    fun endSession(): CorrectionSummary? {
        val session = _session.value ?: return null

        val summary = CorrectionSummary(
            sessionId = session.sessionId,
            packageName = session.packageName,
            totalIssues = session.issues.size,
            corrected = session.corrections.count {
                it.correctionType == GuidedCorrectionType.TAP_DEMONSTRATED ||
                it.correctionType == GuidedCorrectionType.SCROLL_DEMONSTRATED
            },
            skipped = session.corrections.count {
                it.correctionType == GuidedCorrectionType.SKIP_ISSUE
            },
            markedIgnore = session.corrections.count {
                it.correctionType == GuidedCorrectionType.MARK_IGNORE
            },
            markedDangerous = session.corrections.count {
                it.correctionType == GuidedCorrectionType.MARK_DANGEROUS
            },
            verified = session.corrections.count { it.verified },
            duration = (session.endTime ?: System.currentTimeMillis()) - session.startTime,
            corrections = session.corrections.toList()
        )

        Log.i(TAG, "Session ended: ${summary.corrected} corrected, ${summary.skipped} skipped, " +
                "${summary.markedIgnore} ignored, ${summary.markedDangerous} dangerous")

        // Clean up
        _session.value = null
        navigationGuide = null

        return summary
    }

    /**
     * Cancel the current session without saving.
     */
    fun cancelSession() {
        val session = _session.value
        if (session != null) {
            Log.i(TAG, "Session cancelled with ${session.corrections.size} corrections")
        }

        _session.value = null
        navigationGuide = null
    }

    /**
     * Get session statistics.
     */
    fun getSessionStats(): SessionStats? {
        val session = _session.value ?: return null

        return SessionStats(
            totalIssues = session.issues.size,
            currentIndex = session.currentIssueIndex,
            correctionsApplied = session.corrections.size,
            progress = session.progress,
            remainingIssues = session.remainingIssues
        )
    }

    /**
     * Session statistics.
     */
    data class SessionStats(
        val totalIssues: Int,
        val currentIndex: Int,
        val correctionsApplied: Int,
        val progress: Float,
        val remainingIssues: Int
    )
}

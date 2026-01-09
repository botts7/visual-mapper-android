package com.visualmapper.companion.explorer

import android.util.Log
import kotlin.random.Random

/**
 * Stuck Recovery Strategy - Escalating recovery strategies when exploration gets stuck.
 * Extracted from AppExplorerService for modularity and testability.
 *
 * Phase 1 Refactor: This implements a progressive recovery approach:
 * 1. Random scroll (gentle)
 * 2. Press back (navigate up)
 * 3. Random tap (discover hidden elements)
 * 4. Restart app (hard reset)
 * 5. Request user help (human-in-the-loop)
 *
 * Each strategy is tried multiple times before escalating to the next.
 * Statistics are tracked for learning which strategies work best.
 */
class StuckRecoveryStrategy(
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val TAG = "StuckRecovery"
        private const val MAX_ATTEMPTS_PER_STRATEGY = 2
    }

    /**
     * Recovery strategies in escalation order
     */
    enum class Strategy {
        RANDOM_SCROLL,      // Try scrolling in a random direction
        PRESS_BACK,         // Navigate back
        RANDOM_TAP,         // Tap a random location
        SCROLL_TO_EDGE,     // Scroll to very top/bottom
        RESTART_APP,        // Force restart the app
        REQUEST_USER_HELP   // Ask user for help (final resort)
    }

    /**
     * Result of a recovery attempt
     */
    data class RecoveryResult(
        val success: Boolean,
        val strategyUsed: Strategy,
        val action: RecoveryAction,
        val needsUserHelp: Boolean = false,
        val message: String? = null
    )

    /**
     * Action to execute for recovery
     */
    sealed class RecoveryAction {
        data class Scroll(
            val startX: Int, val startY: Int,
            val endX: Int, val endY: Int,
            val durationMs: Long = 300
        ) : RecoveryAction()

        data class Tap(val x: Int, val y: Int) : RecoveryAction()
        object PressBack : RecoveryAction()
        data class RestartApp(val packageName: String) : RecoveryAction()
        data class RequestUserHelp(val message: String) : RecoveryAction()
    }

    // Current strategy index
    private var currentStrategyIndex = 0

    // Attempts at current strategy
    private var currentStrategyAttempts = 0

    // Total recovery attempts this session
    private var totalAttempts = 0

    // Success counts per strategy (for learning)
    private val successCounts = mutableMapOf<Strategy, Int>()
    private val attemptCounts = mutableMapOf<Strategy, Int>()

    // =========================================================================
    // Recovery Logic
    // =========================================================================

    /**
     * Get the next recovery action to try.
     *
     * @param currentPackage Current app package (for restart action)
     * @return RecoveryResult with the action to execute
     */
    fun getNextRecoveryAction(currentPackage: String): RecoveryResult {
        val strategies = Strategy.values()
        val strategy = strategies[currentStrategyIndex]

        currentStrategyAttempts++
        totalAttempts++
        attemptCounts[strategy] = attemptCounts.getOrDefault(strategy, 0) + 1

        Log.d(TAG, "Recovery attempt #$totalAttempts - Strategy: $strategy (attempt $currentStrategyAttempts)")

        val action = when (strategy) {
            Strategy.RANDOM_SCROLL -> generateScrollAction()
            Strategy.PRESS_BACK -> RecoveryAction.PressBack
            Strategy.RANDOM_TAP -> generateRandomTapAction()
            Strategy.SCROLL_TO_EDGE -> generateEdgeScrollAction()
            Strategy.RESTART_APP -> RecoveryAction.RestartApp(currentPackage)
            Strategy.REQUEST_USER_HELP -> RecoveryAction.RequestUserHelp(
                "Exploration is stuck. Please navigate to a new screen or tap an unexplored element."
            )
        }

        return RecoveryResult(
            success = false, // Will be updated by caller
            strategyUsed = strategy,
            action = action,
            needsUserHelp = strategy == Strategy.REQUEST_USER_HELP,
            message = "Trying ${strategy.name.lowercase().replace('_', ' ')}"
        )
    }

    /**
     * Report whether the last recovery action succeeded.
     * Updates statistics and escalates if needed.
     */
    fun reportResult(success: Boolean) {
        val strategy = Strategy.values()[currentStrategyIndex]

        if (success) {
            Log.i(TAG, "Recovery succeeded with strategy: $strategy")
            successCounts[strategy] = successCounts.getOrDefault(strategy, 0) + 1
            // Reset for next stuck episode
            reset()
        } else {
            Log.d(TAG, "Recovery failed with strategy: $strategy")

            // Check if we should escalate
            if (currentStrategyAttempts >= MAX_ATTEMPTS_PER_STRATEGY) {
                escalate()
            }
        }
    }

    /**
     * Escalate to the next strategy
     */
    private fun escalate() {
        currentStrategyAttempts = 0
        if (currentStrategyIndex < Strategy.values().size - 1) {
            currentStrategyIndex++
            val newStrategy = Strategy.values()[currentStrategyIndex]
            Log.i(TAG, "Escalating to strategy: $newStrategy")
        } else {
            Log.w(TAG, "All recovery strategies exhausted")
        }
    }

    /**
     * Check if we've exhausted all strategies
     */
    fun isExhausted(): Boolean =
        currentStrategyIndex >= Strategy.values().size - 1 &&
        currentStrategyAttempts >= MAX_ATTEMPTS_PER_STRATEGY

    /**
     * Reset recovery state (after successful recovery or new exploration)
     */
    fun reset() {
        currentStrategyIndex = 0
        currentStrategyAttempts = 0
        totalAttempts = 0
    }

    // =========================================================================
    // Action Generators
    // =========================================================================

    private fun generateScrollAction(): RecoveryAction.Scroll {
        // Random direction: up, down, left, right
        val directions = listOf("up", "down", "left", "right")
        val direction = directions.random()

        return when (direction) {
            "up" -> RecoveryAction.Scroll(
                startX = screenWidth / 2,
                startY = screenHeight * 3 / 4,
                endX = screenWidth / 2,
                endY = screenHeight / 4,
                durationMs = 300
            )
            "down" -> RecoveryAction.Scroll(
                startX = screenWidth / 2,
                startY = screenHeight / 4,
                endX = screenWidth / 2,
                endY = screenHeight * 3 / 4,
                durationMs = 300
            )
            "left" -> RecoveryAction.Scroll(
                startX = screenWidth * 3 / 4,
                startY = screenHeight / 2,
                endX = screenWidth / 4,
                endY = screenHeight / 2,
                durationMs = 300
            )
            else -> RecoveryAction.Scroll( // right
                startX = screenWidth / 4,
                startY = screenHeight / 2,
                endX = screenWidth * 3 / 4,
                endY = screenHeight / 2,
                durationMs = 300
            )
        }
    }

    private fun generateEdgeScrollAction(): RecoveryAction.Scroll {
        // Scroll to very top or bottom to reveal hidden content
        val toTop = Random.nextBoolean()

        return if (toTop) {
            RecoveryAction.Scroll(
                startX = screenWidth / 2,
                startY = screenHeight / 4,
                endX = screenWidth / 2,
                endY = screenHeight - 100, // Scroll to reveal top content
                durationMs = 500
            )
        } else {
            RecoveryAction.Scroll(
                startX = screenWidth / 2,
                startY = screenHeight * 3 / 4,
                endX = screenWidth / 2,
                endY = 100, // Scroll to reveal bottom content
                durationMs = 500
            )
        }
    }

    private fun generateRandomTapAction(): RecoveryAction.Tap {
        // Tap in a safe zone (center 50% of screen, avoiding edges)
        val safeMarginX = screenWidth / 4
        val safeMarginY = screenHeight / 4

        return RecoveryAction.Tap(
            x = Random.nextInt(safeMarginX, screenWidth - safeMarginX),
            y = Random.nextInt(safeMarginY, screenHeight - safeMarginY)
        )
    }

    // =========================================================================
    // Statistics
    // =========================================================================

    /**
     * Get recovery statistics for analysis
     */
    fun getStatistics(): RecoveryStatistics {
        val strategyStats = Strategy.values().map { strategy ->
            StrategyStats(
                strategy = strategy,
                attempts = attemptCounts.getOrDefault(strategy, 0),
                successes = successCounts.getOrDefault(strategy, 0)
            )
        }

        return RecoveryStatistics(
            totalAttempts = strategyStats.sumOf { it.attempts },
            totalSuccesses = strategyStats.sumOf { it.successes },
            currentStrategy = Strategy.values()[currentStrategyIndex],
            currentAttempt = currentStrategyAttempts,
            strategyStats = strategyStats
        )
    }

    data class StrategyStats(
        val strategy: Strategy,
        val attempts: Int,
        val successes: Int
    ) {
        val successRate: Float
            get() = if (attempts > 0) successes.toFloat() / attempts else 0f
    }

    data class RecoveryStatistics(
        val totalAttempts: Int,
        val totalSuccesses: Int,
        val currentStrategy: Strategy,
        val currentAttempt: Int,
        val strategyStats: List<StrategyStats>
    ) {
        val overallSuccessRate: Float
            get() = if (totalAttempts > 0) totalSuccesses.toFloat() / totalAttempts else 0f
    }

    /**
     * Get recommended strategy based on historical success rates
     */
    fun getRecommendedStrategy(): Strategy? {
        return attemptCounts.keys
            .filter { attemptCounts.getOrDefault(it, 0) >= 3 } // Minimum sample size
            .maxByOrNull { strategy ->
                val attempts = attemptCounts.getOrDefault(strategy, 0)
                val successes = successCounts.getOrDefault(strategy, 0)
                if (attempts > 0) successes.toFloat() / attempts else 0f
            }
    }
}

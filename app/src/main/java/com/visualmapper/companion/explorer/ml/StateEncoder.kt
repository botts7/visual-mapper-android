package com.visualmapper.companion.explorer.ml

import com.visualmapper.companion.explorer.ClickableActionType
import com.visualmapper.companion.explorer.ExploredScreen

/**
 * StateEncoder - Encodes screen state into feature vectors for neural network input
 *
 * Converts an ExploredScreen into a fixed-size float array that captures:
 * - Screen composition (element counts, types)
 * - Element distribution (spatial layout)
 * - Exploration progress (visit count, unexplored ratio)
 * - Historical patterns (successful taps, dangerous elements)
 *
 * Feature dimension: 16 floats (normalized 0-1)
 */
class StateEncoder(private val screenHeight: Int = 2400) {

    companion object {
        const val FEATURE_DIM = 16  // Fixed feature dimension for neural network
    }

    /**
     * Encode a screen state into a feature vector
     */
    fun encode(screen: ExploredScreen): FloatArray {
        return floatArrayOf(
            // === Screen Composition Features (8) ===
            // 0: Clickable element count (normalized)
            normalizeCount(screen.clickableElements.size, 50),

            // 1: Text element count (normalized)
            normalizeCount(screen.textElements.size, 100),

            // 2: Input field count (normalized)
            normalizeCount(screen.inputFields.size, 10),

            // 3: Scrollable container count (normalized)
            normalizeCount(screen.scrollableContainers.size, 5),

            // 4: Is main screen (binary)
            if (isMainScreen(screen.activity)) 1f else 0f,

            // 5: Screen depth estimate (normalized)
            estimateScreenDepth(screen),

            // 6: Unexplored element ratio
            getUnexploredRatio(screen),

            // 7: Bottom navigation tab count (normalized)
            normalizeCount(getBottomNavCount(screen), 5),

            // === Element Distribution Features (4) ===
            // 8: Top third element ratio
            getTopElementRatio(screen),

            // 9: Center third element ratio
            getCenterElementRatio(screen),

            // 10: Bottom third element ratio
            getBottomElementRatio(screen),

            // 11: Average element size (normalized)
            getAverageElementSize(screen),

            // === Exploration History Features (4) ===
            // 12: Screen visit count (normalized)
            normalizeCount(screen.visitCount, 10),

            // 13: Average exploration success rate
            getSuccessfulExplorationRatio(screen),

            // 14: Dangerous/dead-end element ratio
            getDangerousElementRatio(screen),

            // 15: Element diversity score
            getElementDiversityScore(screen)
        )
    }

    /**
     * Normalize a count to 0-1 range
     */
    private fun normalizeCount(count: Int, maxExpected: Int): Float {
        return (count.toFloat() / maxExpected).coerceIn(0f, 1f)
    }

    /**
     * Check if this looks like a main/home screen
     */
    private fun isMainScreen(activity: String): Boolean {
        val mainPatterns = listOf(
            "main", "home", "dashboard", "launcher",
            "homeactivity", "mainactivity"
        )
        val lowerActivity = activity.lowercase()
        return mainPatterns.any { lowerActivity.contains(it) }
    }

    /**
     * Estimate screen depth based on activity name patterns
     */
    private fun estimateScreenDepth(screen: ExploredScreen): Float {
        val activity = screen.activity.lowercase()

        // Deep screens often have these patterns
        val deepPatterns = listOf("detail", "settings", "edit", "view", "info", "about")
        val shallowPatterns = listOf("main", "home", "splash", "launch")

        return when {
            shallowPatterns.any { activity.contains(it) } -> 0.1f
            deepPatterns.any { activity.contains(it) } -> 0.8f
            else -> 0.5f  // Unknown depth
        }
    }

    /**
     * Get ratio of unexplored clickable elements
     */
    private fun getUnexploredRatio(screen: ExploredScreen): Float {
        val total = screen.clickableElements.size
        if (total == 0) return 0f

        val unexplored = screen.clickableElements.count { !it.explored }
        return unexplored.toFloat() / total
    }

    /**
     * Count bottom navigation tabs (elements near bottom with nav-like IDs)
     */
    private fun getBottomNavCount(screen: ExploredScreen): Int {
        val bottomThreshold = screenHeight * 0.85
        return screen.clickableElements.count { element ->
            element.centerY > bottomThreshold &&
                    (element.resourceId?.contains("nav", ignoreCase = true) == true ||
                            element.resourceId?.contains("tab", ignoreCase = true) == true ||
                            element.resourceId?.contains("bottom", ignoreCase = true) == true)
        }
    }

    /**
     * Get ratio of elements in top third of screen
     */
    private fun getTopElementRatio(screen: ExploredScreen): Float {
        val total = screen.clickableElements.size
        if (total == 0) return 0f

        val topThreshold = screenHeight / 3
        val topCount = screen.clickableElements.count { it.centerY < topThreshold }
        return topCount.toFloat() / total
    }

    /**
     * Get ratio of elements in center third of screen
     */
    private fun getCenterElementRatio(screen: ExploredScreen): Float {
        val total = screen.clickableElements.size
        if (total == 0) return 0f

        val topThreshold = screenHeight / 3
        val bottomThreshold = screenHeight * 2 / 3
        val centerCount = screen.clickableElements.count {
            it.centerY >= topThreshold && it.centerY < bottomThreshold
        }
        return centerCount.toFloat() / total
    }

    /**
     * Get ratio of elements in bottom third of screen
     */
    private fun getBottomElementRatio(screen: ExploredScreen): Float {
        val total = screen.clickableElements.size
        if (total == 0) return 0f

        val bottomThreshold = screenHeight * 2 / 3
        val bottomCount = screen.clickableElements.count { it.centerY >= bottomThreshold }
        return bottomCount.toFloat() / total
    }

    /**
     * Get normalized average element size
     */
    private fun getAverageElementSize(screen: ExploredScreen): Float {
        if (screen.clickableElements.isEmpty()) return 0f

        val avgArea = screen.clickableElements.map { element ->
            (element.bounds.width * element.bounds.height).toFloat()
        }.average()

        // Normalize by typical button size (~10000 pixels)
        return (avgArea / 10000.0).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Get ratio of successfully explored elements
     */
    private fun getSuccessfulExplorationRatio(screen: ExploredScreen): Float {
        val explored = screen.clickableElements.filter { it.explored }
        if (explored.isEmpty()) return 0.5f  // No data, assume neutral

        val successful = explored.count { element ->
            element.actionType == ClickableActionType.NAVIGATION ||
                    element.actionType == ClickableActionType.DIALOG
        }
        return successful.toFloat() / explored.size
    }

    /**
     * Get ratio of dangerous/dead-end elements
     */
    private fun getDangerousElementRatio(screen: ExploredScreen): Float {
        val explored = screen.clickableElements.filter { it.explored }
        if (explored.isEmpty()) return 0f

        val dangerous = explored.count { element ->
            element.actionType == ClickableActionType.CLOSES_APP ||
                    element.actionType == ClickableActionType.NO_EFFECT
        }
        return dangerous.toFloat() / explored.size
    }

    /**
     * Score element diversity (more types = more interesting screen)
     */
    private fun getElementDiversityScore(screen: ExploredScreen): Float {
        var score = 0f

        // Add points for each type present
        if (screen.clickableElements.isNotEmpty()) score += 0.25f
        if (screen.textElements.isNotEmpty()) score += 0.25f
        if (screen.inputFields.isNotEmpty()) score += 0.25f
        if (screen.scrollableContainers.isNotEmpty()) score += 0.25f

        return score
    }
}

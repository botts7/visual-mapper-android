package com.visualmapper.companion.explorer

import android.util.Log

/**
 * Tracks exploration coverage metrics and per-screen element statistics.
 *
 * Responsibilities:
 * - Calculate overall coverage (elements, screens, scroll containers)
 * - Track per-screen element visitation stats
 * - Identify exploration frontier (screens needing more exploration)
 * - Check if target coverage has been reached
 *
 * Extracted from AppExplorerService for modularity.
 */
class CoverageTracker {

    companion object {
        private const val TAG = "CoverageTracker"
    }

    // Current coverage metrics
    var metrics: CoverageMetrics = CoverageMetrics()
        private set

    // Per-screen element statistics
    val screenStats = mutableMapOf<String, ScreenElementStats>()

    // Exploration frontier - screens needing more exploration
    private val _explorationFrontier = mutableListOf<ExplorationFrontierItem>()

    /** Read-only view of the exploration frontier. */
    val explorationFrontier: List<ExplorationFrontierItem> get() = _explorationFrontier

    /**
     * Reset all tracking data for a new exploration session.
     */
    fun reset() {
        metrics = CoverageMetrics()
        screenStats.clear()
        _explorationFrontier.clear()
    }

    /**
     * Update coverage metrics based on current exploration state.
     *
     * Calculates:
     * - Screen coverage: % of screens fully explored
     * - Element coverage: % of elements visited
     * - Scroll coverage: % of scrollable containers scrolled
     * - Overall coverage: weighted average (50% elements, 30% screens, 20% scroll)
     *
     * @param exploredScreens Map of all discovered screens
     * @param visitedElements Set of all visited element IDs
     */
    fun updateMetrics(
        exploredScreens: Map<String, ExploredScreen>,
        visitedElements: Set<String>
    ) {
        // Calculate screen coverage
        val totalScreens = exploredScreens.size
        val screensFullyExplored = screenStats.count { (_, stats) -> stats.isFullyExplored }
        val screenCoverage = if (totalScreens > 0) screensFullyExplored.toFloat() / totalScreens else 0f

        // Calculate element coverage
        // FIXED: Only count visited elements that still exist in current screens
        // This prevents inflated coverage when screens have been re-captured with different elements
        val totalElements = exploredScreens.values.sumOf { screen ->
            screen.clickableElements.size
        }
        // Count only visited elements that match current screen elements
        var actualVisitedCount = 0
        for ((screenId, screen) in exploredScreens) {
            for (element in screen.clickableElements) {
                val compositeKey = "$screenId:${element.elementId}"
                if (visitedElements.contains(compositeKey)) {
                    actualVisitedCount++
                }
            }
        }
        val elementCoverage = if (totalElements > 0) actualVisitedCount.toFloat() / totalElements else 0f

        // Calculate scroll coverage
        val totalScrollable = exploredScreens.values.sumOf { screen ->
            screen.scrollableContainers.size
        }
        val scrolledContainers = exploredScreens.values.sumOf { screen ->
            screen.scrollableContainers.count { it.fullyScrolled }
        }
        // If no scrollable containers, don't inflate score - use 0, not 1
        val scrollCoverage = if (totalScrollable > 0) scrolledContainers.toFloat() / totalScrollable else 0f

        // Count unexplored branches using ACTUAL visited elements (not stale counters)
        // A screen has unexplored elements if any of its clickable elements are not in visitedElements
        _explorationFrontier.clear()
        var unexploredBranches = 0

        for ((screenId, screen) in exploredScreens) {
            var unvisitedCount = 0
            for (element in screen.clickableElements) {
                val compositeKey = "$screenId:${element.elementId}"
                if (!visitedElements.contains(compositeKey)) {
                    unvisitedCount++
                }
            }

            // Also count unscrolled containers
            val unscrolledCount = screen.scrollableContainers.count { !it.fullyScrolled }

            if (unvisitedCount > 0 || unscrolledCount > 0) {
                unexploredBranches++
                _explorationFrontier.add(
                    ExplorationFrontierItem(
                        screenId = screenId,
                        unvisitedElementCount = unvisitedCount + unscrolledCount,
                        lastVisited = System.currentTimeMillis(),
                        priority = unvisitedCount + unscrolledCount // Higher priority for more unvisited
                    )
                )
            }
        }
        _explorationFrontier.sortDescending()

        // Calculate overall coverage (weighted average)
        // Elements: 50%, Screens: 30%, Scroll: 20%
        val overallCoverage = (elementCoverage * 0.5f) + (screenCoverage * 0.3f) + (scrollCoverage * 0.2f)

        metrics = CoverageMetrics(
            totalScreensDiscovered = totalScreens,
            screensFullyExplored = screensFullyExplored,
            screenCoveragePercent = screenCoverage * 100f,
            totalElementsDiscovered = totalElements,
            elementsVisited = actualVisitedCount,
            elementCoveragePercent = elementCoverage * 100f,
            totalScrollableContainers = totalScrollable,
            containersFullyScrolled = scrolledContainers,
            scrollCoveragePercent = scrollCoverage * 100f,
            unexploredBranches = unexploredBranches,
            explorationFrontier = _explorationFrontier.take(5).map { it.screenId },
            overallCoverage = overallCoverage
        )

        // Log coverage at 10% intervals
        val prevCoverage = ((overallCoverage - 0.05f) * 10).toInt()
        val currCoverage = (overallCoverage * 10).toInt()
        if (currCoverage > prevCoverage || currCoverage % 2 == 0) {
            Log.i(TAG, "Coverage: ${metrics.summary()}")
        }
    }

    /**
     * Add or update per-screen element statistics when a new screen is discovered.
     *
     * @param screen The newly discovered screen
     */
    fun updateScreenStats(screen: ExploredScreen) {
        val screenId = screen.screenId
        if (!screenStats.containsKey(screenId)) {
            screenStats[screenId] = ScreenElementStats(
                screenId = screenId,
                totalClickable = screen.clickableElements.size,
                visitedClickable = 0,
                totalScrollable = screen.scrollableContainers.size,
                scrolledScrollable = 0,
                totalTextElements = screen.textElements.size
            )
            Log.d(TAG, "Screen stats added: $screenId - ${screen.clickableElements.size} clickable, ${screen.scrollableContainers.size} scrollable")
        }
    }

    /**
     * Mark an element as visited in screen element stats.
     *
     * @param screenId The screen containing the element
     * @param elementId The element that was visited (unused, for future tracking)
     */
    fun markElementVisited(screenId: String, @Suppress("UNUSED_PARAMETER") elementId: String) {
        screenStats[screenId]?.let { stats ->
            if (stats.visitedClickable < stats.totalClickable) {
                stats.visitedClickable++
            }
        }
    }

    /**
     * Mark a scrollable container as fully scrolled.
     *
     * @param screenId The screen containing the container
     */
    fun markContainerScrolled(screenId: String) {
        screenStats[screenId]?.let { stats ->
            if (stats.scrolledScrollable < stats.totalScrollable) {
                stats.scrolledScrollable++
            }
        }
    }

    /**
     * Check if target coverage has been reached (for goal-oriented exploration).
     *
     * @param goal The exploration goal
     * @param targetCoverage The target coverage percentage (0.0 to 1.0)
     * @return true if target coverage is reached
     */
    fun hasReachedTargetCoverage(goal: ExplorationGoal, targetCoverage: Float): Boolean {
        if (goal != ExplorationGoal.COMPLETE_COVERAGE) {
            return false
        }
        return metrics.isComplete(targetCoverage)
    }

    /**
     * Get the exploration frontier (screens needing more exploration).
     *
     * @param limit Maximum number of screens to return
     * @return List of screen IDs sorted by priority
     */
    fun getExplorationFrontier(limit: Int = 5): List<String> {
        return _explorationFrontier.take(limit).map { it.screenId }
    }
}

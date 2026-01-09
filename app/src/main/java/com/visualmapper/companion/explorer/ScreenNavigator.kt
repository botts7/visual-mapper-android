package com.visualmapper.companion.explorer

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.LinkedList

/**
 * Screen Navigator - Manages navigation stack and backtracking during exploration.
 * Extracted from AppExplorerService for modularity and testability.
 *
 * Phase 1 Refactor: This handles all screen-level navigation logic:
 * - Tracking visited screens
 * - Managing navigation stack (for backtracking)
 * - Detecting screen transitions
 * - Deciding when to backtrack vs. explore deeper
 *
 * Thread Safety: Uses synchronized collections and atomic state updates.
 */
class ScreenNavigator(
    private val maxStackDepth: Int = 20,
    private val maxVisitsPerScreen: Int = 5
) {
    companion object {
        private const val TAG = "ScreenNavigator"
    }

    /**
     * Represents a screen in the navigation stack
     */
    data class ScreenEntry(
        val screenId: String,
        val packageName: String,
        val activityName: String?,
        val timestamp: Long = System.currentTimeMillis(),
        val elementCount: Int = 0,
        val unexploredElements: Int = 0
    )

    /**
     * Result of a navigation decision
     */
    sealed class NavigationDecision {
        data class ExploreScreen(val screenId: String) : NavigationDecision()
        data class BacktrackTo(val screenId: String, val reason: String) : NavigationDecision()
        data class BacktrackSteps(val steps: Int, val reason: String) : NavigationDecision()
        object StayOnScreen : NavigationDecision()
        data class DeadEnd(val reason: String) : NavigationDecision()
    }

    // Navigation stack (most recent at end)
    private val navigationStack = LinkedList<ScreenEntry>()

    // Visited screens with visit counts
    private val visitCounts = mutableMapOf<String, Int>()

    // Screens marked as dead-ends (fully explored or stuck)
    private val deadEndScreens = mutableSetOf<String>()

    // Current screen tracking
    private val _currentScreen = MutableStateFlow<ScreenEntry?>(null)
    val currentScreen: StateFlow<ScreenEntry?> = _currentScreen

    // =========================================================================
    // Screen Tracking
    // =========================================================================

    /**
     * Record entering a new screen.
     * Updates navigation stack and visit counts.
     *
     * @return true if this is a new screen, false if revisiting
     */
    @Synchronized
    fun enterScreen(
        screenId: String,
        packageName: String,
        activityName: String?,
        elementCount: Int = 0,
        unexploredElements: Int = 0
    ): Boolean {
        val isNewScreen = !visitCounts.containsKey(screenId)
        val visitCount = visitCounts.getOrDefault(screenId, 0) + 1
        visitCounts[screenId] = visitCount

        val entry = ScreenEntry(
            screenId = screenId,
            packageName = packageName,
            activityName = activityName,
            elementCount = elementCount,
            unexploredElements = unexploredElements
        )

        // Add to stack (avoid duplicates at top)
        if (navigationStack.isEmpty() || navigationStack.last.screenId != screenId) {
            navigationStack.addLast(entry)

            // Trim stack if too deep
            while (navigationStack.size > maxStackDepth) {
                navigationStack.removeFirst()
            }
        } else {
            // Update the top entry with new counts
            navigationStack.removeLast()
            navigationStack.addLast(entry)
        }

        _currentScreen.value = entry

        Log.d(TAG, "Entered screen: $screenId (visit #$visitCount, stack depth: ${navigationStack.size})")
        return isNewScreen
    }

    /**
     * Get the current screen ID
     */
    fun getCurrentScreenId(): String? = _currentScreen.value?.screenId

    /**
     * Check if a screen has been visited before
     */
    fun hasVisited(screenId: String): Boolean = visitCounts.containsKey(screenId)

    /**
     * Get visit count for a screen
     */
    fun getVisitCount(screenId: String): Int = visitCounts.getOrDefault(screenId, 0)

    /**
     * Check if a screen has been visited too many times
     */
    fun isOvervisited(screenId: String): Boolean =
        getVisitCount(screenId) >= maxVisitsPerScreen

    /**
     * Mark a screen as a dead-end (no more useful exploration possible)
     */
    @Synchronized
    fun markAsDeadEnd(screenId: String, reason: String? = null) {
        deadEndScreens.add(screenId)
        Log.i(TAG, "Marked $screenId as dead-end: ${reason ?: "no more elements"}")
    }

    /**
     * Check if a screen is marked as a dead-end
     */
    fun isDeadEnd(screenId: String): Boolean = deadEndScreens.contains(screenId)

    // =========================================================================
    // Navigation Decisions
    // =========================================================================

    /**
     * Decide what to do based on current exploration state.
     *
     * @param currentScreenId Current screen ID
     * @param hasUnexploredElements Whether current screen has unexplored elements
     * @param isStuck Whether we're in a stuck state
     * @return NavigationDecision indicating what action to take
     */
    @Synchronized
    fun decideNavigation(
        currentScreenId: String,
        hasUnexploredElements: Boolean,
        isStuck: Boolean
    ): NavigationDecision {
        // If stuck, try backtracking
        if (isStuck) {
            return decideBacktrack("stuck state")
        }

        // If current screen has unexplored elements, stay
        if (hasUnexploredElements && !isOvervisited(currentScreenId)) {
            return NavigationDecision.StayOnScreen
        }

        // If overvisited but still has elements, mark as dead-end
        if (isOvervisited(currentScreenId)) {
            markAsDeadEnd(currentScreenId, "max visits reached")
            return decideBacktrack("overvisited")
        }

        // No more elements on current screen
        if (!hasUnexploredElements) {
            markAsDeadEnd(currentScreenId, "no unexplored elements")
            return decideBacktrack("exhausted elements")
        }

        return NavigationDecision.StayOnScreen
    }

    /**
     * Decide where to backtrack to.
     */
    private fun decideBacktrack(reason: String): NavigationDecision {
        // Find the most recent screen that isn't a dead-end and isn't overvisited
        val candidates = navigationStack.filter { entry ->
            !isDeadEnd(entry.screenId) && !isOvervisited(entry.screenId)
        }

        if (candidates.isEmpty()) {
            Log.w(TAG, "No backtrack candidates available - exploration may be complete")
            return NavigationDecision.DeadEnd("no viable backtrack targets")
        }

        // Prefer screens with unexplored elements
        val best = candidates.lastOrNull { it.unexploredElements > 0 }
            ?: candidates.last()

        val stepsBack = navigationStack.size - navigationStack.indexOf(navigationStack.find { it.screenId == best.screenId }) - 1

        Log.i(TAG, "Backtracking to ${best.screenId} ($stepsBack steps back) - reason: $reason")

        return if (stepsBack > 0) {
            NavigationDecision.BacktrackSteps(stepsBack, reason)
        } else {
            NavigationDecision.BacktrackTo(best.screenId, reason)
        }
    }

    /**
     * Pop screens from stack during backtracking
     */
    @Synchronized
    fun popSteps(steps: Int): List<ScreenEntry> {
        val popped = mutableListOf<ScreenEntry>()
        repeat(minOf(steps, navigationStack.size - 1)) {
            if (navigationStack.isNotEmpty()) {
                popped.add(navigationStack.removeLast())
            }
        }

        if (navigationStack.isNotEmpty()) {
            _currentScreen.value = navigationStack.last
        }

        return popped
    }

    // =========================================================================
    // Statistics & State
    // =========================================================================

    /**
     * Get navigation statistics
     */
    fun getStatistics(): NavigationStatistics {
        return NavigationStatistics(
            stackDepth = navigationStack.size,
            totalScreensVisited = visitCounts.size,
            deadEndCount = deadEndScreens.size,
            totalVisits = visitCounts.values.sum()
        )
    }

    data class NavigationStatistics(
        val stackDepth: Int,
        val totalScreensVisited: Int,
        val deadEndCount: Int,
        val totalVisits: Int
    )

    /**
     * Get list of all visited screen IDs
     */
    fun getVisitedScreenIds(): Set<String> = visitCounts.keys.toSet()

    /**
     * Get the navigation stack (copy)
     */
    fun getNavigationStack(): List<ScreenEntry> = navigationStack.toList()

    /**
     * Reset navigator state (for new exploration)
     */
    @Synchronized
    fun reset() {
        navigationStack.clear()
        visitCounts.clear()
        deadEndScreens.clear()
        _currentScreen.value = null
        Log.i(TAG, "Navigator reset")
    }

    /**
     * Export navigation data for persistence/analysis
     */
    fun exportNavigationData(): NavigationData {
        return NavigationData(
            stack = navigationStack.toList(),
            visitCounts = visitCounts.toMap(),
            deadEnds = deadEndScreens.toSet()
        )
    }

    /**
     * Import navigation data (for session resume)
     */
    @Synchronized
    fun importNavigationData(data: NavigationData) {
        navigationStack.clear()
        navigationStack.addAll(data.stack)
        visitCounts.clear()
        visitCounts.putAll(data.visitCounts)
        deadEndScreens.clear()
        deadEndScreens.addAll(data.deadEnds)
        _currentScreen.value = navigationStack.lastOrNull()
        Log.i(TAG, "Imported navigation data: ${visitCounts.size} screens, ${deadEndScreens.size} dead-ends")
    }

    data class NavigationData(
        val stack: List<ScreenEntry>,
        val visitCounts: Map<String, Int>,
        val deadEnds: Set<String>
    )
}

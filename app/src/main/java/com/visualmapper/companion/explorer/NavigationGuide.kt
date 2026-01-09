package com.visualmapper.companion.explorer

import android.util.Log
import kotlinx.coroutines.delay

/**
 * NavigationGuide - Helps navigate to specific screens using the navigation graph.
 *
 * Used by GuidedCorrectionActivity to navigate to problematic screens
 * so the user can demonstrate the correct action.
 */
class NavigationGuide(
    private val navigationGraph: NavigationGraph,
    private val gestureExecutor: GestureExecutor
) {
    companion object {
        private const val TAG = "NavigationGuide"
        private const val STEP_DELAY_MS = 1500L
        private const val MAX_NAVIGATION_ATTEMPTS = 3
    }

    /**
     * Get the navigation path from current screen to target screen.
     *
     * @param fromScreenId Current screen ID
     * @param toScreenId Target screen ID
     * @return List of NavigationStep if path exists, null otherwise
     */
    fun getNavigationPath(fromScreenId: String, toScreenId: String): List<NavigationStep>? {
        if (fromScreenId == toScreenId) {
            return emptyList()  // Already on target screen
        }

        val path = navigationGraph.findPath(fromScreenId, toScreenId)
        if (path == null) {
            Log.w(TAG, "No path found from $fromScreenId to $toScreenId")
            return null
        }

        // Convert path to NavigationSteps
        return path.map { (screenId, elementId) ->
            val screen = getScreenInfo(screenId)
            val element = screen?.let { getElementInfo(it, elementId) }

            NavigationStep(
                fromScreenId = screenId,
                toScreenId = navigationGraph.getDestination(screenId, elementId) ?: "",
                elementId = elementId,
                elementText = element?.text,
                elementResourceId = element?.resourceId,
                x = element?.centerX ?: 540,
                y = element?.centerY ?: 960
            )
        }
    }

    /**
     * Auto-navigate to a target screen using the known navigation graph.
     *
     * @param fromScreenId Current screen ID
     * @param toScreenId Target screen ID
     * @param onProgress Callback for progress updates
     * @return NavigationResult indicating success or failure
     */
    suspend fun navigateToScreen(
        fromScreenId: String,
        toScreenId: String,
        onProgress: ((step: Int, total: Int, description: String) -> Unit)? = null
    ): NavigationResult {
        Log.i(TAG, "Navigating from $fromScreenId to $toScreenId")

        // Check if already on target
        if (fromScreenId == toScreenId) {
            return NavigationResult.AlreadyOnScreen
        }

        // Get path
        val path = getNavigationPath(fromScreenId, toScreenId)
        if (path == null) {
            Log.w(TAG, "No navigation path found")
            return NavigationResult.NoPath
        }

        if (path.isEmpty()) {
            return NavigationResult.AlreadyOnScreen
        }

        Log.i(TAG, "Found path with ${path.size} steps")

        // Execute each step
        for ((index, step) in path.withIndex()) {
            onProgress?.invoke(index + 1, path.size, "Navigating to ${step.toScreenId.take(12)}...")

            Log.d(TAG, "Step ${index + 1}/${path.size}: Tap ${step.elementId} at (${step.x}, ${step.y})")

            try {
                // Execute tap
                gestureExecutor.performTap(step.x, step.y)

                // Wait for screen transition
                delay(STEP_DELAY_MS)

                // TODO: Verify we're on the expected screen
                // For now, assume success

            } catch (e: Exception) {
                Log.e(TAG, "Navigation step failed at ${step.elementId}", e)
                return NavigationResult.PartialSuccess(
                    stepsCompleted = index,
                    totalSteps = path.size,
                    failedAt = step.elementId
                )
            }
        }

        Log.i(TAG, "Navigation complete - arrived at $toScreenId")
        return NavigationResult.Success
    }

    /**
     * Navigate to a screen, with retry logic.
     */
    suspend fun navigateWithRetry(
        fromScreenId: String,
        toScreenId: String,
        maxAttempts: Int = MAX_NAVIGATION_ATTEMPTS
    ): NavigationResult {
        var lastResult: NavigationResult = NavigationResult.NoPath

        for (attempt in 1..maxAttempts) {
            Log.i(TAG, "Navigation attempt $attempt/$maxAttempts")

            lastResult = navigateToScreen(fromScreenId, toScreenId)

            when (lastResult) {
                is NavigationResult.Success -> return lastResult
                is NavigationResult.AlreadyOnScreen -> return lastResult
                is NavigationResult.NoPath -> {
                    // No point retrying if there's no path
                    return lastResult
                }
                is NavigationResult.PartialSuccess -> {
                    // Try again from where we got to
                    Log.w(TAG, "Partial success, retrying...")
                    delay(500)
                }
                is NavigationResult.Failed -> {
                    Log.w(TAG, "Navigation failed: ${lastResult.reason}")
                    delay(500)
                }
            }
        }

        return lastResult
    }

    /**
     * Get the shortest path length to a screen.
     */
    fun getPathLength(fromScreenId: String, toScreenId: String): Int? {
        if (fromScreenId == toScreenId) return 0
        return navigationGraph.findPath(fromScreenId, toScreenId)?.size
    }

    /**
     * Check if a screen is reachable from another screen.
     */
    fun isScreenReachable(fromScreenId: String, toScreenId: String): Boolean {
        return getPathLength(fromScreenId, toScreenId) != null
    }

    /**
     * Get all screens that can reach the target screen (for debugging).
     */
    fun getScreensLeadingTo(targetScreenId: String): List<String> {
        val allScreens = navigationGraph.getAllScreens()
        return allScreens.filter { screenId ->
            screenId != targetScreenId && isScreenReachable(screenId, targetScreenId)
        }
    }

    /**
     * Get information about a screen from the exploration state.
     */
    private fun getScreenInfo(screenId: String): ExploredScreen? {
        val service = AppExplorerService.getInstance()
        return service?.getExplorationResult()?.state?.exploredScreens?.get(screenId)
    }

    /**
     * Get information about an element on a screen.
     */
    private fun getElementInfo(screen: ExploredScreen, elementId: String): ClickableElement? {
        return screen.clickableElements.find { it.elementId == elementId }
    }

    /**
     * Get a human-readable description of the navigation path.
     */
    fun describeNavigationPath(path: List<NavigationStep>): String {
        if (path.isEmpty()) return "Already on target screen"

        return buildString {
            appendLine("Navigation path (${path.size} steps):")
            path.forEachIndexed { index, step ->
                val elementDesc = step.elementText ?: step.elementResourceId ?: step.elementId
                appendLine("  ${index + 1}. Tap \"$elementDesc\"")
            }
        }
    }
}

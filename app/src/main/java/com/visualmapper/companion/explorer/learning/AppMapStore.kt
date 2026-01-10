package com.visualmapper.companion.explorer.learning

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores learned app navigation maps for faster subsequent explorations.
 * Persists screen structures, navigation paths, and reliability scores.
 */
class AppMapStore(context: Context) {

    companion object {
        private const val TAG = "AppMapStore"
        private const val PREFS_NAME = "app_maps"
        private const val KEY_PREFIX = "map_"
        private const val MAX_APPS = 50 // Limit stored app maps

        @Volatile
        private var instance: AppMapStore? = null

        fun getInstance(context: Context): AppMapStore {
            return instance ?: synchronized(this) {
                instance ?: AppMapStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // In-memory cache for current session
    private val cachedMaps: MutableMap<String, LearnedAppMap> = mutableMapOf()

    /**
     * Save or update an app map
     */
    fun saveAppMap(appMap: LearnedAppMap) {
        try {
            val updated = appMap.copy(lastUpdated = System.currentTimeMillis())
            cachedMaps[appMap.appPackage] = updated
            prefs.edit()
                .putString("$KEY_PREFIX${appMap.appPackage}", json.encodeToString(updated))
                .apply()
            Log.d(TAG, "Saved app map for ${appMap.appPackage}: ${appMap.screens.size} screens")

            // Cleanup old maps if needed
            cleanupOldMaps()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving app map", e)
        }
    }

    /**
     * Get a previously learned app map
     */
    fun getAppMap(packageName: String): LearnedAppMap? {
        // Check cache first
        cachedMaps[packageName]?.let { return it }

        return try {
            val jsonStr = prefs.getString("$KEY_PREFIX$packageName", null) ?: return null
            val map = json.decodeFromString<LearnedAppMap>(jsonStr)
            cachedMaps[packageName] = map
            Log.d(TAG, "Loaded app map for $packageName: ${map.screens.size} screens")
            map
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app map for $packageName", e)
            null
        }
    }

    /**
     * Check if we have a map for an app
     */
    fun hasAppMap(packageName: String): Boolean {
        return cachedMaps.containsKey(packageName) ||
               prefs.contains("$KEY_PREFIX$packageName")
    }

    /**
     * Get all stored app package names
     */
    fun getStoredPackages(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }
    }

    /**
     * Record a screen discovered during exploration
     */
    fun recordScreen(
        packageName: String,
        screenId: String,
        activityName: String?,
        title: String?,
        hasScrollableContent: Boolean,
        keyElements: List<String>
    ) {
        val map = getOrCreateMap(packageName)
        val existingScreen = map.screens[screenId]

        val updatedScreen = LearnedScreen(
            screenId = screenId,
            activityName = activityName ?: existingScreen?.activityName,
            title = title ?: existingScreen?.title,
            hasScrollableContent = hasScrollableContent,
            keyElements = (existingScreen?.keyElements.orEmpty() + keyElements).distinct().take(20),
            childScreens = existingScreen?.childScreens.orEmpty(),
            visitCount = (existingScreen?.visitCount ?: 0) + 1,
            lastVisited = System.currentTimeMillis()
        )

        val updatedMap = map.copy(
            screens = map.screens + (screenId to updatedScreen),
            lastUpdated = System.currentTimeMillis()
        )
        saveAppMap(updatedMap)
    }

    /**
     * Record a navigation transition
     */
    fun recordTransition(
        packageName: String,
        fromScreenId: String,
        toScreenId: String,
        elementId: String?,
        elementText: String?,
        success: Boolean
    ) {
        val map = getOrCreateMap(packageName)

        // Find or create navigation path
        val pathKey = "$fromScreenId->$toScreenId"
        val existingPaths = map.navigationPaths.toMutableList()
        val existingPath = existingPaths.find { it.fromScreen == fromScreenId && it.toScreen == toScreenId }

        if (existingPath != null) {
            // Update existing path reliability
            val newReliability = updateReliability(existingPath.reliability, success)
            val updatedPath = existingPath.copy(
                reliability = newReliability,
                successCount = existingPath.successCount + if (success) 1 else 0,
                failureCount = existingPath.failureCount + if (!success) 1 else 0,
                lastUsed = System.currentTimeMillis()
            )
            existingPaths.remove(existingPath)
            existingPaths.add(updatedPath)
        } else if (success) {
            // Add new successful path
            val step = NavigationStep(
                actionType = "click",
                elementId = elementId,
                elementText = elementText
            )
            existingPaths.add(NavigationPath(
                fromScreen = fromScreenId,
                toScreen = toScreenId,
                steps = listOf(step),
                reliability = 0.7f, // Start with reasonable confidence
                successCount = 1,
                failureCount = 0,
                lastUsed = System.currentTimeMillis()
            ))
        }

        // Update child screens
        val screens = map.screens.toMutableMap()
        val fromScreen = screens[fromScreenId]
        if (fromScreen != null && success) {
            val childScreens = (fromScreen.childScreens + toScreenId).distinct()
            screens[fromScreenId] = fromScreen.copy(childScreens = childScreens)
        }

        val updatedMap = map.copy(
            screens = screens,
            navigationPaths = existingPaths,
            lastUpdated = System.currentTimeMillis()
        )
        saveAppMap(updatedMap)
    }

    /**
     * Update navigation reliability based on success/failure
     * Uses exponential moving average
     */
    private fun updateReliability(current: Float, success: Boolean): Float {
        val alpha = 0.2f // Learning rate
        val newValue = if (success) 1.0f else 0.0f
        return (1 - alpha) * current + alpha * newValue
    }

    /**
     * Record a menu pattern discovered
     */
    fun recordMenuPattern(
        packageName: String,
        menuType: String, // "hamburger", "bottom_nav", "tab_bar", "drawer"
        triggerElement: String,
        menuItems: List<String>
    ) {
        val map = getOrCreateMap(packageName)

        // Avoid duplicates
        val existingPatterns = map.menuPatterns.toMutableList()
        val existing = existingPatterns.find { it.triggerElement == triggerElement }

        if (existing != null) {
            // Update with new items
            val updatedItems = (existing.menuItems + menuItems).distinct()
            existingPatterns.remove(existing)
            existingPatterns.add(existing.copy(menuItems = updatedItems))
        } else {
            existingPatterns.add(MenuPattern(
                type = menuType,
                triggerElement = triggerElement,
                menuItems = menuItems
            ))
        }

        val updatedMap = map.copy(
            menuPatterns = existingPatterns,
            lastUpdated = System.currentTimeMillis()
        )
        saveAppMap(updatedMap)
    }

    /**
     * Mark a screen as a blocker (login, password, etc.)
     */
    fun markBlockerScreen(packageName: String, screenId: String, blockerType: String) {
        val map = getOrCreateMap(packageName)
        val blockers = (map.blockerScreens + (screenId to blockerType)).toMap()
        saveAppMap(map.copy(blockerScreens = blockers))
        Log.d(TAG, "Marked $screenId as blocker ($blockerType) for $packageName")
    }

    /**
     * Check if a screen is known as a blocker
     */
    fun isBlockerScreen(packageName: String, screenId: String): Boolean {
        return getAppMap(packageName)?.blockerScreens?.containsKey(screenId) == true
    }

    /**
     * Get unexplored screens (discovered but not fully explored)
     */
    fun getUnexploredScreens(packageName: String): List<String> {
        val map = getAppMap(packageName) ?: return emptyList()
        return map.screens.values
            .filter { !it.fullyExplored }
            .sortedByDescending { it.visitCount }
            .map { it.screenId }
    }

    /**
     * Mark a screen as fully explored
     */
    fun markFullyExplored(packageName: String, screenId: String) {
        val map = getAppMap(packageName) ?: return
        val screen = map.screens[screenId] ?: return

        val updatedScreen = screen.copy(fullyExplored = true)
        val updatedMap = map.copy(
            screens = map.screens + (screenId to updatedScreen)
        )
        saveAppMap(updatedMap)
    }

    /**
     * Find the best path between two screens using Dijkstra.
     * Considers path reliability as edge weights.
     */
    fun findBestPath(packageName: String, fromScreenId: String, toScreenId: String): NavigationPath? {
        if (fromScreenId == toScreenId) return null

        val map = getAppMap(packageName) ?: return null

        // Direct path lookup first (most efficient)
        val directPath = map.navigationPaths.find {
            it.fromScreen == fromScreenId && it.toScreen == toScreenId && it.reliability > 0.3f
        }
        if (directPath != null) {
            return directPath
        }

        // Build graph and run Dijkstra for multi-hop paths
        val graph = buildNavigationGraph(map)
        val dijkstraPath = findDijkstraPath(graph, fromScreenId, toScreenId)

        return dijkstraPath?.let { path ->
            constructNavigationPath(map, fromScreenId, toScreenId, path)
        }
    }

    /**
     * Build a navigation graph from the app map.
     * Returns Map<fromScreen, List<Triple<toScreen, elementId, reliability>>>
     */
    private fun buildNavigationGraph(
        map: LearnedAppMap
    ): Map<String, List<Triple<String, String, Float>>> {
        val graph = mutableMapOf<String, MutableList<Triple<String, String, Float>>>()

        for (navPath in map.navigationPaths) {
            if (navPath.reliability < 0.1f) continue  // Skip unreliable paths

            val edges = graph.getOrPut(navPath.fromScreen) { mutableListOf() }
            val elementId = navPath.steps.firstOrNull()?.elementId ?: "unknown"
            edges.add(Triple(navPath.toScreen, elementId, navPath.reliability))
        }
        return graph
    }

    /**
     * Find path using Dijkstra algorithm with reliability as weights.
     * Returns list of (toScreen, elementId) pairs representing the path.
     */
    private fun findDijkstraPath(
        graph: Map<String, List<Triple<String, String, Float>>>,
        fromScreen: String,
        toScreen: String
    ): List<Pair<String, String>>? {
        data class PathState(
            val screen: String,
            val cost: Float,
            val path: List<Pair<String, String>>  // List of (nextScreen, elementId)
        )

        val visited = mutableSetOf<String>()
        val queue = java.util.PriorityQueue<PathState>(compareBy { it.cost })
        queue.add(PathState(fromScreen, 0f, emptyList()))

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.screen in visited) continue
            visited.add(current.screen)

            if (current.screen == toScreen && current.path.isNotEmpty()) {
                return current.path
            }

            val edges = graph[current.screen] ?: continue
            for ((nextScreen, elementId, reliability) in edges) {
                if (nextScreen in visited) continue

                val edgeCost = 1f - reliability  // Higher reliability = lower cost
                val newPath = current.path + (nextScreen to elementId)

                if (nextScreen == toScreen) {
                    return newPath
                }

                queue.add(PathState(nextScreen, current.cost + edgeCost, newPath))
            }
        }
        return null
    }

    /**
     * Construct a NavigationPath from the Dijkstra result.
     */
    private fun constructNavigationPath(
        map: LearnedAppMap,
        fromScreen: String,
        toScreen: String,
        path: List<Pair<String, String>>
    ): NavigationPath {
        val steps = mutableListOf<NavigationStep>()
        var currentScreen = fromScreen

        for ((nextScreen, elementId) in path) {
            // Find the original path to get step details
            val originalPath = map.navigationPaths.find {
                it.fromScreen == currentScreen && it.toScreen == nextScreen
            }

            val step = originalPath?.steps?.firstOrNull() ?: NavigationStep(
                actionType = "click",
                elementId = elementId,
                elementText = null,
                bounds = null
            )
            steps.add(step)
            currentScreen = nextScreen
        }

        // Calculate combined reliability (product of individual reliabilities)
        var combinedReliability = 1f
        currentScreen = fromScreen
        for ((nextScreen, _) in path) {
            val pathReliability = map.navigationPaths.find {
                it.fromScreen == currentScreen && it.toScreen == nextScreen
            }?.reliability ?: 0.5f
            combinedReliability *= pathReliability
            currentScreen = nextScreen
        }

        return NavigationPath(
            fromScreen = fromScreen,
            toScreen = toScreen,
            steps = steps,
            reliability = combinedReliability,
            successCount = 0,
            failureCount = 0,
            lastUsed = System.currentTimeMillis()
        )
    }

    /**
     * Get entry points (screens reachable from launcher)
     */
    fun getEntryPoints(packageName: String): List<String> {
        return getAppMap(packageName)?.entryPoints.orEmpty()
    }

    /**
     * Record an entry point
     */
    fun recordEntryPoint(packageName: String, screenId: String) {
        val map = getOrCreateMap(packageName)
        if (!map.entryPoints.contains(screenId)) {
            saveAppMap(map.copy(entryPoints = map.entryPoints + screenId))
        }
    }

    /**
     * Get statistics about stored app maps
     */
    fun getStats(): AppMapStats {
        val packages = getStoredPackages()
        var totalScreens = 0
        var totalPaths = 0

        packages.forEach { pkg ->
            getAppMap(pkg)?.let { map ->
                totalScreens += map.screens.size
                totalPaths += map.navigationPaths.size
            }
        }

        return AppMapStats(
            totalApps = packages.size,
            totalScreens = totalScreens,
            totalPaths = totalPaths
        )
    }

    /**
     * Clear map for a specific app
     */
    fun clearAppMap(packageName: String) {
        cachedMaps.remove(packageName)
        prefs.edit().remove("$KEY_PREFIX$packageName").apply()
        Log.d(TAG, "Cleared app map for $packageName")
    }

    /**
     * Clear all stored maps
     */
    fun clearAll() {
        cachedMaps.clear()
        prefs.edit().clear().apply()
        Log.d(TAG, "Cleared all app maps")
    }

    /**
     * Get or create a map for a package
     */
    private fun getOrCreateMap(packageName: String): LearnedAppMap {
        return getAppMap(packageName) ?: LearnedAppMap(
            appPackage = packageName,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Cleanup old maps if we have too many
     */
    private fun cleanupOldMaps() {
        val packages = getStoredPackages()
        if (packages.size <= MAX_APPS) return

        // Get maps with last updated times
        val mapsWithTimes = packages.mapNotNull { pkg ->
            getAppMap(pkg)?.let { map -> pkg to map.lastUpdated }
        }.sortedBy { it.second }

        // Remove oldest maps
        val toRemove = mapsWithTimes.take(packages.size - MAX_APPS)
        toRemove.forEach { (pkg, _) ->
            clearAppMap(pkg)
            Log.d(TAG, "Cleaned up old map for $pkg")
        }
    }
}

/**
 * Learned app map containing all navigation knowledge
 */
@Serializable
data class LearnedAppMap(
    val appPackage: String,
    val lastUpdated: Long,
    val screens: Map<String, LearnedScreen> = emptyMap(),
    val navigationPaths: List<NavigationPath> = emptyList(),
    val entryPoints: List<String> = emptyList(),
    val menuPatterns: List<MenuPattern> = emptyList(),
    val blockerScreens: Map<String, String> = emptyMap() // screenId -> blockerType
)

/**
 * Learned screen structure
 */
@Serializable
data class LearnedScreen(
    val screenId: String,
    val activityName: String? = null,
    val title: String? = null,
    val keyElements: List<String> = emptyList(),
    val hasScrollableContent: Boolean = false,
    val childScreens: List<String> = emptyList(),
    val visitCount: Int = 0,
    val lastVisited: Long = 0,
    val fullyExplored: Boolean = false
)

/**
 * Navigation path between screens
 */
@Serializable
data class NavigationPath(
    val fromScreen: String,
    val toScreen: String,
    val steps: List<NavigationStep>,
    val reliability: Float, // 0.0-1.0 based on success rate
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsed: Long = 0
) {
    val totalAttempts: Int get() = successCount + failureCount
}

/**
 * A single step in a navigation path
 */
@Serializable
data class NavigationStep(
    val actionType: String, // "click", "scroll", "back", "menu"
    val elementId: String? = null,
    val elementText: String? = null,
    val bounds: ElementBounds? = null
)

/**
 * Element bounds for tap positioning
 */
@Serializable
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Menu pattern discovered in an app
 */
@Serializable
data class MenuPattern(
    val type: String, // "hamburger", "bottom_nav", "tab_bar", "drawer"
    val triggerElement: String,
    val menuItems: List<String> = emptyList()
)

/**
 * Statistics about stored app maps
 */
data class AppMapStats(
    val totalApps: Int,
    val totalScreens: Int,
    val totalPaths: Int
)

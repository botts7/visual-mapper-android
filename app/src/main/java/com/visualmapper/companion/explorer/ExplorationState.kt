package com.visualmapper.companion.explorer

import android.os.Parcelable
import android.util.Log
import kotlinx.parcelize.Parcelize
import java.security.MessageDigest

/**
 * App Explorer Data Models
 *
 * Used by the Smart Flow Generator to autonomously explore apps
 * and build complete navigation graphs with all UI elements.
 */

/**
 * Overall exploration state for an app
 */
data class ExplorationState(
    val packageName: String,
    val exploredScreens: MutableMap<String, ExploredScreen> = mutableMapOf(),
    val explorationQueue: ArrayDeque<ExplorationTarget> = ArrayDeque(),
    val visitedElements: MutableSet<String> = mutableSetOf(),
    var status: ExplorationStatus = ExplorationStatus.NOT_STARTED,
    var startTime: Long = System.currentTimeMillis(),
    var endTime: Long? = null,
    val config: ExplorationConfig = ExplorationConfig(),
    // Issue tracking for robustness - problems encountered during exploration
    val issues: MutableList<ExplorationIssue> = mutableListOf(),
    // Track retry counts per element to detect loops
    val elementRetryCount: MutableMap<String, Int> = mutableMapOf(),
    // Track screens that couldn't be reached - stop wasting time on them
    val unreachableScreens: MutableSet<String> = mutableSetOf(),
    val screenReachFailures: MutableMap<String, Int> = mutableMapOf(),
    // Patterns that caused app to close - learned during exploration
    val dangerousPatterns: MutableSet<String> = mutableSetOf(),
    // Count of recovery attempts (app re-launches)
    var recoveryAttempts: Int = 0,
    // Navigation graph: tracks which element leads to which screen
    val navigationGraph: NavigationGraph = NavigationGraph(),
    // Non-destructive exploration: Track toggles/switches changed during exploration
    val changedToggles: MutableList<ChangedToggle> = mutableListOf(),
    // === MULTI-PASS EXPLORATION ===
    // Current pass number (1-based, increments with each "Run Another Pass")
    var passNumber: Int = 1,
    // Cumulative sensors discovered across all passes (deduped by elementId)
    val cumulativeSensors: MutableList<GeneratedSensor> = mutableListOf(),
    // Cumulative actions discovered across all passes (deduped by elementId)
    val cumulativeActions: MutableList<GeneratedAction> = mutableListOf()
)

/**
 * Tracks a toggle/switch/checkbox that was changed during exploration.
 * Used for non-destructive exploration - reverts changes after exploration.
 */
data class ChangedToggle(
    val screenId: String,
    val elementId: String,
    val resourceId: String?,
    val text: String?,
    val centerX: Int,
    val centerY: Int,
    val originalChecked: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Issue encountered during exploration.
 * Enhanced with correction context for guided manual correction feature.
 */
@Parcelize
data class ExplorationIssue(
    val screenId: String,
    val elementId: String?,
    val issueType: IssueType,
    val description: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Enhanced fields for guided correction
    val elementBounds: ElementBounds? = null,
    val elementText: String? = null,
    val elementResourceId: String? = null,
    val elementClassName: String? = null,
    // Track if this issue has been corrected
    var corrected: Boolean = false,
    var correctionType: GuidedCorrectionType? = null
) : Parcelable

/**
 * Types of corrections that can be applied to exploration issues.
 */
enum class GuidedCorrectionType {
    TAP_DEMONSTRATED,      // User showed correct tap location
    SCROLL_DEMONSTRATED,   // User showed scroll was needed first
    MARK_IGNORE,           // Element should be ignored in future
    MARK_DANGEROUS,        // Element causes problems, avoid it
    SKIP_ISSUE             // User chose to skip this issue
}

/**
 * Types of issues that can occur during exploration
 */
enum class IssueType {
    ELEMENT_STUCK,        // Couldn't interact with element after retries
    BACK_FAILED,          // Back navigation failed
    APP_MINIMIZED,        // App was minimized unexpectedly
    APP_LEFT,             // Left target app and couldn't return
    TIMEOUT,              // Screen transition timeout
    SCROLL_FAILED,        // Couldn't scroll to reveal element
    DANGEROUS_ELEMENT,    // Element caused app to close
    RECOVERY_FAILED,      // Failed to recover from error state
    BLOCKER_SCREEN        // Login/auth screen blocking exploration
}

/**
 * Exploration status
 */
enum class ExplorationStatus {
    NOT_STARTED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    STOPPED,      // User manually stopped (successful partial completion)
    CANCELLED,    // System/error cancelled
    ERROR
}

/**
 * Exploration goal - determines stopping conditions
 */
enum class ExplorationGoal {
    QUICK_SCAN,         // Current behavior: fast survey with hard limits
    DEEP_MAP,           // Higher limits, thorough scrolling, more time
    COMPLETE_COVERAGE   // Goal-based: 90%+ coverage OR time cap (30 min)
}

/**
 * Coverage metrics for goal-oriented exploration
 */
data class CoverageMetrics(
    val totalScreensDiscovered: Int = 0,
    val screensFullyExplored: Int = 0,
    val screenCoveragePercent: Float = 0f,

    val totalElementsDiscovered: Int = 0,
    val elementsVisited: Int = 0,
    val elementCoveragePercent: Float = 0f,

    val totalScrollableContainers: Int = 0,
    val containersFullyScrolled: Int = 0,
    val scrollCoveragePercent: Float = 0f,

    val unexploredBranches: Int = 0,
    val explorationFrontier: List<String> = emptyList(),

    val overallCoverage: Float = 0f,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /**
     * Check if target coverage has been reached
     */
    fun isComplete(targetCoverage: Float = 0.90f): Boolean {
        return overallCoverage >= targetCoverage
    }

    /**
     * Get human-readable summary
     */
    fun summary(): String {
        return "Coverage: ${(overallCoverage * 100).toInt()}% " +
            "(${elementsVisited}/${totalElementsDiscovered} elements, " +
            "${screensFullyExplored}/${totalScreensDiscovered} screens, " +
            "${unexploredBranches} unexplored branches)"
    }
}

/**
 * Per-screen element statistics for coverage tracking
 */
data class ScreenElementStats(
    val screenId: String,
    val totalClickable: Int,
    var visitedClickable: Int = 0,
    val totalScrollable: Int,
    var scrolledScrollable: Int = 0,
    val totalTextElements: Int = 0
) {
    val isFullyExplored: Boolean
        get() = visitedClickable >= totalClickable && scrolledScrollable >= totalScrollable

    val coveragePercent: Float
        get() = if (totalClickable > 0) visitedClickable.toFloat() / totalClickable else 1f
}

/**
 * Item in the exploration frontier (screens with unexplored elements)
 */
data class ExplorationFrontierItem(
    val screenId: String,
    val unvisitedElementCount: Int,
    val lastVisited: Long,
    val priority: Int = 0
) : Comparable<ExplorationFrontierItem> {
    override fun compareTo(other: ExplorationFrontierItem): Int = other.priority - priority
}

/**
 * Represents a discovered screen in the app
 */
data class ExploredScreen(
    val screenId: String,
    val activity: String,
    val packageName: String,
    val clickableElements: MutableList<ClickableElement> = mutableListOf(),
    val scrollableContainers: MutableList<ScrollableContainer> = mutableListOf(),
    val textElements: MutableList<TextElement> = mutableListOf(),
    val inputFields: MutableList<InputField> = mutableListOf(),
    val timestamp: Long = System.currentTimeMillis(),
    var visitCount: Int = 1,
    val landmarks: List<String> = emptyList() // For screen identification
)

/**
 * A clickable UI element that can be explored
 */
data class ClickableElement(
    val elementId: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: ElementBounds,
    var explored: Boolean = false,
    var leadsToScreen: String? = null, // Screen ID this element navigates to
    var actionType: ClickableActionType = ClickableActionType.UNKNOWN
)

/**
 * Type of action a clickable element performs
 */
enum class ClickableActionType {
    UNKNOWN,
    NAVIGATION,       // Opens a new screen
    TOGGLE,           // Toggles a state (checkbox, switch)
    EXPAND_COLLAPSE,  // Expands/collapses content
    DIALOG,           // Opens a dialog
    MENU,             // Opens a menu
    BACK,             // Navigates back
    EXTERNAL,         // Opens external app/browser
    CLOSES_APP,       // Closes/minimizes the app (home, back to launcher)
    NO_EFFECT,        // Element had no visible effect when tapped
    TRIGGERS_DIALOG   // Triggers a system permission dialog (external but returns to app)
}

/**
 * A scrollable container that may reveal more elements
 */
data class ScrollableContainer(
    val elementId: String,
    val resourceId: String?,
    val className: String,
    val bounds: ElementBounds,
    val scrollDirection: ScrollDirection,
    var fullyScrolled: Boolean = false,
    val discoveredElements: MutableList<String> = mutableListOf() // Element IDs found by scrolling
)

/**
 * Scroll direction
 */
enum class ScrollDirection {
    VERTICAL,
    HORIZONTAL,
    BOTH
}

/**
 * A text element that can be captured as a sensor
 */
data class TextElement(
    val elementId: String,
    val resourceId: String?,
    val text: String,
    val contentDescription: String?,
    val className: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: ElementBounds,
    var suggestedSensorName: String? = null,
    var sensorType: SuggestedSensorType = SuggestedSensorType.TEXT
)

/**
 * Suggested sensor type based on element content
 */
enum class SuggestedSensorType {
    TEXT,
    NUMBER,
    PERCENTAGE,
    CURRENCY,
    DATE_TIME,
    BOOLEAN,
    STATUS
}

/**
 * An input field for text entry
 */
data class InputField(
    val elementId: String,
    val resourceId: String?,
    val hint: String?,
    val text: String?,
    val className: String,
    val centerX: Int,
    val centerY: Int,
    val bounds: ElementBounds,
    val inputType: InputFieldType = InputFieldType.TEXT
)

/**
 * Input field type
 */
enum class InputFieldType {
    TEXT,
    NUMBER,
    PASSWORD,
    EMAIL,
    PHONE,
    MULTILINE
}

/**
 * Element bounds
 */
@Parcelize
data class ElementBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) : Parcelable {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * Target for exploration (what to explore next)
 */
data class ExplorationTarget(
    val type: ExplorationTargetType,
    val screenId: String,
    val elementId: String? = null,
    val scrollContainerId: String? = null,
    val priority: Int = 0, // Higher = more important
    val bounds: ElementBounds? = null // Element bounds for tap/scroll
) : Comparable<ExplorationTarget> {
    override fun compareTo(other: ExplorationTarget): Int = other.priority - priority
}

/**
 * Navigation graph - tracks screen-to-screen transitions
 * Used for path planning and ensuring full coverage
 *
 * ENHANCED: Now tracks:
 * - Multiple destinations per element (conditional navigation)
 * - Blocker screen detection (password, login, setup screens)
 * - Visit counts per destination
 */
class NavigationGraph {
    // Map of (fromScreen, elementId) -> toScreen (legacy, kept for compatibility)
    private val transitions: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    // ENHANCED: Map of (fromScreen:elementId) -> ElementNavigation (tracks ALL destinations)
    private val elementNavigations: MutableMap<String, ElementNavigation> = mutableMapOf()

    // All known screens (discovered during exploration)
    private val knownScreens: MutableSet<String> = mutableSetOf()

    // Screens we've fully explored (all elements visited)
    private val fullyExploredScreens: MutableSet<String> = mutableSetOf()

    // ENHANCED: Screens identified as blockers (password, login, setup)
    private val blockerScreens: MutableSet<String> = mutableSetOf()

    // ENHANCED: Activity name patterns that indicate blocker screens
    private val blockerPatterns = listOf(
        "password", "login", "signin", "sign_in", "signup", "sign_up",
        "auth", "verify", "verification", "setup", "pin", "code",
        "otp", "2fa", "two_factor", "security", "lock", "unlock",
        "register", "registration", "forgot", "reset", "confirm",
        // Mode/account selection screens (login method choice)
        "modeselection", "mode_selection", "usermgmt", "user_mgmt",
        "accountselection", "account_selection", "chooseaccount", "choose_account",
        "selectaccount", "select_account", "authchoice", "auth_choice",
        "signinoptions", "signin_options", "loginoptions", "login_options"
    )

    /**
     * Record a transition: clicking element on fromScreen led to toScreen
     * ENHANCED: Now tracks all destinations and detects conditional navigation
     */
    fun recordTransition(fromScreen: String, elementId: String, toScreen: String,
                         toScreenActivity: String? = null) {
        knownScreens.add(fromScreen)
        knownScreens.add(toScreen)

        // Legacy single-destination tracking
        val screenTransitions = transitions.getOrPut(fromScreen) { mutableMapOf() }
        screenTransitions[elementId] = toScreen

        // ENHANCED: Multi-destination tracking
        val navKey = "$fromScreen:$elementId"
        val nav = elementNavigations.getOrPut(navKey) {
            ElementNavigation(fromScreen, elementId)
        }
        nav.addDestination(toScreen)

        // Check if this destination is a blocker screen
        if (toScreenActivity != null && isBlockerActivity(toScreenActivity)) {
            blockerScreens.add(toScreen)
            nav.markAsBlocker(toScreen)
        }
    }

    /**
     * Check if an activity name suggests it's a blocker screen
     */
    private fun isBlockerActivity(activityName: String): Boolean {
        val lowerName = activityName.lowercase()
        return blockerPatterns.any { pattern -> lowerName.contains(pattern) }
    }

    /**
     * Manually mark a screen as a blocker (based on UI analysis)
     */
    fun markAsBlocker(screenId: String) {
        blockerScreens.add(screenId)
    }

    /**
     * Check if a screen is a known blocker
     */
    fun isBlockerScreen(screenId: String): Boolean {
        return blockerScreens.contains(screenId)
    }

    /**
     * Get all blocker screens
     */
    fun getBlockerScreens(): Set<String> = blockerScreens.toSet()

    /**
     * Get navigation info for an element (all destinations, conditional status)
     */
    fun getElementNavigation(fromScreen: String, elementId: String): ElementNavigation? {
        return elementNavigations["$fromScreen:$elementId"]
    }

    /**
     * Get all conditional elements (those with 2+ destinations)
     */
    fun getConditionalElements(): List<ElementNavigation> {
        return elementNavigations.values.filter { it.isConditional() }
    }

    /**
     * Get the most likely "real" destination (non-blocker) for a conditional element
     */
    fun getRealDestination(fromScreen: String, elementId: String): String? {
        val nav = getElementNavigation(fromScreen, elementId) ?: return null
        // Return the destination that's NOT a blocker, or the most visited one
        return nav.destinations.keys
            .filter { !blockerScreens.contains(it) }
            .maxByOrNull { nav.destinations[it] ?: 0 }
            ?: nav.getMostVisitedDestination()
    }

    /**
     * Get the screen that clicking this element leads to (if known)
     */
    fun getDestination(fromScreen: String, elementId: String): String? {
        return transitions[fromScreen]?.get(elementId)
    }

    /**
     * Mark a screen as fully explored
     */
    fun markFullyExplored(screenId: String) {
        fullyExploredScreens.add(screenId)
    }

    /**
     * Mark a screen as problematic (stuck, no navigable elements, etc.)
     * This helps future explorations avoid wasting time on these screens
     */
    fun markScreenProblematic(screenId: String, reason: String) {
        problematicScreens[screenId] = reason
        Log.w("NavigationGraph", "Marked screen $screenId as problematic: $reason")
    }

    /**
     * Check if a screen is known to be problematic
     */
    fun isProblematicScreen(screenId: String): Boolean {
        return problematicScreens.containsKey(screenId)
    }

    // Track problematic screens
    private val problematicScreens = mutableMapOf<String, String>()

    /**
     * Get screens that haven't been fully explored yet
     */
    fun getUnexploredScreens(): Set<String> {
        return knownScreens - fullyExploredScreens
    }

    /**
     * Find path from current screen to target screen using BFS
     * Returns list of (screenId, elementId) pairs to navigate
     */
    fun findPath(fromScreen: String, toScreen: String): List<Pair<String, String>>? {
        if (fromScreen == toScreen) return emptyList()

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, List<Pair<String, String>>>>()
        queue.add(fromScreen to emptyList())

        while (queue.isNotEmpty()) {
            val (current, path) = queue.removeFirst()
            if (current in visited) continue
            visited.add(current)

            val screenTransitions = transitions[current] ?: continue
            for ((elementId, nextScreen) in screenTransitions) {
                if (nextScreen == toScreen) {
                    return path + (current to elementId)
                }
                if (nextScreen !in visited) {
                    queue.add(nextScreen to path + (current to elementId))
                }
            }
        }
        return null // No path found
    }

    /**
     * Find optimal path using Dijkstra with reliability-weighted edges.
     * Higher reliability paths are preferred (lower cost).
     *
     * @param fromScreen Starting screen ID
     * @param toScreen Target screen ID
     * @return List of (screenId, elementId) pairs, or null if no path exists
     */
    fun findOptimalPath(fromScreen: String, toScreen: String): List<Pair<String, String>>? {
        if (fromScreen == toScreen) return emptyList()

        // PathState for Dijkstra priority queue
        data class PathState(
            val screen: String,
            val cost: Float,
            val path: List<Pair<String, String>>
        )

        val visited = mutableSetOf<String>()
        val queue = java.util.PriorityQueue<PathState>(compareBy { it.cost })
        queue.add(PathState(fromScreen, 0f, emptyList()))

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.screen in visited) continue
            visited.add(current.screen)

            val screenTransitions = transitions[current.screen] ?: continue
            for ((elementId, nextScreen) in screenTransitions) {
                if (nextScreen in visited) continue

                // Skip blocker screens unless they're the target
                if (nextScreen != toScreen && isBlockerScreen(nextScreen)) continue

                // Calculate edge cost based on reliability (lower cost = better)
                val reliability = getTransitionReliability(current.screen, elementId, nextScreen)
                val edgeCost = 1f - reliability  // reliability 1.0 → cost 0.0

                val newPath = current.path + (current.screen to elementId)

                if (nextScreen == toScreen) {
                    return newPath
                }

                queue.add(PathState(nextScreen, current.cost + edgeCost, newPath))
            }
        }
        return null
    }

    /**
     * Get reliability score for a transition (0.0 to 1.0).
     * Based on visit counts from ElementNavigation tracking.
     */
    private fun getTransitionReliability(
        fromScreen: String,
        elementId: String,
        toScreen: String
    ): Float {
        val key = "$fromScreen:$elementId"
        val nav = elementNavigations[key]

        if (nav == null) {
            // Unknown transition - give it a default reliability
            return 0.5f
        }

        val totalVisits = nav.destinations.values.sum()
        if (totalVisits == 0) return 0.5f

        val visitsToTarget = nav.destinations[toScreen] ?: 0
        // Base reliability on how often this transition leads to the expected screen
        val baseReliability = visitsToTarget.toFloat() / totalVisits.toFloat()

        // Boost reliability if element has been used many times (more confidence)
        val confidenceBoost = minOf(0.2f, nav.tapCount * 0.02f)

        // Penalize if this is a conditional element (less predictable)
        val conditionalPenalty = if (nav.isConditional()) 0.1f else 0f

        // Penalize if target is sometimes a blocker
        val blockerPenalty = if (nav.blockerDestinations.contains(toScreen)) 0.3f else 0f

        return (baseReliability + confidenceBoost - conditionalPenalty - blockerPenalty).coerceIn(0.1f, 1.0f)
    }

    /**
     * Get all known screens
     */
    fun getAllScreens(): Set<String> = knownScreens.toSet()

    /**
     * Check if any element leads TO this screen (has incoming transitions)
     */
    fun hasIncomingTransitions(screenId: String): Boolean {
        // Check if any element navigation leads to this screen
        return elementNavigations.values.any { nav ->
            nav.destinations.keys.contains(screenId)
        } || transitions.values.any { elementMap ->
            elementMap.values.contains(screenId)
        }
    }

    /**
     * Get statistics about the navigation graph
     */
    fun getStats(): NavigationGraphStats {
        val totalTransitions = transitions.values.sumOf { it.size }
        return NavigationGraphStats(
            totalScreens = knownScreens.size,
            fullyExploredScreens = fullyExploredScreens.size,
            totalTransitions = totalTransitions,
            conditionalElements = elementNavigations.values.count { it.isConditional() },
            blockerScreens = blockerScreens.size
        )
    }

    /**
     * Get a summary of conditional elements for logging/debugging
     */
    fun getConditionalSummary(): String {
        val conditionals = getConditionalElements()
        if (conditionals.isEmpty()) return "No conditional elements detected"

        return buildString {
            appendLine("=== CONDITIONAL ELEMENTS (${conditionals.size}) ===")
            for (nav in conditionals) {
                appendLine("  ${nav.elementId}:")
                for ((dest, count) in nav.destinations) {
                    val blocker = if (nav.blockerDestinations.contains(dest)) " [BLOCKER]" else ""
                    appendLine("    → ${dest.take(16)}: $count visits$blocker")
                }
            }
        }
    }

    /**
     * Get a summary of blocker screens for logging/debugging
     */
    fun getBlockerSummary(): String {
        if (blockerScreens.isEmpty()) return "No blocker screens detected"

        return buildString {
            appendLine("=== BLOCKER SCREENS (${blockerScreens.size}) ===")
            for (screen in blockerScreens) {
                appendLine("  ${screen.take(16)}")
            }
        }
    }
}

data class NavigationGraphStats(
    val totalScreens: Int,
    val fullyExploredScreens: Int,
    val totalTransitions: Int,
    val conditionalElements: Int = 0,
    val blockerScreens: Int = 0
)

/**
 * Tracks navigation info for a single element across multiple explorations.
 * Detects conditional navigation (same button → different screens based on app state).
 */
data class ElementNavigation(
    val fromScreen: String,
    val elementId: String,
    // All destinations this element has led to, with visit counts
    val destinations: MutableMap<String, Int> = mutableMapOf(),
    // Destinations identified as blockers (password, login screens)
    val blockerDestinations: MutableSet<String> = mutableSetOf(),
    // Timestamp of first observation
    val firstSeen: Long = System.currentTimeMillis(),
    // Total times this element has been tapped
    var tapCount: Int = 0
) {
    /**
     * Add a destination (or increment visit count if already known)
     */
    fun addDestination(screenId: String) {
        destinations[screenId] = (destinations[screenId] ?: 0) + 1
        tapCount++
    }

    /**
     * Mark a destination as a blocker screen
     */
    fun markAsBlocker(screenId: String) {
        blockerDestinations.add(screenId)
    }

    /**
     * Check if this element has conditional navigation (2+ destinations)
     */
    fun isConditional(): Boolean = destinations.size > 1

    /**
     * Get the most visited destination
     */
    fun getMostVisitedDestination(): String? {
        return destinations.maxByOrNull { it.value }?.key
    }

    /**
     * Get non-blocker destinations
     */
    fun getNonBlockerDestinations(): List<String> {
        return destinations.keys.filter { !blockerDestinations.contains(it) }
    }

    /**
     * Get the likely "real" destination (non-blocker with most visits)
     */
    fun getRealDestination(): String? {
        return getNonBlockerDestinations()
            .maxByOrNull { destinations[it] ?: 0 }
    }

    /**
     * Check if all destinations are blockers (element might be locked behind auth)
     */
    fun isFullyBlocked(): Boolean {
        return destinations.isNotEmpty() &&
               destinations.keys.all { blockerDestinations.contains(it) }
    }

    override fun toString(): String {
        val destStr = destinations.entries.joinToString(", ") { (k, v) ->
            val blocker = if (blockerDestinations.contains(k)) " [BLOCKER]" else ""
            "${k.take(8)}:$v$blocker"
        }
        val conditional = if (isConditional()) " [CONDITIONAL]" else ""
        return "ElementNav($elementId$conditional -> [$destStr])"
    }
}

/**
 * Type of exploration target
 */
enum class ExplorationTargetType {
    TAP_ELEMENT,        // Tap a clickable element
    SCROLL_CONTAINER,   // Scroll a container to reveal more elements
    NAVIGATE_TO_SCREEN  // Navigate to a specific screen first
}

/**
 * Exploration mode - determines how thorough the exploration is
 */
enum class ExplorationMode {
    QUICK,      // Quick pass: only tap navigation elements, build screen map fast, skip scrolling
    NORMAL,     // Normal: explore all clickable elements with reasonable depth
    DEEP,       // Deep: full element analysis, scroll everything, max coverage
    MANUAL      // Manual/Follow: watch user navigation, learn from demonstration
}

/**
 * Exploration strategy - determines how elements are selected for exploration
 * This can be user-selected or learned per-app
 */
enum class ExplorationStrategy {
    SCREEN_FIRST,       // Complete all elements on current screen before navigating away (less chaotic)
    PRIORITY_BASED,     // Always pick highest priority element regardless of screen (default, may jump around)
    DEPTH_FIRST,        // Go deep into navigation paths before exploring siblings
    BREADTH_FIRST,      // Explore all top-level screens before going deeper
    ADAPTIVE,           // Learn and adapt strategy based on app behavior (uses LearningStore)
    SYSTEMATIC          // Read like a book: top-left to bottom-right, scroll down to reveal more
}

/**
 * Configuration for exploration
 */
data class ExplorationConfig(
    val mode: ExplorationMode = ExplorationMode.NORMAL,  // Exploration mode
    val strategy: ExplorationStrategy = ExplorationStrategy.SCREEN_FIRST,  // Element selection strategy
    val maxDepth: Int = 5,              // Max navigation depth from start screen
    val maxScreens: Int = 50,           // Max screens to explore
    val maxElements: Int = 500,         // Max total elements to explore
    val maxDurationMs: Long = 10 * 60 * 1000L, // Max exploration duration (10 minutes)
    val maxLaunchRetries: Int = 3,      // Max times to retry launching target app
    val actionDelay: Long = 1000,       // Delay after each action (ms) - increased for stability
    val transitionWait: Long = 2500,    // Wait for screen transition (ms) - increased for slow apps
    val scrollDelay: Long = 500,        // Delay between scroll gestures (ms)
    val maxScrollsPerContainer: Int = 5,// Max scrolls per scrollable container
    val exploreDialogs: Boolean = true, // Should explore dialogs?
    val exploreMenus: Boolean = true,   // Should explore menus?
    val backtrackAfterNewScreen: Boolean = true, // ENABLED: performSmartBack now has system BACK fallback for non-root screens
    val stabilizationWait: Long = 3000, // Max wait for UI to stabilize (ms)
    val excludePackages: Set<String> = setOf(
        "com.android.systemui",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher"
    ),
    val excludeResourceIdPatterns: List<Regex> = listOf(
        Regex(".*back.*", RegexOption.IGNORE_CASE),
        Regex(".*home.*", RegexOption.IGNORE_CASE),
        Regex(".*navigate_up.*", RegexOption.IGNORE_CASE)
    ),
    // === GOAL-ORIENTED EXPLORATION (Phase 1 Enhancement) ===
    val goal: ExplorationGoal = ExplorationGoal.QUICK_SCAN,  // Exploration goal
    val targetCoverage: Float = 0.90f,   // Target coverage for COMPLETE_COVERAGE goal (90%)
    val maxDurationForCoverage: Long = 30 * 60 * 1000L, // Max time for coverage goal (30 min safety cap)
    val enableSystematicBacktracking: Boolean = false,  // Enable frontier-based backtracking
    val trackCoverageMetrics: Boolean = true,  // Track and log coverage metrics
    // === MULTI-PASS EXPLORATION ===
    val maxPasses: Int = 1,  // Max number of passes (1 = single pass, 0 = unlimited until target reached)
    val stopAtTargetCoverage: Boolean = true,  // Stop if targetCoverage is reached before maxPasses
    // === NON-DESTRUCTIVE EXPLORATION ===
    val nonDestructive: Boolean = true,  // Revert toggle/switch changes after exploration
    // === HUMAN-IN-THE-LOOP ===
    val vetoWindowMs: Long = 1500L       // Time window for user to veto actions (ms) - 1.5s for comfortable reaction
) {
    /**
     * Serialize to JSON string
     */
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("mode", mode.name)
            put("strategy", strategy.name)
            put("maxDepth", maxDepth)
            put("maxScreens", maxScreens)
            put("maxElements", maxElements)
            put("maxDurationMs", maxDurationMs)
            put("maxLaunchRetries", maxLaunchRetries)
            put("actionDelay", actionDelay)
            put("transitionWait", transitionWait)
            put("scrollDelay", scrollDelay)
            put("maxScrollsPerContainer", maxScrollsPerContainer)
            put("exploreDialogs", exploreDialogs)
            put("exploreMenus", exploreMenus)
            put("backtrackAfterNewScreen", backtrackAfterNewScreen)
            put("stabilizationWait", stabilizationWait)
            // Goal-oriented exploration fields
            put("goal", goal.name)
            put("targetCoverage", targetCoverage.toDouble())
            put("maxDurationForCoverage", maxDurationForCoverage)
            put("enableSystematicBacktracking", enableSystematicBacktracking)
            put("trackCoverageMetrics", trackCoverageMetrics)
            // Multi-pass exploration fields
            put("maxPasses", maxPasses)
            put("stopAtTargetCoverage", stopAtTargetCoverage)
        }.toString()
    }

    companion object {
        /**
         * Deserialize from JSON string
         */
        fun fromJson(json: String): ExplorationConfig {
            val obj = org.json.JSONObject(json)
            return ExplorationConfig(
                mode = ExplorationMode.valueOf(obj.optString("mode", "NORMAL")),
                strategy = try {
                    ExplorationStrategy.valueOf(obj.optString("strategy", "SCREEN_FIRST"))
                } catch (e: Exception) {
                    ExplorationStrategy.SCREEN_FIRST
                },
                maxDepth = obj.optInt("maxDepth", 5),
                maxScreens = obj.optInt("maxScreens", 50),
                maxElements = obj.optInt("maxElements", 500),
                maxDurationMs = obj.optLong("maxDurationMs", 10 * 60 * 1000L),
                maxLaunchRetries = obj.optInt("maxLaunchRetries", 3),
                actionDelay = obj.optLong("actionDelay", 1000),
                transitionWait = obj.optLong("transitionWait", 2500),
                scrollDelay = obj.optLong("scrollDelay", 500),
                maxScrollsPerContainer = obj.optInt("maxScrollsPerContainer", 5),
                exploreDialogs = obj.optBoolean("exploreDialogs", true),
                exploreMenus = obj.optBoolean("exploreMenus", true),
                backtrackAfterNewScreen = obj.optBoolean("backtrackAfterNewScreen", true),
                stabilizationWait = obj.optLong("stabilizationWait", 3000),
                // Goal-oriented exploration fields
                goal = try {
                    ExplorationGoal.valueOf(obj.optString("goal", "QUICK_SCAN"))
                } catch (e: Exception) {
                    ExplorationGoal.QUICK_SCAN
                },
                targetCoverage = obj.optDouble("targetCoverage", 0.90).toFloat(),
                maxDurationForCoverage = obj.optLong("maxDurationForCoverage", 30 * 60 * 1000L),
                enableSystematicBacktracking = obj.optBoolean("enableSystematicBacktracking", false),
                trackCoverageMetrics = obj.optBoolean("trackCoverageMetrics", true),
                // Multi-pass exploration fields
                maxPasses = obj.optInt("maxPasses", 1),
                stopAtTargetCoverage = obj.optBoolean("stopAtTargetCoverage", true)
            )
        }

        /**
         * Create a config for deep/complete coverage exploration
         */
        fun forDeepExploration(targetCoverage: Float = 0.90f): ExplorationConfig {
            return ExplorationConfig(
                mode = ExplorationMode.DEEP,
                goal = ExplorationGoal.COMPLETE_COVERAGE,
                maxDepth = 10,
                maxScreens = 100,
                maxElements = 500,
                maxDurationMs = 30 * 60 * 1000L,
                targetCoverage = targetCoverage,
                maxDurationForCoverage = 30 * 60 * 1000L,
                enableSystematicBacktracking = true,
                trackCoverageMetrics = true,
                backtrackAfterNewScreen = false  // IMPORTANT: Explore new screens first, don't immediately go back!
            )
        }
    }
}

/**
 * Screen transition record
 */
data class ScreenTransition(
    val fromScreenId: String,
    val toScreenId: String,
    val triggerElementId: String,
    val transitionType: TransitionType,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Type of screen transition
 */
enum class TransitionType {
    FORWARD,    // Normal navigation forward
    BACK,       // Back button or gesture
    DIALOG,     // Dialog appeared
    MENU,       // Menu appeared
    TAB_SWITCH, // Tab was switched
    EXTERNAL    // Left the app
}

/**
 * Exploration result containing all discovered data
 */
data class ExplorationResult(
    val state: ExplorationState,
    val screens: List<ExploredScreen>,
    val transitions: List<ScreenTransition>,
    val navigationGraph: Map<String, List<String>>, // screenId -> list of reachable screenIds
    val generatedFlow: GeneratedFlow?,
    val coverageMetrics: CoverageMetrics? = null,
    val mlInsights: MLInsights? = null
)

/**
 * ML insights from Q-learning during exploration
 */
data class MLInsights(
    val qValueCount: Int,
    val dangerousPatternsCount: Int,
    val positiveRewardElements: List<String>,
    val negativeRewardElements: List<String>,
    val averageReward: Float,
    val topPerformingActions: List<String> = emptyList(),
    val problematicActions: List<String> = emptyList()
)

/**
 * Suggestion for improving exploration results
 */
data class ExplorationSuggestion(
    val type: SuggestionType,
    val title: String,
    val description: String,
    val priority: Int = 0 // Higher = more important
)

enum class SuggestionType {
    LOW_COVERAGE,
    STUCK_ELEMENTS,
    UNEXPLORED_BRANCHES,
    DANGEROUS_PATTERNS,
    STABILITY_GOOD,
    DEEP_EXPLORATION_RECOMMENDED
}

/**
 * Generated flow from exploration
 */
data class GeneratedFlow(
    val name: String,
    val packageName: String,
    val steps: List<GeneratedFlowStep>,
    val sensors: List<GeneratedSensor>,
    val actions: List<GeneratedAction>,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A step in the generated flow
 */
data class GeneratedFlowStep(
    val type: GeneratedStepType,
    val screenId: String?,
    val elementId: String?,
    val x: Int?,
    val y: Int?,
    val text: String?,
    val waitMs: Long?,
    val description: String,
    val optional: Boolean = false
)

/**
 * Type of generated step
 */
enum class GeneratedStepType {
    LAUNCH_APP,
    TAP,
    TYPE_TEXT,
    SWIPE,
    WAIT,
    CAPTURE_SENSOR,
    PERFORM_ACTION,
    ASSERT_SCREEN
}

/**
 * Generated sensor from exploration
 */
data class GeneratedSensor(
    val name: String,
    val screenId: String,
    val elementId: String,
    val resourceId: String?,
    val sensorType: SuggestedSensorType,
    val extractionMethod: String = "none",
    val regexPattern: String? = null,
    val extractNumeric: Boolean = false,
    val removeUnit: Boolean = false,
    val sampleValue: String?,
    val selected: Boolean = true, // User can deselect unwanted sensors
    // Device class, units, etc. - matching server-side SensorDefinition
    val deviceClass: String? = null,
    val unitOfMeasurement: String? = null,
    val icon: String? = null,
    val stateClass: String? = null // "measurement", "total", "total_increasing"
) {
    /**
     * Convert to SensorDefinition for saving/syncing
     */
    fun toSensorDefinition(deviceId: String): com.visualmapper.companion.models.SensorDefinition {
        return com.visualmapper.companion.models.SensorDefinition(
            sensorId = name.lowercase().replace(Regex("[^a-z0-9_]"), "_"),
            name = name,
            deviceId = deviceId,
            deviceClass = deviceClass,
            unitOfMeasurement = unitOfMeasurement,
            icon = icon,
            enabled = selected,
            source = com.visualmapper.companion.models.SensorSource(
                elementResourceId = resourceId
            ),
            extractionRule = com.visualmapper.companion.models.TextExtractionRule(
                method = when (extractionMethod) {
                    "regex" -> com.visualmapper.companion.models.ExtractionMethod.REGEX
                    "numeric" -> com.visualmapper.companion.models.ExtractionMethod.NUMERIC
                    else -> com.visualmapper.companion.models.ExtractionMethod.EXACT
                }
            )
        )
    }

    companion object {
        /**
         * Common device classes for sensor type suggestions
         */
        val DEVICE_CLASSES = listOf(
            null to "None",
            "battery" to "Battery",
            "temperature" to "Temperature",
            "humidity" to "Humidity",
            "power" to "Power",
            "energy" to "Energy",
            "monetary" to "Money",
            "signal_strength" to "Signal Strength",
            "duration" to "Duration",
            "data_size" to "Data Size",
            "speed" to "Speed",
            "distance" to "Distance"
        )

        /**
         * Common units of measurement
         */
        val UNITS = listOf(
            null to "None",
            "%" to "Percent (%)",
            "°C" to "Celsius (°C)",
            "°F" to "Fahrenheit (°F)",
            "W" to "Watts (W)",
            "kWh" to "Kilowatt Hours (kWh)",
            "\$" to "Dollars (\$)",
            "€" to "Euros (€)",
            "dBm" to "dBm",
            "MB" to "Megabytes (MB)",
            "GB" to "Gigabytes (GB)",
            "km" to "Kilometers (km)",
            "mi" to "Miles (mi)",
            "s" to "Seconds (s)",
            "min" to "Minutes (min)"
        )

        /**
         * Extraction methods matching server-side options
         */
        val EXTRACTION_METHODS = listOf(
            "none" to "None (use raw text)",
            "regex" to "Regex (pattern match)",
            "numeric" to "Numeric (extract numbers)",
            "first_word" to "First Word",
            "last_word" to "Last Word"
        )

        /**
         * State classes for Home Assistant
         */
        val STATE_CLASSES = listOf(
            null to "None (text sensor)",
            "measurement" to "Measurement (fluctuating values)",
            "total" to "Total (monotonically increasing)",
            "total_increasing" to "Total Increasing (can reset)"
        )

        /**
         * Apply extraction rules to raw text and return extracted value
         */
        fun applyExtraction(
            rawText: String?,
            method: String,
            regexPattern: String?,
            extractNumeric: Boolean,
            removeUnit: Boolean
        ): Pair<String?, String?> {
            if (rawText.isNullOrEmpty()) {
                return null to "No input text"
            }

            var result = rawText
            var error: String? = null

            // Step 1: Apply extraction method
            when (method) {
                "regex" -> {
                    if (!regexPattern.isNullOrEmpty()) {
                        try {
                            val regex = Regex(regexPattern)
                            val match = regex.find(rawText)
                            result = match?.groupValues?.getOrNull(1) ?: match?.value ?: rawText
                            if (match == null) {
                                error = "Pattern didn't match"
                            }
                        } catch (e: Exception) {
                            error = "Invalid regex: ${e.message}"
                        }
                    }
                }
                "numeric" -> {
                    val numbers = Regex("[-+]?\\d*\\.?\\d+").find(rawText)?.value
                    result = numbers ?: rawText
                    if (numbers == null) {
                        error = "No numbers found"
                    }
                }
                "first_word" -> {
                    result = rawText.trim().split(Regex("\\s+")).firstOrNull() ?: rawText
                }
                "last_word" -> {
                    result = rawText.trim().split(Regex("\\s+")).lastOrNull() ?: rawText
                }
            }

            // Step 2: Extract numeric if requested
            if (extractNumeric && result != null) {
                val numbers = Regex("[-+]?\\d*\\.?\\d+").find(result)?.value
                if (numbers != null) {
                    result = numbers
                }
            }

            // Step 3: Remove unit suffix if requested
            if (removeUnit && result != null) {
                // Remove common unit suffixes
                result = result.replace(Regex("\\s*(%|°C|°F|km|mi|W|kWh|MB|GB|dBm|s|min)\\s*$", RegexOption.IGNORE_CASE), "").trim()
            }

            return result to error
        }
    }
}

/**
 * Generated action from exploration
 */
data class GeneratedAction(
    val name: String,
    val screenId: String,
    val elementId: String,
    val resourceId: String?,
    val actionType: ClickableActionType,
    val stepsToReach: List<GeneratedFlowStep>,
    val selected: Boolean = true // User can deselect unwanted actions
)

/**
 * Screen ID computation utility
 *
 * SIMPLIFIED: Uses only packageName + activity to generate stable screen IDs.
 * This prevents the same screen from getting multiple IDs due to dynamic content
 * like "Inbox (3)" vs "Inbox (5)" or slight layout changes.
 */
object ScreenIdComputer {

    /**
     * Compute a unique screen ID based on package and activity only.
     * This ensures the same activity always gets the same screen ID,
     * regardless of dynamic text content or minor layout differences.
     */
    fun computeScreenId(activity: String, packageName: String): String {
        val hashInput = "$packageName|$activity"
        return sha256(hashInput).take(16)
    }

    /**
     * Legacy method for compatibility - now just returns empty list
     * since we no longer use landmarks for screen ID computation.
     */
    fun extractLandmarks(elements: List<ClickableElement>): List<String> {
        return emptyList()
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Generate element ID based on element properties.
 *
 * SIMPLIFIED: Does NOT include position (x,y) because:
 * - Slight layout shifts would create different IDs for same element
 * - Element IDs should be stable across screen captures
 * - Uses resourceId + text + className for uniqueness
 */
fun generateElementId(
    resourceId: String?,
    text: String?,
    className: String,
    bounds: ElementBounds? = null
): String {
    val parts = mutableListOf<String>()

    // Resource ID is the most stable identifier
    resourceId?.let { parts.add(it.substringAfterLast('/')) }

    // Text content (limited to avoid dynamic content issues)
    text?.takeIf { it.isNotEmpty() && it.length < 30 }?.let { parts.add(it.take(20)) }

    // Class name as fallback
    parts.add(className.substringAfterLast('.'))

    // Add position ONLY when there's no resource ID and no text
    // This ensures anonymous elements (like bottom nav LinearLayouts) are unique
    // but elements with IDs remain stable even if they shift slightly
    val isAnonymous = (resourceId == null || resourceId.isEmpty()) && (text == null || text.isEmpty())
    if (isAnonymous && bounds != null) {
        // Use center position rounded to nearest 10px to reduce collisions
        // (was 50px which caused too many collisions between similar elements)
        val roundedX = ((bounds.x + bounds.width / 2) / 10) * 10
        val roundedY = ((bounds.y + bounds.height / 2) / 10) * 10
        // Also include size (rounded to 20px) to differentiate elements at same position
        val roundedW = (bounds.width / 20) * 20
        val roundedH = (bounds.height / 20) * 20
        parts.add("${roundedX}_${roundedY}_${roundedW}x${roundedH}")
    }

    val result = parts.joinToString("_")
        .replace(Regex("[^a-zA-Z0-9_]"), "")
        .lowercase()

    return result
}

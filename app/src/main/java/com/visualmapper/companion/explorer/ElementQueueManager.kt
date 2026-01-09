package com.visualmapper.companion.explorer

import android.util.Log

/**
 * Manages the exploration queue for clickable elements.
 *
 * Responsibilities:
 * - Queue clickable elements from screens for exploration
 * - Apply exclusion filters (system UI, dangerous elements, etc.)
 * - Detect navigation elements for quick mode
 * - Support deep mode with minimal exclusions
 *
 * Extracted from AppExplorerService for modularity.
 */
class ElementQueueManager(
    private val qLearning: ExplorationQLearning?,
    private val priorityCalculator: ElementPriorityCalculator
) {
    companion object {
        private const val TAG = "ElementQueueManager"
    }

    // Track which screens have been queued (prevents re-queuing)
    private val queuedScreens = mutableSetOf<String>()

    /**
     * Reset queue tracking for a new exploration session.
     */
    fun reset() {
        queuedScreens.clear()
    }

    /**
     * Check if a screen has already been queued.
     */
    fun isScreenQueued(screenId: String): Boolean = queuedScreens.contains(screenId)

    /**
     * Mark a screen as queued.
     */
    fun markScreenQueued(screenId: String) {
        queuedScreens.add(screenId)
    }

    /**
     * Get the set of queued screen IDs (read-only).
     */
    fun getQueuedScreens(): Set<String> = queuedScreens.toSet()

    /**
     * Check if a screen is a low-priority meta page (Settings, About, Login, etc.)
     * These screens rarely have valuable sensors and should be explored minimally.
     */
    fun isLowPriorityScreen(screen: ExploredScreen): Boolean {
        val screenId = screen.screenId.lowercase()
        val activity = screen.activity.lowercase()

        // Check activity name for settings/about patterns
        val lowPriorityActivities = listOf(
            "setting", "preference", "about", "legal", "privacy",
            "terms", "license", "help", "support", "feedback",
            "contact", "faq", "changelog", "whatsnew"
        )

        for (pattern in lowPriorityActivities) {
            if (activity.contains(pattern) || screenId.contains(pattern)) {
                Log.i(TAG, "LOW PRIORITY SCREEN detected: $activity (pattern: $pattern)")
                return true
            }
        }

        // Check if majority of text elements contain settings-like content
        val metaTextCount = screen.textElements.count { textEl ->
            val text = textEl.text.lowercase()
            text.contains("version") || text.contains("privacy") ||
            text.contains("terms") || text.contains("license") ||
            text.contains("copyright") || text.contains("©")
        }

        // Require 5+ meta keywords to reduce false positives
        if (metaTextCount >= 5) {
            Log.i(TAG, "LOW PRIORITY SCREEN detected by content: $activity ($metaTextCount meta texts)")
            return true
        }

        return false
    }

    /**
     * Check if a screen is a login/auth screen that blocks exploration.
     * These screens require user credentials and cannot be explored further.
     * The exploration should try to navigate away from these.
     */
    fun isLoginScreen(screen: ExploredScreen): Boolean {
        val screenId = screen.screenId.lowercase()
        val activity = screen.activity.lowercase()

        // Check activity name for login/auth patterns
        // NOTE: Be careful not to match common activity names like "HomeActivity"
        val loginPatterns = listOf(
            "loginactivity", "signinactivity", "sign_in", "sign-in",
            "authactivity", "authenticateactivity",
            "preloginactivity", "pre_login", "pre-login",
            "registeractivity", "signupactivity", "sign_up", "sign-up",
            "passwordactivity", "credentialactivity",
            "verificationactivity", "verifyactivity",
            "otpactivity", "2faactivity", "mfaactivity"
        )
        // These patterns were too broad and matched normal screens:
        // "welcome", "splash", "intro", "onboard", "auth" (matches HomeAuthActivity etc)

        for (pattern in loginPatterns) {
            if (activity.contains(pattern) || screenId.contains(pattern)) {
                Log.i(TAG, "LOGIN SCREEN detected: $activity (pattern: $pattern)")
                return true
            }
        }

        // Check for login-related UI elements by text content
        val loginTextCount = screen.textElements.count { textEl ->
            val text = textEl.text.lowercase()
            text.contains("log in") || text.contains("login") || text.contains("sign in") ||
            text.contains("username") || text.contains("password") || text.contains("email") ||
            text.contains("forgot password") || text.contains("create account") ||
            text.contains("register") || text.contains("sign up")
        }

        // Check for login-related buttons/clickables
        val loginButtonCount = screen.clickableElements.count { el ->
            val text = (el.text ?: "").lowercase()
            val desc = (el.contentDescription ?: "").lowercase()
            text.contains("log in") || text.contains("login") || text.contains("sign in") ||
            text.contains("submit") || text.contains("continue") ||
            desc.contains("log in") || desc.contains("login") || desc.contains("sign in")
        }

        // If screen has 2+ login-related elements, it's probably a login screen
        if (loginTextCount + loginButtonCount >= 2) {
            Log.i(TAG, "LOGIN SCREEN detected by content: $activity (texts=$loginTextCount, buttons=$loginButtonCount)")
            return true
        }

        return false
    }

    /**
     * Check if a screen is "escapable" - a low-value screen we should navigate away from.
     * This includes both login screens and settings/about pages.
     */
    fun isEscapableScreen(screen: ExploredScreen): Boolean {
        return isLoginScreen(screen) || isLowPriorityScreen(screen)
    }

    /**
     * Queue clickable elements from a screen for exploration.
     *
     * @param screen The screen to queue elements from
     * @param explorationQueue The queue to add targets to
     * @param visitedElements Set of already visited element composite keys
     * @param config The exploration configuration
     * @param visitedNavigationTabs Set of already visited navigation tab IDs
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @param statusBarHeight Status bar height in pixels
     * @param navBarHeight Navigation bar height in pixels
     * @return QueueResult with counts of queued and skipped elements
     */
    fun queueClickableElements(
        screen: ExploredScreen,
        explorationQueue: ArrayDeque<ExplorationTarget>,
        visitedElements: Set<String>,
        config: ExplorationConfig,
        visitedNavigationTabs: Set<String>,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        navBarHeight: Int
    ): QueueResult {
        // Check if screen was already queued - allow re-queuing for unvisited elements
        val isRequeue = queuedScreens.contains(screen.screenId)
        if (isRequeue) {
            // Count unvisited elements to decide if re-queuing is worthwhile
            val unvisitedCount = screen.clickableElements.count { element ->
                val compositeKey = "${screen.screenId}:${element.elementId}"
                !visitedElements.contains(compositeKey)
            }
            if (unvisitedCount == 0) {
                Log.d(TAG, "Screen ${screen.screenId} already queued and all elements visited - skipping")
                return QueueResult(skippedAlreadyQueued = true)
            }
            Log.d(TAG, "Screen ${screen.screenId} already queued but has $unvisitedCount unvisited elements - re-queuing")
        }

        // Check if this is a low-priority settings/about page - limit exploration
        // But don't skip if screen has many clickable elements (likely a real app page)
        val isLowPriorityPage = isLowPriorityScreen(screen)
        if (isLowPriorityPage && screen.clickableElements.size <= 10) {
            Log.i(TAG, "=== LOW PRIORITY PAGE: ${screen.activity} - minimal exploration ===")
            // Mark as queued but only queue back button (to leave quickly)
            queuedScreens.add(screen.screenId)
            return QueueResult(
                elementsQueued = 0,
                skippedLowPriority = screen.clickableElements.size
            )
        }

        val isQuickMode = config.mode == ExplorationMode.QUICK
        val visitStatus = if (isRequeue) "re-queuing unvisited" else "first visit to screen"
        Log.d(TAG, "Processing ${screen.clickableElements.size} clickable elements for queuing ($visitStatus, mode=${config.mode})")

        var queued = 0
        var skippedVisited = 0
        var skippedExcluded = 0
        var skippedQuickMode = 0
        var skippedDeadEnd = 0

        for (element in screen.clickableElements) {
            // Skip elements we've already TAPPED - use COMPOSITE KEY for per-screen tracking
            val compositeKey = "${screen.screenId}:${element.elementId}"
            if (visitedElements.contains(compositeKey)) {
                skippedVisited++
                continue
            }

            // ML OPTIMIZATION: Skip confirmed dead-ends
            if (qLearning?.shouldSkipElement(screen, element) == true) {
                skippedDeadEnd++
                continue
            }

            // Skip excluded elements (aggressive exclusion for queue)
            if (shouldExcludeFromQueue(element, screenWidth, screenHeight, statusBarHeight, navBarHeight)) {
                skippedExcluded++
                continue
            }

            // QUICK MODE: Only queue likely navigation elements
            if (isQuickMode && !isLikelyNavigationElement(element, screenWidth, screenHeight)) {
                skippedQuickMode++
                continue
            }

            // Add to queue with priority
            val priority = if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
                // SYSTEMATIC: Priority based on position - top-left gets highest priority
                // Calculate reading order: (row * 1000) + column
                // Invert so top-left has highest priority (elements processed high-to-low)
                val row = element.centerY / 100  // ~100px per "row"
                val col = element.centerX / 100  // ~100px per "column"
                val readingOrder = (row * 100) + col
                // Invert: higher reading order = lower priority (so top-left is first)
                val prio = 1000 - readingOrder.coerceIn(0, 999)
                Log.d(TAG, "[SYSTEMATIC] Element ${element.elementId} at y=${element.centerY} -> row=$row, readingOrder=$readingOrder, priority=$prio")
                prio
            } else {
                priorityCalculator.calculatePriority(
                    element = element,
                    screen = screen,
                    isAdaptiveMode = config.strategy == ExplorationStrategy.ADAPTIVE,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    visitedNavigationTabs = visitedNavigationTabs
                )
            }
            explorationQueue.add(ExplorationTarget(
                type = ExplorationTargetType.TAP_ELEMENT,
                screenId = screen.screenId,
                elementId = element.elementId,
                priority = priority,
                bounds = element.bounds
            ))
            queued++
        }

        // Also queue scrollable containers (SKIP in QUICK mode)
        var scrollQueued = 0
        if (!isQuickMode) {
            for (container in screen.scrollableContainers) {
                if (!container.fullyScrolled) {
                    // SYSTEMATIC: Scrolls get very high priority to reveal more content first
                    val scrollPriority = if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
                        // In SYSTEMATIC mode, scroll after exploring visible elements
                        // Use position-based priority but ensure scrolls happen in reading order
                        val row = container.bounds.y / 100
                        500 - row  // Earlier scrollables (higher on screen) get done first
                    } else {
                        5  // Normal priority
                    }
                    explorationQueue.add(ExplorationTarget(
                        type = ExplorationTargetType.SCROLL_CONTAINER,
                        screenId = screen.screenId,
                        scrollContainerId = container.elementId,
                        priority = scrollPriority,
                        bounds = container.bounds
                    ))
                    scrollQueued++
                }
            }
        }

        // Log strategy info
        if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
            Log.i(TAG, "SYSTEMATIC MODE: Elements queued in reading order (top-left to bottom-right)")
        }

        // Mark this screen as queued
        queuedScreens.add(screen.screenId)

        val modeInfo = if (isQuickMode) " (QUICK: skipped $skippedQuickMode non-nav)" else ""
        val mlInfo = if (skippedDeadEnd > 0) " (ML: skipped $skippedDeadEnd dead-ends)" else ""
        Log.i(TAG, "Queued $queued clickable targets, $scrollQueued scroll targets " +
                "(skipped $skippedVisited visited, $skippedExcluded excluded)$modeInfo$mlInfo. Total queued screens: ${queuedScreens.size}")

        return QueueResult(
            elementsQueued = queued,
            scrollContainersQueued = scrollQueued,
            skippedVisited = skippedVisited,
            skippedExcluded = skippedExcluded,
            skippedQuickMode = skippedQuickMode,
            skippedDeadEnd = skippedDeadEnd
        )
    }

    /**
     * Queue clickable elements in DEEP mode (minimal exclusions).
     *
     * @param screen The screen to queue elements from
     * @param explorationQueue The queue to add targets to
     * @param visitedElements Set of already visited element composite keys
     * @param config The exploration configuration
     * @param visitedNavigationTabs Set of already visited navigation tab IDs
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @return QueueResult with counts of queued and skipped elements
     */
    fun queueClickableElementsDeep(
        screen: ExploredScreen,
        explorationQueue: ArrayDeque<ExplorationTarget>,
        visitedElements: Set<String>,
        config: ExplorationConfig,
        visitedNavigationTabs: Set<String>,
        screenWidth: Int,
        screenHeight: Int
    ): QueueResult {
        // Check if screen was already queued - allow re-queuing for unvisited elements
        val isRequeue = queuedScreens.contains(screen.screenId)
        if (isRequeue) {
            // Count unvisited elements to decide if re-queuing is worthwhile
            val unvisitedCount = screen.clickableElements.count { element ->
                val compositeKey = "${screen.screenId}:${element.elementId}"
                !visitedElements.contains(compositeKey)
            }
            if (unvisitedCount == 0) {
                Log.d(TAG, "DEEP: Screen ${screen.screenId} already queued and all elements visited - skipping")
                return QueueResult(skippedAlreadyQueued = true)
            }
            Log.d(TAG, "DEEP: Screen ${screen.screenId} already queued but has $unvisitedCount unvisited elements - re-queuing")
        }

        val visitStatus = if (isRequeue) "re-queuing unvisited" else "first visit"
        Log.i(TAG, "=== DEEP MODE: Queueing ALL ${screen.clickableElements.size} elements ($visitStatus) ===")

        var queued = 0
        var skippedVisited = 0

        for (element in screen.clickableElements) {
            val compositeKey = "${screen.screenId}:${element.elementId}"
            if (visitedElements.contains(compositeKey)) {
                skippedVisited++
                continue
            }

            // DEEP MODE: Only skip elements with truly invalid bounds (0 height/width)
            val bounds = element.bounds
            if (bounds.width <= 0 || bounds.height <= 0) {
                Log.d(TAG, "DEEP: Skipping zero-size element: ${element.elementId}")
                continue
            }

            // Add ALL elements with priority based on strategy
            val priority = if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
                // SYSTEMATIC: Priority based on position - top-left gets highest priority
                // Calculate reading order: (row * 100) + column
                // Invert so top-left has highest priority (elements processed high-to-low)
                val row = element.centerY / 100  // ~100px per "row"
                val col = element.centerX / 100  // ~100px per "column"
                val readingOrder = (row * 100) + col
                val prio = 1000 - readingOrder.coerceIn(0, 999)
                Log.d(TAG, "[SYSTEMATIC-DEEP] Element ${element.elementId} at y=${element.centerY} -> row=$row, priority=$prio")
                prio
            } else {
                priorityCalculator.calculatePriority(
                    element = element,
                    screen = screen,
                    isAdaptiveMode = config.strategy == ExplorationStrategy.ADAPTIVE,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    visitedNavigationTabs = visitedNavigationTabs
                ) + 10  // Boost all priorities in deep mode
            }

            explorationQueue.add(ExplorationTarget(
                type = ExplorationTargetType.TAP_ELEMENT,
                screenId = screen.screenId,
                elementId = element.elementId,
                priority = priority,
                bounds = element.bounds
            ))
            queued++
        }

        // DEEP MODE: Queue ALL scrollable containers
        var scrollQueued = 0
        for (container in screen.scrollableContainers) {
            // SYSTEMATIC: Scroll priority based on position (higher = done first)
            val scrollPriority = if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
                val row = container.bounds.y / 100
                500 - row  // Earlier scrollables (higher on screen) get done first
            } else {
                15  // High priority for scrolling in deep mode
            }
            explorationQueue.add(ExplorationTarget(
                type = ExplorationTargetType.SCROLL_CONTAINER,
                screenId = screen.screenId,
                scrollContainerId = container.elementId,
                priority = scrollPriority,
                bounds = container.bounds
            ))
            scrollQueued++
        }

        // Log strategy info
        if (config.strategy == ExplorationStrategy.SYSTEMATIC) {
            Log.i(TAG, "SYSTEMATIC-DEEP MODE: Elements queued in reading order (top-left to bottom-right)")
        }

        queuedScreens.add(screen.screenId)

        Log.i(TAG, "DEEP: Queued $queued elements, $scrollQueued scroll containers " +
                "(skipped $skippedVisited visited). Total queued screens: ${queuedScreens.size}")

        return QueueResult(
            elementsQueued = queued,
            scrollContainersQueued = scrollQueued,
            skippedVisited = skippedVisited
        )
    }

    /**
     * Check if element is likely a navigation element (tabs, menu items, buttons with nav icons).
     * Used in QUICK mode to skip non-navigation elements.
     */
    fun isLikelyNavigationElement(
        element: ClickableElement,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val bounds = element.bounds

        // Bottom navigation bar elements (tabs)
        val isBottomNav = element.centerY > screenHeight - 200 &&
            bounds.height > 40 && bounds.height < 150
        if (isBottomNav) return true

        // Top action bar elements
        val isTopBar = element.centerY < 120 && bounds.height < 80
        if (isTopBar) return true

        // Tab bars (typically at top below action bar)
        val isTabBar = element.centerY in 100..250 &&
            element.className.contains("Tab", ignoreCase = true)
        if (isTabBar) return true

        // Common navigation patterns in resource ID or text
        val navPatterns = listOf(
            "tab", "nav", "menu", "home", "settings", "profile",
            "back", "more", "drawer", "hamburger", "fab"
        )
        val idLower = element.resourceId?.lowercase() ?: ""
        val textLower = element.text?.lowercase() ?: ""
        val descLower = element.contentDescription?.lowercase() ?: ""

        for (pattern in navPatterns) {
            if (idLower.contains(pattern) || textLower.contains(pattern) || descLower.contains(pattern)) {
                return true
            }
        }

        // Large buttons that span significant width (likely main actions)
        val isWideButton = bounds.width > screenWidth * 0.4 &&
            element.className.contains("Button", ignoreCase = true)
        if (isWideButton) return true

        // Card views or list items (might contain navigation)
        val isCard = element.className.contains("Card", ignoreCase = true) ||
            element.className.contains("ListItem", ignoreCase = true)
        if (isCard) return true

        return false
    }

    /**
     * Check if an element should be excluded from the exploration queue.
     *
     * @param element The element to check
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @param statusBarHeight Status bar height in pixels
     * @param navBarHeight Navigation bar height in pixels
     * @return true if the element should be excluded
     */
    fun shouldExcludeFromQueue(
        element: ClickableElement,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        navBarHeight: Int
    ): Boolean {
        // FIRST: Validate bounds - skip elements with invalid dimensions
        if (element.bounds.width <= 0 || element.bounds.height <= 0) {
            Log.d(TAG, "Queue: Excluding invalid bounds element: ${element.bounds.width}x${element.bounds.height}")
            return true
        }

        // Skip elements that are completely off-screen (likely from wrong view hierarchy)
        if (element.centerX < 0 || element.centerY < 0 ||
            element.centerX > screenWidth || element.centerY > screenHeight) {
            Log.d(TAG, "Queue: Excluding off-screen element at (${element.centerX}, ${element.centerY}), screen=${screenWidth}x${screenHeight}")
            return true
        }

        // System UI packages to avoid completely
        val systemPackages = setOf(
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher",
            "com.android.launcher3",
            "com.sec.android.app.launcher",
            "com.miui.home"
        )

        // Check if from system UI package (resource ID starts with system package)
        element.resourceId?.let { resId ->
            val lowercaseResId = resId.lowercase()
            for (pkg in systemPackages) {
                if (lowercaseResId.startsWith(pkg)) {
                    Log.d(TAG, "Queue: Excluding system UI element: $resId")
                    return true
                }
            }
        }

        // Exclude very small elements (likely decoration or hidden)
        if (element.bounds.width < 20 || element.bounds.height < 20) {
            Log.d(TAG, "Queue: Excluding tiny element: ${element.bounds.width}x${element.bounds.height}")
            return true
        }

        // Android navigation bar zone
        val navBarZoneTop = screenHeight - navBarHeight - 10
        if (element.centerY > navBarZoneTop && element.centerY <= screenHeight) {
            Log.d(TAG, "Queue: Excluding nav bar element at y=${element.centerY} (zone starts at $navBarZoneTop): ${element.resourceId ?: element.text}")
            return true
        }

        // Status bar zone
        if (element.centerY < statusBarHeight && element.centerY >= 0) {
            Log.d(TAG, "Queue: Excluding status bar element at y=${element.centerY}: ${element.resourceId ?: element.text}")
            return true
        }

        // Edge gesture zones (only in bottom half for gesture navigation)
        val edgeZone = 30
        if ((element.centerX < edgeZone || element.centerX > screenWidth - edgeZone) &&
            element.centerY > screenHeight / 2) {
            Log.d(TAG, "Queue: Excluding edge element at x=${element.centerX}, y=${element.centerY}")
            return true
        }

        // SECURITY: Exclude password/credential input fields - NEVER interact with these
        val sensitiveKeywords = setOf(
            "password", "passcode", "passphrase", "pass_word",
            "pin", "pincode", "pin_code", "security_code",
            "credential", "secret", "otp", "verification_code",
            "cvv", "cvc", "card_number", "account_number"
        )

        element.resourceId?.lowercase()?.let { resId ->
            for (keyword in sensitiveKeywords) {
                if (resId.contains(keyword)) {
                    Log.w(TAG, "Queue: EXCLUDING SENSITIVE ELEMENT (security): $resId")
                    return true
                }
            }
        }

        element.contentDescription?.lowercase()?.let { contentDesc ->
            for (keyword in sensitiveKeywords) {
                if (contentDesc.contains(keyword)) {
                    Log.w(TAG, "Queue: EXCLUDING SENSITIVE ELEMENT (security): $contentDesc")
                    return true
                }
            }
        }

        element.text?.lowercase()?.let { text ->
            // Only check if text looks like a password hint (short placeholder text)
            if (text.length < 30) {
                for (keyword in sensitiveKeywords) {
                    if (text.contains(keyword)) {
                        Log.w(TAG, "Queue: EXCLUDING SENSITIVE ELEMENT (security): text=$text")
                        return true
                    }
                }
            }
        }

        // Keywords that indicate elements that could close/minimize the app
        val dangerousKeywords = setOf(
            "home", "recent", "recents", "overview", "exit", "minimize",
            "keyboard", "ime", "launcher", "systemui", "go home",
            "show all apps", "switch apps"
        )

        // Check content description for dangerous keywords
        element.contentDescription?.lowercase()?.let { contentDesc ->
            for (keyword in dangerousKeywords) {
                if (contentDesc.contains(keyword)) {
                    Log.d(TAG, "Queue: Excluding dangerous element: $contentDesc")
                    return true
                }
            }
        }

        // Check resource ID for dangerous keywords (but NOT "back" - we handle that separately)
        element.resourceId?.lowercase()?.let { resId ->
            for (keyword in dangerousKeywords) {
                if (resId.contains(keyword)) {
                    Log.d(TAG, "Queue: Excluding dangerous element: $resId")
                    return true
                }
            }
        }

        // Exclude back buttons from queue (but NOT from capture - we need them for findUIBackButton)
        val isBackButton = priorityCalculator.isLikelyBackButton(element)
        if (isBackButton) {
            Log.d(TAG, "Queue: Excluding back button (available for smart back): ${element.resourceId ?: element.contentDescription}")
            return true
        }

        return false
    }

    /**
     * Result of a queue operation.
     */
    data class QueueResult(
        val elementsQueued: Int = 0,
        val scrollContainersQueued: Int = 0,
        val skippedVisited: Int = 0,
        val skippedExcluded: Int = 0,
        val skippedQuickMode: Int = 0,
        val skippedDeadEnd: Int = 0,
        val skippedAlreadyQueued: Boolean = false,
        val skippedLowPriority: Int = 0  // Elements skipped because screen is settings/about page
    )
}

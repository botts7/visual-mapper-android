package com.visualmapper.companion.explorer

import android.util.Log

/**
 * Calculates priority scores for UI elements during exploration.
 *
 * Priorities determine which elements to explore first:
 * - Higher priority = explored sooner
 * - Q-learning can boost/penalize based on learned patterns
 * - Adaptive mode reduces hardcoded biases, letting ML decide
 *
 * Extracted from AppExplorerService for modularity.
 */
class ElementPriorityCalculator(
    private val qLearning: ExplorationQLearning?
) {
    companion object {
        private const val TAG = "ElementPriority"
    }

    /**
     * Calculate the priority of an element for exploration ordering.
     *
     * @param element The clickable element to prioritize
     * @param screen The current screen (for Q-learning context)
     * @param isAdaptiveMode Whether adaptive/ML mode is enabled
     * @param screenWidth Screen width in pixels
     * @param screenHeight Screen height in pixels
     * @param visitedNavigationTabs Set of visited navigation tab IDs
     * @return Priority score (higher = explore sooner)
     */
    fun calculatePriority(
        element: ClickableElement,
        screen: ExploredScreen?,
        isAdaptiveMode: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        visitedNavigationTabs: Set<String>
    ): Int {
        var priority = 0

        // Higher priority for elements with text (likely buttons)
        if (!element.text.isNullOrEmpty()) priority += 10

        // Higher priority for elements with resource IDs (more stable)
        if (!element.resourceId.isNullOrEmpty()) priority += 5

        // === BOTTOM NAVIGATION TAB PRIORITY BOOST ===
        val bottomNavThreshold = screenHeight - 200
        val isBottomNavElement = element.centerY > bottomNavThreshold && element.bounds != null &&
            element.bounds!!.height > 40 && element.bounds!!.height < 150

        if (isBottomNavElement) {
            val navTabId = element.resourceId ?: element.elementId
            val isUnvisitedNavTab = !visitedNavigationTabs.contains(navTabId)

            if (isAdaptiveMode) {
                // ADAPTIVE MODE: Small boost, let Q-learning decide
                if (isUnvisitedNavTab) {
                    priority += 10
                    Log.d(TAG, "ADAPTIVE: Small boost +10 for unvisited bottom nav: ${element.elementId}")
                } else {
                    priority += 5
                }
            } else {
                // NON-ADAPTIVE: Original large boost
                if (isUnvisitedNavTab) {
                    priority += 50
                    Log.d(TAG, "Priority BOOST +50 for unvisited bottom nav: ${element.elementId}")
                } else {
                    priority += 15
                }
            }
        }

        // Lower priority for elements at screen edges (not bottom nav)
        if (element.centerX < 100 || element.centerX > screenWidth - 100) {
            if (element.centerY < bottomNavThreshold) {
                priority -= if (isAdaptiveMode) 2 else 5
            }
        }

        // Lower priority for elements near top (often toolbars)
        if (element.centerY < 200) {
            priority -= if (isAdaptiveMode) 1 else 3
        }

        // Higher priority for elements in the main content area
        if (element.centerX > screenWidth / 4 && element.centerX < screenWidth * 3 / 4 &&
            element.centerY > screenHeight / 4 && element.centerY < screenHeight * 3 / 4) {
            priority += if (isAdaptiveMode) 15 else 5
        }

        // === LOW PRIORITY FOR META/SETTINGS PAGES ===
        // These pages rarely have valuable sensors - deprioritize them
        if (isLowPriorityMetaElement(element)) {
            priority -= 30
            Log.d(TAG, "Low-priority meta element: ${element.text ?: element.resourceId}")
        }

        // === Q-Learning Priority Boost ===
        if (screen != null && qLearning != null) {
            val qBoost = qLearning.getElementPriorityBoost(screen, element)
            priority += if (isAdaptiveMode) (qBoost * 1.5).toInt() else qBoost

            // Strong penalty for dangerous patterns
            if (qLearning.isDangerousPattern(element)) {
                priority -= 100
                Log.d(TAG, "Dangerous pattern penalty for ${element.elementId}")
            }
        }

        return priority
    }

    /**
     * Check if an element is likely a back button.
     *
     * @param element The element to check
     * @return true if the element appears to be a back button
     */
    /**
     * Check if an element leads to low-priority meta pages like Settings, About, Contact, etc.
     * These pages rarely contain valuable sensors for automation.
     *
     * @param element The element to check
     * @return true if the element likely leads to a meta/settings page
     */
    fun isLowPriorityMetaElement(element: ClickableElement): Boolean {
        val text = element.text?.lowercase() ?: ""
        val resId = element.resourceId?.lowercase() ?: ""
        val desc = element.contentDescription?.lowercase() ?: ""

        // Common meta/settings page keywords
        val lowPriorityKeywords = listOf(
            "setting", "about", "contact", "help", "support",
            "privacy", "terms", "legal", "license", "feedback",
            "rate", "review", "share app", "invite", "refer",
            "version", "changelog", "what's new", "faq",
            "policy", "agreement", "tos", "preferences",
            "report", "bug", "issue"
        )

        for (keyword in lowPriorityKeywords) {
            if (text.contains(keyword) || resId.contains(keyword) || desc.contains(keyword)) {
                return true
            }
        }

        return false
    }

    fun isLikelyBackButton(element: ClickableElement): Boolean {
        val resId = element.resourceId?.lowercase() ?: ""
        val desc = element.contentDescription?.lowercase() ?: ""

        // Check for explicit back patterns
        val backPatterns = listOf(
            "navigate_up", "btn_back", "btn_finish", "action_bar_back",
            "toolbar_back", "iv_back", "img_back"
        )
        for (pattern in backPatterns) {
            if (resId.contains(pattern) || desc.contains(pattern)) {
                return true
            }
        }

        // Check content description
        if (desc.contains("back") || desc.contains("navigate up")) {
            return true
        }

        // Top-left ImageButton/ImageView is often a back button
        if (element.centerX < 150 && element.centerY < 200) {
            val className = element.className.lowercase()
            if (className.contains("imagebutton") || className.contains("imageview")) {
                return true
            }
        }

        return false
    }

    /**
     * Check if an element should be excluded from capture.
     * Only excludes system UI elements, keeps all app elements.
     *
     * @param element The element to check
     * @param screenHeight Screen height in pixels
     * @param statusBarHeight Status bar height in pixels
     * @param navBarHeight Navigation bar height in pixels
     * @return true if the element should be excluded
     */
    fun shouldExcludeFromCapture(
        element: ClickableElement,
        screenHeight: Int,
        statusBarHeight: Int,
        navBarHeight: Int
    ): Boolean {
        // System UI packages to avoid
        val systemPackages = setOf(
            "com.android.systemui",
            "com.google.android.apps.nexuslauncher"
        )

        element.resourceId?.let { resId ->
            val lowercaseResId = resId.lowercase()
            for (pkg in systemPackages) {
                if (lowercaseResId.startsWith(pkg)) {
                    return true
                }
            }
        }

        // Exclude elements in the Android navigation bar
        val navBarZone = navBarHeight + 10
        if (element.centerY > screenHeight - navBarZone) {
            return true
        }

        // Exclude elements in the status bar
        if (element.centerY < statusBarHeight) {
            return true
        }

        return false
    }
}

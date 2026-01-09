package com.visualmapper.companion.explorer

/**
 * ActionDetector - Detects action types from clickable elements
 *
 * Identifies what type of action an element performs based on:
 * - Element text/content
 * - Resource ID patterns
 * - Class name (Switch, Checkbox, Button, etc.)
 */
object ActionDetector {

    /**
     * Action category for grouping similar actions
     */
    enum class ActionCategory(val icon: String) {
        PLAYBACK_PLAY("mdi:play"),
        PLAYBACK_PAUSE("mdi:pause"),
        PLAYBACK_STOP("mdi:stop"),
        PLAYBACK_SKIP("mdi:skip-next"),
        TOGGLE("mdi:toggle-switch"),
        REFRESH("mdi:refresh"),
        SUBMIT("mdi:check"),
        DELETE("mdi:delete"),
        SHARE("mdi:share"),
        SETTINGS("mdi:cog"),
        MENU("mdi:menu"),
        NAVIGATION("mdi:arrow-right"),
        UNKNOWN("mdi:gesture-tap")
    }

    // Word patterns for detecting action types
    private val actionPatterns = mapOf(
        // Playback controls
        ActionCategory.PLAYBACK_PLAY to listOf(
            "play", "start", "resume", "begin", "launch"
        ),
        ActionCategory.PLAYBACK_PAUSE to listOf(
            "pause", "stop", "halt"
        ),
        ActionCategory.PLAYBACK_SKIP to listOf(
            "skip", "next", "previous", "forward", "rewind", "back"
        ),

        // Toggle actions
        ActionCategory.TOGGLE to listOf(
            "toggle", "switch", "enable", "disable", "turn on", "turn off",
            "activate", "deactivate", "mute", "unmute"
        ),

        // Refresh/Sync
        ActionCategory.REFRESH to listOf(
            "refresh", "reload", "sync", "update", "retry"
        ),

        // Submit/Confirm
        ActionCategory.SUBMIT to listOf(
            "submit", "confirm", "save", "ok", "done", "apply", "accept", "yes"
        ),

        // Delete/Remove
        ActionCategory.DELETE to listOf(
            "delete", "remove", "clear", "cancel", "dismiss", "close", "no"
        ),

        // Share
        ActionCategory.SHARE to listOf(
            "share", "send", "export", "copy"
        ),

        // Settings
        ActionCategory.SETTINGS to listOf(
            "settings", "preferences", "options", "configure", "config"
        ),

        // Menu
        ActionCategory.MENU to listOf(
            "menu", "more", "overflow"
        )
    )

    // Class name patterns for toggle elements
    private val toggleClassNames = listOf(
        "Switch", "ToggleButton", "Checkbox", "CheckBox",
        "CompoundButton", "SwitchCompat", "MaterialSwitch"
    )

    // Class name patterns for buttons
    private val buttonClassNames = listOf(
        "Button", "ImageButton", "FloatingActionButton",
        "MaterialButton", "AppCompatButton"
    )

    /**
     * Detect the action type of a clickable element.
     */
    fun detectActionType(element: ClickableElement): ClickableActionType {
        // First check class name for obvious types
        val className = element.className.substringAfterLast(".")

        // Toggle switches
        if (toggleClassNames.any { className.contains(it, ignoreCase = true) }) {
            return ClickableActionType.TOGGLE
        }

        // Get text content for pattern matching
        val textContent = buildString {
            element.text?.let { append(it.lowercase()).append(" ") }
            element.contentDescription?.let { append(it.lowercase()).append(" ") }
            element.resourceId?.substringAfterLast("/")?.replace("_", " ")?.let {
                append(it.lowercase())
            }
        }

        if (textContent.isBlank()) {
            // No text content - use class name hints
            return if (buttonClassNames.any { className.contains(it, ignoreCase = true) }) {
                ClickableActionType.UNKNOWN // It's a button but we don't know what it does
            } else {
                ClickableActionType.NAVIGATION // Assume clickable items navigate
            }
        }

        // Match against action patterns
        for ((category, patterns) in actionPatterns) {
            for (pattern in patterns) {
                if (textContent.contains(pattern)) {
                    return mapCategoryToActionType(category)
                }
            }
        }

        // If it's a button with text, it's probably an action
        return if (buttonClassNames.any { className.contains(it, ignoreCase = true) }) {
            ClickableActionType.UNKNOWN
        } else {
            ClickableActionType.NAVIGATION
        }
    }

    /**
     * Get the category of an element (for grouping and icon selection).
     */
    fun getActionCategory(element: ClickableElement): ActionCategory {
        val className = element.className.substringAfterLast(".")

        // Toggle switches
        if (toggleClassNames.any { className.contains(it, ignoreCase = true) }) {
            return ActionCategory.TOGGLE
        }

        val textContent = buildString {
            element.text?.let { append(it.lowercase()).append(" ") }
            element.contentDescription?.let { append(it.lowercase()).append(" ") }
            element.resourceId?.substringAfterLast("/")?.replace("_", " ")?.let {
                append(it.lowercase())
            }
        }

        for ((category, patterns) in actionPatterns) {
            for (pattern in patterns) {
                if (textContent.contains(pattern)) {
                    return category
                }
            }
        }

        return ActionCategory.UNKNOWN
    }

    /**
     * Map action category to ClickableActionType.
     */
    private fun mapCategoryToActionType(category: ActionCategory): ClickableActionType {
        return when (category) {
            ActionCategory.TOGGLE -> ClickableActionType.TOGGLE
            ActionCategory.MENU -> ClickableActionType.MENU
            ActionCategory.NAVIGATION -> ClickableActionType.NAVIGATION
            ActionCategory.SETTINGS, ActionCategory.SHARE,
            ActionCategory.SUBMIT, ActionCategory.DELETE,
            ActionCategory.REFRESH, ActionCategory.PLAYBACK_PLAY,
            ActionCategory.PLAYBACK_PAUSE, ActionCategory.PLAYBACK_STOP,
            ActionCategory.PLAYBACK_SKIP -> ClickableActionType.UNKNOWN // Use UNKNOWN for button actions
            ActionCategory.UNKNOWN -> ClickableActionType.UNKNOWN
        }
    }

    /**
     * Suggest an icon for an action based on its category.
     */
    fun suggestIcon(element: ClickableElement): String {
        return getActionCategory(element).icon
    }

    /**
     * Check if an element is likely a toggle (switch, checkbox).
     */
    fun isToggleElement(element: ClickableElement): Boolean {
        val className = element.className.substringAfterLast(".")
        return toggleClassNames.any { className.contains(it, ignoreCase = true) } ||
               getActionCategory(element) == ActionCategory.TOGGLE
    }

    /**
     * Check if an element is likely a playback control.
     */
    fun isPlaybackControl(element: ClickableElement): Boolean {
        val category = getActionCategory(element)
        return category in listOf(
            ActionCategory.PLAYBACK_PLAY,
            ActionCategory.PLAYBACK_PAUSE,
            ActionCategory.PLAYBACK_STOP,
            ActionCategory.PLAYBACK_SKIP
        )
    }
}

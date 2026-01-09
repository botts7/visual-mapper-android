package com.visualmapper.companion.explorer

/**
 * Defines access levels for exploration features.
 * Higher levels include all capabilities of lower levels.
 */
enum class ExplorationAccessLevel(val level: Int, val displayName: String, val description: String) {
    /**
     * View-only mode - no interactions allowed.
     * Can capture screens and detect elements but cannot interact.
     */
    OBSERVE(0, "Observe", "View-only mode - screen capture and element detection only"),

    /**
     * Safe exploration - read-only taps to reveal hidden content.
     * Can tap to expand menus or reveal tooltips, but no persistent changes.
     */
    SAFE(1, "Safe", "Safe taps to reveal hidden elements and expand menus"),

    /**
     * Standard exploration - normal navigation and interaction.
     * Can navigate screens, scroll, use back button, switch tabs.
     */
    STANDARD(2, "Standard", "Normal navigation - taps, scrolls, back, tab switching"),

    /**
     * Elevated access - can dismiss dialogs and popups.
     * Includes handling permission prompts and dismissing overlays.
     */
    ELEVATED(3, "Elevated", "Can dismiss dialogs, popups, and permission prompts"),

    /**
     * Full access - text input and form filling.
     * Can enter non-sensitive text in search fields, forms, etc.
     */
    FULL(4, "Full", "Text input and form filling (non-sensitive data)"),

    /**
     * Sensitive access - password entry and authentication.
     * Requires Go Mode to be active. Can enter passwords, PINs, OTPs.
     */
    SENSITIVE(5, "Sensitive", "Password entry, PIN, OTP, payment (requires Go Mode)");

    companion object {
        fun fromLevel(level: Int): ExplorationAccessLevel {
            return values().find { it.level == level } ?: STANDARD
        }

        fun fromName(name: String): ExplorationAccessLevel {
            return values().find { it.name.equals(name, ignoreCase = true) } ?: STANDARD
        }

        /**
         * Get all levels up to and including the specified level.
         */
        fun getLevelsUpTo(level: ExplorationAccessLevel): List<ExplorationAccessLevel> {
            return values().filter { it.level <= level.level }
        }

        /**
         * Get all levels above the specified level.
         */
        fun getLevelsAbove(level: ExplorationAccessLevel): List<ExplorationAccessLevel> {
            return values().filter { it.level > level.level }
        }
    }

    /**
     * Check if this level allows the specified action level.
     */
    fun allows(requiredLevel: ExplorationAccessLevel): Boolean {
        return this.level >= requiredLevel.level
    }

    /**
     * Check if this level requires Go Mode (SENSITIVE level).
     */
    fun requiresGoMode(): Boolean {
        return this == SENSITIVE
    }
}

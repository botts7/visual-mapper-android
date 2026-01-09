package com.visualmapper.companion.explorer

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.visualmapper.companion.security.SecurePreferences
import org.json.JSONObject

/**
 * Manages access levels for exploration features.
 * Controls what actions the explorer can perform based on current access level and Go Mode state.
 */
class AccessLevelManager(private val context: Context) {

    companion object {
        private const val TAG = "AccessLevelManager"
        private const val PREFS_NAME = "exploration_access_prefs"

        // Preference keys
        private const val KEY_GLOBAL_ACCESS_LEVEL = "global_access_level"
        private const val KEY_PER_APP_ACCESS_LEVELS = "per_app_access_levels"
        private const val KEY_REQUIRE_CONFIRMATION_ELEVATED = "require_confirmation_elevated"
        private const val KEY_REQUIRE_CONFIRMATION_FULL = "require_confirmation_full"
        private const val KEY_AUTO_DEACTIVATE_GO_MODE_ON_APP_SWITCH = "auto_deactivate_go_mode"
        private const val KEY_MAX_GO_MODE_DURATION_MS = "max_go_mode_duration_ms"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val securePrefs = SecurePreferences(context)

    // Listener for access level changes
    interface AccessLevelChangeListener {
        fun onAccessLevelChanged(newLevel: ExplorationAccessLevel)
        fun onGoModeChanged(isActive: Boolean, remainingMs: Long)
        fun onActionBlocked(action: ExplorationAction, requiredLevel: ExplorationAccessLevel)
    }

    private val listeners = mutableListOf<AccessLevelChangeListener>()

    fun addListener(listener: AccessLevelChangeListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: AccessLevelChangeListener) {
        listeners.remove(listener)
    }

    // =========================================================================
    // Global Access Level
    // =========================================================================

    /**
     * The global default access level for all apps.
     * Default: STANDARD (level 2) - allows navigation without text input.
     */
    var globalAccessLevel: ExplorationAccessLevel
        get() {
            val level = prefs.getInt(KEY_GLOBAL_ACCESS_LEVEL, ExplorationAccessLevel.STANDARD.level)
            return ExplorationAccessLevel.fromLevel(level)
        }
        set(value) {
            prefs.edit().putInt(KEY_GLOBAL_ACCESS_LEVEL, value.level).apply()
            Log.i(TAG, "Global access level set to: ${value.displayName} (${value.level})")
            listeners.forEach { it.onAccessLevelChanged(value) }
        }

    // =========================================================================
    // Per-App Access Levels
    // =========================================================================

    private var perAppAccessLevels: MutableMap<String, ExplorationAccessLevel> = loadPerAppLevels()

    private fun loadPerAppLevels(): MutableMap<String, ExplorationAccessLevel> {
        val json = prefs.getString(KEY_PER_APP_ACCESS_LEVELS, null) ?: return mutableMapOf()
        return try {
            val jsonObj = JSONObject(json)
            val map = mutableMapOf<String, ExplorationAccessLevel>()
            jsonObj.keys().forEach { packageName ->
                val level = jsonObj.optInt(packageName, ExplorationAccessLevel.STANDARD.level)
                map[packageName] = ExplorationAccessLevel.fromLevel(level)
            }
            map
        } catch (e: Exception) {
            Log.e(TAG, "Error loading per-app access levels", e)
            mutableMapOf()
        }
    }

    private fun savePerAppLevels() {
        val jsonObj = JSONObject()
        perAppAccessLevels.forEach { (packageName, level) ->
            jsonObj.put(packageName, level.level)
        }
        prefs.edit().putString(KEY_PER_APP_ACCESS_LEVELS, jsonObj.toString()).apply()
    }

    /**
     * Get the access level for a specific app.
     * Returns the per-app override if set, otherwise the global level.
     */
    fun getAccessLevelForApp(packageName: String): ExplorationAccessLevel {
        return perAppAccessLevels[packageName] ?: globalAccessLevel
    }

    /**
     * Set a per-app access level override.
     * Pass null to remove the override and use global level.
     */
    fun setAccessLevelForApp(packageName: String, level: ExplorationAccessLevel?) {
        if (level == null) {
            perAppAccessLevels.remove(packageName)
            Log.i(TAG, "Removed access level override for: $packageName")
        } else {
            perAppAccessLevels[packageName] = level
            Log.i(TAG, "Set access level for $packageName: ${level.displayName}")
        }
        savePerAppLevels()
    }

    /**
     * Get all per-app overrides.
     */
    fun getAllPerAppOverrides(): Map<String, ExplorationAccessLevel> {
        return perAppAccessLevels.toMap()
    }

    /**
     * Clear all per-app overrides.
     */
    fun clearAllPerAppOverrides() {
        perAppAccessLevels.clear()
        savePerAppLevels()
        Log.i(TAG, "Cleared all per-app access level overrides")
    }

    // =========================================================================
    // Go Mode State (managed by GoModeManager, checked here)
    // =========================================================================

    /**
     * Reference to the Go Mode manager for sensitive action checks.
     */
    var goModeManager: GoModeManager? = null

    /**
     * Check if Go Mode is currently active.
     */
    val isGoModeActive: Boolean
        get() = goModeManager?.isActive() ?: false

    // =========================================================================
    // Action Checks
    // =========================================================================

    /**
     * Check if an action can be performed for a specific app.
     * @param action The action to check
     * @param packageName The target app package name
     * @return true if the action is allowed
     */
    fun canPerformAction(action: ExplorationAction, packageName: String): Boolean {
        val currentLevel = getAccessLevelForApp(packageName)
        val required = action.requiredLevel

        // Check if current level allows the action
        if (!currentLevel.allows(required)) {
            Log.d(TAG, "Action ${action.actionName} blocked - requires ${required.displayName}, have ${currentLevel.displayName}")
            listeners.forEach { it.onActionBlocked(action, required) }
            return false
        }

        // If action is sensitive, also require Go Mode
        if (action.isSensitive && !isGoModeActive) {
            Log.d(TAG, "Sensitive action ${action.actionName} blocked - Go Mode not active")
            listeners.forEach { it.onActionBlocked(action, ExplorationAccessLevel.SENSITIVE) }
            return false
        }

        return true
    }

    /**
     * Check if text entry is allowed for the app.
     */
    fun canEnterText(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.EnterText, packageName)
    }

    /**
     * Check if sensitive data entry is allowed (requires SENSITIVE level + Go Mode).
     */
    fun canEnterSensitiveData(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.EnterPassword, packageName)
    }

    /**
     * Check if dialogs can be dismissed.
     */
    fun canDismissDialogs(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.DismissDialog, packageName)
    }

    /**
     * Check if scrolling is allowed.
     */
    fun canScroll(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.Scroll, packageName)
    }

    /**
     * Check if tapping elements is allowed.
     */
    fun canTap(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.TapElement, packageName)
    }

    /**
     * Check if navigation is allowed.
     */
    fun canNavigate(packageName: String): Boolean {
        return canPerformAction(ExplorationAction.NavigateBack, packageName)
    }

    // =========================================================================
    // Confirmation Settings
    // =========================================================================

    /**
     * Whether to require confirmation before elevated actions.
     */
    var requireConfirmationForElevated: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_CONFIRMATION_ELEVATED, true)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_CONFIRMATION_ELEVATED, value).apply()

    /**
     * Whether to require confirmation before full access actions.
     */
    var requireConfirmationForFull: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_CONFIRMATION_FULL, true)
        set(value) = prefs.edit().putBoolean(KEY_REQUIRE_CONFIRMATION_FULL, value).apply()

    /**
     * Whether to auto-deactivate Go Mode when switching apps.
     */
    var autoDeactivateGoModeOnAppSwitch: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DEACTIVATE_GO_MODE_ON_APP_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DEACTIVATE_GO_MODE_ON_APP_SWITCH, value).apply()

    /**
     * Maximum duration for Go Mode in milliseconds.
     * Default: 5 minutes (300000ms)
     */
    var maxGoModeDurationMs: Long
        get() = prefs.getLong(KEY_MAX_GO_MODE_DURATION_MS, 5 * 60 * 1000L)
        set(value) = prefs.edit().putLong(KEY_MAX_GO_MODE_DURATION_MS, value).apply()

    // =========================================================================
    // Convenience Methods
    // =========================================================================

    /**
     * Get a summary of current access settings.
     */
    fun getAccessSummary(): AccessSummary {
        return AccessSummary(
            globalLevel = globalAccessLevel,
            perAppOverrides = perAppAccessLevels.size,
            goModeActive = isGoModeActive,
            goModeRemainingMs = goModeManager?.getRemainingTime() ?: 0
        )
    }

    /**
     * Reset all access settings to defaults.
     */
    fun resetToDefaults() {
        globalAccessLevel = ExplorationAccessLevel.STANDARD
        clearAllPerAppOverrides()
        requireConfirmationForElevated = true
        requireConfirmationForFull = true
        autoDeactivateGoModeOnAppSwitch = true
        maxGoModeDurationMs = 5 * 60 * 1000L
        Log.i(TAG, "Reset all access settings to defaults")
    }

    data class AccessSummary(
        val globalLevel: ExplorationAccessLevel,
        val perAppOverrides: Int,
        val goModeActive: Boolean,
        val goModeRemainingMs: Long
    )
}

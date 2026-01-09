package com.visualmapper.companion.explorer

import android.util.Log

/**
 * Handles blocker screens (login, password, setup) with access level checks.
 * Only allows interaction with sensitive blockers when Go Mode is active.
 */
class BlockerHandler(
    private val accessManager: AccessLevelManager,
    private val goModeManager: GoModeManager
) {
    companion object {
        private const val TAG = "BlockerHandler"
    }

    /**
     * Types of blocker screens.
     */
    enum class BlockerType(
        val displayName: String,
        val requiredLevel: ExplorationAccessLevel,
        val isSensitive: Boolean
    ) {
        PASSWORD("Password Entry", ExplorationAccessLevel.SENSITIVE, true),
        PIN("PIN Entry", ExplorationAccessLevel.SENSITIVE, true),
        OTP("OTP/2FA Entry", ExplorationAccessLevel.SENSITIVE, true),
        LOGIN("Login Screen", ExplorationAccessLevel.SENSITIVE, true),
        BIOMETRIC("Biometric Prompt", ExplorationAccessLevel.SENSITIVE, true),
        SETUP("Setup Wizard", ExplorationAccessLevel.ELEVATED, false),
        ONBOARDING("Onboarding", ExplorationAccessLevel.ELEVATED, false),
        PERMISSION("Permission Dialog", ExplorationAccessLevel.ELEVATED, false),
        AGE_GATE("Age Verification", ExplorationAccessLevel.ELEVATED, false),
        TERMS("Terms Acceptance", ExplorationAccessLevel.ELEVATED, false),
        SUBSCRIPTION("Subscription Prompt", ExplorationAccessLevel.STANDARD, false),
        RATING("Rate App Dialog", ExplorationAccessLevel.STANDARD, false),
        AD("Advertisement", ExplorationAccessLevel.STANDARD, false),
        UNKNOWN("Unknown Blocker", ExplorationAccessLevel.STANDARD, false)
    }

    /**
     * Result of attempting to handle a blocker.
     */
    sealed class BlockerResult {
        data class Handled(val message: String) : BlockerResult()
        data class Skipped(val reason: String) : BlockerResult()
        data class RequiresGoMode(val message: String) : BlockerResult()
        data class AccessDenied(val requiredLevel: ExplorationAccessLevel) : BlockerResult()
        data class Error(val message: String, val exception: Exception? = null) : BlockerResult()
    }

    /**
     * Represents a detected blocker screen.
     */
    data class BlockerScreen(
        val type: BlockerType,
        val screenId: String,
        val activityName: String?,
        val hasTextInput: Boolean = false,
        val hasSensitiveInput: Boolean = false,
        val elements: List<BlockerElement> = emptyList()
    )

    /**
     * An interactive element on a blocker screen.
     */
    data class BlockerElement(
        val elementId: String,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isSensitive: Boolean = false,
        val bounds: android.graphics.Rect? = null
    )

    // Patterns for detecting blocker types
    private val blockerPatterns = mapOf(
        BlockerType.PASSWORD to listOf(
            "password", "passcode", "pass_word", "passphrase"
        ),
        BlockerType.PIN to listOf(
            "pin", "pincode", "pin_code", "security_code"
        ),
        BlockerType.OTP to listOf(
            "otp", "2fa", "two_factor", "verification_code", "verify_code",
            "sms_code", "auth_code", "authenticator"
        ),
        BlockerType.LOGIN to listOf(
            "login", "log_in", "signin", "sign_in", "auth", "authenticate"
        ),
        BlockerType.BIOMETRIC to listOf(
            "fingerprint", "face_id", "biometric", "touch_id"
        ),
        BlockerType.SETUP to listOf(
            "setup", "set_up", "wizard", "configure", "initial"
        ),
        BlockerType.ONBOARDING to listOf(
            "onboarding", "intro", "welcome", "tutorial", "walkthrough"
        ),
        BlockerType.PERMISSION to listOf(
            "permission", "allow", "grant", "access"
        ),
        BlockerType.AGE_GATE to listOf(
            "age", "birthday", "birth_date", "dob", "adult", "18+"
        ),
        BlockerType.TERMS to listOf(
            "terms", "privacy", "policy", "agreement", "consent", "gdpr"
        ),
        BlockerType.SUBSCRIPTION to listOf(
            "subscribe", "subscription", "premium", "upgrade", "pro"
        ),
        BlockerType.RATING to listOf(
            "rate", "rating", "review", "feedback", "stars"
        ),
        BlockerType.AD to listOf(
            "ad", "advertisement", "sponsored", "promo"
        )
    )

    /**
     * Detect the type of blocker from activity name and screen content.
     */
    fun detectBlockerType(activityName: String?, elementTexts: List<String>): BlockerType {
        val searchText = ((activityName ?: "") + " " + elementTexts.joinToString(" ")).lowercase()

        for ((type, patterns) in blockerPatterns) {
            if (patterns.any { searchText.contains(it) }) {
                Log.d(TAG, "Detected blocker type: ${type.displayName}")
                return type
            }
        }

        return BlockerType.UNKNOWN
    }

    /**
     * Check if a blocker can be handled at the current access level.
     */
    fun canHandleBlocker(blocker: BlockerScreen, packageName: String): Boolean {
        val currentLevel = accessManager.getAccessLevelForApp(packageName)
        val requiredLevel = blocker.type.requiredLevel

        // Check basic access level
        if (!currentLevel.allows(requiredLevel)) {
            Log.d(TAG, "Cannot handle ${blocker.type.displayName} - requires ${requiredLevel.displayName}, have ${currentLevel.displayName}")
            return false
        }

        // Check if sensitive blocker requires Go Mode
        if (blocker.type.isSensitive && !goModeManager.isActive()) {
            Log.d(TAG, "Cannot handle ${blocker.type.displayName} - requires Go Mode")
            return false
        }

        return true
    }

    /**
     * Attempt to handle a blocker screen.
     * Returns the result of the handling attempt.
     */
    suspend fun handleBlocker(blocker: BlockerScreen, packageName: String): BlockerResult {
        Log.i(TAG, "Attempting to handle blocker: ${blocker.type.displayName}")

        val currentLevel = accessManager.getAccessLevelForApp(packageName)
        val requiredLevel = blocker.type.requiredLevel

        // Check access level
        if (!currentLevel.allows(requiredLevel)) {
            Log.w(TAG, "Access denied for ${blocker.type.displayName}")
            return BlockerResult.AccessDenied(requiredLevel)
        }

        // Check Go Mode for sensitive blockers
        if (blocker.type.isSensitive) {
            if (!goModeManager.isActive()) {
                Log.w(TAG, "${blocker.type.displayName} requires Go Mode")
                return BlockerResult.RequiresGoMode(
                    "${blocker.type.displayName} requires Go Mode to be active"
                )
            }

            // Even with Go Mode, we don't auto-fill passwords
            // Just return that we detected it and it requires user input
            return BlockerResult.Skipped(
                "Sensitive blocker detected: ${blocker.type.displayName}. " +
                "Go Mode is active but user input is required."
            )
        }

        // Handle non-sensitive blockers
        return when (blocker.type) {
            BlockerType.SETUP, BlockerType.ONBOARDING -> {
                // Try to find skip/next button
                val skipElement = findSkipElement(blocker.elements)
                if (skipElement != null) {
                    BlockerResult.Handled("Found skip button: ${skipElement.text}")
                } else {
                    BlockerResult.Skipped("No skip option found in ${blocker.type.displayName}")
                }
            }

            BlockerType.PERMISSION -> {
                // Don't auto-accept permissions
                BlockerResult.Skipped("Permission dialog requires user decision")
            }

            BlockerType.TERMS -> {
                // Try to find accept button
                val acceptElement = findAcceptElement(blocker.elements)
                if (acceptElement != null) {
                    BlockerResult.Handled("Found accept button: ${acceptElement.text}")
                } else {
                    BlockerResult.Skipped("No accept option found in ${blocker.type.displayName}")
                }
            }

            BlockerType.RATING, BlockerType.SUBSCRIPTION -> {
                // Try to find dismiss/close button
                val dismissElement = findDismissElement(blocker.elements)
                if (dismissElement != null) {
                    BlockerResult.Handled("Found dismiss button: ${dismissElement.text}")
                } else {
                    BlockerResult.Skipped("No dismiss option found in ${blocker.type.displayName}")
                }
            }

            BlockerType.AD -> {
                // Try to find close/skip button
                val closeElement = findCloseElement(blocker.elements)
                if (closeElement != null) {
                    BlockerResult.Handled("Found close button: ${closeElement.text}")
                } else {
                    BlockerResult.Skipped("No close option found in ${blocker.type.displayName}")
                }
            }

            else -> BlockerResult.Skipped("No handler for ${blocker.type.displayName}")
        }
    }

    /**
     * Find an element that looks like a skip button.
     */
    private fun findSkipElement(elements: List<BlockerElement>): BlockerElement? {
        val skipPatterns = listOf("skip", "later", "not now", "maybe later", "no thanks", "next")
        return elements.find { element ->
            element.isClickable && skipPatterns.any { pattern ->
                (element.text?.lowercase()?.contains(pattern) == true) ||
                (element.contentDescription?.lowercase()?.contains(pattern) == true)
            }
        }
    }

    /**
     * Find an element that looks like an accept button.
     */
    private fun findAcceptElement(elements: List<BlockerElement>): BlockerElement? {
        val acceptPatterns = listOf("accept", "agree", "i agree", "ok", "continue", "proceed")
        return elements.find { element ->
            element.isClickable && acceptPatterns.any { pattern ->
                (element.text?.lowercase()?.contains(pattern) == true) ||
                (element.contentDescription?.lowercase()?.contains(pattern) == true)
            }
        }
    }

    /**
     * Find an element that looks like a dismiss button.
     */
    private fun findDismissElement(elements: List<BlockerElement>): BlockerElement? {
        val dismissPatterns = listOf("dismiss", "close", "cancel", "not now", "no thanks", "maybe later")
        return elements.find { element ->
            element.isClickable && dismissPatterns.any { pattern ->
                (element.text?.lowercase()?.contains(pattern) == true) ||
                (element.contentDescription?.lowercase()?.contains(pattern) == true)
            }
        }
    }

    /**
     * Find an element that looks like a close button.
     */
    private fun findCloseElement(elements: List<BlockerElement>): BlockerElement? {
        val closePatterns = listOf("close", "x", "dismiss", "skip ad", "skip")
        return elements.find { element ->
            element.isClickable && closePatterns.any { pattern ->
                (element.text?.lowercase()?.contains(pattern) == true) ||
                (element.contentDescription?.lowercase()?.contains(pattern) == true) ||
                (element.text == "X" || element.text == "x")
            }
        }
    }

    /**
     * Get information about what's needed to handle a blocker.
     */
    fun getBlockerRequirements(blocker: BlockerScreen): BlockerRequirements {
        return BlockerRequirements(
            blockerType = blocker.type,
            requiredLevel = blocker.type.requiredLevel,
            requiresGoMode = blocker.type.isSensitive,
            requiresUserInput = blocker.hasSensitiveInput,
            canAutoHandle = !blocker.type.isSensitive && blocker.type != BlockerType.PERMISSION
        )
    }

    data class BlockerRequirements(
        val blockerType: BlockerType,
        val requiredLevel: ExplorationAccessLevel,
        val requiresGoMode: Boolean,
        val requiresUserInput: Boolean,
        val canAutoHandle: Boolean
    )
}

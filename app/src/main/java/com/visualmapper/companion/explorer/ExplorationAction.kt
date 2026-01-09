package com.visualmapper.companion.explorer

/**
 * Sealed class defining all possible exploration actions and their required access levels.
 * Each action specifies the minimum access level needed to perform it.
 */
sealed class ExplorationAction(
    val requiredLevel: ExplorationAccessLevel,
    val actionName: String,
    val description: String,
    val isSensitive: Boolean = false
) {
    // OBSERVE level actions (level 0)
    object ScreenCapture : ExplorationAction(
        ExplorationAccessLevel.OBSERVE,
        "screen_capture",
        "Capture screen content"
    )

    object ElementDetection : ExplorationAction(
        ExplorationAccessLevel.OBSERVE,
        "element_detection",
        "Detect UI elements on screen"
    )

    object StateReading : ExplorationAction(
        ExplorationAccessLevel.OBSERVE,
        "state_reading",
        "Read current app state"
    )

    // SAFE level actions (level 1)
    object RevealTap : ExplorationAction(
        ExplorationAccessLevel.SAFE,
        "reveal_tap",
        "Tap to reveal hidden content"
    )

    object ExpandMenu : ExplorationAction(
        ExplorationAccessLevel.SAFE,
        "expand_menu",
        "Expand dropdown or menu"
    )

    object ShowTooltip : ExplorationAction(
        ExplorationAccessLevel.SAFE,
        "show_tooltip",
        "Show tooltip or hint"
    )

    // STANDARD level actions (level 2)
    object TapElement : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "tap_element",
        "Tap on interactive element"
    )

    object Scroll : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "scroll",
        "Scroll content"
    )

    object NavigateBack : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "navigate_back",
        "Navigate back"
    )

    object SwitchTab : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "switch_tab",
        "Switch bottom navigation tab"
    )

    object LongPress : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "long_press",
        "Long press on element"
    )

    object Swipe : ExplorationAction(
        ExplorationAccessLevel.STANDARD,
        "swipe",
        "Swipe gesture"
    )

    // ELEVATED level actions (level 3)
    object DismissDialog : ExplorationAction(
        ExplorationAccessLevel.ELEVATED,
        "dismiss_dialog",
        "Dismiss dialog or popup"
    )

    object CloseOverlay : ExplorationAction(
        ExplorationAccessLevel.ELEVATED,
        "close_overlay",
        "Close overlay or bottom sheet"
    )

    object HandlePermission : ExplorationAction(
        ExplorationAccessLevel.ELEVATED,
        "handle_permission",
        "Handle permission prompt"
    )

    object DismissSnackbar : ExplorationAction(
        ExplorationAccessLevel.ELEVATED,
        "dismiss_snackbar",
        "Dismiss snackbar notification"
    )

    object SkipOnboarding : ExplorationAction(
        ExplorationAccessLevel.ELEVATED,
        "skip_onboarding",
        "Skip onboarding or tutorial"
    )

    // FULL level actions (level 4)
    object EnterText : ExplorationAction(
        ExplorationAccessLevel.FULL,
        "enter_text",
        "Enter non-sensitive text"
    )

    object FillForm : ExplorationAction(
        ExplorationAccessLevel.FULL,
        "fill_form",
        "Fill form fields"
    )

    object SearchQuery : ExplorationAction(
        ExplorationAccessLevel.FULL,
        "search_query",
        "Enter search query"
    )

    object EditField : ExplorationAction(
        ExplorationAccessLevel.FULL,
        "edit_field",
        "Edit text field"
    )

    // SENSITIVE level actions (level 5) - require Go Mode
    object EnterPassword : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "enter_password",
        "Enter password",
        isSensitive = true
    )

    object EnterPin : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "enter_pin",
        "Enter PIN code",
        isSensitive = true
    )

    object EnterOtp : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "enter_otp",
        "Enter OTP code",
        isSensitive = true
    )

    object EnterPayment : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "enter_payment",
        "Enter payment details",
        isSensitive = true
    )

    object AuthenticateUser : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "authenticate_user",
        "Authenticate user credentials",
        isSensitive = true
    )

    object ConfirmTransaction : ExplorationAction(
        ExplorationAccessLevel.SENSITIVE,
        "confirm_transaction",
        "Confirm financial transaction",
        isSensitive = true
    )

    // Custom action for dynamic cases
    data class Custom(
        val customName: String,
        val customDescription: String,
        val level: ExplorationAccessLevel,
        val sensitive: Boolean = false
    ) : ExplorationAction(level, customName, customDescription, sensitive)

    companion object {
        /**
         * Get all predefined actions.
         */
        fun allActions(): List<ExplorationAction> = listOf(
            ScreenCapture, ElementDetection, StateReading,
            RevealTap, ExpandMenu, ShowTooltip,
            TapElement, Scroll, NavigateBack, SwitchTab, LongPress, Swipe,
            DismissDialog, CloseOverlay, HandlePermission, DismissSnackbar, SkipOnboarding,
            EnterText, FillForm, SearchQuery, EditField,
            EnterPassword, EnterPin, EnterOtp, EnterPayment, AuthenticateUser, ConfirmTransaction
        )

        /**
         * Get actions available at a specific access level.
         */
        fun actionsAtLevel(level: ExplorationAccessLevel): List<ExplorationAction> {
            return allActions().filter { it.requiredLevel == level }
        }

        /**
         * Get all actions allowed at or below a specific access level.
         */
        fun actionsAllowedAt(level: ExplorationAccessLevel): List<ExplorationAction> {
            return allActions().filter { it.requiredLevel.level <= level.level }
        }

        /**
         * Get all sensitive actions.
         */
        fun sensitiveActions(): List<ExplorationAction> {
            return allActions().filter { it.isSensitive }
        }

        /**
         * Find action by name.
         */
        fun fromName(name: String): ExplorationAction? {
            return allActions().find { it.actionName.equals(name, ignoreCase = true) }
        }
    }
}

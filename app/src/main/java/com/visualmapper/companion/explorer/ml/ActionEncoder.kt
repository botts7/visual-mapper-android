package com.visualmapper.companion.explorer.ml

import com.visualmapper.companion.explorer.ClickableElement

/**
 * ActionEncoder - Encodes UI element actions into feature vectors for neural network input
 *
 * Converts a ClickableElement into a fixed-size float array that captures:
 * - Position (normalized x, y, size)
 * - Element type (button, image, text, etc.)
 * - Semantic hints (navigation, back, settings, etc.)
 * - Exploration status
 *
 * Feature dimension: 8 floats (normalized 0-1)
 */
class ActionEncoder(
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 2400
) {

    companion object {
        const val ACTION_DIM = 8  // Fixed feature dimension for action encoding
    }

    /**
     * Encode a clickable element into a feature vector
     */
    fun encode(element: ClickableElement): FloatArray {
        return floatArrayOf(
            // === Position Features (3) ===
            // 0: Normalized X position
            element.centerX.toFloat() / screenWidth,

            // 1: Normalized Y position
            element.centerY.toFloat() / screenHeight,

            // 2: Normalized element size
            getNormalizedSize(element),

            // === Type Features (3) ===
            // 3: Is button (binary)
            if (isButton(element)) 1f else 0f,

            // 4: Is image/icon (binary)
            if (isImage(element)) 1f else 0f,

            // 5: Has text (binary)
            if (element.text?.isNotEmpty() == true ||
                element.contentDescription?.isNotEmpty() == true) 1f else 0f,

            // === Semantic Features (2) ===
            // 6: Is navigation element (binary)
            if (isNavigationElement(element)) 1f else 0f,

            // 7: Is potentially dangerous (back, close, exit)
            if (isDangerousElement(element)) 1f else 0f
        )
    }

    /**
     * Encode with extended features (for more detailed analysis)
     */
    fun encodeExtended(element: ClickableElement): FloatArray {
        val basic = encode(element)
        val extended = floatArrayOf(
            // 8: Is in top area
            if (element.centerY < screenHeight / 4) 1f else 0f,

            // 9: Is in bottom area
            if (element.centerY > screenHeight * 3 / 4) 1f else 0f,

            // 10: Is in left area
            if (element.centerX < screenWidth / 3) 1f else 0f,

            // 11: Is in right area
            if (element.centerX > screenWidth * 2 / 3) 1f else 0f,

            // 12: Exploration status
            if (element.explored) 1f else 0f,

            // 13: Is settings-related
            if (isSettingsElement(element)) 1f else 0f,

            // 14: Is search-related
            if (isSearchElement(element)) 1f else 0f,

            // 15: Is menu-related
            if (isMenuElement(element)) 1f else 0f
        )
        return basic + extended
    }

    /**
     * Get normalized element size
     */
    private fun getNormalizedSize(element: ClickableElement): Float {
        val area = (element.bounds.width * element.bounds.height).toFloat()
        val screenArea = (screenWidth * screenHeight).toFloat()

        // Normalize and clamp (typical button is ~0.01 of screen)
        return (area / screenArea * 10).coerceIn(0f, 1f)
    }

    /**
     * Check if element is a button
     */
    private fun isButton(element: ClickableElement): Boolean {
        val className = element.className.lowercase()
        return className.contains("button") ||
                className.contains("imagebutton") ||
                className.contains("materialbutto") ||
                className.contains("floatingactionbutton") ||
                className.contains("chip")
    }

    /**
     * Check if element is an image/icon
     */
    private fun isImage(element: ClickableElement): Boolean {
        val className = element.className.lowercase()
        return className.contains("image") ||
                className.contains("icon") ||
                className.contains("drawable")
    }

    /**
     * Check if element is a navigation element
     */
    private fun isNavigationElement(element: ClickableElement): Boolean {
        val resourceId = element.resourceId?.lowercase() ?: ""
        val text = (element.text ?: element.contentDescription)?.lowercase() ?: ""

        val navPatterns = listOf(
            "nav", "tab", "bottom_navigation", "bottomnav",
            "toolbar", "actionbar", "home", "menu"
        )

        return navPatterns.any { pattern ->
            resourceId.contains(pattern) || text.contains(pattern)
        }
    }

    /**
     * Check if element is potentially dangerous (exits app, goes back)
     */
    private fun isDangerousElement(element: ClickableElement): Boolean {
        val resourceId = element.resourceId?.lowercase() ?: ""
        val text = (element.text ?: element.contentDescription)?.lowercase() ?: ""

        val dangerPatterns = listOf(
            "back", "close", "exit", "cancel", "dismiss",
            "logout", "signout", "sign_out", "delete",
            "navigate_up", "home_as_up"
        )

        return dangerPatterns.any { pattern ->
            resourceId.contains(pattern) || text.contains(pattern)
        }
    }

    /**
     * Check if element is settings-related
     */
    private fun isSettingsElement(element: ClickableElement): Boolean {
        val resourceId = element.resourceId?.lowercase() ?: ""
        val text = (element.text ?: element.contentDescription)?.lowercase() ?: ""

        val settingsPatterns = listOf("setting", "config", "preference", "option", "gear")
        return settingsPatterns.any { pattern ->
            resourceId.contains(pattern) || text.contains(pattern)
        }
    }

    /**
     * Check if element is search-related
     */
    private fun isSearchElement(element: ClickableElement): Boolean {
        val resourceId = element.resourceId?.lowercase() ?: ""
        val text = (element.text ?: element.contentDescription)?.lowercase() ?: ""

        val searchPatterns = listOf("search", "find", "query", "lookup")
        return searchPatterns.any { pattern ->
            resourceId.contains(pattern) || text.contains(pattern)
        }
    }

    /**
     * Check if element is menu-related
     */
    private fun isMenuElement(element: ClickableElement): Boolean {
        val resourceId = element.resourceId?.lowercase() ?: ""
        val text = (element.text ?: element.contentDescription)?.lowercase() ?: ""

        val menuPatterns = listOf("menu", "overflow", "more", "dots", "hamburger", "drawer")
        return menuPatterns.any { pattern ->
            resourceId.contains(pattern) || text.contains(pattern)
        }
    }
}

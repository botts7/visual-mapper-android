package com.visualmapper.companion.navigation

/**
 * Represents an action that may cause a screen transition.
 * Mirrors the server-side TransitionAction model for MQTT compatibility.
 *
 * Used by NavigationLearner to capture and transmit navigation patterns
 * to the Visual Mapper server for building navigation graphs.
 */
data class TransitionAction(
    val actionType: String,  // "tap", "swipe", "go_back", "go_home", "keyevent", "text"
    val x: Int? = null,
    val y: Int? = null,
    val elementResourceId: String? = null,
    val elementText: String? = null,
    val elementClass: String? = null,
    val elementContentDesc: String? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val swipeDirection: String? = null,  // "up", "down", "left", "right"
    val keycode: String? = null,
    val text: String? = null,
    val description: String? = null
) {
    /**
     * Convert to JSON string for MQTT transmission.
     * Matches the server's TransitionAction JSON schema.
     */
    fun toJson(): String {
        return buildString {
            append("{")
            append("\"action_type\":\"$actionType\"")
            x?.let { append(",\"x\":$it") }
            y?.let { append(",\"y\":$it") }
            elementResourceId?.let { append(",\"element_resource_id\":\"${escapeJson(it)}\"") }
            elementText?.let { append(",\"element_text\":\"${escapeJson(it)}\"") }
            elementClass?.let { append(",\"element_class\":\"${escapeJson(it)}\"") }
            elementContentDesc?.let { append(",\"element_content_desc\":\"${escapeJson(it)}\"") }
            startX?.let { append(",\"start_x\":$it") }
            startY?.let { append(",\"start_y\":$it") }
            endX?.let { append(",\"end_x\":$it") }
            endY?.let { append(",\"end_y\":$it") }
            swipeDirection?.let { append(",\"swipe_direction\":\"$it\"") }
            keycode?.let { append(",\"keycode\":\"$it\"") }
            text?.let { append(",\"text\":\"${escapeJson(it)}\"") }
            description?.let { append(",\"description\":\"${escapeJson(it)}\"") }
            append("}")
        }
    }

    companion object {
        /**
         * Create a tap action
         */
        fun tap(x: Int, y: Int, resourceId: String? = null, text: String? = null): TransitionAction {
            return TransitionAction(
                actionType = "tap",
                x = x,
                y = y,
                elementResourceId = resourceId,
                elementText = text
            )
        }

        /**
         * Create a swipe action
         */
        fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, direction: String? = null): TransitionAction {
            return TransitionAction(
                actionType = "swipe",
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                swipeDirection = direction
            )
        }

        /**
         * Create a back button action
         */
        fun goBack(): TransitionAction {
            return TransitionAction(
                actionType = "go_back",
                keycode = "KEYCODE_BACK",
                description = "Press back button"
            )
        }

        /**
         * Create a home button action
         */
        fun goHome(): TransitionAction {
            return TransitionAction(
                actionType = "go_home",
                keycode = "KEYCODE_HOME",
                description = "Press home button"
            )
        }

        /**
         * Create a key event action
         */
        fun keyEvent(keycode: String, description: String? = null): TransitionAction {
            return TransitionAction(
                actionType = "keyevent",
                keycode = keycode,
                description = description
            )
        }

        private fun escapeJson(str: String): String {
            return str
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}

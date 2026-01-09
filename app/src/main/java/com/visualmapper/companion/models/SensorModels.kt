package com.visualmapper.companion.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sensor data models for Android CAPTURE_SENSORS implementation.
 *
 * These models match the server-side Python models (sensor_models.py)
 * and are used when syncing flows with embedded sensor definitions.
 */

/**
 * Text extraction methods available for sensor value extraction.
 * Matches Python ExtractionMethod enum.
 */
@Serializable
enum class ExtractionMethod {
    @SerialName("exact") EXACT,
    @SerialName("regex") REGEX,
    @SerialName("numeric") NUMERIC,
    @SerialName("before") BEFORE,
    @SerialName("after") AFTER,
    @SerialName("between") BETWEEN
}

/**
 * Bounds rectangle for element position on screen.
 * All values are in device screen coordinates.
 */
@Serializable
data class SensorElementBounds(
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    /**
     * Check if these bounds contain a point
     */
    fun contains(px: Int, py: Int): Boolean {
        return px >= x && px < x + width && py >= y && py < y + height
    }

    /**
     * Check if these bounds overlap with other bounds within tolerance
     */
    fun overlaps(other: SensorElementBounds, tolerance: Int = 50): Boolean {
        return kotlin.math.abs(x - other.x) <= tolerance &&
               kotlin.math.abs(y - other.y) <= tolerance &&
               kotlin.math.abs(width - other.width) <= tolerance &&
               kotlin.math.abs(height - other.height) <= tolerance
    }

    /**
     * Get center X coordinate
     */
    val centerX: Int get() = x + width / 2

    /**
     * Get center Y coordinate
     */
    val centerY: Int get() = y + height / 2
}

/**
 * Pipeline step for multi-step text extraction.
 * Each step applies one extraction method to the result of the previous step.
 */
@Serializable
data class ExtractionPipelineStep(
    val method: ExtractionMethod = ExtractionMethod.EXACT,
    @SerialName("regex_pattern") val regexPattern: String? = null,
    @SerialName("before_text") val beforeText: String? = null,
    @SerialName("after_text") val afterText: String? = null,
    @SerialName("between_start") val betweenStart: String? = null,
    @SerialName("between_end") val betweenEnd: String? = null,
    @SerialName("extract_numeric") val extractNumeric: Boolean = false,
    @SerialName("remove_unit") val removeUnit: Boolean = false
)

/**
 * Rules for extracting sensor values from text.
 * Supports single extraction methods or multi-step pipelines.
 */
@Serializable
data class TextExtractionRule(
    val method: ExtractionMethod = ExtractionMethod.EXACT,
    @SerialName("regex_pattern") val regexPattern: String? = null,
    @SerialName("before_text") val beforeText: String? = null,
    @SerialName("after_text") val afterText: String? = null,
    @SerialName("between_start") val betweenStart: String? = null,
    @SerialName("between_end") val betweenEnd: String? = null,
    @SerialName("extract_numeric") val extractNumeric: Boolean = false,
    @SerialName("remove_unit") val removeUnit: Boolean = false,
    @SerialName("fallback_value") val fallbackValue: String? = null,
    val pipeline: List<ExtractionPipelineStep>? = null
) {
    /**
     * Check if this rule uses a pipeline (multi-step extraction)
     */
    fun usesPipeline(): Boolean = !pipeline.isNullOrEmpty()
}

/**
 * Source definition for where to find the sensor value.
 * Can identify elements by resource ID, text, class, or bounds.
 */
@Serializable
data class SensorSource(
    @SerialName("source_type") val sourceType: String = "element",
    @SerialName("element_index") val elementIndex: Int? = null,
    @SerialName("element_text") val elementText: String? = null,
    @SerialName("element_class") val elementClass: String? = null,
    @SerialName("element_resource_id") val elementResourceId: String? = null,
    @SerialName("custom_bounds") val customBounds: SensorElementBounds? = null
) {
    /**
     * Check if this source has a resource ID for matching
     */
    fun hasResourceId(): Boolean = !elementResourceId.isNullOrBlank()

    /**
     * Check if this source has text for matching
     */
    fun hasText(): Boolean = !elementText.isNullOrBlank()

    /**
     * Check if this source has a class for matching
     */
    fun hasClass(): Boolean = !elementClass.isNullOrBlank()

    /**
     * Check if this source has bounds for fallback matching
     */
    fun hasBounds(): Boolean = customBounds != null
}

/**
 * Complete sensor definition with all information needed to capture a value.
 * This is embedded in flow steps when syncing to Android.
 */
@Serializable
data class SensorDefinition(
    @SerialName("sensor_id") val sensorId: String = "",
    val name: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("device_class") val deviceClass: String? = null,
    @SerialName("unit_of_measurement") val unitOfMeasurement: String? = null,
    val icon: String? = null,
    val enabled: Boolean = true,
    val source: SensorSource = SensorSource(),
    @SerialName("extraction_rule") val extractionRule: TextExtractionRule = TextExtractionRule(),
    @SerialName("mqtt_topic") val mqttTopic: String? = null,
    @SerialName("ha_discovery_topic") val haDiscoveryTopic: String? = null
) {
    /**
     * Get the MQTT state topic for publishing values.
     * Uses provided topic or constructs default.
     */
    fun getStateTopic(): String {
        return mqttTopic ?: "visual_mapper/$deviceId/$sensorId/state"
    }

    /**
     * Get the Home Assistant discovery topic.
     * Uses provided topic or constructs default.
     */
    fun getDiscoveryTopic(): String {
        return haDiscoveryTopic ?: "homeassistant/sensor/$deviceId/$sensorId/config"
    }
}

/**
 * Result of a single sensor capture attempt.
 */
data class SensorCaptureResult(
    val sensorId: String,
    val success: Boolean,
    val value: String? = null,
    val rawText: String? = null,
    val confidence: Float = 0f,
    val method: String = "none",
    val errorMessage: String? = null
) {
    companion object {
        fun success(
            sensorId: String,
            value: String,
            rawText: String? = null,
            confidence: Float = 1f,
            method: String = "direct"
        ) = SensorCaptureResult(
            sensorId = sensorId,
            success = true,
            value = value,
            rawText = rawText,
            confidence = confidence,
            method = method
        )

        fun failure(
            sensorId: String,
            errorMessage: String
        ) = SensorCaptureResult(
            sensorId = sensorId,
            success = false,
            errorMessage = errorMessage
        )
    }
}

/**
 * Result of finding an element on screen.
 * Used by SmartElementFinder.
 */
data class ElementMatch(
    val found: Boolean,
    val element: UIElement? = null,
    val bounds: SensorElementBounds? = null,
    val confidence: Float = 0f,
    val method: String = "none",
    val message: String = ""
) {
    companion object {
        fun notFound(message: String) = ElementMatch(
            found = false,
            message = message
        )

        fun found(
            element: UIElement,
            bounds: SensorElementBounds,
            confidence: Float,
            method: String
        ) = ElementMatch(
            found = true,
            element = element,
            bounds = bounds,
            confidence = confidence,
            method = method,
            message = "Found via $method"
        )
    }
}

/**
 * Simplified UI element representation for sensor capture.
 * Matches structure from Accessibility Service.
 */
data class UIElement(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val resourceId: String? = null,
    val bounds: SensorElementBounds? = null,
    val isClickable: Boolean = false,
    val isEnabled: Boolean = true,
    val children: List<UIElement> = emptyList()
) {
    /**
     * Get the display text (text or content description)
     */
    fun getDisplayText(): String? = text ?: contentDescription

    /**
     * Check if this element matches a resource ID (can be partial match)
     */
    fun matchesResourceId(id: String): Boolean {
        if (resourceId.isNullOrBlank()) return false
        return resourceId == id || resourceId.endsWith(":id/$id")
    }

    /**
     * Check if this element's text contains the given string
     */
    fun containsText(searchText: String, ignoreCase: Boolean = true): Boolean {
        val displayText = getDisplayText() ?: return false
        return displayText.contains(searchText, ignoreCase = ignoreCase)
    }

    /**
     * Check if this element's class matches
     */
    fun matchesClass(className: String): Boolean {
        return this.className?.contains(className, ignoreCase = true) == true
    }
}

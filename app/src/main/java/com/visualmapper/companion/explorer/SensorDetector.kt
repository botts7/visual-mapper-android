package com.visualmapper.companion.explorer

/**
 * SensorDetector - Pattern-based sensor detection from text content
 *
 * Analyzes text elements to identify potential sensor values
 * and suggests appropriate device class, unit, and icon.
 */
object SensorDetector {

    /**
     * Result of sensor detection
     */
    data class SensorDetectionResult(
        val sensorType: SuggestedSensorType,
        val deviceClass: String?,
        val unit: String?,
        val icon: String?,
        val extractionMethod: String,
        val confidence: Float
    )

    // Detection patterns ordered by specificity (most specific first)
    private val patterns = listOf(
        // Temperature
        PatternMatcher(
            regex = Regex("-?\\d+\\.?\\d*\\s*°[CF]", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "temperature",
            unit = { match -> if (match.contains("F", ignoreCase = true)) "°F" else "°C" },
            icon = "mdi:thermometer",
            confidence = 0.95f
        ),
        PatternMatcher(
            regex = Regex("-?\\d+\\.?\\d*\\s*degrees?", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "temperature",
            unit = { "°" },
            icon = "mdi:thermometer",
            confidence = 0.80f
        ),

        // Battery/Percentage
        PatternMatcher(
            regex = Regex("\\d{1,3}\\s*%"),
            sensorType = SuggestedSensorType.PERCENTAGE,
            deviceClass = "battery",
            unit = { "%" },
            icon = "mdi:battery",
            confidence = 0.90f
        ),

        // Power/Energy
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*kWh", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "energy",
            unit = { "kWh" },
            icon = "mdi:flash",
            confidence = 0.95f
        ),
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*kW", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "power",
            unit = { "kW" },
            icon = "mdi:flash",
            confidence = 0.95f
        ),
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*W(?!h)", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "power",
            unit = { "W" },
            icon = "mdi:flash",
            confidence = 0.90f
        ),

        // Currency/Monetary
        PatternMatcher(
            regex = Regex("\\$\\s*\\d+\\.?\\d*"),
            sensorType = SuggestedSensorType.CURRENCY,
            deviceClass = "monetary",
            unit = { "$" },
            icon = "mdi:currency-usd",
            confidence = 0.95f
        ),
        PatternMatcher(
            regex = Regex("€\\s*\\d+\\.?\\d*"),
            sensorType = SuggestedSensorType.CURRENCY,
            deviceClass = "monetary",
            unit = { "€" },
            icon = "mdi:currency-eur",
            confidence = 0.95f
        ),
        PatternMatcher(
            regex = Regex("£\\s*\\d+\\.?\\d*"),
            sensorType = SuggestedSensorType.CURRENCY,
            deviceClass = "monetary",
            unit = { "£" },
            icon = "mdi:currency-gbp",
            confidence = 0.95f
        ),

        // Data size
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*[KMGT]?B", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "data_size",
            unit = { match ->
                val prefix = match.uppercase().replace(Regex("[^KMGT]"), "").firstOrNull() ?: ""
                "${prefix}B"
            },
            icon = "mdi:database",
            confidence = 0.85f
        ),

        // Duration/Time
        PatternMatcher(
            regex = Regex("\\d{1,2}:\\d{2}(:\\d{2})?"),
            sensorType = SuggestedSensorType.DATE_TIME,
            deviceClass = "duration",
            unit = { null },
            icon = "mdi:clock-outline",
            confidence = 0.85f
        ),
        PatternMatcher(
            regex = Regex("\\d+\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.DATE_TIME,
            deviceClass = "duration",
            unit = { match ->
                when {
                    match.contains("hour", ignoreCase = true) || match.contains("hr", ignoreCase = true) -> "h"
                    match.contains("min", ignoreCase = true) -> "min"
                    else -> "s"
                }
            },
            icon = "mdi:timer-outline",
            confidence = 0.85f
        ),

        // Speed
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*(mph|km/h|kmh|m/s)", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "speed",
            unit = { match ->
                when {
                    match.contains("mph", ignoreCase = true) -> "mph"
                    match.contains("km", ignoreCase = true) -> "km/h"
                    else -> "m/s"
                }
            },
            icon = "mdi:speedometer",
            confidence = 0.90f
        ),

        // Distance
        PatternMatcher(
            regex = Regex("\\d+\\.?\\d*\\s*(km|mi|miles?|meters?|ft|feet)", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "distance",
            unit = { match ->
                when {
                    match.contains("km", ignoreCase = true) -> "km"
                    match.contains("mi", ignoreCase = true) -> "mi"
                    match.contains("ft", ignoreCase = true) || match.contains("feet", ignoreCase = true) -> "ft"
                    else -> "m"
                }
            },
            icon = "mdi:map-marker-distance",
            confidence = 0.85f
        ),

        // Boolean/Status
        PatternMatcher(
            regex = Regex("^(on|off|open|closed|locked|unlocked|home|away|active|inactive|enabled|disabled)$", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.BOOLEAN,
            deviceClass = null,
            unit = { null },
            icon = "mdi:toggle-switch",
            confidence = 0.90f
        ),

        // Signal strength
        PatternMatcher(
            regex = Regex("-?\\d+\\s*dBm?", RegexOption.IGNORE_CASE),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = "signal_strength",
            unit = { "dBm" },
            icon = "mdi:signal",
            confidence = 0.90f
        ),

        // Generic number (last resort)
        PatternMatcher(
            regex = Regex("^-?\\d+\\.?\\d*$"),
            sensorType = SuggestedSensorType.NUMBER,
            deviceClass = null,
            unit = { null },
            icon = "mdi:numeric",
            confidence = 0.50f
        )
    )

    /**
     * Detect sensor type from text content.
     * @return SensorDetectionResult if a pattern matches, null otherwise
     */
    fun detect(text: String): SensorDetectionResult? {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty() || trimmedText.length > 100) {
            return null
        }

        for (pattern in patterns) {
            val match = pattern.regex.find(trimmedText)
            if (match != null) {
                return SensorDetectionResult(
                    sensorType = pattern.sensorType,
                    deviceClass = pattern.deviceClass,
                    unit = pattern.unit(match.value),
                    icon = pattern.icon,
                    extractionMethod = if (pattern.regex.pattern.contains("^") && pattern.regex.pattern.contains("$")) {
                        "exact"
                    } else {
                        "regex"
                    },
                    confidence = pattern.confidence
                )
            }
        }

        return null
    }

    /**
     * Pattern matcher configuration
     */
    private data class PatternMatcher(
        val regex: Regex,
        val sensorType: SuggestedSensorType,
        val deviceClass: String?,
        val unit: (String) -> String?,
        val icon: String,
        val confidence: Float
    )
}

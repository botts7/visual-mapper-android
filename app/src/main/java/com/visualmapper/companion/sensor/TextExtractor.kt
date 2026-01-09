package com.visualmapper.companion.sensor

import com.visualmapper.companion.models.ExtractionMethod
import com.visualmapper.companion.models.ExtractionPipelineStep
import com.visualmapper.companion.models.TextExtractionRule

/**
 * Text Extractor for sensor value extraction.
 *
 * Ports the Python text_extractor.py to Kotlin.
 * Supports 6 extraction methods plus pipeline support for multi-step extraction.
 *
 * Methods:
 * - EXACT: Returns text as-is
 * - REGEX: Extract using regex pattern
 * - NUMERIC: Extract first number from text
 * - BEFORE: Extract text before a marker
 * - AFTER: Extract text after a marker
 * - BETWEEN: Extract text between two markers
 */
class TextExtractor {

    companion object {
        private const val TAG = "TextExtractor"

        // Common unit patterns to remove
        private val UNIT_PATTERNS = listOf(
            "°[CF]", "degrees?", "celsius", "fahrenheit",
            "%", "percent",
            "mph", "km/h", "m/s",
            "kWh?", "Wh?", "W",
            "MB", "GB", "TB", "KB",
            "ms", "s", "sec", "seconds?", "min", "minutes?", "hrs?", "hours?",
            "psi", "bar", "Pa",
            "lbs?", "kg", "g", "oz"
        )

        // Combined regex for unit removal
        private val UNIT_REGEX = Regex(
            "\\s*(" + UNIT_PATTERNS.joinToString("|") + ")\\s*$",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Extract a value from text using the given extraction rule.
     *
     * @param text The source text to extract from
     * @param rule The extraction rule to apply
     * @return Extracted value or null if extraction failed
     */
    fun extract(text: String?, rule: TextExtractionRule): String? {
        if (text.isNullOrBlank()) {
            return rule.fallbackValue
        }

        // Use pipeline if available, otherwise single extraction
        val result = if (rule.usesPipeline() && rule.pipeline != null) {
            extractPipeline(text, rule.pipeline)
        } else {
            extractSingle(text, rule)
        }

        // Apply numeric extraction if requested (post-processing)
        val numericResult = if (rule.extractNumeric && result != null) {
            extractNumeric(result) ?: result
        } else {
            result
        }

        // Remove unit if requested
        val finalResult = if (rule.removeUnit && numericResult != null) {
            removeUnit(numericResult)
        } else {
            numericResult
        }

        return finalResult ?: rule.fallbackValue
    }

    /**
     * Extract using a single method (not a pipeline).
     */
    private fun extractSingle(text: String, rule: TextExtractionRule): String? {
        return when (rule.method) {
            ExtractionMethod.EXACT -> extractExact(text)
            ExtractionMethod.REGEX -> extractRegex(text, rule.regexPattern)
            ExtractionMethod.NUMERIC -> extractNumeric(text)
            ExtractionMethod.BEFORE -> extractBefore(text, rule.beforeText)
            ExtractionMethod.AFTER -> extractAfter(text, rule.afterText)
            ExtractionMethod.BETWEEN -> extractBetween(text, rule.betweenStart, rule.betweenEnd)
        }
    }

    /**
     * Extract using a pipeline of steps.
     * Each step's output becomes the next step's input.
     */
    fun extractPipeline(text: String, steps: List<ExtractionPipelineStep>): String? {
        var result: String? = text

        for (step in steps) {
            if (result == null) break

            result = when (step.method) {
                ExtractionMethod.EXACT -> result // No-op for exact
                ExtractionMethod.REGEX -> extractRegex(result, step.regexPattern)
                ExtractionMethod.NUMERIC -> extractNumeric(result)
                ExtractionMethod.BEFORE -> extractBefore(result, step.beforeText)
                ExtractionMethod.AFTER -> extractAfter(result, step.afterText)
                ExtractionMethod.BETWEEN -> extractBetween(result, step.betweenStart, step.betweenEnd)
            }

            // Apply post-processing if requested in step
            if (step.extractNumeric && result != null) {
                result = extractNumeric(result) ?: result
            }
            if (step.removeUnit && result != null) {
                result = removeUnit(result)
            }
        }

        return result
    }

    /**
     * EXACT: Return text as-is (trimmed).
     */
    fun extractExact(text: String): String {
        return text.trim()
    }

    /**
     * REGEX: Extract using regex pattern.
     * Returns first capturing group if present, otherwise full match.
     */
    fun extractRegex(text: String, pattern: String?): String? {
        if (pattern.isNullOrBlank()) return null

        return try {
            val regex = Regex(pattern)
            val match = regex.find(text)
            when {
                match == null -> null
                match.groupValues.size > 1 -> match.groupValues[1] // First capturing group
                else -> match.value // Full match
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Regex pattern error: $pattern", e)
            null
        }
    }

    /**
     * NUMERIC: Extract first number from text.
     * Supports negative numbers and decimals.
     */
    fun extractNumeric(text: String): String? {
        val match = Regex("-?\\d+\\.?\\d*").find(text)
        return match?.value
    }

    /**
     * BEFORE: Extract text before a marker.
     */
    fun extractBefore(text: String, beforeText: String?): String? {
        if (beforeText.isNullOrBlank()) return null

        val index = text.indexOf(beforeText)
        if (index == -1) return null

        return text.substring(0, index).trim()
    }

    /**
     * AFTER: Extract text after a marker.
     */
    fun extractAfter(text: String, afterText: String?): String? {
        if (afterText.isNullOrBlank()) return null

        val index = text.indexOf(afterText)
        if (index == -1) return null

        return text.substring(index + afterText.length).trim()
    }

    /**
     * BETWEEN: Extract text between start and end markers.
     */
    fun extractBetween(text: String, startText: String?, endText: String?): String? {
        if (startText.isNullOrBlank() || endText.isNullOrBlank()) return null

        val startIndex = text.indexOf(startText)
        if (startIndex == -1) return null

        val afterStart = startIndex + startText.length
        val endIndex = text.indexOf(endText, afterStart)
        if (endIndex == -1) return null

        return text.substring(afterStart, endIndex).trim()
    }

    /**
     * Remove common units from the end of a value.
     */
    fun removeUnit(text: String): String {
        return text.replace(UNIT_REGEX, "").trim()
    }

    /**
     * Clean whitespace: normalize multiple spaces/newlines to single space.
     */
    fun cleanWhitespace(text: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Check if extracted value looks valid (not empty, not just whitespace).
     */
    fun isValidValue(value: String?): Boolean {
        return !value.isNullOrBlank() && value.length < 1000
    }
}

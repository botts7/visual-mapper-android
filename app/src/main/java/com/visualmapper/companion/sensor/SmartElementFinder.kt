package com.visualmapper.companion.sensor

import android.util.Log
import com.visualmapper.companion.models.ElementMatch
import com.visualmapper.companion.models.SensorElementBounds
import com.visualmapper.companion.models.SensorSource
import com.visualmapper.companion.models.UIElement

/**
 * Smart Element Finder for locating UI elements.
 *
 * Ports the Python element_finder.py (SmartElementFinder) to Kotlin.
 * Uses multiple strategies to find elements with confidence scoring.
 *
 * Strategies (in order of confidence):
 * 1. RESOURCE_ID (1.0) - Match by Android resource ID
 * 2. TEXT_CLASS (0.9) - Match by text + class name
 * 3. TEXT_ONLY (0.7) - Match by text content only
 * 4. CLASS_BOUNDS (0.5) - Match by class + similar bounds
 * 5. STORED_BOUNDS (0.3) - Fallback to stored bounds location
 */
class SmartElementFinder {

    companion object {
        private const val TAG = "SmartElementFinder"

        // Confidence scores for each matching method
        const val CONFIDENCE_RESOURCE_ID = 1.0f
        const val CONFIDENCE_TEXT_CLASS = 0.9f
        const val CONFIDENCE_TEXT_ONLY = 0.7f
        const val CONFIDENCE_CLASS_BOUNDS = 0.5f
        const val CONFIDENCE_STORED_BOUNDS = 0.3f

        // Tolerance for bounds matching (pixels)
        const val BOUNDS_TOLERANCE = 50
    }

    /**
     * Find an element matching the sensor source definition.
     *
     * @param elements List of UI elements from accessibility service
     * @param source Sensor source definition with element identifiers
     * @return ElementMatch with found element or not found result
     */
    fun findElement(elements: List<UIElement>, source: SensorSource): ElementMatch {
        if (elements.isEmpty()) {
            return ElementMatch.notFound("No UI elements available")
        }

        // Flatten the element tree for searching
        val flatElements = flattenElements(elements)

        // Try each strategy in order of confidence

        // 1. Resource ID match (highest confidence)
        if (source.hasResourceId()) {
            val match = findByResourceId(flatElements, source.elementResourceId!!)
            if (match != null) {
                return ElementMatch.found(
                    element = match,
                    bounds = match.bounds ?: SensorElementBounds(),
                    confidence = CONFIDENCE_RESOURCE_ID,
                    method = "resource_id"
                )
            }
        }

        // 2. Text + Class match
        if (source.hasText() && source.hasClass()) {
            val match = findByTextAndClass(flatElements, source.elementText!!, source.elementClass!!)
            if (match != null) {
                return ElementMatch.found(
                    element = match,
                    bounds = match.bounds ?: SensorElementBounds(),
                    confidence = CONFIDENCE_TEXT_CLASS,
                    method = "text_class"
                )
            }
        }

        // 3. Text only match
        if (source.hasText()) {
            val match = findByText(flatElements, source.elementText!!)
            if (match != null) {
                return ElementMatch.found(
                    element = match,
                    bounds = match.bounds ?: SensorElementBounds(),
                    confidence = CONFIDENCE_TEXT_ONLY,
                    method = "text_only"
                )
            }
        }

        // 4. Class + Bounds match
        if (source.hasClass() && source.hasBounds()) {
            val match = findByClassAndBounds(flatElements, source.elementClass!!, source.customBounds!!)
            if (match != null) {
                return ElementMatch.found(
                    element = match,
                    bounds = match.bounds ?: SensorElementBounds(),
                    confidence = CONFIDENCE_CLASS_BOUNDS,
                    method = "class_bounds"
                )
            }
        }

        // 5. Stored bounds fallback (lowest confidence)
        if (source.hasBounds()) {
            val match = findByBounds(flatElements, source.customBounds!!)
            if (match != null) {
                return ElementMatch.found(
                    element = match,
                    bounds = match.bounds ?: SensorElementBounds(),
                    confidence = CONFIDENCE_STORED_BOUNDS,
                    method = "stored_bounds"
                )
            }
        }

        // Nothing found
        return ElementMatch.notFound(
            "Element not found. Tried: " +
            listOfNotNull(
                if (source.hasResourceId()) "resource_id(${source.elementResourceId})" else null,
                if (source.hasText()) "text(${source.elementText})" else null,
                if (source.hasClass()) "class(${source.elementClass})" else null,
                if (source.hasBounds()) "bounds" else null
            ).joinToString(", ")
        )
    }

    /**
     * Find element by resource ID.
     * Supports both full ID (com.example:id/button) and short ID (button).
     */
    fun findByResourceId(elements: List<UIElement>, resourceId: String): UIElement? {
        return elements.find { element ->
            element.matchesResourceId(resourceId)
        }
    }

    /**
     * Find element by text and class.
     * Both must match (text contains, class contains).
     */
    fun findByTextAndClass(elements: List<UIElement>, text: String, className: String): UIElement? {
        return elements.find { element ->
            element.containsText(text) && element.matchesClass(className)
        }
    }

    /**
     * Find element by text content.
     * Uses contains matching (case insensitive).
     */
    fun findByText(elements: List<UIElement>, text: String): UIElement? {
        // First try exact match
        val exactMatch = elements.find { element ->
            element.getDisplayText()?.equals(text, ignoreCase = true) == true
        }
        if (exactMatch != null) return exactMatch

        // Then try contains match
        return elements.find { element ->
            element.containsText(text)
        }
    }

    /**
     * Find element by class and bounds proximity.
     * Class must match and bounds must be within tolerance.
     */
    fun findByClassAndBounds(elements: List<UIElement>, className: String, bounds: SensorElementBounds): UIElement? {
        return elements.find { element ->
            element.matchesClass(className) &&
            element.bounds?.overlaps(bounds, BOUNDS_TOLERANCE) == true
        }
    }

    /**
     * Find element by bounds only (lowest confidence).
     * Returns element whose bounds are closest to target.
     */
    fun findByBounds(elements: List<UIElement>, bounds: SensorElementBounds): UIElement? {
        // Find element with closest bounds
        return elements
            .filter { it.bounds != null }
            .minByOrNull { element ->
                val eb = element.bounds!!
                kotlin.math.abs(eb.x - bounds.x) +
                kotlin.math.abs(eb.y - bounds.y) +
                kotlin.math.abs(eb.width - bounds.width) +
                kotlin.math.abs(eb.height - bounds.height)
            }
            ?.takeIf { element ->
                // Only return if within tolerance
                element.bounds?.overlaps(bounds, BOUNDS_TOLERANCE * 2) == true
            }
    }

    /**
     * Find all elements matching a text pattern.
     * Useful for finding multiple sensors with similar labels.
     */
    fun findAllByText(elements: List<UIElement>, text: String): List<UIElement> {
        val flat = flattenElements(elements)
        return flat.filter { it.containsText(text) }
    }

    /**
     * Find all elements of a specific class.
     */
    fun findAllByClass(elements: List<UIElement>, className: String): List<UIElement> {
        val flat = flattenElements(elements)
        return flat.filter { it.matchesClass(className) }
    }

    /**
     * Find element at a specific point.
     */
    fun findElementAt(elements: List<UIElement>, x: Int, y: Int): UIElement? {
        val flat = flattenElements(elements)
        // Find smallest element containing the point (most specific)
        return flat
            .filter { it.bounds?.contains(x, y) == true }
            .minByOrNull { it.bounds!!.width * it.bounds.height }
    }

    /**
     * Flatten nested element tree into a list.
     * Performs depth-first traversal.
     */
    fun flattenElements(elements: List<UIElement>): List<UIElement> {
        val result = mutableListOf<UIElement>()
        for (element in elements) {
            result.add(element)
            if (element.children.isNotEmpty()) {
                result.addAll(flattenElements(element.children))
            }
        }
        return result
    }

    /**
     * Get all text content from elements.
     * Useful for debugging or full-screen text extraction.
     */
    fun getAllText(elements: List<UIElement>): List<String> {
        return flattenElements(elements)
            .mapNotNull { it.getDisplayText() }
            .filter { it.isNotBlank() }
    }

    /**
     * Count elements in the tree.
     */
    fun countElements(elements: List<UIElement>): Int {
        return flattenElements(elements).size
    }
}

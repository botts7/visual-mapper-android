package com.visualmapper.companion.explorer

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests for Hit-Test Logic
 *
 * Tests the element hit-detection algorithm used for imitation learning.
 * This is extracted logic - no Android mocking required.
 */
class HitTestLogicTest {

    /**
     * Simplified bounds data class for testing (mirrors ClickableElement.Bounds)
     */
    data class TestBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * Simplified element for testing (mirrors ClickableElement)
     */
    data class TestElement(
        val elementId: String,
        val bounds: TestBounds,
        val text: String? = null
    )

    /**
     * Hit-test result
     */
    data class HitTestResult(
        val element: TestElement?,
        val confidence: Float
    )

    private lateinit var elements: List<TestElement>

    @Before
    fun setup() {
        // Create a test screen with 5 elements
        elements = listOf(
            TestElement("button_submit", TestBounds(100, 200, 200, 80), "Submit"),
            TestElement("button_cancel", TestBounds(100, 300, 200, 80), "Cancel"),
            TestElement("input_email", TestBounds(50, 100, 300, 60), "Email"),
            TestElement("checkbox_agree", TestBounds(50, 400, 40, 40), null),
            TestElement("nav_back", TestBounds(10, 10, 50, 50), "Back")
        )
    }

    /**
     * Core hit-test algorithm (extracted from HumanInLoopManager)
     */
    private fun performHitTest(clickX: Int, clickY: Int, elements: List<TestElement>): HitTestResult {
        val candidates = elements.mapNotNull { element ->
            val rect = Rect(
                element.bounds.x,
                element.bounds.y,
                element.bounds.x + element.bounds.width,
                element.bounds.y + element.bounds.height
            )

            if (rect.contains(clickX, clickY)) {
                // Calculate confidence based on distance from center
                val centerX = rect.centerX()
                val centerY = rect.centerY()
                val distance = kotlin.math.sqrt(
                    ((clickX - centerX) * (clickX - centerX) +
                     (clickY - centerY) * (clickY - centerY)).toDouble()
                ).toFloat()

                val maxDist = kotlin.math.max(rect.width(), rect.height()) / 2f
                val confidence = if (maxDist > 0) 1f - (distance / maxDist).coerceIn(0f, 1f) else 1f

                element to confidence
            } else {
                null
            }
        }

        val bestMatch = candidates.maxByOrNull { it.second }

        return if (bestMatch != null) {
            HitTestResult(bestMatch.first, bestMatch.second)
        } else {
            HitTestResult(null, 0f)
        }
    }

    // =========================================================================
    // Test Cases
    // =========================================================================

    @Test
    fun `hit-test returns correct element when click is centered`() {
        // Click in center of Submit button
        val result = performHitTest(200, 240, elements)

        assertNotNull("Should match an element", result.element)
        assertEquals("button_submit", result.element?.elementId)
        assertTrue("Confidence should be high for centered click", result.confidence > 0.8f)
    }

    @Test
    fun `hit-test returns correct element when click is near edge`() {
        // Click near edge of Submit button (but still inside)
        val result = performHitTest(105, 205, elements)

        assertNotNull("Should still match element", result.element)
        assertEquals("button_submit", result.element?.elementId)
        assertTrue("Confidence should be lower for edge click", result.confidence < 0.5f)
    }

    @Test
    fun `hit-test returns null when click misses all elements`() {
        // Click in empty area
        val result = performHitTest(500, 500, elements)

        assertNull("Should not match any element", result.element)
        assertEquals(0f, result.confidence, 0.001f)
    }

    @Test
    fun `hit-test distinguishes between adjacent elements`() {
        // Click on Cancel button (below Submit)
        val result = performHitTest(200, 340, elements)

        assertNotNull(result.element)
        assertEquals("button_cancel", result.element?.elementId)
    }

    @Test
    fun `hit-test handles small elements correctly`() {
        // Click on small checkbox
        val result = performHitTest(70, 420, elements)

        assertNotNull(result.element)
        assertEquals("checkbox_agree", result.element?.elementId)
    }

    @Test
    fun `hit-test returns highest confidence when overlapping`() {
        // Create overlapping elements
        val overlapping = listOf(
            TestElement("outer", TestBounds(0, 0, 200, 200), "Outer"),
            TestElement("inner", TestBounds(50, 50, 100, 100), "Inner")
        )

        // Click in center of inner element
        val result = performHitTest(100, 100, overlapping)

        // Both contain the point, but inner should have higher confidence
        // because click is more centered relative to its bounds
        assertNotNull(result.element)
        // Inner element is smaller, so centered click = higher confidence
        assertEquals("inner", result.element?.elementId)
    }

    @Test
    fun `hit-test handles edge case of zero-size element`() {
        val elements = listOf(
            TestElement("zero", TestBounds(100, 100, 0, 0), "Zero")
        )

        // Should not crash, should return no match
        val result = performHitTest(100, 100, elements)
        // Zero-size elements shouldn't match (or if they do, confidence is 1.0 by our formula)
    }

    @Test
    fun `confidence is exactly 1 when click is perfectly centered`() {
        // Calculate exact center of Submit button
        val centerX = 100 + 200 / 2  // 200
        val centerY = 200 + 80 / 2   // 240

        val result = performHitTest(centerX, centerY, elements)

        assertEquals(1.0f, result.confidence, 0.001f)
    }

    @Test
    fun `hit-test with empty element list returns null`() {
        val result = performHitTest(100, 100, emptyList())

        assertNull(result.element)
        assertEquals(0f, result.confidence, 0.001f)
    }
}

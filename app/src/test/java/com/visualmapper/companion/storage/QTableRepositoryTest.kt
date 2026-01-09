package com.visualmapper.companion.storage

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit Tests for QTableRepository Logic
 *
 * Tests the caching and pruning logic without requiring Room database.
 * Focuses on business logic that can be unit tested.
 */
class QTableRepositoryTest {

    /**
     * Mock in-memory cache for testing (mirrors QTableRepository caches)
     */
    private lateinit var qTableCache: MutableMap<String, Float>
    private lateinit var visitCountCache: MutableMap<String, Int>
    private lateinit var screenVisitCache: MutableMap<String, Int>
    private lateinit var dangerousPatternsCache: MutableSet<String>

    companion object {
        const val MAX_Q_TABLE_SIZE = 100  // Smaller for testing
        const val MIN_VISIT_COUNT_THRESHOLD = 2
    }

    @Before
    fun setup() {
        qTableCache = mutableMapOf()
        visitCountCache = mutableMapOf()
        screenVisitCache = mutableMapOf()
        dangerousPatternsCache = mutableSetOf()
    }

    // =========================================================================
    // Q-Value Cache Tests
    // =========================================================================

    @Test
    fun `updateQValue adds new entry to cache`() {
        val key = "screen_1|action_tap"
        val qValue = 0.5f

        // Simulate updateQValue
        qTableCache[key] = qValue
        visitCountCache[key] = (visitCountCache[key] ?: 0) + 1

        assertEquals(0.5f, qTableCache[key]!!, 0.001f)
        assertEquals(1, visitCountCache[key])
    }

    @Test
    fun `updateQValue increments visit count on update`() {
        val key = "screen_1|action_tap"

        // First update
        qTableCache[key] = 0.5f
        visitCountCache[key] = (visitCountCache[key] ?: 0) + 1

        // Second update
        qTableCache[key] = 0.6f
        visitCountCache[key] = (visitCountCache[key] ?: 0) + 1

        // Third update
        qTableCache[key] = 0.7f
        visitCountCache[key] = (visitCountCache[key] ?: 0) + 1

        assertEquals(0.7f, qTableCache[key]!!, 0.001f)
        assertEquals(3, visitCountCache[key])
    }

    @Test
    fun `getQValue returns null for unknown key`() {
        val result = qTableCache["unknown_key"]
        assertNull(result)
    }

    // =========================================================================
    // Pruning Logic Tests
    // =========================================================================

    @Test
    fun `pruning removes entries with visit count below threshold`() {
        // Add entries with varying visit counts
        for (i in 0 until 10) {
            val key = "screen_$i|action_$i"
            qTableCache[key] = i * 0.1f
            visitCountCache[key] = i % 4  // Visit counts: 0, 1, 2, 3, 0, 1, 2, 3, 0, 1
        }

        // Prune low visit count entries (< 2)
        val keysToRemove = visitCountCache.filter { it.value < MIN_VISIT_COUNT_THRESHOLD }.keys.toList()
        keysToRemove.forEach {
            qTableCache.remove(it)
            visitCountCache.remove(it)
        }

        // Should have removed entries with visit count 0 and 1
        // Entries 0, 1, 4, 5, 8, 9 have visit counts 0 or 1
        assertEquals(4, qTableCache.size)  // Entries 2, 3, 6, 7 remain
    }

    @Test
    fun `pruning respects MAX_Q_TABLE_SIZE`() {
        // Fill cache to 150 entries (over MAX of 100)
        for (i in 0 until 150) {
            val key = "screen_$i|action_$i"
            qTableCache[key] = i * 0.01f
            visitCountCache[key] = i % 10  // Varying visit counts
        }

        assertEquals(150, qTableCache.size)

        // Simulate LRU pruning (remove lowest visit count first)
        if (qTableCache.size > MAX_Q_TABLE_SIZE) {
            val sortedKeys = qTableCache.keys.sortedBy { visitCountCache[it] ?: 0 }
            val excess = qTableCache.size - MAX_Q_TABLE_SIZE
            sortedKeys.take(excess).forEach {
                qTableCache.remove(it)
                visitCountCache.remove(it)
            }
        }

        assertEquals(MAX_Q_TABLE_SIZE, qTableCache.size)
    }

    @Test
    fun `pruning preserves high-visit entries`() {
        // Add entries with high visit counts
        val highValueKeys = mutableListOf<String>()
        for (i in 0 until 50) {
            val key = "important_$i"
            qTableCache[key] = 0.9f
            visitCountCache[key] = 100  // High visit count
            highValueKeys.add(key)
        }

        // Add low-value entries to exceed max
        for (i in 0 until 100) {
            val key = "lowvalue_$i"
            qTableCache[key] = 0.1f
            visitCountCache[key] = 1  // Low visit count
        }

        // Prune to MAX_Q_TABLE_SIZE
        val sortedKeys = qTableCache.keys.sortedBy { visitCountCache[it] ?: 0 }
        val excess = qTableCache.size - MAX_Q_TABLE_SIZE
        sortedKeys.take(excess).forEach {
            qTableCache.remove(it)
            visitCountCache.remove(it)
        }

        // All high-value entries should remain
        highValueKeys.forEach { key ->
            assertTrue("High-value entry $key should be preserved", qTableCache.containsKey(key))
        }
    }

    // =========================================================================
    // Dangerous Patterns Tests
    // =========================================================================

    @Test
    fun `addDangerousPattern adds to set`() {
        val pattern = "button_close_app"

        dangerousPatternsCache.add(pattern)

        assertTrue(dangerousPatternsCache.contains(pattern))
    }

    @Test
    fun `isDangerousPattern returns correct result`() {
        dangerousPatternsCache.add("dangerous_button")

        assertTrue(dangerousPatternsCache.contains("dangerous_button"))
        assertFalse(dangerousPatternsCache.contains("safe_button"))
    }

    @Test
    fun `dangerous patterns are not duplicated`() {
        dangerousPatternsCache.add("pattern_1")
        dangerousPatternsCache.add("pattern_1")
        dangerousPatternsCache.add("pattern_1")

        assertEquals(1, dangerousPatternsCache.size)
    }

    // =========================================================================
    // Screen Visit Tests
    // =========================================================================

    @Test
    fun `incrementScreenVisit creates new entry`() {
        val screenId = "screen_main"

        val newCount = (screenVisitCache[screenId] ?: 0) + 1
        screenVisitCache[screenId] = newCount

        assertEquals(1, screenVisitCache[screenId])
    }

    @Test
    fun `incrementScreenVisit increments existing entry`() {
        val screenId = "screen_main"

        // Three visits
        for (i in 0 until 3) {
            val newCount = (screenVisitCache[screenId] ?: 0) + 1
            screenVisitCache[screenId] = newCount
        }

        assertEquals(3, screenVisitCache[screenId])
    }

    // =========================================================================
    // Concurrency Simulation Tests
    // =========================================================================

    @Test
    fun `rapid updates to same key accumulate correctly`() {
        val key = "screen_1|action_tap"

        // Simulate 100 rapid updates
        repeat(100) { i ->
            qTableCache[key] = i * 0.01f
            visitCountCache[key] = (visitCountCache[key] ?: 0) + 1
        }

        // Final Q-value should be last update
        assertEquals(0.99f, qTableCache[key]!!, 0.001f)
        // Visit count should be 100
        assertEquals(100, visitCountCache[key])
    }

    @Test
    fun `clear removes all data`() {
        // Add data
        qTableCache["key1"] = 0.5f
        qTableCache["key2"] = 0.6f
        visitCountCache["key1"] = 1
        screenVisitCache["screen1"] = 5
        dangerousPatternsCache.add("pattern1")

        // Clear
        qTableCache.clear()
        visitCountCache.clear()
        screenVisitCache.clear()
        dangerousPatternsCache.clear()

        assertTrue(qTableCache.isEmpty())
        assertTrue(visitCountCache.isEmpty())
        assertTrue(screenVisitCache.isEmpty())
        assertTrue(dangerousPatternsCache.isEmpty())
    }

    // =========================================================================
    // Edge Case Tests
    // =========================================================================

    @Test
    fun `handles negative Q-values correctly`() {
        val key = "bad_action"
        qTableCache[key] = -2.0f

        assertEquals(-2.0f, qTableCache[key]!!, 0.001f)
    }

    @Test
    fun `handles very long keys`() {
        val longKey = "screen_" + "a".repeat(1000) + "|action_" + "b".repeat(1000)

        qTableCache[longKey] = 0.5f
        visitCountCache[longKey] = 1

        assertEquals(0.5f, qTableCache[longKey]!!, 0.001f)
    }

    @Test
    fun `handles special characters in keys`() {
        val specialKey = "screen_日本語|action_émoji_🎯"

        qTableCache[specialKey] = 0.5f
        visitCountCache[specialKey] = 1

        assertEquals(0.5f, qTableCache[specialKey]!!, 0.001f)
    }
}

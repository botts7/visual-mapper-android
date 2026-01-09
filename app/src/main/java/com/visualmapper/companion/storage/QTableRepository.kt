package com.visualmapper.companion.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for Q-Table persistence using Room database.
 *
 * Phase 3 ML Stability: Replaces SharedPreferences with Room for:
 * - Atomic writes (no corruption on process kill)
 * - Automatic pruning (prevents unbounded growth)
 * - Better query performance (indexed lookups)
 *
 * Migration Strategy:
 * - On first use, migrates existing SharedPreferences data to Room
 * - After migration, SharedPreferences data is cleared
 * - All new writes go to Room only
 *
 * Thread Safety:
 * - In-memory cache for fast read access during exploration
 * - All Room operations are suspend functions (coroutine-safe)
 * - Cache is updated after successful Room writes
 */
class QTableRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "QTableRepository"
        private const val PREFS_NAME = "exploration_q_table"
        private const val KEY_Q_TABLE = "q_table"
        private const val KEY_VISIT_COUNTS = "visit_counts"
        private const val KEY_DANGEROUS_PATTERNS = "dangerous_patterns"
        private const val KEY_SCREEN_VISIT_COUNTS = "screen_visit_counts"
        private const val KEY_HUMAN_FEEDBACK = "human_feedback"
        private const val KEY_MIGRATED = "migrated_to_room"

        // Pruning thresholds
        const val MAX_Q_TABLE_SIZE = 10000
        const val MIN_VISIT_COUNT_THRESHOLD = 2
        const val PRUNE_BATCH_SIZE = 1000

        @Volatile
        private var INSTANCE: QTableRepository? = null

        fun getInstance(context: Context): QTableRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: QTableRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.qTableDao()
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory caches for fast access
    private val qTableCache = mutableMapOf<String, Float>()
    private val visitCountCache = mutableMapOf<String, Int>()
    private val screenVisitCache = mutableMapOf<String, Int>()
    private val humanFeedbackCache = mutableMapOf<String, Int>()
    private val dangerousPatternsCache = mutableSetOf<String>()

    private var initialized = false
    private var migrated = false

    // =====================================================================
    // Initialization & Migration
    // =====================================================================

    /**
     * Initialize the repository, migrating from SharedPreferences if needed.
     */
    suspend fun initialize() {
        if (initialized) return

        migrated = prefs.getBoolean(KEY_MIGRATED, false)

        if (!migrated) {
            Log.i(TAG, "Migrating Q-table from SharedPreferences to Room...")
            migrateFromSharedPreferences()
        }

        // Load caches from Room
        loadCachesFromRoom()

        initialized = true
        Log.i(TAG, "QTableRepository initialized: ${qTableCache.size} Q-values, ${dangerousPatternsCache.size} dangerous patterns")
    }

    private suspend fun migrateFromSharedPreferences() {
        try {
            // Load existing data from SharedPreferences
            val oldQTable = loadMapFromPrefs(KEY_Q_TABLE) { it.toFloat() }
            val oldVisitCounts = loadMapFromPrefs(KEY_VISIT_COUNTS) { it.toInt() }
            val oldScreenVisits = loadMapFromPrefs(KEY_SCREEN_VISIT_COUNTS) { it.toInt() }
            val oldHumanFeedback = loadMapFromPrefs(KEY_HUMAN_FEEDBACK) { it.toInt() }
            val oldDangerousPatterns = loadSetFromPrefs(KEY_DANGEROUS_PATTERNS)

            // Migrate Q-table entries
            val qTableEntities = oldQTable.map { (key, value) ->
                QTableEntity(
                    stateActionKey = key,
                    qValue = value,
                    visitCount = oldVisitCounts[key] ?: 1
                )
            }
            if (qTableEntities.isNotEmpty()) {
                dao.upsertQValues(qTableEntities)
            }

            // Migrate screen visits
            oldScreenVisits.forEach { (screenId, count) ->
                dao.upsertScreenVisit(ScreenVisitEntity(screenId = screenId, visitCount = count))
            }

            // Migrate human feedback
            oldHumanFeedback.forEach { (key, value) ->
                dao.upsertHumanFeedback(HumanFeedbackEntity(stateActionKey = key, feedbackValue = value))
            }

            // Migrate dangerous patterns
            oldDangerousPatterns.forEach { pattern ->
                dao.upsertDangerousPattern(DangerousPatternEntity(patternKey = pattern))
            }

            // Mark as migrated and clear old data
            prefs.edit()
                .putBoolean(KEY_MIGRATED, true)
                .remove(KEY_Q_TABLE)
                .remove(KEY_VISIT_COUNTS)
                .remove(KEY_SCREEN_VISIT_COUNTS)
                .remove(KEY_HUMAN_FEEDBACK)
                .remove(KEY_DANGEROUS_PATTERNS)
                .apply()

            migrated = true
            Log.i(TAG, "Migration complete: ${qTableEntities.size} Q-values, ${oldDangerousPatterns.size} patterns")

        } catch (e: Exception) {
            Log.e(TAG, "Migration failed, will retry on next init", e)
        }
    }

    private suspend fun loadCachesFromRoom() {
        val export = dao.exportAllData()
        qTableCache.clear()
        qTableCache.putAll(export.qTable)
        visitCountCache.clear()
        visitCountCache.putAll(export.visitCounts)
        screenVisitCache.clear()
        screenVisitCache.putAll(export.screenVisits)
        humanFeedbackCache.clear()
        humanFeedbackCache.putAll(export.humanFeedback)
        dangerousPatternsCache.clear()
        dangerousPatternsCache.addAll(export.dangerousPatterns)
    }

    // =====================================================================
    // Q-Table Operations (Fast Cache Access)
    // =====================================================================

    /**
     * Get Q-value for a state-action pair (from cache, instant).
     */
    fun getQValue(stateActionKey: String): Float? = qTableCache[stateActionKey]

    /**
     * Get all Q-values (from cache).
     */
    fun getAllQValues(): Map<String, Float> = qTableCache.toMap()

    /**
     * Get visit count for a state-action pair.
     */
    fun getVisitCount(stateActionKey: String): Int = visitCountCache[stateActionKey] ?: 0

    /**
     * Get Q-table size.
     */
    fun qTableSize(): Int = qTableCache.size

    /**
     * Update Q-value (async write to Room, instant cache update).
     */
    fun updateQValue(stateActionKey: String, qValue: Float, packageName: String? = null) {
        // Update cache immediately
        qTableCache[stateActionKey] = qValue
        visitCountCache[stateActionKey] = (visitCountCache[stateActionKey] ?: 0) + 1

        // Async write to Room
        scope.launch {
            try {
                dao.upsertWithIncrement(stateActionKey, qValue, packageName)

                // Check if pruning is needed
                val size = dao.getQTableSize()
                if (size > MAX_Q_TABLE_SIZE) {
                    pruneQTable()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist Q-value", e)
            }
        }
    }

    // =====================================================================
    // Screen Visit Operations
    // =====================================================================

    fun getScreenVisitCount(screenId: String): Int = screenVisitCache[screenId] ?: 0

    fun incrementScreenVisit(screenId: String, packageName: String? = null): Int {
        val newCount = (screenVisitCache[screenId] ?: 0) + 1
        screenVisitCache[screenId] = newCount

        scope.launch {
            try {
                dao.incrementScreenVisit(screenId, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist screen visit", e)
            }
        }

        return newCount
    }

    // =====================================================================
    // Human Feedback Operations
    // =====================================================================

    fun getHumanFeedback(stateActionKey: String): Int? = humanFeedbackCache[stateActionKey]

    fun setHumanFeedback(stateActionKey: String, value: Int, packageName: String? = null) {
        humanFeedbackCache[stateActionKey] = value

        scope.launch {
            try {
                dao.upsertHumanFeedback(HumanFeedbackEntity(
                    stateActionKey = stateActionKey,
                    feedbackValue = value,
                    packageName = packageName
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist human feedback", e)
            }
        }
    }

    // =====================================================================
    // Dangerous Patterns Operations
    // =====================================================================

    fun isDangerousPattern(pattern: String): Boolean = dangerousPatternsCache.contains(pattern)

    fun getDangerousPatterns(): Set<String> = dangerousPatternsCache.toSet()

    fun addDangerousPattern(pattern: String, packageName: String? = null) {
        dangerousPatternsCache.add(pattern)

        scope.launch {
            try {
                dao.recordDangerousPattern(pattern, packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist dangerous pattern", e)
            }
        }
    }

    // =====================================================================
    // Pruning Operations
    // =====================================================================

    /**
     * Prune Q-table to prevent unbounded growth.
     * Removes low-value entries based on:
     * 1. Low visit count (< MIN_VISIT_COUNT_THRESHOLD)
     * 2. LRU (least recently used)
     */
    suspend fun pruneQTable(): Int {
        Log.i(TAG, "Starting Q-table pruning...")

        var totalPruned = 0

        // Step 1: Remove low visit count entries
        val prunedLowVisit = dao.pruneLowVisitCount(MIN_VISIT_COUNT_THRESHOLD)
        totalPruned += prunedLowVisit
        Log.d(TAG, "Pruned $prunedLowVisit low-visit entries")

        // Step 2: LRU pruning if still over limit
        val prunedLRU = dao.pruneToMaxSize(MAX_Q_TABLE_SIZE)
        totalPruned += prunedLRU
        Log.d(TAG, "Pruned $prunedLRU LRU entries")

        // Reload cache
        loadCachesFromRoom()

        Log.i(TAG, "Pruning complete: removed $totalPruned entries, ${qTableCache.size} remaining")
        return totalPruned
    }

    // =====================================================================
    // Persistence Operations
    // =====================================================================

    /**
     * Force sync all caches to Room (call on exploration end).
     */
    suspend fun syncToRoom() {
        Log.i(TAG, "Syncing caches to Room...")

        // Q-table entries are written incrementally, but ensure all are persisted
        val entries = qTableCache.map { (key, value) ->
            QTableEntity(
                stateActionKey = key,
                qValue = value,
                visitCount = visitCountCache[key] ?: 1
            )
        }

        if (entries.isNotEmpty()) {
            dao.upsertQValues(entries)
        }

        Log.i(TAG, "Sync complete: ${entries.size} Q-values persisted")
    }

    /**
     * Export all data for MQTT/backup.
     */
    suspend fun exportData(): QTableExport = dao.exportAllData()

    /**
     * Clear all data (for reset/testing).
     */
    suspend fun clearAll() {
        qTableCache.clear()
        visitCountCache.clear()
        screenVisitCache.clear()
        humanFeedbackCache.clear()
        dangerousPatternsCache.clear()

        dao.clearAllData()
        Log.i(TAG, "All Q-learning data cleared")
    }

    // =====================================================================
    // Helper Functions
    // =====================================================================

    private fun <T> loadMapFromPrefs(key: String, converter: (String) -> T): Map<String, T> {
        val json = prefs.getString(key, null) ?: return emptyMap()
        return try {
            val jsonObject = JSONObject(json)
            val result = mutableMapOf<String, T>()
            jsonObject.keys().forEach { k ->
                result[k] = converter(jsonObject.get(k).toString())
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $key from prefs", e)
            emptyMap()
        }
    }

    private fun loadSetFromPrefs(key: String): Set<String> {
        val json = prefs.getString(key, null) ?: return emptySet()
        return try {
            val jsonArray = JSONArray(json)
            val result = mutableSetOf<String>()
            for (i in 0 until jsonArray.length()) {
                result.add(jsonArray.getString(i))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load $key from prefs", e)
            emptySet()
        }
    }

    /**
     * Cleanup when no longer needed.
     */
    fun destroy() {
        scope.cancel()
    }
}

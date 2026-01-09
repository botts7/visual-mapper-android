package com.visualmapper.companion.storage

import androidx.room.*

/**
 * DAO for Q-Table operations.
 *
 * Phase 3 ML Stability: Provides atomic CRUD operations and pruning for Q-learning data.
 *
 * Features:
 * - Upsert operations (insert or update)
 * - Batch operations for efficiency
 * - LRU-based pruning to prevent unbounded growth
 * - App-specific filtering
 */
@Dao
interface QTableDao {

    // =====================================================================
    // Q-Table CRUD Operations
    // =====================================================================

    @Query("SELECT * FROM q_table WHERE stateActionKey = :key LIMIT 1")
    suspend fun getQValue(key: String): QTableEntity?

    @Query("SELECT qValue FROM q_table WHERE stateActionKey = :key LIMIT 1")
    suspend fun getQValueOnly(key: String): Float?

    @Query("SELECT * FROM q_table")
    suspend fun getAllQValues(): List<QTableEntity>

    @Query("SELECT * FROM q_table WHERE packageName = :packageName")
    suspend fun getQValuesForPackage(packageName: String): List<QTableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQValue(entry: QTableEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQValues(entries: List<QTableEntity>)

    @Query("UPDATE q_table SET qValue = :qValue, visitCount = visitCount + 1, lastUpdated = :timestamp WHERE stateActionKey = :key")
    suspend fun updateQValue(key: String, qValue: Float, timestamp: Long = System.currentTimeMillis()): Int

    /**
     * Upsert a Q-value with automatic visit count increment.
     * Returns the row ID (new or updated).
     */
    @Transaction
    suspend fun upsertWithIncrement(key: String, qValue: Float, packageName: String? = null): Long {
        val existing = getQValue(key)
        return if (existing != null) {
            val updated = existing.copy(
                qValue = qValue,
                visitCount = existing.visitCount + 1,
                lastUpdated = System.currentTimeMillis()
            )
            upsertQValue(updated)
            existing.id
        } else {
            val newEntry = QTableEntity(
                stateActionKey = key,
                qValue = qValue,
                visitCount = 1,
                packageName = packageName
            )
            upsertQValue(newEntry)
            0L // Will get auto-generated ID
        }
    }

    @Query("DELETE FROM q_table WHERE stateActionKey = :key")
    suspend fun deleteQValue(key: String)

    @Query("DELETE FROM q_table")
    suspend fun deleteAllQValues()

    @Query("SELECT COUNT(*) FROM q_table")
    suspend fun getQTableSize(): Int

    // =====================================================================
    // Pruning Operations
    // =====================================================================

    /**
     * Delete oldest entries (LRU) to keep table under max size.
     * Prioritizes keeping high-visit-count entries.
     */
    @Query("""
        DELETE FROM q_table WHERE id IN (
            SELECT id FROM q_table
            ORDER BY visitCount ASC, lastUpdated ASC
            LIMIT :count
        )
    """)
    suspend fun pruneLRU(count: Int): Int

    /**
     * Delete entries older than specified timestamp.
     */
    @Query("DELETE FROM q_table WHERE lastUpdated < :cutoffTimestamp")
    suspend fun pruneOlderThan(cutoffTimestamp: Long): Int

    /**
     * Delete entries with visit count below threshold.
     */
    @Query("DELETE FROM q_table WHERE visitCount < :minVisits")
    suspend fun pruneLowVisitCount(minVisits: Int): Int

    /**
     * Prune to keep table at maximum size, removing least valuable entries.
     * Returns number of entries deleted.
     */
    @Transaction
    suspend fun pruneToMaxSize(maxSize: Int): Int {
        val currentSize = getQTableSize()
        if (currentSize <= maxSize) return 0

        val toDelete = currentSize - maxSize
        return pruneLRU(toDelete)
    }

    // =====================================================================
    // Screen Visit Operations
    // =====================================================================

    @Query("SELECT * FROM screen_visits WHERE screenId = :screenId LIMIT 1")
    suspend fun getScreenVisit(screenId: String): ScreenVisitEntity?

    @Query("SELECT visitCount FROM screen_visits WHERE screenId = :screenId LIMIT 1")
    suspend fun getScreenVisitCount(screenId: String): Int?

    @Query("SELECT * FROM screen_visits")
    suspend fun getAllScreenVisits(): List<ScreenVisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScreenVisit(entry: ScreenVisitEntity)

    @Transaction
    suspend fun incrementScreenVisit(screenId: String, packageName: String? = null): Int {
        val existing = getScreenVisit(screenId)
        val newCount = (existing?.visitCount ?: 0) + 1

        val updated = existing?.copy(
            visitCount = newCount,
            lastVisited = System.currentTimeMillis()
        ) ?: ScreenVisitEntity(
            screenId = screenId,
            visitCount = 1,
            packageName = packageName
        )

        upsertScreenVisit(updated)
        return newCount
    }

    @Query("DELETE FROM screen_visits")
    suspend fun deleteAllScreenVisits()

    // =====================================================================
    // Human Feedback Operations
    // =====================================================================

    @Query("SELECT * FROM human_feedback WHERE stateActionKey = :key LIMIT 1")
    suspend fun getHumanFeedback(key: String): HumanFeedbackEntity?

    @Query("SELECT feedbackValue FROM human_feedback WHERE stateActionKey = :key LIMIT 1")
    suspend fun getHumanFeedbackValue(key: String): Int?

    @Query("SELECT * FROM human_feedback")
    suspend fun getAllHumanFeedback(): List<HumanFeedbackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHumanFeedback(entry: HumanFeedbackEntity)

    @Query("DELETE FROM human_feedback")
    suspend fun deleteAllHumanFeedback()

    // =====================================================================
    // Dangerous Patterns Operations
    // =====================================================================

    @Query("SELECT * FROM dangerous_patterns WHERE patternKey = :key LIMIT 1")
    suspend fun getDangerousPattern(key: String): DangerousPatternEntity?

    @Query("SELECT COUNT(*) > 0 FROM dangerous_patterns WHERE patternKey = :key")
    suspend fun isDangerousPattern(key: String): Boolean

    @Query("SELECT * FROM dangerous_patterns")
    suspend fun getAllDangerousPatterns(): List<DangerousPatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDangerousPattern(entry: DangerousPatternEntity)

    @Transaction
    suspend fun recordDangerousPattern(patternKey: String, packageName: String? = null) {
        val existing = getDangerousPattern(patternKey)
        val updated = existing?.copy(
            occurrenceCount = existing.occurrenceCount + 1,
            lastSeen = System.currentTimeMillis()
        ) ?: DangerousPatternEntity(
            patternKey = patternKey,
            occurrenceCount = 1,
            packageName = packageName
        )
        upsertDangerousPattern(updated)
    }

    @Query("DELETE FROM dangerous_patterns")
    suspend fun deleteAllDangerousPatterns()

    // =====================================================================
    // Bulk Operations
    // =====================================================================

    /**
     * Export all Q-learning data as maps (for MQTT/backup).
     */
    @Transaction
    suspend fun exportAllData(): QTableExport {
        return QTableExport(
            qTable = getAllQValues().associate { it.stateActionKey to it.qValue },
            visitCounts = getAllQValues().associate { it.stateActionKey to it.visitCount },
            screenVisits = getAllScreenVisits().associate { it.screenId to it.visitCount },
            humanFeedback = getAllHumanFeedback().associate { it.stateActionKey to it.feedbackValue },
            dangerousPatterns = getAllDangerousPatterns().map { it.patternKey }.toSet()
        )
    }

    /**
     * Clear all Q-learning data (for reset/testing).
     */
    @Transaction
    suspend fun clearAllData() {
        deleteAllQValues()
        deleteAllScreenVisits()
        deleteAllHumanFeedback()
        deleteAllDangerousPatterns()
    }
}

/**
 * Data class for exporting all Q-learning data.
 */
data class QTableExport(
    val qTable: Map<String, Float>,
    val visitCounts: Map<String, Int>,
    val screenVisits: Map<String, Int>,
    val humanFeedback: Map<String, Int>,
    val dangerousPatterns: Set<String>
)

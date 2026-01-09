package com.visualmapper.companion.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for ExecutionResultEntity.
 *
 * Phase 1 Refactor: Thread-safe database operations for offline
 * execution result queueing. All operations use coroutines.
 *
 * Usage Pattern:
 * 1. On flow execution complete: insert(result)
 * 2. On MQTT connect: getPendingResults() -> publish each -> delete on success
 * 3. On publish failure: update retry count with withRetry()
 */
@Dao
interface ExecutionResultDao {

    /**
     * Insert a new execution result.
     * Uses REPLACE strategy in case of duplicate IDs (shouldn't happen normally).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ExecutionResultEntity)

    /**
     * Insert multiple execution results in a single transaction.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<ExecutionResultEntity>)

    /**
     * Get pending results ready for MQTT publishing.
     * Excludes results that were recently retried (exponential backoff).
     *
     * @param limit Maximum number of results to fetch
     */
    @Query("""
        SELECT * FROM execution_results
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    suspend fun getPendingResults(limit: Int = 20): List<ExecutionResultEntity>

    /**
     * Get results that are eligible for retry (not recently attempted).
     *
     * @param cutoffTime Only get results with lastRetryTime before this
     * @param limit Maximum number of results to fetch
     */
    @Query("""
        SELECT * FROM execution_results
        WHERE lastRetryTime < :cutoffTime OR retryCount = 0
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    suspend fun getRetryableResults(cutoffTime: Long, limit: Int = 20): List<ExecutionResultEntity>

    /**
     * Get results for a specific flow.
     */
    @Query("SELECT * FROM execution_results WHERE flowId = :flowId ORDER BY timestamp DESC")
    suspend fun getResultsForFlow(flowId: String): List<ExecutionResultEntity>

    /**
     * Update a result (e.g., increment retry count).
     */
    @Update
    suspend fun update(result: ExecutionResultEntity)

    /**
     * Delete a successfully delivered result.
     */
    @Delete
    suspend fun delete(result: ExecutionResultEntity)

    /**
     * Delete multiple results (batch cleanup after successful delivery).
     */
    @Delete
    suspend fun deleteAll(results: List<ExecutionResultEntity>)

    /**
     * Delete a result by ID.
     */
    @Query("DELETE FROM execution_results WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Get total count of pending results.
     */
    @Query("SELECT COUNT(*) FROM execution_results")
    suspend fun getCount(): Int

    /**
     * Get count of failed results (exceeded retry threshold).
     */
    @Query("SELECT COUNT(*) FROM execution_results WHERE retryCount >= :maxRetries")
    suspend fun getFailedCount(maxRetries: Int = 10): Int

    /**
     * Delete old failed results that exceeded max retries.
     * Prevents infinite growth of the table.
     *
     * @param maxRetries Maximum retry attempts before deletion
     * @return Number of deleted rows
     */
    @Query("DELETE FROM execution_results WHERE retryCount >= :maxRetries")
    suspend fun deleteExpiredResults(maxRetries: Int = 10): Int

    /**
     * Delete results older than a certain age.
     * Safety cleanup for very old stuck results.
     *
     * @param cutoffTime Delete results with timestamp before this
     * @return Number of deleted rows
     */
    @Query("DELETE FROM execution_results WHERE timestamp < :cutoffTime")
    suspend fun deleteOldResults(cutoffTime: Long): Int

    /**
     * Clear all pending results.
     * Use with caution - for testing/reset purposes only.
     */
    @Query("DELETE FROM execution_results")
    suspend fun clearAll()
}

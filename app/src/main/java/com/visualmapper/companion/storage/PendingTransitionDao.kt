package com.visualmapper.companion.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for PendingTransition.
 *
 * Phase 1 Refactor: Thread-safe database operations using Room's
 * suspend functions with coroutines. All operations run on the
 * caller's coroutine dispatcher (typically Dispatchers.IO).
 */
@Dao
interface PendingTransitionDao {

    /**
     * Insert a new pending transition.
     * Returns the auto-generated ID.
     */
    @Insert
    suspend fun insert(transition: PendingTransition): Long

    /**
     * Get the oldest pending transitions for batch processing.
     * Limited to prevent memory issues with large backlogs.
     *
     * @param limit Maximum number of transitions to fetch
     */
    @Query("SELECT * FROM pending_transitions ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getOldestTransitions(limit: Int = 20): List<PendingTransition>

    /**
     * Get transitions that haven't been retried recently.
     * Used for exponential backoff - skip recently failed items.
     *
     * @param minTimeSinceRetry Minimum milliseconds since last retry
     * @param limit Maximum number of transitions to fetch
     */
    @Query("""
        SELECT * FROM pending_transitions
        WHERE lastRetryTime < :cutoffTime OR retryCount = 0
        ORDER BY timestamp ASC
        LIMIT :limit
    """)
    suspend fun getRetryableTransitions(cutoffTime: Long, limit: Int = 20): List<PendingTransition>

    /**
     * Update a transition (e.g., increment retry count).
     */
    @Update
    suspend fun update(transition: PendingTransition)

    /**
     * Delete a successfully delivered transition.
     */
    @Delete
    suspend fun delete(transition: PendingTransition)

    /**
     * Delete multiple transitions (batch cleanup after successful delivery).
     */
    @Delete
    suspend fun deleteAll(transitions: List<PendingTransition>)

    /**
     * Get total count of pending transitions.
     * Useful for monitoring/debugging.
     */
    @Query("SELECT COUNT(*) FROM pending_transitions")
    suspend fun getCount(): Int

    /**
     * Delete old failed transitions that exceeded max retries.
     * Prevents infinite growth of the table.
     *
     * @param maxRetries Maximum retry attempts before deletion
     */
    @Query("DELETE FROM pending_transitions WHERE retryCount >= :maxRetries")
    suspend fun deleteExpiredTransitions(maxRetries: Int = 10)

    /**
     * Clear all pending transitions.
     * Use with caution - for testing/reset purposes only.
     */
    @Query("DELETE FROM pending_transitions")
    suspend fun clearAll()
}

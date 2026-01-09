package com.visualmapper.companion.storage

import android.content.Context
import android.util.Log
import androidx.room.*
import kotlinx.coroutines.*

/**
 * Telemetry Queue Entity for Room Database.
 *
 * Phase 3 ML Stability: Stores exploration telemetry when MQTT is offline.
 * Telemetry is flushed when MQTT reconnects, ensuring no data loss.
 *
 * Types of telemetry:
 * - exploration_log: Q-learning experience data
 * - navigation: Screen transition data
 * - screen_capture: Element discovery data
 */
@Entity(
    tableName = "telemetry_queue",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["type"])
    ]
)
data class TelemetryQueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Type of telemetry (exploration_log, navigation, screen_capture)
     */
    val type: String,

    /**
     * JSON payload to be sent to MQTT
     */
    val payload: String,

    /**
     * MQTT topic to publish to
     */
    val topic: String,

    /**
     * Timestamp when entry was created
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Number of retry attempts
     */
    val retryCount: Int = 0,

    /**
     * Priority (higher = flush first)
     */
    val priority: Int = 0
)

/**
 * DAO for Telemetry Queue operations.
 *
 * Phase 3 ML Stability: Provides CRUD operations for offline telemetry queue.
 */
@Dao
interface TelemetryQueueDao {

    @Query("SELECT * FROM telemetry_queue ORDER BY priority DESC, createdAt ASC LIMIT :limit")
    suspend fun getPending(limit: Int = 100): List<TelemetryQueueEntity>

    @Query("SELECT COUNT(*) FROM telemetry_queue")
    suspend fun getQueueSize(): Int

    @Insert
    suspend fun insert(entry: TelemetryQueueEntity): Long

    @Insert
    suspend fun insertAll(entries: List<TelemetryQueueEntity>)

    @Delete
    suspend fun delete(entry: TelemetryQueueEntity)

    @Query("DELETE FROM telemetry_queue WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM telemetry_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Increment retry count for an entry.
     */
    @Query("UPDATE telemetry_queue SET retryCount = retryCount + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Long)

    /**
     * Delete entries that have exceeded max retries.
     */
    @Query("DELETE FROM telemetry_queue WHERE retryCount >= :maxRetries")
    suspend fun pruneFailedEntries(maxRetries: Int = 3)

    /**
     * Delete oldest entries when queue exceeds max size.
     */
    @Query("""
        DELETE FROM telemetry_queue WHERE id IN (
            SELECT id FROM telemetry_queue
            ORDER BY priority ASC, createdAt ASC
            LIMIT :count
        )
    """)
    suspend fun pruneOldest(count: Int)

    /**
     * Clear all telemetry queue (for reset/testing).
     */
    @Query("DELETE FROM telemetry_queue")
    suspend fun clearAll()
}

/**
 * Offline Telemetry Queue Manager.
 *
 * Phase 3 ML Stability: Manages queuing of telemetry data when MQTT is offline.
 * - Queues exploration logs, navigation data when MQTT is disconnected
 * - Automatically flushes when MQTT reconnects
 * - Uses Room for persistence across app restarts
 * - Implements size limits to prevent unbounded growth
 *
 * Thread Safety:
 * - All operations are suspend functions (coroutine-safe)
 * - Room handles SQLite thread safety internally
 */
class OfflineTelemetryQueue private constructor(private val context: Context) {

    companion object {
        private const val TAG = "OfflineTelemetryQueue"
        private const val MAX_QUEUE_SIZE = 500
        private const val MAX_RETRIES = 3

        @Volatile
        private var INSTANCE: OfflineTelemetryQueue? = null

        fun getInstance(context: Context): OfflineTelemetryQueue {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OfflineTelemetryQueue(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val database = AppDatabase.getDatabase(context)
    private val dao = database.telemetryQueueDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Queue telemetry for later delivery.
     *
     * @param type Telemetry type (exploration_log, navigation)
     * @param payload JSON payload
     * @param topic MQTT topic to publish to
     * @param priority Higher priority entries are flushed first
     */
    suspend fun queue(type: String, payload: String, topic: String, priority: Int = 0) {
        try {
            // Check queue size and prune if needed
            val currentSize = dao.getQueueSize()
            if (currentSize >= MAX_QUEUE_SIZE) {
                val toRemove = (currentSize - MAX_QUEUE_SIZE) + (MAX_QUEUE_SIZE / 10)
                dao.pruneOldest(toRemove)
                Log.w(TAG, "Queue full, pruned $toRemove oldest entries")
            }

            // Insert new entry
            val entry = TelemetryQueueEntity(
                type = type,
                payload = payload,
                topic = topic,
                priority = priority
            )
            dao.insert(entry)
            Log.d(TAG, "Queued $type telemetry (${payload.length} bytes) for topic $topic")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue telemetry", e)
        }
    }

    /**
     * Queue exploration log entries.
     * Higher priority than navigation data.
     */
    suspend fun queueExplorationLog(logsJson: String, topic: String) {
        queue("exploration_log", logsJson, topic, priority = 10)
    }

    /**
     * Queue navigation transition.
     */
    suspend fun queueNavigation(payload: String, topic: String) {
        queue("navigation", payload, topic, priority = 5)
    }

    /**
     * Flush all pending telemetry to MQTT.
     *
     * @param publishFn Async function that publishes to MQTT, returns true on success
     * @return Number of entries successfully flushed
     */
    suspend fun flush(publishFn: suspend (topic: String, payload: String) -> Boolean): Int {
        var flushedCount = 0

        try {
            // Prune failed entries first
            dao.pruneFailedEntries(MAX_RETRIES)

            // Get pending entries (prioritized)
            val pending = dao.getPending()
            if (pending.isEmpty()) {
                return 0
            }

            Log.i(TAG, "Flushing ${pending.size} pending telemetry entries")

            val successIds = mutableListOf<Long>()

            for (entry in pending) {
                try {
                    val success = publishFn(entry.topic, entry.payload)

                    if (success) {
                        successIds.add(entry.id)
                        flushedCount++
                    } else {
                        dao.incrementRetry(entry.id)
                        Log.w(TAG, "Failed to flush entry ${entry.id}, retry ${entry.retryCount + 1}")
                    }
                } catch (e: Exception) {
                    dao.incrementRetry(entry.id)
                    Log.e(TAG, "Exception flushing entry ${entry.id}", e)
                }
            }

            // Delete successful entries
            if (successIds.isNotEmpty()) {
                dao.deleteByIds(successIds)
            }

            Log.i(TAG, "Flushed $flushedCount/${pending.size} telemetry entries")

        } catch (e: Exception) {
            Log.e(TAG, "Error during flush", e)
        }

        return flushedCount
    }

    /**
     * Get current queue size.
     */
    suspend fun getQueueSize(): Int = dao.getQueueSize()

    /**
     * Clear all queued telemetry.
     */
    suspend fun clear() {
        dao.clearAll()
        Log.i(TAG, "Telemetry queue cleared")
    }

    /**
     * Cleanup resources.
     */
    fun destroy() {
        scope.cancel()
    }
}

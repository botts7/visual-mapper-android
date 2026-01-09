package com.visualmapper.companion.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for pending flow execution results.
 *
 * Phase 1 Refactor: Offline resilience for flow execution.
 * Execution results are stored here when MQTT is unavailable,
 * then flushed when connection is restored.
 *
 * This ensures NO execution results are ever lost due to network issues.
 *
 * @property id Unique identifier (UUID-based for cross-device consistency)
 * @property flowId The ID of the flow that was executed
 * @property deviceId The device the flow was executed on
 * @property success Whether the flow execution succeeded
 * @property executedSteps Number of steps successfully executed
 * @property failedStep Index of failed step (null if success)
 * @property errorMessage Error message if failed (null if success)
 * @property executionTimeMs Duration of execution in milliseconds
 * @property payload Full JSON payload of the execution result
 * @property timestamp When the execution completed
 * @property retryCount Number of failed MQTT delivery attempts
 * @property lastRetryTime When the last retry was attempted (for backoff)
 */
@Entity(tableName = "execution_results")
data class ExecutionResultEntity(
    @PrimaryKey
    val id: String,
    val flowId: String,
    val deviceId: String,
    val success: Boolean,
    val executedSteps: Int = 0,
    val failedStep: Int? = null,
    val errorMessage: String? = null,
    val executionTimeMs: Long = 0,
    val payload: String,  // Full JSON result for MQTT publishing
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastRetryTime: Long = 0
) {
    /**
     * Create a copy with incremented retry count and updated retry time.
     */
    fun withRetry(): ExecutionResultEntity = copy(
        retryCount = retryCount + 1,
        lastRetryTime = System.currentTimeMillis()
    )

    /**
     * Check if this result is eligible for retry based on exponential backoff.
     * Base delay: 1 second, max delay: 5 minutes.
     */
    fun isRetryable(maxRetries: Int = 10): Boolean {
        if (retryCount >= maxRetries) return false

        val backoffMs = minOf(
            1000L * (1L shl retryCount.coerceAtMost(10)),  // Exponential: 1s, 2s, 4s, ...
            5 * 60 * 1000L  // Cap at 5 minutes
        )

        return System.currentTimeMillis() - lastRetryTime >= backoffMs
    }
}

package com.visualmapper.companion.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for pending navigation transitions.
 *
 * Phase 1 Refactor: Data persistence for guaranteed delivery.
 * Transitions are stored here when MQTT is unavailable, then
 * flushed when connection is restored.
 *
 * @property id Auto-generated primary key
 * @property payload JSON payload of the transition data
 * @property timestamp When the transition was captured
 * @property retryCount Number of failed delivery attempts
 * @property lastRetryTime When the last retry was attempted (for backoff)
 */
@Entity(tableName = "pending_transitions")
data class PendingTransition(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val payload: String,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastRetryTime: Long = 0
)

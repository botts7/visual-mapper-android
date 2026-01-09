package com.visualmapper.companion.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Q-Table Entity for Room Database.
 *
 * Phase 3 ML Stability: Migrates Q-table from SharedPreferences to Room
 * for atomic writes and better data integrity.
 *
 * Each entry represents a state-action pair with its Q-value.
 * The state-action key format: "{screenId}_{actionHash}"
 */
@Entity(
    tableName = "q_table",
    indices = [
        Index(value = ["stateActionKey"], unique = true),
        Index(value = ["lastUpdated"]),
        Index(value = ["visitCount"])
    ]
)
data class QTableEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Unique key combining state and action (e.g., "screen_hash_action_hash")
     */
    val stateActionKey: String,

    /**
     * Q-value for this state-action pair
     */
    val qValue: Float,

    /**
     * Number of times this state-action pair has been visited
     */
    val visitCount: Int = 1,

    /**
     * Timestamp of last update (for LRU pruning)
     */
    val lastUpdated: Long = System.currentTimeMillis(),

    /**
     * Package name for app-specific filtering
     */
    val packageName: String? = null
)

/**
 * Screen Visit Count Entity.
 *
 * Tracks how many times each screen has been visited for novelty calculation.
 */
@Entity(
    tableName = "screen_visits",
    indices = [
        Index(value = ["screenId"], unique = true)
    ]
)
data class ScreenVisitEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Screen identifier (hash of screen state)
     */
    val screenId: String,

    /**
     * Number of times this screen has been visited
     */
    val visitCount: Int = 1,

    /**
     * Package name for app-specific filtering
     */
    val packageName: String? = null,

    /**
     * Timestamp of first visit
     */
    val firstVisited: Long = System.currentTimeMillis(),

    /**
     * Timestamp of last visit
     */
    val lastVisited: Long = System.currentTimeMillis()
)

/**
 * Human Feedback Entity.
 *
 * Stores human feedback signals for Human-in-the-Loop learning.
 * +1 for imitation (user showed this is correct)
 * -1 for veto (user indicated this was wrong)
 */
@Entity(
    tableName = "human_feedback",
    indices = [
        Index(value = ["stateActionKey"], unique = true)
    ]
)
data class HumanFeedbackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * State-action key matching Q-table entries
     */
    val stateActionKey: String,

    /**
     * Feedback value: +1 (imitation) or -1 (veto)
     */
    val feedbackValue: Int,

    /**
     * Timestamp when feedback was given
     */
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * Package name for app-specific filtering
     */
    val packageName: String? = null
)

/**
 * Dangerous Pattern Entity.
 *
 * Tracks patterns that lead to crashes or app closures.
 */
@Entity(
    tableName = "dangerous_patterns",
    indices = [
        Index(value = ["patternKey"], unique = true)
    ]
)
data class DangerousPatternEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Pattern identifier (element or action hash)
     */
    val patternKey: String,

    /**
     * Number of times this pattern caused issues
     */
    val occurrenceCount: Int = 1,

    /**
     * Package name for app-specific filtering
     */
    val packageName: String? = null,

    /**
     * Timestamp of first occurrence
     */
    val firstSeen: Long = System.currentTimeMillis(),

    /**
     * Timestamp of last occurrence
     */
    val lastSeen: Long = System.currentTimeMillis()
)

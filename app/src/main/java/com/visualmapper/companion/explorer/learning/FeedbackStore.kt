package com.visualmapper.companion.explorer.learning

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores user feedback when they edit sensors/actions.
 * Used to learn from corrections and improve future suggestions.
 */
class FeedbackStore(context: Context) {

    companion object {
        private const val TAG = "FeedbackStore"
        private const val PREFS_NAME = "user_feedback"
        private const val KEY_FEEDBACK_LOG = "feedback_log"
        private const val MAX_ENTRIES = 1000

        @Volatile
        private var instance: FeedbackStore? = null

        fun getInstance(context: Context): FeedbackStore {
            return instance ?: synchronized(this) {
                instance ?: FeedbackStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // In-memory cache for faster access
    private var cachedFeedback: MutableList<UserFeedback>? = null

    /**
     * Record feedback when user edits a sensor or action
     */
    fun saveFeedback(feedback: UserFeedback) {
        try {
            val existing = getAllFeedback().toMutableList()
            existing.add(feedback)

            // Keep only the most recent entries
            val trimmed = if (existing.size > MAX_ENTRIES) {
                existing.takeLast(MAX_ENTRIES)
            } else {
                existing
            }

            // Update cache and storage
            cachedFeedback = trimmed.toMutableList()
            prefs.edit()
                .putString(KEY_FEEDBACK_LOG, json.encodeToString(trimmed))
                .apply()

            Log.d(TAG, "Saved feedback: ${feedback.feedbackType} for ${feedback.elementId}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving feedback", e)
        }
    }

    /**
     * Record sensor name change
     */
    fun recordSensorNameChange(
        appPackage: String,
        screenId: String,
        elementId: String,
        originalName: String,
        newName: String,
        sampleValue: String?
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.SENSOR_NAME_CHANGED,
            originalValue = originalName,
            userValue = newName,
            context = buildMap {
                sampleValue?.let { put("sampleValue", it) }
            }
        ))
    }

    /**
     * Record sensor rejection (user deselected it)
     */
    fun recordSensorRejected(
        appPackage: String,
        screenId: String,
        elementId: String,
        sensorName: String,
        reason: String? = null
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.SENSOR_REJECTED,
            originalValue = sensorName,
            userValue = "",
            context = buildMap {
                reason?.let { put("reason", it) }
            }
        ))
    }

    /**
     * Record device class change
     */
    fun recordDeviceClassChange(
        appPackage: String,
        screenId: String,
        elementId: String,
        originalClass: String?,
        newClass: String,
        sampleValue: String?
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.DEVICE_CLASS_CHANGED,
            originalValue = originalClass ?: "",
            userValue = newClass,
            context = buildMap {
                sampleValue?.let { put("sampleValue", it) }
            }
        ))
    }

    /**
     * Record extraction method change
     */
    fun recordExtractionChange(
        appPackage: String,
        screenId: String,
        elementId: String,
        originalMethod: String,
        newMethod: String,
        regexPattern: String?
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.EXTRACTION_CHANGED,
            originalValue = originalMethod,
            userValue = newMethod,
            context = buildMap {
                regexPattern?.let { put("regexPattern", it) }
            }
        ))
    }

    /**
     * Record unit change
     */
    fun recordUnitChange(
        appPackage: String,
        screenId: String,
        elementId: String,
        originalUnit: String?,
        newUnit: String
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.UNIT_CHANGED,
            originalValue = originalUnit ?: "",
            userValue = newUnit
        ))
    }

    /**
     * Record action name change
     */
    fun recordActionNameChange(
        appPackage: String,
        screenId: String,
        elementId: String,
        originalName: String,
        newName: String,
        buttonText: String?
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.ACTION_NAME_CHANGED,
            originalValue = originalName,
            userValue = newName,
            context = buildMap {
                buttonText?.let { put("buttonText", it) }
            }
        ))
    }

    /**
     * Record action rejection (user deselected it)
     */
    fun recordActionRejected(
        appPackage: String,
        screenId: String,
        elementId: String,
        actionName: String,
        buttonText: String?,
        reason: String? = null
    ) {
        saveFeedback(UserFeedback(
            timestamp = System.currentTimeMillis(),
            appPackage = appPackage,
            screenId = screenId,
            elementId = elementId,
            feedbackType = FeedbackType.ACTION_REJECTED,
            originalValue = actionName,
            userValue = "",
            context = buildMap {
                buttonText?.let { put("buttonText", it) }
                reason?.let { put("reason", it) }
            }
        ))
    }

    /**
     * Suggest action name based on history
     */
    fun suggestActionNameFromHistory(buttonText: String, appPackage: String? = null): String? {
        val feedback = getAllFeedback()
            .filter { it.feedbackType == FeedbackType.ACTION_NAME_CHANGED }
            .filter { appPackage == null || it.appPackage == appPackage }

        // Look for matching button text
        val match = feedback.find {
            it.context["buttonText"]?.equals(buttonText, ignoreCase = true) == true
        }

        return match?.userValue
    }

    /**
     * Check if a button was previously rejected
     */
    fun wasActionRejected(buttonText: String, appPackage: String): Boolean {
        return getAllFeedback()
            .filter { it.feedbackType == FeedbackType.ACTION_REJECTED }
            .filter { it.appPackage == appPackage }
            .any { it.context["buttonText"]?.equals(buttonText, ignoreCase = true) == true }
    }

    /**
     * Get all stored feedback
     */
    fun getAllFeedback(): List<UserFeedback> {
        cachedFeedback?.let { return it }

        return try {
            val jsonStr = prefs.getString(KEY_FEEDBACK_LOG, null)
            if (jsonStr != null) {
                val list = json.decodeFromString<List<UserFeedback>>(jsonStr)
                cachedFeedback = list.toMutableList()
                list
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading feedback", e)
            emptyList()
        }
    }

    /**
     * Get feedback for a specific app
     */
    fun getFeedbackForApp(appPackage: String): List<UserFeedback> {
        return getAllFeedback().filter { it.appPackage == appPackage }
    }

    /**
     * Get feedback for a specific element
     */
    fun getFeedbackForElement(appPackage: String, elementId: String): List<UserFeedback> {
        return getAllFeedback().filter {
            it.appPackage == appPackage && it.elementId == elementId
        }
    }

    /**
     * Find similar patterns to suggest names based on past feedback
     */
    fun suggestNameFromHistory(sampleValue: String, appPackage: String? = null): String? {
        val feedback = getAllFeedback()
            .filter { it.feedbackType == FeedbackType.SENSOR_NAME_CHANGED }
            .filter { appPackage == null || it.appPackage == appPackage }

        // Look for exact sample value matches first
        val exactMatch = feedback.find {
            it.context["sampleValue"] == sampleValue
        }
        if (exactMatch != null) {
            return exactMatch.userValue
        }

        // Look for similar patterns
        val pattern = extractValuePattern(sampleValue)
        val similarMatch = feedback.find {
            it.context["sampleValue"]?.let { sv -> extractValuePattern(sv) == pattern } == true
        }

        return similarMatch?.userValue
    }

    /**
     * Suggest device class based on past feedback
     */
    fun suggestDeviceClassFromHistory(sampleValue: String, appPackage: String? = null): String? {
        val feedback = getAllFeedback()
            .filter { it.feedbackType == FeedbackType.DEVICE_CLASS_CHANGED }
            .filter { appPackage == null || it.appPackage == appPackage }

        val pattern = extractValuePattern(sampleValue)
        val match = feedback.find {
            it.context["sampleValue"]?.let { sv -> extractValuePattern(sv) == pattern } == true
        }

        return match?.userValue
    }

    /**
     * Check if a sensor pattern was previously rejected
     */
    fun wasPatternRejected(sampleValue: String, appPackage: String): Boolean {
        val pattern = extractValuePattern(sampleValue)
        return getAllFeedback()
            .filter { it.feedbackType == FeedbackType.SENSOR_REJECTED }
            .filter { it.appPackage == appPackage }
            .any {
                it.context["sampleValue"]?.let { sv -> extractValuePattern(sv) == pattern } == true
            }
    }

    /**
     * Get statistics for learning insights
     */
    fun getStats(): FeedbackStats {
        val all = getAllFeedback()
        return FeedbackStats(
            totalFeedback = all.size,
            nameChanges = all.count { it.feedbackType == FeedbackType.SENSOR_NAME_CHANGED },
            rejections = all.count { it.feedbackType == FeedbackType.SENSOR_REJECTED },
            deviceClassChanges = all.count { it.feedbackType == FeedbackType.DEVICE_CLASS_CHANGED },
            extractionChanges = all.count { it.feedbackType == FeedbackType.EXTRACTION_CHANGED },
            unitChanges = all.count { it.feedbackType == FeedbackType.UNIT_CHANGED },
            uniqueApps = all.map { it.appPackage }.distinct().size
        )
    }

    /**
     * Clear all feedback (for testing or privacy)
     */
    fun clearAll() {
        cachedFeedback = mutableListOf()
        prefs.edit().remove(KEY_FEEDBACK_LOG).apply()
        Log.d(TAG, "Cleared all feedback")
    }

    // ==================== Manual Correction Support ====================

    /**
     * Record generic feedback (for manual correction mode)
     */
    fun recordFeedback(
        packageName: String,
        screenId: String,
        elementId: String,
        feedbackType: String,
        positive: Boolean
    ) {
        val key = "correction_${packageName}_${screenId}_${elementId}_$feedbackType"
        prefs.edit()
            .putBoolean(key, positive)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
        Log.d(TAG, "Recorded correction feedback: $elementId -> $feedbackType = $positive")
    }

    /**
     * Check if element has negative feedback of a specific type
     */
    fun hasNegativeFeedback(
        packageName: String,
        screenId: String,
        elementId: String,
        feedbackType: String
    ): Boolean {
        val key = "correction_${packageName}_${screenId}_${elementId}_$feedbackType"
        // Return true if key exists and is false (negative feedback)
        return prefs.contains(key) && !prefs.getBoolean(key, true)
    }

    /**
     * Get priority boost for an element based on user corrections
     */
    fun getPriorityBoost(
        packageName: String,
        screenId: String,
        elementId: String
    ): Int {
        // Check for priority feedback keys
        val keys = prefs.all.keys.filter {
            it.startsWith("correction_${packageName}_${screenId}_${elementId}_priority_")
        }

        var maxPriority = 0
        for (key in keys) {
            if (prefs.getBoolean(key, false)) {
                // Extract priority level from key (e.g., "priority_10")
                val level = key.substringAfterLast("_").toIntOrNull() ?: 0
                maxPriority = maxOf(maxPriority, level)
            }
        }

        return maxPriority
    }

    /**
     * Check if element should be skipped during exploration
     */
    fun shouldSkipElement(
        packageName: String,
        screenId: String,
        elementId: String
    ): Boolean {
        return hasNegativeFeedback(packageName, screenId, elementId, "ignore_permanent") ||
               hasNegativeFeedback(packageName, screenId, elementId, "ignore")
    }

    /**
     * Extract a normalized pattern from a value for matching
     */
    private fun extractValuePattern(value: String): String {
        // Replace numbers with # to find similar patterns
        // "94%" -> "#%"
        // "542 km" -> "# km"
        return value.replace(Regex("""\d+(?:[.,]\d+)?"""), "#")
    }
}

@Serializable
data class UserFeedback(
    val timestamp: Long,
    val appPackage: String,
    val screenId: String,
    val elementId: String,
    val feedbackType: FeedbackType,
    val originalValue: String,
    val userValue: String,
    val context: Map<String, String> = emptyMap()
)

@Serializable
enum class FeedbackType {
    SENSOR_NAME_CHANGED,
    SENSOR_REJECTED,
    EXTRACTION_CHANGED,
    DEVICE_CLASS_CHANGED,
    UNIT_CHANGED,
    ACTION_NAME_CHANGED,
    ACTION_REJECTED
}

data class FeedbackStats(
    val totalFeedback: Int,
    val nameChanges: Int,
    val rejections: Int,
    val deviceClassChanges: Int,
    val extractionChanges: Int,
    val unitChanges: Int,
    val uniqueApps: Int
)

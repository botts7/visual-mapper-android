package com.visualmapper.companion.explorer

import android.content.Context
import android.util.Log
import com.visualmapper.companion.explorer.learning.FeedbackStore
import com.visualmapper.companion.explorer.learning.LearningStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manual Correction Manager
 *
 * Allows users to teach the app by:
 * 1. Showing correct navigation paths (tap sequence to reach a screen)
 * 2. Identifying sensors that were missed or misclassified
 * 3. Marking elements as "ignore" or "always click"
 * 4. Defining custom action sequences
 *
 * All corrections are stored and used to improve future explorations.
 */
class ManualCorrectionManager(private val context: Context) {

    companion object {
        private const val TAG = "ManualCorrection"

        @Volatile
        private var instance: ManualCorrectionManager? = null

        fun getInstance(context: Context): ManualCorrectionManager {
            return instance ?: synchronized(this) {
                instance ?: ManualCorrectionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val feedbackStore = FeedbackStore.getInstance(context)
    private val learningStore = LearningStore.getInstance(context)

    // Current correction session state
    private val _correctionMode = MutableStateFlow(CorrectionMode.NONE)
    val correctionMode: StateFlow<CorrectionMode> = _correctionMode.asStateFlow()

    private val _recordedPath = MutableStateFlow<List<PathStep>>(emptyList())
    val recordedPath: StateFlow<List<PathStep>> = _recordedPath.asStateFlow()

    private val _pendingCorrections = MutableStateFlow<List<ElementCorrection>>(emptyList())
    val pendingCorrections: StateFlow<List<ElementCorrection>> = _pendingCorrections.asStateFlow()

    // Current package being corrected
    private var currentPackage: String? = null
    private var currentScreenId: String? = null

    /**
     * Start a manual correction session
     */
    fun startCorrectionSession(packageName: String, mode: CorrectionMode) {
        Log.i(TAG, "Starting correction session for $packageName in mode: $mode")
        currentPackage = packageName
        _correctionMode.value = mode
        _recordedPath.value = emptyList()
        _pendingCorrections.value = emptyList()
    }

    /**
     * End correction session and apply changes
     */
    fun endCorrectionSession(): CorrectionResult {
        val result = CorrectionResult(
            pathStepsRecorded = _recordedPath.value.size,
            correctionsApplied = _pendingCorrections.value.size,
            packageName = currentPackage ?: ""
        )

        // Apply all pending corrections
        applyPendingCorrections()

        // Reset state
        _correctionMode.value = CorrectionMode.NONE
        _recordedPath.value = emptyList()
        _pendingCorrections.value = emptyList()
        currentPackage = null
        currentScreenId = null

        Log.i(TAG, "Correction session ended: $result")
        return result
    }

    /**
     * Record a navigation step (user tapped an element)
     */
    fun recordNavigationStep(
        screenId: String,
        elementId: String,
        elementText: String?,
        elementResourceId: String?,
        resultScreenId: String?
    ) {
        if (_correctionMode.value != CorrectionMode.NAVIGATION_PATH) {
            Log.w(TAG, "Not in navigation path mode, ignoring step")
            return
        }

        val step = PathStep(
            screenId = screenId,
            elementId = elementId,
            elementText = elementText,
            elementResourceId = elementResourceId,
            resultScreenId = resultScreenId,
            timestamp = System.currentTimeMillis()
        )

        _recordedPath.value = _recordedPath.value + step
        currentScreenId = resultScreenId

        Log.i(TAG, "Recorded navigation step: ${step.elementText ?: step.elementId} -> ${step.resultScreenId}")

        // Immediately apply positive Q-value for this transition
        currentPackage?.let { pkg ->
            learningStore.recordPositiveFeedback(
                packageName = pkg,
                screenId = screenId,
                elementId = elementId,
                actionType = "navigation",
                reward = 1.5f  // Higher reward for user-taught paths
            )
        }
    }

    /**
     * Record a sensor correction (user identified an element as a sensor)
     */
    fun recordSensorCorrection(
        screenId: String,
        elementId: String,
        elementText: String?,
        sensorType: String,
        sensorName: String?
    ) {
        val correction = ElementCorrection(
            type = CorrectionType.SENSOR_IDENTIFICATION,
            screenId = screenId,
            elementId = elementId,
            elementText = elementText,
            sensorType = sensorType,
            sensorName = sensorName ?: elementText,
            timestamp = System.currentTimeMillis()
        )

        _pendingCorrections.value = _pendingCorrections.value + correction

        Log.i(TAG, "Recorded sensor correction: $elementId -> $sensorType")
    }

    /**
     * Mark an element to always ignore during exploration
     */
    fun markElementAsIgnore(
        screenId: String,
        elementId: String,
        elementText: String?,
        reason: String?
    ) {
        val correction = ElementCorrection(
            type = CorrectionType.IGNORE_ELEMENT,
            screenId = screenId,
            elementId = elementId,
            elementText = elementText,
            ignoreReason = reason,
            timestamp = System.currentTimeMillis()
        )

        _pendingCorrections.value = _pendingCorrections.value + correction

        // Apply negative feedback immediately
        currentPackage?.let { pkg ->
            feedbackStore.recordFeedback(
                packageName = pkg,
                screenId = screenId,
                elementId = elementId,
                feedbackType = "ignore",
                positive = false
            )
        }

        Log.i(TAG, "Marked element as ignore: $elementId - $reason")
    }

    /**
     * Mark an element as high priority (always explore)
     */
    fun markElementAsPriority(
        screenId: String,
        elementId: String,
        elementText: String?,
        priorityLevel: Int = 10
    ) {
        val correction = ElementCorrection(
            type = CorrectionType.PRIORITY_ELEMENT,
            screenId = screenId,
            elementId = elementId,
            elementText = elementText,
            priorityLevel = priorityLevel,
            timestamp = System.currentTimeMillis()
        )

        _pendingCorrections.value = _pendingCorrections.value + correction

        // Apply positive feedback
        currentPackage?.let { pkg ->
            feedbackStore.recordFeedback(
                packageName = pkg,
                screenId = screenId,
                elementId = elementId,
                feedbackType = "priority",
                positive = true
            )
        }

        Log.i(TAG, "Marked element as priority: $elementId - level $priorityLevel")
    }

    /**
     * Record a custom action sequence for an element
     */
    fun recordActionSequence(
        screenId: String,
        elementId: String,
        actions: List<ActionStep>
    ) {
        val correction = ElementCorrection(
            type = CorrectionType.CUSTOM_ACTION,
            screenId = screenId,
            elementId = elementId,
            actionSequence = actions,
            timestamp = System.currentTimeMillis()
        )

        _pendingCorrections.value = _pendingCorrections.value + correction

        Log.i(TAG, "Recorded action sequence for $elementId: ${actions.size} steps")
    }

    /**
     * Correct a sensor type classification
     */
    fun correctSensorType(
        screenId: String,
        elementId: String,
        originalType: String?,
        correctType: String
    ) {
        val correction = ElementCorrection(
            type = CorrectionType.SENSOR_TYPE_CORRECTION,
            screenId = screenId,
            elementId = elementId,
            originalSensorType = originalType,
            sensorType = correctType,
            timestamp = System.currentTimeMillis()
        )

        _pendingCorrections.value = _pendingCorrections.value + correction

        Log.i(TAG, "Corrected sensor type: $elementId from $originalType to $correctType")
    }

    /**
     * Get suggested sensor type based on user's previous corrections
     */
    fun getSuggestedSensorType(elementText: String?, elementResourceId: String?): String? {
        // Check learning store for similar elements
        val pattern = elementText ?: elementResourceId ?: return null
        return learningStore.getSensorTypeForPattern(currentPackage ?: "", pattern)
    }

    /**
     * Apply all pending corrections to the learning stores
     */
    private fun applyPendingCorrections() {
        val pkg = currentPackage ?: return

        for (correction in _pendingCorrections.value) {
            when (correction.type) {
                CorrectionType.SENSOR_IDENTIFICATION -> {
                    // Store sensor pattern for future recognition
                    learningStore.recordSensorPattern(
                        packageName = pkg,
                        pattern = correction.elementText ?: correction.elementId,
                        sensorType = correction.sensorType ?: "unknown"
                    )
                }
                CorrectionType.SENSOR_TYPE_CORRECTION -> {
                    // Update sensor pattern with correct type
                    learningStore.recordSensorPattern(
                        packageName = pkg,
                        pattern = correction.elementText ?: correction.elementId,
                        sensorType = correction.sensorType ?: "unknown"
                    )
                }
                CorrectionType.IGNORE_ELEMENT -> {
                    // Store in feedback as negative
                    feedbackStore.recordFeedback(
                        packageName = pkg,
                        screenId = correction.screenId,
                        elementId = correction.elementId,
                        feedbackType = "ignore_permanent",
                        positive = false
                    )
                }
                CorrectionType.PRIORITY_ELEMENT -> {
                    // Store in feedback as high priority
                    feedbackStore.recordFeedback(
                        packageName = pkg,
                        screenId = correction.screenId,
                        elementId = correction.elementId,
                        feedbackType = "priority_${correction.priorityLevel}",
                        positive = true
                    )
                }
                CorrectionType.CUSTOM_ACTION -> {
                    // Store action sequence for element
                    correction.actionSequence?.let { actions ->
                        learningStore.recordActionSequence(
                            packageName = pkg,
                            screenId = correction.screenId,
                            elementId = correction.elementId,
                            actions = actions.map { it.toJson() }
                        )
                    }
                }
            }
        }

        // Store the recorded path as a "golden path"
        if (_recordedPath.value.isNotEmpty()) {
            learningStore.recordGoldenPath(
                packageName = pkg,
                pathName = "user_path_${System.currentTimeMillis()}",
                steps = _recordedPath.value.map { it.toJson() }
            )
        }

        Log.i(TAG, "Applied ${_pendingCorrections.value.size} corrections and ${_recordedPath.value.size} path steps")
    }

    /**
     * Get learned golden paths for a package
     */
    fun getGoldenPaths(packageName: String): List<GoldenPath> {
        return learningStore.getGoldenPaths(packageName)
    }

    /**
     * Check if an element should be ignored based on corrections
     */
    fun shouldIgnoreElement(packageName: String, screenId: String, elementId: String): Boolean {
        return feedbackStore.hasNegativeFeedback(packageName, screenId, elementId, "ignore_permanent")
    }

    /**
     * Get priority boost for an element based on corrections
     */
    fun getElementPriorityBoost(packageName: String, screenId: String, elementId: String): Int {
        return feedbackStore.getPriorityBoost(packageName, screenId, elementId)
    }

    /**
     * Get custom action sequence for an element
     */
    fun getCustomActionSequence(packageName: String, screenId: String, elementId: String): List<ActionStep>? {
        return learningStore.getActionSequence(packageName, screenId, elementId)
    }

    /**
     * Cancel current correction session without saving
     */
    fun cancelCorrectionSession() {
        Log.i(TAG, "Correction session cancelled")
        _correctionMode.value = CorrectionMode.NONE
        _recordedPath.value = emptyList()
        _pendingCorrections.value = emptyList()
        currentPackage = null
        currentScreenId = null
    }
}

// === Data Classes ===

enum class CorrectionMode {
    NONE,               // Not in correction mode
    NAVIGATION_PATH,    // Recording navigation path
    SENSOR_MARKING,     // Marking sensors
    ELEMENT_TAGGING     // Tagging elements (ignore/priority)
}

enum class CorrectionType {
    SENSOR_IDENTIFICATION,
    SENSOR_TYPE_CORRECTION,
    IGNORE_ELEMENT,
    PRIORITY_ELEMENT,
    CUSTOM_ACTION
}

data class PathStep(
    val screenId: String,
    val elementId: String,
    val elementText: String?,
    val elementResourceId: String?,
    val resultScreenId: String?,
    val timestamp: Long
) {
    fun toJson(): String {
        return """{"screenId":"$screenId","elementId":"$elementId","text":"${elementText ?: ""}","resultScreen":"${resultScreenId ?: ""}"}"""
    }
}

data class ElementCorrection(
    val type: CorrectionType,
    val screenId: String,
    val elementId: String,
    val elementText: String? = null,
    val sensorType: String? = null,
    val sensorName: String? = null,
    val originalSensorType: String? = null,
    val ignoreReason: String? = null,
    val priorityLevel: Int = 0,
    val actionSequence: List<ActionStep>? = null,
    val timestamp: Long
)

data class ActionStep(
    val actionType: ActionType,
    val targetElementId: String? = null,
    val text: String? = null,
    val delayMs: Long = 0,
    val swipeDirection: String? = null
) {
    fun toJson(): String {
        return """{"type":"${actionType.name}","target":"${targetElementId ?: ""}","text":"${text ?: ""}","delay":$delayMs}"""
    }
}

enum class ActionType {
    TAP,
    LONG_PRESS,
    SWIPE,
    TYPE_TEXT,
    WAIT,
    BACK
}

data class CorrectionResult(
    val pathStepsRecorded: Int,
    val correctionsApplied: Int,
    val packageName: String
)

data class GoldenPath(
    val name: String,
    val steps: List<PathStep>,
    val createdAt: Long
)

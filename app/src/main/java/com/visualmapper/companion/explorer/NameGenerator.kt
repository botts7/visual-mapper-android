package com.visualmapper.companion.explorer

import android.util.Log
import com.visualmapper.companion.accessibility.UIElement
import com.visualmapper.companion.explorer.learning.FeedbackStore
import com.visualmapper.companion.explorer.learning.LearningStore

/**
 * Generates names for sensors and actions based on UI element properties.
 *
 * Uses a priority system:
 * 1. User-learned names from feedback history
 * 2. Pattern-based suggestions from learning store
 * 3. Fallback to resource ID or text-based naming
 *
 * Extracted from AppExplorerService for modularity.
 */
object NameGenerator {

    private const val TAG = "NameGenerator"

    /**
     * Convert UIElement from accessibility service to ClickableElement.
     */
    fun convertToClickableElement(element: UIElement): ClickableElement {
        val bounds = ElementBounds(
            x = element.bounds.x,
            y = element.bounds.y,
            width = element.bounds.width,
            height = element.bounds.height
        )

        return ClickableElement(
            elementId = generateElementId(element.resourceId, element.text, element.className, bounds),
            resourceId = element.resourceId.takeIf { it.isNotEmpty() },
            text = element.text.takeIf { it.isNotEmpty() },
            contentDescription = element.contentDescription.takeIf { it.isNotEmpty() },
            className = element.className,
            centerX = element.bounds.centerX,
            centerY = element.bounds.centerY,
            bounds = bounds
        )
    }

    /**
     * Generate a sensor name from text and resource ID.
     *
     * Priority:
     * 1. User-learned name from feedback history
     * 2. Pattern-based suggestion (e.g., "542 km" -> "Distance")
     * 3. Resource ID-based name (e.g., "tv_battery_level" -> "Battery Level")
     * 4. Text-based name (cleaned up)
     *
     * @param text The text content of the element
     * @param resourceId The Android resource ID (optional)
     * @param packageName The app package name for context
     * @param feedbackStore The feedback store for learned names
     * @param learningStore The learning store for pattern matching
     * @return A generated sensor name
     */
    fun generateSensorName(
        text: String,
        resourceId: String?,
        packageName: String,
        feedbackStore: FeedbackStore?,
        learningStore: LearningStore?
    ): String {
        // 1. Check if user previously named a similar sensor
        feedbackStore?.suggestNameFromHistory(text, packageName)?.let { learnedName ->
            Log.d(TAG, "Using learned sensor name: $learnedName (from user feedback)")
            return learnedName
        }

        // 2. Check pattern-based suggestions (e.g., "542 km" -> "Distance")
        learningStore?.suggestDeviceClass(text, packageName)?.let { patternSuggestion ->
            val baseName = patternSuggestion.deviceClass.replaceFirstChar { it.uppercase() }
            Log.d(TAG, "Using pattern-based name: $baseName (${patternSuggestion.patternType})")
            return baseName
        }

        // 3. Fall back to resource ID or text-based naming
        return resourceId?.substringAfterLast('/')
            ?.replace("_", " ")
            ?.split(" ")
            ?.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            ?: text.take(20).replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replaceFirstChar { it.uppercase() }
    }

    /**
     * Generate an action name from text and resource ID.
     *
     * Priority:
     * 1. User-learned name from feedback history
     * 2. Check if action was previously rejected (returns "_rejected_" prefix)
     * 3. Pattern-based action detection
     * 4. Resource ID-based name
     * 5. Text-based name
     * 6. Timestamp-based fallback
     *
     * @param text The text content of the element (optional)
     * @param resourceId The Android resource ID (optional)
     * @param packageName The app package name for context
     * @param feedbackStore The feedback store for learned names
     * @param learningStore The learning store for pattern matching
     * @return A generated action name
     */
    fun generateActionName(
        text: String?,
        resourceId: String?,
        packageName: String,
        feedbackStore: FeedbackStore?,
        learningStore: LearningStore?
    ): String {
        // 1. Check if user previously named this action
        feedbackStore?.suggestActionNameFromHistory(text ?: "", packageName)?.let { learnedName ->
            Log.d(TAG, "Using learned action name: $learnedName (from user feedback)")
            return learnedName
        }

        // 2. Check if this button was previously rejected
        if (text != null && feedbackStore?.wasActionRejected(text, packageName) == true) {
            Log.d(TAG, "Skipping previously rejected action: $text")
            return "_rejected_${text.take(10)}"
        }

        // 3. Check pattern-based action detection
        learningStore?.detectActionType(text, resourceId)?.let { actionSuggestion ->
            Log.d(TAG, "Using pattern-based action name: ${actionSuggestion.suggestedName}")
            return actionSuggestion.suggestedName
        }

        // 4. Fall back to default naming
        return when {
            !resourceId.isNullOrEmpty() -> {
                val name = resourceId.substringAfterLast('/').replace("_", " ")
                name.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            }
            !text.isNullOrEmpty() -> text.take(20).trim()
            else -> "Action ${System.currentTimeMillis() % 10000}"
        }
    }

    /**
     * Generate an enhanced sensor with device class and unit suggestions.
     *
     * @param text The text content of the element
     * @param resourceId The Android resource ID (optional)
     * @param screenId The screen where this sensor was found
     * @param elementId The element ID
     * @param packageName The app package name for context
     * @param feedbackStore The feedback store for learned names
     * @param learningStore The learning store for pattern matching
     * @return A GeneratedSensor with name, device class, and unit suggestions
     */
    fun generateEnhancedSensor(
        text: String,
        resourceId: String?,
        screenId: String,
        elementId: String,
        packageName: String,
        feedbackStore: FeedbackStore?,
        learningStore: LearningStore?
    ): GeneratedSensor {
        val name = generateSensorName(text, resourceId, packageName, feedbackStore, learningStore)

        // Get device class and unit from learning
        val patternSuggestion = learningStore?.suggestDeviceClass(text, packageName)
        val deviceClass = patternSuggestion?.deviceClass
        val unit = patternSuggestion?.unit

        return GeneratedSensor(
            name = name,
            screenId = screenId,
            elementId = elementId,
            resourceId = resourceId,
            sensorType = SuggestedSensorType.TEXT,
            sampleValue = text,
            deviceClass = deviceClass,
            unitOfMeasurement = unit
        )
    }
}

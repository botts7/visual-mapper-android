package com.visualmapper.companion.explorer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * FlowGenerator - Converts exploration results into executable flows
 *
 * Takes the ExplorationState from AppExplorerService and generates a flow
 * that can be saved and executed to collect sensors/trigger actions.
 *
 * Flow Format matches server-side SensorCollectionFlow model:
 * - flow_id: unique identifier
 * - device_id: Android device ID
 * - name: human-readable name
 * - steps: list of FlowStep objects
 * - update_interval_seconds: execution interval
 * - enabled: whether flow is active
 */
class FlowGenerator(
    private val explorationState: ExplorationState,
    private val deviceId: String,
    private val stableDeviceId: String? = null
) {
    companion object {
        private const val TAG = "FlowGenerator"
    }

    /**
     * Generate a sensor collection flow from exploration results.
     *
     * @param flowName Optional custom name (defaults to "Auto: <AppName>")
     * @param selectedSensors Optional list of sensors to include (defaults to all)
     * @param selectedActions Optional list of actions to include (defaults to none)
     * @return Generated flow as JSON string
     */
    fun generateSensorCollectionFlow(
        flowName: String? = null,
        selectedSensors: List<GeneratedSensor>? = null,
        selectedActions: List<GeneratedAction>? = null
    ): String {
        val appName = explorationState.packageName.substringAfterLast(".")
        val name = flowName ?: "Auto: $appName"

        Log.i(TAG, "Generating flow: $name")
        Log.i(TAG, "  Screens discovered: ${explorationState.exploredScreens.size}")
        Log.i(TAG, "  Navigation graph size: ${explorationState.navigationGraph.getStats().totalTransitions}")

        val steps = mutableListOf<JSONObject>()

        // Step 1: Launch app
        steps.add(createLaunchAppStep(explorationState.packageName))

        // Step 2: Wait for app to load
        steps.add(createWaitStep(2000, "Wait for app launch"))

        // Collect sensors by screen to minimize navigation
        val sensorsByScreen = groupSensorsByScreen(selectedSensors)

        // Step 3: For each screen with sensors, navigate and capture
        for ((screenId, sensors) in sensorsByScreen) {
            Log.d(TAG, "Processing screen $screenId with ${sensors.size} sensors")

            // Add navigation steps to reach this screen
            val navSteps = generateNavigationSteps(screenId)
            steps.addAll(navSteps)

            // Capture sensors on this screen
            val sensorIds = sensors.map { it.name.lowercase().replace(Regex("[^a-z0-9_]"), "_") }
            steps.add(createCaptureSensorsStep(sensorIds, screenId))
        }

        // Step 4: Optional actions
        selectedActions?.filter { it.selected }?.forEach { action ->
            // Generate steps to reach action's screen
            val navSteps = generateNavigationSteps(action.screenId)
            steps.addAll(navSteps)

            // Execute the action
            steps.add(createExecuteActionStep(action))
        }

        // Step 5: Go home
        steps.add(createGoHomeStep())

        // Build the complete flow
        val flow = JSONObject().apply {
            put("flow_id", "auto_${UUID.randomUUID().toString().take(8)}")
            put("device_id", deviceId)
            stableDeviceId?.let { put("stable_device_id", it) }
            put("name", name)
            put("description", "Auto-generated flow from app exploration of ${explorationState.packageName}")
            put("steps", JSONArray(steps))
            put("update_interval_seconds", 300) // 5 minutes default
            put("enabled", false) // Disabled by default - user can enable after review
            put("stop_on_error", false)
            put("max_flow_retries", 3)
            put("flow_timeout", 60)
            put("execution_method", "android") // Execute on companion app
        }

        val flowJson = flow.toString(2)
        Log.i(TAG, "Generated flow with ${steps.size} steps")

        return flowJson
    }

    /**
     * Group sensors by the screen they were discovered on.
     */
    private fun groupSensorsByScreen(sensors: List<GeneratedSensor>?): Map<String, List<GeneratedSensor>> {
        val allSensors = sensors ?: collectAllSensors()
        return allSensors.filter { it.selected }.groupBy { it.screenId }
    }

    /**
     * Collect all sensors from explored screens.
     */
    private fun collectAllSensors(): List<GeneratedSensor> {
        val sensors = mutableListOf<GeneratedSensor>()

        for ((screenId, screen) in explorationState.exploredScreens) {
            for (textElement in screen.textElements) {
                // Use SensorDetector patterns to identify potential sensors
                val detection = SensorDetector.detect(textElement.text)

                if (detection != null || textElement.text.length in 1..50) {
                    sensors.add(
                        GeneratedSensor(
                            name = suggestSensorName(textElement),
                            screenId = screenId,
                            elementId = textElement.elementId,
                            resourceId = textElement.resourceId,
                            sensorType = detection?.sensorType ?: textElement.sensorType,
                            extractionMethod = detection?.extractionMethod ?: "exact",
                            sampleValue = textElement.text,
                            selected = detection != null, // Auto-select detected sensors
                            deviceClass = detection?.deviceClass,
                            unitOfMeasurement = detection?.unit,
                            icon = detection?.icon
                        )
                    )
                }
            }
        }

        Log.i(TAG, "Collected ${sensors.size} potential sensors from ${explorationState.exploredScreens.size} screens")
        return sensors
    }

    /**
     * Suggest a sensor name based on element properties.
     */
    private fun suggestSensorName(element: TextElement): String {
        // Try resource ID first
        val resourceName = element.resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!resourceName.isNullOrEmpty()) {
            return resourceName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        // Try content description
        element.contentDescription?.let {
            if (it.isNotEmpty() && it.length < 30) {
                return it.replaceFirstChar { c -> c.uppercase() }
            }
        }

        // Use text itself (truncated)
        val text = element.text.take(30)
        return text.replaceFirstChar { it.uppercase() }
    }

    /**
     * Generate navigation steps to reach a target screen.
     * Uses the exploration's NavigationGraph to find the path.
     */
    private fun generateNavigationSteps(targetScreenId: String): List<JSONObject> {
        val steps = mutableListOf<JSONObject>()

        // Find path from home screen (first discovered screen) to target
        val homeScreenId = explorationState.exploredScreens.keys.firstOrNull()
        if (homeScreenId == null || homeScreenId == targetScreenId) {
            return steps
        }

        val path = explorationState.navigationGraph.findPath(homeScreenId, targetScreenId)
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "No navigation path found to screen $targetScreenId")
            return steps
        }

        Log.d(TAG, "Navigation path to $targetScreenId: ${path.size} steps")

        // Convert path to tap steps
        for ((fromScreenId, elementId) in path) {
            val screen = explorationState.exploredScreens[fromScreenId] ?: continue
            val element = screen.clickableElements.find { it.elementId == elementId } ?: continue

            // Add tap step
            steps.add(createTapStep(
                x = element.centerX,
                y = element.centerY,
                description = "Navigate: ${element.text ?: element.resourceId ?: elementId}"
            ))

            // Add wait for transition
            steps.add(createWaitStep(1000, "Wait for screen transition"))
        }

        return steps
    }

    /**
     * Create a launch_app step.
     */
    private fun createLaunchAppStep(packageName: String): JSONObject {
        return JSONObject().apply {
            put("step_type", "launch_app")
            put("package", packageName)
            put("description", "Launch $packageName")
        }
    }

    /**
     * Create a wait step.
     */
    private fun createWaitStep(durationMs: Int, description: String): JSONObject {
        return JSONObject().apply {
            put("step_type", "wait")
            put("duration", durationMs)
            put("description", description)
        }
    }

    /**
     * Create a tap step.
     */
    private fun createTapStep(x: Int, y: Int, description: String): JSONObject {
        return JSONObject().apply {
            put("step_type", "tap")
            put("x", x)
            put("y", y)
            put("description", description)
        }
    }

    /**
     * Create a capture_sensors step.
     */
    private fun createCaptureSensorsStep(sensorIds: List<String>, screenId: String): JSONObject {
        return JSONObject().apply {
            put("step_type", "capture_sensors")
            put("sensor_ids", JSONArray(sensorIds))
            put("description", "Capture ${sensorIds.size} sensors on screen ${screenId.take(8)}")
            put("expected_screen_id", screenId)
        }
    }

    /**
     * Create an execute_action step.
     */
    private fun createExecuteActionStep(action: GeneratedAction): JSONObject {
        val screen = explorationState.exploredScreens[action.screenId]
        val element = screen?.clickableElements?.find { it.elementId == action.elementId }

        return JSONObject().apply {
            put("step_type", "tap")
            put("x", element?.centerX ?: 0)
            put("y", element?.centerY ?: 0)
            put("action_id", action.name.lowercase().replace(Regex("[^a-z0-9_]"), "_"))
            put("description", "Action: ${action.name}")
        }
    }

    /**
     * Create a go_home step.
     */
    private fun createGoHomeStep(): JSONObject {
        return JSONObject().apply {
            put("step_type", "go_home")
            put("description", "Return to home screen")
        }
    }

    /**
     * Get all actions discovered during exploration.
     */
    fun getDiscoveredActions(): List<GeneratedAction> {
        val actions = mutableListOf<GeneratedAction>()

        for ((screenId, screen) in explorationState.exploredScreens) {
            for (element in screen.clickableElements) {
                // Identify actionable elements (buttons, toggles, etc.)
                val actionType = ActionDetector.detectActionType(element)

                if (actionType != ClickableActionType.UNKNOWN && actionType != ClickableActionType.NAVIGATION) {
                    // Generate navigation steps to reach this action
                    val stepsToReach = generateNavigationSteps(screenId).map { json ->
                        GeneratedFlowStep(
                            type = GeneratedStepType.TAP,
                            screenId = null,
                            elementId = null,
                            x = json.optInt("x"),
                            y = json.optInt("y"),
                            text = null,
                            waitMs = null,
                            description = json.optString("description", "")
                        )
                    }

                    actions.add(
                        GeneratedAction(
                            name = suggestActionName(element),
                            screenId = screenId,
                            elementId = element.elementId,
                            resourceId = element.resourceId,
                            actionType = actionType,
                            stepsToReach = stepsToReach,
                            selected = false // Actions not selected by default
                        )
                    )
                }
            }
        }

        Log.i(TAG, "Discovered ${actions.size} potential actions")
        return actions
    }

    /**
     * Suggest an action name based on element properties.
     */
    private fun suggestActionName(element: ClickableElement): String {
        // Try text first
        element.text?.let {
            if (it.isNotEmpty() && it.length < 30) {
                return it.replaceFirstChar { c -> c.uppercase() }
            }
        }

        // Try content description
        element.contentDescription?.let {
            if (it.isNotEmpty() && it.length < 30) {
                return it.replaceFirstChar { c -> c.uppercase() }
            }
        }

        // Try resource ID
        val resourceName = element.resourceId?.substringAfterLast("/")?.replace("_", " ")
        if (!resourceName.isNullOrEmpty()) {
            return resourceName.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }

        // Fallback to element ID
        return "Action ${element.elementId.take(8)}"
    }
}

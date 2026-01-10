package com.visualmapper.companion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.visualmapper.companion.R
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.models.SensorDefinition
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.sensor.SensorCaptureManager
import com.visualmapper.companion.storage.AppDatabase
import com.visualmapper.companion.storage.ExecutionResultEntity
import com.visualmapper.companion.storage.FlowStorage
import com.visualmapper.companion.explorer.ScreenWakeManager
import com.visualmapper.companion.ui.MainActivity
import com.visualmapper.companion.VisualMapperApp
import kotlinx.coroutines.*
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * Flow Executor Service
 *
 * Executes sensor flows locally on the device:
 * - Reads UI using Accessibility Service
 * - Performs gestures (tap, swipe, scroll)
 * - Extracts text and publishes to MQTT
 * - Supports scheduled and triggered execution
 *
 * Flow steps:
 * - TAP: Tap at coordinates
 * - SWIPE: Swipe between coordinates
 * - WAIT: Wait for duration
 * - EXTRACT_TEXT: Extract text and publish
 * - LAUNCH_APP: Launch an app
 * - SCROLL: Scroll direction
 */
class FlowExecutorService : Service() {

    companion object {
        private const val TAG = "FlowExecutor"
        private const val NOTIFICATION_CHANNEL_ID = "flow_executor"
        private const val NOTIFICATION_ID = 2001

        // Actions
        const val ACTION_EXECUTE_FLOW = "com.visualmapper.EXECUTE_FLOW"
        const val ACTION_STOP_FLOW = "com.visualmapper.STOP_FLOW"
        const val ACTION_CANCEL_FLOW = "com.visualmapper.CANCEL_FLOW"
        const val EXTRA_FLOW_JSON = "flow_json"
        const val EXTRA_FLOW_ID = "flow_id"
        const val EXTRA_EXECUTION_ID = "execution_id"

        // Singleton instance for UI access
        private var instance: FlowExecutorService? = null

        // Cached flows from server sync
        private val cachedFlows = mutableListOf<Flow>()
        private val flowCallbacks = mutableMapOf<String, (Boolean, String?) -> Unit>()

        fun getInstance(): FlowExecutorService? = instance

        /**
         * Get all cached flows for UI display
         */
        fun getAllFlows(): List<FlowData> {
            return cachedFlows.map { flow ->
                FlowData(
                    id = flow.id,
                    deviceId = flow.deviceId,
                    name = flow.name,
                    description = flow.description,
                    enabled = flow.enabled,
                    sensorCount = flow.steps.count { it.type == StepType.EXTRACT_TEXT || it.type == StepType.CAPTURE_SENSORS },
                    updateIntervalSeconds = flow.updateIntervalSeconds,
                    executionMethod = flow.executionMethod,
                    preferredExecutor = flow.preferredExecutor,
                    fallbackExecutor = flow.fallbackExecutor,
                    lastExecuted = flow.lastExecuted,
                    lastSuccess = flow.lastSuccess
                )
            }
        }

        /**
         * Get flow count
         */
        fun getFlowCount(): Int = cachedFlows.size

        /**
         * Add a new flow (created via wizard) - in-memory only
         * @deprecated Use addFlowAndPersist() for flows that should survive app restart
         */
        fun addFlow(flow: Flow) {
            // Remove existing flow with same ID if present
            cachedFlows.removeAll { it.id == flow.id }
            cachedFlows.add(flow)
            Log.i(TAG, "Added flow: ${flow.id} (${flow.name}) [in-memory only]")
        }

        /**
         * Add a new flow and persist to storage
         * Use this for flows created locally (wizard, etc.)
         */
        fun addFlowAndPersist(flow: Flow, context: Context, deviceId: String) {
            // Add to in-memory cache
            cachedFlows.removeAll { it.id == flow.id }
            cachedFlows.add(flow)

            // Persist to storage
            try {
                FlowStorage.getInstance(context).addFlow(flow, deviceId)
                Log.i(TAG, "Added and persisted flow: ${flow.id} (${flow.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist flow ${flow.id}: ${e.message}")
            }
        }

        /**
         * Sync flows from server response and persist to storage
         */
        fun syncFlows(flows: List<Flow>, context: Context? = null, deviceId: String? = null) {
            cachedFlows.clear()
            cachedFlows.addAll(flows)
            Log.i(TAG, "Synced ${flows.size} flows from server")

            // Persist to storage for offline execution
            if (context != null && deviceId != null) {
                try {
                    FlowStorage.getInstance(context).saveFlows(flows, deviceId)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist flows to storage: ${e.message}")
                }
            }
        }

        /**
         * Load flows from persistent storage
         * Call this at app startup before server sync
         */
        fun loadFromStorage(context: Context): Int {
            return try {
                val storage = FlowStorage.getInstance(context)
                val flows = storage.loadFlows()
                if (flows.isNotEmpty()) {
                    cachedFlows.clear()
                    cachedFlows.addAll(flows)
                    Log.i(TAG, "Loaded ${flows.size} flows from storage (${storage.getStatus()})")
                }
                flows.size
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load flows from storage: ${e.message}")
                0
            }
        }

        /**
         * Check if storage has cached flows
         */
        fun hasStoredFlows(context: Context): Boolean {
            return try {
                FlowStorage.getInstance(context).hasFlows()
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Add or update a single flow
         */
        fun addOrUpdateFlow(flow: Flow) {
            val index = cachedFlows.indexOfFirst { it.id == flow.id }
            if (index >= 0) {
                cachedFlows[index] = flow
            } else {
                cachedFlows.add(flow)
            }
        }

        /**
         * Clear all cached flows
         */
        fun clearFlows() {
            cachedFlows.clear()
        }

        /**
         * Get a specific flow by ID
         */
        fun getFlow(flowId: String): Flow? = cachedFlows.find { it.id == flowId }
    }

    /**
     * Flow data for UI display (simplified view)
     */
    data class FlowData(
        val id: String,
        val deviceId: String,
        val name: String,
        val description: String?,
        val enabled: Boolean,
        val sensorCount: Int,
        val updateIntervalSeconds: Int,
        val executionMethod: String,
        val preferredExecutor: String,
        val fallbackExecutor: String,
        val lastExecuted: String?,
        val lastSuccess: Boolean?
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentFlowJob: Job? = null
    private var currentFlowId: String? = null
    private var currentExecutionId: String? = null

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.i(TAG, "Flow Executor Service created")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
        scope.cancel()
        Log.i(TAG, "Flow Executor Service destroyed")
    }

    /**
     * Execute a flow by ID with callback
     */
    fun executeFlow(flowId: String, callback: (Boolean, String?) -> Unit) {
        val flow = getFlow(flowId)
        if (flow == null) {
            callback(false, "Flow not found: $flowId")
            return
        }

        // Store callback for result
        flowCallbacks[flowId] = callback

        // Serialize and execute
        val flowJson = json.encodeToString(Flow.serializer(), flow)
        val intent = Intent(this, FlowExecutorService::class.java).apply {
            action = ACTION_EXECUTE_FLOW
            putExtra(EXTRA_FLOW_JSON, flowJson)
            putExtra(EXTRA_EXECUTION_ID, "${flowId}_${System.currentTimeMillis()}")
        }
        startService(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always call startForeground immediately to satisfy Android 8.0+ requirement
        // Service must call startForeground() within 5 seconds of startForegroundService()
        startForeground(NOTIFICATION_ID, createNotification("Visual Mapper ready"))

        when (intent?.action) {
            ACTION_EXECUTE_FLOW -> {
                val flowJson = intent.getStringExtra(EXTRA_FLOW_JSON)
                val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID)
                if (flowJson != null) {
                    updateNotification("Executing flow...")
                    executeFlow(flowJson, executionId)
                }
            }
            ACTION_STOP_FLOW, ACTION_CANCEL_FLOW -> {
                stopCurrentFlow()
            }
            null -> {
                // Service started for background availability (from VisualMapperApp.onCreate)
                // Keep running in foreground for getInstance() access
                Log.i(TAG, "FlowExecutorService started for background availability")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // =========================================================================
    // Flow Execution
    // =========================================================================

    private fun executeFlow(flowJson: String, executionId: String? = null) {
        // Cancel any running flow
        currentFlowJob?.cancel()

        currentFlowJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var executedSteps = 0
            var totalSteps = 0

            // Notify navigation learner that flow execution is starting
            VisualMapperAccessibilityService.getInstance()?.setFlowExecuting(true)

            try {
                val flow = json.decodeFromString<Flow>(flowJson)
                currentFlowId = flow.id
                currentExecutionId = executionId
                totalSteps = flow.steps.size
                Log.i(TAG, "Starting flow: ${flow.name} (execution: $executionId)")

                updateNotification("Running: ${flow.name}")

                // ========== ENSURE KNOWN STARTING POINT ==========
                // Go to home screen and relaunch app for consistent flow execution
                val targetPackage = getTargetPackage(flow)
                if (targetPackage != null) {
                    Log.i(TAG, "Ensuring known starting point for package: $targetPackage")
                    updateNotification("Initializing: ${flow.name}")
                    val initSuccess = ensureKnownStartingPoint(targetPackage)
                    if (!initSuccess) {
                        Log.w(TAG, "Failed to initialize starting point, continuing anyway...")
                    }
                } else {
                    Log.w(TAG, "No target package found in flow, skipping initialization")
                }
                // ==================================================

                var flowSuccess = true
                var failedStepError: String? = null

                // Execute each step
                for ((index, step) in flow.steps.withIndex()) {
                    if (!isActive) break

                    Log.d(TAG, "Executing step ${index + 1}/${flow.steps.size}: ${step.type}")
                    updateNotification("Step ${index + 1}/${flow.steps.size}: ${step.type}")

                    var success = executeStep(step, flow)

                    // Retry logic if step failed and retry_on_failure is enabled
                    if (!success && step.retryOnFailure) {
                        repeat(step.maxRetries) { attempt ->
                            Log.w(TAG, "Step failed, retrying (attempt ${attempt + 1}/${step.maxRetries})")
                            delay(1000) // Wait 1 second between retries
                            success = executeStep(step, flow)
                            if (success) return@repeat
                        }
                    }

                    // Track executed steps regardless of success
                    executedSteps = index + 1

                    // Log step result
                    if (success) {
                        Log.i(TAG, "Step ${index + 1} (${step.type}) completed successfully")
                    } else {
                        Log.w(TAG, "Step ${index + 1} (${step.type}) FAILED")
                        flowSuccess = false  // Track that at least one step failed
                    }

                    // Check if flow should stop on error
                    if (!success && flow.stopOnError) {
                        Log.e(TAG, "Step failed and stopOnError=true, stopping flow")
                        failedStepError = "Step ${index + 1} (${step.type}) failed"
                        break
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "Flow completed: ${flow.name} (duration: ${duration}ms, steps: $executedSteps/$totalSteps)")
                publishFlowResult(flow.id, flowSuccess, failedStepError, duration, executedSteps, totalSteps)

            } catch (e: CancellationException) {
                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "Flow cancelled")
                publishFlowResult(currentFlowId ?: "", false, "Cancelled", duration, executedSteps, totalSteps)
            } catch (e: Exception) {
                val duration = System.currentTimeMillis() - startTime
                Log.e(TAG, "Flow execution error", e)
                publishFlowResult(currentFlowId ?: "", false, e.message, duration, executedSteps, totalSteps)
            } finally {
                // Notify navigation learner that flow execution has ended
                VisualMapperAccessibilityService.getInstance()?.setFlowExecuting(false)

                currentFlowId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun executeStep(step: FlowStep, flow: Flow): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance()

        return when (step.type) {
            StepType.TAP -> {
                if (accessibilityService == null) {
                    Log.e(TAG, "Accessibility service not available")
                    false
                } else {
                    val x = step.x ?: return false
                    val y = step.y ?: return false
                    accessibilityService.gestureDispatcher.tap(x.toFloat(), y.toFloat())
                }
            }

            StepType.LONG_PRESS -> {
                if (accessibilityService == null) false
                else {
                    val x = step.x ?: return false
                    val y = step.y ?: return false
                    accessibilityService.gestureDispatcher.longPress(x.toFloat(), y.toFloat())
                }
            }

            StepType.SWIPE -> {
                if (accessibilityService == null) false
                else {
                    val startX = step.startX ?: return false
                    val startY = step.startY ?: return false
                    val endX = step.endX ?: return false
                    val endY = step.endY ?: return false
                    val duration = step.duration ?: 300L
                    accessibilityService.gestureDispatcher.swipe(
                        startX.toFloat(), startY.toFloat(),
                        endX.toFloat(), endY.toFloat(),
                        duration
                    )
                }
            }

            StepType.SCROLL_UP -> {
                accessibilityService?.gestureDispatcher?.scrollUp() ?: false
            }

            StepType.SCROLL_DOWN -> {
                accessibilityService?.gestureDispatcher?.scrollDown() ?: false
            }

            StepType.WAIT -> {
                val ms = step.duration ?: 1000L
                delay(ms)
                true
            }

            StepType.EXTRACT_TEXT -> {
                if (accessibilityService == null) false
                else {
                    val x = step.x
                    val y = step.y

                    val text = if (x != null && y != null) {
                        accessibilityService.extractTextAt(x, y)
                    } else {
                        // Extract all text
                        accessibilityService.extractAllText().joinToString("\n")
                    }

                    if (text != null && step.sensorIds?.isNotEmpty() == true) {
                        // Publish to first sensor in the list (sensor_ids is an array in API)
                        publishSensorValue(step.sensorIds.first(), text, flow.deviceId)
                    }
                    true
                }
            }

            StepType.LAUNCH_APP -> {
                val packageName = step.packageName
                Log.i(TAG, "LAUNCH_APP: Attempting to launch package: $packageName")
                if (packageName == null) {
                    Log.e(TAG, "LAUNCH_APP: package name is null! Step data: type=${step.type}")
                    return false
                }

                // Get expected activity from step or recording context
                val expectedActivity = step.expectedActivity ?: step.screenActivity

                try {
                    // Check current state BEFORE launching
                    val currentActivity = accessibilityService?.getCurrentActivityName()
                    val currentPackage = currentActivity?.substringBefore('/') ?: ""

                    Log.d(TAG, "LAUNCH_APP: Current: $currentActivity, Expected: $expectedActivity")

                    // Already on correct screen?
                    if (expectedActivity != null && activityMatches(currentActivity, expectedActivity)) {
                        Log.i(TAG, "LAUNCH_APP: Already on correct screen: $expectedActivity")
                        return true
                    }

                    // Same app but wrong screen? Go home first for clean restart
                    if (currentPackage == packageName && expectedActivity != null) {
                        Log.i(TAG, "LAUNCH_APP: App open but wrong screen, going home first...")
                        accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                        delay(500)
                    }

                    // Build launch intent using multiple fallback strategies
                    val launchIntent: Intent? = buildLaunchIntent(packageName)

                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        Log.i(TAG, "LAUNCH_APP: Starting activity for $packageName")
                        startActivity(launchIntent)
                        delay(2000) // Wait for app to launch

                        // Validate we reached expected screen
                        if (expectedActivity != null) {
                            val newActivity = accessibilityService?.getCurrentActivityName()
                            if (!activityMatches(newActivity, expectedActivity)) {
                                Log.w(TAG, "LAUNCH_APP: Landed on $newActivity instead of $expectedActivity")
                                // Continue anyway - state validation before next step will handle this
                            } else {
                                Log.i(TAG, "LAUNCH_APP: Successfully launched to expected screen")
                            }
                        } else {
                            Log.i(TAG, "LAUNCH_APP: Successfully launched $packageName")
                        }
                        true
                    } else {
                        Log.e(TAG, "LAUNCH_APP: Could not find any way to launch: $packageName")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "LAUNCH_APP: Failed to launch app: $packageName", e)
                    false
                }
            }

            StepType.FIND_AND_TAP -> {
                if (accessibilityService == null) false
                else {
                    val searchText = step.text ?: return false
                    val element = accessibilityService.findElementByText(searchText)
                    if (element != null) {
                        accessibilityService.gestureDispatcher.tap(
                            element.bounds.centerX.toFloat(),
                            element.bounds.centerY.toFloat()
                        )
                    } else {
                        Log.w(TAG, "Element not found: $searchText")
                        false
                    }
                }
            }

            StepType.WAIT_FOR_ELEMENT -> {
                if (accessibilityService == null) false
                else {
                    val searchText = step.text ?: return false
                    val timeout = step.duration ?: 10000L
                    val startTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startTime < timeout) {
                        val element = accessibilityService.findElementByText(searchText)
                        if (element != null) {
                            return true
                        }
                        delay(500)
                    }
                    Log.w(TAG, "Timeout waiting for element: $searchText")
                    false
                }
            }

            // ========== NEW SERVER STEP TYPES (Stubs for parity) ==========

            StepType.CAPTURE_SENSORS -> {
                if (accessibilityService == null) {
                    Log.e(TAG, "CAPTURE_SENSORS: Accessibility service not available")
                    false
                } else if (step.embeddedSensors.isNullOrEmpty()) {
                    Log.w(TAG, "CAPTURE_SENSORS: No embedded sensors in step - using legacy sensor_ids")
                    // Legacy fallback: if no embedded sensors, treat as simple text extraction
                    // This maintains backward compatibility with older flows
                    step.sensorIds?.forEach { sensorId ->
                        val text = accessibilityService.extractAllText().joinToString("\n")
                        publishSensorValue(sensorId, text, flow.deviceId)
                    }
                    true
                } else {
                    Log.i(TAG, "CAPTURE_SENSORS: Capturing ${step.embeddedSensors.size} sensors")
                    val mqttManager = (application as? com.visualmapper.companion.VisualMapperApp)?.mqttManager
                    val captureManager = SensorCaptureManager(accessibilityService, mqttManager)
                    val results = captureManager.captureSensors(step.embeddedSensors, flow.deviceId)

                    // Log results
                    val summary = captureManager.getSummary(results)
                    Log.i(TAG, "CAPTURE_SENSORS: $summary")

                    // Return true if ANY sensor was captured successfully (partial success)
                    captureManager.hasAnySuccess(results)
                }
            }

            StepType.EXECUTE_ACTION -> {
                Log.w(TAG, "EXECUTE_ACTION: Stub implementation - not yet implemented in Android")
                // TODO: Implement Home Assistant action execution
                true
            }

            StepType.VALIDATE_SCREEN -> {
                val expectedActivity = step.expectedActivity ?: step.screenActivity
                val expectedElements = step.expectedUiElements

                Log.d(TAG, "VALIDATE_SCREEN: Validating screen state...")

                // Strategy 1: Check activity name
                if (expectedActivity != null) {
                    val currentActivity = accessibilityService?.getCurrentActivityName()
                    if (!activityMatches(currentActivity, expectedActivity)) {
                        Log.w(TAG, "VALIDATE_SCREEN: Wrong activity: $currentActivity (expected $expectedActivity)")
                        return handleRecoveryAction(step, step.screenPackage ?: step.packageName)
                    }
                    Log.d(TAG, "VALIDATE_SCREEN: Activity matches: $expectedActivity")
                }

                // Strategy 2: Check for expected UI elements
                if (expectedElements != null && expectedElements.isNotEmpty()) {
                    var matchCount = 0
                    for (expected in expectedElements) {
                        val text = expected["text"] as? String
                        val resourceId = expected["resource-id"] as? String ?: expected["resource_id"] as? String

                        val found = when {
                            resourceId != null -> accessibilityService?.hasElementWithResourceId(resourceId) == true
                            text != null -> accessibilityService?.hasElementWithText(text) == true
                            else -> false
                        }

                        if (found) {
                            matchCount++
                            Log.d(TAG, "VALIDATE_SCREEN: Found element: ${text ?: resourceId}")
                        }
                    }

                    val required = step.uiElementsRequired ?: 1
                    if (matchCount < required) {
                        Log.w(TAG, "VALIDATE_SCREEN: Only $matchCount/${expectedElements.size} elements found (need $required)")
                        return handleRecoveryAction(step, step.screenPackage ?: step.packageName)
                    }
                    Log.d(TAG, "VALIDATE_SCREEN: Found $matchCount elements")
                }

                Log.i(TAG, "VALIDATE_SCREEN: Validation passed")
                true
            }

            StepType.GO_HOME -> {
                accessibilityService?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                ) ?: false
            }

            StepType.GO_BACK -> {
                accessibilityService?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                ) ?: false
            }

            StepType.CONDITIONAL -> {
                val condition = step.condition
                if (condition.isNullOrBlank()) {
                    Log.d(TAG, "CONDITIONAL: No condition specified, passing")
                    return true
                }

                val service = accessibilityService
                if (service == null) {
                    Log.e(TAG, "CONDITIONAL: Accessibility service not available")
                    return false
                }

                val conditionMet = evaluateCondition(condition, service)
                Log.i(TAG, "CONDITIONAL: '$condition' evaluated to $conditionMet")

                val stepsToExecute = if (conditionMet) step.trueSteps else step.falseSteps
                if (stepsToExecute.isNullOrEmpty()) {
                    Log.d(TAG, "CONDITIONAL: No steps to execute for branch")
                    return true
                }

                // Execute nested steps
                Log.i(TAG, "CONDITIONAL: Executing ${stepsToExecute.size} steps from ${if (conditionMet) "true" else "false"} branch")
                for (nestedStep in stepsToExecute) {
                    if (!executeStep(nestedStep, flow)) {
                        Log.w(TAG, "CONDITIONAL: Nested step failed")
                        return false
                    }
                }
                true
            }

            StepType.PULL_REFRESH -> {
                Log.i(TAG, "PULL_REFRESH: Executing pull-to-refresh gesture")
                accessibilityService?.gestureDispatcher?.pullToRefresh() ?: false
            }

            StepType.RESTART_APP -> {
                val packageName = step.packageName ?: return false
                Log.i(TAG, "RESTART_APP: Force stopping and relaunching $packageName")
                try {
                    // Force stop (requires root or ADB, may not work on all devices)
                    Runtime.getRuntime().exec("am force-stop $packageName")
                    delay(500)
                    // Relaunch
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        delay(1000)
                        true
                    } else {
                        Log.e(TAG, "App not found: $packageName")
                        false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart app: $packageName", e)
                    false
                }
            }

            StepType.STITCH_CAPTURE -> {
                Log.w(TAG, "STITCH_CAPTURE: Stub implementation - server should handle stitching")
                // This is handled by server, Android doesn't do screenshot stitching
                true
            }

            StepType.WAKE_SCREEN -> {
                Log.i(TAG, "WAKE_SCREEN: Waking screen via ScreenWakeManager")
                ScreenWakeManager.getInstance(applicationContext).wakeScreen()
            }

            StepType.SLEEP_SCREEN -> {
                Log.i(TAG, "SLEEP_SCREEN: Releasing wake lock and locking screen")
                val wakeManager = ScreenWakeManager.getInstance(applicationContext)
                wakeManager.releaseWakeLock()
                wakeManager.lockScreen()
            }

            StepType.ENSURE_SCREEN_ON -> {
                Log.i(TAG, "ENSURE_SCREEN_ON: Checking screen state")
                val wakeManager = ScreenWakeManager.getInstance(applicationContext)
                if (!wakeManager.isScreenOn()) {
                    Log.i(TAG, "ENSURE_SCREEN_ON: Screen off, waking...")
                    wakeManager.wakeScreen()
                } else {
                    Log.d(TAG, "ENSURE_SCREEN_ON: Screen already on")
                    true
                }
            }

            StepType.TEXT -> {
                val textToType = step.text ?: return false
                Log.i(TAG, "TEXT: Typing text (length: ${textToType.length})")
                val service = accessibilityService
                if (service == null) {
                    Log.e(TAG, "TEXT: Accessibility service not available")
                    false
                } else {
                    service.inputText(textToType)
                }
            }

            StepType.KEYEVENT -> {
                val keycodeName = step.keycode ?: return false
                Log.i(TAG, "KEYEVENT: Dispatching keycode $keycodeName")
                val service = accessibilityService ?: return false
                when (keycodeName.uppercase()) {
                    "HOME", "KEYCODE_HOME" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                    "BACK", "KEYCODE_BACK" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    "RECENTS", "KEYCODE_APP_SWITCH" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                    "POWER", "KEYCODE_POWER" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    "NOTIFICATIONS" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                    "QUICK_SETTINGS" ->
                        service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                    else -> {
                        Log.w(TAG, "KEYEVENT: Unknown keycode $keycodeName")
                        false
                    }
                }
            }
        }
    }

    /**
     * Evaluate a condition string against the current UI state.
     *
     * Supported conditions:
     * - hasText:Submit - Check if element with text "Submit" exists
     * - hasElement:com.app:id/button - Check if element with resource ID exists
     * - activityIs:MainActivity - Check if current activity contains "MainActivity"
     * - notHasText:Loading - Check if element with text "Loading" does NOT exist
     * - notHasElement:com.app:id/spinner - Check if element does NOT exist
     */
    private fun evaluateCondition(
        condition: String,
        service: VisualMapperAccessibilityService
    ): Boolean {
        val parts = condition.split(":", limit = 2)
        val conditionType = parts.getOrNull(0)?.trim() ?: return false
        val value = parts.getOrNull(1)?.trim() ?: return false

        return when (conditionType.lowercase()) {
            "hastext" -> service.hasElementWithText(value)
            "haselement" -> service.hasElementWithResourceId(value)
            "activityis" -> service.getCurrentActivityName()?.contains(value, ignoreCase = true) == true
            "nothastext" -> !service.hasElementWithText(value)
            "nothaselement" -> !service.hasElementWithResourceId(value)
            else -> {
                Log.w(TAG, "evaluateCondition: Unknown condition type '$conditionType'")
                false
            }
        }
    }

    private fun stopCurrentFlow() {
        currentFlowJob?.cancel()
        currentFlowId = null
        currentExecutionId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =========================================================================
    // Activity Matching Helper
    // =========================================================================

    /**
     * Check if current activity matches expected.
     * Supports partial matching (e.g., ".MainActivity" vs "com.app/.MainActivity")
     */
    private fun activityMatches(current: String?, expected: String?): Boolean {
        if (current == null || expected == null) return false
        if (current == expected) return true
        // Try matching just the activity name part (after /)
        val currentName = current.substringAfterLast('/')
        val expectedName = expected.substringAfterLast('/')
        return currentName == expectedName
    }

    // =========================================================================
    // Known Starting Point - Ensures Consistent Flow Execution
    // =========================================================================

    /**
     * Get the target package for a flow.
     * Looks for LAUNCH_APP steps or screen_package in first step.
     */
    private fun getTargetPackage(flow: Flow): String? {
        // Strategy 1: Find first LAUNCH_APP step
        flow.steps.firstOrNull { it.type == StepType.LAUNCH_APP }?.let { step ->
            return step.packageName
        }

        // Strategy 2: Check first step's screen_package
        flow.steps.firstOrNull()?.screenPackage?.let { pkg ->
            return pkg
        }

        // Strategy 3: Check first step's package field (for any step type)
        flow.steps.firstOrNull()?.packageName?.let { pkg ->
            return pkg
        }

        return null
    }

    /**
     * Ensure a known starting point for consistent flow execution.
     *
     * This method:
     * 1. Goes to home screen (clean slate)
     * 2. Waits briefly for home to settle
     * 3. Launches the target app fresh
     * 4. Waits for app to load
     *
     * This ensures every flow execution starts from the same state,
     * preventing issues caused by the app being in an unexpected screen.
     */
    private suspend fun ensureKnownStartingPoint(packageName: String): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service not available for initialization")
            return false
        }

        try {
            // Step 1: Go to home screen for clean slate
            Log.d(TAG, "Init: Going to home screen...")
            accessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            )
            delay(800) // Wait for home screen to settle

            // Step 2: Launch the app fresh
            Log.d(TAG, "Init: Launching $packageName...")
            val launchIntent = buildLaunchIntent(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launchIntent)
                delay(2500) // Wait for app to fully load

                // Verify we're in the right app
                val currentActivity = accessibilityService.getCurrentActivityName()
                val currentPackage = currentActivity?.substringBefore('/') ?: ""

                if (currentPackage == packageName) {
                    Log.i(TAG, "Init: Successfully launched $packageName (activity: $currentActivity)")
                    return true
                } else {
                    Log.w(TAG, "Init: Expected $packageName but got $currentPackage")
                    return false
                }
            } else {
                Log.e(TAG, "Init: Could not build launch intent for $packageName")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Init: Failed to ensure starting point", e)
            return false
        }
    }

    /**
     * Handle recovery action when state validation fails.
     *
     * @param step The step that failed validation
     * @param packageName The package to recover (restart)
     * @return true if recovery succeeded, false otherwise
     */
    private suspend fun handleRecoveryAction(step: FlowStep, packageName: String?): Boolean {
        val recoveryAction = step.recoveryAction
        val accessibilityService = VisualMapperAccessibilityService.getInstance()

        Log.i(TAG, "Recovery: Action=$recoveryAction, Package=$packageName")

        return when (recoveryAction) {
            "force_restart_app" -> {
                if (packageName == null) {
                    Log.e(TAG, "Recovery: Cannot restart - no package name")
                    return false
                }
                Log.i(TAG, "Recovery: Force restarting $packageName")

                // Go home first for clean restart
                accessibilityService?.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                )
                delay(500)

                // Relaunch the app
                val intent = buildLaunchIntent(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    delay(2000) // Wait for app to load
                    Log.i(TAG, "Recovery: Restarted $packageName")
                    true
                } else {
                    Log.e(TAG, "Recovery: Could not build launch intent for $packageName")
                    false
                }
            }

            "skip_step" -> {
                Log.w(TAG, "Recovery: Skipping step due to state mismatch")
                true // Return success to continue flow
            }

            "fail" -> {
                Log.e(TAG, "Recovery: Failing step due to state mismatch")
                false
            }

            else -> {
                Log.w(TAG, "Recovery: Unknown action '$recoveryAction', defaulting to fail")
                false
            }
        }
    }

    // =========================================================================
    // App Launch Helper
    // =========================================================================

    /**
     * Build a launch intent for a package using multiple fallback strategies.
     * Handles devices with restricted launchers (car head units, etc.)
     */
    private fun buildLaunchIntent(packageName: String): Intent? {
        // Strategy 1: Standard launch intent
        packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
            Log.i(TAG, "LAUNCH_APP: Using standard launch intent for $packageName")
            return intent
        }

        // Strategy 2: Query for MAIN/LAUNCHER activities
        Log.w(TAG, "LAUNCH_APP: No standard launch intent, trying manual lookup...")
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val activities = packageManager.queryIntentActivities(mainIntent, 0)
        if (activities.isNotEmpty()) {
            val activity = activities[0].activityInfo
            Log.i(TAG, "LAUNCH_APP: Found LAUNCHER activity: ${activity.name}")
            return Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setClassName(activity.packageName, activity.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        // Strategy 3: Look for any exported activity
        Log.w(TAG, "LAUNCH_APP: No launcher activity found, trying direct package lookup...")
        try {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES
            )
            packageInfo.activities?.firstOrNull { it.exported }?.let { activityInfo ->
                Log.i(TAG, "LAUNCH_APP: Using exported activity: ${activityInfo.name}")
                return Intent().apply {
                    setClassName(packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            // Fallback to any activity
            packageInfo.activities?.firstOrNull()?.let { activityInfo ->
                Log.i(TAG, "LAUNCH_APP: Using first available activity: ${activityInfo.name}")
                return Intent().apply {
                    setClassName(packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LAUNCH_APP: Failed to get package info: ${e.message}")
        }

        Log.e(TAG, "LAUNCH_APP: No launch strategy worked for $packageName")
        return null
    }

    // =========================================================================
    // MQTT Publishing
    // =========================================================================

    private fun publishSensorValue(sensorId: String, value: String, deviceId: String) {
        val mqttManager = (application as? com.visualmapper.companion.VisualMapperApp)?.mqttManager

        // First publish MQTT discovery so Home Assistant knows about this sensor
        val friendlyName = sensorId.replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        mqttManager?.publishDiscovery(
            sensorId = sensorId,
            name = friendlyName,
            deviceClass = null,  // Text sensor
            unit = null
        )

        // Then publish the value
        mqttManager?.publishSensorValue(sensorId, value)

        Log.d(TAG, "Published sensor $sensorId with discovery and value: $value")
    }

    private fun publishFlowResult(
        flowId: String,
        success: Boolean,
        error: String?,
        duration: Long = 0,
        executedSteps: Int = 0,
        totalSteps: Int = 0
    ) {
        val app = application as? VisualMapperApp
        val mqttManager = app?.mqttManager

        // Build the JSON payload for storage and MQTT
        val payload = buildString {
            append("{")
            append("\"flow_id\":\"$flowId\",")
            append("\"success\":$success,")
            append("\"duration\":$duration,")
            append("\"triggered_by\":\"android\",")
            append("\"executed_steps\":$executedSteps,")
            append("\"total_steps\":$totalSteps,")
            if (error != null) {
                val escapedError = error.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                append("\"error\":\"$escapedError\",")
            }
            append("\"timestamp\":${System.currentTimeMillis()}")
            append("}")
        }

        // Get device ID from the flow cache
        val deviceId = cachedFlows.find { it.id == flowId }?.deviceId ?: ""

        // Phase 1: Offline Resilience - Always save to Room DB first
        val executionResult = ExecutionResultEntity(
            id = UUID.randomUUID().toString(),
            flowId = flowId,
            deviceId = deviceId,
            success = success,
            executedSteps = executedSteps,
            failedStep = if (!success) executedSteps else null,
            errorMessage = error,
            executionTimeMs = duration,
            payload = payload,
            timestamp = System.currentTimeMillis()
        )

        // Save to Room DB first (guaranteed persistence)
        scope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.executionResultDao().insert(executionResult)
                Log.d(TAG, "Execution result saved to DB: ${executionResult.id}")

                // Try to publish via MQTT
                if (mqttManager?.connectionState?.value == MqttManager.ConnectionState.CONNECTED) {
                    try {
                        mqttManager.publishFlowResult(
                            flowId = flowId,
                            success = success,
                            error = error,
                            duration = duration,
                            triggeredBy = "android",
                            executedSteps = executedSteps,
                            totalSteps = totalSteps
                        )

                        // MQTT publish succeeded, remove from DB
                        db.executionResultDao().deleteById(executionResult.id)
                        Log.d(TAG, "MQTT publish succeeded, removed from DB: ${executionResult.id}")
                    } catch (e: Exception) {
                        Log.w(TAG, "MQTT publish failed, result stays in DB for retry: ${e.message}")
                        // Result stays in DB for later flush
                    }
                } else {
                    Log.i(TAG, "MQTT offline, result queued for later delivery: ${executionResult.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save execution result to DB", e)
            }
        }

        // Invoke callback if one was registered (synchronously for immediate UI feedback)
        flowCallbacks.remove(flowId)?.invoke(success, error)

        // Update cached flow last execution status
        cachedFlows.find { it.id == flowId }?.let { flow ->
            val updatedFlow = flow.copy(
                lastExecuted = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()),
                lastSuccess = success,
                lastError = if (success) null else error,
                executionCount = flow.executionCount + 1,
                successCount = if (success) flow.successCount + 1 else flow.successCount,
                failureCount = if (!success) flow.failureCount + 1 else flow.failureCount
            )
            val index = cachedFlows.indexOfFirst { it.id == flowId }
            if (index >= 0) {
                cachedFlows[index] = updatedFlow
            }
        }

        val result = if (success) "success" else "failed: $error"
        Log.i(TAG, "Flow $flowId result: $result (queued for MQTT)")
    }

    /**
     * Flush all pending execution results to MQTT.
     *
     * Phase 1: Offline Resilience - Called when MQTT reconnects.
     * Reads all queued results from Room DB and attempts to publish each.
     * Successfully published results are deleted from DB.
     *
     * @return Number of results successfully flushed
     */
    suspend fun flushPendingResults(): Int {
        val app = application as? VisualMapperApp
        val mqttManager = app?.mqttManager

        if (mqttManager?.connectionState?.value != MqttManager.ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot flush pending results: MQTT not connected")
            return 0
        }

        val db = AppDatabase.getDatabase(applicationContext)
        val pendingResults = db.executionResultDao().getPendingResults(50)

        if (pendingResults.isEmpty()) {
            Log.d(TAG, "No pending results to flush")
            return 0
        }

        Log.i(TAG, "Flushing ${pendingResults.size} pending execution results")

        var successCount = 0
        val successfulResults = mutableListOf<ExecutionResultEntity>()

        for (result in pendingResults) {
            try {
                // Check if still retryable (exponential backoff)
                if (!result.isRetryable()) {
                    Log.d(TAG, "Result ${result.id} not yet retryable, skipping")
                    continue
                }

                // Publish to MQTT
                mqttManager.publishFlowResult(
                    flowId = result.flowId,
                    success = result.success,
                    error = result.errorMessage,
                    duration = result.executionTimeMs,
                    triggeredBy = "android",
                    executedSteps = result.executedSteps,
                    totalSteps = result.executedSteps + (if (result.success) 0 else 1)
                )

                successfulResults.add(result)
                successCount++
                Log.d(TAG, "Flushed result ${result.id} for flow ${result.flowId}")

            } catch (e: Exception) {
                Log.w(TAG, "Failed to flush result ${result.id}: ${e.message}")
                // Update retry count
                db.executionResultDao().update(result.withRetry())
            }
        }

        // Delete successfully flushed results
        if (successfulResults.isNotEmpty()) {
            db.executionResultDao().deleteAll(successfulResults)
            Log.i(TAG, "Flushed and deleted $successCount execution results")
        }

        // Clean up expired results (too many retries)
        val expiredCount = db.executionResultDao().deleteExpiredResults(10)
        if (expiredCount > 0) {
            Log.w(TAG, "Deleted $expiredCount expired results (exceeded max retries)")
        }

        return successCount
    }

    // =========================================================================
    // Notifications
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Flow Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when flows are being executed"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FlowExecutorService::class.java).apply {
            action = ACTION_STOP_FLOW
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Visual Mapper")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}

// =========================================================================
// Data Models
// =========================================================================

@Serializable
data class Flow(
    // ========== Identity ==========
    @kotlinx.serialization.SerialName("flow_id") val id: String,
    @kotlinx.serialization.SerialName("device_id") val deviceId: String,
    @kotlinx.serialization.SerialName("stable_device_id") val stableDeviceId: String? = null,

    // ========== Basic Configuration ==========
    val name: String,
    val description: String? = null,
    val steps: List<FlowStep>,

    // ========== Update Configuration ==========
    @kotlinx.serialization.SerialName("update_interval_seconds") val updateIntervalSeconds: Int = 60,
    val enabled: Boolean = true,

    // ========== Error Handling ==========
    @kotlinx.serialization.SerialName("stop_on_error") val stopOnError: Boolean = false,
    @kotlinx.serialization.SerialName("max_flow_retries") val maxFlowRetries: Int = 3,
    @kotlinx.serialization.SerialName("flow_timeout") val flowTimeout: Int = 60,

    // ========== Execution Method (Phase 1 - Execution Routing) ==========
    @kotlinx.serialization.SerialName("execution_method") val executionMethod: String = "server",
    @kotlinx.serialization.SerialName("preferred_executor") val preferredExecutor: String = "android",
    @kotlinx.serialization.SerialName("fallback_executor") val fallbackExecutor: String = "server",

    // ========== Headless Mode (Screen Power Control) ==========
    @kotlinx.serialization.SerialName("auto_wake_before") val autoWakeBefore: Boolean = true,
    @kotlinx.serialization.SerialName("auto_sleep_after") val autoSleepAfter: Boolean = true,
    @kotlinx.serialization.SerialName("verify_screen_on") val verifyScreenOn: Boolean = true,
    @kotlinx.serialization.SerialName("wake_timeout_ms") val wakeTimeoutMs: Int = 3000,

    // ========== Metadata (Optional - for display/debugging) ==========
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("updated_at") val updatedAt: String? = null,

    // ========== Runtime State (Optional - synced from server) ==========
    @kotlinx.serialization.SerialName("last_executed") val lastExecuted: String? = null,
    @kotlinx.serialization.SerialName("last_success") val lastSuccess: Boolean? = null,
    @kotlinx.serialization.SerialName("last_error") val lastError: String? = null,
    @kotlinx.serialization.SerialName("execution_count") val executionCount: Int = 0,
    @kotlinx.serialization.SerialName("success_count") val successCount: Int = 0,
    @kotlinx.serialization.SerialName("failure_count") val failureCount: Int = 0
)

@Serializable
data class FlowStep(
    // ========== Core Fields ==========
    @kotlinx.serialization.SerialName("step_type") val type: StepType,

    // ========== Basic Gestures ==========
    val x: Int? = null,
    val y: Int? = null,
    @kotlinx.serialization.SerialName("start_x") val startX: Int? = null,
    @kotlinx.serialization.SerialName("start_y") val startY: Int? = null,
    @kotlinx.serialization.SerialName("end_x") val endX: Int? = null,
    @kotlinx.serialization.SerialName("end_y") val endY: Int? = null,
    val duration: Long? = null,

    // ========== Text & Input ==========
    val text: String? = null,
    val keycode: String? = null,

    // ========== App Control ==========
    @kotlinx.serialization.SerialName("package") val packageName: String? = null,

    // ========== Action & Sensor ==========
    @kotlinx.serialization.SerialName("action_id") val actionId: String? = null,
    @kotlinx.serialization.SerialName("sensor_ids") val sensorIds: List<String>? = null,

    // ========== Validation ==========
    @kotlinx.serialization.SerialName("validation_element") val validationElement: Map<String, String>? = null,

    // ========== Conditional Logic ==========
    val condition: String? = null,
    @kotlinx.serialization.SerialName("true_steps") val trueSteps: List<FlowStep>? = null,
    @kotlinx.serialization.SerialName("false_steps") val falseSteps: List<FlowStep>? = null,

    // ========== Retry Logic ==========
    @kotlinx.serialization.SerialName("retry_on_failure") val retryOnFailure: Boolean = false,
    @kotlinx.serialization.SerialName("max_retries") val maxRetries: Int = 3,

    // ========== Metadata ==========
    val description: String? = null,

    // ========== State Validation (Phase 8 Hybrid) ==========
    @kotlinx.serialization.SerialName("expected_ui_elements") val expectedUiElements: List<Map<String, String>>? = null,
    @kotlinx.serialization.SerialName("expected_activity") val expectedActivity: String? = null,
    @kotlinx.serialization.SerialName("expected_screenshot") val expectedScreenshot: String? = null,
    @kotlinx.serialization.SerialName("state_match_threshold") val stateMatchThreshold: Float = 0.80f,
    @kotlinx.serialization.SerialName("validate_state") val validateState: Boolean = true,
    @kotlinx.serialization.SerialName("recovery_action") val recoveryAction: String = "force_restart_app",
    @kotlinx.serialization.SerialName("ui_elements_required") val uiElementsRequired: Int = 1,

    // ========== Screen Awareness (Phase 1) ==========
    @kotlinx.serialization.SerialName("screen_activity") val screenActivity: String? = null,
    @kotlinx.serialization.SerialName("screen_package") val screenPackage: String? = null,

    // ========== Timestamp Validation ==========
    @kotlinx.serialization.SerialName("validate_timestamp") val validateTimestamp: Boolean = false,
    @kotlinx.serialization.SerialName("timestamp_element") val timestampElement: Map<String, String>? = null,
    @kotlinx.serialization.SerialName("refresh_max_retries") val refreshMaxRetries: Int = 3,
    @kotlinx.serialization.SerialName("refresh_retry_delay") val refreshRetryDelay: Int = 2000,

    // ========== Embedded Sensors (Android CAPTURE_SENSORS) ==========
    // Server embeds full sensor definitions when syncing to Android
    // This allows Android to capture sensors without needing server
    @kotlinx.serialization.SerialName("embedded_sensors") val embeddedSensors: List<SensorDefinition>? = null
)

@Serializable
enum class StepType {
    // Basic gesture actions
    @SerialName("tap") TAP,
    @SerialName("long_press") LONG_PRESS,
    @SerialName("swipe") SWIPE,
    @SerialName("scroll_up") SCROLL_UP,
    @SerialName("scroll_down") SCROLL_DOWN,

    // Timing and validation
    @SerialName("wait") WAIT,
    @SerialName("extract_text") EXTRACT_TEXT,
    @SerialName("find_and_tap") FIND_AND_TAP,
    @SerialName("wait_for_element") WAIT_FOR_ELEMENT,
    @SerialName("validate_screen") VALIDATE_SCREEN,

    // App control
    @SerialName("launch_app") LAUNCH_APP,
    @SerialName("restart_app") RESTART_APP,
    @SerialName("go_home") GO_HOME,
    @SerialName("go_back") GO_BACK,

    // Advanced actions
    @SerialName("capture_sensors") CAPTURE_SENSORS,
    @SerialName("execute_action") EXECUTE_ACTION,
    @SerialName("conditional") CONDITIONAL,
    @SerialName("pull_refresh") PULL_REFRESH,
    @SerialName("stitch_capture") STITCH_CAPTURE,

    // Screen state control
    @SerialName("wake_screen") WAKE_SCREEN,
    @SerialName("sleep_screen") SLEEP_SCREEN,
    @SerialName("ensure_screen_on") ENSURE_SCREEN_ON,

    // Input actions
    @SerialName("text") TEXT,
    @SerialName("keyevent") KEYEVENT
}

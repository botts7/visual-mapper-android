package com.visualmapper.companion.mqtt

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.service.Flow
import com.visualmapper.companion.service.FlowExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Action Command Handler
 *
 * Processes real-time action commands received via MQTT from Visual Mapper server.
 *
 * Supported Commands:
 * - execute_flow: Execute a flow by ID or inline definition
 * - tap: Perform a tap gesture at coordinates
 * - swipe: Perform a swipe gesture
 * - get_ui_tree: Capture and return UI element tree
 * - get_screen_info: Get current screen state
 * - cancel_flow: Cancel currently running flow
 */
@OptIn(InternalSerializationApi::class)
class ActionCommandHandler(
    private val context: Context,
    private val mqttManager: MqttManager,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "ActionCommandHandler"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Track current execution for cancellation
    private var currentExecutionId: String? = null

    init {
        // Register handler with MQTT manager
        mqttManager.onActionCommand = { actionId, payload ->
            handleCommand(actionId, payload)
        }
        Log.i(TAG, "ActionCommandHandler initialized")
    }

    /**
     * Handle incoming action command
     */
    private fun handleCommand(actionId: String, payload: String) {
        Log.i(TAG, "Processing command: $actionId")

        scope.launch {
            try {
                when (actionId.lowercase()) {
                    "execute_flow" -> handleExecuteFlow(payload)
                    "tap" -> handleTap(payload)
                    "swipe" -> handleSwipe(payload)
                    "long_press" -> handleLongPress(payload)
                    "get_ui_tree" -> handleGetUiTree(payload)
                    "get_screen_info" -> handleGetScreenInfo(payload)
                    "cancel_flow" -> handleCancelFlow(payload)
                    "input_text" -> handleInputText(payload)
                    "key_event" -> handleKeyEvent(payload)
                    "wake_screen" -> handleWakeScreen(payload)
                    "sleep_screen" -> handleSleepScreen(payload)
                    "is_locked" -> handleIsLocked(payload)
                    "get_screen_state" -> handleGetScreenState(payload)
                    "ping" -> handlePing(payload)
                    else -> {
                        Log.w(TAG, "Unknown action: $actionId")
                        sendResponse(actionId, ActionResponse(
                            success = false,
                            error = "Unknown action: $actionId"
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling command $actionId: ${e.message}", e)
                sendResponse(actionId, ActionResponse(
                    success = false,
                    error = e.message ?: "Unknown error"
                ))
            }
        }
    }

    // =========================================================================
    // Command Handlers
    // =========================================================================

    /**
     * Execute a flow (by inline definition)
     */
    private fun handleExecuteFlow(payload: String) {
        val command = json.decodeFromString<ExecuteFlowCommand>(payload)

        Log.i(TAG, "Execute flow: ${command.flowId ?: "inline"}")
        currentExecutionId = command.executionId

        val intent = Intent(context, FlowExecutorService::class.java).apply {
            action = FlowExecutorService.ACTION_EXECUTE_FLOW

            if (command.flow != null) {
                // Inline flow definition
                putExtra(FlowExecutorService.EXTRA_FLOW_JSON,
                    json.encodeToString(Flow.serializer(), command.flow))
            } else if (command.flowId != null) {
                putExtra(FlowExecutorService.EXTRA_FLOW_ID, command.flowId)
            }

            command.executionId?.let {
                putExtra(FlowExecutorService.EXTRA_EXECUTION_ID, it)
            }
        }

        context.startService(intent)

        sendResponse("execute_flow", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("status", "started")
                put("executionId", command.executionId ?: "unknown")
            }
        ))
    }

    /**
     * Perform tap gesture
     */
    private suspend fun handleTap(payload: String) {
        val command = json.decodeFromString<TapCommand>(payload)

        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("tap", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = service.gestureDispatcher.tap(command.x, command.y)

        sendResponse("tap", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("x", command.x)
                put("y", command.y)
            }
        ))
    }

    /**
     * Perform swipe gesture
     */
    private suspend fun handleSwipe(payload: String) {
        val command = json.decodeFromString<SwipeCommand>(payload)

        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("swipe", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = service.gestureDispatcher.swipe(
            command.startX, command.startY,
            command.endX, command.endY,
            command.duration
        )

        sendResponse("swipe", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("startX", command.startX)
                put("startY", command.startY)
                put("endX", command.endX)
                put("endY", command.endY)
            }
        ))
    }

    /**
     * Perform long press gesture
     */
    private suspend fun handleLongPress(payload: String) {
        val command = json.decodeFromString<TapCommand>(payload)

        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("long_press", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = service.gestureDispatcher.longPress(command.x, command.y)

        sendResponse("long_press", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("x", command.x)
                put("y", command.y)
            }
        ))
    }

    /**
     * Get current UI element tree
     */
    private fun handleGetUiTree(@Suppress("UNUSED_PARAMETER") payload: String) {
        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("get_ui_tree", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val elements = service.getUITree()

        sendResponse("get_ui_tree", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("elementCount", elements.size)
                putJsonArray("elements") {
                    elements.forEach { element ->
                        add(buildJsonObject {
                            put("resourceId", element.resourceId)
                            put("className", element.className)
                            put("text", element.text)
                            put("contentDescription", element.contentDescription)
                            putJsonObject("bounds") {
                                put("x", element.bounds.x)
                                put("y", element.bounds.y)
                                put("width", element.bounds.width)
                                put("height", element.bounds.height)
                            }
                            put("isClickable", element.isClickable)
                            put("isScrollable", element.isScrollable)
                            put("isEnabled", element.isEnabled)
                            put("isChecked", element.isChecked)
                            put("isPassword", element.isPassword)
                            put("isSensitive", element.isSensitive)
                        })
                    }
                }
            }
        ))
    }

    /**
     * Get current screen state
     */
    private fun handleGetScreenInfo(@Suppress("UNUSED_PARAMETER") payload: String) {
        val service = VisualMapperAccessibilityService.getInstance()

        val currentPackage: String
        val currentClass: String

        if (service != null) {
            val rootNode = service.rootInActiveWindow
            currentPackage = rootNode?.packageName?.toString() ?: "unknown"
            currentClass = rootNode?.className?.toString() ?: "unknown"
        } else {
            currentPackage = "unknown"
            currentClass = "unknown"
        }

        sendResponse("get_screen_info", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("accessibilityEnabled", service != null)
                put("currentPackage", currentPackage)
                put("currentClass", currentClass)
            }
        ))
    }

    /**
     * Cancel currently running flow
     */
    private fun handleCancelFlow(@Suppress("UNUSED_PARAMETER") payload: String) {
        val intent = Intent(context, FlowExecutorService::class.java).apply {
            action = FlowExecutorService.ACTION_CANCEL_FLOW
        }
        context.startService(intent)

        currentExecutionId = null

        sendResponse("cancel_flow", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("status", "cancelled")
            }
        ))
    }

    /**
     * Input text into focused field
     */
    private fun handleInputText(payload: String) {
        val command = json.decodeFromString<InputTextCommand>(payload)

        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("input_text", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = service.inputText(command.text)

        sendResponse("input_text", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("text", command.text)
            }
        ))
    }

    /**
     * Send key event (back, home, etc.)
     */
    private fun handleKeyEvent(payload: String) {
        val command = json.decodeFromString<KeyEventCommand>(payload)

        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            sendResponse("key_event", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = when (command.key.uppercase()) {
            "BACK" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "HOME" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "RECENTS" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "POWER_DIALOG" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "LOCK_SCREEN" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    false
                }
            }
            else -> false
        }

        sendResponse("key_event", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("key", command.key)
            }
        ))
    }

    /**
     * Wake screen (turn on display)
     */
    private fun handleWakeScreen(@Suppress("UNUSED_PARAMETER") payload: String) {
        // Use PowerManager to wake screen
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "VisualMapper:WakeScreen"
        )

        wakeLock.acquire(3000) // 3 seconds
        wakeLock.release()

        sendResponse("wake_screen", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("status", "screen_woken")
                put("isInteractive", powerManager.isInteractive)
            }
        ))
    }

    /**
     * Sleep screen (turn off display / lock device)
     */
    private fun handleSleepScreen(@Suppress("UNUSED_PARAMETER") payload: String) {
        val service = VisualMapperAccessibilityService.getInstance()

        if (service == null) {
            sendResponse("sleep_screen", ActionResponse(
                success = false,
                error = "Accessibility service not running"
            ))
            return
        }

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API 28+ - Use accessibility service to lock screen
            service.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            )
        } else {
            // Pre-API 28 - Cannot lock screen without device admin
            Log.w(TAG, "sleep_screen requires API 28+ for accessibility-based lock")
            false
        }

        sendResponse("sleep_screen", ActionResponse(
            success = success,
            data = buildJsonObject {
                put("status", if (success) "screen_locked" else "lock_failed")
                put("apiLevel", Build.VERSION.SDK_INT)
            }
        ))
    }

    /**
     * Check if device is locked
     */
    private fun handleIsLocked(@Suppress("UNUSED_PARAMETER") payload: String) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        val isLocked = keyguardManager.isKeyguardLocked
        val isSecure = keyguardManager.isKeyguardSecure
        val isScreenOn = powerManager.isInteractive

        sendResponse("is_locked", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("isLocked", isLocked)
                put("isSecure", isSecure)
                put("isScreenOn", isScreenOn)
                put("requiresUnlock", isLocked && isSecure)
            }
        ))
    }

    /**
     * Get comprehensive screen state
     */
    private fun handleGetScreenState(@Suppress("UNUSED_PARAMETER") payload: String) {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val service = VisualMapperAccessibilityService.getInstance()

        // Get current app/activity from accessibility service
        val currentPackage = service?.currentPackage?.value ?: "unknown"
        val currentActivity = service?.currentActivity?.value ?: "unknown"

        sendResponse("get_screen_state", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("isScreenOn", powerManager.isInteractive)
                put("isLocked", keyguardManager.isKeyguardLocked)
                put("isSecure", keyguardManager.isKeyguardSecure)
                put("accessibilityEnabled", service != null)
                put("currentPackage", currentPackage)
                put("currentActivity", currentActivity)
                put("timestamp", System.currentTimeMillis())
            }
        ))
    }

    /**
     * Handle ping (health check)
     */
    private fun handlePing(@Suppress("UNUSED_PARAMETER") payload: String) {
        val service = VisualMapperAccessibilityService.getInstance()

        sendResponse("ping", ActionResponse(
            success = true,
            data = buildJsonObject {
                put("timestamp", System.currentTimeMillis())
                put("deviceId", deviceId)
                put("accessibilityEnabled", service != null)
                put("status", "alive")
            }
        ))
    }

    // =========================================================================
    // Response Handling
    // =========================================================================

    /**
     * Send response back via MQTT
     */
    private fun sendResponse(actionId: String, response: ActionResponse) {
        val responseJson = json.encodeToString(ActionResponse.serializer(), response)

        // Use MqttManager's publish method via callback
        scope.launch {
            try {
                mqttManager.publishSensorValue("action_response", responseJson)
                Log.d(TAG, "Sent response for $actionId: ${response.success}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send response: ${e.message}")
            }
        }
    }
}

// =========================================================================
// Command Data Classes
// =========================================================================

@Serializable
data class ExecuteFlowCommand(
    val flowId: String? = null,
    val flow: Flow? = null,
    val executionId: String? = null
)

@Serializable
data class TapCommand(
    val x: Float,
    val y: Float
)

@Serializable
data class SwipeCommand(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val duration: Long = 300
)

@Serializable
data class InputTextCommand(
    val text: String
)

@Serializable
data class KeyEventCommand(
    val key: String
)

@Serializable
data class ActionResponse(
    val success: Boolean,
    val error: String? = null,
    val data: JsonObject? = null
)

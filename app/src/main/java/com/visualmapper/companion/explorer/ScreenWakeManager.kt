package com.visualmapper.companion.explorer

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen Wake Manager
 *
 * Centralizes all screen wake/lock functionality for flow execution:
 * - Detection: Is screen on? Is device locked? Is it secure lock (PIN/pattern)?
 * - Wake: Wake screen using PowerManager
 * - Lock: Lock screen using Accessibility GLOBAL_ACTION_LOCK_SCREEN
 * - Wake Lock: Keep CPU/screen on during flow execution
 * - Strategy: Recommend best approach based on setup
 *
 * This module works in conjunction with ConnectionManager to determine
 * if server-assisted unlock is available.
 */
class ScreenWakeManager(private val context: Context) {

    companion object {
        private const val TAG = "ScreenWakeManager"
        private const val WAKE_LOCK_TAG = "VisualMapper:FlowExecution"

        @Volatile
        private var instance: ScreenWakeManager? = null

        fun getInstance(context: Context): ScreenWakeManager {
            return instance ?: synchronized(this) {
                instance ?: ScreenWakeManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Strategy for waking/unlocking device
     */
    enum class WakeStrategy {
        SERVER_ADB,         // Server available - use ADB for full unlock (best)
        POWERMANAGER_ONLY,  // No lock screen - just wake with PowerManager
        SMART_LOCK,         // Smart Lock enabled - wake + trusted location
        MANUAL_REQUIRED     // Secure lock, no server - user must unlock manually
    }

    /**
     * Current state of screen/lock
     */
    data class ScreenState(
        val isScreenOn: Boolean = false,
        val isDeviceLocked: Boolean = false,
        val isSecureLock: Boolean = false,
        val hasServerConnection: Boolean = false,
        val isBatteryOptimizationDisabled: Boolean = false,
        val recommendedStrategy: WakeStrategy = WakeStrategy.MANUAL_REQUIRED
    )

    /**
     * Battery optimization status
     */
    data class BatteryOptimizationStatus(
        val isIgnoringBatteryOptimizations: Boolean,
        val requiresExemption: Boolean,
        val manufacturer: String,
        val hasManufacturerOptimization: Boolean
    )

    // System services
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    // Current wake lock (null when not held)
    private var currentWakeLock: PowerManager.WakeLock? = null

    // State
    private val _screenState = MutableStateFlow(ScreenState())
    val screenState: StateFlow<ScreenState> = _screenState.asStateFlow()

    // =========================================================================
    // Detection Methods
    // =========================================================================

    /**
     * Check if screen is currently on
     */
    fun isScreenOn(): Boolean {
        return powerManager.isInteractive
    }

    /**
     * Check if device is locked (keyguard showing)
     */
    fun isDeviceLocked(): Boolean {
        return keyguardManager.isKeyguardLocked
    }

    /**
     * Check if device has secure lock (PIN, pattern, password, biometric)
     * vs swipe-to-unlock or no lock
     */
    fun isSecureLock(): Boolean {
        return keyguardManager.isDeviceSecure
    }

    /**
     * Check if device is in a trusted Smart Lock state
     * (trusted place, trusted device, on-body detection)
     *
     * Note: There's no direct API for this. We infer it by checking
     * if keyguard is locked but device is not secure.
     */
    fun isSmartLockTrusted(): Boolean {
        // If keyguard is not locked but device has secure lock configured,
        // we're likely in a Smart Lock trusted state
        return keyguardManager.isDeviceSecure && !keyguardManager.isKeyguardLocked
    }

    /**
     * Get current screen state
     */
    fun getCurrentState(): ScreenState {
        val hasServer = try {
            ConnectionManager.getInstance(context).isServerAvailable()
        } catch (e: Exception) {
            false
        }

        val state = ScreenState(
            isScreenOn = isScreenOn(),
            isDeviceLocked = isDeviceLocked(),
            isSecureLock = isSecureLock(),
            hasServerConnection = hasServer,
            isBatteryOptimizationDisabled = isIgnoringBatteryOptimizations(),
            recommendedStrategy = calculateStrategy(hasServer)
        )

        _screenState.value = state
        return state
    }

    // =========================================================================
    // Battery Optimization Detection
    // =========================================================================

    /**
     * Check if app is exempt from battery optimization (Doze mode).
     *
     * When battery optimization is enabled for the app:
     * - Wake locks may not work reliably
     * - Background execution is restricted
     * - Network access may be delayed
     * - Scheduled tasks may be deferred
     *
     * @return true if app is exempt from battery optimization
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // Pre-M devices don't have Doze
        }
    }

    /**
     * Get detailed battery optimization status.
     *
     * Checks both Android's Doze mode and manufacturer-specific battery
     * optimization features (Samsung, Xiaomi, Huawei, etc.)
     */
    fun getBatteryOptimizationStatus(): BatteryOptimizationStatus {
        val manufacturer = Build.MANUFACTURER.lowercase()

        // Known manufacturers with aggressive battery optimization
        val hasManufacturerOptimization = manufacturer in listOf(
            "samsung",     // Samsung: App power management, Sleeping apps
            "xiaomi",      // Xiaomi/Redmi: MIUI Battery saver
            "huawei",      // Huawei: App launch management
            "honor",       // Honor: Similar to Huawei
            "oppo",        // OPPO: Battery optimization
            "vivo",        // Vivo: Battery optimization
            "oneplus",     // OnePlus: Battery optimization
            "realme",      // Realme: Similar to OPPO
            "meizu",       // Meizu: Battery management
            "asus",        // ASUS: Power Master
            "lenovo",      // Lenovo: Battery Guardian
            "zte"          // ZTE: Battery optimization
        )

        val isIgnoring = isIgnoringBatteryOptimizations()

        return BatteryOptimizationStatus(
            isIgnoringBatteryOptimizations = isIgnoring,
            requiresExemption = !isIgnoring,
            manufacturer = Build.MANUFACTURER,
            hasManufacturerOptimization = hasManufacturerOptimization
        )
    }

    /**
     * Open Android's battery optimization settings for this app.
     *
     * This allows the user to disable battery optimization (add to whitelist).
     *
     * @return Intent to launch settings, or null if not available
     */
    fun getBatteryOptimizationSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }

        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * Open manufacturer-specific battery settings.
     *
     * Different manufacturers have their own battery optimization UIs:
     * - Samsung: Device care > Battery > App power management
     * - Xiaomi: Battery & performance > App battery saver
     * - Huawei: Battery > App launch
     * - etc.
     *
     * @return Intent to launch manufacturer settings, or null if unknown
     */
    fun getManufacturerBatterySettingsIntent(): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("samsung") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                }
            }
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                }
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                }
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"
                    )
                }
            }
            manufacturer.contains("vivo") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.vivo.abe",
                        "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"
                    )
                }
            }
            manufacturer.contains("oneplus") -> {
                Intent().apply {
                    component = android.content.ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                }
            }
            else -> null
        }
    }

    /**
     * Get user-friendly instructions for disabling battery optimization.
     */
    fun getBatteryOptimizationInstructions(): String {
        val status = getBatteryOptimizationStatus()
        val manufacturer = status.manufacturer

        val baseInstructions = """
            To ensure flows run reliably when the screen is off:

            1. Open Android Settings
            2. Go to Apps > Visual Mapper
            3. Select 'Battery'
            4. Choose 'Unrestricted' or 'Don't optimize'
        """.trimIndent()

        val manufacturerInstructions = when {
            manufacturer.lowercase().contains("samsung") -> """

                Samsung users also need to:
                1. Open Device Care > Battery
                2. Tap 'App power management'
                3. Add Visual Mapper to 'Never sleeping apps'
            """.trimIndent()

            manufacturer.lowercase().contains("xiaomi") -> """

                Xiaomi/Redmi users also need to:
                1. Open Security app > Battery
                2. Tap 'App battery saver'
                3. Find Visual Mapper and set to 'No restrictions'
            """.trimIndent()

            manufacturer.lowercase().contains("huawei") -> """

                Huawei users also need to:
                1. Open Settings > Battery > App launch
                2. Find Visual Mapper
                3. Disable 'Manage automatically'
                4. Enable all options (Auto-launch, Secondary launch, Run in background)
            """.trimIndent()

            status.hasManufacturerOptimization -> """

                Your device ($manufacturer) may have additional battery optimization.
                Check your device's battery settings for app restrictions.
            """.trimIndent()

            else -> ""
        }

        return baseInstructions + manufacturerInstructions
    }

    /**
     * Calculate recommended wake strategy based on current state
     */
    private fun calculateStrategy(hasServerConnection: Boolean): WakeStrategy {
        return when {
            // Server available - always use ADB (most reliable)
            hasServerConnection -> WakeStrategy.SERVER_ADB

            // No lock screen configured
            !isSecureLock() -> WakeStrategy.POWERMANAGER_ONLY

            // Smart Lock is active (trusted location/device)
            isSmartLockTrusted() -> WakeStrategy.SMART_LOCK

            // Secure lock without server - user must unlock
            else -> WakeStrategy.MANUAL_REQUIRED
        }
    }

    /**
     * Get recommended strategy
     */
    fun getRecommendedStrategy(): WakeStrategy {
        return getCurrentState().recommendedStrategy
    }

    // =========================================================================
    // Wake Methods
    // =========================================================================

    /**
     * Wake the screen.
     *
     * Uses PowerManager with ACQUIRE_CAUSES_WAKEUP flag to turn on screen.
     * Note: This does NOT unlock the device if there's a lock screen.
     *
     * @return true if screen was woken or already on
     */
    fun wakeScreen(): Boolean {
        if (isScreenOn()) {
            Log.d(TAG, "Screen already on")
            return true
        }

        return try {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "$WAKE_LOCK_TAG:ScreenWake"
            )

            wakeLock.acquire(1000L) // 1 second to turn on screen
            wakeLock.release()

            Log.i(TAG, "Screen wake requested")

            // Give it a moment to wake
            Thread.sleep(100)

            isScreenOn().also { success ->
                if (success) {
                    Log.i(TAG, "Screen woke successfully")
                } else {
                    Log.w(TAG, "Screen wake may have failed")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen", e)
            false
        }
    }

    /**
     * Acquire a wake lock to keep CPU/screen on during flow execution.
     *
     * IMPORTANT: Always call releaseWakeLock() when done!
     *
     * @param keepScreenOn If true, keeps screen on. If false, only keeps CPU on.
     * @param timeoutMs Maximum time to hold wake lock (safety timeout)
     * @return true if wake lock acquired
     */
    fun acquireWakeLock(keepScreenOn: Boolean = true, timeoutMs: Long = 5 * 60 * 1000L): Boolean {
        if (currentWakeLock != null) {
            Log.w(TAG, "Wake lock already held")
            return true
        }

        return try {
            val flags = if (keepScreenOn) {
                @Suppress("DEPRECATION")
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            } else {
                PowerManager.PARTIAL_WAKE_LOCK
            }

            currentWakeLock = powerManager.newWakeLock(flags, "$WAKE_LOCK_TAG:FlowExecution")
            currentWakeLock?.acquire(timeoutMs)

            Log.i(TAG, "Wake lock acquired (keepScreenOn=$keepScreenOn, timeout=${timeoutMs}ms)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
            currentWakeLock = null
            false
        }
    }

    /**
     * Release the current wake lock.
     */
    fun releaseWakeLock() {
        val wakeLock = currentWakeLock
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.i(TAG, "Wake lock released")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release wake lock", e)
            }
        }
        currentWakeLock = null
    }

    /**
     * Check if we currently hold a wake lock
     */
    fun isWakeLockHeld(): Boolean {
        return currentWakeLock?.isHeld == true
    }

    // =========================================================================
    // Lock Methods
    // =========================================================================

    /**
     * Lock the screen.
     *
     * Uses Accessibility Service's GLOBAL_ACTION_LOCK_SCREEN (API 28+)
     *
     * @return true if lock command was sent
     */
    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "lockScreen requires API 28+, current: ${Build.VERSION.SDK_INT}")
            return false
        }

        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.w(TAG, "Accessibility service not available for lock screen")
            return false
        }

        return try {
            val success = accessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            )
            if (success) {
                Log.i(TAG, "Screen lock command sent")
            } else {
                Log.w(TAG, "Screen lock command failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock screen", e)
            false
        }
    }

    // =========================================================================
    // Server-Assisted Unlock (via MQTT)
    // =========================================================================

    /**
     * Request server to unlock device via ADB.
     *
     * This sends an MQTT command to the server which will execute
     * the appropriate ADB commands to unlock the device.
     *
     * @param callback Called with result (true if unlock succeeded)
     */
    fun requestServerUnlock(callback: ((Boolean) -> Unit)? = null) {
        val connectionManager = try {
            ConnectionManager.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "ConnectionManager not available", e)
            callback?.invoke(false)
            return
        }

        if (!connectionManager.isServerAvailable()) {
            Log.w(TAG, "Server not available for unlock")
            callback?.invoke(false)
            return
        }

        Log.i(TAG, "Requesting server unlock via MQTT")

        // Send MQTT command to server
        connectionManager.sendCommand(
            topic = "visualmapper/device/unlock",
            payload = mapOf(
                "action" to "unlock",
                "timestamp" to System.currentTimeMillis()
            )
        ) { success ->
            if (success) {
                Log.i(TAG, "Server unlock command sent")
                // Wait a moment for unlock to complete
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val unlocked = !isDeviceLocked()
                    Log.i(TAG, "Server unlock result: ${if (unlocked) "SUCCESS" else "FAILED"}")
                    callback?.invoke(unlocked)
                }, 2000)
            } else {
                Log.w(TAG, "Failed to send unlock command")
                callback?.invoke(false)
            }
        }
    }

    /**
     * Request server to lock device via ADB.
     */
    fun requestServerLock(callback: ((Boolean) -> Unit)? = null) {
        val connectionManager = try {
            ConnectionManager.getInstance(context)
        } catch (e: Exception) {
            Log.e(TAG, "ConnectionManager not available", e)
            callback?.invoke(false)
            return
        }

        if (!connectionManager.isServerAvailable()) {
            Log.w(TAG, "Server not available for lock")
            callback?.invoke(false)
            return
        }

        Log.i(TAG, "Requesting server lock via MQTT")

        connectionManager.sendCommand(
            topic = "visualmapper/device/lock",
            payload = mapOf(
                "action" to "lock",
                "timestamp" to System.currentTimeMillis()
            )
        ) { success ->
            callback?.invoke(success)
        }
    }

    // =========================================================================
    // Flow Execution Helpers
    // =========================================================================

    /**
     * Prepare device for flow execution.
     *
     * This is a high-level method that:
     * 1. Checks battery optimization status
     * 2. Checks screen state
     * 3. Wakes screen if needed
     * 4. Handles unlock based on strategy
     * 5. Acquires wake lock
     *
     * @param requireBatteryOptimizationDisabled If true, fail if battery optimization is on
     * @param callback Called with result (true if ready, false if user action needed)
     */
    fun prepareForFlowExecution(
        requireBatteryOptimizationDisabled: Boolean = false,
        callback: (PrepareResult) -> Unit
    ) {
        val state = getCurrentState()
        Log.i(TAG, "Preparing for flow execution: $state")

        // Step 0: Check battery optimization
        val batteryStatus = getBatteryOptimizationStatus()
        val hasBatteryWarning = !state.isBatteryOptimizationDisabled && !state.hasServerConnection

        if (requireBatteryOptimizationDisabled && hasBatteryWarning) {
            Log.w(TAG, "Battery optimization is enabled - flow may not run reliably")
            callback(PrepareResult.BATTERY_OPTIMIZATION_REQUIRED)
            return
        }

        // Step 1: Wake screen if off
        if (!state.isScreenOn) {
            val woke = wakeScreen()
            if (!woke) {
                callback(PrepareResult.FAILED_WAKE)
                return
            }
        }

        // Determine final result based on battery warning
        val successResult = if (hasBatteryWarning) {
            PrepareResult.READY_WITH_WARNING
        } else {
            PrepareResult.READY
        }

        // Step 2: Handle lock based on strategy
        when (state.recommendedStrategy) {
            WakeStrategy.SERVER_ADB -> {
                if (state.isDeviceLocked) {
                    requestServerUnlock { success ->
                        if (success) {
                            acquireWakeLock()
                            callback(successResult)
                        } else {
                            callback(PrepareResult.FAILED_UNLOCK)
                        }
                    }
                } else {
                    acquireWakeLock()
                    callback(successResult)
                }
            }

            WakeStrategy.POWERMANAGER_ONLY,
            WakeStrategy.SMART_LOCK -> {
                // No lock or Smart Lock trusted - we can proceed
                acquireWakeLock()
                callback(successResult)
            }

            WakeStrategy.MANUAL_REQUIRED -> {
                if (state.isDeviceLocked) {
                    callback(PrepareResult.MANUAL_UNLOCK_REQUIRED)
                } else {
                    acquireWakeLock()
                    callback(successResult)
                }
            }
        }
    }

    /**
     * Clean up after flow execution.
     *
     * @param lockAfter If true, lock the device after releasing wake lock
     */
    fun cleanupAfterFlowExecution(lockAfter: Boolean = false) {
        Log.i(TAG, "Cleaning up after flow execution (lockAfter=$lockAfter)")

        releaseWakeLock()

        if (lockAfter) {
            val state = getCurrentState()
            when (state.recommendedStrategy) {
                WakeStrategy.SERVER_ADB -> {
                    requestServerLock()
                }
                else -> {
                    lockScreen()
                }
            }
        }
    }

    /**
     * Result of prepare operation
     */
    enum class PrepareResult {
        READY,                    // Device is ready for flow execution
        READY_WITH_WARNING,       // Ready but battery optimization may cause issues
        MANUAL_UNLOCK_REQUIRED,   // User must unlock device manually
        FAILED_WAKE,              // Could not wake screen
        FAILED_UNLOCK,            // Server unlock failed
        BATTERY_OPTIMIZATION_REQUIRED  // User needs to disable battery optimization
    }

    /**
     * Get user-friendly message for prepare result
     */
    fun getResultMessage(result: PrepareResult): String {
        return when (result) {
            PrepareResult.READY -> "Device ready"
            PrepareResult.READY_WITH_WARNING -> "Device ready (battery optimization warning)"
            PrepareResult.MANUAL_UNLOCK_REQUIRED -> "Please unlock device to continue"
            PrepareResult.FAILED_WAKE -> "Could not wake screen"
            PrepareResult.FAILED_UNLOCK -> "Could not unlock device"
            PrepareResult.BATTERY_OPTIMIZATION_REQUIRED -> "Please disable battery optimization for reliable flow execution"
        }
    }

    /**
     * Check if flows can run reliably with current battery settings.
     *
     * @return true if battery optimization is disabled or we have a server connection
     */
    fun canRunFlowsReliably(): Boolean {
        val state = getCurrentState()
        // Server connection can work around battery optimization
        // (server can wake device via ADB)
        return state.isBatteryOptimizationDisabled || state.hasServerConnection
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        releaseWakeLock()
    }
}

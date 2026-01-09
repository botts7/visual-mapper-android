package com.visualmapper.companion.explorer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.visualmapper.companion.security.SecurePreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TrainingOrchestrator - Manages automated batch training of multiple apps
 *
 * Features:
 * - Builds queue of trainable apps (whitelisted, non-sensitive, non-blocker)
 * - Explores each app for a configurable duration
 * - Tracks training progress and statistics
 * - Sends exploration data to ML server via MQTT
 * - Respects idle/charging conditions
 */
class TrainingOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "TrainingOrchestrator"

        // Training configuration
        const val DEFAULT_MINUTES_PER_APP = 5
        const val DEFAULT_MAX_APPS_PER_SESSION = 10
        const val COOLDOWN_BETWEEN_APPS_MS = 30_000L  // 30 seconds
        const val MIN_IDLE_TIME_MS = 300_000L  // 5 minutes idle before starting

        // Singleton for access from receivers
        @Volatile
        private var instance: TrainingOrchestrator? = null

        fun getInstance(context: Context): TrainingOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: TrainingOrchestrator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // Training state
    private val _trainingState = MutableStateFlow(TrainingState())
    val trainingState: StateFlow<TrainingState> = _trainingState

    private var trainingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Preferences for tracking last exploration times
    private val prefs = context.getSharedPreferences("training_orchestrator", Context.MODE_PRIVATE)

    // Apps that have been identified as blockers (require login)
    private val blockerApps = mutableSetOf<String>()

    /**
     * Training configuration
     */
    data class TrainingConfig(
        val enabled: Boolean = true,
        val requireCharging: Boolean = true,
        val requireIdle: Boolean = true,
        val minutesPerApp: Int = DEFAULT_MINUTES_PER_APP,
        val maxAppsPerSession: Int = DEFAULT_MAX_APPS_PER_SESSION,
        val cooldownHours: Int = 24,  // Don't re-explore app within this time
        val useNeuralNetwork: Boolean = false
    ) {
        companion object {
            fun fromJson(json: String): TrainingConfig {
                val obj = org.json.JSONObject(json)
                return TrainingConfig(
                    enabled = obj.optBoolean("enabled", true),
                    requireCharging = obj.optBoolean("requireCharging", true),
                    requireIdle = obj.optBoolean("requireIdle", true),
                    minutesPerApp = obj.optInt("minutesPerApp", DEFAULT_MINUTES_PER_APP),
                    maxAppsPerSession = obj.optInt("maxAppsPerSession", DEFAULT_MAX_APPS_PER_SESSION),
                    cooldownHours = obj.optInt("cooldownHours", 24),
                    useNeuralNetwork = obj.optBoolean("useNeuralNetwork", false)
                )
            }
        }

        fun toJson(): String {
            return org.json.JSONObject().apply {
                put("enabled", enabled)
                put("requireCharging", requireCharging)
                put("requireIdle", requireIdle)
                put("minutesPerApp", minutesPerApp)
                put("maxAppsPerSession", maxAppsPerSession)
                put("cooldownHours", cooldownHours)
                put("useNeuralNetwork", useNeuralNetwork)
            }.toString()
        }
    }

    /**
     * Training state for UI updates
     */
    data class TrainingState(
        val isRunning: Boolean = false,
        val currentApp: String? = null,
        val currentAppIndex: Int = 0,
        val totalApps: Int = 0,
        val appsCompleted: Int = 0,
        val appsFailed: Int = 0,
        val startTime: Long = 0,
        val lastError: String? = null
    )

    /**
     * Training statistics
     */
    data class TrainingStats(
        val totalAppsExplored: Int = 0,
        val totalScreensDiscovered: Int = 0,
        val totalElementsTapped: Int = 0,
        val blockerAppsSkipped: Int = 0,
        val sensitiveAppsSkipped: Int = 0,
        val averageTimePerApp: Long = 0
    )

    /**
     * Check if training can start based on conditions
     */
    fun canStartTraining(config: TrainingConfig = TrainingConfig()): Boolean {
        if (_trainingState.value.isRunning) {
            Log.d(TAG, "Training already running")
            return false
        }

        if (config.requireCharging && !isDeviceCharging()) {
            Log.d(TAG, "Device not charging")
            return false
        }

        if (config.requireIdle && !isDeviceIdle()) {
            Log.d(TAG, "Device not idle")
            return false
        }

        return true
    }

    /**
     * Start batch training session
     */
    fun startTraining(config: TrainingConfig = TrainingConfig()) {
        if (!canStartTraining(config)) {
            Log.w(TAG, "Cannot start training - conditions not met")
            return
        }

        Log.i(TAG, "=== STARTING BATCH TRAINING SESSION ===")

        trainingJob = scope.launch {
            try {
                runBatchTraining(config)
            } catch (e: CancellationException) {
                Log.i(TAG, "Training cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Training error", e)
                _trainingState.value = _trainingState.value.copy(
                    lastError = e.message
                )
            } finally {
                _trainingState.value = _trainingState.value.copy(isRunning = false)
                Log.i(TAG, "=== BATCH TRAINING SESSION ENDED ===")
            }
        }
    }

    /**
     * Stop training session
     */
    fun stopTraining() {
        Log.i(TAG, "Stopping training session")
        trainingJob?.cancel()

        // Stop current exploration if running
        val stopIntent = Intent(context, AppExplorerService::class.java).apply {
            action = AppExplorerService.ACTION_STOP
        }
        context.startService(stopIntent)

        _trainingState.value = _trainingState.value.copy(isRunning = false)
    }

    /**
     * Main batch training loop
     */
    private suspend fun runBatchTraining(config: TrainingConfig) {
        val apps = getTrainingCandidates(config)

        if (apps.isEmpty()) {
            Log.w(TAG, "No apps available for training")
            return
        }

        Log.i(TAG, "Found ${apps.size} apps for training")

        _trainingState.value = TrainingState(
            isRunning = true,
            totalApps = apps.size,
            startTime = System.currentTimeMillis()
        )

        var completed = 0
        var failed = 0

        for ((index, packageName) in apps.withIndex()) {
            // Check if we should continue
            if (!_trainingState.value.isRunning) break
            if (config.requireCharging && !isDeviceCharging()) {
                Log.w(TAG, "Device unplugged - stopping training")
                break
            }

            _trainingState.value = _trainingState.value.copy(
                currentApp = packageName,
                currentAppIndex = index + 1
            )

            Log.i(TAG, "=== Training app ${index + 1}/${apps.size}: $packageName ===")

            // Show toast for which app is starting
            showToast("Training ${index + 1}/${apps.size}: ${getAppName(packageName)}")

            val success = exploreApp(packageName, config)

            if (success) {
                completed++
                markAppExplored(packageName)
                showToast("✓ Completed: ${getAppName(packageName)}")
            } else {
                failed++
                showToast("✗ Skipped: ${getAppName(packageName)}")
            }

            _trainingState.value = _trainingState.value.copy(
                appsCompleted = completed,
                appsFailed = failed
            )

            // Cooldown between apps
            if (index < apps.size - 1) {
                Log.d(TAG, "Cooldown before next app...")
                showToast("Waiting 30s before next app... (${apps.size - index - 1} remaining)")
                delay(COOLDOWN_BETWEEN_APPS_MS)
            }
        }

        // Save training stats
        saveTrainingStats(completed, failed)

        Log.i(TAG, "Batch training complete: $completed succeeded, $failed failed")
    }

    /**
     * Get list of apps eligible for training
     */
    private fun getTrainingCandidates(config: TrainingConfig): List<String> {
        // Check if "Allow All Apps" is enabled
        val mainPrefs = context.getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val allowAllApps = mainPrefs.getBoolean("allow_all_apps_training", false)

        Log.i(TAG, "=== GETTING TRAINING CANDIDATES ===")
        Log.i(TAG, "Allow All Apps mode: $allowAllApps")

        val allApps: Set<String>

        if (allowAllApps) {
            // Get all launchable apps on the device
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = pm.queryIntentActivities(mainIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .filter { it != context.packageName }  // Exclude ourselves
                .toSet()

            Log.i(TAG, "All launchable apps: ${launchableApps.size}")
            allApps = launchableApps
        } else {
            // Get apps from consent JSON (user-approved apps)
            val securePrefs = SecurePreferences(context)
            val consentedApps = getConsentedAppsFromJson(securePrefs)

            // Also check whitelist (legacy support)
            val whitelistedApps = securePrefs.whitelistedApps

            // Combine both sources
            allApps = consentedApps + whitelistedApps

            Log.i(TAG, "Consented apps: ${consentedApps.size} - $consentedApps")
            Log.i(TAG, "Whitelisted apps: ${whitelistedApps.size} - $whitelistedApps")
        }

        Log.i(TAG, "Combined apps: ${allApps.size}")

        return allApps
            .filter { packageName ->
                // Skip sensitive apps (use intelligent check with whitelist)
                if (AppExplorerService.isSensitiveAppIntelligent(context, packageName)) {
                    Log.d(TAG, "Skipping sensitive app: $packageName")
                    return@filter false
                }

                // Skip blocker apps (require login)
                if (blockerApps.contains(packageName)) {
                    Log.d(TAG, "Skipping blocker app: $packageName")
                    return@filter false
                }

                // Skip recently explored apps
                if (!shouldExploreApp(packageName, config.cooldownHours)) {
                    Log.d(TAG, "Skipping recently explored: $packageName")
                    return@filter false
                }

                // Verify app is still installed
                if (!isAppInstalled(packageName)) {
                    Log.d(TAG, "App not installed: $packageName")
                    return@filter false
                }

                true
            }
            .take(config.maxAppsPerSession)
    }

    /**
     * Explore a single app
     */
    private suspend fun exploreApp(packageName: String, config: TrainingConfig): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // Check deep exploration preference
                val appPrefs = context.getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
                val deepExploration = appPrefs.getBoolean("deep_exploration", false)

                // Create exploration config based on mode
                val explorationConfig = if (deepExploration) {
                    Log.i(TAG, "Using DEEP exploration mode for $packageName")
                    ExplorationConfig.forDeepExploration(targetCoverage = 0.90f)
                } else {
                    Log.i(TAG, "Using QUICK exploration mode for $packageName")
                    ExplorationConfig(
                        mode = ExplorationMode.QUICK,
                        goal = ExplorationGoal.QUICK_SCAN,
                        maxDurationMs = config.minutesPerApp * 60 * 1000L,
                        maxElements = 50,
                        maxScreens = 20,
                        maxDepth = 5
                    )
                }

                // Start exploration
                val intent = Intent(context, AppExplorerService::class.java).apply {
                    action = AppExplorerService.ACTION_START
                    putExtra(AppExplorerService.EXTRA_PACKAGE_NAME, packageName)
                    putExtra(AppExplorerService.EXTRA_CONFIG, explorationConfig.toJson())
                }
                context.startForegroundService(intent)

                // Wait for exploration to complete (with timeout)
                val timeoutMs = (config.minutesPerApp * 60 * 1000L) + 30_000  // +30s buffer
                val startTime = System.currentTimeMillis()

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    delay(2000)  // Check every 2 seconds

                    val state = AppExplorerService.explorationState.value
                    if (state == null || state.status == ExplorationStatus.COMPLETED ||
                        state.status == ExplorationStatus.STOPPED ||
                        state.status == ExplorationStatus.ERROR ||
                        state.status == ExplorationStatus.CANCELLED) {
                        break
                    }

                    // Check for blocker screen detection
                    if (state.issues.any { it.issueType == IssueType.BLOCKER_SCREEN }) {
                        Log.w(TAG, "Blocker screen detected for $packageName - marking as blocker")
                        blockerApps.add(packageName)
                        saveBlockerApps()
                        return@withContext false
                    }
                }

                // Check final status
                val finalState = AppExplorerService.explorationState.value
                val success = finalState?.status == ExplorationStatus.COMPLETED ||
                             (finalState?.exploredScreens?.size ?: 0) > 1

                Log.i(TAG, "Exploration finished for $packageName: screens=${finalState?.exploredScreens?.size}, status=${finalState?.status}")

                success
            } catch (e: Exception) {
                Log.e(TAG, "Error exploring $packageName", e)
                false
            }
        }
    }

    /**
     * Check if app should be explored based on cooldown
     */
    private fun shouldExploreApp(packageName: String, cooldownHours: Int): Boolean {
        val lastExplored = prefs.getLong("last_explored_$packageName", 0)
        val cooldownMs = cooldownHours * 60 * 60 * 1000L
        return System.currentTimeMillis() - lastExplored > cooldownMs
    }

    /**
     * Mark app as explored
     */
    private fun markAppExplored(packageName: String) {
        prefs.edit()
            .putLong("last_explored_$packageName", System.currentTimeMillis())
            .apply()
    }

    /**
     * Check if app is installed
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Check if device is charging
     */
    private fun isDeviceCharging(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
        return batteryManager?.isCharging == true
    }

    /**
     * Get human-readable app name from package name
     */
    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    /**
     * Show toast message on main thread
     */
    private fun showToast(message: String) {
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Check if device is idle (screen off for a while)
     */
    private fun isDeviceIdle(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return powerManager?.isInteractive == false
    }

    /**
     * Save blocker apps list
     */
    private fun saveBlockerApps() {
        prefs.edit()
            .putStringSet("blocker_apps", blockerApps)
            .apply()
    }

    /**
     * Load blocker apps list
     */
    private fun loadBlockerApps() {
        blockerApps.clear()
        blockerApps.addAll(prefs.getStringSet("blocker_apps", emptySet()) ?: emptySet())
    }

    /**
     * Save training statistics
     */
    private fun saveTrainingStats(completed: Int, failed: Int) {
        val totalCompleted = prefs.getInt("total_apps_explored", 0) + completed
        val totalFailed = prefs.getInt("total_apps_failed", 0) + failed
        val totalSessions = prefs.getInt("total_training_sessions", 0) + 1

        prefs.edit()
            .putInt("total_apps_explored", totalCompleted)
            .putInt("total_apps_failed", totalFailed)
            .putInt("total_training_sessions", totalSessions)
            .putLong("last_training_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * Get training statistics
     */
    fun getTrainingStats(): TrainingStats {
        return TrainingStats(
            totalAppsExplored = prefs.getInt("total_apps_explored", 0),
            blockerAppsSkipped = blockerApps.size
        )
    }

    /**
     * Reset training history (for testing)
     */
    fun resetTrainingHistory() {
        prefs.edit().clear().apply()
        blockerApps.clear()
    }

    /**
     * Parse consented apps from the JSON stored in SecurePreferences
     * Consents are stored as a JSON Array: [{packageName, level, ...}, ...]
     */
    private fun getConsentedAppsFromJson(securePrefs: SecurePreferences): Set<String> {
        return try {
            val json = securePrefs.getConsentsJson() ?: return emptySet()
            val apps = mutableSetOf<String>()

            // Consents are stored as a JSON Array, not Object
            val jsonArray = org.json.JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val consent = jsonArray.optJSONObject(i)
                val packageName = consent?.optString("packageName", "") ?: ""
                val level = consent?.optString("level", "NONE") ?: "NONE"

                // Include if consent level is not NONE and package name exists
                if (level != "NONE" && packageName.isNotEmpty()) {
                    apps.add(packageName)
                }
            }
            Log.d(TAG, "Parsed ${apps.size} consented apps from JSON")
            apps
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse consents JSON", e)
            emptySet()
        }
    }

    init {
        loadBlockerApps()
    }
}

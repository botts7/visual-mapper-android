package com.visualmapper.companion.explorer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import android.graphics.Point
import android.view.WindowManager
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.UIElement
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.ui.fragments.MainContainerActivity
import com.visualmapper.companion.explorer.learning.FeedbackStore
import com.visualmapper.companion.explorer.learning.LearningStore
import com.visualmapper.companion.explorer.learning.AppMapStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App Explorer Service
 *
 * Autonomous app exploration using BFS (Breadth-First Search) algorithm.
 *
 * Algorithm:
 * 1. Launch target app
 * 2. Capture current screen → get all UI elements
 * 3. Queue clickable elements → add unexplored to BFS queue
 * 4. Loop while queue not empty:
 *    a. Pop next element from queue
 *    b. Navigate to source screen if needed
 *    c. Tap element
 *    d. Wait for transition
 *    e. If new screen: record it, queue its elements
 *    f. If dead end: backtrack (BACK button)
 * 5. Generate flow from exploration data
 * 6. Return result for user editing
 */
class AppExplorerService : Service() {

    companion object {
        private const val TAG = "AppExplorer"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_ID_STUCK = 2002
        private const val CHANNEL_ID = "app_explorer_channel"

        const val ACTION_START = "com.visualmapper.companion.START_EXPLORATION"
        const val ACTION_STOP = "com.visualmapper.companion.STOP_EXPLORATION"
        const val ACTION_PAUSE = "com.visualmapper.companion.PAUSE_EXPLORATION"
        const val ACTION_RESUME = "com.visualmapper.companion.RESUME_EXPLORATION"

        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_CONFIG = "exploration_config"

        private var instance: AppExplorerService? = null
        fun getInstance(): AppExplorerService? = instance

        /**
         * Get the gesture executor for external use (e.g., NavigationGuide)
         */
        fun getGestureExecutorInstance(): GestureExecutor? = instance?.getGestureExecutor()

        // =========================================================================
        // SENSITIVE APP DETECTION - Delegated to SensitiveAppDetector
        // =========================================================================

        /**
         * Check if an app is sensitive (blacklisted).
         * Delegates to SensitiveAppDetector for modularity.
         */
        fun isSensitiveApp(packageName: String): Boolean =
            SensitiveAppDetector.isSensitiveApp(packageName)

        /**
         * INTELLIGENT detection using permissions and metadata.
         * Delegates to SensitiveAppDetector for modularity.
         */
        fun isSensitiveAppIntelligent(context: Context, packageName: String): Boolean =
            SensitiveAppDetector.isSensitiveAppIntelligent(context, packageName)

        /**
         * Check if training mode is enabled (Allow All Apps)
         */
        fun isTrainingModeEnabled(context: Context): Boolean =
            SensitiveAppDetector.isTrainingModeEnabled(context)

        // === STOP BUTTON FIX ===
        // Static volatile flag to signal stop across ALL service instances.
        // When Android recreates the service, the new instance may have explorationJob=null
        // but the OLD instance continues running. This flag ensures ALL instances stop.
        @Volatile
        private var shouldStop = false

        // Observable exploration state
        private val _explorationState = MutableStateFlow<ExplorationState?>(null)
        val explorationState: StateFlow<ExplorationState?> = _explorationState

        private val _currentScreen = MutableStateFlow<ExploredScreen?>(null)
        val currentScreen: StateFlow<ExploredScreen?> = _currentScreen

        private val _progress = MutableStateFlow(ExplorationProgress())
        val progress: StateFlow<ExplorationProgress> = _progress
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var explorationJob: Job? = null

    private var state: ExplorationState? = null
    private val screenTransitions = mutableListOf<ScreenTransition>()
    private val navigationStack = ArrayDeque<String>() // Stack of screen IDs for backtracking
    // Element Queue Manager (delegated for modularity)
    private val visitedNavigationTabs = mutableSetOf<String>() // Bottom nav tabs we've clicked

    // Learning stores for enhanced sensor/action generation
    private val feedbackStore by lazy { FeedbackStore.getInstance(applicationContext) }
    private val learningStore by lazy { LearningStore.getInstance(applicationContext) }
    private val appMapStore by lazy { AppMapStore.getInstance(applicationContext) }

    // Self-diagnostics for feedback on limitations
    private val diagnostics by lazy { SelfDiagnostics.getInstance(applicationContext) }

    // === COVERAGE TRACKING (Delegated to CoverageTracker) ===
    private val coverageTracker = CoverageTracker()
    private val coverageMetrics: CoverageMetrics get() = coverageTracker.metrics
    private val screenElementStats: MutableMap<String, ScreenElementStats> get() = coverageTracker.screenStats
    private val explorationFrontier: List<ExplorationFrontierItem> get() = coverageTracker.explorationFrontier

    // === FLOW GENERATION (Phase 2.3) ===
    private var pendingGeneratedFlow: String? = null

    // Screen dimensions for position-based exclusion
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400

    // Track if system BACK closes the app - if so, prefer UI back buttons
    private var systemBackClosesApp: Boolean = false
    private var detectedBackButtonId: String? = null

    // === STUCK DETECTION ===
    // Track consecutive "no effect" taps to detect when we're stuck on a non-navigable screen
    private var consecutiveNoEffectTaps = 0
    private var lastScreenIdForStuckDetection: String? = null
    private val MAX_CONSECUTIVE_NO_EFFECT = 5  // After this many, try to escape

    // Track consecutive failed escape attempts - ask user for help after threshold
    private var consecutiveEscapeFailures = 0
    private val MAX_ESCAPE_FAILURES_BEFORE_HELP = 2  // Ask user after 2 failed escape attempts

    // Track consecutive app relaunch attempts to prevent infinite relaunch loops
    private var consecutiveRelaunchAttempts = 0
    private val MAX_CONSECUTIVE_RELAUNCHES = 3  // Stop exploration after this many consecutive relaunches

    // === CLEAN RESTART RECOVERY ===
    // Track last iteration that discovered new elements (for coverage plateau detection)
    private var lastDiscoveryIteration = 0
    private val COVERAGE_PLATEAU_THRESHOLD = 20  // Iterations without discoveries before restart
    private val STUCK_RESTART_THRESHOLD = 15  // stuckCounter threshold to try restart

    // Cache last known activity to prevent "unknown" activity causing different screen IDs
    private var lastKnownActivity: String? = null

    // System insets for accurate position-based exclusion
    private var statusBarHeight: Int = 80
    private var navBarHeight: Int = 150

    // Cached PendingIntents for notification (prevents stop button from breaking)
    private var stopPendingIntent: PendingIntent? = null
    private var contentPendingIntent: PendingIntent? = null

    // Floating overlay for progress feedback
    private var explorationOverlay: ExplorationOverlay? = null
    private var overlayShowing = false

    // Q-Learning for smart exploration (Machine Learning)
    private var qLearning: ExplorationQLearning? = null

    // Element Priority Calculator (delegated for modularity)
    private val priorityCalculator: ElementPriorityCalculator by lazy {
        ElementPriorityCalculator(qLearning)
    }

    // Element Queue Manager (delegated for modularity)
    private val queueManager: ElementQueueManager by lazy {
        ElementQueueManager(qLearning, priorityCalculator)
    }

    // MQTT Publishing for ML training - extracted for modularity
    private val explorationPublisher by lazy { ExplorationPublisher(this) }

    // Access Control System
    private lateinit var accessManager: AccessLevelManager
    private lateinit var goModeManager: GoModeManager
    private lateinit var blockerHandler: BlockerHandler
    private lateinit var auditLog: ExplorationAuditLog

    // Gesture Execution (delegated for modularity)
    private lateinit var gestureExecutor: GestureExecutor

    // Tap Animation Overlay - shows visual feedback of where taps occur
    private lateinit var tapAnimationOverlay: TapAnimationOverlay

    // Human-in-the-Loop Manager (modularized)
    // Handles intent visualization, veto, yield protocol, and imitation learning
    private lateinit var humanInLoopManager: HumanInLoopManager

    // === PHASE 1 REFACTOR: New modular components ===

    // State Machine for exploration lifecycle (Phase 1.3)
    // Replaces implicit state management with explicit, testable state machine
    private val stateMachine: ExplorationStateMachine by lazy {
        ExplorationStateMachine().also { sm ->
            sm.setOnStateChangedListener { oldState, newState, event ->
                Log.i(TAG, "StateMachine: $oldState -> $newState (event: $event)")
                // Update progress for UI using proper ExplorationStatus enum
                _progress.value = _progress.value.copy(
                    status = when (newState) {
                        ExplorationStateMachine.State.IDLE -> ExplorationStatus.NOT_STARTED
                        ExplorationStateMachine.State.INITIALIZING -> ExplorationStatus.IN_PROGRESS
                        ExplorationStateMachine.State.EXPLORING -> ExplorationStatus.IN_PROGRESS
                        ExplorationStateMachine.State.PAUSED -> ExplorationStatus.PAUSED
                        ExplorationStateMachine.State.STUCK -> ExplorationStatus.IN_PROGRESS
                        ExplorationStateMachine.State.COMPLETING -> ExplorationStatus.IN_PROGRESS
                        ExplorationStateMachine.State.COMPLETED -> ExplorationStatus.COMPLETED
                    }
                )
            }
        }
    }

    // Screen Navigator for navigation stack management (Phase 1.4)
    // Replaces navigationStack ArrayDeque with proper screen tracking
    private val screenNavigator: ScreenNavigator by lazy {
        ScreenNavigator(maxStackDepth = 20, maxVisitsPerScreen = 5)
    }

    // Stuck Recovery Strategy for escalating escape attempts (Phase 1.5)
    // Replaces attemptEscapeFromStuckScreen with modular strategy pattern
    private val stuckRecovery: StuckRecoveryStrategy by lazy {
        StuckRecoveryStrategy(screenWidth, screenHeight)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        // Get actual screen dimensions
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
            Log.i(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get screen dimensions, using defaults", e)
        }

        // Detect actual system insets (nav bar and status bar heights)
        try {
            val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (statusBarId > 0) {
                statusBarHeight = resources.getDimensionPixelSize(statusBarId)
            }
            val navBarId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (navBarId > 0) {
                navBarHeight = resources.getDimensionPixelSize(navBarId)
            }
            Log.i(TAG, "System insets: statusBar=${statusBarHeight}px, navBar=${navBarHeight}px")
        } catch (e: Exception) {
            Log.w(TAG, "Could not get system insets, using defaults", e)
        }

        // Initialize cached PendingIntents (prevents stop button from breaking)
        val contentIntent = Intent(this, MainContainerActivity::class.java)
        contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AppExplorerService::class.java).apply {
            action = ACTION_STOP
        }
        stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Initialize Access Control System
        accessManager = AccessLevelManager(this)
        goModeManager = GoModeManager(this)
        accessManager.goModeManager = goModeManager
        blockerHandler = BlockerHandler(accessManager, goModeManager)
        auditLog = ExplorationAuditLog(this)
        gestureExecutor = GestureExecutor(this, accessManager, auditLog, goModeManager)
        tapAnimationOverlay = TapAnimationOverlay(this)

        // Human-in-the-Loop Manager (modularized)
        humanInLoopManager = HumanInLoopManager(this, qLearning, diagnostics)
        humanInLoopManager.onYieldStarted = { clickBounds, clickInfo ->
            handleUserTakeover(clickBounds, clickInfo)
        }
        humanInLoopManager.onYieldEnded = {
            handleUserInactivityTimeout()
        }

        // Note: Overlay is now created in startExploration() to avoid theme issues
        // during service initialization

        Log.i(TAG, "AppExplorerService created with access control (level=${accessManager.globalAccessLevel.displayName})")
    }

    private fun hasOverlayPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(applicationContext)
        } else {
            true
        }
    }

    // =========================================================================
    // Accessibility Service Health Check
    // =========================================================================

    /**
     * Wait for accessibility service to become available.
     *
     * Android can destroy and recreate the accessibility service at any time,
     * especially when the user switches apps. This function waits for the service
     * to be available and have a valid root window before continuing.
     *
     * @param maxWaitMs Maximum time to wait in milliseconds (default 10 seconds)
     * @return true if service became available, false if timeout
     */
    private suspend fun waitForAccessibilityService(maxWaitMs: Long = 10000): Boolean {
        val startTime = System.currentTimeMillis()
        var attempts = 0

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            attempts++

            // Check if we should stop
            if (shouldStop) {
                Log.i(TAG, "waitForAccessibilityService: shouldStop flag detected")
                return false
            }

            val service = VisualMapperAccessibilityService.getInstance()
            if (service != null) {
                val root = service.rootInActiveWindow
                if (root != null) {
                    if (attempts > 1) {
                        Log.i(TAG, "Accessibility service available after ${System.currentTimeMillis() - startTime}ms ($attempts attempts)")
                    }
                    return true
                }
            }

            // Log progress every 2 seconds (every 4 attempts at 500ms intervals)
            if (attempts % 4 == 0) {
                Log.w(TAG, "Waiting for accessibility service... (${System.currentTimeMillis() - startTime}ms elapsed)")
            }

            delay(500)
        }

        Log.e(TAG, "Accessibility service not available after ${maxWaitMs}ms ($attempts attempts)")
        return false
    }


    /**
     * Parse ExplorationConfig from JSON string
     */
    private fun parseExplorationConfig(configJson: String?): ExplorationConfig {
        // Check if deep exploration is enabled in preferences
        val prefs = getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val deepExplorationEnabled = prefs.getBoolean("deep_exploration", false)

        // Read multi-pass settings
        val maxPasses = prefs.getInt("max_exploration_passes", 1)
        val autoRunPasses = prefs.getBoolean("auto_run_passes", false)
        Log.i(TAG, "Multi-pass settings: maxPasses=$maxPasses, autoRunPasses=$autoRunPasses")

        // Read selected exploration strategy from settings
        val strategyString = prefs.getString("exploration_strategy", "ADAPTIVE") ?: "ADAPTIVE"
        val selectedStrategy = try {
            ExplorationStrategy.valueOf(strategyString)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown strategy '$strategyString', using ADAPTIVE")
            ExplorationStrategy.ADAPTIVE
        }
        Log.i(TAG, "Strategy selection: strategy=$strategyString, using ${selectedStrategy.name}")

        if (configJson.isNullOrEmpty()) {
            Log.d(TAG, "No config provided, using defaults (deep=$deepExplorationEnabled, strategy=$strategyString)")
            val config = if (deepExplorationEnabled) {
                ExplorationConfig.forDeepExploration(targetCoverage = 0.90f).copy(
                    strategy = selectedStrategy,
                    maxPasses = maxPasses
                )
            } else {
                ExplorationConfig(strategy = selectedStrategy, maxPasses = maxPasses)
            }
            Log.i(TAG, "=== EXPLORATION CONFIG (default) ===")
            Log.i(TAG, "  goal=${config.goal}, strategy=${config.strategy}, enableBacktrack=${config.enableSystematicBacktracking}, maxPasses=${config.maxPasses}")
            return config
        }

        try {
            val json = JSONObject(configJson)

            // Parse exploration mode
            val modeStr = json.optString("mode", "NORMAL").uppercase()
            val mode = try {
                ExplorationMode.valueOf(modeStr)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Unknown mode '$modeStr', using NORMAL")
                ExplorationMode.NORMAL
            }

            // Parse exploration goal (or use preference)
            val goalStr = json.optString("goal", "").uppercase()
            val goal = if (goalStr.isNotEmpty()) {
                try {
                    ExplorationGoal.valueOf(goalStr)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Unknown goal '$goalStr', checking preference")
                    if (deepExplorationEnabled) ExplorationGoal.COMPLETE_COVERAGE else ExplorationGoal.QUICK_SCAN
                }
            } else {
                if (deepExplorationEnabled) ExplorationGoal.COMPLETE_COVERAGE else ExplorationGoal.QUICK_SCAN
            }

            // Use deep exploration factory if goal is COMPLETE_COVERAGE
            if (goal == ExplorationGoal.COMPLETE_COVERAGE) {
                val targetCoverage = json.optDouble("targetCoverage", 0.90).toFloat()
                val config = ExplorationConfig.forDeepExploration(targetCoverage).copy(
                    mode = mode,
                    strategy = selectedStrategy,
                    maxPasses = maxPasses,
                    actionDelay = json.optLong("actionDelay", 1000),
                    transitionWait = json.optLong("transitionWait", 2500),
                    scrollDelay = json.optLong("scrollDelay", 500),
                    exploreDialogs = json.optBoolean("exploreDialogs", true),
                    exploreMenus = json.optBoolean("exploreMenus", true)
                )
                Log.i(TAG, "=== EXPLORATION CONFIG (COMPLETE_COVERAGE) ===")
                Log.i(TAG, "  goal=${config.goal}, strategy=${config.strategy}, targetCoverage=${(config.targetCoverage * 100).toInt()}%")
                Log.i(TAG, "  enableSystematicBacktracking=${config.enableSystematicBacktracking}")
                Log.i(TAG, "  backtrackAfterNewScreen=${config.backtrackAfterNewScreen}")
                Log.i(TAG, "  maxDurationForCoverage=${config.maxDurationForCoverage / 60000}min, maxPasses=${config.maxPasses}")
                return config
            }

            return ExplorationConfig(
                mode = mode,
                goal = goal,
                strategy = selectedStrategy,
                maxPasses = maxPasses,
                maxDepth = json.optInt("maxDepth", 5),
                maxScreens = json.optInt("maxScreens", 50),
                maxElements = json.optInt("maxElements", 500),
                maxDurationMs = json.optLong("maxDurationMs", 10 * 60 * 1000L),
                maxLaunchRetries = json.optInt("maxLaunchRetries", 3),
                actionDelay = json.optLong("actionDelay", 1000),
                transitionWait = json.optLong("transitionWait", 2500),
                scrollDelay = json.optLong("scrollDelay", 500),
                maxScrollsPerContainer = json.optInt("maxScrollsPerContainer", 5),
                exploreDialogs = json.optBoolean("exploreDialogs", true),
                exploreMenus = json.optBoolean("exploreMenus", true),
                backtrackAfterNewScreen = json.optBoolean("backtrackAfterNewScreen", true),
                stabilizationWait = json.optLong("stabilizationWait", 3000)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse exploration config: ${e.message}")
            return if (deepExplorationEnabled) {
                ExplorationConfig.forDeepExploration(targetCoverage = 0.90f).copy(strategy = selectedStrategy, maxPasses = maxPasses)
            } else {
                ExplorationConfig(strategy = selectedStrategy, maxPasses = maxPasses)
            }
        }
    }

    /**
     * Show the exploration overlay with progress info.
     * Called when exploration actually starts (not in onCreate) to avoid theme issues.
     */
    private fun showOverlay() {
        Log.i(TAG, "=== showOverlay() called ===")

        // Check if overlay is ACTUALLY showing (not just the flag)
        if (overlayShowing && explorationOverlay != null) {
            Log.d(TAG, "Overlay already showing, skipping")
            return
        }

        // Reset state if flag was stale
        if (overlayShowing && explorationOverlay == null) {
            Log.w(TAG, "overlayShowing was true but overlay is null - resetting state")
            overlayShowing = false
        }

        val hasPermission = hasOverlayPermission()
        Log.i(TAG, "Overlay permission check: $hasPermission")

        if (!hasPermission) {
            Log.w(TAG, "Overlay permission not granted - overlay will not be shown")
            android.widget.Toast.makeText(
                applicationContext,
                "Overlay permission needed to show progress. Grant in Settings > Apps > Visual Mapper > Display over other apps",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            explorationOverlay = ExplorationOverlay(this)
            explorationOverlay?.show()
            overlayShowing = true
            Log.i(TAG, "Exploration overlay shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Could not show exploration overlay", e)
            android.widget.Toast.makeText(
                applicationContext,
                "Could not show progress overlay: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "=== onStartCommand: action=${intent?.action}, explorationJob=${explorationJob?.isActive} ===")

        // Must call startForeground immediately for foreground services on Android 8+
        if (intent?.action == ACTION_START) {
            startForeground(NOTIFICATION_ID, createNotification("Initializing exploration..."))
        }

        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "=== START_EXPLORATION received ===")
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                val configJson = intent.getStringExtra(EXTRA_CONFIG)
                if (packageName != null) {
                    // Parse config from JSON if provided
                    val config = parseExplorationConfig(configJson)
                    Log.i(TAG, "Config: maxElements=${config.maxElements}, maxScreens=${config.maxScreens}, maxDurationMs=${config.maxDurationMs}")

                    // Reset overlay state before new exploration (in case previous didn't clean up)
                    if (overlayShowing || explorationOverlay != null) {
                        Log.w(TAG, "Resetting stale overlay state before new exploration")
                        explorationOverlay?.hide()
                        explorationOverlay = null
                        overlayShowing = false
                    }

                    // Reset progress before starting new exploration (prevents new overlay from seeing old COMPLETED status)
                    _progress.value = ExplorationProgress(
                        screensExplored = 0,
                        elementsExplored = 0,
                        queueSize = 0,
                        status = ExplorationStatus.IN_PROGRESS
                    )
                    Log.d(TAG, "Reset progress to IN_PROGRESS for new exploration")

                    // Show overlay now that we're starting exploration
                    showOverlay()
                    startExploration(packageName, config)
                } else {
                    Log.e(TAG, "No package name provided")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "=== STOP_EXPLORATION received === job active: ${explorationJob?.isActive}")
                stopExploration()
            }
            ACTION_PAUSE -> pauseExploration()
            ACTION_RESUME -> resumeExploration()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        explorationJob?.cancel()
        scope.cancel()

        // Hide overlay
        explorationOverlay?.hide()
        explorationOverlay = null

        // === PHASE 1.8-1.9: Proper resource cleanup ===
        // Clean up human-in-loop manager (releases user interaction detector)
        if (::humanInLoopManager.isInitialized) {
            humanInLoopManager.destroy()
        }

        // Reset state machine
        stateMachine.reset()

        // Reset screen navigator
        screenNavigator.reset()

        // Reset stuck recovery
        stuckRecovery.reset()

        instance = null
        Log.i(TAG, "AppExplorerService destroyed")
    }

    // =========================================================================
    // Pre-Flight Checks
    // =========================================================================

    /**
     * Perform pre-flight checks before starting exploration.
     * Warns user about any issues that might affect ML training.
     */
    private fun performPreFlightChecks() {
        Log.i(TAG, "=== PRE-FLIGHT CHECKS ===")
        val warnings = mutableListOf<String>()

        try {
            val app = application as? VisualMapperApp
            if (app == null) {
                warnings.add("App context not available")
            } else {
                val mqttManager = app.mqttManager

                // Check MQTT connection
                val mqttState = mqttManager.connectionState.value
                Log.i(TAG, "  MQTT Connection: $mqttState")
                if (mqttState != MqttManager.ConnectionState.CONNECTED) {
                    warnings.add("MQTT not connected - ML training data won't be sent")
                }

                // Check ML training mode
                val mlEnabled = mqttManager.isMlTrainingEnabled()
                Log.i(TAG, "  ML Training Enabled: $mlEnabled")
                if (!mlEnabled) {
                    warnings.add("ML Training mode disabled - no data will be collected")
                }
            }

            // Check accessibility service
            val accessibilityService = VisualMapperAccessibilityService.getInstance()
            Log.i(TAG, "  Accessibility Service: ${accessibilityService != null}")
            if (accessibilityService == null) {
                warnings.add("Accessibility service not running")
            }

            // Log and show warnings
            if (warnings.isNotEmpty()) {
                Log.w(TAG, "Pre-flight warnings: ${warnings.joinToString(", ")}")
                // Show first warning as toast (don't spam user with multiple)
                showToast("Warning: ${warnings.first()}")
            } else {
                Log.i(TAG, "Pre-flight checks PASSED - all systems operational")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Pre-flight check error", e)
        }
    }

    // =========================================================================
    // Exploration Control
    // =========================================================================

    private fun startExploration(packageName: String, config: ExplorationConfig = ExplorationConfig()) {
        Log.i(TAG, "Starting exploration of: $packageName")

        // === STATE MACHINE: Signal exploration start ===
        stateMachine.processEvent(ExplorationStateMachine.Event.START_REQUESTED)

        // === PRE-FLIGHT CHECKS ===
        performPreFlightChecks()

        // === ACCESS CONTROL CHECK ===
        val accessLevel = accessManager.getAccessLevelForApp(packageName)
        Log.i(TAG, "Access level for $packageName: ${accessLevel.displayName} (${accessLevel.level})")

        // Start new audit session
        auditLog.startNewSession()
        auditLog.logCustomAction(
            actionName = "exploration_start",
            targetApp = packageName,
            accessLevel = accessLevel,
            goModeActive = goModeManager.isActive(),
            success = true,
            metadata = mapOf(
                "maxDepth" to config.maxDepth.toString(),
                "maxScreens" to config.maxScreens.toString(),
                "maxTime" to (config.maxDurationMs / 1000).toString()
            )
        )

        // === STOP BUTTON FIX ===
        // Reset stop flag when starting new exploration
        shouldStop = false
        Log.i(TAG, "Reset shouldStop=false for new exploration")

        // Initialize state with provided config
        state = ExplorationState(
            packageName = packageName,
            status = ExplorationStatus.IN_PROGRESS,
            config = config
        )
        _explorationState.value = state

        // Clear tracking from previous exploration
        queueManager.reset()
        visitedNavigationTabs.clear()
        navigationStack.clear()
        screenTransitions.clear()

        // === PHASE 1.6b: Reset screen navigator (dual-write) ===
        screenNavigator.reset()
        stuckRecovery.reset()

        // Reset coverage tracking for new exploration
        coverageTracker.reset()

        // Subscribe to Q-table updates from ML training server
        subscribeToServerQTableUpdates()

        // Update notification with package name
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification("Exploring $packageName..."))

        // Launch exploration coroutine
        explorationJob = scope.launch {
            try {
                runExploration(packageName)
            } catch (e: CancellationException) {
                Log.i(TAG, "Exploration cancelled")
                state?.status = ExplorationStatus.CANCELLED
                publishExplorationStatusToMqtt("cancelled", "Exploration was cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Exploration error", e)
                state?.status = ExplorationStatus.ERROR
                publishExplorationStatusToMqtt("failed", "Error: ${e.message}")
            } finally {
                state?.endTime = System.currentTimeMillis()
                _explorationState.value = state
                updateProgress()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun stopExploration() {
        Log.i(TAG, "=== stopExploration called === job active: ${explorationJob?.isActive}, instance: ${instance}, state: ${state != null}")

        // === STATE MACHINE: Signal stop request ===
        stateMachine.processEvent(ExplorationStateMachine.Event.STOP_REQUESTED)

        // === STOP BUTTON FIX ===
        // Set static flag to signal ALL service instances to stop
        shouldStop = true
        Log.i(TAG, "Set shouldStop=true to signal all instances")

        // === NON-DESTRUCTIVE MODE: Revert toggles before stopping ===
        if (state?.config?.nonDestructive == true && !state?.changedToggles.isNullOrEmpty()) {
            val toggleCount = state?.changedToggles?.size ?: 0
            Log.i(TAG, "Non-destructive mode: Reverting $toggleCount toggles before stopping")

            // Launch coroutine to revert toggles, then complete cleanup
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    revertChangedToggles()
                    Log.i(TAG, "Toggle revert completed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error reverting toggles: ${e.message}", e)
                }
                // Continue with cleanup after revert
                completeStopCleanup()
            }
            return // Exit - cleanup will be done in coroutine
        }

        // No toggles to revert, do immediate cleanup
        completeStopCleanup()
    }

    private fun completeStopCleanup() {
        Log.i(TAG, "=== completeStopCleanup called ===")

        // Cancel job if active
        if (explorationJob?.isActive == true) {
            Log.i(TAG, "Cancelling active exploration job")
            explorationJob?.cancel()
        }
        explorationJob = null

        // Update state - ensure we emit even if state is null
        // Use STOPPED for user-initiated stop (successful partial completion)
        if (state != null) {
            state?.status = ExplorationStatus.STOPPED
            state?.endTime = System.currentTimeMillis()
            _explorationState.value = state
            Log.i(TAG, "Emitted STOPPED state (user stopped early)")
        } else {
            // Create a minimal stopped state to notify observers
            val stoppedState = ExplorationState(
                packageName = "",
                config = ExplorationConfig(),
                status = ExplorationStatus.STOPPED
            )
            _explorationState.value = stoppedState
            Log.i(TAG, "Emitted minimal STOPPED state (original state was null)")
        }

        // Update progress safely - emit STOPPED status
        try {
            _progress.value = ExplorationProgress(
                screensExplored = state?.exploredScreens?.size ?: 0,
                elementsExplored = state?.visitedElements?.size ?: 0,
                queueSize = 0,
                status = ExplorationStatus.STOPPED
            )
            Log.i(TAG, "Emitted STOPPED progress")
        } catch (e: Exception) {
            Log.w(TAG, "Error updating progress", e)
        }

        // === ALWAYS PUBLISH LOGS (even for incomplete explorations) ===
        // Training data is valuable even from partial explorations
        try {
            publishExplorationLogsToMqtt()
            Log.i(TAG, "Published exploration logs (incomplete exploration)")
        } catch (e: Exception) {
            Log.w(TAG, "Error publishing exploration logs", e)
        }

        // Hide overlay safely
        try {
            explorationOverlay?.hide()
            explorationOverlay = null
            overlayShowing = false
        } catch (e: Exception) {
            Log.w(TAG, "Error hiding overlay", e)
        }

        // Explicitly cancel all notifications
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(NOTIFICATION_ID)
            notificationManager.cancel(NOTIFICATION_ID_STUCK)
            Log.i(TAG, "All notifications cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "Error cancelling notifications", e)
        }

        // Launch ExplorationResultActivity to show partial results
        // (even when stopped early, user should see what was found)
        val currentState = state
        if (currentState != null && currentState.exploredScreens.isNotEmpty()) {
            try {
                val resultIntent = Intent(this, ExplorationResultActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ExplorationResultActivity.EXTRA_PACKAGE_NAME, currentState.packageName)
                }
                startActivity(resultIntent)
                Log.i(TAG, "Launched ExplorationResultActivity for partial results")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ExplorationResultActivity", e)
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // Clear instance
        instance = null
        Log.i(TAG, "=== Exploration stopped - service stopping ===")
    }

    /**
     * Start another exploration pass on the same app.
     * Preserves: exploredScreens, visitedElements, Q-learning, sensors/actions
     * Resets: queue tracking, navigation stack
     * Will stop if: 100% coverage reached, or max passes reached
     */
    fun startAnotherPass(forceRun: Boolean = false) {
        val currentState = state
        if (currentState == null) {
            Log.e(TAG, "Cannot start another pass - no exploration state")
            return
        }

        val config = currentState.config
        val currentCoverage = coverageTracker.metrics.overallCoverage

        // Check if we've reached target coverage - skip for manual runs (forceRun=true)
        // BUT: Don't exit if there are still unexplored branches (screens with unvisited elements)
        if (!forceRun && config.stopAtTargetCoverage && currentCoverage >= config.targetCoverage) {
            val unexploredBranches = coverageTracker.metrics.unexploredBranches
            if (unexploredBranches == 0) {
                Log.i(TAG, "Target coverage ${(config.targetCoverage * 100).toInt()}% reached AND no unexplored branches - stopping")
                showToast("Target coverage reached! No more passes needed.")
                return
            } else {
                Log.i(TAG, "Coverage at ${(currentCoverage * 100).toInt()}% but $unexploredBranches unexplored branches remain - continuing")
            }
        }

        // Check max passes (0 = unlimited) - skip for manual runs (forceRun=true)
        val nextPassNumber = currentState.passNumber + 1
        if (!forceRun && config.maxPasses > 0 && nextPassNumber > config.maxPasses) {
            Log.i(TAG, "Max passes (${config.maxPasses}) reached - stopping")
            showToast("Max passes reached (${config.maxPasses})")
            return
        }

        Log.i(TAG, "=== Starting exploration pass $nextPassNumber ===")
        Log.i(TAG, "Preserving: ${currentState.exploredScreens.size} screens, ${currentState.visitedElements.size} visited elements")
        Log.i(TAG, "Current coverage: ${(currentCoverage * 100).toInt()}%")

        // === PRESERVE FROM PREVIOUS PASS ===
        // - exploredScreens (already discovered screens)
        // - visitedElements (already tapped elements)
        // - Q-learning (accumulated knowledge)
        // - changedToggles (for non-destructive revert at end)

        // === RESET FOR NEW PASS ===
        shouldStop = false
        currentState.passNumber = nextPassNumber
        currentState.status = ExplorationStatus.IN_PROGRESS
        currentState.startTime = System.currentTimeMillis()
        currentState.endTime = null

        // Clear queue tracking to allow re-queuing of partially explored screens
        queueManager.reset()
        navigationStack.clear()

        // Clear the exploration queue - we'll rebuild it
        currentState.explorationQueue.clear()

        _explorationState.value = currentState

        // Update notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification("Pass $nextPassNumber: Exploring ${currentState.packageName}..."))

        // Show overlay
        try {
            if (explorationOverlay == null) {
                explorationOverlay = ExplorationOverlay(this)
            }
            explorationOverlay?.show()
            overlayShowing = true
        } catch (e: Exception) {
            Log.w(TAG, "Could not show overlay", e)
        }

        // Launch new pass
        explorationJob = scope.launch {
            try {
                runAnotherPass(currentState.packageName, currentState)
            } catch (e: CancellationException) {
                Log.i(TAG, "Pass $nextPassNumber cancelled")
                currentState.status = ExplorationStatus.CANCELLED
            } catch (e: Exception) {
                Log.e(TAG, "Pass $nextPassNumber error", e)
                currentState.status = ExplorationStatus.ERROR
            } finally {
                currentState.endTime = System.currentTimeMillis()
                _explorationState.value = currentState
                updateProgress()
            }
        }
    }

    /**
     * Run another exploration pass, reusing existing state.
     */
    private suspend fun runAnotherPass(packageName: String, existingState: ExplorationState) {
        val config = existingState.config
        Log.i(TAG, "=== Running pass ${existingState.passNumber} ===")

        // Wait for accessibility service to be available (may be temporarily disconnected during activity transition)
        var accessibilityService = VisualMapperAccessibilityService.getInstance()
        var retryCount = 0
        val maxRetries = 10
        while (accessibilityService == null && retryCount < maxRetries) {
            retryCount++
            Log.w(TAG, "Accessibility service not available, waiting... (attempt $retryCount/$maxRetries)")
            delay(1000) // Wait 1 second for service to reconnect
            accessibilityService = VisualMapperAccessibilityService.getInstance()
        }
        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service not available after $maxRetries retries - cannot continue pass")
            showToast("Accessibility service disconnected - please restart exploration")
            return
        }
        Log.i(TAG, "Accessibility service available after $retryCount retries")

        // Relaunch app to home screen for fresh start
        launchApp(packageName, forceRestart = true)
        delay(config.transitionWait)

        // Capture home screen
        refreshScreenDimensions()
        var currentScreen = captureCurrentScreen()

        // === DETECT LOGIN/LOW-PRIORITY SCREENS ===
        // If we landed on a login or settings screen, try ONE back press to escape
        // IMPORTANT: Don't force restart - that can cause logout!
        if (currentScreen != null && queueManager.isLoginScreen(currentScreen)) {
            Log.w(TAG, "Pass ${existingState.passNumber}: On login screen '${currentScreen.activity}' - trying back to escape")

            // Try ONE back press (gentle escape)
            val accessibilityService = VisualMapperAccessibilityService.getInstance()
            accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(1500)

            // Capture new screen
            val afterBackScreen = captureCurrentScreen()
            if (afterBackScreen != null && !queueManager.isLoginScreen(afterBackScreen)) {
                Log.i(TAG, "Successfully escaped login screen to: ${afterBackScreen.activity}")
                currentScreen = afterBackScreen
            } else {
                Log.w(TAG, "Could not escape login screen - app may require authentication")
                // Don't try force restart - that causes logout. Just continue with what we have.
            }
        }

        // Now process the screen we landed on
        if (currentScreen != null) {
            navigationStack.addFirst(currentScreen.screenId)
            _currentScreen.value = currentScreen

            // Check if this is a new screen
            if (!existingState.exploredScreens.containsKey(currentScreen.screenId)) {
                existingState.exploredScreens[currentScreen.screenId] = currentScreen
                Log.i(TAG, "Pass ${existingState.passNumber}: Found new screen: ${currentScreen.screenId}")
            }
        }

        // Re-queue elements from partially explored screens
        reQueuePartiallyExploredScreens(existingState)

        // If queue is empty, try to find unexplored areas
        if (existingState.explorationQueue.isEmpty()) {
            Log.i(TAG, "Queue empty after re-queuing - checking for unexplored areas")
            val frontier = coverageTracker.getExplorationFrontier(10)
            for (screenId in frontier) {
                val screen = existingState.exploredScreens[screenId]
                if (screen != null) {
                    queueClickableElements(screen)
                }
            }
        }

        if (existingState.explorationQueue.isEmpty()) {
            // === CHECK IF WE ONLY FOUND LOGIN SCREENS (actual blockers) ===
            // Settings screens are low-priority but NOT blockers
            val loginScreenCount = existingState.exploredScreens.count { (_, screen) ->
                queueManager.isLoginScreen(screen)
            }
            val nonLoginScreenCount = existingState.exploredScreens.size - loginScreenCount

            Log.i(TAG, "Queue empty - screens: ${existingState.exploredScreens.size} total, $nonLoginScreenCount non-login, $loginScreenCount login")

            // Only report "stuck" if we ONLY found login screens (actual blockers)
            if (nonLoginScreenCount == 0 && loginScreenCount > 0) {
                Log.w(TAG, "=== STUCK ON LOGIN SCREENS - App requires authentication ===")
                showToast("App requires login - could not find explorable content")
                existingState.status = ExplorationStatus.STOPPED
                existingState.issues.add(ExplorationIssue(
                    screenId = existingState.exploredScreens.keys.firstOrNull() ?: "unknown",
                    elementId = null,
                    issueType = IssueType.BLOCKER_SCREEN,
                    description = "Only found login screens - app requires authentication"
                ))
                finishMultiPassExploration()
                return
            }

            Log.i(TAG, "No unexplored elements found - exploration complete!")
            existingState.status = ExplorationStatus.COMPLETED
            showToast("Exploration complete - no new areas to explore")
            finishMultiPassExploration()
            return
        }

        Log.i(TAG, "Queued ${existingState.explorationQueue.size} elements for pass ${existingState.passNumber}")

        // Log initial coverage state
        updateCoverageMetrics()
        Log.i(TAG, "Initial coverage: ${(coverageTracker.metrics.overallCoverage * 100).toInt()}% " +
                "(Elements: ${coverageTracker.metrics.elementsVisited}/${coverageTracker.metrics.totalElementsDiscovered}, " +
                "Screens: ${coverageTracker.metrics.screensFullyExplored}/${coverageTracker.metrics.totalScreensDiscovered}, " +
                "Target: ${(config.targetCoverage * 100).toInt()}%)")

        // === MULTI-PASS EXPLORATION LOOP ===
        var iterations = 0
        val maxIterations = config.maxElements

        while (existingState.status == ExplorationStatus.IN_PROGRESS && iterations < maxIterations && !shouldStop) {
            iterations++

            if (shouldStop) {
                Log.i(TAG, "Pass ${existingState.passNumber}: Stop flag detected")
                break
            }

            // Check if still in target app
            if (!ensureInTargetApp()) {
                Log.e(TAG, "Pass ${existingState.passNumber}: Lost target app")
                existingState.status = ExplorationStatus.ERROR
                break
            }

            // Timeout check
            val elapsedMs = System.currentTimeMillis() - (existingState.startTime ?: System.currentTimeMillis())
            if (elapsedMs > config.maxDurationMs) {
                Log.w(TAG, "Pass ${existingState.passNumber}: Timeout reached")
                existingState.status = ExplorationStatus.COMPLETED
                break
            }

            // Get next target from queue
            var nextTarget = existingState.explorationQueue.maxByOrNull { it.priority }
            if (nextTarget == null) {
                // Queue empty - try to find screens with unvisited elements before giving up
                var foundMore = false
                for ((screenId, screen) in existingState.exploredScreens) {
                    val hasUnvisited = screen.clickableElements.any { el ->
                        !existingState.visitedElements.contains("$screenId:${el.elementId}")
                    }
                    if (hasUnvisited) {
                        queueClickableElements(screen)
                        foundMore = true
                    }
                }
                if (foundMore) {
                    Log.i(TAG, "Pass ${existingState.passNumber}: Queue was empty, re-queued unvisited elements from ${existingState.exploredScreens.size} screens")
                    nextTarget = existingState.explorationQueue.maxByOrNull { it.priority }
                }
                if (nextTarget == null) {
                    Log.i(TAG, "Pass ${existingState.passNumber}: Queue empty, no unvisited elements - pass complete")
                    break
                }
            }
            existingState.explorationQueue.remove(nextTarget)

            // Process the target
            when (nextTarget.type) {
                ExplorationTargetType.TAP_ELEMENT -> {
                    val cachedScreen = existingState.exploredScreens[nextTarget.screenId]
                    val cachedElement = cachedScreen?.clickableElements?.find { it.elementId == nextTarget.elementId }

                    if (cachedElement != null) {
                        Log.d(TAG, "Pass ${existingState.passNumber}[$iterations]: Verifying ${cachedElement.elementId}")

                        // ROBUST: Verify element before showing ANY overlay
                        val freshScreen = captureCurrentScreen()
                        if (freshScreen == null) {
                            Log.w(TAG, "Pass loop: Cannot capture screen - skipping element")
                            continue
                        }

                        if (freshScreen.screenId != nextTarget.screenId) {
                            Log.w(TAG, "Pass loop: Screen mismatch - expected ${nextTarget.screenId}, on ${freshScreen.screenId}")
                            // Clear all elements from old screen
                            existingState.explorationQueue.removeAll { it.screenId == nextTarget.screenId }
                            continue
                        }

                        // Find element in FRESH screen
                        val freshElement = freshScreen.clickableElements.find { it.elementId == cachedElement.elementId }
                        if (freshElement == null) {
                            Log.w(TAG, "Pass loop: Element ${cachedElement.elementId} not in fresh screen")
                            continue
                        }

                        if (!isElementInLiveAccessibilityTree(freshElement)) {
                            Log.w(TAG, "Pass loop: Element ${cachedElement.elementId} not in live accessibility tree")
                            continue
                        }

                        Log.d(TAG, "Pass ${existingState.passNumber}[$iterations]: Element verified, tapping ${freshElement.elementId}")

                        // === HUMAN-IN-THE-LOOP: Intent Visualization & Veto ===
                        if (humanInLoopManager.isEnabled.value) {
                            // Update current screen for hit-testing (use FRESH screen)
                            humanInLoopManager.setCurrentScreen(freshScreen)

                            // Get Q-value confidence
                            val screenHash = qLearning?.computeScreenHash(freshScreen)
                            val actionKey = qLearning?.getActionKey(freshElement) ?: freshElement.elementId
                            val confidence = screenHash?.let { qLearning?.getQValue(it, actionKey) } ?: 0f

                            // Show intent and wait for possible veto - use FRESH element bounds
                            val bounds = android.graphics.Rect(
                                freshElement.bounds.x,
                                freshElement.bounds.y,
                                freshElement.bounds.x + freshElement.bounds.width,
                                freshElement.bounds.y + freshElement.bounds.height
                            )
                            val elementDesc = freshElement.text?.take(20) ?: freshElement.resourceId ?: freshElement.elementId
                            val vetoed = humanInLoopManager.showIntentAndWaitForVeto(
                                bounds = bounds,
                                confidence = confidence,
                                elementDescription = elementDesc,
                                vetoWindowMs = config.vetoWindowMs
                            )

                            if (vetoed) {
                                Log.i(TAG, "Action VETOED by user: ${freshElement.elementId}")
                                if (screenHash != null) {
                                    humanInLoopManager.recordVeto(screenHash, actionKey)
                                }
                                continue  // Skip this element, move to next
                            }

                            // Register bot tap intent before performing - use FRESH coordinates
                            humanInLoopManager.registerBotTapIntent(freshElement.centerX, freshElement.centerY, freshElement.elementId)
                        }

                        // Use freshElement for all subsequent operations
                        val element = freshElement

                        // Mark as visited
                        val compositeKey = "${nextTarget.screenId}:${element.elementId}"
                        existingState.visitedElements.add(compositeKey)

                        // Perform tap
                        val tapSuccess = performTapWithAccessCheck(
                            element.centerX, element.centerY,
                            element.elementId, nextTarget.screenId, packageName
                        )

                        // Clear bot tap intent after tap
                        if (humanInLoopManager.isEnabled.value) {
                            humanInLoopManager.clearBotTapIntent()
                        }

                        if (tapSuccess) {
                            delay(config.actionDelay)

                            // Capture new screen state
                            val newScreen = captureCurrentScreen()
                            if (newScreen != null && !existingState.exploredScreens.containsKey(newScreen.screenId)) {
                                existingState.exploredScreens[newScreen.screenId] = newScreen
                                queueClickableElements(newScreen)
                                Log.i(TAG, "Pass ${existingState.passNumber}: Discovered new screen: ${newScreen.screenId}")
                            }
                        }
                    }
                }

                ExplorationTargetType.SCROLL_CONTAINER -> {
                    // Perform scroll and capture any new elements
                    nextTarget.bounds?.let { bounds ->
                        val centerX = bounds.x + bounds.width / 2
                        val centerY = bounds.y + bounds.height / 2
                        performScroll(centerX, centerY, ScrollDirection.VERTICAL)
                        delay(config.scrollDelay)
                    }
                }

                else -> {}
            }

            // Update progress
            updateProgress()

            // Update coverage metrics before checking (they're calculated, not tracked incrementally)
            updateCoverageMetrics()

            // Check if we've reached target coverage AND no unexplored branches remain
            val currentCoverage = coverageTracker.metrics.overallCoverage
            if (config.stopAtTargetCoverage && currentCoverage >= config.targetCoverage) {
                val unexploredBranches = coverageTracker.metrics.unexploredBranches
                if (unexploredBranches == 0) {
                    Log.i(TAG, "Pass ${existingState.passNumber}: Target coverage ${(config.targetCoverage * 100).toInt()}% reached AND no unexplored branches! " +
                            "(Elements: ${coverageTracker.metrics.elementsVisited}/${coverageTracker.metrics.totalElementsDiscovered}, " +
                            "Screens: ${coverageTracker.metrics.screensFullyExplored}/${coverageTracker.metrics.totalScreensDiscovered})")
                    existingState.status = ExplorationStatus.COMPLETED
                    break
                } else {
                    Log.d(TAG, "Coverage at ${(currentCoverage * 100).toInt()}% but $unexploredBranches unexplored branches remain - continuing")
                }
            }
        }

        // Pass complete
        existingState.status = ExplorationStatus.COMPLETED
        finishMultiPassExploration()
    }

    /**
     * Finish multi-pass exploration cleanly.
     */
    private fun finishMultiPassExploration() {
        Log.i(TAG, "=== Multi-pass exploration finished ===")
        Log.i(TAG, "Total screens: ${state?.exploredScreens?.size}, Total elements visited: ${state?.visitedElements?.size}")

        // Hide overlay
        explorationOverlay?.hide()
        overlayShowing = false

        // Publish logs for ML training
        publishExplorationLogsToMqtt()

        // Save Q-learning
        qLearning?.saveAll()

        // Update coverage metrics
        coverageTracker.updateMetrics(
            state?.exploredScreens ?: emptyMap(),
            state?.visitedElements ?: emptySet()
        )

        // Mark state as completed and set end time
        state?.status = ExplorationStatus.COMPLETED
        state?.endTime = System.currentTimeMillis()

        _explorationState.value = state
        updateProgress()

        // Generate flow and show results
        val currentState = state
        if (currentState != null) {
            val prefs = getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            val autoSaveFlows = prefs.getBoolean("auto_save_flows", false)

            try {
                val deviceId = getDeviceIdForFlows()
                val flowGenerator = FlowGenerator(currentState, deviceId)
                val flowJson = flowGenerator.generateSensorCollectionFlow()

                Log.i(TAG, "=== MULTI-PASS FLOW GENERATED ===")
                Log.i(TAG, "  Coverage: ${(coverageTracker.metrics.overallCoverage * 100).toInt()}%")
                Log.i(TAG, "  Auto-save enabled: $autoSaveFlows")

                if (autoSaveFlows) {
                    publishGeneratedFlowToMqtt(flowJson)
                    showToast("Flow auto-saved and published")
                } else {
                    // Store for review in ExplorationResultActivity
                    pendingGeneratedFlow = flowJson
                    Log.i(TAG, "Flow stored for review")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate flow", e)
            }

            // Always launch ExplorationResultActivity to show results
            try {
                val resultIntent = Intent(this@AppExplorerService, ExplorationResultActivity::class.java).apply {
                    // Use SINGLE_TOP + CLEAR_TOP to bring existing activity to foreground
                    // instead of creating a new instance (it may be in background from moveTaskToBack)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(ExplorationResultActivity.EXTRA_PACKAGE_NAME, currentState.packageName)
                }
                startActivity(resultIntent)
                Log.i(TAG, "Launched ExplorationResultActivity for multi-pass results")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ExplorationResultActivity", e)
                showToast("Exploration complete! Check results in app.")
            }
        }
    }

    /**
     * Re-queue elements from screens that haven't been fully explored.
     *
     * IMPORTANT: Always check visitedElements set, not just counters.
     * Counters can get out of sync when screens have dynamic content.
     */
    private fun reQueuePartiallyExploredScreens(existingState: ExplorationState) {
        var totalQueued = 0
        var screensWithUnvisited = 0

        existingState.exploredScreens.forEach { (screenId, screen) ->
            val stats = coverageTracker.screenStats[screenId]
            val counterSaysFullyExplored = stats?.isFullyExplored ?: false

            // Always check actual visitedElements, don't trust counters alone
            // Counters can be out of sync with dynamic UI content
            var screenQueued = 0
            for (element in screen.clickableElements) {
                val compositeKey = "${screenId}:${element.elementId}"
                if (!existingState.visitedElements.contains(compositeKey)) {
                    // This element hasn't been tapped yet
                    val priority = priorityCalculator.calculatePriority(
                        element = element,
                        screen = screen,
                        isAdaptiveMode = existingState.config.strategy == ExplorationStrategy.ADAPTIVE,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        visitedNavigationTabs = visitedNavigationTabs
                    )
                    existingState.explorationQueue.add(ExplorationTarget(
                        type = ExplorationTargetType.TAP_ELEMENT,
                        screenId = screenId,
                        elementId = element.elementId,
                        priority = priority + 5, // Boost priority for multi-pass
                        bounds = element.bounds
                    ))
                    screenQueued++
                }
            }
            if (screenQueued > 0) {
                screensWithUnvisited++
                Log.d(TAG, "Re-queued $screenQueued unvisited elements from screen: $screenId (counter said fully explored: $counterSaysFullyExplored)")
                totalQueued += screenQueued
            }
        }

        // Log diagnostic info if nothing was queued but we have low coverage
        if (totalQueued == 0) {
            val totalClickable = existingState.exploredScreens.values.sumOf { it.clickableElements.size }
            val visitedCount = existingState.visitedElements.size
            Log.w(TAG, "Re-queued 0 elements but: totalClickable=$totalClickable, visitedCount=$visitedCount")

            // Log element IDs for debugging
            existingState.exploredScreens.forEach { (screenId, screen) ->
                val screenElements = screen.clickableElements.map { it.elementId }.toSet()
                val visitedOnThisScreen = existingState.visitedElements
                    .filter { it.startsWith("$screenId:") }
                    .map { it.substringAfter(":") }
                    .toSet()
                val unvisitedCount = (screenElements - visitedOnThisScreen).size
                if (unvisitedCount > 0) {
                    Log.w(TAG, "Screen $screenId has $unvisitedCount UNVISITED elements but they weren't queued!")
                }
            }
        }

        Log.i(TAG, "Re-queued $totalQueued total elements from $screensWithUnvisited/${existingState.exploredScreens.size} screens")
    }

    private fun pauseExploration() {
        Log.i(TAG, "Pausing exploration")

        // === STATE MACHINE: Signal pause request ===
        stateMachine.processEvent(ExplorationStateMachine.Event.PAUSE_REQUESTED)

        state?.status = ExplorationStatus.PAUSED
        _explorationState.value = state
        // Note: actual pause is handled in the exploration loop
    }

    private fun resumeExploration() {
        Log.i(TAG, "Resuming exploration")

        // === STATE MACHINE: Signal resume request ===
        stateMachine.processEvent(ExplorationStateMachine.Event.RESUME_REQUESTED)

        state?.status = ExplorationStatus.IN_PROGRESS
        _explorationState.value = state
    }

    // =========================================================================
    // Main Exploration Loop
    // =========================================================================

    private suspend fun runExploration(packageName: String) {
        val config = state?.config ?: ExplorationConfig()

        // ======================================================================
        // SENSITIVE APP CHECK - Block exploration of apps with personal data
        // ======================================================================
        // This check runs FIRST, before any whitelist bypass logic.
        // Even if "Allow All Apps Training" is enabled, sensitive apps are BLOCKED.
        if (isSensitiveAppIntelligent(this, packageName)) {
            Log.e(TAG, "BLOCKED: $packageName is a sensitive app (email, banking, messaging, etc.)")
            showToast("Cannot explore $packageName - sensitive app (email/banking/messaging)")
            state?.status = ExplorationStatus.ERROR
            publishExplorationStatusToMqtt("failed", "Sensitive app blocked")
            return
        }
        Log.i(TAG, "Sensitive app check passed for $packageName")

        // === START SELF-DIAGNOSTICS ===
        diagnostics.recordExplorationStart()
        val initialDiagState = diagnostics.runDiagnostics()
        if (initialDiagState.overallHealth == HealthStatus.CRITICAL) {
            Log.e(TAG, "Critical system issue detected: ${initialDiagState.issues.firstOrNull()?.title}")
            initialDiagState.issues.filter { it.severity == DiagnosticSeverity.CRITICAL }.forEach {
                Log.e(TAG, "  ${it.title}: ${it.description}")
            }
        }
        Log.i(TAG, "System health: ${initialDiagState.overallHealth}, ML acceleration: ${initialDiagState.mlAcceleration}")

        // Initialize Q-Learning for smart exploration
        qLearning = ExplorationQLearning(this).apply {
            screenHeight = this@AppExplorerService.screenHeight
        }
        Log.i(TAG, "Q-Learning initialized: ${qLearning?.getStatistics()}")

        // Verify accessibility service is running (with retry for Samsung bouncing)
        var accessibilityService = VisualMapperAccessibilityService.getInstance()
        var retryCount = 0
        val maxRetries = 10
        while (accessibilityService == null && retryCount < maxRetries) {
            retryCount++
            Log.w(TAG, "Accessibility service not available, waiting... (attempt $retryCount/$maxRetries)")
            delay(1000) // Wait 1 second for service to reconnect
            accessibilityService = VisualMapperAccessibilityService.getInstance()
        }
        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service not running after $maxRetries retries!")
            showToast("Accessibility service not enabled. Please enable in Settings > Accessibility > Visual Mapper")
            state?.status = ExplorationStatus.ERROR
            publishExplorationStatusToMqtt("failed", "Accessibility service not running")
            return
        }
        Log.i(TAG, "Accessibility service available after ${retryCount * 1000}ms ($retryCount attempts)")

        // Verify whitelist and consent
        val securePrefs = accessibilityService.getSecurePreferences()
        val consentManager = accessibilityService.getConsentManager()

        if (securePrefs != null) {
            val isWhitelisted = securePrefs.isAppWhitelisted(packageName)
            Log.i(TAG, "Package $packageName whitelisted: $isWhitelisted")
            if (!isWhitelisted) {
                Log.w(TAG, "Package not whitelisted - adding now...")
                securePrefs.addWhitelistedApp(packageName)
            }
        }

        if (consentManager != null) {
            val consentLevel = consentManager.getConsentLevel(packageName)
            Log.i(TAG, "Package $packageName consent level: $consentLevel")
            if (consentLevel == com.visualmapper.companion.security.ConsentManager.ConsentLevel.NONE) {
                Log.w(TAG, "No consent for package - granting FULL consent...")
                consentManager.grantConsent(
                    packageName = packageName,
                    level = com.visualmapper.companion.security.ConsentManager.ConsentLevel.FULL,
                    purpose = "Smart Explorer - automatic app discovery"
                )
            }
        }

        // Step 0: Ensure we start from the app's home screen
        // If app is already open on a sub-screen, navigate back to home first
        Log.i(TAG, "Step 0: Ensuring app starts from home screen...")
        ensureAppStartsFromHome(packageName)

        // Step 1: Launch the target app
        Log.i(TAG, "Step 1: Launching target app: $packageName")
        if (!launchApp(packageName)) {
            Log.e(TAG, "Failed to launch app: $packageName")
            showToast("Failed to launch app: $packageName")
            state?.status = ExplorationStatus.ERROR
            publishExplorationStatusToMqtt("failed", "Failed to launch app")
            return
        }

        Log.i(TAG, "Waiting ${config.transitionWait}ms for app to launch...")
        delay(config.transitionWait)

        // Wait for the target app to be in foreground (up to 15 seconds with 1.5s intervals)
        var waitAttempts = 10
        var launchRetries = 0  // Track launch retries to prevent infinite loop
        while (waitAttempts > 0) {
            // FIXED: Check rootInActiveWindow FIRST (direct read, more reliable)
            // StateFlow currentPackage may lag behind actual window state
            val currentPkg = accessibilityService.rootInActiveWindow?.packageName?.toString()
                ?: accessibilityService.currentPackage?.value
            Log.d(TAG, "Waiting for target app... current=$currentPkg, target=$packageName")
            if (currentPkg == packageName) {
                Log.i(TAG, "Target app is now in foreground")
                break
            }
            waitAttempts--
            if (waitAttempts == 0) {
                launchRetries++
                Log.w(TAG, "Target app did not come to foreground. Current: $currentPkg (retry $launchRetries/${config.maxLaunchRetries})")

                // Check if we've exceeded max retries
                if (launchRetries >= config.maxLaunchRetries) {
                    Log.e(TAG, "Max launch retries exceeded - stopping exploration")
                    showToast("Failed to launch app after ${config.maxLaunchRetries} attempts")
                    state?.status = ExplorationStatus.ERROR
                    publishExplorationStatusToMqtt("failed", "Max launch retries exceeded")
                    return
                }

                showToast("Target app did not open properly. Retry $launchRetries/${config.maxLaunchRetries}...")
                // Try launching again
                if (launchApp(packageName)) {
                    delay(config.transitionWait)
                    waitAttempts = 5 // Give it 5 more tries
                }
            }
            delay(1500) // Increased from 1000ms to 1500ms for slower devices
        }

        // Wait for UI to stabilize (loading screens, splash screens, etc)
        Log.i(TAG, "Waiting for UI to stabilize...")
        waitForUIStabilization(config.stabilizationWait)

        // === MQTT STATUS: Exploration started ===
        publishExplorationStatusToMqtt("started", "App launched, capturing initial screen")

        // Step 2: Capture initial screen - retry a few times if needed
        Log.i(TAG, "Step 2: Capturing initial screen...")
        var initialScreen: ExploredScreen? = null
        var retries = 5
        var recoveryAttempts = 2  // Try to recover if another app takes foreground

        while (initialScreen == null && retries > 0) {
            // Check if a different app took foreground (e.g., mini player, deep link)
            val currentPackage = VisualMapperAccessibilityService.getInstance()?.getCurrentPackageName()
            if (currentPackage != null && currentPackage != packageName && currentPackage != "com.visualmapper.companion") {
                Log.w(TAG, "Another app took foreground: $currentPackage (target: $packageName)")

                if (recoveryAttempts > 0) {
                    recoveryAttempts--
                    Log.i(TAG, "Attempting recovery - pressing back to return to target app")

                    // Try pressing back to dismiss the overlay app
                    VisualMapperAccessibilityService.getInstance()?.performGlobalAction(
                        android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                    )
                    delay(1000)

                    // Check if we're back in target app
                    val afterBackPackage = VisualMapperAccessibilityService.getInstance()?.getCurrentPackageName()
                    if (afterBackPackage == packageName) {
                        Log.i(TAG, "Successfully returned to target app after back press")
                        consecutiveRelaunchAttempts = 0  // Reset on success
                    } else {
                        // Check global relaunch counter before relaunching
                        if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
                            Log.e(TAG, "=== INITIAL CAPTURE LOOP: Too many relaunches ($consecutiveRelaunchAttempts) ===")
                            break  // Exit the retry loop
                        }

                        // Still not in target app - try relaunching
                        consecutiveRelaunchAttempts++
                        Log.i(TAG, "Back didn't work, relaunching target app: $packageName (attempt $consecutiveRelaunchAttempts)")
                        launchApp(packageName)
                        delay(2000)
                    }
                    continue  // Retry capture
                }
            }

            initialScreen = captureCurrentScreen()
            if (initialScreen == null) {
                retries--
                Log.w(TAG, "Capture failed, retrying... ($retries attempts left)")
                delay(1000)
            } else if (initialScreen.clickableElements.isEmpty() && initialScreen.textElements.isEmpty()) {
                // Got a screen but with no elements - might be loading, retry
                Log.w(TAG, "Screen captured but no elements found, retrying...")
                retries--
                delay(1000)
                initialScreen = null
            }
        }

        if (initialScreen == null) {
            Log.e(TAG, "Failed to capture initial screen after retries")
            showToast("Failed to capture screen. App may have redirected to another app.")
            state?.status = ExplorationStatus.ERROR
            return
        }

        // Alert if no elements found
        if (initialScreen.clickableElements.isEmpty() &&
            initialScreen.textElements.isEmpty() &&
            initialScreen.inputFields.isEmpty()) {
            Log.w(TAG, "Warning: No UI elements found on initial screen!")
            showToast("Warning: No UI elements detected. App may not be accessible or permissions missing.")
        }

        Log.i(TAG, "Successfully captured initial screen: ${initialScreen.screenId}")
        Log.i(TAG, "Screen has ${initialScreen.clickableElements.size} clickable, " +
                "${initialScreen.textElements.size} text, ${initialScreen.inputFields.size} input elements")

        // Check if this looks like a splash screen (few/no clickable elements)
        // If so, wait longer and retry
        var currentScreen = initialScreen
        if (initialScreen.clickableElements.isEmpty() ||
            initialScreen.activity.lowercase().contains("splash")) {
            Log.i(TAG, "Detected splash screen, waiting for main activity...")
            delay(3000) // Wait 3 more seconds for splash to complete

            val newScreen = captureCurrentScreen()
            if (newScreen != null && newScreen.screenId != initialScreen.screenId) {
                Log.i(TAG, "Transitioned to new screen: ${newScreen.screenId}")
                Log.i(TAG, "New screen has ${newScreen.clickableElements.size} clickable elements")
                currentScreen = newScreen
            }
        }

        // === CHECK FOR SCROLL-TO-ENABLE BUTTONS (T&C screens) ===
        // Detect disabled buttons that need scrolling to enable
        val accessibilityServiceForScroll = VisualMapperAccessibilityService.getInstance()
        if (accessibilityServiceForScroll != null) {
            val uiElements = accessibilityServiceForScroll.getUITree()
            val disabledButton = detectDisabledScrollButton(uiElements)
            if (disabledButton != null) {
                Log.i(TAG, "Detected disabled T&C button - scrolling to enable")
                val enabled = handleScrollToEnableButton(disabledButton)
                if (enabled) {
                    // Re-capture the screen now that button is enabled
                    val refreshedScreen = captureCurrentScreen()
                    if (refreshedScreen != null) {
                        currentScreen = refreshedScreen
                        Log.i(TAG, "Re-captured screen after scroll-to-enable: ${refreshedScreen.clickableElements.size} clickable elements")
                    }
                }
            }
        }

        // === INITIAL SCREEN BLOCKER CHECK ===
        // Check if the app starts on a login/authentication screen
        // This is common for apps that require sign-in before accessing content
        val initialActivity = currentScreen.activity.lowercase()
        // NOTE: Patterns must be specific - avoid broad patterns like "auth" that match HomeAuthActivity
        val blockerPatterns = listOf(
            "passwordactivity", "loginactivity", "signinactivity", "sign_in", "signupactivity", "sign_up",
            "authactivity", "verifyactivity", "verificationactivity", "setupwizard", "pinactivity",
            "modeselection", "mode_selection", "usermgmt", "user_mgmt",
            "accountselection", "account_selection", "registeractivity", "registrationactivity",
            "preloginactivity", "pre_login", "pre-login"
        )
        // Removed broad patterns: "auth", "welcome", "intro", "onboard", "password", "login", "verify"
        val isInitialBlocker = blockerPatterns.any { initialActivity.contains(it) }
        if (isInitialBlocker) {
            Log.w(TAG, "=== INITIAL SCREEN IS A BLOCKER: ${currentScreen.activity} ===")
            Log.w(TAG, "App requires authentication to explore further. Options:")
            Log.w(TAG, "  1. Log in manually first, then start exploration from main screen")
            Log.w(TAG, "  2. Use Go Mode with credentials (not implemented)")
            showToast("App requires login to explore. Please sign in first.")
            state?.navigationGraph?.markAsBlocker(currentScreen.screenId)
            state?.issues?.add(ExplorationIssue(
                screenId = currentScreen.screenId,
                elementId = null,
                issueType = IssueType.BLOCKER_SCREEN,
                description = "Initial screen is login/authentication blocker: ${currentScreen.activity}"
            ))
            // Still record the screen but mark exploration as stopped early
            state?.exploredScreens?.put(currentScreen.screenId, currentScreen)
            state?.status = ExplorationStatus.STOPPED
            publishExplorationStatusToMqtt("stopped", "App requires authentication")
            return
        }

        state?.exploredScreens?.put(currentScreen.screenId, currentScreen)
        updateScreenElementStats(currentScreen)  // Track coverage stats
        navigationStack.addFirst(currentScreen.screenId)

        // === PHASE 1.6b: Dual-write to ScreenNavigator ===
        screenNavigator.enterScreen(
            screenId = currentScreen.screenId,
            packageName = packageName,
            activityName = currentScreen.activity,
            elementCount = currentScreen.clickableElements.size,
            unexploredElements = currentScreen.clickableElements.size
        )

        _currentScreen.value = currentScreen
        humanInLoopManager.setCurrentScreen(currentScreen)  // For hit-testing in imitation learning
        updateProgress()

        // === MODE-SPECIFIC EXPLORATION ===
        when (config.mode) {
            ExplorationMode.MANUAL -> {
                Log.i(TAG, "=== MANUAL MODE: Watching user navigation ===")
                runManualExploration(currentScreen)
                return  // Manual mode handles its own loop
            }
            ExplorationMode.DEEP -> {
                Log.i(TAG, "=== DEEP MODE: Full element analysis ===")
                // Deep mode queues ALL elements including ones normally skipped
                queueClickableElementsDeep(currentScreen)
            }
            else -> {
                // QUICK and NORMAL modes use standard queuing
                queueClickableElements(currentScreen)
            }
        }

        // === STEP 3.5: MAIN NAVIGATION DISCOVERY ===
        // SKIP this for ADAPTIVE (let Q-learning decide) and SYSTEMATIC (read in order)
        // In other modes, force discovery of all main navigation sections upfront
        val isAdaptiveMode = config.strategy == ExplorationStrategy.ADAPTIVE
        val isSystematicMode = config.strategy == ExplorationStrategy.SYSTEMATIC
        val mainNavDiscovered = when {
            isSystematicMode -> {
                Log.i(TAG, "=== STEP 3.5: SKIPPING NAV DISCOVERY (SYSTEMATIC MODE) ===")
                Log.i(TAG, "Systematic mode: reading screen top-to-bottom, not discovering nav upfront")
                0
            }
            isAdaptiveMode -> {
                Log.i(TAG, "=== STEP 3.5: SKIPPING NAV DISCOVERY (ADAPTIVE MODE) ===")
                Log.i(TAG, "Adaptive mode: letting Q-learning decide when to explore nav tabs")
                0
            }
            else -> {
                Log.i(TAG, "=== STEP 3.5: MANDATORY MAIN NAVIGATION DISCOVERY ===")
                discoverMainNavigation(currentScreen)
            }
        }
        Log.i(TAG, "Main navigation discovery: found $mainNavDiscovered screens (strategy=${config.strategy.name})")

        // === STATE MACHINE: Initialization complete, entering exploration phase ===
        stateMachine.processEvent(ExplorationStateMachine.Event.INITIALIZATION_COMPLETE)

        // Step 4: Main exploration loop (QUICK, NORMAL, DEEP)
        var iterations = 0
        val maxIterations = config.maxElements
        var totalLaunchRetries = 0  // Track total app launch retries to prevent infinite loop
        var verificationDone = false  // Track if we've done final verification

        // === ADAPTIVE STRATEGY LEARNING ===
        // Track discoveries per strategy to learn what works best
        val strategyDiscoveries = mutableMapOf<ExplorationStrategy, Int>()
        val learnedBestStrategy = learningStore.getBestStrategy(packageName)
        var currentAdaptiveStrategy = learnedBestStrategy ?: ExplorationStrategy.SCREEN_FIRST
        var iterationsSinceStrategySwitch = 0
        val iterationsPerStrategy = 50  // Try each strategy for 50 iterations
        // Load enabled strategies from user preferences
        val strategyPrefs = getSharedPreferences("exploration_strategies", Context.MODE_PRIVATE)
        val enabledStrategies = mutableListOf<ExplorationStrategy>()
        if (strategyPrefs.getBoolean("screen_first_enabled", true)) enabledStrategies.add(ExplorationStrategy.SCREEN_FIRST)
        if (strategyPrefs.getBoolean("depth_first_enabled", true)) enabledStrategies.add(ExplorationStrategy.DEPTH_FIRST)
        if (strategyPrefs.getBoolean("breadth_first_enabled", true)) enabledStrategies.add(ExplorationStrategy.BREADTH_FIRST)
        if (strategyPrefs.getBoolean("priority_based_enabled", true)) enabledStrategies.add(ExplorationStrategy.PRIORITY_BASED)
        if (strategyPrefs.getBoolean("systematic_enabled", true)) enabledStrategies.add(ExplorationStrategy.SYSTEMATIC)

        // Fallback to SCREEN_FIRST if all are disabled
        if (enabledStrategies.isEmpty()) enabledStrategies.add(ExplorationStrategy.SCREEN_FIRST)

        val strategiesToTry = if (learnedBestStrategy != null && enabledStrategies.contains(learnedBestStrategy)) {
            // If we have a learned best strategy AND it's enabled, start with it
            listOf(learnedBestStrategy) + enabledStrategies.filter { it != learnedBestStrategy }
        } else {
            enabledStrategies
        }

        Log.i(TAG, "[ADAPTIVE] Enabled strategies: ${strategiesToTry.map { it.name }}")
        var currentStrategyIndex = 0
        var discoveredBeforeSwitch = state?.visitedElements?.size ?: 0
        var stuckCounter = 0  // Track consecutive iterations with no new discoveries
        val stuckThreshold = 10  // Switch strategy if stuck for 10 iterations
        var lastKnownElements = state?.visitedElements?.size ?: 0

        if (learnedBestStrategy != null) {
            Log.i(TAG, "[ADAPTIVE] Using previously learned best strategy: ${learnedBestStrategy.name}")
        }

        while (state?.status == ExplorationStatus.IN_PROGRESS && iterations < maxIterations && !shouldStop) {
            iterations++

            // === STOP BUTTON FIX ===
            // Check static stop flag at start of each iteration
            if (shouldStop) {
                Log.i(TAG, "shouldStop flag detected - stopping exploration loop")
                break
            }

            // === AGGRESSIVE APP DETECTION ===
            // Check if we're still in the target app BEFORE doing anything else
            // This prevents navigating around in wrong apps or home screen
            if (!ensureInTargetApp()) {
                Log.e(TAG, "Failed to return to target app - stopping exploration")
                state?.issues?.add(
                    ExplorationIssue(
                        screenId = navigationStack.firstOrNull() ?: "unknown",
                        elementId = null,
                        issueType = IssueType.APP_LEFT,
                        description = "Lost target app and could not recover"
                    )
                )
                state?.status = ExplorationStatus.ERROR
                break
            }

            // === EXPLORATION TIMEOUT CHECK ===
            // Prevent exploration from running forever
            val elapsedMs = System.currentTimeMillis() - (state?.startTime ?: System.currentTimeMillis())
            if (elapsedMs > config.maxDurationMs) {
                Log.w(TAG, "Exploration timeout reached (${elapsedMs / 1000}s > ${config.maxDurationMs / 1000}s)")
                showToast("Exploration timed out after ${elapsedMs / 60000} minutes")
                state?.status = ExplorationStatus.COMPLETED
                break
            }

            // === ACCESSIBILITY SERVICE HEALTH CHECK ===
            // Android can destroy the accessibility service mid-exploration.
            // If service died, wait for it to recover before continuing.
            if (VisualMapperAccessibilityService.getInstance() == null) {
                Log.w(TAG, "Accessibility service died mid-exploration - waiting for recovery...")
                state?.recoveryAttempts = (state?.recoveryAttempts ?: 0) + 1

                if (!waitForAccessibilityService(15000)) {
                    Log.e(TAG, "Accessibility service did not recover after 15s - stopping exploration")
                    state?.issues?.add(
                        ExplorationIssue(
                            screenId = navigationStack.firstOrNull() ?: "unknown",
                            elementId = null,
                            issueType = IssueType.RECOVERY_FAILED,
                            description = "Accessibility service destroyed and did not recover"
                        )
                    )
                    state?.status = ExplorationStatus.ERROR
                    break
                }

                Log.i(TAG, "Accessibility service recovered - resuming exploration")
                // Re-verify we're still in the target app after service recovery
                val recoveredService = VisualMapperAccessibilityService.getInstance()
                // FIXED: Check rootInActiveWindow FIRST (direct read, more reliable)
                val currentPkg = recoveredService?.rootInActiveWindow?.packageName?.toString()
                    ?: recoveredService?.currentPackage?.value
                val targetPkg = state?.packageName

                if (targetPkg != null && currentPkg != targetPkg) {
                    Log.w(TAG, "After recovery, not in target app. Current: $currentPkg, Target: $targetPkg")

                    // Check relaunch counter to prevent loops
                    if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
                        Log.e(TAG, "=== SERVICE RECOVERY LOOP: Too many relaunches ($consecutiveRelaunchAttempts) ===")
                        state?.status = ExplorationStatus.ERROR
                        break
                    }

                    // Try to re-launch the app
                    consecutiveRelaunchAttempts++
                    Log.i(TAG, "Re-launching target app after service recovery (attempt $consecutiveRelaunchAttempts)")
                    if (!launchApp(targetPkg)) {
                        Log.e(TAG, "Failed to re-launch target app after service recovery")
                        state?.status = ExplorationStatus.ERROR
                        break
                    }
                    delay(2000) // Wait for app to open

                    // Reset counter if we successfully landed in target app
                    if (recoveredService?.rootInActiveWindow?.packageName?.toString() == targetPkg) {
                        consecutiveRelaunchAttempts = 0
                    }
                }
            }

            // Check limits based on exploration goal
            when (config.goal) {
                ExplorationGoal.QUICK_SCAN -> {
                    // Original hard limits
                    if ((state?.exploredScreens?.size ?: 0) >= config.maxScreens) {
                        Log.i(TAG, "QUICK_SCAN: Reached max screens limit: ${config.maxScreens}")
                        break
                    }
                }
                ExplorationGoal.DEEP_MAP -> {
                    // Higher limits (100 screens, 500 elements, 20 min)
                    if ((state?.exploredScreens?.size ?: 0) >= 100) {
                        Log.i(TAG, "DEEP_MAP: Reached max screens limit: 100")
                        break
                    }
                    if (iterations >= 500) {
                        Log.i(TAG, "DEEP_MAP: Reached max elements limit: 500")
                        break
                    }
                    if (elapsedMs > 20 * 60 * 1000L) {
                        Log.i(TAG, "DEEP_MAP: Reached max duration: 20 minutes")
                        break
                    }
                }
                ExplorationGoal.COMPLETE_COVERAGE -> {
                    // Goal-based: 90%+ coverage OR time cap (30 min safety)
                    if (hasReachedTargetCoverage()) {
                        Log.i(TAG, "=== COMPLETE_COVERAGE: TARGET REACHED! ===")
                        Log.i(TAG, "Coverage: ${coverageMetrics.summary()}")
                        showToast("Coverage target reached: ${(coverageMetrics.overallCoverage * 100).toInt()}%")
                        break
                    }
                    if (elapsedMs > config.maxDurationForCoverage) {
                        Log.w(TAG, "COMPLETE_COVERAGE: Safety timeout reached (${elapsedMs / 60000} min)")
                        Log.i(TAG, "Final coverage: ${coverageMetrics.summary()}")
                        showToast("Time limit reached. Coverage: ${(coverageMetrics.overallCoverage * 100).toInt()}%")
                        break
                    }
                    // Also check element/screen limits as safety caps
                    if ((state?.exploredScreens?.size ?: 0) >= 100) {
                        Log.i(TAG, "COMPLETE_COVERAGE: Safety limit reached: 100 screens")
                        break
                    }
                }
            }

            // Get next target - USE STRATEGY-BASED SELECTION
            val currentScreenId = navigationStack.firstOrNull()
            var target: ExplorationTarget? = null
            val strategy = config.strategy

            target = when (strategy) {
                ExplorationStrategy.SCREEN_FIRST -> {
                    // Complete ALL elements on current screen before navigating away
                    // This is less chaotic and more thorough per-screen
                    if (currentScreenId != null) {
                        val currentScreenTarget = state?.explorationQueue
                            ?.filter { it.screenId == currentScreenId }
                            ?.maxByOrNull { it.priority }
                        if (currentScreenTarget != null) {
                            state?.explorationQueue?.remove(currentScreenTarget)
                            Log.d(TAG, "[SCREEN_FIRST] Selected element on current screen: ${currentScreenTarget.elementId}")
                            currentScreenTarget
                        } else {
                            // Current screen exhausted - pick next screen with highest priority element
                            val nextTarget = state?.explorationQueue?.maxByOrNull { it.priority }
                            if (nextTarget != null) {
                                state?.explorationQueue?.remove(nextTarget)
                                Log.d(TAG, "[SCREEN_FIRST] Current screen done, moving to: ${nextTarget.screenId}")
                            }
                            nextTarget
                        }
                    } else {
                        state?.explorationQueue?.maxByOrNull { it.priority }?.also {
                            state?.explorationQueue?.remove(it)
                        }
                    }
                }

                ExplorationStrategy.PRIORITY_BASED -> {
                    // Original behavior: always pick highest priority regardless of screen
                    // May jump between screens frequently
                    val bestTarget = state?.explorationQueue?.maxByOrNull { it.priority }
                    if (bestTarget != null) {
                        state?.explorationQueue?.remove(bestTarget)
                        Log.d(TAG, "[PRIORITY_BASED] Selected highest priority: ${bestTarget.elementId} (${bestTarget.priority})")
                    }
                    bestTarget
                }

                ExplorationStrategy.DEPTH_FIRST -> {
                    // Go deep: prefer elements that lead to new screens (navigation elements)
                    // before exploring non-navigation elements on current screen
                    val navElement = state?.explorationQueue
                        ?.filter { it.type == ExplorationTargetType.NAVIGATE_TO_SCREEN || it.elementId?.contains("nav", ignoreCase = true) == true }
                        ?.maxByOrNull { it.priority }
                    if (navElement != null) {
                        state?.explorationQueue?.remove(navElement)
                        Log.d(TAG, "[DEPTH_FIRST] Selected navigation element: ${navElement.elementId}")
                        navElement
                    } else {
                        // No nav elements, pick highest priority
                        state?.explorationQueue?.maxByOrNull { it.priority }?.also {
                            state?.explorationQueue?.remove(it)
                            Log.d(TAG, "[DEPTH_FIRST] No nav elements, selected: ${it.elementId}")
                        }
                    }
                }

                ExplorationStrategy.BREADTH_FIRST -> {
                    // Explore all screens at current depth before going deeper
                    // Prefer elements on screens we've visited least
                    val screenVisitCounts = state?.exploredScreens?.mapValues {
                        state?.visitedElements?.count { visited -> visited.startsWith(it.key) } ?: 0
                    } ?: emptyMap()
                    val leastVisitedScreen = screenVisitCounts.minByOrNull { it.value }?.key

                    val breadthTarget = if (leastVisitedScreen != null) {
                        state?.explorationQueue
                            ?.filter { it.screenId == leastVisitedScreen }
                            ?.maxByOrNull { it.priority }
                    } else null

                    if (breadthTarget != null) {
                        state?.explorationQueue?.remove(breadthTarget)
                        Log.d(TAG, "[BREADTH_FIRST] Selected from least-visited screen: ${breadthTarget.screenId}")
                        breadthTarget
                    } else {
                        state?.explorationQueue?.maxByOrNull { it.priority }?.also {
                            state?.explorationQueue?.remove(it)
                        }
                    }
                }

                ExplorationStrategy.ADAPTIVE -> {
                    // === MULTI-STRATEGY LEARNING ===
                    // Cycle through strategies, track which discovers the most, learn for next time
                    iterationsSinceStrategySwitch++

                    // Check if we're stuck (no new discoveries)
                    val currentElements = state?.visitedElements?.size ?: 0
                    if (currentElements == lastKnownElements) {
                        stuckCounter++
                    } else {
                        stuckCounter = 0
                        lastKnownElements = currentElements
                        lastDiscoveryIteration = iterations  // Track when last discovery happened
                    }

                    // Switch strategy if: (1) stuck for too long, or (2) completed iteration quota
                    val shouldSwitchForward = (stuckCounter >= stuckThreshold || iterationsSinceStrategySwitch >= iterationsPerStrategy) &&
                        currentStrategyIndex < strategiesToTry.size - 1

                    // Check if we should switch BACK to a better-performing strategy
                    val shouldSwitchBack = stuckCounter >= stuckThreshold && strategyDiscoveries.isNotEmpty() &&
                        strategyDiscoveries.any { (strategy, discoveries) ->
                            strategy != currentAdaptiveStrategy && discoveries > 0
                        }

                    if (shouldSwitchForward || shouldSwitchBack) {
                        val reason = when {
                            stuckCounter >= stuckThreshold && shouldSwitchBack -> "STUCK-SWITCHBACK"
                            stuckCounter >= stuckThreshold -> "STUCK"
                            else -> "QUOTA"
                        }

                        // Record discoveries for current strategy
                        val currentDiscovered = (state?.visitedElements?.size ?: 0) - discoveredBeforeSwitch
                        strategyDiscoveries[currentAdaptiveStrategy] = (strategyDiscoveries[currentAdaptiveStrategy] ?: 0) + currentDiscovered
                        Log.i(TAG, "[ADAPTIVE][$reason] Strategy ${currentAdaptiveStrategy.name} discovered $currentDiscovered elements")

                        if (shouldSwitchBack && stuckCounter >= stuckThreshold) {
                            // Switch back to the best-performing strategy we've tried
                            val bestPrevious = strategyDiscoveries
                                .filter { it.key != currentAdaptiveStrategy && it.value > 0 }
                                .maxByOrNull { it.value }

                            if (bestPrevious != null) {
                                currentAdaptiveStrategy = bestPrevious.key
                                Log.i(TAG, "[ADAPTIVE] Switching BACK to best strategy: ${currentAdaptiveStrategy.name} (${bestPrevious.value} discoveries)")
                            } else if (currentStrategyIndex < strategiesToTry.size - 1) {
                                // No good previous strategy, try next one
                                currentStrategyIndex++
                                currentAdaptiveStrategy = strategiesToTry[currentStrategyIndex]
                                Log.i(TAG, "[ADAPTIVE] Switching to next strategy: ${currentAdaptiveStrategy.name}")
                            }
                        } else if (currentStrategyIndex < strategiesToTry.size - 1) {
                            // Normal forward switch
                            currentStrategyIndex++
                            currentAdaptiveStrategy = strategiesToTry[currentStrategyIndex]
                            Log.i(TAG, "[ADAPTIVE] Switching to strategy: ${currentAdaptiveStrategy.name}")
                        }

                        iterationsSinceStrategySwitch = 0
                        stuckCounter = 0
                        discoveredBeforeSwitch = state?.visitedElements?.size ?: 0
                    } else {
                        // === CLEAN RESTART RECOVERY ===
                        // Strategy switching didn't help (all strategies exhausted or no better option)
                        // Check if we're in a coverage plateau and should try a clean restart
                        val iterationsSinceDiscovery = iterations - lastDiscoveryIteration
                        val shouldTryRestart = (stuckCounter >= STUCK_RESTART_THRESHOLD ||
                            iterationsSinceDiscovery >= COVERAGE_PLATEAU_THRESHOLD) &&
                            consecutiveRelaunchAttempts < MAX_CONSECUTIVE_RELAUNCHES

                        if (shouldTryRestart) {
                            Log.w(TAG, "[ADAPTIVE] Coverage plateau detected " +
                                "(stuck=$stuckCounter, no discoveries for $iterationsSinceDiscovery iterations) " +
                                "- attempting clean restart recovery")

                            val restarted = backtrackToUnexploredBranch()
                            if (restarted) {
                                stuckCounter = 0
                                lastDiscoveryIteration = iterations
                                Log.i(TAG, "[ADAPTIVE] Clean restart recovery successful - resuming exploration")
                                // Record success for learning
                                qLearning?.recordRestartRecovery(
                                    success = true,
                                    reason = if (stuckCounter >= STUCK_RESTART_THRESHOLD) "STUCK_THRESHOLD" else "COVERAGE_PLATEAU"
                                )
                            } else {
                                Log.w(TAG, "[ADAPTIVE] Clean restart recovery failed - continuing with current approach")
                                qLearning?.recordRestartRecovery(success = false, reason = "RESTART_FAILED")
                            }
                        }
                    }

                    // Use the current adaptive strategy
                    when (currentAdaptiveStrategy) {
                        ExplorationStrategy.SCREEN_FIRST -> {
                            if (currentScreenId != null) {
                                val t = state?.explorationQueue?.filter { it.screenId == currentScreenId }?.maxByOrNull { it.priority }
                                if (t != null) { state?.explorationQueue?.remove(t); t }
                                else state?.explorationQueue?.maxByOrNull { it.priority }?.also { state?.explorationQueue?.remove(it) }
                            } else {
                                state?.explorationQueue?.maxByOrNull { it.priority }?.also { state?.explorationQueue?.remove(it) }
                            }
                        }
                        ExplorationStrategy.DEPTH_FIRST -> {
                            val navElement = state?.explorationQueue?.filter { it.type == ExplorationTargetType.NAVIGATE_TO_SCREEN }?.maxByOrNull { it.priority }
                            if (navElement != null) { state?.explorationQueue?.remove(navElement); navElement }
                            else state?.explorationQueue?.maxByOrNull { it.priority }?.also { state?.explorationQueue?.remove(it) }
                        }
                        ExplorationStrategy.BREADTH_FIRST -> {
                            val screenVisitCounts = state?.exploredScreens?.mapValues { state?.visitedElements?.count { v -> v.startsWith(it.key) } ?: 0 } ?: emptyMap()
                            val leastVisited = screenVisitCounts.minByOrNull { it.value }?.key
                            val breadthT = leastVisited?.let { state?.explorationQueue?.filter { t -> t.screenId == it }?.maxByOrNull { t -> t.priority } }
                            if (breadthT != null) { state?.explorationQueue?.remove(breadthT); breadthT }
                            else state?.explorationQueue?.maxByOrNull { it.priority }?.also { state?.explorationQueue?.remove(it) }
                        }
                        else -> {
                            state?.explorationQueue?.maxByOrNull { it.priority }?.also { state?.explorationQueue?.remove(it) }
                        }
                    }
                }

                ExplorationStrategy.SYSTEMATIC -> {
                    // SYSTEMATIC: Read like a book - top-left to bottom-right
                    // Elements are already queued with position-based priority in ElementQueueManager
                    // Just pick highest priority (which is top-left first)
                    if (currentScreenId != null) {
                        // Complete current screen in reading order before moving to next
                        val currentScreenElements = state?.explorationQueue?.filter { it.screenId == currentScreenId }
                        if (currentScreenElements?.isNotEmpty() == true) {
                            // Debug: log priority range
                            val minPrio = currentScreenElements.minByOrNull { it.priority }
                            val maxPrio = currentScreenElements.maxByOrNull { it.priority }
                            Log.d(TAG, "[SYSTEMATIC] Queue has ${currentScreenElements.size} elements. Priority range: ${minPrio?.priority} to ${maxPrio?.priority}")
                        }
                        val currentScreenTarget = currentScreenElements?.maxByOrNull { it.priority }
                        if (currentScreenTarget != null) {
                            state?.explorationQueue?.remove(currentScreenTarget)
                            Log.d(TAG, "[SYSTEMATIC] Reading element at (${currentScreenTarget.bounds?.x}, ${currentScreenTarget.bounds?.y}) priority=${currentScreenTarget.priority}")
                            currentScreenTarget
                        } else {
                            // Current screen done - move to next screen
                            val nextTarget = state?.explorationQueue?.maxByOrNull { it.priority }
                            if (nextTarget != null) {
                                state?.explorationQueue?.remove(nextTarget)
                                Log.d(TAG, "[SYSTEMATIC] Screen done, moving to: ${nextTarget.screenId}")
                            }
                            nextTarget
                        }
                    } else {
                        state?.explorationQueue?.maxByOrNull { it.priority }?.also {
                            state?.explorationQueue?.remove(it)
                        }
                    }
                }
            }

            if (target == null) {
                // Queue is empty - but first verify we haven't missed anything
                if (!verificationDone) {
                    Log.i(TAG, "Queue empty - running verification before completing")

                    // STEP 1: Check for unvisited bottom navigation tabs
                    val unvisitedNavTabs = queueUnvisitedBottomNavTabs()
                    if (unvisitedNavTabs > 0) {
                        Log.i(TAG, "=== FOUND $unvisitedNavTabs UNVISITED BOTTOM NAV TABS - MUST EXPLORE! ===")
                        continue // Re-enter loop to explore new nav sections
                    }

                    // STEP 2: Run general verification
                    val unvisited = verifyCoverageAndQueueMissed()
                    verificationDone = true
                    if (unvisited > 0) {
                        Log.i(TAG, "Verification found $unvisited elements - continuing")
                        continue // Re-enter loop to process newly queued elements
                    }

                    // STEP 3: For COMPLETE_COVERAGE mode, try systematic backtracking
                    Log.d(TAG, "Backtrack check: goal=${config.goal}, coverage=${coverageMetrics.overallCoverage}, " +
                        "target=${config.targetCoverage}, hasReached=${hasReachedTargetCoverage()}, " +
                        "enableBacktrack=${config.enableSystematicBacktracking}")
                    if (config.goal == ExplorationGoal.COMPLETE_COVERAGE && !hasReachedTargetCoverage()) {
                        Log.i(TAG, "Queue empty but coverage target not reached - trying backtrack")
                        Log.i(TAG, "Current: ${coverageMetrics.summary()}")
                        if (backtrackToUnexploredBranch()) {
                            verificationDone = false // Reset to allow new verification after backtrack
                            continue // Re-enter loop to explore the branch we backtracked to
                        }
                        Log.w(TAG, "Backtracking failed - proceeding to complete exploration")
                    }

                    // STEP 4: If queue empty but low coverage, try clean restart recovery
                    // This catches cases where backtracking isn't enabled or goal isn't COMPLETE_COVERAGE
                    val currentCoverage = coverageMetrics.overallCoverage
                    if (currentCoverage < 0.5f && consecutiveRelaunchAttempts < MAX_CONSECUTIVE_RELAUNCHES) {
                        Log.w(TAG, "Queue empty with only ${(currentCoverage * 100).toInt()}% coverage - attempting clean restart recovery")
                        if (backtrackToUnexploredBranch()) {
                            verificationDone = false
                            qLearning?.recordRestartRecovery(success = true, reason = "EMPTY_QUEUE_LOW_COVERAGE")
                            continue
                        } else {
                            qLearning?.recordRestartRecovery(success = false, reason = "EMPTY_QUEUE_RESTART_FAILED")
                            Log.w(TAG, "Clean restart recovery failed - exploration truly complete")
                        }
                    }
                }

                // === CHECK IF WE ONLY FOUND LOGIN SCREENS (actual blockers) ===
                // Settings screens are low-priority but NOT blockers
                val loginCount = state?.exploredScreens?.count { (_, screen) ->
                    queueManager.isLoginScreen(screen)
                } ?: 0
                val nonLoginCount = (state?.exploredScreens?.size ?: 0) - loginCount

                Log.i(TAG, "Completion check - screens: ${state?.exploredScreens?.size} total, $nonLoginCount non-login, $loginCount login")

                if (nonLoginCount == 0 && loginCount > 0) {
                    Log.w(TAG, "=== ONLY LOGIN SCREENS FOUND - App requires authentication ===")
                    showToast("App requires login - could not find explorable content")
                    state?.status = ExplorationStatus.STOPPED
                    state?.issues?.add(ExplorationIssue(
                        screenId = state?.exploredScreens?.keys?.firstOrNull() ?: "unknown",
                        elementId = null,
                        issueType = IssueType.BLOCKER_SCREEN,
                        description = "Only found login screens - app requires authentication"
                    ))
                    break
                }

                Log.i(TAG, "Exploration queue empty and verified - exploration complete")
                break
            }

            // === FIX: Pre-flight screen validation ===
            // Before processing any element, verify it's on the current screen or a reachable screen
            if (target.type == ExplorationTargetType.TAP_ELEMENT) {
                val currentScreenId = _currentScreen.value?.screenId
                if (target.screenId != currentScreenId && !navigationStack.contains(target.screenId)) {
                    Log.w(TAG, "Skipping stale element from unreachable screen ${target.screenId} (current: $currentScreenId)")
                    // Remove all elements from this unreachable screen
                    val removedCount = state?.explorationQueue?.count { it.screenId == target.screenId } ?: 0
                    state?.explorationQueue?.removeAll { it.screenId == target.screenId }
                    if (removedCount > 0) {
                        Log.i(TAG, "Removed $removedCount stale elements from unreachable screen ${target.screenId}")
                    }
                    continue  // Skip to next iteration
                }
            }

            // Process target
            when (target.type) {
                ExplorationTargetType.TAP_ELEMENT -> processElementTap(target)
                ExplorationTargetType.SCROLL_CONTAINER -> processScroll(target)
                ExplorationTargetType.NAVIGATE_TO_SCREEN -> navigateToScreen(target.screenId)
            }

            // Update progress
            updateProgress()
            delay(config.actionDelay)

            // Check for pause
            while (state?.status == ExplorationStatus.PAUSED) {
                delay(500)
            }
        }

        // === NON-DESTRUCTIVE EXPLORATION: Revert changed toggles ===
        if (state?.config?.nonDestructive == true) {
            Log.i(TAG, "Non-destructive mode: Reverting ${state?.changedToggles?.size ?: 0} changed toggles")
            revertChangedToggles()
        }

        // === ADAPTIVE STRATEGY LEARNING: Save best strategy for this app ===
        if (config.strategy == ExplorationStrategy.ADAPTIVE && strategyDiscoveries.isNotEmpty()) {
            // Record final discoveries for current strategy
            val finalDiscovered = (state?.visitedElements?.size ?: 0) - discoveredBeforeSwitch
            strategyDiscoveries[currentAdaptiveStrategy] = (strategyDiscoveries[currentAdaptiveStrategy] ?: 0) + finalDiscovered

            // Find best performing strategy
            val bestStrategy = strategyDiscoveries.maxByOrNull { it.value }
            Log.i(TAG, "=== ADAPTIVE LEARNING RESULTS ===")
            strategyDiscoveries.forEach { (strategy, discoveries) ->
                val marker = if (strategy == bestStrategy?.key) " [BEST]" else ""
                Log.i(TAG, "  ${strategy.name}: $discoveries discoveries$marker")
            }

            // Save best strategy for this app in LearningStore
            if (bestStrategy != null) {
                try {
                    learningStore.recordBestStrategy(packageName, bestStrategy.key)
                    Log.i(TAG, "Saved best strategy for $packageName: ${bestStrategy.key.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save best strategy: ${e.message}")
                }
            }
        }

        // Exploration complete
        state?.status = ExplorationStatus.COMPLETED
        state?.endTime = System.currentTimeMillis()

        // Update progress to notify overlay (triggers auto-hide)
        updateProgress()

        val issueCount = state?.issues?.size ?: 0
        val togglesReverted = state?.changedToggles?.size ?: 0
        Log.i(TAG, "=== EXPLORATION COMPLETED ===")
        Log.i(TAG, "  Screens: ${state?.exploredScreens?.size}")
        Log.i(TAG, "  Elements: ${state?.visitedElements?.size}")
        Log.i(TAG, "  Issues: $issueCount")
        Log.i(TAG, "  Recovery attempts: ${state?.recoveryAttempts}")
        Log.i(TAG, "  Dangerous patterns learned: ${state?.dangerousPatterns?.size}")
        Log.i(TAG, "  Toggles reverted: $togglesReverted")

        // Save Q-learning state for future explorations
        qLearning?.saveAll()
        val qStats = qLearning?.getStatistics()
        Log.i(TAG, "  Q-Learning: ${qStats?.qTableSize} Q-values, ${qStats?.dangerousPatterns} dangerous patterns")

        // Publish exploration logs to ML training server if ML Training mode is enabled
        publishExplorationLogsToMqtt()

        // === FLOW GENERATION (Phase 2.3) ===
        // Generate flow from exploration results if we have sensors/actions
        val currentState = state
        if (currentState != null && currentState.exploredScreens.isNotEmpty()) {
            try {
                val prefs = getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
                val autoSaveFlows = prefs.getBoolean("auto_save_flows", false)

                val deviceId = getDeviceIdForFlows()
                val flowGenerator = FlowGenerator(currentState, deviceId)
                val flowJson = flowGenerator.generateSensorCollectionFlow()

                Log.i(TAG, "=== FLOW GENERATED ===")
                Log.i(TAG, "  Auto-save enabled: $autoSaveFlows")

                if (autoSaveFlows) {
                    // Auto-save: Publish flow via MQTT
                    publishGeneratedFlowToMqtt(flowJson)
                    showToast("Flow auto-saved and published")
                } else {
                    // Store for review in ExplorationResultActivity
                    pendingGeneratedFlow = flowJson
                    Log.i(TAG, "Flow stored for review")

                    // Launch ExplorationResultActivity to show results to user
                    try {
                        val resultIntent = Intent(this@AppExplorerService, ExplorationResultActivity::class.java).apply {
                            // Use SINGLE_TOP + CLEAR_TOP to bring existing activity to foreground
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            putExtra(ExplorationResultActivity.EXTRA_PACKAGE_NAME, state?.packageName)
                        }
                        startActivity(resultIntent)
                        Log.i(TAG, "Launched ExplorationResultActivity for review")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to launch ExplorationResultActivity", e)
                        showToast("Exploration complete! Check results in app.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate flow", e)
            }
        }

        // === AUDIT LOG: Exploration completed ===
        auditLog.logCustomAction(
            actionName = "exploration_complete",
            targetApp = state?.packageName ?: "",
            accessLevel = accessManager.getAccessLevelForApp(state?.packageName ?: ""),
            goModeActive = goModeManager.isActive(),
            success = true,
            metadata = mapOf(
                "screens" to (state?.exploredScreens?.size ?: 0).toString(),
                "elements" to (state?.visitedElements?.size ?: 0).toString(),
                "issues" to (issueCount).toString(),
                "durationMs" to ((state?.endTime ?: 0) - (state?.startTime ?: 0)).toString()
            )
        )

        // Save audit log
        scope.launch {
            auditLog.saveToFile()
        }

        // === MQTT STATUS: Exploration completed ===
        publishExplorationStatusToMqtt("completed", "Exploration finished")

        // Log issue summary
        if (issueCount > 0) {
            Log.w(TAG, "=== ISSUE SUMMARY ===")
            state?.issues?.groupBy { it.issueType }?.forEach { (type, issues) ->
                Log.w(TAG, "  $type: ${issues.size} occurrences")
                issues.take(3).forEach { issue ->
                    Log.w(TAG, "    - ${issue.description}")
                }
                if (issues.size > 3) {
                    Log.w(TAG, "    ... and ${issues.size - 3} more")
                }
            }
        }

        // === CONDITIONAL NAVIGATION & BLOCKER SUMMARY ===
        val navGraph = state?.navigationGraph
        if (navGraph != null) {
            val graphStats = navGraph.getStats()
            Log.i(TAG, "=== NAVIGATION INTELLIGENCE ===")
            Log.i(TAG, "  Conditional elements: ${graphStats.conditionalElements}")
            Log.i(TAG, "  Blocker screens detected: ${graphStats.blockerScreens}")

            // Log conditional elements (same button → multiple destinations)
            val conditionals = navGraph.getConditionalElements()
            if (conditionals.isNotEmpty()) {
                Log.i(TAG, "=== CONDITIONAL ELEMENTS (button can lead to different screens) ===")
                for (nav in conditionals.take(5)) {
                    Log.i(TAG, "  ${nav.elementId}:")
                    for ((dest, count) in nav.destinations) {
                        val isBlocker = nav.blockerDestinations.contains(dest)
                        val blockerTag = if (isBlocker) " [BLOCKER]" else ""
                        Log.i(TAG, "    → ${dest.take(16)}: $count visits$blockerTag")
                    }
                }
                if (conditionals.size > 5) {
                    Log.i(TAG, "  ... and ${conditionals.size - 5} more conditional elements")
                }
            }

            // Log blocker screens
            val blockers = navGraph.getBlockerScreens()
            if (blockers.isNotEmpty()) {
                Log.i(TAG, "=== BLOCKER SCREENS (password/login/setup) ===")
                for (blocker in blockers) {
                    Log.i(TAG, "  ${blocker.take(16)}")
                }
            }
        }
    }

    // =========================================================================
    // Screen Capture
    // =========================================================================

    /**
     * Refresh screen dimensions to handle device rotation.
     * Called before each screen capture to ensure correct dimension detection.
     */
    private fun refreshScreenDimensions() {
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val display = windowManager.defaultDisplay
            val size = Point()
            display.getRealSize(size)

            // Check if dimensions changed (rotation)
            if (size.x != screenWidth || size.y != screenHeight) {
                Log.i(TAG, "Screen dimensions changed: ${screenWidth}x${screenHeight} -> ${size.x}x${size.y}")

                // === FIX 3: CLEAR QUEUE ON DIMENSION CHANGE ===
                // When device rotates, all queued element coordinates become invalid
                val queueSize = state?.explorationQueue?.size ?: 0
                if (queueSize > 0) {
                    state?.explorationQueue?.clear()
                    Log.i(TAG, "=== DIMENSION CHANGE: Cleared $queueSize stale elements from queue ===")
                }

                screenWidth = size.x
                screenHeight = size.y
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh screen dimensions", e)
        }
    }

    private suspend fun captureCurrentScreen(): ExploredScreen? {
        // Refresh dimensions in case device rotated
        refreshScreenDimensions()

        // === FIX FOR SERVICE BEING DESTROYED MID-EXPLORATION ===
        // Android can destroy the accessibility service at any time (especially when user switches apps).
        // Wait for service to be available with a root window before continuing.
        if (!waitForAccessibilityService(5000)) {
            Log.e(TAG, "Accessibility service not available - cannot capture screen")
            return null
        }

        // Get service and root node (should succeed after waitForAccessibilityService)
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service became null after wait")
            return null
        }

        val rootNode = accessibilityService.rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "rootInActiveWindow is null after wait")
            return null
        }

        // === ACTIVITY DETECTION FIX ===
        // The currentActivity StateFlow sometimes doesn't update, causing "unknown" which
        // creates different screen IDs for the same screen. Use multiple fallbacks.
        var currentActivity = accessibilityService.currentActivity?.value
        if (currentActivity.isNullOrEmpty() || currentActivity == "unknown") {
            // Fallback: Try to get activity from root node's window
            try {
                val windowInfo = rootNode.window
                if (windowInfo != null) {
                    val windowTitle = windowInfo.title?.toString()
                    if (!windowTitle.isNullOrEmpty() && windowTitle.contains("/")) {
                        // Window title often looks like "com.package/ActivityName"
                        currentActivity = windowTitle.substringAfter("/")
                        Log.d(TAG, "Got activity from window title: $currentActivity")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not get activity from window: ${e.message}")
            }
        }
        // === FILTER INVALID ACTIVITY NAMES ===
        // Sometimes accessibility returns View class names instead of Activity names.
        // These are invalid and cause screen ID mismatches.
        val invalidActivityPatterns = listOf(
            "android.widget.",
            "android.view.",
            "androidx.recyclerview.",
            "androidx.viewpager.",
            "android.webkit.",
            "LinearLayout",
            "FrameLayout",
            "ViewGroup",
            "RecyclerView"
        )
        val isInvalidActivity = currentActivity != null && invalidActivityPatterns.any {
            currentActivity!!.contains(it)
        }
        if (isInvalidActivity) {
            Log.d(TAG, "Detected invalid activity name (View class): $currentActivity - using fallback")
            currentActivity = null
        }

        // Final fallback: use the cached activity from last successful detection
        if (currentActivity.isNullOrEmpty() || currentActivity == "unknown") {
            currentActivity = lastKnownActivity ?: "unknown"
            Log.d(TAG, "Using lastKnownActivity fallback: $currentActivity")
        } else {
            // Cache successful activity detection - only if it looks like an Activity
            lastKnownActivity = currentActivity
        }

        // FIXED: Try rootNode FIRST (direct read, most reliable), then StateFlow, then state fallback
        var currentPackage = rootNode.packageName?.toString()
        if (currentPackage == null) {
            currentPackage = accessibilityService.currentPackage?.value
            Log.d(TAG, "Using package from StateFlow: $currentPackage")
        }
        if (currentPackage == null) {
            currentPackage = state?.packageName
            Log.d(TAG, "Using package from state: $currentPackage")
        }
        if (currentPackage == null) {
            Log.e(TAG, "Cannot determine current package")
            return null
        }

        Log.d(TAG, "Capturing screen: package=$currentPackage, activity=$currentActivity")

        // === PACKAGE VERIFICATION FIX ===
        // Verify we're in the target app before capturing. This prevents capturing
        // launcher/home screens when the app was accidentally minimized.
        val targetPackage = state?.packageName
        if (targetPackage != null && currentPackage != targetPackage) {
            Log.w(TAG, "PACKAGE MISMATCH! current=$currentPackage, target=$targetPackage")
            Log.w(TAG, "Not capturing screen from wrong app - will trigger recovery")
            return null  // Don't capture screens from wrong apps
        }

        // Verify whitelist and consent before getting UI tree
        val securePrefs = accessibilityService.getSecurePreferences()
        val consentManager = accessibilityService.getConsentManager()

        if (securePrefs != null) {
            val isWhitelisted = securePrefs.isAppWhitelisted(currentPackage)
            Log.d(TAG, "Package whitelist check: $currentPackage = $isWhitelisted")
            if (!isWhitelisted) {
                Log.w(TAG, "Package not whitelisted, adding: $currentPackage")
                securePrefs.addWhitelistedApp(currentPackage)
            }
        } else {
            Log.w(TAG, "SecurePreferences is null!")
        }

        if (consentManager != null) {
            val consentLevel = consentManager.getConsentLevel(currentPackage)
            Log.d(TAG, "Package consent level: $currentPackage = $consentLevel")
            if (consentLevel == com.visualmapper.companion.security.ConsentManager.ConsentLevel.NONE) {
                Log.w(TAG, "No consent, granting FULL for: $currentPackage")
                consentManager.grantConsent(
                    packageName = currentPackage,
                    level = com.visualmapper.companion.security.ConsentManager.ConsentLevel.FULL,
                    purpose = "Smart Explorer capture"
                )
            }
        } else {
            Log.w(TAG, "ConsentManager is null!")
        }

        // Get all UI elements (with retry for Samsung accessibility bouncing)
        var elements = accessibilityService.getUITree()
        var uiRetry = 0
        while (elements.isEmpty() && uiRetry < 5) {
            uiRetry++
            Log.w(TAG, "getUITree() returned 0 elements, retrying... (attempt $uiRetry/5)")
            kotlinx.coroutines.delay(500) // Wait 500ms for window content to populate
            // Re-get accessibility service in case it reconnected
            val refreshedService = VisualMapperAccessibilityService.getInstance()
            if (refreshedService != null) {
                elements = refreshedService.getUITree()
            }
        }
        Log.d(TAG, "getUITree() returned ${elements.size} elements (after $uiRetry retries)")

        // Convert to our data models
        val clickableElements = mutableListOf<ClickableElement>()
        val scrollableContainers = mutableListOf<ScrollableContainer>()
        val textElements = mutableListOf<TextElement>()
        val inputFields = mutableListOf<InputField>()

        for (element in elements) {
            val bounds = ElementBounds(
                x = element.bounds.x,
                y = element.bounds.y,
                width = element.bounds.width,
                height = element.bounds.height
            )

            val elementId = generateElementId(
                element.resourceId,
                element.text,
                element.className,
                bounds
            )

            // Debug: Check anonymous elements
            val isAnon = element.resourceId.isEmpty() && element.text.isEmpty()
            if (isAnon && element.className.contains("LinearLayout")) {
                Log.d(TAG, "DEBUG ANON LinearLayout: resourceId='${element.resourceId}' text='${element.text}' bounds=$bounds -> ID=$elementId")
            }

            // Categorize element
            val isEditText = element.className.contains("EditText", ignoreCase = true)

            when {
                element.isClickable -> {
                    clickableElements.add(ClickableElement(
                        elementId = elementId,
                        resourceId = element.resourceId.takeIf { it.isNotEmpty() },
                        text = element.text.takeIf { it.isNotEmpty() },
                        contentDescription = element.contentDescription.takeIf { it.isNotEmpty() },
                        className = element.className,
                        centerX = element.bounds.centerX,
                        centerY = element.bounds.centerY,
                        bounds = bounds
                    ))
                }
                element.isScrollable -> {
                    scrollableContainers.add(ScrollableContainer(
                        elementId = elementId,
                        resourceId = element.resourceId.takeIf { it.isNotEmpty() },
                        className = element.className,
                        bounds = bounds,
                        scrollDirection = ScrollDirection.VERTICAL
                    ))
                }
                isEditText -> {
                    inputFields.add(InputField(
                        elementId = elementId,
                        resourceId = element.resourceId.takeIf { it.isNotEmpty() },
                        hint = null,
                        text = element.text.takeIf { it.isNotEmpty() },
                        className = element.className,
                        centerX = element.bounds.centerX,
                        centerY = element.bounds.centerY,
                        bounds = bounds
                    ))
                }
                element.text.isNotEmpty() -> {
                    textElements.add(TextElement(
                        elementId = elementId,
                        resourceId = element.resourceId.takeIf { it.isNotEmpty() },
                        text = element.text,
                        contentDescription = element.contentDescription.takeIf { it.isNotEmpty() },
                        className = element.className,
                        centerX = element.bounds.centerX,
                        centerY = element.bounds.centerY,
                        bounds = bounds,
                        sensorType = detectSensorType(element.text)
                    ))
                }
            }
        }

        // =====================================================================
        // PARENT-CHILD RELATIONSHIP ANALYSIS
        // Filter out text elements that are labels inside clickable elements
        // =====================================================================
        val filteredTextElements = textElements.filter { textEl ->
            val textBounds = textEl.bounds
            // Check if this text element is fully contained within any clickable element
            val isInsideClickable = clickableElements.any { clickEl ->
                val clickBounds = clickEl.bounds
                // Text is inside clickable if its bounds are fully contained
                textBounds.x >= clickBounds.x &&
                textBounds.y >= clickBounds.y &&
                (textBounds.x + textBounds.width) <= (clickBounds.x + clickBounds.width) &&
                (textBounds.y + textBounds.height) <= (clickBounds.y + clickBounds.height)
            }
            if (isInsideClickable) {
                Log.d(TAG, "Filtered text '${textEl.text.take(20)}' - it's a label inside a clickable element")
            }
            !isInsideClickable  // Keep only text NOT inside clickables
        }.toMutableList()

        Log.d(TAG, "Parent-child filter: ${textElements.size} -> ${filteredTextElements.size} text elements (${textElements.size - filteredTextElements.size} labels removed)")

        // Compute screen ID using ONLY package + activity (stable, no dynamic content)
        val screenId = ScreenIdComputer.computeScreenId(currentActivity, currentPackage)

        return ExploredScreen(
            screenId = screenId,
            activity = currentActivity,
            packageName = currentPackage,
            clickableElements = clickableElements,
            scrollableContainers = scrollableContainers,
            textElements = filteredTextElements,
            inputFields = inputFields,
            landmarks = emptyList()  // No longer used for screen ID
        )
    }

    private fun detectSensorType(text: String): SuggestedSensorType {
        return when {
            text.matches(Regex("^\\d+%$")) -> SuggestedSensorType.PERCENTAGE
            text.matches(Regex("^\\$[\\d,.]+$")) -> SuggestedSensorType.CURRENCY
            text.matches(Regex("^[\\d,.]+$")) -> SuggestedSensorType.NUMBER
            text.lowercase() in listOf("on", "off", "yes", "no", "true", "false") ->
                SuggestedSensorType.BOOLEAN
            else -> SuggestedSensorType.TEXT
        }
    }

    // =========================================================================
    // Scroll-to-Enable Button Detection (Terms & Conditions screens)
    // =========================================================================

    /**
     * Patterns that indicate a button that needs scrolling to enable.
     * These are typically "Accept", "Agree", "Acknowledge", "Continue" buttons
     * that are disabled until the user scrolls to the bottom of the content.
     */
    private val scrollToEnablePatterns = listOf(
        "acknowledge", "accept", "agree", "continue", "confirm", "i agree",
        "i accept", "i acknowledge", "got it", "proceed", "submit", "next"
    )

    /**
     * Detect if a screen has a disabled "scroll-to-enable" button.
     * Returns the disabled button element if found, null otherwise.
     */
    private fun detectDisabledScrollButton(elements: List<UIElement>): UIElement? {
        Log.i(TAG, "=== CHECKING FOR DISABLED SCROLL-TO-ENABLE BUTTONS ===")
        Log.i(TAG, "Checking ${elements.size} elements for T&C button patterns")

        var foundAnyMatch = false
        for (element in elements) {
            val text = element.text.lowercase().trim()
            val contentDesc = element.contentDescription.lowercase().trim()
            val className = element.className.lowercase()

            // Skip if text is too long (not a button label - probably T&C content)
            if (text.length > 50) continue

            // Only check button-like elements
            val isButtonLike = className.contains("button") ||
                className.contains("textview") ||
                className.contains("view")

            if (!isButtonLike) continue

            // Check if text matches T&C button patterns
            val matchesPattern = scrollToEnablePatterns.any { pattern ->
                text == pattern ||  // Exact match
                text.startsWith(pattern) ||  // Starts with pattern
                contentDesc == pattern ||
                contentDesc.startsWith(pattern)
            }

            if (matchesPattern) {
                foundAnyMatch = true
                Log.i(TAG, "BUTTON MATCH: text='${element.text}' desc='${element.contentDescription}' " +
                    "isClickable=${element.isClickable} className=${element.className}")

                // Check if button is NOT clickable (disabled)
                if (!element.isClickable) {
                    Log.i(TAG, ">>> FOUND DISABLED BUTTON: '${element.text}' - WILL SCROLL <<<")
                    return element
                } else {
                    Log.i(TAG, "Button is CLICKABLE (enabled) - no scroll needed")
                }
            }
        }

        if (!foundAnyMatch) {
            Log.d(TAG, "No T&C button patterns found (checked button-like elements only)")
        }
        return null
    }

    /**
     * Handle a screen with a disabled T&C button by scrolling to the bottom.
     * Returns true if the button was enabled after scrolling.
     */
    private suspend fun handleScrollToEnableButton(disabledButton: UIElement): Boolean {
        Log.i(TAG, "=== SCROLL-TO-ENABLE: Attempting to enable button '${disabledButton.text}' ===")

        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false

        // Get screen dimensions for scroll
        val windowManager = getSystemService(WindowManager::class.java)
        val display = windowManager.defaultDisplay
        val size = android.graphics.Point()
        display.getRealSize(size)
        val screenWidth = size.x
        val screenHeight = size.y

        // Scroll from center-bottom to center-top (scroll down to reveal bottom content)
        val startX = screenWidth / 2
        val startY = (screenHeight * 0.75).toInt()  // Start at 75% from top
        val endY = (screenHeight * 0.15).toInt()    // End at 15% from top (longer scroll)

        val maxScrollAttempts = 30  // Many scrolls for very long T&C pages

        // Perform ALL scrolls - no early termination
        for (scrollAttempt in 1..maxScrollAttempts) {
            Log.i(TAG, "=== SCROLL $scrollAttempt/$maxScrollAttempts to enable button ===")

            // Perform aggressive scroll (swipe up to scroll down)
            val scrolled = accessibilityService.gestureDispatcher.swipe(
                startX, startY, startX, endY, 300  // Fast swipe
            )

            if (!scrolled) {
                Log.w(TAG, "Scroll gesture failed on attempt $scrollAttempt - retrying")
                delay(200)
                // Try again immediately
                accessibilityService.gestureDispatcher.swipe(startX, startY, startX, endY, 300)
            }

            delay(400)  // Wait for content to load

            // Re-check if button is now enabled
            val elements = accessibilityService.getUITree()

            // Check for button enabled
            for (element in elements) {
                val text = element.text.lowercase()
                val contentDesc = element.contentDescription.lowercase()

                val matchesPattern = scrollToEnablePatterns.any { pattern ->
                    text.contains(pattern) || contentDesc.contains(pattern)
                }

                if (matchesPattern && element.isClickable) {
                    Log.i(TAG, "=== SUCCESS: Button '${element.text}' ENABLED after $scrollAttempt scrolls ===")
                    return true
                }
            }
        }

        // Did all scrolls but button still not enabled
        Log.w(TAG, "=== SCROLL-TO-ENABLE FAILED after $maxScrollAttempts scrolls ===")
        return false
    }

    // =========================================================================
    // Element Queue Management
    // =========================================================================

    /**
     * Queue clickable elements from a screen for exploration.
     * Delegates to ElementQueueManager for modularity.
     */
    private fun queueClickableElements(screen: ExploredScreen) {
        val config = state?.config ?: return
        val explorationQueue = state?.explorationQueue ?: return
        val visitedElements = state?.visitedElements ?: emptySet()

        queueManager.queueClickableElements(
            screen = screen,
            explorationQueue = explorationQueue,
            visitedElements = visitedElements,
            config = config,
            visitedNavigationTabs = visitedNavigationTabs,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            statusBarHeight = statusBarHeight,
            navBarHeight = navBarHeight
        )
    }

    /**
     * Check if element is likely a navigation element (tabs, menu items, buttons with nav icons).
     * Used in QUICK mode to skip non-navigation elements.
     * Delegates to ElementQueueManager.
     */
    private fun isLikelyNavigationElement(element: ClickableElement): Boolean =
        queueManager.isLikelyNavigationElement(element, screenWidth, screenHeight)

    // =========================================================================
    // NON-DESTRUCTIVE EXPLORATION - Toggle Detection & Revert
    // =========================================================================

    /**
     * Check if element is a toggle/switch/checkbox.
     */
    private fun isToggleElement(element: ClickableElement): Boolean {
        val className = element.className.lowercase()
        val toggleClasses = listOf(
            "switch", "togglebutton", "checkbox", "compoundbutton",
            "switchcompat", "materialswitch", "appcompatcheckbox"
        )
        if (toggleClasses.any { className.contains(it) }) {
            return true
        }

        // Check resource ID patterns
        val resourceId = element.resourceId?.lowercase() ?: ""
        val togglePatterns = listOf("switch", "toggle", "checkbox", "enable", "disable")
        if (togglePatterns.any { resourceId.contains(it) }) {
            return true
        }

        // Check if it's checkable via accessibility
        return element.actionType == ClickableActionType.TOGGLE
    }

    /**
     * Get the current checked state of a toggle element from accessibility.
     * Returns null if unable to determine.
     */
    private fun getElementCheckedState(element: ClickableElement): Boolean? {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return null
        val rootNode = accessibilityService.rootInActiveWindow ?: return null

        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        try {
            // Find the node at element's position
            findNodesAtPosition(rootNode, element.centerX, element.centerY, nodes)

            for (node in nodes) {
                // Check if this node is checkable
                if (node.isCheckable) {
                    return node.isChecked
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting checked state", e)
        } finally {
            // Always recycle all nodes in finally block to prevent double-recycle
            nodes.forEach {
                try { it.recycle() } catch (e: Exception) { /* Already recycled */ }
            }
        }

        return null
    }

    /**
     * Find accessibility nodes at a specific position.
     */
    private fun findNodesAtPosition(
        node: android.view.accessibility.AccessibilityNodeInfo,
        x: Int,
        y: Int,
        results: MutableList<android.view.accessibility.AccessibilityNodeInfo>
    ) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        if (rect.contains(x, y)) {
            // Add this node if it's checkable
            if (node.isCheckable) {
                results.add(android.view.accessibility.AccessibilityNodeInfo.obtain(node))
            }

            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findNodesAtPosition(child, x, y, results)
                child.recycle()
            }
        }
    }

    /**
     * Revert all changed toggles back to their original state.
     * Called at the end of exploration for non-destructive mode.
     */
    private suspend fun revertChangedToggles() {
        val toggles = state?.changedToggles ?: return
        if (toggles.isEmpty()) {
            Log.i(TAG, "No toggles to revert")
            return
        }

        Log.i(TAG, "=== REVERTING ${toggles.size} CHANGED TOGGLES ===")

        // Group by screen for efficient navigation
        val togglesByScreen = toggles.groupBy { it.screenId }

        for ((screenId, screenToggles) in togglesByScreen) {
            Log.i(TAG, "Reverting ${screenToggles.size} toggles on screen $screenId")

            // Try to navigate to the screen
            val navigated = navigateToScreen(screenId)
            if (!navigated) {
                Log.w(TAG, "Could not navigate to screen $screenId to revert toggles")
                continue
            }

            delay(1000) // Wait for screen to load

            // Revert each toggle on this screen
            for (toggle in screenToggles.reversed()) { // Reverse order to undo in LIFO order
                val currentChecked = getToggleStateAtPosition(toggle.centerX, toggle.centerY)
                if (currentChecked != null && currentChecked != toggle.originalChecked) {
                    Log.i(TAG, "Reverting toggle '${toggle.text ?: toggle.resourceId}': $currentChecked -> ${toggle.originalChecked}")
                    performTap(toggle.centerX, toggle.centerY)
                    delay(500) // Wait for toggle animation
                } else if (currentChecked == toggle.originalChecked) {
                    Log.d(TAG, "Toggle '${toggle.text ?: toggle.resourceId}' already in original state")
                }
            }
        }

        Log.i(TAG, "=== TOGGLE REVERT COMPLETE ===")
        toggles.clear()
    }

    /**
     * Get toggle state at a specific position.
     */
    private fun getToggleStateAtPosition(x: Int, y: Int): Boolean? {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return null
        val rootNode = accessibilityService.rootInActiveWindow ?: return null

        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        try {
            findNodesAtPosition(rootNode, x, y, nodes)

            for (node in nodes) {
                if (node.isCheckable) {
                    return node.isChecked
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting toggle state at position", e)
        } finally {
            // Always recycle all nodes in finally block to prevent double-recycle
            nodes.forEach {
                try { it.recycle() } catch (e: Exception) { /* Already recycled */ }
            }
        }

        return null
    }

    /**
     * FIX 1: Check if an element exists in the LIVE accessibility tree with IDENTITY VERIFICATION.
     * This verifies the element is still present in the real UI by matching:
     * - resourceId, OR text, OR contentDescription (not just coordinates!)
     * Returns true only if a matching element exists at the expected position.
     */
    private fun isElementInLiveAccessibilityTree(element: ClickableElement): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        val rootNode = accessibilityService.rootInActiveWindow ?: return false

        val nodes = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        try {
            findMatchingNodeAtPosition(rootNode, element, nodes)
            if (nodes.isEmpty()) {
                Log.d(TAG, "isElementInLiveAccessibilityTree: No matching element found")
                Log.d(TAG, "  - elementId: ${element.elementId}, resourceId: ${element.resourceId}, text: ${element.text}")
            }
            return nodes.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking element in live tree", e)
            return false
        } finally {
            // Always recycle all nodes in finally block
            nodes.forEach {
                try { it.recycle() } catch (e: Exception) { /* Already recycled */ }
            }
        }
    }

    /**
     * ROBUST element verification before ANY tap or overlay display.
     * This is the SINGLE GATE for all element interactions - ensures overlays
     * only show for elements that actually exist on the current screen.
     *
     * Performs 5 checks:
     * 1. Re-capture current screen (fresh from accessibility tree)
     * 2. Verify we're on the expected screen
     * 3. Verify element exists in FRESH screen data
     * 4. Verify bounds haven't drifted too far (50px max)
     * 5. Verify element exists in LIVE accessibility tree
     *
     * @param element The element to verify
     * @param expectedScreenId The screen ID where this element was found
     * @param actionDescription Description for logging (e.g., "hamburger_menu", "nav_tab")
     * @return true if element verified and safe to tap, false if stale
     */
    private suspend fun verifyElementBeforeAction(
        element: ClickableElement,
        expectedScreenId: String,
        actionDescription: String
    ): Boolean {
        // 1. Re-capture current screen (fresh from accessibility tree)
        val currentScreen = captureCurrentScreen()
        if (currentScreen == null) {
            Log.w(TAG, "[$actionDescription] Cannot verify - screen capture failed")
            return false
        }

        // 2. Verify we're on the expected screen
        if (currentScreen.screenId != expectedScreenId) {
            Log.w(TAG, "[$actionDescription] SCREEN MISMATCH: Expected $expectedScreenId, on ${currentScreen.screenId}")
            // Clear stale elements from old screen
            val removedCount = state?.explorationQueue?.count { it.screenId == expectedScreenId } ?: 0
            if (removedCount > 0) {
                state?.explorationQueue?.removeAll { it.screenId == expectedScreenId }
                Log.i(TAG, "[$actionDescription] Cleared $removedCount stale elements from $expectedScreenId")
            }
            return false
        }

        // 3. Verify element exists in FRESH screen data
        val freshElement = currentScreen.clickableElements.find { it.elementId == element.elementId }
        if (freshElement == null) {
            Log.w(TAG, "[$actionDescription] Element ${element.elementId} not found in fresh screen")
            return false
        }

        // 4. Verify bounds haven't drifted too far (50px max)
        val driftX = kotlin.math.abs(freshElement.centerX - element.centerX)
        val driftY = kotlin.math.abs(freshElement.centerY - element.centerY)
        if (driftX > 50 || driftY > 50) {
            Log.w(TAG, "[$actionDescription] Element ${element.elementId} drifted too far: ${driftX}x, ${driftY}y")
            return false
        }

        // 5. Verify element exists in LIVE accessibility tree
        if (!isElementInLiveAccessibilityTree(freshElement)) {
            Log.w(TAG, "[$actionDescription] Element ${element.elementId} not in live accessibility tree")
            return false
        }

        Log.d(TAG, "[$actionDescription] Element verified: ${element.elementId}")
        return true
    }

    /**
     * Find accessibility nodes at a specific position that MATCH the target element's identity.
     * Uses resourceId, text, or contentDescription for identity verification when available.
     * Falls back to coordinate-only matching for elements without identifiers.
     */
    private fun findMatchingNodeAtPosition(
        node: android.view.accessibility.AccessibilityNodeInfo,
        targetElement: ClickableElement,
        results: MutableList<android.view.accessibility.AccessibilityNodeInfo>
    ) {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        if (rect.contains(targetElement.centerX, targetElement.centerY)) {
            if (node.isClickable || node.isFocusable || node.isCheckable) {
                val nodeResourceId = node.viewIdResourceName
                val nodeText = node.text?.toString()
                val nodeContentDesc = node.contentDescription?.toString()

                // Check if target element has ANY identifiers we can match against
                val targetHasResourceId = !targetElement.resourceId.isNullOrEmpty()
                val targetHasText = !targetElement.text.isNullOrEmpty()
                val targetHasContentDesc = !targetElement.contentDescription.isNullOrEmpty()
                val targetHasAnyIdentifier = targetHasResourceId || targetHasText || targetHasContentDesc

                if (targetHasAnyIdentifier) {
                    // Target has identifiers - require at least one match
                    val resourceIdMatch = targetHasResourceId && nodeResourceId == targetElement.resourceId
                    val textMatch = targetHasText && nodeText == targetElement.text
                    val contentDescMatch = targetHasContentDesc && nodeContentDesc == targetElement.contentDescription

                    if (resourceIdMatch || textMatch || contentDescMatch) {
                        results.add(android.view.accessibility.AccessibilityNodeInfo.obtain(node))
                    }
                } else {
                    // ROBUST FIX: Anonymous elements require EXACT bounds match (not just center point)
                    // This prevents matching wrong elements at similar positions on different screens
                    val boundsMatch = kotlin.math.abs(rect.left - targetElement.bounds.x) < 20 &&
                                      kotlin.math.abs(rect.top - targetElement.bounds.y) < 20 &&
                                      kotlin.math.abs(rect.width() - targetElement.bounds.width) < 20 &&
                                      kotlin.math.abs(rect.height() - targetElement.bounds.height) < 20

                    if (boundsMatch) {
                        results.add(android.view.accessibility.AccessibilityNodeInfo.obtain(node))
                    }
                }
            }

            // Check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findMatchingNodeAtPosition(child, targetElement, results)
                child.recycle()
            }
        }
    }

    // =========================================================================
    // MANUAL MODE - Learn from User Navigation
    // =========================================================================

    /**
     * MANUAL MODE: Watch user navigate the app and learn from their actions.
     * Instead of auto-tapping, we observe accessibility events and record transitions.
     * This is "imitation learning" - the ML learns optimal paths from human demonstration.
     */
    private suspend fun runManualExploration(initialScreen: ExploredScreen) {
        val config = state?.config ?: return
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return

        Log.i(TAG, "=== MANUAL EXPLORATION STARTED ===")
        Log.i(TAG, "Navigate the app manually. Your actions will be recorded for ML training.")
        showToast("Manual Mode: Navigate the app. Your actions are being recorded!")

        // Track last known screen for transition detection
        var lastScreenId = initialScreen.screenId
        var lastScreenHash = initialScreen.screenId
        var manualActions = 0
        val maxManualActions = config.maxElements

        // Update overlay - the overlay observes progress StateFlow
        updateProgress()

        while (state?.status == ExplorationStatus.IN_PROGRESS && manualActions < maxManualActions && !shouldStop) {
            // Check timeout
            val elapsedMs = System.currentTimeMillis() - (state?.startTime ?: System.currentTimeMillis())
            if (elapsedMs > config.maxDurationMs) {
                Log.i(TAG, "Manual exploration timeout reached")
                break
            }

            // Poll for screen changes (user navigated to new screen)
            delay(500)  // Check every 500ms

            // Capture current screen
            val currentScreen = captureCurrentScreen()
            if (currentScreen == null) {
                continue
            }

            // Detect if screen changed
            if (currentScreen.screenId != lastScreenHash) {
                manualActions++
                val previousScreenId = lastScreenId  // Save before updating

                // Get the element that was clicked (captured by accessibility service)
                val clickedElement = accessibilityService.lastClickedElement
                val isBackNavigation = clickedElement == null &&
                    state?.exploredScreens?.containsKey(currentScreen.screenId) == true

                val clickedElementId = when {
                    // System back button/gesture (no click event, returning to known screen)
                    isBackNavigation -> "system_back"

                    // UI element was clicked
                    clickedElement != null -> {
                        val resId = clickedElement.resourceId?.substringAfterLast("/") ?: ""
                        val text = clickedElement.text?.take(20)?.replace(Regex("[^a-zA-Z0-9]"), "_") ?: ""
                        val className = clickedElement.className?.substringAfterLast(".") ?: ""
                        when {
                            resId.isNotEmpty() -> "${resId}_${className.lowercase()}"
                            text.isNotEmpty() -> "${text}_${className.lowercase()}"
                            clickedElement.bounds != null -> {
                                val cx = clickedElement.bounds.centerX()
                                val cy = clickedElement.bounds.centerY()
                                "_${className.lowercase()}_${cx}_${cy}"
                            }
                            else -> "user_click_$manualActions"
                        }
                    }

                    // Unknown navigation (no click, new screen)
                    else -> "user_action_$manualActions"
                }

                Log.i(TAG, "=== MANUAL: User navigated to new screen ===")
                Log.i(TAG, "  From: $previousScreenId -> To: ${currentScreen.screenId}")
                Log.i(TAG, "  Clicked element: $clickedElementId")
                Log.i(TAG, "  Activity: ${currentScreen.activity}")
                Log.i(TAG, "  Clickable elements: ${currentScreen.clickableElements.size}")

                // Record the transition with the actual element that was clicked
                state?.navigationGraph?.recordTransition(
                    previousScreenId,
                    clickedElementId,
                    currentScreen.screenId,
                    currentScreen.activity
                )

                // Clear the clicked element after using it
                accessibilityService.clearLastClickedElement()

                // Add screen to explored screens
                if (!state?.exploredScreens?.containsKey(currentScreen.screenId)!!) {
                    state?.exploredScreens?.put(currentScreen.screenId, currentScreen)
                    navigationStack.addFirst(currentScreen.screenId)
                    Log.i(TAG, "MANUAL: Discovered new screen: ${currentScreen.screenId}")
                    diagnostics.recordScreenDiscovered()

                    // Reward Q-learning for user's navigation choice (with actual element ID)
                    qLearning?.updateQ(
                        screenHash = previousScreenId,
                        actionKey = clickedElementId,
                        reward = 1.0f,  // User's choice is always positive
                        nextScreenHash = currentScreen.screenId
                    )
                    checkPeriodicPublish()
                }

                // Update tracking AFTER recording
                lastScreenId = currentScreen.screenId
                lastScreenHash = currentScreen.screenId
                _currentScreen.value = currentScreen

                // Update progress - overlay observes the StateFlow
                updateProgress()
                Log.i(TAG, "MANUAL: ${state?.exploredScreens?.size ?: 0} screens discovered")
                Log.d(TAG, "MANUAL: Transition recorded: $previousScreenId --[$clickedElementId]--> ${currentScreen.screenId}")
            }
        }

        Log.i(TAG, "=== MANUAL EXPLORATION COMPLETED ===")
        Log.i(TAG, "  Total manual actions: $manualActions")
        Log.i(TAG, "  Screens discovered: ${state?.exploredScreens?.size}")
        Log.i(TAG, "  Navigation transitions: ${state?.navigationGraph?.getStats()?.totalTransitions}")

        showToast("Manual exploration complete! ${state?.exploredScreens?.size} screens learned.")
        state?.status = ExplorationStatus.COMPLETED
        state?.endTime = System.currentTimeMillis()

        // Update progress to notify overlay (triggers auto-hide)
        updateProgress()

        // Save Q-learning state
        qLearning?.saveAll()

        // Publish logs to ML training server
        publishExplorationLogsToMqtt()

        // Log navigation intelligence
        val conditionalElements = state?.navigationGraph?.getConditionalElements()?.size ?: 0
        val blockerScreens = state?.navigationGraph?.getBlockerScreens()?.size ?: 0
        Log.i(TAG, "=== NAVIGATION INTELLIGENCE ===")
        Log.i(TAG, "  Conditional elements: $conditionalElements")
        Log.i(TAG, "  Blocker screens detected: $blockerScreens")

        // Publish final status
        publishExplorationStatusToMqtt("completed", "Manual exploration finished")
    }

    // =========================================================================
    // DEEP MODE - Full Element Analysis
    // =========================================================================

    /**
     * Queue clickable elements in DEEP mode (minimal exclusions).
     * Delegates to ElementQueueManager for modularity.
     */
    private fun queueClickableElementsDeep(screen: ExploredScreen) {
        val config = state?.config ?: return
        val explorationQueue = state?.explorationQueue ?: return
        val visitedElements = state?.visitedElements ?: emptySet()

        queueManager.queueClickableElementsDeep(
            screen = screen,
            explorationQueue = explorationQueue,
            visitedElements = visitedElements,
            config = config,
            visitedNavigationTabs = visitedNavigationTabs,
            screenWidth = screenWidth,
            screenHeight = screenHeight
        )
    }

    /**
     * MANDATORY MAIN NAVIGATION DISCOVERY
     *
     * This runs BEFORE the main exploration loop to discover all main sections of the app.
     * It finds bottom navigation tabs, hamburger menus, and other primary navigation elements,
     * clicks each one, and records the resulting screens.
     *
     * This ensures we have a map of the app's main structure before diving into any section.
     *
     * @param homeScreen The initial/home screen of the app
     * @return Number of main screens discovered
     */
    private suspend fun discoverMainNavigation(homeScreen: ExploredScreen): Int {
        Log.i(TAG, "=== DISCOVERING MAIN NAVIGATION ===")
        var discoveredScreens = 0
        val homeScreenId = homeScreen.screenId

        // Find all bottom navigation elements
        val bottomNavThreshold = screenHeight - 200
        val bottomNavElements = homeScreen.clickableElements.filter { element ->
            element.centerY > bottomNavThreshold &&
            element.bounds.height > 40 && element.bounds.height < 150
        }

        Log.i(TAG, "Found ${bottomNavElements.size} potential bottom nav elements")

        if (bottomNavElements.isEmpty()) {
            // No bottom nav - try finding hamburger menu or other main nav
            Log.i(TAG, "No bottom nav found - checking for hamburger menu...")
            val menuElement = findHamburgerMenu(homeScreen)
            if (menuElement != null) {
                Log.i(TAG, "Found hamburger menu - verifying before tap...")

                // ROBUST: Verify element before showing overlay or tapping
                if (!verifyElementBeforeAction(menuElement, homeScreen.screenId, "hamburger_menu")) {
                    Log.w(TAG, "Hamburger menu element stale - skipping")
                    return discoveredScreens
                }

                // Element verified - safe to show overlay and tap
                tapAnimationOverlay.showTapAnimation(menuElement.centerX.toFloat(), menuElement.centerY.toFloat())
                performTap(menuElement.centerX, menuElement.centerY)
                delay(1500)
                val menuScreen = captureCurrentScreen()
                if (menuScreen != null && menuScreen.screenId != homeScreenId) {
                    Log.i(TAG, "Discovered menu screen: ${menuScreen.screenId}")
                    state?.exploredScreens?.put(menuScreen.screenId, menuScreen)
                    discoveredScreens++

                    // Queue menu items for later exploration
                    queueClickableElements(menuScreen)

                    // Go back to home
                    performSmartBack()
                    delay(1000)
                }
            }
            return discoveredScreens
        }

        // Click each bottom nav tab and record the screen
        for ((index, navElement) in bottomNavElements.withIndex()) {
            val navId = navElement.resourceId ?: navElement.elementId

            // Skip if already visited
            if (visitedNavigationTabs.contains(navId)) {
                Log.d(TAG, "Nav tab already visited: $navId")
                continue
            }

            Log.i(TAG, "Clicking nav tab ${index + 1}/${bottomNavElements.size}: $navId")

            // ROBUST: Verify element before EACH nav tab tap (UI may have changed during loop)
            // Re-capture screen to get fresh state
            val freshScreen = captureCurrentScreen()
            if (freshScreen == null || freshScreen.screenId != homeScreen.screenId) {
                Log.w(TAG, "Screen changed during nav tab iteration - aborting loop")
                break
            }

            // Find fresh version of this nav element
            val freshNavElement = freshScreen.clickableElements.find { it.elementId == navElement.elementId }
            if (freshNavElement == null) {
                Log.w(TAG, "Nav element $navId no longer exists - skipping")
                continue
            }

            if (!isElementInLiveAccessibilityTree(freshNavElement)) {
                Log.w(TAG, "Nav element $navId not in live tree - skipping")
                continue
            }

            // Use FRESH coordinates - show tap animation and click
            tapAnimationOverlay.showTapAnimation(freshNavElement.centerX.toFloat(), freshNavElement.centerY.toFloat())
            val tapSuccess = performTap(freshNavElement.centerX, freshNavElement.centerY)
            if (!tapSuccess) {
                Log.w(TAG, "Failed to tap nav element: $navId")
                continue
            }

            delay(1500) // Wait for screen transition

            // Check if we're still in the target app
            if (!isInTargetApp()) {
                Log.w(TAG, "Left app after clicking nav - recovering...")
                recoverFromMinimizedApp(state?.packageName ?: "")
                continue
            }

            // Capture the new screen
            val newScreen = captureCurrentScreen()
            if (newScreen == null) {
                Log.w(TAG, "Failed to capture screen after nav tap")
                continue
            }

            // Mark nav tab as visited
            visitedNavigationTabs.add(navId)

            // Check if this is a new screen
            if (!state?.exploredScreens?.containsKey(newScreen.screenId)!!) {
                Log.i(TAG, "=== DISCOVERED NEW MAIN SECTION: ${newScreen.screenId} ===")
                state?.exploredScreens?.put(newScreen.screenId, newScreen)
                navigationStack.addFirst(newScreen.screenId)
                _currentScreen.value = newScreen
                discoveredScreens++

                // Queue elements from this screen for later exploration
                queueClickableElements(newScreen)

                // Update progress
                updateProgress()
            } else {
                Log.d(TAG, "Screen already known: ${newScreen.screenId}")
            }
        }

        // Return to home screen for consistent starting point
        Log.i(TAG, "Returning to home screen after main nav discovery...")
        while (navigationStack.firstOrNull() != homeScreenId && navigationStack.size > 1) {
            val backSuccess = performSmartBack()
            if (!backSuccess) break
            delay(500)
            navigationStack.removeFirstOrNull()
        }

        Log.i(TAG, "=== MAIN NAVIGATION DISCOVERY COMPLETE: $discoveredScreens screens ===")
        return discoveredScreens
    }

    /**
     * Find hamburger menu (3-line icon) typically in top-left corner
     */
    private fun findHamburgerMenu(screen: ExploredScreen): ClickableElement? {
        return screen.clickableElements.find { element ->
            val isTopLeft = element.centerX < 150 && element.centerY < 200
            val hasMenuPattern = element.resourceId?.lowercase()?.let { id ->
                id.contains("menu") || id.contains("drawer") || id.contains("hamburger") ||
                id.contains("nav") || id.contains("toggle")
            } ?: false
            val hasMenuDesc = element.contentDescription?.lowercase()?.let { desc ->
                desc.contains("menu") || desc.contains("drawer") || desc.contains("navigation") ||
                desc.contains("open")
            } ?: false

            isTopLeft && (hasMenuPattern || hasMenuDesc ||
                element.className.contains("ImageButton") || element.className.contains("ImageView"))
        }
    }

    /**
     * FORCE EXPLORATION OF BOTTOM NAVIGATION TABS
     * Scans current screen for bottom nav elements we haven't visited yet.
     * This ensures we explore ALL major sections of the app (not just one tab).
     * Returns number of nav tabs queued.
     */
    private suspend fun queueUnvisitedBottomNavTabs(): Int {
        val currentScreen = captureCurrentScreen() ?: return 0
        val bottomNavThreshold = screenHeight - 200
        var queued = 0

        Log.i(TAG, "=== CHECKING FOR UNVISITED BOTTOM NAVIGATION TABS ===")
        Log.i(TAG, "Screen height: $screenHeight, threshold: $bottomNavThreshold")
        Log.i(TAG, "Already visited nav tabs: ${visitedNavigationTabs.size}")

        for (element in currentScreen.clickableElements) {
            // Check if this is a bottom navigation element
            val isBottomNav = element.centerY > bottomNavThreshold &&
                element.bounds != null &&
                element.bounds!!.height > 40 && element.bounds!!.height < 150

            if (!isBottomNav) continue

            val navTabId = element.resourceId ?: element.elementId

            // Skip if already visited
            if (visitedNavigationTabs.contains(navTabId)) {
                Log.d(TAG, "Nav tab already visited: $navTabId")
                continue
            }

            // Skip if already visited - use COMPOSITE KEY for per-screen tracking
            val compositeKey = "${currentScreen.screenId}:${element.elementId}"
            if (state?.visitedElements?.contains(compositeKey) == true) {
                Log.d(TAG, "Nav tab already in visited elements: $compositeKey")
                continue
            }

            // Skip if element has exceeded retry limit (loop detection)
            val retryKey = "${currentScreen.screenId}:${element.elementId}"
            val retryCount = state?.elementRetryCount?.getOrDefault(retryKey, 0) ?: 0
            if (retryCount >= 4) {
                Log.d(TAG, "Nav tab skipped - exceeded retry limit ($retryCount): $retryKey")
                continue
            }

            // Queue this unvisited nav tab
            // In SYSTEMATIC mode, use position-based priority (bottom = low priority)
            // In ADAPTIVE mode, use normal priority (let Q-learning decide)
            // In other modes, use maximum priority to ensure coverage
            val isSystematicMode = state?.config?.strategy == ExplorationStrategy.SYSTEMATIC
            val isAdaptiveMode = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
            val navPriority = when {
                isSystematicMode -> {
                    // Bottom nav elements should have LOW priority in SYSTEMATIC mode
                    val row = element.centerY / 100
                    val col = element.centerX / 100
                    val readingOrder = (row * 100) + col
                    1000 - readingOrder.coerceIn(0, 999)
                }
                isAdaptiveMode -> 30  // Lower priority in adaptive mode
                else -> 100  // High priority in other modes
            }

            Log.i(TAG, "=== QUEUEING UNVISITED NAV TAB: ${element.elementId} at y=${element.centerY} (priority=$navPriority, systematic=$isSystematicMode, adaptive=$isAdaptiveMode) ===")
            state?.explorationQueue?.addFirst(ExplorationTarget(
                type = ExplorationTargetType.TAP_ELEMENT,
                screenId = currentScreen.screenId,
                elementId = element.elementId,
                priority = navPriority,
                bounds = element.bounds
            ))
            queued++
        }

        Log.i(TAG, "=== QUEUED $queued UNVISITED BOTTOM NAV TABS ===")
        return queued
    }

    /**
     * COMPREHENSIVE VERIFICATION: Before completing exploration:
     * 1. Check current screen for unvisited elements
     * 2. Check NavigationGraph for unexplored screens
     * 3. Try to navigate to unexplored screens using known paths
     * Returns the number of unvisited elements/screens found and queued.
     */
    private suspend fun verifyCoverageAndQueueMissed(): Int {
        Log.i(TAG, "=== VERIFICATION: Comprehensive coverage check ===")

        val config = state?.config ?: return 0
        val visitedElements = state?.visitedElements ?: return 0
        val navigationGraph = state?.navigationGraph ?: return 0
        var unvisitedCount = 0

        // === STEP 1: Check current screen for unvisited elements ===
        val currentScreen = captureCurrentScreen()
        if (currentScreen != null) {
            for (element in currentScreen.clickableElements) {
                // Use COMPOSITE KEY for per-screen tracking
                val compositeKey = "${currentScreen.screenId}:${element.elementId}"
                if (visitedElements.contains(compositeKey)) continue
                if (shouldExcludeFromQueue(element, config)) continue

                Log.d(TAG, "Verification: Found unvisited element on current screen: $compositeKey")
                state?.explorationQueue?.add(ExplorationTarget(
                    type = ExplorationTargetType.TAP_ELEMENT,
                    screenId = currentScreen.screenId,
                    elementId = element.elementId,
                    priority = 15,
                    bounds = element.bounds
                ))
                unvisitedCount++
            }

            // Check scrollable containers
            for (container in currentScreen.scrollableContainers) {
                val compositeKey = "${currentScreen.screenId}:${container.elementId}"
                if (visitedElements.contains(compositeKey)) continue
                if (!container.fullyScrolled) {
                    Log.d(TAG, "Verification: Found unscrolled container: ${container.elementId}")
                    state?.explorationQueue?.add(ExplorationTarget(
                        type = ExplorationTargetType.SCROLL_CONTAINER,
                        screenId = currentScreen.screenId,
                        scrollContainerId = container.elementId,
                        priority = 10,
                        bounds = container.bounds
                    ))
                    unvisitedCount++
                }
            }

            // Mark current screen as fully explored if no unvisited elements
            if (unvisitedCount == 0) {
                navigationGraph.markFullyExplored(currentScreen.screenId)
            }
        }

        // === STEP 2: Check for unexplored screens in NavigationGraph ===
        val unexploredScreens = navigationGraph.getUnexploredScreens()
        if (unexploredScreens.isNotEmpty() && currentScreen != null) {
            Log.i(TAG, "Verification: Found ${unexploredScreens.size} unexplored screens in NavigationGraph")

            for (targetScreen in unexploredScreens) {
                // Try to find a path to this screen
                val path = navigationGraph.findPath(currentScreen.screenId, targetScreen)
                if (path != null && path.isNotEmpty()) {
                    // We know how to get there! Queue navigation
                    val (fromScreen, elementId) = path.first()
                    Log.d(TAG, "Verification: Queueing navigation to unexplored screen $targetScreen via $elementId")
                    state?.explorationQueue?.add(ExplorationTarget(
                        type = ExplorationTargetType.NAVIGATE_TO_SCREEN,
                        screenId = targetScreen,
                        elementId = elementId,
                        priority = 20 // High priority for unexplored screens
                    ))
                    unvisitedCount++
                } else {
                    Log.d(TAG, "Verification: No known path to screen $targetScreen")
                }
            }
        }

        // === STEP 3: Check ALL explored screens for elements we might have missed ===
        val exploredScreens = state?.exploredScreens?.values ?: emptyList()
        for (screen in exploredScreens) {
            if (screen.screenId == currentScreen?.screenId) continue // Already checked

            var screenUnvisited = 0
            for (element in screen.clickableElements) {
                // Use COMPOSITE KEY for per-screen tracking
                val compositeKey = "${screen.screenId}:${element.elementId}"
                if (visitedElements.contains(compositeKey)) continue
                if (shouldExcludeFromQueue(element, config)) continue

                // Found an unvisited element on a previously seen screen
                state?.explorationQueue?.add(ExplorationTarget(
                    type = ExplorationTargetType.TAP_ELEMENT,
                    screenId = screen.screenId,
                    elementId = element.elementId,
                    priority = 12,
                    bounds = element.bounds
                ))
                screenUnvisited++
                unvisitedCount++
            }

            if (screenUnvisited > 0) {
                Log.d(TAG, "Verification: Found $screenUnvisited unvisited elements on screen ${screen.screenId}")
            }
        }

        // === STATS ===
        val graphStats = navigationGraph.getStats()
        val totalVisited = visitedElements.size
        val totalScreens = state?.exploredScreens?.size ?: 0
        val queueSize = state?.explorationQueue?.size ?: 0

        Log.i(TAG, "=== VERIFICATION COMPLETE ===")
        Log.i(TAG, "  Unvisited items queued: $unvisitedCount")
        Log.i(TAG, "  Total visited elements: $totalVisited")
        Log.i(TAG, "  Total explored screens: $totalScreens")
        Log.i(TAG, "  NavigationGraph: ${graphStats.totalScreens} screens, ${graphStats.totalTransitions} transitions")
        Log.i(TAG, "  Fully explored screens: ${graphStats.fullyExploredScreens}/${graphStats.totalScreens}")
        Log.i(TAG, "  Queue size after verification: $queueSize")

        return unvisitedCount
    }

    /**
     * Check if an element should be excluded from the exploration queue.
     * Delegates to ElementQueueManager for modularity.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun shouldExcludeFromQueue(element: ClickableElement, config: ExplorationConfig): Boolean =
        queueManager.shouldExcludeFromQueue(element, screenWidth, screenHeight, statusBarHeight, navBarHeight)

    // =========================================================================
    // Element Priority (Delegated to ElementPriorityCalculator)
    // =========================================================================

    /** Check if an element is likely a back button. */
    private fun isLikelyBackButton(element: ClickableElement): Boolean =
        priorityCalculator.isLikelyBackButton(element)

    /** Check if an element should be excluded from capture (system UI only). */
    private fun shouldExcludeFromCapture(element: ClickableElement): Boolean =
        priorityCalculator.shouldExcludeFromCapture(element, screenHeight, statusBarHeight, navBarHeight)

    /** Calculate the priority of an element for exploration ordering. */
    private fun calculateElementPriority(element: ClickableElement, screen: ExploredScreen? = null): Int =
        priorityCalculator.calculatePriority(
            element = element,
            screen = screen,
            isAdaptiveMode = state?.config?.strategy == ExplorationStrategy.ADAPTIVE,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            visitedNavigationTabs = visitedNavigationTabs
        )

    // =========================================================================
    // Element Interaction
    // =========================================================================

    private suspend fun processElementTap(target: ExplorationTarget) {
        val screenId = target.screenId
        val elementId = target.elementId ?: return

        // === FIX: Early validation - Is this screen reachable? ===
        // Defense-in-depth check (main loop also validates this)
        val currentScreenId = _currentScreen.value?.screenId
        if (screenId != currentScreenId && !navigationStack.contains(screenId)) {
            Log.w(TAG, "processElementTap: Screen $screenId not reachable (current: $currentScreenId, stack: ${navigationStack.take(3)})")
            // Remove all stale elements from this unreachable screen
            val removedCount = state?.explorationQueue?.count { it.screenId == screenId } ?: 0
            if (removedCount > 0) {
                state?.explorationQueue?.removeAll { it.screenId == screenId }
                Log.i(TAG, "Removed $removedCount stale elements from unreachable screen $screenId")
            }
            return
        }

        // === HANDLE UNREACHABLE SCREENS ===
        // If target screen is not reachable, try alternative navigation paths
        val stackTopScreenId = navigationStack.firstOrNull()
        if (stackTopScreenId != screenId && screenId !in navigationStack) {
            val currentScreen = captureCurrentScreen()
            if (currentScreen != null) {
                val config = state?.config ?: ExplorationConfig()
                val visitedElements = state?.visitedElements ?: mutableSetOf()

                // STRATEGY 1: Try bottom nav tabs first - these can reveal whole new sections
                val screenHeight = currentScreen.clickableElements.maxOfOrNull { it.centerY } ?: 1920
                val bottomNavThreshold = screenHeight - 200
                val bottomNavElements = currentScreen.clickableElements.filter { element ->
                    element.centerY > bottomNavThreshold &&
                    element.bounds.height > 40 &&
                    element.bounds.height < 150
                }
                var addedNavTabs = 0
                for (navElement in bottomNavElements) {
                    val navKey = "${currentScreen.screenId}:${navElement.elementId}"
                    if (visitedElements.contains(navKey)) continue

                    // Check if already in queue
                    val alreadyQueued = state?.explorationQueue?.any {
                        it.screenId == currentScreen.screenId && it.elementId == navElement.elementId
                    } == true
                    if (alreadyQueued) continue

                    // Use strategy-appropriate priority
                    val isSystematic = state?.config?.strategy == ExplorationStrategy.SYSTEMATIC
                    val isAdaptive = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
                    val navPriority = calculateStrategyPriority(navElement.centerX, navElement.centerY, isSystematic, isAdaptive, 25, 100)

                    state?.explorationQueue?.addFirst(ExplorationTarget(
                        type = ExplorationTargetType.TAP_ELEMENT,
                        screenId = currentScreen.screenId,
                        elementId = navElement.elementId,
                        priority = navPriority,
                        bounds = navElement.bounds
                    ))
                    addedNavTabs++
                    Log.i(TAG, "Screen $screenId not reachable - added nav tab ${navElement.elementId} with priority $navPriority (systematic=$isSystematic, adaptive=$isAdaptive)")
                }

                if (addedNavTabs > 0) {
                    Log.i(TAG, "Screen $screenId not reachable - added $addedNavTabs bottom nav tabs to try")
                    // Don't re-queue unreachable element yet - let's explore nav tabs first
                    if (target.priority > -30) {
                        val requeued = target.copy(priority = target.priority - 5)
                        state?.explorationQueue?.addLast(requeued)
                    }
                    return
                }

                // STRATEGY 2: Add unvisited elements from current screen
                var addedCurrentScreenElements = 0
                val isSystematic = state?.config?.strategy == ExplorationStrategy.SYSTEMATIC
                val isAdaptive = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
                for (element in currentScreen.clickableElements) {
                    val compositeKey = "${currentScreen.screenId}:${element.elementId}"
                    if (visitedElements.contains(compositeKey)) continue
                    if (shouldExcludeFromQueue(element, config)) continue

                    // Check if already in queue
                    val alreadyQueued = state?.explorationQueue?.any {
                        it.screenId == currentScreen.screenId && it.elementId == element.elementId
                    } == true
                    if (alreadyQueued) continue

                    // Use strategy-appropriate priority
                    val elemPriority = when {
                        isSystematic -> calculateStrategyPriority(element.centerX, element.centerY, true, false)
                        isAdaptive -> calculateElementPriority(element, currentScreen)
                        else -> 50
                    }

                    state?.explorationQueue?.addFirst(ExplorationTarget(
                        type = ExplorationTargetType.TAP_ELEMENT,
                        screenId = currentScreen.screenId,
                        elementId = element.elementId,
                        priority = elemPriority,
                        bounds = element.bounds
                    ))
                    addedCurrentScreenElements++
                }

                if (addedCurrentScreenElements > 0) {
                    Log.i(TAG, "Screen $screenId not reachable - added $addedCurrentScreenElements elements from CURRENT screen ${currentScreen.screenId} (systematic=$isSystematic)")
                }
            }

            // THEN: Track screen reach failures and TRY SMART RECOVERY
            val failures = (state?.screenReachFailures?.get(screenId) ?: 0) + 1
            state?.screenReachFailures?.put(screenId, failures)

            // === HUMAN-LIKE REASONING: Why can't I reach this screen? ===
            currentScreen?.let { safeScreen ->
                when (failures) {
                    1 -> {
                        // First failure: Maybe there's hidden navigation - try scrolling
                        Log.i(TAG, "REASONING: Can't reach $screenId - let me check if I missed something on current screen")
                        val currentScrollables = safeScreen.scrollableContainers
                        if (currentScrollables.isNotEmpty()) {
                            Log.i(TAG, "REASONING: Found ${currentScrollables.size} scrollable containers - queueing scrolls to reveal hidden nav")
                            val isAdaptive = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
                            currentScrollables.forEach { container ->
                                val scrollKey = "${safeScreen.screenId}:${container.elementId}"
                                if (state?.visitedElements?.contains(scrollKey) != true) {
                                    state?.explorationQueue?.addFirst(ExplorationTarget(
                                        type = ExplorationTargetType.SCROLL_CONTAINER,
                                        screenId = safeScreen.screenId,
                                        elementId = container.elementId,
                                        priority = if (isAdaptive) 20 else 60,
                                        bounds = container.bounds
                                    ))
                                }
                            }
                        }
                    }
                    2 -> {
                        // Second failure: Look for menu/nav patterns I might have missed
                        Log.i(TAG, "REASONING: Still can't reach $screenId - checking for menu/navigation patterns")
                        val navPatterns = listOf("menu", "nav", "more", "options", "settings", "hamburger", "drawer")
                        val potentialNavElements = safeScreen.clickableElements.filter { el ->
                            navPatterns.any { pattern ->
                                el.elementId.lowercase().contains(pattern) ||
                                el.text?.lowercase()?.contains(pattern) == true
                            }
                        }
                        if (potentialNavElements.isNotEmpty()) {
                            Log.i(TAG, "REASONING: Found ${potentialNavElements.size} potential nav elements to try")
                            val isAdaptive = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
                            potentialNavElements.forEach { el ->
                                val key = "${safeScreen.screenId}:${el.elementId}"
                                if (state?.visitedElements?.contains(key) != true) {
                                    state?.explorationQueue?.addFirst(ExplorationTarget(
                                        type = ExplorationTargetType.TAP_ELEMENT,
                                        screenId = safeScreen.screenId,
                                        elementId = el.elementId,
                                        priority = if (isAdaptive) 20 else 55,
                                        bounds = el.bounds
                                    ))
                                }
                            }
                        }
                    }
                }
            }

            if (failures >= 3) {
                // Third failure: Give up but provide actionable diagnosis
                state?.unreachableScreens?.add(screenId)

                // Diagnose WHY the screen is unreachable
                val hasIncomingTransitions = state?.navigationGraph?.hasIncomingTransitions(screenId) == true
                val wasEverVisited = state?.exploredScreens?.containsKey(screenId) == true
                val targetActivity = state?.exploredScreens?.get(screenId)?.activity

                val reason = when {
                    !wasEverVisited -> "Screen never visited - may require gesture, deep nav, or specific app state"
                    !hasIncomingTransitions -> "No recorded navigation path - was initial screen or reached via swipe/gesture"
                    else -> "Navigation path failed - screen may be conditional (login required?) or time-dependent"
                }

                val suggestion = if (targetActivity != null) {
                    "Try: adb shell am start -n ${state?.packageName}/$targetActivity"
                } else {
                    "Try manual exploration or swipe gestures to find hidden navigation"
                }

                Log.w(TAG, "Screen $screenId marked UNREACHABLE: $reason")
                Log.i(TAG, "SUGGESTION: $suggestion")
                state?.issues?.add(ExplorationIssue(
                    screenId = screenId,
                    elementId = elementId,
                    issueType = IssueType.RECOVERY_FAILED,
                    description = "Screen unreachable: $reason. $suggestion"
                ))

                // Remove all elements from this screen from queue
                state?.explorationQueue?.removeAll { it.screenId == screenId }
            } else if (target.priority > -15) {
                // Reduced threshold from -30 to -15 for faster give-up
                val requeued = target.copy(priority = target.priority - 5)
                state?.explorationQueue?.addLast(requeued)
                Log.d(TAG, "Screen $screenId not reachable (failure $failures/3) - re-queued element $elementId with priority ${requeued.priority}")
            } else {
                Log.w(TAG, "Screen $screenId not reachable and priority too low - discarding element $elementId")
            }
            return
        }

        Log.d(TAG, "Processing tap: $elementId on screen $screenId")

        // === LOOP DETECTION ===
        val retryKey = "${screenId}:${elementId}"
        val retryCount = state?.elementRetryCount?.getOrDefault(retryKey, 0) ?: 0
        val maxRetries = 4  // Increased from 2 - elements may need more attempts due to UI lag

        if (retryCount >= maxRetries) {
            Log.w(TAG, "LOOP DETECTED: Skipping element after $retryCount retries: $elementId")
            state?.issues?.add(ExplorationIssue(
                screenId = screenId,
                elementId = elementId,
                issueType = IssueType.ELEMENT_STUCK,
                description = "Skipped after $retryCount failed attempts"
            ))
            return  // Skip this element, continue with queue
        }
        state?.elementRetryCount?.put(retryKey, retryCount + 1)

        // === TRY SCROLLING ON RETRY 2 ===
        // If element failed once, try scrolling to reveal it before retrying
        if (retryCount == 1) {
            val currentScreen = captureCurrentScreen()
            if (currentScreen?.scrollableContainers?.isNotEmpty() == true) {
                Log.i(TAG, "Element failed once, trying scroll to reveal: $elementId")
                val scrollable = currentScreen.scrollableContainers.first()
                val scrollCenterX = scrollable.bounds.x + scrollable.bounds.width / 2
                val scrollCenterY = scrollable.bounds.y + scrollable.bounds.height / 2
                performScroll(scrollCenterX, scrollCenterY, ScrollDirection.VERTICAL)
                delay(1000)
                // Re-capture to get updated element positions
                val refreshedScreen = captureCurrentScreen()
                if (refreshedScreen != null) {
                    state?.exploredScreens?.put(refreshedScreen.screenId, refreshedScreen)
                }
            }
        }

        // === CHECK FOR DANGEROUS PATTERN ===
        if (isDangerousElement(target, elementId)) {
            Log.w(TAG, "Skipping dangerous element: $elementId")
            state?.issues?.add(ExplorationIssue(
                screenId = screenId,
                elementId = elementId,
                issueType = IssueType.DANGEROUS_ELEMENT,
                description = "Element matches pattern that previously caused app to close"
            ))
            return
        }

        // Navigate to screen if needed
        if (navigationStack.firstOrNull() != screenId) {
            if (!navigateToScreen(screenId)) {
                Log.w(TAG, "Failed to navigate to screen: $screenId")
                return
            }
        }

        // === CRITICAL FIX: Re-capture current screen to get FRESH element positions ===
        // The stored screen may have stale coordinates if the UI has changed
        val currentScreen = captureCurrentScreen()
        if (currentScreen == null) {
            Log.w(TAG, "Failed to capture current screen for element tap")
            return
        }

        // Verify we're still on the expected screen
        if (currentScreen.screenId != screenId) {
            Log.w(TAG, "Screen changed! Expected $screenId, now on ${currentScreen.screenId}")
            // DON'T re-queue - the element is from a different screen and will never match
            // The new screen will be handled by the main exploration loop
            // Remove any remaining elements from the old screen to prevent stuck loops
            val oldScreenElementCount = state?.explorationQueue?.count { it.screenId == screenId } ?: 0
            if (oldScreenElementCount > 0) {
                state?.explorationQueue?.removeAll { it.screenId == screenId }
                Log.i(TAG, "Removed $oldScreenElementCount stale elements from old screen $screenId")
            }
            return
        }

        // === FIX 2: SCREEN ELEMENT COUNT VALIDATION ===
        // If screen layout changed dramatically, elements may be stale even with same screenId
        val originalScreen = state?.exploredScreens?.get(screenId)
        val originalElementCount = originalScreen?.clickableElements?.size ?: 0
        val currentElementCount = currentScreen.clickableElements.size
        if (kotlin.math.abs(originalElementCount - currentElementCount) > 3) {
            Log.w(TAG, "=== SCREEN STRUCTURE CHANGED: Element count $originalElementCount -> $currentElementCount ===")
            // Clear stale elements from this screen's queue
            val staleCount = state?.explorationQueue?.count { it.screenId == screenId } ?: 0
            if (staleCount > 0) {
                state?.explorationQueue?.removeAll { it.screenId == screenId }
                Log.i(TAG, "Cleared $staleCount stale elements from queue due to screen structure change")
            }
            // Re-queue fresh elements from the new screen
            queueClickableElements(currentScreen)
            return
        }

        // Find the element on the CURRENT screen (not the cached one)
        val element = currentScreen.clickableElements.find { it.elementId == elementId }
        if (element == null) {
            Log.w(TAG, "Element $elementId not found on current screen - may have scrolled away or UI changed")
            // Don't re-queue - the element is gone
            return
        }

        // === FIX 4: BOUNDS DRIFT DETECTION ===
        // Check if element position drifted significantly from queued target (layout shift, scroll, etc.)
        val storedBounds = target.bounds
        if (storedBounds != null) {
            val freshBounds = element.bounds
            val centerDriftX = kotlin.math.abs(storedBounds.centerX - freshBounds.centerX)
            val centerDriftY = kotlin.math.abs(storedBounds.centerY - freshBounds.centerY)
            val totalDrift = centerDriftX + centerDriftY
            if (totalDrift > 100) {  // 100 pixels total drift threshold
                Log.w(TAG, "=== ELEMENT BOUNDS DRIFT: $elementId drifted ${totalDrift}px (${centerDriftX}x, ${centerDriftY}y) ===")
                Log.w(TAG, "  Stored: (${storedBounds.centerX}, ${storedBounds.centerY}) -> Fresh: (${freshBounds.centerX}, ${freshBounds.centerY})")
                // Continue with FRESH bounds (element variable already has fresh data)
            }
        }

        // Pre-tap safety check - verify we're in the target app
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        // FIXED: Check rootInActiveWindow FIRST (direct read, more reliable)
        val currentPackage = accessibilityService?.rootInActiveWindow?.packageName?.toString()
            ?: accessibilityService?.currentPackage?.value
        val targetPackage = state?.packageName

        if (currentPackage != null && targetPackage != null && currentPackage != targetPackage) {
            Log.w(TAG, "Not in target app before tap! Current: $currentPackage, Target: $targetPackage")

            // Check relaunch counter to prevent infinite loops
            if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
                Log.e(TAG, "=== PRE-TAP RELAUNCH LOOP: Skipping tap, too many relaunches ($consecutiveRelaunchAttempts) ===")
                return
            }

            consecutiveRelaunchAttempts++
            Log.i(TAG, "Re-launching target app (attempt $consecutiveRelaunchAttempts) and skipping current tap")
            if (launchApp(targetPackage)) {
                delay(state?.config?.transitionWait ?: 1500)
                // Re-capture the screen after re-launch to refresh element queue
                val refreshedScreen = captureCurrentScreen()
                if (refreshedScreen != null) {
                    Log.i(TAG, "Re-captured screen after re-launch: ${refreshedScreen.screenId}")
                    if (!state?.exploredScreens?.containsKey(refreshedScreen.screenId)!!) {
                        state?.exploredScreens?.put(refreshedScreen.screenId, refreshedScreen)
                        queueClickableElements(refreshedScreen)
                    }
                    // Reset counter since we successfully captured a screen
                    consecutiveRelaunchAttempts = 0
                }
            } else {
                Log.e(TAG, "Failed to re-launch target app")
            }
            // Skip this tap - coordinates are stale after app change
            return
        }

        // Detailed logging of what we're about to tap
        Log.i(TAG, "=== TAPPING ELEMENT ===")
        Log.i(TAG, "  elementId: $elementId")
        Log.i(TAG, "  resourceId: ${element.resourceId}")
        Log.i(TAG, "  text: ${element.text}")
        Log.i(TAG, "  contentDescription: ${element.contentDescription}")
        Log.i(TAG, "  className: ${element.className}")
        Log.i(TAG, "  position: (${element.centerX}, ${element.centerY})")
        Log.i(TAG, "  bounds: x=${element.bounds.x}, y=${element.bounds.y}, w=${element.bounds.width}, h=${element.bounds.height}")
        Log.i(TAG, "=======================")

        // Mark as visited (use COMPOSITE KEY: screenId:elementId for per-screen tracking)
        // This allows same-ID elements on different screens to be explored independently
        val compositeKey = "$screenId:$elementId"
        state?.visitedElements?.add(compositeKey)
        Log.d(TAG, "Marked visited: $compositeKey")
        element.explored = true

        // Track for diagnostics
        diagnostics.recordElementExplored()

        // Update coverage tracking stats
        markElementVisited(screenId, elementId)

        // Track bottom navigation tabs we've visited
        val bottomNavThreshold = screenHeight - 200
        if (element.centerY > bottomNavThreshold && element.bounds.height > 40 && element.bounds.height < 150) {
            val navTabId = element.resourceId ?: elementId
            if (!visitedNavigationTabs.contains(navTabId)) {
                visitedNavigationTabs.add(navTabId)
                Log.i(TAG, "=== VISITED BOTTOM NAV TAB: $navTabId === (total visited: ${visitedNavigationTabs.size})")
            }
        }

        // Capture screen state before tap for Q-learning
        val beforeScreen = captureCurrentScreen()
        val beforeScreenId = beforeScreen?.screenId
        val beforeScreenHash = beforeScreen?.let { qLearning?.computeScreenHash(it) }
        val actionKey = qLearning?.getActionKey(element)

        // Update HumanInLoopManager's current screen for hit-testing (imitation learning)
        humanInLoopManager.setCurrentScreen(beforeScreen)

        // === CRITICAL: VERIFY ELEMENT EXISTS BEFORE SHOWING ANY OVERLAYS ===
        // These checks must happen BEFORE intent visualization to avoid showing stale element positions

        // Screen mismatch check - if screen changed since element was queued, abort immediately
        val preOverlayScreen = captureCurrentScreen()
        if (preOverlayScreen != null && preOverlayScreen.screenId != screenId) {
            Log.w(TAG, "=== PRE-OVERLAY SCREEN MISMATCH: Queued for $screenId, now on ${preOverlayScreen.screenId} ===")
            val staleCount = state?.explorationQueue?.count { it.screenId == screenId } ?: 0
            if (staleCount > 0) {
                state?.explorationQueue?.removeAll { it.screenId == screenId }
                Log.i(TAG, "Cleared $staleCount stale elements from screen $screenId BEFORE overlay")
            }
            return  // Abort - screen changed, don't show overlay for wrong screen
        }

        // Verify element exists in live accessibility tree BEFORE showing overlay
        if (!isElementInLiveAccessibilityTree(element)) {
            Log.w(TAG, "=== PRE-OVERLAY STALE ELEMENT: $elementId not found in live accessibility tree! ===")
            Log.w(TAG, "  Element: ${element.text ?: element.resourceId ?: element.className}")
            Log.w(TAG, "  Position: (${element.centerX}, ${element.centerY})")
            // Don't show overlay - element is stale
            return
        }

        // === NON-DESTRUCTIVE EXPLORATION: Track toggle state before clicking ===
        val isToggle = isToggleElement(element)
        if (isToggle) {
            val currentChecked = getElementCheckedState(element)
            if (currentChecked != null) {
                Log.i(TAG, "=== TOGGLE DETECTED: ${element.text ?: element.resourceId} - currently ${if (currentChecked) "ON" else "OFF"} ===")
                state?.changedToggles?.add(ChangedToggle(
                    screenId = screenId,
                    elementId = elementId,
                    resourceId = element.resourceId,
                    text = element.text,
                    centerX = element.centerX,
                    centerY = element.centerY,
                    originalChecked = currentChecked
                ))
            }
        }

        // === HUMAN-IN-THE-LOOP: Intent Visualization & Veto ===
        if (humanInLoopManager.isEnabled.value) {
            // Get Q-value confidence for this action
            val actionKey = qLearning?.getActionKey(element) ?: elementId
            val confidence = beforeScreenHash?.let {
                qLearning?.getQValue(it, actionKey) ?: 0f
            } ?: 0f

            // Show intent and wait for possible veto
            val elementDesc = element.text?.take(20) ?: element.resourceId ?: elementId
            val vetoed = humanInLoopManager.showIntentAndWaitForVeto(
                bounds = android.graphics.Rect(
                    element.bounds.x,
                    element.bounds.y,
                    element.bounds.x + element.bounds.width,
                    element.bounds.y + element.bounds.height
                ),
                confidence = confidence,
                elementDescription = elementDesc,
                vetoWindowMs = state?.config?.vetoWindowMs ?: 1500L
            )

            if (vetoed) {
                Log.i(TAG, "Action VETOED by user for element: $elementId")
                // Record veto in manager (handles Q-update and human feedback)
                if (beforeScreenHash != null) {
                    humanInLoopManager.recordVeto(beforeScreenHash, actionKey)
                }
                return  // Abort this action
            }
        }

        // === FINAL PRE-TAP VERIFICATION ===
        // Quick re-check after veto window (screen may have changed during the wait)
        if (!isElementInLiveAccessibilityTree(element)) {
            Log.w(TAG, "=== POST-VETO STALE ELEMENT: $elementId no longer in accessibility tree! ===")
            humanInLoopManager.hideIntentOverlay()
            return
        }

        // Show tap animation before performing the tap
        tapAnimationOverlay.showTapAnimation(element.centerX.toFloat(), element.centerY.toFloat())

        // Human-in-the-Loop: Register bot tap intent before performing
        humanInLoopManager.registerBotTapIntent(element.centerX, element.centerY, elementId)

        // Perform tap
        val success = performTap(element.centerX, element.centerY)

        // Human-in-the-Loop: Clear bot tap intent after tap completes
        humanInLoopManager.clearBotTapIntent()

        if (!success) {
            Log.w(TAG, "Tap failed for element: $elementId")
            return
        }

        // === STATE MACHINE: Element successfully tapped ===
        stateMachine.processEvent(ExplorationStateMachine.Event.ELEMENT_TAPPED)

        // Wait for potential transition and UI to stabilize
        delay(state?.config?.transitionWait ?: 2500)
        waitForUIStabilization(state?.config?.stabilizationWait ?: 3000)

        // Capture screen after tap
        val afterScreen = captureCurrentScreen()
        if (afterScreen == null) {
            Log.w(TAG, "Failed to capture screen after tap")
            return
        }

        // Check if we left the target app (tapped back/home/etc)
        // FIXED: Check rootInActiveWindow FIRST (direct read, more reliable)
        val postTapPackage = accessibilityService?.rootInActiveWindow?.packageName?.toString()
            ?: accessibilityService?.currentPackage?.value

        if (postTapPackage != null && targetPackage != null && postTapPackage != targetPackage) {
            Log.w(TAG, "Left target app after tap! Current: $postTapPackage, Target: $targetPackage")

            // === PLAY STORE REDIRECT HANDLING ===
            // Apps needing updates often redirect to Play Store
            // Quick recovery: press BACK to return to app
            if (postTapPackage == "com.android.vending") {
                Log.w(TAG, "Redirected to Play Store - pressing BACK to return")
                val service = VisualMapperAccessibilityService.getInstance()
                service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(2000)

                // Check if we're back in target app
                val afterBackPackage = service?.rootInActiveWindow?.packageName?.toString()
                if (afterBackPackage == targetPackage) {
                    Log.i(TAG, "Successfully returned from Play Store to $targetPackage")
                    // Mark element as causing redirect (not dangerous, just skip)
                    element.actionType = ClickableActionType.NO_EFFECT
                    state?.issues?.add(ExplorationIssue(
                        screenId = screenId,
                        elementId = elementId,
                        issueType = IssueType.ELEMENT_STUCK,
                        description = "Element redirected to Play Store - skipped"
                    ))
                    // Continue with exploration - don't do full recovery
                    return
                }
                // Still not in target app, fall through to full recovery
            }

            // === PERMISSION DIALOG HANDLING ===
            // Apps may request runtime permissions which opens the system permission controller
            // Wait for user to complete the permission dialog, then try to return
            val permissionDialogPackages = listOf(
                "com.android.permissioncontroller",
                "com.google.android.permissioncontroller",
                "com.android.packageinstaller",
                "com.google.android.packageinstaller",
                "com.samsung.android.permissioncontroller"
            )
            if (permissionDialogPackages.any { postTapPackage?.contains(it) == true }) {
                Log.i(TAG, "=== PERMISSION DIALOG DETECTED: $postTapPackage ===")
                Log.i(TAG, "Waiting for user to complete permission dialog...")

                // Wait for user to handle the permission dialog (up to 30 seconds)
                val service = VisualMapperAccessibilityService.getInstance()
                var waitTime = 0
                val maxWaitTime = 30000 // 30 seconds
                var returnedToApp = false

                while (waitTime < maxWaitTime && !shouldStop) {
                    delay(1000)
                    waitTime += 1000

                    val currentPkg = service?.rootInActiveWindow?.packageName?.toString()
                    if (currentPkg == targetPackage) {
                        Log.i(TAG, "User completed permission dialog - back in $targetPackage")
                        returnedToApp = true
                        break
                    }

                    // Still in dialog - log every 5 seconds
                    if (waitTime % 5000 == 0) {
                        Log.d(TAG, "Still waiting for permission dialog... (${waitTime/1000}s)")
                    }
                }

                if (returnedToApp) {
                    // Successfully returned - don't mark element as dangerous
                    element.actionType = ClickableActionType.TRIGGERS_DIALOG
                    state?.issues?.add(ExplorationIssue(
                        screenId = screenId,
                        elementId = elementId,
                        issueType = IssueType.ELEMENT_STUCK,
                        description = "Element triggered permission dialog - user completed"
                    ))
                    // Re-capture screen and continue exploration
                    delay(1500)
                    val refreshedScreen = captureCurrentScreen()
                    if (refreshedScreen != null) {
                        _currentScreen.value = refreshedScreen
                        if (!state?.exploredScreens?.containsKey(refreshedScreen.screenId)!!) {
                            state?.exploredScreens?.put(refreshedScreen.screenId, refreshedScreen)
                            queueClickableElements(refreshedScreen)
                        }
                    }
                    return
                } else {
                    Log.w(TAG, "Permission dialog timed out after ${waitTime/1000}s - trying to return")
                    // Try pressing BACK to dismiss
                    service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(2000)
                }
                // Fall through to full recovery if still not in app
            }

            // === SETTINGS REDIRECT HANDLING ===
            // Apps may redirect to Settings for permissions or app settings
            if (postTapPackage == "com.android.settings" || postTapPackage == "com.samsung.android.app.settings") {
                Log.w(TAG, "Redirected to Settings - pressing BACK to return")
                val service = VisualMapperAccessibilityService.getInstance()
                service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                delay(2000)

                val afterBackPackage = service?.rootInActiveWindow?.packageName?.toString()
                if (afterBackPackage == targetPackage) {
                    Log.i(TAG, "Successfully returned from Settings to $targetPackage")
                    element.actionType = ClickableActionType.NO_EFFECT
                    state?.issues?.add(ExplorationIssue(
                        screenId = screenId,
                        elementId = elementId,
                        issueType = IssueType.ELEMENT_STUCK,
                        description = "Element redirected to Settings - returned"
                    ))
                    return
                }
            }

            // Mark this element as causing app exit
            element.actionType = ClickableActionType.CLOSES_APP

            // === LEARN DANGEROUS PATTERN ===
            val pattern = element.resourceId ?: element.className
            state?.dangerousPatterns?.add(pattern)
            Log.w(TAG, "Marked pattern as dangerous (causes app close): $pattern")

            // === Q-LEARNING UPDATE: App closed = very bad reward ===
            if (beforeScreenHash != null && actionKey != null) {
                val reward = qLearning?.calculateReward(TapResult.CLOSED_APP) ?: -1.5f
                qLearning?.updateQ(beforeScreenHash, actionKey, reward, null)
                qLearning?.markPatternDangerous(element)
                Log.d(TAG, "Q-learning: App closed, reward=$reward")
                checkPeriodicPublish()
            }

            // Log the issue
            state?.issues?.add(ExplorationIssue(
                screenId = screenId,
                elementId = elementId,
                issueType = IssueType.APP_MINIMIZED,
                description = "Element caused app to close: ${element.resourceId ?: element.text ?: element.className}"
            ))

            // Use recovery logic
            if (!recoverFromMinimizedApp(targetPackage)) {
                Log.e(TAG, "Failed to recover from minimized app")
            }
            return
        }

        // Check if we navigated to a new screen
        if (afterScreen.screenId != beforeScreenId) {
            // New screen discovered!
            Log.i(TAG, "Discovered new screen: ${afterScreen.screenId}")
            diagnostics.recordScreenDiscovered()

            // === STATE MACHINE: New screen discovered ===
            stateMachine.processEvent(ExplorationStateMachine.Event.NEW_SCREEN_DISCOVERED)

            element.leadsToScreen = afterScreen.screenId
            element.actionType = ClickableActionType.NAVIGATION

            // === Q-LEARNING UPDATE: New screen = best reward ===
            if (beforeScreenHash != null && actionKey != null) {
                val nextScreenHash = qLearning?.computeScreenHash(afterScreen)
                val reward = qLearning?.calculateReward(TapResult.NEW_SCREEN) ?: 1.0f
                qLearning?.updateQ(beforeScreenHash, actionKey, reward, nextScreenHash)
                Log.d(TAG, "Q-learning: New screen discovered, reward=$reward")
                checkPeriodicPublish()
            }

            // === RESET STUCK DETECTION: New screen means we're making progress ===
            consecutiveNoEffectTaps = 0
            consecutiveRelaunchAttempts = 0
            lastScreenIdForStuckDetection = null

            // === NAVIGATION GRAPH: Record this transition for path planning ===
            // Pass activity name for blocker detection (password, login, setup screens)
            state?.navigationGraph?.recordTransition(screenId, elementId, afterScreen.screenId, afterScreen.activity)

            // Log blocker detection
            val isBlocker = state?.navigationGraph?.isBlockerScreen(afterScreen.screenId) == true
            val blockerTag = if (isBlocker) " [BLOCKER DETECTED]" else ""
            Log.d(TAG, "NavigationGraph: Recorded transition $screenId --[$elementId]--> ${afterScreen.screenId}$blockerTag")

            // === BLOCKER SCREEN HANDLING ===
            // If we landed on a blocker screen (password, login, setup), go back immediately
            if (isBlocker) {
                Log.i(TAG, "=== BLOCKER SCREEN - Navigating back to continue exploration ===")
                Log.i(TAG, "  Blocker screen: ${afterScreen.screenId}")
                Log.i(TAG, "  Activity: ${afterScreen.activity}")

                // Don't queue elements from blocker screen - they require authentication
                // Just go back and continue with other elements
                val backSuccess = performSmartBack()
                if (backSuccess) {
                    Log.i(TAG, "Successfully navigated back from blocker screen")
                    // Update current screen after going back
                    val returnedScreen = captureCurrentScreen()
                    if (returnedScreen != null) {
                        _currentScreen.value = returnedScreen
                        navigationStack.addFirst(returnedScreen.screenId)
                    }
                } else {
                    Log.w(TAG, "Failed to navigate back from blocker screen")
                }
                return  // Don't process this screen further
            }

            // === LOW-PRIORITY SCREEN HANDLING (Settings, About, etc.) ===
            // If we landed on a settings/about screen, record it but navigate back
            // These screens are low-value for sensor extraction
            if (queueManager.isLowPriorityScreen(afterScreen)) {
                Log.i(TAG, "=== LOW-PRIORITY SCREEN - Navigating back to continue exploration ===")
                Log.i(TAG, "  Low-priority screen: ${afterScreen.screenId}")
                Log.i(TAG, "  Activity: ${afterScreen.activity}")

                // Still record the screen (for completeness) but don't queue elements
                state?.exploredScreens?.put(afterScreen.screenId, afterScreen)

                // Navigate back to explore more valuable screens
                val backSuccess = performSmartBack()
                if (backSuccess) {
                    Log.i(TAG, "Successfully navigated back from low-priority screen")
                    val returnedScreen = captureCurrentScreen()
                    if (returnedScreen != null) {
                        _currentScreen.value = returnedScreen
                        navigationStack.addFirst(returnedScreen.screenId)
                    }
                } else {
                    Log.w(TAG, "Failed to navigate back from low-priority screen")
                }
                return  // Don't queue elements from this screen
            }

            // === CHECK FOR SCROLL-TO-ENABLE BUTTONS (T&C screens) ===
            val scrollAccessibility = VisualMapperAccessibilityService.getInstance()
            if (scrollAccessibility != null) {
                val scrollElements = scrollAccessibility.getUITree()
                val disabledScrollButton = detectDisabledScrollButton(scrollElements)
                if (disabledScrollButton != null) {
                    Log.i(TAG, "New screen has disabled T&C button - scrolling to enable")
                    handleScrollToEnableButton(disabledScrollButton)
                    // Re-capture after scroll to get updated clickable elements
                    val refreshedAfterScreen = captureCurrentScreen()
                    if (refreshedAfterScreen != null && refreshedAfterScreen.screenId == afterScreen.screenId) {
                        // Update the screen in our explored screens map
                        state?.exploredScreens?.put(afterScreen.screenId, refreshedAfterScreen)
                        Log.i(TAG, "Updated screen after scroll-to-enable: ${refreshedAfterScreen.clickableElements.size} clickable elements")
                    }
                }
            }

            // Check if this element now has multiple destinations (conditional)
            val elementNav = state?.navigationGraph?.getElementNavigation(screenId, elementId)
            if (elementNav?.isConditional() == true) {
                Log.i(TAG, "=== CONDITIONAL ELEMENT DETECTED: $elementId has ${elementNav.destinations.size} destinations ===")
            }

            // Record transition
            screenTransitions.add(ScreenTransition(
                fromScreenId = screenId,
                toScreenId = afterScreen.screenId,
                triggerElementId = elementId,
                transitionType = TransitionType.FORWARD
            ))

            // Learn navigation for future explorations
            val packageName = state?.packageName ?: ""
            if (packageName.isNotEmpty()) {
                appMapStore.recordTransition(
                    packageName = packageName,
                    fromScreenId = screenId,
                    toScreenId = afterScreen.screenId,
                    elementId = elementId,
                    elementText = element.text,
                    success = true
                )
            }

            // Check if this is a new screen or revisit
            if (!state?.exploredScreens?.containsKey(afterScreen.screenId)!!) {
                state?.exploredScreens?.put(afterScreen.screenId, afterScreen)
                navigationStack.addFirst(afterScreen.screenId)

                // === PHASE 1.6b: Dual-write to ScreenNavigator ===
                val pkgName = state?.packageName ?: ""
                val unexplored = afterScreen.clickableElements.count { el ->
                    val compositeKey = "${afterScreen.screenId}:${el.elementId}"
                    state?.visitedElements?.contains(compositeKey) != true
                }
                screenNavigator.enterScreen(
                    screenId = afterScreen.screenId,
                    packageName = pkgName,
                    activityName = afterScreen.activity,
                    elementCount = afterScreen.clickableElements.size,
                    unexploredElements = unexplored
                )

                _currentScreen.value = afterScreen

                // Learn this screen for future explorations
                if (packageName.isNotEmpty()) {
                    appMapStore.recordScreen(
                        packageName = packageName,
                        screenId = afterScreen.screenId,
                        activityName = afterScreen.activity,
                        title = afterScreen.landmarks.firstOrNull(),
                        hasScrollableContent = afterScreen.scrollableContainers.isNotEmpty(),
                        keyElements = afterScreen.clickableElements.mapNotNull { it.text }.take(5)
                    )
                }

                // === COMPREHENSIVE QUEUE CLEANUP ON ANY SCREEN CHANGE ===
                // When a new screen is discovered, clean up ALL unreachable elements
                // This prevents stale elements from previous screens being tapped
                val isBottomNavSwitch = element.centerY > (screenHeight - 200) &&
                    element.bounds.height > 40 && element.bounds.height < 150

                val beforeQueueSize = state?.explorationQueue?.size ?: 0
                // Remove elements from screens that are not in our navigation stack
                // These screens are unreachable from the current navigation context
                state?.explorationQueue?.removeAll { target ->
                    target.screenId != afterScreen.screenId &&
                    !navigationStack.contains(target.screenId)
                }
                val removed = beforeQueueSize - (state?.explorationQueue?.size ?: 0)
                if (removed > 0) {
                    val navType = if (isBottomNavSwitch) "BOTTOM NAV SECTION SWITCH" else "SCREEN NAVIGATION"
                    Log.i(TAG, "=== $navType: Cleared $removed unreachable elements from queue ===")
                }

                // Queue elements from new screen - use appropriate method for exploration mode
                val config = state?.config ?: ExplorationConfig()
                if (config.goal == ExplorationGoal.COMPLETE_COVERAGE) {
                    queueClickableElementsDeep(afterScreen)
                } else {
                    queueClickableElements(afterScreen)
                }
            } else {
                // Revisit - increment visit count
                state?.exploredScreens?.get(afterScreen.screenId)?.visitCount =
                    (state?.exploredScreens?.get(afterScreen.screenId)?.visitCount ?: 0) + 1
            }

            // Backtrack if configured
            if (state?.config?.backtrackAfterNewScreen == true) {
                delay(state?.config?.actionDelay ?: 500)

                // Use smart back which learns to use UI back buttons if system BACK closes app
                val backSuccess = performSmartBack()

                if (!backSuccess || !isInTargetApp()) {
                    // Back closed the app - log issue and use recovery
                    Log.w(TAG, "Back navigation left the app - using recovery")
                    state?.issues?.add(ExplorationIssue(
                        screenId = afterScreen.screenId,
                        elementId = elementId,
                        issueType = IssueType.BACK_FAILED,
                        description = "Back navigation closed the app from screen ${afterScreen.screenId}"
                    ))

                    if (targetPackage != null) {
                        if (!recoverFromMinimizedApp(targetPackage)) {
                            Log.e(TAG, "Recovery after back failed")
                        }
                    }
                } else {
                    // Successful backtrack - wait for screen to stabilize
                    delay(state?.config?.transitionWait ?: 2500)
                    waitForUIStabilization(2000)

                    // === FIX: Update navigationStack after successful backtrack ===
                    // Remove the new screen we just navigated away from
                    if (navigationStack.firstOrNull() == afterScreen.screenId) {
                        navigationStack.removeFirst()
                        Log.i(TAG, "Removed ${afterScreen.screenId} from navigationStack after backtrack. Stack size: ${navigationStack.size}")
                    }

                    // Re-queue elements on the screen we returned to
                    // This ensures we explore all elements even after navigating away and back
                    val returnedScreen = captureCurrentScreen()
                    if (returnedScreen != null) {
                        Log.i(TAG, "Returned to screen: ${returnedScreen.screenId}")
                        _currentScreen.value = returnedScreen

                        // Update state with current screen (queueClickableElements checks internally)
                        if (!queueManager.isScreenQueued(returnedScreen.screenId)) {
                            queueClickableElements(returnedScreen)
                        }
                    }
                }
            }
        } else {
            // Same screen - element might be a toggle, expand, or no effect
            Log.d(TAG, "Element had no navigation effect: $elementId")

            // === CHECK IF THIS IS A DISABLED T&C BUTTON THAT NEEDS SCROLLING ===
            val elementText = (element.text ?: "").lowercase().trim()
            val isTCButton = scrollToEnablePatterns.any { pattern ->
                elementText == pattern || elementText.startsWith(pattern)
            }

            if (isTCButton && afterScreen.scrollableContainers.isNotEmpty()) {
                Log.i(TAG, "=== T&C BUTTON '$elementText' had no effect - scrolling to enable ===")

                // Scroll to bottom of the page
                val scrollContainer = afterScreen.scrollableContainers.first()
                val centerX = scrollContainer.bounds.x + scrollContainer.bounds.width / 2
                val startY = scrollContainer.bounds.y + (scrollContainer.bounds.height * 0.8).toInt()
                val endY = scrollContainer.bounds.y + (scrollContainer.bounds.height * 0.2).toInt()

                val scrollService = VisualMapperAccessibilityService.getInstance()
                if (scrollService != null) {
                    // Scroll multiple times to reach bottom
                    for (scrollNum in 1..20) {
                        Log.d(TAG, "Scroll $scrollNum/20 to enable T&C button")
                        scrollService.gestureDispatcher.swipe(centerX, startY, centerX, endY, 300)
                        delay(400)
                    }

                    // Try tapping the button again
                    delay(500)
                    Log.i(TAG, "Retrying tap on T&C button after scrolling")
                    val retrySuccess = performTap(element.centerX, element.centerY)
                    if (retrySuccess) {
                        delay(1500)
                        val retryScreen = captureCurrentScreen()
                        if (retryScreen != null && retryScreen.screenId != screenId) {
                            Log.i(TAG, "=== T&C BUTTON WORKED after scroll! Navigated to ${retryScreen.screenId} ===")
                            element.leadsToScreen = retryScreen.screenId
                            element.actionType = ClickableActionType.NAVIGATION
                            if (!state?.exploredScreens?.containsKey(retryScreen.screenId)!!) {
                                state?.exploredScreens?.put(retryScreen.screenId, retryScreen)
                                navigationStack.addFirst(retryScreen.screenId)
                                _currentScreen.value = retryScreen
                                queueClickableElements(retryScreen)
                            }
                            return  // Success!
                        }
                    }
                    Log.w(TAG, "T&C button still didn't work after scrolling")
                }
            }

            element.actionType = ClickableActionType.NO_EFFECT

            // === Q-LEARNING UPDATE: No effect = slight negative reward ===
            if (beforeScreenHash != null && actionKey != null) {
                val reward = qLearning?.calculateReward(TapResult.NO_CHANGE) ?: -0.1f
                qLearning?.updateQ(beforeScreenHash, actionKey, reward, beforeScreenHash)
                Log.d(TAG, "Q-learning: No effect, reward=$reward")
                checkPeriodicPublish()
            }

            // === STUCK DETECTION: Track consecutive no-effect taps ===
            if (lastScreenIdForStuckDetection == screenId) {
                consecutiveNoEffectTaps++
                Log.d(TAG, "Stuck detection: $consecutiveNoEffectTaps consecutive no-effect taps on screen $screenId")

                // === STATE MACHINE: Report no progress ===
                stateMachine.processEvent(ExplorationStateMachine.Event.NO_PROGRESS_DETECTED)

                if (consecutiveNoEffectTaps >= MAX_CONSECUTIVE_NO_EFFECT) {
                    Log.w(TAG, "=== STUCK DETECTED: $consecutiveNoEffectTaps no-effect taps - attempting escape ===")

                    // === STATE MACHINE: Stuck threshold reached ===
                    stateMachine.processEvent(ExplorationStateMachine.Event.STUCK_THRESHOLD_REACHED)

                    // Record stuck event in diagnostics
                    diagnostics.recordStuckEvent()

                    // === LEARN FROM BEING STUCK ===
                    // Give strong negative reward to discourage returning to this screen
                    val stuckScreenHash = qLearning?.computeScreenHash(afterScreen)
                    if (stuckScreenHash != null) {
                        // Mark this screen as a dead-end in Q-learning
                        qLearning?.markScreenAsDeadEnd(screenId)
                        Log.i(TAG, "Q-learning: Marked screen $screenId as dead-end (stuck)")
                    }

                    // Record in navigation graph that this screen is problematic
                    state?.navigationGraph?.markScreenProblematic(screenId, "stuck_no_effect")

                    // === PHASE 1.6c: Use modular recovery strategy ===
                    val escaped = attemptModularRecovery()
                    if (escaped) {
                        consecutiveNoEffectTaps = 0
                        lastScreenIdForStuckDetection = null

                        // === STATE MACHINE: Recovery succeeded ===
                        stateMachine.processEvent(ExplorationStateMachine.Event.RECOVERY_SUCCEEDED)
                    } else {
                        // === STATE MACHINE: Recovery failed ===
                        stateMachine.processEvent(ExplorationStateMachine.Event.RECOVERY_FAILED)
                    }
                }
            } else {
                // New screen, reset counter
                consecutiveNoEffectTaps = 1
                lastScreenIdForStuckDetection = screenId
            }
        }
    }

    /**
     * Execute a recovery action from StuckRecoveryStrategy.
     * Phase 1.6c: Modular stuck recovery using strategy pattern.
     *
     * @param action The recovery action to execute
     * @return true if recovery succeeded (screen changed or new elements found)
     */
    private suspend fun executeModularRecoveryAction(action: StuckRecoveryStrategy.RecoveryAction): Boolean {
        val beforeScreenId = lastScreenIdForStuckDetection ?: ""

        return when (action) {
            is StuckRecoveryStrategy.RecoveryAction.Scroll -> {
                Log.i(TAG, "StuckRecovery: Executing scroll (${action.startX},${action.startY}) -> (${action.endX},${action.endY})")
                val service = VisualMapperAccessibilityService.getInstance()
                if (service != null) {
                    service.gestureDispatcher.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
                    delay(action.durationMs + 500)
                    val afterScreen = captureCurrentScreen()
                    afterScreen != null && afterScreen.screenId != beforeScreenId
                } else {
                    false
                }
            }

            is StuckRecoveryStrategy.RecoveryAction.Tap -> {
                Log.i(TAG, "StuckRecovery: Executing random tap at (${action.x}, ${action.y})")
                val success = performTap(action.x, action.y)
                if (success) {
                    delay(1500)
                    val afterScreen = captureCurrentScreen()
                    afterScreen != null && afterScreen.screenId != beforeScreenId
                } else {
                    false
                }
            }

            is StuckRecoveryStrategy.RecoveryAction.PressBack -> {
                Log.i(TAG, "StuckRecovery: Executing back press")
                val backSuccess = performSmartBack()
                if (backSuccess) {
                    delay(1500)
                    val afterScreen = captureCurrentScreen()
                    afterScreen != null && afterScreen.screenId != beforeScreenId
                } else {
                    false
                }
            }

            is StuckRecoveryStrategy.RecoveryAction.RestartApp -> {
                Log.i(TAG, "StuckRecovery: Restarting app ${action.packageName}")
                val recovered = recoverFromMinimizedApp(action.packageName)
                if (recovered) {
                    delay(2000)
                    val freshScreen = captureCurrentScreen()
                    if (freshScreen != null) {
                        state?.exploredScreens?.put(freshScreen.screenId, freshScreen)
                        navigationStack.clear()
                        navigationStack.addFirst(freshScreen.screenId)
                        screenNavigator.reset()
                        screenNavigator.enterScreen(
                            screenId = freshScreen.screenId,
                            packageName = action.packageName,
                            activityName = freshScreen.activity,
                            elementCount = freshScreen.clickableElements.size,
                            unexploredElements = freshScreen.clickableElements.size
                        )
                        _currentScreen.value = freshScreen
                        val config = state?.config ?: ExplorationConfig()
                        if (config.goal == ExplorationGoal.COMPLETE_COVERAGE) {
                            queueClickableElementsDeep(freshScreen)
                        } else {
                            queueClickableElements(freshScreen)
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }

            is StuckRecoveryStrategy.RecoveryAction.RequestUserHelp -> {
                Log.i(TAG, "StuckRecovery: Requesting user help - ${action.message}")
                requestUserHelpForStuck()
            }
        }
    }

    /**
     * Attempt to escape from a stuck screen using modular strategy first,
     * then falling back to the legacy complex recovery logic.
     * Phase 1.6c: Hybrid approach during transition.
     */
    private suspend fun attemptModularRecovery(): Boolean {
        val targetPackage = state?.packageName ?: return false

        // Try modular recovery strategy
        val recoveryResult = stuckRecovery.getNextRecoveryAction(targetPackage)
        Log.i(TAG, "StuckRecovery: Using strategy ${recoveryResult.strategyUsed} - ${recoveryResult.message}")

        val success = executeModularRecoveryAction(recoveryResult.action)
        stuckRecovery.reportResult(success)

        if (success) {
            Log.i(TAG, "StuckRecovery: Strategy ${recoveryResult.strategyUsed} succeeded!")
            return true
        }

        // If modular strategies are exhausted, fall back to legacy complex recovery
        if (stuckRecovery.isExhausted()) {
            Log.w(TAG, "StuckRecovery: All modular strategies exhausted, trying legacy recovery")
            return attemptEscapeFromStuckScreen()
        }

        return false
    }

    /**
     * @deprecated Use attemptModularRecovery() instead. Kept for fallback during transition.
     * Attempt to escape from a stuck screen where taps have no navigation effect.
     * Tries in order: back button, bottom nav tabs, scroll to reveal more.
     */
    @Deprecated("Use attemptModularRecovery() for modular recovery. This is kept as fallback.")
    private suspend fun attemptEscapeFromStuckScreen(): Boolean {
        Log.i(TAG, "=== ESCAPE ATTEMPT (LEGACY): Trying to leave stuck screen ===")

        val targetPackage = state?.packageName

        // Strategy 1: Try pressing back button to go to a different screen
        Log.i(TAG, "Escape Strategy 1: Press back button")
        val backSuccess = performSmartBack()

        // Check if we're still in the target app
        if (!isInTargetApp()) {
            Log.w(TAG, "=== ESCAPE: Left the app! Relaunching... ===")
            // Clear the stale queue - these elements are from the old session
            state?.explorationQueue?.clear()
            navigationStack.clear()
            queueManager.reset()

            // Try to relaunch the app
            if (targetPackage != null && recoverFromMinimizedApp(targetPackage)) {
                delay(2000)
                val freshScreen = captureCurrentScreen()
                if (freshScreen != null) {
                    Log.i(TAG, "=== ESCAPE: App relaunched, now on screen: ${freshScreen.screenId} ===")
                    state?.exploredScreens?.put(freshScreen.screenId, freshScreen)
                    navigationStack.addFirst(freshScreen.screenId)
                    _currentScreen.value = freshScreen
                    val config = state?.config ?: ExplorationConfig()
                    if (config.goal == ExplorationGoal.COMPLETE_COVERAGE) {
                        queueClickableElementsDeep(freshScreen)
                    } else {
                        queueClickableElements(freshScreen)
                    }
                    return true
                }
            }
            Log.e(TAG, "=== ESCAPE FAILED: Could not relaunch app ===")
            return false
        }

        if (backSuccess) {
            delay(1500)
            val newScreen = captureCurrentScreen()
            if (newScreen != null && newScreen.screenId != lastScreenIdForStuckDetection) {
                Log.i(TAG, "=== ESCAPE SUCCESS via back button! Now on screen: ${newScreen.screenId} ===")

                // Queue elements from the new screen
                if (!state?.exploredScreens?.containsKey(newScreen.screenId)!!) {
                    state?.exploredScreens?.put(newScreen.screenId, newScreen)
                    val config = state?.config ?: ExplorationConfig()
                    if (config.goal == ExplorationGoal.COMPLETE_COVERAGE) {
                        queueClickableElementsDeep(newScreen)
                    } else {
                        queueClickableElements(newScreen)
                    }
                }
                navigationStack.addFirst(newScreen.screenId)
                _currentScreen.value = newScreen
                return true
            }
        }

        // Strategy 2: Try bottom navigation tabs
        Log.i(TAG, "Escape Strategy 2: Try bottom nav tabs")
        val unvisitedTabs = queueUnvisitedBottomNavTabs()
        if (unvisitedTabs > 0) {
            Log.i(TAG, "=== ESCAPE: Found $unvisitedTabs unvisited bottom nav tabs ===")
            return true  // Will be processed in next iteration
        }

        // Strategy 3: Try scrolling to reveal more elements
        Log.i(TAG, "Escape Strategy 3: Try scrolling")
        val currentScreen = captureCurrentScreen()
        if (currentScreen != null && currentScreen.scrollableContainers.isNotEmpty()) {
            val container = currentScreen.scrollableContainers.first()
            val centerX = container.bounds.x + container.bounds.width / 2
            val startY = container.bounds.y + (container.bounds.height * 0.7).toInt()
            val endY = container.bounds.y + (container.bounds.height * 0.3).toInt()

            val scrollService = VisualMapperAccessibilityService.getInstance()
            if (scrollService != null) {
                Log.i(TAG, "Scrolling to reveal more elements...")
                scrollService.gestureDispatcher.swipe(centerX, startY, centerX, endY, 300)
                delay(1000)

                // Check if new elements appeared
                val afterScroll = captureCurrentScreen()
                if (afterScroll != null) {
                    val newElements = afterScroll.clickableElements.filter { element ->
                        val compositeKey = "${afterScroll.screenId}:${element.elementId}"
                        state?.visitedElements?.contains(compositeKey) != true
                    }
                    if (newElements.isNotEmpty()) {
                        Log.i(TAG, "=== ESCAPE: Scroll revealed ${newElements.size} new elements ===")
                        // Queue the new elements
                        val isSystematic = state?.config?.strategy == ExplorationStrategy.SYSTEMATIC
                        val isAdaptive = state?.config?.strategy == ExplorationStrategy.ADAPTIVE
                        for (element in newElements) {
                            val config = state?.config ?: ExplorationConfig()
                            if (shouldExcludeFromQueue(element, config)) continue

                            // Use strategy-appropriate priority
                            val elemPriority = when {
                                isSystematic -> calculateStrategyPriority(element.centerX, element.centerY, true, false)
                                isAdaptive -> calculateElementPriority(element, afterScroll)
                                else -> 60
                            }

                            state?.explorationQueue?.addFirst(ExplorationTarget(
                                type = ExplorationTargetType.TAP_ELEMENT,
                                screenId = afterScroll.screenId,
                                elementId = element.elementId,
                                priority = elemPriority,
                                bounds = element.bounds
                            ))
                        }
                        return true
                    }
                }
            }
        }

        // All automatic strategies failed
        consecutiveEscapeFailures++
        Log.w(TAG, "=== ESCAPE FAILED: All strategies exhausted (failure $consecutiveEscapeFailures/$MAX_ESCAPE_FAILURES_BEFORE_HELP) ===")

        // If we've failed multiple times, ask user for help
        if (consecutiveEscapeFailures >= MAX_ESCAPE_FAILURES_BEFORE_HELP) {
            Log.i(TAG, "=== REQUESTING USER HELP: Exploration stuck ===")
            val userHelped = requestUserHelpForStuck()
            if (userHelped) {
                consecutiveEscapeFailures = 0
                return true
            }
        }

        return false
    }

    /**
     * Request user help when exploration is stuck and all automatic strategies failed.
     * Shows a notification and waits for user to perform the back gesture.
     */
    private suspend fun requestUserHelpForStuck(): Boolean {
        val currentScreenId = lastScreenIdForStuckDetection ?: "unknown"
        Log.i(TAG, "Requesting user help for stuck screen: $currentScreenId")

        // Show notification
        showStuckNotification()

        // Update overlay to show help request
        explorationOverlay?.showHelpRequest(
            "Exploration Stuck!",
            "Please press the BACK button/gesture to help navigation"
        )

        // Wait for user to perform back gesture (check every second for screen change)
        val maxWaitSeconds = 30
        val beforeScreen = captureCurrentScreen()?.screenId

        for (i in 1..maxWaitSeconds) {
            delay(1000)

            // Check if user stopped exploration
            if (shouldStop || state?.status != ExplorationStatus.IN_PROGRESS) {
                Log.i(TAG, "User stopped exploration while waiting for help")
                return false
            }

            // Check if screen changed (user helped)
            val currentScreen = captureCurrentScreen()
            if (currentScreen != null && currentScreen.screenId != beforeScreen) {
                Log.i(TAG, "=== USER HELPED: Screen changed to ${currentScreen.screenId} ===")

                // Queue elements from new screen
                state?.exploredScreens?.put(currentScreen.screenId, currentScreen)
                navigationStack.addFirst(currentScreen.screenId)
                _currentScreen.value = currentScreen

                val config = state?.config ?: ExplorationConfig()
                if (config.goal == ExplorationGoal.COMPLETE_COVERAGE) {
                    queueClickableElementsDeep(currentScreen)
                } else {
                    queueClickableElements(currentScreen)
                }

                // Hide help request
                explorationOverlay?.hideHelpRequest()
                showToast("Thanks! Continuing exploration...")
                return true
            }

            // Update countdown on overlay
            val remaining = maxWaitSeconds - i
            if (remaining > 0 && remaining % 5 == 0) {
                explorationOverlay?.showHelpRequest(
                    "Exploration Stuck!",
                    "Press BACK button ($remaining seconds remaining)"
                )
            }
        }

        // Timeout - user didn't help
        Log.w(TAG, "User help timeout - no back gesture detected")
        explorationOverlay?.hideHelpRequest()
        showToast("Timeout - skipping stuck screen")

        // Mark this screen as problematic and continue
        state?.navigationGraph?.markScreenProblematic(currentScreenId, "stuck_user_timeout")
        return false
    }

    /**
     * Show a notification that exploration is stuck and needs user help
     */
    private fun showStuckNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            // Create PendingIntent to open ExplorationResultActivity when tapped
            val intent = Intent(this, ExplorationResultActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = android.app.Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Exploration Stuck")
                .setContentText("Please press the BACK button to help navigation")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(android.app.Notification.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID_STUCK, notification)
            Log.i(TAG, "Showed stuck notification")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show stuck notification", e)
        }
    }

    private suspend fun processScroll(target: ExplorationTarget) {
        val screenId = target.screenId
        val containerId = target.scrollContainerId ?: return

        // === HANDLE UNREACHABLE SCREENS ===
        val currentScreenId = navigationStack.firstOrNull()
        if (currentScreenId != screenId && screenId !in navigationStack) {
            // FIRST: Add current screen's unexplored elements (same logic as processElementTap)
            val currentScreen = captureCurrentScreen()
            if (currentScreen != null) {
                val config = state?.config ?: ExplorationConfig()
                val visitedElements = state?.visitedElements ?: mutableSetOf()
                var addedCurrentScreenElements = 0

                val isSystematic = config.strategy == ExplorationStrategy.SYSTEMATIC
                val isAdaptive = config.strategy == ExplorationStrategy.ADAPTIVE
                for (element in currentScreen.clickableElements) {
                    val compositeKey = "${currentScreen.screenId}:${element.elementId}"
                    if (visitedElements.contains(compositeKey)) continue
                    if (shouldExcludeFromQueue(element, config)) continue

                    val alreadyQueued = state?.explorationQueue?.any {
                        it.screenId == currentScreen.screenId && it.elementId == element.elementId
                    } == true
                    if (alreadyQueued) continue

                    // Use strategy-appropriate priority
                    val elemPriority = when {
                        isSystematic -> calculateStrategyPriority(element.centerX, element.centerY, true, false)
                        isAdaptive -> calculateElementPriority(element, currentScreen)
                        else -> 50
                    }

                    state?.explorationQueue?.addFirst(ExplorationTarget(
                        type = ExplorationTargetType.TAP_ELEMENT,
                        screenId = currentScreen.screenId,
                        elementId = element.elementId,
                        priority = elemPriority,
                        bounds = element.bounds
                    ))
                    addedCurrentScreenElements++
                }

                if (addedCurrentScreenElements > 0) {
                    Log.i(TAG, "Screen $screenId not reachable - added $addedCurrentScreenElements elements from CURRENT screen ${currentScreen.screenId} (systematic=$isSystematic)")
                }
            }

            // THEN: Track screen reach failures and give up sooner
            val failures = (state?.screenReachFailures?.get(screenId) ?: 0) + 1
            state?.screenReachFailures?.put(screenId, failures)

            if (failures >= 3) {
                state?.unreachableScreens?.add(screenId)
                Log.w(TAG, "Screen $screenId marked UNREACHABLE after $failures failures - discarding scroll $containerId")
                state?.explorationQueue?.removeAll { it.screenId == screenId }
            } else if (target.priority > -10) {
                val requeued = target.copy(priority = target.priority - 5)
                state?.explorationQueue?.addLast(requeued)
                Log.d(TAG, "Screen $screenId not reachable (failure $failures/3) - re-queued scroll $containerId with priority ${requeued.priority}")
            }
            return
        }

        Log.d(TAG, "Processing scroll: $containerId on screen $screenId")

        // Navigate to screen if needed
        if (navigationStack.firstOrNull() != screenId) {
            if (!navigateToScreen(screenId)) {
                return
            }
        }

        val screen = state?.exploredScreens?.get(screenId) ?: return
        val container = screen.scrollableContainers.find { it.elementId == containerId }

        // If container not found, mark the screen's first container as fullyScrolled to prevent infinite loop
        if (container == null) {
            Log.w(TAG, "Scroll container $containerId not found on screen $screenId - marking all containers as scrolled")
            screen.scrollableContainers.forEach { it.fullyScrolled = true }
            return
        }

        val config = state?.config ?: return

        // Track consecutive "no change" scrolls to detect already at bottom
        var noChangeScrolls = 0
        val maxNoChangeScrolls = 2  // If 2 scrolls in a row don't change anything, we're at bottom

        // Perform scrolls
        repeat(config.maxScrollsPerContainer) { scrollIndex ->
            val beforeElements = captureCurrentScreen()?.clickableElements?.map { it.elementId }?.toSet()
                ?: emptySet()
            val beforeCount = beforeElements.size

            // Scroll down
            val scrolled = performScroll(
                container.bounds.centerX,
                container.bounds.centerY,
                container.scrollDirection
            )

            if (!scrolled) {
                container.fullyScrolled = true
                Log.i(TAG, "Scroll gesture failed for $containerId - marking as fully scrolled")
                return
            }

            delay(config.scrollDelay)

            // Check for new elements
            val afterScreen = captureCurrentScreen() ?: return
            val afterElementIds = afterScreen.clickableElements.map { it.elementId }.toSet()
            val newElementIds = afterElementIds.filter { it !in beforeElements }
            val newElements = afterScreen.clickableElements.filter { it.elementId in newElementIds }

            // Check if content actually changed (elements appeared or IDs changed)
            val contentChanged = newElementIds.isNotEmpty() || afterElementIds != beforeElements

            if (!contentChanged) {
                noChangeScrolls++
                Log.d(TAG, "Scroll $scrollIndex: No change detected ($noChangeScrolls/$maxNoChangeScrolls)")

                if (noChangeScrolls >= maxNoChangeScrolls) {
                    container.fullyScrolled = true
                    Log.i(TAG, "Container $containerId already at bottom (no change after $noChangeScrolls scrolls) - marking as fully scrolled")
                    return
                }
                // Don't process further but let the loop continue to try another scroll
            } else {
                // Reset counter since we saw change
                noChangeScrolls = 0

                if (newElements.isEmpty()) {
                    container.fullyScrolled = true
                    Log.i(TAG, "Container $containerId: No new elements after scroll - marking as fully scrolled")
                    return
                }

                // Queue new elements
                for (element in newElements) {
                    container.discoveredElements.add(element.elementId)
                    // Use composite key: screenId:elementId for per-screen tracking
                    val compositeKey = "${screenId}:${element.elementId}"
                    if (state?.visitedElements?.contains(compositeKey) != true) {
                        state?.explorationQueue?.add(ExplorationTarget(
                            type = ExplorationTargetType.TAP_ELEMENT,
                            screenId = screenId,
                            elementId = element.elementId,
                            priority = calculateElementPriority(element, afterScreen)
                        ))
                    }
                }
            }
        }

        // If we completed all scroll iterations, mark as fully scrolled
        container.fullyScrolled = true
        Log.i(TAG, "Container $containerId: Completed max scrolls (${config.maxScrollsPerContainer}) - marking as fully scrolled")
    }

    // =========================================================================
    // Navigation
    // =========================================================================

    private suspend fun navigateToScreen(targetScreenId: String): Boolean {
        val currentScreenId = navigationStack.firstOrNull()
        if (currentScreenId == targetScreenId) {
            return true
        }

        Log.i(TAG, "=== NAVIGATE TO SCREEN: $targetScreenId (from: $currentScreenId) ===")

        // OPTION 1: Check if target is in our back stack (we can navigate back to it)
        if (targetScreenId in navigationStack) {
            Log.d(TAG, "Target is in back stack - navigating back")
            while (navigationStack.firstOrNull() != targetScreenId) {
                val backSuccess = performSmartBack()
                if (!backSuccess) {
                    Log.w(TAG, "Could not navigate back (no UI back button) - stopping navigation")
                    return false
                }
                delay(state?.config?.transitionWait ?: 1500)
                navigationStack.removeFirstOrNull()

                if (!isInTargetApp()) {
                    Log.w(TAG, "Left target app during navigation - aborting")
                    return false
                }
            }
            Log.i(TAG, "Successfully navigated back to $targetScreenId")
            return true
        }

        // OPTION 2: Use NavigationGraph to find a forward path
        val navigationGraph = state?.navigationGraph
        if (navigationGraph != null && currentScreenId != null) {
            val path = navigationGraph.findPath(currentScreenId, targetScreenId)
            if (path != null && path.isNotEmpty()) {
                Log.i(TAG, "Found forward path to $targetScreenId: ${path.size} steps")
                return navigateForwardPath(path, targetScreenId)
            }
        }

        // OPTION 3: Try to find a path from any screen in our stack
        if (navigationGraph != null) {
            for (stackScreen in navigationStack) {
                val path = navigationGraph.findPath(stackScreen, targetScreenId)
                if (path != null && path.isNotEmpty()) {
                    Log.i(TAG, "Found path from stack screen $stackScreen to $targetScreenId")
                    // First navigate back to that stack screen
                    if (navigateToScreen(stackScreen)) {
                        return navigateForwardPath(path, targetScreenId)
                    }
                }
            }
        }

        // OPTION 4: Go to home and try to find a path from there
        Log.w(TAG, "No direct path to $targetScreenId - trying from home screen")
        val homeScreenId = navigationStack.lastOrNull()  // Assume last in stack is home
        if (homeScreenId != null && homeScreenId != currentScreenId) {
            // Navigate back to home
            while (navigationStack.size > 1) {
                val backSuccess = performSmartBack()
                if (!backSuccess) break
                delay(state?.config?.transitionWait ?: 1500)
                navigationStack.removeFirstOrNull()
            }

            // Try to find path from home
            if (navigationGraph != null) {
                val path = navigationGraph.findPath(navigationStack.firstOrNull() ?: "", targetScreenId)
                if (path != null && path.isNotEmpty()) {
                    Log.i(TAG, "Found path from home to $targetScreenId")
                    return navigateForwardPath(path, targetScreenId)
                }
            }
        }

        Log.w(TAG, "Cannot navigate to screen: $targetScreenId (no path found)")
        return false
    }

    /**
     * Navigate forward through a series of element taps to reach a target screen.
     * Path is a list of (screenId, elementId) pairs.
     */
    private suspend fun navigateForwardPath(path: List<Pair<String, String>>, targetScreenId: String): Boolean {
        Log.d(TAG, "Navigating forward path: ${path.map { "${it.first}→${it.second}" }}")

        for ((screenId, elementId) in path) {
            // Find the element on the cached screen
            val cachedScreen = state?.exploredScreens?.get(screenId)
            if (cachedScreen == null) {
                Log.w(TAG, "Screen $screenId not found in explored screens")
                return false
            }

            val cachedElement = cachedScreen.clickableElements.find { it.elementId == elementId }
            if (cachedElement == null) {
                Log.w(TAG, "Element $elementId not found on cached screen $screenId")
                return false
            }

            // ROBUST: Verify element before navigation tap
            if (!verifyElementBeforeAction(cachedElement, screenId, "nav_path")) {
                Log.w(TAG, "Navigation path element stale - aborting path")
                return false
            }

            // Re-get fresh element after verification
            val freshScreen = captureCurrentScreen()
            val freshElement = freshScreen?.clickableElements?.find { it.elementId == elementId }
            if (freshElement == null) {
                Log.w(TAG, "Fresh element $elementId not found after verification")
                return false
            }

            // Tap with FRESH coordinates
            Log.d(TAG, "Forward nav: Tapping verified element $elementId at (${freshElement.centerX}, ${freshElement.centerY})")
            tapAnimationOverlay.showTapAnimation(freshElement.centerX.toFloat(), freshElement.centerY.toFloat())
            val tapSuccess = performTap(freshElement.centerX, freshElement.centerY)
            if (!tapSuccess) {
                Log.w(TAG, "Failed to tap $elementId")
                return false
            }

            delay(state?.config?.transitionWait ?: 1500)
            waitForUIStabilization(2000)

            // Verify we're still in the target app
            if (!isInTargetApp()) {
                Log.w(TAG, "Left target app during forward navigation")
                return false
            }

            // Capture the new screen and update stack
            val newScreen = captureCurrentScreen()
            if (newScreen != null && newScreen.screenId !in navigationStack) {
                navigationStack.addFirst(newScreen.screenId)
                if (newScreen.screenId !in (state?.exploredScreens?.keys ?: emptySet())) {
                    state?.exploredScreens?.put(newScreen.screenId, newScreen)
                }
            }
        }

        // Verify we reached the target
        val finalScreen = captureCurrentScreen()
        if (finalScreen?.screenId == targetScreenId) {
            Log.i(TAG, "Successfully navigated to $targetScreenId via forward path")
            return true
        }

        Log.w(TAG, "Forward path didn't reach target (landed on ${finalScreen?.screenId})")
        return false
    }

    /**
     * Backtrack to an unexplored branch (screen with unvisited elements).
     * Used for systematic coverage in COMPLETE_COVERAGE mode.
     *
     * Algorithm:
     * 1. Check exploration frontier for screens with unexplored elements
     * 2. Try to navigate to the highest priority unexplored screen
     * 3. If no path found, restart app and try from home screen
     *
     * @return true if successfully navigated to an unexplored branch
     */
    private suspend fun backtrackToUnexploredBranch(): Boolean {
        val config = state?.config ?: return false
        if (!config.enableSystematicBacktracking) {
            return false
        }

        // Sort frontier by priority (most unexplored elements first)
        val sortedFrontier = explorationFrontier.sortedDescending()
        if (sortedFrontier.isEmpty()) {
            Log.d(TAG, "Backtrack: No unexplored branches in frontier")
            return false
        }

        Log.i(TAG, "=== SYSTEMATIC BACKTRACK: ${sortedFrontier.size} unexplored branches ===")
        for (item in sortedFrontier.take(3)) {
            Log.d(TAG, "  Frontier: ${item.screenId.take(12)} - ${item.unvisitedElementCount} unvisited")
        }

        // Try to navigate to each frontier screen in priority order
        for (frontierItem in sortedFrontier) {
            val targetScreenId = frontierItem.screenId
            val currentScreenId = navigationStack.firstOrNull()

            if (targetScreenId == currentScreenId) {
                Log.d(TAG, "Already on frontier screen $targetScreenId")
                return true
            }

            Log.i(TAG, "Attempting to navigate to frontier: $targetScreenId (${frontierItem.unvisitedElementCount} unvisited)")

            // Try to navigate to this screen
            if (navigateToScreen(targetScreenId)) {
                Log.i(TAG, "=== BACKTRACK SUCCESS: Now on $targetScreenId ===")

                // Queue unvisited elements from this screen
                val screen = state?.exploredScreens?.get(targetScreenId)
                if (screen != null) {
                    // FIX: Use composite key format "${screenId}:${elementId}" to match how elements are stored
                    val unvisitedElements = screen.clickableElements.filter {
                        val compositeKey = "$targetScreenId:${it.elementId}"
                        !state!!.visitedElements.contains(compositeKey)
                    }
                    for (element in unvisitedElements) {
                        val priority = qLearning?.getElementPriorityBoost(screen, element) ?: 0
                        state?.explorationQueue?.add(
                            ExplorationTarget(
                                type = ExplorationTargetType.TAP_ELEMENT,
                                screenId = targetScreenId,
                                elementId = element.elementId,
                                priority = priority,
                                bounds = element.bounds
                            )
                        )
                    }
                    Log.i(TAG, "Queued ${unvisitedElements.size} unvisited elements from $targetScreenId")
                }

                return true
            }
        }

        // No path found to any frontier screen - try restarting app
        Log.w(TAG, "No path to frontier screens - restarting app from home")
        val packageName = state?.packageName ?: return false

        // Check relaunch counter before attempting restart
        if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
            Log.e(TAG, "=== NAVIGATE RELAUNCH LOOP: Too many relaunches ($consecutiveRelaunchAttempts), giving up ===")
            return false
        }

        // Force close and relaunch
        try {
            val accessibilityService = VisualMapperAccessibilityService.getInstance()
            accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
            accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            delay(500)
            accessibilityService?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            delay(1000)

            // Relaunch app with counter increment
            consecutiveRelaunchAttempts++
            Log.i(TAG, "Relaunching for navigation (attempt $consecutiveRelaunchAttempts)")
            if (launchApp(packageName)) {
                delay(2000)
                val homeScreen = captureCurrentScreen()
                if (homeScreen != null) {
                    Log.i(TAG, "App relaunched - now on ${homeScreen.screenId}")
                    navigationStack.clear()
                    navigationStack.addFirst(homeScreen.screenId)
                    consecutiveRelaunchAttempts = 0  // Reset on success
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart app for backtracking", e)
        }

        return false
    }

    // =========================================================================
    // Gesture Execution (Delegated to GestureExecutor)
    // =========================================================================

    /** Ensure app exploration starts from the app's home/main screen. */
    private suspend fun ensureAppStartsFromHome(packageName: String) =
        gestureExecutor.ensureAppStartsFromHome(packageName)

    /** Launch an app by package name. */
    private suspend fun launchApp(packageName: String, forceRestart: Boolean = false): Boolean =
        gestureExecutor.launchApp(packageName, forceRestart)

    /** Perform a tap at the specified coordinates. */
    private suspend fun performTap(x: Int, y: Int): Boolean =
        gestureExecutor.performTap(x, y)

    /** Perform tap with access level check and audit logging. */
    private suspend fun performTapWithAccessCheck(
        x: Int,
        y: Int,
        elementId: String?,
        screenId: String?,
        packageName: String
    ): Boolean = gestureExecutor.performTapWithAccessCheck(x, y, elementId, screenId, packageName)

    /** Perform a back navigation. */
    private suspend fun performBack(): Boolean =
        gestureExecutor.performBack()

    /** Perform scroll with access level check and audit logging. */
    private suspend fun performScrollWithAccessCheck(
        centerX: Int,
        centerY: Int,
        direction: ScrollDirection,
        packageName: String,
        screenId: String?
    ): Boolean = gestureExecutor.performScrollWithAccessCheck(centerX, centerY, direction, packageName, screenId)

    /** Perform a scroll gesture. */
    private suspend fun performScroll(centerX: Int, centerY: Int, direction: ScrollDirection): Boolean =
        gestureExecutor.performScroll(centerX, centerY, direction)

    /** Check if we're currently in the target app. */
    private fun isInTargetApp(): Boolean =
        gestureExecutor.isInTargetApp(state?.packageName)

    /**
     * AGGRESSIVE APP DETECTION: Ensure we're in the target app before any action.
     * If not in target app, immediately return to it.
     * This should be called BEFORE every exploration action to prevent navigating in wrong apps.
     *
     * @return true if we're now in the target app, false if recovery failed
     */
    private suspend fun ensureInTargetApp(): Boolean {
        val targetPackage = state?.packageName ?: return false
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false

        // Check current package
        val currentPackage = accessibilityService.rootInActiveWindow?.packageName?.toString()
            ?: accessibilityService.currentPackage?.value

        if (currentPackage == targetPackage) {
            // Reset relaunch counter when we're in the target app
            consecutiveRelaunchAttempts = 0
            return true // Already in target app
        }

        // We're NOT in the target app - log and recover immediately
        Log.w(TAG, "=== NOT IN TARGET APP! Current: $currentPackage, Target: $targetPackage ===")
        diagnostics.recordAppLeftUnexpectedly(currentPackage, targetPackage)

        // Check if we've hit the relaunch limit to prevent infinite loops
        if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
            Log.e(TAG, "=== RELAUNCH LOOP DETECTED: $consecutiveRelaunchAttempts consecutive relaunches ===")
            Log.e(TAG, "App keeps leaving immediately after relaunch - stopping exploration")
            showToast("App is unstable - exploration stopped")
            state?.issues?.add(ExplorationIssue(
                screenId = state?.exploredScreens?.keys?.lastOrNull() ?: "unknown",
                elementId = null,
                issueType = IssueType.RECOVERY_FAILED,
                description = "App keeps crashing/closing after $consecutiveRelaunchAttempts consecutive relaunches"
            ))
            return false
        }

        // Check if we're on home/launcher - if so, just relaunch
        val isLauncher = currentPackage?.contains("launcher", ignoreCase = true) == true ||
            currentPackage?.contains("home", ignoreCase = true) == true

        if (isLauncher) {
            Log.i(TAG, "On launcher/home screen - relaunching target app (attempt ${consecutiveRelaunchAttempts + 1})")
            consecutiveRelaunchAttempts++
            if (launchApp(targetPackage)) {
                delay(2000)
                return isInTargetApp()
            }
            return false
        }

        // Try pressing back first (faster recovery)
        Log.i(TAG, "Trying back press to return to target app")
        accessibilityService.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        delay(500)

        if (isInTargetApp()) {
            Log.i(TAG, "Back press returned us to target app")
            consecutiveRelaunchAttempts = 0  // Reset counter since back worked
            return true
        }

        // Back didn't work - force relaunch
        consecutiveRelaunchAttempts++
        Log.i(TAG, "Back didn't work - relaunching target app (attempt $consecutiveRelaunchAttempts)")
        if (launchApp(targetPackage)) {
            delay(2000)
            if (isInTargetApp()) {
                Log.i(TAG, "Successfully relaunched target app")
                // Clear navigation state since we restarted
                navigationStack.clear()
                val freshScreen = captureCurrentScreen()
                if (freshScreen != null) {
                    navigationStack.addFirst(freshScreen.screenId)
                }
                return true
            }
        }

        Log.e(TAG, "Failed to return to target app after multiple attempts")
        return false
    }

    /**
     * Check if an element matches a dangerous pattern (previously caused app to close)
     */
    private fun isDangerousElement(target: ExplorationTarget, elementId: String): Boolean {
        val dangerousPatterns = state?.dangerousPatterns ?: return false
        if (dangerousPatterns.isEmpty()) return false

        val screen = state?.exploredScreens?.get(target.screenId) ?: return false
        val element = screen.clickableElements.find { it.elementId == elementId } ?: return false

        val resId = element.resourceId ?: ""
        val className = element.className

        return dangerousPatterns.any { pattern ->
            resId.contains(pattern, ignoreCase = true) ||
            className.contains(pattern, ignoreCase = true)
        }
    }

    /**
     * Recover from minimized app - clear stale state and restart from fresh screen
     */
    private suspend fun recoverFromMinimizedApp(targetPackage: String): Boolean {
        Log.w(TAG, "Recovering from minimized app...")

        // === OVERLAY RECOVERY FIX ===
        // Re-show overlay if it was hidden when app minimized
        if (!overlayShowing) {
            Log.i(TAG, "Re-showing overlay after app recovery")
            withContext(Dispatchers.Main) {
                showOverlay()
            }
        }

        state?.recoveryAttempts = (state?.recoveryAttempts ?: 0) + 1
        diagnostics.recordRecoveryAttempt()
        val attempts = state?.recoveryAttempts ?: 0

        // If too many recovery attempts, we may be stuck
        if (attempts > 5) {
            Log.e(TAG, "Too many recovery attempts ($attempts) - exploration may be stuck")
            state?.issues?.add(ExplorationIssue(
                screenId = "recovery",
                elementId = null,
                issueType = IssueType.RECOVERY_FAILED,
                description = "Too many recovery attempts: $attempts"
            ))
            // Continue anyway but log the issue
        }

        // Clear navigation state
        navigationStack.clear()

        // Check global relaunch counter before relaunching
        if (consecutiveRelaunchAttempts >= MAX_CONSECUTIVE_RELAUNCHES) {
            Log.e(TAG, "=== MINIMIZED APP RECOVERY LOOP: Too many relaunches ($consecutiveRelaunchAttempts) ===")
            return false
        }

        // Re-launch app
        consecutiveRelaunchAttempts++
        Log.i(TAG, "Re-launching app from minimized state (attempt $consecutiveRelaunchAttempts)")
        if (!launchApp(targetPackage)) {
            state?.issues?.add(ExplorationIssue(
                screenId = "recovery",
                elementId = null,
                issueType = IssueType.RECOVERY_FAILED,
                description = "Failed to relaunch $targetPackage"
            ))
            return false
        }

        // Wait for app to fully load
        delay(3000)
        waitForUIStabilization(5000)

        // Capture fresh screen and continue
        val freshScreen = captureCurrentScreen()
        if (freshScreen != null) {
            navigationStack.addFirst(freshScreen.screenId)
            if (state?.exploredScreens?.containsKey(freshScreen.screenId) != true) {
                state?.exploredScreens?.put(freshScreen.screenId, freshScreen)
                queueClickableElements(freshScreen)
            }
            consecutiveRelaunchAttempts = 0  // Reset on successful recovery
            Log.i(TAG, "Recovery successful - now on screen: ${freshScreen.screenId}")
            return true
        }

        Log.e(TAG, "Recovery failed - couldn't capture screen after relaunch")
        return false
    }

    /**
     * Wait for UI to stabilize (stop changing) before continuing
     * This helps with loading screens and swipe-to-refresh
     */
    private suspend fun waitForUIStabilization(maxWaitMs: Long = 3000): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        val startTime = System.currentTimeMillis()
        var lastElementCount = -1
        var stableCount = 0

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val elements = accessibilityService.getUITree()
            val currentCount = elements.size

            if (currentCount == lastElementCount && currentCount > 0) {
                stableCount++
                if (stableCount >= 2) {
                    Log.d(TAG, "UI stabilized with $currentCount elements after ${System.currentTimeMillis() - startTime}ms")
                    return true
                }
            } else {
                stableCount = 0
            }
            lastElementCount = currentCount
            delay(500)
        }

        Log.w(TAG, "UI did not stabilize within ${maxWaitMs}ms")
        return false
    }

    /**
     * Smart back navigation - ONLY uses UI back button, NEVER system BACK.
     *
     * ROOT CAUSE FIX: System BACK (performGlobalAction(GLOBAL_ACTION_BACK)) closes the app
     * when on the ROOT activity OR when navigating back from bottom navigation tab destinations.
     * We now ONLY use UI back buttons for navigation. If no UI back button is found, we
     * return false and let the exploration continue without trying to go back.
     *
     * IMPORTANT: This is safer than trying system BACK because:
     * 1. Many apps have multiple "root" screens (one per bottom nav tab)
     * 2. navigationStack.size doesn't accurately reflect Android's activity back stack
     * 3. It's better to miss some back navigation than to close the app
     */
    private suspend fun performSmartBack(): Boolean {
        Log.i(TAG, "performSmartBack: Looking for UI back button first, then fallbacks")

        // Try up to 3 times to find and use a UI back button
        for (attempt in 1..3) {
            Log.d(TAG, "Back navigation attempt $attempt/3 (UI back button)")

            val backButton = findUIBackButton()
            if (backButton != null) {
                Log.i(TAG, "Found UI back button: ${backButton.resourceId ?: backButton.contentDescription}")
                val success = performTap(backButton.centerX, backButton.centerY)
                delay(1500)

                if (isInTargetApp()) {
                    Log.i(TAG, "UI back button succeeded on attempt $attempt")
                    return true
                } else {
                    Log.w(TAG, "UI back button closed the app on attempt $attempt - STOPPING")
                    return false
                }
            }

            Log.d(TAG, "No UI back button found on attempt $attempt")
            if (attempt < 3) {
                delay(500)
            }
        }

        // FALLBACK 1: Try clicking a bottom navigation item to escape the dead-end
        Log.i(TAG, "No UI back button - trying bottom nav fallback")
        val bottomNavSuccess = tryBottomNavFallback()
        if (bottomNavSuccess) {
            Log.i(TAG, "Bottom nav fallback succeeded")
            return true
        }

        // FALLBACK 2: System BACK as LAST RESORT (risky but sometimes necessary)
        Log.w(TAG, "No UI back or bottom nav - trying system BACK as last resort")
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService != null) {
            val backSuccess = accessibilityService.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            )
            delay(1000)

            if (backSuccess && isInTargetApp()) {
                Log.i(TAG, "System BACK succeeded (still in app)")
                return true
            } else if (!isInTargetApp()) {
                Log.w(TAG, "System BACK closed the app - STOPPING")
                return false
            }
        }

        Log.w(TAG, "All back navigation methods failed")
        return false
    }

    /**
     * Try clicking a bottom navigation item to escape a dead-end screen.
     * Returns true if we successfully navigated to a different screen.
     */
    private suspend fun tryBottomNavFallback(): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        val elements = accessibilityService.getUITree()

        // Find bottom navigation elements (items near the bottom of screen)
        val bottomNavElements = elements.filter { element ->
            val bottom = element.bounds.y + element.bounds.height
            element.isClickable &&
            bottom > screenHeight - 200 &&
            element.bounds.height > 40 && element.bounds.height < 150
        }

        if (bottomNavElements.isEmpty()) {
            Log.d(TAG, "No bottom nav elements found for fallback")
            return false
        }

        // Find a nav item we haven't visited recently
        val currentScreenId = navigationStack.firstOrNull()
        for (navElement in bottomNavElements) {
            val elementId = navElement.resourceId.takeIf { it.isNotEmpty() }?.substringAfterLast("/") ?:
                           "_${navElement.className.substringAfterLast(".")}_${navElement.bounds.centerX}"

            // Skip if we've already visited this nav tab recently
            if (visitedNavigationTabs.contains(elementId)) {
                continue
            }

            Log.i(TAG, "Trying bottom nav fallback: $elementId")
            performTap(navElement.bounds.centerX, navElement.bounds.centerY)
            delay(1500)

            // Check if screen changed
            val newScreen = captureCurrentScreen()
            if (newScreen != null && newScreen.screenId != currentScreenId) {
                Log.i(TAG, "Bottom nav fallback navigated to new screen: ${newScreen.screenId}")
                visitedNavigationTabs.add(elementId)
                return true
            }
        }

        return false
    }

    /**
     * Find a UI back button on current screen.
     * Gets FRESH elements from accessibility service to ensure we have up-to-date UI.
     * If not found initially, tries scrolling up to reveal a possibly collapsed toolbar.
     */
    private suspend fun findUIBackButton(): ClickableElement? {
        // First try: search directly
        var backButton = searchForBackButton()
        if (backButton != null) {
            return backButton
        }

        // Second try: scroll up to reveal collapsed toolbar
        Log.d(TAG, "Back button not found, trying scroll up to reveal toolbar")
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return null

        // Scroll down (which pulls content down and reveals toolbar at top)
        val scrollSuccess = accessibilityService.gestureDispatcher.swipe(
            screenWidth / 2f, 300f,  // From near top
            screenWidth / 2f, 600f,  // Scroll down
            200
        )

        if (scrollSuccess) {
            delay(500)
            backButton = searchForBackButton()
            if (backButton != null) {
                Log.d(TAG, "Found back button after scroll")
                return backButton
            }
        }

        // Third try: scroll to very top (in case we're in the middle of content)
        Log.d(TAG, "Trying harder scroll to reveal toolbar")
        accessibilityService.gestureDispatcher.swipe(
            screenWidth / 2f, 200f,
            screenWidth / 2f, 800f,
            300
        )
        delay(500)

        return searchForBackButton()
    }

    /**
     * Search for back button in current UI tree
     */
    private fun searchForBackButton(): ClickableElement? {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return null
        val elements = accessibilityService.getUITree()

        Log.d(TAG, "searchForBackButton: searching ${elements.size} elements")

        // Look for common back button patterns
        val backPatterns = listOf(
            "btn_finish", "btn_back", "navigate_up",
            "toolbar_back", "action_bar_back", "iv_back", "img_back"
        )

        for (element in elements) {
            // Only consider clickable elements
            if (!element.isClickable) continue

            val resId = element.resourceId?.lowercase() ?: ""
            val contentDesc = element.contentDescription?.lowercase() ?: ""

            // Check for back button patterns in resource ID
            for (pattern in backPatterns) {
                if (resId.contains(pattern)) {
                    Log.d(TAG, "Found back button by resId pattern '$pattern': $resId")
                    return convertToClickableElement(element)
                }
            }

            // Check content description for "back" or "navigate"
            if (contentDesc.contains("back") || contentDesc.contains("navigate up") ||
                contentDesc.contains("go back")) {
                Log.d(TAG, "Found back button by contentDesc: $contentDesc")
                return convertToClickableElement(element)
            }

            // Check for top-left ImageButton/ImageView (common back button position)
            if (element.bounds.centerX < 150 && element.bounds.centerY < 200) {
                val className = element.className.lowercase()
                if (className.contains("imagebutton") || className.contains("imageview")) {
                    Log.d(TAG, "Found top-left image as potential back: ${element.resourceId ?: className}")
                    return convertToClickableElement(element)
                }
            }
        }

        return null
    }

    /** Convert UIElement from accessibility service to ClickableElement. */
    private fun convertToClickableElement(element: UIElement): ClickableElement =
        NameGenerator.convertToClickableElement(element)

    // =========================================================================
    // Progress and Notification
    // =========================================================================

    private fun updateProgress() {
        val isManualMode = state?.config?.mode == ExplorationMode.MANUAL

        // For Manual mode: count total clickable elements discovered across all screens
        // For other modes: count visited elements
        val elementsCount = if (isManualMode) {
            state?.exploredScreens?.values?.sumOf { it.clickableElements.size } ?: 0
        } else {
            state?.visitedElements?.size ?: 0
        }

        // For Manual mode: show navigation paths learned instead of queue
        val queueOrPaths = if (isManualMode) {
            state?.navigationGraph?.getStats()?.totalTransitions ?: 0
        } else {
            state?.explorationQueue?.size ?: 0
        }

        _progress.value = ExplorationProgress(
            screensExplored = state?.exploredScreens?.size ?: 0,
            elementsExplored = elementsCount,
            queueSize = queueOrPaths,
            status = state?.status ?: ExplorationStatus.NOT_STARTED
        )

        // Update notification with mode-specific text
        val notificationText = if (isManualMode) {
            "Manual: ${state?.exploredScreens?.size} screens, $elementsCount elements found"
        } else {
            "Screens: ${state?.exploredScreens?.size}, Elements: $elementsCount"
        }
        val notification = createNotification(notificationText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)

        // Update coverage metrics if goal-oriented tracking is enabled
        val config = state?.config ?: return
        if (config.trackCoverageMetrics) {
            updateCoverageMetrics()
        }
    }

    // =========================================================================
    // Coverage Tracking (Delegated to CoverageTracker)
    // =========================================================================

    /** Update coverage metrics for goal-oriented exploration. */
    private fun updateCoverageMetrics() {
        val currentState = state ?: return
        coverageTracker.updateMetrics(currentState.exploredScreens, currentState.visitedElements)
    }

    /** Update per-screen element statistics when a new screen is discovered. */
    private fun updateScreenElementStats(screen: ExploredScreen) =
        coverageTracker.updateScreenStats(screen)

    /** Mark an element as visited in screen element stats. */
    private fun markElementVisited(screenId: String, elementId: String) =
        coverageTracker.markElementVisited(screenId, elementId)

    /** Mark a scrollable container as fully scrolled. */
    private fun markContainerScrolled(screenId: String) =
        coverageTracker.markContainerScrolled(screenId)

    /** Check if target coverage has been reached (for goal-oriented exploration). */
    private fun hasReachedTargetCoverage(): Boolean {
        val config = state?.config ?: return false
        return coverageTracker.hasReachedTargetCoverage(config.goal, config.targetCoverage)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Explorer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows app exploration progress"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        // Use cached PendingIntents to prevent stop button from breaking
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Exploring App")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    // =========================================================================
    // Flow Generation
    // =========================================================================

    fun generateFlow(): GeneratedFlow? {
        val currentState = state ?: return null

        val steps = mutableListOf<GeneratedFlowStep>()
        val sensors = mutableListOf<GeneratedSensor>()
        val actions = mutableListOf<GeneratedAction>()

        // Find the entry screen (earliest discovered screen by timestamp)
        val entryScreenId = currentState.exploredScreens.entries.minByOrNull { (_, screen) ->
            screen.timestamp
        }?.key

        // Generate launch step
        steps.add(GeneratedFlowStep(
            type = GeneratedStepType.LAUNCH_APP,
            screenId = null,
            elementId = null,
            x = null,
            y = null,
            text = currentState.packageName,
            waitMs = 2000,
            description = "Launch ${currentState.packageName}"
        ))

        // Generate sensors from all text elements
        for ((_, screen) in currentState.exploredScreens) {
            for (textElement in screen.textElements) {
                sensors.add(GeneratedSensor(
                    name = textElement.suggestedSensorName
                        ?: generateSensorName(textElement.text, textElement.resourceId),
                    screenId = screen.screenId,
                    elementId = textElement.elementId,
                    resourceId = textElement.resourceId,
                    sensorType = textElement.sensorType,
                    sampleValue = textElement.text
                ))
            }

            // Generate actions from clickable elements
            for (clickable in screen.clickableElements) {
                if (clickable.explored && clickable.actionType != ClickableActionType.NO_EFFECT) {
                    // Compute navigation path from entry screen to this action's screen
                    val stepsToReach = computeStepsToScreen(
                        currentState,
                        entryScreenId,
                        screen.screenId
                    )

                    actions.add(GeneratedAction(
                        name = generateActionName(clickable.text, clickable.resourceId),
                        screenId = screen.screenId,
                        elementId = clickable.elementId,
                        resourceId = clickable.resourceId,
                        actionType = clickable.actionType,
                        stepsToReach = stepsToReach
                    ))
                }
            }
        }

        return GeneratedFlow(
            name = "Explored_${currentState.packageName.substringAfterLast('.')}",
            packageName = currentState.packageName,
            steps = steps,
            sensors = sensors,
            actions = actions
        )
    }

    /**
     * Compute navigation steps to reach a target screen from an entry screen.
     * Uses the navigation graph's Dijkstra-based optimal path finding.
     *
     * @param currentState The current exploration state
     * @param entryScreenId The screen where navigation starts (app launch screen)
     * @param targetScreenId The screen to navigate to
     * @return List of GeneratedFlowStep to reach the target, empty if already there or no path
     */
    private fun computeStepsToScreen(
        currentState: ExplorationState,
        entryScreenId: String?,
        targetScreenId: String
    ): List<GeneratedFlowStep> {
        // If entry screen is null or same as target, no steps needed
        if (entryScreenId == null || entryScreenId == targetScreenId) {
            return emptyList()
        }

        // Use optimal path finding from navigation graph
        val path = currentState.navigationGraph.findOptimalPath(entryScreenId, targetScreenId)
            ?: return emptyList()

        // Convert path to GeneratedFlowSteps
        return path.mapNotNull { (screenId, elementId) ->
            val screen = currentState.exploredScreens[screenId]
            val element = screen?.clickableElements?.find { it.elementId == elementId }

            if (element != null) {
                GeneratedFlowStep(
                    type = GeneratedStepType.TAP,
                    screenId = screenId,
                    elementId = elementId,
                    x = element.centerX,
                    y = element.centerY,
                    text = null,
                    waitMs = 1500,  // Wait for navigation
                    description = "Tap ${element.text ?: element.resourceId ?: "element"}"
                )
            } else {
                // Element not found in explored screens, skip
                null
            }
        }
    }

    // =========================================================================
    // Name Generation (Delegated to NameGenerator)
    // =========================================================================

    /** Generate sensor name from text and resource ID. */
    private fun generateSensorName(text: String, resourceId: String?): String =
        NameGenerator.generateSensorName(
            text = text,
            resourceId = resourceId,
            packageName = state?.packageName ?: "",
            feedbackStore = feedbackStore,
            learningStore = learningStore
        )

    /** Generate action name from text and resource ID. */
    private fun generateActionName(text: String?, resourceId: String?): String =
        NameGenerator.generateActionName(
            text = text,
            resourceId = resourceId,
            packageName = state?.packageName ?: "",
            feedbackStore = feedbackStore,
            learningStore = learningStore
        )

    /** Generate enhanced sensor with device class and unit suggestions. */
    fun generateEnhancedSensor(
        text: String,
        resourceId: String?,
        screenId: String,
        elementId: String
    ): GeneratedSensor = NameGenerator.generateEnhancedSensor(
        text = text,
        resourceId = resourceId,
        screenId = screenId,
        elementId = elementId,
        packageName = state?.packageName ?: "",
        feedbackStore = feedbackStore,
        learningStore = learningStore
    )

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Calculate priority for an element based on exploration strategy.
     * SYSTEMATIC mode uses position-based priority (top-left = high, bottom-right = low)
     * ADAPTIVE mode uses a lower base priority to let Q-learning decide
     * Other modes use the default priority
     *
     * @param centerX Element center X coordinate
     * @param centerY Element center Y coordinate
     * @param isSystematic Whether we're in SYSTEMATIC mode
     * @param isAdaptive Whether we're in ADAPTIVE mode
     * @param adaptivePriority Priority to use in ADAPTIVE mode
     * @param defaultPriority Priority to use in other modes
     * @return The calculated priority
     */
    private fun calculateStrategyPriority(
        centerX: Int,
        centerY: Int,
        isSystematic: Boolean,
        isAdaptive: Boolean,
        adaptivePriority: Int = 30,
        defaultPriority: Int = 50
    ): Int {
        return when {
            isSystematic -> {
                // Position-based priority: top-left gets highest priority
                val row = centerY / 100
                val col = centerX / 100
                val readingOrder = (row * 100) + col
                1000 - readingOrder.coerceIn(0, 999)
            }
            isAdaptive -> adaptivePriority
            else -> defaultPriority
        }
    }

    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Get the gesture executor for use by NavigationGuide and other components.
     */
    fun getGestureExecutor(): GestureExecutor? {
        return if (::gestureExecutor.isInitialized) gestureExecutor else null
    }

    fun getExplorationResult(): ExplorationResult? {
        val currentState = state ?: return null

        // Build navigation graph
        val navGraph = mutableMapOf<String, MutableList<String>>()
        for (transition in screenTransitions) {
            navGraph.getOrPut(transition.fromScreenId) { mutableListOf() }
                .add(transition.toScreenId)
        }

        return ExplorationResult(
            state = currentState,
            screens = currentState.exploredScreens.values.toList(),
            transitions = screenTransitions.toList(),
            navigationGraph = navGraph,
            generatedFlow = generateFlow(),
            coverageMetrics = coverageMetrics,
            mlInsights = getMLInsights()
        )
    }

    /**
     * Get ML insights from Q-learning for display in results
     */
    fun getMLInsights(): MLInsights? {
        val q = qLearning ?: return null
        val qTable = q.getQTable()

        // Find positive and negative reward elements
        val positiveElements = mutableListOf<String>()
        val negativeElements = mutableListOf<String>()
        var totalReward = 0f
        var rewardCount = 0

        for ((key, value) in qTable) {
            if (value > 0.5f) {
                // Parse element info from key (format: "screenHash|actionKey")
                val parts = key.split("|")
                if (parts.size >= 2) {
                    positiveElements.add(parts[1]) // actionKey
                }
            } else if (value < -0.3f) {
                val parts = key.split("|")
                if (parts.size >= 2) {
                    negativeElements.add(parts[1])
                }
            }
            totalReward += value
            rewardCount++
        }

        val avgReward = if (rewardCount > 0) totalReward / rewardCount else 0f

        // Top performing = highest Q-values
        val topActions = qTable.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { "${it.key.substringAfter("|")} (${String.format("%.2f", it.value)})" }

        // Problematic = lowest Q-values
        val problemActions = qTable.entries
            .sortedBy { it.value }
            .take(5)
            .filter { it.value < 0 }
            .map { "${it.key.substringAfter("|")} (${String.format("%.2f", it.value)})" }

        return MLInsights(
            qValueCount = qTable.size,
            dangerousPatternsCount = state?.dangerousPatterns?.size ?: 0,
            positiveRewardElements = positiveElements.distinct().take(10),
            negativeRewardElements = negativeElements.distinct().take(10),
            averageReward = avgReward,
            topPerformingActions = topActions,
            problematicActions = problemActions
        )
    }

    /**
     * Generate suggestions for improving exploration
     */
    fun generateSuggestions(): List<ExplorationSuggestion> {
        val suggestions = mutableListOf<ExplorationSuggestion>()
        val coverage = coverageMetrics.overallCoverage
        val issues = state?.issues ?: emptyList()

        // Low coverage suggestion
        if (coverage < 0.7f) {
            suggestions.add(ExplorationSuggestion(
                type = SuggestionType.LOW_COVERAGE,
                title = "Low Coverage (${(coverage * 100).toInt()}%)",
                description = "Enable Deep Exploration mode in Settings for more thorough coverage (targets 90%).",
                priority = 3
            ))
        }

        // Stuck elements suggestion
        val stuckCount = issues.count { it.issueType == IssueType.ELEMENT_STUCK }
        if (stuckCount > 0) {
            suggestions.add(ExplorationSuggestion(
                type = SuggestionType.STUCK_ELEMENTS,
                title = "$stuckCount Elements Stuck",
                description = "Some UI elements didn't respond to taps. They may need scrolling to become visible, or require longer tap delays.",
                priority = 2
            ))
        }

        // Unexplored branches suggestion
        val unexploredBranches = coverageMetrics.unexploredBranches
        if (unexploredBranches > 0) {
            suggestions.add(ExplorationSuggestion(
                type = SuggestionType.UNEXPLORED_BRANCHES,
                title = "$unexploredBranches Unexplored Branches",
                description = "Some navigation paths weren't fully explored. Running exploration again may discover more screens.",
                priority = 1
            ))
        }

        // Dangerous patterns warning
        val dangerousCount = state?.dangerousPatterns?.size ?: 0
        if (dangerousCount > 0) {
            suggestions.add(ExplorationSuggestion(
                type = SuggestionType.DANGEROUS_PATTERNS,
                title = "$dangerousCount Crash Patterns Detected",
                description = "Some element combinations caused the app to close. These will be avoided in future explorations.",
                priority = 2
            ))
        }

        // Stability good message
        if (dangerousCount == 0 && stuckCount < 3) {
            suggestions.add(ExplorationSuggestion(
                type = SuggestionType.STABILITY_GOOD,
                title = "Good Stability",
                description = "The app is stable and suitable for automated flows.",
                priority = 0
            ))
        }

        return suggestions.sortedByDescending { it.priority }
    }

    // =========================================================================
    // ML Training - MQTT Publishing (Delegated to ExplorationPublisher)
    // =========================================================================

    /** Publish exploration logs to ML training server via MQTT. */
    private fun publishExplorationLogsToMqtt() =
        explorationPublisher.publishExplorationLogs(qLearning)

    /** Called after each Q-learning update to periodically publish logs to server. */
    private fun checkPeriodicPublish() =
        explorationPublisher.checkPeriodicPublish(qLearning)

    /** Subscribe to Q-table updates from ML training server. */
    private fun subscribeToServerQTableUpdates() =
        explorationPublisher.subscribeToQTableUpdates(qLearning) { message ->
            showToast(message)
        }

    /** Publish exploration status to MQTT for Python script coordination. */
    private fun publishExplorationStatusToMqtt(status: String, message: String? = null) {
        val packageName = state?.packageName ?: return
        explorationPublisher.publishStatus(
            status = status,
            packageName = packageName,
            screensExplored = state?.exploredScreens?.size ?: 0,
            elementsExplored = state?.visitedElements?.size ?: 0,
            queueSize = state?.explorationQueue?.size ?: 0,
            message = message
        )
    }

    /** Get the device ID for flow generation. */
    private fun getDeviceIdForFlows(): String =
        explorationPublisher.getDeviceIdForFlows()

    /** Publish generated flow to MQTT for the server to save. */
    private fun publishGeneratedFlowToMqtt(flowJson: String) =
        explorationPublisher.publishGeneratedFlow(flowJson)

    /**
     * Get the pending generated flow (for ExplorationResultActivity).
     */
    fun getPendingGeneratedFlow(): String? = pendingGeneratedFlow

    /**
     * Clear the pending generated flow after it's been reviewed.
     */
    fun clearPendingGeneratedFlow() {
        pendingGeneratedFlow = null
    }

    // =========================================================================
    // Access Control Getters (for external configuration)
    // =========================================================================

    /**
     * Get the AccessLevelManager for configuring access levels.
     */
    fun getAccessManager(): AccessLevelManager = accessManager

    /**
     * Get the GoModeManager for activating/deactivating Go Mode.
     */
    fun getGoModeManager(): GoModeManager = goModeManager

    /**
     * Get the BlockerHandler for handling blocker screens.
     */
    fun getBlockerHandler(): BlockerHandler = blockerHandler

    /**
     * Get the ExplorationAuditLog for viewing action history.
     */
    fun getAuditLog(): ExplorationAuditLog = auditLog

    /**
     * Check if exploration is allowed for an app at the current access level.
     */
    fun isExplorationAllowed(packageName: String): Boolean {
        val level = accessManager.getAccessLevelForApp(packageName)
        // Need at least STANDARD level to explore
        return level.level >= ExplorationAccessLevel.STANDARD.level
    }

    /**
     * Get the current access level for an app.
     */
    fun getAccessLevel(packageName: String): ExplorationAccessLevel {
        return accessManager.getAccessLevelForApp(packageName)
    }

    // =========================================================================
    // Human-in-the-Loop: Yield Protocol
    // =========================================================================

    /**
     * Called when user takes control (clicks on screen during exploration).
     * Triggers the yield protocol - pause exploration and let user interact.
     */
    private fun handleUserTakeover(clickBounds: android.graphics.Rect, clickInfo: String?) {
        Log.i(TAG, "=== USER TAKEOVER - YIELDING ===")

        // Update overlay to show we're yielding
        explorationOverlay?.showHelpRequest(
            "User Control",
            "You're in control. Resume after ${UserInteractionDetector.DEFAULT_INACTIVITY_TIMEOUT_MS / 1000}s of inactivity..."
        )
    }

    /**
     * Called when user finishes interacting (inactivity timeout).
     * Re-syncs with current screen state and resumes exploration.
     */
    private fun handleUserInactivityTimeout() {
        Log.i(TAG, "=== USER INACTIVITY TIMEOUT - RE-SYNCING ===")

        scope.launch {
            try {
                // Hide help request on overlay
                explorationOverlay?.hideHelpRequest()

                // Re-sync: Capture current screen state
                val currentScreen = captureCurrentScreen()
                if (currentScreen != null) {
                    Log.i(TAG, "Re-synced to screen: ${currentScreen.activity}")

                    // Check if user navigated to a new screen
                    val existingScreen = state?.exploredScreens?.get(currentScreen.screenId)
                    val previousScreenId = navigationStack.firstOrNull()

                    if (existingScreen == null) {
                        // This is a new screen - add it!
                        Log.i(TAG, "User navigated to NEW screen: ${currentScreen.screenId}")
                        state?.exploredScreens?.put(currentScreen.screenId, currentScreen)

                        // Update navigation stack
                        if (currentScreen.screenId !in navigationStack) {
                            navigationStack.addFirst(currentScreen.screenId)
                        }

                        // Queue unexplored elements from this screen
                        state?.let { stateRef ->
                            val queue = ArrayDeque<ExplorationTarget>()
                            currentScreen.clickableElements.forEach { element ->
                                if (!element.explored) {
                                    queue.add(ExplorationTarget(
                                        type = ExplorationTargetType.TAP_ELEMENT,
                                        screenId = currentScreen.screenId,
                                        elementId = element.elementId,
                                        priority = 50,  // Default priority for user-discovered screens
                                        bounds = element.bounds
                                    ))
                                }
                            }
                            stateRef.explorationQueue.addAll(queue)
                        }

                        // Record this discovery with imitation bonus
                        // (User showed us how to get to this screen)
                        recordUserNavigationDiscovery(currentScreen, previousScreenId)
                    } else {
                        // Known screen - just update navigation stack
                        if (currentScreen.screenId !in navigationStack) {
                            navigationStack.addFirst(currentScreen.screenId)
                        }
                    }

                    // Update progress
                    updateProgress()
                } else {
                    Log.w(TAG, "Failed to capture screen after re-sync")
                }

                Log.i(TAG, "=== RESUMED EXPLORATION ===")

            } catch (e: Exception) {
                Log.e(TAG, "Error during re-sync", e)
            }
        }
    }

    /**
     * Record a screen discovery made by the user (imitation learning).
     * Applies positive H(s,a) to encourage similar navigation in the future.
     *
     * @param newScreen The new screen discovered by the user
     * @param fromScreenId The screen ID we were on before the user navigated (if known)
     */
    private fun recordUserNavigationDiscovery(newScreen: ExploredScreen, fromScreenId: String? = null) {
        val sourceScreenId = fromScreenId ?: "unknown"

        Log.i(TAG, "Recording user navigation discovery: $sourceScreenId -> ${newScreen.screenId}")

        // If we know the source screen, we could potentially learn the transition
        // For now, just log the event for analytics

        // Give exploration bonus for user-discovered screens
        // This encourages the bot to explore in similar directions
        scope.launch {
            diagnostics.onExplorationEvent(
                SelfDiagnostics.DiagnosticEvent.USER_TEACHING,
                "User showed us a new screen: ${newScreen.activity}",
                mapOf(
                    "from_screen" to sourceScreenId,
                    "to_screen" to newScreen.screenId,
                    "new_elements" to newScreen.clickableElements.size.toString()
                )
            )
        }
    }

    /**
     * Check if we're currently yielding to user control.
     */
    fun isCurrentlyYielding(): Boolean = humanInLoopManager.isCurrentlyYielding()

    /**
     * Get the user interaction detector for external access (e.g., from AccessibilityService)
     */
    fun getUserInteractionDetector(): UserInteractionDetector = humanInLoopManager.getUserInteractionDetector()

    /**
     * Get the human-in-the-loop manager for external access
     */
    fun getHumanInLoopManager(): HumanInLoopManager = humanInLoopManager
}

/**
 * Progress data for UI updates
 */
data class ExplorationProgress(
    val screensExplored: Int = 0,
    val elementsExplored: Int = 0,
    val queueSize: Int = 0,
    val status: ExplorationStatus = ExplorationStatus.NOT_STARTED
)

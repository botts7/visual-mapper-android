package com.visualmapper.companion.explorer

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.visualmapper.companion.R
import com.visualmapper.companion.security.ConsentManager
import com.visualmapper.companion.security.SecurePreferences
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Activity to display and edit exploration results
 *
 * Shows discovered screens, sensors, and actions from app exploration.
 * User can select/deselect items before generating the final flow.
 */
class ExplorationResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_START_EXPLORATION = "start_exploration"
        const val EXTRA_EXPLORATION_MODE = "exploration_mode"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var tabLayout: TabLayout
    private lateinit var progressOverlay: View
    private lateinit var fabFixIssues: ExtendedFloatingActionButton

    private var explorationResult: ExplorationResult? = null
    private var currentTab = 0

    // Adapters for each tab
    private val screensAdapter = ScreensAdapter()
    private val sensorsAdapter = SensorsAdapter()
    private val actionsAdapter = ActionsAdapter()
    private val issuesAdapter = IssuesAdapter { issue ->
        // Handle "Fix This" click for individual issue
        startGuidedCorrectionForSingle(issue)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exploration_result)

        setupViews()
        setupTabs()
        observeExploration()

        // Check if we should start exploration
        if (intent.getBooleanExtra(EXTRA_START_EXPLORATION, false)) {
            wasProperlyStarted = true
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
            val modeName = intent.getStringExtra(EXTRA_EXPLORATION_MODE) ?: "NORMAL"
            if (packageName != null) {
                startExploration(packageName, modeName)
            }
        } else {
            // Activity was started without EXTRA_START_EXPLORATION - check if there's active exploration
            val service = AppExplorerService.getInstance()
            val currentState = AppExplorerService.explorationState.value
            if (service != null && currentState?.status == ExplorationStatus.IN_PROGRESS) {
                // There's an active exploration - this is a valid resume
                wasProperlyStarted = true
                startedForPackage = currentState.packageName
                Log.i("ExplorationResult", "Resuming for active exploration of ${currentState.packageName}")
            } else {
                // No active exploration - this is a stale activity
                Log.w("ExplorationResult", "onCreate without exploration - will close on resume")
            }
        }
    }

    // Track the package name this activity was started for
    private var startedForPackage: String? = null
    // Track if this activity was properly started (not just resumed from back stack)
    private var wasProperlyStarted = false
    // Track if exploration has completed (prevents stale overlay from showing)
    private var explorationCompleted = false

    override fun onResume() {
        super.onResume()

        // Check if this activity should still be shown
        val service = AppExplorerService.getInstance()
        val currentState = AppExplorerService.explorationState.value

        Log.d("ExplorationResult", "onResume: service=${service != null}, result=${explorationResult != null}, status=${currentState?.status}, wasStarted=$wasProperlyStarted, startedFor=$startedForPackage")

        // If this activity was never properly started (resumed from stale back stack), close it
        if (!wasProperlyStarted) {
            Log.w("ExplorationResult", "Activity resumed from back stack without proper start. Finishing.")
            finish()
            return
        }

        if (service == null && explorationResult == null) {
            // No service and no cached results - this is a stale activity
            Log.w("ExplorationResult", "Stale activity detected - no service or results. Finishing.")
            finish()
            return
        }

        // Check if the service is running for a different package than we started for
        val currentPackage = currentState?.packageName
        if (startedForPackage != null && currentPackage != null && startedForPackage != currentPackage) {
            Log.w("ExplorationResult", "Stale activity - started for $startedForPackage but service is exploring $currentPackage. Finishing.")
            finish()
            return
        }

        // If exploration is not in progress and we have no results, try to get them
        if (currentState?.status != ExplorationStatus.IN_PROGRESS && explorationResult == null) {
            val result = service?.getExplorationResult()
            if (result == null || result.screens.isEmpty()) {
                Log.w("ExplorationResult", "No valid exploration results. Finishing.")
                finish()
                return
            }
        }

        // If we have cached results but service is now idle (new exploration might start), clear stale results
        if (explorationResult != null && currentState == null && service == null) {
            Log.w("ExplorationResult", "Service is gone, clearing stale results and finishing.")
            explorationResult = null
            finish()
            return
        }

        // Ensure overlay is hidden if exploration is already complete
        if (explorationCompleted || explorationResult != null) {
            progressOverlay.visibility = View.GONE
        }
    }

    private fun setupViews() {
        // Toolbar with dynamic title showing app name
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).apply {
            setSupportActionBar(this)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            setNavigationOnClickListener { finish() }
        }

        // Set dynamic title based on package being explored
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        if (packageName != null) {
            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)
                ).toString()
            } catch (e: Exception) {
                packageName.substringAfterLast('.')
            }
            supportActionBar?.title = "Exploring: $appName"
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = screensAdapter

        tabLayout = findViewById(R.id.tabLayout)
        progressOverlay = findViewById(R.id.progressOverlay)

        // Buttons
        findViewById<View>(R.id.btnCancel).setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.btnSaveFlow).setOnClickListener {
            saveFlow()
        }

        findViewById<View>(R.id.btnStopExploration).setOnClickListener {
            stopExploration()
        }

        findViewById<View>(R.id.btnRunAnotherPass).setOnClickListener {
            runAnotherPass()
        }

        // Fix Issues FAB
        fabFixIssues = findViewById(R.id.fabFixIssues)
        fabFixIssues.setOnClickListener {
            startGuidedCorrection()
        }
    }

    private var anotherPassPromptShown = false

    private fun showAnotherPassPrompt() {
        // Only show once per completion, and only if we have valid results
        if (anotherPassPromptShown || explorationResult == null) return

        val result = explorationResult ?: return
        val coverage = result.coverageMetrics?.overallCoverage ?: 0f
        val passNumber = result.state.passNumber

        // Load settings
        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        val maxPasses = prefs.getInt("max_exploration_passes", 1)
        val autoRunPasses = prefs.getBoolean("auto_run_passes", false)

        Log.i("ExplorationResult", "Pass $passNumber complete. maxPasses=$maxPasses, autoRun=$autoRunPasses, coverage=${(coverage * 100).toInt()}%")

        // Don't prompt if we've reached high coverage
        if (coverage >= 0.95f) {
            Log.i("ExplorationResult", "High coverage (${(coverage * 100).toInt()}%) - skipping another pass prompt")
            return
        }

        // Check if we've reached max passes (0 = unlimited)
        if (maxPasses > 0 && passNumber >= maxPasses) {
            Log.i("ExplorationResult", "Reached max passes ($maxPasses) - no more passes")
            return
        }

        // Check if there are unexplored branches - if not, don't run another pass
        val unexploredBranches = result.coverageMetrics?.unexploredBranches ?: 0
        if (unexploredBranches == 0) {
            Log.i("ExplorationResult", "No unexplored branches remaining - stopping auto-run")
            Toast.makeText(this, "Exploration complete - no more areas to explore", Toast.LENGTH_SHORT).show()
            return
        }

        // Track if passes are making progress to prevent infinite loops
        val prefs2 = getSharedPreferences("exploration_progress", MODE_PRIVATE)
        val lastPassVisited = prefs2.getInt("last_pass_visited_${result.state.packageName}", 0)
        val currentVisited = result.state.visitedElements.size

        // If visited count hasn't changed for 3 passes, stop auto-running
        val stuckPasses = prefs2.getInt("stuck_passes_${result.state.packageName}", 0)
        if (currentVisited == lastPassVisited) {
            val newStuckCount = stuckPasses + 1
            prefs2.edit().putInt("stuck_passes_${result.state.packageName}", newStuckCount).apply()
            if (newStuckCount >= 3) {
                Log.w("ExplorationResult", "No progress for $newStuckCount passes - stopping auto-run")
                Toast.makeText(this, "Exploration stuck - no new elements found", Toast.LENGTH_LONG).show()
                prefs2.edit().putInt("stuck_passes_${result.state.packageName}", 0).apply()
                return
            }
        } else {
            // Made progress - reset stuck counter
            prefs2.edit()
                .putInt("last_pass_visited_${result.state.packageName}", currentVisited)
                .putInt("stuck_passes_${result.state.packageName}", 0)
                .apply()
        }

        anotherPassPromptShown = true

        // If auto-run is enabled, start next pass automatically
        if (autoRunPasses) {
            Log.i("ExplorationResult", "Auto-run enabled - starting pass ${passNumber + 1} (unexplored: $unexploredBranches)")
            Toast.makeText(this, "Starting pass ${passNumber + 1}...", Toast.LENGTH_SHORT).show()
            runAnotherPass()
            return
        }

        val message = if (passNumber > 1) {
            "Pass $passNumber complete with ${(coverage * 100).toInt()}% coverage.\n\nRun another pass to find more sensors?"
        } else {
            "Exploration complete with ${(coverage * 100).toInt()}% coverage.\n\nRun another pass to improve results?"
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Run Another Pass?")
            .setMessage(message)
            .setPositiveButton("Yes, Run Another") { _, _ ->
                runAnotherPass()
            }
            .setNegativeButton("No, Review Results") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    private fun runAnotherPass() {
        val service = AppExplorerService.getInstance()

        // Reset flags for new pass
        anotherPassPromptShown = false
        explorationCompleted = false  // Allow overlay to show again

        if (service != null) {
            // Service is still running - use startAnotherPass for continuation
            progressOverlay.visibility = View.VISIBLE
            findViewById<TextView>(R.id.textProgressStatus).text = "Starting another pass..."

            // Start another pass - forceRun=true bypasses coverage/max pass checks for manual clicks
            service.startAnotherPass(forceRun = true)

            Toast.makeText(this, "Running another exploration pass...", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        } else {
            // Service was stopped - restart a fresh exploration
            val packageName = explorationResult?.state?.packageName
                ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

            if (packageName != null) {
                Toast.makeText(this, "Restarting exploration...", Toast.LENGTH_SHORT).show()
                progressOverlay.visibility = View.VISIBLE
                findViewById<TextView>(R.id.textProgressStatus).text = "Restarting exploration..."

                // Get the mode from current state or default to NORMAL
                val modeName = explorationResult?.state?.config?.mode?.name ?: "NORMAL"
                startExploration(packageName, modeName)
            } else {
                Toast.makeText(this, "Cannot restart - no package info available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                updateAdapter()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun observeExploration() {
        // Observe progress
        lifecycleScope.launch {
            AppExplorerService.progress.collectLatest { progress ->
                updateProgressUI(progress)
            }
        }

        // Observe exploration state
        lifecycleScope.launch {
            AppExplorerService.explorationState.collectLatest { state ->
                when (state?.status) {
                    ExplorationStatus.COMPLETED -> {
                        explorationCompleted = true  // Mark as done to prevent stale overlay
                        progressOverlay.visibility = View.GONE
                        loadResults()
                        // Ask if user wants to run another pass
                        showAnotherPassPrompt()
                    }
                    ExplorationStatus.STOPPED -> {
                        // User stopped early - still show results if any
                        explorationCompleted = true  // Mark as done to prevent stale overlay
                        progressOverlay.visibility = View.GONE
                        if (explorationResult != null) {
                            loadResults()
                        } else {
                            Toast.makeText(this@ExplorationResultActivity,
                                "Exploration stopped early",
                                Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                    ExplorationStatus.CANCELLED, ExplorationStatus.ERROR -> {
                        explorationCompleted = true  // Mark as done to prevent stale overlay
                        progressOverlay.visibility = View.GONE
                        if (explorationResult == null) {
                            Toast.makeText(this@ExplorationResultActivity,
                                "Exploration ${if (state.status == ExplorationStatus.CANCELLED) "cancelled" else "failed"}",
                                Toast.LENGTH_LONG).show()
                            finish()
                        }
                    }
                    ExplorationStatus.IN_PROGRESS -> {
                        // Only show overlay if we haven't already completed
                        // This prevents stale emissions from showing the overlay again
                        if (!explorationCompleted) {
                            progressOverlay.visibility = View.VISIBLE
                            // Update progress text to show current pass
                            val passNum = state.passNumber
                            if (passNum > 1) {
                                findViewById<TextView>(R.id.textProgressStatus).text = "Running pass $passNum..."
                            } else {
                                findViewById<TextView>(R.id.textProgressStatus).text = "Exploring..."
                            }
                            // Reset stats to show exploration is in progress
                            findViewById<TextView>(R.id.textScreenCount).text = "-"
                            findViewById<TextView>(R.id.textSensorCount).text = "-"
                            findViewById<TextView>(R.id.textActionCount).text = "-"
                            findViewById<TextView>(R.id.textIssueCount).text = "-"
                        }
                    }
                    else -> {
                        // For any other state, hide progress overlay
                        progressOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateProgressUI(progress: ExplorationProgress) {
        findViewById<TextView>(R.id.textProgressDetails)?.text =
            "Screens: ${progress.screensExplored}, Elements: ${progress.elementsExplored}"
    }

    // Store mode for passing through whitelist dialog
    private var pendingModeName: String = "NORMAL"

    private fun startExploration(packageName: String, modeName: String = "NORMAL") {
        pendingModeName = modeName
        startedForPackage = packageName
        // Check if app is whitelisted
        val securePrefs = SecurePreferences(this)
        if (!securePrefs.isAppWhitelisted(packageName)) {
            // Show dialog asking to whitelist
            showWhitelistDialog(packageName, securePrefs)
        } else {
            // Already whitelisted, start exploration
            doStartExploration(packageName, modeName)
        }
    }

    private fun showWhitelistDialog(packageName: String, securePrefs: SecurePreferences) {
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }

        AlertDialog.Builder(this)
            .setTitle("Allow App Access")
            .setMessage("To explore \"$appName\", Visual Mapper needs permission to read its UI elements.\n\nAdd this app to the allowed list?")
            .setPositiveButton("Allow") { _, _ ->
                // Add to whitelist
                securePrefs.addWhitelistedApp(packageName)

                // Grant consent for exploration
                try {
                    val auditLogger = com.visualmapper.companion.security.AuditLogger(this)
                    val consentManager = ConsentManager(this, securePrefs, auditLogger)
                    consentManager.grantConsent(
                        packageName = packageName,
                        level = ConsentManager.ConsentLevel.FULL,
                        purpose = "Smart Explore - automatic app discovery"
                    )
                } catch (e: Exception) {
                    // Continue even if consent manager fails
                }

                Toast.makeText(this, "App added to allowed list", Toast.LENGTH_SHORT).show()
                doStartExploration(packageName, pendingModeName)
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "Exploration cancelled", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun doStartExploration(packageName: String, modeName: String = "NORMAL") {
        explorationCompleted = false  // Reset flag for new exploration
        progressOverlay.visibility = View.VISIBLE

        // Build config JSON with mode
        val configJson = org.json.JSONObject().apply {
            put("mode", modeName)
        }.toString()

        val intent = Intent(this, AppExplorerService::class.java).apply {
            action = AppExplorerService.ACTION_START
            putExtra(AppExplorerService.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AppExplorerService.EXTRA_CONFIG, configJson)
        }

        // Must use startForegroundService on Android 8+ for foreground services
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Go to background so target app can be explored
        // The floating overlay will show progress
        val modeDesc = when (modeName) {
            "QUICK" -> " (Quick Pass)"
            "DEEP" -> " (Deep Analysis)"
            else -> ""
        }
        Toast.makeText(this, "Exploring $packageName$modeDesc... Check notification to stop.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopExploration() {
        Log.i("ExplorationResult", "Stop button clicked - sending stop intent")
        Toast.makeText(this, "Stopping exploration...", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, AppExplorerService::class.java).apply {
            action = AppExplorerService.ACTION_STOP
        }
        startService(intent)

        // Also immediately update UI
        findViewById<TextView>(R.id.textProgressStatus).text = "Stopping..."
    }

    private fun loadResults() {
        val service = AppExplorerService.getInstance()
        explorationResult = service?.getExplorationResult()

        if (explorationResult == null) {
            Toast.makeText(this, "No exploration results available", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val result = explorationResult!!

        // Update coverage card
        val coverageMetrics = result.coverageMetrics
        if (coverageMetrics != null) {
            val coveragePercent = (coverageMetrics.overallCoverage * 100).toInt()
            findViewById<TextView>(R.id.textCoveragePercent).text = "$coveragePercent%"
            findViewById<android.widget.ProgressBar>(R.id.progressCoverage).progress = coveragePercent

            val details = "${coverageMetrics.elementsVisited}/${coverageMetrics.totalElementsDiscovered} elements explored"
            findViewById<TextView>(R.id.textCoverageDetails).text = details
        }

        // Update summary stats
        findViewById<TextView>(R.id.textScreenCount).text = result.screens.size.toString()
        findViewById<TextView>(R.id.textSensorCount).text =
            (result.generatedFlow?.sensors?.size ?: 0).toString()
        findViewById<TextView>(R.id.textActionCount).text =
            (result.generatedFlow?.actions?.size ?: 0).toString()
        findViewById<TextView>(R.id.textIssueCount).text = result.state.issues.size.toString()

        // Update pass info if multi-pass
        val passNumber = result.state.passNumber
        val textPassInfo = findViewById<TextView>(R.id.textPassInfo)
        if (passNumber > 1) {
            val sensorCount = result.generatedFlow?.sensors?.size ?: 0
            val coveragePercent = (result.coverageMetrics?.overallCoverage?.times(100))?.toInt() ?: 0
            textPassInfo.text = "Pass $passNumber complete • $coveragePercent% coverage • $sensorCount sensors found"
            textPassInfo.visibility = View.VISIBLE
        } else {
            textPassInfo.visibility = View.GONE
        }

        // Update adapters
        screensAdapter.screens = result.screens
        sensorsAdapter.sensors = result.generatedFlow?.sensors?.toMutableList() ?: mutableListOf()
        actionsAdapter.actions = result.generatedFlow?.actions?.toMutableList() ?: mutableListOf()
        issuesAdapter.issues = result.state.issues

        // Show Fix Issues FAB if there are issues
        if (result.state.issues.isNotEmpty()) {
            fabFixIssues.visibility = View.VISIBLE
            fabFixIssues.text = "Fix ${result.state.issues.size} Issues"
        } else {
            fabFixIssues.visibility = View.GONE
        }

        // Show suggestions if any
        val suggestions = service?.generateSuggestions() ?: emptyList()
        if (suggestions.isNotEmpty()) {
            val cardSuggestions = findViewById<View>(R.id.cardSuggestions)
            cardSuggestions.visibility = View.VISIBLE

            val layoutSuggestions = findViewById<android.widget.LinearLayout>(R.id.layoutSuggestions)
            layoutSuggestions.removeAllViews()

            for (suggestion in suggestions) {
                val suggestionView = layoutInflater.inflate(
                    android.R.layout.simple_list_item_2, layoutSuggestions, false
                )
                suggestionView.findViewById<TextView>(android.R.id.text1).apply {
                    text = suggestion.title
                    setTextColor(resources.getColor(
                        when (suggestion.type) {
                            SuggestionType.LOW_COVERAGE -> R.color.warning
                            SuggestionType.STUCK_ELEMENTS -> R.color.error
                            SuggestionType.UNEXPLORED_BRANCHES -> R.color.warning
                            SuggestionType.DANGEROUS_PATTERNS -> R.color.error
                            SuggestionType.STABILITY_GOOD -> R.color.success
                            SuggestionType.DEEP_EXPLORATION_RECOMMENDED -> R.color.primary
                        }, null
                    ))
                }
                suggestionView.findViewById<TextView>(android.R.id.text2).text = suggestion.description
                layoutSuggestions.addView(suggestionView)
            }
        }

        // Show ML insights if available
        val mlInsights = result.mlInsights
        if (mlInsights != null && mlInsights.qValueCount > 0) {
            val cardML = findViewById<View>(R.id.cardMLInsights)
            cardML.visibility = View.VISIBLE

            val statsText = buildString {
                appendLine("Q-values learned: ${mlInsights.qValueCount}")
                appendLine("Dangerous patterns: ${mlInsights.dangerousPatternsCount}")
                appendLine("Average reward: ${String.format("%.3f", mlInsights.averageReward)}")

                if (mlInsights.topPerformingActions.isNotEmpty()) {
                    appendLine("\nTop performing actions:")
                    mlInsights.topPerformingActions.take(3).forEach { action ->
                        appendLine("  - $action")
                    }
                }

                if (mlInsights.problematicActions.isNotEmpty()) {
                    appendLine("\nProblematic actions:")
                    mlInsights.problematicActions.take(3).forEach { action ->
                        appendLine("  - $action")
                    }
                }
            }
            findViewById<TextView>(R.id.textMLStats).text = statsText
        }

        updateAdapter()
    }

    private fun updateAdapter() {
        recyclerView.adapter = when (currentTab) {
            0 -> screensAdapter
            1 -> sensorsAdapter
            2 -> actionsAdapter
            3 -> issuesAdapter
            else -> screensAdapter
        }
    }

    // =========================================================================
    // Guided Correction
    // =========================================================================

    /**
     * Start guided correction for all issues.
     */
    private fun startGuidedCorrection() {
        val result = explorationResult ?: return
        val issues = result.state.issues

        if (issues.isEmpty()) {
            Toast.makeText(this, "No issues to fix", Toast.LENGTH_SHORT).show()
            return
        }

        startGuidedCorrection(issues)
    }

    /**
     * Start guided correction for a single issue.
     */
    private fun startGuidedCorrectionForSingle(issue: ExplorationIssue) {
        startGuidedCorrection(listOf(issue))
    }

    /**
     * Launch the GuidedCorrectionActivity with the given issues.
     */
    private fun startGuidedCorrection(issues: List<ExplorationIssue>) {
        val packageName = explorationResult?.state?.packageName
            ?: intent.getStringExtra(EXTRA_PACKAGE_NAME)

        if (packageName == null) {
            Toast.makeText(this, "Cannot start correction - no package info", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i("ExplorationResult", "Starting guided correction for ${issues.size} issues in $packageName")

        // TODO: Launch GuidedCorrectionActivity when implemented
        // For now, show a placeholder dialog
        AlertDialog.Builder(this)
            .setTitle("Guided Correction")
            .setMessage(buildString {
                appendLine("Ready to fix ${issues.size} issue(s):")
                appendLine()
                issues.take(5).forEachIndexed { index, issue ->
                    appendLine("${index + 1}. ${issue.issueType.name}")
                    issue.elementText?.let { appendLine("   Element: $it") }
                }
                if (issues.size > 5) {
                    appendLine("... and ${issues.size - 5} more")
                }
                appendLine()
                appendLine("This feature will guide you through fixing each issue by:")
                appendLine("1. Navigating to the problematic screen")
                appendLine("2. Letting you demonstrate the correct action")
                appendLine("3. Learning from your correction")
            })
            .setPositiveButton("Start") { _, _ ->
                // TODO: Launch GuidedCorrectionActivity
                val intent = Intent(this, GuidedCorrectionActivity::class.java).apply {
                    putExtra("package_name", packageName)
                    putParcelableArrayListExtra("issues", ArrayList(issues))
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveFlow() {
        val result = explorationResult ?: return
        val flow = result.generatedFlow ?: return

        // Filter selected sensors and actions
        val selectedSensors = sensorsAdapter.sensors.filter { it.selected }
        val selectedActions = actionsAdapter.actions.filter { it.selected }

        // Get MQTT manager
        val app = application as? com.visualmapper.companion.VisualMapperApp
        val mqttManager = app?.mqttManager

        if (mqttManager != null && mqttManager.connectionState.value ==
            com.visualmapper.companion.mqtt.MqttManager.ConnectionState.CONNECTED) {
            // Publish discovery messages for each sensor
            for (sensor in selectedSensors) {
                val sensorId = sensor.name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                mqttManager.publishDiscovery(
                    sensorId = sensorId,
                    name = sensor.name,
                    deviceClass = sensor.deviceClass,
                    unit = sensor.unitOfMeasurement
                )
            }
            Toast.makeText(this,
                "Published ${selectedSensors.size} sensors to Home Assistant",
                Toast.LENGTH_LONG).show()
        } else {
            // Save sensor definitions locally for later sync
            val deviceId = app?.stableDeviceId ?: "unknown"
            saveSensorDefinitions(deviceId, selectedSensors)
            Toast.makeText(this,
                "Saved ${selectedSensors.size} sensors locally. Connect to MQTT to sync.",
                Toast.LENGTH_LONG).show()
        }

        finish()
    }

    private fun saveSensorDefinitions(deviceId: String, sensors: List<GeneratedSensor>) {
        try {
            val prefs = getSharedPreferences("exploration_sensors", MODE_PRIVATE)
            val existingJson = prefs.getString("sensors_$deviceId", "[]")
            val existingList = try {
                org.json.JSONArray(existingJson).let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
            } catch (e: Exception) {
                emptyList()
            }

            // Add new sensor IDs
            val newIds = sensors.map { it.name.lowercase().replace(Regex("[^a-z0-9_]"), "_") }
            val allIds = (existingList + newIds).distinct()

            val jsonArray = org.json.JSONArray(allIds)
            prefs.edit().putString("sensors_$deviceId", jsonArray.toString()).apply()

            // Save individual sensor definitions
            for (sensor in sensors) {
                val sensorId = sensor.name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
                val sensorJson = org.json.JSONObject().apply {
                    put("sensor_id", sensorId)
                    put("name", sensor.name)
                    put("device_id", deviceId)
                    put("device_class", sensor.deviceClass ?: org.json.JSONObject.NULL)
                    put("unit_of_measurement", sensor.unitOfMeasurement ?: org.json.JSONObject.NULL)
                    put("sample_value", sensor.sampleValue ?: org.json.JSONObject.NULL)
                    put("sensor_type", sensor.sensorType.name)
                    put("selected", sensor.selected)
                }
                prefs.edit().putString("sensor_${deviceId}_$sensorId", sensorJson.toString()).apply()
            }

            android.util.Log.i("ExplorationResult", "Saved ${sensors.size} sensor definitions locally")
        } catch (e: Exception) {
            android.util.Log.e("ExplorationResult", "Failed to save sensor definitions", e)
        }
    }

    // =========================================================================
    // Adapters
    // =========================================================================

    inner class ScreensAdapter : RecyclerView.Adapter<ScreensAdapter.ViewHolder>() {
        var screens: List<ExploredScreen> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textActivity: TextView = view.findViewById(R.id.textActivity)
            val textDetails: TextView = view.findViewById(R.id.textDetails)
            val textElementCount: TextView = view.findViewById(R.id.textElementCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_explored_screen, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val screen = screens[position]
            holder.textActivity.text = screen.activity.substringAfterLast('.')
            holder.textDetails.text = "ID: ${screen.screenId.take(8)}..."
            holder.textElementCount.text = "${screen.clickableElements.size} clickable, " +
                    "${screen.textElements.size} text, ${screen.inputFields.size} inputs"
        }

        override fun getItemCount() = screens.size
    }

    inner class SensorsAdapter : RecyclerView.Adapter<SensorsAdapter.ViewHolder>() {
        var sensors: MutableList<GeneratedSensor> = mutableListOf()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val textName: TextView = view.findViewById(R.id.textName)
            val textValue: TextView = view.findViewById(R.id.textValue)
            val textType: TextView = view.findViewById(R.id.textType)
            val textDeviceClass: TextView = view.findViewById(R.id.textDeviceClass)
            val btnEdit: View = view.findViewById(R.id.btnEdit)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_generated_sensor, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val sensor = sensors[position]
            holder.checkbox.isChecked = sensor.selected
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                sensors[position] = sensor.copy(selected = isChecked)
            }
            holder.textName.text = sensor.name
            holder.textValue.text = "Sample: ${sensor.sampleValue ?: "N/A"}"
            holder.textType.text = sensor.sensorType.name

            // Show device class and unit if set
            val deviceClassInfo = buildString {
                sensor.deviceClass?.let { append(it) }
                sensor.unitOfMeasurement?.let {
                    if (isNotEmpty()) append(" ")
                    append("($it)")
                }
            }
            if (deviceClassInfo.isNotEmpty()) {
                holder.textDeviceClass.text = deviceClassInfo
                holder.textDeviceClass.visibility = View.VISIBLE
            } else {
                holder.textDeviceClass.visibility = View.GONE
            }

            holder.btnEdit.setOnClickListener {
                showSensorEditDialog(position, sensor)
            }
        }

        override fun getItemCount() = sensors.size
    }

    private fun showSensorEditDialog(position: Int, sensor: GeneratedSensor) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_sensor, null)

        // Get all views
        val textRawValue = dialogView.findViewById<TextView>(R.id.textRawValue)
        val editName = dialogView.findViewById<android.widget.EditText>(R.id.editName)
        val spinnerExtractionMethod = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerExtractionMethod)
        val layoutRegexPattern = dialogView.findViewById<View>(R.id.layoutRegexPattern)
        val editRegexPattern = dialogView.findViewById<android.widget.EditText>(R.id.editRegexPattern)
        val textRegexHelp = dialogView.findViewById<TextView>(R.id.textRegexHelp)
        val checkExtractNumeric = dialogView.findViewById<CheckBox>(R.id.checkExtractNumeric)
        val checkRemoveUnit = dialogView.findViewById<CheckBox>(R.id.checkRemoveUnit)
        val textPreviewResult = dialogView.findViewById<TextView>(R.id.textPreviewResult)
        val textPreviewError = dialogView.findViewById<TextView>(R.id.textPreviewError)
        val spinnerDeviceClass = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerDeviceClass)
        val spinnerUnit = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerUnit)
        val spinnerStateClass = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerStateClass)

        // Show raw value
        textRawValue.text = "Sample: ${sensor.sampleValue ?: "(no sample)"}"

        // Set current values
        editName.setText(sensor.name)
        checkExtractNumeric.isChecked = sensor.extractNumeric
        checkRemoveUnit.isChecked = sensor.removeUnit
        editRegexPattern.setText(sensor.regexPattern ?: "")

        // Function to update preview
        fun updatePreview() {
            val method = GeneratedSensor.EXTRACTION_METHODS.getOrNull(
                spinnerExtractionMethod.selectedItemPosition
            )?.first ?: "none"
            val pattern = editRegexPattern.text.toString()
            val extractNum = checkExtractNumeric.isChecked
            val removeUnitVal = checkRemoveUnit.isChecked

            val (result, error) = GeneratedSensor.applyExtraction(
                sensor.sampleValue,
                method,
                pattern,
                extractNum,
                removeUnitVal
            )

            if (error != null) {
                textPreviewResult.text = result ?: sensor.sampleValue
                textPreviewResult.setTextColor(resources.getColor(R.color.warning, null))
                textPreviewError.text = error
                textPreviewError.visibility = View.VISIBLE
            } else {
                textPreviewResult.text = result ?: "(empty)"
                textPreviewResult.setTextColor(resources.getColor(R.color.success, null))
                textPreviewError.visibility = View.GONE
            }
        }

        // Set up extraction method spinner
        val extractionMethods = GeneratedSensor.EXTRACTION_METHODS
        val extractionAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            extractionMethods.map { it.second }
        )
        spinnerExtractionMethod.adapter = extractionAdapter
        val currentMethodIndex = extractionMethods.indexOfFirst { it.first == sensor.extractionMethod }
        if (currentMethodIndex >= 0) {
            spinnerExtractionMethod.setSelection(currentMethodIndex)
        }

        // Show/hide regex pattern based on selection
        spinnerExtractionMethod.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val method = extractionMethods.getOrNull(pos)?.first
                val showRegex = method == "regex"
                layoutRegexPattern.visibility = if (showRegex) View.VISIBLE else View.GONE
                textRegexHelp.visibility = if (showRegex) View.VISIBLE else View.GONE
                updatePreview()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Update preview on changes
        editRegexPattern.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
        })
        checkExtractNumeric.setOnCheckedChangeListener { _, _ -> updatePreview() }
        checkRemoveUnit.setOnCheckedChangeListener { _, _ -> updatePreview() }

        // Set up device class spinner
        val deviceClasses = GeneratedSensor.DEVICE_CLASSES
        val deviceClassAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            deviceClasses.map { it.second }
        )
        spinnerDeviceClass.adapter = deviceClassAdapter
        val currentDeviceClassIndex = deviceClasses.indexOfFirst { it.first == sensor.deviceClass }
        if (currentDeviceClassIndex >= 0) {
            spinnerDeviceClass.setSelection(currentDeviceClassIndex)
        }

        // Set up unit spinner
        val units = GeneratedSensor.UNITS
        val unitAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            units.map { it.second }
        )
        spinnerUnit.adapter = unitAdapter
        val currentUnitIndex = units.indexOfFirst { it.first == sensor.unitOfMeasurement }
        if (currentUnitIndex >= 0) {
            spinnerUnit.setSelection(currentUnitIndex)
        }

        // Set up state class spinner
        val stateClasses = GeneratedSensor.STATE_CLASSES
        val stateClassAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            stateClasses.map { it.second }
        )
        spinnerStateClass.adapter = stateClassAdapter
        val currentStateClassIndex = stateClasses.indexOfFirst { it.first == sensor.stateClass }
        if (currentStateClassIndex >= 0) {
            spinnerStateClass.setSelection(currentStateClassIndex)
        }

        // Initial preview
        updatePreview()

        AlertDialog.Builder(this)
            .setTitle("Edit Sensor")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newName = editName.text.toString().trim()
                val selectedMethod = extractionMethods.getOrNull(spinnerExtractionMethod.selectedItemPosition)?.first ?: "none"
                val selectedDeviceClass = deviceClasses.getOrNull(spinnerDeviceClass.selectedItemPosition)?.first
                val selectedUnit = units.getOrNull(spinnerUnit.selectedItemPosition)?.first
                val selectedStateClass = stateClasses.getOrNull(spinnerStateClass.selectedItemPosition)?.first

                sensorsAdapter.sensors[position] = sensor.copy(
                    name = if (newName.isNotEmpty()) newName else sensor.name,
                    extractionMethod = selectedMethod,
                    regexPattern = if (selectedMethod == "regex") editRegexPattern.text.toString() else null,
                    extractNumeric = checkExtractNumeric.isChecked,
                    removeUnit = checkRemoveUnit.isChecked,
                    deviceClass = selectedDeviceClass,
                    unitOfMeasurement = selectedUnit,
                    stateClass = selectedStateClass
                )
                sensorsAdapter.notifyItemChanged(position)
            }
            .setNeutralButton("Convert to Action") { _, _ ->
                // Convert this sensor to an action
                val newAction = GeneratedAction(
                    name = sensor.name,
                    screenId = sensor.screenId,
                    elementId = sensor.elementId,
                    resourceId = sensor.resourceId,
                    actionType = ClickableActionType.TOGGLE,  // Default to toggle, user can change
                    stepsToReach = emptyList(),  // No navigation steps for converted items
                    selected = true
                )
                // Remove from sensors, add to actions
                sensorsAdapter.sensors.removeAt(position)
                sensorsAdapter.notifyItemRemoved(position)
                actionsAdapter.actions.add(newAction)
                val newPosition = actionsAdapter.actions.size - 1
                actionsAdapter.notifyItemInserted(newPosition)
                Toast.makeText(this, "'${sensor.name}' converted to action", Toast.LENGTH_SHORT).show()
                // Open action edit dialog so user can adjust type
                showActionEditDialog(newPosition, newAction)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class ActionsAdapter : RecyclerView.Adapter<ActionsAdapter.ViewHolder>() {
        var actions: MutableList<GeneratedAction> = mutableListOf()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
            val textName: TextView = view.findViewById(R.id.textName)
            val textType: TextView = view.findViewById(R.id.textType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_generated_action, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val action = actions[position]
            holder.checkbox.isChecked = action.selected
            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                actions[position] = action.copy(selected = isChecked)
            }
            holder.textName.text = action.name
            holder.textType.text = action.actionType.name

            // Long press to edit/convert action
            holder.itemView.setOnLongClickListener {
                showActionEditDialog(position, action)
                true
            }
        }

        override fun getItemCount() = actions.size
    }

    private fun showActionEditDialog(position: Int, action: GeneratedAction) {
        // Action type options
        val actionTypes = ClickableActionType.values().map { it.name }

        AlertDialog.Builder(this)
            .setTitle("Edit Action: ${action.name}")
            .setItems(arrayOf(
                "Change Type",
                "Convert to Sensor",
                "Delete"
            )) { _, which ->
                when (which) {
                    0 -> {
                        // Change action type
                        AlertDialog.Builder(this)
                            .setTitle("Select Action Type")
                            .setItems(actionTypes.toTypedArray()) { _, typeIndex ->
                                val newType = ClickableActionType.values()[typeIndex]
                                actionsAdapter.actions[position] = action.copy(actionType = newType)
                                actionsAdapter.notifyItemChanged(position)
                            }
                            .show()
                    }
                    1 -> {
                        // Convert to sensor
                        val newSensor = GeneratedSensor(
                            name = action.name,
                            screenId = action.screenId,
                            elementId = action.elementId,
                            resourceId = action.resourceId,
                            sensorType = SuggestedSensorType.TEXT,
                            extractionMethod = "none",
                            sampleValue = action.name,  // Use name as sample value
                            selected = true
                        )
                        actionsAdapter.actions.removeAt(position)
                        actionsAdapter.notifyItemRemoved(position)
                        sensorsAdapter.sensors.add(newSensor)
                        val newPosition = sensorsAdapter.sensors.size - 1
                        sensorsAdapter.notifyItemInserted(newPosition)
                        Toast.makeText(this, "'${action.name}' converted to sensor", Toast.LENGTH_SHORT).show()
                        // Open sensor edit dialog so user can adjust settings
                        showSensorEditDialog(newPosition, newSensor)
                    }
                    2 -> {
                        // Delete action
                        actionsAdapter.actions.removeAt(position)
                        actionsAdapter.notifyItemRemoved(position)
                        Toast.makeText(this, "'${action.name}' deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class IssuesAdapter(
        private val onFixClick: (ExplorationIssue) -> Unit
    ) : RecyclerView.Adapter<IssuesAdapter.ViewHolder>() {
        var issues: List<ExplorationIssue> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconIssue: ImageView = view.findViewById(R.id.iconIssue)
            val textIssueType: TextView = view.findViewById(R.id.textIssueType)
            val textIssueDescription: TextView = view.findViewById(R.id.textIssueDescription)
            val textIssueLocation: TextView = view.findViewById(R.id.textIssueLocation)
            val btnFixThis: View = view.findViewById(R.id.btnFixThis)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_exploration_issue, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val issue = issues[position]

            // Format title based on issue type
            val title = when (issue.issueType) {
                IssueType.ELEMENT_STUCK -> "Element Stuck"
                IssueType.BACK_FAILED -> "Back Navigation Failed"
                IssueType.APP_MINIMIZED -> "App Minimized"
                IssueType.APP_LEFT -> "Left Target App"
                IssueType.TIMEOUT -> "Timeout"
                IssueType.SCROLL_FAILED -> "Scroll Failed"
                IssueType.DANGEROUS_ELEMENT -> "Dangerous Element"
                IssueType.RECOVERY_FAILED -> "Recovery Failed"
                IssueType.BLOCKER_SCREEN -> "Blocker Screen"
            }
            holder.textIssueType.text = title

            // Description
            holder.textIssueDescription.text = issue.description

            // Location info
            val location = buildString {
                if (issue.screenId.isNotEmpty()) {
                    append("Screen: ${issue.screenId.take(20)}")
                }
                issue.elementText?.let { text ->
                    if (isNotEmpty()) append(" | ")
                    append("\"$text\"")
                }
            }
            if (location.isNotEmpty()) {
                holder.textIssueLocation.text = location
                holder.textIssueLocation.visibility = View.VISIBLE
            } else {
                holder.textIssueLocation.visibility = View.GONE
            }

            // Fix button
            holder.btnFixThis.setOnClickListener {
                onFixClick(issue)
            }

            // Show corrected state if already fixed
            if (issue.corrected) {
                holder.iconIssue.setImageResource(R.drawable.ic_check)
                holder.iconIssue.setColorFilter(resources.getColor(R.color.success, null))
                holder.textIssueType.setTextColor(resources.getColor(R.color.success, null))
                holder.btnFixThis.visibility = View.GONE
            } else {
                holder.iconIssue.setImageResource(R.drawable.ic_warning)
                holder.iconIssue.setColorFilter(resources.getColor(R.color.error, null))
                holder.textIssueType.setTextColor(resources.getColor(R.color.error, null))
                holder.btnFixThis.visibility = View.VISIBLE
            }
        }

        override fun getItemCount() = issues.size
    }
}

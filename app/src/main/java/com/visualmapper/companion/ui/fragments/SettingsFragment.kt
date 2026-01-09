package com.visualmapper.companion.ui.fragments

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.explorer.AutoTrainingService
import com.visualmapper.companion.explorer.TrainingOrchestrator
import com.visualmapper.companion.ui.AuditLogActivity
import com.visualmapper.companion.ui.ExplorationSettingsActivity
import com.visualmapper.companion.ui.PrivacySettingsActivity
import com.visualmapper.companion.explorer.ConnectionManager
import com.visualmapper.companion.explorer.ConnectionMode
import com.visualmapper.companion.explorer.LearningMode
import com.visualmapper.companion.explorer.OperationMode
import com.visualmapper.companion.explorer.learning.FeedbackStore
import com.visualmapper.companion.explorer.learning.AppMapStore
import com.visualmapper.companion.explorer.learning.LearningStore
import com.visualmapper.companion.explorer.ScreenWakeManager
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.AdapterView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Settings Fragment
 *
 * Manages:
 * - Accessibility service
 * - Server connection
 * - MQTT connection
 * - Auto-connect settings
 * - Privacy settings navigation
 * - Audit log navigation
 */
class SettingsFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }
    // Use shared serverSyncManager from app (singleton)
    private val serverSync get() = app.serverSyncManager

    // Permission request launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.i("SettingsFragment", "POST_NOTIFICATIONS permission granted - starting training")
            doStartBatchTraining()
        } else {
            android.util.Log.w("SettingsFragment", "POST_NOTIFICATIONS permission denied")
            Toast.makeText(
                requireContext(),
                "Notification permission required for batch training progress",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Accessibility views
    private lateinit var accessibilityStatusDot: View
    private lateinit var textAccessibilityStatus: TextView
    private lateinit var btnEnableAccessibility: Button

    // Server views
    private lateinit var editServerUrl: TextInputEditText
    private lateinit var editDeviceIp: TextInputEditText
    private lateinit var serverStatusDot: View
    private lateinit var textServerStatus: TextView
    private lateinit var btnServerConnect: Button

    // MQTT views
    private lateinit var editMqttBroker: TextInputEditText
    private lateinit var editMqttPort: TextInputEditText
    private lateinit var editMqttUsername: TextInputEditText
    private lateinit var editMqttPassword: TextInputEditText
    private lateinit var switchMqttSsl: SwitchMaterial  // Phase 1: SSL toggle
    private lateinit var btnSslHelp: android.widget.ImageButton  // Phase 1: SSL help button
    private lateinit var mqttStatusDot: View
    private lateinit var textMqttStatus: TextView
    private lateinit var btnMqttConnect: Button
    private lateinit var btnMqttDisconnect: Button

    // Auto-connect
    private lateinit var switchAutoConnect: SwitchMaterial

    // Device info
    private lateinit var textDeviceId: TextView
    private lateinit var textAndroidId: TextView

    // Navigation
    private lateinit var btnPrivacySettings: LinearLayout
    private lateinit var btnAuditLog: LinearLayout
    private lateinit var btnExplorationSettings: LinearLayout

    // Test actions
    private lateinit var cardTestActions: View
    private lateinit var btnTestGesture: Button
    private lateinit var btnTestUiRead: Button

    // ML Training
    private lateinit var cardMlTraining: View
    private lateinit var switchMlTraining: SwitchMaterial
    private lateinit var switchAllowAllApps: SwitchMaterial
    private lateinit var switchDeepExploration: SwitchMaterial
    private lateinit var switchAutoSaveFlows: SwitchMaterial
    private lateinit var textMlStatus: TextView
    private lateinit var textQTableStats: TextView
    private lateinit var btnResetQLearning: Button
    private lateinit var textTrainingStatus: TextView
    private lateinit var btnStartBatchTraining: Button
    private lateinit var btnStopBatchTraining: Button

    // Learning & Connectivity
    private lateinit var radioConnectionMode: RadioGroup
    private lateinit var radioConnAuto: RadioButton
    private lateinit var radioConnServer: RadioButton
    private lateinit var radioConnStandalone: RadioButton
    private lateinit var effectiveModeStatusDot: View
    private lateinit var textEffectiveMode: TextView
    private lateinit var radioLearningMode: RadioGroup
    private lateinit var radioLearnOnDevice: RadioButton
    private lateinit var radioLearnSync: RadioButton
    private lateinit var radioLearnDisabled: RadioButton
    private lateinit var textLearningStats: TextView
    private lateinit var btnViewLearningData: Button
    private lateinit var btnClearLearningData: Button

    // Smart Learning (Adaptive Strategy)
    private lateinit var switchStrategyAdaptive: SwitchMaterial
    private lateinit var textLearnedStrategies: TextView

    // Exploration Strategy
    private lateinit var spinnerStrategy: Spinner
    private lateinit var textStrategyDescription: TextView

    // Multi-Pass Exploration
    private lateinit var spinnerMaxPasses: Spinner
    private lateinit var switchAutoRunPasses: SwitchMaterial

    // Flow Execution Settings
    private lateinit var switchWakeScreen: SwitchMaterial
    private lateinit var switchLockAfterFlow: SwitchMaterial
    private lateinit var switchFlowOverlay: SwitchMaterial
    private lateinit var switchPauseOnTouch: SwitchMaterial
    private lateinit var spinnerResumeDelay: Spinner
    private lateinit var batteryStatusDot: View
    private lateinit var textBatteryStatus: TextView
    private lateinit var btnBatterySettings: Button
    private lateinit var textBatteryHint: TextView

    // ADB Connection Sharing
    private lateinit var editAdbPort: TextInputEditText
    private lateinit var textDeviceIpAddress: TextView
    private lateinit var btnAnnounceDevice: Button
    private lateinit var btnWithdrawAnnouncement: Button
    private lateinit var textAdbSharingStatus: TextView
    private var isAdbSharing = false

    // Backend Services
    private lateinit var cardBackendServices: View
    private lateinit var mlServerStatusDot: View
    private lateinit var textMlServerStatus: TextView
    private lateinit var btnMlServerStart: Button
    private lateinit var btnMlServerStop: Button
    private lateinit var btnRefreshServices: Button

    // Learning stores and managers
    private val connectionManager by lazy { ConnectionManager.getInstance(requireContext()) }
    private val feedbackStore by lazy { FeedbackStore.getInstance(requireContext()) }
    private val appMapStore by lazy { AppMapStore.getInstance(requireContext()) }
    private val learningStore by lazy { LearningStore.getInstance(requireContext()) }
    private val screenWakeManager by lazy { ScreenWakeManager.getInstance(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupListeners()
        loadSavedSettings()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't close serverSync - it's a shared singleton in VisualMapperApp
    }

    private fun initViews(view: View) {
        // Accessibility
        accessibilityStatusDot = view.findViewById(R.id.accessibilityStatusDot)
        textAccessibilityStatus = view.findViewById(R.id.textAccessibilityStatus)
        btnEnableAccessibility = view.findViewById(R.id.btnEnableAccessibility)

        // Server
        editServerUrl = view.findViewById(R.id.editServerUrl)
        editDeviceIp = view.findViewById(R.id.editDeviceIp)
        serverStatusDot = view.findViewById(R.id.serverStatusDot)
        textServerStatus = view.findViewById(R.id.textServerStatus)
        btnServerConnect = view.findViewById(R.id.btnServerConnect)

        // MQTT
        editMqttBroker = view.findViewById(R.id.editMqttBroker)
        editMqttPort = view.findViewById(R.id.editMqttPort)
        editMqttUsername = view.findViewById(R.id.editMqttUsername)
        editMqttPassword = view.findViewById(R.id.editMqttPassword)
        switchMqttSsl = view.findViewById(R.id.switchMqttSsl)  // Phase 1: SSL toggle
        btnSslHelp = view.findViewById(R.id.btnSslHelp)  // Phase 1: SSL help button
        btnSslHelp.setOnClickListener { showMqttHelpDialog() }
        mqttStatusDot = view.findViewById(R.id.mqttStatusDot)
        textMqttStatus = view.findViewById(R.id.textMqttStatus)
        btnMqttConnect = view.findViewById(R.id.btnMqttConnect)
        btnMqttDisconnect = view.findViewById(R.id.btnMqttDisconnect)

        // Backend Services
        cardBackendServices = view.findViewById(R.id.cardBackendServices)
        mlServerStatusDot = view.findViewById(R.id.mlServerStatusDot)
        textMlServerStatus = view.findViewById(R.id.textMlServerStatus)
        btnMlServerStart = view.findViewById(R.id.btnMlServerStart)
        btnMlServerStop = view.findViewById(R.id.btnMlServerStop)
        btnRefreshServices = view.findViewById(R.id.btnRefreshServices)

        // Auto-connect
        switchAutoConnect = view.findViewById(R.id.switchAutoConnect)

        // Device info
        textDeviceId = view.findViewById(R.id.textDeviceId)
        textAndroidId = view.findViewById(R.id.textAndroidId)

        // Navigation
        btnPrivacySettings = view.findViewById(R.id.btnPrivacySettings)
        btnAuditLog = view.findViewById(R.id.btnAuditLog)
        btnExplorationSettings = view.findViewById(R.id.btnExplorationSettings)

        // Test actions
        cardTestActions = view.findViewById(R.id.cardTestActions)
        btnTestGesture = view.findViewById(R.id.btnTestGesture)
        btnTestUiRead = view.findViewById(R.id.btnTestUiRead)

        // ML Training
        cardMlTraining = view.findViewById(R.id.cardMlTraining)
        switchMlTraining = view.findViewById(R.id.switchMlTraining)
        switchAllowAllApps = view.findViewById(R.id.switchAllowAllApps)
        switchDeepExploration = view.findViewById(R.id.switchDeepExploration)
        switchAutoSaveFlows = view.findViewById(R.id.switchAutoSaveFlows)
        textMlStatus = view.findViewById(R.id.textMlStatus)
        textQTableStats = view.findViewById(R.id.textQTableStats)
        btnResetQLearning = view.findViewById(R.id.btnResetQLearning)
        textTrainingStatus = view.findViewById(R.id.textTrainingStatus)
        btnStartBatchTraining = view.findViewById(R.id.btnStartBatchTraining)
        btnStopBatchTraining = view.findViewById(R.id.btnStopBatchTraining)

        // Learning & Connectivity
        radioConnectionMode = view.findViewById(R.id.radioConnectionMode)
        radioConnAuto = view.findViewById(R.id.radioConnAuto)
        radioConnServer = view.findViewById(R.id.radioConnServer)
        radioConnStandalone = view.findViewById(R.id.radioConnStandalone)
        effectiveModeStatusDot = view.findViewById(R.id.effectiveModeStatusDot)
        textEffectiveMode = view.findViewById(R.id.textEffectiveMode)
        radioLearningMode = view.findViewById(R.id.radioLearningMode)
        radioLearnOnDevice = view.findViewById(R.id.radioLearnOnDevice)
        radioLearnSync = view.findViewById(R.id.radioLearnSync)
        radioLearnDisabled = view.findViewById(R.id.radioLearnDisabled)
        textLearningStats = view.findViewById(R.id.textLearningStats)
        btnViewLearningData = view.findViewById(R.id.btnViewLearningData)
        btnClearLearningData = view.findViewById(R.id.btnClearLearningData)

        // Smart Learning toggle
        switchStrategyAdaptive = view.findViewById(R.id.switchStrategyAdaptive)
        textLearnedStrategies = view.findViewById(R.id.textLearnedStrategies)

        // Exploration Strategy
        spinnerStrategy = view.findViewById(R.id.spinnerStrategy)
        textStrategyDescription = view.findViewById(R.id.textStrategyDescription)

        // Multi-Pass Exploration
        spinnerMaxPasses = view.findViewById(R.id.spinnerMaxPasses)
        switchAutoRunPasses = view.findViewById(R.id.switchAutoRunPasses)

        // Flow Execution Settings
        switchWakeScreen = view.findViewById(R.id.switchWakeScreen)
        switchLockAfterFlow = view.findViewById(R.id.switchLockAfterFlow)
        switchFlowOverlay = view.findViewById(R.id.switchFlowOverlay)
        switchPauseOnTouch = view.findViewById(R.id.switchPauseOnTouch)
        spinnerResumeDelay = view.findViewById(R.id.spinnerResumeDelay)
        batteryStatusDot = view.findViewById(R.id.batteryStatusDot)
        textBatteryStatus = view.findViewById(R.id.textBatteryStatus)
        btnBatterySettings = view.findViewById(R.id.btnBatterySettings)
        textBatteryHint = view.findViewById(R.id.textBatteryHint)

        // ADB Connection Sharing
        editAdbPort = view.findViewById(R.id.editAdbPort)
        textDeviceIpAddress = view.findViewById(R.id.textDeviceIpAddress)
        btnAnnounceDevice = view.findViewById(R.id.btnAnnounceDevice)
        btnWithdrawAnnouncement = view.findViewById(R.id.btnWithdrawAnnouncement)
        textAdbSharingStatus = view.findViewById(R.id.textAdbSharingStatus)

        // Display device IP address
        updateDeviceIpDisplay()

        android.util.Log.i("SettingsFragment", "=== initViews completed ===")
        android.util.Log.i("SettingsFragment", "btnStartBatchTraining found: ${btnStartBatchTraining != null}")
    }

    private fun setupListeners() {
        // Accessibility
        btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // Server
        btnServerConnect.setOnClickListener {
            connectToServer()
        }

        // MQTT
        btnMqttConnect.setOnClickListener {
            connectMqtt()
        }
        btnMqttDisconnect.setOnClickListener {
            disconnectMqtt()
        }

        // ADB Connection Sharing
        btnAnnounceDevice.setOnClickListener {
            announceDevice()
        }
        btnWithdrawAnnouncement.setOnClickListener {
            withdrawAnnouncement()
        }

        // Backend Services
        btnMlServerStart.setOnClickListener {
            startMlServer()
        }
        btnMlServerStop.setOnClickListener {
            stopMlServer()
        }
        btnRefreshServices.setOnClickListener {
            refreshServicesStatus()
        }

        // Auto-connect
        switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            saveAutoConnectSetting(isChecked)
        }

        // Navigation
        btnPrivacySettings.setOnClickListener {
            startActivity(Intent(requireContext(), PrivacySettingsActivity::class.java))
        }
        btnAuditLog.setOnClickListener {
            startActivity(Intent(requireContext(), AuditLogActivity::class.java))
        }
        btnExplorationSettings.setOnClickListener {
            startActivity(Intent(requireContext(), ExplorationSettingsActivity::class.java))
        }

        // Test actions
        btnTestGesture.setOnClickListener {
            testGesture()
        }
        btnTestUiRead.setOnClickListener {
            testUiRead()
        }

        // ML Training
        switchMlTraining.setOnCheckedChangeListener { _, isChecked ->
            saveMlTrainingSetting(isChecked)
            updateMlTrainingStatus(isChecked)
        }
        switchAllowAllApps.setOnCheckedChangeListener { _, isChecked ->
            saveAllowAllAppsSetting(isChecked)
        }
        switchDeepExploration.setOnCheckedChangeListener { _, isChecked ->
            saveDeepExplorationSetting(isChecked)
        }
        switchAutoSaveFlows.setOnCheckedChangeListener { _, isChecked ->
            saveAutoSaveFlowsSetting(isChecked)
        }
        btnResetQLearning.setOnClickListener {
            resetQLearning()
        }

        // Batch Training
        android.util.Log.i("SettingsFragment", "Setting up batch training button listener")
        btnStartBatchTraining.setOnClickListener {
            android.util.Log.i("SettingsFragment", "*** BATCH TRAINING BUTTON CLICKED ***")
            startBatchTraining()
        }
        btnStopBatchTraining.setOnClickListener {
            stopBatchTraining()
        }

        // Learning & Connectivity
        radioConnectionMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioConnAuto -> ConnectionMode.AUTO
                R.id.radioConnServer -> ConnectionMode.SERVER_ONLY
                R.id.radioConnStandalone -> ConnectionMode.STANDALONE
                else -> ConnectionMode.AUTO
            }
            connectionManager.setConnectionMode(mode)
            updateEffectiveModeDisplay()
        }

        radioLearningMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioLearnOnDevice -> LearningMode.ON_DEVICE
                R.id.radioLearnSync -> LearningMode.SERVER_SYNC
                R.id.radioLearnDisabled -> LearningMode.DISABLED
                else -> LearningMode.ON_DEVICE
            }
            connectionManager.setLearningMode(mode)
        }

        // Smart Learning toggle
        val strategyPrefs = requireContext().getSharedPreferences("exploration_strategies", Context.MODE_PRIVATE)
        switchStrategyAdaptive.setOnCheckedChangeListener { _, isChecked ->
            strategyPrefs.edit().putBoolean("adaptive_enabled", isChecked).apply()
            if (isChecked) {
                Toast.makeText(requireContext(), "Smart Learning: Will optimize exploration per app", Toast.LENGTH_SHORT).show()
            }
        }

        btnViewLearningData.setOnClickListener {
            showLearningDataDialog()
        }

        btnClearLearningData.setOnClickListener {
            showClearLearningConfirmation()
        }

        // Exploration Strategy
        setupStrategySettings()

        // Multi-Pass Exploration
        setupMultiPassSettings()

        // Flow Execution Settings
        setupFlowExecutionSettings()

        android.util.Log.i("SettingsFragment", "=== setupListeners completed ===")
    }

    private fun loadSavedSettings() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)

        // Load saved values
        prefs.getString("server_url", null)?.let { editServerUrl.setText(it) }
        prefs.getString("device_ip", null)?.let { editDeviceIp.setText(it) }
        prefs.getString("mqtt_broker", null)?.let { editMqttBroker.setText(it) }
        val savedMqttPort = prefs.getInt("mqtt_port", 1883)
        editMqttPort.setText(savedMqttPort.toString())
        switchMqttSsl.isChecked = prefs.getBoolean("mqtt_ssl", false)  // Phase 1: Load SSL preference

        val autoConnect = prefs.getBoolean("auto_connect", true)
        switchAutoConnect.isChecked = autoConnect

        // Device info
        updateDeviceIdDisplay()

        // Auto-detect IP if not saved
        if (prefs.getString("device_ip", null) == null) {
            tryDetectDeviceIp()
        }

        // ML Training settings
        val mlTrainingEnabled = prefs.getBoolean("ml_training_enabled", false)
        switchMlTraining.isChecked = mlTrainingEnabled
        updateMlTrainingStatus(mlTrainingEnabled)

        // Allow All Apps (training mode)
        val allowAllApps = prefs.getBoolean("allow_all_apps_training", false)
        switchAllowAllApps.isChecked = allowAllApps

        // Deep exploration mode (goal-based coverage)
        val deepExploration = prefs.getBoolean("deep_exploration", false)
        switchDeepExploration.isChecked = deepExploration

        // Auto-save generated flows
        val autoSaveFlows = prefs.getBoolean("auto_save_flows", false)
        switchAutoSaveFlows.isChecked = autoSaveFlows

        updateQTableStats()

        // Refresh backend services status
        refreshServicesStatus()

        // Learning & Connectivity settings
        loadLearningSettings()

        // ADB Connection Sharing
        loadSavedAdbPort()
    }

    private fun loadLearningSettings() {
        // Connection Mode
        when (connectionManager.getConnectionMode()) {
            ConnectionMode.AUTO -> radioConnAuto.isChecked = true
            ConnectionMode.SERVER_ONLY -> radioConnServer.isChecked = true
            ConnectionMode.STANDALONE -> radioConnStandalone.isChecked = true
        }

        // Learning Mode
        when (connectionManager.getLearningMode()) {
            LearningMode.ON_DEVICE -> radioLearnOnDevice.isChecked = true
            LearningMode.SERVER_SYNC -> radioLearnSync.isChecked = true
            LearningMode.DISABLED -> radioLearnDisabled.isChecked = true
        }

        // Update displays
        updateEffectiveModeDisplay()
        updateLearningStatsDisplay()

        // Load Smart Learning toggle state
        val strategyPrefs = requireContext().getSharedPreferences("exploration_strategies", Context.MODE_PRIVATE)
        switchStrategyAdaptive.isChecked = strategyPrefs.getBoolean("adaptive_enabled", false)

        // Display learned strategies
        updateLearnedStrategiesDisplay()

        // Observe effective mode changes
        viewLifecycleOwner.lifecycleScope.launch {
            connectionManager.effectiveMode.collectLatest { mode ->
                updateEffectiveModeDisplay()
            }
        }
    }

    private fun updateLearnedStrategiesDisplay() {
        val learnedStrategies = learningStore.getAllLearnedStrategies()
        if (learnedStrategies.isEmpty()) {
            textLearnedStrategies.text = "No learned strategies yet. Run ADAPTIVE exploration to learn."
        } else {
            val text = buildString {
                append("Learned best strategies:\n")
                learnedStrategies.forEach { (packageName, strategy) ->
                    val appName = packageName.substringAfterLast(".")
                    append("• $appName: $strategy\n")
                }
            }
            textLearnedStrategies.text = text.trim()
        }
    }

    private fun updateEffectiveModeDisplay() {
        val mode = connectionManager.effectiveMode.value
        val (text, dotRes) = when (mode) {
            OperationMode.SERVER -> "Mode: Connected to Server" to R.drawable.status_dot_green
            OperationMode.STANDALONE -> "Mode: Standalone (Offline)" to R.drawable.status_dot_yellow
            OperationMode.SERVER_UNAVAILABLE -> "Mode: Server Required (Unavailable)" to R.drawable.status_dot_red
            OperationMode.CHECKING -> "Mode: Checking..." to R.drawable.status_dot_gray
        }
        textEffectiveMode.text = text
        effectiveModeStatusDot.setBackgroundResource(dotRes)
    }

    private fun updateLearningStatsDisplay() {
        val feedbackStats = feedbackStore.getStats()
        val appMapStats = appMapStore.getStats()
        val learningStats = learningStore.getStats()

        val statsText = buildString {
            append("User Feedback: ${feedbackStats.totalFeedback} edits\n")
            append("App Maps: ${appMapStats.totalApps} apps, ${appMapStats.totalScreens} screens\n")
            append("Patterns: ${learningStats.patternCount} patterns, ${learningStats.dangerCount} dangers")
        }
        textLearningStats.text = statsText
    }

    private fun showLearningDataDialog() {
        val feedbackStats = feedbackStore.getStats()
        val appMapStats = appMapStore.getStats()
        val learningStats = learningStore.getStats()

        val details = buildString {
            appendLine("=== User Feedback ===")
            appendLine("Total edits recorded: ${feedbackStats.totalFeedback}")
            appendLine("Name changes: ${feedbackStats.nameChanges}")
            appendLine("Rejections: ${feedbackStats.rejections}")
            appendLine("Device class changes: ${feedbackStats.deviceClassChanges}")
            appendLine("Unit changes: ${feedbackStats.unitChanges}")
            appendLine("Unique apps: ${feedbackStats.uniqueApps}")
            appendLine()
            appendLine("=== App Maps ===")
            appendLine("Apps learned: ${appMapStats.totalApps}")
            appendLine("Screens mapped: ${appMapStats.totalScreens}")
            appendLine("Navigation paths: ${appMapStats.totalPaths}")
            appendLine()
            appendLine("=== Patterns ===")
            appendLine("Value patterns: ${learningStats.patternCount}")
            appendLine("Dangerous elements: ${learningStats.dangerCount}")
            appendLine("Semantic contexts: ${learningStats.semanticCount}")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Learning Data")
            .setMessage(details)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showClearLearningConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Learning Data")
            .setMessage("This will delete all learned patterns, app maps, and user feedback. This action cannot be undone.\n\nAre you sure?")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllLearningData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllLearningData() {
        feedbackStore.clearAll()
        appMapStore.clearAll()
        learningStore.clearAll()

        updateLearningStatsDisplay()
        Toast.makeText(requireContext(), "Learning data cleared", Toast.LENGTH_SHORT).show()
    }

    // === Exploration Strategy Settings ===

    private fun setupStrategySettings() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)

        // Strategy options with user-friendly names
        val strategyOptions = listOf(
            "Adaptive (Auto-learn)",      // ADAPTIVE
            "Screen First (Thorough)",    // SCREEN_FIRST
            "Systematic (Read like book)", // SYSTEMATIC
            "Priority Based (Smart)",     // PRIORITY_BASED
            "Depth First (Deep)",         // DEPTH_FIRST
            "Breadth First (Wide)"        // BREADTH_FIRST
        )
        val strategyValues = listOf(
            "ADAPTIVE",
            "SCREEN_FIRST",
            "SYSTEMATIC",
            "PRIORITY_BASED",
            "DEPTH_FIRST",
            "BREADTH_FIRST"
        )
        // Descriptions explaining what each strategy does
        val strategyDescriptions = listOf(
            "Uses Q-Learning to try different strategies and learns which works best for each app. Remembers optimal approaches across sessions.",
            "Explores all elements on current screen before navigating to new screens. Best for thorough single-screen coverage.",
            "Reads the screen like a book: top-left to bottom-right, row by row. Good for forms, lists, and predictable layouts.",
            "Uses ML-boosted priority scores to select the most valuable elements first. Focuses on high-impact interactions.",
            "Follows navigation deep into the app before backtracking. Good for exploring nested menus and settings.",
            "Explores all options at each level before going deeper. Good for discovering all available screens."
        )

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            strategyOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStrategy.adapter = adapter

        // Load saved value (default to ADAPTIVE)
        val savedStrategy = prefs.getString("exploration_strategy", "ADAPTIVE") ?: "ADAPTIVE"
        val selectedIndex = strategyValues.indexOf(savedStrategy).takeIf { it >= 0 } ?: 0
        spinnerStrategy.setSelection(selectedIndex)

        // Show initial description
        textStrategyDescription.text = strategyDescriptions[selectedIndex]

        // Flag to prevent saving during initial load
        var isUserSelection = false

        // Save on selection and update description
        spinnerStrategy.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Always update description
                textStrategyDescription.text = strategyDescriptions[position]

                if (!isUserSelection) {
                    isUserSelection = true
                    return
                }
                val value = strategyValues[position]
                prefs.edit().putString("exploration_strategy", value).apply()
                android.util.Log.i("SettingsFragment", "Strategy set to: $value")
                Toast.makeText(requireContext(), "${strategyOptions[position]} selected", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // === Multi-Pass Exploration Settings ===

    private fun setupMultiPassSettings() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)

        // Spinner options: 1, 2, 3, 5, "Until 100%"
        val passOptions = listOf("1 Pass", "2 Passes", "3 Passes", "5 Passes", "Until 100%")
        val passValues = listOf(1, 2, 3, 5, 0)  // 0 = unlimited until target

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            passOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMaxPasses.adapter = adapter

        // Load saved value
        val savedMaxPasses = prefs.getInt("max_exploration_passes", 1)
        val selectedIndex = passValues.indexOf(savedMaxPasses).takeIf { it >= 0 } ?: 0
        spinnerMaxPasses.setSelection(selectedIndex)

        // Flag to prevent saving during initial load
        var isUserSelection = false

        // Save on selection
        spinnerMaxPasses.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUserSelection) {
                    // First call is from setSelection during init - skip saving
                    isUserSelection = true
                    return
                }
                val value = passValues[position]
                prefs.edit().putInt("max_exploration_passes", value).apply()
                android.util.Log.i("SettingsFragment", "Max passes set to: $value")
                Toast.makeText(requireContext(), "${passOptions[position]} selected", Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Auto-run passes toggle
        val autoRun = prefs.getBoolean("auto_run_passes", false)
        switchAutoRunPasses.isChecked = autoRun

        switchAutoRunPasses.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_run_passes", isChecked).apply()
            val message = if (isChecked) {
                "Auto-run: All passes will run automatically"
            } else {
                "Manual mode: You'll be prompted after each pass"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    // === Flow Execution Settings ===

    private fun setupFlowExecutionSettings() {
        val prefs = requireContext().getSharedPreferences("flow_execution", Context.MODE_PRIVATE)

        // Load saved values
        switchWakeScreen.isChecked = prefs.getBoolean("wake_screen", true)
        switchLockAfterFlow.isChecked = prefs.getBoolean("lock_after_flow", false)
        switchFlowOverlay.isChecked = prefs.getBoolean("show_overlay", true)
        switchPauseOnTouch.isChecked = prefs.getBoolean("pause_on_touch", true)

        // Resume delay spinner
        val delayOptions = listOf("3 seconds", "5 seconds", "7 seconds", "10 seconds")
        val delayValues = listOf(3000L, 5000L, 7000L, 10000L)

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            delayOptions
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerResumeDelay.adapter = adapter

        // Load saved delay
        val savedDelay = prefs.getLong("resume_delay", 3000L)
        val delayIndex = delayValues.indexOf(savedDelay).takeIf { it >= 0 } ?: 0
        spinnerResumeDelay.setSelection(delayIndex)

        // Flag to prevent saving during initial load
        var isUserSelection = false

        spinnerResumeDelay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!isUserSelection) {
                    isUserSelection = true
                    return
                }
                val value = delayValues[position]
                prefs.edit().putLong("resume_delay", value).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Toggle listeners
        switchWakeScreen.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wake_screen", isChecked).apply()
        }

        switchLockAfterFlow.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("lock_after_flow", isChecked).apply()
        }

        switchFlowOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_overlay", isChecked).apply()
        }

        switchPauseOnTouch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pause_on_touch", isChecked).apply()
        }

        // Battery settings button
        btnBatterySettings.setOnClickListener {
            openBatteryOptimizationSettings()
        }

        // Check battery optimization status
        updateBatteryOptimizationStatus()
    }

    private fun updateBatteryOptimizationStatus() {
        val status = screenWakeManager.getBatteryOptimizationStatus()

        if (!status.isIgnoringBatteryOptimizations) {
            // Battery optimization is ON (bad for flows)
            batteryStatusDot.setBackgroundResource(R.drawable.status_dot_yellow)
            textBatteryStatus.text = "Battery optimization enabled (may affect flows)"
            btnBatterySettings.visibility = View.VISIBLE
            textBatteryHint.visibility = View.VISIBLE

            // Show manufacturer-specific hint
            val instructions = screenWakeManager.getBatteryOptimizationInstructions()
            textBatteryHint.text = instructions
        } else {
            // Battery optimization is OFF (good)
            batteryStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            textBatteryStatus.text = "Battery optimization disabled (optimal)"
            btnBatterySettings.visibility = View.GONE
            textBatteryHint.visibility = View.GONE
        }

        // Show extra warning if device has manufacturer-specific optimization
        if (status.hasManufacturerOptimization && !status.isIgnoringBatteryOptimizations) {
            textBatteryStatus.text = "${status.manufacturer} battery optimization may affect flows"
        }
    }

    private fun openBatteryOptimizationSettings() {
        // Try manufacturer-specific intent first
        val manufacturerIntent = screenWakeManager.getManufacturerBatterySettingsIntent()
        if (manufacturerIntent != null) {
            try {
                startActivity(manufacturerIntent)
                return
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment", "Manufacturer battery settings not available")
            }
        }

        // Try standard battery optimization request
        val standardIntent = screenWakeManager.getBatteryOptimizationSettingsIntent()
        if (standardIntent != null) {
            try {
                startActivity(standardIntent)
                return
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment", "Battery optimization intent failed")
            }
        }

        // Fallback to general battery optimization settings
        try {
            val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(fallbackIntent)
        } catch (e: Exception) {
            // Last resort: open app settings
            try {
                val appIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(appIntent)
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Could not open settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveSettings() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_url", editServerUrl.text.toString())
            putString("device_ip", editDeviceIp.text.toString())
            putString("mqtt_broker", editMqttBroker.text.toString())
            putInt("mqtt_port", editMqttPort.text.toString().toIntOrNull() ?: 1883)
            putBoolean("mqtt_ssl", switchMqttSsl.isChecked)  // Phase 1: Save SSL preference
            apply()
        }
    }

    private fun saveAutoConnectSetting(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_connect", enabled).apply()
    }

    private fun tryDetectDeviceIp() {
        try {
            // Use NetworkInterface to get accurate device IP (more reliable than deprecated WifiManager)
            val ip = getDeviceIpAddress()
            if (ip != null) {
                editDeviceIp.setText(ip)
                android.util.Log.i("SettingsFragment", "Auto-detected device IP: $ip")
            } else {
                android.util.Log.w("SettingsFragment", "Could not auto-detect device IP")
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsFragment", "Failed to auto-detect IP: ${e.message}")
        }
    }

    /**
     * Get device IP address using NetworkInterface (more reliable than deprecated WifiManager)
     */
    private fun getDeviceIpAddress(): String? {
        try {
            // Iterate through all network interfaces
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                // Prefer WiFi interface (wlan0)
                val isWifi = networkInterface.name.startsWith("wlan")

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Skip IPv6 and loopback
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        // Skip link-local addresses
                        if (ip != null && !ip.startsWith("169.254")) {
                            // Prefer WiFi IP, but return any valid IP if no WiFi
                            if (isWifi) {
                                return ip
                            }
                        }
                    }
                }
            }

            // Fallback: Try any non-loopback IPv4 address
            val interfaces2 = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val networkInterface = interfaces2.nextElement()
                if (!networkInterface.isUp) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("169.254") && !ip.startsWith("127.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SettingsFragment", "Error getting device IP: ${e.message}")
        }
        return null
    }

    private fun observeState() {
        // Observe MQTT connection state
        viewLifecycleOwner.lifecycleScope.launch {
            app.mqttManager.connectionState.collectLatest { state ->
                updateMqttStatus(state)
            }
        }

        // Observe server connection state
        viewLifecycleOwner.lifecycleScope.launch {
            serverSync.connectionState.collectLatest { state ->
                updateServerStatus(state)
            }
        }

        // Observe batch training state
        viewLifecycleOwner.lifecycleScope.launch {
            TrainingOrchestrator.getInstance(requireContext())
                .trainingState
                .collectLatest { state ->
                    updateBatchTrainingStatus(state)
                }
        }
    }

    private fun updateBatchTrainingStatus(state: TrainingOrchestrator.TrainingState) {
        if (state.isRunning) {
            val statusText = buildString {
                append("Training: App ${state.currentAppIndex}/${state.totalApps}")
                state.currentApp?.let { app ->
                    val shortName = app.substringAfterLast(".")
                    append(" - $shortName")
                }
                if (state.appsCompleted > 0) {
                    append("\nCompleted: ${state.appsCompleted}")
                }
                if (state.appsFailed > 0) {
                    append(", Failed: ${state.appsFailed}")
                }
            }
            textTrainingStatus.text = statusText
            btnStartBatchTraining.visibility = View.GONE
            btnStopBatchTraining.visibility = View.VISIBLE
        } else {
            if (state.appsCompleted > 0 || state.appsFailed > 0) {
                textTrainingStatus.text = "Last run: ${state.appsCompleted} completed, ${state.appsFailed} failed"
            } else {
                textTrainingStatus.text = "Status: Idle"
            }
            btnStartBatchTraining.visibility = View.VISIBLE
            btnStopBatchTraining.visibility = View.GONE
        }
    }

    private fun startBatchTraining() {
        android.util.Log.i("SettingsFragment", "=== START BATCH TRAINING CLICKED ===")

        // Step 1: Check if accessibility service is enabled
        val accessibilityRunning = VisualMapperAccessibilityService.isRunning()
        android.util.Log.i("SettingsFragment", "Accessibility running: $accessibilityRunning")

        if (!accessibilityRunning) {
            showPermissionDialog(
                title = "Accessibility Service Required",
                message = "Batch training needs the Accessibility Service to read app screens and perform gestures.\n\nWould you like to enable it now?",
                positiveText = "Open Settings",
                onPositive = {
                    openAccessibilitySettings()
                }
            )
            return
        }

        // Step 2: Check overlay permission
        if (!android.provider.Settings.canDrawOverlays(requireContext())) {
            showPermissionDialog(
                title = "Overlay Permission Required",
                message = "Batch training needs overlay permission to show the exploration progress indicator.\n\nWould you like to grant it now?",
                positiveText = "Grant Permission",
                onPositive = {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                }
            )
            return
        }

        // Step 3: Check POST_NOTIFICATIONS permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                showPermissionDialog(
                    title = "Notification Permission Required",
                    message = "Batch training needs notification permission to show progress updates.\n\nWould you like to grant it now?",
                    positiveText = "Grant Permission",
                    onPositive = {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
                return
            }
        }

        // All permissions granted - start training
        doStartBatchTraining()
    }

    private fun showPermissionDialog(
        title: String,
        message: String,
        positiveText: String,
        onPositive: () -> Unit
    ) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onPositive() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show MQTT connection error with helpful guidance for SSL issues.
     */
    private fun showMqttErrorDialog(errorMessage: String) {
        try {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("MQTT Connection Failed")
                .setMessage(errorMessage)
                .setPositiveButton("OK", null)
                .setNeutralButton("Help") { _, _ ->
                    showMqttHelpDialog()
                }
                .show()
        } catch (e: Exception) {
            // Fragment may not be attached
            android.util.Log.w("SettingsFragment", "Could not show error dialog: ${e.message}")
        }
    }

    /**
     * Show help dialog for MQTT/SSL setup.
     */
    private fun showMqttHelpDialog() {
        val helpText = """
            |MQTT Connection Help
            |
            |Standard Connection (no SSL):
            |• Port: 1883 (default)
            |• SSL toggle: OFF
            |
            |Secure Connection (SSL/TLS):
            |• Port: 8883 (standard SSL port)
            |• SSL toggle: ON
            |• Broker must have SSL enabled
            |• Firewall must allow port 8883
            |
            |Common Issues:
            |• Timeout: Check firewall/port
            |• Refused: Broker not running
            |• Certificate: SSL not configured
            |
            |For Home Assistant Mosquitto:
            |Add to mosquitto.conf:
            |  listener 8883
            |  certfile /ssl/cert.pem
            |  keyfile /ssl/key.pem
        """.trimMargin()

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("MQTT Setup Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Actually start batch training (called after permission check passes)
     */
    private fun doStartBatchTraining() {
        // Check if ML Training mode is enabled
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val mlTrainingEnabled = prefs.getBoolean("ml_training_enabled", false)
        val allowAllApps = prefs.getBoolean("allow_all_apps_training", false)

        if (!mlTrainingEnabled) {
            Toast.makeText(
                requireContext(),
                "Please enable ML Training Mode first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check if there are any apps to train
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        val securePrefs = accessibilityService?.getSecurePreferences()

        val hasConsentedApps = securePrefs?.getConsentsJson()?.let {
            try {
                val jsonArray = org.json.JSONArray(it)
                var count = 0
                for (i in 0 until jsonArray.length()) {
                    val consent = jsonArray.optJSONObject(i)
                    val level = consent?.optString("level", "NONE") ?: "NONE"
                    if (level != "NONE") count++
                }
                count > 0
            } catch (e: Exception) { false }
        } ?: false

        val hasWhitelistedApps = securePrefs?.whitelistedApps?.isNotEmpty() ?: false

        if (!allowAllApps && !hasConsentedApps && !hasWhitelistedApps) {
            Toast.makeText(
                requireContext(),
                "No apps available for training!\n\nEither:\n• Enable 'Allow All Apps' toggle, or\n• Go to Privacy Settings to consent apps",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Create training config
        val config = TrainingOrchestrator.TrainingConfig(
            enabled = true,
            requireCharging = false,  // Don't require charging for manual start
            requireIdle = false,      // Don't require idle for manual start
            minutesPerApp = 5,
            maxAppsPerSession = 10
        )

        android.util.Log.i("SettingsFragment", "Starting AutoTrainingService with config: $config")

        // Start training service
        AutoTrainingService.start(requireContext(), config, AutoTrainingService.TRIGGER_MANUAL)

        Toast.makeText(requireContext(), "Starting batch training...", Toast.LENGTH_SHORT).show()
    }

    private fun stopBatchTraining() {
        AutoTrainingService.stop(requireContext())
        Toast.makeText(requireContext(), "Stopping training...", Toast.LENGTH_SHORT).show()
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = VisualMapperAccessibilityService.isRunning()

        if (isEnabled) {
            accessibilityStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            textAccessibilityStatus.text = "Enabled"
            btnEnableAccessibility.visibility = View.GONE
            cardTestActions.visibility = View.VISIBLE
        } else {
            accessibilityStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
            textAccessibilityStatus.text = "Not enabled"
            btnEnableAccessibility.visibility = View.VISIBLE
            cardTestActions.visibility = View.GONE
        }
    }

    private fun updateMqttStatus(state: MqttManager.ConnectionState) {
        when (state) {
            MqttManager.ConnectionState.CONNECTED -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                textMqttStatus.text = "Connected"
                btnMqttConnect.visibility = View.GONE
                btnMqttDisconnect.visibility = View.VISIBLE
            }
            MqttManager.ConnectionState.CONNECTING -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                textMqttStatus.text = "Connecting..."
            }
            MqttManager.ConnectionState.ERROR -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                val errorMsg = app.mqttManager.lastError.value
                // Show short status, full error in toast
                textMqttStatus.text = if (errorMsg != null) {
                    // Take first line for status text
                    errorMsg.lines().firstOrNull()?.take(40) ?: "Error"
                } else {
                    "Connection failed"
                }
                // Show full error as toast for user guidance
                if (errorMsg != null && errorMsg.length > 40) {
                    showMqttErrorDialog(errorMsg)
                }
                btnMqttConnect.visibility = View.VISIBLE
                btnMqttDisconnect.visibility = View.GONE
            }
            MqttManager.ConnectionState.DISCONNECTED -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                textMqttStatus.text = "Disconnected"
                btnMqttConnect.visibility = View.VISIBLE
                btnMqttDisconnect.visibility = View.GONE
            }
        }
    }

    private fun updateServerStatus(state: ServerSyncManager.ConnectionState) {
        when (state) {
            ServerSyncManager.ConnectionState.CONNECTED -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                textServerStatus.text = "Connected"
                btnServerConnect.text = "Disconnect"
            }
            ServerSyncManager.ConnectionState.CONNECTING -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                textServerStatus.text = "Connecting..."
            }
            ServerSyncManager.ConnectionState.MQTT_ONLY -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_yellow)
                textServerStatus.text = "MQTT Only (HTTP skipped)"
                btnServerConnect.text = "Disconnect"
            }
            ServerSyncManager.ConnectionState.ERROR -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                val error = serverSync.lastError.value ?: "Server not reachable"
                textServerStatus.text = error
                btnServerConnect.text = "Retry"
            }
            ServerSyncManager.ConnectionState.DISCONNECTED -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                textServerStatus.text = "Not connected"
                btnServerConnect.text = "Connect"
            }
        }
    }

    private fun updateDeviceIdDisplay() {
        val hasStableId = app.stableDeviceId != app.androidId
        textDeviceId.text = if (hasStableId) {
            "Stable ID: ${app.stableDeviceId.take(16)}..."
        } else {
            "Stable ID: Not synced with server"
        }
        textAndroidId.text = "Android ID: ${app.androidId.take(16)}..."
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(requireContext(), "Find 'Visual Mapper Companion' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun connectMqtt() {
        val broker = editMqttBroker.text.toString().trim()
        val portStr = editMqttPort.text.toString().trim()
        val username = editMqttUsername.text.toString().trim().takeIf { it.isNotEmpty() }
        val password = editMqttPassword.text.toString().trim().takeIf { it.isNotEmpty() }

        if (broker.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter MQTT broker address", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull() ?: 1883

        saveSettings()

        app.mqttManager.connect(
            brokerHost = broker,
            brokerPort = port,
            username = username,
            password = password,
            deviceId = app.stableDeviceId,
            useSsl = switchMqttSsl.isChecked  // Phase 1: Pass SSL setting
        )
    }

    private fun disconnectMqtt() {
        app.mqttManager.disconnect()
    }

    private fun connectToServer() {
        val currentState = serverSync.connectionState.value

        if (currentState == ServerSyncManager.ConnectionState.CONNECTED) {
            serverSync.disconnect()
            return
        }

        val url = editServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter server URL", Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        viewLifecycleOwner.lifecycleScope.launch {
            // Try to get stable device ID from server using device IP
            val deviceIp = editDeviceIp.text.toString().trim()

            // If we have a device IP and don't have a stable ID yet, fetch it from server
            if (deviceIp.isNotEmpty() && app.stableDeviceId == app.androidId) {
                Toast.makeText(requireContext(), "Fetching stable device ID...", Toast.LENGTH_SHORT).show()

                // Construct ADB device ID (IP:PORT format for wireless ADB)
                val adbDeviceId = "$deviceIp:46747"

                val stableId = serverSync.getStableDeviceId(url, adbDeviceId)
                if (stableId != null) {
                    app.setStableDeviceId(stableId)
                    updateDeviceIdDisplay()
                    Toast.makeText(requireContext(), "Synced with server! Device ID: ${stableId.take(8)}...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Could not fetch stable ID from server (using Android ID)", Toast.LENGTH_SHORT).show()
                }
            }

            val success = serverSync.connect(url, app.stableDeviceId)
            if (success) {
                Toast.makeText(requireContext(), "Connected to server", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testGesture() {
        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(requireContext(), "Accessibility service not running", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Test Gesture")
            .setMessage("Gesture test is working!\n\nTo test gestures properly, use MQTT commands:\n\n" +
                    "Topic: visualmapper/devices/{device_id}/actions\n" +
                    "Payload: {\"action\":\"tap\",\"x\":500,\"y\":1000}\n\n" +
                    "This avoids the app interfering with the gesture.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun testUiRead() {
        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(requireContext(), "Accessibility service not running", Toast.LENGTH_SHORT).show()
            return
        }

        val elements = service.getUITree()
        val clickableCount = elements.count { it.isClickable }
        val textElements = elements.filter { it.text.isNotEmpty() }

        val message = "Found ${elements.size} elements\n" +
                "$clickableCount clickable\n" +
                "${textElements.size} with text"

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

        // Log first few text elements
        textElements.take(5).forEach { element ->
            android.util.Log.d("UITree", "Text: ${element.text} @ ${element.bounds}")
        }
    }

    // === ML Training Methods ===

    private fun saveMlTrainingSetting(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("ml_training_enabled", enabled).apply()

        // Notify MqttManager of the setting change
        app.mqttManager.setMlTrainingEnabled(enabled)
    }

    private fun saveAllowAllAppsSetting(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_all_apps_training", enabled).apply()

        // Show warning when enabling
        if (enabled) {
            Toast.makeText(
                requireContext(),
                "Training mode: All apps can be explored (sensitive apps still skipped)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveDeepExplorationSetting(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("deep_exploration", enabled).apply()

        val message = if (enabled) {
            "Deep exploration: Target 90% coverage (up to 30 minutes per app)"
        } else {
            "Quick exploration: Fast survey with standard limits"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun saveAutoSaveFlowsSetting(enabled: Boolean) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_save_flows", enabled).apply()

        val message = if (enabled) {
            "Generated flows will be auto-saved"
        } else {
            "Generated flows will require review before saving"
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMlTrainingStatus(enabled: Boolean) {
        if (enabled) {
            textMlStatus.text = "Status: Enabled - Logs will be sent to ML server"
        } else {
            textMlStatus.text = "Status: Disabled"
        }
    }

    private fun updateQTableStats() {
        try {
            // Get Q-learning stats from SharedPreferences
            val qPrefs = requireContext().getSharedPreferences("exploration_q_table", Context.MODE_PRIVATE)
            val qTableJson = qPrefs.getString("q_table", null)
            val dangerousPatternsJson = qPrefs.getString("dangerous_patterns", null)

            val qTableSize = if (qTableJson != null) {
                try {
                    org.json.JSONObject(qTableJson).length()
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }

            val dangerousCount = if (dangerousPatternsJson != null) {
                try {
                    org.json.JSONArray(dangerousPatternsJson).length()
                } catch (e: Exception) {
                    0
                }
            } else {
                0
            }

            textQTableStats.text = "Q-Table: $qTableSize entries, $dangerousCount dangerous patterns"
        } catch (e: Exception) {
            textQTableStats.text = "Q-Table: Unable to read stats"
            android.util.Log.e("SettingsFragment", "Error reading Q-table stats", e)
        }
    }

    private fun resetQLearning() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Q-Learning Data")
            .setMessage("This will delete all learned exploration patterns. The app will need to relearn optimal exploration strategies. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                // Clear Q-table SharedPreferences
                val qPrefs = requireContext().getSharedPreferences("exploration_q_table", Context.MODE_PRIVATE)
                qPrefs.edit().clear().apply()

                // Send reset command to ML server via MQTT
                if (app.mqttManager.connectionState.value == MqttManager.ConnectionState.CONNECTED) {
                    app.mqttManager.publishCommand("visualmapper/exploration/command", "{\"command\":\"reset\"}")
                }

                updateQTableStats()
                Toast.makeText(requireContext(), "Q-Learning data reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // Backend Services Control
    // =========================================================================

    private fun refreshServicesStatus() {
        val serverUrl = editServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Server URL not configured", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                textMlServerStatus.text = "Checking..."
                mlServerStatusDot.setBackgroundResource(R.drawable.status_dot_gray)

                // Run network call on IO thread
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$serverUrl/api/services/ml/status")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    val responseCode = connection.responseCode
                    val response = if (responseCode == 200) {
                        connection.inputStream.bufferedReader().readText()
                    } else null
                    connection.disconnect()
                    Pair(responseCode, response)
                }

                val (responseCode, response) = result
                if (responseCode == 200 && response != null) {
                    val json = org.json.JSONObject(response)
                    val running = json.optBoolean("running", false)
                    val details = json.optString("details", "")
                    val dockerMode = json.optBoolean("docker_mode", false)

                    if (running) {
                        mlServerStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                        textMlServerStatus.text = if (dockerMode) "Running (Docker)" else "Running"
                    } else {
                        mlServerStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                        textMlServerStatus.text = details.ifEmpty { "Stopped" }
                    }

                    // In Docker mode, Start/Stop buttons are disabled
                    if (dockerMode) {
                        btnMlServerStart.visibility = View.VISIBLE
                        btnMlServerStop.visibility = View.GONE
                        btnMlServerStart.isEnabled = false
                        btnMlServerStart.alpha = 0.5f
                        btnMlServerStart.text = "Start (N/A)"
                    } else if (running) {
                        btnMlServerStart.visibility = View.GONE
                        btnMlServerStop.visibility = View.VISIBLE
                        btnMlServerStop.isEnabled = true
                        btnMlServerStop.alpha = 1.0f
                    } else {
                        btnMlServerStart.visibility = View.VISIBLE
                        btnMlServerStop.visibility = View.GONE
                        btnMlServerStart.isEnabled = true
                        btnMlServerStart.alpha = 1.0f
                        btnMlServerStart.text = "Start"
                    }
                } else {
                    mlServerStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                    textMlServerStatus.text = "Error: $responseCode"
                }
            } catch (e: Exception) {
                mlServerStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                textMlServerStatus.text = "Unreachable"
                android.util.Log.e("SettingsFragment", "Failed to check ML server status", e)
            }
        }
    }

    private fun startMlServer() {
        val serverUrl = editServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Server URL not configured", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnMlServerStart.isEnabled = false
                btnMlServerStart.text = "Starting..."

                val responseCode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$serverUrl/api/services/ml/start")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val code = connection.responseCode
                    connection.disconnect()
                    code
                }

                if (responseCode == 200) {
                    Toast.makeText(requireContext(), "ML Training Server started", Toast.LENGTH_SHORT).show()
                    refreshServicesStatus()
                } else {
                    Toast.makeText(requireContext(), "Failed to start: HTTP $responseCode", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message ?: "Network error"}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("SettingsFragment", "Failed to start ML server", e)
            } finally {
                btnMlServerStart.isEnabled = true
                btnMlServerStart.text = "Start"
            }
        }
    }

    private fun stopMlServer() {
        val serverUrl = editServerUrl.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Server URL not configured", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                btnMlServerStop.isEnabled = false
                btnMlServerStop.text = "Stopping..."

                val responseCode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val url = java.net.URL("$serverUrl/api/services/ml/stop")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val code = connection.responseCode
                    connection.disconnect()
                    code
                }

                if (responseCode == 200) {
                    Toast.makeText(requireContext(), "ML Training Server stopped", Toast.LENGTH_SHORT).show()
                    refreshServicesStatus()
                } else {
                    Toast.makeText(requireContext(), "Failed to stop: HTTP $responseCode", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed: ${e.message ?: "Network error"}", Toast.LENGTH_SHORT).show()
                android.util.Log.e("SettingsFragment", "Failed to stop ML server", e)
            } finally {
                btnMlServerStop.isEnabled = true
                btnMlServerStop.text = "Stop"
            }
        }
    }

    // === ADB Connection Sharing Methods ===

    private fun updateDeviceIpDisplay() {
        val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ipInt = wifiInfo?.ipAddress ?: 0

        if (ipInt != 0) {
            val ip = String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                ipInt shr 8 and 0xff,
                ipInt shr 16 and 0xff,
                ipInt shr 24 and 0xff
            )
            textDeviceIpAddress.text = "Device IP: $ip"
        } else {
            textDeviceIpAddress.text = "Device IP: Not connected to WiFi"
        }
    }

    private fun announceDevice() {
        val portText = editAdbPort.text?.toString()?.trim()
        if (portText.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "Please enter your ADB port from Wireless Debugging settings", Toast.LENGTH_LONG).show()
            return
        }

        val port = portText.toIntOrNull()
        if (port == null || port < 1 || port > 65535) {
            Toast.makeText(requireContext(), "Invalid port number", Toast.LENGTH_SHORT).show()
            return
        }

        // Check MQTT connection
        val mqttManager = app.mqttManager
        if (mqttManager.connectionState.value != MqttManager.ConnectionState.CONNECTED) {
            Toast.makeText(requireContext(), "MQTT not connected. Connect to MQTT first.", Toast.LENGTH_LONG).show()
            return
        }

        // Announce device
        mqttManager.announceDeviceForConnection(port)

        isAdbSharing = true
        updateAdbSharingUI()

        // Save port for next time
        requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            .edit()
            .putInt("adb_port", port)
            .apply()

        Toast.makeText(requireContext(), "Connection info shared with server", Toast.LENGTH_SHORT).show()
    }

    private fun withdrawAnnouncement() {
        val mqttManager = app.mqttManager
        mqttManager.withdrawDeviceAnnouncement()

        isAdbSharing = false
        updateAdbSharingUI()

        Toast.makeText(requireContext(), "Stopped sharing connection info", Toast.LENGTH_SHORT).show()
    }

    private fun updateAdbSharingUI() {
        if (isAdbSharing) {
            btnAnnounceDevice.visibility = View.GONE
            btnWithdrawAnnouncement.visibility = View.VISIBLE
            textAdbSharingStatus.text = "Sharing connection info with server"
            textAdbSharingStatus.setTextColor(resources.getColor(R.color.success, null))
        } else {
            btnAnnounceDevice.visibility = View.VISIBLE
            btnWithdrawAnnouncement.visibility = View.GONE
            textAdbSharingStatus.text = "Not sharing"
            textAdbSharingStatus.setTextColor(resources.getColor(R.color.text_secondary, null))
        }
    }

    private fun loadSavedAdbPort() {
        val savedPort = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            .getInt("adb_port", 0)
        if (savedPort > 0) {
            editAdbPort.setText(savedPort.toString())
        }
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}

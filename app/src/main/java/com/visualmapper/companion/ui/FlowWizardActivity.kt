package com.visualmapper.companion.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.service.Flow
import com.visualmapper.companion.service.FlowExecutorService
import com.visualmapper.companion.service.FlowStep
import com.visualmapper.companion.service.StepType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Flow Wizard Activity
 *
 * Guides user through creating a new flow:
 * 1. Select target app
 * 2. Record steps (taps, swipes)
 * 3. Add sensor capture
 * 4. Review and save
 */
class FlowWizardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FlowWizard"
    }

    private val app by lazy { application as VisualMapperApp }

    // Current step
    private var currentStep = 1
    private val totalSteps = 4

    // Step containers
    private lateinit var layoutStep1: View
    private lateinit var layoutStep2: View
    private lateinit var layoutStep3: View
    private lateinit var layoutStep4: View

    // Progress
    private lateinit var textStepIndicator: TextView
    private lateinit var progressBar: ProgressBar

    // Navigation
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    // Step 1: App Selection
    private lateinit var recyclerApps: RecyclerView
    private lateinit var editSearchApps: EditText
    private var installedApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()
    private var selectedApp: AppInfo? = null

    // Step 2: Recording
    private lateinit var imageScreenshot: ImageView
    private lateinit var textRecordingStatus: TextView
    private lateinit var btnLaunchApp: MaterialButton
    private lateinit var btnCaptureScreen: MaterialButton
    private lateinit var recyclerSteps: RecyclerView
    private lateinit var btnAddTap: MaterialButton
    private lateinit var btnAddSwipe: MaterialButton
    private lateinit var btnAddWait: MaterialButton
    private var recordedSteps: MutableList<FlowStep> = mutableListOf()
    private var currentScreenshot: Bitmap? = null
    private var tapStartX: Float = 0f
    private var tapStartY: Float = 0f

    // Step 3: Sensor Capture
    private lateinit var textSensorInstructions: TextView
    private lateinit var btnAddSensor: MaterialButton
    private lateinit var recyclerSensors: RecyclerView
    private var sensorSteps: MutableList<FlowStep> = mutableListOf()

    // Step 4: Review
    private lateinit var editFlowName: EditText
    private lateinit var editFlowDescription: EditText
    private lateinit var spinnerExecutionMethod: Spinner
    private lateinit var textReviewStepCount: TextView
    private lateinit var textReviewSensorCount: TextView
    private lateinit var btnSaveFlow: MaterialButton

    // Device info
    private var deviceId: String = ""
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_wizard)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Create Flow"

        deviceId = intent.getStringExtra("device_id") ?: getLocalDeviceId()

        initViews()
        setupNavigation()
        loadInstalledApps()
        showStep(1)
    }

    private fun getLocalDeviceId(): String {
        // Get device ID from accessibility service or settings
        return VisualMapperAccessibilityService.getInstance()?.let {
            // Use a combination of device info
            "${Build.MODEL}_${Build.SERIAL}".replace(" ", "_")
        } ?: "android_device"
    }

    private fun initViews() {
        // Progress
        textStepIndicator = findViewById(R.id.textStepIndicator)
        progressBar = findViewById(R.id.progressBar)

        // Navigation
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)

        // Step containers
        layoutStep1 = findViewById(R.id.layoutStep1)
        layoutStep2 = findViewById(R.id.layoutStep2)
        layoutStep3 = findViewById(R.id.layoutStep3)
        layoutStep4 = findViewById(R.id.layoutStep4)

        // Step 1: App Selection
        recyclerApps = findViewById(R.id.recyclerApps)
        editSearchApps = findViewById(R.id.editSearchApps)
        recyclerApps.layoutManager = LinearLayoutManager(this)

        // Step 2: Recording
        imageScreenshot = findViewById(R.id.imageScreenshot)
        textRecordingStatus = findViewById(R.id.textRecordingStatus)
        btnLaunchApp = findViewById(R.id.btnLaunchApp)
        btnCaptureScreen = findViewById(R.id.btnCaptureScreen)
        recyclerSteps = findViewById(R.id.recyclerSteps)
        btnAddTap = findViewById(R.id.btnAddTap)
        btnAddSwipe = findViewById(R.id.btnAddSwipe)
        btnAddWait = findViewById(R.id.btnAddWait)
        recyclerSteps.layoutManager = LinearLayoutManager(this)

        // Step 3: Sensors
        textSensorInstructions = findViewById(R.id.textSensorInstructions)
        btnAddSensor = findViewById(R.id.btnAddSensor)
        recyclerSensors = findViewById(R.id.recyclerSensors)
        recyclerSensors.layoutManager = LinearLayoutManager(this)

        // Step 4: Review
        editFlowName = findViewById(R.id.editFlowName)
        editFlowDescription = findViewById(R.id.editFlowDescription)
        spinnerExecutionMethod = findViewById(R.id.spinnerExecutionMethod)
        textReviewStepCount = findViewById(R.id.textReviewStepCount)
        textReviewSensorCount = findViewById(R.id.textReviewSensorCount)
        btnSaveFlow = findViewById(R.id.btnSaveFlow)

        setupStep1()
        setupStep2()
        setupStep3()
        setupStep4()
    }

    private fun setupNavigation() {
        btnBack.setOnClickListener {
            if (currentStep > 1) {
                showStep(currentStep - 1)
            } else {
                finish()
            }
        }

        btnNext.setOnClickListener {
            if (validateCurrentStep()) {
                if (currentStep < totalSteps) {
                    showStep(currentStep + 1)
                }
            }
        }
    }

    private fun showStep(step: Int) {
        currentStep = step

        // Update progress
        textStepIndicator.text = "Step $step of $totalSteps"
        progressBar.progress = (step * 100) / totalSteps

        // Hide all steps
        layoutStep1.visibility = View.GONE
        layoutStep2.visibility = View.GONE
        layoutStep3.visibility = View.GONE
        layoutStep4.visibility = View.GONE

        // Show current step
        when (step) {
            1 -> {
                layoutStep1.visibility = View.VISIBLE
                btnBack.text = "Cancel"
                btnNext.text = "Next"
                btnNext.isEnabled = selectedApp != null
            }
            2 -> {
                layoutStep2.visibility = View.VISIBLE
                btnBack.text = "Back"
                btnNext.text = "Next"
                btnNext.isEnabled = true
                updateStepsList()
            }
            3 -> {
                layoutStep3.visibility = View.VISIBLE
                btnBack.text = "Back"
                btnNext.text = "Next"
                btnNext.isEnabled = true
                updateSensorsList()
            }
            4 -> {
                layoutStep4.visibility = View.VISIBLE
                btnBack.text = "Back"
                btnNext.visibility = View.GONE
                prepareReview()
            }
        }
    }

    private fun validateCurrentStep(): Boolean {
        return when (currentStep) {
            1 -> {
                if (selectedApp == null) {
                    Toast.makeText(this, "Please select an app", Toast.LENGTH_SHORT).show()
                    false
                } else true
            }
            2 -> {
                // Steps are optional - can have flow with just launch + sensors
                true
            }
            3 -> {
                // Sensors are optional
                true
            }
            else -> true
        }
    }

    // ==================== STEP 1: App Selection ====================

    private fun setupStep1() {
        editSearchApps.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                packages
                    .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || hasLaunchIntent(pm, it.packageName) }
                    .filter { hasLaunchIntent(pm, it.packageName) }
                    .map { appInfo ->
                        AppInfo(
                            packageName = appInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            icon = try { pm.getApplicationIcon(appInfo.packageName) } catch (e: Exception) { null }
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }

            installedApps = apps
            filteredApps = apps
            updateAppsList()
        }
    }

    private fun hasLaunchIntent(pm: PackageManager, packageName: String): Boolean {
        return pm.getLaunchIntentForPackage(packageName) != null
    }

    private fun filterApps(query: String) {
        filteredApps = if (query.isBlank()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        updateAppsList()
    }

    private fun updateAppsList() {
        recyclerApps.adapter = AppAdapter(filteredApps) { app ->
            selectedApp = app
            btnNext.isEnabled = true
            updateAppsList() // Refresh to show selection
        }
    }

    // ==================== STEP 2: Recording ====================

    private fun setupStep2() {
        btnLaunchApp.setOnClickListener {
            launchSelectedApp()
        }

        btnCaptureScreen.setOnClickListener {
            captureCurrentScreen()
        }

        btnAddTap.setOnClickListener {
            Toast.makeText(this, "Tap on the screenshot to add a tap step", Toast.LENGTH_SHORT).show()
        }

        btnAddSwipe.setOnClickListener {
            Toast.makeText(this, "Swipe on the screenshot to add a swipe step", Toast.LENGTH_SHORT).show()
        }

        btnAddWait.setOnClickListener {
            addWaitStep()
        }

        // Screenshot tap handling
        imageScreenshot.setOnTouchListener { _, event ->
            handleScreenshotTouch(event)
            true
        }
    }

    private fun launchSelectedApp() {
        val app = selectedApp ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
            Toast.makeText(this, "Launching ${app.label}...", Toast.LENGTH_SHORT).show()

            // Auto-add launch step
            val launchStep = FlowStep(
                type = StepType.LAUNCH_APP,
                packageName = app.packageName,
                description = "Launch ${app.label}"
            )
            if (recordedSteps.isEmpty() || recordedSteps.first().type != StepType.LAUNCH_APP) {
                recordedSteps.add(0, launchStep)
                updateStepsList()
            }
        }
    }

    private fun captureCurrentScreen() {
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Toast.makeText(this, "Accessibility service not available", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                textRecordingStatus.text = "Capturing screen..."

                // Get screen dimensions
                val displayMetrics = resources.displayMetrics
                screenWidth = displayMetrics.widthPixels
                screenHeight = displayMetrics.heightPixels

                // Check if server is connected
                try {
                    val serverSync = app.serverSyncManager
                    val connectionState = serverSync.connectionState.value

                    when (connectionState) {
                        com.visualmapper.companion.server.ServerSyncManager.ConnectionState.CONNECTED -> {
                            // TODO: Implement actual screenshot capture via server API
                            // For now, allow manual recording without screenshot
                            textRecordingStatus.text = "Navigate to target app, tap to record actions"
                            Toast.makeText(this@FlowWizardActivity,
                                "Server connected. Tap on screen area to record tap/swipe actions.",
                                Toast.LENGTH_LONG).show()
                        }
                        com.visualmapper.companion.server.ServerSyncManager.ConnectionState.MQTT_ONLY -> {
                            textRecordingStatus.text = "MQTT only - manual recording available"
                            Toast.makeText(this@FlowWizardActivity,
                                "HTTP not available. Tap on screen area to record actions.",
                                Toast.LENGTH_LONG).show()
                        }
                        com.visualmapper.companion.server.ServerSyncManager.ConnectionState.ERROR -> {
                            val error = serverSync.lastError.value ?: "Server not reachable"
                            textRecordingStatus.text = "Server error: $error"
                        }
                        else -> {
                            textRecordingStatus.text = "Server not connected - manual recording only"
                            Toast.makeText(this@FlowWizardActivity,
                                "Connect to server in Settings for full features",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    textRecordingStatus.text = "Manual recording mode"
                    Log.w(TAG, "Server sync not available: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen", e)
                textRecordingStatus.text = "Capture failed: ${e.message}"
            }
        }
    }

    private fun handleScreenshotTouch(event: MotionEvent) {
        if (currentScreenshot == null) {
            // No screenshot - just record raw coordinates scaled to image view
            val imageWidth = imageScreenshot.width.toFloat()
            val imageHeight = imageScreenshot.height.toFloat()

            // Validate dimensions to prevent division by zero crash
            if (imageWidth <= 0 || imageHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
                Toast.makeText(this, "Please wait for screen to load", Toast.LENGTH_SHORT).show()
                return
            }

            // Scale touch coordinates to device screen coordinates
            val scaleX = screenWidth.toFloat() / imageWidth
            val scaleY = screenHeight.toFloat() / imageHeight

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    tapStartX = event.x * scaleX
                    tapStartY = event.y * scaleY
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.x * scaleX
                    val endY = event.y * scaleY

                    val dx = kotlin.math.abs(endX - tapStartX)
                    val dy = kotlin.math.abs(endY - tapStartY)

                    if (dx < 50 && dy < 50) {
                        // Tap
                        addTapStep(tapStartX.toInt(), tapStartY.toInt())
                    } else {
                        // Swipe
                        addSwipeStep(
                            tapStartX.toInt(), tapStartY.toInt(),
                            endX.toInt(), endY.toInt()
                        )
                    }
                }
            }
        }
    }

    private fun addTapStep(x: Int, y: Int) {
        val step = FlowStep(
            type = StepType.TAP,
            x = x,
            y = y,
            description = "Tap at ($x, $y)"
        )
        recordedSteps.add(step)
        updateStepsList()
        Toast.makeText(this, "Added tap at ($x, $y)", Toast.LENGTH_SHORT).show()
    }

    private fun addSwipeStep(startX: Int, startY: Int, endX: Int, endY: Int) {
        val step = FlowStep(
            type = StepType.SWIPE,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = 300,
            description = "Swipe from ($startX, $startY) to ($endX, $endY)"
        )
        recordedSteps.add(step)
        updateStepsList()
        Toast.makeText(this, "Added swipe gesture", Toast.LENGTH_SHORT).show()
    }

    private fun addWaitStep() {
        val step = FlowStep(
            type = StepType.WAIT,
            duration = 1000,
            description = "Wait 1 second"
        )
        recordedSteps.add(step)
        updateStepsList()
        Toast.makeText(this, "Added wait step", Toast.LENGTH_SHORT).show()
    }

    private fun updateStepsList() {
        recyclerSteps.adapter = StepAdapter(recordedSteps) { position ->
            recordedSteps.removeAt(position)
            updateStepsList()
        }
    }

    // ==================== STEP 3: Sensor Capture ====================

    private fun setupStep3() {
        btnAddSensor.setOnClickListener {
            addSensorCaptureStep()
        }
    }

    private fun addSensorCaptureStep() {
        // For now, add a placeholder capture_sensors step
        // In full implementation, would show element picker
        val step = FlowStep(
            type = StepType.CAPTURE_SENSORS,
            sensorIds = emptyList(),
            description = "Capture sensors"
        )
        sensorSteps.add(step)
        updateSensorsList()
        Toast.makeText(this, "Sensor capture added. Configure sensors on server.", Toast.LENGTH_LONG).show()
    }

    private fun updateSensorsList() {
        recyclerSensors.adapter = SensorStepAdapter(sensorSteps) { position ->
            sensorSteps.removeAt(position)
            updateSensorsList()
        }
    }

    // ==================== STEP 4: Review & Save ====================

    private fun setupStep4() {
        // Execution method spinner
        val methods = listOf("android", "server", "auto")
        spinnerExecutionMethod.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, methods)

        btnSaveFlow.setOnClickListener {
            saveFlow()
        }
    }

    private fun prepareReview() {
        val app = selectedApp ?: return

        // Set default name
        if (editFlowName.text.isBlank()) {
            editFlowName.setText("${app.label} Flow")
        }

        // Update counts
        val totalSteps = recordedSteps.size + sensorSteps.size
        textReviewStepCount.text = "$totalSteps steps"
        textReviewSensorCount.text = "${sensorSteps.size} sensor captures"
    }

    private fun saveFlow() {
        val targetApp = selectedApp ?: return

        val flowName = editFlowName.text.toString().ifBlank { "${targetApp.label} Flow" }
        val description = editFlowDescription.text.toString()
        val executionMethod = spinnerExecutionMethod.selectedItem.toString()

        // Build flow
        val allSteps = mutableListOf<FlowStep>()

        // Add launch step if not present
        if (recordedSteps.none { it.type == StepType.LAUNCH_APP }) {
            allSteps.add(FlowStep(
                type = StepType.LAUNCH_APP,
                packageName = targetApp.packageName,
                description = "Launch ${targetApp.label}"
            ))
        }

        allSteps.addAll(recordedSteps)
        allSteps.addAll(sensorSteps)

        val flowId = "flow_${deviceId}_${System.currentTimeMillis()}"
        val flow = Flow(
            id = flowId,
            deviceId = deviceId,
            name = flowName,
            description = description,
            steps = allSteps,
            executionMethod = executionMethod,
            enabled = true,
            updateIntervalSeconds = 60
        )

        // Save flow
        lifecycleScope.launch {
            try {
                // Save to FlowExecutorService AND persist to storage
                FlowExecutorService.addFlowAndPersist(flow, this@FlowWizardActivity, deviceId)

                // Try to sync to server (optional - flow is saved locally)
                try {
                    val serverSync = app.serverSyncManager
                    // POST to server API - for now, just log
                    Log.i(TAG, "Flow saved locally: $flowId (server sync not implemented yet)")
                } catch (e: UninitializedPropertyAccessException) {
                    Log.i(TAG, "Flow saved locally: $flowId (server not connected)")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not sync to server: ${e.message}")
                }

                val totalFlows = FlowExecutorService.getFlowCount()
                Toast.makeText(this@FlowWizardActivity, "Flow saved: $flowName (total: $totalFlows flows)", Toast.LENGTH_LONG).show()
                finish()

            } catch (e: Exception) {
                Log.e(TAG, "Error saving flow", e)
                Toast.makeText(this@FlowWizardActivity, "Error saving flow: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ==================== Data Classes ====================

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?
    )

    // ==================== Adapters ====================

    inner class AppAdapter(
        private val apps: List<AppInfo>,
        private val onSelect: (AppInfo) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.imageAppIcon)
            val label: TextView = view.findViewById(R.id.textAppLabel)
            val packageName: TextView = view.findViewById(R.id.textAppPackage)
            val card: MaterialCardView = view.findViewById(R.id.cardApp)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_app_select, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.label.text = app.label
            holder.packageName.text = app.packageName
            app.icon?.let { holder.icon.setImageDrawable(it) }

            val isSelected = app.packageName == selectedApp?.packageName
            holder.card.isChecked = isSelected
            holder.card.strokeWidth = if (isSelected) 4 else 0

            holder.card.setOnClickListener {
                onSelect(app)
            }
        }

        override fun getItemCount() = apps.size
    }

    inner class StepAdapter(
        private val steps: List<FlowStep>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<StepAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val number: TextView = view.findViewById(R.id.textStepNumber)
            val type: TextView = view.findViewById(R.id.textStepType)
            val description: TextView = view.findViewById(R.id.textStepDescription)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteStep)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_wizard_step, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val step = steps[position]
            holder.number.text = "${position + 1}"
            holder.type.text = step.type.name
            holder.description.text = step.description ?: getStepDescription(step)
            holder.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = steps.size

        private fun getStepDescription(step: FlowStep): String {
            return when (step.type) {
                StepType.LAUNCH_APP -> "Launch ${step.packageName}"
                StepType.TAP -> "Tap at (${step.x}, ${step.y})"
                StepType.SWIPE -> "Swipe"
                StepType.WAIT -> "Wait ${step.duration}ms"
                StepType.CAPTURE_SENSORS -> "Capture sensors"
                else -> step.type.name
            }
        }
    }

    inner class SensorStepAdapter(
        private val steps: List<FlowStep>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<SensorStepAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val number: TextView = view.findViewById(R.id.textStepNumber)
            val type: TextView = view.findViewById(R.id.textStepType)
            val description: TextView = view.findViewById(R.id.textStepDescription)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteStep)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_wizard_step, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val step = steps[position]
            holder.number.text = "${position + 1}"
            holder.type.text = "CAPTURE"
            holder.description.text = "Capture sensors (configure on server)"
            holder.btnDelete.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = steps.size
    }
}

package com.visualmapper.companion.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.visualmapper.companion.R
import com.visualmapper.companion.service.Flow
import com.visualmapper.companion.service.FlowExecutorService
import com.visualmapper.companion.service.StepType

/**
 * Activity for viewing and editing flow details
 *
 * Features:
 * - Display full flow information
 * - Edit execution method (server/android/auto)
 * - Configure preferred/fallback executor for auto mode
 * - View execution statistics
 * - Execute flow on demand
 */
class FlowDetailActivity : AppCompatActivity() {

    // View references
    private lateinit var textFlowName: TextView
    private lateinit var textDescription: TextView
    private lateinit var textFlowId: TextView
    private lateinit var textDeviceId: TextView
    private lateinit var viewStatus: View
    private lateinit var textStatus: TextView
    private lateinit var switchEnabled: SwitchMaterial

    // Execution method
    private lateinit var radioGroupExecution: RadioGroup
    private lateinit var radioServer: RadioButton
    private lateinit var radioAndroid: RadioButton
    private lateinit var radioAuto: RadioButton
    private lateinit var layoutAutoSettings: View
    private lateinit var spinnerPreferred: Spinner
    private lateinit var spinnerFallback: Spinner

    // Schedule & steps
    private lateinit var textInterval: TextView
    private lateinit var textStepCount: TextView
    private lateinit var textSensorCount: TextView
    private lateinit var textStopOnError: TextView

    // Statistics
    private lateinit var textLastExecuted: TextView
    private lateinit var textLastResult: TextView
    private lateinit var textExecutionCount: TextView
    private lateinit var textSuccessRate: TextView

    // Buttons
    private lateinit var btnSave: Button
    private lateinit var btnExecute: Button

    // Data
    private var flowId: String? = null
    private var deviceId: String? = null
    private var currentFlow: Flow? = null

    // Executor options
    private val executorOptions = listOf("android", "server")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_detail)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get flow ID from intent
        flowId = intent.getStringExtra("flow_id")
        deviceId = intent.getStringExtra("device_id")

        if (flowId == null) {
            Toast.makeText(this, "Flow ID not provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupSpinners()
        setupListeners()
        loadFlow()
    }

    private fun initViews() {
        // Flow info
        textFlowName = findViewById(R.id.textFlowName)
        textDescription = findViewById(R.id.textDescription)
        textFlowId = findViewById(R.id.textFlowId)
        textDeviceId = findViewById(R.id.textDeviceId)
        viewStatus = findViewById(R.id.viewStatus)
        textStatus = findViewById(R.id.textStatus)
        switchEnabled = findViewById(R.id.switchEnabled)

        // Execution method
        radioGroupExecution = findViewById(R.id.radioGroupExecution)
        radioServer = findViewById(R.id.radioServer)
        radioAndroid = findViewById(R.id.radioAndroid)
        radioAuto = findViewById(R.id.radioAuto)
        layoutAutoSettings = findViewById(R.id.layoutAutoSettings)
        spinnerPreferred = findViewById(R.id.spinnerPreferred)
        spinnerFallback = findViewById(R.id.spinnerFallback)

        // Schedule & steps
        textInterval = findViewById(R.id.textInterval)
        textStepCount = findViewById(R.id.textStepCount)
        textSensorCount = findViewById(R.id.textSensorCount)
        textStopOnError = findViewById(R.id.textStopOnError)

        // Statistics
        textLastExecuted = findViewById(R.id.textLastExecuted)
        textLastResult = findViewById(R.id.textLastResult)
        textExecutionCount = findViewById(R.id.textExecutionCount)
        textSuccessRate = findViewById(R.id.textSuccessRate)

        // Buttons
        btnSave = findViewById(R.id.btnSave)
        btnExecute = findViewById(R.id.btnExecute)
    }

    private fun setupSpinners() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            executorOptions.map { it.replaceFirstChar { c -> c.uppercase() } }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerPreferred.adapter = adapter
        spinnerFallback.adapter = adapter
    }

    private fun setupListeners() {
        // Execution method radio group
        radioGroupExecution.setOnCheckedChangeListener { _, checkedId ->
            val showAutoSettings = checkedId == R.id.radioAuto
            layoutAutoSettings.visibility = if (showAutoSettings) View.VISIBLE else View.GONE
        }

        // Enable switch
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            updateStatusDisplay(isChecked)
        }

        // Save button
        btnSave.setOnClickListener {
            saveChanges()
        }

        // Execute button
        btnExecute.setOnClickListener {
            executeFlow()
        }
    }

    private fun loadFlow() {
        val flow = FlowExecutorService.getFlow(flowId!!)

        if (flow == null) {
            Toast.makeText(this, "Flow not found: $flowId", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentFlow = flow
        supportActionBar?.title = flow.name

        // Populate UI
        textFlowName.text = flow.name
        textFlowId.text = flow.id
        textDeviceId.text = flow.deviceId

        // Description
        if (!flow.description.isNullOrBlank()) {
            textDescription.text = flow.description
            textDescription.visibility = View.VISIBLE
        } else {
            textDescription.visibility = View.GONE
        }

        // Status
        switchEnabled.isChecked = flow.enabled
        updateStatusDisplay(flow.enabled)

        // Execution method
        when (flow.executionMethod) {
            "server" -> radioServer.isChecked = true
            "android" -> radioAndroid.isChecked = true
            "auto" -> {
                radioAuto.isChecked = true
                layoutAutoSettings.visibility = View.VISIBLE
            }
            else -> radioServer.isChecked = true
        }

        // Preferred/Fallback executors
        val preferredIndex = executorOptions.indexOf(flow.preferredExecutor)
        val fallbackIndex = executorOptions.indexOf(flow.fallbackExecutor)
        if (preferredIndex >= 0) spinnerPreferred.setSelection(preferredIndex)
        if (fallbackIndex >= 0) spinnerFallback.setSelection(fallbackIndex)

        // Schedule & steps
        textInterval.text = "${flow.updateIntervalSeconds}s"
        textStepCount.text = flow.steps.size.toString()

        // Count sensors (steps that produce sensor values)
        val sensorCount = flow.steps.count {
            it.type == StepType.EXTRACT_TEXT || it.type == StepType.CAPTURE_SENSORS
        }
        textSensorCount.text = sensorCount.toString()

        textStopOnError.text = if (flow.stopOnError) "Yes" else "No"

        // Execution statistics
        textLastExecuted.text = flow.lastExecuted ?: "Never"
        textLastResult.text = when (flow.lastSuccess) {
            true -> "Success"
            false -> "Failed"
            null -> "-"
        }
        textExecutionCount.text = flow.executionCount.toString()

        // Calculate success rate
        val totalExecutions = flow.executionCount
        if (totalExecutions > 0) {
            val rate = (flow.successCount.toFloat() / totalExecutions * 100).toInt()
            textSuccessRate.text = "$rate% (${flow.successCount}/${totalExecutions})"
        } else {
            textSuccessRate.text = "N/A"
        }
    }

    private fun updateStatusDisplay(enabled: Boolean) {
        viewStatus.setBackgroundResource(
            if (enabled) R.drawable.circle_green else R.drawable.circle_gray
        )
        textStatus.text = if (enabled) "Enabled" else "Disabled"
        textStatus.setTextColor(
            resources.getColor(
                if (enabled) android.R.color.holo_green_dark else android.R.color.darker_gray,
                theme
            )
        )
    }

    private fun saveChanges() {
        val flow = currentFlow ?: return

        // Determine new execution method
        val executionMethod = when {
            radioServer.isChecked -> "server"
            radioAndroid.isChecked -> "android"
            radioAuto.isChecked -> "auto"
            else -> "server"
        }

        // Get preferred/fallback from spinners
        val preferred = executorOptions[spinnerPreferred.selectedItemPosition]
        val fallback = executorOptions[spinnerFallback.selectedItemPosition]

        // Create updated flow
        val updatedFlow = flow.copy(
            enabled = switchEnabled.isChecked,
            executionMethod = executionMethod,
            preferredExecutor = preferred,
            fallbackExecutor = fallback
        )

        // Update cached flow
        FlowExecutorService.addOrUpdateFlow(updatedFlow)
        currentFlow = updatedFlow

        Toast.makeText(this, "Changes saved locally", Toast.LENGTH_SHORT).show()

        // TODO: Sync changes back to server via MQTT or HTTP
        // For now, changes are only saved locally
    }

    private fun executeFlow() {
        val flow = currentFlow ?: return

        // Get full flow from cache
        val fullFlow = FlowExecutorService.getFlow(flow.id)
        if (fullFlow == null) {
            Toast.makeText(this, "Flow not found in cache", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Executing ${flow.name}...", Toast.LENGTH_SHORT).show()
        btnExecute.isEnabled = false

        // Start FlowExecutorService via Intent (works even if service not running)
        val flowJson = kotlinx.serialization.json.Json.encodeToString(
            com.visualmapper.companion.service.Flow.serializer(),
            fullFlow
        )
        val intent = android.content.Intent(this, FlowExecutorService::class.java).apply {
            action = FlowExecutorService.ACTION_EXECUTE_FLOW
            putExtra(FlowExecutorService.EXTRA_FLOW_JSON, flowJson)
            putExtra(FlowExecutorService.EXTRA_EXECUTION_ID, "${flow.id}_${System.currentTimeMillis()}")
        }

        // Use startForegroundService for Android O+ compatibility
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Re-enable button after a delay (service handles execution asynchronously)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            btnExecute.isEnabled = true
        }, 2000)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}

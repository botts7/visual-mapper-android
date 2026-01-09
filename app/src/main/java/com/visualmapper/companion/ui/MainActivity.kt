package com.visualmapper.companion.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.databinding.ActivityMainBinding
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.service.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main Activity
 *
 * Provides configuration UI for:
 * - Enabling Accessibility Service
 * - Configuring MQTT broker connection
 * - Connecting to Visual Mapper server
 * - Syncing flows
 * - Navigating to settings
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app by lazy { application as VisualMapperApp }
    private lateinit var serverSync: ServerSyncManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverSync = ServerSyncManager(this)

        setupUI()
        observeState()
        loadSavedSettings()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverSync.close()
    }

    private fun setupUI() {
        // Device ID display (show stable ID if available, otherwise Android ID)
        updateDeviceIdDisplay()

        // Accessibility button
        binding.btnEnableAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }

        // MQTT connect button
        binding.btnMqttConnect.setOnClickListener {
            connectMqtt()
        }

        // MQTT disconnect button
        binding.btnMqttDisconnect.setOnClickListener {
            disconnectMqtt()
        }

        // Server connect button
        binding.btnServerConnect.setOnClickListener {
            connectToServer()
        }

        // Sync flows button
        binding.btnSyncFlows.setOnClickListener {
            syncFlows()
        }

        // Test gesture button
        binding.btnTestGesture.setOnClickListener {
            testGesture()
        }

        // Test UI read button
        binding.btnTestUiRead.setOnClickListener {
            testUiRead()
        }

        // Settings navigation
        binding.btnPrivacySettings.setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }

        binding.btnAuditLog.setOnClickListener {
            startActivity(Intent(this, AuditLogActivity::class.java))
        }

        binding.btnViewFlows.setOnClickListener {
            showSyncedFlows()
        }
    }

    private fun loadSavedSettings() {
        // Load saved server URL if any
        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        val savedServerUrl = prefs.getString("server_url", null)
        val savedDeviceIp = prefs.getString("device_ip", null)
        val savedMqttBroker = prefs.getString("mqtt_broker", null)
        val savedMqttPort = prefs.getInt("mqtt_port", 1883)
        val autoConnect = prefs.getBoolean("auto_connect", true)

        savedServerUrl?.let { binding.editServerUrl.setText(it) }
        savedDeviceIp?.let { binding.editDeviceIp.setText(it) }
        savedMqttBroker?.let { binding.editMqttBroker.setText(it) }
        binding.editMqttPort.setText(savedMqttPort.toString())

        // Try to auto-detect device IP from WiFi connection
        if (savedDeviceIp == null) {
            tryDetectDeviceIp()
        }

        // Auto-connect if enabled and we have saved settings
        if (autoConnect) {
            autoReconnect(savedMqttBroker, savedMqttPort, savedServerUrl)
        }
    }

    private fun autoReconnect(mqttBroker: String?, mqttPort: Int, serverUrl: String?) {
        // Auto-connect to MQTT if we have broker settings
        if (!mqttBroker.isNullOrBlank()) {
            android.util.Log.i("MainActivity", "Auto-connecting to MQTT: $mqttBroker:$mqttPort")
            lifecycleScope.launch {
                try {
                    app.mqttManager.connect(
                        brokerHost = mqttBroker,
                        brokerPort = mqttPort,
                        username = null,
                        password = null,
                        deviceId = app.stableDeviceId
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Auto-connect MQTT failed: ${e.message}")
                }
            }
        }

        // Auto-connect to server if we have URL
        if (!serverUrl.isNullOrBlank()) {
            android.util.Log.i("MainActivity", "Auto-connecting to server: $serverUrl")
            lifecycleScope.launch {
                try {
                    kotlinx.coroutines.delay(1000) // Wait for MQTT first
                    serverSync.connect(serverUrl, app.stableDeviceId)
                    // Auto-sync flows after connecting
                    kotlinx.coroutines.delay(500)
                    val deviceIp = binding.editDeviceIp.text.toString().trim()
                    val adbDeviceId = if (deviceIp.isNotEmpty()) "$deviceIp:46747" else null
                    serverSync.syncFlows(app.stableDeviceId, adbDeviceId)
                } catch (e: Exception) {
                    android.util.Log.w("MainActivity", "Auto-connect server failed: ${e.message}")
                }
            }
        }
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        prefs.edit().apply {
            putString("server_url", binding.editServerUrl.text.toString())
            putString("device_ip", binding.editDeviceIp.text.toString())
            putString("mqtt_broker", binding.editMqttBroker.text.toString())
            putInt("mqtt_port", binding.editMqttPort.text.toString().toIntOrNull() ?: 1883)
            apply()
        }
    }

    private fun tryDetectDeviceIp() {
        try {
            val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress

            if (ipAddress != 0) {
                val ip = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    (ipAddress shr 8) and 0xff,
                    (ipAddress shr 16) and 0xff,
                    (ipAddress shr 24) and 0xff
                )
                binding.editDeviceIp.setText(ip)
                android.util.Log.i("MainActivity", "Auto-detected device IP: $ip")
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed to auto-detect IP: ${e.message}")
        }
    }

    private fun observeState() {
        // Observe MQTT connection state
        lifecycleScope.launch {
            app.mqttManager.connectionState.collectLatest { state ->
                updateMqttStatus(state)
            }
        }

        // Observe server connection state
        lifecycleScope.launch {
            serverSync.connectionState.collectLatest { state ->
                updateServerStatus(state)
            }
        }

        // Observe synced flows
        lifecycleScope.launch {
            serverSync.syncedFlows.collectLatest { flows ->
                binding.textFlowCount.text = "Flows: ${flows.size} synced"
            }
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = VisualMapperAccessibilityService.isRunning()

        if (isEnabled) {
            binding.textAccessibilityStatus.text = getString(R.string.accessibility_enabled)
            binding.textAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_dark))
            binding.btnEnableAccessibility.visibility = View.GONE
            binding.cardTestActions.visibility = View.VISIBLE
        } else {
            binding.textAccessibilityStatus.text = getString(R.string.accessibility_disabled)
            binding.textAccessibilityStatus.setTextColor(getColor(android.R.color.holo_red_dark))
            binding.btnEnableAccessibility.visibility = View.VISIBLE
            binding.cardTestActions.visibility = View.GONE
        }
    }

    private fun updateMqttStatus(state: MqttManager.ConnectionState) {
        when (state) {
            MqttManager.ConnectionState.CONNECTED -> {
                binding.textMqttStatus.text = getString(R.string.mqtt_status_connected)
                binding.textMqttStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.btnMqttConnect.visibility = View.GONE
                binding.btnMqttDisconnect.visibility = View.VISIBLE
            }
            MqttManager.ConnectionState.CONNECTING -> {
                binding.textMqttStatus.text = getString(R.string.status_connecting)
                binding.textMqttStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
            MqttManager.ConnectionState.ERROR -> {
                binding.textMqttStatus.text = getString(R.string.status_error)
                binding.textMqttStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.btnMqttConnect.visibility = View.VISIBLE
                binding.btnMqttDisconnect.visibility = View.GONE
            }
            MqttManager.ConnectionState.DISCONNECTED -> {
                binding.textMqttStatus.text = getString(R.string.mqtt_status_disconnected)
                binding.textMqttStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.btnMqttConnect.visibility = View.VISIBLE
                binding.btnMqttDisconnect.visibility = View.GONE
            }
        }
    }

    private fun updateServerStatus(state: ServerSyncManager.ConnectionState) {
        when (state) {
            ServerSyncManager.ConnectionState.CONNECTED -> {
                binding.textServerStatus.text = "Connected"
                binding.textServerStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.btnServerConnect.text = "Disconnect"
                binding.btnSyncFlows.visibility = View.VISIBLE
            }
            ServerSyncManager.ConnectionState.CONNECTING -> {
                binding.textServerStatus.text = "Connecting..."
                binding.textServerStatus.setTextColor(getColor(android.R.color.darker_gray))
            }
            ServerSyncManager.ConnectionState.MQTT_ONLY -> {
                binding.textServerStatus.text = "MQTT Only"
                binding.textServerStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                binding.btnServerConnect.text = "Disconnect"
                binding.btnSyncFlows.visibility = View.GONE  // Can't sync without HTTP
            }
            ServerSyncManager.ConnectionState.ERROR -> {
                val error = serverSync.lastError.value ?: "Server not reachable"
                binding.textServerStatus.text = error
                binding.textServerStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.btnServerConnect.text = "Retry"
                binding.btnSyncFlows.visibility = View.GONE
            }
            ServerSyncManager.ConnectionState.DISCONNECTED -> {
                binding.textServerStatus.text = "Not connected"
                binding.textServerStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.btnServerConnect.text = "Connect"
                binding.btnSyncFlows.visibility = View.GONE
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Find 'Visual Mapper Companion' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun connectMqtt() {
        val broker = binding.editMqttBroker.text.toString().trim()
        val portStr = binding.editMqttPort.text.toString().trim()
        val username = binding.editMqttUsername.text.toString().trim().takeIf { it.isNotEmpty() }
        val password = binding.editMqttPassword.text.toString().trim().takeIf { it.isNotEmpty() }

        if (broker.isEmpty()) {
            Toast.makeText(this, "Please enter MQTT broker address", Toast.LENGTH_SHORT).show()
            return
        }

        val port = portStr.toIntOrNull() ?: 1883

        saveSettings()

        app.mqttManager.connect(
            brokerHost = broker,
            brokerPort = port,
            username = username,
            password = password,
            deviceId = app.stableDeviceId
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

        val url = binding.editServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Please enter server URL", Toast.LENGTH_SHORT).show()
            return
        }

        saveSettings()

        lifecycleScope.launch {
            // Try to get stable device ID from server using device IP
            val deviceIp = binding.editDeviceIp.text.toString().trim()

            // If we have a device IP and don't have a stable ID yet, fetch it from server
            if (deviceIp.isNotEmpty() && app.stableDeviceId == app.androidId) {
                Toast.makeText(this@MainActivity, "Fetching stable device ID...", Toast.LENGTH_SHORT).show()

                // Construct ADB device ID (IP:PORT format for wireless ADB)
                val adbDeviceId = "$deviceIp:46747" // Default wireless ADB port

                val stableId = serverSync.getStableDeviceId(url, adbDeviceId)
                if (stableId != null) {
                    app.setStableDeviceId(stableId)
                    updateDeviceIdDisplay()
                    Toast.makeText(this@MainActivity, "Synced with server! Device ID: ${stableId.take(8)}...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Could not fetch stable ID from server (using Android ID)", Toast.LENGTH_SHORT).show()
                }
            }

            val success = serverSync.connect(url, app.stableDeviceId)
            if (success) {
                Toast.makeText(this@MainActivity, "Connected to server", Toast.LENGTH_SHORT).show()
                // Auto-sync flows after connection
                syncFlows()
            } else {
                Toast.makeText(this@MainActivity, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateDeviceIdDisplay() {
        val hasStableId = app.stableDeviceId != app.androidId
        val displayText = if (hasStableId) {
            "Stable Device ID: ${app.stableDeviceId.take(8)}..."
        } else {
            "Android ID: ${app.androidId.take(8)}... (Not synced with server)"
        }
        binding.textDeviceId.text = displayText
    }

    private fun syncFlows() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Syncing flows...", Toast.LENGTH_SHORT).show()

            // Pass both stable device ID and ADB device ID for backwards compatibility
            val deviceIp = binding.editDeviceIp.text.toString().trim()
            val adbDeviceId = if (deviceIp.isNotEmpty()) "$deviceIp:46747" else null
            val flows = serverSync.syncFlows(app.stableDeviceId, adbDeviceId)

            Toast.makeText(this@MainActivity, "Synced ${flows.size} flows", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSyncedFlows() {
        // Open FlowListActivity
        startActivity(Intent(this, FlowListActivity::class.java))
    }

    private fun showFlowDetails(flow: Flow) {
        val details = buildString {
            appendLine("Name: ${flow.name}")
            appendLine("ID: ${flow.id}")
            appendLine("Device: ${flow.deviceId}")
            appendLine("Enabled: ${flow.enabled}")
            appendLine()
            appendLine("Steps (${flow.steps.size}):")
            flow.steps.forEachIndexed { index, step ->
                appendLine("  ${index + 1}. ${step.type}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(flow.name)
            .setMessage(details)
            .setPositiveButton("Run Now") { _, _ ->
                runFlow(flow)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runFlow(flow: Flow) {
        val intent = Intent(this, com.visualmapper.companion.service.FlowExecutorService::class.java).apply {
            action = com.visualmapper.companion.service.FlowExecutorService.ACTION_EXECUTE_FLOW
            putExtra(
                com.visualmapper.companion.service.FlowExecutorService.EXTRA_FLOW_JSON,
                kotlinx.serialization.json.Json.encodeToString(
                    Flow.serializer(),
                    flow
                )
            )
        }
        startService(intent)
        Toast.makeText(this, "Running flow: ${flow.name}", Toast.LENGTH_SHORT).show()
    }

    private fun testGesture() {
        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, "Accessibility service not running", Toast.LENGTH_SHORT).show()
            return
        }

        // Show info dialog instead of actually tapping (to avoid UI feedback loops)
        AlertDialog.Builder(this)
            .setTitle("Test Gesture")
            .setMessage("Gesture test is working!\n\nTo test gestures properly, use MQTT commands instead:\n\n" +
                    "Topic: visualmapper/devices/{device_id}/actions\n" +
                    "Payload: {\"action\":\"tap\",\"x\":500,\"y\":1000}\n\n" +
                    "This avoids the app interfering with the gesture.")
            .setPositiveButton("OK", null)
            .show()

        android.util.Log.i("MainActivity", "Gesture dispatcher available and ready for MQTT commands")
    }

    private fun testUiRead() {
        val service = VisualMapperAccessibilityService.getInstance()
        if (service == null) {
            Toast.makeText(this, "Accessibility service not running", Toast.LENGTH_SHORT).show()
            return
        }

        val elements = service.getUITree()
        val clickableCount = elements.count { it.isClickable }
        val textElements = elements.filter { it.text.isNotEmpty() }

        val message = "Found ${elements.size} elements\n" +
                "$clickableCount clickable\n" +
                "${textElements.size} with text"

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        // Log first few text elements
        textElements.take(5).forEach { element ->
            android.util.Log.d("UITree", "Text: ${element.text} @ ${element.bounds}")
        }
    }
}

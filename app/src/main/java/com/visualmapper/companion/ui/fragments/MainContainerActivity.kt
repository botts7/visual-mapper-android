package com.visualmapper.companion.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.service.FlowExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Main Container Activity
 *
 * Hosts:
 * - Bottom navigation bar
 * - Fragment container for Dashboard, Devices, Flows, Settings
 * - Handles auto-reconnect on launch
 *
 * Uses replace-based fragment management to avoid "Fragment already added" crashes
 * on configuration changes.
 */
class MainContainerActivity : AppCompatActivity() {

    private val app by lazy { application as VisualMapperApp }
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var toolbar: MaterialToolbar
    // Use shared serverSyncManager from app (singleton)
    private val serverSync get() = app.serverSyncManager

    // Track which nav item is currently selected (for state restoration)
    private var currentNavItemId: Int = R.id.nav_dashboard

    companion object {
        private const val TAG = "MainContainer"
        private const val KEY_CURRENT_NAV = "current_nav_item"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_container)

        initViews()
        setupNavigation()

        // Handle state restoration vs fresh start
        if (savedInstanceState != null) {
            // Restore selected nav item
            currentNavItemId = savedInstanceState.getInt(KEY_CURRENT_NAV, R.id.nav_dashboard)
            // FragmentManager has already restored the fragment, just sync the nav selection
            bottomNavigation.selectedItemId = currentNavItemId
            // Update toolbar title
            updateToolbarTitle(currentNavItemId)
        } else {
            // Fresh start - show dashboard
            showFragment(R.id.nav_dashboard)
            bottomNavigation.selectedItemId = R.id.nav_dashboard
        }

        // Auto-reconnect to MQTT/Server
        autoReconnect()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_NAV, currentNavItemId)
    }

    override fun onResume() {
        super.onResume()
        // Pause flows when app opens - both on server and locally
        pauseFlowsOnAppOpen()
    }

    override fun onPause() {
        super.onPause()
        // Resume flows when app goes to background
        resumeFlowsOnAppClose()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't close serverSync - it's a shared singleton in VisualMapperApp
    }

    /**
     * Pause flows when the companion app opens.
     * This prevents flows from executing while the user is interacting with the app.
     */
    private fun pauseFlowsOnAppOpen() {
        Log.i(TAG, "App opened - pausing flows on device and server")

        // 1. Stop any locally executing flow
        try {
            val stopIntent = Intent(this, FlowExecutorService::class.java).apply {
                action = FlowExecutorService.ACTION_STOP_FLOW
            }
            startService(stopIntent)
            Log.i(TAG, "Sent stop signal to local FlowExecutorService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop local flows: ${e.message}")
        }

        // 2. Notify server to pause flows for this device
        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        val deviceIp = prefs.getString("device_ip", null)
        val adbDeviceId = if (!deviceIp.isNullOrBlank()) "$deviceIp:46747" else null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSync.pauseFlowsForDevice(app.stableDeviceId, adbDeviceId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pause server flows: ${e.message}")
            }
        }
    }

    /**
     * Resume flows when the companion app goes to background.
     * This allows scheduled flows to resume executing.
     */
    private fun resumeFlowsOnAppClose() {
        Log.i(TAG, "App closing - resuming flows on server")

        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        val deviceIp = prefs.getString("device_ip", null)
        val adbDeviceId = if (!deviceIp.isNullOrBlank()) "$deviceIp:46747" else null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSync.resumeFlowsForDevice(app.stableDeviceId, adbDeviceId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resume server flows: ${e.message}")
            }
        }
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        setSupportActionBar(toolbar)
    }

    private fun setupNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            // Only switch if selecting a different tab
            if (item.itemId != currentNavItemId) {
                showFragment(item.itemId)
            }
            true
        }
    }

    /**
     * Show a fragment for the given navigation item.
     * Uses replace() which safely removes any existing fragment first.
     */
    private fun showFragment(navItemId: Int) {
        currentNavItemId = navItemId

        val (fragment, title) = when (navItemId) {
            R.id.nav_dashboard -> DashboardFragment.newInstance() to "Home"
            R.id.nav_flows -> FlowsFragment.newInstance() to "Flows"
            R.id.nav_sensors -> SensorsFragment.newInstance() to "Sensors"
            R.id.nav_actions -> ActionsFragment.newInstance() to "Actions"
            R.id.nav_settings -> SettingsFragment.newInstance() to "Settings"
            else -> return
        }

        supportActionBar?.title = title

        // Use replace() - it's safe and handles any existing fragments
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }

    /**
     * Update toolbar title without switching fragments (for restoration)
     */
    private fun updateToolbarTitle(navItemId: Int) {
        val title = when (navItemId) {
            R.id.nav_dashboard -> "Home"
            R.id.nav_flows -> "Flows"
            R.id.nav_sensors -> "Sensors"
            R.id.nav_actions -> "Actions"
            R.id.nav_settings -> "Settings"
            else -> "Visual Mapper"
        }
        supportActionBar?.title = title
    }

    /**
     * Auto-reconnect to MQTT and server on app launch
     */
    private fun autoReconnect() {
        val prefs = getSharedPreferences("visual_mapper", MODE_PRIVATE)
        val autoConnect = prefs.getBoolean("auto_connect", true)

        if (!autoConnect) return

        val mqttBroker = prefs.getString("mqtt_broker", null)
        val mqttPort = prefs.getInt("mqtt_port", 1883)
        val mqttSsl = prefs.getBoolean("mqtt_ssl", false)  // Phase 1: Load SSL preference
        val serverUrl = prefs.getString("server_url", null)
        val deviceIp = prefs.getString("device_ip", null)

        CoroutineScope(Dispatchers.IO).launch {
            // Auto-connect to MQTT
            if (!mqttBroker.isNullOrBlank()) {
                android.util.Log.i("MainContainer", "Auto-connecting to MQTT: $mqttBroker:$mqttPort (SSL=$mqttSsl)")
                try {
                    app.mqttManager.connect(
                        brokerHost = mqttBroker,
                        brokerPort = mqttPort,
                        username = null,
                        password = null,
                        deviceId = app.stableDeviceId,
                        useSsl = mqttSsl  // Phase 1: Pass SSL setting
                    )
                } catch (e: Exception) {
                    android.util.Log.w("MainContainer", "Auto-connect MQTT failed: ${e.message}")
                }
            }

            // Auto-connect to server
            if (!serverUrl.isNullOrBlank()) {
                android.util.Log.i("MainContainer", "Auto-connecting to server: $serverUrl")
                try {
                    kotlinx.coroutines.delay(1000) // Wait for MQTT first
                    serverSync.connect(serverUrl, app.stableDeviceId)

                    // Auto-sync flows
                    kotlinx.coroutines.delay(500)
                    val adbDeviceId = if (!deviceIp.isNullOrBlank()) "$deviceIp:46747" else null
                    serverSync.syncFlows(app.stableDeviceId, adbDeviceId)
                } catch (e: Exception) {
                    android.util.Log.w("MainContainer", "Auto-connect server failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Navigate to settings tab (called from DashboardFragment)
     */
    fun navigateToSettings() {
        bottomNavigation.selectedItemId = R.id.nav_settings
    }

    /**
     * Navigate to flows tab
     */
    fun navigateToFlows() {
        bottomNavigation.selectedItemId = R.id.nav_flows
    }
}

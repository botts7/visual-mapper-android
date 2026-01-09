package com.visualmapper.companion.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.server.ServerSyncManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Devices Fragment
 *
 * Shows:
 * - This device info
 * - List of devices from server
 */
class DevicesFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }
    // Use shared serverSyncManager from app (singleton)
    private val serverSync get() = app.serverSyncManager

    // Views
    private lateinit var textDeviceCount: TextView
    private lateinit var thisDeviceStatusDot: View
    private lateinit var thisDeviceId: TextView
    private lateinit var thisDeviceIp: TextView
    private lateinit var thisDeviceCapabilities: TextView
    private lateinit var recyclerDevices: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var btnRefreshDevices: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_devices, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupListeners()
        loadThisDeviceInfo()
        observeState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't close serverSync - it's a shared singleton in VisualMapperApp
    }

    private fun initViews(view: View) {
        textDeviceCount = view.findViewById(R.id.textDeviceCount)
        thisDeviceStatusDot = view.findViewById(R.id.thisDeviceStatusDot)
        thisDeviceId = view.findViewById(R.id.thisDeviceId)
        thisDeviceIp = view.findViewById(R.id.thisDeviceIp)
        thisDeviceCapabilities = view.findViewById(R.id.thisDeviceCapabilities)
        recyclerDevices = view.findViewById(R.id.recyclerDevices)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        btnRefreshDevices = view.findViewById(R.id.btnRefreshDevices)
    }

    private fun setupRecyclerView() {
        recyclerDevices.layoutManager = LinearLayoutManager(requireContext())
        // TODO: Add device adapter when API provides device list
    }

    private fun setupListeners() {
        btnRefreshDevices.setOnClickListener {
            refreshDevices()
        }
    }

    private fun loadThisDeviceInfo() {
        // Show device ID
        thisDeviceId.text = "Device ID: ${app.stableDeviceId.take(16)}..."

        // Detect device IP using NetworkInterface (more reliable than deprecated WifiManager)
        val ip = getDeviceIpAddress()
        if (ip != null) {
            thisDeviceIp.text = "IP: $ip"
        } else {
            thisDeviceIp.text = "IP: Not connected to network"
        }

        // Check capabilities
        updateCapabilities()
    }

    /**
     * Get device IP address using NetworkInterface (more reliable than deprecated WifiManager)
     */
    private fun getDeviceIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                // Prefer WiFi interface
                val isWifi = networkInterface.name.startsWith("wlan")

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                        val ip = address.hostAddress
                        if (ip != null && !ip.startsWith("169.254")) {
                            if (isWifi) return ip
                        }
                    }
                }
            }

            // Fallback: any valid IPv4
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
            android.util.Log.e("DevicesFragment", "Error getting device IP: ${e.message}")
        }
        return null
    }

    private fun updateCapabilities() {
        val capabilities = mutableListOf<String>()

        // Check accessibility service
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService != null) {
            capabilities.add("accessibility")
            capabilities.add("gestures")
            capabilities.add("ui_reading")
            capabilities.add("flow_execution")
        }

        // Check MQTT connection
        if (app.mqttManager.connectionState.value == com.visualmapper.companion.mqtt.MqttManager.ConnectionState.CONNECTED) {
            capabilities.add("mqtt")
        }

        if (capabilities.isEmpty()) {
            thisDeviceCapabilities.text = "Capabilities: None (enable accessibility)"
            thisDeviceStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
        } else {
            thisDeviceCapabilities.text = "Capabilities: ${capabilities.joinToString(", ")}"
            thisDeviceStatusDot.setBackgroundResource(R.drawable.status_dot_green)
        }
    }

    private fun observeState() {
        // Update capabilities when MQTT state changes
        viewLifecycleOwner.lifecycleScope.launch {
            app.mqttManager.connectionState.collectLatest {
                updateCapabilities()
            }
        }
    }

    private fun refreshDevices() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("visual_mapper", android.content.Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", null)

            if (serverUrl.isNullOrBlank()) {
                android.widget.Toast.makeText(requireContext(), "Please configure server URL in Settings", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            android.widget.Toast.makeText(requireContext(), "Refreshing devices...", android.widget.Toast.LENGTH_SHORT).show()

            // TODO: Implement device list API call
            // For now just show empty state
            layoutEmpty.visibility = View.VISIBLE
            recyclerDevices.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance() = DevicesFragment()
    }
}

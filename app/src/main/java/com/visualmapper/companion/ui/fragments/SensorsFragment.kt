package com.visualmapper.companion.ui.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sensors Fragment
 *
 * Shows list of sensors synced from server with:
 * - Sensor name and ID
 * - Current value
 * - Last updated time
 * - Device association
 */
class SensorsFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }

    // Views
    private lateinit var recyclerSensors: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var textSensorCount: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var progressLoading: ProgressBar

    private lateinit var adapter: SensorAdapter
    private var sensors: List<SensorData> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_sensors, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        loadSensors()
    }

    override fun onResume() {
        super.onResume()
        loadSensors()
    }

    private fun initViews(view: View) {
        recyclerSensors = view.findViewById(R.id.recyclerSensors)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        textSensorCount = view.findViewById(R.id.textSensorCount)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        progressLoading = view.findViewById(R.id.progressLoading)

        btnRefresh.setOnClickListener {
            loadSensors()
        }
    }

    private fun setupRecyclerView() {
        adapter = SensorAdapter(
            onSensorClick = { sensor ->
                showSensorDetails(sensor)
            }
        )

        recyclerSensors.layoutManager = LinearLayoutManager(requireContext())
        recyclerSensors.adapter = adapter
    }

    private fun loadSensors() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (serverUrl.isNullOrBlank()) {
            showEmpty("Server not configured")
            return
        }

        progressLoading.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchSensors(serverUrl)
                }

                sensors = result
                adapter.submitList(result)
                updateUI()

            } catch (e: Exception) {
                showEmpty("Failed to load sensors: ${e.message}")
            } finally {
                progressLoading.visibility = View.GONE
            }
        }
    }

    private suspend fun fetchSensors(serverUrl: String): List<SensorData> {
        // Try multiple device IDs to find sensors
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val deviceIp = prefs.getString("device_ip", null)

        // Build list of device IDs to try
        val deviceIds = mutableListOf<String>()
        deviceIds.add(app.stableDeviceId)  // Try app's stable ID first
        if (!deviceIp.isNullOrBlank()) {
            deviceIds.add("$deviceIp:46747")  // Try ADB device ID format
        }

        // Also try to get ADB device IDs from server (they may differ from local IP)
        try {
            val adbUrl = URL("$serverUrl/api/adb/devices")
            val adbConn = adbUrl.openConnection() as HttpURLConnection
            adbConn.connectTimeout = 3000
            adbConn.readTimeout = 3000
            if (adbConn.responseCode == 200) {
                val response = adbConn.inputStream.bufferedReader().readText()
                val jsonObj = JSONObject(response)
                val devicesArray = jsonObj.optJSONArray("devices")
                if (devicesArray != null) {
                    for (i in 0 until devicesArray.length()) {
                        val device = devicesArray.getJSONObject(i)
                        val adbId = device.optString("id", "")
                        if (adbId.isNotBlank() && !deviceIds.contains(adbId)) {
                            deviceIds.add(adbId)
                        }
                    }
                }
            }
            adbConn.disconnect()
        } catch (e: Exception) {
            // Ignore - continue with existing device IDs
        }

        for (deviceId in deviceIds) {
            try {
                val url = URL("$serverUrl/api/sensors/$deviceId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                try {
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().readText()
                        val sensors = parseSensors(response)
                        if (sensors.isNotEmpty()) {
                            return sensors  // Found sensors with this device ID
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                // Try next device ID
            }
        }

        return emptyList()
    }

    private fun parseSensors(json: String): List<SensorData> {
        val sensors = mutableListOf<SensorData>()

        // Backend returns: { "sensors": [...], "count": N }
        val jsonObject = JSONObject(json)
        val jsonArray = jsonObject.optJSONArray("sensors") ?: return emptyList()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            // Get extraction method from nested extraction_rule object
            val extractionRule = obj.optJSONObject("extraction_rule")
            val method = extractionRule?.optString("method", "exact") ?: "exact"

            sensors.add(SensorData(
                id = obj.optString("sensor_id", obj.optString("id", "")),
                name = obj.optString("friendly_name", obj.optString("name", "Unknown")),
                deviceId = obj.optString("device_id", ""),
                value = obj.optString("current_value", obj.optString("value", "--")),
                unit = obj.optString("unit_of_measurement", obj.optString("unit", "")),
                lastUpdated = obj.optString("last_updated", ""),
                flowId = obj.optString("flow_id", ""),
                extractionMethod = method
            ))
        }

        return sensors
    }

    private fun updateUI() {
        progressLoading.visibility = View.GONE

        if (sensors.isEmpty()) {
            recyclerSensors.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            textSensorCount.text = "No sensors"
        } else {
            recyclerSensors.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            textSensorCount.text = "${sensors.size} sensors"
        }
    }

    private fun showEmpty(message: String) {
        progressLoading.visibility = View.GONE
        recyclerSensors.visibility = View.GONE
        layoutEmpty.visibility = View.VISIBLE
        textSensorCount.text = message
    }

    private fun showSensorDetails(sensor: SensorData) {
        // Show sensor details in a dialog
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(sensor.name)
            .setMessage("""
                ID: ${sensor.id}
                Device: ${sensor.deviceId}
                Value: ${sensor.value} ${sensor.unit}
                Flow: ${sensor.flowId}
                Extraction: ${sensor.extractionMethod}
                Last Updated: ${sensor.lastUpdated}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .create()
        dialog.show()
    }

    // ==========================================================================
    // Sensor Adapter
    // ==========================================================================

    inner class SensorAdapter(
        private val onSensorClick: (SensorData) -> Unit
    ) : RecyclerView.Adapter<SensorAdapter.SensorViewHolder>() {

        private var sensorList: List<SensorData> = emptyList()

        fun submitList(newList: List<SensorData>) {
            sensorList = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SensorViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sensor, parent, false)
            return SensorViewHolder(view)
        }

        override fun onBindViewHolder(holder: SensorViewHolder, position: Int) {
            holder.bind(sensorList[position])
        }

        override fun getItemCount(): Int = sensorList.size

        inner class SensorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textSensorName: TextView = itemView.findViewById(R.id.textSensorName)
            private val textSensorId: TextView = itemView.findViewById(R.id.textSensorId)
            private val textSensorValue: TextView = itemView.findViewById(R.id.textSensorValue)
            private val textDeviceId: TextView = itemView.findViewById(R.id.textDeviceId)
            private val chipMethod: Chip = itemView.findViewById(R.id.chipMethod)

            fun bind(sensor: SensorData) {
                textSensorName.text = sensor.name
                textSensorId.text = sensor.id
                textSensorValue.text = "${sensor.value} ${sensor.unit}"
                textDeviceId.text = sensor.deviceId
                chipMethod.text = sensor.extractionMethod

                itemView.setOnClickListener { onSensorClick(sensor) }
            }
        }
    }

    // ==========================================================================
    // Data Classes
    // ==========================================================================

    data class SensorData(
        val id: String,
        val name: String,
        val deviceId: String,
        val value: String,
        val unit: String,
        val lastUpdated: String,
        val flowId: String,
        val extractionMethod: String
    )

    companion object {
        fun newInstance() = SensorsFragment()
    }
}

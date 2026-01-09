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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
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
 * Actions Fragment
 *
 * Shows list of actions from server with:
 * - Action name and type
 * - Target device
 * - Execute button
 */
class ActionsFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }

    // Views
    private lateinit var recyclerActions: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var textActionCount: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var progressLoading: ProgressBar

    private lateinit var adapter: ActionAdapter
    private var actions: List<ActionData> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_actions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        loadActions()
    }

    override fun onResume() {
        super.onResume()
        loadActions()
    }

    private fun initViews(view: View) {
        recyclerActions = view.findViewById(R.id.recyclerActions)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        textActionCount = view.findViewById(R.id.textActionCount)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        progressLoading = view.findViewById(R.id.progressLoading)

        btnRefresh.setOnClickListener {
            loadActions()
        }
    }

    private fun setupRecyclerView() {
        adapter = ActionAdapter(
            onActionClick = { action ->
                showActionDetails(action)
            },
            onExecuteClick = { action ->
                executeAction(action)
            }
        )

        recyclerActions.layoutManager = LinearLayoutManager(requireContext())
        recyclerActions.adapter = adapter
    }

    private fun loadActions() {
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
                    fetchActions(serverUrl)
                }

                actions = result
                adapter.submitList(result)
                updateUI()

            } catch (e: Exception) {
                showEmpty("Failed to load actions: ${e.message}")
            } finally {
                progressLoading.visibility = View.GONE
            }
        }
    }

    private suspend fun fetchActions(serverUrl: String): List<ActionData> {
        // Try multiple device IDs to find actions
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
                val url = URL("$serverUrl/api/actions/$deviceId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                try {
                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().readText()
                        val actions = parseActions(response)
                        if (actions.isNotEmpty()) {
                            return actions  // Found actions with this device ID
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

    private fun parseActions(json: String): List<ActionData> {
        val actions = mutableListOf<ActionData>()

        // Backend returns: { "actions": [...], "count": N }
        val jsonObject = JSONObject(json)
        val jsonArray = jsonObject.optJSONArray("actions") ?: return emptyList()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            actions.add(ActionData(
                id = obj.optString("action_id", obj.optString("id", "")),
                name = obj.optString("name", "Unknown"),
                deviceId = obj.optString("device_id", ""),
                actionType = obj.optString("action_type", "tap"),
                description = obj.optString("description", ""),
                x = obj.optInt("x", 0),
                y = obj.optInt("y", 0),
                enabled = obj.optBoolean("enabled", true)
            ))
        }

        return actions
    }

    private fun updateUI() {
        progressLoading.visibility = View.GONE

        if (actions.isEmpty()) {
            recyclerActions.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            textActionCount.text = "No actions"
        } else {
            recyclerActions.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            textActionCount.text = "${actions.size} actions"
        }
    }

    private fun showEmpty(message: String) {
        progressLoading.visibility = View.GONE
        recyclerActions.visibility = View.GONE
        layoutEmpty.visibility = View.VISIBLE
        textActionCount.text = message
    }

    private fun showActionDetails(action: ActionData) {
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(action.name)
            .setMessage("""
                ID: ${action.id}
                Device: ${action.deviceId}
                Type: ${action.actionType}
                Position: (${action.x}, ${action.y})
                Description: ${action.description}
            """.trimIndent())
            .setPositiveButton("Execute") { _, _ ->
                executeAction(action)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun executeAction(action: ActionData) {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (serverUrl.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Server not configured", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Executing ${action.name}...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    executeActionApi(serverUrl, action.id, action.deviceId)
                }

                if (success) {
                    Toast.makeText(requireContext(), "Action executed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Action failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun executeActionApi(serverUrl: String, actionId: String, deviceId: String): Boolean {
        val url = URL("$serverUrl/api/actions/$actionId/execute?device_id=$deviceId")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        return try {
            connection.outputStream.write("{}".toByteArray())
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }

    // ==========================================================================
    // Action Adapter
    // ==========================================================================

    inner class ActionAdapter(
        private val onActionClick: (ActionData) -> Unit,
        private val onExecuteClick: (ActionData) -> Unit
    ) : RecyclerView.Adapter<ActionAdapter.ActionViewHolder>() {

        private var actionList: List<ActionData> = emptyList()

        fun submitList(newList: List<ActionData>) {
            actionList = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ActionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_action, parent, false)
            return ActionViewHolder(view)
        }

        override fun onBindViewHolder(holder: ActionViewHolder, position: Int) {
            holder.bind(actionList[position])
        }

        override fun getItemCount(): Int = actionList.size

        inner class ActionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textActionName: TextView = itemView.findViewById(R.id.textActionName)
            private val textActionId: TextView = itemView.findViewById(R.id.textActionId)
            private val textDeviceId: TextView = itemView.findViewById(R.id.textDeviceId)
            private val chipActionType: Chip = itemView.findViewById(R.id.chipActionType)
            private val btnExecute: Button = itemView.findViewById(R.id.btnExecute)

            fun bind(action: ActionData) {
                textActionName.text = action.name
                textActionId.text = action.id
                textDeviceId.text = action.deviceId
                chipActionType.text = action.actionType

                itemView.setOnClickListener { onActionClick(action) }
                btnExecute.setOnClickListener { onExecuteClick(action) }
            }
        }
    }

    // ==========================================================================
    // Data Classes
    // ==========================================================================

    data class ActionData(
        val id: String,
        val name: String,
        val deviceId: String,
        val actionType: String,
        val description: String,
        val x: Int,
        val y: Int,
        val enabled: Boolean
    )

    companion object {
        fun newInstance() = ActionsFragment()
    }
}

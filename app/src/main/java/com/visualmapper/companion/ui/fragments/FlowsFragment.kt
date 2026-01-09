package com.visualmapper.companion.ui.fragments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.service.FlowExecutorService
import com.visualmapper.companion.ui.FlowDetailActivity
import com.visualmapper.companion.ui.FlowWizardActivity
import kotlinx.coroutines.launch

/**
 * Flows Fragment
 *
 * Shows:
 * - List of synced flows
 * - Filter by enabled/execution method
 * - Execute flows
 * - Navigate to flow details
 */
class FlowsFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }
    // Use shared serverSyncManager from app (singleton)
    private val serverSync get() = app.serverSyncManager

    // Views
    private lateinit var recyclerFlows: RecyclerView
    private lateinit var layoutEmpty: LinearLayout
    private lateinit var textFlowCount: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnSyncFlows: Button
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipEnabled: Chip
    private lateinit var chipAndroid: Chip
    private lateinit var fabCreateFlow: FloatingActionButton

    private lateinit var adapter: FlowAdapter
    private var allFlows: List<FlowExecutorService.FlowData> = emptyList()
    private var filteredFlows: List<FlowExecutorService.FlowData> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_flows, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupRecyclerView()
        setupFilters()
        loadFlows()
    }

    override fun onResume() {
        super.onResume()
        loadFlows()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't close serverSync - it's a shared singleton in VisualMapperApp
    }

    private fun initViews(view: View) {
        recyclerFlows = view.findViewById(R.id.recyclerFlows)
        layoutEmpty = view.findViewById(R.id.layoutEmpty)
        textFlowCount = view.findViewById(R.id.textFlowCount)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnSyncFlows = view.findViewById(R.id.btnSyncFlows)
        chipGroupFilter = view.findViewById(R.id.chipGroupFilter)
        chipAll = view.findViewById(R.id.chipAll)
        chipEnabled = view.findViewById(R.id.chipEnabled)
        chipAndroid = view.findViewById(R.id.chipAndroid)

        btnRefresh.setOnClickListener {
            syncFlowsFromServer()
        }

        btnSyncFlows.setOnClickListener {
            syncFlowsFromServer()
        }

        // FAB to create new flow
        fabCreateFlow = view.findViewById(R.id.fabCreateFlow)
        fabCreateFlow.setOnClickListener {
            val intent = Intent(requireContext(), FlowWizardActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupRecyclerView() {
        adapter = FlowAdapter(
            onFlowClick = { flow ->
                // Open flow details
                val intent = Intent(requireContext(), FlowDetailActivity::class.java)
                intent.putExtra("flow_id", flow.id)
                intent.putExtra("device_id", flow.deviceId)
                startActivity(intent)
            },
            onExecuteClick = { flow ->
                executeFlow(flow)
            }
        )

        recyclerFlows.layoutManager = LinearLayoutManager(requireContext())
        recyclerFlows.adapter = adapter
    }

    private fun setupFilters() {
        chipGroupFilter.setOnCheckedStateChangeListener { _, _ ->
            applyFilter()
        }
    }

    private fun loadFlows() {
        // Get flows from FlowExecutorService (companion object)
        allFlows = FlowExecutorService.getAllFlows()

        applyFilter()
    }

    private fun applyFilter() {
        filteredFlows = when {
            chipEnabled.isChecked -> allFlows.filter { it.enabled }
            chipAndroid.isChecked -> allFlows.filter { it.executionMethod == "android" || it.executionMethod == "auto" }
            else -> allFlows // All
        }

        adapter.submitList(filteredFlows)
        updateUI()
    }

    private fun updateUI() {
        val count = filteredFlows.size
        val total = allFlows.size

        if (total == 0) {
            recyclerFlows.visibility = View.GONE
            layoutEmpty.visibility = View.VISIBLE
            textFlowCount.text = "No flows synced"
        } else {
            recyclerFlows.visibility = View.VISIBLE
            layoutEmpty.visibility = View.GONE
            textFlowCount.text = if (count == total) {
                "$total flows synced from server"
            } else {
                "Showing $count of $total flows"
            }
        }
    }

    private fun syncFlowsFromServer() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", null)
            val deviceIp = prefs.getString("device_ip", null)

            if (serverUrl.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Please configure server URL in Settings", Toast.LENGTH_SHORT).show()
                return@launch
            }

            Toast.makeText(requireContext(), "Syncing flows...", Toast.LENGTH_SHORT).show()

            try {
                // Connect to server if not connected
                if (serverSync.connectionState.value != ServerSyncManager.ConnectionState.CONNECTED) {
                    serverSync.connect(serverUrl, app.stableDeviceId)
                }

                val adbDeviceId = if (!deviceIp.isNullOrBlank()) "$deviceIp:46747" else null
                val flows = serverSync.syncFlows(app.stableDeviceId, adbDeviceId)

                Toast.makeText(requireContext(), "Synced ${flows?.size ?: 0} flows", Toast.LENGTH_SHORT).show()
                loadFlows()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Sync failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeFlow(flow: FlowExecutorService.FlowData) {
        // Get full flow object from cache
        val fullFlow = FlowExecutorService.getFlow(flow.id)
        if (fullFlow == null) {
            Toast.makeText(requireContext(), "Flow not found in cache", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Executing ${flow.name}...", Toast.LENGTH_SHORT).show()

        // Start FlowExecutorService via Intent (works even if service not running)
        val flowJson = kotlinx.serialization.json.Json.encodeToString(
            com.visualmapper.companion.service.Flow.serializer(),
            fullFlow
        )
        val intent = android.content.Intent(requireContext(), FlowExecutorService::class.java).apply {
            action = FlowExecutorService.ACTION_EXECUTE_FLOW
            putExtra(FlowExecutorService.EXTRA_FLOW_JSON, flowJson)
            putExtra(FlowExecutorService.EXTRA_EXECUTION_ID, "${flow.id}_${System.currentTimeMillis()}")
        }

        // Use startForegroundService for Android O+ compatibility
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    // ==========================================================================
    // Flow Adapter
    // ==========================================================================

    inner class FlowAdapter(
        private val onFlowClick: (FlowExecutorService.FlowData) -> Unit,
        private val onExecuteClick: (FlowExecutorService.FlowData) -> Unit
    ) : RecyclerView.Adapter<FlowAdapter.FlowViewHolder>() {

        private var flows: List<FlowExecutorService.FlowData> = emptyList()

        fun submitList(newFlows: List<FlowExecutorService.FlowData>) {
            flows = newFlows
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FlowViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_flow, parent, false)
            return FlowViewHolder(view)
        }

        override fun onBindViewHolder(holder: FlowViewHolder, position: Int) {
            holder.bind(flows[position])
        }

        override fun getItemCount(): Int = flows.size

        inner class FlowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val viewStatus: View = itemView.findViewById(R.id.viewStatus)
            private val textFlowName: TextView = itemView.findViewById(R.id.textFlowName)
            private val textFlowId: TextView = itemView.findViewById(R.id.textFlowId)
            private val textSensorCount: TextView = itemView.findViewById(R.id.textSensorCount)
            private val textInterval: TextView = itemView.findViewById(R.id.textInterval)
            private val chipExecutionMethod: Chip = itemView.findViewById(R.id.chipExecutionMethod)
            private val btnExecute: Button = itemView.findViewById(R.id.btnExecute)

            fun bind(flow: FlowExecutorService.FlowData) {
                textFlowName.text = flow.name
                textFlowId.text = "${flow.id} • ${flow.deviceId}"
                textSensorCount.text = "${flow.sensorCount} sensors"
                textInterval.text = "Every ${flow.updateIntervalSeconds}s"

                // Status indicator
                viewStatus.setBackgroundResource(
                    if (flow.enabled) R.drawable.circle_green else R.drawable.circle_gray
                )

                // Execution method badge
                chipExecutionMethod.text = when (flow.executionMethod) {
                    "android" -> "Android"
                    "auto" -> "Auto"
                    else -> "Server"
                }

                // Click handlers
                itemView.setOnClickListener { onFlowClick(flow) }
                btnExecute.setOnClickListener { onExecuteClick(flow) }
            }
        }
    }

    companion object {
        fun newInstance() = FlowsFragment()
    }
}

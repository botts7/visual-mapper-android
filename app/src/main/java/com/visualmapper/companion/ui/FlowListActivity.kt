package com.visualmapper.companion.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.visualmapper.companion.R
import com.visualmapper.companion.service.FlowExecutorService

/**
 * Activity for browsing and managing synced flows
 *
 * Features:
 * - Display list of flows synced from server
 * - Filter by enabled/disabled, execution method
 * - Quick execute button for each flow
 * - Navigate to flow details
 */
class FlowListActivity : AppCompatActivity() {

    private lateinit var recyclerFlows: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var textFlowCount: TextView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnSyncFlows: Button
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var chipAll: Chip
    private lateinit var chipEnabled: Chip
    private lateinit var chipAndroid: Chip

    private lateinit var adapter: FlowAdapter
    private var allFlows: List<FlowExecutorService.FlowData> = emptyList()
    private var filteredFlows: List<FlowExecutorService.FlowData> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_flow_list)

        supportActionBar?.title = "Flows"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initViews()
        setupRecyclerView()
        setupFilters()
        loadFlows()
    }

    private fun initViews() {
        recyclerFlows = findViewById(R.id.recyclerFlows)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        textFlowCount = findViewById(R.id.textFlowCount)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSyncFlows = findViewById(R.id.btnSyncFlows)
        chipGroupFilter = findViewById(R.id.chipGroupFilter)
        chipAll = findViewById(R.id.chipAll)
        chipEnabled = findViewById(R.id.chipEnabled)
        chipAndroid = findViewById(R.id.chipAndroid)

        btnRefresh.setOnClickListener {
            loadFlows()
            Toast.makeText(this, "Refreshing flows...", Toast.LENGTH_SHORT).show()
        }

        btnSyncFlows.setOnClickListener {
            // Trigger flow sync from server
            Toast.makeText(this, "Syncing flows from server...", Toast.LENGTH_SHORT).show()
            // TODO: Call server sync API
        }
    }

    private fun setupRecyclerView() {
        adapter = FlowAdapter(
            onFlowClick = { flow ->
                // Open flow details
                val intent = Intent(this, FlowDetailActivity::class.java)
                intent.putExtra("flow_id", flow.id)
                intent.putExtra("device_id", flow.deviceId)
                startActivity(intent)
            },
            onExecuteClick = { flow ->
                executeFlow(flow)
            }
        )

        recyclerFlows.layoutManager = LinearLayoutManager(this)
        recyclerFlows.adapter = adapter
    }

    private fun setupFilters() {
        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
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

    private fun executeFlow(flow: FlowExecutorService.FlowData) {
        Toast.makeText(this, "Executing ${flow.name}...", Toast.LENGTH_SHORT).show()

        // Execute via FlowExecutorService
        val flowExecutor = FlowExecutorService.getInstance()
        flowExecutor?.executeFlow(flow.id) { success, message ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "Flow executed successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Execution failed: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onResume() {
        super.onResume()
        loadFlows() // Refresh when returning to this activity
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
}

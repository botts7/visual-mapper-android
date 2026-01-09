package com.visualmapper.companion.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.visualmapper.companion.R
import com.visualmapper.companion.security.AuditLogger
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audit Log Activity
 *
 * Shows transparent log of all data access:
 * - What apps were monitored
 * - What data was captured
 * - What was blocked
 * - When consent was changed
 */
class AuditLogActivity : AppCompatActivity() {

    private lateinit var auditLogger: AuditLogger
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AuditLogAdapter
    private lateinit var spinnerFilter: Spinner
    private lateinit var txtStats: TextView
    private lateinit var btnExport: ImageButton
    private lateinit var btnClear: ImageButton

    private var currentFilter: String = "All"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audit_log)

        auditLogger = AuditLogger(this)

        setupUI()
        observeEntries()
    }

    private fun setupUI() {
        recyclerView = findViewById(R.id.recyclerAuditLog)
        spinnerFilter = findViewById(R.id.spinnerFilter)
        txtStats = findViewById(R.id.txtStats)
        btnExport = findViewById(R.id.btnExport)
        btnClear = findViewById(R.id.btnClear)

        // Setup RecyclerView
        adapter = AuditLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Setup filter spinner
        val filterOptions = arrayOf(
            "All",
            "UI Reads",
            "Sensitive Blocked",
            "Consent Changes",
            "Data Transmit",
            "Gestures",
            "Access Denied"
        )
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, filterOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFilter.adapter = spinnerAdapter

        spinnerFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentFilter = filterOptions[position]
                applyFilter()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnExport.setOnClickListener {
            exportLog()
        }

        btnClear.setOnClickListener {
            showClearConfirmation()
        }
    }

    private fun observeEntries() {
        lifecycleScope.launch {
            auditLogger.entries.collectLatest { entries ->
                updateStats(entries)
                applyFilter()
            }
        }
    }

    private fun updateStats(entries: List<AuditLogger.AuditEntry>) {
        val total = entries.size
        val uiReads = entries.count { it.eventType == "UI_READ" }
        val blocked = entries.count { it.eventType == "SENSITIVE_BLOCKED" }
        val denied = entries.count { it.eventType == "ACCESS_DENIED" }

        txtStats.text = "Total: $total | Reads: $uiReads | Blocked: $blocked | Denied: $denied"
    }

    private fun applyFilter() {
        val entries = auditLogger.entries.value
        val filtered = when (currentFilter) {
            "UI Reads" -> entries.filter { it.eventType == "UI_READ" }
            "Sensitive Blocked" -> entries.filter { it.eventType == "SENSITIVE_BLOCKED" }
            "Consent Changes" -> entries.filter { it.eventType == "CONSENT_CHANGE" }
            "Data Transmit" -> entries.filter { it.eventType == "DATA_TRANSMIT" }
            "Gestures" -> entries.filter { it.eventType == "GESTURE" }
            "Access Denied" -> entries.filter { it.eventType == "ACCESS_DENIED" }
            else -> entries
        }
        adapter.submitList(filtered.reversed()) // Show newest first
    }

    private fun exportLog() {
        val report = auditLogger.exportReport()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Visual Mapper Audit Log")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(intent, "Export Audit Log"))
    }

    private fun showClearConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Audit Log")
            .setMessage("Delete all audit log entries? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                auditLogger.clearLogs()
                Toast.makeText(this, "Audit log cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * Adapter for audit log entries
 */
class AuditLogAdapter : RecyclerView.Adapter<AuditLogAdapter.ViewHolder>() {

    private var items: List<AuditLogger.AuditEntry> = emptyList()
    private val dateFormat = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

    fun submitList(list: List<AuditLogger.AuditEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audit_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtTime: TextView = itemView.findViewById(R.id.txtTime)
        private val txtType: TextView = itemView.findViewById(R.id.txtEventType)
        private val txtApp: TextView = itemView.findViewById(R.id.txtAppName)
        private val txtDetails: TextView = itemView.findViewById(R.id.txtDetails)
        private val iconType: ImageView = itemView.findViewById(R.id.iconEventType)

        fun bind(entry: AuditLogger.AuditEntry) {
            txtTime.text = dateFormat.format(Date(entry.timestamp))
            txtType.text = entry.eventType.replace("_", " ")
            txtApp.text = entry.packageName?.substringAfterLast('.') ?: "system"
            txtDetails.text = entry.details

            // Set icon and color by type
            val (iconRes, color) = when (entry.eventType) {
                "UI_READ" -> Pair(android.R.drawable.ic_menu_view, 0xFF3B82F6.toInt())
                "SENSITIVE_BLOCKED" -> Pair(android.R.drawable.ic_lock_lock, 0xFFEF4444.toInt())
                "CONSENT_CHANGE" -> Pair(android.R.drawable.ic_menu_edit, 0xFFF59E0B.toInt())
                "DATA_TRANSMIT" -> Pair(android.R.drawable.ic_menu_send, 0xFF22C55E.toInt())
                "GESTURE" -> Pair(android.R.drawable.ic_menu_compass, 0xFF8B5CF6.toInt())
                "ACCESS_DENIED" -> Pair(android.R.drawable.ic_delete, 0xFFEF4444.toInt())
                else -> Pair(android.R.drawable.ic_menu_info_details, 0xFF64748B.toInt())
            }

            iconType.setImageResource(iconRes)
            iconType.setColorFilter(color)
            txtType.setTextColor(color)
        }
    }
}

package com.visualmapper.companion.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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
import com.google.android.material.card.MaterialCardView
import com.visualmapper.companion.R
import com.visualmapper.companion.navigation.NavigationLearningMode
import com.visualmapper.companion.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Privacy Settings Activity
 *
 * Allows users to:
 * - View and manage per-app consent
 * - Configure app whitelist
 * - View audit logs
 * - Export privacy report
 * - Revoke all consents
 */
class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var securePrefs: SecurePreferences
    private lateinit var consentManager: ConsentManager
    private lateinit var auditLogger: AuditLogger

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppConsentAdapter

    private lateinit var switchSensitiveDetection: Switch
    private lateinit var switchAuditLogging: Switch
    private lateinit var spinnerRetention: Spinner
    private lateinit var btnAddApp: Button
    private lateinit var btnViewAuditLog: Button
    private lateinit var btnExportReport: Button
    private lateinit var btnRevokeAll: Button

    // Navigation learning views
    private lateinit var switchNavigationLearning: Switch
    private lateinit var layoutLearningMode: View
    private lateinit var radioGroupLearningMode: RadioGroup
    private lateinit var radioExecutionOnly: RadioButton
    private lateinit var radioPassive: RadioButton

    // Installed apps for picker
    private var installedApps: List<AppInfo> = emptyList()
    private var filteredApps: List<AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy_settings)

        // Initialize security components
        securePrefs = SecurePreferences(this)
        auditLogger = AuditLogger(this)
        consentManager = ConsentManager(this, securePrefs, auditLogger)

        setupUI()
        loadSettings()
        observeConsents()
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)

                packages
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
        }
    }

    private fun hasLaunchIntent(pm: PackageManager, packageName: String): Boolean {
        return pm.getLaunchIntentForPackage(packageName) != null
    }

    private fun setupUI() {
        // Find views
        recyclerView = findViewById(R.id.recyclerAppConsents)
        switchSensitiveDetection = findViewById(R.id.switchSensitiveDetection)
        switchAuditLogging = findViewById(R.id.switchAuditLogging)
        spinnerRetention = findViewById(R.id.spinnerRetention)
        btnAddApp = findViewById(R.id.btnAddApp)
        btnViewAuditLog = findViewById(R.id.btnViewAuditLog)
        btnExportReport = findViewById(R.id.btnExportReport)
        btnRevokeAll = findViewById(R.id.btnRevokeAll)

        // Setup RecyclerView
        adapter = AppConsentAdapter(
            onConsentClick = { consent -> showEditConsentDialog(consent) },
            onRevokeClick = { consent -> revokeConsent(consent) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Setup retention spinner
        val retentionOptions = arrayOf("1 day", "7 days", "30 days", "90 days", "Never delete")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, retentionOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRetention.adapter = spinnerAdapter

        // Switch listeners
        switchSensitiveDetection.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.sensitiveDetectionEnabled = isChecked
        }

        switchAuditLogging.setOnCheckedChangeListener { _, isChecked ->
            auditLogger.setEnabled(isChecked)
            securePrefs.auditLoggingEnabled = isChecked
        }

        spinnerRetention.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val days = when (position) {
                    0 -> 1
                    1 -> 7
                    2 -> 30
                    3 -> 90
                    else -> 365 * 10 // "Never" = 10 years
                }
                securePrefs.dataRetentionDays = days
                auditLogger.applyRetention(days)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Button listeners
        btnAddApp.setOnClickListener {
            showAddAppDialog()
        }

        btnViewAuditLog.setOnClickListener {
            startActivity(Intent(this, AuditLogActivity::class.java))
        }

        btnExportReport.setOnClickListener {
            exportPrivacyReport()
        }

        btnRevokeAll.setOnClickListener {
            showRevokeAllDialog()
        }

        // Navigation learning views
        switchNavigationLearning = findViewById(R.id.switchNavigationLearning)
        layoutLearningMode = findViewById(R.id.layoutLearningMode)
        radioGroupLearningMode = findViewById(R.id.radioGroupLearningMode)
        radioExecutionOnly = findViewById(R.id.radioExecutionOnly)
        radioPassive = findViewById(R.id.radioPassive)

        // Navigation learning toggle
        switchNavigationLearning.setOnCheckedChangeListener { _, isChecked ->
            securePrefs.navigationLearningEnabled = isChecked
            layoutLearningMode.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Learning mode radio group
        radioGroupLearningMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioPassive -> NavigationLearningMode.PASSIVE
                else -> NavigationLearningMode.EXECUTION_ONLY
            }
            securePrefs.navigationLearningMode = mode
        }
    }

    private fun loadSettings() {
        switchSensitiveDetection.isChecked = securePrefs.sensitiveDetectionEnabled
        switchAuditLogging.isChecked = securePrefs.auditLoggingEnabled

        // Set retention spinner position
        val days = securePrefs.dataRetentionDays
        val position = when {
            days <= 1 -> 0
            days <= 7 -> 1
            days <= 30 -> 2
            days <= 90 -> 3
            else -> 4
        }
        spinnerRetention.setSelection(position)

        // Navigation learning settings
        switchNavigationLearning.isChecked = securePrefs.navigationLearningEnabled
        layoutLearningMode.visibility = if (securePrefs.navigationLearningEnabled) View.VISIBLE else View.GONE

        // Set radio button based on saved mode
        when (securePrefs.navigationLearningMode) {
            NavigationLearningMode.PASSIVE -> radioPassive.isChecked = true
            NavigationLearningMode.EXECUTION_ONLY -> radioExecutionOnly.isChecked = true
        }
    }

    private fun observeConsents() {
        lifecycleScope.launch {
            consentManager.consents.collectLatest { consents ->
                adapter.submitList(consents.values.toList())
            }
        }
    }

    private fun showEditConsentDialog(consent: ConsentManager.AppConsent) {
        val levels = arrayOf("None (Block)", "Basic (Structure only)", "Full (With text)", "Sensitive (Include hints)")
        val currentLevel = consent.level.ordinal

        AlertDialog.Builder(this)
            .setTitle("Consent for ${consent.packageName}")
            .setSingleChoiceItems(levels, currentLevel) { dialog, which ->
                val newLevel = ConsentManager.ConsentLevel.entries[which]
                consentManager.grantConsent(
                    packageName = consent.packageName,
                    level = newLevel,
                    dataCategories = consent.dataCategories,
                    purpose = consent.purpose
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun revokeConsent(consent: ConsentManager.AppConsent) {
        AlertDialog.Builder(this)
            .setTitle("Revoke Consent")
            .setMessage("Revoke consent for ${consent.packageName}? The app will no longer be monitored.")
            .setPositiveButton("Revoke") { _, _ ->
                consentManager.revokeConsent(consent.packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddAppDialog() {
        // Create dialog with app picker
        val dialogView = layoutInflater.inflate(R.layout.dialog_app_picker, null)
        val recyclerApps = dialogView.findViewById<RecyclerView>(R.id.recyclerApps)
        val editSearch = dialogView.findViewById<EditText>(R.id.editSearchApps)
        val progressLoading = dialogView.findViewById<ProgressBar>(R.id.progressLoading)

        recyclerApps.layoutManager = LinearLayoutManager(this)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create()

        // Show loading if apps not yet loaded
        if (installedApps.isEmpty()) {
            progressLoading.visibility = View.VISIBLE
            // Reload apps
            lifecycleScope.launch {
                loadInstalledApps()
                progressLoading.visibility = View.GONE
                updateAppPickerList(recyclerApps, dialog)
            }
        } else {
            updateAppPickerList(recyclerApps, dialog)
        }

        // Search filter
        editSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString() ?: ""
                filteredApps = if (query.isBlank()) {
                    installedApps
                } else {
                    installedApps.filter {
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                    }
                }
                updateAppPickerList(recyclerApps, dialog)
            }
        })

        dialog.show()
    }

    private fun updateAppPickerList(recyclerView: RecyclerView, dialog: AlertDialog) {
        recyclerView.adapter = AppPickerAdapter(filteredApps) { app ->
            dialog.dismiss()
            showConsentLevelDialog(app.packageName, app.label)
        }
    }

    private fun showConsentLevelDialog(packageName: String, appLabel: String? = null) {
        val displayName = appLabel ?: packageName.substringAfterLast('.')

        val levels = arrayOf(
            "Basic (Structure only)",
            "Full (With text)",
            "Sensitive (Include hints)"
        )

        AlertDialog.Builder(this)
            .setTitle("Consent for $displayName")
            .setItems(levels) { _, which ->
                val level = when (which) {
                    0 -> ConsentManager.ConsentLevel.BASIC
                    1 -> ConsentManager.ConsentLevel.FULL
                    else -> ConsentManager.ConsentLevel.SENSITIVE
                }

                consentManager.grantConsent(
                    packageName = packageName,
                    level = level,
                    dataCategories = listOf("ui_structure", "text_content"),
                    purpose = "Testing and development"
                )

                Toast.makeText(this, "Consent granted to $displayName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRevokeAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Revoke All Consents")
            .setMessage("This will revoke consent for ALL apps. You will need to re-approve each app. Continue?")
            .setPositiveButton("Revoke All") { _, _ ->
                consentManager.revokeAllConsents()
                Toast.makeText(this, "All consents revoked", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportPrivacyReport() {
        val consentReport = consentManager.exportConsentReport()
        val auditReport = auditLogger.exportReport()

        val fullReport = """
$consentReport

$auditReport
        """.trimIndent()

        // Share the report
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Visual Mapper Privacy Report")
            putExtra(Intent.EXTRA_TEXT, fullReport)
        }
        startActivity(Intent.createChooser(intent, "Export Privacy Report"))
    }
}

/**
 * Adapter for displaying app consents
 */
class AppConsentAdapter(
    private val onConsentClick: (ConsentManager.AppConsent) -> Unit,
    private val onRevokeClick: (ConsentManager.AppConsent) -> Unit
) : RecyclerView.Adapter<AppConsentAdapter.ViewHolder>() {

    private var items: List<ConsentManager.AppConsent> = emptyList()
    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    fun submitList(list: List<ConsentManager.AppConsent>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_consent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val txtPackage: TextView = itemView.findViewById(R.id.txtPackageName)
        private val txtLevel: TextView = itemView.findViewById(R.id.txtConsentLevel)
        private val txtDate: TextView = itemView.findViewById(R.id.txtConsentDate)
        private val btnRevoke: ImageButton = itemView.findViewById(R.id.btnRevoke)

        fun bind(consent: ConsentManager.AppConsent) {
            txtPackage.text = consent.packageName.substringAfterLast('.')
            txtLevel.text = consent.level.name

            val dateStr = dateFormat.format(Date(consent.grantedAt))
            txtDate.text = "Since $dateStr"

            // Color code by level
            val color = when (consent.level) {
                ConsentManager.ConsentLevel.NONE -> 0xFFEF4444.toInt() // Red
                ConsentManager.ConsentLevel.BASIC -> 0xFFF59E0B.toInt() // Yellow
                ConsentManager.ConsentLevel.FULL -> 0xFF22C55E.toInt() // Green
                ConsentManager.ConsentLevel.SENSITIVE -> 0xFF3B82F6.toInt() // Blue
            }
            txtLevel.setTextColor(color)

            itemView.setOnClickListener { onConsentClick(consent) }
            btnRevoke.setOnClickListener { onRevokeClick(consent) }
        }
    }
}

/**
 * Data class for installed app info
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

/**
 * Adapter for app picker dialog
 */
class AppPickerAdapter(
    private val apps: List<AppInfo>,
    private val onAppSelected: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppPickerAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_select, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(apps[position])
    }

    override fun getItemCount() = apps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.imageAppIcon)
        private val label: TextView = itemView.findViewById(R.id.textAppLabel)
        private val packageName: TextView = itemView.findViewById(R.id.textAppPackage)
        private val card: MaterialCardView = itemView.findViewById(R.id.cardApp)

        fun bind(app: AppInfo) {
            label.text = app.label
            packageName.text = app.packageName
            app.icon?.let { icon.setImageDrawable(it) }

            card.setOnClickListener {
                onAppSelected(app)
            }
        }
    }
}

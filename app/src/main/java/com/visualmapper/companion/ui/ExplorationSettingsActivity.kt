package com.visualmapper.companion.ui

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.visualmapper.companion.R
import com.visualmapper.companion.explorer.*

/**
 * Settings activity for configuring exploration access levels and Go Mode.
 */
class ExplorationSettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ExplorationSettings"
    }

    private lateinit var accessManager: AccessLevelManager
    private lateinit var goModeManager: GoModeManager
    private lateinit var auditLog: ExplorationAuditLog

    // Views
    private lateinit var accessLevelSlider: SeekBar
    private lateinit var accessLevelDescription: TextView
    private lateinit var goModeStatus: TextView
    private lateinit var goModeTimer: TextView
    private lateinit var goModeDurationGroup: RadioGroup
    private lateinit var goModeButton: Button
    private lateinit var autoDeactivateSwitch: SwitchCompat
    private lateinit var confirmElevatedSwitch: SwitchCompat
    private lateinit var confirmFullSwitch: SwitchCompat
    private lateinit var auditLogCount: TextView
    private lateinit var viewAuditLogButton: Button
    private lateinit var exportAuditLogButton: Button
    private lateinit var resetButton: Button

    private var timerUpdater: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_exploration_settings)

        // Setup action bar
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Access Control"
        }

        // Initialize managers
        accessManager = AccessLevelManager(this)
        goModeManager = GoModeManager(this)
        accessManager.goModeManager = goModeManager
        auditLog = ExplorationAuditLog(this)

        // Find views
        findViews()

        // Setup listeners
        setupAccessLevelSlider()
        setupGoModeSection()
        setupSafetyOptions()
        setupAuditLogSection()
        setupResetButton()

        // Load current values
        loadCurrentSettings()
    }

    private fun findViews() {
        accessLevelSlider = findViewById(R.id.accessLevelSlider)
        accessLevelDescription = findViewById(R.id.accessLevelDescription)
        goModeStatus = findViewById(R.id.goModeStatus)
        goModeTimer = findViewById(R.id.goModeTimer)
        goModeDurationGroup = findViewById(R.id.goModeDurationGroup)
        goModeButton = findViewById(R.id.goModeButton)
        autoDeactivateSwitch = findViewById(R.id.autoDeactivateSwitch)
        confirmElevatedSwitch = findViewById(R.id.confirmElevatedSwitch)
        confirmFullSwitch = findViewById(R.id.confirmFullSwitch)
        auditLogCount = findViewById(R.id.auditLogCount)
        viewAuditLogButton = findViewById(R.id.viewAuditLogButton)
        exportAuditLogButton = findViewById(R.id.exportAuditLogButton)
        resetButton = findViewById(R.id.resetButton)
    }

    private fun setupAccessLevelSlider() {
        accessLevelSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = ExplorationAccessLevel.fromLevel(progress)
                updateAccessLevelDescription(level)

                if (fromUser) {
                    accessManager.globalAccessLevel = level
                    Log.i(TAG, "Access level changed to: ${level.displayName}")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateAccessLevelDescription(level: ExplorationAccessLevel) {
        accessLevelDescription.text = "${level.displayName} - ${level.description}"

        // Update color based on level
        val colorRes = when (level) {
            ExplorationAccessLevel.OBSERVE, ExplorationAccessLevel.SAFE -> R.color.primary
            ExplorationAccessLevel.STANDARD -> R.color.secondary
            ExplorationAccessLevel.ELEVATED -> R.color.warning_yellow
            ExplorationAccessLevel.FULL -> R.color.warning_orange
            ExplorationAccessLevel.SENSITIVE -> R.color.error_red
        }
        accessLevelDescription.setTextColor(getColor(colorRes))
    }

    private fun setupGoModeSection() {
        goModeButton.setOnClickListener {
            if (goModeManager.isActive()) {
                // Deactivate
                goModeManager.deactivate(GoModeManager.DeactivationReason.USER_CANCELLED)
                updateGoModeUI()
            } else {
                // Get selected duration
                val duration = when (goModeDurationGroup.checkedRadioButtonId) {
                    R.id.duration30s -> GoModeManager.DURATION_30_SECONDS
                    R.id.duration1m -> GoModeManager.DURATION_1_MINUTE
                    R.id.duration2m -> GoModeManager.DURATION_2_MINUTES
                    R.id.duration5m -> GoModeManager.DURATION_5_MINUTES
                    else -> GoModeManager.DURATION_30_SECONDS
                }

                // Show confirmation dialog
                showGoModeConfirmation(duration)
            }
        }

        // Add Go Mode listener
        goModeManager.addListener(object : GoModeManager.GoModeListener {
            override fun onGoModeActivated(durationMs: Long) {
                runOnUiThread { updateGoModeUI() }
            }

            override fun onGoModeDeactivated(reason: GoModeManager.DeactivationReason) {
                runOnUiThread { updateGoModeUI() }
            }

            override fun onGoModeTick(remainingMs: Long) {
                runOnUiThread {
                    goModeTimer.text = goModeManager.getRemainingTimeFormatted()
                }
            }
        })
    }

    private fun showGoModeConfirmation(durationMs: Long) {
        val durationText = when (durationMs) {
            GoModeManager.DURATION_30_SECONDS -> "30 seconds"
            GoModeManager.DURATION_1_MINUTE -> "1 minute"
            GoModeManager.DURATION_2_MINUTES -> "2 minutes"
            GoModeManager.DURATION_5_MINUTES -> "5 minutes"
            else -> "${durationMs / 1000} seconds"
        }

        AlertDialog.Builder(this)
            .setTitle("Activate Go Mode?")
            .setMessage(
                "Go Mode enables sensitive actions (password entry, PIN, OTP) for $durationText.\n\n" +
                "Only activate if you need the explorer to interact with login screens.\n\n" +
                "All actions will be logged."
            )
            .setPositiveButton("Activate") { _, _ ->
                goModeManager.activate(durationMs)
                updateGoModeUI()
                Toast.makeText(this, "Go Mode activated for $durationText", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateGoModeUI() {
        if (goModeManager.isActive()) {
            goModeStatus.text = "Active"
            goModeStatus.setTextColor(getColor(R.color.secondary))
            goModeTimer.visibility = android.view.View.VISIBLE
            goModeTimer.text = goModeManager.getRemainingTimeFormatted()
            goModeButton.text = "Deactivate Go Mode"
            goModeButton.setBackgroundColor(getColor(R.color.error_red))
        } else {
            goModeStatus.text = "Inactive"
            goModeStatus.setTextColor(getColor(R.color.text_secondary))
            goModeTimer.visibility = android.view.View.GONE
            goModeButton.text = "Activate Go Mode"
            goModeButton.setBackgroundColor(getColor(R.color.warning_yellow))
        }
    }

    private fun setupSafetyOptions() {
        autoDeactivateSwitch.setOnCheckedChangeListener { _, isChecked ->
            accessManager.autoDeactivateGoModeOnAppSwitch = isChecked
            Log.i(TAG, "Auto-deactivate on app switch: $isChecked")
        }

        confirmElevatedSwitch.setOnCheckedChangeListener { _, isChecked ->
            accessManager.requireConfirmationForElevated = isChecked
            Log.i(TAG, "Confirm elevated actions: $isChecked")
        }

        confirmFullSwitch.setOnCheckedChangeListener { _, isChecked ->
            accessManager.requireConfirmationForFull = isChecked
            Log.i(TAG, "Confirm full access actions: $isChecked")
        }
    }

    private fun setupAuditLogSection() {
        viewAuditLogButton.setOnClickListener {
            showAuditLogDialog()
        }

        exportAuditLogButton.setOnClickListener {
            exportAuditLog()
        }
    }

    private fun showAuditLogDialog() {
        val entries = auditLog.getRecentEntries(50)

        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Audit Log")
                .setMessage("No entries yet. Actions will be logged during exploration.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val logText = buildString {
            entries.reversed().forEach { entry ->
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(entry.timestamp))
                val status = when {
                    entry.wasBlocked -> "[BLOCKED]"
                    entry.success -> "[OK]"
                    else -> "[FAILED]"
                }
                val goMode = if (entry.goModeActive) " [GO]" else ""

                appendLine("$time | ${entry.action} $status$goMode")
                appendLine("  App: ${entry.targetApp}")
                if (entry.blockReason != null) {
                    appendLine("  Reason: ${entry.blockReason}")
                }
                appendLine()
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Audit Log (${entries.size} entries)")
            .setMessage(logText)
            .setPositiveButton("OK", null)
            .setNeutralButton("Clear") { _, _ ->
                auditLog.clearAll()
                updateAuditLogCount()
                Toast.makeText(this, "Audit log cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun exportAuditLog() {
        val json = auditLog.exportToJson()

        // Copy to clipboard
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        val clip = android.content.ClipData.newPlainText("Audit Log", json)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, "Audit log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun updateAuditLogCount() {
        auditLogCount.text = "${auditLog.getEntryCount()} entries"
    }

    private fun setupResetButton() {
        resetButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to Defaults?")
                .setMessage("This will reset all access control settings to their default values.")
                .setPositiveButton("Reset") { _, _ ->
                    accessManager.resetToDefaults()
                    loadCurrentSettings()
                    Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun loadCurrentSettings() {
        // Access level
        val level = accessManager.globalAccessLevel
        accessLevelSlider.progress = level.level
        updateAccessLevelDescription(level)

        // Safety options
        autoDeactivateSwitch.isChecked = accessManager.autoDeactivateGoModeOnAppSwitch
        confirmElevatedSwitch.isChecked = accessManager.requireConfirmationForElevated
        confirmFullSwitch.isChecked = accessManager.requireConfirmationForFull

        // Go Mode
        updateGoModeUI()

        // Audit log
        updateAuditLogCount()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerUpdater?.cancel()
    }
}

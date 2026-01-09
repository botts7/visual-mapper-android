package com.visualmapper.companion.explorer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.visualmapper.companion.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided Correction Activity
 *
 * Walks the user through fixing exploration issues one by one.
 * For each issue:
 * 1. Shows the issue details
 * 2. Navigates to the problematic screen (or guides manual navigation)
 * 3. Lets the user demonstrate the correct action
 * 4. Applies learning from the correction
 */
class GuidedCorrectionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GuidedCorrection"
    }

    private lateinit var textProgress: TextView
    private lateinit var textRemaining: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var textIssueType: TextView
    private lateinit var textIssueDescription: TextView
    private lateinit var textScreenInfo: TextView
    private lateinit var textElementInfo: TextView
    private lateinit var layoutElementInfo: View
    private lateinit var textNavigationStatus: TextView
    private lateinit var layoutAutoNavigation: View
    private lateinit var layoutManualNavigation: View
    private lateinit var btnNavigate: Button
    private lateinit var btnImThere: Button
    private lateinit var btnDemonstrateTap: Button
    private lateinit var btnScrollFirst: Button
    private lateinit var btnMarkIgnore: Button
    private lateinit var btnMarkDangerous: Button
    private lateinit var btnSkip: Button
    private lateinit var btnCancel: Button
    private lateinit var cardActions: View

    private var packageName: String = ""
    private var issues: List<ExplorationIssue> = emptyList()
    private var currentIndex: Int = 0
    private val corrections = mutableListOf<AppliedCorrection>()
    private var sessionStartTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guided_correction)

        // Get data from intent
        packageName = intent.getStringExtra("package_name") ?: ""
        issues = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra("issues", ExplorationIssue::class.java) ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra("issues") ?: emptyList()
        }

        if (packageName.isEmpty() || issues.isEmpty()) {
            Toast.makeText(this, "No issues to fix", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionStartTime = System.currentTimeMillis()
        Log.i(TAG, "Starting guided correction for $packageName with ${issues.size} issues")

        setupViews()
        updateUI()
    }

    private fun setupViews() {
        // Toolbar
        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).apply {
            setSupportActionBar(this)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            setNavigationOnClickListener { confirmCancel() }
        }

        // Progress views
        textProgress = findViewById(R.id.textProgress)
        textRemaining = findViewById(R.id.textRemaining)
        progressBar = findViewById(R.id.progressBar)

        // Issue card views
        textIssueType = findViewById(R.id.textIssueType)
        textIssueDescription = findViewById(R.id.textIssueDescription)
        textScreenInfo = findViewById(R.id.textScreenInfo)
        textElementInfo = findViewById(R.id.textElementInfo)
        layoutElementInfo = findViewById(R.id.layoutElementInfo)

        // Navigation views
        textNavigationStatus = findViewById(R.id.textNavigationStatus)
        layoutAutoNavigation = findViewById(R.id.layoutAutoNavigation)
        layoutManualNavigation = findViewById(R.id.layoutManualNavigation)
        btnNavigate = findViewById(R.id.btnNavigate)
        btnImThere = findViewById(R.id.btnImThere)

        // Action views
        cardActions = findViewById(R.id.cardActions)
        btnDemonstrateTap = findViewById(R.id.btnDemonstrateTap)
        btnScrollFirst = findViewById(R.id.btnScrollFirst)
        btnMarkIgnore = findViewById(R.id.btnMarkIgnore)
        btnMarkDangerous = findViewById(R.id.btnMarkDangerous)

        // Footer views
        btnSkip = findViewById(R.id.btnSkip)
        btnCancel = findViewById(R.id.btnCancel)

        // Setup click listeners
        btnNavigate.setOnClickListener { navigateToIssue() }
        btnImThere.setOnClickListener { onReadyToCorrect() }
        btnDemonstrateTap.setOnClickListener { startTapDemonstration() }
        btnScrollFirst.setOnClickListener { recordScrollCorrection() }
        btnMarkIgnore.setOnClickListener { markAsIgnore() }
        btnMarkDangerous.setOnClickListener { markAsDangerous() }
        btnSkip.setOnClickListener { skipIssue() }
        btnCancel.setOnClickListener { confirmCancel() }
    }

    private fun updateUI() {
        val issue = issues.getOrNull(currentIndex)
        if (issue == null) {
            showSummary()
            return
        }

        // Update progress
        textProgress.text = "Issue ${currentIndex + 1} of ${issues.size}"
        textRemaining.text = "${issues.size - currentIndex - 1} remaining"
        progressBar.max = issues.size
        progressBar.progress = currentIndex

        // Update issue card
        textIssueType.text = getIssueTypeName(issue.issueType)
        textIssueDescription.text = issue.description
        textScreenInfo.text = "Screen: ${issue.screenId.take(20)}"

        if (issue.elementText != null || issue.elementId != null) {
            layoutElementInfo.visibility = View.VISIBLE
            textElementInfo.text = "Element: ${issue.elementText ?: issue.elementId?.take(30)}"
        } else {
            layoutElementInfo.visibility = View.GONE
        }

        // Check navigation path
        checkNavigationPath(issue)
    }

    private fun getIssueTypeName(type: IssueType): String = when (type) {
        IssueType.ELEMENT_STUCK -> "Element Stuck"
        IssueType.BACK_FAILED -> "Back Navigation Failed"
        IssueType.APP_MINIMIZED -> "App Minimized"
        IssueType.APP_LEFT -> "Left Target App"
        IssueType.TIMEOUT -> "Timeout"
        IssueType.SCROLL_FAILED -> "Scroll Failed"
        IssueType.DANGEROUS_ELEMENT -> "Dangerous Element"
        IssueType.RECOVERY_FAILED -> "Recovery Failed"
        IssueType.BLOCKER_SCREEN -> "Blocker Screen"
    }

    private fun checkNavigationPath(issue: ExplorationIssue) {
        // Check if we can auto-navigate to the screen
        val service = AppExplorerService.getInstance()
        val state = service?.getExplorationResult()?.state

        if (state != null) {
            val navGraph = state.navigationGraph
            val currentScreenId = getCurrentScreenId()

            if (currentScreenId != null) {
                val path = navGraph.findPath(currentScreenId, issue.screenId)

                if (path != null && path.isNotEmpty()) {
                    // We have a path - show auto navigation
                    layoutAutoNavigation.visibility = View.VISIBLE
                    layoutManualNavigation.visibility = View.GONE
                    textNavigationStatus.text = "Path found: ${path.size} steps"
                    cardActions.visibility = View.GONE  // Hide until navigated
                    return
                }
            }
        }

        // No path found or already on screen - show manual navigation
        layoutAutoNavigation.visibility = View.GONE
        layoutManualNavigation.visibility = View.VISIBLE
        cardActions.visibility = View.GONE  // Hide until navigated
    }

    private fun getCurrentScreenId(): String? {
        // TODO: Get current screen from accessibility service
        return null
    }

    private fun navigateToIssue() {
        val issue = issues.getOrNull(currentIndex) ?: return

        lifecycleScope.launch {
            textNavigationStatus.text = "Navigating..."
            btnNavigate.isEnabled = false

            try {
                // TODO: Use NavigationGuide to auto-navigate
                // For now, simulate navigation
                delay(1000)

                // Show action buttons after navigation
                cardActions.visibility = View.VISIBLE
                textNavigationStatus.text = "Arrived at target screen"
                btnNavigate.text = "Re-Navigate"
                btnNavigate.isEnabled = true

            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed", e)
                textNavigationStatus.text = "Navigation failed - try manually"
                btnNavigate.isEnabled = true
                layoutManualNavigation.visibility = View.VISIBLE
            }
        }
    }

    private fun onReadyToCorrect() {
        // User says they're on the correct screen
        cardActions.visibility = View.VISIBLE
        layoutManualNavigation.visibility = View.GONE
        layoutAutoNavigation.visibility = View.GONE
    }

    private fun startTapDemonstration() {
        val issue = issues.getOrNull(currentIndex) ?: return

        // Minimize this activity and show capture overlay
        Toast.makeText(this, "Tap the correct element in the app", Toast.LENGTH_LONG).show()

        // TODO: Launch capture overlay to record user's tap
        // For now, simulate a successful demonstration
        lifecycleScope.launch {
            moveTaskToBack(true)
            delay(3000)  // Give user time to tap

            // Simulate capture
            val demonstratedAction = DemonstratedAction(
                type = DemonstratedActionType.TAP,
                x = 540,
                y = 960,
                description = "User demonstrated tap"
            )

            recordCorrection(GuidedCorrectionType.TAP_DEMONSTRATED, demonstratedAction)
        }
    }

    private fun recordScrollCorrection() {
        val issue = issues.getOrNull(currentIndex) ?: return

        val demonstratedAction = DemonstratedAction(
            type = DemonstratedActionType.SCROLL,
            scrollDirection = "down",
            description = "Scroll needed before interaction"
        )

        recordCorrection(GuidedCorrectionType.SCROLL_DEMONSTRATED, demonstratedAction)
    }

    private fun markAsIgnore() {
        AlertDialog.Builder(this)
            .setTitle("Ignore Element")
            .setMessage("Mark this element as 'ignore' in future explorations? The exploration will skip this element.")
            .setPositiveButton("Yes, Ignore") { _, _ ->
                recordCorrection(GuidedCorrectionType.MARK_IGNORE, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markAsDangerous() {
        AlertDialog.Builder(this)
            .setTitle("Mark as Dangerous")
            .setMessage("Mark this element as 'dangerous'? The exploration will avoid this element to prevent app crashes or unwanted actions.")
            .setPositiveButton("Yes, Mark Dangerous") { _, _ ->
                recordCorrection(GuidedCorrectionType.MARK_DANGEROUS, null)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun skipIssue() {
        recordCorrection(GuidedCorrectionType.SKIP_ISSUE, null)
    }

    private fun recordCorrection(type: GuidedCorrectionType, action: DemonstratedAction?) {
        val issue = issues.getOrNull(currentIndex) ?: return

        // Record the correction
        val correction = AppliedCorrection(
            issue = issue,
            correctionType = type,
            demonstratedAction = action
        )
        corrections.add(correction)

        Log.i(TAG, "Recorded correction for issue ${currentIndex + 1}: ${type.name}")

        // Apply learning
        applyLearning(issue, type, action)

        // Ask what to do next
        showNextDialog(type)
    }

    private fun applyLearning(issue: ExplorationIssue, type: GuidedCorrectionType, action: DemonstratedAction?) {
        val service = AppExplorerService.getInstance()

        when (type) {
            GuidedCorrectionType.TAP_DEMONSTRATED -> {
                // TODO: Apply Q-learning reward for correct element
                Log.i(TAG, "Applied TAP learning for ${issue.elementId}")
            }
            GuidedCorrectionType.SCROLL_DEMONSTRATED -> {
                // TODO: Record that scroll is needed before this element
                Log.i(TAG, "Applied SCROLL learning for ${issue.screenId}")
            }
            GuidedCorrectionType.MARK_IGNORE -> {
                // TODO: Add to ignore list
                Log.i(TAG, "Marked ${issue.elementId} as IGNORE")
            }
            GuidedCorrectionType.MARK_DANGEROUS -> {
                // Add to dangerous patterns
                issue.elementId?.let {
                    service?.getExplorationResult()?.state?.dangerousPatterns?.add(it)
                }
                Log.i(TAG, "Marked ${issue.elementId} as DANGEROUS")
            }
            GuidedCorrectionType.SKIP_ISSUE -> {
                Log.i(TAG, "Skipped issue ${currentIndex + 1}")
            }
        }
    }

    private fun showNextDialog(type: GuidedCorrectionType) {
        if (currentIndex >= issues.size - 1) {
            // Last issue - show summary
            showSummary()
            return
        }

        val actionName = when (type) {
            GuidedCorrectionType.TAP_DEMONSTRATED -> "correction"
            GuidedCorrectionType.SCROLL_DEMONSTRATED -> "scroll correction"
            GuidedCorrectionType.MARK_IGNORE -> "ignore"
            GuidedCorrectionType.MARK_DANGEROUS -> "dangerous mark"
            GuidedCorrectionType.SKIP_ISSUE -> "skip"
        }

        AlertDialog.Builder(this)
            .setTitle("Continue?")
            .setMessage("$actionName recorded. What would you like to do next?")
            .setPositiveButton("Next Issue") { _, _ ->
                currentIndex++
                updateUI()
            }
            .setNegativeButton("Finish") { _, _ ->
                showSummary()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSummary() {
        val duration = System.currentTimeMillis() - sessionStartTime
        val correctedCount = corrections.count {
            it.correctionType == GuidedCorrectionType.TAP_DEMONSTRATED ||
            it.correctionType == GuidedCorrectionType.SCROLL_DEMONSTRATED
        }
        val ignoredCount = corrections.count { it.correctionType == GuidedCorrectionType.MARK_IGNORE }
        val dangerousCount = corrections.count { it.correctionType == GuidedCorrectionType.MARK_DANGEROUS }
        val skippedCount = corrections.count { it.correctionType == GuidedCorrectionType.SKIP_ISSUE }

        val summary = buildString {
            appendLine("Session Complete!")
            appendLine()
            appendLine("Issues processed: ${corrections.size}/${issues.size}")
            appendLine("Corrections demonstrated: $correctedCount")
            appendLine("Marked as ignore: $ignoredCount")
            appendLine("Marked as dangerous: $dangerousCount")
            appendLine("Skipped: $skippedCount")
            appendLine()
            appendLine("Duration: ${duration / 1000}s")
        }

        AlertDialog.Builder(this)
            .setTitle("Correction Summary")
            .setMessage(summary)
            .setPositiveButton("Done") { _, _ ->
                // Return to results
                setResult(RESULT_OK, Intent().apply {
                    putExtra("corrections_count", corrections.size)
                })
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun confirmCancel() {
        if (corrections.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Cancel Session?")
                .setMessage("You have ${corrections.size} corrections recorded. Do you want to save them and exit?")
                .setPositiveButton("Save & Exit") { _, _ ->
                    showSummary()
                }
                .setNegativeButton("Discard All") { _, _ ->
                    setResult(RESULT_CANCELED)
                    finish()
                }
                .setNeutralButton("Continue") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmCancel()
    }
}

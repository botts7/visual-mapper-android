package com.visualmapper.companion.ui.overlay

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Action Menu Activity
 *
 * Shows when user taps an element in recording mode.
 * Presents options: Tap, Type Text, Create Sensor, Create Action, Wait
 *
 * Styled as a bottom sheet dialog for quick interaction.
 */
class ActionMenuActivity : AppCompatActivity() {

    // Element info from intent
    private var elementText: String? = null
    private var elementClass: String? = null
    private var elementResourceId: String? = null
    private var elementContentDesc: String? = null
    private var elementX: Int = 0
    private var elementY: Int = 0
    private var elementWidth: Int = 0
    private var elementHeight: Int = 0
    private var elementCenterX: Int = 0
    private var elementCenterY: Int = 0
    private var targetPackage: String? = null
    private var isRawCoordinates: Boolean = false
    private var tapX: Int = 0
    private var tapY: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_action_menu)

        // Make activity look like a dialog
        window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        window?.setGravity(android.view.Gravity.BOTTOM)

        // Extract intent data
        extractIntentData()

        // Setup UI
        setupElementInfo()
        setupActionButtons()
    }

    private fun extractIntentData() {
        intent?.let {
            isRawCoordinates = it.getBooleanExtra("is_raw_coordinates", false)

            if (isRawCoordinates) {
                tapX = it.getIntExtra("tap_x", 0)
                tapY = it.getIntExtra("tap_y", 0)
            } else {
                elementText = it.getStringExtra("element_text")
                elementClass = it.getStringExtra("element_class")
                elementResourceId = it.getStringExtra("element_resource_id")
                elementContentDesc = it.getStringExtra("element_content_desc")
                elementX = it.getIntExtra("element_x", 0)
                elementY = it.getIntExtra("element_y", 0)
                elementWidth = it.getIntExtra("element_width", 0)
                elementHeight = it.getIntExtra("element_height", 0)
                elementCenterX = it.getIntExtra("element_center_x", 0)
                elementCenterY = it.getIntExtra("element_center_y", 0)
            }

            targetPackage = it.getStringExtra("target_package")
        }
    }

    private fun setupElementInfo() {
        val textElementInfo = findViewById<TextView>(R.id.textElementInfo)
        val textElementDetails = findViewById<TextView>(R.id.textElementDetails)

        if (isRawCoordinates) {
            textElementInfo.text = "Tap at coordinates"
            textElementDetails.text = "Position: ($tapX, $tapY)"
        } else {
            // Show element text or class name
            val displayText = when {
                !elementText.isNullOrEmpty() -> "\"$elementText\""
                !elementContentDesc.isNullOrEmpty() -> elementContentDesc
                !elementResourceId.isNullOrEmpty() -> elementResourceId?.substringAfterLast('/')
                else -> elementClass?.substringAfterLast('.') ?: "Unknown"
            }
            textElementInfo.text = displayText

            // Build details string
            val details = buildString {
                append("Class: ${elementClass?.substringAfterLast('.') ?: "Unknown"}")
                if (!elementResourceId.isNullOrEmpty()) {
                    append("\nID: ${elementResourceId?.substringAfterLast('/')}")
                }
                append("\nPosition: ($elementCenterX, $elementCenterY)")
                append("\nSize: ${elementWidth}x${elementHeight}")
            }
            textElementDetails.text = details
        }
    }

    private fun setupActionButtons() {
        // Tap Element
        findViewById<Button>(R.id.btnTapElement).setOnClickListener {
            performTap()
        }

        // Type Text (show input field)
        val btnTypeText = findViewById<Button>(R.id.btnTypeText)
        val layoutTypeText = findViewById<LinearLayout>(R.id.layoutTypeText)
        val editTextInput = findViewById<EditText>(R.id.editTextInput)
        val btnSendText = findViewById<Button>(R.id.btnSendText)

        btnTypeText.setOnClickListener {
            layoutTypeText.visibility = View.VISIBLE
            btnTypeText.visibility = View.GONE
        }

        btnSendText.setOnClickListener {
            val text = editTextInput.text.toString()
            if (text.isNotEmpty()) {
                performTypeText(text)
            } else {
                Toast.makeText(this, "Enter text to type", Toast.LENGTH_SHORT).show()
            }
        }

        // Create Sensor
        findViewById<Button>(R.id.btnCreateSensor).setOnClickListener {
            createSensor()
        }

        // Create Action
        findViewById<Button>(R.id.btnCreateAction).setOnClickListener {
            createAction()
        }

        // Add Wait
        findViewById<Button>(R.id.btnAddWait).setOnClickListener {
            addWait()
        }

        // Cancel
        findViewById<Button>(R.id.btnCancel).setOnClickListener {
            finish()
        }
    }

    // =========================================================================
    // Actions
    // =========================================================================

    private fun performTap() {
        val x = if (isRawCoordinates) tapX else elementCenterX
        val y = if (isRawCoordinates) tapY else elementCenterY

        CoroutineScope(Dispatchers.Main).launch {
            val service = VisualMapperAccessibilityService.getInstance()
            if (service != null) {
                val success = service.gestureDispatcher.tap(x.toFloat(), y.toFloat())
                if (success) {
                    Toast.makeText(this@ActionMenuActivity, "Tapped at ($x, $y)", Toast.LENGTH_SHORT).show()

                    // Add to recorded steps
                    addRecordedStep(RecordedStep(
                        type = StepType.TAP,
                        description = elementText ?: "Tap at ($x, $y)",
                        x = x,
                        y = y,
                        elementResourceId = elementResourceId,
                        elementText = elementText,
                        elementClass = elementClass
                    ))
                } else {
                    Toast.makeText(this@ActionMenuActivity, "Tap failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@ActionMenuActivity, "Accessibility service not running", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun performTypeText(text: String) {
        CoroutineScope(Dispatchers.Main).launch {
            val service = VisualMapperAccessibilityService.getInstance()
            if (service != null) {
                // First tap to focus the element
                val x = if (isRawCoordinates) tapX else elementCenterX
                val y = if (isRawCoordinates) tapY else elementCenterY

                service.gestureDispatcher.tap(x.toFloat(), y.toFloat())

                // Wait briefly for focus
                kotlinx.coroutines.delay(300)

                // Type text using input injection
                val success = service.gestureDispatcher.typeText(text)
                if (success) {
                    Toast.makeText(this@ActionMenuActivity, "Typed: $text", Toast.LENGTH_SHORT).show()

                    addRecordedStep(RecordedStep(
                        type = StepType.TYPE_TEXT,
                        description = "Type: $text",
                        x = x,
                        y = y,
                        elementResourceId = elementResourceId,
                        elementText = text
                    ))
                } else {
                    Toast.makeText(this@ActionMenuActivity, "Type failed", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this@ActionMenuActivity, "Accessibility service not running", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun createSensor() {
        // Check if we have text to extract
        if (elementText.isNullOrEmpty() && elementContentDesc.isNullOrEmpty()) {
            Toast.makeText(this, "No text found in this element", Toast.LENGTH_LONG).show()
            return
        }

        // For now, create a simple sensor definition
        // In full implementation, this would open a dialog for configuration
        val sensorName = when {
            !elementResourceId.isNullOrEmpty() -> elementResourceId!!.substringAfterLast('/')
            !elementText.isNullOrEmpty() -> elementText!!.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")
            else -> "sensor_${System.currentTimeMillis()}"
        }

        Toast.makeText(this, "Sensor created: $sensorName\nValue: ${elementText ?: elementContentDesc}", Toast.LENGTH_LONG).show()

        addRecordedStep(RecordedStep(
            type = StepType.CAPTURE_SENSOR,
            description = "Capture: $sensorName",
            x = elementCenterX,
            y = elementCenterY,
            elementResourceId = elementResourceId,
            elementText = elementText,
            elementClass = elementClass,
            sensorConfig = SensorConfig(
                name = sensorName,
                sensorType = "sensor",
                extractionMethod = "exact"
            )
        ))

        finish()
    }

    private fun createAction() {
        val actionName = when {
            !elementResourceId.isNullOrEmpty() -> "action_${elementResourceId!!.substringAfterLast('/')}"
            !elementText.isNullOrEmpty() -> "action_${elementText!!.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}"
            else -> "action_${System.currentTimeMillis()}"
        }

        Toast.makeText(this, "Action created: $actionName", Toast.LENGTH_LONG).show()

        addRecordedStep(RecordedStep(
            type = StepType.CREATE_ACTION,
            description = "Action: $actionName",
            x = elementCenterX,
            y = elementCenterY,
            elementResourceId = elementResourceId,
            elementText = elementText,
            elementClass = elementClass,
            actionConfig = ActionConfig(
                name = actionName,
                actionType = "tap"
            )
        ))

        finish()
    }

    private fun addWait() {
        Toast.makeText(this, "Wait step added (2 seconds)", Toast.LENGTH_SHORT).show()

        addRecordedStep(RecordedStep(
            type = StepType.WAIT,
            description = "Wait 2s"
        ))

        finish()
    }

    private fun addRecordedStep(step: RecordedStep) {
        // Get the overlay service and add step
        // For now, just log it - in full implementation this would communicate with the service
        android.util.Log.i("ActionMenu", "Recorded step: ${step.type} - ${step.description}")

        // TODO: Communicate with RecordingOverlayService to add step
    }
}

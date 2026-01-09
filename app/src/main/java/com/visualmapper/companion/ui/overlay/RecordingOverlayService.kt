package com.visualmapper.companion.ui.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.visualmapper.companion.R
import com.visualmapper.companion.accessibility.UIElement
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Recording Overlay Service
 *
 * Provides a floating overlay for recording flows directly on the device.
 *
 * Features:
 * - Floating action button to toggle recording mode
 * - Mode toggle: Navigate vs Record
 * - Element detection and highlighting
 * - Action menu for creating sensors/actions
 */
class RecordingOverlayService : Service() {

    companion object {
        private const val TAG = "RecordingOverlay"

        // Intent actions
        const val ACTION_SHOW = "com.visualmapper.companion.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.visualmapper.companion.HIDE_OVERLAY"
        const val ACTION_TOGGLE = "com.visualmapper.companion.TOGGLE_OVERLAY"

        // Check if overlay permission is granted
        fun canDrawOverlay(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        // Request overlay permission
        fun requestOverlayPermission(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private var floatingButton: View? = null
    private var controlPanel: View? = null
    private var elementHighlight: View? = null

    private var isRecordingMode = false
    private var isPanelExpanded = false

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Recorded steps for current session
    private val recordedSteps = mutableListOf<RecordedStep>()

    // Current app being recorded
    private var targetPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "RecordingOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
            ACTION_TOGGLE -> toggleOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        hideOverlay()
        Log.i(TAG, "RecordingOverlayService destroyed")
    }

    // =========================================================================
    // Overlay Management
    // =========================================================================

    private fun showOverlay() {
        if (!canDrawOverlay(this)) {
            Log.w(TAG, "Overlay permission not granted")
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
            requestOverlayPermission(this)
            return
        }

        if (floatingButton != null) {
            Log.d(TAG, "Overlay already shown")
            return
        }

        createFloatingButton()
        Log.i(TAG, "Overlay shown")
    }

    private fun hideOverlay() {
        floatingButton?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing floating button", e)
            }
            floatingButton = null
        }

        controlPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing control panel", e)
            }
            controlPanel = null
        }

        hideActionMenuOverlay()
        hideElementHighlight()
        hideTouchInterceptor()
        Log.i(TAG, "Overlay hidden")
    }

    private fun toggleOverlay() {
        if (floatingButton != null) {
            hideOverlay()
        } else {
            showOverlay()
        }
    }

    // =========================================================================
    // Floating Button
    // =========================================================================

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun createFloatingButton() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingButton = inflater.inflate(R.layout.overlay_floating_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 100  // Lower to avoid status bar cutoff
        }

        // Setup button click (now a TextView, not ImageButton)
        val btnRecord = floatingButton?.findViewById<TextView>(R.id.btnFloatingRecord)
        val modeIndicator = floatingButton?.findViewById<TextView>(R.id.textModeIndicator)

        btnRecord?.setOnClickListener {
            if (isPanelExpanded) {
                hideControlPanel()
            } else {
                showControlPanel()
            }
        }

        // Make button draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingButton?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingButton, params)
                    true
                }
                else -> false
            }
        }

        updateModeIndicator(modeIndicator)
        windowManager.addView(floatingButton, params)
    }

    private fun updateModeIndicator(textView: TextView?) {
        textView?.text = if (isRecordingMode) "REC" else "NAV"
        textView?.setBackgroundColor(
            if (isRecordingMode)
                resources.getColor(R.color.error, null)
            else
                resources.getColor(R.color.success, null)
        )
        // Only show indicator when panel is expanded
        textView?.visibility = if (isPanelExpanded) View.VISIBLE else View.GONE
    }

    // =========================================================================
    // Control Panel
    // =========================================================================

    @SuppressLint("InflateParams")
    private fun showControlPanel() {
        if (controlPanel != null) return

        // IMPORTANT: Hide touch interceptor while control panel is visible
        // so users can interact with the panel buttons
        hideTouchInterceptor()

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        controlPanel = inflater.inflate(R.layout.overlay_control_panel, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 160  // Position below floating button (100 + button height + gap)
        }

        // Setup controls
        setupControlPanel()

        windowManager.addView(controlPanel, params)
        isPanelExpanded = true

        // Show mode indicator on floating button
        val modeIndicator = floatingButton?.findViewById<TextView>(R.id.textModeIndicator)
        updateModeIndicator(modeIndicator)
    }

    private fun setupControlPanel() {
        val panel = controlPanel ?: return

        // Mode toggle
        val btnNavigate = panel.findViewById<View>(R.id.btnModeNavigate)
        val btnRecord = panel.findViewById<View>(R.id.btnModeRecord)
        val textStepCount = panel.findViewById<TextView>(R.id.textStepCount)
        val textCurrentApp = panel.findViewById<TextView>(R.id.textCurrentApp)

        // Update current app display
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        val currentPkg = accessibilityService?.currentPackage?.value
        textCurrentApp?.text = currentPkg?.substringAfterLast('.') ?: "No app"
        targetPackage = currentPkg

        // Update step count
        textStepCount?.text = "Steps: ${recordedSteps.size}"

        // Mode buttons
        btnNavigate?.setOnClickListener {
            setRecordingMode(false)
            updateModeButtons(btnNavigate, btnRecord)
        }

        btnRecord?.setOnClickListener {
            setRecordingMode(true)
            updateModeButtons(btnNavigate, btnRecord)
        }

        updateModeButtons(btnNavigate, btnRecord)

        // Undo button
        panel.findViewById<View>(R.id.btnUndo)?.setOnClickListener {
            if (recordedSteps.isNotEmpty()) {
                recordedSteps.removeLast()
                textStepCount?.text = "Steps: ${recordedSteps.size}"
                Toast.makeText(this, "Step removed", Toast.LENGTH_SHORT).show()
            }
        }

        // Save button
        panel.findViewById<View>(R.id.btnSave)?.setOnClickListener {
            saveRecordedFlow()
        }

        // Close button
        panel.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            hideControlPanel()
        }
    }

    private fun updateModeButtons(btnNavigate: View?, btnRecord: View?) {
        btnNavigate?.alpha = if (isRecordingMode) 0.5f else 1.0f
        btnRecord?.alpha = if (isRecordingMode) 1.0f else 0.5f

        // Update floating button indicator
        val modeIndicator = floatingButton?.findViewById<TextView>(R.id.textModeIndicator)
        updateModeIndicator(modeIndicator)
    }

    private fun hideControlPanel() {
        controlPanel?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing control panel", e)
            }
            controlPanel = null
        }
        isPanelExpanded = false

        // Hide mode indicator on floating button
        val modeIndicator = floatingButton?.findViewById<TextView>(R.id.textModeIndicator)
        modeIndicator?.visibility = View.GONE

        // IMPORTANT: Restore touch interceptor if in recording mode
        if (isRecordingMode) {
            showTouchInterceptor()
        }
    }

    // =========================================================================
    // Recording Mode
    // =========================================================================

    private fun setRecordingMode(enabled: Boolean) {
        isRecordingMode = enabled
        Log.i(TAG, "Recording mode: $enabled")

        if (enabled) {
            // Auto-whitelist current app for element detection
            autoWhitelistCurrentApp()
            // Show touch interceptor
            showTouchInterceptor()
        } else {
            // Hide touch interceptor
            hideTouchInterceptor()
        }
    }

    private fun autoWhitelistCurrentApp() {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return
        val currentPackage = accessibilityService.currentPackage?.value ?: return

        // Skip our own app
        if (currentPackage == packageName) return

        try {
            val securePrefs = com.visualmapper.companion.security.SecurePreferences(this)
            if (!securePrefs.isAppWhitelisted(currentPackage)) {
                securePrefs.addWhitelistedApp(currentPackage)

                // Also grant consent
                val auditLogger = com.visualmapper.companion.security.AuditLogger(this)
                val consentManager = com.visualmapper.companion.security.ConsentManager(this, securePrefs, auditLogger)
                consentManager.grantConsent(
                    packageName = currentPackage,
                    level = com.visualmapper.companion.security.ConsentManager.ConsentLevel.FULL,
                    purpose = "Recording flow"
                )

                Log.i(TAG, "Auto-whitelisted app for recording: $currentPackage")
                Toast.makeText(this, "Enabled recording for: ${getAppLabel(currentPackage)}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to whitelist app", e)
        }
    }

    private fun getAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }
    }

    private var touchInterceptor: View? = null

    // Swipe gesture detection
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isSwipeGesture = false
    private val SWIPE_THRESHOLD = 100 // Minimum distance for a swipe

    @SuppressLint("ClickableViewAccessibility")
    private fun showTouchInterceptor() {
        if (touchInterceptor != null) return

        touchInterceptor = View(this).apply {
            setBackgroundColor(0x01000000) // Nearly transparent
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        touchInterceptor?.setOnTouchListener { _, event ->
            if (!isRecordingMode) return@setOnTouchListener false

            // IMPORTANT: Check if touch is on control panel or floating button
            // If so, we need to NOT consume the event AND hide ourselves temporarily
            if (isTouchOnOverlayControls(event.rawX.toInt(), event.rawY.toInt())) {
                // Don't process touches on our own controls
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeStartY = event.rawY
                    isSwipeGesture = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - swipeStartX
                    val deltaY = event.rawY - swipeStartY
                    val distance = kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY)
                    if (distance > SWIPE_THRESHOLD) {
                        isSwipeGesture = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isSwipeGesture) {
                        // Record as swipe gesture
                        handleRecordingSwipe(
                            swipeStartX.toInt(), swipeStartY.toInt(),
                            event.rawX.toInt(), event.rawY.toInt()
                        )
                    } else {
                        // Record as tap
                        handleRecordingTouch(swipeStartX.toInt(), swipeStartY.toInt())
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(touchInterceptor, params)
    }

    private fun hideTouchInterceptor() {
        touchInterceptor?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing touch interceptor", e)
            }
            touchInterceptor = null
        }
    }

    /**
     * Check if touch is on our overlay controls (floating button or control panel)
     * to avoid capturing our own button presses
     */
    private fun isTouchOnOverlayControls(x: Int, y: Int): Boolean {
        // Check floating button
        floatingButton?.let { btn ->
            val loc = IntArray(2)
            btn.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + btn.width, loc[1] + btn.height)
            if (rect.contains(x, y)) return@isTouchOnOverlayControls true
        }

        // Check control panel
        controlPanel?.let { panel ->
            val loc = IntArray(2)
            panel.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + panel.width, loc[1] + panel.height)
            if (rect.contains(x, y)) return@isTouchOnOverlayControls true
        }

        // Check action menu
        actionMenuOverlay?.let { menu ->
            val loc = IntArray(2)
            menu.getLocationOnScreen(loc)
            val rect = android.graphics.Rect(loc[0], loc[1], loc[0] + menu.width, loc[1] + menu.height)
            if (rect.contains(x, y)) return@isTouchOnOverlayControls true
        }

        return false
    }

    // =========================================================================
    // Element Detection
    // =========================================================================

    private fun handleRecordingTouch(x: Int, y: Int) {
        Log.d(TAG, "Recording touch at ($x, $y)")

        // Get element at coordinates from AccessibilityService
        val accessibilityService = VisualMapperAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Toast.makeText(this, "Accessibility service not running", Toast.LENGTH_SHORT).show()
            return
        }

        val element = accessibilityService.findElementAtCoordinates(x, y)

        if (element != null) {
            Log.i(TAG, "Found element: ${element.text} (${element.className})")
            showElementHighlight(element)
            showActionMenu(element, x, y)
        } else {
            Log.d(TAG, "No element found at ($x, $y)")
            // Record raw tap
            showActionMenuForCoordinates(x, y)
        }
    }

    private fun handleRecordingSwipe(startX: Int, startY: Int, endX: Int, endY: Int) {
        Log.d(TAG, "Recording swipe from ($startX, $startY) to ($endX, $endY)")

        val deltaX = endX - startX
        val deltaY = endY - startY

        // Determine swipe direction
        val direction = when {
            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) -> {
                if (deltaX > 0) "right" else "left"
            }
            else -> {
                if (deltaY > 0) "down" else "up"
            }
        }

        // Calculate duration based on distance (longer swipe = longer duration)
        val distance = kotlin.math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toInt()
        val duration = (distance / 2).coerceIn(200, 1000) // 200-1000ms

        // Create swipe step
        val step = RecordedStep(
            type = StepType.SWIPE,
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            duration = duration,
            description = "Swipe $direction"
        )

        recordedSteps.add(step)
        controlPanel?.findViewById<TextView>(R.id.textStepCount)?.text = "Steps: ${recordedSteps.size}"

        Toast.makeText(this, "Recorded: Swipe $direction", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Recorded swipe $direction: ($startX,$startY) -> ($endX,$endY) duration=$duration")
    }

    // =========================================================================
    // Element Highlight
    // =========================================================================

    private fun showElementHighlight(element: UIElement) {
        hideElementHighlight()

        elementHighlight = View(this).apply {
            setBackgroundResource(R.drawable.element_highlight)
        }

        val params = WindowManager.LayoutParams(
            element.bounds.width,
            element.bounds.height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = element.bounds.x
            this.y = element.bounds.y
        }

        windowManager.addView(elementHighlight, params)

        // Auto-hide after 3 seconds
        elementHighlight?.postDelayed({
            hideElementHighlight()
        }, 3000)
    }

    private fun hideElementHighlight() {
        elementHighlight?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing element highlight", e)
            }
            elementHighlight = null
        }
    }

    // =========================================================================
    // Action Menu Overlay
    // =========================================================================

    private var actionMenuOverlay: View? = null
    private var currentSelectedElement: UIElement? = null
    private var currentTapX: Int = 0
    private var currentTapY: Int = 0
    private var isRawCoordinates: Boolean = false

    private fun showActionMenu(element: UIElement, x: Int, y: Int) {
        currentSelectedElement = element
        currentTapX = element.bounds.centerX
        currentTapY = element.bounds.centerY
        isRawCoordinates = false
        showActionMenuOverlay()
    }

    private fun showActionMenuForCoordinates(x: Int, y: Int) {
        currentSelectedElement = null
        currentTapX = x
        currentTapY = y
        isRawCoordinates = true
        showActionMenuOverlay()
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun showActionMenuOverlay() {
        hideActionMenuOverlay()

        // Hide touch interceptor while action menu is showing
        hideTouchInterceptor()

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        actionMenuOverlay = inflater.inflate(R.layout.overlay_action_menu, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Add outside touch listener to dismiss menu
        actionMenuOverlay?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideActionMenuOverlay()
                true
            } else {
                false
            }
        }

        setupActionMenuOverlay()
        windowManager.addView(actionMenuOverlay, params)
    }

    private fun hideActionMenuOverlay() {
        actionMenuOverlay?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing action menu overlay", e)
            }
            actionMenuOverlay = null
        }

        // Also hide element highlight
        hideElementHighlight()

        // Re-show touch interceptor if still in recording mode
        if (isRecordingMode) {
            showTouchInterceptor()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupActionMenuOverlay() {
        val menu = actionMenuOverlay ?: return
        val element = currentSelectedElement

        // Update element info display
        val textElementInfo = menu.findViewById<TextView>(R.id.textElementInfo)
        val textElementDetails = menu.findViewById<TextView>(R.id.textElementDetails)

        if (isRawCoordinates) {
            textElementInfo?.text = "Tap at coordinates"
            textElementDetails?.text = "Position: ($currentTapX, $currentTapY)"
        } else if (element != null) {
            val displayText = when {
                element.text.isNotEmpty() -> "\"${element.text}\""
                element.contentDescription.isNotEmpty() -> element.contentDescription
                element.resourceId.isNotEmpty() -> element.resourceId.substringAfterLast('/')
                else -> element.className.substringAfterLast('.')
            }
            textElementInfo?.text = displayText

            val details = buildString {
                append("Class: ${element.className.substringAfterLast('.')}")
                if (element.resourceId.isNotEmpty()) {
                    append("\nID: ${element.resourceId.substringAfterLast('/')}")
                }
                append("\nPosition: (${element.bounds.centerX}, ${element.bounds.centerY})")
            }
            textElementDetails?.text = details
        }

        // Handle outside touch to dismiss
        menu.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hideActionMenuOverlay()
                true
            } else false
        }

        // Tap button
        menu.findViewById<View>(R.id.btnTapElement)?.setOnClickListener {
            performTapAction()
        }

        // Type text button
        val btnTypeText = menu.findViewById<View>(R.id.btnTypeText)
        val layoutTypeText = menu.findViewById<View>(R.id.layoutTypeText)
        val editTextInput = menu.findViewById<android.widget.EditText>(R.id.editTextInput)
        val btnSendText = menu.findViewById<View>(R.id.btnSendText)

        btnTypeText?.setOnClickListener {
            layoutTypeText?.visibility = View.VISIBLE
            btnTypeText.visibility = View.GONE
        }

        btnSendText?.setOnClickListener {
            val text = editTextInput?.text?.toString() ?: ""
            if (text.isNotEmpty()) {
                performTypeTextAction(text)
            } else {
                Toast.makeText(this, "Enter text to type", Toast.LENGTH_SHORT).show()
            }
        }

        // Sensor button
        menu.findViewById<View>(R.id.btnCreateSensor)?.setOnClickListener {
            performCreateSensorAction()
        }

        // Action button
        menu.findViewById<View>(R.id.btnCreateAction)?.setOnClickListener {
            performCreateActionAction()
        }

        // Wait button
        menu.findViewById<View>(R.id.btnAddWait)?.setOnClickListener {
            performAddWaitAction()
        }

        // Cancel button
        menu.findViewById<View>(R.id.btnCancel)?.setOnClickListener {
            hideActionMenuOverlay()
        }
    }

    private fun performTapAction() {
        val element = currentSelectedElement
        val x = currentTapX
        val y = currentTapY

        scope.launch {
            val service = VisualMapperAccessibilityService.getInstance()
            if (service != null) {
                val success = service.gestureDispatcher.tap(x.toFloat(), y.toFloat())
                if (success) {
                    Toast.makeText(this@RecordingOverlayService, "Tapped at ($x, $y)", Toast.LENGTH_SHORT).show()

                    addStep(RecordedStep(
                        type = StepType.TAP,
                        description = element?.text?.takeIf { it.isNotEmpty() } ?: "Tap at ($x, $y)",
                        x = x,
                        y = y,
                        elementResourceId = element?.resourceId,
                        elementText = element?.text,
                        elementClass = element?.className
                    ))
                } else {
                    Toast.makeText(this@RecordingOverlayService, "Tap failed", Toast.LENGTH_SHORT).show()
                }
            }
            hideActionMenuOverlay()
        }
    }

    private fun performTypeTextAction(text: String) {
        val x = currentTapX
        val y = currentTapY

        scope.launch {
            val service = VisualMapperAccessibilityService.getInstance()
            if (service != null) {
                // Tap to focus first
                service.gestureDispatcher.tap(x.toFloat(), y.toFloat())
                delay(300)

                // Type text
                val success = service.gestureDispatcher.typeText(text)
                if (success) {
                    Toast.makeText(this@RecordingOverlayService, "Typed: $text", Toast.LENGTH_SHORT).show()

                    addStep(RecordedStep(
                        type = StepType.TYPE_TEXT,
                        description = "Type: $text",
                        x = x,
                        y = y,
                        elementText = text
                    ))
                } else {
                    Toast.makeText(this@RecordingOverlayService, "Type failed", Toast.LENGTH_SHORT).show()
                }
            }
            hideActionMenuOverlay()
        }
    }

    private fun performCreateSensorAction() {
        val element = currentSelectedElement

        if (element == null || (element.text.isEmpty() && element.contentDescription.isEmpty())) {
            Toast.makeText(this, "No text found in element", Toast.LENGTH_LONG).show()
            hideActionMenuOverlay()
            return
        }

        val sensorName = when {
            element.resourceId.isNotEmpty() -> element.resourceId.substringAfterLast('/')
            element.text.isNotEmpty() -> element.text.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")
            else -> "sensor_${System.currentTimeMillis()}"
        }

        Toast.makeText(this, "Sensor: $sensorName\nValue: ${element.text.ifEmpty { element.contentDescription }}", Toast.LENGTH_LONG).show()

        addStep(RecordedStep(
            type = StepType.CAPTURE_SENSOR,
            description = "Capture: $sensorName",
            x = element.bounds.centerX,
            y = element.bounds.centerY,
            elementResourceId = element.resourceId,
            elementText = element.text,
            elementClass = element.className,
            sensorConfig = SensorConfig(
                name = sensorName,
                sensorType = "sensor",
                extractionMethod = "exact"
            )
        ))

        hideActionMenuOverlay()
    }

    private fun performCreateActionAction() {
        val element = currentSelectedElement

        val actionName = when {
            element?.resourceId?.isNotEmpty() == true -> "action_${element.resourceId.substringAfterLast('/')}"
            element?.text?.isNotEmpty() == true -> "action_${element.text.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}"
            else -> "action_${System.currentTimeMillis()}"
        }

        Toast.makeText(this, "Action: $actionName", Toast.LENGTH_LONG).show()

        addStep(RecordedStep(
            type = StepType.CREATE_ACTION,
            description = "Action: $actionName",
            x = currentTapX,
            y = currentTapY,
            elementResourceId = element?.resourceId,
            elementText = element?.text,
            elementClass = element?.className,
            actionConfig = ActionConfig(
                name = actionName,
                actionType = "tap"
            )
        ))

        hideActionMenuOverlay()
    }

    private fun performAddWaitAction() {
        Toast.makeText(this, "Wait step added (2 seconds)", Toast.LENGTH_SHORT).show()

        addStep(RecordedStep(
            type = StepType.WAIT,
            description = "Wait 2s"
        ))

        hideActionMenuOverlay()
    }

    // =========================================================================
    // Flow Management
    // =========================================================================

    fun addStep(step: RecordedStep) {
        recordedSteps.add(step)
        Log.i(TAG, "Added step: ${step.type} - ${step.description}")

        // Update step count in control panel
        controlPanel?.findViewById<TextView>(R.id.textStepCount)?.text =
            "Steps: ${recordedSteps.size}"
    }

    private fun saveRecordedFlow() {
        if (recordedSteps.isEmpty()) {
            Toast.makeText(this, "No steps recorded", Toast.LENGTH_SHORT).show()
            return
        }

        // TODO: Show save dialog to name the flow
        // For now, auto-save with timestamp
        val flowName = "Flow_${System.currentTimeMillis()}"

        Toast.makeText(this, "Saved $flowName with ${recordedSteps.size} steps", Toast.LENGTH_LONG).show()

        // Clear steps
        recordedSteps.clear()
        controlPanel?.findViewById<TextView>(R.id.textStepCount)?.text = "Steps: 0"
    }
}

/**
 * Represents a recorded step in a flow
 */
data class RecordedStep(
    val type: StepType,
    val description: String,
    val x: Int? = null,
    val y: Int? = null,
    // For swipe gestures
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val duration: Int? = null,
    // Element info
    val elementResourceId: String? = null,
    val elementText: String? = null,
    val elementClass: String? = null,
    val sensorConfig: SensorConfig? = null,
    val actionConfig: ActionConfig? = null,
    val timestamp: Long = System.currentTimeMillis()
)

enum class StepType {
    TAP,
    SWIPE,
    TYPE_TEXT,
    WAIT,
    CAPTURE_SENSOR,
    CREATE_ACTION
}

data class SensorConfig(
    val name: String,
    val sensorType: String,
    val extractionMethod: String,
    val extractionPattern: String? = null
)

data class ActionConfig(
    val name: String,
    val actionType: String
)

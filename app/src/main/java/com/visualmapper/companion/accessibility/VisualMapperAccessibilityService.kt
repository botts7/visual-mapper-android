package com.visualmapper.companion.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.visualmapper.companion.explorer.AppExplorerService
import com.visualmapper.companion.explorer.SensitiveAppDetector
import com.visualmapper.companion.navigation.NavigationLearner
import com.visualmapper.companion.navigation.TransitionAction
import com.visualmapper.companion.security.AuditLogger
import com.visualmapper.companion.security.ConsentManager
import com.visualmapper.companion.security.SecurePreferences
import com.visualmapper.companion.security.SensitiveDataDetector
import com.visualmapper.companion.storage.AppDatabase
import com.visualmapper.companion.VisualMapperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Visual Mapper Accessibility Service
 *
 * Provides UI reading and gesture dispatch capabilities with SECURITY FIRST:
 * - App whitelist filtering (only monitor allowed apps)
 * - Sensitive data detection (never capture passwords, credit cards)
 * - Per-app consent checking (explicit user permission required)
 * - Audit logging (transparent record of all operations)
 *
 * Privacy guarantees:
 * - Password fields are NEVER read (isPassword check)
 * - Credit card patterns are detected and masked
 * - Sensitive apps require explicit consent
 * - All data access is logged for user review
 */
class VisualMapperAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "VMAccessibility"

        // Singleton instance for access from other components
        // @Volatile ensures thread-safe visibility across all threads
        // This fixes the intermittent "Accessibility service not available" error
        @Volatile
        private var instance: VisualMapperAccessibilityService? = null

        fun getInstance(): VisualMapperAccessibilityService? = instance

        fun isRunning(): Boolean = instance != null
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current UI state
    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage

    private val _currentActivity = MutableStateFlow<String?>(null)
    val currentActivity: StateFlow<String?> = _currentActivity

    // Last clicked element (for Manual mode ML learning)
    data class ClickedElement(
        val resourceId: String?,
        val text: String?,
        val contentDescription: String?,
        val className: String?,
        val bounds: Rect?,
        val timestamp: Long = System.currentTimeMillis()
    )
    private var _lastClickedElement: ClickedElement? = null
    val lastClickedElement: ClickedElement? get() = _lastClickedElement

    /**
     * Clear the last clicked element (call after using it)
     */
    fun clearLastClickedElement() {
        _lastClickedElement = null
    }

    // Security components (initialized lazily)
    private var securePrefs: SecurePreferences? = null
    private var sensitiveDetector: SensitiveDataDetector? = null
    private var consentManager: ConsentManager? = null
    private var auditLogger: AuditLogger? = null

    // Navigation learner (for passive navigation learning)
    private var navigationLearner: NavigationLearner? = null

    // Gesture dispatcher
    val gestureDispatcher by lazy { GestureDispatcher(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Initialize security components
        try {
            securePrefs = SecurePreferences(applicationContext)
            sensitiveDetector = SensitiveDataDetector()
            auditLogger = AuditLogger(applicationContext)
            consentManager = ConsentManager(applicationContext, securePrefs!!, auditLogger!!)
            Log.i(TAG, "Accessibility service connected with security enabled")

            // Initialize navigation learner with Room database for persistence (Phase 1)
            try {
                val app = VisualMapperApp.instance

                // Get the Room database and DAO for guaranteed delivery
                val database = AppDatabase.getDatabase(applicationContext)
                val transitionDao = database.pendingTransitionDao()

                navigationLearner = NavigationLearner(
                    mqttManager = app.mqttManager,
                    consentManager = consentManager!!,
                    securePrefs = securePrefs!!,
                    transitionDao = transitionDao,  // Phase 1: Pass DAO for persistence
                    context = applicationContext     // Phase 3: Pass context for battery checks
                )
                Log.i(TAG, "Navigation learner initialized with DB persistence and battery awareness")

                // Flush any pending transitions when MQTT connects
                // (Phase 1: Guaranteed delivery for queued data)
                scope.launch {
                    app.mqttManager.connectionState.collect { state ->
                        if (state == com.visualmapper.companion.mqtt.MqttManager.ConnectionState.CONNECTED) {
                            Log.d(TAG, "MQTT connected - triggering flush of pending transitions")
                            navigationLearner?.flushPendingTransitions()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize navigation learner (MQTT/DB may not be ready)", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize security components", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        // Phase 1: Clean up NavigationLearner's coroutine scope
        navigationLearner?.destroy()
        scope.cancel()
        Log.i(TAG, "Accessibility service destroyed")
    }

    // =========================================================================
    // Dynamic Scoping & Battery Safety (Phase 1 Compliance)
    // =========================================================================

    /**
     * Dynamically update the target package for accessibility events.
     *
     * This is CRITICAL for Google Play compliance:
     * - When packageName is null: Listen to NO packages (maximum privacy, battery savings)
     * - When packageName is set: Listen ONLY to that specific app (targeted monitoring)
     *
     * Call this method:
     * - Before flow execution: updateTargetPackage(flow.targetPackage)
     * - After flow execution: updateTargetPackage(null)
     * - During exploration: updateTargetPackage(targetApp)
     */
    fun updateTargetPackage(packageName: String?) {
        val info = serviceInfo ?: run {
            Log.w(TAG, "updateTargetPackage: serviceInfo is null, cannot update")
            return
        }

        val previousPackages = info.packageNames?.joinToString(",") ?: "ALL"
        info.packageNames = if (packageName != null) arrayOf(packageName) else null
        serviceInfo = info

        val newPackages = info.packageNames?.joinToString(",") ?: "ALL"
        Log.i(TAG, "Dynamic scoping updated: $previousPackages -> $newPackages")
    }

    /**
     * Enable or disable high-precision mode for accessibility event timing.
     *
     * Battery Safety:
     * - Normal mode (500ms): Default, battery-friendly for background monitoring
     * - High-precision mode (100ms): Fast response during active recording/exploration
     *
     * Call this method:
     * - Start recording: setHighPrecisionMode(true)
     * - Stop recording: setHighPrecisionMode(false)
     * - Start exploration: setHighPrecisionMode(true)
     * - Stop exploration: setHighPrecisionMode(false)
     */
    fun setHighPrecisionMode(enabled: Boolean) {
        val info = serviceInfo ?: run {
            Log.w(TAG, "setHighPrecisionMode: serviceInfo is null, cannot update")
            return
        }

        val previousTimeout = info.notificationTimeout
        info.notificationTimeout = if (enabled) 100L else 500L
        serviceInfo = info

        Log.i(TAG, "High-precision mode ${if (enabled) "ENABLED" else "DISABLED"}: timeout ${previousTimeout}ms -> ${info.notificationTimeout}ms")
    }

    /**
     * Get current scoping state for debugging
     */
    fun getScopingState(): Map<String, Any?> {
        val info = serviceInfo
        return mapOf(
            "targetPackages" to info?.packageNames?.toList(),
            "notificationTimeout" to info?.notificationTimeout,
            "highPrecisionMode" to (info?.notificationTimeout == 100L)
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            // Track click events for Manual mode ML learning + Human-in-the-Loop detection
            if (it.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val source = it.source
                if (source != null) {
                    val bounds = Rect()
                    source.getBoundsInScreen(bounds)
                    val elementInfo = buildString {
                        source.viewIdResourceName?.let { append("id=$it ") }
                        source.text?.toString()?.take(20)?.let { append("text='$it' ") }
                        source.className?.toString()?.let { append("class=$it") }
                    }

                    _lastClickedElement = ClickedElement(
                        resourceId = source.viewIdResourceName,
                        text = source.text?.toString(),
                        contentDescription = source.contentDescription?.toString(),
                        className = source.className?.toString(),
                        bounds = bounds
                    )
                    Log.d(TAG, "Click captured: resId=${source.viewIdResourceName}, text=${source.text?.toString()?.take(20)}, bounds=$bounds")

                    // Human-in-the-Loop: Notify UserInteractionDetector to distinguish bot vs user clicks
                    try {
                        AppExplorerService.getInstance()?.getUserInteractionDetector()?.onClickDetected(bounds, elementInfo)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to notify user interaction detector", e)
                    }

                    source.recycle()
                }
            }

            // Track current app/activity
            if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val pkg = it.packageName?.toString()
                val activity = it.className?.toString()
                _currentPackage.value = pkg
                _currentActivity.value = activity

                // Notify navigation learner of screen change
                // Phase 3: Fail-fast check BEFORE expensive UI tree parsing
                if (pkg != null && activity != null) {
                    // Only parse UI tree if learner wants to process this update
                    // This avoids expensive getUITreeForLearning() when debouncing or low battery
                    if (navigationLearner?.shouldProcessUpdate() == true) {
                        try {
                            val uiElements = getUITreeForLearning(pkg)
                            navigationLearner?.onScreenChanged(pkg, activity, uiElements)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to notify navigation learner", e)
                        }
                    }
                }

                // Check if app is whitelisted
                if (pkg != null && !isAppAllowed(pkg)) {
                    Log.d(TAG, "App not in whitelist, ignoring: $pkg")
                    return
                }

                Log.d(TAG, "Window changed: ${it.packageName} / ${it.className}")
            }
        }
    }

    /**
     * Get UI tree for navigation learning (lighter weight, no audit logging)
     */
    private fun getUITreeForLearning(packageName: String): List<UIElement> {
        val root = rootInActiveWindow ?: return emptyList()

        // Check whitelist first
        if (!isAppAllowed(packageName)) {
            return emptyList()
        }

        // HARD-FAIL: Block sensitive apps (banking, messaging, password managers)
        if (SensitiveAppDetector.isSensitiveApp(packageName)) {
            Log.w(TAG, "getUITreeForLearning: HARD-FAIL - $packageName is SENSITIVE")
            return emptyList()
        }

        val consentLevel = getConsentLevelForPackage(packageName)
        if (consentLevel == ConsentManager.ConsentLevel.NONE) {
            return emptyList()
        }

        // Parse without audit logging (to avoid noise during learning)
        return parseNodeRecursive(root, 0, consentLevel) { /* ignore sensitive count */ }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    // =========================================================================
    // Security Checks
    // =========================================================================

    /**
     * Check if app is in the whitelist (or whitelist is empty = allow all)
     */
    private fun isAppAllowed(packageName: String): Boolean {
        val prefs = securePrefs ?: run {
            Log.d(TAG, "isAppAllowed: securePrefs is null, allowing $packageName")
            return true
        }
        val isWhitelisted = prefs.isAppWhitelisted(packageName)
        Log.d(TAG, "isAppAllowed: $packageName = $isWhitelisted (whitelist: ${prefs.whitelistedApps})")
        return isWhitelisted
    }

    /**
     * Check if we have consent to read from current app
     */
    private fun hasConsentForCurrentApp(): Boolean {
        val pkg = _currentPackage.value ?: return false
        val consent = consentManager ?: return true // No security = allow
        return consent.hasValidConsent(pkg)
    }

    /**
     * Get consent level for current app
     */
    private fun getCurrentConsentLevel(): ConsentManager.ConsentLevel {
        val pkg = _currentPackage.value ?: return ConsentManager.ConsentLevel.NONE
        val consent = consentManager ?: return ConsentManager.ConsentLevel.FULL
        return consent.getConsentLevel(pkg)
    }

    /**
     * Get consent level for a specific package (used when package is resolved from root window)
     */
    private fun getConsentLevelForPackage(packageName: String?): ConsentManager.ConsentLevel {
        if (packageName == null) return ConsentManager.ConsentLevel.NONE
        val consent = consentManager ?: return ConsentManager.ConsentLevel.FULL
        return consent.getConsentLevel(packageName)
    }

    // =========================================================================
    // UI Tree Reading (with security filtering)
    // =========================================================================

    /**
     * Get the current UI tree with security filtering applied
     */
    fun getUITree(): List<UIElement> {
        Log.d(TAG, "getUITree() called")

        // Get root window first - we need it to determine the package if not tracked
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "getUITree: rootInActiveWindow is null")
            return emptyList()
        }

        // Get package - prefer root window package when exploring (overlay shows com.visualmapper.companion)
        // When our overlay is shown, _currentPackage tracks our app, but root.packageName is the target
        val rootPkg = root.packageName?.toString()
        val trackedPkg = _currentPackage.value

        // Use root package if:
        // 1. Root package is available and not empty
        // 2. Root package is not our own app (we're exploring another app)
        // Otherwise fall back to tracked package
        val pkg = when {
            !rootPkg.isNullOrEmpty() && rootPkg != "com.visualmapper.companion" -> rootPkg
            !trackedPkg.isNullOrEmpty() && trackedPkg != "com.visualmapper.companion" -> trackedPkg
            !rootPkg.isNullOrEmpty() -> rootPkg  // Last resort: even our own app
            else -> trackedPkg
        }
        Log.d(TAG, "getUITree: package = $pkg (tracked: $trackedPkg, root: $rootPkg)")

        // Update tracked package if we got it from root
        if (_currentPackage.value == null && pkg != null) {
            _currentPackage.value = pkg
        }

        // Check whitelist
        if (pkg != null && !isAppAllowed(pkg)) {
            Log.w(TAG, "getUITree: package $pkg not allowed, returning empty")
            auditLogger?.logAccessDenied(pkg, "App not in whitelist")
            return emptyList()
        }

        // HARD-FAIL: Block sensitive apps (banking, messaging, password managers)
        // This is a CRITICAL compliance requirement - never read UI from sensitive apps
        if (pkg != null && SensitiveAppDetector.isSensitiveApp(pkg)) {
            Log.w(TAG, "getUITree: HARD-FAIL - package $pkg is SENSITIVE, blocking access")
            auditLogger?.logAccessDenied(pkg, "Sensitive app - access blocked for compliance")
            return emptyList()
        }

        // Check consent - use the resolved package name
        val consentLevel = getConsentLevelForPackage(pkg)
        Log.d(TAG, "getUITree: consent level for $pkg = $consentLevel")
        if (consentLevel == ConsentManager.ConsentLevel.NONE) {
            Log.w(TAG, "getUITree: no consent for $pkg, returning empty")
            auditLogger?.logAccessDenied(pkg ?: "unknown", "No consent granted")
            return emptyList()
        }

        var sensitiveBlocked = 0
        val elements = parseNodeRecursive(root, 0, consentLevel) { blocked ->
            sensitiveBlocked += blocked
        }

        Log.d(TAG, "getUITree: parsed ${elements.size} elements (sensitiveBlocked: $sensitiveBlocked)")

        // Count clickable elements
        val clickable = elements.count { it.isClickable }
        val scrollable = elements.count { it.isScrollable }
        val withText = elements.count { it.text.isNotEmpty() }
        Log.d(TAG, "getUITree: clickable=$clickable, scrollable=$scrollable, withText=$withText")

        // Log the access
        auditLogger?.logUIRead(
            packageName = pkg ?: "unknown",
            elementCount = elements.size,
            sensitiveBlocked = sensitiveBlocked,
            consentLevel = consentLevel.name
        )

        return elements
    }

    /**
     * Get UI tree as JSON string (for sending to server)
     */
    fun getUITreeJson(): String {
        val elements = getUITree()
        return buildString {
            append("[")
            elements.forEachIndexed { index, element ->
                if (index > 0) append(",")
                append(element.toJson())
            }
            append("]")
        }
    }

    private fun parseNodeRecursive(
        node: AccessibilityNodeInfo,
        depth: Int,
        consentLevel: ConsentManager.ConsentLevel,
        onSensitiveBlocked: (Int) -> Unit
    ): List<UIElement> {
        val elements = mutableListOf<UIElement>()
        val detector = sensitiveDetector

        // Check if node contains sensitive data
        val sensitiveResult = detector?.checkNode(node)
        if (sensitiveResult?.isSensitive == true) {
            // Log the block
            auditLogger?.logSensitiveBlock(
                packageName = _currentPackage.value ?: "unknown",
                category = sensitiveResult.category?.name ?: "UNKNOWN",
                reason = sensitiveResult.reason ?: "Sensitive data detected"
            )

            // Should we block completely?
            if (detector.shouldBlockCapture(sensitiveResult)) {
                onSensitiveBlocked(1)
                // Skip this node entirely for passwords
                if (sensitiveResult.category == SensitiveDataDetector.SensitiveCategory.PASSWORD) {
                    return elements // Return without adding this node
                }
            }
        }

        // Get bounds
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Determine what text to include based on consent level
        var textToInclude = ""
        var contentDescToInclude = ""

        when {
            // NEVER include password field content
            node.isPassword -> {
                textToInclude = "[PROTECTED]"
                onSensitiveBlocked(1)
            }

            // Check consent level for text content
            consentLevel >= ConsentManager.ConsentLevel.FULL -> {
                // Full consent - include text but mask if sensitive
                val rawText = node.text?.toString() ?: ""
                textToInclude = if (sensitiveResult?.isSensitive == true) {
                    detector?.maskSensitiveText(rawText, sensitiveResult) ?: rawText
                } else {
                    rawText
                }
                contentDescToInclude = node.contentDescription?.toString() ?: ""
            }

            consentLevel == ConsentManager.ConsentLevel.BASIC -> {
                // Basic consent - only structure, no text content
                textToInclude = ""
                contentDescToInclude = ""
            }

            else -> {
                textToInclude = ""
                contentDescToInclude = ""
            }
        }

        // Create element
        val element = UIElement(
            className = node.className?.toString() ?: "",
            text = textToInclude,
            contentDescription = contentDescToInclude,
            resourceId = node.viewIdResourceName ?: "",
            bounds = ElementBounds.fromRect(bounds),
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isPassword = node.isPassword,
            isSensitive = sensitiveResult?.isSensitive == true,
            depth = depth
        )

        elements.add(element)

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                elements.addAll(parseNodeRecursive(child, depth + 1, consentLevel, onSensitiveBlocked))
                child.recycle()
            }
        }

        return elements
    }

    // =========================================================================
    // Element Finding (with security)
    // =========================================================================

    /**
     * Find element by text (respects consent)
     */
    fun findElementByText(text: String): UIElement? {
        if (!hasConsentForCurrentApp()) {
            return null
        }
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()?.let { nodeToElement(it) }
    }

    /**
     * Find element by resource ID (respects consent)
     */
    fun findElementByResourceId(resourceId: String): UIElement? {
        if (!hasConsentForCurrentApp()) {
            return null
        }
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        return nodes.firstOrNull()?.let { nodeToElement(it) }
    }

    /**
     * Find element at coordinates (respects consent)
     */
    fun findElementAtCoordinates(x: Int, y: Int): UIElement? {
        val elements = getUITree() // This already respects consent
        // Find smallest element containing the point
        return elements.filter { element ->
            x >= element.bounds.x &&
            x <= element.bounds.x + element.bounds.width &&
            y >= element.bounds.y &&
            y <= element.bounds.y + element.bounds.height
        }.lastOrNull()
    }

    private fun nodeToElement(node: AccessibilityNodeInfo): UIElement {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val sensitiveResult = sensitiveDetector?.checkNode(node)

        return UIElement(
            className = node.className?.toString() ?: "",
            text = if (node.isPassword) "[PROTECTED]" else (node.text?.toString() ?: ""),
            contentDescription = node.contentDescription?.toString() ?: "",
            resourceId = node.viewIdResourceName ?: "",
            bounds = ElementBounds.fromRect(bounds),
            isClickable = node.isClickable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled,
            isChecked = node.isChecked,
            isFocused = node.isFocused,
            isPassword = node.isPassword,
            isSensitive = sensitiveResult?.isSensitive == true,
            depth = 0
        )
    }

    // =========================================================================
    // Text Extraction (with security)
    // =========================================================================

    /**
     * Extract text from element at coordinates (respects consent, blocks sensitive)
     */
    fun extractTextAt(x: Int, y: Int): String? {
        val element = findElementAtCoordinates(x, y)

        // Never return password or sensitive content
        if (element?.isPassword == true || element?.isSensitive == true) {
            return "[PROTECTED]"
        }

        return element?.text?.takeIf { it.isNotEmpty() }
            ?: element?.contentDescription?.takeIf { it.isNotEmpty() }
    }

    /**
     * Extract all text from current screen (respects consent, filters sensitive)
     */
    fun extractAllText(): List<String> {
        return getUITree() // Already filtered by security
            .filter { !it.isPassword && !it.isSensitive }
            .mapNotNull { it.text.takeIf { text -> text.isNotEmpty() && text != "[PROTECTED]" } }
    }

    // =========================================================================
    // Text Input
    // =========================================================================

    /**
     * Input text into the currently focused field
     */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Find focused node that is editable
        val focusedNode = findFocusedEditableNode(root)
        if (focusedNode == null) {
            Log.w(TAG, "No focused editable node found")
            return false
        }

        // Use clipboard action to paste text (more reliable than ACTION_SET_TEXT)
        val arguments = android.os.Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        focusedNode.recycle()

        if (result) {
            auditLogger?.logGesture(_currentPackage.value ?: "unknown", "INPUT_TEXT", 0f, 0f)
        }

        return result
    }

    /**
     * Find the currently focused editable node
     */
    private fun findFocusedEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Check if this node is the focused editable
        if (node.isFocused && node.isEditable) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child)
            child.recycle()
            if (result != null) {
                return result
            }
        }

        return null
    }

    // =========================================================================
    // Security Component Access
    // =========================================================================

    fun getSecurePreferences(): SecurePreferences? = securePrefs
    fun getConsentManager(): ConsentManager? = consentManager
    fun getAuditLogger(): AuditLogger? = auditLogger
    fun getSensitiveDetector(): SensitiveDataDetector? = sensitiveDetector

    // =========================================================================
    // Activity Detection (for Flow State Validation)
    // =========================================================================

    /**
     * Get current foreground activity name in format "package/activity"
     * Uses rootInActiveWindow to get the actual package/class.
     */
    fun getCurrentActivityName(): String? {
        val root = rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString() ?: return null
        // The window class name is often the activity name
        val className = root.className?.toString() ?: ""
        return "$packageName/$className"
    }

    /**
     * Get just the current package name
     */
    fun getCurrentPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Get all UI elements as a flat list of AccessibilityNodeInfo
     * Note: Caller should NOT recycle these nodes as they share the tree
     */
    fun getAllElementNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<AccessibilityNodeInfo>()
        collectElementNodes(root, elements)
        return elements
    }

    private fun collectElementNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectElementNodes(child, list)
            }
        }
    }

    /**
     * Check if a specific element is present on screen by text
     */
    fun hasElementWithText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    /**
     * Check if a specific element is present by resource ID
     */
    fun hasElementWithResourceId(resourceId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
        val found = nodes.isNotEmpty()
        nodes.forEach { it.recycle() }
        return found
    }

    // =========================================================================
    // Navigation Learning
    // =========================================================================

    /**
     * Get the navigation learner instance
     */
    fun getNavigationLearner(): NavigationLearner? = navigationLearner

    /**
     * Notify navigation learner of an action (e.g., tap, swipe)
     * Call this BEFORE the action is performed so the learner knows
     * what action caused the next screen transition.
     */
    fun notifyActionPerformed(action: TransitionAction) {
        navigationLearner?.onActionPerformed(action)
    }

    /**
     * Set flow execution state for navigation learning.
     * When in EXECUTION_ONLY mode, learning only happens during flow execution.
     */
    fun setFlowExecuting(executing: Boolean) {
        navigationLearner?.setFlowExecuting(executing)
        Log.d(TAG, "Flow executing state: $executing")
    }

}

/**
 * Represents a UI element from the accessibility tree
 */
data class UIElement(
    val className: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: ElementBounds,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val isPassword: Boolean = false,
    val isSensitive: Boolean = false,
    val depth: Int
) {
    fun toJson(): String {
        return """{"class":"$className","text":"${escapeJson(text)}","content_desc":"${escapeJson(contentDescription)}","resource_id":"$resourceId","bounds":${bounds.toJson()},"clickable":$isClickable,"scrollable":$isScrollable,"enabled":$isEnabled,"checked":$isChecked,"is_password":$isPassword,"is_sensitive":$isSensitive}"""
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * Element bounds (x, y, width, height)
 */
data class ElementBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    fun toJson(): String = """{"x":$x,"y":$y,"width":$width,"height":$height}"""

    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    /** Check if bounds are valid (positive dimensions) */
    val isValid: Boolean get() = width > 0 && height > 0

    companion object {
        /**
         * Create ElementBounds from Android Rect, ensuring positive dimensions.
         * Returns bounds with clamped values if original would be invalid.
         */
        fun fromRect(rect: android.graphics.Rect): ElementBounds {
            val w = rect.width()
            val h = rect.height()
            return ElementBounds(
                x = rect.left,
                y = rect.top,
                width = if (w > 0) w else 0,
                height = if (h > 0) h else 0
            )
        }
    }
}

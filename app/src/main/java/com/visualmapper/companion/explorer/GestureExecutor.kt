package com.visualmapper.companion.explorer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import kotlinx.coroutines.delay

/**
 * Executes gestures (taps, scrolls, back) with access level checking and audit logging.
 *
 * Responsibilities:
 * - Perform tap gestures with access control
 * - Perform scroll gestures with access control
 * - Perform back navigation
 * - Launch apps and ensure target app is active
 *
 * Extracted from AppExplorerService for modularity.
 */
class GestureExecutor(
    private val context: Context,
    private val accessManager: AccessLevelManager,
    private val auditLog: ExplorationAuditLog,
    private val goModeManager: GoModeManager
) {
    companion object {
        private const val TAG = "GestureExecutor"
    }

    /**
     * Perform a tap at the specified coordinates.
     *
     * @param x The x coordinate
     * @param y The y coordinate
     * @return true if the tap was performed successfully
     */
    suspend fun performTap(x: Int, y: Int): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        return accessibilityService.gestureDispatcher.tap(x.toFloat(), y.toFloat())
    }

    /**
     * Perform tap with access level check and audit logging.
     *
     * @param x The x coordinate
     * @param y The y coordinate
     * @param elementId The element ID (for logging)
     * @param screenId The screen ID (for logging)
     * @param packageName The target app package name
     * @return true if the tap was performed successfully
     */
    suspend fun performTapWithAccessCheck(
        x: Int,
        y: Int,
        elementId: String?,
        screenId: String?,
        packageName: String
    ): Boolean {
        // Check access level
        if (!accessManager.canTap(packageName)) {
            Log.w(TAG, "[ACCESS DENIED] Tap blocked - access level too low")
            auditLog.logAction(
                action = ExplorationAction.TapElement,
                targetApp = packageName,
                targetElement = elementId,
                screenId = screenId,
                accessLevel = accessManager.getAccessLevelForApp(packageName),
                wasBlocked = true,
                blockReason = "Access level too low for tap action",
                goModeActive = goModeManager.isActive(),
                success = false
            )
            return false
        }

        val result = performTap(x, y)

        // Log the action
        auditLog.logAction(
            action = ExplorationAction.TapElement,
            targetApp = packageName,
            targetElement = elementId,
            screenId = screenId,
            accessLevel = accessManager.getAccessLevelForApp(packageName),
            wasBlocked = false,
            goModeActive = goModeManager.isActive(),
            success = result
        )

        return result
    }

    /**
     * Perform a back navigation.
     *
     * @return true if the back action was performed successfully
     */
    suspend fun performBack(): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        return accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Perform scroll with access level check and audit logging.
     *
     * @param centerX The center x coordinate
     * @param centerY The center y coordinate
     * @param direction The scroll direction
     * @param packageName The target app package name
     * @param screenId The screen ID (for logging)
     * @return true if the scroll was performed successfully
     */
    suspend fun performScrollWithAccessCheck(
        centerX: Int,
        centerY: Int,
        direction: ScrollDirection,
        packageName: String,
        screenId: String?
    ): Boolean {
        // Check access level
        if (!accessManager.canScroll(packageName)) {
            Log.w(TAG, "[ACCESS DENIED] Scroll blocked - access level too low")
            auditLog.logAction(
                action = ExplorationAction.Scroll,
                targetApp = packageName,
                screenId = screenId,
                accessLevel = accessManager.getAccessLevelForApp(packageName),
                wasBlocked = true,
                blockReason = "Access level too low for scroll action",
                goModeActive = goModeManager.isActive(),
                success = false
            )
            return false
        }

        val result = performScroll(centerX, centerY, direction)

        // Log the action
        auditLog.logAction(
            action = ExplorationAction.Scroll,
            targetApp = packageName,
            screenId = screenId,
            accessLevel = accessManager.getAccessLevelForApp(packageName),
            wasBlocked = false,
            goModeActive = goModeManager.isActive(),
            success = result
        )

        return result
    }

    /**
     * Perform a scroll gesture.
     *
     * @param centerX The center x coordinate
     * @param centerY The center y coordinate
     * @param direction The scroll direction
     * @return true if the scroll was performed successfully
     */
    suspend fun performScroll(centerX: Int, centerY: Int, direction: ScrollDirection): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false

        val scrollAmount = 500 // pixels
        val (endX, endY) = when (direction) {
            ScrollDirection.VERTICAL -> centerX to centerY - scrollAmount
            ScrollDirection.HORIZONTAL -> centerX - scrollAmount to centerY
            ScrollDirection.BOTH -> centerX - scrollAmount / 2 to centerY - scrollAmount / 2
        }

        return accessibilityService.gestureDispatcher.swipe(
            centerX.toFloat(), centerY.toFloat(),
            endX.toFloat(), endY.toFloat(),
            300
        )
    }

    /**
     * Check if we're currently in the target app.
     *
     * @param targetPackage The target app package name
     * @return true if the current foreground app matches the target
     */
    fun isInTargetApp(targetPackage: String?): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        // Check rootInActiveWindow FIRST (direct read, more reliable)
        val currentPackage = accessibilityService.rootInActiveWindow?.packageName?.toString()
            ?: accessibilityService.currentPackage?.value
        return currentPackage != null && currentPackage == targetPackage
    }

    /**
     * Launch an app by package name.
     *
     * @param packageName The app to launch
     * @param forceRestart If true, clear task and start fresh
     * @return true if the app was launched successfully
     */
    suspend fun launchApp(packageName: String, forceRestart: Boolean = false): Boolean {
        return try {
            // Check if already in foreground - don't relaunch if so
            val currentPackage = VisualMapperAccessibilityService.getInstance()?.getCurrentPackageName()
            if (currentPackage == packageName && !forceRestart) {
                Log.i(TAG, "App $packageName already in foreground - no relaunch needed")
                return true
            }

            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                if (forceRestart) {
                    // Clear task to ensure we start from main activity (full restart)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    Log.i(TAG, "Force restarting app: $packageName")
                } else {
                    // Bring to front without killing existing instance
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    Log.i(TAG, "Bringing app to front: $packageName")
                }
                context.startActivity(intent)
                true
            } else {
                Log.e(TAG, "No launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
            false
        }
    }

    /**
     * Ensure app exploration starts from the app's home/main screen.
     * If the app is already open on a sub-screen, navigate back to home first.
     *
     * @param packageName The target app package name
     */
    suspend fun ensureAppStartsFromHome(packageName: String) {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return

        // Check if target app is currently in foreground
        val currentPkg = accessibilityService.rootInActiveWindow?.packageName?.toString()
        if (currentPkg != packageName) {
            Log.d(TAG, "Target app not in foreground (current: $currentPkg) - will launch fresh")
            return
        }

        Log.i(TAG, "Target app already in foreground - checking if on sub-screen...")

        // Check for back buttons that indicate we're on a sub-screen
        var backAttempts = 0
        val maxBackAttempts = 10  // Prevent infinite loop

        while (backAttempts < maxBackAttempts) {
            // Capture current screen to check for back buttons
            val uiElements = accessibilityService.getUITree()
            val backButton = uiElements.find { element ->
                val resourceId = element.resourceId?.lowercase() ?: ""
                val contentDesc = element.contentDescription?.lowercase() ?: ""

                // Common back button patterns
                (resourceId.contains("back") ||
                 resourceId.contains("btn_finish") ||
                 resourceId.contains("navigate_up") ||
                 resourceId.contains("close") ||
                 contentDesc.contains("back") ||
                 contentDesc.contains("navigate up") ||
                 contentDesc == "关闭" ||  // Chinese "close"
                 contentDesc == "返回") && // Chinese "back"
                element.isClickable
            }

            if (backButton == null) {
                Log.i(TAG, "No back button found - likely at home screen (after $backAttempts back presses)")
                break
            }

            // Found a back button - we're on a sub-screen
            Log.d(TAG, "Found back button on sub-screen: ${backButton.resourceId ?: backButton.contentDescription}")
            Log.i(TAG, "Navigating back to home (attempt ${backAttempts + 1}/$maxBackAttempts)...")

            // Tap the back button
            val bounds = backButton.bounds
            val centerX = bounds.x + bounds.width / 2
            val centerY = bounds.y + bounds.height / 2
            accessibilityService.gestureDispatcher.tap(centerX.toFloat(), centerY.toFloat())
            delay(1000)  // Wait for navigation

            // Verify we're still in the target app
            val newPkg = accessibilityService.rootInActiveWindow?.packageName?.toString()
            if (newPkg != packageName) {
                Log.i(TAG, "Back navigation exited the app - will relaunch")
                return
            }

            backAttempts++
        }

        if (backAttempts >= maxBackAttempts) {
            Log.w(TAG, "Max back attempts reached - forcing app relaunch")
            // Go to home screen, then let launchApp relaunch the app fresh
            accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(500)
        }

        Log.i(TAG, "App should now be at home screen (or will be relaunched)")
    }

    /**
     * Go to the Android home screen.
     *
     * @return true if the action was performed
     */
    suspend fun goToHomeScreen(): Boolean {
        val accessibilityService = VisualMapperAccessibilityService.getInstance() ?: return false
        return accessibilityService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }
}

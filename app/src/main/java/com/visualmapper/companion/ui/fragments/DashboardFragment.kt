package com.visualmapper.companion.ui.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.appcompat.app.AlertDialog
import com.visualmapper.companion.R
import com.visualmapper.companion.VisualMapperApp
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.explorer.ExplorationMode
import com.visualmapper.companion.explorer.ExplorationResultActivity
import com.visualmapper.companion.mqtt.MqttManager
import com.visualmapper.companion.server.ServerSyncManager
import com.visualmapper.companion.service.FlowExecutorService
import com.visualmapper.companion.ui.overlay.RecordingOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dashboard Fragment
 *
 * Shows:
 * - Connection status (MQTT, Server, Accessibility)
 * - Quick stats (flows, sensors, devices)
 * - Recent activity
 * - Quick actions
 */
class DashboardFragment : Fragment() {

    private val app by lazy { requireActivity().application as VisualMapperApp }
    // Use shared serverSyncManager from app (singleton)
    private val serverSync get() = app.serverSyncManager

    // Callback for after notification permission is handled
    private var pendingNotificationCallback: (() -> Unit)? = null

    // Permission launcher for POST_NOTIFICATIONS (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, run pending callback
            pendingNotificationCallback?.invoke()
        } else {
            // Permission denied, show dialog with option to continue anyway
            pendingNotificationCallback?.let { callback ->
                showNotificationPermissionDialog(callback)
            }
        }
        pendingNotificationCallback = null
    }

    // Views
    private lateinit var mqttStatusDot: View
    private lateinit var mqttStatusText: TextView
    private lateinit var serverStatusDot: View
    private lateinit var serverStatusText: TextView
    private lateinit var accessibilityStatusDot: View
    private lateinit var accessibilityStatusText: TextView
    private lateinit var flowsCount: TextView
    private lateinit var sensorsCount: TextView
    private lateinit var actionsCount: TextView
    private lateinit var devicesCount: TextView
    private lateinit var recentActivityList: RecyclerView
    private lateinit var emptyActivityText: TextView
    private lateinit var btnSyncFlows: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var btnStartRecording: Button
    private lateinit var btnSmartExplore: Button

    private lateinit var activityAdapter: RecentActivityAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
        loadStats()
        loadRecentActivity()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Don't close serverSync - it's a shared singleton in VisualMapperApp
    }

    private fun initViews(view: View) {
        // Connection status views
        mqttStatusDot = view.findViewById(R.id.mqttStatusDot)
        mqttStatusText = view.findViewById(R.id.mqttStatusText)
        serverStatusDot = view.findViewById(R.id.serverStatusDot)
        serverStatusText = view.findViewById(R.id.serverStatusText)
        accessibilityStatusDot = view.findViewById(R.id.accessibilityStatusDot)
        accessibilityStatusText = view.findViewById(R.id.accessibilityStatusText)

        // Stats views
        flowsCount = view.findViewById(R.id.flowsCount)
        sensorsCount = view.findViewById(R.id.sensorsCount)
        actionsCount = view.findViewById(R.id.actionsCount)
        devicesCount = view.findViewById(R.id.devicesCount)

        // Activity views
        recentActivityList = view.findViewById(R.id.recentActivityList)
        emptyActivityText = view.findViewById(R.id.emptyActivityText)

        // Setup activity list
        activityAdapter = RecentActivityAdapter()
        recentActivityList.layoutManager = LinearLayoutManager(requireContext())
        recentActivityList.adapter = activityAdapter

        // Action buttons
        btnSyncFlows = view.findViewById(R.id.btnSyncFlows)
        btnOpenSettings = view.findViewById(R.id.btnOpenSettings)
        btnStartRecording = view.findViewById(R.id.btnStartRecording)
        btnSmartExplore = view.findViewById(R.id.btnSmartExplore)
    }

    private fun setupListeners() {
        btnSyncFlows.setOnClickListener {
            syncFlows()
        }

        btnOpenSettings.setOnClickListener {
            // Navigate to settings tab
            (activity as? MainContainerActivity)?.navigateToSettings()
        }

        btnStartRecording.setOnClickListener {
            startRecordingOverlay()
        }

        btnSmartExplore.setOnClickListener {
            showAppPickerForExploration()
        }
    }

    private fun startRecordingOverlay() {
        // Check if accessibility service is running
        if (!VisualMapperAccessibilityService.isRunning()) {
            android.widget.Toast.makeText(
                requireContext(),
                "Please enable Accessibility Service first",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check overlay permission
        if (!RecordingOverlayService.canDrawOverlay(requireContext())) {
            android.widget.Toast.makeText(
                requireContext(),
                "Please grant overlay permission",
                android.widget.Toast.LENGTH_LONG
            ).show()
            RecordingOverlayService.requestOverlayPermission(requireContext())
            return
        }

        // Check/request notification permission (skippable on Android 13+)
        if (!areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ - request runtime permission
                requestNotificationPermission { doStartRecordingOverlay() }
            } else {
                // Older Android - show dialog to enable in settings
                showNotificationPermissionDialog { doStartRecordingOverlay() }
            }
            return
        }

        doStartRecordingOverlay()
    }

    private fun doStartRecordingOverlay() {
        // Start the overlay service
        val intent = Intent(requireContext(), RecordingOverlayService::class.java).apply {
            action = RecordingOverlayService.ACTION_SHOW
        }
        requireContext().startService(intent)

        android.widget.Toast.makeText(
            requireContext(),
            "Recording overlay started. Switch to the app you want to record.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun areNotificationsEnabled(): Boolean {
        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        // Also check if notifications are enabled in system settings
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(requireContext())
        return notificationManager.areNotificationsEnabled()
    }

    private fun requestNotificationPermission(onGrantedOrSkipped: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - need runtime permission
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingNotificationCallback = onGrantedOrSkipped
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // Permission already granted or not needed
        onGrantedOrSkipped()
    }

    private fun showNotificationPermissionDialog(onContinue: (() -> Unit)? = null) {
        AlertDialog.Builder(requireContext())
            .setTitle("Notifications Recommended")
            .setMessage("Notifications are recommended to show the Stop button and progress updates.\n\nWithout notifications, you won't be able to stop exploration/recording from the notification bar.\n\nWould you like to enable notifications?")
            .setPositiveButton("Open Settings") { _, _ ->
                openNotificationSettings()
            }
            .setNegativeButton("Skip") { _, _ ->
                onContinue?.invoke()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun openNotificationSettings() {
        val intent = Intent().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            } else {
                action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = android.net.Uri.parse("package:${requireContext().packageName}")
            }
        }
        startActivity(intent)
    }

    private fun showAppPickerForExploration() {
        // Check if accessibility service is running
        if (!VisualMapperAccessibilityService.isRunning()) {
            android.widget.Toast.makeText(
                requireContext(),
                "Please enable Accessibility Service first",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Check overlay permission (needed for progress overlay)
        if (!RecordingOverlayService.canDrawOverlay(requireContext())) {
            android.widget.Toast.makeText(
                requireContext(),
                "Please grant overlay permission for progress display",
                android.widget.Toast.LENGTH_LONG
            ).show()
            RecordingOverlayService.requestOverlayPermission(requireContext())
            return
        }

        // Check/request notification permission (skippable on Android 13+)
        if (!areNotificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ - request runtime permission
                requestNotificationPermission { doShowAppPicker() }
            } else {
                // Older Android - show dialog to enable in settings
                showNotificationPermissionDialog { doShowAppPicker() }
            }
            return
        }

        doShowAppPicker()
    }

    private fun doShowAppPicker() {
        // Get list of installed apps with launcher activities
        val pm = requireContext().packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != requireContext().packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        // Build list of app names and packages
        val appNames = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        val appPackages = apps.map { it.activityInfo.packageName }

        AlertDialog.Builder(requireContext())
            .setTitle("Select App to Explore")
            .setItems(appNames) { _, which ->
                val packageName = appPackages[which]
                showModeSelector(packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModeSelector(packageName: String) {
        // Get human-readable app name
        val pm = requireContext().packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast('.')
        }

        val modes = arrayOf(
            "Quick Pass - Fast screen discovery",
            "Normal - Balanced exploration",
            "Deep - Full element analysis",
            "Manual - Learn from your navigation"
        )
        val modeValues = arrayOf(
            ExplorationMode.QUICK,
            ExplorationMode.NORMAL,
            ExplorationMode.DEEP,
            ExplorationMode.MANUAL
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Explore: $appName")
            .setItems(modes) { _, which ->
                val mode = modeValues[which]
                startSmartExploration(packageName, mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSmartExploration(packageName: String, mode: ExplorationMode = ExplorationMode.NORMAL) {
        // Launch exploration result activity which will start the service
        val intent = Intent(requireContext(), ExplorationResultActivity::class.java).apply {
            putExtra(ExplorationResultActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(ExplorationResultActivity.EXTRA_START_EXPLORATION, true)
            putExtra(ExplorationResultActivity.EXTRA_EXPLORATION_MODE, mode.name)
        }
        startActivity(intent)
    }

    private fun observeState() {
        // Observe MQTT connection state
        viewLifecycleOwner.lifecycleScope.launch {
            app.mqttManager.connectionState.collectLatest { state ->
                updateMqttStatus(state)
            }
        }

        // Observe server connection state
        viewLifecycleOwner.lifecycleScope.launch {
            serverSync.connectionState.collectLatest { state ->
                updateServerStatus(state)
            }
        }

        // Observe synced flows
        viewLifecycleOwner.lifecycleScope.launch {
            serverSync.syncedFlows.collectLatest { flows ->
                flowsCount.text = flows.size.toString()
            }
        }
    }

    private fun updateAccessibilityStatus() {
        val isEnabled = VisualMapperAccessibilityService.isRunning()

        if (isEnabled) {
            accessibilityStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            accessibilityStatusText.text = "Enabled"
        } else {
            accessibilityStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
            accessibilityStatusText.text = "Disabled"
        }
    }

    private fun updateMqttStatus(state: MqttManager.ConnectionState) {
        when (state) {
            MqttManager.ConnectionState.CONNECTED -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                mqttStatusText.text = "Connected"
            }
            MqttManager.ConnectionState.CONNECTING -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                mqttStatusText.text = "Connecting..."
            }
            MqttManager.ConnectionState.ERROR -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                mqttStatusText.text = "Error"
            }
            MqttManager.ConnectionState.DISCONNECTED -> {
                mqttStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                mqttStatusText.text = "Disconnected"
            }
        }
    }

    private fun updateServerStatus(state: ServerSyncManager.ConnectionState) {
        when (state) {
            ServerSyncManager.ConnectionState.CONNECTED -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_green)
                serverStatusText.text = "Connected"
            }
            ServerSyncManager.ConnectionState.CONNECTING -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                serverStatusText.text = "Connecting..."
            }
            ServerSyncManager.ConnectionState.MQTT_ONLY -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_yellow)
                serverStatusText.text = "MQTT Only"
            }
            ServerSyncManager.ConnectionState.ERROR -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_red)
                serverStatusText.text = "Not Reachable"
            }
            ServerSyncManager.ConnectionState.DISCONNECTED -> {
                serverStatusDot.setBackgroundResource(R.drawable.status_dot_gray)
                serverStatusText.text = "Disconnected"
            }
        }
    }

    private fun syncFlows() {
        viewLifecycleOwner.lifecycleScope.launch {
            val prefs = requireContext().getSharedPreferences("visual_mapper", android.content.Context.MODE_PRIVATE)
            val serverUrl = prefs.getString("server_url", null)
            val deviceIp = prefs.getString("device_ip", null)

            if (serverUrl.isNullOrBlank()) {
                android.widget.Toast.makeText(requireContext(), "Please configure server URL in Settings", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }

            android.widget.Toast.makeText(requireContext(), "Syncing flows...", android.widget.Toast.LENGTH_SHORT).show()

            val adbDeviceId = if (!deviceIp.isNullOrBlank()) "$deviceIp:46747" else null
            val flows = serverSync.syncFlows(app.stableDeviceId, adbDeviceId)

            android.widget.Toast.makeText(requireContext(), "Synced ${flows?.size ?: 0} flows", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadStats() {
        // Load flow count from local cache
        val flows = FlowExecutorService.getAllFlows()
        flowsCount.text = flows.size.toString()

        // Load sensor, action and device counts from server
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (!serverUrl.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val counts = withContext(Dispatchers.IO) {
                        fetchCounts(serverUrl)
                    }
                    sensorsCount.text = counts.sensors.toString()
                    actionsCount.text = counts.actions.toString()
                    devicesCount.text = counts.devices.toString()
                } catch (e: Exception) {
                    // Keep current values on error
                }
            }
        }
    }

    private data class ServerCounts(
        val sensors: Int = 0,
        val actions: Int = 0,
        val devices: Int = 0
    )

    private suspend fun fetchCounts(serverUrl: String): ServerCounts {
        var sensorCount = 0
        var actionCount = 0
        var deviceCount = 0

        try {
            // Fetch sensors count
            val sensorsUrl = URL("$serverUrl/api/sensors")
            val sensorsConn = sensorsUrl.openConnection() as HttpURLConnection
            sensorsConn.connectTimeout = 5000
            sensorsConn.readTimeout = 5000
            if (sensorsConn.responseCode == 200) {
                val response = sensorsConn.inputStream.bufferedReader().readText()
                sensorCount = JSONArray(response).length()
            }
            sensorsConn.disconnect()
        } catch (e: Exception) { /* ignore */ }

        try {
            // Fetch actions count
            val actionsUrl = URL("$serverUrl/api/actions")
            val actionsConn = actionsUrl.openConnection() as HttpURLConnection
            actionsConn.connectTimeout = 5000
            actionsConn.readTimeout = 5000
            if (actionsConn.responseCode == 200) {
                val response = actionsConn.inputStream.bufferedReader().readText()
                actionCount = JSONArray(response).length()
            }
            actionsConn.disconnect()
        } catch (e: Exception) { /* ignore */ }

        try {
            // Fetch devices count (ADB connected devices)
            val devicesUrl = URL("$serverUrl/api/adb/devices")
            val devicesConn = devicesUrl.openConnection() as HttpURLConnection
            devicesConn.connectTimeout = 5000
            devicesConn.readTimeout = 5000
            if (devicesConn.responseCode == 200) {
                val response = devicesConn.inputStream.bufferedReader().readText()
                val jsonObj = org.json.JSONObject(response)
                deviceCount = jsonObj.optJSONArray("devices")?.length() ?: 0
            }
            devicesConn.disconnect()
        } catch (e: Exception) { /* ignore */ }

        return ServerCounts(sensorCount, actionCount, deviceCount)
    }

    private fun loadRecentActivity() {
        val prefs = requireContext().getSharedPreferences("visual_mapper", Context.MODE_PRIVATE)
        val serverUrl = prefs.getString("server_url", null)

        if (serverUrl.isNullOrBlank()) {
            showEmptyActivity()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val activities = withContext(Dispatchers.IO) {
                    fetchRecentActivity(serverUrl)
                }

                if (activities.isEmpty()) {
                    showEmptyActivity()
                } else {
                    recentActivityList.visibility = View.VISIBLE
                    emptyActivityText.visibility = View.GONE
                    activityAdapter.submitList(activities)
                }
            } catch (e: Exception) {
                showEmptyActivity()
            }
        }
    }

    private suspend fun fetchRecentActivity(serverUrl: String): List<ActivityItem> {
        val activities = mutableListOf<ActivityItem>()

        try {
            val url = URL("$serverUrl/api/flows/execution-history?limit=5")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                val jsonArray = JSONArray(response)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    activities.add(ActivityItem(
                        flowName = obj.optString("flow_name", obj.optString("flow_id", "Unknown")),
                        status = obj.optString("status", "unknown"),
                        triggeredBy = obj.optString("triggered_by", "unknown"),
                        timestamp = obj.optString("started_at", obj.optString("timestamp", ""))
                    ))
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            // Return empty list on error
        }

        return activities
    }

    private fun showEmptyActivity() {
        recentActivityList.visibility = View.GONE
        emptyActivityText.visibility = View.VISIBLE
    }

    // ==========================================================================
    // Recent Activity Adapter
    // ==========================================================================

    data class ActivityItem(
        val flowName: String,
        val status: String,
        val triggeredBy: String,
        val timestamp: String
    )

    inner class RecentActivityAdapter : RecyclerView.Adapter<RecentActivityAdapter.ViewHolder>() {

        private var items: List<ActivityItem> = emptyList()

        fun submitList(newItems: List<ActivityItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recent_activity, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val textFlowName: TextView = itemView.findViewById(R.id.textFlowName)
            private val textStatus: TextView = itemView.findViewById(R.id.textStatus)
            private val textTime: TextView = itemView.findViewById(R.id.textTime)

            fun bind(item: ActivityItem) {
                textFlowName.text = item.flowName
                textStatus.text = "${item.status} • ${item.triggeredBy}"

                // Format timestamp
                val displayTime = try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val date = sdf.parse(item.timestamp.substringBefore('.'))
                    val outSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                    outSdf.format(date!!)
                } catch (e: Exception) {
                    item.timestamp.takeLast(8)
                }
                textTime.text = displayTime

                // Status color
                textStatus.setTextColor(when (item.status) {
                    "completed" -> requireContext().getColor(R.color.success)
                    "failed" -> requireContext().getColor(R.color.error)
                    else -> requireContext().getColor(R.color.text_secondary)
                })
            }
        }
    }

    companion object {
        fun newInstance() = DashboardFragment()
    }
}

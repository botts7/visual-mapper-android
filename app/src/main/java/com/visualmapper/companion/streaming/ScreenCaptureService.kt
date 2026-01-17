package com.visualmapper.companion.streaming

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.visualmapper.companion.R
import com.visualmapper.companion.ui.fragments.MainContainerActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground service for MediaProjection-based screen capture and streaming.
 * Provides significantly lower latency than ADB-based capture.
 *
 * Target latency: 50-150ms (vs 100-3000ms for WiFi ADB)
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "screen_streaming"

        // Intent extras
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_QUALITY = "quality"

        // Actions
        const val ACTION_START = "com.visualmapper.companion.streaming.START"
        const val ACTION_STOP = "com.visualmapper.companion.streaming.STOP"

        // Singleton instance for status checking
        @Volatile
        private var instance: ScreenCaptureService? = null

        fun isRunning(): Boolean = instance != null

        fun getInstance(): ScreenCaptureService? = instance

        /**
         * Start the screen capture service.
         * Must have MediaProjection permission result before calling.
         */
        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            serverUrl: String,
            deviceId: String,
            quality: String = "fast"
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
                putExtra(EXTRA_QUALITY, quality)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the screen capture service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // State
    enum class StreamState {
        IDLE,
        STARTING,
        STREAMING,
        PAUSED,
        ERROR
    }

    private val _streamState = MutableStateFlow(StreamState.IDLE)
    val streamState: StateFlow<StreamState> = _streamState

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount

    // MediaProjection components
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // Threading
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // Streaming components
    private var wsClient: StreamingWebSocketClient? = null
    private var encoder: ScreenEncoder? = null
    private var streamJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Configuration
    private var quality = StreamQuality.FAST
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320
    private var lastOrientation = 0

    // Frame pacing
    private var lastFrameTime = 0L
    private var frameNumber = 0

    // Orientation change callback
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                checkOrientationChange()
            }
        }
    }

    // Wake lock
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Service created")

        createNotificationChannel()
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
                val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: ""
                val qualityStr = intent.getStringExtra(EXTRA_QUALITY) ?: "fast"

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection permission result")
                    stopSelf()
                    return START_NOT_STICKY
                }

                quality = StreamQuality.fromString(qualityStr)
                startCapture(resultCode, resultData, serverUrl, deviceId)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        stopCapture()
        instance = null
        super.onDestroy()
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        serverUrl: String,
        deviceId: String
    ) {
        Log.i(TAG, "Starting capture: server=$serverUrl, device=$deviceId, quality=${quality.name}")
        _streamState.value = StreamState.STARTING

        // Start foreground immediately
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Acquire wake lock
        acquireWakeLock()

        // Initialize MediaProjection
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection")
            _streamState.value = StreamState.ERROR
            stopSelf()
            return
        }

        // Register callback to handle projection stop
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                stopCapture()
            }
        }, null)

        // Initialize encoder
        encoder = ScreenEncoder(quality)

        // Initialize WebSocket client
        wsClient = StreamingWebSocketClient(serverUrl, deviceId)
        wsClient?.connect()

        // Start handler thread for image processing
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        // Create ImageReader
        val scaledWidth = if (quality.maxHeight > 0 && screenHeight > quality.maxHeight) {
            (screenWidth * quality.maxHeight / screenHeight)
        } else {
            screenWidth
        }
        val scaledHeight = if (quality.maxHeight > 0) {
            minOf(screenHeight, quality.maxHeight)
        } else {
            screenHeight
        }

        imageReader = ImageReader.newInstance(
            scaledWidth,
            scaledHeight,
            PixelFormat.RGBA_8888,
            2  // Max 2 images in buffer
        )

        // Create VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            scaledWidth,
            scaledHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        // Start streaming loop
        startStreamingLoop()

        // Register display listener for orientation changes
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, handler)
        lastOrientation = resources.configuration.orientation

        _streamState.value = StreamState.STREAMING
        updateNotification("Streaming at ${quality.targetFps} FPS")
        Log.i(TAG, "Capture started: ${scaledWidth}x${scaledHeight} at ${quality.targetFps} FPS")
    }

    private fun checkOrientationChange() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation != lastOrientation) {
            Log.i(TAG, "Orientation changed: $lastOrientation -> $currentOrientation")
            lastOrientation = currentOrientation

            // Update screen metrics
            getScreenMetrics()

            // Recreate virtual display with new dimensions
            recreateVirtualDisplay()
        }
    }

    private fun recreateVirtualDisplay() {
        Log.i(TAG, "Recreating virtual display for new orientation: ${screenWidth}x${screenHeight}")

        // Release old resources
        virtualDisplay?.release()
        imageReader?.close()

        // Calculate new scaled dimensions
        val scaledWidth = if (quality.maxHeight > 0 && screenHeight > quality.maxHeight) {
            (screenWidth * quality.maxHeight / screenHeight)
        } else {
            screenWidth
        }
        val scaledHeight = if (quality.maxHeight > 0) {
            minOf(screenHeight, quality.maxHeight)
        } else {
            screenHeight
        }

        // Create new ImageReader
        imageReader = ImageReader.newInstance(
            scaledWidth,
            scaledHeight,
            PixelFormat.RGBA_8888,
            2
        )

        // Create new VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            scaledWidth,
            scaledHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            handler
        )

        Log.i(TAG, "Virtual display recreated: ${scaledWidth}x${scaledHeight}")
    }

    private fun startStreamingLoop() {
        streamJob = scope.launch {
            var lastCaptureTime = System.currentTimeMillis()

            while (isActive && _streamState.value == StreamState.STREAMING) {
                try {
                    // Get current battery state for adaptive FPS
                    val (batteryPercent, isCharging) = getBatteryState()
                    val frameDelay = if (StreamingConfig.loadFromPrefs(
                            this@ScreenCaptureService, "", ""
                        ).adaptiveFps) {
                        AdaptiveFps.getFrameDelayMs(batteryPercent, isCharging, quality)
                    } else {
                        quality.frameDelayMs
                    }

                    // Wait for next frame slot
                    val elapsed = System.currentTimeMillis() - lastCaptureTime
                    if (elapsed < frameDelay) {
                        delay(frameDelay - elapsed)
                    }
                    lastCaptureTime = System.currentTimeMillis()

                    // Capture frame
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        try {
                            val bitmap = imageToBitmap(image)
                            if (bitmap != null) {
                                // Encode with header
                                val captureTimeMs = System.currentTimeMillis()
                                val frameData = encoder?.encodeWithHeader(
                                    bitmap,
                                    ++frameNumber,
                                    captureTimeMs
                                )

                                if (frameData != null) {
                                    wsClient?.sendFrame(frameData)
                                    _frameCount.value = frameNumber

                                    // Log periodically
                                    if (frameNumber == 1 || frameNumber % 60 == 0) {
                                        val stats = wsClient?.getStats()
                                        Log.d(TAG, "Frame $frameNumber sent, ${frameData.size} bytes, FPS: ${stats?.fps?.let { "%.1f".format(it) } ?: "?"}")
                                    }
                                }

                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                            }
                        } finally {
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Streaming loop error: ${e.message}", e)
                    delay(100)  // Brief pause on error
                }
            }
        }
    }

    private fun stopCapture() {
        Log.i(TAG, "Stopping capture")
        _streamState.value = StreamState.IDLE

        // Unregister display listener
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister display listener: ${e.message}")
        }

        // Cancel streaming job
        streamJob?.cancel()
        streamJob = null

        // Stop WebSocket
        wsClient?.disconnect()
        wsClient?.destroy()
        wsClient = null

        // Release MediaProjection
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        // Stop handler thread
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        // Release wake lock
        releaseWakeLock()

        // Clear frame count
        frameNumber = 0
        _frameCount.value = 0

        scope.cancel()
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // Create bitmap with padding
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to actual dimensions if there's padding
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap: ${e.message}")
            null
        }
    }

    private fun getBatteryState(): Pair<Int, Boolean> {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 50
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair((level * 100 / scale), isCharging)
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        Log.d(TAG, "Screen metrics: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VisualMapper::ScreenStreaming"
        )
        wakeLock?.acquire(4 * 60 * 60 * 1000L)  // 4 hour max
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when screen is being streamed"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainContainerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Streaming")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    /**
     * Update streaming quality dynamically.
     */
    fun setQuality(newQuality: StreamQuality) {
        if (newQuality == quality) return
        quality = newQuality
        encoder?.setQuality(newQuality)
        updateNotification("Streaming at ${newQuality.targetFps} FPS")
        Log.i(TAG, "Quality changed to ${newQuality.name}")
    }

    /**
     * Get current streaming statistics.
     */
    fun getStats(): Map<String, Any> {
        val wsStats = wsClient?.getStats()
        return mapOf(
            "state" to _streamState.value.name,
            "frameCount" to frameNumber,
            "connected" to (wsStats?.connected ?: false),
            "framesSent" to (wsStats?.framesSent ?: 0),
            "bytesTotal" to (wsStats?.bytesTotal ?: 0L),
            "fps" to (wsStats?.fps ?: 0.0),
            "quality" to quality.name
        )
    }
}

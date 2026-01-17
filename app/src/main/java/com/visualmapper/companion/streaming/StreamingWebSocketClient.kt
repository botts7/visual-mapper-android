package com.visualmapper.companion.streaming

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for streaming screen captures to the backend server.
 * Uses binary MJPEG protocol for efficient frame transmission.
 */
class StreamingWebSocketClient(
    private val serverUrl: String,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "StreamingWSClient"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val PING_INTERVAL_MS = 30000L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = RECONNECT_DELAY_MS

    // Frame sending channel for backpressure handling
    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
    private var sendJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Stats
    private var framesSent = 0
    private var bytesTotal = 0L
    private var connectTime = 0L

    /**
     * Connect to the backend WebSocket endpoint.
     */
    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTED ||
            _connectionState.value == ConnectionState.CONNECTING) {
            Log.d(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        Log.i(TAG, "Connecting to $serverUrl for device $deviceId")

        // Build WebSocket URL
        val wsUrl = buildWebSocketUrl()
        Log.d(TAG, "WebSocket URL: $wsUrl")

        // Create OkHttp client with appropriate timeouts
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // No read timeout for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED
                _lastError.value = null
                reconnectDelay = RECONNECT_DELAY_MS
                connectTime = System.currentTimeMillis()
                framesSent = 0
                bytesTotal = 0L

                // Start frame sender coroutine
                startFrameSender()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: $text")
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message: ${bytes.size} bytes")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                stopFrameSender()

                // Don't auto-reconnect if closed normally
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR
                _lastError.value = t.message ?: "Connection failed"
                stopFrameSender()
                scheduleReconnect()
            }
        })
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting")
        reconnectJob?.cancel()
        reconnectJob = null
        stopFrameSender()

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        client?.dispatcher?.executorService?.shutdown()
        client = null

        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Send a frame to the server.
     * Uses a conflated channel to drop old frames if sending is slow.
     *
     * @param frameData Binary frame data (8-byte header + JPEG)
     */
    fun sendFrame(frameData: ByteArray) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return
        }

        // Offer to channel (non-blocking, drops if full)
        frameChannel.trySend(frameData)
    }

    /**
     * Get streaming statistics.
     */
    fun getStats(): StreamStats {
        val uptime = if (connectTime > 0) System.currentTimeMillis() - connectTime else 0L
        val fps = if (uptime > 1000) (framesSent * 1000.0 / uptime) else 0.0

        return StreamStats(
            connected = _connectionState.value == ConnectionState.CONNECTED,
            framesSent = framesSent,
            bytesTotal = bytesTotal,
            uptimeMs = uptime,
            fps = fps
        )
    }

    private fun buildWebSocketUrl(): String {
        // Convert HTTP URL to WebSocket URL
        val baseUrl = serverUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            .trimEnd('/')

        return "$baseUrl/ws/companion-stream/$deviceId"
    }

    private fun startFrameSender() {
        sendJob = scope.launch {
            for (frame in frameChannel) {
                try {
                    val sent = webSocket?.send(frame.toByteString()) ?: false
                    if (sent) {
                        framesSent++
                        bytesTotal += frame.size

                        // Log periodically
                        if (framesSent == 1 || framesSent % 60 == 0) {
                            Log.d(TAG, "Sent frame $framesSent (${frame.size} bytes)")
                        }
                    } else {
                        Log.w(TAG, "Failed to send frame (buffer full?)")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending frame: ${e.message}")
                }
            }
        }
    }

    private fun stopFrameSender() {
        sendJob?.cancel()
        sendJob = null
    }

    private fun handleTextMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "quality" -> {
                    val quality = json.optString("quality", "medium")
                    Log.i(TAG, "Server requested quality change: $quality")
                    // Notify listeners (handled by ScreenCaptureService)
                }
                "pause" -> {
                    Log.i(TAG, "Server requested pause")
                }
                "resume" -> {
                    Log.i(TAG, "Server requested resume")
                }
                "config" -> {
                    Log.i(TAG, "Received config from server")
                }
                "keepalive" -> {
                    // Keepalive, no action needed
                }
                else -> {
                    Log.d(TAG, "Unknown message type: ${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse server message: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) {
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING

        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            delay(reconnectDelay)

            // Exponential backoff
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_DELAY_MS)

            connect()
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        disconnect()
        scope.cancel()
        frameChannel.close()
    }

    data class StreamStats(
        val connected: Boolean,
        val framesSent: Int,
        val bytesTotal: Long,
        val uptimeMs: Long,
        val fps: Double
    )
}

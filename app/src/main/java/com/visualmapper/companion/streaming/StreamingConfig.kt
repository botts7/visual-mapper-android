package com.visualmapper.companion.streaming

import android.content.Context

/**
 * Configuration for screen streaming.
 * Manages quality settings, frame rates, and battery optimization.
 */
data class StreamingConfig(
    val serverUrl: String,
    val deviceId: String,
    val quality: StreamQuality = StreamQuality.MEDIUM,
    val adaptiveFps: Boolean = true,
    val maxWidth: Int = 720,
    val maxHeight: Int = 1280
) {
    companion object {
        private const val PREFS_NAME = "streaming_config"
        private const val KEY_ENABLED = "streaming_enabled"
        private const val KEY_QUALITY = "streaming_quality"
        private const val KEY_ADAPTIVE_FPS = "adaptive_fps"

        fun loadFromPrefs(context: Context, serverUrl: String, deviceId: String): StreamingConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return StreamingConfig(
                serverUrl = serverUrl,
                deviceId = deviceId,
                quality = StreamQuality.fromString(prefs.getString(KEY_QUALITY, "medium") ?: "medium"),
                adaptiveFps = prefs.getBoolean(KEY_ADAPTIVE_FPS, true)
            )
        }

        fun isStreamingEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ENABLED, false)
        }

        fun setStreamingEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun saveQuality(context: Context, quality: StreamQuality) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_QUALITY, quality.name.lowercase()).apply()
        }
    }
}

/**
 * Stream quality presets matching backend QUALITY_PRESETS.
 */
enum class StreamQuality(
    val maxHeight: Int,
    val jpegQuality: Int,
    val targetFps: Int,
    val frameDelayMs: Long
) {
    HIGH(0, 85, 5, 150),           // Native resolution, ~5 FPS
    MEDIUM(720, 75, 12, 80),       // 720p, ~12 FPS
    LOW(480, 65, 18, 50),          // 480p, ~18 FPS
    FAST(360, 55, 25, 40),         // 360p, ~25 FPS
    ULTRAFAST(240, 45, 30, 30);    // 240p, ~30 FPS

    companion object {
        fun fromString(value: String): StreamQuality {
            return when (value.lowercase()) {
                "high" -> HIGH
                "medium" -> MEDIUM
                "low" -> LOW
                "fast" -> FAST
                "ultrafast" -> ULTRAFAST
                else -> MEDIUM
            }
        }
    }
}

/**
 * Battery-aware FPS adjustment.
 * Returns target FPS based on battery level and charging state.
 */
object AdaptiveFps {
    fun getTargetFps(batteryPercent: Int, isCharging: Boolean, baseQuality: StreamQuality): Int {
        return when {
            isCharging -> 25  // Max FPS when charging
            batteryPercent > 50 -> minOf(baseQuality.targetFps, 20)
            batteryPercent > 20 -> minOf(baseQuality.targetFps, 12)
            else -> 5  // Power saving mode
        }
    }

    fun getFrameDelayMs(batteryPercent: Int, isCharging: Boolean, baseQuality: StreamQuality): Long {
        val fps = getTargetFps(batteryPercent, isCharging, baseQuality)
        return (1000L / fps)
    }
}

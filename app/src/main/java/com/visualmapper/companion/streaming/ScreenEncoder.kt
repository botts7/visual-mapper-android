package com.visualmapper.companion.streaming

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Encodes screen captures to JPEG format for streaming.
 * Handles resolution scaling and quality adjustments.
 */
class ScreenEncoder(
    private val quality: StreamQuality = StreamQuality.MEDIUM
) {
    companion object {
        private const val TAG = "ScreenEncoder"
    }

    private var currentQuality = quality
    private val outputStream = ByteArrayOutputStream(256 * 1024) // 256KB initial buffer

    /**
     * Update encoding quality dynamically.
     */
    fun setQuality(quality: StreamQuality) {
        currentQuality = quality
        Log.d(TAG, "Quality updated to ${quality.name}")
    }

    /**
     * Encode a Bitmap to JPEG bytes with optional scaling.
     *
     * @param bitmap The source bitmap from screen capture
     * @return JPEG encoded bytes, or null on failure
     */
    fun encode(bitmap: Bitmap): ByteArray? {
        return try {
            // Scale if needed
            val scaledBitmap = scaleIfNeeded(bitmap)

            // Encode to JPEG
            outputStream.reset()
            val success = scaledBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                currentQuality.jpegQuality,
                outputStream
            )

            // Clean up scaled bitmap if it was created
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }

            if (success) {
                outputStream.toByteArray()
            } else {
                Log.e(TAG, "JPEG compression failed")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Encoding failed: ${e.message}", e)
            null
        }
    }

    /**
     * Encode a Bitmap to JPEG with frame header for MJPEG protocol v2.
     * Header format (12 bytes):
     *   - 4 bytes: frame_number (big-endian int)
     *   - 4 bytes: capture_time_ms (big-endian int)
     *   - 2 bytes: width (big-endian unsigned short)
     *   - 2 bytes: height (big-endian unsigned short)
     *
     * Width/height allows backend to detect orientation changes.
     *
     * @param bitmap The source bitmap
     * @param frameNumber Sequential frame number
     * @param captureTimeMs Timestamp when frame was captured
     * @return Frame data with header + JPEG bytes, or null on failure
     */
    fun encodeWithHeader(bitmap: Bitmap, frameNumber: Int, captureTimeMs: Long): ByteArray? {
        // Scale if needed (to get actual encoded dimensions)
        val scaledBitmap = scaleIfNeeded(bitmap)
        val encodedWidth = scaledBitmap.width
        val encodedHeight = scaledBitmap.height

        // Encode to JPEG
        outputStream.reset()
        val success = scaledBitmap.compress(
            Bitmap.CompressFormat.JPEG,
            currentQuality.jpegQuality,
            outputStream
        )

        // Clean up scaled bitmap if it was created
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }

        if (!success) {
            Log.e(TAG, "JPEG compression failed")
            return null
        }

        val jpegBytes = outputStream.toByteArray()

        // Create header: frame_number (4) + capture_time (4) + width (2) + height (2) = 12 bytes
        val header = ByteBuffer.allocate(12)
        header.putInt(frameNumber)
        header.putInt((captureTimeMs % Int.MAX_VALUE).toInt())
        header.putShort(encodedWidth.toShort())
        header.putShort(encodedHeight.toShort())

        // Combine header + JPEG
        val result = ByteArray(12 + jpegBytes.size)
        System.arraycopy(header.array(), 0, result, 0, 12)
        System.arraycopy(jpegBytes, 0, result, 12, jpegBytes.size)

        return result
    }

    /**
     * Scale bitmap to target resolution if needed.
     */
    private fun scaleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxHeight = currentQuality.maxHeight
        if (maxHeight <= 0 || bitmap.height <= maxHeight) {
            return bitmap
        }

        val scale = maxHeight.toFloat() / bitmap.height
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = maxHeight

        return try {
            val matrix = Matrix()
            matrix.postScale(scale, scale)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during scaling, using original bitmap")
            bitmap
        }
    }

    /**
     * Get estimated frame size for the current quality setting.
     */
    fun getEstimatedFrameSize(): Int {
        return when (currentQuality) {
            StreamQuality.HIGH -> 150_000      // ~150KB
            StreamQuality.MEDIUM -> 80_000     // ~80KB
            StreamQuality.LOW -> 40_000        // ~40KB
            StreamQuality.FAST -> 25_000       // ~25KB
            StreamQuality.ULTRAFAST -> 15_000  // ~15KB
        }
    }
}

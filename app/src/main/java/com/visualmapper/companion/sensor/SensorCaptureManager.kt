package com.visualmapper.companion.sensor

import android.util.Log
import com.visualmapper.companion.accessibility.VisualMapperAccessibilityService
import com.visualmapper.companion.accessibility.UIElement as AccessibilityUIElement
import com.visualmapper.companion.models.SensorCaptureResult
import com.visualmapper.companion.models.SensorDefinition
import com.visualmapper.companion.models.SensorElementBounds
import com.visualmapper.companion.models.UIElement as SensorUIElement
import com.visualmapper.companion.mqtt.MqttManager

/**
 * Sensor Capture Manager
 *
 * Orchestrates sensor value capture on Android:
 * 1. Gets UI tree from Accessibility Service
 * 2. Uses SmartElementFinder to locate target elements
 * 3. Uses TextExtractor to extract values
 * 4. Publishes to MQTT via MqttManager
 *
 * Supports partial success - captures as many sensors as possible
 * and returns detailed results for each.
 */
class SensorCaptureManager(
    private val accessibilityService: VisualMapperAccessibilityService?,
    private val mqttManager: MqttManager?
) {
    companion object {
        private const val TAG = "SensorCaptureManager"
    }

    private val textExtractor = TextExtractor()
    private val elementFinder = SmartElementFinder()

    /**
     * Capture values for a list of sensors.
     *
     * @param sensors List of sensor definitions to capture
     * @param deviceId Device ID for MQTT publishing
     * @return List of capture results (one per sensor)
     */
    fun captureSensors(sensors: List<SensorDefinition>?, deviceId: String): List<SensorCaptureResult> {
        if (sensors.isNullOrEmpty()) {
            Log.d(TAG, "No sensors to capture")
            return emptyList()
        }

        if (accessibilityService == null) {
            Log.e(TAG, "Accessibility service not available")
            return sensors.map { sensor ->
                SensorCaptureResult.failure(sensor.sensorId, "Accessibility service not available")
            }
        }

        Log.i(TAG, "Capturing ${sensors.size} sensors for device $deviceId")

        // Get current UI tree once (more efficient than per-sensor)
        val accessibilityElements = accessibilityService.getUITree()
        if (accessibilityElements.isEmpty()) {
            Log.w(TAG, "No UI elements available")
            return sensors.map { sensor ->
                SensorCaptureResult.failure(sensor.sensorId, "No UI elements available")
            }
        }

        // Convert accessibility elements to sensor element format
        val sensorElements = convertToSensorElements(accessibilityElements)
        Log.d(TAG, "Found ${sensorElements.size} elements in UI tree")

        // Capture each sensor
        val results = mutableListOf<SensorCaptureResult>()
        var successCount = 0

        for (sensor in sensors) {
            val result = captureSingleSensor(sensor, sensorElements, deviceId)
            results.add(result)

            if (result.success) {
                successCount++
                // Publish to MQTT immediately on success
                publishSensorValue(sensor, result.value!!, deviceId)
            } else {
                Log.w(TAG, "Failed to capture ${sensor.sensorId}: ${result.errorMessage}")
            }
        }

        Log.i(TAG, "Captured $successCount/${sensors.size} sensors successfully")
        return results
    }

    /**
     * Capture a single sensor value.
     */
    fun captureSingleSensor(
        sensor: SensorDefinition,
        elements: List<SensorUIElement>,
        deviceId: String
    ): SensorCaptureResult {
        Log.d(TAG, "Capturing sensor: ${sensor.sensorId}")

        // Find the target element
        val match = elementFinder.findElement(elements, sensor.source)

        if (!match.found || match.element == null) {
            return SensorCaptureResult.failure(
                sensorId = sensor.sensorId,
                errorMessage = "Element not found: ${match.message}"
            )
        }

        // Get raw text from element
        val rawText = match.element.getDisplayText()
        if (rawText.isNullOrBlank()) {
            return SensorCaptureResult.failure(
                sensorId = sensor.sensorId,
                errorMessage = "Element has no text content"
            )
        }

        // Apply extraction rule
        val extractedValue = textExtractor.extract(rawText, sensor.extractionRule)
        if (extractedValue.isNullOrBlank()) {
            return SensorCaptureResult.failure(
                sensorId = sensor.sensorId,
                errorMessage = "Extraction failed. Raw text: '$rawText'"
            )
        }

        Log.d(TAG, "Captured ${sensor.sensorId}: '$extractedValue' (raw: '$rawText', method: ${match.method})")

        return SensorCaptureResult.success(
            sensorId = sensor.sensorId,
            value = extractedValue,
            rawText = rawText,
            confidence = match.confidence,
            method = match.method
        )
    }

    /**
     * Publish sensor value to MQTT.
     */
    private fun publishSensorValue(sensor: SensorDefinition, value: String, deviceId: String) {
        if (mqttManager == null) {
            Log.w(TAG, "MQTT manager not available, skipping publish for ${sensor.sensorId}")
            return
        }

        // Publish Home Assistant discovery first
        mqttManager.publishDiscovery(
            sensorId = sensor.sensorId,
            name = sensor.name,
            deviceClass = sensor.deviceClass,
            unit = sensor.unitOfMeasurement
        )

        // Publish the value
        mqttManager.publishSensorValue(sensor.sensorId, value)

        Log.d(TAG, "Published ${sensor.sensorId} = '$value' via MQTT")
    }

    /**
     * Convert accessibility UIElements to sensor UIElements.
     * This bridges the two element formats.
     */
    private fun convertToSensorElements(accessibilityElements: List<AccessibilityUIElement>): List<SensorUIElement> {
        return accessibilityElements.map { element ->
            SensorUIElement(
                text = element.text.takeIf { it.isNotBlank() && it != "[PROTECTED]" },
                contentDescription = element.contentDescription.takeIf { it.isNotBlank() },
                className = element.className.takeIf { it.isNotBlank() },
                resourceId = element.resourceId.takeIf { it.isNotBlank() },
                bounds = SensorElementBounds(
                    x = element.bounds.x,
                    y = element.bounds.y,
                    width = element.bounds.width,
                    height = element.bounds.height
                ),
                isClickable = element.isClickable,
                isEnabled = element.isEnabled,
                children = emptyList() // Accessibility tree is already flat
            )
        }
    }

    /**
     * Get count of successful captures from results.
     */
    fun countSuccessful(results: List<SensorCaptureResult>): Int {
        return results.count { it.success }
    }

    /**
     * Get count of failed captures from results.
     */
    fun countFailed(results: List<SensorCaptureResult>): Int {
        return results.count { !it.success }
    }

    /**
     * Check if any captures were successful.
     */
    fun hasAnySuccess(results: List<SensorCaptureResult>): Boolean {
        return results.any { it.success }
    }

    /**
     * Get all successful capture values as a map.
     */
    fun getSuccessfulValues(results: List<SensorCaptureResult>): Map<String, String> {
        return results
            .filter { it.success && it.value != null }
            .associate { it.sensorId to it.value!! }
    }

    /**
     * Generate a summary of capture results.
     */
    fun getSummary(results: List<SensorCaptureResult>): String {
        val success = countSuccessful(results)
        val failed = countFailed(results)
        val total = results.size

        return buildString {
            append("Captured $success/$total sensors")
            if (failed > 0) {
                append(" ($failed failed)")
            }
        }
    }
}

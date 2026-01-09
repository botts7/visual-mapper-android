package com.visualmapper.companion.explorer.ml

import android.content.Context
import android.os.Build
import android.util.Log
import com.visualmapper.companion.explorer.ClickableElement
import com.visualmapper.companion.explorer.ExploredScreen
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLiteQNetwork - TensorFlow Lite inference engine for Q-value prediction
 *
 * Features:
 * - Loads TFLite models from file or assets
 * - Predicts Q-values for state-action pairs
 * - Supports GPU acceleration when available
 * - Hot-reload of updated models from MQTT
 *
 * Network Architecture (expected):
 * - Input: [batch, 24] (16 state features + 8 action features)
 * - Hidden: Dense layers with ReLU
 * - Output: [batch, 1] (Q-value)
 */
class TFLiteQNetwork(private val context: Context) {

    companion object {
        private const val TAG = "TFLiteQNetwork"
        const val INPUT_DIM = StateEncoder.FEATURE_DIM + ActionEncoder.ACTION_DIM  // 16 + 8 = 24
        const val MODEL_FILENAME = "q_network.tflite"
        const val BOOTSTRAP_MODEL_FILENAME = "bootstrap_model.tflite"
        const val ASSETS_MODEL_PATH = "models/q_network_bootstrap.tflite"

        // Model download URL (for bootstrap model)
        const val BOOTSTRAP_MODEL_URL = "https://github.com/your-repo/visual-mapper-models/releases/latest/download/bootstrap_model.tflite"

        // Singleton for easy access
        @Volatile
        private var instance: TFLiteQNetwork? = null

        fun getInstance(context: Context): TFLiteQNetwork {
            return instance ?: synchronized(this) {
                instance ?: TFLiteQNetwork(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Model source for tracking where current model came from
     */
    enum class ModelSource {
        NONE,           // No model loaded
        ASSETS,         // Bundled with APK
        BOOTSTRAP,      // Downloaded bootstrap model
        TRAINED         // User-trained model from MQTT
    }

    var modelSource: ModelSource = ModelSource.NONE
        private set

    /**
     * Acceleration type being used
     */
    enum class AccelerationType {
        CPU,        // CPU only (fallback)
        GPU,        // GPU delegate
        NNAPI,      // Neural Network API (NPU/DSP)
        UNKNOWN
    }

    var accelerationType: AccelerationType = AccelerationType.UNKNOWN
        private set

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var isModelLoaded = false
    private var modelVersion: String = ""

    // Performance tracking
    private var inferenceCount: Long = 0
    private var totalInferenceTimeMs: Long = 0
    private var lastInferenceTimeMs: Long = 0

    private val stateEncoder = StateEncoder()
    private val actionEncoder = ActionEncoder()

    // Model directory
    private val modelDir: File by lazy {
        File(context.filesDir, "models").apply { mkdirs() }
    }

    /**
     * Check if model is loaded and ready
     */
    fun isReady(): Boolean = isModelLoaded && interpreter != null

    /**
     * Get current model version
     */
    fun getModelVersion(): String = modelVersion

    /**
     * Auto-load the best available model
     * Priority: Trained > Bootstrap > Assets
     */
    fun loadBestAvailableModel(): Boolean {
        Log.i(TAG, "Looking for best available model...")

        // 1. Try user-trained model first
        val trainedModel = File(modelDir, MODEL_FILENAME)
        if (trainedModel.exists()) {
            Log.i(TAG, "Found trained model")
            if (loadModelInternal(trainedModel, ModelSource.TRAINED)) {
                return true
            }
        }

        // 2. Try downloaded bootstrap model
        val bootstrapModel = File(modelDir, BOOTSTRAP_MODEL_FILENAME)
        if (bootstrapModel.exists()) {
            Log.i(TAG, "Found bootstrap model")
            if (loadModelInternal(bootstrapModel, ModelSource.BOOTSTRAP)) {
                return true
            }
        }

        // 3. Try bundled assets model
        if (loadFromAssets()) {
            return true
        }

        Log.w(TAG, "No model available - will use Q-table only")
        return false
    }

    /**
     * Load model from assets (bundled with APK)
     */
    fun loadFromAssets(): Boolean {
        try {
            val assetManager = context.assets
            val assetList = assetManager.list("models") ?: emptyArray()

            if (!assetList.contains("q_network_bootstrap.tflite")) {
                Log.d(TAG, "No bootstrap model in assets")
                return false
            }

            close()

            // Load from assets into ByteBuffer
            val inputStream = assetManager.open(ASSETS_MODEL_PATH)
            val modelBytes = inputStream.readBytes()
            inputStream.close()

            // Save to temp file for memory-mapped loading
            val tempFile = File(context.cacheDir, "temp_model.tflite")
            tempFile.writeBytes(modelBytes)

            // Load with acceleration
            val modelBuffer = loadModelFile(tempFile)
            val (interp, accelType) = tryLoadWithAcceleration(modelBuffer)

            // Clean up temp file
            tempFile.delete()

            if (interp == null) {
                Log.w(TAG, "Failed to load assets model with any acceleration")
                return false
            }

            interpreter = interp
            accelerationType = accelType
            modelSource = ModelSource.ASSETS
            modelVersion = "bundled"
            isModelLoaded = true

            Log.i(TAG, "=== Loaded model from assets ===")
            Log.i(TAG, "  Acceleration: $accelerationType")
            logModelDetails()
            return true

        } catch (e: Exception) {
            Log.d(TAG, "Could not load from assets: ${e.message}")
            return false
        }
    }

    /**
     * Load model from internal storage
     */
    fun loadModel(modelPath: String? = null): Boolean {
        val file = if (modelPath != null) {
            File(modelPath)
        } else {
            File(modelDir, MODEL_FILENAME)
        }
        return loadModelInternal(file, ModelSource.TRAINED)
    }

    /**
     * Internal model loading implementation
     * Tries acceleration in order: NNAPI (NPU/DSP) -> GPU -> CPU
     */
    private fun loadModelInternal(file: File, source: ModelSource): Boolean {
        try {
            if (!file.exists()) {
                Log.w(TAG, "Model file not found: ${file.absolutePath}")
                return false
            }

            // Close existing interpreter
            close()

            // Load model buffer
            val modelBuffer = loadModelFile(file)

            // Try acceleration methods in priority order
            val (interp, accelType) = tryLoadWithAcceleration(modelBuffer)

            if (interp == null) {
                Log.e(TAG, "Failed to create interpreter with any acceleration method")
                return false
            }

            interpreter = interp
            accelerationType = accelType

            // Set model source and version
            modelSource = source
            modelVersion = file.lastModified().toString()

            isModelLoaded = true
            Log.i(TAG, "=== Model loaded successfully ===")
            Log.i(TAG, "  File: ${file.name}")
            Log.i(TAG, "  Source: $source")
            Log.i(TAG, "  Acceleration: $accelerationType")

            // Log model input/output details
            logModelDetails()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            isModelLoaded = false
            modelSource = ModelSource.NONE
            accelerationType = AccelerationType.UNKNOWN
            return false
        }
    }

    /**
     * Try to load model with best available acceleration
     * Priority: NNAPI (NPU/DSP) -> GPU -> CPU
     */
    private fun tryLoadWithAcceleration(modelBuffer: MappedByteBuffer): Pair<Interpreter?, AccelerationType> {
        // 1. Try NNAPI first (for NPU/DSP acceleration on supported devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                Log.d(TAG, "Trying NNAPI delegate (NPU/DSP)...")
                val nnapiOptions = NnApiDelegate.Options().apply {
                    // Use sustained speed for consistent performance
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                    // Allow FP16 for faster inference
                    setAllowFp16(true)
                }
                nnapiDelegate = NnApiDelegate(nnapiOptions)

                val options = Interpreter.Options().apply {
                    addDelegate(nnapiDelegate)
                    setNumThreads(4)
                }

                val interp = Interpreter(modelBuffer.duplicate(), options)

                // Test inference to verify it works
                if (testInference(interp)) {
                    Log.i(TAG, "NNAPI delegate enabled (NPU/DSP acceleration)")
                    return Pair(interp, AccelerationType.NNAPI)
                } else {
                    interp.close()
                    nnapiDelegate?.close()
                    nnapiDelegate = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI delegate failed: ${e.message}")
                nnapiDelegate?.close()
                nnapiDelegate = null
            }
        }

        // 2. Try GPU acceleration
        try {
            Log.d(TAG, "Trying GPU delegate...")
            val compatList = CompatibilityList()

            if (compatList.isDelegateSupportedOnThisDevice) {
                val gpuOptions = GpuDelegate.Options().apply {
                    // Use default precision for best performance
                    setPrecisionLossAllowed(true)
                }
                gpuDelegate = GpuDelegate(gpuOptions)

                val options = Interpreter.Options().apply {
                    addDelegate(gpuDelegate)
                    setNumThreads(4)
                }

                val interp = Interpreter(modelBuffer.duplicate(), options)

                // Test inference to verify it works
                if (testInference(interp)) {
                    Log.i(TAG, "GPU delegate enabled")
                    return Pair(interp, AccelerationType.GPU)
                } else {
                    interp.close()
                    gpuDelegate?.close()
                    gpuDelegate = null
                }
            } else {
                Log.d(TAG, "GPU delegate not supported on this device")
            }
        } catch (e: Exception) {
            Log.w(TAG, "GPU delegate failed: ${e.message}")
            gpuDelegate?.close()
            gpuDelegate = null
        }

        // 3. Fall back to CPU
        try {
            Log.d(TAG, "Falling back to CPU...")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                // Enable XNNPACK for optimized CPU inference
                setUseXNNPACK(true)
            }

            val interp = Interpreter(modelBuffer.duplicate(), options)
            Log.i(TAG, "CPU inference enabled (with XNNPACK optimization)")
            return Pair(interp, AccelerationType.CPU)
        } catch (e: Exception) {
            Log.e(TAG, "CPU inference failed: ${e.message}")
            return Pair(null, AccelerationType.UNKNOWN)
        }
    }

    /**
     * Test inference to verify the model works with the current delegate
     */
    private fun testInference(interp: Interpreter): Boolean {
        return try {
            // Create dummy input
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_DIM * 4).apply {
                order(ByteOrder.nativeOrder())
                for (i in 0 until INPUT_DIM) {
                    putFloat(0f)
                }
                rewind()
            }

            val outputBuffer = ByteBuffer.allocateDirect(4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Run test inference
            interp.run(inputBuffer, outputBuffer)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Test inference failed: ${e.message}")
            false
        }
    }

    /**
     * Load model from bytes (for MQTT updates)
     */
    fun loadModelFromBytes(modelBytes: ByteArray, version: String): Boolean {
        try {
            // Save to file first
            val file = File(modelDir, MODEL_FILENAME)
            file.writeBytes(modelBytes)
            Log.i(TAG, "Saved model to: ${file.absolutePath} (${modelBytes.size} bytes)")

            // Load the saved model
            val success = loadModel(file.absolutePath)
            if (success) {
                modelVersion = version
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model from bytes", e)
            return false
        }
    }

    /**
     * Predict Q-value for a state-action pair
     */
    fun predictQValue(screen: ExploredScreen, element: ClickableElement): Float {
        if (!isReady()) {
            Log.w(TAG, "Model not ready, returning 0")
            return 0f
        }

        try {
            val startTime = System.currentTimeMillis()

            // Encode state and action
            val stateFeatures = stateEncoder.encode(screen)
            val actionFeatures = actionEncoder.encode(element)

            // Concatenate features
            val inputFeatures = stateFeatures + actionFeatures

            // Verify input size
            if (inputFeatures.size != INPUT_DIM) {
                Log.e(TAG, "Invalid input size: ${inputFeatures.size}, expected: $INPUT_DIM")
                return 0f
            }

            // Prepare input buffer
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_DIM * 4).apply {
                order(ByteOrder.nativeOrder())
                for (value in inputFeatures) {
                    putFloat(value)
                }
                rewind()
            }

            // Prepare output buffer
            val outputBuffer = ByteBuffer.allocateDirect(4).apply {
                order(ByteOrder.nativeOrder())
            }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Track performance
            val elapsed = System.currentTimeMillis() - startTime
            lastInferenceTimeMs = elapsed
            totalInferenceTimeMs += elapsed
            inferenceCount++

            // Get result
            outputBuffer.rewind()
            return outputBuffer.float

        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            return 0f
        }
    }

    /**
     * Get performance statistics
     */
    fun getPerformanceStats(): PerformanceStats {
        val avgTime = if (inferenceCount > 0) totalInferenceTimeMs.toFloat() / inferenceCount else 0f
        return PerformanceStats(
            accelerationType = accelerationType,
            inferenceCount = inferenceCount,
            totalTimeMs = totalInferenceTimeMs,
            avgTimeMs = avgTime,
            lastTimeMs = lastInferenceTimeMs
        )
    }

    /**
     * Reset performance counters
     */
    fun resetPerformanceStats() {
        inferenceCount = 0
        totalInferenceTimeMs = 0
        lastInferenceTimeMs = 0
    }

    data class PerformanceStats(
        val accelerationType: AccelerationType,
        val inferenceCount: Long,
        val totalTimeMs: Long,
        val avgTimeMs: Float,
        val lastTimeMs: Long
    ) {
        fun summary(): String {
            return "Acceleration: $accelerationType, Inferences: $inferenceCount, Avg: ${String.format("%.2f", avgTimeMs)}ms"
        }
    }

    /**
     * Predict Q-values for all elements on a screen (batch mode for efficiency)
     */
    fun predictQValues(screen: ExploredScreen): Map<ClickableElement, Float> {
        if (!isReady() || screen.clickableElements.isEmpty()) {
            return emptyMap()
        }

        val results = mutableMapOf<ClickableElement, Float>()
        val stateFeatures = stateEncoder.encode(screen)

        for (element in screen.clickableElements) {
            try {
                val actionFeatures = actionEncoder.encode(element)
                val inputFeatures = stateFeatures + actionFeatures

                val inputBuffer = ByteBuffer.allocateDirect(INPUT_DIM * 4).apply {
                    order(ByteOrder.nativeOrder())
                    for (value in inputFeatures) {
                        putFloat(value)
                    }
                    rewind()
                }

                val outputBuffer = ByteBuffer.allocateDirect(4).apply {
                    order(ByteOrder.nativeOrder())
                }

                interpreter?.run(inputBuffer, outputBuffer)

                outputBuffer.rewind()
                results[element] = outputBuffer.float

            } catch (e: Exception) {
                Log.w(TAG, "Error predicting Q-value for ${element.elementId}", e)
                results[element] = 0f
            }
        }

        return results
    }

    /**
     * Select best action based on Q-values
     */
    fun selectBestAction(
        screen: ExploredScreen,
        excludeExplored: Boolean = true
    ): ClickableElement? {
        if (!isReady()) return null

        val elements = if (excludeExplored) {
            screen.clickableElements.filter { !it.explored }
        } else {
            screen.clickableElements.toList()
        }

        if (elements.isEmpty()) return null

        val qValues = predictQValues(screen)

        return elements
            .filter { qValues.containsKey(it) }
            .maxByOrNull { qValues[it] ?: 0f }
    }

    /**
     * Get top N actions by Q-value
     */
    fun getTopActions(
        screen: ExploredScreen,
        n: Int = 5,
        excludeExplored: Boolean = true
    ): List<Pair<ClickableElement, Float>> {
        if (!isReady()) return emptyList()

        val elements = if (excludeExplored) {
            screen.clickableElements.filter { !it.explored }
        } else {
            screen.clickableElements.toList()
        }

        val qValues = predictQValues(screen)

        return elements
            .mapNotNull { element ->
                qValues[element]?.let { qValue -> element to qValue }
            }
            .sortedByDescending { it.second }
            .take(n)
    }

    /**
     * Load model file as MappedByteBuffer
     */
    private fun loadModelFile(file: File): MappedByteBuffer {
        val inputStream = FileInputStream(file)
        val fileChannel = inputStream.channel
        val startOffset = 0L
        val declaredLength = fileChannel.size()
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Log model input/output details for debugging
     */
    private fun logModelDetails() {
        interpreter?.let { interp ->
            try {
                val inputTensor = interp.getInputTensor(0)
                val outputTensor = interp.getOutputTensor(0)

                Log.i(TAG, "Model details:")
                Log.i(TAG, "  Input shape: ${inputTensor.shape().contentToString()}")
                Log.i(TAG, "  Input dtype: ${inputTensor.dataType()}")
                Log.i(TAG, "  Output shape: ${outputTensor.shape().contentToString()}")
                Log.i(TAG, "  Output dtype: ${outputTensor.dataType()}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not log model details: ${e.message}")
            }
        }
    }

    /**
     * Close interpreter and release resources
     */
    fun close() {
        try {
            // Close delegates first
            nnapiDelegate?.close()
            nnapiDelegate = null

            gpuDelegate?.close()
            gpuDelegate = null

            // Close interpreter
            interpreter?.close()
            interpreter = null

            isModelLoaded = false
            accelerationType = AccelerationType.UNKNOWN
            Log.d(TAG, "TFLite resources released")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing TFLite", e)
        }
    }

    /**
     * Delete stored model (for testing/reset)
     */
    fun deleteModel() {
        close()
        val file = File(modelDir, MODEL_FILENAME)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Model file deleted")
        }
    }
}

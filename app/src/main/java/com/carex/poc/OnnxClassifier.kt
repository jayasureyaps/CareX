package com.carex.poc

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Wraps ONNX Runtime to run the trained RandomForest activity classifier on-device.
 *
 * The model was trained with scikit-learn and converted to ONNX via skl2onnx.
 * Standard sklearn→ONNX conversion produces two outputs:
 *   - "label"         : int64 tensor of shape [1] — the predicted class index
 *   - "probabilities" : float32 tensor of shape [1, 4] — class probabilities
 *
 * Label mapping (matching the training labels):
 *   1→Sitting, 2→Standing, 3→Walking, 4→Falling
 */
class OnnxClassifier(context: Context) {

    companion object {
        private const val TAG = "OnnxClassifier"
        private const val MODEL_FILE = "activity_rf.onnx"

        /** Activity label map — keys are the class indices from the trained model. */
        val LABEL_MAP = mapOf(
            1L to "Sitting",
            2L to "Standing",
            3L to "Walking",
            4L to "Falling"
        )

        // Also keep a 0-indexed version for fallback (some ONNX exports use 0-indexed)
        val LABEL_MAP_ZERO = mapOf(
            0L to "Sitting",
            1L to "Standing",
            2L to "Walking",
            3L to "Falling"
        )
    }

    private val ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var inputName: String = "float_input" // default sklearn→ONNX input name

    /** True if the model loaded successfully and is ready for inference. */
    var isReady: Boolean = false
        private set

    init {
        try {
            // Load the ONNX model bytes from the assets directory
            val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            if (modelBytes.size < 16) {
                // Placeholder / empty file — skip loading
                Log.w(TAG, "Model file is too small (${modelBytes.size} bytes), likely a placeholder. " +
                        "Replace assets/$MODEL_FILE with your real ONNX model.")
            } else {
                session = ortEnv.createSession(modelBytes)
                // Discover the actual input name from the model metadata
                inputName = session!!.inputNames.first()
                isReady = true
                Log.i(TAG, "ONNX model loaded successfully. Input name: $inputName, " +
                        "outputs: ${session!!.outputNames}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model: ${e.message}", e)
        }
    }

    /**
     * Run inference on the 40 extracted features.
     *
     * @param features FloatArray of exactly 40 values in the canonical feature order.
     * @return Pair(label, confidence) e.g. ("Walking", 0.87f), or null if model isn't loaded.
     */
    fun predict(features: FloatArray): Pair<String, Float>? {
        val sess = session ?: return null
        if (!isReady) return null

        return try {
            // Build input tensor of shape [1, 40] (batch=1, features=40)
            val shape = longArrayOf(1, features.size.toLong())
            val buffer = FloatBuffer.wrap(features)
            val inputTensor = OnnxTensor.createTensor(ortEnv, buffer, shape)

            // Run inference
            val results = sess.run(mapOf(inputName to inputTensor))

            // --- Determine output names dynamically ---
            val labelName = sess.outputNames.firstOrNull { it.contains("label", ignoreCase = true) }
                ?: "label"
            val probName = sess.outputNames.firstOrNull { it.contains("prob", ignoreCase = true) }
                ?: "probabilities"

            var predictedClass: Long = -1
            var confidence = 0f

            // --- Extract predicted label ---
            val labelOutput = results.get(labelName)
            if (labelOutput.isPresent) {
                val labelValue = labelOutput.get().value
                predictedClass = when (labelValue) {
                    is LongArray -> labelValue[0]
                    is IntArray -> labelValue[0].toLong()
                    is Long -> labelValue
                    is Int -> labelValue.toLong()
                    is Array<*> -> (labelValue.firstOrNull() as? Number)?.toLong() ?: -1L
                    else -> {
                        Log.w(TAG, "Unexpected label type: ${labelValue?.javaClass}")
                        -1L
                    }
                }
            }

            // --- Extract probabilities ---
            val probOutput = results.get(probName)
            if (probOutput.isPresent) {
                val probValue = probOutput.get().value
                when (probValue) {
                    // skl2onnx with zipmap=False outputs a 2D float array [1, num_classes]
                    is Array<*> -> {
                        val row = probValue.firstOrNull()
                        if (row is FloatArray && predictedClass >= 0) {
                            // Classes are [1,2,3,4], so class index = predictedClass - 1
                            val classIdx = (predictedClass - 1).toInt()
                            if (classIdx in row.indices) {
                                confidence = row[classIdx]
                            }
                        }
                    }
                    is List<*> -> {
                        // Fallback: Sequence of maps (older skl2onnx with zipmap=True)
                        val rawMap = probValue.firstOrNull() as? Map<*, *>
                        if (rawMap != null) {
                            val floatMap = rawMap.entries.associate { (k, v) ->
                                ((k as Number).toLong()) to ((v as Number).toFloat())
                            }

                            if (predictedClass < 0) {
                                val best = floatMap.maxByOrNull { it.value }
                                predictedClass = best?.key ?: -1L
                            }
                            if (predictedClass >= 0) {
                                confidence = floatMap[predictedClass] ?: 0f
                            }
                        }
                    }
                    else -> Log.w(TAG, "Unexpected probabilities type: ${probValue?.javaClass}")
                }
            }

            // Map class index to human-readable label
            val label = LABEL_MAP[predictedClass]
                ?: LABEL_MAP_ZERO[predictedClass]
                ?: "Unknown ($predictedClass)"

            // If we still have no confidence, default to 1.0 (model gave a hard prediction)
            if (confidence == 0f && predictedClass >= 0) confidence = 1f

            // Clean up native resources
            inputTensor.close()
            results.close()

            Log.d(TAG, "Prediction: $label (${(confidence * 100).toInt()}%)")
            Pair(label, confidence)

        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}", e)
            null
        }
    }

    /** Release ONNX Runtime resources. Call when the app is done. */
    fun close() {
        try {
            session?.close()
            // Note: OrtEnvironment is a singleton; don't close it here.
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ONNX session: ${e.message}", e)
        }
    }
}

package com.carex.poc

import kotlin.math.sqrt

/**
 * Pure-Kotlin feature extraction from a window of IMU samples.
 *
 * Given a window of 60 samples (2 seconds at 30 Hz, each sample = 6 floats: ax, ay, az, gx, gy, gz),
 * this produces exactly 40 features in the order expected by the trained
 * scikit-learn RandomForestClassifier.
 *
 * Feature order (40 total):
 *   For each of 8 signals [acc_mag, gyr_mag, ax, ay, az, gx, gy, gz]:
 *     mean, std, min, max, ptp (peak-to-peak)   → 8 × 5 = 40 features
 *
 * Accelerometer scaling:
 *   If the median accelerometer magnitude exceeds 40,
 *   the accel values are divided by 8.0 (matching the training pipeline).
 *
 * This mirrors the Python training pipeline (train.py) exactly.
 */
object FeatureExtractor {

    /** Number of samples expected per window (2 seconds × 30 Hz). */
    const val WINDOW_SIZE = 60

    /** Number of output features. */
    const val NUM_FEATURES = 40

    /** Channel indices in each sample array. */
    private const val AX = 0; private const val AY = 1; private const val AZ = 2
    private const val GX = 3; private const val GY = 4; private const val GZ = 5

    /**
     * Extract 40 features from a window of IMU samples.
     *
     * @param window  List of exactly [WINDOW_SIZE] samples, each a FloatArray of 6 values
     *                ordered as [ax, ay, az, gx, gy, gz].
     * @return FloatArray of exactly [NUM_FEATURES] features in the canonical order.
     * @throws IllegalArgumentException if window size or sample width is wrong.
     */
    fun extract(window: List<FloatArray>): FloatArray {
        require(window.size == WINDOW_SIZE) {
            "Expected $WINDOW_SIZE samples, got ${window.size}"
        }

        // ---- Apply accelerometer scale fix (matching train.py) ----
        // Compute median accelerometer magnitude across the window.
        // If median > 40, divide all accel axes by 8.0.
        val accMagAll = FloatArray(WINDOW_SIZE) { i ->
            val ax = window[i][AX].toDouble()
            val ay = window[i][AY].toDouble()
            val az = window[i][AZ].toDouble()
            sqrt(ax * ax + ay * ay + az * az).toFloat()
        }

        val sortedMag = accMagAll.sorted()
        val median = if (WINDOW_SIZE % 2 == 0) {
            (sortedMag[WINDOW_SIZE / 2 - 1] + sortedMag[WINDOW_SIZE / 2]) / 2f
        } else {
            sortedMag[WINDOW_SIZE / 2]
        }

        val scaledWindow: List<FloatArray> = if (median > 40f) {
            window.map { sample ->
                floatArrayOf(
                    sample[AX] / 8f,
                    sample[AY] / 8f,
                    sample[AZ] / 8f,
                    sample[GX],
                    sample[GY],
                    sample[GZ]
                )
            }
        } else {
            window
        }

        // ---- Build the 8 signal columns ----
        // Signal order: acc_mag, gyr_mag, ax, ay, az, gx, gy, gz

        val accMag = FloatArray(WINDOW_SIZE) { i ->
            val ax = scaledWindow[i][AX].toDouble()
            val ay = scaledWindow[i][AY].toDouble()
            val az = scaledWindow[i][AZ].toDouble()
            sqrt(ax * ax + ay * ay + az * az).toFloat()
        }

        val gyrMag = FloatArray(WINDOW_SIZE) { i ->
            val gx = scaledWindow[i][GX].toDouble()
            val gy = scaledWindow[i][GY].toDouble()
            val gz = scaledWindow[i][GZ].toDouble()
            sqrt(gx * gx + gy * gy + gz * gz).toFloat()
        }

        val axCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][AX] }
        val ayCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][AY] }
        val azCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][AZ] }
        val gxCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][GX] }
        val gyCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][GY] }
        val gzCol = FloatArray(WINDOW_SIZE) { scaledWindow[it][GZ] }

        val signals = arrayOf(accMag, gyrMag, axCol, ayCol, azCol, gxCol, gyCol, gzCol)

        // ---- Extract 5 features per signal (8 × 5 = 40) ----
        val features = FloatArray(NUM_FEATURES)
        var idx = 0

        for (signal in signals) {
            // 1. mean
            val mean = signal.average().toFloat()
            features[idx++] = mean

            // 2. std (population standard deviation, ddof=0 matching numpy default)
            val variance = signal.map { (it - mean).toDouble() * (it - mean).toDouble() }.average()
            features[idx++] = sqrt(variance).toFloat()

            // 3. min
            val min = signal.min()
            features[idx++] = min

            // 4. max
            val max = signal.max()
            features[idx++] = max

            // 5. ptp (peak-to-peak = max - min, matching numpy's np.ptp)
            features[idx++] = max - min
        }

        check(idx == NUM_FEATURES) { "Bug: produced $idx features, expected $NUM_FEATURES" }
        return features
    }
}

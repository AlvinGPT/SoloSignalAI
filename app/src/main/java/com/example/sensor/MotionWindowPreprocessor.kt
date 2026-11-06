package com.example.sensor

import com.example.domain.MotionSample
import com.example.domain.SensorWindowSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Processed 4.0-second (200-sample @ 50 Hz) orientation-invariant motion window ready for
 * on-device LiteRT 1D CNN inference and baseline feature evaluation.
 */
data class PreprocessedMotionWindow(
    val rawChannels: Array<FloatArray>, // [200][6] unnormalized orientation-invariant channels
    val engineeredFeatures36: FloatArray, // [36] statistical & spectral features for RF baseline
    val snapshot: SensorWindowSnapshot,
    val sampleQualityGood: Boolean,
    val maxTimestampGapMs: Long
)

/**
 * Real-device sensor preprocessor that:
 * 1. Measures empirical sampling frequency (Hz) and timestamp jitter/gaps across Android hardware.
 * 2. Linearly resamples arbitrary monotonic sensor timestamps onto a uniform 50.0 Hz (20 ms) grid of 200 samples.
 * 3. Separates estimated gravity g_est from dynamic body acceleration b = a - g_est via a 0.3 Hz low-pass filter.
 * 4. Computes 6 orientation-invariant time-series channels so classification does not depend on phone orientation:
 *    - Channel 0: totalAccelG (||a|| / g)
 *    - Channel 1: bodyAccelG (||a - g_est|| / g)
 *    - Channel 2: verticalBodyAccelG ((a - g_est) · g_hat / g)
 *    - Channel 3: horizontalBodyAccelG (||(a - g_est) - a_vert * g_hat|| / g)
 *    - Channel 4: jerkMagnitudeGPerSec (d(||a||/g) / dt)
 *    - Channel 5: gyroMagnitudeRadPerSec (||ω|| in rad/s, or 0 if no hardware gyroscope)
 */
object MotionWindowPreprocessor {
    const val WINDOW_SAMPLES = 200
    const val NUM_CHANNELS = 6
    const val STEP_NANOS = 20_000_000L // 20 ms (50 Hz)
    const val GRAVITY_MS2 = 9.80665f
    const val LOW_PASS_ALPHA = 0.963f
    const val MAX_ALLOWED_GAP_MS = 250L

    fun preprocess(
        samples: List<MotionSample>,
        hasGyroscopeHardware: Boolean = samples.any { it.hasGyroscope },
        accelerometerMaxRangeMs2: Float? = null
    ): PreprocessedMotionWindow {
        if (samples.isEmpty()) {
            val emptyGrid = Array(WINDOW_SAMPLES) { floatArrayOf(1.0f, 0f, 0f, 0f, 0f, 0f) }
            return PreprocessedMotionWindow(
                rawChannels = emptyGrid,
                engineeredFeatures36 = FloatArray(36),
                snapshot = SensorWindowSnapshot(
                    hasGyroscope = hasGyroscopeHardware,
                    accelerometerMaxRangeMs2 = accelerometerMaxRangeMs2
                ),
                sampleQualityGood = false,
                maxTimestampGapMs = 0L
            )
        }

        val sorted = if (isMonotonic(samples)) samples else samples.sortedBy { it.timestampNanos }
        val n = sorted.size
        val startNanos = sorted.first().timestampNanos
        val endNanos = sorted.last().timestampNanos
        val spanNanos = max(1L, endNanos - startNanos)
        val durationSec = spanNanos / 1_000_000_000.0f

        var maxGapNanos = 0L
        for (i in 1 until n) {
            val dt = sorted[i].timestampNanos - sorted[i - 1].timestampNanos
            if (dt > maxGapNanos) {
                maxGapNanos = dt
            }
        }
        val maxGapMs = maxGapNanos / 1_000_000L
        val empiricalHz = if (n > 1 && durationSec > 0.05f) {
            (n - 1).toFloat() / durationSec
        } else {
            0f
        }

        // Resample onto uniform 200-sample 50 Hz grid anchored to startNanos
        val resampledAx = FloatArray(WINDOW_SAMPLES)
        val resampledAy = FloatArray(WINDOW_SAMPLES)
        val resampledAz = FloatArray(WINDOW_SAMPLES)
        val resampledGx = FloatArray(WINDOW_SAMPLES)
        val resampledGy = FloatArray(WINDOW_SAMPLES)
        val resampledGz = FloatArray(WINDOW_SAMPLES)

        var idx = 0
        for (i in 0 until WINDOW_SAMPLES) {
            val targetT = startNanos + i * STEP_NANOS
            while (idx + 1 < n && sorted[idx + 1].timestampNanos < targetT) {
                idx++
            }
            if (idx + 1 < n) {
                val s0 = sorted[idx]
                val s1 = sorted[idx + 1]
                val dt = max(1L, s1.timestampNanos - s0.timestampNanos).toFloat()
                val frac = ((targetT - s0.timestampNanos).toFloat() / dt).coerceIn(0f, 1f)
                resampledAx[i] = s0.ax + frac * (s1.ax - s0.ax)
                resampledAy[i] = s0.ay + frac * (s1.ay - s0.ay)
                resampledAz[i] = s0.az + frac * (s1.az - s0.az)
                resampledGx[i] = s0.gx + frac * (s1.gx - s0.gx)
                resampledGy[i] = s0.gy + frac * (s1.gy - s0.gy)
                resampledGz[i] = s0.gz + frac * (s1.gz - s0.gz)
            } else {
                val last = sorted.last()
                resampledAx[i] = last.ax
                resampledAy[i] = last.ay
                resampledAz[i] = last.az
                resampledGx[i] = last.gx
                resampledGy[i] = last.gy
                resampledGz[i] = last.gz
            }
        }

        // Initialize gravity estimate from first 10 resampled steps (200 ms)
        val initCount = min(10, WINDOW_SAMPLES)
        var gxEst = 0f
        var gyEst = 0f
        var gzEst = 0f
        for (k in 0 until initCount) {
            gxEst += resampledAx[k]
            gyEst += resampledAy[k]
            gzEst += resampledAz[k]
        }
        gxEst /= initCount.toFloat()
        gyEst /= initCount.toFloat()
        gzEst /= initCount.toFloat()

        val rawChannels = Array(WINDOW_SAMPLES) { FloatArray(NUM_CHANNELS) }
        var prevTotalG = sqrt(gxEst * gxEst + gyEst * gyEst + gzEst * gzEst) / GRAVITY_MS2

        var minTotalG = Float.MAX_VALUE
        var peakTotalMs2 = 0f
        var peakVertBodyG = 0f
        var peakHorizBodyG = 0f
        var peakJerkGPerSec = 0f
        var peakGyroDegPerSec = 0f
        var peakIdx = 0

        for (i in 0 until WINDOW_SAMPLES) {
            val ax = resampledAx[i]
            val ay = resampledAy[i]
            val az = resampledAz[i]
            val wx = resampledGx[i]
            val wy = resampledGy[i]
            val wz = resampledGz[i]

            gxEst = LOW_PASS_ALPHA * gxEst + (1f - LOW_PASS_ALPHA) * ax
            gyEst = LOW_PASS_ALPHA * gyEst + (1f - LOW_PASS_ALPHA) * ay
            gzEst = LOW_PASS_ALPHA * gzEst + (1f - LOW_PASS_ALPHA) * az

            val totalMagMs2 = sqrt(ax * ax + ay * ay + az * az)
            val totalG = totalMagMs2 / GRAVITY_MS2

            val bx = ax - gxEst
            val by = ay - gyEst
            val bz = az - gzEst
            val bodyMagMs2 = sqrt(bx * bx + by * by + bz * bz)
            val bodyG = bodyMagMs2 / GRAVITY_MS2

            val gNorm = sqrt(gxEst * gxEst + gyEst * gyEst + gzEst * gzEst)
            val (ux, uy, uz) = if (gNorm > 1e-3f) {
                Triple(gxEst / gNorm, gyEst / gNorm, gzEst / gNorm)
            } else {
                Triple(0f, 0f, 1f)
            }

            val vertMs2 = bx * ux + by * uy + bz * uz
            val vertG = vertMs2 / GRAVITY_MS2

            val hx = bx - vertMs2 * ux
            val hy = by - vertMs2 * uy
            val hz = bz - vertMs2 * uz
            val horizG = sqrt(hx * hx + hy * hy + hz * hz) / GRAVITY_MS2

            val jerkGs = if (i > 0) (totalG - prevTotalG) / 0.020f else 0f
            prevTotalG = totalG

            val gyroRad = sqrt(wx * wx + wy * wy + wz * wz)
            val gyroDeg = Math.toDegrees(gyroRad.toDouble()).toFloat()

            rawChannels[i][0] = totalG
            rawChannels[i][1] = bodyG
            rawChannels[i][2] = vertG
            rawChannels[i][3] = horizG
            rawChannels[i][4] = jerkGs
            rawChannels[i][5] = gyroRad

            if (totalG < minTotalG) minTotalG = totalG
            if (totalMagMs2 > peakTotalMs2) {
                peakTotalMs2 = totalMagMs2
                peakIdx = i
            }
            if (abs(vertG) > peakVertBodyG) peakVertBodyG = abs(vertG)
            if (horizG > peakHorizBodyG) peakHorizBodyG = horizG
            if (abs(jerkGs) > peakJerkGPerSec) peakJerkGPerSec = abs(jerkGs)
            if (gyroDeg > peakGyroDegPerSec) peakGyroDegPerSec = gyroDeg
        }

        // Compute post-peak stillness standard deviation (in m/s²)
        val postStart = min(WINDOW_SAMPLES - 10, peakIdx + 15)
        var postMeanG = 0f
        val postCount = WINDOW_SAMPLES - postStart
        for (i in postStart until WINDOW_SAMPLES) {
            postMeanG += rawChannels[i][0]
        }
        postMeanG /= postCount.toFloat()

        var postVarSumG = 0f
        for (i in postStart until WINDOW_SAMPLES) {
            val d = rawChannels[i][0] - postMeanG
            postVarSumG += d * d
        }
        val postStdMs2 = sqrt(postVarSumG / postCount.toFloat()) * GRAVITY_MS2

        // Downsample 20-point magnitude envelope in g for compact explainability display
        val envelope = ArrayList<Float>(20)
        for (b in 0 until 20) {
            val sIdx = b * 10
            var maxBlockG = 0f
            for (k in sIdx until min(WINDOW_SAMPLES, sIdx + 10)) {
                if (rawChannels[k][0] > maxBlockG) {
                    maxBlockG = rawChannels[k][0]
                }
            }
            envelope.add(maxBlockG)
        }

        val features36 = extractRf36Features(rawChannels)
        val qualityGood = n >= 15 && maxGapMs <= MAX_ALLOWED_GAP_MS && spanNanos >= 1_000_000_000L

        val snapshot = SensorWindowSnapshot(
            windowSampleCount = n,
            windowStartNanos = startNanos,
            windowEndNanos = endNanos,
            empiricalSamplingRateHz = empiricalHz,
            minTotalAccelG = if (minTotalG == Float.MAX_VALUE) 1.0f else minTotalG,
            peakTotalAccelMs2 = peakTotalMs2,
            peakVerticalBodyAccelG = peakVertBodyG,
            peakHorizontalBodyAccelG = peakHorizBodyG,
            peakJerkGPerSec = peakJerkGPerSec,
            peakGyroDegPerSec = peakGyroDegPerSec,
            postImpactStillnessStdDevMs2 = postStdMs2,
            hasGyroscope = hasGyroscopeHardware,
            accelerometerMaxRangeMs2 = accelerometerMaxRangeMs2,
            downsampledMagnitudeEnvelopeG = envelope
        )

        return PreprocessedMotionWindow(
            rawChannels = rawChannels,
            engineeredFeatures36 = features36,
            snapshot = snapshot,
            sampleQualityGood = qualityGood,
            maxTimestampGapMs = maxGapMs
        )
    }

    /**
     * Extracts the exact 36 statistical, phase, and spectral features used by the
     * Random Forest baseline comparator in train_fall_detector.py.
     */
    fun extractRf36Features(window: Array<FloatArray>): FloatArray {
        val feats = FloatArray(36)
        var outIdx = 0

        for (c in 0 until NUM_CHANNELS) {
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE
            var sumV = 0f
            for (t in 0 until WINDOW_SAMPLES) {
                val v = window[t][c]
                if (v < minV) minV = v
                if (v > maxV) maxV = v
                sumV += v
            }
            val meanV = sumV / WINDOW_SAMPLES.toFloat()
            var varSum = 0f
            for (t in 0 until WINDOW_SAMPLES) {
                val d = window[t][c] - meanV
                varSum += d * d
            }
            val stdV = sqrt(varSum / WINDOW_SAMPLES.toFloat())
            feats[outIdx++] = minV
            feats[outIdx++] = maxV
            feats[outIdx++] = meanV
            feats[outIdx++] = stdV
        }

        var peakIdx = 0
        var peakVal = -Float.MAX_VALUE
        for (t in 0 until WINDOW_SAMPLES) {
            val v = window[t][0]
            if (v > peakVal) {
                peakVal = v
                peakIdx = t
            }
        }

        val preStart = max(0, peakIdx - 35)
        var preMinG = Float.MAX_VALUE
        for (t in preStart..peakIdx) {
            if (window[t][0] < preMinG) preMinG = window[t][0]
        }

        val postStart = min(WINDOW_SAMPLES - 10, peakIdx + 15)
        val postLen = WINDOW_SAMPLES - postStart
        var postMean = 0f
        var postBodyMean = 0f
        var postGyroMean = 0f
        for (t in postStart until WINDOW_SAMPLES) {
            postMean += window[t][0]
            postBodyMean += window[t][1]
            postGyroMean += window[t][5]
        }
        postMean /= postLen.toFloat()
        postBodyMean /= postLen.toFloat()
        postGyroMean /= postLen.toFloat()

        var postVar = 0f
        for (t in postStart until WINDOW_SAMPLES) {
            val d = window[t][0] - postMean
            postVar += d * d
        }
        val postStd = sqrt(postVar / postLen.toFloat())

        var zcCount = 0
        for (t in 1 until WINDOW_SAMPLES) {
            val d0 = window[t - 1][0] - 1.0f
            val d1 = window[t][0] - 1.0f
            if (d0 * d1 < 0f && (abs(d0) > 0.06f || abs(d1) > 0.06f)) {
                zcCount++
            }
        }
        val zcr = zcCount.toFloat() / WINDOW_SAMPLES.toFloat()

        var highPeaks = 0
        for (t in 1 until WINDOW_SAMPLES - 1) {
            val v = window[t][0]
            if (v > 2.5f && v >= window[t - 1][0] && v >= window[t + 1][0]) {
                highPeaks++
            }
        }

        var smaSum = 0f
        for (t in 0 until WINDOW_SAMPLES) {
            smaSum += abs(window[t][2]) + abs(window[t][3])
        }
        val sma = smaSum / WINDOW_SAMPLES.toFloat()

        var ffCount = 0
        for (t in 0..peakIdx) {
            if (window[t][0] < 0.62f) {
                ffCount++
            }
        }

        val preEnergyLen = max(1, min(WINDOW_SAMPLES, peakIdx + 5))
        var preEnergy = 0f
        for (t in 0 until preEnergyLen) {
            val b = window[t][1]
            preEnergy += b * b
        }
        preEnergy /= preEnergyLen.toFloat()

        var postEnergy = 0f
        for (t in postStart until WINDOW_SAMPLES) {
            val b = window[t][1]
            postEnergy += b * b
        }
        postEnergy /= postLen.toFloat()
        val energyRatio = preEnergy / (postEnergy + 1e-4f)

        feats[outIdx++] = peakVal
        feats[outIdx++] = preMinG
        feats[outIdx++] = postMean
        feats[outIdx++] = postStd
        feats[outIdx++] = postBodyMean
        feats[outIdx++] = postGyroMean
        feats[outIdx++] = zcr
        feats[outIdx++] = highPeaks.toFloat()
        feats[outIdx++] = sma
        feats[outIdx++] = ffCount.toFloat()
        feats[outIdx++] = energyRatio
        feats[outIdx] = peakIdx.toFloat() / WINDOW_SAMPLES.toFloat()

        return feats
    }

    private fun isMonotonic(samples: List<MotionSample>): Boolean {
        for (i in 1 until samples.size) {
            if (samples[i].timestampNanos < samples[i - 1].timestampNanos) return false
        }
        return true
    }
}

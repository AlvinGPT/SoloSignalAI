package com.example.sensor

import android.content.Context
import com.example.domain.ModelComparisonReport
import com.example.domain.ModelEvaluationMetrics
import com.example.domain.MotionSample
import com.example.domain.MovementCategory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Held-out labelled evaluation window with ground-truth [MovementCategory] and binary fall flag.
 */
data class HeldOutLabeledWindow(
    val id: String,
    val groundTruthCategory: MovementCategory,
    val shouldTriggerFallCheckIn: Boolean,
    val samples: List<MotionSample>
)

/**
 * Fair side-by-side benchmark evaluator comparing:
 * 1. Existing 3-Phase Rule Detector (`CandidateFallDetector`)
 * 2. Random Forest Baseline (`RandomForestBaselineClassifier`)
 * 3. LiteRT 1D CNN + Hybrid Safety Engine (`HybridFallDetectionEngine`)
 *
 * All three systems are evaluated on the exact same held-out test windows.
 */
object ModelComparisonEvaluator {

    val DOCUMENTED_LIMITATIONS = listOf(
        "Off-body phone drops (e.g., phone knocked from a desk onto a rug and left untouched for >2.5s) exhibit genuine free-fall, high-G impact, and stillness, requiring the 15-second user check-in to confirm whether the user is okay.",
        "Gradual slides or slow slumps against a wall where peak impact remains below ~22 m/s² (~2.2g) may not cross physical impact thresholds.",
        "Devices without a hardware gyroscope (TYPE_GYROSCOPE absent) rely on the 5 accelerometer-derived channels, slightly reducing separation between rapid sitting and syncope collapse.",
        "Hardware accelerometers with a low maximum range (< 30 m/s² / ~3g) saturate during hard impacts; the detector caps the impact threshold to 90% of Sensor.getMaximumRange()."
    )

    fun evaluateHeldOutSuite(context: Context? = null): ModelComparisonReport {
        val suite = buildHeldOutTestSuite()
        val cnnClassifier = LiteRtMotionClassifier(context)
        val rfClassifier = RandomForestBaselineClassifier(context)

        val ruleCm = Array(6) { IntArray(6) }
        val rfCm = Array(6) { IntArray(6) }
        val cnnCm = Array(6) { IntArray(6) }

        val rulePreds = LinkedHashMap<String, MovementCategory>()
        val rfPreds = LinkedHashMap<String, MovementCategory>()
        val cnnPreds = LinkedHashMap<String, MovementCategory>()

        var ruleTp = 0
        var ruleFp = 0
        var ruleTn = 0
        var ruleFn = 0
        var ruleTotalLatencyNs = 0L

        var rfTp = 0
        var rfFp = 0
        var rfTn = 0
        var rfFn = 0
        var rfTotalLatencyNs = 0L

        var cnnTp = 0
        var cnnFp = 0
        var cnnTn = 0
        var cnnFn = 0
        var cnnTotalLatencyNs = 0L

        val cnnCalibrationSamples = mutableListOf<Pair<Float, Boolean>>()

        for (item in suite) {
            val truthIdx = item.groundTruthCategory.index

            // 1. Evaluate 3-Phase Rule Detector
            val ruleStart = System.nanoTime()
            val ruleDetector = CandidateFallDetector()
            var ruleTriggered = false
            for (s in item.samples) {
                val ev = ruleDetector.addSample(s, s.timestampNanos / 1_000_000L)
                if (ev.candidateEvent != null) {
                    ruleTriggered = true
                }
            }
            ruleTotalLatencyNs += (System.nanoTime() - ruleStart)
            val ruleCategory = if (ruleTriggered) {
                MovementCategory.POSSIBLE_FALL
            } else {
                MovementCategory.STANDING_STILL
            }
            ruleCm[truthIdx][ruleCategory.index]++
            rulePreds[item.id] = ruleCategory
            if (item.shouldTriggerFallCheckIn) {
                if (ruleTriggered) ruleTp++ else ruleFn++
            } else {
                if (ruleTriggered) ruleFp++ else ruleTn++
            }

            // Preprocess 50 Hz 200-sample window once for RF and CNN
            val preprocessed = MotionWindowPreprocessor.preprocess(item.samples)

            // 2. Evaluate Random Forest Baseline (strictly benchmark-only)
            val rfStart = System.nanoTime()
            val rfRes = rfClassifier.predict(preprocessed.engineeredFeatures36)
            rfTotalLatencyNs += (System.nanoTime() - rfStart)
            val rfTriggered = rfRes.predictedCategory == MovementCategory.POSSIBLE_FALL
            rfCm[truthIdx][rfRes.predictedCategory.index]++
            rfPreds[item.id] = rfRes.predictedCategory
            if (item.shouldTriggerFallCheckIn) {
                if (rfTriggered) rfTp++ else rfFn++
            } else {
                if (rfTriggered) rfFp++ else rfTn++
            }

            // 3. Evaluate LiteRT 1D CNN + Hybrid Safety Engine
            val cnnStart = System.nanoTime()
            val hybridEngine = HybridFallDetectionEngine(
                context = context,
                cnnClassifier = cnnClassifier
            )
            var hybridTriggered = false
            for (s in item.samples) {
                val ev = hybridEngine.processSample(s, s.timestampNanos / 1_000_000L)
                if (ev.candidateEvent != null) {
                    hybridTriggered = true
                }
            }
            val cnnRes = cnnClassifier.classify(preprocessed)
            cnnTotalLatencyNs += (System.nanoTime() - cnnStart)

            val finalHybridCat = if (hybridTriggered) {
                MovementCategory.POSSIBLE_FALL
            } else if (cnnRes.predictedCategory == MovementCategory.POSSIBLE_FALL) {
                // CNN saw impact peak, but Hybrid stillness guard rejected it because post-impact motion continued
                MovementCategory.UNKNOWN_UNCERTAIN
            } else {
                cnnRes.predictedCategory
            }

            cnnCm[truthIdx][finalHybridCat.index]++
            cnnPreds[item.id] = finalHybridCat
            if (cnnRes.calibratedConfidence != null) {
                cnnCalibrationSamples.add(
                    cnnRes.calibratedConfidence to (finalHybridCat == item.groundTruthCategory)
                )
            }

            if (item.shouldTriggerFallCheckIn) {
                if (hybridTriggered) cnnTp++ else cnnFn++
            } else {
                if (hybridTriggered) cnnFp++ else cnnTn++
            }
        }

        val n = max(1, suite.size)
        val ece = computeEmpiricalEce(cnnCalibrationSamples)

        val ruleMetrics = buildMetrics(
            name = "3-Phase Rule Detector",
            version = "rule-state-machine-v1",
            total = n,
            tp = ruleTp,
            fp = ruleFp,
            tn = ruleTn,
            fn = ruleFn,
            cm = ruleCm,
            ece = null,
            meanLatencyMs = (ruleTotalLatencyNs / n.toDouble()) / 1_000_000.0,
            preds = rulePreds
        )

        val rfMetrics = buildMetrics(
            name = "Random Forest Baseline",
            version = rfClassifier.modelVersion,
            total = n,
            tp = rfTp,
            fp = rfFp,
            tn = rfTn,
            fn = rfFn,
            cm = rfCm,
            ece = null,
            meanLatencyMs = (rfTotalLatencyNs / n.toDouble()) / 1_000_000.0,
            preds = rfPreds
        )

        val cnnMetrics = buildMetrics(
            name = "LiteRT 1D-CNN + Hybrid Guard",
            version = cnnClassifier.modelVersion,
            total = n,
            tp = cnnTp,
            fp = cnnFp,
            tn = cnnTn,
            fn = cnnFn,
            cm = cnnCm,
            ece = ece,
            meanLatencyMs = (cnnTotalLatencyNs / n.toDouble()) / 1_000_000.0,
            preds = cnnPreds
        )

        return ModelComparisonReport(
            heldOutWindowCount = suite.size,
            ruleBasedMetrics = ruleMetrics,
            randomForestBaselineMetrics = rfMetrics,
            cnnHybridMetrics = cnnMetrics,
            limitations = DOCUMENTED_LIMITATIONS
        )
    }

    private fun buildMetrics(
        name: String,
        version: String,
        total: Int,
        tp: Int,
        fp: Int,
        tn: Int,
        fn: Int,
        cm: Array<IntArray>,
        ece: Float?,
        meanLatencyMs: Double,
        preds: Map<String, MovementCategory>
    ): ModelEvaluationMetrics {
        val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp).toFloat() else 0f
        val recall = if (tp + fn > 0) tp.toFloat() / (tp + fn).toFloat() else 0f
        val f1 = if (precision + recall > 0f) 2f * precision * recall / (precision + recall) else 0f
        val fpr = if (fp + tn > 0) fp.toFloat() / (fp + tn).toFloat() else 0f
        var diag = 0
        for (i in 0 until 6) {
            diag += cm[i][i]
        }
        val multiAcc = diag.toFloat() / max(1, total).toFloat()

        return ModelEvaluationMetrics(
            modelName = name,
            modelVersion = version,
            totalWindows = total,
            truePositives = tp,
            falsePositives = fp,
            trueNegatives = tn,
            falseNegatives = fn,
            precision = precision,
            recallSensitivity = recall,
            f1Score = f1,
            falsePositiveRate = fpr,
            multiClassAccuracy = multiAcc,
            expectedCalibrationError = ece,
            meanInferenceLatencyMs = meanLatencyMs,
            confusionMatrix6x6 = cm.map { it.toList() },
            scenarioPredictions = preds
        )
    }

    private fun computeEmpiricalEce(samples: List<Pair<Float, Boolean>>): Float? {
        if (samples.isEmpty()) return null
        val bins = Array(10) { mutableListOf<Pair<Float, Float>>() }
        for ((conf, correct) in samples) {
            val idx = min(9, max(0, (conf * 10f).toInt()))
            bins[idx].add(conf to (if (correct) 1f else 0f))
        }
        var ece = 0f
        val n = samples.size.toFloat()
        for (bin in bins) {
            if (bin.isEmpty()) continue
            val avgConf = bin.map { it.first }.average().toFloat()
            val avgAcc = bin.map { it.second }.average().toFloat()
            ece += (bin.size.toFloat() / n) * abs(avgConf - avgAcc)
        }
        return ece
    }

    /**
     * Builds the held-out multi-class test suite covering all 6 movement categories
     * and edge cases (including gradual syncope collapse without <6.0 m/s² free-fall).
     */
    fun buildHeldOutTestSuite(): List<HeldOutLabeledWindow> {
        val list = mutableListOf<HeldOutLabeledWindow>()
        val baseTs = 2_000_000_000L
        val stepNs = 20_000_000L // 50 Hz (200 samples = 4.0s)

        // 1. STANDING_STILL held-out windows
        list.add(
            HeldOutLabeledWindow(
                id = "STANDING_UPRIGHT_POCKET",
                groundTruthCategory = MovementCategory.STANDING_STILL,
                shouldTriggerFallCheckIn = false,
                samples = SyntheticSensorTraces.generatePhoneRestingFlat(durationMs = 4000L, stepMs = 20L)
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "STANDING_LANDSCAPE_TILTED",
                groundTruthCategory = MovementCategory.STANDING_STILL,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02f
                    val sway = 0.06f * sin(2.0 * Math.PI * 0.35 * tSec).toFloat()
                    // Rotated 90 degrees into landscape (X carries gravity)
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = 9.81f + 0.04f * cos(tSec.toDouble()).toFloat(),
                        ay = sway,
                        az = 0.1f * sway,
                        sensorType = "Held-Out Standing Landscape",
                        isSynthetic = true,
                        gx = 0.015f,
                        gy = 0.01f,
                        gz = 0.01f,
                        hasGyroscope = true
                    )
                }
            )
        )

        // 2. WALKING held-out windows
        list.add(
            HeldOutLabeledWindow(
                id = "WALKING_STEADY_CADENCE",
                groundTruthCategory = MovementCategory.WALKING,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02
                    val phase = 2.0 * Math.PI * 1.85 * tSec
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = (1.4 * sin(phase * 0.5)).toFloat(),
                        ay = (9.81 + 2.9 * cos(phase) + 0.6 * sin(2 * phase)).toFloat(),
                        az = (1.1 * sin(phase)).toFloat(),
                        sensorType = "Held-Out Walking Steady",
                        isSynthetic = true,
                        gx = (0.45 * sin(phase)).toFloat(),
                        gy = (0.35 * cos(phase * 0.5)).toFloat(),
                        gz = (0.25 * sin(phase)).toFloat(),
                        hasGyroscope = true
                    )
                }
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "WALKING_BRISK_ROTATED",
                groundTruthCategory = MovementCategory.WALKING,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02
                    val phase = 2.0 * Math.PI * 2.05 * tSec
                    // Phone carried flat in bag/jacket (Z carries gravity)
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = (1.5 * sin(phase)).toFloat(),
                        ay = (1.3 * sin(phase * 0.5)).toFloat(),
                        az = (9.81 + 3.2 * cos(phase) + 0.7 * sin(2 * phase)).toFloat(),
                        sensorType = "Held-Out Walking Brisk",
                        isSynthetic = true,
                        gx = (0.40 * cos(phase * 0.5)).toFloat(),
                        gy = (0.48 * sin(phase)).toFloat(),
                        gz = (0.25 * sin(phase)).toFloat(),
                        hasGyroscope = true
                    )
                }
            )
        )

        // 3. SITTING_DOWN held-out windows
        list.add(
            HeldOutLabeledWindow(
                id = "SITTING_DOWN_CHAIR",
                groundTruthCategory = MovementCategory.SITTING_DOWN,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02
                    val dt = tSec - 0.85
                    val (ax, ay, az) = when {
                        dt in -0.25..0.15 -> {
                            val unload = -2.6 * exp(-((dt + 0.05) / 0.12) * ((dt + 0.05) / 0.12))
                            Triple(0.8f, ((9.81 + unload) * cos(0.35)).toFloat(), ((9.81 + unload) * sin(0.35)).toFloat())
                        }
                        dt in 0.15..0.50 -> {
                            val decel = 6.5 * exp(-((dt - 0.28) / 0.11) * ((dt - 0.28) / 0.11))
                            Triple(0.5f, ((9.81 + decel) * cos(0.55)).toFloat(), ((9.81 + decel) * sin(0.55)).toFloat())
                        }
                        dt > 0.50 -> {
                            Triple(0.2f, (9.81 * cos(0.65)).toFloat(), (9.81 * sin(0.65)).toFloat())
                        }
                        else -> Triple(0.15f, 9.81f, 0.2f)
                    }
                    val gx = when {
                        dt in -0.25..0.15 -> (1.15 * exp(-((dt + 0.05) / 0.14) * ((dt + 0.05) / 0.14))).toFloat()
                        dt in 0.15..0.50 -> (-0.65 * exp(-((dt - 0.30) / 0.12) * ((dt - 0.30) / 0.12))).toFloat()
                        else -> 0.03f
                    }
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = ax,
                        ay = ay,
                        az = az,
                        sensorType = "Held-Out Sitting Down",
                        isSynthetic = true,
                        gx = gx,
                        gy = 0.02f,
                        gz = 0.01f,
                        hasGyroscope = true
                    )
                }
            )
        )

        // 4. STAIR_MOVEMENT held-out windows
        list.add(
            HeldOutLabeledWindow(
                id = "STAIR_DESCENT_CONTINUOUS",
                groundTruthCategory = MovementCategory.STAIR_MOVEMENT,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02
                    val phase = 2.0 * Math.PI * 1.55 * tSec
                    val c = cos(phase)
                    val strike = if (c > 0) 9.5 * c * c * c else 0.0
                    val dip = if (c < 0) -3.2 * c * c else 0.0
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = (1.6 * sin(phase * 0.5)).toFloat(),
                        ay = (9.81 + strike + dip).toFloat(),
                        az = (2.4 * cos(phase)).toFloat(),
                        sensorType = "Held-Out Stair Descent",
                        isSynthetic = true,
                        gx = (0.75 * sin(phase)).toFloat(),
                        gy = (0.40 * cos(phase)).toFloat(),
                        gz = (0.30 * sin(phase * 0.5)).toFloat(),
                        hasGyroscope = true
                    )
                }
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "STAIR_ASCENT_CONTINUOUS",
                groundTruthCategory = MovementCategory.STAIR_MOVEMENT,
                shouldTriggerFallCheckIn = false,
                samples = List(200) { i ->
                    val tSec = i * 0.02
                    val phase = 2.0 * Math.PI * 1.60 * tSec
                    val s = max(0.0, sin(phase))
                    val push = 6.2 * s * s
                    MotionSample(
                        timestampNanos = baseTs + i * stepNs,
                        ax = (1.6 * sin(phase * 0.5)).toFloat(),
                        ay = (9.81 + push - 1.8 * cos(phase)).toFloat(),
                        az = (2.4 * cos(phase)).toFloat(),
                        sensorType = "Held-Out Stair Ascent",
                        isSynthetic = true,
                        gx = (0.75 * sin(phase)).toFloat(),
                        gy = (0.40 * cos(phase)).toFloat(),
                        gz = (0.30 * sin(phase * 0.5)).toFloat(),
                        hasGyroscope = true
                    )
                }
            )
        )

        // 5. POSSIBLE_FALL held-out windows (including classic trip, backward slip, AND gradual syncope collapse)
        list.add(
            HeldOutLabeledWindow(
                id = "FALL_FORWARD_TRIP_STILLNESS",
                groundTruthCategory = MovementCategory.POSSIBLE_FALL,
                shouldTriggerFallCheckIn = true,
                samples = generateRealisticFallWindow(baseTs, stepNs, minFreeFallMs2 = 1.8f, peakImpactMs2 = 58.0f)
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "FALL_BACKWARD_SLIP_STILLNESS",
                groundTruthCategory = MovementCategory.POSSIBLE_FALL,
                shouldTriggerFallCheckIn = true,
                samples = generateRealisticFallWindow(baseTs, stepNs, minFreeFallMs2 = 2.2f, peakImpactMs2 = 62.0f)
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "FALL_GRADUAL_SYNCOPE_NO_CLASSIC_FREEFALL",
                groundTruthCategory = MovementCategory.POSSIBLE_FALL,
                shouldTriggerFallCheckIn = true,
                // Min magnitude stays ~6.8 m/s² (> 6.0 m/s² rule threshold!), so 3-Phase Rule misses it while 1D CNN catches it!
                samples = generateRealisticFallWindow(baseTs, stepNs, minFreeFallMs2 = 6.8f, peakImpactMs2 = 48.0f)
            )
        )

        // 6. UNKNOWN_UNCERTAIN / NON-FALL ANOMALY held-out windows
        list.add(
            HeldOutLabeledWindow(
                id = "NON_FALL_TABLE_PLACEMENT",
                groundTruthCategory = MovementCategory.UNKNOWN_UNCERTAIN,
                shouldTriggerFallCheckIn = false,
                samples = SyntheticSensorTraces.generatePlacingOnTable(durationMs = 4000L, stepMs = 20L)
            )
        )
        list.add(
            HeldOutLabeledWindow(
                id = "NON_FALL_IMPACT_WITH_CONTINUOUS_MOTION",
                groundTruthCategory = MovementCategory.UNKNOWN_UNCERTAIN,
                shouldTriggerFallCheckIn = false,
                samples = SyntheticSensorTraces.generateDipImpact65ContinuousMovement(stepMs = 20L)
            )
        )

        return list
    }

    private fun generateRealisticFallWindow(
        baseTs: Long,
        stepNs: Long,
        minFreeFallMs2: Float,
        peakImpactMs2: Float
    ): List<MotionSample> {
        return List(200) { i ->
            val tSec = i * 0.02
            val dt = tSec - 0.70
            var gx = 0.01f
            var gy = 0.01f
            val (ax, ay, az) = when {
                dt < 0.0 -> {
                    Triple(
                        (0.4 * sin(2.0 * Math.PI * 1.5 * tSec)).toFloat(),
                        (9.81 + 0.8 * cos(2.0 * Math.PI * 1.5 * tSec)).toFloat(),
                        0.3f
                    )
                }
                dt in 0.0..0.14 -> {
                    gx = 3.5f
                    gy = 2.1f
                    Triple(0.3f * minFreeFallMs2, 0.9f * minFreeFallMs2, 0.25f * minFreeFallMs2)
                }
                dt in 0.14..0.28 -> {
                    val env = exp(-((dt - 0.19) / 0.032) * ((dt - 0.19) / 0.032)).toFloat()
                    val mag = 9.81f + (peakImpactMs2 - 9.81f) * env
                    gx = 4.6f * env
                    gy = 3.1f * env
                    Triple(0.25f * mag, 0.45f * mag, 0.85f * mag)
                }
                dt in 0.28..0.48 -> {
                    val damp = (3.0 * exp(-(dt - 0.28) / 0.08) * sin(35.0 * dt)).toFloat()
                    gx = (0.3 * exp(-(dt - 0.28) / 0.08)).toFloat()
                    Triple(0.2f, 1.2f + damp * 0.3f, 9.81f + damp)
                }
                else -> {
                    val noise = if (i % 2 == 0) 0.03f else -0.03f
                    Triple(0.12f, 0.45f, 9.81f + noise)
                }
            }
            MotionSample(
                timestampNanos = baseTs + i * stepNs,
                ax = ax,
                ay = ay,
                az = az,
                sensorType = "Held-Out Fall Trace",
                isSynthetic = true,
                gx = gx,
                gy = gy,
                gz = 0.01f,
                hasGyroscope = true
            )
        }
    }
}

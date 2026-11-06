package com.example.sensor

import android.util.Log
import com.example.domain.CandidateEventSummary
import com.example.domain.MotionFeatures
import com.example.domain.MotionSample
import java.util.UUID
import kotlin.math.sqrt

// ============================================================================
// TUNABLE FALL DETECTION THRESHOLDS & TIMINGS
// All acceleration values are in m/s² (Earth gravity ≈ 9.81 m/s²).
// Modify these constants to tune sensitivity, window timings, and thresholds.
// ============================================================================

/** Acceleration magnitude below which free-fall begins (m/s²). Earth resting gravity ≈ 9.81 m/s². */
const val FREE_FALL_THRESHOLD: Float = 6.0f

/** Minimum continuous duration that acceleration magnitude must stay below [FREE_FALL_THRESHOLD] (milliseconds). */
const val FREE_FALL_MIN_DURATION_MS: Long = 40L

/** Maximum allowed time window between the end of free-fall and the impact peak (milliseconds). */
const val IMPACT_WINDOW_MS: Long = 1000L

/** Acceleration magnitude threshold required during the impact phase (m/s²). Default is 30.0 m/s² (~3.06g on Earth). */
const val IMPACT_THRESHOLD: Float = 30.0f

/** High-sensitivity acceleration magnitude impact threshold (m/s²). ~2.75g on Earth. */
const val SENSITIVE_IMPACT_THRESHOLD: Float = 27.0f

/** Post-impact physical rebound & damping window (milliseconds) allowing the device to settle before requiring stillness. */
const val POST_IMPACT_REBOUND_WINDOW_MS: Long = 500L

/** Maximum transient single-sample outliers allowed during the 2.5s stillness window before rejecting as active movement. */
const val MAX_STILLNESS_TRANSIENT_OUTLIERS: Int = 3

/** Post-impact observation duration during which the device must remain still (milliseconds). */
const val STILLNESS_MS: Long = 2500L

/** Nominal Earth gravity acceleration magnitude at rest (m/s²). */
const val NOMINAL_GRAVITY_MS2: Float = 9.81f

/** Maximum allowed deviation from nominal gravity during stillness phase (m/s²). Magnitude must stay in 9.81 ± 2.0 m/s² (7.81 to 11.81 m/s²). */
const val STILLNESS_TOLERANCE_MS2: Float = 2.0f

/** Maximum allowed standard deviation of acceleration magnitude during the 2.5s stillness phase (m/s²). */
const val STILLNESS_MAX_STD_DEV_MS2: Float = 1.0f

/** Minimum number of consecutive stillness samples required before confirming candidate fall. */
const val STILLNESS_MIN_SAMPLES: Int = 10

/** Cooldown duration after a candidate fall or user answer before a new candidate can be triggered (milliseconds). */
const val COOLDOWN_MS: Long = 30_000L

/**
 * Configurable multi-stage fall detection parameters.
 */
data class DetectorConfig(
    val maxBufferCapacity: Int = 180,
    val windowDurationNanos: Long = 4_000_000_000L,
    val minSamplesRequired: Int = 10,
    val freeFallThreshold: Float = FREE_FALL_THRESHOLD,
    val freeFallMinDurationMs: Long = FREE_FALL_MIN_DURATION_MS,
    val impactWindowMs: Long = IMPACT_WINDOW_MS,
    val impactThreshold: Float = IMPACT_THRESHOLD,
    val stillnessMs: Long = STILLNESS_MS,
    val nominalGravityMs2: Float = NOMINAL_GRAVITY_MS2,
    val stillnessToleranceMs2: Float = STILLNESS_TOLERANCE_MS2,
    val stillnessMaxStdDevMs2: Float = STILLNESS_MAX_STD_DEV_MS2,
    val cooldownDurationNanos: Long = COOLDOWN_MS * 1_000_000L
)

data class DetectionEvaluation(
    val features: MotionFeatures,
    val candidateEvent: CandidateEventSummary? = null,
    val candidateSummary: CandidateEventSummary? = candidateEvent
)

/**
 * Quantitative evaluation report measuring fall-detection sensitivity, false-positive rate,
 * and detection delay across positive fall scenarios and negative everyday-motion scenarios.
 */
data class FallDetectionBenchmarkReport(
    val truePositives: Int,
    val falseNegatives: Int,
    val trueNegatives: Int,
    val falsePositives: Int,
    val sensitivity: Float,
    val falsePositiveRate: Float,
    val meanDetectionDelayMs: Long,
    val scenarioOutcomes: Map<String, Boolean>
)

/**
 * 3-Phase Sequential Fall Detector enforcing strict physical ordering:
 *
 * 1. FREE-FALL: Magnitude below [FREE_FALL_THRESHOLD] (6.0 m/s²) for at least [FREE_FALL_MIN_DURATION_MS] (40 ms).
 * 2. IMPACT: Within [IMPACT_WINDOW_MS] (1000 ms) after Phase 1, magnitude above [IMPACT_THRESHOLD] (30.0 m/s² / ~3.06g, or 27.0 m/s² / ~2.75g).
 *    (Capped to 90% of max range if hardware accelerometer range < configured threshold).
 * 3. STILLNESS: For [STILLNESS_MS] (2500 ms) after impact, magnitude stays within 9.81 ± 2.0 m/s² with low variation.
 *
 * An impact alone NEVER triggers an alert; all three phases are strictly required in order.
 * Processing is purely local with zero external AI model calls per sensor reading.
 */
class CandidateFallDetector(
    private val config: DetectorConfig = DetectorConfig(),
    sensorMaxRange: Float? = null
) {
    enum class PhaseState {
        SEEKING_FREE_FALL,
        IN_FREE_FALL,
        WAITING_FOR_IMPACT,
        OBSERVING_STILLNESS
    }

    private val ringBuffer = ArrayDeque<MotionSample>(config.maxBufferCapacity)

    private var phaseState = PhaseState.SEEKING_FREE_FALL
    private var freeFallStartNanos: Long = 0L
    private var freeFallEndNanos: Long = 0L
    private var minFreeFallMagnitude: Float = Float.MAX_VALUE

    private var impactPeakMagnitude: Float = 0f
    private var impactTimestampNanos: Long = 0L

    private var stillnessStartNanos: Long = 0L
    private val stillnessMagnitudes = mutableListOf<Float>()
    private var stillnessOutlierCount: Int = 0

    private var lastTriggeredTimestampNanos: Long? = null
    private var effectiveImpactThreshold: Float = config.impactThreshold
    private var lastDecisionReason: String = "Monitoring active — awaiting free-fall (< ${config.freeFallThreshold} m/s² for >= ${config.freeFallMinDurationMs}ms)"

    init {
        setSensorMaximumRange(sensorMaxRange)
    }

    /**
     * Checks the device's accelerometer maximum range (Sensor.getMaximumRange()).
     * If it is below [config.impactThreshold] (default 30.0 m/s²), logs a warning and caps impact threshold to 90% of that maximum.
     */
    fun setSensorMaximumRange(maxRange: Float?) {
        if (maxRange != null && maxRange > 0f && maxRange < config.impactThreshold) {
            safeLogWarning(
                "CandidateFallDetector",
                "Accelerometer maximum range (${maxRange} m/s²) is below configured impact threshold (${config.impactThreshold} m/s²). " +
                    "Capping impact threshold to 90% of max range (${maxRange * 0.90f} m/s²)."
            )
            effectiveImpactThreshold = maxRange * 0.90f
        } else {
            effectiveImpactThreshold = config.impactThreshold
        }
    }

    private fun safeLogWarning(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {
            println("[$tag] WARN: $message")
        }
    }

    private fun debugLog(message: String) {
        try {
            Log.d("SoloSignalFallDetector", message)
        } catch (_: Throwable) {
        }
    }

    fun getEffectiveImpactThreshold(): Float = effectiveImpactThreshold

    fun getPhaseState(): PhaseState = phaseState

    fun reset() {
        ringBuffer.clear()
        lastTriggeredTimestampNanos = null
        lastDecisionReason = "Monitoring active — awaiting free-fall (< ${config.freeFallThreshold} m/s² for >= ${config.freeFallMinDurationMs}ms)"
        resetPhases()
    }

    fun clearWindowKeepCooldown() {
        ringBuffer.clear()
        resetPhases()
    }

    /**
     * Enforces a 30-second cooldown after a candidate fall or after the user answers.
     */
    fun applyCooldown(timestampNanos: Long = System.nanoTime()) {
        lastTriggeredTimestampNanos = timestampNanos
        lastDecisionReason = "Cooldown active (30s) — preventing duplicate candidate alerts"
        resetPhases()
    }

    private fun resetPhases() {
        phaseState = PhaseState.SEEKING_FREE_FALL
        freeFallStartNanos = 0L
        freeFallEndNanos = 0L
        minFreeFallMagnitude = Float.MAX_VALUE
        impactPeakMagnitude = 0f
        impactTimestampNanos = 0L
        stillnessStartNanos = 0L
        stillnessMagnitudes.clear()
        stillnessOutlierCount = 0
    }

    fun getBufferedSamples(): List<MotionSample> = ringBuffer.toList()

    fun getRecentMagnitudes(maxCount: Int = 60): List<Float> {
        val list = ringBuffer.toList()
        return list.takeLast(maxCount).map { it.magnitude }
    }

    fun processSample(
        sample: MotionSample,
        nowElapsedMillis: Long = System.currentTimeMillis()
    ): DetectionEvaluation {
        return addSample(sample, nowElapsedMillis)
    }

    fun addSample(
        sample: MotionSample,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): DetectionEvaluation {
        val previousSample = ringBuffer.lastOrNull()
        if (previousSample != null && sample.timestampNanos < previousSample.timestampNanos) {
            ringBuffer.clear()
            resetPhases()
        }

        if (ringBuffer.size >= config.maxBufferCapacity) {
            ringBuffer.removeFirst()
        }
        ringBuffer.addLast(sample)

        val cutoffNanos = sample.timestampNanos - config.windowDurationNanos
        while (ringBuffer.size > 1 && ringBuffer.first().timestampNanos < cutoffNanos) {
            ringBuffer.removeFirst()
        }

        val m = sample.magnitude
        val t = sample.timestampNanos

        // Check 30-second cooldown
        val inCooldown = lastTriggeredTimestampNanos?.let { lastTs ->
            val elapsedNanos = t - lastTs
            elapsedNanos in 0 until config.cooldownDurationNanos
        } ?: false

        if (inCooldown) {
            resetPhases()
            lastDecisionReason = "Rejected: Cooldown active (30s) after previous candidate fall"
            return DetectionEvaluation(
                features = buildMotionFeatures(
                    sample = sample,
                    summary = "Cooldown active (30s) — preventing duplicate alerts"
                ),
                candidateEvent = null
            )
        }

        var rejectedAsTable = false

        // ====================================================================
        // 3-PHASE SEQUENTIAL FALL DETECTION STATE MACHINE
        // ====================================================================
        when (phaseState) {
            PhaseState.SEEKING_FREE_FALL -> {
                if (m < config.freeFallThreshold) {
                    phaseState = PhaseState.IN_FREE_FALL
                    freeFallStartNanos = t
                    freeFallEndNanos = 0L
                    minFreeFallMagnitude = m
                    lastDecisionReason = "Phase 1 started: Free-fall dip (${"%.1f".format(m)} m/s² < ${config.freeFallThreshold} m/s²)"
                    debugLog("Phase 1 (Free-Fall) started: magnitude=${"%.2f".format(m)} m/s² < threshold=${config.freeFallThreshold} m/s²")
                } else if (m >= effectiveImpactThreshold) {
                    // An impact alone without prior free-fall NEVER triggers an alert.
                    rejectedAsTable = true
                    lastDecisionReason = "Rejected: Impact peak (${"%.1f".format(m)} m/s²) had no preceding free-fall phase (< ${config.freeFallThreshold} m/s² for >= ${config.freeFallMinDurationMs}ms)"
                }
            }

            PhaseState.IN_FREE_FALL -> {
                if (m < config.freeFallThreshold) {
                    if (m < minFreeFallMagnitude) {
                        minFreeFallMagnitude = m
                    }
                    val currentDurationMs = (t - freeFallStartNanos) / 1_000_000L
                    if (currentDurationMs >= config.freeFallMinDurationMs) {
                        // Satisfied minimum 40 ms free-fall requirement
                        freeFallEndNanos = t
                        lastDecisionReason = "Phase 1 verified (${currentDurationMs}ms free-fall, min ${"%.1f".format(minFreeFallMagnitude)} m/s²) — awaiting impact >= ${"%.1f".format(effectiveImpactThreshold)} m/s²"
                    }
                } else {
                    // Magnitude rose >= 6.0 m/s²
                    val totalDurationMs = (t - freeFallStartNanos) / 1_000_000L
                    if (freeFallEndNanos != 0L || totalDurationMs >= config.freeFallMinDurationMs) {
                        // Phase A successfully met!
                        if (freeFallEndNanos == 0L) {
                            freeFallEndNanos = t
                        }
                        if (m >= effectiveImpactThreshold) {
                            // Phase B IMPACT met immediately on this transition sample!
                            phaseState = PhaseState.OBSERVING_STILLNESS
                            impactPeakMagnitude = m
                            impactTimestampNanos = t
                            stillnessStartNanos = 0L
                            stillnessMagnitudes.clear()
                            stillnessOutlierCount = 0
                            lastDecisionReason = "Phase 2 verified: Impact peak ${"%.1f".format(m)} m/s² (>= ${"%.1f".format(effectiveImpactThreshold)} m/s²) — observing 2.5s stillness"
                            debugLog("Phase 2 (Impact) met on transition: peak=${"%.2f".format(m)} m/s² >= threshold=${effectiveImpactThreshold} m/s². Observing stillness.")
                        } else {
                            phaseState = PhaseState.WAITING_FOR_IMPACT
                            lastDecisionReason = "Phase 1 verified (${totalDurationMs}ms free-fall) — awaiting impact >= ${"%.1f".format(effectiveImpactThreshold)} m/s² within ${config.impactWindowMs}ms"
                            debugLog("Phase 1 verified (free-fall ${totalDurationMs}ms). Awaiting impact >= ${effectiveImpactThreshold} m/s² within ${config.impactWindowMs}ms...")
                        }
                    } else {
                        // Free fall was too short (< 40 ms)
                        lastDecisionReason = "Rejected: Free-fall dip too brief (${totalDurationMs}ms < ${config.freeFallMinDurationMs}ms required)"
                        debugLog("Phase 1 reset: free-fall duration=${totalDurationMs}ms < required=${config.freeFallMinDurationMs}ms (minMag=${"%.2f".format(minFreeFallMagnitude)} m/s²)")
                        resetPhases()
                    }
                }
            }

            PhaseState.WAITING_FOR_IMPACT -> {
                val elapsedSinceFreeFallMs = (t - freeFallEndNanos) / 1_000_000L
                if (elapsedSinceFreeFallMs > config.impactWindowMs) {
                    // Expired: > 1000 ms elapsed after free-fall without >= threshold impact
                    lastDecisionReason = "Rejected: No impact >= ${"%.1f".format(effectiveImpactThreshold)} m/s² within ${config.impactWindowMs}ms after free-fall"
                    debugLog("Phase 2 expired: ${elapsedSinceFreeFallMs}ms elapsed without impact >= ${effectiveImpactThreshold} m/s²")
                    resetPhases()
                    if (m < config.freeFallThreshold) {
                        phaseState = PhaseState.IN_FREE_FALL
                        freeFallStartNanos = t
                        freeFallEndNanos = 0L
                        minFreeFallMagnitude = m
                    }
                } else if (m >= effectiveImpactThreshold) {
                    // Phase B IMPACT satisfied within 1000 ms!
                    phaseState = PhaseState.OBSERVING_STILLNESS
                    impactPeakMagnitude = m
                    impactTimestampNanos = t
                    stillnessStartNanos = 0L
                    stillnessMagnitudes.clear()
                    stillnessOutlierCount = 0
                    lastDecisionReason = "Phase 2 verified: Impact peak ${"%.1f".format(m)} m/s² (>= ${"%.1f".format(effectiveImpactThreshold)} m/s²) — observing 2.5s stillness"
                    debugLog("Phase 2 (Impact) detected: peak=${"%.2f".format(m)} m/s² >= threshold=${effectiveImpactThreshold} m/s² (after ${elapsedSinceFreeFallMs}ms). Observing stillness for ${config.stillnessMs}ms...")
                } else if (m < config.freeFallThreshold) {
                    // New free fall dip began
                    phaseState = PhaseState.IN_FREE_FALL
                    freeFallStartNanos = t
                    freeFallEndNanos = 0L
                    minFreeFallMagnitude = m
                }
            }

            PhaseState.OBSERVING_STILLNESS -> {
                val dtSinceImpactMs = (t - impactTimestampNanos) / 1_000_000L
                if (m >= effectiveImpactThreshold && dtSinceImpactMs <= POST_IMPACT_REBOUND_WINDOW_MS) {
                    // Peak impact impulse duration (within rebound window)
                    if (m > impactPeakMagnitude) {
                        impactPeakMagnitude = m
                    }
                    impactTimestampNanos = t
                    stillnessStartNanos = 0L
                    stillnessMagnitudes.clear()
                    stillnessOutlierCount = 0
                } else {
                    val minStillBound = config.nominalGravityMs2 - config.stillnessToleranceMs2 // 7.81 m/s²
                    val maxStillBound = config.nominalGravityMs2 + config.stillnessToleranceMs2 // 11.81 m/s²

                    if (m in minStillBound..maxStillBound) {
                        if (stillnessStartNanos == 0L) {
                            stillnessStartNanos = t
                        }
                        stillnessMagnitudes.add(m)

                        val stillnessDurationMs = (t - stillnessStartNanos) / 1_000_000L
                        lastDecisionReason = "Phase 3 (Stillness): ${stillnessDurationMs}ms / ${config.stillnessMs}ms post-impact rest observed"
                        if (stillnessMagnitudes.size >= STILLNESS_MIN_SAMPLES && stillnessDurationMs >= config.stillnessMs) {
                            // Compute variation across the stillness window
                            val mean = stillnessMagnitudes.average().toFloat()
                            var varianceSum = 0f
                            for (valMag in stillnessMagnitudes) {
                                val diff = valMag - mean
                                varianceSum += diff * diff
                            }
                            val stdDev = sqrt(varianceSum / stillnessMagnitudes.size)

                            if (stdDev <= config.stillnessMaxStdDevMs2) {
                                // ALL THREE PHASES A, B, AND C PASSED IN ORDER!
                                lastTriggeredTimestampNanos = t
                                val ffDurationMs = maxOf(config.freeFallMinDurationMs, (freeFallEndNanos - freeFallStartNanos) / 1_000_000L)

                                val candidate = CandidateEventSummary(
                                    eventId = UUID.randomUUID().toString(),
                                    detectedAtEpochMillis = currentEpochMillis,
                                    peakMagnitude = impactPeakMagnitude,
                                    minMagnitude = minFreeFallMagnitude,
                                    suddenChangeCount = 1,
                                    orientationChangeDeg = 0f,
                                    postImpactStillnessStdDev = stdDev,
                                    triggerSource = if (sample.isSynthetic) {
                                        "Synthetic Trace (${sample.sensorType})"
                                    } else {
                                        "Live Accelerometer (TYPE_ACCELEROMETER)"
                                    },
                                    summaryNote = "Possible fall detected: free-fall (${ffDurationMs}ms, min ${"%.1f".format(minFreeFallMagnitude)} m/s²) → " +
                                        "impact (${"%.1f".format(impactPeakMagnitude)} m/s², ${"%.2f".format(impactPeakMagnitude / config.nominalGravityMs2)}g) → " +
                                        "stillness (${stillnessDurationMs}ms, σ=${"%.2f".format(stdDev)} m/s²).",
                                    peakDynamicAccelerationMs2 = maxOf(0f, impactPeakMagnitude - config.nominalGravityMs2),
                                    peakGyroDegPerSec = sample.gyroMagnitudeDegPerSec,
                                    impactDurationMs = dtSinceImpactMs.coerceIn(20L, POST_IMPACT_REBOUND_WINDOW_MS),
                                    detectionDelayMs = stillnessDurationMs
                                )

                                lastDecisionReason = "Accepted (${if (sample.isSynthetic) "SIMULATED" else "LIVE"}): Free-fall (${ffDurationMs}ms) → Impact (${"%.1f".format(impactPeakMagnitude)} m/s²) → Stillness (${stillnessDurationMs}ms, σ=${"%.2f".format(stdDev)} m/s²)"
                                debugLog("Candidate fall confirmed! EventId=${candidate.eventId}, Impact=${"%.2f".format(candidate.peakMagnitude)} m/s², Stillness StdDev=${"%.2f".format(stdDev)}")
                                resetPhases()

                                val evalFeatures = buildMotionFeatures(
                                    sample = sample,
                                    summary = "Possible fall validated: ${"%.1f".format(candidate.peakMagnitude)} m/s² impact followed by stillness",
                                    rejectedTable = false
                                )
                                return DetectionEvaluation(
                                    features = evalFeatures,
                                    candidateEvent = candidate
                                )
                            } else {
                                // High variation in the stillness window (device still moving) -> Fail
                                lastDecisionReason = "Rejected: High post-impact variance (σ=${"%.2f".format(stdDev)} > ${config.stillnessMaxStdDevMs2} m/s²)"
                                debugLog("Phase 3 (Stillness) failed: high variance (σ=${"%.2f".format(stdDev)} > ${config.stillnessMaxStdDevMs2} m/s²). Resetting.")
                                resetPhases()
                            }
                        }
                    } else {
                        // Magnitude outside 9.81 ± 2.0 m/s²
                        if (dtSinceImpactMs <= POST_IMPACT_REBOUND_WINDOW_MS) {
                            // Transient physical rebound or ringing immediately after impact
                        } else {
                            stillnessOutlierCount += 1
                            if (stillnessOutlierCount > MAX_STILLNESS_TRANSIENT_OUTLIERS) {
                                // Active continuous movement after impact (walking, running, shaking) -> Stillness check fails
                                lastDecisionReason = "Rejected: Active movement after impact (${"%.1f".format(m)} m/s² outside ${"%.1f".format(minStillBound)}..${"%.1f".format(maxStillBound)} m/s²)"
                                debugLog("Phase 3 (Stillness) failed: movement outside range (${"%.2f".format(m)} m/s² outside ${"%.2f".format(minStillBound)}..${"%.2f".format(maxStillBound)} at ${dtSinceImpactMs}ms after impact). Resetting.")
                                resetPhases()
                            }
                        }
                    }
                }
            }
        }

        val statusDescription = when (phaseState) {
            PhaseState.SEEKING_FREE_FALL -> "Monitoring: normal movement (${"%.1f".format(m)} m/s²)"
            PhaseState.IN_FREE_FALL -> "Phase 1: Free-fall dip observed (${"%.1f".format(m)} m/s²)"
            PhaseState.WAITING_FOR_IMPACT -> "Phase 2: Free-fall verified; awaiting impact within 1.0s..."
            PhaseState.OBSERVING_STILLNESS -> "Phase 3: Impact ${"%.1f".format(impactPeakMagnitude)} m/s² detected; observing 2.5s stillness..."
        }

        val features = buildMotionFeatures(
            sample = sample,
            summary = statusDescription,
            rejectedTable = rejectedAsTable
        )
        return DetectionEvaluation(features = features, candidateEvent = null)
    }

    private fun buildMotionFeatures(
        sample: MotionSample,
        summary: String,
        rejectedTable: Boolean = false
    ): MotionFeatures {
        val samples = ringBuffer.toList()
        val count = samples.size
        val peakMag = if (samples.isNotEmpty()) samples.maxOf { it.magnitude } else sample.magnitude
        val minMag = if (samples.isNotEmpty()) samples.minOf { it.magnitude } else sample.magnitude
        val meanMag = if (samples.isNotEmpty()) samples.map { it.magnitude }.average().toFloat() else sample.magnitude

        return MotionFeatures(
            windowSampleCount = count,
            windowDurationMs = if (samples.size > 1) {
                (samples.last().timestampNanos - samples.first().timestampNanos) / 1_000_000L
            } else 0L,
            currentMagnitude = sample.magnitude,
            deltaMagnitude = 0f,
            peakMagnitude = peakMag,
            minMagnitude = minMag,
            meanMagnitude = meanMag,
            stdDevMagnitude = 0f,
            suddenChangeCount = if (phaseState != PhaseState.SEEKING_FREE_FALL) 1 else 0,
            orientationChangeDeg = 0f,
            postPeakStillnessStdDev = 0f,
            hasPostPeakWindow = phaseState == PhaseState.OBSERVING_STILLNESS,
            sampleQualityGood = count >= 5,
            evaluationSummary = summary,
            peakDynamicAccelerationMs2 = maxOf(0f, peakMag - config.nominalGravityMs2),
            peakGyroDegPerSec = sample.gyroMagnitudeDegPerSec,
            impactDurationMs = 50L,
            impactImpulseMs2Ms = 0f,
            rejectedAsTablePlacement = rejectedTable,
            rejectedAsRhythmicActivity = false,
            currentAx = sample.ax,
            currentAy = sample.ay,
            currentAz = sample.az,
            currentMagnitudeG = sample.magnitudeG,
            isSyntheticSample = sample.isSynthetic,
            sensorTypeLabel = sample.sensorType,
            effectiveImpactThresholdMs2 = effectiveImpactThreshold,
            detectorPhase = phaseState.name,
            lastDecisionReason = lastDecisionReason
        )
    }

    companion object {
        const val PEAK_IMPACT_THRESHOLD_MS2 = IMPACT_THRESHOLD

        /**
         * Evaluates sensitivity, false-positive rate, and mean detection delay across all
         * [SyntheticScenario] test traces using the supplied [DetectorConfig].
         */
        fun evaluateBenchmarkSuite(config: DetectorConfig = DetectorConfig()): FallDetectionBenchmarkReport {
            var tp = 0
            var fn = 0
            var tn = 0
            var fp = 0
            val delaysMs = mutableListOf<Long>()
            val outcomes = LinkedHashMap<String, Boolean>()

            for (scenario in SyntheticScenario.entries) {
                val detector = CandidateFallDetector(config)
                val samples = SyntheticSensorTraces.generateTrace(scenario)
                var triggeredCandidate: CandidateEventSummary? = null

                for (sample in samples) {
                    val eval = detector.addSample(sample, sample.timestampNanos / 1_000_000L)
                    if (eval.candidateEvent != null && triggeredCandidate == null) {
                        triggeredCandidate = eval.candidateEvent
                    }
                }

                val didTrigger = triggeredCandidate != null
                outcomes[scenario.name] = didTrigger

                if (scenario.shouldTriggerCandidateCheckIn) {
                    if (didTrigger) {
                        tp++
                        delaysMs.add(triggeredCandidate.detectionDelayMs)
                    } else {
                        fn++
                    }
                } else {
                    if (didTrigger) {
                        fp++
                    } else {
                        tn++
                    }
                }
            }

            val sensitivity = if (tp + fn > 0) tp.toFloat() / (tp + fn).toFloat() else 0f
            val fpr = if (fp + tn > 0) fp.toFloat() / (fp + tn).toFloat() else 0f
            val meanDelay = if (delaysMs.isNotEmpty()) delaysMs.average().toLong() else 0L

            return FallDetectionBenchmarkReport(
                truePositives = tp,
                falseNegatives = fn,
                trueNegatives = tn,
                falsePositives = fp,
                sensitivity = sensitivity,
                falsePositiveRate = fpr,
                meanDetectionDelayMs = meanDelay,
                scenarioOutcomes = outcomes
            )
        }
    }
}

package com.example.sensor

import android.content.Context
import com.example.domain.CandidateEventSummary
import com.example.domain.MlExplainabilityRecord
import com.example.domain.ModelReadinessStatus
import com.example.domain.MotionFeatures
import com.example.domain.MotionSample
import com.example.domain.MovementCategory
import com.example.domain.PersonalCalibrationDecision
import com.example.domain.PersonalMovementProfile
import com.example.domain.SensorWindowSnapshot
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

/**
 * Live runtime Hybrid Fall & Motion Detection Engine combining:
 * 1. Layer 1 — Primary On-Device LiteRT 1D CNN (`LiteRtMotionClassifier`) classifying 4.0s (200-sample @ 50Hz)
 *    orientation-invariant windows across 6 movement categories with temperature-calibrated confidence,
 *    plus Deterministic 3-Phase Rule Safety Fallback (`CandidateFallDetector`).
 * 2. Layer 2 — User's local `PersonalMovementProfile` learned from verified post-check-in feedback.
 * 3. Layer 3 — `PersonalLearningEngine` combining the general model with personal prototype similarity
 *    and bounded threshold calibration while enforcing a Hard Safety Override on severe falls.
 *
 * NOTE: The Random Forest baseline is strictly isolated to `ModelComparisonEvaluator` and does NOT
 * participate in live runtime check-in decisions.
 */
class HybridFallDetectionEngine(
    context: Context? = null,
    config: DetectorConfig = DetectorConfig(),
    sensorMaxRange: Float? = null,
    val cnnClassifier: LiteRtMotionClassifier = LiteRtMotionClassifier(context)
) {
    companion object {
        const val ML_FALL_CONFIDENCE_THRESHOLD = 0.72f
        const val ML_INFERENCE_STRIDE_SAMPLES = 15
    }

    val ruleDetector = CandidateFallDetector(config, sensorMaxRange)

    private val windowBuffer = ArrayDeque<MotionSample>(240)
    private var samplesSinceLastInference = 0
    private var lastCnnResult: CnnInferenceResult? = null
    private var lastWindowSnapshot: SensorWindowSnapshot = SensorWindowSnapshot()
    private var lastExplainability: MlExplainabilityRecord? = null

    // Cached sensor window & CNN output from the most recent candidate check-in event for Layer 2 feedback learning
    @Volatile
    private var lastCandidateWindowSnapshot: SensorWindowSnapshot = SensorWindowSnapshot()
    @Volatile
    private var lastCandidateCnnResult: CnnInferenceResult? = null
    @Volatile
    private var personalMovementProfile: PersonalMovementProfile = PersonalMovementProfile()
    @Volatile
    private var lastPersonalDecision: PersonalCalibrationDecision = PersonalCalibrationDecision()

    // Callback invoked when Layer 3 suppresses a recurring non-severe false alarm
    var onRecurringFalseAlarmSuppressed: ((PersonalCalibrationDecision) -> Unit)? = null

    // Tracks high-impact events that did not have a classic <6.0 m/s² free-fall dip (e.g. gradual collapse)
    private var pendingNonFreeFallImpactNanos: Long = 0L
    private var pendingNonFreeFallPeakMs2: Float = 0f
    private var pendingNonFreeFallMinG: Float = 1.0f
    private var lastHybridTriggeredNanos: Long? = null
    private val cooldownNanos: Long = config.cooldownDurationNanos

    private var hasGyroscopeHardware: Boolean = false
    private var accelerometerMaxRangeMs2: Float? = sensorMaxRange

    fun setSensorHardwareCapabilities(hasGyroscope: Boolean, maxRangeMs2: Float?) {
        hasGyroscopeHardware = hasGyroscope
        accelerometerMaxRangeMs2 = maxRangeMs2
        ruleDetector.setSensorMaximumRange(maxRangeMs2)
    }

    fun setPersonalMovementProfile(profile: PersonalMovementProfile) {
        personalMovementProfile = profile
    }

    fun getPersonalMovementProfile(): PersonalMovementProfile = personalMovementProfile

    fun getLastPersonalCalibrationDecision(): PersonalCalibrationDecision = lastPersonalDecision

    fun getLastCandidateSensorContext(): Pair<SensorWindowSnapshot, CnnInferenceResult?> {
        val snap = if (lastCandidateWindowSnapshot.windowSampleCount > 0) {
            lastCandidateWindowSnapshot
        } else {
            lastWindowSnapshot
        }
        val cnn = lastCandidateCnnResult ?: lastCnnResult
        return snap to cnn
    }

    fun reset() {
        ruleDetector.reset()
        windowBuffer.clear()
        samplesSinceLastInference = 0
        lastCnnResult = null
        lastWindowSnapshot = SensorWindowSnapshot()
        lastExplainability = null
        pendingNonFreeFallImpactNanos = 0L
        pendingNonFreeFallPeakMs2 = 0f
        pendingNonFreeFallMinG = 1.0f
        lastHybridTriggeredNanos = null
    }

    fun applyCooldown(timestampNanos: Long = System.nanoTime()) {
        ruleDetector.applyCooldown(timestampNanos)
        lastHybridTriggeredNanos = timestampNanos
        pendingNonFreeFallImpactNanos = 0L
    }

    fun getRecentMagnitudes(maxCount: Int = 60): List<Float> =
        ruleDetector.getRecentMagnitudes(maxCount)

    fun addSample(
        sample: MotionSample,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): DetectionEvaluation = processSample(sample, currentEpochMillis)

    fun processSample(
        sample: MotionSample,
        currentEpochMillis: Long = System.currentTimeMillis()
    ): DetectionEvaluation {
        val prev = windowBuffer.lastOrNull()
        if (prev != null && sample.timestampNanos < prev.timestampNanos) {
            windowBuffer.clear()
            samplesSinceLastInference = 0
            pendingNonFreeFallImpactNanos = 0L
        }

        if (windowBuffer.size >= 240) {
            windowBuffer.removeFirst()
        }
        windowBuffer.addLast(sample)

        // Retain 4.5 seconds of history for 4.0-second 50 Hz interpolation
        val cutoffNanos = sample.timestampNanos - 4_500_000_000L
        while (windowBuffer.size > 1 && windowBuffer.first().timestampNanos < cutoffNanos) {
            windowBuffer.removeFirst()
        }

        samplesSinceLastInference++

        // 1. Run deterministic 3-Phase Rule Detector on this sample
        val ruleEval = ruleDetector.addSample(sample, currentEpochMillis)

        // Check if we are in 30-second cooldown
        val inCooldown = lastHybridTriggeredNanos?.let { lastTs ->
            val elapsed = sample.timestampNanos - lastTs
            elapsed in 0 until cooldownNanos
        } ?: false

        // Track high-energy impacts (>= effectiveImpactThreshold) for ML gradual/syncope collapse detection
        val effectiveImpact = ruleDetector.getEffectiveImpactThreshold()
        if (!inCooldown && sample.magnitude >= effectiveImpact) {
            if (pendingNonFreeFallImpactNanos == 0L || sample.magnitude > pendingNonFreeFallPeakMs2) {
                pendingNonFreeFallImpactNanos = sample.timestampNanos
                pendingNonFreeFallPeakMs2 = sample.magnitude
                pendingNonFreeFallMinG = windowBuffer.minOfOrNull { it.magnitudeG } ?: sample.magnitudeG
            }
        }

        val postImpactElapsedMs = if (pendingNonFreeFallImpactNanos > 0L) {
            (sample.timestampNanos - pendingNonFreeFallImpactNanos) / 1_000_000L
        } else {
            0L
        }

        val mlNonFreeFallReady = pendingNonFreeFallImpactNanos > 0L && postImpactElapsedMs in 2500L..3200L

        val shouldRunCnnNow = ruleEval.candidateEvent != null ||
            mlNonFreeFallReady ||
            (windowBuffer.size >= 15 && samplesSinceLastInference >= ML_INFERENCE_STRIDE_SAMPLES)

        if (shouldRunCnnNow) {
            samplesSinceLastInference = 0
            val preprocessed = MotionWindowPreprocessor.preprocess(
                samples = windowBuffer.toList(),
                hasGyroscopeHardware = hasGyroscopeHardware || sample.hasGyroscope,
                accelerometerMaxRangeMs2 = accelerometerMaxRangeMs2
            )
            lastWindowSnapshot = preprocessed.snapshot
            lastCnnResult = cnnClassifier.classify(preprocessed)
        }

        val cnn = lastCnnResult
        val activeProfile = personalMovementProfile

        // ====================================================================
        // CASE 1: 3-Phase Rule Detector confirmed Free-Fall -> Impact -> 2.5s Stillness
        // ====================================================================
        if (ruleEval.candidateEvent != null && !inCooldown) {
            val ruleCandidate = ruleEval.candidateEvent
            val layer3Decision = PersonalLearningEngine.evaluateLayer3Decision(
                profile = activeProfile,
                snapshot = lastWindowSnapshot,
                cnnResult = cnn,
                baseMlConfidenceThreshold = ML_FALL_CONFIDENCE_THRESHOLD,
                baseRuleImpactThresholdMs2 = effectiveImpact,
                candidatePeakMs2 = ruleCandidate.peakMagnitude,
                candidateMinMs2 = ruleCandidate.minMagnitude,
                candidateStillnessStdMs2 = ruleCandidate.postImpactStillnessStdDev
            )
            lastPersonalDecision = layer3Decision

            // If Layer 3 Personalised Filter recognizes a recurring non-severe false alarm for this user,
            // suppress the false alarm cleanly while logging the reason.
            if (layer3Decision.suppressedRecurringFalseAlarm && !layer3Decision.hardSafetyOverrideActive) {
                pendingNonFreeFallImpactNanos = 0L
                onRecurringFalseAlarmSuppressed?.invoke(layer3Decision)
                val suppressedFeatures = enrichFeatures(
                    base = ruleEval.features,
                    cnn = cnn,
                    snapshot = lastWindowSnapshot,
                    explainability = lastExplainability,
                    decisionOverride = layer3Decision.decisionSummary
                )
                return DetectionEvaluation(
                    features = suppressedFeatures,
                    candidateEvent = null
                )
            }

            lastHybridTriggeredNanos = sample.timestampNanos
            pendingNonFreeFallImpactNanos = 0L
            lastCandidateWindowSnapshot = lastWindowSnapshot
            lastCandidateCnnResult = cnn

            val baseExplainability = buildCheckInExplainability(
                cnn = cnn,
                snapshot = lastWindowSnapshot,
                triggeredByRuleFallback = cnn == null ||
                    cnn.readiness != ModelReadinessStatus.CALIBRATED_READY ||
                    cnn.predictedCategory != MovementCategory.POSSIBLE_FALL ||
                    cnn.isUncertain,
                ruleSummaryNote = ruleCandidate.summaryNote,
                epochMillis = currentEpochMillis
            )
            val combinedReason = if (layer3Decision.personalisationApplied) {
                "${baseExplainability.whyCheckInInitiated} [${layer3Decision.decisionSummary}]"
            } else {
                baseExplainability.whyCheckInInitiated
            }
            val explainability = baseExplainability.copy(whyCheckInInitiated = combinedReason)
            lastExplainability = explainability

            val enrichedCandidate = ruleCandidate.copy(
                summaryNote = explainability.whyCheckInInitiated,
                explainabilityRecord = explainability
            )
            val enrichedFeatures = enrichFeatures(
                base = ruleEval.features,
                cnn = cnn,
                snapshot = lastWindowSnapshot,
                explainability = explainability,
                decisionOverride = explainability.whyCheckInInitiated
            )
            return DetectionEvaluation(
                features = enrichedFeatures,
                candidateEvent = enrichedCandidate
            )
        }

        // ====================================================================
        // CASE 2: ML 1D CNN Detects Possible Fall on Impact + 2.5s Stillness
        // (e.g., gradual syncope collapse where min magnitude stayed 6.0..7.8 m/s²)
        // ====================================================================
        val preCheckLayer3 = PersonalLearningEngine.evaluateLayer3Decision(
            profile = activeProfile,
            snapshot = lastWindowSnapshot,
            cnnResult = cnn,
            baseMlConfidenceThreshold = ML_FALL_CONFIDENCE_THRESHOLD,
            baseRuleImpactThresholdMs2 = effectiveImpact,
            candidatePeakMs2 = pendingNonFreeFallPeakMs2,
            candidateMinMs2 = pendingNonFreeFallMinG * MotionSample.GRAVITY_EARTH_MS2
        )
        val effectiveMlConfThreshold = preCheckLayer3.effectiveMlConfidenceThreshold

        if (mlNonFreeFallReady && !inCooldown && cnn != null &&
            cnn.readiness == ModelReadinessStatus.CALIBRATED_READY &&
            cnn.predictedCategory == MovementCategory.POSSIBLE_FALL &&
            (cnn.calibratedConfidence ?: 0f) >= effectiveMlConfThreshold &&
            !cnn.isUncertain
        ) {
            // Verify post-impact stillness in the last 2.5 seconds of windowBuffer
            val stillnessHoldSamples = windowBuffer.filter {
                (it.timestampNanos - pendingNonFreeFallImpactNanos) / 1_000_000L in 500L..3200L
            }
            if (stillnessHoldSamples.size >= 10) {
                val mags = stillnessHoldSamples.map { it.magnitude }
                val meanMag = mags.average().toFloat()
                var varSum = 0f
                for (m in mags) {
                    val d = m - meanMag
                    varSum += d * d
                }
                val stdDev = sqrt(varSum / mags.size)
                val inGravityBand = meanMag in 7.81f..11.81f && stdDev <= 1.0f

                if (inGravityBand) {
                    val peakMs2 = pendingNonFreeFallPeakMs2
                    val minMs2 = pendingNonFreeFallMinG * MotionSample.GRAVITY_EARTH_MS2
                    val layer3Decision = PersonalLearningEngine.evaluateLayer3Decision(
                        profile = activeProfile,
                        snapshot = lastWindowSnapshot,
                        cnnResult = cnn,
                        baseMlConfidenceThreshold = ML_FALL_CONFIDENCE_THRESHOLD,
                        baseRuleImpactThresholdMs2 = effectiveImpact,
                        candidatePeakMs2 = peakMs2,
                        candidateMinMs2 = minMs2,
                        candidateStillnessStdMs2 = stdDev
                    )
                    lastPersonalDecision = layer3Decision

                    if (layer3Decision.suppressedRecurringFalseAlarm && !layer3Decision.hardSafetyOverrideActive) {
                        pendingNonFreeFallImpactNanos = 0L
                        onRecurringFalseAlarmSuppressed?.invoke(layer3Decision)
                        val suppressedFeatures = enrichFeatures(
                            base = ruleEval.features,
                            cnn = cnn,
                            snapshot = lastWindowSnapshot,
                            explainability = lastExplainability,
                            decisionOverride = layer3Decision.decisionSummary
                        )
                        return DetectionEvaluation(
                            features = suppressedFeatures,
                            candidateEvent = null
                        )
                    }

                    lastHybridTriggeredNanos = sample.timestampNanos
                    ruleDetector.applyCooldown(sample.timestampNanos)
                    pendingNonFreeFallImpactNanos = 0L
                    lastCandidateWindowSnapshot = lastWindowSnapshot
                    lastCandidateCnnResult = cnn

                    val confPct = String.format(Locale.US, "%.1f%%", (cnn.calibratedConfidence ?: 0f) * 100f)
                    val baseReason = "Accepted: 1D CNN (${cnn.modelVersion}) classified Possible Fall (confidence $confPct): " +
                        "pre-impact unloading (${String.format(Locale.US, "%.1f", minMs2)} m/s²) → " +
                        "impact (${String.format(Locale.US, "%.1f", peakMs2)} m/s²) → " +
                        "post-impact stillness (σ=${String.format(Locale.US, "%.2f", stdDev)} m/s²)."
                    val reason = if (layer3Decision.personalisationApplied) {
                        "$baseReason [${layer3Decision.decisionSummary}]"
                    } else {
                        baseReason
                    }

                    val explainability = MlExplainabilityRecord(
                        modelVersion = cnn.modelVersion,
                        modelReadiness = cnn.readiness,
                        predictedCategory = MovementCategory.POSSIBLE_FALL,
                        calibratedConfidence = cnn.calibratedConfidence,
                        classProbabilities = cnn.classProbabilities,
                        normalizedEntropy = cnn.normalizedEntropy,
                        isUncertain = cnn.isUncertain,
                        triggeredByRuleFallback = false,
                        timestampEpochMillis = currentEpochMillis,
                        windowSnapshot = lastWindowSnapshot,
                        whyCheckInInitiated = reason
                    )
                    lastExplainability = explainability

                    val candidate = CandidateEventSummary(
                        eventId = UUID.randomUUID().toString(),
                        detectedAtEpochMillis = currentEpochMillis,
                        peakMagnitude = peakMs2,
                        minMagnitude = minMs2,
                        suddenChangeCount = 1,
                        orientationChangeDeg = 0f,
                        postImpactStillnessStdDev = stdDev,
                        triggerSource = if (sample.isSynthetic) {
                            "1D CNN + Synthetic Trace (${sample.sensorType})"
                        } else {
                            "1D CNN + Live Sensors (${cnn.modelVersion})"
                        },
                        summaryNote = reason,
                        peakDynamicAccelerationMs2 = maxOf(0f, peakMs2 - NOMINAL_GRAVITY_MS2),
                        peakGyroDegPerSec = lastWindowSnapshot.peakGyroDegPerSec,
                        impactDurationMs = 80L,
                        detectionDelayMs = postImpactElapsedMs,
                        explainabilityRecord = explainability
                    )

                    val enrichedFeatures = enrichFeatures(
                        base = ruleEval.features,
                        cnn = cnn,
                        snapshot = lastWindowSnapshot,
                        explainability = explainability,
                        decisionOverride = reason
                    )
                    return DetectionEvaluation(
                        features = enrichedFeatures,
                        candidateEvent = candidate
                    )
                }
            }
            pendingNonFreeFallImpactNanos = 0L
        } else if (postImpactElapsedMs > 3200L) {
            pendingNonFreeFallImpactNanos = 0L
        }

        // Normal continuous monitoring update
        val continuousExplainability = if (cnn != null) {
            MlExplainabilityRecord(
                modelVersion = cnn.modelVersion,
                modelReadiness = cnn.readiness,
                predictedCategory = cnn.predictedCategory,
                calibratedConfidence = cnn.calibratedConfidence,
                classProbabilities = cnn.classProbabilities,
                normalizedEntropy = cnn.normalizedEntropy,
                isUncertain = cnn.isUncertain,
                triggeredByRuleFallback = cnn.readiness != ModelReadinessStatus.CALIBRATED_READY,
                timestampEpochMillis = currentEpochMillis,
                windowSnapshot = lastWindowSnapshot,
                whyCheckInInitiated = ""
            )
        } else {
            lastExplainability
        }

        val enrichedFeatures = enrichFeatures(
            base = ruleEval.features,
            cnn = cnn,
            snapshot = lastWindowSnapshot,
            explainability = continuousExplainability,
            decisionOverride = null
        )
        return DetectionEvaluation(
            features = enrichedFeatures,
            candidateEvent = null
        )
    }

    private fun buildCheckInExplainability(
        cnn: CnnInferenceResult?,
        snapshot: SensorWindowSnapshot,
        triggeredByRuleFallback: Boolean,
        ruleSummaryNote: String,
        epochMillis: Long
    ): MlExplainabilityRecord {
        if (cnn == null || cnn.readiness != ModelReadinessStatus.CALIBRATED_READY) {
            val reason = "Accepted: Rule Safety Fallback initiated check-in (ML model unavailable): $ruleSummaryNote"
            return MlExplainabilityRecord(
                modelVersion = cnn?.modelVersion ?: "rule-fallback-only",
                modelReadiness = ModelReadinessStatus.MODEL_UNAVAILABLE_FALLBACK_ACTIVE,
                predictedCategory = MovementCategory.POSSIBLE_FALL,
                calibratedConfidence = null,
                classProbabilities = emptyMap(),
                normalizedEntropy = null,
                isUncertain = true,
                triggeredByRuleFallback = true,
                timestampEpochMillis = epochMillis,
                windowSnapshot = snapshot,
                whyCheckInInitiated = reason
            )
        }

        val conf = cnn.calibratedConfidence
        val confFormatted = if (conf != null) {
            String.format(Locale.US, "%.1f%%", conf * 100f)
        } else {
            "N/A"
        }

        val reason = if (!triggeredByRuleFallback && cnn.predictedCategory == MovementCategory.POSSIBLE_FALL) {
            "Accepted: Hybrid 1D CNN (${cnn.modelVersion}, confidence $confFormatted) + 3-Phase Rule verified check-in: $ruleSummaryNote"
        } else {
            "Accepted: Hybrid Safety Fallback initiated check-in (1D CNN classified '${cnn.predictedCategory.displayLabel}' " +
                "with confidence $confFormatted, uncertain=${cnn.isUncertain}): $ruleSummaryNote"
        }

        return MlExplainabilityRecord(
            modelVersion = cnn.modelVersion,
            modelReadiness = cnn.readiness,
            predictedCategory = if (!triggeredByRuleFallback) cnn.predictedCategory else MovementCategory.POSSIBLE_FALL,
            calibratedConfidence = conf,
            classProbabilities = cnn.classProbabilities,
            normalizedEntropy = cnn.normalizedEntropy,
            isUncertain = cnn.isUncertain || triggeredByRuleFallback,
            triggeredByRuleFallback = triggeredByRuleFallback,
            timestampEpochMillis = epochMillis,
            windowSnapshot = snapshot,
            whyCheckInInitiated = reason
        )
    }

    private fun enrichFeatures(
        base: MotionFeatures,
        cnn: CnnInferenceResult?,
        snapshot: SensorWindowSnapshot,
        explainability: MlExplainabilityRecord?,
        decisionOverride: String?
    ): MotionFeatures {
        val readiness = cnn?.readiness ?: cnnClassifier.readinessStatus
        val version = cnn?.modelVersion ?: cnnClassifier.modelVersion
        val category = cnn?.predictedCategory ?: MovementCategory.STANDING_STILL
        val confidence = if (readiness == ModelReadinessStatus.CALIBRATED_READY) {
            cnn?.calibratedConfidence
        } else {
            null
        }
        return base.copy(
            lastDecisionReason = decisionOverride ?: base.lastDecisionReason,
            predictedCategory = category,
            calibratedConfidence = confidence,
            normalizedEntropy = cnn?.normalizedEntropy,
            isPredictionUncertain = cnn?.isUncertain ?: (readiness != ModelReadinessStatus.CALIBRATED_READY),
            modelVersion = version,
            modelReadiness = readiness,
            empiricalSamplingRateHz = snapshot.empiricalSamplingRateHz,
            hasGyroscopeHardware = hasGyroscopeHardware || snapshot.hasGyroscope,
            explainabilityRecord = explainability
        )
    }
}

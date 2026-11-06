package com.example.sensor

import com.example.domain.FeedbackLearningLabel
import com.example.domain.FeelingOkayStatus
import com.example.domain.IncidentVerificationType
import com.example.domain.ModelReadinessStatus
import com.example.domain.MovementCategory
import com.example.domain.PersonalCalibrationDecision
import com.example.domain.PersonalLearningEventStatus
import com.example.domain.PersonalLearningExample
import com.example.domain.PersonalLearningStage
import com.example.domain.PersonalMovementProfile
import com.example.domain.PostFallAssessmentDraft
import com.example.domain.SensorWindowSnapshot
import com.example.domain.SosAlertOutcomeState
import com.example.domain.SosLearningSourceType
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Outcome of linking a Post-Incident Assessment questionnaire to an existing SOS event
 * or standalone check-in event.
 */
sealed class QuestionnaireLinkOutcome {
    data class ValidatedOrUpdated(
        val updatedExample: PersonalLearningExample,
        val wasLinkedToSosEvent: Boolean
    ) : QuestionnaireLinkOutcome()

    data class DuplicateIgnored(
        val existingExample: PersonalLearningExample,
        val reason: String
    ) : QuestionnaireLinkOutcome()
}

/**
 * Layer 2 & Layer 3 Personalised Fall Detection & Continuous Learning Engine.
 *
 * Responsibilities:
 * 1. Extracts a 16-dimensional normalized kinematic + model-probability feature vector from each
 *    candidate event's 4.0s sensor window (`SensorWindowSnapshot` + `CnnInferenceResult`).
 * 2. Captures three distinct learning/history sources:
 *    - Post-Incident Questionnaire feedback (`QUESTIONNAIRE_ONLY`)
 *    - Automatic Fall SOS alerts (`AUTOMATIC_FALL_SOS` -> initially `UNVERIFIED_SOS_EVENT`)
 *    - Manual SOS button events (`MANUAL_SOS` -> `MANUAL_SOS_EVENT`, never treated as a fall label)
 * 3. Records emergency-contact acknowledgements on the linked `alertId` without ever treating
 *    alert delivery or acknowledgement as proof of a fall.
 * 4. Connects later Post-Incident Questionnaire responses back to the original SOS `alertId`,
 *    validating the preserved 16-D movement features and preventing duplicate learning.
 * 5. Recomputes the user's on-device `PersonalMovementProfile` (feature prototype centroids + bounded
 *    threshold calibration) only after at least 5 confirmed examples have been collected.
 * 6. Evaluates candidate falls by combining the frozen Layer 1 general model with Layer 2 personal
 *    prototypes while enforcing a strict Hard Safety Override so severe falls are never suppressed.
 */
object PersonalLearningEngine {

    const val FEATURE_VECTOR_DIM = 16
    const val MIN_CONFIRMED_EXAMPLES = PersonalLearningStage.MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION

    // Bounded calibration guardrails around general baseline
    const val MAX_CONFIDENCE_THRESHOLD_INCREASE = 0.08f
    const val MAX_CONFIDENCE_THRESHOLD_DECREASE = -0.06f
    const val MAX_IMPACT_THRESHOLD_INCREASE_MS2 = 3.5f
    const val MAX_IMPACT_THRESHOLD_DECREASE_MS2 = -2.0f

    // Hard safety override thresholds: any event meeting these physical criteria can NEVER be suppressed
    const val HARD_SAFETY_PEAK_ACCEL_MS2 = 37.3f // ~3.8g severe impact
    const val HARD_SAFETY_DEEP_UNLOADING_G = 0.35f
    const val HARD_SAFETY_MODERATE_IMPACT_MS2 = 28.0f
    const val HARD_SAFETY_STILLNESS_MAX_STD_MS2 = 0.90f

    // Prototype cosine similarity thresholds
    const val RECURRING_NON_FALL_SIMILARITY_THRESHOLD = 0.86f
    const val CONFIRMED_FALL_SIMILARITY_THRESHOLD = 0.85f

    /**
     * Extracts a deterministic 16-dimensional normalized feature vector `[0..1]` combining
     * 8 physical kinematic descriptors from [SensorWindowSnapshot] and 8 probability/uncertainty
     * descriptors from the frozen Layer 1 [CnnInferenceResult].
     */
    fun extractFeatureVector16D(
        snapshot: SensorWindowSnapshot,
        cnnResult: CnnInferenceResult?
    ): List<Float> {
        val peakG = (snapshot.peakTotalAccelMs2 / 9.80665f).coerceIn(0f, 8.0f) / 8.0f
        val minG = snapshot.minTotalAccelG.coerceIn(0f, 1.5f) / 1.5f
        val vertG = snapshot.peakVerticalBodyAccelG.coerceIn(0f, 6.0f) / 6.0f
        val horizG = snapshot.peakHorizontalBodyAccelG.coerceIn(0f, 6.0f) / 6.0f
        val jerkNorm = snapshot.peakJerkGPerSec.coerceIn(0f, 120f) / 120f
        val gyroNorm = snapshot.peakGyroDegPerSec.coerceIn(0f, 450f) / 450f
        val stillnessNorm = snapshot.postImpactStillnessStdDevMs2.coerceIn(0f, 4.0f) / 4.0f

        val env = snapshot.downsampledMagnitudeEnvelopeG
        val envSpread = if (env.size >= 2) {
            val maxEnv = env.maxOrNull() ?: 1.0f
            val minEnv = env.minOrNull() ?: 1.0f
            ((maxEnv - minEnv).coerceIn(0f, 8.0f)) / 8.0f
        } else {
            (peakG - minG * (1.5f / 8.0f)).coerceIn(0f, 1.0f)
        }

        val probs = cnnResult?.classProbabilities.orEmpty()
        val pStand = (probs[MovementCategory.STANDING_STILL] ?: (1f / 6f)).coerceIn(0f, 1f)
        val pWalk = (probs[MovementCategory.WALKING] ?: (1f / 6f)).coerceIn(0f, 1f)
        val pSit = (probs[MovementCategory.SITTING_DOWN] ?: (1f / 6f)).coerceIn(0f, 1f)
        val pStair = (probs[MovementCategory.STAIR_MOVEMENT] ?: (1f / 6f)).coerceIn(0f, 1f)
        val pFall = (probs[MovementCategory.POSSIBLE_FALL] ?: (1f / 6f)).coerceIn(0f, 1f)
        val pUncertain = (probs[MovementCategory.UNKNOWN_UNCERTAIN] ?: (1f / 6f)).coerceIn(0f, 1f)

        val conf = (cnnResult?.calibratedConfidence ?: 0.5f).coerceIn(0f, 1f)
        val entropy = (cnnResult?.normalizedEntropy ?: 0.5f).coerceIn(0f, 1f)

        return listOf(
            peakG,
            minG,
            vertG,
            horizG,
            jerkNorm,
            gyroNorm,
            stillnessNorm,
            envSpread,
            pStand,
            pWalk,
            pSit,
            pStair,
            pFall,
            pUncertain,
            conf,
            entropy
        )
    }

    /**
     * Resolves a human-readable detection method string and whether the physics-based rule fallback
     * was involved in detecting the event.
     */
    fun resolveDetectionMethodAndFallback(
        cnnResult: CnnInferenceResult?,
        triggeredByRuleFallback: Boolean = false,
        triggerSource: String = ""
    ): Pair<String, Boolean> {
        val lowerSource = triggerSource.lowercase(Locale.US)
        val fallbackUsed = triggeredByRuleFallback ||
            cnnResult == null ||
            cnnResult.readiness != ModelReadinessStatus.CALIBRATED_READY ||
            cnnResult.predictedCategory != MovementCategory.POSSIBLE_FALL ||
            lowerSource.contains("rule") ||
            lowerSource.contains("3phase") ||
            lowerSource.contains("physics")

        val method = when {
            cnnResult == null || cnnResult.readiness != ModelReadinessStatus.CALIBRATED_READY ->
                "PHYSICS_RULE_FALLBACK"
            triggeredByRuleFallback ->
                "PHYSICS_RULE_FALLBACK"
            fallbackUsed ->
                "HYBRID_CNN_PLUS_PHYSICS"
            else ->
                "CNN_PRIMARY"
        }
        return method to fallbackUsed
    }

    /**
     * Requirement 1 — Captures an automatically detected fall event that resulted in an SOS alert.
     *
     * Important safety guarantee:
     * An alert being created or dispatched is evidence of a possible incident, NOT proof of a fall.
     * This record is stored initially as [FeedbackLearningLabel.UNVERIFIED_SOS_EVENT] with
     * `includedInTraining = false` and `eventStatus = PersonalLearningEventStatus.AWAITING_FEEDBACK`.
     */
    fun createAutomaticSosLearningEvent(
        userId: String,
        alertId: String,
        linkedEventId: String,
        snapshot: SensorWindowSnapshot,
        cnnResult: CnnInferenceResult?,
        triggeredByRuleFallback: Boolean = false,
        triggerSource: String = "AUTOMATIC_FALL_SOS",
        alertOutcome: SosAlertOutcomeState = SosAlertOutcomeState.DISPATCH_ACCEPTED,
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): PersonalLearningExample {
        val cleanAlertId = alertId.trim()
        val cleanEventId = linkedEventId.trim().ifBlank { cleanAlertId }
        val featureVec = extractFeatureVector16D(snapshot, cnnResult)
        val peakG = (snapshot.peakTotalAccelMs2 / 9.80665f).coerceAtLeast(0.1f)
        val predictedCat = cnnResult?.predictedCategory ?: MovementCategory.POSSIBLE_FALL
        val validConfidence = cnnResult?.calibratedConfidence?.takeIf {
            !it.isNaN() && !it.isInfinite() && it in 0f..1f &&
                cnnResult.readiness == ModelReadinessStatus.CALIBRATED_READY &&
                !triggeredByRuleFallback
        }
        val (detectionMethod, physicsFallbackInvolved) = resolveDetectionMethodAndFallback(
            cnnResult = cnnResult,
            triggeredByRuleFallback = triggeredByRuleFallback,
            triggerSource = triggerSource
        )

        return PersonalLearningExample(
            exampleId = "ple_sos_${cleanAlertId.ifBlank { UUID.randomUUID().toString().take(10) }}",
            userId = userId.ifBlank { "local_default" },
            linkedEventId = cleanEventId,
            timestampEpochMillis = timestampEpochMillis,
            incidentVerification = IncidentVerificationType.NOT_SURE,
            learningLabel = FeedbackLearningLabel.UNVERIFIED_SOS_EVENT,
            featureVector16D = featureVec,
            peakTotalAccelG = peakG,
            minTotalAccelG = snapshot.minTotalAccelG,
            postImpactStillnessStdDevMs2 = snapshot.postImpactStillnessStdDevMs2,
            peakGyroDegPerSec = snapshot.peakGyroDegPerSec,
            baseModelPredictedCategory = predictedCat,
            baseModelConfidence = validConfidence,
            includedInTraining = false,
            validationNote = "Awaiting user feedback: Automatic SOS alert (${cleanAlertId.ifBlank { cleanEventId }}) recorded; not used for training until verified by post-incident questionnaire.",
            alertId = cleanAlertId,
            sourceType = SosLearningSourceType.AUTOMATIC_FALL_SOS,
            eventStatus = PersonalLearningEventStatus.AWAITING_FEEDBACK,
            detectionMethod = detectionMethod,
            physicsFallbackInvolved = physicsFallbackInvolved,
            alertOutcome = alertOutcome,
            recipientAcknowledged = alertOutcome == SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED,
            acknowledgedAtEpochMillis = if (alertOutcome == SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED) timestampEpochMillis else null,
            questionnaireCompleted = false,
            questionnaireCompletedAtEpochMillis = null
        )
    }

    /**
     * Requirement 2 — Captures a user-initiated Manual SOS event.
     *
     * Important safety guarantee:
     * Manual SOS indicates the user requested help, which can happen without any fall occurring.
     * It is stored with `sourceType = MANUAL_SOS`, `learningLabel = MANUAL_SOS_EVENT`,
     * `eventStatus = MANUAL_SOS_NOT_A_FALL_LABEL`, and `includedInTraining = false`.
     */
    fun createManualSosLearningEvent(
        userId: String,
        alertId: String,
        snapshot: SensorWindowSnapshot?,
        cnnResult: CnnInferenceResult?,
        alertOutcome: SosAlertOutcomeState = SosAlertOutcomeState.DISPATCH_ACCEPTED,
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): PersonalLearningExample {
        val cleanAlertId = alertId.trim()
        val hasMovementContext = snapshot != null && snapshot.windowSampleCount > 0 && snapshot.peakTotalAccelMs2 > 0f
        val featureVec = if (hasMovementContext && snapshot != null) {
            extractFeatureVector16D(snapshot, cnnResult)
        } else {
            emptyList()
        }
        val peakG = if (hasMovementContext && snapshot != null) {
            snapshot.peakTotalAccelMs2 / 9.80665f
        } else {
            1.0f
        }
        val minG = if (hasMovementContext && snapshot != null) snapshot.minTotalAccelG else 1.0f
        val stillnessStd = if (hasMovementContext && snapshot != null) snapshot.postImpactStillnessStdDevMs2 else 0f
        val gyroPeak = if (hasMovementContext && snapshot != null) snapshot.peakGyroDegPerSec else 0f
        val predictedCat = cnnResult?.predictedCategory ?: MovementCategory.STANDING_STILL
        val validConfidence = cnnResult?.calibratedConfidence?.takeIf {
            !it.isNaN() && !it.isInfinite() && it in 0f..1f
        }

        return PersonalLearningExample(
            exampleId = "ple_manual_${cleanAlertId.ifBlank { UUID.randomUUID().toString().take(10) }}",
            userId = userId.ifBlank { "local_default" },
            linkedEventId = cleanAlertId.ifBlank { "manual_sos_$timestampEpochMillis" },
            timestampEpochMillis = timestampEpochMillis,
            incidentVerification = IncidentVerificationType.NOT_SURE,
            learningLabel = FeedbackLearningLabel.MANUAL_SOS_EVENT,
            featureVector16D = featureVec,
            peakTotalAccelG = peakG,
            minTotalAccelG = minG,
            postImpactStillnessStdDevMs2 = stillnessStd,
            peakGyroDegPerSec = gyroPeak,
            baseModelPredictedCategory = predictedCat,
            baseModelConfidence = validConfidence,
            includedInTraining = false,
            validationNote = "Manual SOS — user-initiated help request. Kept separate from automatic fall detections and never used as a fall-training label.",
            alertId = cleanAlertId,
            sourceType = SosLearningSourceType.MANUAL_SOS,
            eventStatus = PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL,
            detectionMethod = "USER_MANUAL_SOS",
            physicsFallbackInvolved = false,
            alertOutcome = alertOutcome,
            recipientAcknowledged = alertOutcome == SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED,
            acknowledgedAtEpochMillis = if (alertOutcome == SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED) timestampEpochMillis else null,
            questionnaireCompleted = false,
            questionnaireCompletedAtEpochMillis = null
        )
    }

    /**
     * Requirement 3 — Records that the connected emergency contact received and acknowledged an alert.
     *
     * Critical safety guarantee:
     * Acknowledging an alert NEVER confirms that a fall happened and NEVER changes `learningLabel`
     * to `CONFIRMED_FALL` or flips `includedInTraining` to `true`.
     */
    fun applyEmergencyContactAcknowledgement(
        existing: PersonalLearningExample,
        acknowledgedByName: String = "Emergency contact",
        acknowledgedAtEpochMillis: Long = System.currentTimeMillis()
    ): PersonalLearningExample {
        val cleanName = acknowledgedByName.trim().ifBlank { "Emergency contact" }
        val updatedNote = if (existing.validationNote.contains("Contact acknowledged", ignoreCase = true)) {
            existing.validationNote
        } else {
            "${existing.validationNote} • Contact acknowledged ($cleanName)."
        }
        return existing.copy(
            alertOutcome = SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED,
            recipientAcknowledged = true,
            acknowledgedAtEpochMillis = acknowledgedAtEpochMillis,
            acknowledgedByName = cleanName,
            // Explicitly preserve learningLabel, eventStatus, and includedInTraining so acknowledgement never trains the model
            learningLabel = existing.learningLabel,
            eventStatus = existing.eventStatus,
            includedInTraining = existing.includedInTraining,
            validationNote = updatedNote
        )
    }

    /**
     * Requirement 4 — Connects a Post-Incident Assessment questionnaire to an existing SOS event
     * (by `alertId` or `linkedEventId`) or builds a validated feedback example, while enforcing:
     * - Duplicate questionnaire prevention (one event can contribute to learning at most once).
     * - Manual SOS protection (manual SOS without a movement-related fall incident is never trained as a fall).
     * - Automatic SOS validation using the original event's preserved 16-D movement feature vector.
     */
    fun linkQuestionnaireFeedbackToSosEvent(
        userId: String,
        draft: PostFallAssessmentDraft,
        existingEvent: PersonalLearningExample?,
        fallbackSnapshot: SensorWindowSnapshot,
        fallbackCnnResult: CnnInferenceResult?,
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): QuestionnaireLinkOutcome {
        // 1. Prevent duplicate learning if this event was already validated through a questionnaire
        if (existingEvent != null && existingEvent.questionnaireCompleted) {
            return QuestionnaireLinkOutcome.DuplicateIgnored(
                existingExample = existingEvent,
                reason = "Duplicate questionnaire ignored: Event ${existingEvent.canonicalDedupKey} has already been processed for personal learning."
            )
        }

        // 2. If linked to a Manual SOS event without a movement-related incident, keep it excluded from fall training
        if (existingEvent != null && existingEvent.sourceType == SosLearningSourceType.MANUAL_SOS) {
            val verification = draft.incidentVerification ?: IncidentVerificationType.NOT_SURE
            val updatedManual = existingEvent.copy(
                incidentVerification = verification,
                learningLabel = FeedbackLearningLabel.MANUAL_SOS_EVENT,
                eventStatus = PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL,
                includedInTraining = false,
                questionnaireCompleted = true,
                questionnaireCompletedAtEpochMillis = timestampEpochMillis,
                validationNote = "Manual SOS — not a fall training label (user-initiated SOS kept separate from fall detection calibration)."
            )
            return QuestionnaireLinkOutcome.ValidatedOrUpdated(
                updatedExample = updatedManual,
                wasLinkedToSosEvent = true
            )
        }

        // 3. Reconstruct or use the preserved sensor window from the original automatic SOS event if available
        val effectiveSnapshot = if (existingEvent != null &&
            existingEvent.featureVector16D.size == FEATURE_VECTOR_DIM &&
            existingEvent.peakTotalAccelG > 0f
        ) {
            SensorWindowSnapshot(
                windowSampleCount = 200,
                empiricalSamplingRateHz = 50.0f,
                minTotalAccelG = existingEvent.minTotalAccelG,
                peakTotalAccelMs2 = existingEvent.peakTotalAccelG * 9.80665f,
                postImpactStillnessStdDevMs2 = existingEvent.postImpactStillnessStdDevMs2,
                peakGyroDegPerSec = existingEvent.peakGyroDegPerSec,
                hasGyroscope = existingEvent.peakGyroDegPerSec > 0f
            )
        } else {
            fallbackSnapshot
        }

        val validated = validateAndBuildFeedbackExample(
            userId = userId,
            draft = draft,
            snapshot = effectiveSnapshot,
            cnnResult = fallbackCnnResult,
            timestampEpochMillis = timestampEpochMillis
        )

        if (existingEvent == null) {
            return QuestionnaireLinkOutcome.ValidatedOrUpdated(
                updatedExample = validated,
                wasLinkedToSosEvent = false
            )
        }

        // Merge validated feedback label into the original automatic SOS event record, preserving its original
        // 16-D movement feature vector, alertId, detection method, and contact acknowledgement state.
        val preservedFeatureVector = if (existingEvent.featureVector16D.size == FEATURE_VECTOR_DIM) {
            existingEvent.featureVector16D
        } else {
            validated.featureVector16D
        }

        val resolvedStatus = if (validated.includedInTraining && validated.learningLabel.isConfirmedTrainingExample) {
            PersonalLearningEventStatus.CONFIRMED_MOVEMENT_PATTERN
        } else {
            PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING
        }

        val merged = existingEvent.copy(
            incidentVerification = validated.incidentVerification,
            learningLabel = validated.learningLabel,
            featureVector16D = preservedFeatureVector,
            includedInTraining = validated.includedInTraining,
            eventStatus = resolvedStatus,
            questionnaireCompleted = true,
            questionnaireCompletedAtEpochMillis = timestampEpochMillis,
            validationNote = buildString {
                append(validated.validationNote)
                if (existingEvent.alertId.isNotBlank()) {
                    append(" Linked to SOS alert ${existingEvent.alertId}.")
                }
                if (existingEvent.recipientAcknowledged) {
                    append(" Contact acknowledged.")
                }
            }
        )

        return QuestionnaireLinkOutcome.ValidatedOrUpdated(
            updatedExample = merged,
            wasLinkedToSosEvent = true
        )
    }

    /**
     * Maps the user's Post-Fall Assessment answers to a validated [PersonalLearningExample].
     *
     * Safety rules enforced:
     * 1. Pressing "I'm OK" alone does not create a training example; an explicit [IncidentVerificationType] is required.
     * 2. `NOT_SURE` is marked [FeedbackLearningLabel.UNCERTAIN_EXCLUDED] (`includedInTraining = false`).
     * 3. If the user selects a non-fall/false-alarm option (`PHONE_FELL_NOT_ME`, `SITTING_DOWN`, `NORMAL_MOVEMENT`,
     *    `FALSE_ALARM`) YET reports severe physical symptoms or feeling unwell (`feelingOkay == NO` or
     *    `hasSevereWarningIndicators()`), the example is marked [FeedbackLearningLabel.CONTRADICTORY_EXCLUDED]
     *    (`includedInTraining = false`) so contradictory answers never reduce fall sensitivity.
     * 4. If the sensor window has fewer than 10 samples or invalid quality, it is marked
     *    [FeedbackLearningLabel.LOW_QUALITY_EXCLUDED] (`includedInTraining = false`).
     */
    fun validateAndBuildFeedbackExample(
        userId: String,
        draft: PostFallAssessmentDraft,
        snapshot: SensorWindowSnapshot,
        cnnResult: CnnInferenceResult?,
        timestampEpochMillis: Long = System.currentTimeMillis()
    ): PersonalLearningExample {
        val verification = draft.incidentVerification ?: IncidentVerificationType.NOT_SURE
        val featureVec = extractFeatureVector16D(snapshot, cnnResult)
        val peakG = snapshot.peakTotalAccelMs2 / 9.80665f
        val predictedCat = cnnResult?.predictedCategory ?: MovementCategory.POSSIBLE_FALL
        val baseConf = cnnResult?.calibratedConfidence
        val (detectionMethod, physicsFallbackInvolved) = resolveDetectionMethodAndFallback(
            cnnResult = cnnResult,
            triggeredByRuleFallback = false
        )
        val alertIdFromDraft = draft.triggeredAlertId?.trim().orEmpty()

        // 1. Uncertain answer -> never use as confirmed training example
        if (verification == IncidentVerificationType.NOT_SURE) {
            return PersonalLearningExample(
                exampleId = "ple_${UUID.randomUUID().toString().take(10)}_$timestampEpochMillis",
                userId = userId.ifBlank { "local_default" },
                linkedEventId = draft.linkedEventId,
                timestampEpochMillis = timestampEpochMillis,
                incidentVerification = verification,
                learningLabel = FeedbackLearningLabel.UNCERTAIN_EXCLUDED,
                featureVector16D = featureVec,
                peakTotalAccelG = peakG,
                minTotalAccelG = snapshot.minTotalAccelG,
                postImpactStillnessStdDevMs2 = snapshot.postImpactStillnessStdDevMs2,
                peakGyroDegPerSec = snapshot.peakGyroDegPerSec,
                baseModelPredictedCategory = predictedCat,
                baseModelConfidence = baseConf,
                includedInTraining = false,
                validationNote = "Excluded: User selected 'Not sure' — uncertain events are never used for training.",
                alertId = alertIdFromDraft,
                sourceType = if (alertIdFromDraft.isNotBlank()) SosLearningSourceType.AUTOMATIC_FALL_SOS else SosLearningSourceType.QUESTIONNAIRE_ONLY,
                eventStatus = PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING,
                detectionMethod = detectionMethod,
                physicsFallbackInvolved = physicsFallbackInvolved,
                questionnaireCompleted = true,
                questionnaireCompletedAtEpochMillis = timestampEpochMillis
            )
        }

        // 2. Contradictory feedback check: non-fall selected, but user reported severe symptoms or feeling unwell
        val hasConcerningSymptoms = draft.hasSevereWarningIndicators() ||
            draft.feelingOkay == FeelingOkayStatus.NO
        if (verification.isFalseDetection && hasConcerningSymptoms) {
            return PersonalLearningExample(
                exampleId = "ple_${UUID.randomUUID().toString().take(10)}_$timestampEpochMillis",
                userId = userId.ifBlank { "local_default" },
                linkedEventId = draft.linkedEventId,
                timestampEpochMillis = timestampEpochMillis,
                incidentVerification = verification,
                learningLabel = FeedbackLearningLabel.CONTRADICTORY_EXCLUDED,
                featureVector16D = featureVec,
                peakTotalAccelG = peakG,
                minTotalAccelG = snapshot.minTotalAccelG,
                postImpactStillnessStdDevMs2 = snapshot.postImpactStillnessStdDevMs2,
                peakGyroDegPerSec = snapshot.peakGyroDegPerSec,
                baseModelPredictedCategory = predictedCat,
                baseModelConfidence = baseConf,
                includedInTraining = false,
                validationNote = "Excluded for safety: Non-fall option '${verification.simpleLabel}' was paired with concerning physical symptoms.",
                alertId = alertIdFromDraft,
                sourceType = if (alertIdFromDraft.isNotBlank()) SosLearningSourceType.AUTOMATIC_FALL_SOS else SosLearningSourceType.QUESTIONNAIRE_ONLY,
                eventStatus = PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING,
                detectionMethod = detectionMethod,
                physicsFallbackInvolved = physicsFallbackInvolved,
                questionnaireCompleted = true,
                questionnaireCompletedAtEpochMillis = timestampEpochMillis
            )
        }

        // 3. Sensor window quality check
        if (snapshot.windowSampleCount < 10 || snapshot.peakTotalAccelMs2 <= 0f) {
            return PersonalLearningExample(
                exampleId = "ple_${UUID.randomUUID().toString().take(10)}_$timestampEpochMillis",
                userId = userId.ifBlank { "local_default" },
                linkedEventId = draft.linkedEventId,
                timestampEpochMillis = timestampEpochMillis,
                incidentVerification = verification,
                learningLabel = FeedbackLearningLabel.LOW_QUALITY_EXCLUDED,
                featureVector16D = featureVec,
                peakTotalAccelG = peakG,
                minTotalAccelG = snapshot.minTotalAccelG,
                postImpactStillnessStdDevMs2 = snapshot.postImpactStillnessStdDevMs2,
                peakGyroDegPerSec = snapshot.peakGyroDegPerSec,
                baseModelPredictedCategory = predictedCat,
                baseModelConfidence = baseConf,
                includedInTraining = false,
                validationNote = "Excluded: Sensor window had insufficient samples (${snapshot.windowSampleCount}).",
                alertId = alertIdFromDraft,
                sourceType = if (alertIdFromDraft.isNotBlank()) SosLearningSourceType.AUTOMATIC_FALL_SOS else SosLearningSourceType.QUESTIONNAIRE_ONLY,
                eventStatus = PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING,
                detectionMethod = detectionMethod,
                physicsFallbackInvolved = physicsFallbackInvolved,
                questionnaireCompleted = true,
                questionnaireCompletedAtEpochMillis = timestampEpochMillis
            )
        }

        // 4. Validated confirmed feedback label
        val resolvedLabel = when (verification) {
            IncidentVerificationType.EXPERIENCED_FALL -> FeedbackLearningLabel.CONFIRMED_FALL
            IncidentVerificationType.PHONE_FELL_NOT_ME,
            IncidentVerificationType.PHONE_DROPPED_OR_PLACED -> FeedbackLearningLabel.PHONE_ONLY_INCIDENT
            IncidentVerificationType.SITTING_DOWN -> FeedbackLearningLabel.NORMAL_SITTING
            IncidentVerificationType.NORMAL_MOVEMENT -> FeedbackLearningLabel.NORMAL_MOVEMENT
            IncidentVerificationType.FALSE_ALARM -> FeedbackLearningLabel.FALSE_DETECTION
            IncidentVerificationType.NOT_SURE -> FeedbackLearningLabel.UNCERTAIN_EXCLUDED
        }

        val included = resolvedLabel.isConfirmedTrainingExample

        return PersonalLearningExample(
            exampleId = "ple_${UUID.randomUUID().toString().take(10)}_$timestampEpochMillis",
            userId = userId.ifBlank { "local_default" },
            linkedEventId = draft.linkedEventId,
            timestampEpochMillis = timestampEpochMillis,
            incidentVerification = verification,
            learningLabel = resolvedLabel,
            featureVector16D = featureVec,
            peakTotalAccelG = peakG,
            minTotalAccelG = snapshot.minTotalAccelG,
            postImpactStillnessStdDevMs2 = snapshot.postImpactStillnessStdDevMs2,
            peakGyroDegPerSec = snapshot.peakGyroDegPerSec,
            baseModelPredictedCategory = predictedCat,
            baseModelConfidence = baseConf,
            includedInTraining = included,
            validationNote = "Confirmed training example (${resolvedLabel.displayLabel}).",
            alertId = alertIdFromDraft,
            sourceType = if (alertIdFromDraft.isNotBlank()) SosLearningSourceType.AUTOMATIC_FALL_SOS else SosLearningSourceType.QUESTIONNAIRE_ONLY,
            eventStatus = if (included) {
                PersonalLearningEventStatus.CONFIRMED_MOVEMENT_PATTERN
            } else {
                PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING
            },
            detectionMethod = detectionMethod,
            physicsFallbackInvolved = physicsFallbackInvolved,
            questionnaireCompleted = true,
            questionnaireCompletedAtEpochMillis = timestampEpochMillis
        )
    }

    /**
     * Deduplicates learning examples by their canonical event/alert key so that the same event
     * can never contribute to Personal Learning more than once.
     * If both an unverified record and a questionnaire-validated record exist for the same key,
     * the validated record is retained.
     */
    fun deduplicateExamples(examples: List<PersonalLearningExample>): List<PersonalLearningExample> {
        val byKey = LinkedHashMap<String, PersonalLearningExample>()
        for (ex in examples) {
            val key = ex.canonicalDedupKey
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = ex
            } else {
                // Prefer the record that has completed questionnaire validation
                if (!existing.questionnaireCompleted && ex.questionnaireCompleted) {
                    byKey[key] = ex
                }
            }
        }
        return byKey.values.toList()
    }

    /**
     * Recomputes the user's Layer 2 [PersonalMovementProfile] from their validated local examples.
     *
     * Controlled adaptation rules:
     * - Deduplicates examples by `canonicalDedupKey` so one event can contribute to learning at most once.
     * - Excludes `UNVERIFIED_SOS_EVENT`, `MANUAL_SOS_EVENT`, `UNCERTAIN_EXCLUDED`, `CONTRADICTORY_EXCLUDED`,
     *   and `LOW_QUALITY_EXCLUDED` from training centroids and calibration deltas.
     * - If fewer than [MIN_CONFIRMED_EXAMPLES] (5) confirmed examples exist, `learningStage` is
     *   [PersonalLearningStage.GETTING_STARTED] (0) or [PersonalLearningStage.LEARNING] (1..4), and
     *   calibration deltas remain strictly `0.0f` so a single questionnaire answer never shifts thresholds.
     * - Once `>= 5` confirmed examples are collected (and `personalisationEnabled == true`), `learningStage`
     *   transitions to [PersonalLearningStage.PERSONALISED] and bounded prototype centroids + threshold
     *   adjustments are computed.
     */
    fun recomputePersonalMovementProfile(
        userId: String,
        examples: List<PersonalLearningExample>,
        personalisationEnabled: Boolean = true,
        previousSuppressedCount: Int = 0,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): PersonalMovementProfile {
        val cleanUserId = userId.ifBlank { "local_default" }
        val uniqueExamples = deduplicateExamples(examples)

        val eligibleTrainingExamples = uniqueExamples.filter {
            it.includedInTraining &&
                it.sourceType != SosLearningSourceType.MANUAL_SOS &&
                it.learningLabel != FeedbackLearningLabel.UNVERIFIED_SOS_EVENT &&
                it.learningLabel != FeedbackLearningLabel.MANUAL_SOS_EVENT &&
                it.featureVector16D.size == FEATURE_VECTOR_DIM
        }

        val confirmedNormal = eligibleTrainingExamples.filter { it.learningLabel.isConfirmedNormalOrFalseAlarm }
        val confirmedFalls = eligibleTrainingExamples.filter { it.learningLabel.isConfirmedFall }
        val excludedUncertain = uniqueExamples.count {
            !it.includedInTraining &&
                (it.learningLabel == FeedbackLearningLabel.UNCERTAIN_EXCLUDED ||
                    it.learningLabel == FeedbackLearningLabel.LOW_QUALITY_EXCLUDED)
        }
        val excludedContradictory = uniqueExamples.count {
            !it.includedInTraining && it.learningLabel == FeedbackLearningLabel.CONTRADICTORY_EXCLUDED
        }

        val awaitingFeedbackCount = uniqueExamples.count { it.isAwaitingUserFeedback }
        val manualSosCount = uniqueExamples.count {
            it.sourceType == SosLearningSourceType.MANUAL_SOS ||
                it.learningLabel == FeedbackLearningLabel.MANUAL_SOS_EVENT
        }
        val acknowledgedCount = uniqueExamples.count {
            it.recipientAcknowledged || it.alertOutcome == SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED
        }
        val validatedSosCount = uniqueExamples.count {
            it.sourceType == SosLearningSourceType.AUTOMATIC_FALL_SOS &&
                it.questionnaireCompleted &&
                it.includedInTraining
        }

        val phoneDropCount = confirmedNormal.count { it.learningLabel == FeedbackLearningLabel.PHONE_ONLY_INCIDENT }
        val sittingCount = confirmedNormal.count { it.learningLabel == FeedbackLearningLabel.NORMAL_SITTING }
        val walkingCount = confirmedNormal.count { it.learningLabel == FeedbackLearningLabel.NORMAL_MOVEMENT }
        val falseAlarmCount = confirmedNormal.count { it.learningLabel == FeedbackLearningLabel.FALSE_DETECTION }

        val totalConfirmed = confirmedNormal.size + confirmedFalls.size
        val stage = PersonalLearningStage.fromConfirmedCount(
            confirmedTotal = totalConfirmed,
            personalisationEnabled = personalisationEnabled
        )

        val normalCentroid = computeCentroid16D(confirmedNormal.map { it.featureVector16D })
        val fallCentroid = computeCentroid16D(confirmedFalls.map { it.featureVector16D })

        if (stage != PersonalLearningStage.PERSONALISED) {
            val summary = when {
                !personalisationEnabled ->
                    "Personalised calibration paused — using general pre-trained model baseline."
                totalConfirmed == 0 ->
                    "Getting started: using the general pre-trained fall detection model."
                else -> {
                    val needed = (MIN_CONFIRMED_EXAMPLES - totalConfirmed).coerceAtLeast(1)
                    "Learning your movement patterns ($totalConfirmed of $MIN_CONFIRMED_EXAMPLES confirmed examples; $needed more needed before adapting)."
                }
            }
            return PersonalMovementProfile(
                userId = cleanUserId,
                learningStage = stage,
                confirmedNormalCount = confirmedNormal.size,
                confirmedFallCount = confirmedFalls.size,
                excludedUncertainCount = excludedUncertain,
                excludedContradictoryCount = excludedContradictory,
                phoneDropExampleCount = phoneDropCount,
                sittingExampleCount = sittingCount,
                walkingNormalExampleCount = walkingCount,
                falseDetectionExampleCount = falseAlarmCount,
                normalPrototypeCentroid16D = normalCentroid,
                fallPrototypeCentroid16D = fallCentroid,
                calibratedConfidenceDelta = 0f,
                calibratedImpactThresholdDeltaMs2 = 0f,
                personalisationEnabled = personalisationEnabled,
                suppressedFalseAlarmsCount = previousSuppressedCount,
                lastUpdatedEpochMillis = if (uniqueExamples.isNotEmpty()) nowEpochMillis else 0L,
                profileSummaryNote = summary,
                awaitingFeedbackSosCount = awaitingFeedbackCount,
                manualSosEventCount = manualSosCount,
                acknowledgedAlertCount = acknowledgedCount,
                validatedSosEventCount = validatedSosCount
            )
        }

        // Stage == PERSONALISED (>= 5 confirmed examples)
        // Compute bounded EMA/ratio calibration offsets
        val normalRatio = confirmedNormal.size.toFloat() / max(1, totalConfirmed).toFloat()
        val fallRatio = confirmedFalls.size.toFloat() / max(1, totalConfirmed).toFloat()
        val evidenceScale = min(1.0f, (totalConfirmed - MIN_CONFIRMED_EXAMPLES + 1).toFloat() / 6.0f)

        val rawConfDelta = when {
            // Mostly false alarms / normal movements -> modestly raise ML confidence requirement for non-severe impacts
            normalRatio >= 0.70f -> (0.03f + 0.05f * evidenceScale) * normalRatio
            // User has experienced multiple real falls -> slightly increase sensitivity (lower threshold)
            fallRatio >= 0.50f -> -0.02f - (0.04f * evidenceScale * fallRatio)
            else -> 0.015f * evidenceScale
        }.coerceIn(MAX_CONFIDENCE_THRESHOLD_DECREASE, MAX_CONFIDENCE_THRESHOLD_INCREASE)

        val rawImpactDeltaMs2 = when {
            normalRatio >= 0.70f -> (1.2f + 1.8f * evidenceScale) * normalRatio
            fallRatio >= 0.50f -> -0.8f - (1.2f * evidenceScale * fallRatio)
            else -> 0.5f * evidenceScale
        }.coerceIn(MAX_IMPACT_THRESHOLD_DECREASE_MS2, MAX_IMPACT_THRESHOLD_INCREASE_MS2)

        val summary = String.format(
            Locale.US,
            "Personalised with %d confirmed examples (%d normal/phone-drop, %d fall). Recurring false-alarm filter active with hard fall safety override.",
            totalConfirmed,
            confirmedNormal.size,
            confirmedFalls.size
        )

        return PersonalMovementProfile(
            userId = cleanUserId,
            learningStage = PersonalLearningStage.PERSONALISED,
            confirmedNormalCount = confirmedNormal.size,
            confirmedFallCount = confirmedFalls.size,
            excludedUncertainCount = excludedUncertain,
            excludedContradictoryCount = excludedContradictory,
            phoneDropExampleCount = phoneDropCount,
            sittingExampleCount = sittingCount,
            walkingNormalExampleCount = walkingCount,
            falseDetectionExampleCount = falseAlarmCount,
            normalPrototypeCentroid16D = normalCentroid,
            fallPrototypeCentroid16D = fallCentroid,
            calibratedConfidenceDelta = rawConfDelta,
            calibratedImpactThresholdDeltaMs2 = rawImpactDeltaMs2,
            personalisationEnabled = true,
            suppressedFalseAlarmsCount = previousSuppressedCount,
            lastUpdatedEpochMillis = nowEpochMillis,
            profileSummaryNote = summary,
            awaitingFeedbackSosCount = awaitingFeedbackCount,
            manualSosEventCount = manualSosCount,
            acknowledgedAlertCount = acknowledgedCount,
            validatedSosEventCount = validatedSosCount
        )
    }

    /**
     * Layer 3 — Evaluates a candidate motion window using both the frozen Layer 1 general model
     * and the user's Layer 2 [PersonalMovementProfile].
     *
     * Key Safety Guarantee:
     * Personalisation can reduce repeated false detections (such as brisk sitting or phone-drop patterns
     * that closely match the user's confirmed non-fall prototype), but it is NEVER allowed to suppress
     * a high-impact or deep-unloading fall (`hardSafetyOverrideActive == true`).
     */
    fun evaluateLayer3Decision(
        profile: PersonalMovementProfile,
        snapshot: SensorWindowSnapshot,
        cnnResult: CnnInferenceResult?,
        baseMlConfidenceThreshold: Float = HybridFallDetectionEngine.ML_FALL_CONFIDENCE_THRESHOLD,
        baseRuleImpactThresholdMs2: Float = 24.5f,
        candidatePeakMs2: Float = snapshot.peakTotalAccelMs2,
        candidateMinMs2: Float = snapshot.minTotalAccelG * 9.80665f,
        candidateStillnessStdMs2: Float = snapshot.postImpactStillnessStdDevMs2
    ): PersonalCalibrationDecision {
        val minG = min(snapshot.minTotalAccelG, candidateMinMs2 / 9.80665f)
        val peakMs2 = max(snapshot.peakTotalAccelMs2, candidatePeakMs2)
        val stillnessStd = if (candidateStillnessStdMs2 > 0f) {
            candidateStillnessStdMs2
        } else {
            snapshot.postImpactStillnessStdDevMs2
        }

        // Hard safety override: severe physical impact or deep free-fall with post-impact stillness
        val isSeverePhysicalFall = peakMs2 >= HARD_SAFETY_PEAK_ACCEL_MS2 ||
            (minG <= HARD_SAFETY_DEEP_UNLOADING_G &&
                peakMs2 >= HARD_SAFETY_MODERATE_IMPACT_MS2 &&
                stillnessStd <= HARD_SAFETY_STILLNESS_MAX_STD_MS2)

        if (!profile.isPersonalisedActive) {
            return PersonalCalibrationDecision(
                personalisationApplied = false,
                learningStage = profile.learningStage,
                baseMlConfidenceThreshold = baseMlConfidenceThreshold,
                effectiveMlConfidenceThreshold = baseMlConfidenceThreshold,
                baseRuleImpactThresholdMs2 = baseRuleImpactThresholdMs2,
                effectiveRuleImpactThresholdMs2 = baseRuleImpactThresholdMs2,
                similarityToNormalPrototype = null,
                similarityToFallPrototype = null,
                matchedRecurringNonFallPattern = false,
                suppressedRecurringFalseAlarm = false,
                hardSafetyOverrideActive = isSeverePhysicalFall,
                decisionSummary = "Layer 1 General Model (${profile.learningStage.displayLabel}, ${profile.totalConfirmedExamples}/$MIN_CONFIRMED_EXAMPLES examples)"
            )
        }

        val currentVec = extractFeatureVector16D(snapshot, cnnResult)
        val simNormal = if (profile.normalPrototypeCentroid16D.size == FEATURE_VECTOR_DIM && profile.confirmedNormalCount >= 3) {
            cosineSimilarity(currentVec, profile.normalPrototypeCentroid16D)
        } else {
            null
        }
        val simFall = if (profile.fallPrototypeCentroid16D.size == FEATURE_VECTOR_DIM && profile.confirmedFallCount >= 1) {
            cosineSimilarity(currentVec, profile.fallPrototypeCentroid16D)
        } else {
            null
        }

        val matchesRecurringNonFall = simNormal != null &&
            simNormal >= RECURRING_NON_FALL_SIMILARITY_THRESHOLD &&
            (simFall == null || (simNormal - simFall) >= 0.06f)

        val matchesConfirmedFall = simFall != null &&
            simFall >= CONFIRMED_FALL_SIMILARITY_THRESHOLD &&
            (simNormal == null || simFall >= simNormal)

        var effectiveConfThreshold = (baseMlConfidenceThreshold + profile.calibratedConfidenceDelta)
            .coerceIn(
                baseMlConfidenceThreshold + MAX_CONFIDENCE_THRESHOLD_DECREASE,
                baseMlConfidenceThreshold + MAX_CONFIDENCE_THRESHOLD_INCREASE
            )
        var effectiveImpactThresholdMs2 = (baseRuleImpactThresholdMs2 + profile.calibratedImpactThresholdDeltaMs2)
            .coerceIn(
                baseRuleImpactThresholdMs2 + MAX_IMPACT_THRESHOLD_DECREASE_MS2,
                baseRuleImpactThresholdMs2 + MAX_IMPACT_THRESHOLD_INCREASE_MS2
            )

        if (matchesRecurringNonFall && !isSeverePhysicalFall) {
            effectiveConfThreshold = min(
                baseMlConfidenceThreshold + MAX_CONFIDENCE_THRESHOLD_INCREASE,
                effectiveConfThreshold + 0.03f
            )
            effectiveImpactThresholdMs2 = min(
                baseRuleImpactThresholdMs2 + MAX_IMPACT_THRESHOLD_INCREASE_MS2,
                effectiveImpactThresholdMs2 + 1.5f
            )
        } else if (matchesConfirmedFall) {
            effectiveConfThreshold = max(
                baseMlConfidenceThreshold + MAX_CONFIDENCE_THRESHOLD_DECREASE,
                effectiveConfThreshold - 0.03f
            )
            effectiveImpactThresholdMs2 = max(
                baseRuleImpactThresholdMs2 + MAX_IMPACT_THRESHOLD_DECREASE_MS2,
                effectiveImpactThresholdMs2 - 1.0f
            )
        }

        // Determine if a borderline non-severe trigger should be suppressed because it matches
        // the user's recurring normal/phone-drop profile and stays below the personalised threshold
        val cnnConf = cnnResult?.calibratedConfidence ?: 0f
        val cnnPredictedFall = cnnResult?.predictedCategory == MovementCategory.POSSIBLE_FALL
        val suppressed = !isSeverePhysicalFall &&
            matchesRecurringNonFall &&
            peakMs2 < effectiveImpactThresholdMs2 &&
            (!cnnPredictedFall || cnnConf < effectiveConfThreshold)

        val summary = when {
            isSeverePhysicalFall ->
                "Layer 3 Hard Safety Override: high-severity impact (${String.format(Locale.US, "%.1f", peakMs2)} m/s²) always initiates check-in."
            suppressed ->
                "Layer 3 Personalised Filter: suppressed recurring non-fall pattern (similarity ${String.format(Locale.US, "%.0f%%", (simNormal ?: 0f) * 100f)} to your confirmed normal/phone-drop profile)."
            matchesConfirmedFall ->
                "Layer 3 Personalised Match: resembles your confirmed fall profile (similarity ${String.format(Locale.US, "%.0f%%", (simFall ?: 0f) * 100f)})."
            else ->
                "Layer 3 Personalised Active (ML threshold ${String.format(Locale.US, "%.1f%%", effectiveConfThreshold * 100f)}, impact guard ${String.format(Locale.US, "%.1f", effectiveImpactThresholdMs2)} m/s²)."
        }

        return PersonalCalibrationDecision(
            personalisationApplied = true,
            learningStage = profile.learningStage,
            baseMlConfidenceThreshold = baseMlConfidenceThreshold,
            effectiveMlConfidenceThreshold = effectiveConfThreshold,
            baseRuleImpactThresholdMs2 = baseRuleImpactThresholdMs2,
            effectiveRuleImpactThresholdMs2 = effectiveImpactThresholdMs2,
            similarityToNormalPrototype = simNormal,
            similarityToFallPrototype = simFall,
            matchedRecurringNonFallPattern = matchesRecurringNonFall,
            suppressedRecurringFalseAlarm = suppressed,
            hardSafetyOverrideActive = isSeverePhysicalFall,
            decisionSummary = summary
        )
    }

    private fun computeCentroid16D(vectors: List<List<Float>>): List<Float> {
        val valid = vectors.filter { it.size == FEATURE_VECTOR_DIM }
        if (valid.isEmpty()) return emptyList()
        val sums = FloatArray(FEATURE_VECTOR_DIM)
        for (vec in valid) {
            for (i in 0 until FEATURE_VECTOR_DIM) {
                sums[i] += vec[i]
            }
        }
        val n = valid.size.toFloat()
        return List(FEATURE_VECTOR_DIM) { i -> sums[i] / n }
    }

    fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until n) {
            val va = a[i]
            val vb = b[i]
            dot += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom <= 1e-7f) return 0f
        return (dot / denom).coerceIn(0f, 1f)
    }
}

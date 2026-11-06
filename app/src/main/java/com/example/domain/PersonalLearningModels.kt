package com.example.domain

import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Three-stage personalisation readiness progression for SoloSignal AI:
 * - [GETTING_STARTED]: 0 confirmed movement feedback examples recorded. Relies 100% on the frozen Layer 1 general model.
 * - [LEARNING]: 1..4 confirmed movement feedback examples recorded. Gathering baseline patterns; Layer 1 general thresholds remain unchanged.
 * - [PERSONALISED]: >= 5 confirmed movement feedback examples recorded. Layer 2 personal movement profile and Layer 3 bounded calibration are active.
 */
enum class PersonalLearningStage(
    val displayLabel: String,
    val badgeLabel: String,
    val description: String
) {
    GETTING_STARTED(
        displayLabel = "Getting started",
        badgeLabel = "General Model",
        description = "Using the general pre-trained fall detection model. Complete post-check-in feedback to help SoloSignal learn your movement patterns."
    ),
    LEARNING(
        displayLabel = "Learning",
        badgeLabel = "Learning Patterns",
        description = "Collecting confirmed movement examples on this device. General model sensitivity remains unchanged until at least 5 confirmed examples are verified."
    ),
    PERSONALISED(
        displayLabel = "Personalised",
        badgeLabel = "Personalised Active",
        description = "Combining the general pre-trained model with your personal movement profile to reduce repeated false detections while keeping strict fall safety guardrails."
    );

    companion object {
        const val MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION = 5

        fun fromConfirmedCount(confirmedTotal: Int, personalisationEnabled: Boolean = true): PersonalLearningStage {
            if (!personalisationEnabled) return GETTING_STARTED
            return when {
                confirmedTotal <= 0 -> GETTING_STARTED
                confirmedTotal < MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION -> LEARNING
                else -> PERSONALISED
            }
        }

        fun fromStorageKey(raw: String?): PersonalLearningStage {
            if (raw.isNullOrBlank()) return GETTING_STARTED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: GETTING_STARTED
        }
    }
}

/**
 * Origin of a local Personal Learning event record:
 * 1. [QUESTIONNAIRE_ONLY]: User answered the Post-Incident Assessment after cancelling check-in ("I'm OK").
 * 2. [AUTOMATIC_FALL_SOS]: An automatically detected possible fall resulted in an emergency alert being created/dispatched.
 *    Starts as [FeedbackLearningLabel.UNVERIFIED_SOS_EVENT] (`Awaiting feedback`) until verified by user questionnaire.
 * 3. [MANUAL_SOS]: User manually pressed the SOS button. Kept separate from automatic fall detections and never
 *    treated as a confirmed fall label.
 */
enum class SosLearningSourceType(
    val displayLabel: String,
    val shortLabel: String
) {
    QUESTIONNAIRE_ONLY(
        displayLabel = "Post-Incident Questionnaire",
        shortLabel = "Questionnaire"
    ),
    AUTOMATIC_FALL_SOS(
        displayLabel = "Automatic Fall SOS Alert",
        shortLabel = "Automatic SOS"
    ),
    MANUAL_SOS(
        displayLabel = "Manual SOS Button",
        shortLabel = "Manual SOS"
    );

    companion object {
        fun fromStorageKey(raw: String?): SosLearningSourceType {
            if (raw.isNullOrBlank()) return QUESTIONNAIRE_ONLY
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: QUESTIONNAIRE_ONLY
        }
    }
}

/**
 * User-facing validation & learning eligibility status displayed in the Personal Learning history.
 * Ensures an event is never shown as "learned" merely because an SOS alert was sent or acknowledged.
 */
enum class PersonalLearningEventStatus(
    val displayLabel: String,
    val badgeLabel: String
) {
    AWAITING_FEEDBACK(
        displayLabel = "Awaiting feedback",
        badgeLabel = "Awaiting feedback"
    ),
    CONFIRMED_MOVEMENT_PATTERN(
        displayLabel = "Confirmed movement pattern",
        badgeLabel = "Confirmed pattern"
    ),
    EXCLUDED_FROM_FALL_LEARNING(
        displayLabel = "Excluded from fall learning",
        badgeLabel = "Excluded"
    ),
    MANUAL_SOS_NOT_A_FALL_LABEL(
        displayLabel = "Manual SOS — not a fall label",
        badgeLabel = "Manual SOS"
    );

    companion object {
        fun fromStorageKey(raw: String?): PersonalLearningEventStatus {
            if (raw.isNullOrBlank()) return EXCLUDED_FROM_FALL_LEARNING
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: EXCLUDED_FROM_FALL_LEARNING
        }
    }
}

/**
 * Delivery and emergency-contact acknowledgement lifecycle recorded on a local learning event.
 * Important: Alert creation, delivery, or recipient acknowledgement NEVER confirms that a fall occurred.
 */
enum class SosAlertOutcomeState(
    val displayLabel: String,
    val shortLabel: String
) {
    NONE(
        displayLabel = "No alert sent",
        shortLabel = "Local only"
    ),
    ALERT_CREATED(
        displayLabel = "Alert document created",
        shortLabel = "Alert created"
    ),
    DISPATCH_ACCEPTED(
        displayLabel = "Emergency alert dispatched to contact",
        shortLabel = "Dispatch accepted"
    ),
    RECIPIENT_ACKNOWLEDGED(
        displayLabel = "Emergency contact acknowledged alert",
        shortLabel = "Contact acknowledged"
    ),
    DISPATCH_FAILED(
        displayLabel = "Emergency alert delivery failed",
        shortLabel = "Dispatch failed"
    );

    companion object {
        fun fromStorageKey(raw: String?): SosAlertOutcomeState {
            if (raw.isNullOrBlank()) return NONE
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: NONE
        }
    }
}

/**
 * Explicit feedback label derived from the user's Post-Fall Assessment answers or SOS event state.
 * Pressing "I'm OK", sending an SOS alert, or receiving a contact acknowledgement never automatically
 * labels an event as a confirmed fall; only the user's explicit incident verification and symptom
 * consistency determine a training label.
 */
enum class FeedbackLearningLabel(
    val displayLabel: String,
    val shortLabel: String,
    val isConfirmedTrainingExample: Boolean,
    val isConfirmedFall: Boolean,
    val isConfirmedNormalOrFalseAlarm: Boolean
) {
    CONFIRMED_FALL(
        displayLabel = "Confirmed user-reported fall",
        shortLabel = "User-reported fall",
        isConfirmedTrainingExample = true,
        isConfirmedFall = true,
        isConfirmedNormalOrFalseAlarm = false
    ),
    PHONE_ONLY_INCIDENT(
        displayLabel = "Phone-only incident (not a human fall)",
        shortLabel = "Phone drop only",
        isConfirmedTrainingExample = true,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = true
    ),
    NORMAL_SITTING(
        displayLabel = "Normal sitting movement",
        shortLabel = "Sitting down",
        isConfirmedTrainingExample = true,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = true
    ),
    NORMAL_MOVEMENT(
        displayLabel = "Normal walking or everyday movement",
        shortLabel = "Normal movement",
        isConfirmedTrainingExample = true,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = true
    ),
    FALSE_DETECTION(
        displayLabel = "User-reported false detection",
        shortLabel = "Wrong detection",
        isConfirmedTrainingExample = true,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = true
    ),
    UNVERIFIED_SOS_EVENT(
        displayLabel = "Unverified automatic SOS event — awaiting user feedback",
        shortLabel = "Awaiting feedback",
        isConfirmedTrainingExample = false,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = false
    ),
    MANUAL_SOS_EVENT(
        displayLabel = "Manual SOS — user-initiated help request (not a fall label)",
        shortLabel = "Manual SOS — not a fall label",
        isConfirmedTrainingExample = false,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = false
    ),
    UNCERTAIN_EXCLUDED(
        displayLabel = "Uncertain — excluded from personal learning",
        shortLabel = "Uncertain (excluded)",
        isConfirmedTrainingExample = false,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = false
    ),
    CONTRADICTORY_EXCLUDED(
        displayLabel = "Contradictory symptoms — excluded for safety",
        shortLabel = "Excluded for safety",
        isConfirmedTrainingExample = false,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = false
    ),
    LOW_QUALITY_EXCLUDED(
        displayLabel = "Incomplete sensor window — excluded",
        shortLabel = "Sensor gap (excluded)",
        isConfirmedTrainingExample = false,
        isConfirmedFall = false,
        isConfirmedNormalOrFalseAlarm = false
    );

    companion object {
        fun fromStorageKey(raw: String?): FeedbackLearningLabel {
            if (raw.isNullOrBlank()) return UNCERTAIN_EXCLUDED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: UNCERTAIN_EXCLUDED
        }
    }
}

/**
 * Single movement/SOS learning record stored strictly on-device in Room.
 * Contains compact kinematic and 16-D normalized feature embeddings (never raw audio or cloud-uploaded daily motion),
 * plus SOS alert linkage (`alertId`), detection method, physics fallback involvement, alert delivery/acknowledgement
 * outcome, and questionnaire validation state.
 */
data class PersonalLearningExample(
    val exampleId: String = "ple_${UUID.randomUUID().toString().take(10)}_${System.currentTimeMillis()}",
    val userId: String = "local_default",
    val linkedEventId: String,
    val timestampEpochMillis: Long = System.currentTimeMillis(),
    val incidentVerification: IncidentVerificationType,
    val learningLabel: FeedbackLearningLabel,
    val featureVector16D: List<Float>,
    val peakTotalAccelG: Float,
    val minTotalAccelG: Float,
    val postImpactStillnessStdDevMs2: Float,
    val peakGyroDegPerSec: Float,
    val baseModelPredictedCategory: MovementCategory,
    val baseModelConfidence: Float?,
    val includedInTraining: Boolean,
    val validationNote: String,
    val alertId: String = "",
    val sourceType: SosLearningSourceType = SosLearningSourceType.QUESTIONNAIRE_ONLY,
    val eventStatus: PersonalLearningEventStatus = when {
        learningLabel == FeedbackLearningLabel.UNVERIFIED_SOS_EVENT ->
            PersonalLearningEventStatus.AWAITING_FEEDBACK
        learningLabel == FeedbackLearningLabel.MANUAL_SOS_EVENT ||
            sourceType == SosLearningSourceType.MANUAL_SOS ->
            PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL
        includedInTraining && learningLabel.isConfirmedTrainingExample ->
            PersonalLearningEventStatus.CONFIRMED_MOVEMENT_PATTERN
        else ->
            PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING
    },
    val detectionMethod: String = "HYBRID_CNN_PLUS_PHYSICS",
    val physicsFallbackInvolved: Boolean = false,
    val alertOutcome: SosAlertOutcomeState = SosAlertOutcomeState.NONE,
    val recipientAcknowledged: Boolean = false,
    val acknowledgedAtEpochMillis: Long? = null,
    val acknowledgedByName: String = "",
    val questionnaireCompleted: Boolean = learningLabel != FeedbackLearningLabel.UNVERIFIED_SOS_EVENT &&
        learningLabel != FeedbackLearningLabel.MANUAL_SOS_EVENT,
    val questionnaireCompletedAtEpochMillis: Long? = if (
        learningLabel != FeedbackLearningLabel.UNVERIFIED_SOS_EVENT &&
        learningLabel != FeedbackLearningLabel.MANUAL_SOS_EVENT
    ) {
        timestampEpochMillis
    } else {
        null
    }
) {
    /**
     * Canonical deduplication key ensuring a single incident or SOS alert can contribute to
     * personal calibration at most once, even if linked by both `alertId` and `linkedEventId`.
     */
    val canonicalDedupKey: String
        get() = alertId.trim().takeIf { it.isNotBlank() }
            ?: linkedEventId.trim().takeIf { it.isNotBlank() }
            ?: exampleId

    /**
     * True when this record is an automatic SOS event that has not yet been verified through
     * the Post-Incident Assessment questionnaire.
     */
    val isAwaitingUserFeedback: Boolean
        get() = sourceType == SosLearningSourceType.AUTOMATIC_FALL_SOS &&
            !questionnaireCompleted &&
            (learningLabel == FeedbackLearningLabel.UNVERIFIED_SOS_EVENT ||
                eventStatus == PersonalLearningEventStatus.AWAITING_FEEDBACK)
}

/**
 * Layer 2 — Personal Movement Profile learned locally for a specific user.
 * Stores prototype centroids in 16-D normalized kinematic/latent feature space and bounded calibration offsets.
 */
data class PersonalMovementProfile(
    val userId: String = "local_default",
    val learningStage: PersonalLearningStage = PersonalLearningStage.GETTING_STARTED,
    val confirmedNormalCount: Int = 0,
    val confirmedFallCount: Int = 0,
    val excludedUncertainCount: Int = 0,
    val excludedContradictoryCount: Int = 0,
    val phoneDropExampleCount: Int = 0,
    val sittingExampleCount: Int = 0,
    val walkingNormalExampleCount: Int = 0,
    val falseDetectionExampleCount: Int = 0,
    val normalPrototypeCentroid16D: List<Float> = emptyList(),
    val fallPrototypeCentroid16D: List<Float> = emptyList(),
    val calibratedConfidenceDelta: Float = 0f,
    val calibratedImpactThresholdDeltaMs2: Float = 0f,
    val personalisationEnabled: Boolean = true,
    val suppressedFalseAlarmsCount: Int = 0,
    val lastUpdatedEpochMillis: Long = 0L,
    val profileSummaryNote: String = "Using general pre-trained model baseline.",
    val awaitingFeedbackSosCount: Int = 0,
    val manualSosEventCount: Int = 0,
    val acknowledgedAlertCount: Int = 0,
    val validatedSosEventCount: Int = 0
) {
    val totalConfirmedExamples: Int
        get() = confirmedNormalCount + confirmedFallCount

    val totalExcludedExamples: Int
        get() = excludedUncertainCount + excludedContradictoryCount

    val isPersonalisedActive: Boolean
        get() = personalisationEnabled &&
            learningStage == PersonalLearningStage.PERSONALISED &&
            totalConfirmedExamples >= PersonalLearningStage.MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION

    fun formatEffectiveAdjustmentsSummary(): String {
        if (!isPersonalisedActive) {
            val remaining = (PersonalLearningStage.MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION - totalConfirmedExamples)
                .coerceAtLeast(0)
            val awaitingSuffix = if (awaitingFeedbackSosCount > 0) {
                " ($awaitingFeedbackSosCount SOS event(s) awaiting feedback)"
            } else {
                ""
            }
            return if (totalConfirmedExamples == 0) {
                "General model active (0 of ${PersonalLearningStage.MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION} confirmed examples recorded)$awaitingSuffix."
            } else {
                "Learning your movement patterns ($totalConfirmedExamples of ${PersonalLearningStage.MIN_CONFIRMED_EXAMPLES_FOR_PERSONALISATION} confirmed examples; $remaining more needed before adapting)$awaitingSuffix."
            }
        }
        val confSign = if (calibratedConfidenceDelta >= 0f) "+" else ""
        val impSign = if (calibratedImpactThresholdDeltaMs2 >= 0f) "+" else ""
        return String.format(
            Locale.US,
            "Personalised calibration active: ML confidence Δ %s%.1f%%, recurring non-fall impact guard %s%.1f m/s².",
            confSign,
            calibratedConfidenceDelta * 100f,
            impSign,
            calibratedImpactThresholdDeltaMs2
        )
    }
}

/**
 * Layer 3 — Output of the Personalised Decision System for a candidate motion window.
 */
data class PersonalCalibrationDecision(
    val personalisationApplied: Boolean = false,
    val learningStage: PersonalLearningStage = PersonalLearningStage.GETTING_STARTED,
    val baseMlConfidenceThreshold: Float = 0.72f,
    val effectiveMlConfidenceThreshold: Float = 0.72f,
    val baseRuleImpactThresholdMs2: Float = 24.5f,
    val effectiveRuleImpactThresholdMs2: Float = 24.5f,
    val similarityToNormalPrototype: Float? = null,
    val similarityToFallPrototype: Float? = null,
    val matchedRecurringNonFallPattern: Boolean = false,
    val suppressedRecurringFalseAlarm: Boolean = false,
    val hardSafetyOverrideActive: Boolean = false,
    val decisionSummary: String = "Evaluated by general pre-trained model + 3-phase rule fallback."
)

/**
 * JSON codec for storing 16-D float feature vectors in Room.
 */
object PersonalLearningJsonCodec {
    fun encodeFloatList(values: List<Float>): String {
        val arr = JSONArray()
        for (v in values) {
            val clean = if (v.isNaN() || v.isInfinite()) 0.0 else v.toDouble()
            arr.put(clean)
        }
        return arr.toString()
    }

    fun decodeFloatList(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Float>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(arr.optDouble(i, 0.0).toFloat())
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeDecisionJson(decision: PersonalCalibrationDecision): String {
        return try {
            JSONObject().apply {
                put("personalisationApplied", decision.personalisationApplied)
                put("learningStage", decision.learningStage.name)
                put("effectiveMlConfidenceThreshold", decision.effectiveMlConfidenceThreshold.toDouble())
                put("effectiveRuleImpactThresholdMs2", decision.effectiveRuleImpactThresholdMs2.toDouble())
                if (decision.similarityToNormalPrototype != null) {
                    put("similarityToNormalPrototype", decision.similarityToNormalPrototype.toDouble())
                }
                if (decision.similarityToFallPrototype != null) {
                    put("similarityToFallPrototype", decision.similarityToFallPrototype.toDouble())
                }
                put("matchedRecurringNonFallPattern", decision.matchedRecurringNonFallPattern)
                put("suppressedRecurringFalseAlarm", decision.suppressedRecurringFalseAlarm)
                put("hardSafetyOverrideActive", decision.hardSafetyOverrideActive)
                put("decisionSummary", decision.decisionSummary)
            }.toString()
        } catch (_: Exception) {
            ""
        }
    }
}

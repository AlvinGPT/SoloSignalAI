package com.example.sensor

import com.example.domain.FeedbackLearningLabel
import com.example.domain.FeelingOkayStatus
import com.example.domain.IncidentVerificationType
import com.example.domain.ModelReadinessStatus
import com.example.domain.MotionSample
import com.example.domain.MovementCategory
import com.example.domain.PainLevel
import com.example.domain.PersonalLearningEventStatus
import com.example.domain.PersonalLearningExample
import com.example.domain.PersonalLearningStage
import com.example.domain.PostFallAssessmentDraft
import com.example.domain.SensorWindowSnapshot
import com.example.domain.SosAlertOutcomeState
import com.example.domain.SosLearningSourceType
import com.example.domain.TriStateAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying the upgraded Personal Learning Engine across all 10 required
 * SOS event, emergency-contact acknowledgement, and questionnaire-linkage scenarios.
 */
class PersonalLearningSosEventTest {

    private val fallLikeSensorSnapshot = SensorWindowSnapshot(
        windowSampleCount = 200,
        windowStartNanos = 1_000_000_000L,
        windowEndNanos = 5_000_000_000L,
        empiricalSamplingRateHz = 50.0f,
        minTotalAccelG = 0.28f,
        peakTotalAccelMs2 = 34.5f,
        peakVerticalBodyAccelG = 2.6f,
        peakHorizontalBodyAccelG = 1.4f,
        peakJerkGPerSec = 38.0f,
        peakGyroDegPerSec = 185.0f,
        postImpactStillnessStdDevMs2 = 0.32f,
        hasGyroscope = true,
        downsampledMagnitudeEnvelopeG = listOf(1.0f, 0.28f, 3.52f, 1.02f, 0.99f, 1.00f)
    )

    private val calibratedCnnFallResult = CnnInferenceResult(
        readiness = ModelReadinessStatus.CALIBRATED_READY,
        modelVersion = "solosignal_cnn_1d_v1.2",
        predictedCategory = MovementCategory.POSSIBLE_FALL,
        calibratedConfidence = 0.84f,
        classProbabilities = mapOf(
            MovementCategory.STANDING_STILL to 0.02f,
            MovementCategory.WALKING to 0.03f,
            MovementCategory.SITTING_DOWN to 0.05f,
            MovementCategory.STAIR_MOVEMENT to 0.03f,
            MovementCategory.POSSIBLE_FALL to 0.84f,
            MovementCategory.UNKNOWN_UNCERTAIN to 0.03f
        ),
        normalizedEntropy = 0.26f,
        isUncertain = false,
        inferenceLatencyMs = 3.2
    )

    /**
     * 1. Automatic SOS creates an unverified learning event:
     * When an automatically detected fall results in an SOS alert, it captures the 16-D movement
     * features, associates them with the unique alertId, records detection method, CNN classification,
     * physics fallback involvement, model confidence, and alert outcome, and stores the event as
     * UNVERIFIED_SOS_EVENT (`includedInTraining = false`, `eventStatus = AWAITING_FEEDBACK`).
     */
    @Test
    fun automaticSos_createsUnverifiedLearningEvent_notConfirmedFall() {
        val autoSosEvent = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_fall_evt_101",
            linkedEventId = "fall_evt_101",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult,
            triggeredByRuleFallback = false,
            triggerSource = "HYBRID_3PHASE_AND_CNN",
            alertOutcome = SosAlertOutcomeState.DISPATCH_ACCEPTED,
            timestampEpochMillis = 1700000100000L
        )

        assertEquals("alert_fall_evt_101", autoSosEvent.alertId)
        assertEquals("fall_evt_101", autoSosEvent.linkedEventId)
        assertEquals(SosLearningSourceType.AUTOMATIC_FALL_SOS, autoSosEvent.sourceType)
        assertEquals(FeedbackLearningLabel.UNVERIFIED_SOS_EVENT, autoSosEvent.learningLabel)
        assertEquals(PersonalLearningEventStatus.AWAITING_FEEDBACK, autoSosEvent.eventStatus)
        assertEquals("Awaiting feedback", autoSosEvent.eventStatus.displayLabel)
        assertFalse("Automatic SOS must NOT be immediately included in training", autoSosEvent.includedInTraining)
        assertFalse("Automatic SOS must NOT be marked as a confirmed fall", autoSosEvent.learningLabel.isConfirmedFall)
        assertFalse("Questionnaire is not yet completed", autoSosEvent.questionnaireCompleted)
        assertTrue(autoSosEvent.isAwaitingUserFeedback)
        assertEquals(16, autoSosEvent.featureVector16D.size)
        assertEquals(MovementCategory.POSSIBLE_FALL, autoSosEvent.baseModelPredictedCategory)
        assertEquals(0.84f, autoSosEvent.baseModelConfidence ?: 0f, 0.0001f)
        assertEquals("HYBRID_CNN_PLUS_PHYSICS", autoSosEvent.detectionMethod)
        assertTrue(autoSosEvent.physicsFallbackInvolved)
        assertEquals(SosAlertOutcomeState.DISPATCH_ACCEPTED, autoSosEvent.alertOutcome)
    }

    /**
     * 2. Manual SOS does not create a confirmed-fall label:
     * When the user manually presses the SOS button, it records a user-initiated MANUAL_SOS event,
     * captures local movement context if available, does NOT classify it as a confirmed fall,
     * and does NOT treat it as a movement-training label.
     */
    @Test
    fun manualSos_doesNotCreateConfirmedFallOrTrainingLabel() {
        val manualSosEvent = PersonalLearningEngine.createManualSosLearningEvent(
            userId = "user_senior_1",
            alertId = "sos_manual_202",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult,
            alertOutcome = SosAlertOutcomeState.DISPATCH_ACCEPTED,
            timestampEpochMillis = 1700000200000L
        )

        assertEquals("sos_manual_202", manualSosEvent.alertId)
        assertEquals(SosLearningSourceType.MANUAL_SOS, manualSosEvent.sourceType)
        assertEquals(FeedbackLearningLabel.MANUAL_SOS_EVENT, manualSosEvent.learningLabel)
        assertEquals(PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL, manualSosEvent.eventStatus)
        assertEquals("Manual SOS — not a fall label", manualSosEvent.eventStatus.displayLabel)
        assertFalse("Manual SOS must never be included in fall training", manualSosEvent.includedInTraining)
        assertFalse("Manual SOS must never be labeled as a confirmed fall", manualSosEvent.learningLabel.isConfirmedFall)
        assertEquals("USER_MANUAL_SOS", manualSosEvent.detectionMethod)
        assertFalse(manualSosEvent.physicsFallbackInvolved)

        // Even if a questionnaire is later linked to a Manual SOS, it must remain excluded from fall training
        val draftForManual = PostFallAssessmentDraft(
            linkedEventId = "sos_manual_202",
            triggeredAlertId = "sos_manual_202",
            incidentVerification = IncidentVerificationType.EXPERIENCED_FALL
        )
        val linkOutcome = PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = draftForManual,
            existingEvent = manualSosEvent,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        )
        assertTrue(linkOutcome is QuestionnaireLinkOutcome.ValidatedOrUpdated)
        val updatedManual = (linkOutcome as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample
        assertFalse("Manual SOS must still not be included in fall training", updatedManual.includedInTraining)
        assertEquals(PersonalLearningEventStatus.MANUAL_SOS_NOT_A_FALL_LABEL, updatedManual.eventStatus)
    }

    /**
     * 3. Recipient acknowledgement does not create a fall label:
     * When the connected emergency contact receives and acknowledges an alert, the acknowledgement
     * is recorded on the original alertId, but it NEVER converts an unverified or manual SOS into
     * CONFIRMED_FALL or enables training.
     */
    @Test
    fun recipientAcknowledgement_recordsOutcomeButNeverCreatesConfirmedFallLabel() {
        val unverifiedAutoSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_fall_evt_303",
            linkedEventId = "fall_evt_303",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult,
            alertOutcome = SosAlertOutcomeState.DISPATCH_ACCEPTED
        )

        val acknowledgedEvent = PersonalLearningEngine.applyEmergencyContactAcknowledgement(
            existing = unverifiedAutoSos,
            acknowledgedByName = "Caretaker Alice",
            acknowledgedAtEpochMillis = 1700000305000L
        )

        assertTrue(acknowledgedEvent.recipientAcknowledged)
        assertEquals(SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED, acknowledgedEvent.alertOutcome)
        assertEquals("Caretaker Alice", acknowledgedEvent.acknowledgedByName)
        assertEquals(1700000305000L, acknowledgedEvent.acknowledgedAtEpochMillis)
        assertEquals(
            "Acknowledgement must NOT change UNVERIFIED_SOS_EVENT to CONFIRMED_FALL",
            FeedbackLearningLabel.UNVERIFIED_SOS_EVENT,
            acknowledgedEvent.learningLabel
        )
        assertEquals(PersonalLearningEventStatus.AWAITING_FEEDBACK, acknowledgedEvent.eventStatus)
        assertFalse("Acknowledged unverified SOS must NOT be included in training", acknowledgedEvent.includedInTraining)

        val profile = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = listOf(acknowledgedEvent)
        )
        assertEquals(0, profile.confirmedFallCount)
        assertEquals(0, profile.totalConfirmedExamples)
        assertEquals(1, profile.acknowledgedAlertCount)
        assertEquals(1, profile.awaitingFeedbackSosCount)
    }

    /**
     * 4. A later confirmed questionnaire validates the linked event:
     * Completing the Post-Incident Assessment with "I fell" (`EXPERIENCED_FALL`) links to the
     * original SOS event by `alertId`, preserves the original 16-D movement feature vector,
     * and converts the unverified event into `CONFIRMED_FALL`.
     */
    @Test
    fun laterConfirmedQuestionnaire_validatesLinkedAutomaticSosEventIntoConfirmedFall() {
        val unverifiedAutoSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_fall_evt_404",
            linkedEventId = "fall_evt_404",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult,
            alertOutcome = SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED
        )

        // Simulate a later questionnaire when the live sensor window is flat/idle on a table
        val idleLaterSnapshot = SensorWindowSnapshot(
            windowSampleCount = 200,
            minTotalAccelG = 0.99f,
            peakTotalAccelMs2 = 9.81f
        )
        val draft = PostFallAssessmentDraft(
            linkedEventId = "fall_evt_404",
            triggeredAlertId = "alert_fall_evt_404",
            incidentVerification = IncidentVerificationType.EXPERIENCED_FALL,
            feelingOkay = FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE,
            painLevel = PainLevel.MILD_PAIN
        )

        val outcome = PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = draft,
            existingEvent = unverifiedAutoSos,
            fallbackSnapshot = idleLaterSnapshot,
            fallbackCnnResult = null,
            timestampEpochMillis = 1700000450000L
        )

        assertTrue(outcome is QuestionnaireLinkOutcome.ValidatedOrUpdated)
        val validated = (outcome as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample
        assertTrue(outcome.wasLinkedToSosEvent)
        assertEquals(unverifiedAutoSos.exampleId, validated.exampleId)
        assertEquals("alert_fall_evt_404", validated.alertId)
        assertEquals(FeedbackLearningLabel.CONFIRMED_FALL, validated.learningLabel)
        assertEquals(PersonalLearningEventStatus.CONFIRMED_MOVEMENT_PATTERN, validated.eventStatus)
        assertTrue(validated.includedInTraining)
        assertTrue(validated.questionnaireCompleted)
        assertFalse(validated.isAwaitingUserFeedback)
        // Verify original 16-D fall feature vector was preserved instead of overwritten by idleLaterSnapshot
        assertEquals(unverifiedAutoSos.featureVector16D, validated.featureVector16D)
    }

    /**
     * 5. False detection feedback updates the correct learning category:
     * Linking questionnaire responses for normal sitting, phone-only incident, and false detection
     * converts the unverified SOS events into NORMAL_SITTING, PHONE_ONLY_INCIDENT, and FALSE_DETECTION.
     */
    @Test
    fun falseDetectionFeedback_updatesCorrectLearningCategories() {
        val sittingSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_sit_501",
            linkedEventId = "evt_sit_501",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )
        val phoneDropSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_phone_502",
            linkedEventId = "evt_phone_502",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )
        val falseAlarmSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_false_503",
            linkedEventId = "evt_false_503",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )

        val validatedSitting = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = "evt_sit_501",
                triggeredAlertId = "alert_sit_501",
                incidentVerification = IncidentVerificationType.SITTING_DOWN,
                feelingOkay = FeelingOkayStatus.YES,
                painLevel = PainLevel.NO_PAIN
            ),
            existingEvent = sittingSos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        val validatedPhoneDrop = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = "evt_phone_502",
                triggeredAlertId = "alert_phone_502",
                incidentVerification = IncidentVerificationType.PHONE_FELL_NOT_ME,
                feelingOkay = FeelingOkayStatus.YES,
                painLevel = PainLevel.NO_PAIN
            ),
            existingEvent = phoneDropSos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        val validatedFalseAlarm = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = "evt_false_503",
                triggeredAlertId = "alert_false_503",
                incidentVerification = IncidentVerificationType.FALSE_ALARM,
                feelingOkay = FeelingOkayStatus.YES,
                painLevel = PainLevel.NO_PAIN
            ),
            existingEvent = falseAlarmSos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        assertEquals(FeedbackLearningLabel.NORMAL_SITTING, validatedSitting.learningLabel)
        assertEquals(FeedbackLearningLabel.PHONE_ONLY_INCIDENT, validatedPhoneDrop.learningLabel)
        assertEquals(FeedbackLearningLabel.FALSE_DETECTION, validatedFalseAlarm.learningLabel)

        val profile = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = listOf(validatedSitting, validatedPhoneDrop, validatedFalseAlarm)
        )
        assertEquals(3, profile.confirmedNormalCount)
        assertEquals(0, profile.confirmedFallCount)
        assertEquals(1, profile.sittingExampleCount)
        assertEquals(1, profile.phoneDropExampleCount)
        assertEquals(1, profile.falseDetectionExampleCount)
        assertEquals(3, profile.validatedSosEventCount)
    }

    /**
     * 6. Duplicate questionnaires do not train twice:
     * Submitting a second questionnaire for an already-validated SOS event returns DuplicateIgnored
     * and `recomputePersonalMovementProfile` deduplicates by canonicalDedupKey.
     */
    @Test
    fun duplicateQuestionnaires_doNotTrainTwice() {
        val unverifiedSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_dup_601",
            linkedEventId = "evt_dup_601",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )

        val draft = PostFallAssessmentDraft(
            linkedEventId = "evt_dup_601",
            triggeredAlertId = "alert_dup_601",
            incidentVerification = IncidentVerificationType.EXPERIENCED_FALL,
            feelingOkay = FeelingOkayStatus.YES
        )

        val firstOutcome = PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = draft,
            existingEvent = unverifiedSos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        )
        assertTrue(firstOutcome is QuestionnaireLinkOutcome.ValidatedOrUpdated)
        val validatedOnce = (firstOutcome as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        // Second submission for the same event must be rejected as DuplicateIgnored
        val secondOutcome = PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = draft,
            existingEvent = validatedOnce,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        )
        assertTrue(
            "Second questionnaire for the same event must be ignored",
            secondOutcome is QuestionnaireLinkOutcome.DuplicateIgnored
        )

        // Even if duplicate rows with the same alertId/linkedEventId are passed to recomputePersonalMovementProfile,
        // deduplication ensures the event counts only once
        val profile = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = listOf(validatedOnce, validatedOnce.copy(exampleId = "duplicate_row_id"))
        )
        assertEquals(1, profile.confirmedFallCount)
        assertEquals(1, profile.totalConfirmedExamples)
    }

    /**
     * 7. Unanswered assessments remain unverified (and uncertain/contradictory answers are excluded):
     * An automatic SOS event without a completed questionnaire remains UNVERIFIED_SOS_EVENT (`Awaiting feedback`),
     * and answering `NOT_SURE` or contradictory symptoms is excluded from training.
     */
    @Test
    fun unansweredAssessments_remainUnverified_andUncertainOrContradictoryAreExcluded() {
        val unansweredSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_unanswered_701",
            linkedEventId = "evt_unanswered_701",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )

        assertTrue(unansweredSos.isAwaitingUserFeedback)
        assertFalse(unansweredSos.questionnaireCompleted)
        assertFalse(unansweredSos.includedInTraining)
        assertEquals(FeedbackLearningLabel.UNVERIFIED_SOS_EVENT, unansweredSos.learningLabel)
        assertEquals(PersonalLearningEventStatus.AWAITING_FEEDBACK, unansweredSos.eventStatus)

        // Uncertain answer ("Not sure") on another SOS event -> excluded from fall learning
        val uncertainSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_uncertain_702",
            linkedEventId = "evt_uncertain_702",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )
        val uncertainValidated = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = "evt_uncertain_702",
                triggeredAlertId = "alert_uncertain_702",
                incidentVerification = IncidentVerificationType.NOT_SURE
            ),
            existingEvent = uncertainSos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        assertFalse(uncertainValidated.includedInTraining)
        assertEquals(FeedbackLearningLabel.UNCERTAIN_EXCLUDED, uncertainValidated.learningLabel)
        assertEquals(PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING, uncertainValidated.eventStatus)

        // Contradictory answer (says "Normal movement" but reports severe pain + hit head) -> excluded for safety
        val contradictorySos = PersonalLearningEngine.createAutomaticSosLearningEvent(
            userId = "user_senior_1",
            alertId = "alert_contradict_703",
            linkedEventId = "evt_contradict_703",
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )
        val contradictoryValidated = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = "evt_contradict_703",
                triggeredAlertId = "alert_contradict_703",
                incidentVerification = IncidentVerificationType.NORMAL_MOVEMENT,
                painLevel = PainLevel.SEVERE_PAIN,
                hitHead = TriStateAnswer.YES
            ),
            existingEvent = contradictorySos,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        assertFalse(contradictoryValidated.includedInTraining)
        assertEquals(FeedbackLearningLabel.CONTRADICTORY_EXCLUDED, contradictoryValidated.learningLabel)
        assertEquals(PersonalLearningEventStatus.EXCLUDED_FROM_FALL_LEARNING, contradictoryValidated.eventStatus)
    }

    /**
     * 8. Personal Learning still requires five eligible examples:
     * Unverified SOS events, Manual SOS events, and contact acknowledgements do not count toward
     * the 5 confirmed examples required to activate the PERSONALISED stage.
     */
    @Test
    fun personalLearning_stillRequiresFiveEligibleValidatedExamplesBeforePersonalisedStage() {
        val examples = mutableListOf<PersonalLearningExample>()

        // Add 6 unverified automatic SOS events (3 acknowledged by contact) and 4 manual SOS events
        for (i in 1..6) {
            val autoSos = PersonalLearningEngine.createAutomaticSosLearningEvent(
                userId = "user_senior_1",
                alertId = "unverified_auto_$i",
                linkedEventId = "evt_auto_$i",
                snapshot = fallLikeSensorSnapshot,
                cnnResult = calibratedCnnFallResult
            )
            examples.add(
                if (i <= 3) PersonalLearningEngine.applyEmergencyContactAcknowledgement(autoSos, "Caretaker")
                else autoSos
            )
        }
        for (i in 1..4) {
            examples.add(
                PersonalLearningEngine.createManualSosLearningEvent(
                    userId = "user_senior_1",
                    alertId = "manual_sos_$i",
                    snapshot = fallLikeSensorSnapshot,
                    cnnResult = calibratedCnnFallResult
                )
            )
        }

        // With 0 validated examples, stage MUST be GETTING_STARTED and deltas 0f
        val profileWithZeroValidated = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = examples
        )
        assertEquals(PersonalLearningStage.GETTING_STARTED, profileWithZeroValidated.learningStage)
        assertEquals(0, profileWithZeroValidated.totalConfirmedExamples)
        assertEquals(0f, profileWithZeroValidated.calibratedConfidenceDelta, 0.0001f)
        assertEquals(0f, profileWithZeroValidated.calibratedImpactThresholdDeltaMs2, 0.0001f)
        assertEquals(6, profileWithZeroValidated.awaitingFeedbackSosCount)
        assertEquals(4, profileWithZeroValidated.manualSosEventCount)
        assertEquals(3, profileWithZeroValidated.acknowledgedAlertCount)

        // Validate 4 of the automatic SOS events -> stage becomes LEARNING (still < 5, deltas still 0f)
        for (i in 0 until 4) {
            val existing = examples[i]
            val validated = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
                userId = "user_senior_1",
                draft = PostFallAssessmentDraft(
                    linkedEventId = existing.linkedEventId,
                    triggeredAlertId = existing.alertId,
                    incidentVerification = IncidentVerificationType.SITTING_DOWN,
                    feelingOkay = FeelingOkayStatus.YES,
                    painLevel = PainLevel.NO_PAIN
                ),
                existingEvent = existing,
                fallbackSnapshot = fallLikeSensorSnapshot,
                fallbackCnnResult = calibratedCnnFallResult
            ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample
            examples[i] = validated
        }

        val profileWithFourValidated = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = examples
        )
        assertEquals(PersonalLearningStage.LEARNING, profileWithFourValidated.learningStage)
        assertEquals(4, profileWithFourValidated.totalConfirmedExamples)
        assertFalse(profileWithFourValidated.isPersonalisedActive)
        assertEquals(0f, profileWithFourValidated.calibratedConfidenceDelta, 0.0001f)

        // Validate the 5th automatic SOS event -> stage transitions to PERSONALISED!
        val fifthExisting = examples[4]
        examples[4] = (PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
            userId = "user_senior_1",
            draft = PostFallAssessmentDraft(
                linkedEventId = fifthExisting.linkedEventId,
                triggeredAlertId = fifthExisting.alertId,
                incidentVerification = IncidentVerificationType.PHONE_FELL_NOT_ME,
                feelingOkay = FeelingOkayStatus.YES,
                painLevel = PainLevel.NO_PAIN
            ),
            existingEvent = fifthExisting,
            fallbackSnapshot = fallLikeSensorSnapshot,
            fallbackCnnResult = calibratedCnnFallResult
        ) as QuestionnaireLinkOutcome.ValidatedOrUpdated).updatedExample

        val profileWithFiveValidated = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = examples
        )
        assertEquals(PersonalLearningStage.PERSONALISED, profileWithFiveValidated.learningStage)
        assertEquals(5, profileWithFiveValidated.totalConfirmedExamples)
        assertTrue(profileWithFiveValidated.isPersonalisedActive)
        assertEquals(5, profileWithFiveValidated.validatedSosEventCount)
        assertEquals(1, profileWithFiveValidated.awaitingFeedbackSosCount)
    }

    /**
     * 9. CNN model weights remain unchanged:
     * Personal Learning performs local statistical prototype & threshold adaptation in Layer 2/3
     * while the Layer 1 1D CNN model weights and inference outputs remain 100% frozen and invariant.
     */
    @Test
    fun cnnModelWeights_remainFrozenAndUnchangedAcrossPersonalLearningUpdates() {
        val classifier = LiteRtMotionClassifier(context = null)
        val rawSamples = List(MotionWindowPreprocessor.WINDOW_SAMPLES) { idx ->
            MotionSample(
                timestampNanos = idx * 20_000_000L,
                ax = 0.2f,
                ay = 9.7f,
                az = 0.5f,
                gx = 0.01f,
                gy = 0.02f,
                gz = 0.01f
            )
        }
        val uniformWindow = MotionWindowPreprocessor.preprocess(rawSamples)

        val inferenceBefore = classifier.classify(uniformWindow)
        val featureVecBefore = PersonalLearningEngine.extractFeatureVector16D(
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )

        // Train a personalised profile with 6 confirmed examples
        val confirmedExamples = (1..6).map { idx ->
            PersonalLearningEngine.validateAndBuildFeedbackExample(
                userId = "user_senior_1",
                draft = PostFallAssessmentDraft(
                    linkedEventId = "evt_$idx",
                    incidentVerification = IncidentVerificationType.SITTING_DOWN,
                    feelingOkay = FeelingOkayStatus.YES,
                    painLevel = PainLevel.NO_PAIN
                ),
                snapshot = fallLikeSensorSnapshot,
                cnnResult = calibratedCnnFallResult
            )
        }
        val personalisedProfile = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = confirmedExamples
        )
        assertTrue(personalisedProfile.isPersonalisedActive)

        val inferenceAfter = classifier.classify(uniformWindow)
        val featureVecAfter = PersonalLearningEngine.extractFeatureVector16D(
            snapshot = fallLikeSensorSnapshot,
            cnnResult = calibratedCnnFallResult
        )

        assertEquals(inferenceBefore.readiness, inferenceAfter.readiness)
        assertEquals(inferenceBefore.modelVersion, inferenceAfter.modelVersion)
        assertEquals(inferenceBefore.predictedCategory, inferenceAfter.predictedCategory)
        assertEquals(inferenceBefore.calibratedConfidence, inferenceAfter.calibratedConfidence)
        assertEquals("16-D feature extraction from frozen CNN output must be identical", featureVecBefore, featureVecAfter)
    }

    /**
     * 10. Severe-event safety overrides remain intact:
     * Even when Personal Learning is in the PERSONALISED stage with 6 confirmed non-fall examples
     * whose prototype centroid matches the current window with 100% cosine similarity, a severe physical
     * impact (>= 37.3 m/s²) or deep unloading + impact + stillness triggers `hardSafetyOverrideActive = true`
     * and is NEVER suppressed (`suppressedRecurringFalseAlarm = false`).
     */
    @Test
    fun severeEventSafetyOverrides_remainIntactAndNeverSuppressHighSeverityFalls() {
        val moderateSittingSnapshot = fallLikeSensorSnapshot.copy(
            peakTotalAccelMs2 = 25.0f,
            minTotalAccelG = 0.65f,
            postImpactStillnessStdDevMs2 = 1.2f
        )

        val confirmedNonFallExamples = (1..6).map { idx ->
            PersonalLearningEngine.validateAndBuildFeedbackExample(
                userId = "user_senior_1",
                draft = PostFallAssessmentDraft(
                    linkedEventId = "sit_evt_$idx",
                    incidentVerification = IncidentVerificationType.SITTING_DOWN,
                    feelingOkay = FeelingOkayStatus.YES,
                    painLevel = PainLevel.NO_PAIN
                ),
                snapshot = moderateSittingSnapshot,
                cnnResult = calibratedCnnFallResult.copy(calibratedConfidence = 0.68f)
            )
        }

        val personalisedProfile = PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_senior_1",
            examples = confirmedNonFallExamples
        )
        assertTrue(personalisedProfile.isPersonalisedActive)

        // Case A: Severe impact peak (41.0 m/s² >= 37.3 m/s²)
        val severeImpactDecision = PersonalLearningEngine.evaluateLayer3Decision(
            profile = personalisedProfile,
            snapshot = moderateSittingSnapshot.copy(peakTotalAccelMs2 = 41.0f),
            cnnResult = calibratedCnnFallResult.copy(calibratedConfidence = 0.68f),
            candidatePeakMs2 = 41.0f
        )
        assertTrue("Hard safety override must activate on severe impact", severeImpactDecision.hardSafetyOverrideActive)
        assertFalse("Severe impact must NEVER be suppressed by personal calibration", severeImpactDecision.suppressedRecurringFalseAlarm)

        // Case B: Deep free-fall unloading (0.25g <= 0.35g) + moderate impact (30.0 m/s² >= 28.0 m/s²) + post-impact stillness (0.30 <= 0.90)
        val deepUnloadingDecision = PersonalLearningEngine.evaluateLayer3Decision(
            profile = personalisedProfile,
            snapshot = moderateSittingSnapshot.copy(
                minTotalAccelG = 0.25f,
                peakTotalAccelMs2 = 30.0f,
                postImpactStillnessStdDevMs2 = 0.30f
            ),
            cnnResult = calibratedCnnFallResult.copy(calibratedConfidence = 0.68f),
            candidatePeakMs2 = 30.0f,
            candidateMinMs2 = 0.25f * 9.80665f,
            candidateStillnessStdMs2 = 0.30f
        )
        assertTrue("Hard safety override must activate on deep free-fall + impact + stillness", deepUnloadingDecision.hardSafetyOverrideActive)
        assertFalse("Deep unloading fall must NEVER be suppressed", deepUnloadingDecision.suppressedRecurringFalseAlarm)
    }
}

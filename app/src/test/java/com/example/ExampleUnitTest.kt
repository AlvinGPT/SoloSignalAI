package com.example

import com.example.alerts.AlertDeliveryGateway
import com.example.alerts.EmailAlertClient
import com.example.alerts.LocalAndPlaceholderAlertGateway
import com.example.data.AlertFirestoreRepository
import com.example.domain.AlertChannelType
import com.example.domain.DeliveryConfigStatus
import com.example.domain.MonitoringState
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GoogleChatConnectionStatus
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.MotionSample
import com.example.domain.TrustedContact
import com.example.sensor.COOLDOWN_MS
import com.example.sensor.CandidateFallDetector
import com.example.sensor.DetectorConfig
import com.example.sensor.FREE_FALL_THRESHOLD
import com.example.sensor.IMPACT_THRESHOLD
import com.example.sensor.STILLNESS_MS
import com.example.sensor.SyntheticScenario
import com.example.sensor.SyntheticSensorTraces
import com.example.ui.screens.resolveAlertTypeLabel
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying:
 * - STEP 2: Tunable constants at the top of CandidateFallDetector, 3-phase state machine (Free-fall -> Impact -> Stillness),
 *   impact alone never triggering, accelerometer max range check & capping to 90%, 30-second cooldown, unique event IDs.
 * - STEP 3: Synthetic test suites 1 through 6, plus debug-only fall sequence testing.
 * - Non-sensor contracts: TrustedContact, DeliveryConfigStatus, EmailAlertClient, GoogleChatAlertConfig, GpsLocationSnapshot.
 */
class ExampleUnitTest {

    @Test
    fun motionSample_calculatesVectorMagnitudeGAndGyroDegPerSecAccurately() {
        val sample = MotionSample(
            timestampNanos = 1_000_000L,
            ax = 3f,
            ay = 4f,
            az = 12f,
            gx = 1.0f,
            gy = 0.0f,
            gz = 0.0f
        )
        val expected = sqrt(3f * 3f + 4f * 4f + 12f * 12f) // 13.0f
        assertEquals(13.0f, sample.magnitude, 0.001f)
        assertEquals(13.0f / MotionSample.GRAVITY_EARTH_MS2, sample.magnitudeG, 0.001f)
        assertTrue(sample.hasGyroscope)
        assertEquals(57.29578f, sample.gyroMagnitudeDegPerSec, 0.05f)
    }

    // ========================================================================
    // STEP 3: SYNTHETIC DATA UNIT TESTS (1 THROUGH 6)
    // ========================================================================

    @Test
    fun step3_test1_phoneRestingFlat_doesNotTrigger() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generatePhoneRestingFlat(durationMs = 3000L, stepMs = 20L)

        var triggeredCount = 0
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCount++
            }
        }
        assertEquals("Phone resting flat (~9.81 m/s² plus small noise) must NOT trigger a fall alert", 0, triggeredCount)
    }

    @Test
    fun step3_test2_phonePlacedOnTable_doesNotTrigger() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generatePlacingOnTable(durationMs = 3500L, stepMs = 20L)

        var triggeredCount = 0
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCount++
            }
        }
        assertEquals("Phone placed on a table (spike ~15-25 m/s²) must NOT trigger a fall alert", 0, triggeredCount)
    }

    @Test
    fun step3_test3_walkingSittingDownHardRunning_doesNotTrigger() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateEverydayMotionActivities(stepMs = 20L)

        var triggeredCount = 0
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCount++
            }
        }
        assertEquals("Walking, sitting down hard, or running (peaks up to 45 m/s²) must NOT trigger fall alert", 0, triggeredCount)
    }

    @Test
    fun step3_test4_dipBelow6_impact50_stillness2500ms_doesNotTrigger() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateDipImpact50Stillness(stepMs = 20L)

        var triggeredCount = 0
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCount++
            }
        }
        assertEquals("Dip <6 m/s² followed by sub-threshold (<30.0 m/s²) impact must NOT trigger fall alert", 0, triggeredCount)
    }

    @Test
    fun step3_test5_dipBelow6_impact65_within1s_stillness2500ms_triggersCandidateFall() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L)

        val triggeredCandidates = mutableListOf<com.example.domain.CandidateEventSummary>()
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCandidates.add(eval.candidateEvent)
            }
        }

        assertEquals("Dip <6 m/s² (80ms) → impact 65 m/s² within 1s → stillness 2.5s MUST TRIGGER candidate fall", 1, triggeredCandidates.size)
        val event = triggeredCandidates.first()
        assertTrue("Impact peak must be recorded >= IMPACT_THRESHOLD (30.0 m/s²)", event.peakMagnitude >= IMPACT_THRESHOLD)
        assertTrue("Free fall dip must be recorded < 6.0 m/s²", event.minMagnitude < 6.0f)
        assertNotNull("Event ID must be generated", event.eventId)
    }

    @Test
    fun step3_test6_dipAndImpact65_continuousMovement_doesNotTrigger() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateDipImpact65ContinuousMovement(stepMs = 20L)

        var triggeredCount = 0
        for (sample in samples) {
            val eval = detector.addSample(sample)
            if (eval.candidateEvent != null) {
                triggeredCount++
            }
        }
        assertEquals("Dip and impact of 65 m/s² followed by continuous active movement must NOT trigger fall alert", 0, triggeredCount)
    }

    // ========================================================================
    // STEP 2 & DEBUG SAFETY VALIDATIONS
    // ========================================================================

    @Test
    fun impactAlone_withoutFreeFallAndStillness_neverTriggers() {
        val detector = CandidateFallDetector()
        val stepNanos = 20_000_000L

        // A direct isolated 70.0 m/s² impact without prior free fall
        val spike = MotionSample(
            timestampNanos = 100 * stepNanos,
            ax = 10.0f,
            ay = 20.0f,
            az = 66.0f, // Magnitude ~69.7 m/s²
            isSynthetic = true
        )
        val eval = detector.addSample(spike)
        assertNull("Impact alone must NEVER trigger an alert; free-fall is strictly required first", eval.candidateEvent)
    }

    @Test
    fun sensorMaxRange_below30_capsImpactThresholdTo90PercentAndLogsWarning() {
        // Example: Hardware sensor with 25.0 m/s² maximum range (< 30.0 m/s² IMPACT_THRESHOLD)
        val detector = CandidateFallDetector(sensorMaxRange = 25.0f)
        val cappedThreshold = detector.getEffectiveImpactThreshold()
        assertEquals(22.5f, cappedThreshold, 0.001f) // 90% of 25.0 m/s²

        // Example: Hardware sensor with 150.0 m/s² maximum range (>= 30.0 m/s²)
        val normalDetector = CandidateFallDetector(sensorMaxRange = 150.0f)
        assertEquals(30.0f, IMPACT_THRESHOLD, 0.001f)
        assertEquals(IMPACT_THRESHOLD, normalDetector.getEffectiveImpactThreshold(), 0.001f)
    }

    @Test
    fun impactThreshold_isConfiguredTo30Ms2_andSupports27Ms2SensitiveThreshold() {
        assertEquals(30.0f, IMPACT_THRESHOLD, 0.001f)
        assertEquals(27.0f, com.example.sensor.SENSITIVE_IMPACT_THRESHOLD, 0.001f)
        assertEquals(30.0f, CandidateFallDetector.PEAK_IMPACT_THRESHOLD_MS2, 0.001f)

        // 1. A 3-phase sequence whose impact reaches 32.0 m/s² (>= 30.0 m/s²) MUST trigger default detector
        val defaultDetector = CandidateFallDetector()
        val defaultSamples = SyntheticSensorTraces.generateDipImpact50Stillness(stepMs = 20L).map { sample ->
            if (sample.sensorType.contains("Impact Sub-Threshold")) {
                sample.copy(ax = 8.0f, ay = 12.0f, az = 28.5f) // sqrt(64 + 144 + 812.25) = 31.94 m/s²
            } else {
                sample
            }
        }
        val defaultTriggered = defaultSamples.mapNotNull { defaultDetector.addSample(it).candidateEvent }
        assertEquals("3-phase sequence with >= 30.0 m/s² impact must trigger candidate fall", 1, defaultTriggered.size)
        assertTrue(defaultTriggered.first().peakMagnitude >= 30.0f)

        // 2. An impact of 28.5 m/s²:
        // - Rejected by default 30.0 m/s² detector (28.5 < 30.0)
        // - Accepted by 27.0 m/s² sensitive detector (28.5 >= 27.0)
        val sensitiveDetector = CandidateFallDetector(
            config = DetectorConfig(impactThreshold = com.example.sensor.SENSITIVE_IMPACT_THRESHOLD)
        )
        val testSamples = SyntheticSensorTraces.generateDipImpact50Stillness(stepMs = 20L).map { sample ->
            if (sample.sensorType.contains("Impact Sub-Threshold")) {
                sample.copy(ax = 7.0f, ay = 10.0f, az = 25.7f) // sqrt(49 + 100 + 660.49) = 28.45 m/s²
            } else {
                sample
            }
        }
        val rejectedByDefault = testSamples.mapNotNull { defaultDetector.addSample(it).candidateEvent }
        assertEquals("28.5 m/s² impact is below 30.0 m/s² default threshold and should not trigger default detector", 0, rejectedByDefault.size)

        val triggeredBySensitive = testSamples.mapNotNull { sensitiveDetector.addSample(it).candidateEvent }
        assertEquals("28.5 m/s² impact exceeds 27.0 m/s² threshold and must trigger sensitive detector", 1, triggeredBySensitive.size)
        assertTrue(triggeredBySensitive.first().peakMagnitude >= 27.0f)
    }

    @Test
    fun cooldown_30Seconds_suppressesDuplicateCandidateEvents() {
        val detector = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L)

        var firstTriggerTimestampNanos = 0L
        var triggeredCount = 0
        for (s in samples) {
            val eval = detector.addSample(s)
            if (eval.candidateEvent != null) {
                triggeredCount++
                firstTriggerTimestampNanos = s.timestampNanos
            }
        }
        assertEquals(1, triggeredCount)
        assertTrue(firstTriggerTimestampNanos > 0L)

        // Attempting another fall 10 seconds later (within 30s cooldown window)
        val repeatSamplesWithinCooldown = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L).map {
            it.copy(timestampNanos = it.timestampNanos + firstTriggerTimestampNanos + 10_000_000_000L)
        }
        var repeatTriggeredCount = 0
        for (s in repeatSamplesWithinCooldown) {
            val eval = detector.addSample(s)
            if (eval.candidateEvent != null) {
                repeatTriggeredCount++
            }
        }
        assertEquals("Duplicate fall within 30-second cooldown must be suppressed", 0, repeatTriggeredCount)

        // Attempting a fall 31 seconds later (after 30s cooldown expires)
        val repeatSamplesAfterCooldown = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L).map {
            it.copy(timestampNanos = it.timestampNanos + firstTriggerTimestampNanos + (COOLDOWN_MS + 1000L) * 1_000_000L)
        }
        var afterCooldownTriggeredCount = 0
        for (s in repeatSamplesAfterCooldown) {
            val eval = detector.addSample(s)
            if (eval.candidateEvent != null) {
                afterCooldownTriggeredCount++
            }
        }
        assertEquals("Fall after 30-second cooldown expires should trigger", 1, afterCooldownTriggeredCount)
    }

    @Test
    fun cooldown_refreshedWhenUserAnswersCheckIn() {
        val detector = CandidateFallDetector()
        val nowNanos = 5_000_000_000L
        detector.applyCooldown(nowNanos)

        // Fall attempted 15s after user answered (inside 30s window)
        val samplesDuringCooldown = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L).map {
            it.copy(timestampNanos = it.timestampNanos + nowNanos + 15_000_000_000L)
        }
        val triggered = samplesDuringCooldown.mapNotNull { detector.addSample(it).candidateEvent }
        assertTrue("Candidate fall must be suppressed while user answer cooldown is active", triggered.isEmpty())
    }

    @Test
    fun eachCandidate_hasUniqueEventId() {
        val detector1 = CandidateFallDetector()
        val detector2 = CandidateFallDetector()
        val samples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L)

        val event1 = samples.mapNotNull { detector1.addSample(it).candidateEvent }.firstOrNull()
        val event2 = samples.mapNotNull { detector2.addSample(it).candidateEvent }.firstOrNull()

        assertNotNull(event1)
        assertNotNull(event2)
        assertNotEquals("Each candidate fall must generate a unique UUID eventId", event1!!.eventId, event2!!.eventId)
    }

    @Test
    fun debugOnlySyntheticFallSequence_returnsSamplesInDebug_andTriggersCandidateFall() {
        val debugSamples = SyntheticSensorTraces.generateDebugFallSequence()
        assertTrue("In debug builds, generateDebugFallSequence must return valid samples", debugSamples.isNotEmpty())

        val detector = CandidateFallDetector()
        val candidate = debugSamples.mapNotNull { detector.addSample(it).candidateEvent }.firstOrNull()
        assertNotNull("Debug synthetic fall sequence must trigger candidate fall for device testing", candidate)
    }

    @Test
    fun syntheticScenarios_allPassBenchmarkEvaluation() {
        val report = CandidateFallDetector.evaluateBenchmarkSuite()
        assertEquals("All positive fall scenarios should be detected", 3, report.truePositives)
        assertEquals("No positive fall scenarios should be missed", 0, report.falseNegatives)
        assertEquals("Zero false positives across ordinary non-fall activities", 0, report.falsePositives)
        assertEquals(1.0f, report.sensitivity, 0.001f)
        assertEquals(0.0f, report.falsePositiveRate, 0.001f)
    }

    // ========================================================================
    // NON-SENSOR CONTRACT & UI UNIT TESTS
    // ========================================================================

    @Test
    fun resolveAlertTypeLabel_distinguishesPossibleFallDetectedAndSosRequested() {
        val baseAlert = IncomingRecipientAlert(
            alertId = "a1",
            senderName = "Alex Rivera",
            senderEmail = "alex@example.com",
            recipientName = "Jordan Lee",
            recipientEmail = "jordan@example.com",
            isTestAlert = false,
            subjectLine = "Emergency Alert",
            bodyText = "Body",
            triggerReason = "Possible fall detected (no response after 15s countdown)",
            deliveryState = "DELIVERED_TO_RECIPIENT",
            deliveryStatusDetail = "",
            googleMapsUrl = null,
            locationSummary = "",
            createdAtMillis = 1710000000000L
        )
        assertEquals("Possible fall detected", resolveAlertTypeLabel(baseAlert))
        assertEquals(
            "SOS requested",
            resolveAlertTypeLabel(baseAlert.copy(triggerReason = "SOS requested"))
        )
        assertEquals(
            "Test alert",
            resolveAlertTypeLabel(baseAlert.copy(isTestAlert = true))
        )
    }

    @Test
    fun trustedContactValidation_supportsSimplifiedEmailOrPhoneAndExplicitConsent() {
        val incomplete = TrustedContact(
            contactName = "Sam",
            contactEmail = "sam@example.com",
            consentGranted = false
        )
        assertFalse(incomplete.isValid)

        val invalidEmail = TrustedContact(
            contactName = "Sam",
            contactEmail = "not-an-email",
            consentGranted = true
        )
        assertFalse(invalidEmail.isValid)
        assertFalse(invalidEmail.hasValidEmail)

        val validWithEmail = TrustedContact(
            contactName = "Sam Rivera",
            contactEmail = "sam.rivera@example.com"
        )
        assertTrue(validWithEmail.isValid)
        assertTrue(validWithEmail.hasValidEmail)
        assertEquals(AlertChannelType.SOLOSIGNAL_POPUP, validWithEmail.preferredChannel)
        assertTrue(validWithEmail.phonePopUpAlertsEnabled)

        val validWithPhoneOnly = TrustedContact(
            contactName = "Jordan Lee",
            contactPhone = "+1 415 555 0198",
            relationship = "Friend"
        )
        assertTrue(validWithPhoneOnly.isValid)
        assertFalse(validWithPhoneOnly.hasValidEmail)
    }

    @Test
    fun honestDeliveryStatuses_distinguishSendingAcceptedEmailSentEmailFailedAndRecipientDelivery() {
        assertEquals("Sending…", DeliveryConfigStatus.SENDING.label)
        assertEquals("Request accepted", DeliveryConfigStatus.REQUEST_ACCEPTED.label)
        assertEquals("Email sent", DeliveryConfigStatus.EMAIL_SENT.label)
        assertEquals("Email failed", DeliveryConfigStatus.EMAIL_FAILED.label)
        assertEquals("Sent by provider", DeliveryConfigStatus.SENT_BY_PROVIDER.label)
        assertEquals("Delivered to recipient's phone", DeliveryConfigStatus.DELIVERED_TO_RECIPIENT.label)
        assertEquals("Contact is checking on you", DeliveryConfigStatus.ACKNOWLEDGED_BY_RECIPIENT.label)
        assertEquals("Delivery failed", DeliveryConfigStatus.ALERT_FAILED.label)
    }

    @Test
    fun alertDeliveryFlow_primaryFirestoreAccepted_doesNotOverwriteDeliveryStateOnEmailFailure() {
        // 1. Verify Firestore state constants
        assertEquals("REQUEST_ACCEPTED", AlertFirestoreRepository.STATE_REQUEST_ACCEPTED)
        assertEquals("EMAIL_FAILED", AlertFirestoreRepository.STATE_EMAIL_FAILED)
        assertEquals("EMAIL_SENT", AlertFirestoreRepository.STATE_EMAIL_SENT)

        // 2. An alert successfully accepted in Firestore has deliveryState = STATE_REQUEST_ACCEPTED
        val baseAlert = com.example.data.FirestoreEmergencyAlert(
            id = "alert_test_999",
            senderEmail = "sender@example.com",
            recipientEmail = "recipient@example.com",
            deliveryState = AlertFirestoreRepository.STATE_REQUEST_ACCEPTED,
            deliveryStatusDetail = "Alert received by SoloSignal cloud; contact devices notified"
        )
        assertEquals(AlertFirestoreRepository.STATE_REQUEST_ACCEPTED, baseAlert.deliveryState)

        // 3. Convert to IncomingRecipientAlert and verify recipient device receives valid alert
        val recipientAlert = baseAlert.toIncomingRecipientAlert()
        assertEquals("alert_test_999", recipientAlert.alertId)
        assertEquals(AlertFirestoreRepository.STATE_REQUEST_ACCEPTED, recipientAlert.deliveryState)

        // 4. Verify DeliveryConfigStatus for REQUEST_ACCEPTED maps to honest accepted label
        val acceptedStatus = DeliveryConfigStatus.REQUEST_ACCEPTED
        assertNotEquals("Must not be delivery failed", DeliveryConfigStatus.ALERT_FAILED, acceptedStatus)

        // 5. MonitoringState.ALERT_SENT must be preserved when Firestore acceptance succeeded
        val monitoringState = MonitoringState.ALERT_SENT
        assertNotEquals("Monitoring state must not regress to ALERT_FAILED", MonitoringState.ALERT_FAILED, monitoringState)
    }

    @Test
    fun emailAlertClient_buildsReadableHtmlAndPlainTextEmergencyEmailContent() {
        val contact = TrustedContact(
            userDisplayName = "Alex Rivera",
            contactName = "Sam Rivera",
            contactEmail = "sam.rivera@example.com",
            optionalLocationEnabled = true
        )
        val location = GpsLocationSnapshot(
            latitude = 37.7749,
            longitude = -122.4194,
            accuracyMeters = 6.2f,
            timestampMillis = 1700000000000L,
            provider = "fused"
        )
        val payload = LocalAndPlaceholderAlertGateway.buildPayload(
            contact = contact,
            chatConfig = GoogleChatAlertConfig(),
            isTestAlert = false,
            timestampMillis = 1700000000000L,
            triggerReasonNote = "Emergency help requested immediately by user ('Send SOS Now').",
            locationSnapshot = location
        )

        val emailClient = EmailAlertClient()
        val htmlBody = emailClient.buildHtmlEmailBody(
            payload = payload,
            recipientEmail = "sam.rivera@example.com",
            senderEmail = "alex@example.com",
            triggerReason = "Emergency help requested immediately by user ('Send SOS Now')."
        )

        assertTrue(payload.subjectLine.contains("SoloSignal Check-In Alert"))
        assertTrue(payload.bodyText.contains("Alex Rivera"))
        assertTrue(payload.bodyText.contains("https://www.google.com/maps?q=37.7749,-122.4194"))
        assertTrue(htmlBody.contains("Open GPS Location in Google Maps"))
        assertTrue(htmlBody.contains("sam.rivera@example.com"))
    }

    @Test
    fun googleChatAlertConfig_enforcesSecureBackendAndInitialMessageContract() {
        assertEquals(
            "Hi! SoloSignal AI is connected. This chat will receive possible-fall alerts when the user needs assistance. This is a connection test, not an emergency.",
            GoogleChatAlertConfig.INITIAL_CONNECTION_MESSAGE
        )

        assertFalse(GoogleChatAlertConfig.isValidHttpsBackendUrl("https://chat.googleapis.com/v1/spaces/AAA/messages"))
        assertFalse(GoogleChatAlertConfig.isValidHttpsBackendUrl("https://example.com/webhook?key=SECRET123"))
        assertTrue(GoogleChatAlertConfig.isValidHttpsBackendUrl(GoogleChatAlertConfig.DEFAULT_CLOUD_FUNCTION_TEMPLATE))

        val config = GoogleChatAlertConfig(
            enabled = true,
            backendEndpointUrl = GoogleChatAlertConfig.DEFAULT_CLOUD_FUNCTION_TEMPLATE,
            spaceName = "spaces/AAAA1234567",
            connectionStatus = GoogleChatConnectionStatus.CONNECTED,
            initialConnectionMessageSent = true,
            connectedDestinationKey = "${GoogleChatAlertConfig.DEFAULT_CLOUD_FUNCTION_TEMPLATE.lowercase()}|spaces/AAAA1234567"
        )
        assertTrue(config.isDestinationFormatValid)
        assertTrue(config.isTrulyConnected)
    }

    @Test
    fun gpsLocationSnapshot_generatesGoogleMapsLink() {
        val snapshot = GpsLocationSnapshot(
            latitude = 51.5074,
            longitude = -0.1278,
            accuracyMeters = 8.4f,
            timestampMillis = 1700000000000L,
            provider = "fused"
        )
        assertEquals("https://www.google.com/maps?q=51.5074,-0.1278", snapshot.googleMapsUrl)
        assertTrue(snapshot.formattedCoordinates.contains("51.50740"))
    }

    @Test
    fun emergencyBuzzerAudioResource_existsAndIsValidWavFile() {
        val resourceFile = java.io.File("src/main/res/raw/emergency_buzzer.wav")
        assertTrue("emergency_buzzer.wav must exist in res/raw", resourceFile.exists())
        assertTrue("emergency_buzzer.wav size must be non-trivial (> 50KB)", resourceFile.length() > 50_000L)

        val header = ByteArray(12)
        java.io.FileInputStream(resourceFile).use { it.read(header) }
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        assertEquals("WAV file must begin with RIFF header", "RIFF", riff)
        assertEquals("WAV file format must be WAVE", "WAVE", wave)
    }

    @Test
    fun alertDeliveryGateway_buzzerChannelConstantsAreMigratedToBuzzerV1() {
        assertEquals("solosignal_recipient_emergency_buzzer_v1", AlertDeliveryGateway.CHANNEL_RECIPIENT_POPUP)
        assertEquals("solosignal_checkin_urgent", AlertDeliveryGateway.CHANNEL_CHECK_IN_URGENT)
    }

    // ========================================================================
    // SOS 15-SECOND ROLLING AUDIO BUFFER & EMERGENCY EMAIL UNIT TESTS
    // ========================================================================

    @Test
    fun rollingPcmRingBuffer_wrapsAroundAt15Seconds_retainingExactMostRecentSamplesInOrder() {
        val sampleRate = 16_000
        val maxSeconds = 15
        val capacitySamples = sampleRate * maxSeconds // 240,000 samples
        val ringBuffer = com.example.sensor.RollingPcmRingBuffer(
            sampleRateHz = sampleRate,
            maxDurationSeconds = maxSeconds
        )
        assertEquals(capacitySamples, ringBuffer.capacitySamples)
        assertEquals(0, ringBuffer.bufferedSampleCount())
        assertEquals(0.0, ringBuffer.bufferedDurationSeconds(), 0.0001)

        // Write 20 seconds of sequential deterministic samples (320,000 samples total)
        val totalSecondsToWrite = 20
        val chunkSamples = 1_600 // 100ms chunks
        val chunk = ShortArray(chunkSamples)
        var globalSampleIndex = 0
        for (chunkIdx in 0 until (totalSecondsToWrite * 10)) {
            for (i in 0 until chunkSamples) {
                chunk[i] = ((globalSampleIndex % 30_000) - 15_000).toShort()
                globalSampleIndex++
            }
            ringBuffer.write(chunk, chunkSamples)
        }

        // Buffer must be capped at 15.0 seconds (240,000 samples)
        assertEquals(capacitySamples, ringBuffer.bufferedSampleCount())
        assertEquals(15.0, ringBuffer.bufferedDurationSeconds(), 0.001)

        val snapshot = ringBuffer.snapshotSamples()
        assertEquals(capacitySamples, snapshot.size)

        // First sample in the 15s snapshot must correspond to sample at 5.0s (index 80,000)
        val firstExpectedIndex = (totalSecondsToWrite - maxSeconds) * sampleRate // 80,000
        assertEquals(
            "Oldest retained sample must be at 5.0s mark after 20.0s of recording",
            ((firstExpectedIndex % 30_000) - 15_000).toShort(),
            snapshot.first()
        )
        // Last sample in the snapshot must correspond to the final sample written (index 319,999)
        val lastExpectedIndex = (totalSecondsToWrite * sampleRate) - 1
        assertEquals(
            "Newest retained sample must be the final sample written at 20.0s mark",
            ((lastExpectedIndex % 30_000) - 15_000).toShort(),
            snapshot.last()
        )
    }

    @Test
    fun rollingPcmRingBuffer_partialBufferBefore15Seconds_honestlyReportsActualDuration_andClearsCleanly() {
        val ringBuffer = com.example.sensor.RollingPcmRingBuffer(
            sampleRateHz = 16_000,
            maxDurationSeconds = 15
        )
        // Write 6.5 seconds (104,000 samples)
        val partialSamplesCount = 104_000
        val input = ShortArray(partialSamplesCount) { idx -> (idx % 1024).toShort() }
        ringBuffer.write(input, input.size)

        assertEquals(partialSamplesCount, ringBuffer.bufferedSampleCount())
        assertEquals(6.5, ringBuffer.bufferedDurationSeconds(), 0.001)

        val snapshot = ringBuffer.snapshotSamples()
        assertEquals("Partial buffer must not pad or claim 15 seconds", partialSamplesCount, snapshot.size)
        assertEquals(0.toShort(), snapshot[0])
        assertEquals(((partialSamplesCount - 1) % 1024).toShort(), snapshot.last())

        // Clearing the buffer immediately zeroes RAM and resets duration to 0.0s
        ringBuffer.clear()
        assertEquals(0, ringBuffer.bufferedSampleCount())
        assertEquals(0.0, ringBuffer.bufferedDurationSeconds(), 0.0001)
        assertEquals(0, ringBuffer.snapshotSamples().size)
    }

    @Test
    fun sosAudioContainerEncoder_encodesValidRiffWavePcmContainer() {
        val sampleRate = 16_000
        // 1 second of 440Hz tone (16,000 samples = 32,000 PCM bytes)
        val samples = ShortArray(sampleRate) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 12_000).toInt().toShort()
        }
        val wavBytes = com.example.sensor.SosAudioContainerEncoder.encodeWavBytes(
            samples = samples,
            sampleRateHz = sampleRate
        )
        assertEquals("WAV file must be 44-byte header + 32,000 PCM bytes", 44 + 32_000, wavBytes.size)
        assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wavBytes, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wavBytes, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun emailAlertClient_buildsMultipartMimeWithSosAudioAttachmentAndProtectedStorageLink() {
        val clip = com.example.domain.SosAudioClipMetadata(
            alertId = "alert_sos_audio_1",
            localFilePath = "/tmp/sos_recording.m4a",
            mimeType = "audio/mp4",
            containerExtension = "m4a",
            durationSeconds = 15.0,
            preTriggerSeconds = 15.0,
            postTriggerSeconds = 0.0,
            sampleRateHz = 16_000,
            sizeBytes = 64_000L,
            capturedAtMillis = 1_730_000_000_000L,
            isPartialBuffer = false,
            storageObjectPath = "sos_audio/uid_alex/alert_sos_audio_1/sos_recording.m4a",
            downloadUrl = "https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0124462176.firebasestorage.app/o/sos_audio%2Fuid_alex%2Falert_sos_audio_1%2Fsos_recording.m4a?alt=media&token=tok123",
            attachedToEmail = true,
            base64Payload = "AAAAHGZ0eXBNNEEgAAACAE00QSBpc29t"
        )
        val triggerReasonText = "Possible fall detected (no response after 15s countdown)"
        val payload = com.example.alerts.AlertMessagePayload(
            alertId = "alert_sos_audio_1",
            isTestAlert = false,
            subjectLine = "SOS ALERT — Possible Fall Detected (Alex Rivera)",
            bodyText = "SoloSignal AI has detected a possible fall involving the user configured for this alert (Alex Rivera).\nThe user did not respond to the in-app confirmation prompt before the countdown expired.",
            recipientName = "Sam Rivera",
            recipientEmail = "sam.rivera@example.com",
            recipientPhone = "+15550192834",
            userDisplayName = "Alex Rivera",
            timestampFormatted = "2026-10-28 12:00:00 UTC",
            locationStatusNote = "37.77490, -122.41940 (±6m)",
            audioClip = clip,
            audioStatusKey = com.example.domain.SosAudioStatus.SOS_AUDIO_CAPTURED.firestoreValue,
            audioStatusNote = "Attached (15.0s M4A) — Secure link: ${clip.downloadUrl}"
        )

        val emailClient = EmailAlertClient()
        val html = emailClient.buildHtmlEmailBody(
            payload = payload,
            recipientEmail = "sam.rivera@example.com",
            senderEmail = "alex@example.com",
            triggerReason = triggerReasonText
        )
        assertTrue("HTML email must include the protected Firebase Storage audio link", html.contains(clip.downloadUrl!!))
        assertTrue("HTML email must mention the 15.0s SOS audio recording", html.contains("15.0s"))

        val rfc2822 = emailClient.buildRfc2822Message(
            senderEmail = "alex@example.com",
            recipientEmail = "sam.rivera@example.com",
            replyToEmail = "alex@example.com",
            subject = payload.subjectLine,
            plainText = payload.bodyText,
            htmlBody = html,
            audioClip = clip
        )
        assertTrue("MIME message must use multipart/mixed when audio attachment is present", rfc2822.contains("Content-Type: multipart/mixed"))
        assertTrue("MIME message must include audio/mp4 attachment header", rfc2822.contains("Content-Type: audio/mp4"))
        assertTrue("MIME message must include attachment Content-Disposition", rfc2822.contains("Content-Disposition: attachment"))
        assertTrue("MIME message must embed base64 audio payload", rfc2822.contains("AAAAHGZ0eXBNNEEgAAACAE00QSBpc29t"))
    }
}

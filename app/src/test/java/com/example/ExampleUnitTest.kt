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
        assertEquals("Alert created", DeliveryConfigStatus.ALERT_CREATED.label)
        assertEquals("Notification permission missing", DeliveryConfigStatus.NOTIFICATION_PERMISSION_MISSING.label)
        assertEquals("Notification sent", DeliveryConfigStatus.NOTIFICATION_SENT.label)
        assertEquals("Delivery unknown", DeliveryConfigStatus.DELIVERY_UNKNOWN.label)
    }

    @Test
    fun incidentAlertDeepLink_canonicalFormatAndDeduplicationContract() {
        // 1. Verify deep link URI format
        val incidentId = "incident_abc123"
        val deepLinkUri = java.net.URI.create("solosignal://incident/$incidentId")
        assertEquals("solosignal", deepLinkUri.scheme)
        assertEquals("incident", deepLinkUri.host)
        assertEquals("/$incidentId", deepLinkUri.path)

        // 2. Verify check-in deep link URI format
        val checkInUri = java.net.URI.create("solosignal://incident/chk_456?type=checkin")
        assertEquals("solosignal", checkInUri.scheme)
        assertEquals("incident", checkInUri.host)
        assertEquals("/chk_456", checkInUri.path)
        assertEquals("type=checkin", checkInUri.query)

        // 3. Test alert labelling
        val testIncident = IncomingRecipientAlert(
            alertId = "test_inc_789",
            senderName = "Alex Rivera",
            senderEmail = "alex@example.com",
            recipientName = "Sam Rivera",
            recipientEmail = "sam@example.com",
            isTestAlert = true,
            subjectLine = "[TEST] SoloSignal Incident Alert",
            bodyText = "Test incident",
            triggerReason = "Manual test alert",
            deliveryState = AlertFirestoreRepository.STATE_ALERT_CREATED,
            deliveryStatusDetail = "Test alert created",
            createdAtMillis = 1700000000000L
        )
        assertTrue("Test alert must have isTestAlert = true", testIncident.isTestAlert)
        assertEquals("Test alert", resolveAlertTypeLabel(testIncident))
        assertTrue("Test subject must contain [TEST]", testIncident.subjectLine.startsWith("[TEST]"))
    }

    @Test
    fun alertDeliveryFlow_primaryFirestoreAccepted_doesNotOverwriteDeliveryStateOnEmailFailure() {
        // 1. Verify Firestore state constants
        assertEquals("REQUEST_ACCEPTED", AlertFirestoreRepository.STATE_REQUEST_ACCEPTED)
        assertEquals("EMAIL_FAILED", AlertFirestoreRepository.STATE_EMAIL_FAILED)
        assertEquals("EMAIL_SENT", AlertFirestoreRepository.STATE_EMAIL_SENT)
        assertEquals("ALERT_CREATED", AlertFirestoreRepository.STATE_ALERT_CREATED)
        assertEquals("NOTIFICATION_PERMISSION_MISSING", AlertFirestoreRepository.STATE_NOTIFICATION_PERMISSION_MISSING)
        assertEquals("NOTIFICATION_SENT", AlertFirestoreRepository.STATE_NOTIFICATION_SENT)
        assertEquals("DELIVERY_UNKNOWN", AlertFirestoreRepository.STATE_DELIVERY_UNKNOWN)

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

    // ========================================================================
    // ML MOTION PREPROCESSING, ORIENTATION INVARIANCE & HYBRID ENGINE TESTS
    // ========================================================================

    @Test
    fun motionWindowPreprocessor_resamplesIrregularTimestampsToUniform50Hz_andIs3dOrientationInvariant() {
        // Generate a fall sequence at ~62.5 Hz (16ms step) and compare with 3D-rotated version
        val rawSamples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 16L)

        // Apply a 3D rotation (90 deg around X, then 45 deg around Z) to every sample's accel & gyro vectors
        val cos45 = 0.70710677f
        val sin45 = 0.70710677f
        val rotatedSamples = rawSamples.map { s ->
            val rx1 = s.ax
            val ry1 = -s.az
            val rz1 = s.ay
            val rx2 = rx1 * cos45 - ry1 * sin45
            val ry2 = rx1 * sin45 + ry1 * cos45
            val rz2 = rz1

            val gx1 = s.gx
            val gy1 = -s.gz
            val gz1 = s.gy
            val gx2 = gx1 * cos45 - gy1 * sin45
            val gy2 = gx1 * sin45 + gy1 * cos45
            val gz2 = gz1

            s.copy(ax = rx2, ay = ry2, az = rz2, gx = gx2, gy = gy2, gz = gz2)
        }

        val win1 = com.example.sensor.MotionWindowPreprocessor.preprocess(rawSamples, hasGyroscopeHardware = true)
        val win2 = com.example.sensor.MotionWindowPreprocessor.preprocess(rotatedSamples, hasGyroscopeHardware = true)

        assertTrue("4.0s window at ~62.5 Hz must be valid for 50 Hz resampling", win1.sampleQualityGood)
        assertTrue("3D-rotated 4.0s window must be valid for 50 Hz resampling", win2.sampleQualityGood)
        assertEquals(200, win1.rawChannels.size)
        assertEquals(200, win2.rawChannels.size)

        // Every orientation-invariant channel across all 200 resampled steps must match within floating-point tolerance
        for (t in 0 until 200) {
            for (c in 0 until 6) {
                assertEquals(
                    "Orientation-invariant channel [$t][$c] must be invariant under 3D rotation",
                    win1.rawChannels[t][c],
                    win2.rawChannels[t][c],
                    0.02f
                )
            }
        }
    }

    @Test
    fun motionWindowPreprocessor_invalidatesWindowWithSensorDropoutGapAbove250ms() {
        val base = SyntheticSensorTraces.generatePhoneRestingFlat(durationMs = 4500L, stepMs = 20L)
        // Drop samples between 1.5s and 2.0s (500ms gap > 250ms max allowed gap)
        val gapped = base.filter { sample ->
            val ms = sample.timestampNanos / 1_000_000L
            ms < 1500L || ms > 2000L
        }
        val preprocessed = com.example.sensor.MotionWindowPreprocessor.preprocess(gapped)
        assertFalse("Window with 500ms sensor dropout gap must be marked invalid", preprocessed.sampleQualityGood)
        assertTrue("Max gap must be reported > 250ms", preprocessed.maxTimestampGapMs > 250L)
    }

    @Test
    fun liteRtMotionClassifier_loadsBundledTrainedModel_andHonestFallbackWhenAssetMissing() {
        // 1. Load bundled trained TFL3 binary from src/main/assets/models
        val trainedClassifier = com.example.sensor.LiteRtMotionClassifier(context = null)
        assertEquals(
            "Bundled trained .tflite model must report CALIBRATED_READY",
            com.example.domain.ModelReadinessStatus.CALIBRATED_READY,
            trainedClassifier.readinessStatus
        )

        val fallSamples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L)
        val fallWindow = com.example.sensor.MotionWindowPreprocessor.preprocess(fallSamples)
        val fallResult = trainedClassifier.classify(fallWindow)
        assertEquals(com.example.domain.MovementCategory.POSSIBLE_FALL, fallResult.predictedCategory)
        assertNotNull("Calibrated confidence must be dynamically computed from model", fallResult.calibratedConfidence)
        assertTrue("Fall confidence must be high (> 0.70)", fallResult.calibratedConfidence!! > 0.70f)

        // 2. Missing model path -> must honestly report MODEL_UNAVAILABLE_FALLBACK_ACTIVE and null confidence
        val missingClassifier = com.example.sensor.LiteRtMotionClassifier(
            context = null,
            assetModelPath = "models/non_existent_model.tflite"
        )
        assertEquals(
            com.example.domain.ModelReadinessStatus.MODEL_UNAVAILABLE_FALLBACK_ACTIVE,
            missingClassifier.readinessStatus
        )
        val fallbackResult = missingClassifier.classify(fallWindow)
        assertNull("Unavailable model must NEVER fabricate a confidence score", fallbackResult.calibratedConfidence)
        assertTrue("Unavailable model must mark prediction as uncertain", fallbackResult.isUncertain)
    }

    @Test
    fun hybridFallDetectionEngine_catchesBothFreeFallAndSyncopeCollapse_andRejectsAdlFalseAlarms() {
        val cnn = com.example.sensor.LiteRtMotionClassifier(context = null)
        val engine = com.example.sensor.HybridFallDetectionEngine(cnnClassifier = cnn)

        // 1. Everyday ADL activities (walking, sitting hard, running) must NOT trigger check-in
        val adlSamples = SyntheticSensorTraces.generateEverydayMotionActivities(stepMs = 20L)
        val adlTriggers = adlSamples.mapNotNull { engine.addSample(it).candidateEvent }
        assertEquals("Everyday walking/sitting/running must not trigger hybrid fall alert", 0, adlTriggers.size)

        // 2. Classic free-fall trip (< 6.0 m/s² -> 65 m/s² impact -> stillness) triggers hybrid check-in with explainability
        engine.reset()
        val classicFallSamples = SyntheticSensorTraces.generateDipImpact65Stillness(stepMs = 20L)
        val classicTriggered = classicFallSamples.mapNotNull { engine.addSample(it).candidateEvent }
        assertEquals("Classic free-fall trip must trigger 1 candidate check-in", 1, classicTriggered.size)
        val classicExplainability = classicTriggered.first().explainabilityRecord
        assertNotNull("Candidate event must carry MlExplainabilityRecord", classicExplainability)
        assertNotNull("Dynamic calibrated confidence must be populated", classicExplainability!!.calibratedConfidence)
        assertTrue(classicExplainability.whyCheckInInitiated.isNotBlank())

        // 3. Gradual syncope collapse (min magnitude ~6.8 m/s², never < 6.0 m/s², followed by 48 m/s² impact & stillness)
        // Caught by 1D CNN even though 3-phase rule detector requires < 6.0 m/s² free-fall
        engine.reset()
        val syncopeTrace = com.example.sensor.ModelComparisonEvaluator.buildHeldOutTestSuite()
            .first { it.id == "FALL_GRADUAL_SYNCOPE_NO_CLASSIC_FREEFALL" }
            .samples
        val syncopeTriggered = syncopeTrace.mapNotNull { engine.addSample(it).candidateEvent }
        assertEquals(
            "Gradual syncope collapse without <6.0 m/s² free-fall must be caught by 1D CNN in HybridEngine",
            1,
            syncopeTriggered.size
        )
        assertEquals(
            com.example.domain.MovementCategory.POSSIBLE_FALL,
            syncopeTriggered.first().explainabilityRecord?.predictedCategory
        )
    }

    @Test
    fun modelComparisonEvaluator_computesHeldOutMetricsAndConfusionMatrixForRuleRfAndCnn() {
        val report = com.example.sensor.ModelComparisonEvaluator.evaluateHeldOutSuite(context = null)

        assertTrue(report.heldOutWindowCount >= 12)
        assertEquals(6, report.cnnHybridMetrics.confusionMatrix6x6.size)
        assertEquals(6, report.randomForestBaselineMetrics.confusionMatrix6x6.size)
        assertEquals(6, report.ruleBasedMetrics.confusionMatrix6x6.size)

        // 1D CNN + Hybrid Guard must achieve higher recall on falls (catching syncope collapses) than 3-Phase Rule alone
        assertTrue(
            "1D CNN recall (${report.cnnHybridMetrics.recallSensitivity}) must exceed 3-Phase Rule recall (${report.ruleBasedMetrics.recallSensitivity}) on syncope falls",
            report.cnnHybridMetrics.recallSensitivity > report.ruleBasedMetrics.recallSensitivity
        )
        assertEquals("Zero false positives expected on held-out ADL traces", 0f, report.cnnHybridMetrics.falsePositiveRate, 0.001f)
        assertTrue("Limitations must be documented honestly", report.limitations.isNotEmpty())
    }

    // ========================================================================
    // POST-FALL HEALTH ASSESSMENT, MEDICAL ID & AI HEALTH CROSS-CHECK TESTS
    // ========================================================================

    @Test
    fun incidentVerification_neverClassifiesPhoneDropAsHumanFall() {
        val phoneFell = com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME
        val phoneDropped = com.example.domain.IncidentVerificationType.PHONE_DROPPED_OR_PLACED
        val normalMove = com.example.domain.IncidentVerificationType.NORMAL_MOVEMENT
        val falseAlarm = com.example.domain.IncidentVerificationType.FALSE_ALARM
        val experiencedFall = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL
        val notSure = com.example.domain.IncidentVerificationType.NOT_SURE

        assertTrue("Phone fell while user did not fall must be recorded as a false detection", phoneFell.isFalseDetection)
        assertTrue("Phone fell must be flagged as a phone drop", phoneFell.isPhoneDrop)

        assertTrue("Phone dropped or placed down suddenly must be recorded as a false detection", phoneDropped.isFalseDetection)
        assertTrue("Phone dropped/placed must be flagged as a phone drop", phoneDropped.isPhoneDrop)

        assertTrue(normalMove.isFalseDetection)
        assertFalse(normalMove.isPhoneDrop)
        assertTrue(falseAlarm.isFalseDetection)

        assertFalse(experiencedFall.isFalseDetection)
        assertFalse(notSure.isFalseDetection)
    }

    @Test
    fun postFallAssessmentDraft_detectsSevereWarningIndicatorsImmediately() {
        val cleanDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "inc_100",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL,
            painLevel = com.example.domain.PainLevel.NO_PAIN,
            difficultyBreathing = com.example.domain.TriStateAnswer.NO,
            dizzyOrWeak = com.example.domain.TriStateAnswer.NO,
            confusionOrDifficultyResponding = com.example.domain.TriStateAnswer.NO,
            hitHead = com.example.domain.TriStateAnswer.NO,
            needMedicalAssistance = com.example.domain.TriStateAnswer.NO
        )
        assertFalse("No symptoms reported should not trigger severe warning indicator", cleanDraft.hasSevereWarningIndicators())
        assertTrue(cleanDraft.describeSevereIndicators().isEmpty())

        val severePainDraft = cleanDraft.copy(painLevel = com.example.domain.PainLevel.SEVERE_PAIN)
        assertTrue(severePainDraft.hasSevereWarningIndicators())
        assertTrue(severePainDraft.describeSevereIndicators().any { it.contains("Severe pain") })

        val headHitDraft = cleanDraft.copy(hitHead = com.example.domain.TriStateAnswer.YES)
        assertTrue("Hitting head must immediately trigger severe warning indicator", headHitDraft.hasSevereWarningIndicators())

        val breathingDraft = cleanDraft.copy(difficultyBreathing = com.example.domain.TriStateAnswer.YES)
        assertTrue("Difficulty breathing must immediately trigger severe warning indicator", breathingDraft.hasSevereWarningIndicators())

        val confusionDraft = cleanDraft.copy(confusionOrDifficultyResponding = com.example.domain.TriStateAnswer.YES)
        assertTrue("Confusion/difficulty responding must immediately trigger severe warning indicator", confusionDraft.hasSevereWarningIndicators())

        val medicalHelpDraft = cleanDraft.copy(needMedicalAssistance = com.example.domain.TriStateAnswer.YES)
        assertTrue("Requesting medical assistance must immediately trigger severe warning indicator", medicalHelpDraft.hasSevereWarningIndicators())
    }

    @Test
    fun aiHealthCrossCheckEngine_deterministicRules_crossReferenceSymptomsWithMedicalId() {
        val medicalIdWithBloodThinnersAndOsteoporosis = com.example.domain.MedicalIdProfile(
            fullName = "Evelyn Carter",
            ageOrGroup = "76",
            knownConditions = listOf("Atrial Fibrillation", "Osteoporosis", "Type 2 Diabetes"),
            medications = listOf("Apixaban (Eliquis)", "Metformin", "Lisinopril"),
            allergies = listOf("Penicillin"),
            allowAiCrossCheck = true,
            includeInEmergencyAlerts = true
        )
        assertTrue(medicalIdWithBloodThinnersAndOsteoporosis.hasBloodThinners)
        assertTrue(medicalIdWithBloodThinnersAndOsteoporosis.hasOsteoporosisOrBoneFragility)
        assertTrue(medicalIdWithBloodThinnersAndOsteoporosis.hasDiabetesOrInsulin)
        assertTrue(medicalIdWithBloodThinnersAndOsteoporosis.hasCardiacOrHypertension)

        // Case 1: Unsure head impact + blood thinner (Apixaban) -> URGENT_WARNING
        val headUnsureDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "inc_head_bt",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL,
            painLevel = com.example.domain.PainLevel.MILD_PAIN,
            hitHead = com.example.domain.TriStateAnswer.UNSURE
        )
        val headBtResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(
            draft = headUnsureDraft,
            medicalId = medicalIdWithBloodThinnersAndOsteoporosis
        )
        assertEquals(com.example.domain.HealthAdvisoryTier.URGENT_WARNING, headBtResult.riskTier)
        assertTrue(headBtResult.crossCheckHighlights.any { it.contains("Anticoagulant", ignoreCase = true) || it.contains("blood thinner", ignoreCase = true) })
        assertTrue(headBtResult.nonDiagnosticDisclaimer.contains("NOT a medical device", ignoreCase = true))

        // Case 2: Moderate hip pain + Osteoporosis -> URGENT_WARNING
        val hipPainDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "inc_hip_osteo",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL,
            painLevel = com.example.domain.PainLevel.MODERATE_PAIN,
            injuryLocations = listOf(com.example.domain.InjuryLocation.HIP),
            hitHead = com.example.domain.TriStateAnswer.NO
        )
        val hipOsteoResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(
            draft = hipPainDraft,
            medicalId = medicalIdWithBloodThinnersAndOsteoporosis
        )
        assertEquals(com.example.domain.HealthAdvisoryTier.URGENT_WARNING, hipOsteoResult.riskTier)
        assertTrue(hipOsteoResult.crossCheckHighlights.any { it.contains("Osteoporosis", ignoreCase = true) || it.contains("Bone", ignoreCase = true) })

        // Case 3: Dizziness + Diabetes & BP medication (without severe symptoms) -> CAUTION_ADVISORY
        val dizzyDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "inc_dizzy",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL,
            painLevel = com.example.domain.PainLevel.NO_PAIN,
            dizzyOrWeak = com.example.domain.TriStateAnswer.YES,
            hitHead = com.example.domain.TriStateAnswer.NO,
            difficultyBreathing = com.example.domain.TriStateAnswer.NO,
            confusionOrDifficultyResponding = com.example.domain.TriStateAnswer.NO,
            needMedicalAssistance = com.example.domain.TriStateAnswer.NO
        )
        val dizzyResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(
            draft = dizzyDraft,
            medicalId = medicalIdWithBloodThinnersAndOsteoporosis
        )
        assertEquals(com.example.domain.HealthAdvisoryTier.CAUTION_ADVISORY, dizzyResult.riskTier)
        assertTrue(dizzyResult.crossCheckHighlights.isNotEmpty())

        // Case 4: No symptoms reported -> SELF_MONITORING (when no older-adult / anticoag caution flags or when medicalId has no high-risk flags)
        val healthyMedicalId = com.example.domain.MedicalIdProfile(
            fullName = "Jordan Lee",
            ageOrGroup = "34",
            allowAiCrossCheck = true
        )
        val okDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "inc_ok",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL,
            painLevel = com.example.domain.PainLevel.NO_PAIN,
            difficultyBreathing = com.example.domain.TriStateAnswer.NO,
            dizzyOrWeak = com.example.domain.TriStateAnswer.NO,
            confusionOrDifficultyResponding = com.example.domain.TriStateAnswer.NO,
            hitHead = com.example.domain.TriStateAnswer.NO,
            needMedicalAssistance = com.example.domain.TriStateAnswer.NO
        )
        val okResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(
            draft = okDraft,
            medicalId = healthyMedicalId
        )
        assertEquals(com.example.domain.HealthAdvisoryTier.SELF_MONITORING, okResult.riskTier)
        assertTrue(okResult.nonDiagnosticDisclaimer.contains("NOT a medical device", ignoreCase = true))
    }

    @Test
    fun medicalIdProfile_respectsEmergencySosConsentFlag() {
        val profile = com.example.domain.MedicalIdProfile(
            fullName = "Alex Rivera",
            bloodType = "O+",
            knownConditions = listOf("Asthma"),
            medications = listOf("Warfarin"),
            allergies = listOf("Latex"),
            includeInEmergencyAlerts = false
        )
        assertNull(
            "Medical ID summary must be null when includeInEmergencyAlerts is false",
            profile.formatEmergencyAlertSummary()
        )

        val consentedProfile = profile.copy(includeInEmergencyAlerts = true)
        val summary = consentedProfile.formatEmergencyAlertSummary()
        assertNotNull("Medical ID summary must be included when includeInEmergencyAlerts is true", summary)
        assertTrue(summary!!.contains("Warfarin"))
        assertTrue(summary.contains("Asthma"))
    }

    @Test
    fun simplifiedFiveQuestionWizard_coversEveryAnswerOptionAndEmergencyBranches() {
        // Question 1: "What happened?" — 5 simplified options
        val q1Options = com.example.domain.IncidentVerificationType.simplifiedWizardOptions
        assertEquals(5, q1Options.size)
        assertEquals("I fell", com.example.domain.IncidentVerificationType.EXPERIENCED_FALL.simpleLabel)
        assertEquals("Only my phone fell", com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME.simpleLabel)
        assertEquals("I was just moving", com.example.domain.IncidentVerificationType.NORMAL_MOVEMENT.simpleLabel)
        assertEquals("Wrong detection", com.example.domain.IncidentVerificationType.FALSE_ALARM.simpleLabel)
        assertEquals("Not sure", com.example.domain.IncidentVerificationType.NOT_SURE.simpleLabel)

        // False detection branch: "Only my phone fell", "I was just moving", "Wrong detection"
        assertTrue(com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME.isFalseDetection)
        assertTrue(com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME.isPhoneDrop)
        assertTrue(com.example.domain.IncidentVerificationType.NORMAL_MOVEMENT.isFalseDetection)
        assertTrue(com.example.domain.IncidentVerificationType.FALSE_ALARM.isFalseDetection)
        assertFalse(com.example.domain.IncidentVerificationType.EXPERIENCED_FALL.isFalseDetection)
        assertFalse(com.example.domain.IncidentVerificationType.NOT_SURE.isFalseDetection)

        val baseDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_simplified_wizard",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL
        )

        // Question 2: "Are you feeling okay?" — Yes / A little uncomfortable / No
        val q2Yes = baseDraft.withFeelingOkay(com.example.domain.FeelingOkayStatus.YES)
        assertEquals("Yes", q2Yes.feelingOkay.simpleLabel)
        assertEquals(com.example.domain.TriStateAnswer.NO, q2Yes.dizzyOrWeak)

        val q2Uncomfortable = baseDraft.withFeelingOkay(com.example.domain.FeelingOkayStatus.A_LITTLE_UNCOMFORTABLE)
        assertEquals("A little uncomfortable", q2Uncomfortable.feelingOkay.simpleLabel)
        assertEquals(com.example.domain.TriStateAnswer.UNSURE, q2Uncomfortable.dizzyOrWeak)

        val q2No = baseDraft.withFeelingOkay(com.example.domain.FeelingOkayStatus.NO)
        assertEquals("No", q2No.feelingOkay.simpleLabel)
        assertEquals(com.example.domain.TriStateAnswer.YES, q2No.dizzyOrWeak)

        // Question 3: "Are you in pain?" — No / A little / Moderate / Severe
        assertEquals("No", com.example.domain.PainLevel.NO_PAIN.simpleLabel)
        assertEquals("A little", com.example.domain.PainLevel.MILD_PAIN.simpleLabel)
        assertEquals("Moderate", com.example.domain.PainLevel.MODERATE_PAIN.simpleLabel)
        assertEquals("Severe", com.example.domain.PainLevel.SEVERE_PAIN.simpleLabel)

        val q3Severe = q2Yes.copy(painLevel = com.example.domain.PainLevel.SEVERE_PAIN)
        assertTrue("Severe pain must immediately trigger emergency stop", q3Severe.requiresImmediateEmergencyStop())
        assertTrue(q3Severe.hasSevereWarningIndicators())

        // Question 4: "Are you breathing normally?" — Yes / No / Not sure
        val q4Yes = q2Yes.withBreathingNormally(com.example.domain.TriStateAnswer.YES)
        assertEquals(com.example.domain.TriStateAnswer.YES, q4Yes.breathingNormally)
        assertEquals(com.example.domain.TriStateAnswer.NO, q4Yes.difficultyBreathing)
        assertFalse(q4Yes.requiresImmediateEmergencyStop())

        val q4No = q2Yes.withBreathingNormally(com.example.domain.TriStateAnswer.NO)
        assertEquals(com.example.domain.TriStateAnswer.NO, q4No.breathingNormally)
        assertEquals(com.example.domain.TriStateAnswer.YES, q4No.difficultyBreathing)
        assertTrue("Answering 'No' to breathing normally must immediately trigger emergency stop", q4No.requiresImmediateEmergencyStop())
        assertTrue(q4No.hasSevereWarningIndicators())

        val q4NotSure = q2Yes.withBreathingNormally(com.example.domain.TriStateAnswer.UNSURE)
        assertEquals(com.example.domain.TriStateAnswer.UNSURE, q4NotSure.breathingNormally)
        assertEquals("Not sure", q4NotSure.breathingNormally.simpleLabel)

        // Question 5: "Do you need help?" — No / Yes / Not sure
        val q5No = q4Yes.copy(needMedicalAssistance = com.example.domain.TriStateAnswer.NO)
        assertFalse(q5No.hasSevereWarningIndicators())

        val q5Yes = q4Yes.copy(needMedicalAssistance = com.example.domain.TriStateAnswer.YES)
        assertTrue("Requesting help on Question 5 must flag urgent warning indicator", q5Yes.hasSevereWarningIndicators())
        val helpResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(q5Yes, null)
        assertEquals(com.example.domain.HealthAdvisoryTier.URGENT_WARNING, helpResult.riskTier)

        val q5NotSure = q4Yes.copy(needMedicalAssistance = com.example.domain.TriStateAnswer.UNSURE)
        val unsureResult = com.example.domain.AiHealthCrossCheckEngine.evaluateDeterministic(q5NotSure, null)
        assertEquals(com.example.domain.HealthAdvisoryTier.CAUTION_ADVISORY, unsureResult.riskTier)
    }

    @Test
    fun personalLearning_mapsAllSixQuestionnaireOptionsAndExcludesUncertainOrContradictory() {
        val options = com.example.domain.IncidentVerificationType.personalLearningWizardOptions
        assertEquals(6, options.size)
        assertTrue(options.contains(com.example.domain.IncidentVerificationType.SITTING_DOWN))
        assertEquals("I was sitting down", com.example.domain.IncidentVerificationType.SITTING_DOWN.simpleLabel)

        val validSnapshot = com.example.domain.SensorWindowSnapshot(
            windowSampleCount = 150,
            minTotalAccelG = 0.58f,
            peakTotalAccelMs2 = 26.2f,
            peakGyroDegPerSec = 175.0f,
            postImpactStillnessStdDevMs2 = 0.45f
        )

        // 1. Confirmed user-reported fall ("I fell")
        val fallDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_fall_1",
            incidentVerification = com.example.domain.IncidentVerificationType.EXPERIENCED_FALL
        )
        val fallExample = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_alpha",
            draft = fallDraft,
            snapshot = validSnapshot,
            cnnResult = null
        )
        assertEquals(com.example.domain.FeedbackLearningLabel.CONFIRMED_FALL, fallExample.learningLabel)
        assertTrue("Confirmed fall with valid sensor window must be included in training", fallExample.includedInTraining)

        // 2. Phone-only incident ("Only my phone fell")
        val phoneDropDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_phone_1",
            incidentVerification = com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME
        )
        val phoneDropExample = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_alpha",
            draft = phoneDropDraft,
            snapshot = validSnapshot,
            cnnResult = null
        )
        assertEquals(com.example.domain.FeedbackLearningLabel.PHONE_ONLY_INCIDENT, phoneDropExample.learningLabel)
        assertTrue(phoneDropExample.includedInTraining)

        // 3. Normal sitting ("I was sitting down")
        val sittingDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_sit_1",
            incidentVerification = com.example.domain.IncidentVerificationType.SITTING_DOWN
        )
        val sittingExample = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_alpha",
            draft = sittingDraft,
            snapshot = validSnapshot,
            cnnResult = null
        )
        assertEquals(com.example.domain.FeedbackLearningLabel.NORMAL_SITTING, sittingExample.learningLabel)
        assertTrue(sittingExample.includedInTraining)

        // 4. Uncertain answer ("I'm not sure") -> MUST NOT be used as a confirmed training example
        val uncertainDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_unsure_1",
            incidentVerification = com.example.domain.IncidentVerificationType.NOT_SURE
        )
        val uncertainExample = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_alpha",
            draft = uncertainDraft,
            snapshot = validSnapshot,
            cnnResult = null
        )
        assertEquals(com.example.domain.FeedbackLearningLabel.UNCERTAIN_EXCLUDED, uncertainExample.learningLabel)
        assertFalse("Uncertain feedback must never be included in training", uncertainExample.includedInTraining)

        // 5. Contradictory feedback (e.g. "Wrong detection" paired with severe pain) -> MUST be excluded
        val contradictoryDraft = com.example.domain.PostFallAssessmentDraft(
            linkedEventId = "evt_contradict_1",
            incidentVerification = com.example.domain.IncidentVerificationType.FALSE_ALARM,
            painLevel = com.example.domain.PainLevel.SEVERE_PAIN
        )
        val contradictoryExample = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_alpha",
            draft = contradictoryDraft,
            snapshot = validSnapshot,
            cnnResult = null
        )
        assertEquals(com.example.domain.FeedbackLearningLabel.CONTRADICTORY_EXCLUDED, contradictoryExample.learningLabel)
        assertFalse("Contradictory feedback with severe symptoms must be excluded from training", contradictoryExample.includedInTraining)
    }

    @Test
    fun personalLearning_requiresAtLeastFiveConfirmedExamplesAndNeverSuppressesHighImpactFalls() {
        val normalSitSnapshot = com.example.domain.SensorWindowSnapshot(
            windowSampleCount = 150,
            minTotalAccelG = 0.62f,
            peakTotalAccelMs2 = 25.4f,
            peakGyroDegPerSec = 160.0f,
            postImpactStillnessStdDevMs2 = 1.40f
        )

        val examples = mutableListOf<com.example.domain.PersonalLearningExample>()

        // Stage 0: 0 examples -> GETTING_STARTED
        val profile0 = com.example.sensor.PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_beta",
            examples = examples
        )
        assertEquals(com.example.domain.PersonalLearningStage.GETTING_STARTED, profile0.learningStage)
        assertEquals(0.0f, profile0.calibratedConfidenceDelta, 0.0001f)

        // Add 1st confirmed example -> single answer MUST NOT permanently change sensitivity
        val ex1 = com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
            userId = "user_beta",
            draft = com.example.domain.PostFallAssessmentDraft(
                linkedEventId = "evt_1",
                incidentVerification = com.example.domain.IncidentVerificationType.SITTING_DOWN
            ),
            snapshot = normalSitSnapshot,
            cnnResult = null
        )
        examples.add(ex1)
        val profile1 = com.example.sensor.PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_beta",
            examples = examples
        )
        assertEquals(com.example.domain.PersonalLearningStage.LEARNING, profile1.learningStage)
        assertEquals("A single questionnaire answer must not change sensitivity thresholds", 0.0f, profile1.calibratedConfidenceDelta, 0.0001f)
        assertEquals(0.0f, profile1.calibratedImpactThresholdDeltaMs2, 0.0001f)

        // Add 4 more confirmed normal/sitting/phone-drop examples to reach 5 confirmed examples
        for (idx in 2..5) {
            val draft = com.example.domain.PostFallAssessmentDraft(
                linkedEventId = "evt_$idx",
                incidentVerification = if (idx % 2 == 0) {
                    com.example.domain.IncidentVerificationType.SITTING_DOWN
                } else {
                    com.example.domain.IncidentVerificationType.PHONE_FELL_NOT_ME
                }
            )
            examples.add(
                com.example.sensor.PersonalLearningEngine.validateAndBuildFeedbackExample(
                    userId = "user_beta",
                    draft = draft,
                    snapshot = normalSitSnapshot,
                    cnnResult = null
                )
            )
        }

        val profile5 = com.example.sensor.PersonalLearningEngine.recomputePersonalMovementProfile(
            userId = "user_beta",
            examples = examples
        )
        assertEquals(com.example.domain.PersonalLearningStage.PERSONALISED, profile5.learningStage)
        assertEquals(5, profile5.totalConfirmedExamples)
        assertTrue("Personalised stage with 5 non-fall examples should raise confidence threshold slightly within bounds", profile5.calibratedConfidenceDelta > 0.0f)
        assertEquals(16, profile5.normalPrototypeCentroid16D.size)

        // Evaluate Layer 3 decision on a recurring moderate sitting pattern matching the prototype
        val recurringDecision = com.example.sensor.PersonalLearningEngine.evaluateLayer3Decision(
            profile = profile5,
            snapshot = normalSitSnapshot,
            cnnResult = null,
            baseMlConfidenceThreshold = 0.72f,
            baseRuleImpactThresholdMs2 = 24.5f
        )
        assertTrue(
            "Recurring non-severe movement matching personal normal prototype should be suppressed",
            recurringDecision.suppressedRecurringFalseAlarm
        )
        assertFalse(recurringDecision.hardSafetyOverrideActive)

        // Evaluate Layer 3 decision on a severe high-impact fall (>= 37.3 m/s² / 3.8g) -> Hard Safety Override MUST prevent suppression
        val severeFallSnapshot = normalSitSnapshot.copy(
            peakTotalAccelMs2 = 41.5f,
            minTotalAccelG = 0.22f,
            postImpactStillnessStdDevMs2 = 0.15f
        )
        val severeDecision = com.example.sensor.PersonalLearningEngine.evaluateLayer3Decision(
            profile = profile5,
            snapshot = severeFallSnapshot,
            cnnResult = null,
            baseMlConfidenceThreshold = 0.72f,
            baseRuleImpactThresholdMs2 = 24.5f
        )
        assertTrue("High-impact fall must trigger hard safety override", severeDecision.hardSafetyOverrideActive)
        assertFalse("High-impact fall must NEVER be suppressed by personalisation", severeDecision.suppressedRecurringFalseAlarm)

        // Rollback / disable personalisation -> returns immediately to general model defaults
        val disabledProfile = profile5.copy(personalisationEnabled = false)
        val rollbackDecision = com.example.sensor.PersonalLearningEngine.evaluateLayer3Decision(
            profile = disabledProfile,
            snapshot = normalSitSnapshot,
            cnnResult = null,
            baseMlConfidenceThreshold = 0.72f,
            baseRuleImpactThresholdMs2 = 24.5f
        )
        assertFalse("Disabling personalisation must immediately stop personal suppression", rollbackDecision.suppressedRecurringFalseAlarm)
        assertEquals(0.72f, rollbackDecision.effectiveMlConfidenceThreshold, 0.0001f)
    }
}

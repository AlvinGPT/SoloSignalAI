package com.example.alerts

import com.example.data.FirestoreEmergencyAlert
import com.example.domain.AlertChannelType
import com.example.domain.CandidateEventSummary
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.MlExplainabilityRecord
import com.example.domain.SosAudioClipMetadata
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactVerificationStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying the unified EmergencyAlertDispatcher across all three emergency
 * trigger pathways (TEST, MANUAL_SOS, AUTOMATIC_FALL) and failure/edge-case scenarios.
 */
class EmergencyAlertDispatcherTest {

    private val verifiedContact = TrustedContact(
        userDisplayName = "Bob Senior",
        contactName = "Caretaker Alice",
        contactEmail = "alice@example.com",
        contactPhone = "+15551234567",
        relationship = "Daughter",
        preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP,
        consentGranted = true,
        optionalLocationEnabled = true,
        phonePopUpAlertsEnabled = true,
        verificationStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS,
        recipientHasRegisteredAccount = true,
        recipientAccepted = true,
        recipientRegisteredDeviceCount = 2,
        recipientNotificationsEnabled = true,
        linkId = "uid_senior_bob__uid_caretaker_alice_123",
        recipientUid = "uid_caretaker_alice_123"
    )

    private val sampleGps = GpsLocationSnapshot(
        latitude = 38.8315,
        longitude = -77.3117,
        accuracyMeters = 6.5f,
        timestampMillis = 1700000000000L,
        provider = "fused"
    )

    private fun defaultFormatPayload(
        alertId: String,
        contact: TrustedContact,
        eventSummary: CandidateEventSummary?,
        isTestAlert: Boolean,
        triggerReason: String,
        locationSnapshot: GpsLocationSnapshot?,
        locationDiagnostics: String?,
        audioClip: SosAudioClipMetadata?,
        audioStatusKey: String,
        audioStatusNote: String?,
        @Suppress("UNUSED_PARAMETER") medicalIdSummary: String?
    ): AlertMessagePayload {
        return AlertMessagePayload(
            alertId = alertId,
            isTestAlert = isTestAlert,
            subjectLine = if (isTestAlert) "[TEST] SoloSignal Alert" else "[URGENT] SoloSignal Emergency Alert",
            bodyText = "Reason: $triggerReason | Event: ${eventSummary?.eventId ?: "none"}",
            recipientName = contact.contactName,
            recipientEmail = contact.contactEmail,
            recipientPhone = contact.contactPhone,
            userDisplayName = contact.userDisplayName.ifBlank { "SoloSignal User" },
            timestampFormatted = "2026-11-04 10:00:00",
            locationStatusNote = locationDiagnostics ?: "GPS available",
            locationSnapshot = locationSnapshot,
            audioClip = audioClip,
            audioStatusKey = audioStatusKey,
            audioStatusNote = audioStatusNote ?: "Disabled"
        )
    }

    @Before
    fun setUp() {
        EmergencyDispatchDiagnostics.clearForTesting()
    }

    /**
     * 1. Test Alert dispatch:
     * Pressing Send Test Alert calls the unified dispatcher with type = TEST.
     */
    @Test
    fun testAlertDispatch_callsUnifiedDispatcherWithTestType() = runBlocking {
        var capturedPayload: AlertMessagePayload? = null
        var capturedRecipientUid: String? = null

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, contact, payload, triggerReason, channelUsed, recipientUid, _ ->
                capturedPayload = payload
                capturedRecipientUid = recipientUid
                Result.success(
                    FirestoreEmergencyAlert(
                        id = alertId,
                        alertId = alertId,
                        senderName = contact.userDisplayName,
                        recipientEmail = contact.contactEmail,
                        recipientUid = recipientUid,
                        isTestAlert = payload.isTestAlert,
                        triggerReason = triggerReason,
                        channelUsed = channelUsed
                    )
                )
            },
            deliverRemoteAlert = { _, payload, _, _, _ ->
                assertTrue(payload.isTestAlert)
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "Firebase Cloud Functions",
                    messageId = "projects/solosignal/messages/test_001",
                    detail = "Test push & email delivered",
                    emailSent = true
                )
            }
        )

        EmergencyDispatchDiagnostics.log(
            correlationId = "corr_test_001",
            stage = EmergencyDispatchLogStage.TEST_ALERT_BUTTON_PRESSED,
            alertType = EmergencyAlertDispatchType.TEST,
            detail = "User tapped Send Test Alert"
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.TEST,
            correlationId = "corr_test_001",
            triggerReason = "User tapped 'Send Test Alert'"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        val dispatched = outcome as EmergencyDispatchOutcome.Dispatched
        assertEquals(EmergencyAlertDispatchType.TEST, dispatched.alertType)
        assertEquals("uid_caretaker_alice_123", dispatched.recipientUid)
        assertEquals("uid_caretaker_alice_123", capturedRecipientUid)
        assertTrue(dispatched.firestoreCreated)
        assertTrue(dispatched.fcmAccepted)
        assertNotNull(capturedPayload)
        assertTrue(capturedPayload!!.isTestAlert)

        val stages = EmergencyDispatchDiagnostics.getEntriesForCorrelationId("corr_test_001").map { it.stage }
        assertTrue(stages.contains(EmergencyDispatchLogStage.TEST_ALERT_BUTTON_PRESSED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.EMERGENCY_DISPATCH_STARTED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.RECIPIENT_RESOLVED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.ALERT_DOCUMENT_CREATED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.FCM_DISPATCH_STARTED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED))
    }

    /**
     * 2. Manual SOS dispatch:
     * Pressing the SOS button calls the unified dispatcher with type = MANUAL_SOS.
     */
    @Test
    fun manualSosDispatch_callsUnifiedDispatcherWithManualSosType() = runBlocking {
        var capturedPayload: AlertMessagePayload? = null
        var capturedRecipientUid: String? = null

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, contact, payload, triggerReason, channelUsed, recipientUid, _ ->
                capturedPayload = payload
                capturedRecipientUid = recipientUid
                Result.success(
                    FirestoreEmergencyAlert(
                        id = alertId,
                        alertId = alertId,
                        senderName = contact.userDisplayName,
                        recipientEmail = contact.contactEmail,
                        recipientUid = recipientUid,
                        isTestAlert = payload.isTestAlert,
                        triggerReason = triggerReason,
                        channelUsed = channelUsed
                    )
                )
            },
            deliverRemoteAlert = { _, payload, _, _, _ ->
                assertFalse("Manual SOS must not be marked as test alert", payload.isTestAlert)
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "Firebase Cloud Functions",
                    messageId = "projects/solosignal/messages/sos_002",
                    detail = "Manual SOS push & email delivered",
                    emailSent = true
                )
            }
        )

        EmergencyDispatchDiagnostics.log(
            correlationId = "corr_sos_002",
            stage = EmergencyDispatchLogStage.MANUAL_SOS_BUTTON_PRESSED,
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            detail = "Red SOS button pressed on HomeScreen"
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            correlationId = "corr_sos_002",
            triggerReason = "Manual SOS button pressed on Home screen"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        val dispatched = outcome as EmergencyDispatchOutcome.Dispatched
        assertEquals(EmergencyAlertDispatchType.MANUAL_SOS, dispatched.alertType)
        assertEquals("uid_caretaker_alice_123", dispatched.recipientUid)
        assertEquals("uid_caretaker_alice_123", capturedRecipientUid)
        assertTrue(dispatched.firestoreCreated)
        assertTrue(dispatched.fcmAccepted)
        assertFalse(capturedPayload!!.isTestAlert)

        val stages = EmergencyDispatchDiagnostics.getEntriesForCorrelationId("corr_sos_002").map { it.stage }
        assertTrue(stages.contains(EmergencyDispatchLogStage.MANUAL_SOS_BUTTON_PRESSED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.ALERT_DOCUMENT_CREATED))
        assertTrue(stages.contains(EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED))
    }

    /**
     * 3. Automatic fall alert after check-in timeout:
     * Simulating a detected fall followed by check-in timeout triggers the unified dispatcher
     * with type = AUTOMATIC_FALL.
     */
    @Test
    fun automaticFallAlertAfterTimeout_callsUnifiedDispatcherWithAutomaticFallType() = runBlocking {
        val corrId = "corr_fall_timeout_003"
        val candidateEvent = CandidateEventSummary(
            eventId = "fall_evt_99",
            detectedAtEpochMillis = 1700000010000L,
            peakMagnitude = 39.2f,
            minMagnitude = 1.8f,
            suddenChangeCount = 4,
            orientationChangeDeg = 76f,
            postImpactStillnessStdDev = 0.25f,
            triggerSource = "REAL_SENSOR_3PHASE",
            summaryNote = "3-phase impact + posture change + stillness"
        )

        EmergencyDispatchDiagnostics.log(
            correlationId = corrId,
            stage = EmergencyDispatchLogStage.FALL_EVENT_CONFIRMED,
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            alertId = "alert_fall_evt_99",
            detail = "Candidate fall confirmed (peak=39.2 m/s²)"
        )
        EmergencyDispatchDiagnostics.log(
            correlationId = corrId,
            stage = EmergencyDispatchLogStage.CHECK_IN_TIMEOUT,
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            alertId = "alert_fall_evt_99",
            detail = "Check-in countdown reached 0s without user response"
        )

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, payload, triggerReason, _, recipientUid, _ ->
                Result.success(
                    FirestoreEmergencyAlert(
                        id = alertId,
                        alertId = alertId,
                        recipientUid = recipientUid,
                        isTestAlert = payload.isTestAlert,
                        triggerReason = triggerReason
                    )
                )
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "Firebase Cloud Functions",
                    messageId = "projects/solosignal/messages/fall_003",
                    detail = "Automatic fall alert delivered",
                    emailSent = true
                )
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            correlationId = corrId,
            triggerReason = "Check-in countdown expired without user response",
            candidateSummary = candidateEvent
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        val dispatched = outcome as EmergencyDispatchOutcome.Dispatched
        assertEquals(EmergencyAlertDispatchType.AUTOMATIC_FALL, dispatched.alertType)
        assertEquals("alert_fall_evt_99", dispatched.alertId)
        assertTrue(dispatched.firestoreCreated)
        assertTrue(dispatched.fcmAccepted)

        val stages = EmergencyDispatchDiagnostics.getEntriesForCorrelationId(corrId).map { it.stage }
        assertEquals(
            listOf(
                EmergencyDispatchLogStage.FALL_EVENT_CONFIRMED,
                EmergencyDispatchLogStage.CHECK_IN_TIMEOUT,
                EmergencyDispatchLogStage.EMERGENCY_DISPATCH_STARTED,
                EmergencyDispatchLogStage.RECIPIENT_RESOLVED,
                EmergencyDispatchLogStage.ALERT_DOCUMENT_CREATED,
                EmergencyDispatchLogStage.FCM_DISPATCH_STARTED,
                EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED
            ),
            stages
        )
    }

    /**
     * 4. Automatic alert when user selects "I need help" / "Send SOS Now":
     * Selecting an urgent response during check-in or the post-fall questionnaire triggers
     * the unified dispatcher immediately with type = AUTOMATIC_FALL.
     */
    @Test
    fun automaticFallAlertWhenUserSelectsNeedHelp_callsUnifiedDispatcherImmediately() = runBlocking {
        var capturedReason: String? = null
        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, payload, triggerReason, _, recipientUid, _ ->
                capturedReason = triggerReason
                Result.success(
                    FirestoreEmergencyAlert(
                        id = alertId,
                        alertId = alertId,
                        recipientUid = recipientUid,
                        isTestAlert = payload.isTestAlert,
                        triggerReason = triggerReason
                    )
                )
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "Firebase Cloud Functions",
                    messageId = "msg_help_004",
                    detail = "Immediate help alert delivered"
                )
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            correlationId = "corr_need_help_004",
            triggerReason = "User tapped 'Send SOS Now' during check-in"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        assertEquals("User tapped 'Send SOS Now' during check-in", capturedReason)
        assertEquals(
            EmergencyAlertDispatchType.AUTOMATIC_FALL,
            (outcome as EmergencyDispatchOutcome.Dispatched).alertType
        )
    }

    /**
     * 5. Missing emergency contact:
     * If no emergency contact is configured, the app fails safely with a clear state and does not crash.
     */
    @Test
    fun missingEmergencyContact_failsSafelyWithClearStateAndDoesNotCrash() = runBlocking {
        var firestoreCalled = false
        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { TrustedContact() }, // unconfigured empty contact
            resolveRecipientUid = { null },
            captureLocation = { null to "Unused" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { _, _, _, _, _, _, _ ->
                firestoreCalled = true
                Result.failure(IllegalStateException("Should not be called"))
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                AlertDeliveryResult.Failed("Should not be called")
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            correlationId = "corr_missing_005",
            triggerReason = "Manual SOS button pressed"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Failed)
        val failed = outcome as EmergencyDispatchOutcome.Failed
        assertFalse(firestoreCalled)
        assertEquals(DeliveryConfigStatus.NOT_CONFIGURED, failed.deliveryStatus)
        assertTrue(failed.reason.contains("No valid trusted contact", ignoreCase = true))

        val stages = EmergencyDispatchDiagnostics.getEntriesForCorrelationId("corr_missing_005").map { it.stage }
        assertTrue(stages.contains(EmergencyDispatchLogStage.DISPATCH_FAILED))
    }

    /**
     * 6. Invalid recipient token / no registered device:
     * If the recipient has no registered FCM token and email also fails, the failure is logged
     * clearly and fcmAccepted is false.
     */
    @Test
    fun invalidOrMissingRecipientToken_logsAndSurfacesReadinessWarning() = runBlocking {
        val contactWithoutFcmToken = verifiedContact.copy(
            verificationStatus = TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED,
            recipientRegisteredDeviceCount = 0,
            recipientUid = ""
        )

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { contactWithoutFcmToken },
            resolveRecipientUid = { null },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, payload, triggerReason, _, _, _ ->
                Result.success(
                    FirestoreEmergencyAlert(
                        id = alertId,
                        alertId = alertId,
                        isTestAlert = payload.isTestAlert,
                        triggerReason = triggerReason
                    )
                )
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                AlertDeliveryResult.Failed(
                    reason = "No active FCM token registered for recipient and Gmail token missing",
                    requiresGmailAuth = true
                )
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            correlationId = "corr_notoken_006",
            triggerReason = "Manual SOS"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        val dispatched = outcome as EmergencyDispatchOutcome.Dispatched
        assertFalse("fcmAccepted must be false when no FCM token is registered", dispatched.fcmAccepted)
        assertFalse(dispatched.emailSent)

        val entries = EmergencyDispatchDiagnostics.getEntriesForCorrelationId("corr_notoken_006")
        assertTrue(entries.any { it.stage == EmergencyDispatchLogStage.DISPATCH_FAILED && it.detail.contains("RECIPIENT_TOKEN_MISSING") })
    }

    /**
     * 7. Firebase/backend failure:
     * If both Firestore and remote backend return an error, the app does not falsely report
     * that the alert was delivered.
     */
    @Test
    fun firebaseAndBackendFailure_doesNotFalselyReportAlertDelivered() = runBlocking {
        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { _, _, _, _, _, _, _ ->
                Result.failure(RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions"))
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                AlertDeliveryResult.Failed("Cloud Function HTTP 503 Unavailable")
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            correlationId = "corr_backend_fail_007",
            triggerReason = "Check-in countdown expired"
        )

        assertTrue("Outcome must be Failed when both Firestore and backend fail", outcome is EmergencyDispatchOutcome.Failed)
        val failed = outcome as EmergencyDispatchOutcome.Failed
        assertEquals(DeliveryConfigStatus.ALERT_FAILED, failed.deliveryStatus)
        assertTrue(failed.reason.contains("PERMISSION_DENIED"))

        val stages = EmergencyDispatchDiagnostics.getEntriesForCorrelationId("corr_backend_fail_007").map { it.stage }
        assertTrue(stages.contains(EmergencyDispatchLogStage.DISPATCH_FAILED))
        assertFalse(stages.contains(EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED))
    }

    /**
     * 8. Duplicate SOS prevention:
     * Pressing SOS repeatedly or simultaneous timeout + manual trigger for the same alert/event
     * does not send duplicate identical alerts in an uncontrolled loop.
     */
    @Test
    fun duplicateSosPrevention_rapidPressesOrTimeoutPlusManual_sendsOnlyOneAlert() = runBlocking {
        val firestoreWriteCount = AtomicInteger(0)
        val remoteSendCount = AtomicInteger(0)

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = {
                delay(40)
                sampleGps to "GPS fix captured"
            },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, _, _, _, recipientUid, _ ->
                firestoreWriteCount.incrementAndGet()
                Result.success(FirestoreEmergencyAlert(id = alertId, alertId = alertId, recipientUid = recipientUid))
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                remoteSendCount.incrementAndGet()
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "FCM",
                    messageId = "msg_dedup_008",
                    detail = "Delivered once"
                )
            }
        )

        val sharedEvent = CandidateEventSummary(
            eventId = "shared_fall_event_008",
            detectedAtEpochMillis = 1700000050000L,
            peakMagnitude = 36.0f,
            minMagnitude = 2.0f,
            suddenChangeCount = 3,
            orientationChangeDeg = 70f,
            postImpactStillnessStdDev = 0.3f,
            triggerSource = "REAL_SENSOR",
            summaryNote = "Fall event"
        )

        val jobs = (1..4).map { idx ->
            async {
                dispatcher.dispatch(
                    alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                    correlationId = "corr_dup_$idx",
                    alertIdOverride = "alert_shared_fall_event_008",
                    triggerReason = "Concurrent trigger #$idx",
                    candidateSummary = sharedEvent
                )
            }
        }

        val results = jobs.awaitAll()
        val dispatchedCount = results.count { it is EmergencyDispatchOutcome.Dispatched }
        val suppressedCount = results.count { it is EmergencyDispatchOutcome.DuplicateSuppressed }

        assertEquals("Only 1 alert should be dispatched", 1, dispatchedCount)
        assertEquals("3 duplicate triggers should be suppressed", 3, suppressedCount)
        assertEquals(1, firestoreWriteCount.get())
        assertEquals(1, remoteSendCount.get())
    }

    /**
     * 9. Correct alert type and recipient:
     * When local TrustedContact has a blank recipientUid, resolveRecipientUid resolves the
     * verified UID from Firestore link/directory and injects it into both Firestore and remote payloads.
     */
    @Test
    fun correctAlertTypeAndRecipient_verifiesOutgoingPayloadAndCorrelationLogs() = runBlocking {
        val contactWithBlankLocalUid = verifiedContact.copy(recipientUid = "")
        var firestoreRecipientUid: String? = null
        var remoteContactUid: String? = null

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { contactWithBlankLocalUid },
            resolveRecipientUid = {
                // Simulates AlertFirestoreRepository.resolveVerifiedRecipientUid lookup
                "uid_caretaker_alice_123"
            },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, _, _, _, recipientUid, _ ->
                firestoreRecipientUid = recipientUid
                Result.success(FirestoreEmergencyAlert(id = alertId, alertId = alertId, recipientUid = recipientUid))
            },
            deliverRemoteAlert = { contact, _, _, _, _ ->
                remoteContactUid = contact.recipientUid
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "FCM",
                    messageId = "msg_009",
                    detail = "Delivered"
                )
            }
        )

        val outcome = dispatcher.dispatch(
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            correlationId = "corr_recipient_009",
            triggerReason = "Manual SOS"
        )

        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
        val dispatched = outcome as EmergencyDispatchOutcome.Dispatched
        assertEquals("uid_caretaker_alice_123", dispatched.recipientUid)
        assertEquals("uid_caretaker_alice_123", firestoreRecipientUid)
        assertEquals("uid_caretaker_alice_123", remoteContactUid)
        assertEquals("alice@example.com", dispatched.recipientEmail)
    }

    /**
     * 10. Asynchronous completion:
     * The dispatch function waits for asynchronous Firestore and remote delivery calls to complete
     * before returning and marking the alert as sent.
     */
    @Test
    fun asynchronousCompletion_waitsForDispatchCompletionBeforeMarkingAlertSent() = runBlocking {
        val remoteGate = CompletableDeferred<Unit>()
        var remoteCompleted = false

        val dispatcher = EmergencyAlertDispatcher(
            resolveContact = { verifiedContact },
            resolveRecipientUid = { it.recipientUid },
            captureLocation = { sampleGps to "GPS fix captured" },
            formatPayload = ::defaultFormatPayload,
            createFirestoreAlert = { alertId, _, _, _, _, recipientUid, _ ->
                Result.success(FirestoreEmergencyAlert(id = alertId, alertId = alertId, recipientUid = recipientUid))
            },
            deliverRemoteAlert = { _, _, _, _, _ ->
                remoteGate.await()
                remoteCompleted = true
                AlertDeliveryResult.RemoteDelivered(
                    channelUsed = AlertChannelType.SOLOSIGNAL_POPUP,
                    providerName = "FCM",
                    messageId = "msg_async_010",
                    detail = "Completed after gate"
                )
            }
        )

        val deferredOutcome = async {
            dispatcher.dispatch(
                alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                correlationId = "corr_async_010",
                triggerReason = "Check-in timeout"
            )
        }

        delay(50)
        assertFalse("dispatch() must not return before async delivery finishes", deferredOutcome.isCompleted)
        assertFalse(remoteCompleted)

        remoteGate.complete(Unit)
        val outcome = deferredOutcome.await()

        assertTrue(remoteCompleted)
        assertTrue(outcome is EmergencyDispatchOutcome.Dispatched)
    }

    /**
     * Verifies that the Automatic Full-Screen Emergency Alert constants and identifiers
     * are properly configured and distinct from check-in and standard dispatch channels.
     */
    @Test
    fun fullScreenEmergencyConstants_areProperlyConfiguredAndDistinct() {
        assertEquals("solosignal_emergency_fullscreen_v1", AlertDeliveryGateway.CHANNEL_EMERGENCY_FULLSCREEN)
        assertEquals("com.example.solosignal.ACTION_VIEW_ACTIVE_EMERGENCY", AlertDeliveryGateway.ACTION_VIEW_ACTIVE_EMERGENCY)
        assertEquals("extra_open_emergency_active", AlertDeliveryGateway.EXTRA_OPEN_EMERGENCY_ACTIVE)
        assertEquals(3104, AlertDeliveryGateway.NOTIFICATION_ID_EMERGENCY_FULLSCREEN)

        // Ensure distinct from check-in notification ID
        assertTrue(AlertDeliveryGateway.NOTIFICATION_ID_EMERGENCY_FULLSCREEN != AlertDeliveryGateway.NOTIFICATION_ID_CHECK_IN)
        assertTrue(AlertDeliveryGateway.NOTIFICATION_ID_EMERGENCY_FULLSCREEN != AlertDeliveryGateway.NOTIFICATION_ID_REAL_ALERT)
    }
}

package com.example

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.alerts.AlertDeliveryGateway
import com.example.alerts.AlertDeliveryResult
import com.example.alerts.EmailAlertClient
import com.example.data.AlertFirestoreRepository
import com.example.domain.AlertChannelType
import com.example.domain.CandidateEventSummary
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactVerificationStatus
import com.example.sensor.CandidateFallDetector
import com.example.sensor.SafetyMonitoringController
import com.example.sensor.SyntheticSensorTraces
import com.example.ui.screens.CheckInOverlay
import com.example.ui.screens.RecipientEmergencyAlertScreen
import com.example.ui.theme.SoloSignalTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.core.content.ContextCompat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@org.junit.Ignore("Robolectric requires external android-all maven artifact download which is not pre-cached for API 34 in container")
@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun candidateFallDetector_rejectsNormalEverydayAndTableTraces_andDetectsFallLikeTrace() {
        val detector = CandidateFallDetector()

        for (trace in listOf(
            SyntheticSensorTraces.TraceType.NORMAL_WALKING,
            SyntheticSensorTraces.TraceType.SITTING_DOWN_QUICKLY,
            SyntheticSensorTraces.TraceType.PLACING_ON_TABLE,
            SyntheticSensorTraces.TraceType.PHONE_SHAKEN_IN_HAND,
            SyntheticSensorTraces.TraceType.BRIEF_BUMP_CONTAINED
        )) {
            detector.reset()
            val samples = SyntheticSensorTraces.generateTrace(trace)
            var triggered: CandidateEventSummary? = null
            samples.forEachIndexed { idx, sample ->
                val result = detector.processSample(sample, nowElapsedMillis = (idx * 20L) + 10_000L)
                if (result.candidateSummary != null) {
                    triggered = result.candidateSummary
                }
            }
            assertNull("Expected $trace NOT to trigger a candidate fall check-in", triggered)
        }

        detector.reset()
        val fallSamples = SyntheticSensorTraces.generateTrace(
            SyntheticSensorTraces.TraceType.FALL_LIKE_IMPACT_STILLNESS
        )
        var fallTriggered: CandidateEventSummary? = null
        fallSamples.forEachIndexed { idx, sample ->
            val result = detector.processSample(sample, nowElapsedMillis = (idx * 20L) + 50_000L)
            if (result.candidateSummary != null) {
                fallTriggered = result.candidateSummary
                return@forEachIndexed
            }
        }
        assertNotNull("Expected FALL_LIKE_IMPACT_STILLNESS to trigger candidate check-in", fallTriggered)
        assertTrue(fallTriggered!!.peakMagnitude >= CandidateFallDetector.PEAK_IMPACT_THRESHOLD_MS2)
    }

    @Test
    fun checkInOverlay_displays15SecondCountdown_imOkCancel_andSendSosNow() {
        var cancelled = false
        var sendNow = false
        val contact = TrustedContact(
            userDisplayName = "Alex",
            contactName = "Jordan",
            contactEmail = "jordan@example.com"
        )

        composeTestRule.setContent {
            SoloSignalTheme {
                CheckInOverlay(
                    countdownRemaining = 15,
                    totalCountdownSeconds = 15,
                    candidateSummary = null,
                    trustedContact = contact,
                    onConfirmImOkay = { cancelled = true },
                    onRequestHelpNow = { sendNow = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Possible fall detected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Are you okay?").assertIsDisplayed()
        composeTestRule.onNodeWithTag("countdown_seconds_text").assertTextEquals("15s")
        composeTestRule.onNodeWithText("I'm OK").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Send SOS Now").performScrollTo().assertIsDisplayed()

        composeTestRule.onNodeWithTag("im_okay_cancel_button").performScrollTo().performClick()
        assertTrue("Tapping I'm OK must cancel the pending alert", cancelled)

        composeTestRule.onNodeWithTag("need_help_now_button").performScrollTo().performClick()
        assertTrue("Tapping Send SOS Now must trigger immediate SOS dispatch", sendNow)
    }

    @Test
    fun incomingEmergencyAlert_autoOpensAlertScreen_playsSoundAndVibrates_andStopsImmediatelyOnAcknowledge() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AlertDeliveryGateway.resetDeduplicationForTesting(app)

        val controller = SafetyMonitoringController.getInstance(app)
        controller.dismissActiveRecipientEmergencyAlert()

        val incoming = IncomingRecipientAlert(
            alertId = "alert_auto_open_99",
            senderName = "Alex Rivera",
            senderEmail = "alex@example.com",
            recipientName = "Jordan Lee",
            recipientEmail = "jordan@example.com",
            isTestAlert = false,
            subjectLine = "URGENT: SoloSignal Safety Check-In Alert for Alex Rivera",
            bodyText = "Possible fall detected for Alex Rivera.",
            triggerReason = "Possible fall detected (no response after 15s countdown)",
            deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT,
            deliveryStatusDetail = "Delivered",
            googleMapsUrl = "https://www.google.com/maps?q=37.422,-122.0841",
            locationSummary = "37.42200, -122.08410 (±8m)",
            createdAtMillis = System.currentTimeMillis(),
            latitude = 37.4220,
            longitude = -122.0841
        )

        val firstHandled = controller.onIncomingEmergencyAlertReceived(incoming)
        assertTrue("First arrival of new alertId must be processed", firstHandled)
        assertNotNull(
            "Incoming emergency alert must immediately set activeRecipientEmergencyAlert when app is open",
            controller.activeRecipientEmergencyAlert.value
        )
        assertEquals("alert_auto_open_99", controller.activeRecipientEmergencyAlert.value?.alertId)
        assertTrue(
            "Emergency alert sound must be triggered for the new alertId",
            controller.alertDeliveryGateway.didPlaySoundForAlert("alert_auto_open_99")
        )
        assertTrue(
            "Emergency alert vibration must be triggered for the new alertId",
            controller.alertDeliveryGateway.didVibrateForAlert("alert_auto_open_99")
        )
        assertTrue(
            "Alarm must be active before acknowledgement",
            controller.alertDeliveryGateway.isAlarmPlaying()
        )
        assertTrue(
            "Vibration must be active before acknowledgement",
            controller.alertDeliveryGateway.isVibrating()
        )

        // Verify high-importance notification with "SOS ALERT — Alex Rivera needs help", "View Emergency", and fullScreenIntent
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = Shadows.shadowOf(nm)
        val postedNotifs = shadowNm.allNotifications
        assertTrue("High-priority emergency notification must be posted", postedNotifs.isNotEmpty())
        val emergencyNotif = postedNotifs.first()
        val notifTitle = emergencyNotif.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        assertEquals("SOS ALERT — Alex Rivera needs help", notifTitle)
        assertNotNull("fullScreenIntent must be configured for background/lock-screen launch", emergencyNotif.fullScreenIntent)
        val actionTitles = emergencyNotif.actions?.map { it.title.toString() }.orEmpty()
        assertTrue(
            "Notification must include 'View Emergency' action",
            actionTitles.contains(AlertDeliveryGateway.ACTION_LABEL_VIEW_EMERGENCY)
        )
        assertTrue(
            "Notification must include 'I'm checking on them' action",
            actionTitles.contains(AlertDeliveryGateway.ACTION_LABEL_CHECKING_ON_THEM)
        )

        // Duplicate arrival of the same alertId must be ignored (no duplicate sound or screen reset)
        val duplicateHandled = controller.onIncomingEmergencyAlertReceived(incoming)
        assertFalse("Duplicate alertId must be deduplicated", duplicateHandled)

        // Tapping "I'm checking on them" must IMMEDIATELY stop alarm sound, stop vibration, and dismiss notification
        controller.acknowledgeIncomingAlert("alert_auto_open_99")
        assertFalse(
            "Alarm sound must stop immediately after acknowledgement",
            controller.alertDeliveryGateway.isAlarmPlaying()
        )
        assertFalse(
            "Vibration must stop immediately after acknowledgement",
            controller.alertDeliveryGateway.isVibrating()
        )
        assertTrue(
            "Emergency notification must be dismissed immediately after acknowledgement",
            shadowNm.allNotifications.isEmpty()
        )
        assertTrue(
            "Alert must be persisted as acknowledged locally",
            controller.alertDeliveryGateway.isAlertAcknowledged("alert_auto_open_99")
        )
        assertEquals(
            AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
            controller.activeRecipientEmergencyAlert.value?.deliveryState
        )
        assertEquals(
            "Contact is checking on you",
            controller.activeRecipientEmergencyAlert.value?.deliveryStatusDetail
        )

        // Subsequent duplicate delivery of the same alertId must NOT restart the alarm or repost notification
        val afterAckHandled = controller.onIncomingEmergencyAlertReceived(incoming)
        assertFalse("Acknowledged alert must never re-trigger", afterAckHandled)
        assertFalse(
            "Alarm must remain stopped after duplicate delivery of acknowledged alert",
            controller.alertDeliveryGateway.isAlarmPlaying()
        )
        assertFalse(
            "Vibration must remain stopped after duplicate delivery of acknowledged alert",
            controller.alertDeliveryGateway.isVibrating()
        )
    }

    @Test
    fun trustedContact_isReadyForPushAlerts_requiresAuthorizedRelationshipAndRegisteredDevice() {
        val emailOnlyContact = TrustedContact(
            userDisplayName = "Alex",
            contactName = "Jordan",
            contactEmail = "jordan@example.com",
            verificationStatus = TrustedContactVerificationStatus.INVITATION_NEEDED
        )
        assertTrue(emailOnlyContact.isValid)
        assertFalse(
            "Presence of email alone must NOT mark contact as ready for push alerts",
            emailOnlyContact.isReadyForPushAlerts
        )
        assertEquals(
            "This person hasn't joined SoloSignal yet. Invite them to install the app and complete setup, or choose another trusted contact who is already registered.",
            TrustedContactVerificationStatus.UNREGISTERED_CONTACT_EXPLANATION
        )

        val acceptedWithoutDevice = emailOnlyContact.copy(
            verificationStatus = TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED,
            recipientHasRegisteredAccount = true,
            recipientAccepted = true,
            recipientRegisteredDeviceCount = 0
        )
        assertFalse(acceptedWithoutDevice.isReadyForPushAlerts)
        assertEquals("Offline", com.example.ui.screens.resolveContactBadge(acceptedWithoutDevice).first)

        val readyContact = emailOnlyContact.copy(
            verificationStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS,
            recipientHasRegisteredAccount = true,
            recipientAccepted = true,
            recipientRegisteredDeviceCount = 1,
            recipientNotificationsEnabled = true
        )
        assertTrue(readyContact.isReadyForPushAlerts)
        assertEquals("Connected", com.example.ui.screens.resolveContactBadge(readyContact).first)

        val signedInOtherDeviceContact = emailOnlyContact.copy(
            verificationStatus = TrustedContactVerificationStatus.REGISTERED,
            recipientHasRegisteredAccount = true,
            recipientAccepted = false,
            recipientRegisteredDeviceCount = 1
        )
        assertEquals("Registered", com.example.ui.screens.resolveContactBadge(signedInOtherDeviceContact).first)

        val pendingRequestContact = signedInOtherDeviceContact.copy(
            verificationStatus = TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING
        )
        assertEquals("Pending", com.example.ui.screens.resolveContactBadge(pendingRequestContact).first)
        assertEquals("Invite", com.example.ui.screens.resolveContactBadge(emailOnlyContact).first)
    }

    @Test
    fun alertDeliveryGateway_buildsEmailAndPopUpPayloadWithGpsMapLink_andPostsRecipientPopUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        AlertDeliveryGateway.resetDeduplicationForTesting()
        val gateway = AlertDeliveryGateway(app)
        val contact = TrustedContact(
            userDisplayName = "Alex Rivera",
            contactName = "Jordan Lee",
            contactEmail = "jordan@example.com",
            contactPhone = "+15550192834",
            relationship = "Partner",
            preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP,
            consentGranted = true,
            optionalLocationEnabled = true,
            phonePopUpAlertsEnabled = true,
            verificationStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS,
            recipientAccepted = true,
            recipientRegisteredDeviceCount = 1,
            recipientNotificationsEnabled = true
        )
        val gpsSnapshot = GpsLocationSnapshot(
            latitude = 37.4220,
            longitude = -122.0841,
            accuracyMeters = 8f,
            timestampMillis = 1710000000000L,
            provider = "fused",
            nearbyEmergencyContext = "Near Charleston Rd, Mountain View"
        )

        val payload = gateway.formatMessagePayload(
            alertId = "alert_unit_test_1",
            contact = contact,
            eventSummary = null,
            isTestAlert = true,
            triggerReason = "Manual test alert",
            locationSnapshot = gpsSnapshot
        )

        assertTrue(payload.subjectLine.contains("TEST — NOT AN EMERGENCY"))
        assertTrue(payload.bodyText.contains("https://www.google.com/maps?q=37.422,-122.0841"))
        assertTrue(payload.locationStatusNote.contains("37.42200, -122.08410"))

        val emailClient = EmailAlertClient()
        val rfcMessage = emailClient.buildRfc2822Message(
            senderEmail = "alex@example.com",
            recipientEmail = "jordan@example.com",
            replyToEmail = "alex@example.com",
            subject = payload.subjectLine,
            plainText = payload.bodyText,
            htmlBody = "<p>Test Emergency HTML</p>"
        )
        assertTrue(rfcMessage.contains("To: jordan@example.com"))
        assertTrue(rfcMessage.contains("Reply-To: alex@example.com"))

        val incoming = IncomingRecipientAlert(
            alertId = "alert_unit_test_1",
            senderName = "Alex Rivera",
            senderEmail = "alex@example.com",
            recipientName = "Jordan Lee",
            recipientEmail = "jordan@example.com",
            isTestAlert = true,
            subjectLine = payload.subjectLine,
            bodyText = payload.bodyText,
            triggerReason = "Manual test alert",
            deliveryState = AlertFirestoreRepository.STATE_SENT_BY_PROVIDER,
            deliveryStatusDetail = "Sent",
            googleMapsUrl = gpsSnapshot.googleMapsUrl,
            locationSummary = payload.locationStatusNote,
            createdAtMillis = System.currentTimeMillis(),
            latitude = gpsSnapshot.latitude,
            longitude = gpsSnapshot.longitude
        )
        // Without POST_NOTIFICATIONS on Android 13+, pop-up notification must honestly report false
        val popUpWithoutPerm = gateway.postIncomingTrustedContactPopUpNotification(incoming)
        assertFalse(popUpWithoutPerm)

        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val popUpPosted = gateway.postIncomingTrustedContactPopUpNotification(incoming)
        assertTrue(popUpPosted)

        val localContact = contact.copy(preferredChannel = AlertChannelType.LOCAL_ONLY)
        val result = gateway.deliverAlert(localContact, payload, GoogleChatAlertConfig())
        assertTrue(result is AlertDeliveryResult.LocalFallbackPosted)
    }

    @Test
    fun recipientEmergencyAlertScreen_displaysEssentialEmergencyInfoAndActions() {
        var acknowledgedId: String? = null
        val alert = IncomingRecipientAlert(
            alertId = "alert_emergency_42",
            senderName = "Alex Rivera",
            senderEmail = "alex@example.com",
            recipientName = "Jordan Lee",
            recipientEmail = "jordan@example.com",
            isTestAlert = false,
            subjectLine = "URGENT: SoloSignal Safety Check-In Alert for Alex Rivera",
            bodyText = "Possible fall detected for Alex Rivera.",
            triggerReason = "Possible fall detected (no response after 15s countdown)",
            deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT,
            deliveryStatusDetail = "Delivered to recipient phone",
            googleMapsUrl = "https://www.google.com/maps?q=37.422,-122.0841",
            locationSummary = "GPS Coordinates: 37.42200, -122.08410 (±8m)",
            createdAtMillis = 1710000000000L,
            latitude = 37.4220,
            longitude = -122.0841
        )

        composeTestRule.setContent {
            SoloSignalTheme {
                RecipientEmergencyAlertScreen(
                    alert = alert,
                    onAcknowledge = { id -> acknowledgedId = id },
                    onClose = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("recipient_emergency_alert_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("emergency_alert_sender_name_text").assertTextEquals("Alex Rivera")
        composeTestRule.onNodeWithTag("emergency_alert_type_text").assertTextEquals("Possible fall detected")
        composeTestRule.onNodeWithTag("emergency_alert_coordinates_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("open_in_google_maps_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("contact_sender_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("acknowledge_emergency_screen_button").performScrollTo().performClick()
        assertEquals("alert_emergency_42", acknowledgedId)
    }

    @Test
    fun newUsersStartWithBlankAccount_isolatedPerUser_whileExistingUsersRetainSavedContacts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefsRepo = com.example.data.PreferencesRepository(app)

        // 1. User A signs in and saves a private emergency contact
        prefsRepo.setActiveUser("uid_existing_user_a")
        val existingCheck = prefsRepo.getPreferencesForUser("uid_existing_user_a")
        assertFalse("Brand-new User A must start with a blank contact list", existingCheck.trustedContact.isValid)

        val userAContact = TrustedContact(
            userDisplayName = "User A",
            contactName = "Sarah Connor",
            contactEmail = "sarah@example.com",
            contactPhone = "+15550101111",
            relationship = "Family",
            consentGranted = true,
            linkId = "link_uid_existing_user_a_sarah_example_com",
            verificationStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS,
            recipientHasRegisteredAccount = true,
            recipientAccepted = true,
            recipientRegisteredDeviceCount = 1,
            recipientNotificationsEnabled = true
        )
        prefsRepo.saveTrustedContact(userAContact)
        prefsRepo.setOnboardingCompleted(true)

        val savedUserA = prefsRepo.getPreferencesForUser("uid_existing_user_a")
        assertTrue("User A must have their saved contact", savedUserA.trustedContact.isValid)
        assertEquals("Sarah Connor", savedUserA.trustedContact.contactName)

        // 2. Newly registered User B signs in on the same device: must start completely blank!
        prefsRepo.setActiveUser("uid_brand_new_user_b")
        val newUserBPrefs = prefsRepo.getPreferencesForUser("uid_brand_new_user_b")
        assertFalse(
            "Newly registered User B must start with an empty emergency-contact list and never inherit User A's contact",
            newUserBPrefs.trustedContact.isValid
        )
        assertEquals("", newUserBPrefs.trustedContact.contactName)
        assertEquals("", newUserBPrefs.trustedContact.contactEmail)
        assertFalse("Newly registered User B must have onboardingCompleted == false", newUserBPrefs.onboardingCompleted)

        // 3. Switching back to existing User A preserves User A's saved contact without erasing data
        prefsRepo.setActiveUser("uid_existing_user_a")
        val restoredUserAPrefs = prefsRepo.getPreferencesForUser("uid_existing_user_a")
        assertTrue("Existing User A must retain their saved contact", restoredUserAPrefs.trustedContact.isValid)
        assertEquals("Sarah Connor", restoredUserAPrefs.trustedContact.contactName)
        assertEquals("sarah@example.com", restoredUserAPrefs.trustedContact.contactEmail)
    }

    @Test
    fun homeScreen_removesEmergencyAlertBannerImmediatelyAfterAcknowledgement_andDoesNotReappear() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AlertDeliveryGateway.resetDeduplicationForTesting(app)
        val controller = SafetyMonitoringController.getInstance(app)

        val alert = IncomingRecipientAlert(
            alertId = "alert_banner_dismiss_77",
            senderName = "Taylor Swift",
            senderEmail = "taylor@example.com",
            recipientName = "Jordan",
            recipientEmail = "jordan@example.com",
            isTestAlert = false,
            subjectLine = "URGENT: SoloSignal Safety Check-In Alert",
            bodyText = "SOS requested by Taylor Swift.",
            triggerReason = "Manual SOS triggered",
            deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT,
            deliveryStatusDetail = "Delivered",
            googleMapsUrl = "https://www.google.com/maps?q=37.422,-122.0841",
            locationSummary = "37.42200, -122.08410 (±5m)",
            createdAtMillis = System.currentTimeMillis(),
            latitude = 37.4220,
            longitude = -122.0841
        )

        val alertsState = androidx.compose.runtime.mutableStateOf(listOf(alert))
        var acknowledgedCallbackId: String? = null

        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.HomeScreen(
                    monitoringState = com.example.domain.MonitoringState.STOPPED,
                    latestFeatures = com.example.domain.MotionFeatures(),
                    recentMagnitudes = emptyList(),
                    trustedContact = TrustedContact(),
                    googleChatConfig = GoogleChatAlertConfig(),
                    deliveryStatus = com.example.domain.DeliveryConfigStatus.NOT_CONFIGURED,
                    deliveryStatusDetail = "",
                    latestLocationSnapshot = null,
                    locationDiagnostics = "",
                    incomingRecipientAlerts = alertsState.value,
                    incomingContactRequests = emptyList(),
                    hasAccelerometer = true,
                    onboardingCompleted = true,
                    userEmail = "jordan@example.com",
                    onToggleMonitoring = {},
                    onRunSyntheticTrace = {},
                    onSendTestAlert = {},
                    onRefreshLocation = {},
                    onAcknowledgeIncomingAlert = { id ->
                        acknowledgedCallbackId = id
                        controller.acknowledgeIncomingAlert(id)
                        alertsState.value = alertsState.value.map { item ->
                            if (item.alertId == id) {
                                item.copy(
                                    deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                                    deliveryStatusDetail = "Contact is checking on you"
                                )
                            } else {
                                item
                            }
                        }
                    },
                    onOpenRecipientAlert = {},
                    onAcceptIncomingContactRequest = {},
                    onNavigateToContacts = {},
                    onCompleteOnboarding = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Before acknowledgement, the active emergency banner is displayed on HomeScreen
        composeTestRule.onNodeWithTag("active_incoming_emergency_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Taylor Swift needs assistance").assertIsDisplayed()

        // 2. Recipient presses "I'm checking on them" on the Home banner
        composeTestRule.onNodeWithTag("acknowledge_incoming_alert_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("alert_banner_dismiss_77", acknowledgedCallbackId)

        // 3. Banner must be immediately removed from the Home screen
        composeTestRule.onNodeWithTag("active_incoming_emergency_banner").assertDoesNotExist()
        assertFalse("Alarm must be stopped", controller.alertDeliveryGateway.isAlarmPlaying())
        assertFalse("Vibration must be stopped", controller.alertDeliveryGateway.isVibrating())

        // 4. Even if a stale unacknowledged copy arrives from a refresh or duplicate push, banner must NOT reappear
        alertsState.value = listOf(
            alert.copy(deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT)
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("active_incoming_emergency_banner").assertDoesNotExist()
    }

    @Test
    fun emergencyBuzzerChannel_configuredWithRawBuzzerUriAndHighImportance_andDeletesObsoleteChannels() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val gateway = AlertDeliveryGateway(app)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create an obsolete channel beforehand to verify safe migration
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    "solosignal_recipient_emergency_v5",
                    "Old Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
            assertNotNull(nm.getNotificationChannel("solosignal_recipient_emergency_v5"))

            // ensureChannels should migrate and delete the old channel
            gateway.ensureChannels()

            assertNull(
                "Obsolete notification channel that locked default sound must be deleted",
                nm.getNotificationChannel("solosignal_recipient_emergency_v5")
            )

            val buzzerChannel = nm.getNotificationChannel(AlertDeliveryGateway.CHANNEL_RECIPIENT_BUZZER)
            assertNotNull("New buzzer channel must exist", buzzerChannel)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, buzzerChannel.importance)
            val soundUri = buzzerChannel.sound
            assertNotNull("Buzzer channel must have a custom sound URI configured", soundUri)
            assertTrue(
                "Channel sound URI must reference the bundled raw emergency_buzzer asset: $soundUri",
                soundUri.toString().contains("emergency_buzzer")
            )
        }

        val buzzerUri = gateway.getEmergencyBuzzerUri()
        assertTrue(
            "Buzzer URI must point to raw/emergency_buzzer: $buzzerUri",
            buzzerUri.toString().contains("raw/emergency_buzzer")
        )

        // Verify testEmergencyBuzzer and stopEmergencyBuzzerTest lifecycle
        val testStarted = gateway.testEmergencyBuzzer(10_000L)
        assertTrue("Test buzzer should return true", testStarted)
        assertTrue("Alarm should report playing during buzzer test", gateway.isAlarmPlaying())
        gateway.stopEmergencyBuzzerTest()
        assertFalse("Alarm should stop playing after stopEmergencyBuzzerTest", gateway.isAlarmPlaying())
    }

    @Test
    fun qrContactCodec_roundTripBetweenUsers_rejectsInvalidExpiredSelfAndDuplicates() {
        val now = 1_730_000_000_000L

        // 1. User A generates a personal QR payload
        val userAPayload = com.example.domain.QrContactCodec.createPersonalPayload(
            uid = "uid_user_a_123",
            displayName = "Alex Rivera",
            email = "alex.rivera@example.com",
            phone = "+1 (555) 234-5678",
            nowMillis = now
        )
        val encodedUri = com.example.domain.QrContactCodec.encode(userAPayload)
        assertTrue(
            "Encoded QR URI must use versioned solosignal://contact scheme",
            encodedUri.startsWith(com.example.domain.QrContactCodec.URI_PREFIX)
        )
        assertFalse(
            "QR code must never embed raw email in plaintext outside the encoded payload",
            encodedUri.contains("alex.rivera@example.com")
        )

        val qrBitmap = com.example.domain.QrBitmapGenerator.generateBitmap(encodedUri, sizePx = 320)
        assertNotNull("ZXing QR bitmap generator must produce a non-null Bitmap", qrBitmap)
        assertEquals(320, qrBitmap?.width)
        assertEquals(320, qrBitmap?.height)

        // 2. User B scans User A's QR code within validity window
        val validScan = com.example.domain.QrContactCodec.decodeAndValidate(
            rawQrContent = encodedUri,
            scannerUid = "uid_user_b_456",
            scannerEmail = "jordan.lee@example.com",
            existingContact = TrustedContact(),
            nowMillis = now + 60_000L
        )
        assertTrue("Valid QR scanned by User B must succeed", validScan is com.example.domain.QrContactValidationResult.Valid)
        val decoded = (validScan as com.example.domain.QrContactValidationResult.Valid).payload
        assertEquals("uid_user_a_123", decoded.uid)
        assertEquals("Alex Rivera", decoded.displayName)
        assertEquals("alex.rivera@example.com", decoded.email)
        assertEquals("+1 (555) 234-5678", decoded.phone)
        assertEquals(userAPayload.inviteToken, decoded.inviteToken)

        // 3. Expired QR code is rejected
        val expiredScan = com.example.domain.QrContactCodec.decodeAndValidate(
            rawQrContent = encodedUri,
            scannerUid = "uid_user_b_456",
            scannerEmail = "jordan.lee@example.com",
            existingContact = TrustedContact(),
            nowMillis = now + com.example.domain.QrContactCodec.DEFAULT_TTL_MILLIS + 5_000L
        )
        assertTrue("Expired QR must be rejected", expiredScan is com.example.domain.QrContactValidationResult.Expired)

        // 4. Self-scan is rejected when User A scans their own QR code
        val selfScan = com.example.domain.QrContactCodec.decodeAndValidate(
            rawQrContent = encodedUri,
            scannerUid = "uid_user_a_123",
            scannerEmail = "alex.rivera@example.com",
            existingContact = TrustedContact(),
            nowMillis = now + 10_000L
        )
        assertTrue("Scanning one's own QR code must be rejected", selfScan is com.example.domain.QrContactValidationResult.SelfScanRejected)

        // 5. Duplicate contact is rejected if User B already has User A saved
        val alreadySavedContact = TrustedContact(
            userDisplayName = "Jordan Lee",
            contactName = "Alex Rivera",
            contactEmail = "alex.rivera@example.com",
            contactPhone = "+15552345678",
            consentGranted = true
        )
        val duplicateScan = com.example.domain.QrContactCodec.decodeAndValidate(
            rawQrContent = encodedUri,
            scannerUid = "uid_user_b_456",
            scannerEmail = "jordan.lee@example.com",
            existingContact = alreadySavedContact,
            nowMillis = now + 10_000L
        )
        assertTrue("Scanning an already-saved contact must return DuplicateContact", duplicateScan is com.example.domain.QrContactValidationResult.DuplicateContact)

        // 6. Invalid, damaged, and unsupported version payloads are rejected
        for (invalidInput in listOf(
            "",
            "https://example.com/not-solosignal",
            "solosignal://contact?v=1&payload=%%%NOT_BASE64%%%",
            """{"schema":"other.app","v":1}""",
            """{"schema":"solosignal.qr.contact","v":1,"token":"inv_123456","iat":100,"exp":200,"name":"","email":""}"""
        )) {
            val res = com.example.domain.QrContactCodec.decodeAndValidate(
                rawQrContent = invalidInput,
                scannerUid = "uid_user_b_456",
                nowMillis = 150L
            )
            assertTrue("Malformed input '$invalidInput' must be rejected as InvalidFormat", res is com.example.domain.QrContactValidationResult.InvalidFormat)
        }

        val futureVersionJson = """{"schema":"solosignal.qr.contact","v":99,"token":"inv_12345678","iat":100,"exp":999999,"name":"Alex","email":"alex@example.com"}"""
        val unsupportedRes = com.example.domain.QrContactCodec.decodeAndValidate(
            rawQrContent = futureVersionJson,
            scannerUid = "uid_user_b_456",
            nowMillis = 500L
        )
        assertTrue("Future QR schema version must return UnsupportedVersion", unsupportedRes is com.example.domain.QrContactValidationResult.UnsupportedVersion)
    }

    @Test
    fun trustedContactScreen_displaysQrIcons_opensPersonalQrAndScannerWithGracefulPermissionDenial() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.CAMERA)

        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.TrustedContactScreen(
                    currentContact = TrustedContact(),
                    googleChatConfig = GoogleChatAlertConfig(),
                    deliveryStatus = com.example.domain.DeliveryConfigStatus.NOT_CONFIGURED,
                    deliveryStatusDetail = "",
                    requiresGmailAuthorization = false,
                    hasGmailTokenConfigured = false,
                    lastMessagePreview = null,
                    latestLocationSnapshot = null,
                    locationDiagnostics = "",
                    isCapturingLocation = false,
                    incomingContactRequests = emptyList(),
                    registeredDevices = emptyList(),
                    fcmRegistrationStatus = "Registered",
                    isVerifyingTrustedContact = false,
                    currentUserUid = "uid_user_a_123",
                    currentUserDisplayName = "Alex Rivera",
                    currentUserEmail = "alex.rivera@example.com",
                    currentUserPhone = "+15552345678",
                    onVerifyAndSaveContact = { _, _, _ -> },
                    onAcceptIncomingContactRequest = {},
                    onDeleteContact = {},
                    onRefreshFcmDevice = {},
                    onSendTestAlert = {},
                    onRefreshLocation = {},
                    onSaveGmailAccessToken = {},
                    onSaveAndConnectGoogleChat = { _, _, _, _ -> },
                    onSendManualGoogleChatVerification = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Both QR icons must be displayed beside the Contacts heading
        composeTestRule.onNodeWithTag("add_emergency_contact_heading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("my_qr_code_icon_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_qr_code_icon_button").assertIsDisplayed()

        // Tap My QR Code icon -> opens Personal QR dialog with required instruction and Refresh/Close controls
        composeTestRule.onNodeWithTag("my_qr_code_icon_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("personal_qr_code_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("personal_qr_instruction_text")
            .assertTextEquals("Let the other person scan this code to add you as an emergency contact.")
        composeTestRule.onNodeWithTag("personal_qr_code_image").assertIsDisplayed()
        composeTestRule.onNodeWithTag("refresh_qr_code_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("close_personal_qr_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("personal_qr_code_dialog").assertDoesNotExist()

        // Tap Scan QR icon when CAMERA permission is denied -> shows graceful permission state & alignment instruction
        composeTestRule.onNodeWithTag("scan_qr_code_icon_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scan_qr_code_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_qr_instruction_text")
            .assertTextEquals("Align the QR code inside the frame.")
        composeTestRule.onNodeWithTag("camera_permission_denied_container").assertIsDisplayed()
        composeTestRule.onNodeWithTag("request_camera_permission_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("close_scan_qr_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scan_qr_code_dialog").assertDoesNotExist()
    }

    @Test
    fun trustedContactScreen_populatesFormFromScannedQr_andSavesOnlyAfterConfirmation() {
        val now = System.currentTimeMillis()
        val scannedPayload = com.example.domain.QrContactCodec.createPersonalPayload(
            uid = "uid_recipient_jordan",
            displayName = "Jordan Lee",
            email = "jordan.lee@example.com",
            phone = "+15559876543",
            nowMillis = now
        )

        var savedContact: TrustedContact? = null
        var sentPermissionRequest = false

        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.TrustedContactScreen(
                    currentContact = TrustedContact(),
                    googleChatConfig = GoogleChatAlertConfig(),
                    deliveryStatus = com.example.domain.DeliveryConfigStatus.NOT_CONFIGURED,
                    deliveryStatusDetail = "",
                    requiresGmailAuthorization = false,
                    hasGmailTokenConfigured = false,
                    lastMessagePreview = null,
                    latestLocationSnapshot = null,
                    locationDiagnostics = "",
                    isCapturingLocation = false,
                    incomingContactRequests = emptyList(),
                    registeredDevices = emptyList(),
                    fcmRegistrationStatus = "Registered",
                    isVerifyingTrustedContact = false,
                    currentUserUid = "uid_sender_alex",
                    currentUserDisplayName = "Alex Rivera",
                    currentUserEmail = "alex.rivera@example.com",
                    initialScannedQrPayload = scannedPayload,
                    onVerifyAndSaveContact = { contact, sendPermReq, _ ->
                        savedContact = contact
                        sentPermissionRequest = sendPermReq
                    },
                    onAcceptIncomingContactRequest = {},
                    onDeleteContact = {},
                    onRefreshFcmDevice = {},
                    onSendTestAlert = {},
                    onRefreshLocation = {},
                    onSaveGmailAccessToken = {},
                    onSaveAndConnectGoogleChat = { _, _, _, _ -> },
                    onSendManualGoogleChatVerification = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Before confirmation, the contact must NOT be saved silently
        assertNull("Scanned QR contact must NOT be saved before user confirms", savedContact)

        // 2. Confirmation dialog ("Add Emergency Contact?") is displayed with extracted details
        composeTestRule.onNodeWithTag("confirm_scanned_contact_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_scanned_contact_title").assertTextEquals("Add Emergency Contact?")
        composeTestRule.onNodeWithTag("confirm_scanned_contact_name").assertTextEquals("Jordan Lee")
        composeTestRule.onNodeWithTag("confirm_scanned_contact_email").assertTextEquals("jordan.lee@example.com")
        composeTestRule.onNodeWithTag("confirm_scanned_contact_phone").assertTextEquals("+15559876543")

        // 3. Tapping "Confirm and Add" saves the contact, requests recipient permission, and shows success banner
        composeTestRule.onNodeWithTag("confirm_and_add_qr_contact_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNotNull("Contact must be saved after tapping Confirm and Add", savedContact)
        assertEquals("Jordan Lee", savedContact?.contactName)
        assertEquals("jordan.lee@example.com", savedContact?.contactEmail)
        assertEquals("+15559876543", savedContact?.contactPhone)
        assertEquals("uid_recipient_jordan", savedContact?.recipientUid)
        assertEquals(AlertChannelType.SOLOSIGNAL_POPUP, savedContact?.preferredChannel)
        assertTrue("Confirmed QR contact must trigger recipient verification & permission request", sentPermissionRequest)
        composeTestRule.onNodeWithTag("qr_contact_saved_banner").assertIsDisplayed()
    }

    @Test
    fun sosWorkflow_checkInCancellation_discardsTemporaryAudioSnapshotAndTransitionsToCancelled() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = SafetyMonitoringController.getInstance(app)
        controller.stopMonitoring()

        // Seed 10 seconds of synthetic PCM samples into the rolling audio recorder's ring buffer
        val tenSecondsSamples = ShortArray(16_000 * 10) { idx -> ((idx % 2000) - 1000).toShort() }
        controller.sosAudioRecorder.feedPcmSamplesForTesting(tenSecondsSamples)
        assertEquals(10.0, controller.sosAudioRecorder.getBufferedDurationSeconds(), 0.01)

        // Trigger a candidate fall check-in
        val candidateSummary = CandidateEventSummary(
            eventId = "evt_cancel_audio_test_${System.currentTimeMillis()}",
            detectedAtEpochMillis = System.currentTimeMillis(),
            peakMagnitude = 62.5f,
            minMagnitude = 2.1f,
            suddenChangeCount = 4,
            orientationChangeDeg = 78f,
            postImpactStillnessStdDev = 0.25f,
            triggerSource = "Test Fall Sequence",
            summaryNote = "Free-fall dip -> 62.5 m/s² impact -> 2.5s stillness"
        )
        controller.triggerCandidateCheckIn(candidateSummary)

        assertEquals(com.example.domain.MonitoringState.CHECK_IN, controller.monitoringState.value)
        assertEquals(
            com.example.domain.SosWorkflowState.AWAITING_USER_CONFIRMATION,
            controller.sosWorkflowState.value
        )
        assertTrue(
            "Pre-trigger 15s audio snapshot must be held in memory during countdown",
            controller.sosAudioRecorder.hasCandidateSnapshot()
        )

        // User taps "I'm OK" during countdown -> cancels SOS and immediately discards temporary candidate audio snapshot
        controller.cancelCheckInByUser()

        assertEquals(
            "Cancelling check-in must transition sosWorkflowState to CANCELLED",
            com.example.domain.SosWorkflowState.CANCELLED,
            controller.sosWorkflowState.value
        )
        assertFalse(
            "Temporary candidate audio snapshot must be discarded when user confirms I'm OK",
            controller.sosAudioRecorder.hasCandidateSnapshot()
        )
        controller.sosAudioRecorder.stopBufferingAndClear()
    }

    @Test
    fun settingsPrivacyScreen_sosAudioToggle_requiresExplicitConsentDialog_andDisplaysStatusBadge() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)

        var enabledResult = false
        var consentResult = false
        var deleteCalled = false

        val enabledState = androidx.compose.runtime.mutableStateOf(false)
        val consentState = androidx.compose.runtime.mutableStateOf(false)
        val statusState = androidx.compose.runtime.mutableStateOf(com.example.domain.SosAudioStatus.DISABLED)

        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.SettingsPrivacyScreen(
                    countdownSeconds = 15,
                    vibrationEnabled = true,
                    optionalLocationEnabled = true,
                    hasAccelerometer = true,
                    deliveryStatus = com.example.domain.DeliveryConfigStatus.READY_TO_TEST,
                    deliveryStatusDetail = "Ready",
                    googleChatConfig = GoogleChatAlertConfig(),
                    userEmail = "alex@example.com",
                    onCountdownSecondsChanged = {},
                    onVibrationEnabledChanged = {},
                    onOptionalLocationChanged = {},
                    onDeleteAllPersonalData = {},
                    onSignOut = {},
                    sosAudioEnabled = enabledState.value,
                    sosAudioConsentGranted = consentState.value,
                    sosAudioStatus = statusState.value,
                    sosAudioBufferedSeconds = 15.0,
                    onSosAudioEnabledChange = { enabled, consent ->
                        enabledResult = enabled
                        consentResult = consent
                        enabledState.value = enabled
                        consentState.value = consent
                        statusState.value = if (enabled) {
                            com.example.domain.SosAudioStatus.AUDIO_MONITORING_ENABLED
                        } else {
                            com.example.domain.SosAudioStatus.DISABLED
                        }
                    },
                    onDeleteStoredSosAudio = {
                        deleteCalled = true
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. SOS Audio settings card & status badge are displayed
        composeTestRule.onNodeWithTag("sos_audio_settings_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sos_audio_status_badge").assertTextEquals("SOS audio disabled")

        // 2. Toggling SOS Audio Recording ON without prior consent opens the explicit disclosure dialog first
        composeTestRule.onNodeWithTag("sos_audio_enable_switch").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertFalse("Audio monitoring must NOT enable before user accepts the consent dialog", enabledResult)
        composeTestRule.onNodeWithTag("sos_audio_consent_dialog").assertIsDisplayed()

        // 3. Accepting the disclosure dialog enables SOS Audio Recording and updates status badge
        composeTestRule.onNodeWithTag("sos_audio_consent_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Consent dialog confirmation must enable SOS audio", enabledResult)
        assertTrue("Consent dialog confirmation must record explicit user consent", consentResult)
        composeTestRule.onNodeWithTag("sos_audio_status_badge").assertTextEquals("Audio monitoring enabled")

        // 4. Tapping Delete Stored SOS Audio Clips invokes cleanup callback
        composeTestRule.onNodeWithTag("delete_sos_audio_clips_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertTrue("Tapping Delete Stored SOS Audio Clips must invoke cleanup handler", deleteCalled)
    }

    @Test
    fun alertDeliveryGateway_nonTestFallAlert_usesRequiredSosSubjectAndBody_andHonestAudioFallbackWhenUnavailable() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val gateway = AlertDeliveryGateway(app)
        val contact = TrustedContact(
            userDisplayName = "Alex Rivera",
            contactName = "Jordan Lee",
            contactEmail = "jordan@example.com",
            preferredChannel = AlertChannelType.EMAIL,
            consentGranted = true,
            optionalLocationEnabled = true,
            sosAudioEnabled = true,
            sosAudioConsentGranted = true
        )

        // Case 1: Audio capture was unavailable (e.g. microphone permission missing) -> must NOT claim 15s audio is attached
        val fallbackPayload = gateway.formatMessagePayload(
            alertId = "alert_fallback_no_audio",
            contact = contact,
            eventSummary = null,
            isTestAlert = false,
            triggerReason = "Possible fall detected (no response after 15s countdown)",
            locationSnapshot = null,
            audioClip = null,
            audioStatusKey = com.example.domain.SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED.firestoreValue,
            audioStatusNote = "Microphone permission was not granted when SOS was triggered."
        )
        assertEquals("SOS ALERT — Possible Fall Detected (Alex Rivera)", fallbackPayload.subjectLine)
        assertTrue(
            "Body must include the required SOS fall countdown statement",
            fallbackPayload.bodyText.contains("SoloSignal AI has detected a possible fall involving the user configured for this alert (Alex Rivera).")
        )
        assertFalse(
            "Fallback email without audio clip must NEVER falsely claim 15-second audio is attached",
            fallbackPayload.bodyText.contains("Attached (15.0s")
        )
        assertTrue(
            "Fallback email must honestly explain why audio was not included",
            fallbackPayload.bodyText.contains("Microphone permission was not granted")
        )

        // Case 2: 15-second SOS audio clip was captured and uploaded -> includes required statement and download link
        val capturedClip = com.example.domain.SosAudioClipMetadata(
            alertId = "alert_with_audio_15s",
            localFilePath = "/tmp/sos_recording.m4a",
            mimeType = "audio/mp4",
            containerExtension = "m4a",
            durationSeconds = 15.0,
            preTriggerSeconds = 15.0,
            postTriggerSeconds = 0.0,
            sampleRateHz = 16_000,
            sizeBytes = 48_000L,
            capturedAtMillis = 1_730_000_000_000L,
            isPartialBuffer = false,
            storageObjectPath = "sos_audio/uid_alex/alert_with_audio_15s/sos_recording.m4a",
            downloadUrl = "https://firebasestorage.googleapis.com/v0/b/gen-lang-client-0124462176.firebasestorage.app/o/sos_audio?alt=media&token=abc"
        )
        val audioPayload = gateway.formatMessagePayload(
            alertId = "alert_with_audio_15s",
            contact = contact,
            eventSummary = null,
            isTestAlert = false,
            triggerReason = "Possible fall detected (no response after 15s countdown)",
            locationSnapshot = null,
            audioClip = capturedClip,
            audioStatusKey = com.example.domain.SosAudioStatus.SOS_AUDIO_CAPTURED.firestoreValue
        )
        assertEquals("SOS ALERT — Possible Fall Detected (Alex Rivera)", audioPayload.subjectLine)
        assertTrue(
            "Email with captured 15s clip must include the 15.0s audio attachment note",
            audioPayload.bodyText.contains("Attached (15.0s M4A)")
        )
        assertTrue(
            "Email with uploaded clip must include the protected Firebase Storage link",
            audioPayload.bodyText.contains(capturedClip.downloadUrl!!)
        )
    }

    @Test
    fun launcherIcon_resourcesExistAndLoadable_withSafeZoneBounds() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fgDrawable = ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground)
        assertNotNull("ic_launcher_foreground must be loadable", fgDrawable)

        val bgDrawable = ContextCompat.getDrawable(app, R.drawable.ic_launcher_background)
        assertNotNull("ic_launcher_background must be loadable", bgDrawable)

        val monoDrawable = ContextCompat.getDrawable(app, R.drawable.ic_launcher_foreground_monochrome)
        assertNotNull("ic_launcher_foreground_monochrome must be loadable", monoDrawable)

        val lightLogo = ContextCompat.getDrawable(app, R.drawable.ic_solosignal_logo_light)
        assertNotNull("ic_solosignal_logo_light must be loadable", lightLogo)

        val darkLogo = ContextCompat.getDrawable(app, R.drawable.ic_solosignal_logo_dark)
        assertNotNull("ic_solosignal_logo_dark must be loadable", darkLogo)
    }

    @Test
    fun signInScreen_displaysLogoContainerAndHeadingCorrectly() {
        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.SignInScreen(onAuthSuccess = {})
            }
        }
        composeTestRule.onNodeWithTag("solosignal_official_logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_heading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("welcome_subtitle").assertIsDisplayed()
    }

    @Test
    fun controllerSensorPipeline_processesSimulatedFallSequenceIntoCheckIn_andEnforcesCooldownAfterCancel() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = SafetyMonitoringController.getInstance(app)
        controller.startMonitoring()

        val fallSamples = SyntheticSensorTraces.generateTrace(
            SyntheticSensorTraces.TraceType.FALL_LIKE_IMPACT_STILLNESS
        )
        var candidateDetected: CandidateEventSummary? = null
        for (sample in fallSamples) {
            val eval = controller.processSensorSampleForTesting(sample)
            if (eval.candidateSummary != null) {
                candidateDetected = eval.candidateSummary
                break
            }
        }

        assertNotNull("Simulated fall sequence fed into SafetyMonitoringController must trigger candidate fall", candidateDetected)
        assertEquals(com.example.domain.MonitoringState.CHECK_IN, controller.monitoringState.value)
        assertEquals(
            com.example.domain.SosWorkflowState.AWAITING_USER_CONFIRMATION,
            controller.sosWorkflowState.value
        )
        assertTrue(controller.latestFeatures.value.isSyntheticSample)
        assertTrue(controller.latestFeatures.value.lastDecisionReason.contains("Accepted"))

        // User cancels check-in -> transitions to CANCELLED and activates 30s cooldown
        controller.cancelCheckInByUser()
        assertEquals(com.example.domain.MonitoringState.CANCELLED, controller.monitoringState.value)
    }

    @Test
    fun homeScreen_sensorDebugAndSafeSimulationPanel_displaysLiveValuesPhaseAndTriggersSimulation() {
        var triggeredTrace: SyntheticSensorTraces.TraceType? = null
        val features = com.example.domain.MotionFeatures(
            windowSampleCount = 45,
            windowDurationMs = 900L,
            currentMagnitude = 9.81f,
            peakMagnitude = 12.40f,
            currentAx = 0.15f,
            currentAy = -0.22f,
            currentAz = 9.80f,
            currentMagnitudeG = 1.00f,
            isSyntheticSample = true,
            effectiveImpactThresholdMs2 = 30.0f,
            detectorPhase = "SEEKING_FREE_FALL",
            lastDecisionReason = "Monitoring active — awaiting free-fall (< 6.0 m/s² for >= 40ms)"
        )

        composeTestRule.setContent {
            SoloSignalTheme {
                com.example.ui.screens.HomeScreen(
                    monitoringState = com.example.domain.MonitoringState.MONITORING,
                    latestFeatures = features,
                    recentMagnitudes = listOf(9.8f, 9.9f, 9.8f),
                    trustedContact = TrustedContact(
                        userDisplayName = "Alex",
                        contactName = "Jordan",
                        contactEmail = "jordan@example.com",
                        consentGranted = true
                    ),
                    googleChatConfig = GoogleChatAlertConfig(),
                    deliveryStatus = com.example.domain.DeliveryConfigStatus.REQUEST_ACCEPTED,
                    deliveryStatusDetail = "Emergency request accepted in Cloud Firestore",
                    latestLocationSnapshot = null,
                    locationDiagnostics = "",
                    incomingRecipientAlerts = emptyList(),
                    incomingContactRequests = emptyList(),
                    hasAccelerometer = true,
                    onboardingCompleted = true,
                    userEmail = "alex@example.com",
                    onToggleMonitoring = {},
                    onRunSyntheticTrace = { trace -> triggeredTrace = trace },
                    onSendTestAlert = {},
                    onRefreshLocation = {},
                    onAcknowledgeIncomingAlert = {},
                    onOpenRecipientAlert = {},
                    onAcceptIncomingContactRequest = {},
                    onNavigateToContacts = {},
                    onCompleteOnboarding = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        // Verify REQUEST_ACCEPTED status displays "Alert sent" on HomeScreen
        composeTestRule.onNodeWithTag("monitoring_status_pill").assertTextEquals("Alert sent")

        // Expand Sensor Debug & Safe Simulation panel
        composeTestRule.onNodeWithTag("sensor_debug_panel_toggle").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("sensor_debug_panel").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sensor_debug_source_badge").assertTextEquals("SIMULATED TEST")
        composeTestRule.onNodeWithTag("sensor_debug_live_values_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sensor_debug_phase_text").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("sensor_debug_decision_reason_text").performScrollTo().assertIsDisplayed()

        // Trigger Safe Simulation Mode button
        composeTestRule.onNodeWithTag("simulate_fall_trace_button").performScrollTo().performClick()
        assertEquals(
            SyntheticSensorTraces.TraceType.FALL_LIKE_IMPACT_STILLNESS,
            triggeredTrace
        )
    }

    @Test
    fun eventHistoryDatabase_migration1To2_preservesExistingHistoryRowsWithoutDataLoss() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "test_migration_1_to_2_${System.currentTimeMillis()}.db"
        val dbFile = app.getDatabasePath(dbName)
        if (dbFile.exists()) dbFile.delete()

        // 1. Create a Version 1 SQLite database manually with the exact v1 schema and insert a legacy row
        val sqliteDb = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `safety_events` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `eventGroupUuid` TEXT NOT NULL,
                `timestampMillis` INTEGER NOT NULL,
                `eventType` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `outcomeSummary` TEXT NOT NULL,
                `deliveryStatus` TEXT NOT NULL,
                `sensorDetails` TEXT NOT NULL,
                `isSyntheticTest` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            INSERT INTO `safety_events` (
                `eventGroupUuid`, `timestampMillis`, `eventType`, `title`,
                `outcomeSummary`, `deliveryStatus`, `sensorDetails`, `isSyntheticTest`
            ) VALUES (
                'uuid_legacy_v1_row', 1710000123456, 'POSSIBLE_FALL_DETECTED', 'Possible Fall Detected',
                'Legacy v1 event before ML upgrade', 'Sent', 'Peak: 64.2 m/s²', 0
            )
            """.trimIndent()
        )
        sqliteDb.version = 1
        sqliteDb.close()

        // 2. Open with Room at version 2 using EventHistoryDatabase.MIGRATION_1_2 (NO destructive fallback)
        val migratedRoomDb = androidx.room.Room.databaseBuilder(
            app,
            com.example.data.EventHistoryDatabase::class.java,
            dbName
        )
            .addMigrations(com.example.data.EventHistoryDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val rows = migratedRoomDb.eventLogDao().getRecentEventsSnapshot(limit = 10)
        assertEquals("Existing v1 history row must survive migration to v2 without data loss", 1, rows.size)
        val migratedRow = rows.first()
        assertEquals("Possible Fall Detected", migratedRow.title)
        assertEquals("Legacy v1 event before ML upgrade", migratedRow.outcomeSummary)
        assertEquals("Peak: 64.2 m/s²", migratedRow.sensorDetails)
        assertEquals("", migratedRow.modelVersion)
        assertNull("Legacy v1 row must have null modelConfidence after migration", migratedRow.modelConfidence)

        // 3. Insert a new v2 row with ML explainability metadata
        runBlocking {
            migratedRoomDb.eventLogDao().insertEvent(
                com.example.data.SafetyEventEntity(
                    timestampMillis = 1710000999999L,
                    eventType = "POSSIBLE_FALL_DETECTED",
                    title = "Fall detected",
                    outcomeSummary = "Hybrid 1D CNN check-in",
                    deliveryStatus = "Sent",
                    sensorDetails = "50.0 Hz resampled",
                    modelVersion = "solosignal_cnn_1d_v1",
                    predictedActivity = "POSSIBLE_FALL",
                    modelConfidence = 0.942f,
                    isUncertain = false,
                    explainabilityJson = """{"modelVersion":"solosignal_cnn_1d_v1","predictedCategory":"POSSIBLE_FALL","calibratedConfidence":0.942}"""
                )
            )
        }

        val updatedRows = migratedRoomDb.eventLogDao().getRecentEventsSnapshot(limit = 10)
        assertEquals(2, updatedRows.size)
        val newest = updatedRows.first()
        assertEquals("solosignal_cnn_1d_v1", newest.modelVersion)
        assertEquals("POSSIBLE_FALL", newest.predictedActivity)
        assertEquals(0.942f, newest.modelConfidence ?: 0f, 0.001f)

        migratedRoomDb.close()
        dbFile.delete()
    }
}

package com.example.sensor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.alerts.AlertDeliveryGateway
import com.example.alerts.AlertDeliveryResult
import com.example.alerts.AlertMessagePayload
import com.example.alerts.EmergencyLocationProvider
import com.example.alerts.GoogleChatAlertClient
import com.example.alerts.GoogleChatSendOutcome
import com.example.alerts.LocationCaptureOutcome
import com.example.alerts.TrustedContactVerificationClient
import com.example.data.AlertFirestoreRepository
import com.example.data.EventHistoryDatabase
import com.example.data.EventLogEntity
import com.example.data.PreferencesRepository
import com.example.data.SosAudioStorageUploadResult
import com.example.data.SosAudioStorageUploader
import com.example.data.UserPreferencesState
import com.example.domain.AlertChannelType
import com.example.domain.CandidateEventSummary
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GoogleChatConnectionStatus
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.MonitoringState
import com.example.domain.MotionFeatures
import com.example.domain.MotionSample
import com.example.domain.RecipientDeviceInfo
import com.example.domain.SafetyEventType
import com.example.domain.SosAudioClipMetadata
import com.example.domain.SosAudioStatus
import com.example.domain.SosWorkflowState
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.domain.TrustedContactVerificationStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Central singleton coordinator for SoloSignal AI:
 * - Motion sensor capture and candidate fall detection
 * - 15-second rolling in-memory SOS audio buffer (opt-in, microphone-permission gated)
 * - Check-in countdown and user confirmation
 * - Real GPS location capture and permission diagnostics
 * - Privacy-preserving Trusted Contact verification, invitation, and acceptance workflow
 * - Recipient FCM device registration and multi-device token management
 * - Two-device emergency alert dispatch (FCM push + Firestore + Firebase Storage + Email / Google Chat)
 */
class SafetyMonitoringController private constructor(
    private val appContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferencesRepository = PreferencesRepository(appContext)
    private val database = EventHistoryDatabase.getInstance(appContext)
    private val eventDao = database.eventLogDao()
    val alertDeliveryGateway = AlertDeliveryGateway(appContext)
    val locationProvider = EmergencyLocationProvider(appContext)
    val sosAudioRecorder = RollingSosAudioRecorder(appContext)
    val sosAudioStorageUploader = SosAudioStorageUploader()
    private val googleChatClient = GoogleChatAlertClient()
    private val firestoreRepository by lazy { AlertFirestoreRepository(appContext) }
    private val contactVerificationClient by lazy {
        TrustedContactVerificationClient(firestoreRepository)
    }

    private val sensorManager = SensorMonitorManager(appContext) { sample ->
        onSensorSample(sample)
    }
    private val detector = CandidateFallDetector()

    private var countdownJob: Job? = null
    private var replayJob: Job? = null
    private var senderAlertsObserverJob: Job? = null
    private var recipientAlertsObserverJob: Job? = null
    private var outgoingContactLinksJob: Job? = null
    private var incomingContactRequestsJob: Job? = null
    private var userDevicesObserverJob: Job? = null
    private var registeredContactObserverJob: Job? = null
    private var activeObservedUid: String? = null
    private var activeObservedContactEmail: String? = null

    private val notifiedIncomingAlertIds = ConcurrentHashMap.newKeySet<String>()
    private val loggedRecipientAckAlertIds = ConcurrentHashMap.newKeySet<String>()
    private val loggedSenderAckAlertIds = ConcurrentHashMap.newKeySet<String>()
    private val dispatchedEmergencyAlertIds = ConcurrentHashMap.newKeySet<String>()
    private val triggeredCandidateEventIds = ConcurrentHashMap.newKeySet<String>()
    private val cancelledCandidateEventIds = ConcurrentHashMap.newKeySet<String>()
    private var activeCheckInAlertId: String? = null

    private val _monitoringState = MutableStateFlow(MonitoringState.STOPPED)
    val monitoringState: StateFlow<MonitoringState> = _monitoringState.asStateFlow()

    private val _sosWorkflowState = MutableStateFlow(SosWorkflowState.IDLE)
    val sosWorkflowState: StateFlow<SosWorkflowState> = _sosWorkflowState.asStateFlow()

    private val _sosAudioStatus = MutableStateFlow(SosAudioStatus.DISABLED)
    val sosAudioStatus: StateFlow<SosAudioStatus> = _sosAudioStatus.asStateFlow()

    val sosAudioBufferedSeconds: StateFlow<Double> = sosAudioRecorder.bufferedDurationSeconds

    private val _lastCapturedAudioClip = MutableStateFlow<SosAudioClipMetadata?>(null)
    val lastCapturedAudioClip: StateFlow<SosAudioClipMetadata?> = _lastCapturedAudioClip.asStateFlow()

    private val _latestFeatures = MutableStateFlow(MotionFeatures())
    val latestFeatures: StateFlow<MotionFeatures> = _latestFeatures.asStateFlow()

    private val _recentMagnitudes = MutableStateFlow<List<Float>>(emptyList())
    val recentMagnitudes: StateFlow<List<Float>> = _recentMagnitudes.asStateFlow()

    private val _countdownRemaining = MutableStateFlow(15)
    val countdownRemaining: StateFlow<Int> = _countdownRemaining.asStateFlow()

    private val _activeCandidateSummary = MutableStateFlow<CandidateEventSummary?>(null)
    val activeCandidateSummary: StateFlow<CandidateEventSummary?> = _activeCandidateSummary.asStateFlow()

    private val _deliveryStatus = MutableStateFlow(DeliveryConfigStatus.NOT_CONFIGURED)
    val deliveryStatus: StateFlow<DeliveryConfigStatus> = _deliveryStatus.asStateFlow()

    private val _deliveryStatusDetail = MutableStateFlow(DeliveryConfigStatus.NOT_CONFIGURED.detail)
    val deliveryStatusDetail: StateFlow<String> = _deliveryStatusDetail.asStateFlow()

    private val _requiresGmailAuthorization = MutableStateFlow(false)
    val requiresGmailAuthorization: StateFlow<Boolean> = _requiresGmailAuthorization.asStateFlow()

    private val _lastMessagePreview = MutableStateFlow<AlertMessagePayload?>(null)
    val lastMessagePreview: StateFlow<AlertMessagePayload?> = _lastMessagePreview.asStateFlow()

    private val _latestLocationSnapshot = MutableStateFlow<GpsLocationSnapshot?>(null)
    val latestLocationSnapshot: StateFlow<GpsLocationSnapshot?> = _latestLocationSnapshot.asStateFlow()

    private val _locationDiagnostics = MutableStateFlow("Ready to capture real GPS coordinates when permitted")
    val locationDiagnostics: StateFlow<String> = _locationDiagnostics.asStateFlow()

    private val _isCapturingLocation = MutableStateFlow(false)
    val isCapturingLocation: StateFlow<Boolean> = _isCapturingLocation.asStateFlow()

    private val _incomingRecipientAlerts = MutableStateFlow<List<IncomingRecipientAlert>>(emptyList())
    val incomingRecipientAlerts: StateFlow<List<IncomingRecipientAlert>> = _incomingRecipientAlerts.asStateFlow()

    private val _activeRecipientEmergencyAlert = MutableStateFlow<IncomingRecipientAlert?>(null)
    val activeRecipientEmergencyAlert: StateFlow<IncomingRecipientAlert?> = _activeRecipientEmergencyAlert.asStateFlow()

    private val _incomingContactRequests = MutableStateFlow<List<TrustedContactRequestItem>>(emptyList())
    val incomingContactRequests: StateFlow<List<TrustedContactRequestItem>> = _incomingContactRequests.asStateFlow()

    private val _registeredDevices = MutableStateFlow<List<RecipientDeviceInfo>>(emptyList())
    val registeredDevices: StateFlow<List<RecipientDeviceInfo>> = _registeredDevices.asStateFlow()

    private val _fcmRegistrationStatus = MutableStateFlow("Checking device push notification registration...")
    val fcmRegistrationStatus: StateFlow<String> = _fcmRegistrationStatus.asStateFlow()

    private val _isVerifyingTrustedContact = MutableStateFlow(false)
    val isVerifyingTrustedContact: StateFlow<Boolean> = _isVerifyingTrustedContact.asStateFlow()

    private val _contactSyncError = MutableStateFlow<String?>(null)
    val contactSyncError: StateFlow<String?> = _contactSyncError.asStateFlow()

    private val _requiresInitialContactSetup = MutableStateFlow(false)
    val requiresInitialContactSetup: StateFlow<Boolean> = _requiresInitialContactSetup.asStateFlow()

    private val _preferencesState = MutableStateFlow(UserPreferencesState())
    val preferencesState: StateFlow<UserPreferencesState> = _preferencesState.asStateFlow()

    val eventLogsFlow: Flow<List<EventLogEntity>> = eventDao.observeAllEvents()
    val hasAccelerometer: Boolean = sensorManager.hasAccelerometer

    init {
        alertDeliveryGateway.ensureChannels()
        scope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                _preferencesState.value = prefs
                sosAudioRecorder.purgeExpiredLocalClips(prefs.sosAudioRetentionDays)
                syncSosAudioMonitoringWithPreferences(prefs)
                if (prefs.trustedContact.isValid || prefs.onboardingCompleted) {
                    _requiresInitialContactSetup.value = false
                }
                if (_monitoringState.value != MonitoringState.CHECK_IN &&
                    _monitoringState.value != MonitoringState.ALERT_PENDING &&
                    _monitoringState.value != MonitoringState.ALERT_SENT &&
                    _monitoringState.value != MonitoringState.ALERT_FAILED
                ) {
                    _countdownRemaining.value = prefs.countdownSeconds
                    updateIdleDeliveryStatus(prefs.trustedContact)
                }
                observeSavedContactRegistration(prefs.trustedContact)
            }
        }
        ensureAuthenticatedSessionInitialized()
    }

    /**
     * Ensures that if a user is already signed in when the process starts in the background
     * (e.g. via MonitoringForegroundService or FCM), real-time Firestore alert listeners are attached.
     */
    fun ensureAuthenticatedSessionInitialized() {
        val currentUser = try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        }
        if (currentUser != null && activeObservedUid != currentUser.uid) {
            onAuthenticatedUserChanged(currentUser.uid, currentUser.email)
        }
    }

    fun clearContactSyncError() {
        _contactSyncError.value = null
    }

    fun completeInitialContactSetup() {
        _requiresInitialContactSetup.value = false
        scope.launch {
            preferencesRepository.setOnboardingCompleted(true, activeObservedUid)
        }
    }

    private fun observeSavedContactRegistration(contact: TrustedContact) {
        val uid = activeObservedUid
        val email = contact.contactEmail.trim().lowercase()
        if (uid.isNullOrBlank() || !contact.isValid || !email.contains("@")) {
            activeObservedContactEmail = null
            registeredContactObserverJob?.cancel()
            return
        }
        if (activeObservedContactEmail == email && registeredContactObserverJob?.isActive == true) {
            return
        }
        activeObservedContactEmail = email
        registeredContactObserverJob?.cancel()
        registeredContactObserverJob = scope.launch {
            try {
                firestoreRepository.observeRegisteredContactByEmail(email).collect { reg ->
                    if (reg != null && reg.hasRegisteredAccount) {
                        val latest = _preferencesState.value.trustedContact
                        val needsRefresh = latest.contactEmail.trim().equals(email, ignoreCase = true) &&
                            (!latest.recipientHasRegisteredAccount ||
                                latest.recipientUid != reg.userId ||
                                latest.verificationStatus == TrustedContactVerificationStatus.INVITATION_NEEDED ||
                                latest.recipientRegisteredDeviceCount != reg.activeDeviceCount ||
                                latest.recipientNotificationsEnabled != reg.notificationsEnabled)
                        if (needsRefresh) {
                            verifyAndSaveTrustedContact(
                                contact = latest.copy(
                                    recipientHasRegisteredAccount = true,
                                    recipientUid = reg.userId.ifBlank { latest.recipientUid },
                                    recipientRegisteredDeviceCount = maxOf(latest.recipientRegisteredDeviceCount, reg.activeDeviceCount),
                                    recipientNotificationsEnabled = reg.notificationsEnabled || latest.recipientNotificationsEnabled
                                ),
                                sendPermissionRequest = latest.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING ||
                                    latest.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING,
                                markInvitationSent = false
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Synchronizes the signed-in user's profile, isolates emergency contacts strictly to [uid],
     * registers this Android device for FCM push notifications, observes outgoing/incoming
     * trusted-contact links, and listens for real-time emergency alerts.
     */
    fun onAuthenticatedUserChanged(uid: String?, email: String?) {
        if (uid.isNullOrBlank()) {
            activeObservedUid = null
            activeObservedContactEmail = null
            preferencesRepository.setActiveUser(null)
            senderAlertsObserverJob?.cancel()
            recipientAlertsObserverJob?.cancel()
            outgoingContactLinksJob?.cancel()
            incomingContactRequestsJob?.cancel()
            userDevicesObserverJob?.cancel()
            registeredContactObserverJob?.cancel()
            _preferencesState.value = UserPreferencesState()
            _requiresInitialContactSetup.value = false
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = DeliveryConfigStatus.NOT_CONFIGURED.detail
            _activeRecipientEmergencyAlert.value = null
            _incomingRecipientAlerts.value = emptyList()
            _incomingContactRequests.value = emptyList()
            _registeredDevices.value = emptyList()
            _contactSyncError.value = null
            return
        }
        if (activeObservedUid == uid) return
        activeObservedUid = uid

        // Switch PreferencesRepository to this authenticated user's isolated namespace immediately
        preferencesRepository.setActiveUser(uid)
        _activeRecipientEmergencyAlert.value = null
        _incomingRecipientAlerts.value = emptyList()
        _incomingContactRequests.value = emptyList()
        _contactSyncError.value = null

        // Keep background incoming emergency alert listener active while signed in
        MonitoringForegroundService.startAlertListenerOnly(appContext)

        // Retry any acknowledgements recorded while offline
        retryPendingAcknowledgements()

        // 0. Immediately publish current user's registration record so other phones recognize this account right away
        scope.launch {
            val notifEnabled = hasNotificationPermission()
            firestoreRepository.publishCurrentUserRegistration(
                activeDeviceCount = 1,
                notificationsEnabled = notifEnabled
            )
            if (!email.isNullOrBlank()) {
                firestoreRepository.syncIncomingLinksForRecipient(
                    recipientEmail = email,
                    activeDeviceCount = 1,
                    notificationsEnabled = notifEnabled
                )
            }
        }

        // 1. Register this device for FCM push notifications and sync recipient links
        refreshAndRegisterFcmDevice()

        // 2. Sync user profile & existing link record from Firestore (isolated to uid)
        scope.launch {
            val scopedPrefs = preferencesRepository.getPreferencesForUser(uid)
            if (activeObservedUid == uid) {
                _preferencesState.value = scopedPrefs
                updateIdleDeliveryStatus(scopedPrefs.trustedContact)
                if (!scopedPrefs.trustedContact.isValid && !scopedPrefs.onboardingCompleted) {
                    _requiresInitialContactSetup.value = true
                }
            }

            val profileRes = firestoreRepository.loadUserProfile()
            val remoteProfile = profileRes.getOrNull()
            val localContact = scopedPrefs.trustedContact
            val hasRemoteContact = remoteProfile != null &&
                (!remoteProfile.trustedContactEmail.isNullOrBlank() || !remoteProfile.trustedContactPhone.isNullOrBlank())

            if (remoteProfile != null && !localContact.isValid && hasRemoteContact) {
                val remoteEmail = remoteProfile.trustedContactEmail?.trim()?.lowercase().orEmpty()
                val existingLink = if (remoteEmail.isNotBlank()) {
                    firestoreRepository.getOutgoingContactLink(uid, remoteEmail).getOrNull()
                } else {
                    null
                }
                val channel = try {
                    AlertChannelType.valueOf(remoteProfile.preferredChannel ?: AlertChannelType.SOLOSIGNAL_POPUP.name)
                } catch (_: Exception) {
                    AlertChannelType.SOLOSIGNAL_POPUP
                }
                val verStatus = existingLink?.status ?: TrustedContactVerificationStatus.fromStorageKey(
                    remoteProfile.trustedContactVerificationStatus
                )
                val restored = TrustedContact(
                    userDisplayName = remoteProfile.displayName,
                    contactName = existingLink?.recipientName?.takeIf { it.isNotBlank() }
                        ?: remoteProfile.trustedContactName.orEmpty(),
                    contactEmail = remoteEmail,
                    contactPhone = existingLink?.recipientPhone?.takeIf { it.isNotBlank() }
                        ?: remoteProfile.trustedContactPhone.orEmpty(),
                    relationship = existingLink?.relationship?.takeIf { it.isNotBlank() }
                        ?: (remoteProfile.trustedContactRelationship ?: "Family"),
                    preferredChannel = channel,
                    consentGranted = true,
                    optionalLocationEnabled = remoteProfile.shareLocation ?: true,
                    phonePopUpAlertsEnabled = remoteProfile.popUpAlertsEnabled ?: true,
                    verificationStatus = verStatus,
                    verificationDetail = existingLink?.statusDetail?.takeIf { it.isNotBlank() }
                        ?: verStatus.defaultDetail,
                    recipientHasRegisteredAccount = existingLink?.recipientHasRegisteredAccount ?: false,
                    recipientAccepted = existingLink?.recipientAccepted ?: false,
                    recipientRegisteredDeviceCount = existingLink?.recipientRegisteredDeviceCount ?: 0,
                    recipientNotificationsEnabled = existingLink?.recipientNotificationsEnabled ?: false,
                    linkId = existingLink?.linkId?.takeIf { it.isNotBlank() }
                        ?: if (remoteEmail.isNotBlank()) firestoreRepository.buildLinkId(uid, remoteEmail) else "",
                    recipientUid = existingLink?.recipientUid.orEmpty()
                )
                if (restored.isValid) {
                    _requiresInitialContactSetup.value = false
                    preferencesRepository.setOnboardingCompleted(true, uid)
                    preferencesRepository.saveTrustedContact(restored, uid)
                    verifyAndSaveTrustedContact(
                        contact = restored,
                        sendPermissionRequest = verStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING,
                        markInvitationSent = verStatus == TrustedContactVerificationStatus.INVITATION_PENDING
                    )
                }
            } else if (localContact.isValid) {
                _requiresInitialContactSetup.value = false
                preferencesRepository.setOnboardingCompleted(true, uid)
                firestoreRepository.upsertUserProfile(localContact)
                verifyAndSaveTrustedContact(
                    contact = localContact,
                    sendPermissionRequest = localContact.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING,
                    markInvitationSent = localContact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING
                )
            } else {
                // New user with a blank account: ensure no stale contact exists and prompt setup
                if (!scopedPrefs.onboardingCompleted) {
                    _requiresInitialContactSetup.value = true
                }
                updateIdleDeliveryStatus(TrustedContact())
            }
            observeSavedContactRegistration(_preferencesState.value.trustedContact)
        }

        // 3. Observe user's registered FCM devices
        userDevicesObserverJob?.cancel()
        userDevicesObserverJob = scope.launch {
            try {
                firestoreRepository.observeUserRegisteredDevices(uid).collect { devices ->
                    _registeredDevices.value = devices
                }
            } catch (_: Exception) {
            }
        }

        // 4. Observe outgoing trusted-contact links owned by this user (senderUid == uid)
        outgoingContactLinksJob?.cancel()
        outgoingContactLinksJob = scope.launch {
            try {
                firestoreRepository.observeOutgoingContactLinks(uid).collect { links ->
                    val currentContact = _preferencesState.value.trustedContact
                    val ownedLinks = links.filter { it.senderUid == uid }
                    val matching = ownedLinks.firstOrNull {
                        it.recipientEmail.equals(currentContact.contactEmail.trim(), ignoreCase = true)
                    } ?: ownedLinks.firstOrNull()

                    if (matching != null && currentContact.isValid &&
                        currentContact.contactEmail.equals(matching.recipientEmail, ignoreCase = true)
                    ) {
                        val updated = currentContact.copy(
                            verificationStatus = matching.status,
                            verificationDetail = matching.statusDetail,
                            recipientHasRegisteredAccount = matching.recipientHasRegisteredAccount,
                            recipientAccepted = matching.recipientAccepted,
                            recipientRegisteredDeviceCount = matching.recipientRegisteredDeviceCount,
                            recipientNotificationsEnabled = matching.recipientNotificationsEnabled,
                            linkId = matching.linkId,
                            recipientUid = matching.recipientUid.orEmpty()
                        )
                        if (updated != currentContact) {
                            _contactSyncError.value = null
                            preferencesRepository.saveTrustedContact(updated, uid)
                            updateIdleDeliveryStatus(updated)
                        }
                    } else if (matching != null && !currentContact.isValid &&
                        matching.recipientName.isNotBlank() && matching.recipientEmail.isNotBlank()
                    ) {
                        // Restore existing user's saved outgoing link if local cache was empty
                        val restoredFromLink = TrustedContact(
                            userDisplayName = matching.senderName,
                            contactName = matching.recipientName,
                            contactEmail = matching.recipientEmail,
                            contactPhone = matching.recipientPhone,
                            relationship = matching.relationship.ifBlank { "Family" },
                            preferredChannel = AlertChannelType.SOLOSIGNAL_POPUP,
                            consentGranted = true,
                            verificationStatus = matching.status,
                            verificationDetail = matching.statusDetail,
                            recipientHasRegisteredAccount = matching.recipientHasRegisteredAccount,
                            recipientAccepted = matching.recipientAccepted,
                            recipientRegisteredDeviceCount = matching.recipientRegisteredDeviceCount,
                            recipientNotificationsEnabled = matching.recipientNotificationsEnabled,
                            linkId = matching.linkId,
                            recipientUid = matching.recipientUid.orEmpty()
                        )
                        if (restoredFromLink.isValid) {
                            _requiresInitialContactSetup.value = false
                            preferencesRepository.setOnboardingCompleted(true, uid)
                            preferencesRepository.saveTrustedContact(restoredFromLink, uid)
                            updateIdleDeliveryStatus(restoredFromLink)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 5. Observe incoming trusted-contact requests addressed to this user's email and sync registration state
        incomingContactRequestsJob?.cancel()
        if (!email.isNullOrBlank()) {
            incomingContactRequestsJob = scope.launch {
                try {
                    firestoreRepository.observeIncomingContactRequests(email).collect { requests ->
                        _incomingContactRequests.value = requests
                        val activeCount = _registeredDevices.value.size.coerceAtLeast(1)
                        val notifEnabled = hasNotificationPermission()
                        val needsSync = requests.any { req ->
                            !req.recipientHasRegisteredAccount ||
                                req.recipientUid != uid ||
                                req.recipientRegisteredDeviceCount != activeCount ||
                                req.status == TrustedContactVerificationStatus.INVITATION_NEEDED
                        }
                        if (needsSync) {
                            firestoreRepository.syncIncomingLinksForRecipient(
                                recipientEmail = email,
                                activeDeviceCount = activeCount,
                                notificationsEnabled = notifEnabled
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 6. Observe sender's own dispatched alerts so sender sees real-time recipient delivery & acknowledgment
        senderAlertsObserverJob?.cancel()
        senderAlertsObserverJob = scope.launch {
            try {
                firestoreRepository.observeSenderAlerts(uid).collect { alerts ->
                    val now = System.currentTimeMillis()
                    val latest = alerts.firstOrNull() ?: return@collect
                    val createdMillis = (latest.createdAt ?: latest.updatedAt)?.toDate()?.time ?: now
                    val isRecentSenderAlert = (now - createdMillis) < ACTIVE_ALERT_MAX_AGE_MILLIS
                    if (!isRecentSenderAlert) return@collect

                    Log.d(TAG, "observeSenderAlerts update: docId=${latest.id}, deliveryState=${latest.deliveryState}")
                    when (latest.deliveryState) {
                        AlertFirestoreRepository.STATE_REQUEST_ACCEPTED -> {
                            _monitoringState.value = MonitoringState.ALERT_SENT
                            _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                            _deliveryStatus.value = DeliveryConfigStatus.REQUEST_ACCEPTED
                            _deliveryStatusDetail.value = latest.deliveryStatusDetail.ifBlank {
                                DeliveryConfigStatus.REQUEST_ACCEPTED.detail
                            }
                        }
                        AlertFirestoreRepository.STATE_EMAIL_SENT -> {
                            _monitoringState.value = MonitoringState.ALERT_SENT
                            _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                            _deliveryStatus.value = DeliveryConfigStatus.EMAIL_SENT
                            _deliveryStatusDetail.value = latest.deliveryStatusDetail.ifBlank {
                                DeliveryConfigStatus.EMAIL_SENT.detail
                            }
                            _requiresGmailAuthorization.value = false
                        }
                        AlertFirestoreRepository.STATE_EMAIL_FAILED -> {
                            // If the alert was accepted in Firestore, the primary alert is sent.
                            // Only update detail note without marking the overall alert as failed.
                            _deliveryStatusDetail.value = latest.deliveryStatusDetail.ifBlank {
                                DeliveryConfigStatus.EMAIL_FAILED.detail
                            }
                        }
                        AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT -> {
                            _monitoringState.value = MonitoringState.ALERT_SENT
                            _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                            _deliveryStatus.value = DeliveryConfigStatus.DELIVERED_TO_RECIPIENT
                            _deliveryStatusDetail.value = latest.deliveryStatusDetail.ifBlank {
                                DeliveryConfigStatus.DELIVERED_TO_RECIPIENT.detail
                            }
                        }
                        AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT -> {
                            _deliveryStatus.value = DeliveryConfigStatus.ACKNOWLEDGED_BY_RECIPIENT
                            _deliveryStatusDetail.value = "Contact is checking on you"
                            val ackAlertId = latest.alertId.orEmpty().ifBlank { latest.id }
                            if (ackAlertId.isNotBlank() && loggedSenderAckAlertIds.add(ackAlertId)) {
                                logEvent(
                                    type = SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED,
                                    summary = "Contact is checking on you (${latest.recipientName ?: latest.recipientEmail ?: "Emergency contact"} acknowledged alert)",
                                    channel = AlertChannelType.SOLOSIGNAL_POPUP.label,
                                    deliveryOutcome = "Contact is checking on you"
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 7. Observe incoming emergency alerts where this user is the Trusted Contact recipient (by email OR recipientUid)
        recipientAlertsObserverJob?.cancel()
        recipientAlertsObserverJob = scope.launch {
            try {
                val flowToCollect = if (!email.isNullOrBlank()) {
                    combine(
                        firestoreRepository.observeRecipientAlertsByEmail(email),
                        firestoreRepository.observeRecipientAlertsByUid(uid)
                    ) { byEmail, byUid ->
                        (byEmail + byUid)
                            .distinctBy { it.id.ifBlank { it.alertId.orEmpty() } }
                            .sortedByDescending { it.createdAt?.toDate()?.time ?: 0L }
                    }
                } else {
                    firestoreRepository.observeRecipientAlertsByUid(uid)
                }

                flowToCollect.collect { alerts ->
                    val now = System.currentTimeMillis()
                    val incomingList = alerts.mapNotNull { doc ->
                        val mapped = doc.toIncomingRecipientAlert()
                        val isRecent = (now - mapped.createdAtMillis) < ACTIVE_ALERT_MAX_AGE_MILLIS
                        val isAcknowledged =
                            mapped.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                                alertDeliveryGateway.isAlertAcknowledged(mapped.alertId)

                        when {
                            isAcknowledged -> mapped.copy(
                                deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                                deliveryStatusDetail = "Contact is checking on you"
                            )
                            isRecent -> mapped
                            else -> null // Ignore stale unacknowledged records from old database sessions
                        }
                    }
                    _incomingRecipientAlerts.value = incomingList

                    // Update active emergency screen alert if open
                    val currentActive = _activeRecipientEmergencyAlert.value
                    if (currentActive != null) {
                        val refreshed = incomingList.firstOrNull { it.alertId == currentActive.alertId }
                        if (refreshed != null) {
                            _activeRecipientEmergencyAlert.value = refreshed
                            if (refreshed.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT) {
                                alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification(refreshed.alertId)
                            }
                        }
                    }

                    // Trigger immediate navigation + high-priority pop-up notification for new unacknowledged alerts
                    for (incoming in incomingList) {
                        val isRecent = (now - incoming.createdAtMillis) < ACTIVE_ALERT_MAX_AGE_MILLIS
                        val isAck = incoming.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                            alertDeliveryGateway.isAlertAcknowledged(incoming.alertId)
                        if (incoming.alertId.isNotBlank() && isRecent && !isAck) {
                            onIncomingEmergencyAlertReceived(incoming)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Handles a new incoming emergency alert from either the real-time Firestore listener or
     * Firebase Cloud Messaging (`SoloSignalFirebaseMessagingService`):
     * - Deduplicates by `incoming.alertId` so duplicate snapshots/messages do not re-trigger sound or duplicate screens.
     * - If the alert is already acknowledged locally or remotely, ensures the alarm and notification are stopped and never restarts them.
     * - Immediately updates `_activeRecipientEmergencyAlert` so if SoloSignal AI is open, it navigates
     *   directly to `RecipientEmergencyAlertScreen`.
     * - Posts the high-priority emergency notification with urgent sound, vibration, `fullScreenIntent`,
     *   and the `"View Emergency"` action button for background / locked-screen states.
     */
    fun onIncomingEmergencyAlertReceived(incoming: IncomingRecipientAlert): Boolean {
        val alertId = incoming.alertId.trim()
        if (alertId.isBlank()) return false

        val isAcknowledged =
            incoming.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                alertDeliveryGateway.isAlertAcknowledged(alertId)

        val effectiveIncoming = if (isAcknowledged &&
            incoming.deliveryState != AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT
        ) {
            incoming.copy(
                deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                deliveryStatusDetail = "Contact is checking on you"
            )
        } else {
            incoming
        }

        // Keep incoming alerts list up to date
        val currentList = _incomingRecipientAlerts.value
        val existingIdx = currentList.indexOfFirst { it.alertId == alertId }
        if (existingIdx >= 0) {
            val mutable = currentList.toMutableList()
            mutable[existingIdx] = effectiveIncoming
            _incomingRecipientAlerts.value = mutable
        } else {
            _incomingRecipientAlerts.value = listOf(effectiveIncoming) + currentList
        }

        // Never start or re-trigger the alarm or notification for an acknowledged alert
        if (isAcknowledged) {
            alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification(alertId)
            notifiedIncomingAlertIds.add(alertId)
            if (_activeRecipientEmergencyAlert.value?.alertId == alertId) {
                _activeRecipientEmergencyAlert.value = effectiveIncoming
            }
            return false
        }

        if (!notifiedIncomingAlertIds.add(alertId) ||
            alertDeliveryGateway.didPostNotificationForAlert(alertId)
        ) {
            if (_activeRecipientEmergencyAlert.value?.alertId == alertId) {
                _activeRecipientEmergencyAlert.value = effectiveIncoming
            }
            return false
        }

        // Immediately navigate to the emergency alert screen when the app is open
        _activeRecipientEmergencyAlert.value = effectiveIncoming

        val posted = alertDeliveryGateway.postIncomingTrustedContactPopUpNotification(effectiveIncoming)
        scope.launch {
            if (effectiveIncoming.deliveryState == AlertFirestoreRepository.STATE_REQUEST_ACCEPTED ||
                effectiveIncoming.deliveryState == AlertFirestoreRepository.STATE_SENT_BY_PROVIDER ||
                effectiveIncoming.deliveryState == AlertFirestoreRepository.STATE_EMAIL_SENT
            ) {
                firestoreRepository.confirmRecipientPhonePopUpDelivery(
                    alertId = alertId,
                    statusDetail = if (posted) {
                        "High-priority phone pop-up alert displayed on ${effectiveIncoming.recipientEmail.ifBlank { "recipient" }}'s device."
                    } else {
                        "Alert received on ${effectiveIncoming.recipientEmail.ifBlank { "recipient" }}'s device (notification permission off)."
                    }
                )
            }
            logEvent(
                type = SafetyEventType.RECIPIENT_ALERT_RECEIVED,
                summary = "SOS ALERT — ${effectiveIncoming.senderName} needs help",
                channel = AlertChannelType.SOLOSIGNAL_POPUP.label,
                deliveryOutcome = effectiveIncoming.locationSummary.ifBlank { "Delivered to device" }
            )
        }
        return true
    }

    fun onSenderMeetFallbackReceived(
        alertId: String,
        recipientName: String,
        escalationStatus: String,
        meetUrl: String?,
        meetError: String?
    ) {
        if (alertId.isBlank()) return
        val detail = when {
            !meetUrl.isNullOrBlank() ->
                "No response from $recipientName after 15s — Emergency Google Meet link created: $meetUrl"
            !meetError.isNullOrBlank() ->
                "Escalation check for $recipientName ($escalationStatus): $meetError"
            else ->
                "Escalation check completed for $recipientName ($escalationStatus)."
        }
        _deliveryStatusDetail.value = detail
    }

    /**
     * Fetches the current Firebase Cloud Messaging token, registers this device in the user's
     * private `/users/{uid}/devices/{deviceId}` subcollection, and updates any incoming
     * trusted-contact links so senders know this recipient device is ready for push alerts.
     */
    fun refreshAndRegisterFcmDevice() {
        scope.launch {
            val currentUser = try {
                Firebase.auth.currentUser
            } catch (_: Exception) {
                null
            }
            if (currentUser == null) {
                _fcmRegistrationStatus.value = "Sign in to register this device for emergency push alerts."
                return@launch
            }

            val notificationsEnabled = hasNotificationPermission()
            val deviceId = preferencesRepository.getOrCreateDeviceInstallationId()

            val fcmToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (_: Exception) {
                val cached = _preferencesState.value.fcmToken
                if (cached.isNotBlank()) cached else "fcm_token_${deviceId}"
            }

            if (fcmToken.isNotBlank()) {
                preferencesRepository.saveFcmToken(fcmToken)
            }

            val regResult = firestoreRepository.registerOrUpdateRecipientDevice(
                deviceId = deviceId,
                fcmToken = fcmToken,
                notificationsEnabled = notificationsEnabled
            )

            regResult.onSuccess { (activeCount, anyNotifEnabled) ->
                _fcmRegistrationStatus.value = if (notificationsEnabled) {
                    "Device registered for FCM push alerts ($activeCount active device(s) on your account)."
                } else {
                    "Device registered with FCM, but Android notification permission is disabled. Enable notifications in Android Settings to receive pop-up alerts."
                }

                val email = currentUser.email?.trim()?.lowercase().orEmpty()
                if (email.isNotBlank()) {
                    firestoreRepository.syncIncomingLinksForRecipient(
                        recipientEmail = email,
                        activeDeviceCount = activeCount,
                        notificationsEnabled = anyNotifEnabled
                    )
                }
            }.onFailure { err ->
                _fcmRegistrationStatus.value =
                    "Could not verify cloud device registration (${err.localizedMessage ?: "check connection"})."
            }
        }
    }

    /**
     * Verifies whether a trusted contact's email is associated with a registered SoloSignal account
     * and creates/updates the contact authorization request without duplicate records.
     */
    fun verifyAndSaveTrustedContact(
        contact: TrustedContact,
        sendPermissionRequest: Boolean = false,
        markInvitationSent: Boolean = false,
        onComplete: ((TrustedContact) -> Unit)? = null
    ) {
        scope.launch {
            _isVerifyingTrustedContact.value = true
            try {
                val previousContact = _preferencesState.value.trustedContact
                val previousEmail = previousContact.contactEmail.trim().lowercase()
                val newEmail = contact.contactEmail.trim().lowercase()
                val uid = try {
                    Firebase.auth.currentUser?.uid.orEmpty()
                } catch (_: Exception) {
                    ""
                }.ifBlank { activeObservedUid.orEmpty() }

                // Avoid duplicate outgoing contact records if the user replaced a previous email address
                if (uid.isNotBlank() &&
                    previousEmail.isNotBlank() &&
                    newEmail.isNotBlank() &&
                    previousEmail != newEmail
                ) {
                    val oldLinkId = previousContact.linkId.ifBlank {
                        firestoreRepository.buildLinkId(uid, previousEmail)
                    }
                    try {
                        firestoreRepository.revokeAndDeleteTrustedContactLink(oldLinkId)
                    } catch (_: Exception) {
                    }
                }

                val localDeviceCount = _registeredDevices.value.size.coerceAtLeast(1)
                val localNotifEnabled = hasNotificationPermission()

                val verifyResult = contactVerificationClient.verifyOrRequestContact(
                    contact = contact,
                    sendPermissionRequest = sendPermissionRequest,
                    markInvitationSent = markInvitationSent,
                    localDeviceCount = localDeviceCount,
                    localNotificationsEnabled = localNotifEnabled
                )

                val updatedContact = verifyResult.fold(
                    onSuccess = { linkItem ->
                        _contactSyncError.value = null
                        contact.copy(
                            verificationStatus = linkItem.status,
                            verificationDetail = linkItem.statusDetail,
                            recipientHasRegisteredAccount = linkItem.recipientHasRegisteredAccount,
                            recipientAccepted = linkItem.recipientAccepted,
                            recipientRegisteredDeviceCount = linkItem.recipientRegisteredDeviceCount,
                            recipientNotificationsEnabled = linkItem.recipientNotificationsEnabled,
                            linkId = linkItem.linkId,
                            recipientUid = linkItem.recipientUid.orEmpty()
                        )
                    },
                    onFailure = { err ->
                        val errorMsg = "Account lookup or sync failed: ${err.localizedMessage ?: "check your connection and tap Refresh"}."
                        _contactSyncError.value = errorMsg
                        val fallbackStatus = when {
                            contact.recipientAccepted -> contact.verificationStatus
                            contact.recipientHasRegisteredAccount -> if (sendPermissionRequest) {
                                TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING
                            } else {
                                TrustedContactVerificationStatus.REGISTERED
                            }
                            markInvitationSent || contact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING ->
                                TrustedContactVerificationStatus.INVITATION_PENDING
                            else -> contact.verificationStatus
                        }
                        contact.copy(
                            verificationStatus = fallbackStatus,
                            verificationDetail = errorMsg
                        )
                    }
                )

                preferencesRepository.saveTrustedContact(updatedContact, uid.ifBlank { null })
                preferencesRepository.setOnboardingCompleted(true, uid.ifBlank { null })
                firestoreRepository.upsertUserProfile(updatedContact)
                updateIdleDeliveryStatus(updatedContact)
                observeSavedContactRegistration(updatedContact)

                logEvent(
                    type = SafetyEventType.TRUSTED_CONTACT_VERIFIED,
                    summary = "Trusted contact ${updatedContact.contactName} (${updatedContact.contactEmail}): ${updatedContact.verificationStatus.label}",
                    channel = updatedContact.preferredChannel.label,
                    deliveryOutcome = updatedContact.verificationDetail
                )

                onComplete?.invoke(updatedContact)
            } finally {
                _isVerifyingTrustedContact.value = false
            }
        }
    }

    /**
     * Called when the signed-in user accepts an incoming trusted-contact request from another user.
     */
    fun acceptIncomingContactRequest(linkId: String) {
        if (linkId.isBlank()) return
        scope.launch {
            val activeCount = _registeredDevices.value.size.coerceAtLeast(1)
            val notifEnabled = hasNotificationPermission()
            refreshAndRegisterFcmDevice()
            firestoreRepository.acceptIncomingContactRequest(
                linkId = linkId,
                activeDeviceCount = activeCount,
                notificationsEnabled = notifEnabled
            )
            logEvent(
                type = SafetyEventType.TRUSTED_CONTACT_VERIFIED,
                summary = "Accepted incoming trusted-contact request ($linkId)",
                channel = AlertChannelType.SOLOSIGNAL_POPUP.label,
                deliveryOutcome = if (notifEnabled) "Ready for emergency push alerts" else "Accepted (enable notifications)"
            )
        }
    }

    /**
     * Removes the saved trusted contact and revokes the associated alert authorization in Firestore.
     */
    fun removeTrustedContactAndRevoke() {
        scope.launch {
            val current = _preferencesState.value.trustedContact
            val uid = try {
                Firebase.auth.currentUser?.uid.orEmpty()
            } catch (_: Exception) {
                ""
            }.ifBlank { activeObservedUid.orEmpty() }
            val linkId = current.linkId.ifBlank {
                if (uid.isNotBlank() && current.contactEmail.isNotBlank()) {
                    firestoreRepository.buildLinkId(uid, current.contactEmail)
                } else {
                    ""
                }
            }
            preferencesRepository.deleteTrustedContact(uid.ifBlank { null })
            firestoreRepository.deleteUserProfileContact()
            if (linkId.isNotBlank()) {
                firestoreRepository.revokeAndDeleteTrustedContactLink(linkId)
            }
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = DeliveryConfigStatus.NOT_CONFIGURED.detail
        }
    }

    /**
     * Opens the full-screen Recipient Emergency Alert view for a specific incoming alert,
     * or loads it from Firestore if opened from a background FCM notification tap.
     */
    fun openRecipientEmergencyAlert(
        alert: IncomingRecipientAlert?,
        fallbackAlertId: String? = null
    ) {
        val targetId = fallbackAlertId?.takeIf { it.isNotBlank() } ?: alert?.alertId
        if (alert != null) {
            val isAck = alert.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                alertDeliveryGateway.isAlertAcknowledged(alert.alertId)
            _activeRecipientEmergencyAlert.value = if (isAck) {
                alert.copy(
                    deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                    deliveryStatusDetail = "Contact is checking on you"
                )
            } else {
                alert
            }
        }
        if (!targetId.isNullOrBlank()) {
            scope.launch {
                val existing = _incomingRecipientAlerts.value.firstOrNull { it.alertId == targetId }
                if (existing != null) {
                    val isAck = existing.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                        alertDeliveryGateway.isAlertAcknowledged(existing.alertId)
                    _activeRecipientEmergencyAlert.value = if (isAck) {
                        existing.copy(
                            deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                            deliveryStatusDetail = "Contact is checking on you"
                        )
                    } else {
                        existing
                    }
                } else {
                    val fetched = firestoreRepository.getAlertById(targetId).getOrNull()
                    if (fetched != null) {
                        val mapped = fetched.toIncomingRecipientAlert()
                        val isAck = mapped.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
                            alertDeliveryGateway.isAlertAcknowledged(mapped.alertId)
                        _activeRecipientEmergencyAlert.value = if (isAck) {
                            mapped.copy(
                                deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                                deliveryStatusDetail = "Contact is checking on you"
                            )
                        } else {
                            mapped
                        }
                    }
                }
            }
        }
    }

    fun dismissActiveRecipientEmergencyAlert() {
        val currentAlertId = _activeRecipientEmergencyAlert.value?.alertId
        if (!currentAlertId.isNullOrBlank()) {
            alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification(currentAlertId)
        } else {
            alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification()
        }
        _activeRecipientEmergencyAlert.value = null
    }

    fun testEmergencyBuzzer(durationMillis: Long = 5000L): Boolean {
        return alertDeliveryGateway.testEmergencyBuzzer(durationMillis)
    }

    fun stopEmergencyBuzzerTest() {
        alertDeliveryGateway.stopEmergencyBuzzerTest()
    }

    fun isEmergencyBuzzerPlaying(): Boolean {
        return alertDeliveryGateway.isAlarmPlaying()
    }

    /**
     * Called when the recipient taps "I'm checking on them" on an incoming alert screen, banner, or notification.
     * Immediately and synchronously:
     * 1. Stops the currently playing emergency alarm Ringtone.
     * 2. Cancels all active vibration.
     * 3. Cancels/dismisses the active emergency notification.
     * 4. Marks the alert (and any other unacknowledged incoming alerts in the active list) as acknowledged
     *    locally in memory + SharedPreferences so the Home screen alert banner is removed immediately and cannot reappear.
     * 5. Updates the UI state and Activity History immediately with "Contact is checking on you", and syncs
     *    the acknowledgement to Firestore (with offline persistence & retry) so the sender sees "Contact is checking on you".
     */
    fun acknowledgeIncomingAlert(alertId: String) {
        val cleanId = alertId.trim()
        if (cleanId.isBlank()) return

        // Collect all currently unacknowledged alert IDs so acknowledging clears the active banner completely
        val alertIdsToAcknowledge = LinkedHashSet<String>().apply {
            add(cleanId)
            _incomingRecipientAlerts.value.forEach { item ->
                if (item.alertId.isNotBlank() &&
                    item.deliveryState != AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT
                ) {
                    add(item.alertId.trim())
                }
            }
        }

        // 1-4. Immediately stop alarm sound, cancel vibration, dismiss notifications, and persist local ack
        for (id in alertIdsToAcknowledge) {
            alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification(id)
            notifiedIncomingAlertIds.add(id)
            AlertDeliveryGateway.enqueuePendingFirestoreAcknowledgement(appContext, id)
        }

        // 5. Immediately update in-memory active alert and incoming alerts list
        val currentActive = _activeRecipientEmergencyAlert.value
        val senderName = currentActive?.takeIf { it.alertId == cleanId }?.senderName
            ?: _incomingRecipientAlerts.value.firstOrNull { it.alertId == cleanId }?.senderName
            ?: _incomingRecipientAlerts.value.firstOrNull()?.senderName
            ?: "Emergency contact"

        if (currentActive != null && alertIdsToAcknowledge.contains(currentActive.alertId)) {
            _activeRecipientEmergencyAlert.value = currentActive.copy(
                deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                deliveryStatusDetail = "Contact is checking on you"
            )
        }

        _incomingRecipientAlerts.value = _incomingRecipientAlerts.value.map { item ->
            if (alertIdsToAcknowledge.contains(item.alertId)) {
                item.copy(
                    deliveryState = AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT,
                    deliveryStatusDetail = "Contact is checking on you"
                )
            } else {
                item
            }
        }

        if (loggedRecipientAckAlertIds.add(cleanId)) {
            logEvent(
                type = SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED,
                summary = "Contact is checking on you — acknowledged alert for $senderName",
                channel = AlertChannelType.SOLOSIGNAL_POPUP.label,
                deliveryOutcome = "Contact is checking on you"
            )
        }

        scope.launch {
            for (id in alertIdsToAcknowledge) {
                syncAcknowledgementToFirestoreWithRetry(id)
            }
        }
    }

    private suspend fun syncAcknowledgementToFirestoreWithRetry(alertId: String) {
        val isFirebaseInitialized = try {
            com.google.firebase.FirebaseApp.getApps(appContext).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
        if (!isFirebaseInitialized) return

        val detail = "Contact is checking on you"
        for (attempt in 0 until 3) {
            val res = try {
                firestoreRepository.acknowledgeAlertAsRecipient(
                    alertId = alertId,
                    statusDetail = detail
                )
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (res.isSuccess) {
                AlertDeliveryGateway.markPendingFirestoreAcknowledgementSynced(appContext, alertId)
                return
            }
            delay(1500L * (attempt + 1))
        }
    }

    private fun retryPendingAcknowledgements() {
        val pending = AlertDeliveryGateway.getPendingFirestoreAcknowledgements(appContext)
        if (pending.isEmpty()) return
        scope.launch {
            for (alertId in pending) {
                syncAcknowledgementToFirestoreWithRetry(alertId)
            }
        }
    }

    /**
     * Unregisters the current device's FCM token on sign-out so alerts are not sent to signed-out accounts.
     */
    suspend fun unregisterDeviceOnSignOut() {
        try {
            val deviceId = preferencesRepository.getOrCreateDeviceInstallationId()
            firestoreRepository.unregisterRecipientDevice(deviceId)
        } catch (_: Exception) {
        }
    }

    fun hasNotificationPermission(): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return runtimeGranted && NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    }

    private fun updateIdleDeliveryStatus(contact: TrustedContact) {
        if (!contact.isValid) {
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = DeliveryConfigStatus.NOT_CONFIGURED.detail
            return
        }

        _deliveryStatus.value = DeliveryConfigStatus.READY_TO_TEST
        _deliveryStatusDetail.value = when {
            contact.isReadyForPushAlerts ->
                "Ready for alerts: ${contact.contactName} (${contact.contactEmail}) is verified with ${contact.recipientRegisteredDeviceCount} active device(s)."
            contact.preferredChannel == AlertChannelType.LOCAL_ONLY ->
                "Local device notification mode active (external alerts disabled)."
            else ->
                "Contact status: ${contact.verificationStatus.label}. ${contact.verificationDetail}"
        }
    }

    /**
     * Proactively captures a real GPS fix and updates the UI state so the user can verify
     * their real-time coordinates and Google Maps link before an emergency.
     */
    fun refreshCurrentGpsLocation() {
        if (_isCapturingLocation.value) return
        scope.launch {
            _isCapturingLocation.value = true
            _locationDiagnostics.value = "Requesting live GPS fix from device FusedLocationProvider / LocationManager..."
            when (val outcome = locationProvider.captureCurrentLocation( enrichWithGeminiMapsContext = true)) {
                is LocationCaptureOutcome.Success -> {
                    _latestLocationSnapshot.value = outcome.snapshot
                    _locationDiagnostics.value =
                        "Live GPS fix acquired (${outcome.snapshot.provider}): ${outcome.snapshot.formattedCoordinates}"
                }
                is LocationCaptureOutcome.Unavailable -> {
                    _locationDiagnostics.value = outcome.reason
                }
            }
            _isCapturingLocation.value = false
        }
    }

    fun isSosAudioMonitoringActive(): Boolean {
        return sosAudioRecorder.isBufferingActive()
    }

    fun hasMicrophonePermission(): Boolean {
        return sosAudioRecorder.hasMicrophonePermission()
    }

    private fun syncSosAudioMonitoringWithPreferences(prefs: UserPreferencesState) {
        val activeWorkflow = _sosWorkflowState.value
        val inActiveAlertPipeline = activeWorkflow == SosWorkflowState.AUDIO_FINALIZING ||
            activeWorkflow == SosWorkflowState.UPLOADING_AUDIO ||
            activeWorkflow == SosWorkflowState.SENDING_EMAIL

        if (!prefs.sosAudioEnabled || !prefs.sosAudioConsentGranted) {
            if (sosAudioRecorder.isBufferingActive()) {
                sosAudioRecorder.stopBufferingAndClear()
            }
            if (!inActiveAlertPipeline) {
                _sosAudioStatus.value = SosAudioStatus.DISABLED
            }
            return
        }

        if (!sosAudioRecorder.hasMicrophonePermission()) {
            if (sosAudioRecorder.isBufferingActive()) {
                sosAudioRecorder.stopBufferingAndClear()
            }
            if (!inActiveAlertPipeline) {
                _sosAudioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
            }
            return
        }

        val currentMonState = _monitoringState.value
        if (currentMonState == MonitoringState.MONITORING ||
            currentMonState == MonitoringState.CHECK_IN ||
            currentMonState == MonitoringState.CANDIDATE_EVENT
        ) {
            if (!sosAudioRecorder.isBufferingActive()) {
                val startedStatus = sosAudioRecorder.startRollingBuffer()
                if (!inActiveAlertPipeline) {
                    _sosAudioStatus.value = startedStatus
                }
            } else if (!inActiveAlertPipeline) {
                _sosAudioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
            }
        } else {
            if (sosAudioRecorder.isBufferingActive()) {
                sosAudioRecorder.stopBufferingAndClear()
            }
            if (!inActiveAlertPipeline) {
                _sosAudioStatus.value = SosAudioStatus.AUDIO_MONITORING_ENABLED
            }
        }
    }

    fun setSosAudioEnabled(enabled: Boolean, consentGranted: Boolean = enabled) {
        scope.launch {
            preferencesRepository.setSosAudioEnabled(
                enabled = enabled,
                consentGranted = consentGranted,
                uidOverride = activeObservedUid
            )
            val updatedPrefs = _preferencesState.value.copy(
                sosAudioEnabled = enabled,
                sosAudioConsentGranted = if (enabled) consentGranted else false,
                trustedContact = _preferencesState.value.trustedContact.copy(
                    sosAudioEnabled = enabled,
                    sosAudioConsentGranted = if (enabled) consentGranted else false
                )
            )
            _preferencesState.value = updatedPrefs
            syncSosAudioMonitoringWithPreferences(updatedPrefs)
            if (_monitoringState.value == MonitoringState.MONITORING) {
                MonitoringForegroundService.start(appContext)
            }
            if (updatedPrefs.trustedContact.isValid) {
                firestoreRepository.upsertUserProfile(updatedPrefs.trustedContact)
            }
        }
    }

    fun setSosAudioRetentionDays(days: Int) {
        scope.launch {
            val coerced = days.coerceIn(1, 30)
            preferencesRepository.setSosAudioRetentionDays(coerced, activeObservedUid)
            sosAudioRecorder.purgeExpiredLocalClips(coerced)
        }
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        val currentPrefs = _preferencesState.value
        if (!granted && currentPrefs.sosAudioEnabled) {
            sosAudioRecorder.stopBufferingAndClear()
            _sosAudioStatus.value = SosAudioStatus.MICROPHONE_PERMISSION_REQUIRED
        } else {
            syncSosAudioMonitoringWithPreferences(currentPrefs)
            if (_monitoringState.value == MonitoringState.MONITORING) {
                MonitoringForegroundService.start(appContext)
            }
        }
    }

    fun deleteStoredSosAudioRecordings() {
        val lastClip = _lastCapturedAudioClip.value
        sosAudioRecorder.deleteAllFinalizedAudioFiles()
        _lastCapturedAudioClip.value = null
        scope.launch {
            val remotePath = lastClip?.storageObjectPath
            if (!remotePath.isNullOrBlank()) {
                sosAudioStorageUploader.deleteSosAudioRecording(remotePath)
            }
        }
    }

    fun startMonitoring() {
        if (_monitoringState.value == MonitoringState.MONITORING) return
        replayJob?.cancel()
        detector.reset()
        val started = sensorManager.startListening()
        _monitoringState.value = MonitoringState.MONITORING
        _sosWorkflowState.value = SosWorkflowState.MONITORING
        syncSosAudioMonitoringWithPreferences(_preferencesState.value)
        if (!started) {
            _latestFeatures.value = MotionFeatures(
                evaluationSummary = "No hardware accelerometer found on this device — use Replay Traces to test."
            )
        }
        MonitoringForegroundService.start(appContext)
        logEvent(
            type = SafetyEventType.MONITORING_STARTED,
            summary = if (started) "Hardware accelerometer monitoring started (~50 Hz)" else "Monitoring started in trace replay mode",
            channel = "System"
        )
    }

    fun stopMonitoring() {
        countdownJob?.cancel()
        replayJob?.cancel()
        sensorManager.stopListening()
        sosAudioRecorder.stopBufferingAndClear()
        alertDeliveryGateway.cancelCheckInNotification()
        detector.reset()
        _activeCandidateSummary.value = null
        _monitoringState.value = MonitoringState.STOPPED
        _sosWorkflowState.value = SosWorkflowState.IDLE
        syncSosAudioMonitoringWithPreferences(_preferencesState.value)
        MonitoringForegroundService.stop(appContext)
        logEvent(
            type = SafetyEventType.MONITORING_STOPPED,
            summary = "Safety monitoring stopped by user",
            channel = "System"
        )
    }

    private fun onSensorSample(sample: MotionSample) {
        val state = _monitoringState.value
        if (state != MonitoringState.MONITORING && state != MonitoringState.CANCELLED) {
            return
        }
        val eval = detector.processSample(sample, SystemClock.elapsedRealtime())
        _latestFeatures.value = eval.features
        _recentMagnitudes.value = detector.getRecentMagnitudes(60)

        if (eval.candidateSummary != null) {
            triggerCandidateCheckIn(eval.candidateSummary)
        }
    }

    fun runSyntheticTrace(traceType: SyntheticSensorTraces.TraceType) {
        replayJob?.cancel()
        countdownJob?.cancel()
        detector.reset()
        if (_monitoringState.value == MonitoringState.STOPPED) {
            _monitoringState.value = MonitoringState.MONITORING
            _sosWorkflowState.value = SosWorkflowState.MONITORING
            syncSosAudioMonitoringWithPreferences(_preferencesState.value)
        } else if (_monitoringState.value != MonitoringState.MONITORING) {
            _monitoringState.value = MonitoringState.MONITORING
            _sosWorkflowState.value = SosWorkflowState.MONITORING
        }

        val samples = SyntheticSensorTraces.generateTrace(traceType)
        replayJob = scope.launch {
            for (sample in samples) {
                if (_monitoringState.value == MonitoringState.CHECK_IN ||
                    _monitoringState.value == MonitoringState.ALERT_PENDING
                ) {
                    break
                }
                val eval = detector.processSample(sample, SystemClock.elapsedRealtime())
                _latestFeatures.value = eval.features
                _recentMagnitudes.value = detector.getRecentMagnitudes(60)
                if (eval.candidateSummary != null) {
                    triggerCandidateCheckIn(eval.candidateSummary)
                    break
                }
                delay(20L)
            }
        }
    }

    fun triggerCandidateCheckIn(summary: CandidateEventSummary) {
        if (_monitoringState.value == MonitoringState.CHECK_IN ||
            _monitoringState.value == MonitoringState.ALERT_PENDING
        ) {
            return
        }
        if (summary.eventId.isNotBlank() && !triggeredCandidateEventIds.add(summary.eventId)) {
            return
        }

        val deterministicAlertId = "alert_${summary.eventId.ifBlank { UUID.randomUUID().toString() }}"
        activeCheckInAlertId = deterministicAlertId
        _activeCandidateSummary.value = summary
        _monitoringState.value = MonitoringState.CANDIDATE_EVENT
        _sosWorkflowState.value = SosWorkflowState.POSSIBLE_FALL_DETECTED
        val prefs = _preferencesState.value
        if ((prefs.sosAudioEnabled && prefs.sosAudioConsentGranted) ||
            sosAudioRecorder.getBufferedDurationSeconds() > 0.0
        ) {
            sosAudioRecorder.snapshotPreTriggerCandidate()
        }
        val totalSeconds = prefs.countdownSeconds.coerceIn(10, 60)
        _countdownRemaining.value = totalSeconds

        logEvent(
            type = SafetyEventType.POSSIBLE_FALL_DETECTED,
            summary = summary.summaryNote,
            channel = prefs.trustedContact.preferredChannel.label
        )

        _monitoringState.value = MonitoringState.CHECK_IN
        _sosWorkflowState.value = SosWorkflowState.AWAITING_USER_CONFIRMATION
        alertDeliveryGateway.vibrateForCheckIn(prefs.vibrationEnabled)
        alertDeliveryGateway.postCheckInUrgentNotification(totalSeconds, summary)

        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = totalSeconds
            while (remaining > 0 && _monitoringState.value == MonitoringState.CHECK_IN) {
                delay(1000L)
                remaining -= 1
                _countdownRemaining.value = remaining
                if (remaining > 0 && remaining % 5 == 0) {
                    alertDeliveryGateway.postCheckInUrgentNotification(remaining, summary)
                }
            }
            val currentSummary = _activeCandidateSummary.value
            val wasCancelled = currentSummary != null && cancelledCandidateEventIds.contains(currentSummary.eventId)
            if (remaining <= 0 &&
                _monitoringState.value == MonitoringState.CHECK_IN &&
                currentSummary != null &&
                !wasCancelled
            ) {
                dispatchEmergencyAlert(
                    isTestAlert = false,
                    triggerReason = "Possible fall detected (no response after ${totalSeconds}s countdown)",
                    eventType = SafetyEventType.COUNTDOWN_EXPIRED
                )
            }
        }
    }

    fun cancelCheckInByUser(reasonNote: String = "User tapped 'I'm OK'") {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        sosAudioRecorder.discardCandidateSnapshot()
        val summary = _activeCandidateSummary.value
        if (summary != null && summary.eventId.isNotBlank()) {
            cancelledCandidateEventIds.add(summary.eventId)
        }
        activeCheckInAlertId = null
        _monitoringState.value = MonitoringState.CANCELLED
        _sosWorkflowState.value = SosWorkflowState.CANCELLED
        logEvent(
            type = SafetyEventType.CHECK_IN_CANCELLED,
            summary = "$reasonNote (${summary?.summaryNote ?: "check-in active"})",
            channel = "Cancelled — temporary SOS audio discarded, no alert sent"
        )
        scope.launch {
            delay(3500L)
            if (_monitoringState.value == MonitoringState.CANCELLED) {
                _activeCandidateSummary.value = null
                val nextMonState = if (sensorManager.hasAccelerometer) {
                    MonitoringState.MONITORING
                } else {
                    MonitoringState.STOPPED
                }
                _monitoringState.value = nextMonState
                _sosWorkflowState.value = if (nextMonState == MonitoringState.MONITORING) {
                    SosWorkflowState.MONITORING
                } else {
                    SosWorkflowState.IDLE
                }
            }
        }
    }

    fun requestImmediateHelp(reasonNote: String = "Possible fall detected — Send SOS Now") {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        scope.launch {
            dispatchEmergencyAlert(
                isTestAlert = false,
                triggerReason = reasonNote,
                eventType = SafetyEventType.HELP_REQUESTED_NOW
            )
        }
    }

    /**
     * Manual SOS action triggered directly from the Home screen SOS button,
     * completely independent of automatic fall detection or sensor monitoring state.
     */
    fun triggerManualSos() {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        activeCheckInAlertId = "sos_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
        _activeCandidateSummary.value = null
        scope.launch {
            dispatchEmergencyAlert(
                isTestAlert = false,
                triggerReason = "SOS requested",
                eventType = SafetyEventType.HELP_REQUESTED_NOW
            )
        }
    }

    /**
     * Sends a clearly labelled TEST alert (`isTestAlert = true`) through the real
     * GPS + Firestore + FCM + Gmail API pipeline so the user can verify both
     * email delivery and two-device phone pop-up notifications.
     */
    fun sendTestAlert() {
        scope.launch {
            dispatchEmergencyAlert(
                isTestAlert = true,
                triggerReason = "Manual test alert triggered by user (TEST — not an emergency)",
                eventType = SafetyEventType.TEST_ALERT_TRIGGERED
            )
        }
    }

    private suspend fun dispatchEmergencyAlert(
        isTestAlert: Boolean,
        triggerReason: String,
        eventType: SafetyEventType
    ) {
        val prefs = preferencesRepository.preferencesFlow.first()
        val contact = prefs.trustedContact
        val summary = _activeCandidateSummary.value

        if (!isTestAlert && summary != null && cancelledCandidateEventIds.contains(summary.eventId)) {
            return
        }

        val alertId = if (isTestAlert) {
            "test_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
        } else {
            activeCheckInAlertId ?: "alert_${summary?.eventId ?: UUID.randomUUID().toString().take(12)}"
        }

        // Prevent duplicate emergency dispatches for the same event
        Log.i(TAG, "dispatchEmergencyAlert initiated: alertId=$alertId, isTestAlert=$isTestAlert, triggerReason='$triggerReason'")
        if (!isTestAlert && !dispatchedEmergencyAlertIds.add(alertId)) {
            Log.w(TAG, "Suppressed duplicate dispatch for already processed alertId=$alertId")
            return
        }

        _monitoringState.value = MonitoringState.ALERT_PENDING
        _sosWorkflowState.value = SosWorkflowState.SOS_TRIGGERED
        _deliveryStatus.value = DeliveryConfigStatus.SENDING
        _deliveryStatusDetail.value = DeliveryConfigStatus.SENDING.detail
        _requiresGmailAuthorization.value = false

        if (!contact.isValid) {
            val missingReason =
                "No valid trusted contact saved. Add a trusted contact with a valid email address first."
            _monitoringState.value = MonitoringState.ALERT_FAILED
            _sosWorkflowState.value = SosWorkflowState.ALERT_FAILED
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = missingReason
            logEvent(
                type = eventType,
                summary = "$triggerReason — Failed: $missingReason",
                channel = contact.preferredChannel.label,
                deliveryOutcome = missingReason
            )
            return
        }

        // Step 1: Capture real GPS coordinates if location sharing is enabled
        var capturedSnapshot: GpsLocationSnapshot? = null
        var locDiag: String? = null
        if (contact.optionalLocationEnabled) {
            _isCapturingLocation.value = true
            when (val locOutcome = locationProvider.captureCurrentLocation(enrichWithGeminiMapsContext = true)) {
                is LocationCaptureOutcome.Success -> {
                    capturedSnapshot = locOutcome.snapshot
                    _latestLocationSnapshot.value = locOutcome.snapshot
                    locDiag = "Live GPS fix attached: ${locOutcome.snapshot.formattedCoordinates}"
                    _locationDiagnostics.value = locDiag
                }
                is LocationCaptureOutcome.Unavailable -> {
                    locDiag = locOutcome.reason
                    _locationDiagnostics.value = locDiag
                }
            }
            _isCapturingLocation.value = false
        } else {
            locDiag = "Location not included (location sharing disabled in Privacy Settings)"
        }

        // Step 2: Finalize & Upload 15-second SOS audio recording if explicitly enabled by user
        var capturedAudioClip: SosAudioClipMetadata? = null
        var resolvedAudioStatusKey = "DISABLED"
        var resolvedAudioStatusNote: String? = null

        if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted) {
            _sosWorkflowState.value = SosWorkflowState.AUDIO_FINALIZING
            _deliveryStatusDetail.value = "Finalizing 15-second SOS audio clip..."
            val postSeconds = if (isTestAlert) 0 else prefs.postTriggerAudioSeconds.coerceIn(0, 3)

            when (val audioOutcome = sosAudioRecorder.finalizeSosAudioForAlert(alertId, postSeconds)) {
                is SosAudioFinalizeOutcome.Captured -> {
                    capturedAudioClip = audioOutcome.metadata
                    _lastCapturedAudioClip.value = capturedAudioClip
                    _sosAudioStatus.value = SosAudioStatus.SOS_AUDIO_CAPTURED
                    resolvedAudioStatusKey = "SOS_AUDIO_CAPTURED"
                    resolvedAudioStatusNote = "Attached (${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s ${capturedAudioClip.containerExtension.uppercase(Locale.US)} audio clip captured)"

                    logEvent(
                        type = SafetyEventType.SOS_AUDIO_CAPTURED,
                        summary = "Captured ${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s SOS audio clip (${capturedAudioClip.containerExtension.uppercase(Locale.US)})",
                        channel = "Local Rolling Buffer"
                    )

                    val uid = try {
                        Firebase.auth.currentUser?.uid.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }.ifBlank { activeObservedUid.orEmpty() }

                    if (uid.isNotBlank()) {
                        _sosWorkflowState.value = SosWorkflowState.UPLOADING_AUDIO
                        _sosAudioStatus.value = SosAudioStatus.UPLOAD_IN_PROGRESS
                        _deliveryStatusDetail.value = "Uploading SOS audio recording to Firebase Storage..."
                        val localFile = File(capturedAudioClip.localFilePath.orEmpty())
                        when (val uploadRes = sosAudioStorageUploader.uploadSosAudioRecording(
                            alertId = alertId,
                            localFile = localFile,
                            clipMetadata = capturedAudioClip,
                            userIdOverride = uid
                        )) {
                            is SosAudioStorageUploadResult.Uploaded -> {
                                capturedAudioClip = uploadRes.updatedMetadata
                                _lastCapturedAudioClip.value = capturedAudioClip
                                resolvedAudioStatusKey = "UPLOADED"
                                resolvedAudioStatusNote =
                                    "Uploaded (${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s ${capturedAudioClip.containerExtension.uppercase(Locale.US)}) — Secure link: ${uploadRes.downloadUrl}"
                                logEvent(
                                    type = SafetyEventType.SOS_AUDIO_UPLOADED,
                                    summary = "Uploaded SOS audio to Firebase Storage (${uploadRes.storageObjectPath})",
                                    channel = "Firebase Storage",
                                    deliveryOutcome = "Protected download link generated"
                                )
                            }
                            is SosAudioStorageUploadResult.Failed -> {
                                _sosAudioStatus.value = SosAudioStatus.AUDIO_UPLOAD_FAILED
                                resolvedAudioStatusKey = "AUDIO_UPLOAD_FAILED"
                                resolvedAudioStatusNote =
                                    "Captured ${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s audio locally (cloud storage upload failed: ${uploadRes.reason})"
                            }
                        }
                    }
                }
                is SosAudioFinalizeOutcome.Unavailable -> {
                    _sosAudioStatus.value = audioOutcome.status
                    resolvedAudioStatusKey = audioOutcome.status.name
                    resolvedAudioStatusNote = audioOutcome.reason
                }
            }
        } else {
            resolvedAudioStatusKey = "DISABLED"
            resolvedAudioStatusNote = "Not included (SOS audio recording is disabled in Privacy Settings)"
        }

        // Step 3: Format email + pop-up alert payload with Google Maps link and SOS audio metadata
        _sosWorkflowState.value = SosWorkflowState.SENDING_EMAIL
        val payload = alertDeliveryGateway.formatMessagePayload(
            alertId = alertId,
            contact = contact,
            eventSummary = summary,
            isTestAlert = isTestAlert,
            triggerReason = triggerReason,
            locationSnapshot = capturedSnapshot,
            locationDiagnostics = locDiag,
            audioClip = capturedAudioClip,
            audioStatusKey = resolvedAudioStatusKey,
            audioStatusNote = resolvedAudioStatusNote
        )
        _lastMessagePreview.value = payload

        // Step 4: Record in Cloud Firestore (`REQUEST_ACCEPTED`) to trigger real-time FCM push & recipient pop-up
        Log.i(TAG, "Writing emergency alert to Cloud Firestore: alertId=$alertId, contactEmail=${contact.contactEmail}, channel=${contact.preferredChannel.name}")
        val firestoreResult = firestoreRepository.createEmergencyAlert(
            alertId = alertId,
            contact = contact,
            payload = payload,
            triggerReason = triggerReason,
            channelUsed = contact.preferredChannel.name,
            recipientUid = contact.recipientUid.takeIf { it.isNotBlank() }
        )

        if (firestoreResult.isSuccess) {
            Log.i(TAG, "Cloud Firestore createEmergencyAlert SUCCEEDED for alertId=$alertId")
            _deliveryStatus.value = DeliveryConfigStatus.REQUEST_ACCEPTED
            val pushNote = if (contact.isReadyForPushAlerts) {
                "Push alert dispatched to ${contact.contactName}'s registered device(s)."
            } else {
                "Note: Push notification not yet active (${contact.verificationStatus.label})."
            }
            _deliveryStatusDetail.value =
                "Emergency request accepted in Cloud Firestore ($alertId). $pushNote Sending email to ${contact.contactEmail}..."
        } else {
            val err = firestoreResult.exceptionOrNull()
            Log.e(TAG, "Cloud Firestore createEmergencyAlert FAILED for alertId=$alertId: ${err?.message}")
        }

        // Step 5: Deliver transactional email (and optional Google Chat message)
        val result = alertDeliveryGateway.deliverAlert(
            contact = contact,
            payload = payload,
            googleChatConfig = prefs.googleChatConfig,
            triggerReason = triggerReason,
            gmailAccessToken = prefs.gmailAccessToken
        )

        when (result) {
            is AlertDeliveryResult.RemoteDelivered -> {
                _monitoringState.value = MonitoringState.ALERT_SENT
                _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted && result.emailSent) {
                    _sosAudioStatus.value = SosAudioStatus.SOS_EMAIL_SENT
                }
                _deliveryStatus.value = if (result.emailSent) {
                    DeliveryConfigStatus.EMAIL_SENT
                } else {
                    DeliveryConfigStatus.SENT_BY_PROVIDER
                }
                val combinedDetail = if (contact.isReadyForPushAlerts) {
                    "${result.detail} High-priority FCM push alert also sent to ${contact.contactName}'s phone."
                } else {
                    "${result.detail} (Phone push status: ${contact.verificationStatus.label})"
                }
                _deliveryStatusDetail.value = combinedDetail
                _requiresGmailAuthorization.value = false

                if (firestoreResult.isSuccess) {
                    firestoreRepository.updateAlertEmailStatus(
                        alertId = alertId,
                        emailSent = result.emailSent,
                        providerName = result.providerName,
                        providerMessageId = result.messageId,
                        statusDetail = combinedDetail
                    )
                }

                logEvent(
                    type = eventType,
                    summary = "$triggerReason — Sent to ${contact.contactName} (${contact.contactEmail})",
                    channel = result.channelUsed.label,
                    deliveryOutcome = combinedDetail
                )
            }

            is AlertDeliveryResult.LocalFallbackPosted -> {
                _monitoringState.value = MonitoringState.ALERT_SENT
                _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                _deliveryStatus.value = DeliveryConfigStatus.TEST_SENT
                _deliveryStatusDetail.value = result.detail
                logEvent(
                    type = eventType,
                    summary = "$triggerReason — ${result.detail}",
                    channel = AlertChannelType.LOCAL_ONLY.label,
                    deliveryOutcome = result.detail
                )
            }

            is AlertDeliveryResult.Failed -> {
                val alertRecordedInFirestore = firestoreResult.isSuccess
                Log.w(TAG, "Auxiliary email delivery returned Failed: reason=${result.reason}, requiresGmailAuth=${result.requiresGmailAuth}, alertRecordedInFirestore=$alertRecordedInFirestore")

                // If the emergency alert was already recorded and accepted in Cloud Firestore,
                // the alert dispatch succeeded. The auxiliary transactional email failing or
                // requiring OAuth authorization must NOT mark the overall emergency alert as failed.
                if (alertRecordedInFirestore) {
                    _monitoringState.value = MonitoringState.ALERT_SENT
                    _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                    _deliveryStatus.value = DeliveryConfigStatus.REQUEST_ACCEPTED

                    val fullReason = if (contact.isReadyForPushAlerts) {
                        "Emergency push alert dispatched to ${contact.contactName}'s phone via FCM/Firestore ($alertId). Email notice: ${result.reason}"
                    } else {
                        "Emergency record accepted in Cloud Firestore ($alertId). Push alert dispatched. Email notice: ${result.reason}"
                    }
                    _deliveryStatusDetail.value = fullReason
                    _requiresGmailAuthorization.value = result.requiresGmailAuth

                    firestoreRepository.updateAlertEmailStatus(
                        alertId = alertId,
                        emailSent = false,
                        providerName = "Gmail API",
                        errorMessage = result.reason,
                        statusDetail = fullReason
                    )

                    logEvent(
                        type = eventType,
                        summary = "$triggerReason — Alert recorded in Firestore ($alertId); push dispatched to ${contact.contactName}",
                        channel = AlertChannelType.SOLOSIGNAL_POPUP.label,
                        deliveryOutcome = fullReason
                    )
                } else {
                    // Genuine failure: Cloud Firestore write failed AND auxiliary delivery failed
                    _monitoringState.value = MonitoringState.ALERT_FAILED
                    _sosWorkflowState.value = SosWorkflowState.ALERT_FAILED
                    _deliveryStatus.value = DeliveryConfigStatus.ALERT_FAILED
                    _deliveryStatusDetail.value = result.reason
                    _requiresGmailAuthorization.value = result.requiresGmailAuth

                    if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted &&
                        _sosAudioStatus.value != SosAudioStatus.AUDIO_UPLOAD_FAILED
                    ) {
                        _sosAudioStatus.value = SosAudioStatus.SOS_EMAIL_FAILED
                    }

                    logEvent(
                        type = eventType,
                        summary = "$triggerReason — Delivery failed: ${result.reason}",
                        channel = contact.preferredChannel.label,
                        deliveryOutcome = result.reason
                    )
                }
            }
        }

        // Return to monitoring after showing confirmation state
        scope.launch {
            delay(6000L)
            if (_monitoringState.value == MonitoringState.ALERT_SENT ||
                _monitoringState.value == MonitoringState.ALERT_FAILED
            ) {
                activeCheckInAlertId = null
                _activeCandidateSummary.value = null
                val nextMonState = if (sensorManager.hasAccelerometer) {
                    MonitoringState.MONITORING
                } else {
                    MonitoringState.STOPPED
                }
                _monitoringState.value = nextMonState
                _sosWorkflowState.value = if (nextMonState == MonitoringState.MONITORING) {
                    SosWorkflowState.MONITORING
                } else {
                    SosWorkflowState.IDLE
                }
                syncSosAudioMonitoringWithPreferences(_preferencesState.value)
            }
        }
    }

    fun saveAndConnectGoogleChat(
        enabled: Boolean,
        backendEndpointUrl: String,
        spaceName: String,
        spaceDisplayLabel: String
    ) {
        scope.launch {
            val previous = _preferencesState.value.googleChatConfig
            val draft = GoogleChatAlertConfig(
                enabled = enabled,
                backendEndpointUrl = backendEndpointUrl.trim(),
                spaceName = spaceName.trim(),
                spaceDisplayLabel = spaceDisplayLabel.trim(),
                connectionStatus = GoogleChatConnectionStatus.CONNECTING,
                statusDetail = GoogleChatConnectionStatus.CONNECTING.defaultDetail,
                initialConnectionMessageSent = false,
                connectedDestinationKey = "",
                lastVerifiedEpochMillis = previous.lastVerifiedEpochMillis
            )

            if (!draft.isDestinationFormatValid) {
                val invalidConfig = draft.copy(
                    connectionStatus = GoogleChatConnectionStatus.CONNECTION_FAILED,
                    statusDetail = "Invalid configuration: Enter an https:// backend endpoint URL and a Google Chat space resource name (e.g. spaces/AAAABBBB)."
                )
                preferencesRepository.saveGoogleChatConfig(invalidConfig)
                return@launch
            }

            val alreadyVerifiedForSameDestination = previous.isTrulyConnected &&
                previous.connectedDestinationKey == draft.currentDestinationKey

            if (alreadyVerifiedForSameDestination) {
                val preserved = draft.copy(
                    connectionStatus = GoogleChatConnectionStatus.CONNECTED,
                    statusDetail = "Connected to ${draft.normalizedSpaceName}. Initial connection message already verified.",
                    initialConnectionMessageSent = true,
                    connectedDestinationKey = previous.connectedDestinationKey,
                    lastVerifiedEpochMillis = previous.lastVerifiedEpochMillis
                )
                preferencesRepository.saveGoogleChatConfig(preserved)
                return@launch
            }

            preferencesRepository.saveGoogleChatConfig(draft)

            val senderName = _preferencesState.value.trustedContact.userDisplayName
                .ifBlank { "SoloSignal User" }

            when (val outcome = googleChatClient.sendInitialConnectionMessage(draft, senderName)) {
                is GoogleChatSendOutcome.Success -> {
                    val connectedConfig = draft.copy(
                        connectionStatus = GoogleChatConnectionStatus.CONNECTED,
                        statusDetail = outcome.detail,
                        initialConnectionMessageSent = true,
                        connectedDestinationKey = draft.currentDestinationKey,
                        lastVerifiedEpochMillis = outcome.timestampMillis
                    )
                    preferencesRepository.saveGoogleChatConfig(connectedConfig)
                    logEvent(
                        type = SafetyEventType.GOOGLE_CHAT_CONNECTED,
                        summary = "Connected Google Chat space ${draft.normalizedSpaceName}",
                        channel = "Google Chat API (${outcome.messageId})",
                        deliveryOutcome = outcome.detail
                    )
                }
                is GoogleChatSendOutcome.Failure -> {
                    val failedConfig = draft.copy(
                        connectionStatus = GoogleChatConnectionStatus.CONNECTION_FAILED,
                        statusDetail = outcome.reason,
                        initialConnectionMessageSent = false,
                        connectedDestinationKey = ""
                    )
                    preferencesRepository.saveGoogleChatConfig(failedConfig)
                }
            }
        }
    }

    fun sendManualGoogleChatVerification() {
        scope.launch {
            val current = _preferencesState.value.googleChatConfig
            val connecting = current.copy(
                connectionStatus = GoogleChatConnectionStatus.CONNECTING,
                statusDetail = "Sending verification message to ${current.normalizedSpaceName}..."
            )
            preferencesRepository.saveGoogleChatConfig(connecting)

            val senderName = _preferencesState.value.trustedContact.userDisplayName
                .ifBlank { "SoloSignal User" }

            when (val outcome = googleChatClient.sendInitialConnectionMessage(current, senderName)) {
                is GoogleChatSendOutcome.Success -> {
                    val updated = current.copy(
                        connectionStatus = GoogleChatConnectionStatus.CONNECTED,
                        statusDetail = outcome.detail,
                        initialConnectionMessageSent = true,
                        connectedDestinationKey = current.currentDestinationKey,
                        lastVerifiedEpochMillis = outcome.timestampMillis
                    )
                    preferencesRepository.saveGoogleChatConfig(updated)
                }
                is GoogleChatSendOutcome.Failure -> {
                    val failed = current.copy(
                        connectionStatus = GoogleChatConnectionStatus.CONNECTION_FAILED,
                        statusDetail = outcome.reason,
                        initialConnectionMessageSent = false,
                        connectedDestinationKey = ""
                    )
                    preferencesRepository.saveGoogleChatConfig(failed)
                }
            }
        }
    }

    fun clearAllHistory() {
        scope.launch {
            eventDao.clearAllEvents()
        }
    }

    fun deleteEventById(id: Long) {
        scope.launch {
            eventDao.deleteEventById(id)
        }
    }

    fun deleteAllPersonalData() {
        stopMonitoring()
        deleteStoredSosAudioRecordings()
        scope.launch {
            eventDao.clearAllEvents()
            removeTrustedContactAndRevoke()
            preferencesRepository.clearAllUserData()
            _lastMessagePreview.value = null
            _latestLocationSnapshot.value = null
            _lastCapturedAudioClip.value = null
            _sosAudioStatus.value = SosAudioStatus.DISABLED
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = DeliveryConfigStatus.NOT_CONFIGURED.detail
        }
    }

    private fun logEvent(
        type: SafetyEventType,
        summary: String,
        channel: String,
        deliveryOutcome: String = ""
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                eventDao.insertEvent(
                    EventLogEntity(
                        timestampEpochMillis = System.currentTimeMillis(),
                        eventType = type.name,
                        title = type.title,
                        details = summary,
                        channelUsed = channel,
                        deliveryOutcome = deliveryOutcome
                    )
                )
                Log.d(TAG, "Event logged to history database: type=${type.name}, channel=$channel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert event into history database (non-fatal): ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SoloSignalAlert"
        const val ACTIVE_ALERT_MAX_AGE_MILLIS: Long = 15 * 60 * 1000L

        @Volatile
        private var INSTANCE: SafetyMonitoringController? = null

        fun getInstance(context: Context): SafetyMonitoringController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SafetyMonitoringController(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

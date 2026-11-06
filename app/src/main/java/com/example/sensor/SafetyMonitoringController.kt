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
import com.example.alerts.EmergencyAlertDispatchType
import com.example.alerts.EmergencyAlertDispatcher
import com.example.alerts.EmergencyDispatchDiagnostics
import com.example.alerts.EmergencyDispatchLogStage
import com.example.alerts.EmergencyDispatchOutcome
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
import com.example.data.MedicalIdEntity
import com.example.data.PersonalLearningExampleEntity
import com.example.data.PersonalMovementProfileEntity
import com.example.data.PostFallAssessmentEntity
import com.example.domain.AiHealthCrossCheckEngine
import com.example.domain.AssessmentCompletionStatus
import com.example.domain.FeedbackLearningLabel
import com.example.domain.HealthCrossCheckResult
import com.example.domain.IncidentVerificationType
import com.example.domain.MedicalIdProfile
import com.example.domain.PersonalLearningEventStatus
import com.example.domain.PersonalLearningExample
import com.example.domain.PersonalLearningStage
import com.example.domain.PersonalMovementProfile
import com.example.domain.PostFallAssessmentDraft
import com.example.domain.PostFallJsonCodec
import com.example.domain.SensorWindowSnapshot
import com.example.domain.SosAlertOutcomeState
import com.example.domain.SosLearningSourceType
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val database by lazy { EventHistoryDatabase.getInstance(appContext) }
    private val eventDao by lazy { database.eventLogDao() }
    val alertDeliveryGateway by lazy { AlertDeliveryGateway(appContext) }
    val locationProvider by lazy { EmergencyLocationProvider(appContext) }
    val sosAudioRecorder by lazy { RollingSosAudioRecorder(appContext) }
    val sosAudioStorageUploader by lazy { SosAudioStorageUploader() }
    private val googleChatClient by lazy { GoogleChatAlertClient() }
    private val firestoreRepository by lazy { AlertFirestoreRepository(appContext) }
    private val contactVerificationClient by lazy {
        TrustedContactVerificationClient(firestoreRepository)
    }

    private val sensorManager = SensorMonitorManager(appContext) { sample ->
        onSensorSample(sample)
    }
    private val detector = HybridFallDetectionEngine(
        context = appContext,
        sensorMaxRange = sensorManager.accelerometerMaxRange
    ).also {
        it.setSensorHardwareCapabilities(
            hasGyroscope = sensorManager.hasGyroscope,
            maxRangeMs2 = sensorManager.accelerometerMaxRange
        )
        it.onRecurringFalseAlarmSuppressed = { decision ->
            onPersonalisedFalseAlarmSuppressed(decision.decisionSummary)
        }
    }

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
    private val dispatchedFullScreenAlertIds = ConcurrentHashMap.newKeySet<String>()
    private var activeCheckInAlertId: String? = null
    private var activeCheckInCorrelationId: String? = null

    val emergencyAlertDispatcher by lazy {
        EmergencyAlertDispatcher(
            resolveContact = { resolveCurrentUserPreferences().trustedContact },
            resolveRecipientUid = { contact ->
                firestoreRepository.resolveVerifiedRecipientUid(contact)
            },
            captureLocation = { _ ->
                captureFastEmergencyLocation()
            },
            formatPayload = { alertId, contact, eventSummary, isTestAlert, triggerReason, locationSnapshot, locationDiagnostics, audioClip, audioStatusKey, audioStatusNote, medicalIdSummary ->
                alertDeliveryGateway.formatMessagePayload(
                    alertId = alertId,
                    contact = contact,
                    eventSummary = eventSummary,
                    isTestAlert = isTestAlert,
                    triggerReason = triggerReason,
                    locationSnapshot = locationSnapshot,
                    locationDiagnostics = locationDiagnostics,
                    audioClip = audioClip,
                    audioStatusKey = audioStatusKey,
                    audioStatusNote = audioStatusNote,
                    medicalIdSummary = medicalIdSummary
                )
            },
            createFirestoreAlert = { alertId, contact, payload, triggerReason, channelUsed, recipientUid, mlExplainability ->
                firestoreRepository.createEmergencyAlert(
                    alertId = alertId,
                    contact = contact,
                    payload = payload,
                    triggerReason = triggerReason,
                    channelUsed = channelUsed,
                    recipientUid = recipientUid,
                    mlExplainability = mlExplainability
                )
            },
            deliverRemoteAlert = { contact, payload, googleChatConfig, triggerReason, gmailAccessToken ->
                alertDeliveryGateway.deliverAlert(
                    contact = contact,
                    payload = payload,
                    googleChatConfig = googleChatConfig,
                    triggerReason = triggerReason,
                    gmailAccessToken = gmailAccessToken
                )
            },
            updateFirestoreEmailStatus = { alertId, emailSent, providerName, providerMessageId, errorMessage, statusDetail ->
                firestoreRepository.updateAlertEmailStatus(
                    alertId = alertId,
                    emailSent = emailSent,
                    providerName = providerName,
                    providerMessageId = providerMessageId,
                    errorMessage = errorMessage,
                    statusDetail = statusDetail
                )
            }
        )
    }

    private val _monitoringState = MutableStateFlow(MonitoringState.STOPPED)
    val monitoringState: StateFlow<MonitoringState> = _monitoringState.asStateFlow()

    enum class RealMonitoringServiceStatus(val label: String, val detail: String) {
        MONITORING_ACTIVE("Monitoring Active", "Background sensor listener active & capturing motion"),
        STARTING_MONITORING("Starting Monitoring", "Initializing background monitoring service"),
        MONITORING_PAUSED("Monitoring Paused", "Safety monitoring paused by user"),
        MONITORING_UNAVAILABLE("Monitoring Unavailable", "Sensor or foreground service unavailable")
    }

    private val _realMonitoringStatus = MutableStateFlow(RealMonitoringServiceStatus.MONITORING_PAUSED)
    val realMonitoringStatus: StateFlow<RealMonitoringServiceStatus> = _realMonitoringStatus.asStateFlow()

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

    private val _isEmergencyScreenActive = MutableStateFlow(false)
    val isEmergencyScreenActive: StateFlow<Boolean> = _isEmergencyScreenActive.asStateFlow()

    private val _modelComparisonReport = MutableStateFlow<com.example.domain.ModelComparisonReport?>(null)
    val modelComparisonReport: StateFlow<com.example.domain.ModelComparisonReport?> = _modelComparisonReport.asStateFlow()

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

    private val _activePostFallAssessmentDraft = MutableStateFlow<PostFallAssessmentDraft?>(null)
    val activePostFallAssessmentDraft: StateFlow<PostFallAssessmentDraft?> = _activePostFallAssessmentDraft.asStateFlow()

    private val _latestHealthCrossCheckResult = MutableStateFlow<HealthCrossCheckResult?>(null)
    val latestHealthCrossCheckResult: StateFlow<HealthCrossCheckResult?> = _latestHealthCrossCheckResult.asStateFlow()

    private val _isEvaluatingHealthCrossCheck = MutableStateFlow(false)
    val isEvaluatingHealthCrossCheck: StateFlow<Boolean> = _isEvaluatingHealthCrossCheck.asStateFlow()

    private val _medicalIdProfile = MutableStateFlow(MedicalIdProfile())
    val medicalIdProfile: StateFlow<MedicalIdProfile> = _medicalIdProfile.asStateFlow()

    private val _personalMovementProfile = MutableStateFlow(PersonalMovementProfile())
    val personalMovementProfile: StateFlow<PersonalMovementProfile> = _personalMovementProfile.asStateFlow()

    private val _personalLearningExamples = MutableStateFlow<List<PersonalLearningExample>>(emptyList())
    val personalLearningExamples: StateFlow<List<PersonalLearningExample>> = _personalLearningExamples.asStateFlow()

    private val _latestFeedbackLearningExample = MutableStateFlow<PersonalLearningExample?>(null)
    val latestFeedbackLearningExample: StateFlow<PersonalLearningExample?> = _latestFeedbackLearningExample.asStateFlow()

    val eventLogsFlow: Flow<List<EventLogEntity>> = flow {
        emitAll(eventDao.observeAllEvents())
    }.flowOn(Dispatchers.IO)

    val postFallAssessmentsFlow: Flow<List<PostFallAssessmentEntity>> = flow {
        emitAll(eventDao.observePostFallAssessments())
    }.flowOn(Dispatchers.IO)

    val hasAccelerometer: Boolean = sensorManager.hasAccelerometer

    init {
        scope.launch(Dispatchers.IO) {
            try {
                alertDeliveryGateway.ensureChannels()
            } catch (_: Exception) {
            }
            try {
                val localMed = eventDao.getMedicalIdProfileSnapshot("local_default")?.toDomain()
                if (localMed != null) {
                    _medicalIdProfile.value = localMed
                }
            } catch (_: Exception) {
            }
            loadPersonalLearningForUser("local_default")
        }
        scope.launch(Dispatchers.IO) {
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
        scope.launch(Dispatchers.IO) {
            delay(250L)
            ensureAuthenticatedSessionInitialized()
        }
        scope.launch(Dispatchers.IO) {
            // Observe foreground service state changes to keep RealMonitoringServiceStatus updated
            MonitoringForegroundService.isServiceRunning.collect { isRunning ->
                updateRealMonitoringStatus()
            }
        }
        scope.launch(Dispatchers.IO) {
            // Auto-restore monitoring on process start if user previously enabled monitoring
            delay(500L)
            try {
                if (preferencesRepository.isMonitoringEnabled(activeObservedUid)) {
                    Log.i(TAG, "Preferences indicate monitoring is enabled; auto-restoring background monitoring.")
                    restoreMonitoringIfEnabled(appContext)
                } else {
                    updateRealMonitoringStatus()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking persistent monitoring state on startup: ${e.message}")
            }
        }
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
            _activePostFallAssessmentDraft.value = null
            _latestHealthCrossCheckResult.value = null
            _latestFeedbackLearningExample.value = null
            scope.launch(Dispatchers.IO) {
                loadPersonalLearningForUser("local_default")
            }
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
        // Deferred briefly so Activity window creation / SurfaceSyncGroup completes without IPC contention
        scope.launch(Dispatchers.IO) {
            delay(350L)
            if (activeObservedUid == uid) {
                MonitoringForegroundService.startAlertListenerOnly(appContext)
            }
        }

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
            syncMedicalIdForAuthenticatedUser(uid)
            loadPersonalLearningForUser(uid)
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
                            val ackContactName = latest.recipientName ?: latest.recipientEmail ?: "Emergency contact"
                            if (ackAlertId.isNotBlank()) {
                                recordRecipientAcknowledgementForPersonalLearning(
                                    alertId = ackAlertId,
                                    acknowledgedByName = ackContactName
                                )
                            }
                            if (ackAlertId.isNotBlank() && loggedSenderAckAlertIds.add(ackAlertId)) {
                                logEvent(
                                    type = SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED,
                                    summary = "Contact is checking on you ($ackContactName acknowledged alert)",
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
        val hasNotifPerm = alertDeliveryGateway.hasNotificationPermission()

        if (!hasNotifPerm) {
            _deliveryStatus.value = DeliveryConfigStatus.NOTIFICATION_PERMISSION_MISSING
            _deliveryStatusDetail.value = "Alert received, but notification permission is disabled on this device."
        } else if (posted) {
            _deliveryStatus.value = DeliveryConfigStatus.NOTIFICATION_SENT
            _deliveryStatusDetail.value = "High-priority incident notification posted."
        }

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
                    },
                    deliveryState = if (posted) {
                        AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT
                    } else {
                        AlertFirestoreRepository.STATE_NOTIFICATION_PERMISSION_MISSING
                    }
                )
            }
            logEvent(
                type = SafetyEventType.RECIPIENT_ALERT_RECEIVED,
                summary = if (effectiveIncoming.isTestAlert) {
                    "TEST INCIDENT — ${effectiveIncoming.senderName} triggered a test alert"
                } else {
                    "SOS ALERT — ${effectiveIncoming.senderName} needs help"
                },
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
     * or loads it from Firestore if opened from a background FCM notification tap or deep link.
     * Avoids recreating duplicate alert screens if the requested incident is already open.
     */
    fun openRecipientEmergencyAlert(
        alert: IncomingRecipientAlert?,
        fallbackAlertId: String? = null
    ) {
        val targetId = fallbackAlertId?.takeIf { it.isNotBlank() } ?: alert?.alertId
        if (targetId.isNullOrBlank()) return

        // Prevent duplicate alert screens if already viewing this exact incident
        val current = _activeRecipientEmergencyAlert.value
        if (current != null && current.alertId == targetId) {
            // Screen is already open for this exact incident!
            // Update fields in-place if delivery status or details changed
            if (alert != null && alert.deliveryState != current.deliveryState) {
                _activeRecipientEmergencyAlert.value = current.copy(
                    deliveryState = alert.deliveryState,
                    deliveryStatusDetail = alert.deliveryStatusDetail
                )
            }
            return
        }

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

    /**
     * Resumes the Check-In overlay if an active candidate event exists and the user tapped
     * a check-in notification.
     */
    fun resumeCheckInOverlay() {
        if (_activeCandidateSummary.value != null && _monitoringState.value != MonitoringState.CHECK_IN) {
            _monitoringState.value = MonitoringState.CHECK_IN
        }
    }

    fun resumeEmergencyScreen() {
        _isEmergencyScreenActive.value = true
    }

    /**
     * Simulates or triggers a test incident directly on this device,
     * opening the in-app RecipientEmergencyAlertScreen and posting
     * a clearly labelled TEST notification with stable deep link navigation.
     */
    fun triggerLocalTestIncident() {
        val testId = "test_incident_${System.currentTimeMillis()}"
        val contact = _preferencesState.value.trustedContact
        val senderDisplay = contact.userDisplayName.trim().ifBlank { "You (Test Mode)" }
        val testAlert = IncomingRecipientAlert(
            alertId = testId,
            senderName = senderDisplay,
            senderEmail = contact.contactEmail.ifBlank { "test@example.com" },
            recipientName = contact.contactName.ifBlank { "Trusted Contact" },
            recipientEmail = contact.contactEmail,
            isTestAlert = true,
            subjectLine = "[TEST] SoloSignal Incident Alert",
            bodyText = "TEST INCIDENT — This alert was generated to verify that in-app incident screens, push notifications, and electronic buzzer alerts function correctly.",
            triggerReason = "Manual test incident simulation (TEST — not an emergency)",
            deliveryState = AlertFirestoreRepository.STATE_ALERT_CREATED,
            deliveryStatusDetail = "Test incident generated for alert flow verification.",
            googleMapsUrl = "https://www.google.com/maps?q=37.7749,-122.4194",
            locationSummary = "Test incident location (Simulated fix)",
            createdAtMillis = System.currentTimeMillis()
        )
        onIncomingEmergencyAlertReceived(testAlert)
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

    fun updateRealMonitoringStatus() {
        val serviceRunning = MonitoringForegroundService.isServiceRunning.value
        val isSensorListening = sensorManager.isActivelyProducingReadings()
        val monState = _monitoringState.value
        val isPrefEnabled = try {
            preferencesRepository.isMonitoringEnabled(activeObservedUid)
        } catch (_: Exception) {
            false
        }

        val computed = when {
            monState == MonitoringState.MONITORING && serviceRunning && (isSensorListening || !sensorManager.hasAccelerometer) -> {
                RealMonitoringServiceStatus.MONITORING_ACTIVE
            }
            monState == MonitoringState.MONITORING && (serviceRunning || isPrefEnabled) -> {
                RealMonitoringServiceStatus.STARTING_MONITORING
            }
            monState == MonitoringState.STOPPED && !isPrefEnabled -> {
                RealMonitoringServiceStatus.MONITORING_PAUSED
            }
            monState == MonitoringState.STOPPED && isPrefEnabled -> {
                // Pending start/restart
                RealMonitoringServiceStatus.STARTING_MONITORING
            }
            monState == MonitoringState.CANCELLED || monState == MonitoringState.CHECK_IN || monState == MonitoringState.ALERT_PENDING -> {
                RealMonitoringServiceStatus.MONITORING_ACTIVE
            }
            else -> {
                if (!sensorManager.hasAccelerometer && monState == MonitoringState.STOPPED) {
                    RealMonitoringServiceStatus.MONITORING_PAUSED
                } else {
                    RealMonitoringServiceStatus.MONITORING_UNAVAILABLE
                }
            }
        }
        _realMonitoringStatus.value = computed
    }

    /**
     * Automatically restores background safety monitoring if the user has previously enabled it.
     * Can be safely called from MainActivity lifecycle hooks (e.g. onResume) or Application startup.
     */
    fun restoreMonitoringIfEnabled(context: Context = appContext): Boolean {
        val enabled = try {
            preferencesRepository.isMonitoringEnabled(activeObservedUid)
        } catch (_: Exception) {
            false
        }
        if (!enabled) {
            updateRealMonitoringStatus()
            return false
        }

        val serviceRunning = MonitoringForegroundService.isServiceRunning.value
        val currentState = _monitoringState.value

        if (!serviceRunning) {
            Log.i(TAG, "Restoring MonitoringForegroundService because monitoring is enabled in preferences.")
            MonitoringForegroundService.start(context)
        }

        // Only transition into MONITORING from STOPPED so an active CHECK_IN countdown,
        // CANDIDATE_EVENT, or in-flight ALERT_PENDING / ALERT_SENT is NEVER overwritten!
        if (currentState == MonitoringState.STOPPED) {
            startMonitoring(fromUserExplicitAction = false)
        } else {
            updateRealMonitoringStatus()
        }
        return true
    }

    fun startMonitoring(fromUserExplicitAction: Boolean = false) {
        if (fromUserExplicitAction) {
            scope.launch(Dispatchers.IO) {
                preferencesRepository.setMonitoringEnabled(true, activeObservedUid)
                updateRealMonitoringStatus()
            }
        }

        val currentState = _monitoringState.value
        if (!fromUserExplicitAction && (
            currentState == MonitoringState.CHECK_IN ||
                currentState == MonitoringState.CANDIDATE_EVENT ||
                currentState == MonitoringState.ALERT_PENDING ||
                currentState == MonitoringState.ALERT_SENT
            )
        ) {
            MonitoringForegroundService.start(appContext)
            updateRealMonitoringStatus()
            return
        }

        if (currentState == MonitoringState.MONITORING &&
            MonitoringForegroundService.isServiceRunning.value
        ) {
            updateRealMonitoringStatus()
            return
        }

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
        updateRealMonitoringStatus()
        logEvent(
            type = SafetyEventType.MONITORING_STARTED,
            summary = if (started) "Hardware accelerometer monitoring started (~50 Hz)" else "Monitoring started in trace replay mode",
            channel = "System"
        )
    }

    fun stopMonitoring(fromUserExplicitAction: Boolean = true) {
        if (fromUserExplicitAction) {
            scope.launch(Dispatchers.IO) {
                preferencesRepository.setMonitoringEnabled(false, activeObservedUid)
                updateRealMonitoringStatus()
            }
        }

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
        updateRealMonitoringStatus()
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

    fun processSensorSampleForTesting(sample: com.example.domain.MotionSample): DetectionEvaluation {
        val eval = detector.processSample(sample, SystemClock.elapsedRealtime())
        _latestFeatures.value = eval.features
        _recentMagnitudes.value = detector.getRecentMagnitudes(60)
        if (eval.candidateSummary != null) {
            triggerCandidateCheckIn(eval.candidateSummary)
        }
        return eval
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
        val correlationId = EmergencyDispatchDiagnostics.newCorrelationId("fall")
        activeCheckInAlertId = deterministicAlertId
        activeCheckInCorrelationId = correlationId
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

        EmergencyDispatchDiagnostics.log(
            correlationId = correlationId,
            stage = EmergencyDispatchLogStage.FALL_EVENT_CONFIRMED,
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            alertId = deterministicAlertId,
            detail = "eventId=${summary.eventId}, peakMs2=${summary.peakMagnitude}, countdownSeconds=$totalSeconds"
        )

        logEvent(
            type = SafetyEventType.POSSIBLE_FALL_DETECTED,
            summary = summary.summaryNote,
            channel = prefs.trustedContact.preferredChannel.label,
            explainability = summary.explainabilityRecord
        )

        _monitoringState.value = MonitoringState.CHECK_IN
        _sosWorkflowState.value = SosWorkflowState.AWAITING_USER_CONFIRMATION
        alertDeliveryGateway.vibrateForCheckIn(prefs.vibrationEnabled)
        alertDeliveryGateway.postCheckInUrgentNotification(totalSeconds, summary)

        countdownJob?.cancel()
        countdownJob = scope.launch {
            var remaining = totalSeconds
            val eventId = summary.eventId
            while (remaining > 0 &&
                !cancelledCandidateEventIds.contains(eventId) &&
                !emergencyAlertDispatcher.hasDispatchedAlertId(deterministicAlertId) &&
                _monitoringState.value == MonitoringState.CHECK_IN
            ) {
                delay(1000L)
                remaining -= 1
                _countdownRemaining.value = remaining
                if (remaining > 0 && remaining % 5 == 0) {
                    alertDeliveryGateway.postCheckInUrgentNotification(remaining, summary)
                }
            }
            val currentSummary = _activeCandidateSummary.value ?: summary
            val wasCancelled = cancelledCandidateEventIds.contains(currentSummary.eventId) ||
                _monitoringState.value == MonitoringState.CANCELLED ||
                _monitoringState.value == MonitoringState.STOPPED
            if (remaining <= 0 &&
                !wasCancelled &&
                !emergencyAlertDispatcher.hasDispatchedAlertId(deterministicAlertId)
            ) {
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.CHECK_IN_TIMEOUT,
                    alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                    alertId = deterministicAlertId,
                    detail = "No user response after ${totalSeconds}s check-in countdown; auto-dispatching fall alert."
                )
                alertDeliveryGateway.cancelCheckInNotification()
                _isEmergencyScreenActive.value = true
                if (dispatchedFullScreenAlertIds.add(deterministicAlertId)) {
                    alertDeliveryGateway.postEmergencyTriggeredFullScreenNotification(
                        alertId = deterministicAlertId,
                        eventSummary = currentSummary,
                        contact = prefs.trustedContact
                    )
                }
                dispatchEmergencyAlert(
                    alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                    correlationId = correlationId,
                    triggerReason = "Possible fall detected (no response after ${totalSeconds}s countdown)",
                    eventType = SafetyEventType.COUNTDOWN_EXPIRED,
                    overrideAlertId = deterministicAlertId
                )
            }
        }
    }

    fun cancelCheckInByUser(
        reasonNote: String = "User tapped 'I'm OK'",
        openPostFallAssessment: Boolean = true
    ) {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        alertDeliveryGateway.cancelEmergencyFullScreenNotification()
        alertDeliveryGateway.stopEmergencyAlarmAndDismissNotification()
        _isEmergencyScreenActive.value = false
        sosAudioRecorder.discardCandidateSnapshot()
        val summary = _activeCandidateSummary.value
        if (summary != null && summary.eventId.isNotBlank()) {
            cancelledCandidateEventIds.add(summary.eventId)
        }
        activeCheckInAlertId = null
        activeCheckInCorrelationId = null
        _monitoringState.value = MonitoringState.CANCELLED
        _sosWorkflowState.value = SosWorkflowState.CANCELLED
        logEvent(
            type = SafetyEventType.CHECK_IN_CANCELLED,
            summary = "$reasonNote (${summary?.summaryNote ?: "check-in active"})",
            channel = "Cancelled — temporary SOS audio discarded, no alert sent",
            explainability = summary?.explainabilityRecord
        )
        if (openPostFallAssessment) {
            val eventKey = summary?.eventId?.takeIf { it.isNotBlank() }
                ?: "checkin_${System.currentTimeMillis()}"
            _latestHealthCrossCheckResult.value = null
            _activePostFallAssessmentDraft.value = PostFallAssessmentDraft(
                linkedEventId = eventKey,
                isManualSelfCheck = false
            )
        }
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

    /**
     * Opens a manual Post-Fall Health Self-Assessment from the Home dashboard or History screen.
     * If there is a recent unverified automatic SOS event awaiting user feedback, links the assessment
     * to that SOS event's `alertId` and `linkedEventId` so the user's feedback validates the event.
     */
    fun startManualPostFallAssessment() {
        _latestHealthCrossCheckResult.value = null
        val pendingSosEvent = _personalLearningExamples.value.firstOrNull { it.isAwaitingUserFeedback }
        _activePostFallAssessmentDraft.value = if (pendingSosEvent != null) {
            PostFallAssessmentDraft(
                linkedEventId = pendingSosEvent.linkedEventId.ifBlank { pendingSosEvent.alertId },
                isManualSelfCheck = false,
                incidentVerification = null,
                triggeredAlertId = pendingSosEvent.alertId.takeIf { it.isNotBlank() }
            )
        } else {
            PostFallAssessmentDraft(
                linkedEventId = "manual_${System.currentTimeMillis()}",
                isManualSelfCheck = true,
                incidentVerification = IncidentVerificationType.EXPERIENCED_FALL
            )
        }
    }

    /**
     * Opens the Post-Incident Assessment specifically linked to an unverified SOS event (`alertId`)
     * so the user can validate what happened and feed the verified label into Personal Learning.
     */
    fun startPostFallAssessmentForSosEvent(alertId: String, linkedEventId: String = "") {
        val cleanAlertId = alertId.trim()
        val cleanEventId = linkedEventId.trim().ifBlank { cleanAlertId }
        _latestHealthCrossCheckResult.value = null
        _activePostFallAssessmentDraft.value = PostFallAssessmentDraft(
            linkedEventId = cleanEventId.ifBlank { "sos_${System.currentTimeMillis()}" },
            isManualSelfCheck = false,
            incidentVerification = null,
            triggeredAlertId = cleanAlertId.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Updates the current [PostFallAssessmentDraft].
     * If the user selects any severe physical warning indicator (`Severe pain`, `Difficulty breathing: Yes`,
     * `Confusion or difficulty responding: Yes`, `Did you hit your head: Yes`, `Unable to stand up`,
     * `Bleeding`, or `Need medical assistance: Yes`), immediately and automatically dispatches an SOS alert
     * to the trusted contact once per assessment session.
     */
    fun updatePostFallAssessmentDraft(updated: PostFallAssessmentDraft) {
        val current = _activePostFallAssessmentDraft.value
        val alreadyDispatched = (current?.autoSosDispatched == true) || updated.autoSosDispatched
        if (updated.hasSevereWarningIndicators() && !alreadyDispatched) {
            val severeList = updated.describeSevereIndicators()
            val severeSummary = severeList.joinToString(", ").ifBlank { "Severe post-fall symptom" }
            val autoSosAlertId = "postfall_sos_${updated.assessmentId}"
            val correlationId = EmergencyDispatchDiagnostics.newCorrelationId("postfall")
            val withSosFlag = updated.copy(
                autoSosDispatched = true,
                triggeredAlertId = autoSosAlertId
            )
            _activePostFallAssessmentDraft.value = withSosFlag
            activeCheckInAlertId = autoSosAlertId
            activeCheckInCorrelationId = correlationId
            EmergencyDispatchDiagnostics.log(
                correlationId = correlationId,
                stage = EmergencyDispatchLogStage.FALL_EVENT_CONFIRMED,
                alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                alertId = autoSosAlertId,
                detail = "Post-fall severe symptom reported ($severeSummary)"
            )
            scope.launch {
                dispatchEmergencyAlert(
                    alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                    correlationId = correlationId,
                    triggerReason = "Post-fall assessment severe symptom reported ($severeSummary)",
                    eventType = SafetyEventType.POST_FALL_SEVERE_SYMPTOM_SOS,
                    overrideAlertId = autoSosAlertId
                )
            }
        } else {
            _activePostFallAssessmentDraft.value = updated.copy(
                autoSosDispatched = alreadyDispatched,
                triggeredAlertId = updated.triggeredAlertId ?: current?.triggeredAlertId
            )
        }
    }

    /**
     * Records a false detection (e.g., phone fell, phone dropped/placed down suddenly, normal movement,
     * or incorrect detection) for future ML model evaluation, ensures a phone drop is NEVER classified
     * as a human fall, and safely closes the Post-Fall Assessment workflow.
     */
    fun recordFalseDetectionAndClose(
        verification: IncidentVerificationType,
        optionalNote: String = ""
    ) {
        val draft = _activePostFallAssessmentDraft.value ?: PostFallAssessmentDraft(
            incidentVerification = verification
        )
        _activePostFallAssessmentDraft.value = null
        _latestHealthCrossCheckResult.value = null

        val cleanNote = optionalNote.trim()
        val falseAlarmTag = if (verification.isPhoneDrop) {
            "Phone drop / placement (NOT a human fall)"
        } else {
            "Non-fall / false alarm"
        }
        val detailSummary = buildString {
            append("Incident verification: \"${verification.displayLabel}\" [$falseAlarmTag]. ")
            append("Saved as a verified false detection for future ML evaluation.")
            if (cleanNote.isNotBlank()) {
                append(" User note: $cleanNote")
            }
        }

        val entity = PostFallAssessmentEntity(
            assessmentId = draft.assessmentId,
            linkedEventId = draft.linkedEventId,
            timestampMillis = System.currentTimeMillis(),
            completionStatus = AssessmentCompletionStatus.FALSE_DETECTION_CLOSED.name,
            incidentVerification = verification.name,
            isFalseDetection = true,
            isPhoneDrop = verification.isPhoneDrop,
            painLevel = draft.painLevel.name,
            difficultyBreathing = draft.difficultyBreathing.name,
            dizzyOrWeak = draft.dizzyOrWeak.name,
            confusionOrDifficultyResponding = draft.confusionOrDifficultyResponding.name,
            hitHead = draft.hitHead.name,
            needMedicalAssistance = draft.needMedicalAssistance.name,
            userNotes = cleanNote,
            advisoryHeadline = "False Detection Recorded — ${verification.displayLabel}",
            advisoryExplanation = detailSummary
        )

        scope.launch(Dispatchers.IO) {
            try {
                eventDao.insertPostFallAssessment(entity)
            } catch (_: Exception) {
            }
            recordQuestionnaireFeedbackForPersonalLearning(
                draft = draft.copy(incidentVerification = verification)
            )
        }

        logEvent(
            type = SafetyEventType.POST_FALL_FALSE_DETECTION_LOGGED,
            summary = detailSummary,
            channel = falseAlarmTag,
            deliveryOutcome = "False alarm closed safely — no emergency alert sent"
        )
    }

    /**
     * Evaluates the user's completed Post-Fall Assessment against their voluntary Medical ID
     * using the Hybrid AI Health Cross-Check Engine (deterministic clinical rules + Gemini),
     * saves the assessment and advisory to Room, and displays the Safety Advisory card.
     */
    fun submitPostFallAssessment() {
        val draft = _activePostFallAssessmentDraft.value ?: return
        val currentMedicalId = _medicalIdProfile.value
        _isEvaluatingHealthCrossCheck.value = true

        // Instant deterministic evaluation so UI never blocks if network is slow
        val deterministicResult = AiHealthCrossCheckEngine.evaluateDeterministic(draft, currentMedicalId)
        _latestHealthCrossCheckResult.value = deterministicResult

        scope.launch {
            val finalResult = try {
                AiHealthCrossCheckEngine.evaluateHybrid(draft, currentMedicalId)
            } catch (_: Exception) {
                deterministicResult
            }
            _latestHealthCrossCheckResult.value = finalResult
            _isEvaluatingHealthCrossCheck.value = false
            _activePostFallAssessmentDraft.value = null

            val entity = PostFallAssessmentEntity(
                assessmentId = draft.assessmentId,
                linkedEventId = draft.linkedEventId,
                timestampMillis = finalResult.evaluatedAtEpochMillis,
                completionStatus = AssessmentCompletionStatus.COMPLETED.name,
                incidentVerification = (draft.incidentVerification ?: IncidentVerificationType.EXPERIENCED_FALL).name,
                isFalseDetection = draft.incidentVerification?.isFalseDetection == true,
                isPhoneDrop = draft.incidentVerification?.isPhoneDrop == true,
                painLevel = draft.painLevel.name,
                difficultyBreathing = draft.difficultyBreathing.name,
                dizzyOrWeak = draft.dizzyOrWeak.name,
                confusionOrDifficultyResponding = draft.confusionOrDifficultyResponding.name,
                hitHead = draft.hitHead.name,
                needMedicalAssistance = draft.needMedicalAssistance.name,
                injuryLocationsJson = PostFallJsonCodec.encodeStringList(draft.injuryLocations.map { it.name }),
                recoveryAbility = draft.recoveryAbility.name,
                bleedingReported = draft.bleedingReported,
                userNotes = draft.userNotes.trim(),
                severeSymptomDetected = finalResult.severeSymptomDetected,
                autoSosTriggered = finalResult.autoSosTriggered,
                triggeredAlertId = finalResult.triggeredAlertId,
                riskTier = finalResult.riskTier.name,
                advisoryHeadline = finalResult.advisoryHeadline,
                advisoryExplanation = finalResult.advisoryExplanation,
                crossCheckHighlightsJson = PostFallJsonCodec.encodeStringList(finalResult.crossCheckHighlights),
                recommendedNextStepsJson = PostFallJsonCodec.encodeStringList(finalResult.recommendedNextSteps),
                usedMedicalIdContext = finalResult.usedMedicalIdContext,
                evaluationSource = finalResult.evaluationSource
            )

            try {
                eventDao.insertPostFallAssessment(entity)
            } catch (_: Exception) {
            }

            if (!draft.isManualSelfCheck || draft.incidentVerification != null) {
                recordQuestionnaireFeedbackForPersonalLearning(draft)
            }

            val symptomSummary = buildString {
                append("Verification: ${(draft.incidentVerification ?: IncidentVerificationType.EXPERIENCED_FALL).displayLabel} • ")
                append("Pain: ${draft.painLevel.displayLabel} • ")
                append("Breathing difficulty: ${draft.difficultyBreathing.displayLabel} • ")
                append("Dizzy/weak: ${draft.dizzyOrWeak.displayLabel} • ")
                append("Confusion: ${draft.confusionOrDifficultyResponding.displayLabel} • ")
                append("Hit head: ${draft.hitHead.displayLabel} • ")
                append("Need medical help: ${draft.needMedicalAssistance.displayLabel}")
            }

            logEvent(
                type = SafetyEventType.POST_FALL_ASSESSMENT_COMPLETED,
                summary = "$symptomSummary | Advisory: ${finalResult.riskTier.badgeLabel} — ${finalResult.advisoryHeadline}",
                channel = if (finalResult.usedMedicalIdContext) {
                    "Post-Fall Assessment + Medical ID Cross-Check (${finalResult.evaluationSource})"
                } else {
                    "Post-Fall Assessment (${finalResult.evaluationSource})"
                },
                deliveryOutcome = finalResult.riskTier.badgeLabel
            )

            logEvent(
                type = SafetyEventType.AI_HEALTH_CROSS_CHECK_GENERATED,
                summary = "${finalResult.advisoryHeadline}: ${finalResult.crossCheckHighlights.joinToString(" • ").ifBlank { finalResult.advisoryExplanation }}",
                channel = finalResult.evaluationSource,
                deliveryOutcome = finalResult.riskTier.badgeLabel
            )
        }
    }

    /**
     * Allows the user to skip or dismiss the Post-Fall Assessment at any time.
     */
    fun skipPostFallAssessment(reason: String = "User skipped post-fall health assessment") {
        val draft = _activePostFallAssessmentDraft.value
        _activePostFallAssessmentDraft.value = null
        _isEvaluatingHealthCrossCheck.value = false
        if (draft != null) {
            val entity = PostFallAssessmentEntity(
                assessmentId = draft.assessmentId,
                linkedEventId = draft.linkedEventId,
                timestampMillis = System.currentTimeMillis(),
                completionStatus = AssessmentCompletionStatus.SKIPPED.name,
                incidentVerification = draft.incidentVerification?.name ?: "SKIPPED",
                isFalseDetection = false,
                isPhoneDrop = false,
                painLevel = draft.painLevel.name,
                difficultyBreathing = draft.difficultyBreathing.name,
                dizzyOrWeak = draft.dizzyOrWeak.name,
                confusionOrDifficultyResponding = draft.confusionOrDifficultyResponding.name,
                hitHead = draft.hitHead.name,
                needMedicalAssistance = draft.needMedicalAssistance.name,
                userNotes = draft.userNotes.trim()
            )
            scope.launch(Dispatchers.IO) {
                try {
                    eventDao.insertPostFallAssessment(entity)
                } catch (_: Exception) {
                }
            }
            logEvent(
                type = SafetyEventType.POST_FALL_ASSESSMENT_SKIPPED,
                summary = reason,
                channel = "Post-Fall Assessment",
                deliveryOutcome = "Skipped"
            )
        }
    }

    fun dismissHealthCrossCheckResult() {
        _latestHealthCrossCheckResult.value = null
        _activePostFallAssessmentDraft.value = null
        _isEvaluatingHealthCrossCheck.value = false
    }

    /**
     * Saves the user's voluntary Medical ID profile locally in Room and (when consented)
     * syncs it to their private authenticated Firestore document (`users/{uid}/medical_id/profile`).
     */
    fun saveMedicalIdProfile(profile: MedicalIdProfile) {
        val resolvedId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        val timestamped = profile.copy(
            profileId = resolvedId,
            updatedAtEpochMillis = System.currentTimeMillis()
        )
        _medicalIdProfile.value = timestamped
        scope.launch(Dispatchers.IO) {
            try {
                eventDao.upsertMedicalIdProfile(MedicalIdEntity.fromDomain(timestamped))
                eventDao.upsertMedicalIdProfile(
                    MedicalIdEntity.fromDomain(timestamped.copy(profileId = "local_default"))
                )
            } catch (_: Exception) {
            }
            if (!activeObservedUid.isNullOrBlank()) {
                if (timestamped.syncToCloudFirestore && timestamped.isConfigured) {
                    firestoreRepository.upsertMedicalIdProfile(timestamped)
                } else if (!timestamped.syncToCloudFirestore) {
                    firestoreRepository.deleteMedicalIdProfile()
                }
            }
            logEvent(
                type = SafetyEventType.MEDICAL_ID_UPDATED,
                summary = "Medical ID updated (Conditions: ${timestamped.knownConditions.size}, Medications: ${timestamped.medications.size}, AI Cross-Check: ${if (timestamped.allowAiCrossCheck) "On" else "Off"}, Cloud Sync: ${if (timestamped.syncToCloudFirestore) "On" else "Off"})",
                channel = if (timestamped.syncToCloudFirestore) "Local Room + Private Cloud Backup" else "Local Room Only",
                deliveryOutcome = "Saved"
            )
        }
    }

    /**
     * Clears the user's Medical ID from both local Room storage and Cloud Firestore.
     */
    fun clearMedicalIdProfile() {
        val resolvedId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        _medicalIdProfile.value = MedicalIdProfile(profileId = resolvedId)
        scope.launch(Dispatchers.IO) {
            try {
                eventDao.clearAllMedicalIdProfiles()
            } catch (_: Exception) {
            }
            if (!activeObservedUid.isNullOrBlank()) {
                firestoreRepository.deleteMedicalIdProfile()
            }
        }
    }

    private suspend fun syncMedicalIdForAuthenticatedUser(uid: String) {
        try {
            val localUserEntity = eventDao.getMedicalIdProfileSnapshot(uid)
                ?: eventDao.getMedicalIdProfileSnapshot("local_default")
            val localProfile = localUserEntity?.toDomain()
            if (localProfile != null && localProfile.isConfigured) {
                _medicalIdProfile.value = localProfile.copy(profileId = uid)
            }
            val remoteProfile = firestoreRepository.loadMedicalIdProfile().getOrNull()
            if (remoteProfile != null && remoteProfile.isConfigured) {
                val shouldUseRemote = localProfile == null ||
                    !localProfile.isConfigured ||
                    remoteProfile.updatedAtEpochMillis >= localProfile.updatedAtEpochMillis
                if (shouldUseRemote) {
                    _medicalIdProfile.value = remoteProfile
                    eventDao.upsertMedicalIdProfile(MedicalIdEntity.fromDomain(remoteProfile))
                    eventDao.upsertMedicalIdProfile(
                        MedicalIdEntity.fromDomain(remoteProfile.copy(profileId = "local_default"))
                    )
                }
            } else if (localProfile != null && localProfile.isConfigured && localProfile.syncToCloudFirestore) {
                firestoreRepository.upsertMedicalIdProfile(localProfile.copy(profileId = uid))
            }
        } catch (_: Exception) {
        }
    }

    fun requestImmediateHelp(
        reasonNote: String = "Possible fall detected — Send SOS Now",
        onComplete: (() -> Unit)? = null
    ) {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        val correlationId = activeCheckInCorrelationId ?: EmergencyDispatchDiagnostics.newCorrelationId("fall_help")
        val targetAlertId = activeCheckInAlertId
            ?: "alert_${_activeCandidateSummary.value?.eventId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(12)}"
        activeCheckInAlertId = targetAlertId
        _isEmergencyScreenActive.value = true
        if (dispatchedFullScreenAlertIds.add(targetAlertId)) {
            alertDeliveryGateway.postEmergencyTriggeredFullScreenNotification(
                alertId = targetAlertId,
                eventSummary = _activeCandidateSummary.value,
                contact = _preferencesState.value.trustedContact
            )
        }
        EmergencyDispatchDiagnostics.log(
            correlationId = correlationId,
            stage = EmergencyDispatchLogStage.FALL_EVENT_CONFIRMED,
            alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
            alertId = targetAlertId,
            detail = "User selected I need help / Send SOS Now: $reasonNote"
        )
        scope.launch {
            try {
                dispatchEmergencyAlert(
                    alertType = EmergencyAlertDispatchType.AUTOMATIC_FALL,
                    correlationId = correlationId,
                    triggerReason = reasonNote,
                    eventType = SafetyEventType.HELP_REQUESTED_NOW,
                    overrideAlertId = targetAlertId
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Manual SOS action triggered directly from the Home screen SOS button,
     * completely independent of automatic fall detection or sensor monitoring state.
     */
    fun triggerManualSos(onComplete: (() -> Unit)? = null) {
        countdownJob?.cancel()
        alertDeliveryGateway.cancelCheckInNotification()
        val correlationId = EmergencyDispatchDiagnostics.newCorrelationId("sos")
        val manualSosAlertId = "sos_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
        activeCheckInAlertId = manualSosAlertId
        activeCheckInCorrelationId = correlationId
        _activeCandidateSummary.value = null

        EmergencyDispatchDiagnostics.log(
            correlationId = correlationId,
            stage = EmergencyDispatchLogStage.MANUAL_SOS_BUTTON_PRESSED,
            alertType = EmergencyAlertDispatchType.MANUAL_SOS,
            alertId = manualSosAlertId,
            detail = "Manual SOS button pressed by user"
        )

        scope.launch {
            try {
                dispatchEmergencyAlert(
                    alertType = EmergencyAlertDispatchType.MANUAL_SOS,
                    correlationId = correlationId,
                    triggerReason = "SOS requested",
                    eventType = SafetyEventType.HELP_REQUESTED_NOW,
                    overrideAlertId = manualSosAlertId
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Sends a clearly labelled TEST alert (`isTestAlert = true`) through the real
     * GPS + Firestore + FCM + Gmail API pipeline so the user can verify both
     * email delivery and two-device phone pop-up notifications.
     */
    fun sendTestAlert(onComplete: (() -> Unit)? = null) {
        val correlationId = EmergencyDispatchDiagnostics.newCorrelationId("test")
        val testAlertId = "test_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
        EmergencyDispatchDiagnostics.log(
            correlationId = correlationId,
            stage = EmergencyDispatchLogStage.TEST_ALERT_BUTTON_PRESSED,
            alertType = EmergencyAlertDispatchType.TEST,
            alertId = testAlertId,
            detail = "Send Test Alert button pressed by user"
        )
        scope.launch {
            try {
                dispatchEmergencyAlert(
                    alertType = EmergencyAlertDispatchType.TEST,
                    correlationId = correlationId,
                    triggerReason = "Manual test alert triggered by user (TEST — not an emergency)",
                    eventType = SafetyEventType.TEST_ALERT_TRIGGERED,
                    overrideAlertId = testAlertId
                )
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Resolves the current user's preferences and saved emergency contact reliably across
     * foreground UI, background service, and broadcast receiver execution contexts.
     */
    private suspend fun resolveCurrentUserPreferences(): UserPreferencesState {
        val inMemory = _preferencesState.value
        if (inMemory.trustedContact.isValid) {
            return inMemory
        }
        val uid = activeObservedUid?.takeIf { it.isNotBlank() } ?: try {
            Firebase.auth.currentUser?.uid?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        if (!uid.isNullOrBlank()) {
            val scoped = preferencesRepository.getPreferencesForUser(uid)
            if (scoped.trustedContact.isValid) {
                _preferencesState.value = scoped
                return scoped
            }
        }
        val flowPrefs = try {
            withTimeoutOrNull(1000L) {
                preferencesRepository.preferencesFlow.first()
            }
        } catch (_: Exception) {
            null
        }
        if (flowPrefs != null && flowPrefs.trustedContact.isValid) {
            _preferencesState.value = flowPrefs
            return flowPrefs
        }
        return flowPrefs ?: inMemory
    }

    /**
     * Captures GPS coordinates quickly for emergency dispatch without blocking on slow
     * third-party HTTP enrichment calls before the Firestore alert document is created.
     */
    private suspend fun captureFastEmergencyLocation(): Pair<GpsLocationSnapshot?, String> {
        _isCapturingLocation.value = true
        return try {
            val outcome = withTimeoutOrNull(3500L) {
                locationProvider.captureCurrentLocation(enrichWithGeminiMapsContext = false)
            }
            when (outcome) {
                is LocationCaptureOutcome.Success -> {
                    _latestLocationSnapshot.value = outcome.snapshot
                    val diag = "Live GPS fix attached: ${outcome.snapshot.formattedCoordinates}"
                    _locationDiagnostics.value = diag
                    outcome.snapshot to diag
                }
                is LocationCaptureOutcome.Unavailable -> {
                    val cached = _latestLocationSnapshot.value
                    if (cached != null) {
                        val diag = "Recent GPS fix attached: ${cached.formattedCoordinates}"
                        _locationDiagnostics.value = diag
                        cached to diag
                    } else {
                        _locationDiagnostics.value = outcome.reason
                        null to outcome.reason
                    }
                }
                null -> {
                    val cached = _latestLocationSnapshot.value
                    if (cached != null) {
                        val diag = "Recent GPS fix attached: ${cached.formattedCoordinates}"
                        _locationDiagnostics.value = diag
                        cached to diag
                    } else {
                        val diag = "GPS location timed out; dispatching alert immediately without waiting."
                        _locationDiagnostics.value = diag
                        null to diag
                    }
                }
            }
        } finally {
            _isCapturingLocation.value = false
        }
    }

    /**
     * Unified emergency alert dispatch used by:
     * - `EmergencyAlertDispatchType.TEST` ("Send Test Alert")
     * - `EmergencyAlertDispatchType.MANUAL_SOS` (Manual SOS button)
     * - `EmergencyAlertDispatchType.AUTOMATIC_FALL` (Check-in timeout or "Send SOS Now")
     */
    private suspend fun dispatchEmergencyAlert(
        alertType: EmergencyAlertDispatchType,
        correlationId: String = EmergencyDispatchDiagnostics.newCorrelationId(),
        triggerReason: String,
        eventType: SafetyEventType,
        overrideAlertId: String? = null
    ): EmergencyDispatchOutcome = withContext(NonCancellable) {
        val isTestAlert = alertType.isTestAlert
        val prefs = resolveCurrentUserPreferences()
        val contact = prefs.trustedContact
        val summary = if (alertType == EmergencyAlertDispatchType.AUTOMATIC_FALL) {
            _activeCandidateSummary.value
        } else {
            null
        }

        if (!isTestAlert &&
            overrideAlertId == null &&
            eventType != SafetyEventType.POST_FALL_SEVERE_SYMPTOM_SOS &&
            summary != null &&
            cancelledCandidateEventIds.contains(summary.eventId)
        ) {
            return@withContext EmergencyDispatchOutcome.DuplicateSuppressed(
                correlationId = correlationId,
                alertId = "cancelled_${summary.eventId}",
                alertType = alertType
            )
        }

        val alertId = when {
            !overrideAlertId.isNullOrBlank() -> overrideAlertId
            alertType == EmergencyAlertDispatchType.TEST ->
                "test_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
            alertType == EmergencyAlertDispatchType.MANUAL_SOS ->
                activeCheckInAlertId ?: "sos_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
            else ->
                activeCheckInAlertId ?: "alert_${summary?.eventId ?: UUID.randomUUID().toString().take(12)}"
        }

        if (!isTestAlert && (
            !dispatchedEmergencyAlertIds.add(alertId) ||
                emergencyAlertDispatcher.hasDispatchedAlertId(alertId)
            )
        ) {
            EmergencyDispatchDiagnostics.log(
                correlationId = correlationId,
                stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                alertType = alertType,
                alertId = alertId,
                detail = "DUPLICATE_SUPPRESSED: Suppressed duplicate dispatch for already processed alertId=$alertId"
            )
            return@withContext EmergencyDispatchOutcome.DuplicateSuppressed(
                correlationId = correlationId,
                alertId = alertId,
                alertType = alertType
            )
        }

        _monitoringState.value = MonitoringState.ALERT_PENDING
        _sosWorkflowState.value = SosWorkflowState.SOS_TRIGGERED
        _deliveryStatus.value = DeliveryConfigStatus.SENDING
        _deliveryStatusDetail.value = DeliveryConfigStatus.SENDING.detail
        _requiresGmailAuthorization.value = false

        // Optional local SOS audio snapshot (bounded so it can never block Firestore alert creation)
        var capturedAudioClip: SosAudioClipMetadata? = null
        var resolvedAudioStatusKey = "DISABLED"
        var resolvedAudioStatusNote: String? = null

        if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted) {
            _sosWorkflowState.value = SosWorkflowState.AUDIO_FINALIZING
            val postSeconds = if (isTestAlert) 0 else prefs.postTriggerAudioSeconds.coerceIn(0, 2)
            val audioOutcome = withTimeoutOrNull(2500L) {
                sosAudioRecorder.finalizeSosAudioForAlert(alertId, postSeconds)
            }
            when (audioOutcome) {
                is SosAudioFinalizeOutcome.Captured -> {
                    capturedAudioClip = audioOutcome.metadata
                    _lastCapturedAudioClip.value = capturedAudioClip
                    _sosAudioStatus.value = SosAudioStatus.SOS_AUDIO_CAPTURED
                    resolvedAudioStatusKey = "SOS_AUDIO_CAPTURED"
                    resolvedAudioStatusNote =
                        "Attached (${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s ${capturedAudioClip.containerExtension.uppercase(Locale.US)} audio clip captured)"
                    logEvent(
                        type = SafetyEventType.SOS_AUDIO_CAPTURED,
                        summary = "Captured ${String.format(Locale.US, "%.1f", capturedAudioClip.durationSeconds)}s SOS audio clip (${capturedAudioClip.containerExtension.uppercase(Locale.US)})",
                        channel = "Local Rolling Buffer"
                    )
                }
                is SosAudioFinalizeOutcome.Unavailable -> {
                    _sosAudioStatus.value = audioOutcome.status
                    resolvedAudioStatusKey = audioOutcome.status.name
                    resolvedAudioStatusNote = audioOutcome.reason
                }
                null -> {
                    resolvedAudioStatusKey = "AUDIO_MONITORING_UNAVAILABLE"
                    resolvedAudioStatusNote = "Audio finalization timed out; alert dispatched immediately."
                }
            }
        } else {
            resolvedAudioStatusKey = "DISABLED"
            resolvedAudioStatusNote = "Not included (SOS audio recording is disabled in Privacy Settings)"
        }

        val consentedMedicalIdSummary = _medicalIdProfile.value.formatEmergencyAlertSummary()
        val activeExplainability = summary?.explainabilityRecord ?: _latestFeatures.value.explainabilityRecord

        _sosWorkflowState.value = SosWorkflowState.SENDING_EMAIL

        // Execute unified Firestore + FCM + Email dispatch pipeline
        val outcome = emergencyAlertDispatcher.dispatch(
            alertType = alertType,
            correlationId = correlationId,
            alertIdOverride = alertId,
            triggerReason = triggerReason,
            candidateSummary = summary,
            googleChatConfig = prefs.googleChatConfig,
            gmailAccessToken = prefs.gmailAccessToken,
            audioClip = capturedAudioClip,
            audioStatusKey = resolvedAudioStatusKey,
            audioStatusNote = resolvedAudioStatusNote,
            medicalIdSummary = consentedMedicalIdSummary,
            mlExplainability = if (!isTestAlert) activeExplainability else null,
            onProgressStateChanged = { status, detail ->
                _deliveryStatus.value = status
                _deliveryStatusDetail.value = detail
            }
        )

        when (outcome) {
            is EmergencyDispatchOutcome.Dispatched -> {
                _lastMessagePreview.value = outcome.payload
                _monitoringState.value = MonitoringState.ALERT_SENT
                _sosWorkflowState.value = SosWorkflowState.ALERT_SENT
                _deliveryStatus.value = outcome.deliveryStatus
                _deliveryStatusDetail.value = outcome.statusDetail
                _requiresGmailAuthorization.value = outcome.requiresGmailAuth
                if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted && outcome.emailSent) {
                    _sosAudioStatus.value = SosAudioStatus.SOS_EMAIL_SENT
                }

                logEvent(
                    type = eventType,
                    summary = "$triggerReason — Dispatched (${alertType.wireKey}) to ${contact.contactName} (${outcome.recipientEmail})",
                    channel = contact.preferredChannel.label,
                    deliveryOutcome = outcome.statusDetail,
                    explainability = activeExplainability
                )

                // Record automatic or manual SOS event in on-device Personal Learning history
                if (!isTestAlert) {
                    val outcomeState = if (outcome.fcmAccepted || outcome.emailSent) {
                        SosAlertOutcomeState.DISPATCH_ACCEPTED
                    } else if (outcome.firestoreCreated) {
                        SosAlertOutcomeState.ALERT_CREATED
                    } else {
                        SosAlertOutcomeState.NONE
                    }
                    recordSosEventForPersonalLearning(
                        alertType = alertType,
                        alertId = alertId,
                        candidateSummary = summary,
                        explainability = activeExplainability,
                        alertOutcome = outcomeState
                    )
                }

                // Upload SOS audio to Firebase Storage asynchronously AFTER the alert document has already been created
                val clipToUpload = capturedAudioClip
                if (clipToUpload != null && outcome.firestoreCreated) {
                    val uid = try {
                        Firebase.auth.currentUser?.uid.orEmpty()
                    } catch (_: Exception) {
                        ""
                    }.ifBlank { activeObservedUid.orEmpty() }
                    if (uid.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val localFile = File(clipToUpload.localFilePath.orEmpty())
                            val uploadRes = withTimeoutOrNull(8000L) {
                                sosAudioStorageUploader.uploadSosAudioRecording(
                                    alertId = alertId,
                                    localFile = localFile,
                                    clipMetadata = clipToUpload,
                                    userIdOverride = uid
                                )
                            }
                            if (uploadRes is SosAudioStorageUploadResult.Uploaded) {
                                _lastCapturedAudioClip.value = uploadRes.updatedMetadata
                                firestoreRepository.updateAlertAudioStatus(
                                    alertId = alertId,
                                    audioStatus = "UPLOADED",
                                    durationSeconds = clipToUpload.durationSeconds,
                                    storageObjectPath = uploadRes.storageObjectPath,
                                    audioDownloadUrl = uploadRes.downloadUrl
                                )
                            }
                        }
                    }
                }
            }

            is EmergencyDispatchOutcome.Failed -> {
                // Allow retry if dispatch failed before reaching recipient
                dispatchedEmergencyAlertIds.remove(alertId)
                _monitoringState.value = MonitoringState.ALERT_FAILED
                _sosWorkflowState.value = SosWorkflowState.ALERT_FAILED
                _deliveryStatus.value = outcome.deliveryStatus
                _deliveryStatusDetail.value = outcome.reason
                _requiresGmailAuthorization.value = outcome.requiresGmailAuth

                if (prefs.sosAudioEnabled && prefs.sosAudioConsentGranted &&
                    _sosAudioStatus.value != SosAudioStatus.AUDIO_UPLOAD_FAILED
                ) {
                    _sosAudioStatus.value = SosAudioStatus.SOS_EMAIL_FAILED
                }

                logEvent(
                    type = eventType,
                    summary = "$triggerReason — Delivery failed: ${outcome.reason}",
                    channel = contact.preferredChannel.label,
                    deliveryOutcome = outcome.reason
                )
            }

            is EmergencyDispatchOutcome.DuplicateSuppressed -> {
                // Already handled or in flight
            }
        }

        // Return to monitoring after showing confirmation state
        scope.launch {
            delay(6000L)
            if (_monitoringState.value == MonitoringState.ALERT_SENT ||
                _monitoringState.value == MonitoringState.ALERT_FAILED
            ) {
                activeCheckInAlertId = null
                activeCheckInCorrelationId = null
                _activeCandidateSummary.value = null
                val nextMonState = if (sensorManager.hasAccelerometer || preferencesRepository.isMonitoringEnabled(activeObservedUid)) {
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
        outcome
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

    private suspend fun loadPersonalLearningForUser(userId: String) {
        val cleanId = userId.ifBlank { "local_default" }
        try {
            val exampleEntities = eventDao.getPersonalLearningExamplesSnapshot(cleanId)
            val examples = exampleEntities.map { it.toDomain() }
            val existingProfile = eventDao.getPersonalMovementProfileSnapshot(cleanId)?.toDomain()
            val recomputed = PersonalLearningEngine.recomputePersonalMovementProfile(
                userId = cleanId,
                examples = examples,
                personalisationEnabled = existingProfile?.personalisationEnabled ?: true,
                previousSuppressedCount = existingProfile?.suppressedFalseAlarmsCount ?: 0,
                nowEpochMillis = existingProfile?.lastUpdatedEpochMillis?.takeIf { it > 0L }
                    ?: (examples.maxOfOrNull { it.timestampEpochMillis } ?: 0L)
            )
            eventDao.upsertPersonalMovementProfile(PersonalMovementProfileEntity.fromDomain(recomputed))
            _personalLearningExamples.value = examples
            _personalMovementProfile.value = recomputed
            detector.setPersonalMovementProfile(recomputed)
        } catch (_: Exception) {
        }
    }

    /**
     * Captures an Automatic Fall SOS event (initially `UNVERIFIED_SOS_EVENT` awaiting user feedback)
     * or a Manual SOS event (`MANUAL_SOS_EVENT` — not a fall training label) in local Room storage.
     */
    private suspend fun recordSosEventForPersonalLearning(
        alertType: EmergencyAlertDispatchType,
        alertId: String,
        candidateSummary: CandidateEventSummary?,
        explainability: com.example.domain.MlExplainabilityRecord?,
        alertOutcome: SosAlertOutcomeState
    ) {
        if (alertType == EmergencyAlertDispatchType.TEST) return
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        val (rawSnapshot, cnnResult) = detector.getLastCandidateSensorContext()

        val effectiveSnapshot = when {
            rawSnapshot.windowSampleCount >= 10 -> rawSnapshot
            explainability != null && explainability.windowSnapshot.windowSampleCount >= 10 ->
                explainability.windowSnapshot
            candidateSummary != null -> SensorWindowSnapshot(
                windowSampleCount = 200,
                empiricalSamplingRateHz = 50.0f,
                minTotalAccelG = (candidateSummary.minMagnitude / MotionSample.GRAVITY_EARTH_MS2).coerceAtLeast(0.1f),
                peakTotalAccelMs2 = candidateSummary.peakMagnitude.coerceAtLeast(9.81f),
                peakVerticalBodyAccelG = (candidateSummary.peakDynamicAccelerationMs2 / MotionSample.GRAVITY_EARTH_MS2).coerceAtLeast(0f),
                peakHorizontalBodyAccelG = (candidateSummary.peakDynamicAccelerationMs2 / (MotionSample.GRAVITY_EARTH_MS2 * 2f)).coerceAtLeast(0f),
                peakJerkGPerSec = 25.0f,
                peakGyroDegPerSec = candidateSummary.peakGyroDegPerSec,
                postImpactStillnessStdDevMs2 = candidateSummary.postImpactStillnessStdDev,
                hasGyroscope = candidateSummary.peakGyroDegPerSec > 0f
            )
            else -> rawSnapshot
        }

        try {
            // Avoid overwriting an event that has already been validated by a questionnaire
            val existingByAlert = if (alertId.isNotBlank()) {
                eventDao.getPersonalLearningExampleByAlertId(resolvedUserId, alertId)?.toDomain()
            } else null
            val linkedEventKey = candidateSummary?.eventId?.takeIf { it.isNotBlank() } ?: alertId
            val existingByEvent = if (existingByAlert == null && linkedEventKey.isNotBlank()) {
                eventDao.getPersonalLearningExampleByLinkedEventId(resolvedUserId, linkedEventKey)?.toDomain()
            } else null
            val existing = existingByAlert ?: existingByEvent

            val recordToSave = if (existing != null && existing.questionnaireCompleted) {
                // Questionnaire was already completed before or during SOS dispatch; attach alertId and outcome only
                existing.copy(
                    alertId = alertId.ifBlank { existing.alertId },
                    alertOutcome = if (existing.recipientAcknowledged) {
                        SosAlertOutcomeState.RECIPIENT_ACKNOWLEDGED
                    } else {
                        alertOutcome
                    }
                )
            } else if (alertType == EmergencyAlertDispatchType.MANUAL_SOS) {
                PersonalLearningEngine.createManualSosLearningEvent(
                    userId = resolvedUserId,
                    alertId = alertId,
                    snapshot = effectiveSnapshot.takeIf { it.windowSampleCount > 0 },
                    cnnResult = cnnResult,
                    alertOutcome = alertOutcome
                )
            } else {
                PersonalLearningEngine.createAutomaticSosLearningEvent(
                    userId = resolvedUserId,
                    alertId = alertId,
                    linkedEventId = linkedEventKey,
                    snapshot = if (effectiveSnapshot.windowSampleCount >= 10) {
                        effectiveSnapshot
                    } else {
                        SensorWindowSnapshot(
                            windowSampleCount = 200,
                            empiricalSamplingRateHz = 50.0f,
                            minTotalAccelG = 0.55f,
                            peakTotalAccelMs2 = 31.0f,
                            peakVerticalBodyAccelG = 2.0f,
                            peakHorizontalBodyAccelG = 1.0f,
                            peakJerkGPerSec = 28.0f,
                            peakGyroDegPerSec = 125.0f,
                            postImpactStillnessStdDevMs2 = 0.40f
                        )
                    },
                    cnnResult = cnnResult,
                    triggeredByRuleFallback = explainability?.triggeredByRuleFallback ?: false,
                    triggerSource = candidateSummary?.triggerSource ?: "AUTOMATIC_FALL_SOS",
                    alertOutcome = alertOutcome
                )
            }

            eventDao.insertPersonalLearningExample(PersonalLearningExampleEntity.fromDomain(recordToSave))
            val allExamples = eventDao.getPersonalLearningExamplesSnapshot(resolvedUserId).map { it.toDomain() }
            val currentProfile = _personalMovementProfile.value
            val updatedProfile = PersonalLearningEngine.recomputePersonalMovementProfile(
                userId = resolvedUserId,
                examples = allExamples,
                personalisationEnabled = currentProfile.personalisationEnabled,
                previousSuppressedCount = currentProfile.suppressedFalseAlarmsCount,
                nowEpochMillis = System.currentTimeMillis()
            )
            eventDao.upsertPersonalMovementProfile(PersonalMovementProfileEntity.fromDomain(updatedProfile))
            _personalLearningExamples.value = allExamples
            _personalMovementProfile.value = updatedProfile
            detector.setPersonalMovementProfile(updatedProfile)
        } catch (_: Exception) {
        }
    }

    /**
     * Associates a connected emergency contact's alert acknowledgement with the original `alertId`
     * in local Personal Learning history without ever converting the event into a confirmed fall label.
     */
    fun recordRecipientAcknowledgementForPersonalLearning(
        alertId: String,
        acknowledgedByName: String = "Emergency contact"
    ) {
        val cleanAlertId = alertId.trim()
        if (cleanAlertId.isBlank()) return
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        scope.launch(Dispatchers.IO) {
            try {
                val existingEntity = eventDao.getPersonalLearningExampleByAlertId(resolvedUserId, cleanAlertId)
                    ?: eventDao.getPersonalLearningExampleByLinkedEventId(resolvedUserId, cleanAlertId)
                val existing = existingEntity?.toDomain() ?: return@launch
                val updated = PersonalLearningEngine.applyEmergencyContactAcknowledgement(
                    existing = existing,
                    acknowledgedByName = acknowledgedByName
                )
                eventDao.insertPersonalLearningExample(PersonalLearningExampleEntity.fromDomain(updated))
                val allExamples = eventDao.getPersonalLearningExamplesSnapshot(resolvedUserId).map { it.toDomain() }
                val currentProfile = _personalMovementProfile.value
                val updatedProfile = PersonalLearningEngine.recomputePersonalMovementProfile(
                    userId = resolvedUserId,
                    examples = allExamples,
                    personalisationEnabled = currentProfile.personalisationEnabled,
                    previousSuppressedCount = currentProfile.suppressedFalseAlarmsCount,
                    nowEpochMillis = System.currentTimeMillis()
                )
                eventDao.upsertPersonalMovementProfile(PersonalMovementProfileEntity.fromDomain(updatedProfile))
                _personalLearningExamples.value = allExamples
                _personalMovementProfile.value = updatedProfile
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Associates the completed post-detection questionnaire with the candidate event's 4.0s sensor
     * window (or previously recorded `UNVERIFIED_SOS_EVENT` by `alertId` / `linkedEventId`),
     * validates quality and symptom consistency, prevents duplicate training for the same event,
     * saves the example strictly on-device, and updates the user's Layer 2 [PersonalMovementProfile].
     */
    private suspend fun recordQuestionnaireFeedbackForPersonalLearning(draft: PostFallAssessmentDraft) {
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        val (rawSnapshot, cnnResult) = detector.getLastCandidateSensorContext()
        val candidateSummary = _activeCandidateSummary.value

        val effectiveSnapshot = when {
            rawSnapshot.windowSampleCount >= 10 -> rawSnapshot
            candidateSummary != null -> SensorWindowSnapshot(
                windowSampleCount = 200,
                empiricalSamplingRateHz = 50.0f,
                minTotalAccelG = (candidateSummary.minMagnitude / MotionSample.GRAVITY_EARTH_MS2).coerceAtLeast(0.1f),
                peakTotalAccelMs2 = candidateSummary.peakMagnitude.coerceAtLeast(9.81f),
                peakVerticalBodyAccelG = (candidateSummary.peakDynamicAccelerationMs2 / MotionSample.GRAVITY_EARTH_MS2).coerceAtLeast(0f),
                peakHorizontalBodyAccelG = (candidateSummary.peakDynamicAccelerationMs2 / (MotionSample.GRAVITY_EARTH_MS2 * 2f)).coerceAtLeast(0f),
                peakJerkGPerSec = 25.0f,
                peakGyroDegPerSec = candidateSummary.peakGyroDegPerSec,
                postImpactStillnessStdDevMs2 = candidateSummary.postImpactStillnessStdDev,
                hasGyroscope = candidateSummary.peakGyroDegPerSec > 0f
            )
            else -> SensorWindowSnapshot(
                windowSampleCount = 200,
                empiricalSamplingRateHz = 50.0f,
                minTotalAccelG = 0.65f,
                peakTotalAccelMs2 = 28.0f,
                peakVerticalBodyAccelG = 1.8f,
                peakHorizontalBodyAccelG = 0.9f,
                peakJerkGPerSec = 22.0f,
                peakGyroDegPerSec = 110.0f,
                postImpactStillnessStdDevMs2 = 0.45f
            )
        }

        try {
            val alertIdKey = draft.triggeredAlertId?.trim().orEmpty()
            val eventIdKey = draft.linkedEventId.trim()

            val existingByAlert = if (alertIdKey.isNotBlank()) {
                eventDao.getPersonalLearningExampleByAlertId(resolvedUserId, alertIdKey)?.toDomain()
            } else null
            val existingByEvent = if (existingByAlert == null && eventIdKey.isNotBlank()) {
                eventDao.getPersonalLearningExampleByLinkedEventId(resolvedUserId, eventIdKey)?.toDomain()
            } else null
            // Also check if there is a recent unverified automatic SOS event when completing an assessment right after an SOS
            val existing = existingByAlert ?: existingByEvent

            val linkOutcome = PersonalLearningEngine.linkQuestionnaireFeedbackToSosEvent(
                userId = resolvedUserId,
                draft = draft,
                existingEvent = existing,
                fallbackSnapshot = effectiveSnapshot,
                fallbackCnnResult = cnnResult
            )

            when (linkOutcome) {
                is QuestionnaireLinkOutcome.DuplicateIgnored -> {
                    _latestFeedbackLearningExample.value = linkOutcome.existingExample
                    return
                }
                is QuestionnaireLinkOutcome.ValidatedOrUpdated -> {
                    val example = linkOutcome.updatedExample
                    _latestFeedbackLearningExample.value = example
                    eventDao.insertPersonalLearningExample(PersonalLearningExampleEntity.fromDomain(example))
                    val allExamples = eventDao.getPersonalLearningExamplesSnapshot(resolvedUserId).map { it.toDomain() }
                    val currentProfile = _personalMovementProfile.value
                    val updatedProfile = PersonalLearningEngine.recomputePersonalMovementProfile(
                        userId = resolvedUserId,
                        examples = allExamples,
                        personalisationEnabled = currentProfile.personalisationEnabled,
                        previousSuppressedCount = currentProfile.suppressedFalseAlarmsCount,
                        nowEpochMillis = System.currentTimeMillis()
                    )
                    eventDao.upsertPersonalMovementProfile(PersonalMovementProfileEntity.fromDomain(updatedProfile))
                    _personalLearningExamples.value = allExamples
                    _personalMovementProfile.value = updatedProfile
                    detector.setPersonalMovementProfile(updatedProfile)

                    logEvent(
                        type = SafetyEventType.PERSONAL_LEARNING_UPDATED,
                        summary = "${example.learningLabel.displayLabel} (${example.eventStatus.displayLabel}) • Stage: ${updatedProfile.learningStage.displayLabel} (${updatedProfile.confirmedNormalCount} normal, ${updatedProfile.confirmedFallCount} fall, ${updatedProfile.totalExcludedExamples} excluded)",
                        channel = "On-Device Personal Learning (Layer 2/3)",
                        deliveryOutcome = updatedProfile.learningStage.displayLabel
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun onPersonalisedFalseAlarmSuppressed(decisionSummary: String) {
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        val current = _personalMovementProfile.value
        val updated = current.copy(
            suppressedFalseAlarmsCount = current.suppressedFalseAlarmsCount + 1,
            lastUpdatedEpochMillis = System.currentTimeMillis()
        )
        _personalMovementProfile.value = updated
        detector.setPersonalMovementProfile(updated)
        scope.launch(Dispatchers.IO) {
            try {
                eventDao.upsertPersonalMovementProfile(
                    PersonalMovementProfileEntity.fromDomain(updated.copy(userId = resolvedUserId))
                )
            } catch (_: Exception) {
            }
        }
        logEvent(
            type = SafetyEventType.PERSONAL_LEARNING_UPDATED,
            summary = decisionSummary,
            channel = "Layer 3 Personalised Filter",
            deliveryOutcome = "Recurring false alarm suppressed"
        )
    }

    /**
     * Toggles whether Layer 3 Personalised Calibration is active or paused (reverting immediately
     * to the frozen Layer 1 general pre-trained model without deleting stored examples).
     */
    fun setPersonalisationEnabled(enabled: Boolean) {
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        scope.launch(Dispatchers.IO) {
            try {
                val allExamples = eventDao.getPersonalLearningExamplesSnapshot(resolvedUserId).map { it.toDomain() }
                val current = _personalMovementProfile.value
                val updated = PersonalLearningEngine.recomputePersonalMovementProfile(
                    userId = resolvedUserId,
                    examples = allExamples,
                    personalisationEnabled = enabled,
                    previousSuppressedCount = current.suppressedFalseAlarmsCount,
                    nowEpochMillis = System.currentTimeMillis()
                )
                eventDao.upsertPersonalMovementProfile(PersonalMovementProfileEntity.fromDomain(updated))
                _personalMovementProfile.value = updated
                detector.setPersonalMovementProfile(updated)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Resets personal calibration back to the original Layer 1 general pre-trained model baseline
     * and clears stored personal learning examples for the current user.
     */
    fun resetPersonalProfileToGeneralModel() {
        deleteAllPersonalLearningData()
    }

    /**
     * Permanently deletes all on-device personal learning examples and movement profile calibration
     * for the current user and restores the general pre-trained model baseline.
     */
    fun deleteAllPersonalLearningData() {
        val resolvedUserId = activeObservedUid?.takeIf { it.isNotBlank() } ?: "local_default"
        val resetProfile = PersonalMovementProfile(
            userId = resolvedUserId,
            learningStage = PersonalLearningStage.GETTING_STARTED,
            personalisationEnabled = true,
            profileSummaryNote = "Reset to general pre-trained model baseline."
        )
        _personalLearningExamples.value = emptyList()
        _personalMovementProfile.value = resetProfile
        _latestFeedbackLearningExample.value = null
        detector.setPersonalMovementProfile(resetProfile)

        scope.launch(Dispatchers.IO) {
            try {
                eventDao.deletePersonalLearningExamplesForUser(resolvedUserId)
                eventDao.deletePersonalMovementProfileForUser(resolvedUserId)
                eventDao.deletePersonalLearningExamplesForUser("local_default")
                eventDao.deletePersonalMovementProfileForUser("local_default")
            } catch (_: Exception) {
            }
            logEvent(
                type = SafetyEventType.PERSONAL_LEARNING_RESET,
                summary = "Personal movement profile and stored learning examples deleted. Reverted to general pre-trained model baseline.",
                channel = "On-Device Personal Learning",
                deliveryOutcome = "Reset to General Model"
            )
        }
    }

    fun deleteAllPersonalData() {
        stopMonitoring()
        deleteStoredSosAudioRecordings()
        scope.launch {
            eventDao.clearAllEvents()
            eventDao.clearAllPostFallAssessments()
            eventDao.clearAllMedicalIdProfiles()
            eventDao.clearAllPersonalLearningExamples()
            eventDao.clearAllPersonalMovementProfiles()
            if (!activeObservedUid.isNullOrBlank()) {
                firestoreRepository.deleteMedicalIdProfile()
            }
            removeTrustedContactAndRevoke()
            preferencesRepository.clearAllUserData()
            _medicalIdProfile.value = MedicalIdProfile()
            val resetProfile = PersonalMovementProfile()
            _personalMovementProfile.value = resetProfile
            _personalLearningExamples.value = emptyList()
            _latestFeedbackLearningExample.value = null
            detector.setPersonalMovementProfile(resetProfile)
            _activePostFallAssessmentDraft.value = null
            _latestHealthCrossCheckResult.value = null
            _isEvaluatingHealthCrossCheck.value = false
            _lastMessagePreview.value = null
            _latestLocationSnapshot.value = null
            _lastCapturedAudioClip.value = null
            _sosAudioStatus.value = SosAudioStatus.DISABLED
            _deliveryStatus.value = DeliveryConfigStatus.NOT_CONFIGURED
            _deliveryStatusDetail.value = DeliveryConfigStatus.NOT_CONFIGURED.detail
        }
    }

    fun runModelComparisonBenchmark() {
        scope.launch(Dispatchers.Default) {
            try {
                val report = ModelComparisonEvaluator.evaluateHeldOutSuite(appContext)
                _modelComparisonReport.value = report
            } catch (e: Exception) {
                Log.e(TAG, "Benchmark evaluation failed: ${e.message}")
            }
        }
    }

    private fun logEvent(
        type: SafetyEventType,
        summary: String,
        channel: String,
        deliveryOutcome: String = "",
        explainability: com.example.domain.MlExplainabilityRecord? = null
    ) {
        val resolvedExplainability = explainability ?: _latestFeatures.value.explainabilityRecord
        scope.launch(Dispatchers.IO) {
            try {
                eventDao.insertEvent(
                    EventLogEntity(
                        timestampEpochMillis = System.currentTimeMillis(),
                        eventType = type.name,
                        title = type.title,
                        details = summary,
                        channelUsed = channel,
                        deliveryOutcome = deliveryOutcome,
                        modelVersion = resolvedExplainability?.modelVersion.orEmpty(),
                        predictedActivity = resolvedExplainability?.predictedCategory?.name.orEmpty(),
                        modelConfidence = resolvedExplainability?.calibratedConfidence,
                        isUncertain = resolvedExplainability?.isUncertain ?: false,
                        explainabilityJson = resolvedExplainability?.toCompactJson().orEmpty()
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

package com.example.ui

import android.app.Application
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.EventLogEntity
import com.example.data.UserPreferencesState
import com.example.domain.CandidateEventSummary
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.MonitoringState
import com.example.domain.MotionFeatures
import com.example.domain.RecipientDeviceInfo
import com.example.domain.SosAudioClipMetadata
import com.example.domain.SosAudioStatus
import com.example.domain.SosWorkflowState
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.sensor.SafetyMonitoringController
import com.example.sensor.SyntheticSensorTraces
import com.example.ui.auth.GoogleAuthManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SoloSignalViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = SafetyMonitoringController.getInstance(application)

    private val _currentUser = MutableStateFlow<FirebaseUser?>(
        try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        }
    )
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    val monitoringState: StateFlow<MonitoringState> = controller.monitoringState
    val sosWorkflowState: StateFlow<SosWorkflowState> = controller.sosWorkflowState
    val sosAudioStatus: StateFlow<SosAudioStatus> = controller.sosAudioStatus
    val sosAudioBufferedSeconds: StateFlow<Double> = controller.sosAudioBufferedSeconds
    val lastCapturedAudioClip: StateFlow<SosAudioClipMetadata?> = controller.lastCapturedAudioClip
    val latestFeatures: StateFlow<MotionFeatures> = controller.latestFeatures
    val recentMagnitudes: StateFlow<List<Float>> = controller.recentMagnitudes
    val countdownRemaining: StateFlow<Int> = controller.countdownRemaining
    val activeCandidateSummary: StateFlow<CandidateEventSummary?> = controller.activeCandidateSummary
    val deliveryStatus: StateFlow<DeliveryConfigStatus> = controller.deliveryStatus
    val deliveryStatusDetail: StateFlow<String> = controller.deliveryStatusDetail
    val requiresGmailAuthorization: StateFlow<Boolean> = controller.requiresGmailAuthorization
    val lastMessagePreview = controller.lastMessagePreview
    val latestLocationSnapshot: StateFlow<GpsLocationSnapshot?> = controller.latestLocationSnapshot
    val locationDiagnostics: StateFlow<String> = controller.locationDiagnostics
    val isCapturingLocation: StateFlow<Boolean> = controller.isCapturingLocation
    val incomingRecipientAlerts: StateFlow<List<IncomingRecipientAlert>> = controller.incomingRecipientAlerts
    val activeRecipientEmergencyAlert: StateFlow<IncomingRecipientAlert?> = controller.activeRecipientEmergencyAlert
    val incomingContactRequests: StateFlow<List<TrustedContactRequestItem>> = controller.incomingContactRequests
    val registeredDevices: StateFlow<List<RecipientDeviceInfo>> = controller.registeredDevices
    val fcmRegistrationStatus: StateFlow<String> = controller.fcmRegistrationStatus
    val isVerifyingTrustedContact: StateFlow<Boolean> = controller.isVerifyingTrustedContact
    val contactSyncError: StateFlow<String?> = controller.contactSyncError
    val requiresInitialContactSetup: StateFlow<Boolean> = controller.requiresInitialContactSetup
    val preferencesState: StateFlow<UserPreferencesState> = controller.preferencesState

    val eventLogs: StateFlow<List<EventLogEntity>> = controller.eventLogsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val hasAccelerometer: Boolean = controller.hasAccelerometer

    init {
        try {
            Firebase.auth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                _currentUser.value = user
                controller.onAuthenticatedUserChanged(user?.uid, user?.email)
            }
        } catch (_: Exception) {
        }
    }

    fun onAuthSuccess() {
        val user = try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        }
        _currentUser.value = user
        controller.onAuthenticatedUserChanged(user?.uid, user?.email)
    }

    fun completeInitialContactSetup() {
        controller.completeInitialContactSetup()
    }

    fun signOut() {
        viewModelScope.launch {
            controller.stopMonitoring()
            controller.unregisterDeviceOnSignOut()
            controller.onAuthenticatedUserChanged(null, null)
            val app = getApplication<Application>()
            val credentialManager = CredentialManager.create(app)
            GoogleAuthManager.signOut(
                credentialManager = credentialManager,
                onSignOutComplete = {
                    _currentUser.value = null
                },
                scope = viewModelScope
            )
        }
    }

    fun saveGmailAccessToken(token: String) {
        viewModelScope.launch {
            controller.preferencesRepository.saveGmailAccessToken(token)
        }
    }

    fun toggleMonitoring() {
        if (monitoringState.value == MonitoringState.STOPPED) {
            controller.startMonitoring()
        } else {
            controller.stopMonitoring()
        }
    }

    fun startMonitoring() {
        controller.startMonitoring()
    }

    fun stopMonitoring() {
        controller.stopMonitoring()
    }

    fun runTrace(traceType: SyntheticSensorTraces.TraceType) {
        controller.runSyntheticTrace(traceType)
    }

    fun confirmImOkay() {
        controller.cancelCheckInByUser("User tapped 'I'm OK' on check-in screen")
    }

    fun requestHelpNow() {
        controller.requestImmediateHelp("Possible fall detected — Send SOS Now")
    }

    fun triggerManualSos() {
        controller.triggerManualSos()
    }

    fun sendTestAlert() {
        controller.sendTestAlert()
    }

    fun refreshCurrentGpsLocation() {
        controller.refreshCurrentGpsLocation()
    }

    fun refreshAndRegisterFcmDevice() {
        controller.refreshAndRegisterFcmDevice()
    }

    fun acknowledgeIncomingAlert(alertId: String) {
        controller.acknowledgeIncomingAlert(alertId)
    }

    fun openRecipientEmergencyAlert(
        alert: IncomingRecipientAlert?,
        fallbackAlertId: String? = null
    ) {
        controller.openRecipientEmergencyAlert(alert, fallbackAlertId)
    }

    fun dismissActiveRecipientEmergencyAlert() {
        controller.dismissActiveRecipientEmergencyAlert()
    }

    fun testEmergencyBuzzer(durationMillis: Long = 5000L): Boolean {
        return controller.testEmergencyBuzzer(durationMillis)
    }

    fun stopEmergencyBuzzerTest() {
        controller.stopEmergencyBuzzerTest()
    }

    fun isEmergencyBuzzerPlaying(): Boolean {
        return controller.isEmergencyBuzzerPlaying()
    }

    fun verifyAndSaveTrustedContact(
        contact: TrustedContact,
        sendPermissionRequest: Boolean = false,
        markInvitationSent: Boolean = false,
        onComplete: ((TrustedContact) -> Unit)? = null
    ) {
        controller.verifyAndSaveTrustedContact(
            contact = contact,
            sendPermissionRequest = sendPermissionRequest,
            markInvitationSent = markInvitationSent,
            onComplete = onComplete
        )
    }

    fun acceptIncomingContactRequest(linkId: String) {
        controller.acceptIncomingContactRequest(linkId)
    }

    fun saveTrustedContact(contact: TrustedContact) {
        controller.verifyAndSaveTrustedContact(
            contact = contact,
            sendPermissionRequest = true,
            markInvitationSent = false
        )
    }

    fun deleteTrustedContact() {
        controller.removeTrustedContactAndRevoke()
    }

    fun saveAndConnectGoogleChat(
        enabled: Boolean,
        backendEndpointUrl: String,
        spaceName: String,
        spaceDisplayLabel: String
    ) {
        controller.saveAndConnectGoogleChat(
            enabled = enabled,
            backendEndpointUrl = backendEndpointUrl,
            spaceName = spaceName,
            spaceDisplayLabel = spaceDisplayLabel
        )
    }

    fun sendManualGoogleChatVerification() {
        controller.sendManualGoogleChatVerification()
    }

    fun setCountdownSeconds(seconds: Int) {
        viewModelScope.launch {
            controller.preferencesRepository.setCountdownSeconds(seconds)
        }
    }

    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controller.preferencesRepository.setVibrationEnabled(enabled)
        }
    }

    fun setOptionalLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            controller.preferencesRepository.setOptionalLocationEnabled(enabled)
        }
    }

    fun setSosAudioEnabled(enabled: Boolean, consentGranted: Boolean = enabled) {
        controller.setSosAudioEnabled(enabled, consentGranted)
    }

    fun setSosAudioRetentionDays(days: Int) {
        controller.setSosAudioRetentionDays(days)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        controller.onMicrophonePermissionResult(granted)
    }

    fun deleteStoredSosAudioRecordings() {
        controller.deleteStoredSosAudioRecordings()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            controller.preferencesRepository.setOnboardingCompleted(true)
        }
    }

    fun clearAllHistory() {
        controller.clearAllHistory()
    }

    fun deleteEventById(id: Long) {
        controller.deleteEventById(id)
    }

    fun deleteAllPersonalData() {
        controller.deleteAllPersonalData()
    }
}

package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.alerts.AlertDeliveryGateway
import com.example.data.AlertFirestoreRepository
import com.example.domain.AppRoutes
import com.example.domain.IncomingRecipientAlert
import com.example.domain.MonitoringState
import com.example.sensor.SafetyMonitoringController
import com.example.ui.SoloSignalViewModel
import com.example.ui.components.SoloSignalLogo
import com.example.ui.screens.ActivityHistoryScreen
import com.example.ui.screens.CheckInOverlay
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.MedicalIdSheet
import com.example.ui.screens.PostFallAssessmentScreen
import com.example.ui.screens.RecipientEmergencyAlertScreen
import com.example.ui.screens.SettingsPrivacyScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.screens.TrustedContactScreen
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.SoloSignalTheme
import com.example.ui.theme.WarmMistBackground

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val repo = com.example.data.PreferencesRepository(newBase)
        val langCode = repo.getSelectedLanguageCode()
        val wrapped = com.example.util.LocaleHelper.wrap(newBase, langCode)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenDisplay(true)
        handleIncomingAlertIntent(intent)
        setContent {
            SoloSignalTheme {
                SoloSignalAppRoot()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configureLockScreenDisplay(true)
    }

    fun configureLockScreenDisplay(enable: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(enable)
            setTurnScreenOn(enable)
        } else {
            @Suppress("DEPRECATION")
            if (enable) {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            } else {
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
    }

    fun keepScreenActive(enable: Boolean) {
        if (enable) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureLockScreenDisplay(true)
        handleIncomingAlertIntent(intent)
    }

    /**
     * Extracts incoming emergency or test alert parameters from either:
     * 1. A local/foreground notification PendingIntent (`EXTRA_INCOMING_ALERT_ID`, `solosignal://incident/{id}`)
     * 2. A background Firebase Cloud Messaging notification tap (`alertId`, `senderName`, etc. in extras)
     * 3. A Check-In notification tap when a fall incident is active
     * 4. An automatic full-screen emergency intent when a fall countdown completes without user response
     */
    private fun handleIncomingAlertIntent(intent: Intent?) {
        if (intent == null) return

        // 1. Check if an automatic full-screen emergency alert was triggered
        val isOpenEmergency = intent.getBooleanExtra(AlertDeliveryGateway.EXTRA_OPEN_EMERGENCY_ACTIVE, false) ||
            intent.action?.startsWith(AlertDeliveryGateway.ACTION_VIEW_ACTIVE_EMERGENCY) == true ||
            intent.data?.getQueryParameter("type") == "emergency_fullscreen"

        if (isOpenEmergency) {
            configureLockScreenDisplay(true)
            SafetyMonitoringController.getInstance(applicationContext).resumeEmergencyScreen()
            return
        }

        // 2. Check if user tapped a Check-In notification (incident detected on this phone)
        val isOpenCheckIn = intent.getBooleanExtra(AlertDeliveryGateway.EXTRA_OPEN_CHECK_IN, false) ||
            intent.action?.startsWith(AlertDeliveryGateway.ACTION_VIEW_CHECK_IN) == true ||
            intent.data?.getQueryParameter("type") == "checkin"

        if (isOpenCheckIn) {
            configureLockScreenDisplay(true)
            SafetyMonitoringController.getInstance(applicationContext).resumeCheckInOverlay()
            return
        }

        // 2. Extract stable incident ID from extras OR deep link URI
        val extras = intent.extras
        val alertIdFromData = intent.data?.let { uri ->
            if (uri.scheme == "solosignal" && (uri.host == "incident" || uri.host == "alert")) {
                uri.lastPathSegment
            } else if (uri.pathSegments.contains("incident") || uri.pathSegments.contains("alert")) {
                uri.lastPathSegment
            } else null
        }?.takeIf { it.isNotBlank() }

        val alertId = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_ALERT_ID)?.takeIf { it.isNotBlank() }
            ?: extras?.getString("alertId")?.takeIf { it.isNotBlank() }
            ?: alertIdFromData
            ?: return

        val controller = SafetyMonitoringController.getInstance(applicationContext)

        // Do not re-open an alert that has already been acknowledged
        if (AlertDeliveryGateway(applicationContext).isAlertAcknowledged(alertId)) {
            intent.removeExtra(AlertDeliveryGateway.EXTRA_INCOMING_ALERT_ID)
            intent.removeExtra("alertId")
            return
        }

        // Avoid creating duplicate alert screens if already viewing this exact incident
        val currentActive = controller.activeRecipientEmergencyAlert.value
        if (currentActive != null && currentActive.alertId == alertId) {
            return
        }

        val senderName = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_SENDER_NAME)
            ?: extras?.getString("senderName")
            ?: intent.data?.getQueryParameter("senderName")
            ?: "SoloSignal User"
        val senderEmail = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_SENDER_EMAIL)
            ?: extras?.getString("senderEmail")
            ?: intent.data?.getQueryParameter("senderEmail")
            ?: ""
        val triggerReason = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_TRIGGER_REASON)
            ?: extras?.getString("triggerReason")
            ?: intent.data?.getQueryParameter("triggerReason")
            ?: "Emergency check-in alert"
        val locationSummary = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_LOCATION_SUMMARY)
            ?: extras?.getString("locationSummary")
            ?: ""
        val mapsUrl = extras?.getString(AlertDeliveryGateway.EXTRA_INCOMING_MAPS_URL)
            ?: extras?.getString("googleMapsUrl")
            ?: intent.data?.getQueryParameter("mapsUrl")
        val createdAt = if (extras != null && extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_CREATED_AT)) {
            extras.getLong(AlertDeliveryGateway.EXTRA_INCOMING_CREATED_AT, System.currentTimeMillis())
        } else {
            extras?.getString("createdAtMillis")?.toLongOrNull()
                ?: intent.data?.getQueryParameter("createdAt")?.toLongOrNull()
                ?: System.currentTimeMillis()
        }
        val isTest = if (extras != null && extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_IS_TEST)) {
            extras.getBoolean(AlertDeliveryGateway.EXTRA_INCOMING_IS_TEST, false)
        } else {
            extras?.getString("isTestAlert")?.equals("true", ignoreCase = true) == true ||
                intent.data?.getQueryParameter("isTest")?.equals("true", ignoreCase = true) == true ||
                alertId.startsWith("test_")
        }
        val lat = if (extras != null && extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_LATITUDE)) {
            extras.getDouble(AlertDeliveryGateway.EXTRA_INCOMING_LATITUDE)
        } else {
            extras?.getString("latitude")?.toDoubleOrNull()
        }
        val lng = if (extras != null && extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_LONGITUDE)) {
            extras.getDouble(AlertDeliveryGateway.EXTRA_INCOMING_LONGITUDE)
        } else {
            extras?.getString("longitude")?.toDoubleOrNull()
        }

        val initialAlert = IncomingRecipientAlert(
            alertId = alertId,
            senderName = senderName,
            senderEmail = senderEmail,
            recipientName = "Trusted Contact",
            recipientEmail = "",
            isTestAlert = isTest,
            subjectLine = if (isTest) "[TEST] Alert from $senderName" else "Emergency Alert from $senderName",
            bodyText = "$senderName triggered an emergency check-in alert ($triggerReason). $locationSummary",
            triggerReason = triggerReason,
            deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT,
            deliveryStatusDetail = "Opened from emergency alert notification or deep link.",
            googleMapsUrl = mapsUrl,
            locationSummary = locationSummary,
            createdAtMillis = createdAt,
            latitude = lat,
            longitude = lng
        )

        controller.openRecipientEmergencyAlert(initialAlert, alertId)
    }
}

private enum class SoloSignalTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    HOME(AppRoutes.ROUTE_HOME, R.string.nav_home, Icons.Default.Home, "nav_tab_home"),
    ACTIVITY(AppRoutes.ROUTE_ACTIVITY, R.string.nav_activity, Icons.Default.History, "nav_tab_activity"),
    CONTACTS(AppRoutes.ROUTE_CONTACTS, R.string.nav_contacts, Icons.Default.People, "nav_tab_contacts"),
    SETTINGS(AppRoutes.ROUTE_SETTINGS, R.string.nav_settings, Icons.Default.Settings, "nav_tab_settings")
}

@Composable
fun SoloSignalAppRoot(
    viewModel: SoloSignalViewModel = viewModel()
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    val monitoringState by viewModel.monitoringState.collectAsStateWithLifecycle()
    val realMonitoringStatus by viewModel.realMonitoringStatus.collectAsStateWithLifecycle()
    val latestFeatures by viewModel.latestFeatures.collectAsStateWithLifecycle()
    val recentMagnitudes by viewModel.recentMagnitudes.collectAsStateWithLifecycle()
    val countdownRemaining by viewModel.countdownRemaining.collectAsStateWithLifecycle()
    val activeCandidateSummary by viewModel.activeCandidateSummary.collectAsStateWithLifecycle()
    val deliveryStatus by viewModel.deliveryStatus.collectAsStateWithLifecycle()
    val deliveryStatusDetail by viewModel.deliveryStatusDetail.collectAsStateWithLifecycle()
    val requiresGmailAuth by viewModel.requiresGmailAuthorization.collectAsStateWithLifecycle()
    val lastMessagePreview by viewModel.lastMessagePreview.collectAsStateWithLifecycle()
    val latestLocationSnapshot by viewModel.latestLocationSnapshot.collectAsStateWithLifecycle()
    val locationDiagnostics by viewModel.locationDiagnostics.collectAsStateWithLifecycle()
    val isCapturingLocation by viewModel.isCapturingLocation.collectAsStateWithLifecycle()
    val incomingRecipientAlerts by viewModel.incomingRecipientAlerts.collectAsStateWithLifecycle()
    val activeRecipientEmergencyAlert by viewModel.activeRecipientEmergencyAlert.collectAsStateWithLifecycle()
    val incomingContactRequests by viewModel.incomingContactRequests.collectAsStateWithLifecycle()
    val registeredDevices by viewModel.registeredDevices.collectAsStateWithLifecycle()
    val fcmRegistrationStatus by viewModel.fcmRegistrationStatus.collectAsStateWithLifecycle()
    val isVerifyingTrustedContact by viewModel.isVerifyingTrustedContact.collectAsStateWithLifecycle()
    val contactSyncError by viewModel.contactSyncError.collectAsStateWithLifecycle()
    val requiresInitialContactSetup by viewModel.requiresInitialContactSetup.collectAsStateWithLifecycle()
    val sosWorkflowState by viewModel.sosWorkflowState.collectAsStateWithLifecycle()
    val sosAudioStatus by viewModel.sosAudioStatus.collectAsStateWithLifecycle()
    val sosAudioBufferedSeconds by viewModel.sosAudioBufferedSeconds.collectAsStateWithLifecycle()
    val lastCapturedAudioClip by viewModel.lastCapturedAudioClip.collectAsStateWithLifecycle()
    val prefs by viewModel.preferencesState.collectAsStateWithLifecycle()
    val eventLogs by viewModel.eventLogs.collectAsStateWithLifecycle()
    val modelComparisonReport by viewModel.modelComparisonReport.collectAsStateWithLifecycle()
    val activePostFallAssessmentDraft by viewModel.activePostFallAssessmentDraft.collectAsStateWithLifecycle()
    val latestHealthCrossCheckResult by viewModel.latestHealthCrossCheckResult.collectAsStateWithLifecycle()
    val isEvaluatingHealthCrossCheck by viewModel.isEvaluatingHealthCrossCheck.collectAsStateWithLifecycle()
    val medicalIdProfile by viewModel.medicalIdProfile.collectAsStateWithLifecycle()
    val personalMovementProfile by viewModel.personalMovementProfile.collectAsStateWithLifecycle()
    val personalLearningExamples by viewModel.personalLearningExamples.collectAsStateWithLifecycle()
    var showMedicalIdEditor by rememberSaveable { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.restoreMonitoringIfEnabled(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Gate all Cloud Firestore & alert operations behind Google Sign-In
    if (currentUser == null) {
        SignInScreen(
            onAuthSuccess = { viewModel.onAuthSuccess() }
        )
        return
    }

    LaunchedEffect(currentUser?.uid) {
        kotlinx.coroutines.delay(400L)
        viewModel.refreshCurrentGpsLocation()
        viewModel.restoreMonitoringIfEnabled(context.applicationContext)
    }

    var currentTab by rememberSaveable { mutableStateOf(SoloSignalTab.HOME) }
    val isEmergencyActive by viewModel.isEmergencyScreenActive.collectAsStateWithLifecycle()

    // Manage keeping the screen awake while emergency or check-in is displayed
    val activity = context as? MainActivity
    androidx.compose.runtime.DisposableEffect(monitoringState == MonitoringState.CHECK_IN || isEmergencyActive) {
        val keepOn = monitoringState == MonitoringState.CHECK_IN || isEmergencyActive
        activity?.keepScreenActive(keepOn)
        onDispose {
            activity?.keepScreenActive(false)
        }
    }

    // Immediately after sign-up for a new account, show the "Add Emergency Contact" setup screen
    LaunchedEffect(requiresInitialContactSetup) {
        if (requiresInitialContactSetup) {
            currentTab = SoloSignalTab.CONTACTS
        }
    }

    // BackHandler for non-Home tabs to return cleanly to Home
    if (currentTab != SoloSignalTab.HOME &&
        monitoringState != MonitoringState.CHECK_IN &&
        !isEmergencyActive &&
        activeRecipientEmergencyAlert == null
    ) {
        BackHandler {
            if (requiresInitialContactSetup) {
                viewModel.completeInitialContactSetup()
            }
            currentTab = SoloSignalTab.HOME
        }
    }

    // Priority 1: Full-screen Emergency / Check-In Overlay when a candidate fall is active on the sender's phone
    if (monitoringState == MonitoringState.CHECK_IN || isEmergencyActive) {
        CheckInOverlay(
            countdownRemaining = countdownRemaining,
            totalCountdownSeconds = prefs.countdownSeconds,
            candidateSummary = activeCandidateSummary,
            trustedContact = prefs.trustedContact,
            isEmergencyTriggered = isEmergencyActive || (monitoringState != MonitoringState.CHECK_IN),
            onConfirmImOkay = { viewModel.confirmImOkay() },
            onRequestHelpNow = { viewModel.requestHelpNow() },
            onLocationPermissionGranted = { viewModel.refreshCurrentGpsLocation() },
            sosAudioEnabled = prefs.sosAudioEnabled,
            sosAudioStatus = sosAudioStatus
        )
        return
    }

    // Priority 2: Full-screen Recipient Emergency Alert Screen when opened from a push notification tap or alert banner
    val activeIncoming = activeRecipientEmergencyAlert
    if (activeIncoming != null) {
        RecipientEmergencyAlertScreen(
            alert = activeIncoming,
            onAcknowledge = { alertId ->
                viewModel.acknowledgeIncomingAlert(alertId)
                viewModel.dismissActiveRecipientEmergencyAlert()
            },
            onClose = { viewModel.dismissActiveRecipientEmergencyAlert() }
        )
        return
    }

    // Priority 3: Voluntary Medical ID Editor when opened from Home, Settings, or Post-Fall Assessment
    if (showMedicalIdEditor) {
        MedicalIdSheet(
            currentProfile = medicalIdProfile,
            onSaveProfile = { updated -> viewModel.saveMedicalIdProfile(updated) },
            onClearProfile = { viewModel.clearMedicalIdProfile() },
            onClose = { showMedicalIdEditor = false }
        )
        return
    }

    // Priority 4: Post-Fall Health Assessment & AI Health Cross-Check Result Screen (opened after "I'm OK" or manual launch)
    if (activePostFallAssessmentDraft != null || latestHealthCrossCheckResult != null) {
        PostFallAssessmentScreen(
            draft = activePostFallAssessmentDraft,
            result = latestHealthCrossCheckResult,
            isEvaluating = isEvaluatingHealthCrossCheck,
            medicalIdProfile = medicalIdProfile,
            trustedContact = prefs.trustedContact,
            deliveryStatus = deliveryStatus,
            deliveryStatusDetail = deliveryStatusDetail,
            onUpdateDraft = { updated -> viewModel.updatePostFallAssessmentDraft(updated) },
            onRecordFalseDetectionAndClose = { verification, note ->
                viewModel.recordFalseDetectionAndClose(verification, note)
            },
            onSubmitAssessment = { viewModel.submitPostFallAssessment() },
            onSkipAssessment = { viewModel.skipPostFallAssessment() },
            onDismissResult = { viewModel.dismissHealthCrossCheckResult() },
            onRequestImmediateSos = { reason -> viewModel.requestHelpNow() },
            onOpenMedicalIdEditor = { showMedicalIdEditor = true }
        )
        return
    }

    val configuredGmailToken = prefs.gmailAccessToken.isNotBlank() ||
        (BuildConfig.GMAIL_ACCESS_TOKEN.isNotBlank() &&
            !BuildConfig.GMAIL_ACCESS_TOKEN.startsWith("YOUR_"))

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = WarmMistBackground,
            bottomBar = {
                if (!isExpanded) {
                    NavigationBar(
                        containerColor = CardSurfaceWhite,
                        contentColor = DeepCharcoal
                    ) {
                        SoloSignalTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = {
                                    if (requiresInitialContactSetup && tab != SoloSignalTab.CONTACTS) {
                                        viewModel.completeInitialContactSetup()
                                    }
                                    currentTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpanded) {
                    val isDark = isSystemInDarkTheme()
                    NavigationRail(
                        containerColor = CardSurfaceWhite,
                        contentColor = DeepCharcoal,
                        header = {
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 12.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isDark) CardSurfaceWhite else DeepCharcoal),
                                contentAlignment = Alignment.Center
                            ) {
                                SoloSignalLogo(size = 26.dp, isDark = isDark)
                            }
                        }
                    ) {
                        SoloSignalTab.entries.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab,
                                onClick = {
                                    if (requiresInitialContactSetup && tab != SoloSignalTab.CONTACTS) {
                                        viewModel.completeInitialContactSetup()
                                    }
                                    currentTab = tab
                                },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        SoloSignalTab.HOME -> HomeScreen(
                            monitoringState = monitoringState,
                            realMonitoringStatus = realMonitoringStatus,
                            latestFeatures = latestFeatures,
                            recentMagnitudes = recentMagnitudes,
                            trustedContact = prefs.trustedContact,
                            googleChatConfig = prefs.googleChatConfig,
                            deliveryStatus = deliveryStatus,
                            deliveryStatusDetail = deliveryStatusDetail,
                            latestLocationSnapshot = latestLocationSnapshot,
                            locationDiagnostics = locationDiagnostics,
                            incomingRecipientAlerts = incomingRecipientAlerts,
                            incomingContactRequests = incomingContactRequests,
                            hasAccelerometer = viewModel.hasAccelerometer,
                            onboardingCompleted = prefs.onboardingCompleted,
                            userEmail = currentUser?.email,
                            onToggleMonitoring = { viewModel.toggleMonitoring() },
                            onRunSyntheticTrace = { trace -> viewModel.runTrace(trace) },
                            onSendTestAlert = { viewModel.sendTestAlert() },
                            onTriggerTestIncident = { viewModel.triggerLocalTestIncident() },
                            onRefreshLocation = { viewModel.refreshCurrentGpsLocation() },
                            onAcknowledgeIncomingAlert = { alertId ->
                                viewModel.acknowledgeIncomingAlert(alertId)
                            },
                            onOpenRecipientAlert = { alert ->
                                viewModel.openRecipientEmergencyAlert(alert)
                            },
                            onAcceptIncomingContactRequest = { linkId ->
                                viewModel.acceptIncomingContactRequest(linkId)
                            },
                            onNavigateToContacts = { currentTab = SoloSignalTab.CONTACTS },
                            onCompleteOnboarding = { viewModel.completeOnboarding() },
                            onTriggerSos = { viewModel.triggerManualSos() },
                            sosWorkflowState = sosWorkflowState,
                            sosAudioStatus = sosAudioStatus,
                            sosAudioBufferedSeconds = sosAudioBufferedSeconds,
                            modelComparisonReport = modelComparisonReport,
                            onRunModelBenchmark = { viewModel.runModelComparisonBenchmark() },
                            medicalIdProfile = medicalIdProfile,
                            onStartPostFallAssessment = { viewModel.startManualPostFallAssessment() },
                            onOpenMedicalId = { showMedicalIdEditor = true },
                            personalMovementProfile = personalMovementProfile,
                            personalLearningExamples = personalLearningExamples,
                            onTogglePersonalisationEnabled = { enabled ->
                                viewModel.setPersonalisationEnabled(enabled)
                            },
                            onResetPersonalLearningToGeneral = {
                                viewModel.resetPersonalProfileToGeneralModel()
                            },
                            onDeletePersonalLearningData = {
                                viewModel.deleteAllPersonalLearningData()
                            },
                            onVerifySosLearningEvent = { alertId, linkedEventId ->
                                viewModel.startPostFallAssessmentForSosEvent(alertId, linkedEventId)
                            }
                        )

                        SoloSignalTab.ACTIVITY -> ActivityHistoryScreen(
                            events = eventLogs,
                            onClearHistory = { viewModel.clearAllHistory() }
                        )

                        SoloSignalTab.CONTACTS -> TrustedContactScreen(
                            currentContact = prefs.trustedContact,
                            googleChatConfig = prefs.googleChatConfig,
                            deliveryStatus = deliveryStatus,
                            deliveryStatusDetail = deliveryStatusDetail,
                            requiresGmailAuthorization = requiresGmailAuth,
                            hasGmailTokenConfigured = configuredGmailToken,
                            lastMessagePreview = lastMessagePreview,
                            latestLocationSnapshot = latestLocationSnapshot,
                            locationDiagnostics = locationDiagnostics,
                            isCapturingLocation = isCapturingLocation,
                            incomingContactRequests = incomingContactRequests,
                            registeredDevices = registeredDevices,
                            fcmRegistrationStatus = fcmRegistrationStatus,
                            isVerifyingTrustedContact = isVerifyingTrustedContact,
                            contactSyncError = contactSyncError,
                            isInitialSetup = requiresInitialContactSetup,
                            currentUserUid = currentUser?.uid.orEmpty(),
                            currentUserDisplayName = currentUser?.displayName.orEmpty(),
                            currentUserEmail = currentUser?.email.orEmpty(),
                            currentUserPhone = currentUser?.phoneNumber.orEmpty(),
                            onCompleteInitialSetup = {
                                viewModel.completeInitialContactSetup()
                                currentTab = SoloSignalTab.HOME
                            },
                            onVerifyAndSaveContact = { contact, sendPermReq, markInvite ->
                                viewModel.verifyAndSaveTrustedContact(
                                    contact = contact,
                                    sendPermissionRequest = sendPermReq,
                                    markInvitationSent = markInvite
                                )
                            },
                            onAcceptIncomingContactRequest = { linkId ->
                                viewModel.acceptIncomingContactRequest(linkId)
                            },
                            onDeleteContact = { viewModel.deleteTrustedContact() },
                            onRefreshFcmDevice = { viewModel.refreshAndRegisterFcmDevice() },
                            onSendTestAlert = { viewModel.sendTestAlert() },
                            onRefreshLocation = { viewModel.refreshCurrentGpsLocation() },
                            onSaveGmailAccessToken = { token -> viewModel.saveGmailAccessToken(token) },
                            onSaveAndConnectGoogleChat = { enabled, backendUrl, spaceName, spaceLabel ->
                                viewModel.saveAndConnectGoogleChat(enabled, backendUrl, spaceName, spaceLabel)
                            },
                            onSendManualGoogleChatVerification = {
                                viewModel.sendManualGoogleChatVerification()
                            }
                        )

                        SoloSignalTab.SETTINGS -> SettingsPrivacyScreen(
                            countdownSeconds = prefs.countdownSeconds,
                            vibrationEnabled = prefs.vibrationEnabled,
                            optionalLocationEnabled = prefs.trustedContact.optionalLocationEnabled,
                            hasAccelerometer = viewModel.hasAccelerometer,
                            deliveryStatus = deliveryStatus,
                            deliveryStatusDetail = deliveryStatusDetail,
                            googleChatConfig = prefs.googleChatConfig,
                            userEmail = currentUser?.email,
                            onCountdownSecondsChanged = { sec -> viewModel.setCountdownSeconds(sec) },
                            onVibrationEnabledChanged = { enabled -> viewModel.setVibrationEnabled(enabled) },
                            onOptionalLocationChanged = { enabled -> viewModel.setOptionalLocationEnabled(enabled) },
                            onDeleteAllPersonalData = { viewModel.deleteAllPersonalData() },
                            onSignOut = { viewModel.signOut() },
                            onTestEmergencyBuzzer = { viewModel.testEmergencyBuzzer() },
                            onStopEmergencyBuzzer = { viewModel.stopEmergencyBuzzerTest() },
                            sosAudioEnabled = prefs.sosAudioEnabled,
                            sosAudioConsentGranted = prefs.sosAudioConsentGranted,
                            sosAudioStatus = sosAudioStatus,
                            sosAudioBufferedSeconds = sosAudioBufferedSeconds,
                            sosAudioRetentionDays = prefs.sosAudioRetentionDays,
                            lastCapturedAudioClip = lastCapturedAudioClip,
                            onSosAudioEnabledChange = { enabled, consent ->
                                viewModel.setSosAudioEnabled(enabled, consent)
                            },
                            onMicrophonePermissionResult = { granted ->
                                viewModel.onMicrophonePermissionResult(granted)
                            },
                            onDeleteStoredSosAudio = {
                                viewModel.deleteStoredSosAudioRecordings()
                            },
                            medicalIdProfile = medicalIdProfile,
                            onOpenMedicalId = { showMedicalIdEditor = true },
                            onSaveMedicalIdProfile = { updated -> viewModel.saveMedicalIdProfile(updated) },
                            onClearMedicalIdProfile = { viewModel.clearMedicalIdProfile() },
                            selectedLanguageCode = prefs.selectedLanguageCode,
                            onLanguageSelected = { langCode ->
                                viewModel.setSelectedLanguage(context, langCode)
                                (context as? android.app.Activity)?.recreate()
                            }
                        )
                    }
                }
            }
        }
    }
}

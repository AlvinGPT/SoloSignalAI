package com.example

import android.content.Intent
import android.os.Bundle
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
import com.example.ui.screens.RecipientEmergencyAlertScreen
import com.example.ui.screens.SettingsPrivacyScreen
import com.example.ui.screens.SignInScreen
import com.example.ui.screens.TrustedContactScreen
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.SoloSignalTheme
import com.example.ui.theme.WarmMistBackground

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingAlertIntent(intent)
        setContent {
            SoloSignalTheme {
                SoloSignalAppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAlertIntent(intent)
    }

    /**
     * Extracts incoming emergency alert parameters from either:
     * 1. A local/foreground notification PendingIntent (`EXTRA_INCOMING_ALERT_ID` etc.)
     * 2. A background Firebase Cloud Messaging notification tap (`alertId`, `senderName`, etc. in extras)
     */
    private fun handleIncomingAlertIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val alertId = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_ALERT_ID)
            ?.takeIf { it.isNotBlank() }
            ?: extras.getString("alertId")?.takeIf { it.isNotBlank() }
            ?: return

        // Do not re-open an alert that has already been acknowledged
        if (AlertDeliveryGateway(applicationContext).isAlertAcknowledged(alertId)) {
            intent.removeExtra(AlertDeliveryGateway.EXTRA_INCOMING_ALERT_ID)
            intent.removeExtra("alertId")
            return
        }

        val senderName = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_SENDER_NAME)
            ?: extras.getString("senderName")
            ?: "SoloSignal User"
        val senderEmail = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_SENDER_EMAIL)
            ?: extras.getString("senderEmail")
            ?: ""
        val triggerReason = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_TRIGGER_REASON)
            ?: extras.getString("triggerReason")
            ?: "Emergency check-in alert"
        val locationSummary = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_LOCATION_SUMMARY)
            ?: extras.getString("locationSummary")
            ?: ""
        val mapsUrl = extras.getString(AlertDeliveryGateway.EXTRA_INCOMING_MAPS_URL)
            ?: extras.getString("googleMapsUrl")
        val createdAt = if (extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_CREATED_AT)) {
            extras.getLong(AlertDeliveryGateway.EXTRA_INCOMING_CREATED_AT, System.currentTimeMillis())
        } else {
            extras.getString("createdAtMillis")?.toLongOrNull() ?: System.currentTimeMillis()
        }
        val isTest = if (extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_IS_TEST)) {
            extras.getBoolean(AlertDeliveryGateway.EXTRA_INCOMING_IS_TEST, false)
        } else {
            extras.getString("isTestAlert")?.equals("true", ignoreCase = true) == true
        }
        val lat = if (extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_LATITUDE)) {
            extras.getDouble(AlertDeliveryGateway.EXTRA_INCOMING_LATITUDE)
        } else {
            extras.getString("latitude")?.toDoubleOrNull()
        }
        val lng = if (extras.containsKey(AlertDeliveryGateway.EXTRA_INCOMING_LONGITUDE)) {
            extras.getDouble(AlertDeliveryGateway.EXTRA_INCOMING_LONGITUDE)
        } else {
            extras.getString("longitude")?.toDoubleOrNull()
        }

        val initialAlert = IncomingRecipientAlert(
            alertId = alertId,
            senderName = senderName,
            senderEmail = senderEmail,
            recipientName = "Trusted Contact",
            recipientEmail = "",
            isTestAlert = isTest,
            subjectLine = "Emergency Alert from $senderName",
            bodyText = "$senderName triggered an emergency check-in alert ($triggerReason). $locationSummary",
            triggerReason = triggerReason,
            deliveryState = AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT,
            deliveryStatusDetail = "Opened from emergency push notification.",
            googleMapsUrl = mapsUrl,
            locationSummary = locationSummary,
            createdAtMillis = createdAt,
            latitude = lat,
            longitude = lng
        )

        SafetyMonitoringController.getInstance(applicationContext)
            .openRecipientEmergencyAlert(initialAlert, alertId)
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

    // Gate all Cloud Firestore & alert operations behind Google Sign-In
    if (currentUser == null) {
        SignInScreen(
            onAuthSuccess = { viewModel.onAuthSuccess() }
        )
        return
    }

    LaunchedEffect(currentUser?.uid) {
        viewModel.refreshCurrentGpsLocation()
    }

    var currentTab by rememberSaveable { mutableStateOf(SoloSignalTab.HOME) }

    // Immediately after sign-up for a new account, show the "Add Emergency Contact" setup screen
    LaunchedEffect(requiresInitialContactSetup) {
        if (requiresInitialContactSetup) {
            currentTab = SoloSignalTab.CONTACTS
        }
    }

    // BackHandler for non-Home tabs to return cleanly to Home
    if (currentTab != SoloSignalTab.HOME &&
        monitoringState != MonitoringState.CHECK_IN &&
        activeRecipientEmergencyAlert == null
    ) {
        BackHandler {
            if (requiresInitialContactSetup) {
                viewModel.completeInitialContactSetup()
            }
            currentTab = SoloSignalTab.HOME
        }
    }

    // Priority 1: Full-screen Check-In Overlay when a candidate fall is active on the sender's phone
    if (monitoringState == MonitoringState.CHECK_IN) {
        CheckInOverlay(
            countdownRemaining = countdownRemaining,
            totalCountdownSeconds = prefs.countdownSeconds,
            candidateSummary = activeCandidateSummary,
            trustedContact = prefs.trustedContact,
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
                            sosAudioBufferedSeconds = sosAudioBufferedSeconds
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
                            }
                        )
                    }
                }
            }
        }
    }
}

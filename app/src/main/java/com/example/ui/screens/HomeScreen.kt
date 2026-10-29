package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.alerts.AlertDeliveryGateway
import com.example.data.AlertFirestoreRepository
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.MonitoringState
import com.example.domain.MotionFeatures
import com.example.domain.SosAudioStatus
import com.example.domain.SosWorkflowState
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.domain.TrustedContactVerificationStatus
import com.example.sensor.SyntheticSensorTraces
import com.example.ui.components.PastelCard
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.ui.components.SoloSignalLogo
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.LavenderHighlight
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.SkyInfoSurface
import com.example.ui.theme.WarmMistBackground
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Simplified, minimal Home Screen for SoloSignal AI:
 * 1. App name and safety status.
 * 2. One prominent red grainy-gradient SOS button.
 * 3. One compact safety-monitoring status.
 * 4. One emergency-contact section.
 */
@Composable
fun HomeScreen(
    monitoringState: MonitoringState,
    latestFeatures: MotionFeatures,
    recentMagnitudes: List<Float>,
    trustedContact: TrustedContact,
    googleChatConfig: GoogleChatAlertConfig,
    deliveryStatus: DeliveryConfigStatus,
    deliveryStatusDetail: String,
    latestLocationSnapshot: GpsLocationSnapshot?,
    locationDiagnostics: String,
    incomingRecipientAlerts: List<IncomingRecipientAlert>,
    incomingContactRequests: List<TrustedContactRequestItem>,
    hasAccelerometer: Boolean,
    onboardingCompleted: Boolean,
    userEmail: String?,
    onToggleMonitoring: () -> Unit,
    onRunSyntheticTrace: (SyntheticSensorTraces.TraceType) -> Unit,
    onSendTestAlert: () -> Unit,
    onRefreshLocation: () -> Unit,
    onAcknowledgeIncomingAlert: (String) -> Unit,
    onOpenRecipientAlert: (IncomingRecipientAlert) -> Unit,
    onAcceptIncomingContactRequest: (String) -> Unit,
    onNavigateToContacts: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    onTriggerSos: () -> Unit = {},
    activeSenderEscalationStatus: String = AlertFirestoreRepository.ESCALATION_NONE,
    activeSenderMeetUrl: String? = null,
    activeSenderMeetError: String? = null,
    onCancelEmergencyEscalation: () -> Unit = {},
    sosWorkflowState: SosWorkflowState = SosWorkflowState.IDLE,
    sosAudioStatus: SosAudioStatus = SosAudioStatus.DISABLED,
    sosAudioBufferedSeconds: Double = 0.0
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var hasLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPerm = granted
        if (granted) onRefreshLocation()
    }

    val gateway = remember(context) { AlertDeliveryGateway(context.applicationContext) }
    var alertSetupWarning by remember { mutableStateOf(gateway.getRecipientAlertSetupWarning()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        gateway.ensureChannels()
        alertSetupWarning = gateway.getRecipientAlertSetupWarning()
    }

    LaunchedEffect(Unit) {
        gateway.ensureChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            alertSetupWarning = gateway.getRecipientAlertSetupWarning()
        }
    }

    val isProtected = monitoringState == MonitoringState.MONITORING && trustedContact.isValid
    val isMonitoringActive = monitoringState != MonitoringState.STOPPED

    val (safetyLabel, safetyBadgeColor) = when {
        deliveryStatus == DeliveryConfigStatus.ACKNOWLEDGED_BY_RECIPIENT ->
            "Contact is checking on you" to MintSoftSurface
        deliveryStatus == DeliveryConfigStatus.VIDEO_CALL_ESCALATED ||
            activeSenderEscalationStatus == AlertFirestoreRepository.ESCALATION_MEET_CREATED ->
            "Video call fallback" to CoralSoftSurface
        deliveryStatus == DeliveryConfigStatus.VIDEO_CALL_FALLBACK_FAILED ||
            activeSenderEscalationStatus == AlertFirestoreRepository.ESCALATION_MEET_FAILED ->
            "No response (Alert active)" to CoralSoftSurface
        deliveryStatus == DeliveryConfigStatus.DELIVERED_TO_RECIPIENT ->
            "Delivered" to MintSoftSurface
        monitoringState == MonitoringState.ALERT_SENT ||
            deliveryStatus == DeliveryConfigStatus.REQUEST_ACCEPTED ||
            deliveryStatus == DeliveryConfigStatus.EMAIL_SENT ||
            deliveryStatus == DeliveryConfigStatus.SENT_BY_PROVIDER ||
            deliveryStatus == DeliveryConfigStatus.ALERT_SENT ->
            "Alert sent" to MintSoftSurface
        monitoringState == MonitoringState.ALERT_FAILED ||
            deliveryStatus == DeliveryConfigStatus.ALERT_FAILED ->
            "Failed to send" to CoralSoftSurface
        monitoringState == MonitoringState.ALERT_PENDING ||
            monitoringState == MonitoringState.CHECK_IN ||
            deliveryStatus == DeliveryConfigStatus.SENDING ->
            "Sending…" to CoralSoftSurface
        !trustedContact.isValid -> "Not configured" to CoralSoftSurface
        isProtected -> "Protected" to MintSoftSurface
        else -> "Paused" to LavenderSoftSurface
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("home_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. App Name and Safety Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val isDarkTheme = isSystemInDarkTheme()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isDarkTheme) CardSurfaceWhite else DeepCharcoal),
                        contentAlignment = Alignment.Center
                    ) {
                        SoloSignalLogo(
                            size = 24.dp,
                            contentDescription = stringResource(R.string.cd_app_logo),
                            isDark = isDarkTheme
                        )
                    }
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                StatusPillBadge(
                    label = safetyLabel,
                    containerColor = safetyBadgeColor,
                    modifier = Modifier.testTag("monitoring_status_pill")
                )
            }

            // Notification / Sound / Vibration setup warning if disabled on device
            val currentSetupWarning = alertSetupWarning
            if (!currentSetupWarning.isNullOrBlank()) {
                PastelCard(
                    containerColor = CoralSoftSurface,
                    borderColor = CoralUrgentHighlight,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentSetupWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = DeepCharcoal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    try {
                                        val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(settingsIntent)
                                    } catch (_: Exception) {
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("enable_alert_notifications_button")
                        ) {
                            Text("Enable", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // Active Incoming Emergency Alert Banner (only shown when recipient has an active UNACKNOWLEDGED alert)
            var locallyAcknowledgedIds by remember { mutableStateOf(setOf<String>()) }
            val activeUnacknowledgedAlert = incomingRecipientAlerts.firstOrNull { alert ->
                alert.deliveryState != AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT &&
                    !locallyAcknowledgedIds.contains(alert.alertId) &&
                    !gateway.isAlertAcknowledged(alert.alertId)
            }
            if (activeUnacknowledgedAlert != null) {
                PastelCard(
                    containerColor = if (activeUnacknowledgedAlert.isTestAlert) SkyInfoSurface else CoralSoftSurface,
                    borderColor = if (activeUnacknowledgedAlert.isTestAlert) LavenderHighlight else CoralUrgentHighlight,
                    modifier = Modifier.testTag("active_incoming_emergency_banner")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionEyebrow(
                            text = if (activeUnacknowledgedAlert.isTestAlert) "TEST ALERT" else "EMERGENCY ALERT",
                            modifier = Modifier.weight(1f)
                        )
                        StatusPillBadge(
                            label = "Urgent",
                            containerColor = CoralUrgentHighlight
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${activeUnacknowledgedAlert.senderName} needs assistance",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onOpenRecipientAlert(activeUnacknowledgedAlert) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("open_incoming_emergency_screen_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open", color = Color.White, maxLines = 1, softWrap = false)
                        }

                        if (!activeUnacknowledgedAlert.googleMapsUrl.isNullOrBlank()) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(activeUnacknowledgedAlert.googleMapsUrl))
                                        )
                                    } catch (_: Exception) {
                                    }
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .testTag("incoming_alert_open_map_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Map", maxLines = 1, softWrap = false)
                            }
                        }

                        Button(
                            onClick = {
                                locallyAcknowledgedIds = locallyAcknowledgedIds + activeUnacknowledgedAlert.alertId
                                onAcknowledgeIncomingAlert(activeUnacknowledgedAlert.alertId)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MintConfirm,
                                contentColor = DeepCharcoal
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("acknowledge_incoming_alert_button")
                        ) {
                            Text("I'm checking on them", maxLines = 1, softWrap = false)
                        }
                    }

                    val recipientBannerMeetUrl = activeUnacknowledgedAlert.meetUrl
                        ?.trim()
                        ?.takeIf { it.startsWith("https://meet.google.com/") }
                    if (recipientBannerMeetUrl != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(recipientBannerMeetUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CoralUrgentHighlight,
                                contentColor = DeepCharcoal
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("banner_join_video_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Join Emergency Video Call",
                                color = DeepCharcoal,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Fallen User (Sender) 15-Second Timeout Video-Call Fallback Banner
            val senderCleanMeetUrl = activeSenderMeetUrl
                ?.trim()
                ?.takeIf { it.startsWith("https://meet.google.com/") }
            val showSenderMeetCreated =
                (activeSenderEscalationStatus == AlertFirestoreRepository.ESCALATION_MEET_CREATED ||
                    deliveryStatus == DeliveryConfigStatus.VIDEO_CALL_ESCALATED) &&
                    senderCleanMeetUrl != null
            val showSenderMeetFailed =
                activeSenderEscalationStatus == AlertFirestoreRepository.ESCALATION_MEET_FAILED ||
                    deliveryStatus == DeliveryConfigStatus.VIDEO_CALL_FALLBACK_FAILED

            if (showSenderMeetCreated || showSenderMeetFailed) {
                val contactName = trustedContact.contactName.ifBlank { "Your trusted contact" }
                PastelCard(
                    containerColor = CoralSoftSurface,
                    borderColor = CoralUrgentHighlight,
                    modifier = Modifier.testTag("sender_meet_fallback_card")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionEyebrow(
                            text = if (showSenderMeetCreated) {
                                "VIDEO-CALL FALLBACK TRIGGERED"
                            } else {
                                "VIDEO-CALL FALLBACK UNAVAILABLE"
                            },
                            modifier = Modifier.weight(1f)
                        )
                        StatusPillBadge(
                            label = "15s Timeout",
                            containerColor = CoralUrgentHighlight
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (showSenderMeetCreated) {
                            "$contactName has not responded within 15 seconds. An emergency Google Meet link was sent to them."
                        } else {
                            "$contactName has not responded within 15 seconds. Could not create a Google Meet link (${activeSenderMeetError ?: "API unavailable"}). Your emergency notification remains active."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepCharcoal,
                        modifier = Modifier.testTag("sender_meet_fallback_message")
                    )
                    if (showSenderMeetCreated && senderCleanMeetUrl != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Both participants must join the Meet link and grant camera/microphone permissions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(senderCleanMeetUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                } catch (_: Exception) {
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(999.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .testTag("sender_join_emergency_video_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = "Join Emergency Video Call",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Join Emergency Video Call",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // Pending Incoming Contact Request Banner (only shown when someone requests to add this user)
            val pendingRequests = incomingContactRequests.filter { !it.recipientAccepted }
            if (pendingRequests.isNotEmpty()) {
                val req = pendingRequests.first()
                PastelCard(containerColor = LavenderSoftSurface) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${req.senderName} added you as a contact",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = DeepCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = { onAcceptIncomingContactRequest(req.linkId) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("home_accept_contact_request_button")
                        ) {
                            Text("Accept", color = Color.White, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 2. Prominent Red Grainy-Gradient SOS Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                RedGrainySosButton(
                    onClick = {
                        if (!hasLocationPerm && trustedContact.optionalLocationEnabled) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                        if (onTriggerSos != {}) {
                            onTriggerSos()
                        } else {
                            onSendTestAlert()
                        }
                    }
                )
            }

            // 3. One Compact Safety-Monitoring Status
            PastelCard(
                containerColor = if (isProtected) MintSoftSurface else LavenderSoftSurface,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionEyebrow(text = "FALL MONITORING")
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = when {
                                isProtected -> "Active in background"
                                !trustedContact.isValid -> "Add a contact to start"
                                else -> "Monitoring paused"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = onToggleMonitoring,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DeepCharcoal,
                            contentColor = Color.White,
                            disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                            disabledContentColor = Color.White.copy(alpha = 0.9f)
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("toggle_monitoring_button")
                    ) {
                        Icon(
                            imageVector = if (isMonitoringActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isMonitoringActive) "Pause" else "Resume",
                            color = Color.White,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (sosAudioStatus != SosAudioStatus.DISABLED) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPillBadge(
                            label = sosAudioStatus.label,
                            containerColor = when (sosAudioStatus) {
                                SosAudioStatus.AUDIO_MONITORING_ENABLED,
                                SosAudioStatus.SOS_AUDIO_CAPTURED,
                                SosAudioStatus.SOS_EMAIL_SENT -> MintConfirm
                                SosAudioStatus.UPLOAD_IN_PROGRESS -> WarmMistBackground
                                else -> CoralSoftSurface
                            },
                            modifier = Modifier.testTag("home_sos_audio_status_badge")
                        )
                        if (sosAudioStatus == SosAudioStatus.AUDIO_MONITORING_ENABLED && sosAudioBufferedSeconds > 0.0) {
                            Text(
                                text = String.format(java.util.Locale.US, "%.1fs / 15.0s buffered", sosAudioBufferedSeconds),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = DeepCharcoal
                            )
                        }
                    }
                }
            }

            // 4. One Emergency-Contact Section
            PastelCard(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .clickable { onNavigateToContacts() }
                    .testTag("trusted_contact_summary_card")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(LavenderSoftSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (trustedContact.isValid) Icons.Default.Person else Icons.Default.PersonAdd,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            SectionEyebrow(text = "EMERGENCY CONTACT")
                            Text(
                                text = if (trustedContact.isValid) {
                                    trustedContact.contactName
                                } else {
                                    "No contact saved"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (trustedContact.isValid) {
                                    trustedContact.contactEmail.ifBlank { trustedContact.contactPhone }
                                } else {
                                    "Add a contact for SOS alerts"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    val (contactBadgeLabel, contactBadgeColor) = resolveContactBadge(trustedContact)
                    StatusPillBadge(
                        label = contactBadgeLabel,
                        containerColor = contactBadgeColor,
                        modifier = Modifier.testTag("home_contact_verification_badge")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onNavigateToContacts,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("configure_contact_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (trustedContact.isValid) "Edit" else "Add",
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    if (trustedContact.isValid) {
                        Button(
                            onClick = onSendTestAlert,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = DeepCharcoal,
                                contentColor = Color.White,
                                disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.9f)
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("send_test_alert_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Send test",
                                color = Color.White,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resolves the short, accurate status badge label and background color for a TrustedContact:
 * - "Connected" when the contact account is linked and reachable
 * - "Offline" when the linked contact is not currently reachable (no active device or notifications disabled)
 * - "Pending" when an invitation or contact request has been sent and awaits acceptance
 * - "Registered" when an existing SoloSignal account is detected and ready to link
 * - "Invite" only when no existing account or accepted invitation is found
 */
internal fun resolveContactBadge(contact: TrustedContact): Pair<String, Color> {
    if (!contact.isValid) {
        return "Not set" to CoralSoftSurface
    }
    return when {
        contact.recipientAccepted &&
            (contact.verificationStatus == TrustedContactVerificationStatus.READY_FOR_ALERTS ||
                contact.verificationStatus == TrustedContactVerificationStatus.ACCEPTED) ->
            "Connected" to MintSoftSurface
        contact.recipientAccepted &&
            (contact.verificationStatus == TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED ||
                contact.verificationStatus == TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED) ->
            "Offline" to LavenderSoftSurface
        contact.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING ||
            contact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING ->
            "Pending" to LavenderSoftSurface
        contact.recipientHasRegisteredAccount ||
            contact.verificationStatus == TrustedContactVerificationStatus.REGISTERED ->
            "Registered" to SkyInfoSurface
        else ->
            "Invite" to CoralSoftSurface
    }
}

/**
 * Circular SOS button with a red, grainy radial gradient:
 * - Brighter red (#FF3B30) toward the center, deep crimson red (#7A0008) at the edges
 * - Subtle procedural grain/noise texture overlay
 * - Soft red outer glow and gentle elevation shadow
 * - High-contrast white "SOS" text and smaller "SEND ALERT" label
 */
@Composable
private fun RedGrainySosButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Precompute deterministic grain particles in normalized polar coordinates
    val grainParticles = remember {
        val list = ArrayList<GrainParticle>(420)
        var seed = 0x5F3759DFu
        for (i in 0 until 420) {
            seed = seed * 1664525u + 1013904223u
            val u1 = ((seed shr 8) and 0xFFFFu).toFloat() / 65535f
            seed = seed * 1664525u + 1013904223u
            val u2 = ((seed shr 8) and 0xFFFFu).toFloat() / 65535f
            seed = seed * 1664525u + 1013904223u
            val isLight = ((seed shr 16) and 1u) == 1u
            val r = sqrt(u1) * 0.96f
            val theta = u2 * 6.2831855f
            val alpha = 0.045f + (i % 5) * 0.015f
            list.add(
                GrainParticle(
                    normX = r * cos(theta),
                    normY = r * sin(theta),
                    isLight = isLight,
                    alpha = alpha
                )
            )
        }
        list
    }

    Box(
        modifier = modifier.size(216.dp),
        contentAlignment = Alignment.Center
    ) {
        // Soft red ambient glow behind the button
        Canvas(modifier = Modifier.fillMaxSize()) {
            val glowRadius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x55EF233C),
                        Color(0x24D90429),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )
        }

        // Main circular button with shadow, radial red gradient, and subtle grain texture
        Box(
            modifier = Modifier
                .size(176.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFFD90429),
                    spotColor = Color(0xFF9B0000)
                )
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF7575).copy(alpha = 0.65f),
                            Color(0xFF5A0004).copy(alpha = 0.8f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = Color.White),
                    role = Role.Button,
                    onClick = onClick
                )
                .testTag("home_sos_button")
                .testTag("need_help_now_button"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val focalCenter = Offset(center.x, center.y - radius * 0.08f)

                // Base radial gradient: brighter red toward the center, deep red at the edges
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFF3B30),
                            0.48f to Color(0xFFE51C23),
                            0.80f to Color(0xFFB70914),
                            1.0f to Color(0xFF6E0007)
                        ),
                        center = focalCenter,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )

                // Subtle grain/noise texture across the circular surface
                val dotRadius = 1.25.dp.toPx()
                for (p in grainParticles) {
                    val px = center.x + p.normX * radius
                    val py = center.y + p.normY * radius
                    drawCircle(
                        color = if (p.isLight) {
                            Color.White.copy(alpha = p.alpha)
                        } else {
                            Color(0xFF2B0000).copy(alpha = p.alpha * 1.15f)
                        },
                        radius = dotRadius,
                        center = Offset(px, py)
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "SOS",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "SEND ALERT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

private data class GrainParticle(
    val normX: Float,
    val normY: Float,
    val isLight: Boolean,
    val alpha: Float
)

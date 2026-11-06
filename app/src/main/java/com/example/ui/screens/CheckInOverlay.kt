package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.CandidateEventSummary
import com.example.domain.SosAudioStatus
import com.example.domain.TrustedContact
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.WarmMistBackground

@Composable
fun CheckInOverlay(
    countdownRemaining: Int,
    totalCountdownSeconds: Int = 15,
    candidateSummary: CandidateEventSummary?,
    trustedContact: TrustedContact,
    isEmergencyTriggered: Boolean = false,
    onConfirmImOkay: () -> Unit,
    onRequestHelpNow: () -> Unit,
    onLocationPermissionGranted: () -> Unit = {},
    sosAudioEnabled: Boolean = false,
    sosAudioStatus: SosAudioStatus = SosAudioStatus.DISABLED
) {
    // Back press during check-in safely cancels the check-in
    BackHandler {
        onConfirmImOkay()
    }

    val context = LocalContext.current
    var hasLocationPermission by remember {
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
        hasLocationPermission = granted
        if (granted) {
            onLocationPermissionGranted()
        }
    }

    val progressTarget = (countdownRemaining.toFloat() / totalCountdownSeconds.coerceAtLeast(1).toFloat())
        .coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(targetValue = progressTarget, label = "checkin_progress")

    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("check_in_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPillBadge(
                label = if (isEmergencyTriggered) stringResource(R.string.checkin_emergency_sos_active) else stringResource(R.string.checkin_badge_active),
                containerColor = CoralUrgentHighlight,
                contentColor = if (isEmergencyTriggered) androidx.compose.ui.graphics.Color.White else DeepCharcoal,
                modifier = Modifier.testTag("checkin_possible_fall_badge")
            )

            Text(
                text = if (isEmergencyTriggered) stringResource(R.string.checkin_badge_active) else stringResource(R.string.checkin_headline),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = DeepCharcoal,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("checkin_are_you_okay_text")
            )

            Text(
                text = if (isEmergencyTriggered) {
                    if (trustedContact.isValid) {
                        stringResource(R.string.checkin_dispatched_to_contact, trustedContact.contactName)
                    } else {
                        stringResource(R.string.checkin_sos_active_generic)
                    }
                } else if (trustedContact.isValid) {
                    stringResource(R.string.checkin_sending_sos_countdown, trustedContact.contactName, countdownRemaining)
                } else {
                    stringResource(R.string.checkin_tap_ok_or_sos)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralSlate,
                textAlign = TextAlign.Center
            )

            if (isEmergencyTriggered) {
                // Large accessible red SOS status indicator
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(136.dp)
                        .padding(vertical = 2.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        .testTag("emergency_sos_active_indicator")
                ) {
                    Surface(
                        modifier = Modifier.size(136.dp),
                        shape = CircleShape,
                        color = CoralUrgentHighlight.copy(alpha = 0.25f)
                    ) {}
                    Surface(
                        modifier = Modifier.size(104.dp),
                        shape = CircleShape,
                        color = CoralUrgentHighlight,
                        shadowElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Active Emergency SOS",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "SOS",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp
                                ),
                                color = androidx.compose.ui.graphics.Color.White
                            )
                        }
                    }
                }
            } else {
                // Large circular countdown ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(136.dp)
                        .padding(vertical = 2.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { 1f },
                        modifier = Modifier.fillMaxSize(),
                        color = CoralSoftSurface,
                        strokeWidth = 10.dp
                    )
                    CircularProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = DeepCharcoal,
                        strokeWidth = 10.dp
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "${countdownRemaining}s",
                            style = MaterialTheme.typography.displayMedium.copy(
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = DeepCharcoal,
                            modifier = Modifier.testTag("countdown_seconds_text")
                        )
                        SectionEyebrow(text = stringResource(R.string.seconds_label))
                    }
                }
            }

            // ML Explainability & Why Check-In Initiated summary card
            val explainability = candidateSummary?.explainabilityRecord
            if (explainability != null || !candidateSummary?.summaryNote.isNullOrBlank()) {
                Surface(
                    color = LavenderSoftSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("checkin_ml_explainability_card")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (explainability != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${explainability.predictedCategory.displayLabel} (${explainability.formatConfidenceBadge()})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = DeepCharcoal,
                                    modifier = Modifier.testTag("checkin_ml_prediction_text")
                                )
                                Text(
                                    text = if (explainability.isUncertain) {
                                        "${explainability.modelVersion} • Uncertain"
                                    } else {
                                        explainability.modelVersion
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeutralSlate
                                )
                            }
                            Text(
                                text = explainability.whyCheckInInitiated,
                                style = MaterialTheme.typography.labelSmall,
                                color = DeepCharcoal,
                                modifier = Modifier.testTag("checkin_ml_reason_text")
                            )
                        } else if (!candidateSummary?.summaryNote.isNullOrBlank()) {
                            Text(
                                text = candidateSummary?.summaryNote.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = DeepCharcoal
                            )
                        }
                    }
                }
            }

            // Location status banner during check-in
            if (trustedContact.optionalLocationEnabled) {
                Surface(
                    color = if (hasLocationPermission) MintSoftSurface else CoralSoftSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = DeepCharcoal,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (hasLocationPermission) stringResource(R.string.checkin_gps_ready) else stringResource(R.string.checkin_location_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = DeepCharcoal
                            )
                        }
                        if (!hasLocationPermission) {
                            TextButton(
                                onClick = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("checkin_grant_location_button")
                            ) {
                                Text(
                                    text = stringResource(R.string.checkin_allow_gps),
                                    color = DeepCharcoal,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            if (sosAudioEnabled || sosAudioStatus != SosAudioStatus.DISABLED) {
                Surface(
                    color = LavenderSoftSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("checkin_sos_audio_notice")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = sosAudioStatus.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal
                        )
                        Text(
                            text = stringResource(R.string.checkin_sos_discard_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = NeutralSlate
                        )
                    }
                }
            }

            // Primary Oversized Cancel CTA ("I'm OK")
            Button(
                onClick = onConfirmImOkay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MintConfirm,
                    contentColor = DeepCharcoal
                ),
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(CircleShape)
                    .testTag("im_okay_cancel_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    text = stringResource(R.string.action_im_okay),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            // Secondary Immediate Escalation CTA ("Send SOS Now" during countdown)
            if (!isEmergencyTriggered) {
                Button(
                    onClick = onRequestHelpNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = androidx.compose.ui.graphics.Color.White,
                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                        disabledContentColor = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.9f)
                    ),
                    shape = CircleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(CircleShape)
                        .testTag("need_help_now_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(R.string.action_need_help_now),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = NeutralSlate,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    text = stringResource(R.string.safety_not_911_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

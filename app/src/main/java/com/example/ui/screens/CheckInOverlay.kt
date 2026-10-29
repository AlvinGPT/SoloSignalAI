package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
                label = "Possible fall detected",
                containerColor = CoralUrgentHighlight,
                contentColor = DeepCharcoal,
                modifier = Modifier.testTag("checkin_possible_fall_badge")
            )

            Text(
                text = "Are you okay?",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = DeepCharcoal,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("checkin_are_you_okay_text")
            )

            Text(
                text = if (trustedContact.isValid) {
                    "Sending SOS to ${trustedContact.contactName} in ${countdownRemaining}s unless cancelled."
                } else {
                    "Tap I'm OK to cancel, or Send SOS Now."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = NeutralSlate,
                textAlign = TextAlign.Center
            )

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
                    SectionEyebrow(text = "SECONDS")
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
                                text = if (hasLocationPermission) "GPS location ready" else "Location disabled",
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
                                    text = "Allow GPS",
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
                            text = "Tap \"I'm OK\" to cancel SOS and immediately discard the temporary 15-second audio snapshot.",
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
                    text = "I'm OK",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }

            // Secondary Immediate Escalation CTA ("Send SOS Now")
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
                    text = "Send SOS Now",
                    color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
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

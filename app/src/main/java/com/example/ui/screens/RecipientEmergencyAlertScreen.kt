package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.AlertFirestoreRepository
import com.example.domain.IncomingRecipientAlert
import com.example.ui.components.PastelCard
import com.example.ui.components.SectionEyebrow
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardSurfaceWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.CoralUrgentHighlight
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.LavenderHighlight
import com.example.ui.theme.MintConfirm
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.SkyInfoSurface
import com.example.ui.theme.WarmMistBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves a concise, immediately understandable alert type label for the recipient screen:
 * - "Possible fall detected"
 * - "SOS requested"
 * - "Test alert"
 */
internal fun resolveAlertTypeLabel(alert: IncomingRecipientAlert): String {
    if (alert.isTestAlert) return "Test alert"
    val reason = alert.triggerReason.trim()
    return when {
        reason.equals("SOS requested", ignoreCase = true) ||
            reason.startsWith("Manual SOS", ignoreCase = true) ->
            "SOS requested"
        reason.contains("fall", ignoreCase = true) ||
            reason.contains("countdown", ignoreCase = true) ->
            "Possible fall detected"
        reason.contains("SOS", ignoreCase = true) ||
            reason.contains("help", ignoreCase = true) ->
            "SOS requested"
        alert.bodyText.contains("Possible fall", ignoreCase = true) ->
            "Possible fall detected"
        else ->
            "SOS requested"
    }
}

/**
 * Minimal, high-clarity full-screen Emergency Alert view opened immediately when a new
 * emergency alert arrives or when the recipient taps the incoming alert notification.
 *
 * Shows only essential information:
 * - EMERGENCY ALERT
 * - Contact's name
 * - Alert type ("Possible fall detected" or "SOS requested")
 * - Time received
 * - Shared location, if available
 * - Clear map/location action and contact action
 */
@Composable
fun RecipientEmergencyAlertScreen(
    alert: IncomingRecipientAlert,
    onAcknowledge: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler {
        onClose()
    }

    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val timeFormatter = remember { SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()) }
    val formattedTime = remember(alert.createdAtMillis) {
        timeFormatter.format(Date(alert.createdAtMillis))
    }

    val alertTypeLabel = remember(alert) { resolveAlertTypeLabel(alert) }
    val isAcknowledged = alert.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT
    val hasValidCoordinates = alert.latitude != null && alert.longitude != null
    val hasMapsUrl = !alert.googleMapsUrl.isNullOrBlank() || hasValidCoordinates
    val cleanMeetUrl = remember(alert.meetUrl) {
        alert.meetUrl?.trim()?.takeIf { it.startsWith("https://meet.google.com/") }
    }

    fun launchGoogleMeet() {
        val url = cleanMeetUrl ?: return
        try {
            val meetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(meetIntent)
        } catch (_: Exception) {
        }
    }

    val resolvedMapsUrl = remember(alert.googleMapsUrl, alert.latitude, alert.longitude) {
        when {
            !alert.googleMapsUrl.isNullOrBlank() -> alert.googleMapsUrl
            alert.latitude != null && alert.longitude != null ->
                "https://www.google.com/maps/search/?api=1&query=${alert.latitude},${alert.longitude}"
            else -> null
        }
    }

    fun launchGoogleMaps() {
        val lat = alert.latitude
        val lng = alert.longitude
        val encodedLabel = Uri.encode(alert.senderName.ifBlank { "Emergency Alert Location" })

        try {
            if (lat != null && lng != null) {
                val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($encodedLabel)")
                val mapsAppIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (mapsAppIntent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(mapsAppIntent)
                    return
                }
            }
            val fallbackUrl = resolvedMapsUrl ?: return
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(browserIntent)
        } catch (_: Exception) {
            val fallbackUrl = resolvedMapsUrl ?: return
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun contactSender() {
        try {
            if (alert.senderEmail.isNotBlank()) {
                val mailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:${Uri.encode(alert.senderEmail)}")
                    putExtra(Intent.EXTRA_SUBJECT, "Checking on your SoloSignal alert")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(mailIntent)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            }
        } catch (_: Exception) {
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .testTag("recipient_emergency_alert_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top Bar: EMERGENCY ALERT badge + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPillBadge(
                    label = if (alert.isTestAlert) "TEST ALERT" else "EMERGENCY ALERT",
                    containerColor = if (alert.isTestAlert) SkyInfoSurface else CoralUrgentHighlight,
                    contentColor = DeepCharcoal,
                    modifier = Modifier.testTag("emergency_alert_title_badge")
                )

                IconButton(
                    onClick = onClose,
                    modifier = Modifier.testTag("close_emergency_screen_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close emergency alert screen",
                        tint = DeepCharcoal
                    )
                }
            }

            // 2. Essential Alert Details Card: Contact Name, Alert Type, Time Received, Shared Location
            PastelCard(
                containerColor = CardSurfaceWhite,
                borderColor = if (alert.isTestAlert) LavenderHighlight else CoralUrgentHighlight
            ) {
                // Header: EMERGENCY ALERT & Contact Name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CoralSoftSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Emergency alert icon",
                            tint = DeepCharcoal,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        SectionEyebrow(text = "EMERGENCY ALERT")
                        Text(
                            text = alert.senderName.ifBlank { "Emergency Contact" },
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("emergency_alert_sender_name_text")
                        )
                        if (alert.senderEmail.isNotBlank()) {
                            Text(
                                text = alert.senderEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = NeutralSlate,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(14.dp))

                // Alert Type ("Possible fall detected" or "SOS requested")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Alert type",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeutralSlate
                        )
                    }
                    Text(
                        text = alertTypeLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = DeepCharcoal,
                        modifier = Modifier.testTag("emergency_alert_type_text")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Time Received
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Time received",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NeutralSlate
                        )
                    }
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepCharcoal,
                        modifier = Modifier.testTag("emergency_alert_time_text")
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(12.dp))

                // Shared Location
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = DeepCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Shared location",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DeepCharcoal
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (hasValidCoordinates) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "%.5f, %.5f",
                            alert.latitude,
                            alert.longitude
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("emergency_alert_coordinates_text")
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Text(
                    text = when {
                        alert.locationSummary.isNotBlank() -> alert.locationSummary
                        hasMapsUrl -> "GPS location shared"
                        else -> "Location not available"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = NeutralSlate,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("emergency_alert_location_summary")
                )

                if (cleanMeetUrl != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = HairlineDivider)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "No acknowledgement within 15s — Video-call fallback active. Tapping 'Join Emergency Video Call' opens Google Meet; both participants must join and grant camera/microphone permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("emergency_meet_fallback_note")
                    )
                }
            }

            // Prominent "Join Emergency Video Call" Button when 15-second fallback triggered
            if (cleanMeetUrl != null) {
                Button(
                    onClick = { launchGoogleMeet() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CoralUrgentHighlight,
                        contentColor = DeepCharcoal
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp)
                        .testTag("join_emergency_video_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "Join Emergency Video Call",
                        tint = DeepCharcoal,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Join Emergency Video Call",
                        color = DeepCharcoal,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 3. Clear Map/Location Action and Contact Action
            if (hasMapsUrl) {
                Button(
                    onClick = { launchGoogleMaps() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = Color.White,
                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 54.dp)
                        .testTag("open_in_google_maps_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open location in Google Maps",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Open Location in Maps",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // Contact Action Button (Call / Message / Email Sender)
            Button(
                onClick = { contactSender() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = DeepCharcoal,
                    contentColor = Color.White,
                    disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                    disabledContentColor = Color.White.copy(alpha = 0.9f)
                ),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 54.dp)
                    .testTag("contact_sender_button")
            ) {
                Icon(
                    imageVector = if (alert.senderEmail.isNotBlank()) Icons.Default.Email else Icons.Default.Call,
                    contentDescription = "Contact sender",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Contact ${alert.senderName.ifBlank { "Sender" }}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Acknowledge Action Button
            if (!isAcknowledged) {
                Button(
                    onClick = { onAcknowledge(alert.alertId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MintConfirm,
                        contentColor = DeepCharcoal
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 52.dp)
                        .testTag("acknowledge_emergency_screen_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "I'm checking on them",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else {
                StatusPillBadge(
                    label = "Acknowledged",
                    containerColor = MintSoftSurface
                )
            }

            OutlinedButton(
                onClick = onClose,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .testTag("return_to_dashboard_button")
            ) {
                Text(
                    text = "Close",
                    color = DeepCharcoal,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

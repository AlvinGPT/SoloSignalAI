package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.SafetyEventEntity
import com.example.domain.SafetyEventType
import com.example.ui.components.PastelCard
import com.example.ui.components.StatusPillBadge
import com.example.ui.theme.CardWhite
import com.example.ui.theme.CoralSoftSurface
import com.example.ui.theme.DeepCharcoal
import com.example.ui.theme.HairlineDivider
import com.example.ui.theme.InkBlack
import com.example.ui.theme.LavenderSoftSurface
import com.example.ui.theme.MintSoftSurface
import com.example.ui.theme.NeutralSlate
import com.example.ui.theme.SkyInfoSurface
import com.example.ui.theme.WarmMistBackground
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clean, minimal Activity History screen for SoloSignal AI:
 * - Compact header with aligned Clear button and confirmation dialog.
 * - Organised activity items displaying only:
 *   1. Event: Fall detected / Alert cancelled / Test alert
 *   2. Status: Sent / Cancelled / Failed (non-wrapping pill badge)
 *   3. Time: Short date and time
 *   4. Details: Optional one-line summary + expandable "Details" section for sensor readings & diagnostics.
 */
@Composable
fun ActivityHistoryScreen(
    events: List<SafetyEventEntity>,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearConfirmDialog by rememberSaveable { mutableStateOf(false) }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "Clear activity history?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal
                )
            },
            text = {
                Text(
                    text = "Remove all recorded events from this device?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NeutralSlate
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirmDialog = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DeepCharcoal,
                        contentColor = Color.White,
                        disabledContainerColor = DeepCharcoal.copy(alpha = 0.5f),
                        disabledContentColor = Color.White.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("confirm_clear_history_button")
                ) {
                    Text("Clear", color = Color.White, maxLines = 1, softWrap = false)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearConfirmDialog = false },
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.testTag("cancel_clear_history_button")
                ) {
                    Text("Cancel", color = DeepCharcoal, maxLines = 1, softWrap = false)
                }
            },
            containerColor = CardWhite
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WarmMistBackground)
            .testTag("activity_history_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 540.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Compact Header Row with aligned Clear button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Activity History",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = DeepCharcoal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("activity_history_heading")
                )

                if (events.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { showClearConfirmDialog = true },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 40.dp)
                            .testTag("clear_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            tint = DeepCharcoal,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Clear",
                            color = DeepCharcoal,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (events.isEmpty()) {
                PastelCard(
                    backgroundColor = CardWhite,
                    cornerRadius = 20.dp,
                    contentPadding = PaddingValues(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_history_card")
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(LavenderSoftSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.History,
                                contentDescription = "No recorded activity yet",
                                tint = DeepCharcoal,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "No events recorded yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Fall detections, cancelled alerts, and test alerts will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NeutralSlate,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("activity_history_list")
                ) {
                    items(events, key = { it.id }) { item ->
                        HistoryEventCard(event = item)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEventCard(event: SafetyEventEntity) {
    var detailsExpanded by rememberSaveable(event.id) { mutableStateOf(false) }

    val eventLabel = resolveCompactEventTitle(event)
    val (statusLabel, statusBadgeColor) = resolveCompactEventStatus(event)
    val shortTime = remember(event.timestampMillis) { formatShortTimestamp(event.timestampMillis) }
    val fullTime = remember(event.timestampMillis) { formatFullTimestamp(event.timestampMillis) }
    val oneLineSummary = remember(event.eventType, event.outcomeSummary, event.deliveryStatus) {
        resolveShortOneLineSummary(event)
    }

    val icon = when (event.eventType) {
        SafetyEventType.CHECK_IN_CANCELLED.name,
        SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED.name -> Icons.Filled.CheckCircle
        SafetyEventType.TEST_ALERT_TRIGGERED.name,
        SafetyEventType.RECIPIENT_ALERT_RECEIVED.name -> Icons.Filled.NotificationsActive
        else -> Icons.Filled.WarningAmber
    }

    val iconBgColor = when (statusLabel) {
        "Cancelled" -> LavenderSoftSurface
        "Failed" -> CoralSoftSurface
        "Acknowledged" -> MintSoftSurface
        else -> if (event.eventType == SafetyEventType.TEST_ALERT_TRIGGERED.name) SkyInfoSurface else MintSoftSurface
    }

    val hasExpandableDetails = event.outcomeSummary.isNotBlank() ||
        event.deliveryStatus.isNotBlank() ||
        event.sensorDetails.isNotBlank()

    PastelCard(
        backgroundColor = CardWhite,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_event_card_${event.id}")
    ) {
        // Top Row: Event Icon + Event Title + Time on left, non-wrapping Status pill on right
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(iconBgColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = DeepCharcoal,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = eventLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = InkBlack,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = shortTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralSlate,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            StatusPillBadge(
                label = statusLabel,
                containerColor = statusBadgeColor,
                modifier = Modifier.testTag("history_status_badge_${event.id}")
            )
        }

        // Optional 1-line short summary + compact expandable Details toggle
        if (oneLineSummary.isNotBlank() || hasExpandableDetails) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (oneLineSummary.isNotBlank()) {
                    Text(
                        text = oneLineSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }

                if (hasExpandableDetails) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { detailsExpanded = !detailsExpanded }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("toggle_details_${event.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Details",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = DeepCharcoal,
                            maxLines = 1,
                            softWrap = false
                        )
                        Icon(
                            imageVector = if (detailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (detailsExpanded) "Hide details" else "Show details",
                            tint = DeepCharcoal,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Expandable Details section for sensor readings, acceleration values, and full diagnostics
        AnimatedVisibility(visible = detailsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("expanded_details_${event.id}"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HorizontalDivider(color = HairlineDivider)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Recorded: $fullTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = NeutralSlate
                )
                if (event.deliveryStatus.isNotBlank()) {
                    Text(
                        text = "Result: ${event.deliveryStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = DeepCharcoal
                    )
                }
                if (event.outcomeSummary.isNotBlank()) {
                    Text(
                        text = event.outcomeSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = NeutralSlate
                    )
                }
                if (event.sensorDetails.isNotBlank()) {
                    Text(
                        text = event.sensorDetails,
                        style = MaterialTheme.typography.labelSmall,
                        color = NeutralSlate
                    )
                }
            }
        }
    }
}

/**
 * Maps an event to a concise 1-line title:
 * - "Fall detected"
 * - "Alert cancelled"
 * - "Test alert"
 */
internal fun resolveCompactEventTitle(event: SafetyEventEntity): String {
    return when (event.eventType) {
        SafetyEventType.CHECK_IN_CANCELLED.name -> "Alert cancelled"
        SafetyEventType.TEST_ALERT_TRIGGERED.name -> "Test alert"
        SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED.name -> "Alert acknowledged"
        SafetyEventType.RECIPIENT_ALERT_RECEIVED.name -> "Emergency alert received"
        SafetyEventType.POSSIBLE_FALL_DETECTED.name,
        SafetyEventType.COUNTDOWN_EXPIRED.name,
        SafetyEventType.HELP_REQUESTED_NOW.name -> "Fall detected"
        else -> when {
            event.title.contains("Acknowledged", ignoreCase = true) -> "Alert acknowledged"
            event.title.contains("Cancel", ignoreCase = true) -> "Alert cancelled"
            event.title.contains("Test", ignoreCase = true) -> "Test alert"
            else -> "Fall detected"
        }
    }
}

/**
 * Maps an event to a concise non-wrapping status badge:
 * - "Acknowledged"
 * - "Sent"
 * - "Cancelled"
 * - "Failed"
 */
internal fun resolveCompactEventStatus(event: SafetyEventEntity): Pair<String, Color> {
    val rawStatus = "${event.deliveryStatus} ${event.outcomeSummary}"
    return when {
        event.eventType == SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED.name ||
            rawStatus.contains("Acknowledged", ignoreCase = true) ||
            rawStatus.contains("checking on you", ignoreCase = true) ->
            "Acknowledged" to MintSoftSurface
        event.eventType == SafetyEventType.CHECK_IN_CANCELLED.name ||
            event.deliveryStatus.contains("Cancel", ignoreCase = true) ->
            "Cancelled" to LavenderSoftSurface
        rawStatus.contains("Fail", ignoreCase = true) ||
            rawStatus.contains("Not configured", ignoreCase = true) ||
            rawStatus.contains("Blocked", ignoreCase = true) ||
            rawStatus.contains("Unavailable", ignoreCase = true) ->
            "Failed" to CoralSoftSurface
        else ->
            "Sent" to MintSoftSurface
    }
}

/**
 * Produces a concise one-line human-readable summary while keeping raw sensor readings
 * and acceleration values inside the expandable Details section.
 */
private fun resolveShortOneLineSummary(event: SafetyEventEntity): String {
    return when (event.eventType) {
        SafetyEventType.CHECK_IN_CANCELLED.name ->
            "Cancelled by user during check-in."
        SafetyEventType.TEST_ALERT_TRIGGERED.name ->
            if (event.deliveryStatus.contains("Fail", ignoreCase = true)) {
                "Test alert could not be delivered."
            } else {
                "Test alert sent to emergency contact."
            }
        SafetyEventType.RECIPIENT_ALERT_ACKNOWLEDGED.name ->
            event.outcomeSummary.ifBlank { "Contact is checking on you." }
        SafetyEventType.RECIPIENT_ALERT_RECEIVED.name ->
            event.outcomeSummary.ifBlank { "Received emergency alert." }
        SafetyEventType.POSSIBLE_FALL_DETECTED.name ->
            "Check-in triggered by motion sensor."
        SafetyEventType.COUNTDOWN_EXPIRED.name ->
            "No response during check-in countdown."
        SafetyEventType.HELP_REQUESTED_NOW.name ->
            "Emergency alert triggered immediately."
        else -> event.deliveryStatus.ifBlank { event.outcomeSummary }
    }
}

private fun formatShortTimestamp(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

private fun formatFullTimestamp(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM d, yyyy • h:mm:ss a", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}

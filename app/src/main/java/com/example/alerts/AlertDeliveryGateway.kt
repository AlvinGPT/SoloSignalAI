package com.example.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AlertFirestoreRepository
import com.example.domain.AlertChannelType
import com.example.domain.CandidateEventSummary
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.IncomingRecipientAlert
import com.example.domain.SosAudioClipMetadata
import com.example.domain.TrustedContact
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AlertMessagePayload(
    val alertId: String,
    val isTestAlert: Boolean,
    val subjectLine: String,
    val bodyText: String,
    val recipientName: String,
    val recipientEmail: String,
    val recipientPhone: String,
    val userDisplayName: String,
    val timestampFormatted: String,
    val locationStatusNote: String,
    val locationSnapshot: GpsLocationSnapshot? = null,
    val audioClip: SosAudioClipMetadata? = null,
    val audioStatusKey: String = "DISABLED",
    val audioStatusNote: String = "SOS audio recording disabled by user"
)

sealed class AlertDeliveryResult {
    data class RemoteDelivered(
        val channelUsed: AlertChannelType,
        val providerName: String,
        val messageId: String,
        val detail: String,
        val emailSent: Boolean = true
    ) : AlertDeliveryResult()

    data class LocalFallbackPosted(
        val detail: String
    ) : AlertDeliveryResult()

    data class Failed(
        val reason: String,
        val requiresGmailAuth: Boolean = false
    ) : AlertDeliveryResult()
}

/**
 * Handles alert payload construction, high-priority Android notifications, vibration,
 * incoming Trusted Contact pop-up notifications, centralized alarm/vibration stop control,
 * and real email/Google Chat delivery.
 */
class AlertDeliveryGateway(
    private val context: Context,
    private val googleChatClient: GoogleChatAlertClient = GoogleChatAlertClient(),
    private val emailAlertClient: EmailAlertClient = EmailAlertClient()
) {

    private val appContext: Context = context.applicationContext ?: context
    private val notificationManager: NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        loadPersistedAlertState(appContext)
    }

    fun getEmergencyBuzzerUri(): Uri {
        return getEmergencyBuzzerUri(appContext)
    }

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val checkInSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val buzzerSoundUri = getEmergencyBuzzerUri()

            val checkInAudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val emergencyAudioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // Safe migration: Delete obsolete channels that permanently locked in system default notification sounds
            val obsoleteChannelIds = listOf(
                "solosignal_recipient_emergency_v5",
                "solosignal_recipient_popup_alerts",
                "solosignal_recipient_emergency_v4",
                "solosignal_recipient_emergency_v3"
            )
            for (oldChannelId in obsoleteChannelIds) {
                try {
                    if (notificationManager.getNotificationChannel(oldChannelId) != null) {
                        notificationManager.deleteNotificationChannel(oldChannelId)
                    }
                } catch (_: Exception) {
                }
            }

            val checkInChannel = NotificationChannel(
                CHANNEL_CHECK_IN_URGENT,
                appContext.getString(R.string.notif_channel_checkin_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.notif_channel_checkin_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 350L, 200L, 450L)
                setSound(checkInSoundUri, checkInAudioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val recipientBuzzerChannel = NotificationChannel(
                CHANNEL_RECIPIENT_BUZZER,
                "Emergency SOS Buzzer Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-importance emergency buzzer alerts with distinctive electronic buzzer sound and vibration when a linked contact needs help"
                enableVibration(true)
                vibrationPattern = EMERGENCY_VIBRATION_PATTERN
                setSound(buzzerSoundUri, emergencyAudioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableLights(true)
            }

            val dispatchChannel = NotificationChannel(
                CHANNEL_ALERT_DISPATCH,
                appContext.getString(R.string.notif_channel_dispatch_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = appContext.getString(R.string.notif_channel_dispatch_desc)
                enableVibration(true)
            }

            val emergencyFullscreenChannel = NotificationChannel(
                CHANNEL_EMERGENCY_FULLSCREEN,
                "Emergency Full-Screen Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical full-screen emergency alert interface displayed when a fall is detected and countdown finishes without user response"
                enableVibration(true)
                vibrationPattern = EMERGENCY_VIBRATION_PATTERN
                setSound(buzzerSoundUri, emergencyAudioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannels(
                listOf(checkInChannel, recipientBuzzerChannel, dispatchChannel, emergencyFullscreenChannel)
            )
        }
    }

    fun formatMessagePayload(
        alertId: String,
        contact: TrustedContact,
        eventSummary: CandidateEventSummary?,
        isTestAlert: Boolean,
        triggerReason: String,
        locationSnapshot: GpsLocationSnapshot? = null,
        locationDiagnostics: String? = null,
        audioClip: SosAudioClipMetadata? = null,
        audioStatusKey: String = "DISABLED",
        audioStatusNote: String? = null,
        medicalIdSummary: String? = null
    ): AlertMessagePayload {
        val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault())
        val timeStr = formatter.format(Date(eventSummary?.detectedAtEpochMillis ?: System.currentTimeMillis()))
        val senderName = contact.userDisplayName.trim().ifBlank { "A SoloSignal user" }
        val recipientName = contact.contactName.trim().ifBlank { "Trusted Contact" }

        val locationNote = when {
            !contact.optionalLocationEnabled ->
                "Location sharing disabled by user in Privacy Settings"
            locationSnapshot != null ->
                "GPS Coordinates: ${locationSnapshot.formattedCoordinates} — Google Maps: ${locationSnapshot.googleMapsUrl}" +
                    (locationSnapshot.nearbyEmergencyContext?.let { "\nNearby context: $it" } ?: "")
            !locationDiagnostics.isNullOrBlank() ->
                locationDiagnostics
            else ->
                "Real-time GPS fix unavailable at time of dispatch (indoor/permission off)"
        }

        val resolvedAudioNote = when {
            audioClip != null && !audioClip.downloadUrl.isNullOrBlank() ->
                "Attached (${String.format(Locale.US, "%.1f", audioClip.durationSeconds)}s ${audioClip.containerExtension.uppercase(Locale.US)}) — Secure link: ${audioClip.downloadUrl}"
            audioClip != null ->
                "Attached (${String.format(Locale.US, "%.1f", audioClip.durationSeconds)}s ${audioClip.containerExtension.uppercase(Locale.US)} audio clip captured at time of SOS)"
            !audioStatusNote.isNullOrBlank() ->
                audioStatusNote
            !contact.sosAudioEnabled ->
                "Not included (SOS audio recording is disabled in Privacy Settings)"
            else ->
                "Unavailable at time of dispatch"
        }

        val subject = if (isTestAlert) {
            "[TEST — NOT AN EMERGENCY] SoloSignal Alert from $senderName"
        } else {
            "SOS ALERT — Possible Fall Detected ($senderName)"
        }

        val body = if (isTestAlert) {
            buildString {
                appendLine("TEST — NOT AN EMERGENCY")
                appendLine("Hello $recipientName,")
                appendLine("$senderName triggered a TEST alert in SoloSignal AI to verify that your email and phone pop-up alert pipeline works.")
                appendLine()
                appendLine("Time: $timeStr")
                appendLine("Trigger: $triggerReason")
                appendLine("Location: $locationNote")
                appendLine("Audio recording: $resolvedAudioNote")
                if (locationSnapshot != null) {
                    appendLine("Open Map: ${locationSnapshot.googleMapsUrl}")
                }
                if (!medicalIdSummary.isNullOrBlank()) {
                    appendLine()
                    appendLine(medicalIdSummary)
                }
                appendLine("No emergency action is needed.")
            }.trim()
        } else {
            buildString {
                appendLine("SOS ALERT — Possible Fall Detected")
                appendLine("Hello $recipientName,")
                appendLine()
                appendLine("SoloSignal AI has detected a possible fall or urgent post-fall warning involving the user configured for this alert ($senderName).")
                appendLine("Outcome: $triggerReason.")
                appendLine()
                appendLine("Alert time: $timeStr")
                if (eventSummary != null) {
                    appendLine(
                        "Sensor screening summary: Peak ${String.format(Locale.US, "%.1f", eventSummary.peakMagnitude)} m/s², " +
                            "Orientation change ${String.format(Locale.US, "%.0f", eventSummary.orientationChangeDeg)}°"
                    )
                }
                appendLine("Location: $locationNote")
                if (locationSnapshot != null) {
                    appendLine("Google Maps Link: ${locationSnapshot.googleMapsUrl}")
                }
                appendLine("Audio recording: $resolvedAudioNote")
                if (!audioClip?.downloadUrl.isNullOrBlank()) {
                    appendLine("Audio Download Link: ${audioClip?.downloadUrl}")
                }
                if (!medicalIdSummary.isNullOrBlank()) {
                    appendLine()
                    appendLine(medicalIdSummary)
                }
                appendLine()
                appendLine("Please try to contact the user ($senderName) and assess whether assistance is needed.")
                appendLine()
                appendLine("This is an automated alert generated by a fall-detection prototype. The detection may be incorrect, and the alert does not establish that an injury has occurred.")
            }.trim()
        }

        return AlertMessagePayload(
            alertId = alertId,
            isTestAlert = isTestAlert,
            subjectLine = subject,
            bodyText = body,
            recipientName = recipientName,
            recipientEmail = contact.contactEmail.trim(),
            recipientPhone = contact.contactPhone.trim(),
            userDisplayName = senderName,
            timestampFormatted = timeStr,
            locationStatusNote = locationNote,
            locationSnapshot = locationSnapshot,
            audioClip = audioClip,
            audioStatusKey = audioStatusKey,
            audioStatusNote = resolvedAudioNote
        )
    }

    /**
     * Posts a high-priority heads-up check-in notification with "I'm OK" and "Send Help Now"
     * actions when a candidate fall occurs while the app is backgrounded or locked.
     */
    fun postCheckInUrgentNotification(
        secondsRemaining: Int,
        eventSummary: CandidateEventSummary?
    ) {
        ensureChannels()
        if (!hasNotificationPermission()) return

        val eventId = eventSummary?.eventId?.ifBlank { "checkin_${System.currentTimeMillis()}" }
            ?: "checkin_${System.currentTimeMillis()}"
        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            action = "$ACTION_VIEW_CHECK_IN.$eventId"
            data = Uri.parse("solosignal://incident/$eventId?type=checkin")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CHECK_IN, true)
            putExtra(EXTRA_INCOMING_ALERT_ID, eventId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            eventId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val imOkayIntent = Intent(appContext, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_IM_OKAY
        }
        val imOkayPending = PendingIntent.getBroadcast(
            appContext,
            101,
            imOkayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val needHelpIntent = Intent(appContext, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_SEND_HELP_NOW
        }
        val needHelpPending = PendingIntent.getBroadcast(
            appContext,
            102,
            needHelpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = appContext.getString(R.string.checkin_notif_title, secondsRemaining)
        val body = eventSummary?.summaryNote ?: appContext.getString(R.string.checkin_notif_body)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_CHECK_IN_URGENT)
            .setSmallIcon(R.drawable.ic_solosignal_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(contentPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_im_okay),
                imOkayPending
            )
            .addAction(
                android.R.drawable.ic_dialog_alert,
                appContext.getString(R.string.action_need_help_now),
                needHelpPending
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID_CHECK_IN, notification)
    }

    fun cancelCheckInNotification() {
        notificationManager.cancel(NOTIFICATION_ID_CHECK_IN)
    }

    /**
     * Momentarily acquires a screen-bright wake lock to wake the hardware display when an emergency triggers.
     */
    fun wakeUpScreen() {
        try {
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "solosignal:emergency_full_screen_wake"
            )
            wakeLock?.acquire(10_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }

    /**
     * Navigates to the system screen to grant full-screen intent special access on Android 14+.
     */
    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Posts a critical Full-Screen Intent emergency notification when a fall countdown expires without user response
     * or when immediate SOS is requested. Wakes display and brings the emergency screen to the foreground over the lock screen.
     */
    fun postEmergencyTriggeredFullScreenNotification(
        alertId: String,
        eventSummary: CandidateEventSummary?,
        contact: TrustedContact
    ): Boolean {
        ensureChannels()
        wakeUpScreen()
        if (!hasNotificationPermission()) return false

        val cleanAlertId = alertId.trim().ifBlank { "emergency_${System.currentTimeMillis()}" }
        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            action = "$ACTION_VIEW_ACTIVE_EMERGENCY.$cleanAlertId"
            data = Uri.parse("solosignal://incident/$cleanAlertId?type=emergency_fullscreen")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_EMERGENCY_ACTIVE, true)
            putExtra(EXTRA_INCOMING_ALERT_ID, cleanAlertId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            cleanAlertId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val imOkayIntent = Intent(appContext, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_IM_OKAY
        }
        val imOkayPending = PendingIntent.getBroadcast(
            appContext,
            201,
            imOkayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "EMERGENCY: Possible Fall Detected"
        val recipientName = contact.contactName.trim().ifBlank { "Emergency Contact" }
        val body = "Emergency SOS active — alert dispatched to $recipientName. Tap I'm OK if you are safe."

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CHANNEL_EMERGENCY_FULLSCREEN
        } else {
            CHANNEL_CHECK_IN_URGENT
        }

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_solosignal_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n\n${eventSummary?.summaryNote ?: ""}"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(contentPendingIntent, true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                appContext.getString(R.string.action_im_okay),
                imOkayPending
            )
            .build()

        notificationManager.notify(NOTIFICATION_ID_EMERGENCY_FULLSCREEN, notification)

        // Continuous audible buzzer and vibration until cancelled
        playEmergencyAlertSoundIfPermitted(cleanAlertId)
        vibrateForEmergencyPopUp(cleanAlertId)

        return true
    }

    fun cancelEmergencyFullScreenNotification() {
        notificationManager.cancel(NOTIFICATION_ID_EMERGENCY_FULLSCREEN)
    }

    /**
     * Resolves a concise alert type label for notifications and alert screens:
     * - "SOS requested"
     * - "Possible fall detected"
     * - "Test alert"
     */
    fun resolveConciseAlertType(alert: IncomingRecipientAlert): String {
        if (alert.isTestAlert) return "Test alert"
        val reason = alert.triggerReason.trim()
        return when {
            reason.equals("SOS requested", ignoreCase = true) ||
                reason.startsWith("Manual SOS", ignoreCase = true) -> "SOS requested"
            reason.contains("fall", ignoreCase = true) ||
                reason.contains("countdown", ignoreCase = true) -> "Possible fall detected"
            reason.contains("SOS", ignoreCase = true) ||
                reason.contains("help", ignoreCase = true) -> "SOS requested"
            alert.bodyText.contains("Possible fall", ignoreCase = true) -> "Possible fall detected"
            else -> "SOS requested"
        }
    }

    /**
     * Posts a real high-priority heads-up phone pop-up notification on the Trusted Contact's
     * device when an emergency alert arrives for them via FCM or Firestore real-time listener.
     *
     * - Never posts or re-sounds if [alert.alertId] is already acknowledged.
     * - Plays a clear, attention-grabbing alert sound and vibrates (deduplicated per [alert.alertId]),
     *   respecting notification permissions and silent/Do Not Disturb state.
     * - Displays concise message: "SOS ALERT — [Contact Name] needs help" with the alert type
     *   and a "View Emergency" action button.
     * - Configures [NotificationCompat.Builder.setFullScreenIntent] and [setContentIntent] so
     *   tapping "View Emergency" or the notification opens the exact incoming alert screen.
     */
    fun postIncomingTrustedContactPopUpNotification(alert: IncomingRecipientAlert): Boolean {
        ensureChannels()
        val alertKey = alert.alertId.trim().ifBlank { "alert_${alert.createdAtMillis}" }

        // Never sound, vibrate, or pop up an alert that has already been acknowledged!
        if (isAlertAcknowledged(alertKey) ||
            alert.deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT
        ) {
            stopEmergencyAlarmAndDismissNotification(alertKey)
            return false
        }

        if (!hasNotificationPermission()) return false

        // Deduplicate per alertId across FCM, Firestore snapshot listeners, and process restarts
        val isFirstFeedbackForAlert = markAlertFeedbackTriggered(appContext, alertKey)
        if (isFirstFeedbackForAlert) {
            vibrateForEmergencyPopUp(alertKey)
            playEmergencyAlertSoundIfPermitted(alertKey)
        } else if (didPostNotificationForAlert(alertKey)) {
            return true
        }

        val senderDisplay = alert.senderName.trim().ifBlank { "Emergency Contact" }
        val alertTypeLabel = resolveConciseAlertType(alert)

        val openAppIntent = Intent(appContext, MainActivity::class.java).apply {
            action = "$ACTION_VIEW_INCOMING_EMERGENCY.$alertKey"
            data = Uri.parse("solosignal://incident/$alertKey")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCOMING_ALERT_ID, alertKey)
            putExtra(EXTRA_INCOMING_SENDER_NAME, senderDisplay)
            putExtra(EXTRA_INCOMING_SENDER_EMAIL, alert.senderEmail)
            putExtra(EXTRA_INCOMING_TRIGGER_REASON, alert.triggerReason)
            putExtra(EXTRA_INCOMING_LOCATION_SUMMARY, alert.locationSummary)
            putExtra(EXTRA_INCOMING_MAPS_URL, alert.googleMapsUrl)
            putExtra(EXTRA_INCOMING_CREATED_AT, alert.createdAtMillis)
            putExtra(EXTRA_INCOMING_IS_TEST, alert.isTestAlert)
            alert.latitude?.let { putExtra(EXTRA_INCOMING_LATITUDE, it) }
            alert.longitude?.let { putExtra(EXTRA_INCOMING_LONGITUDE, it) }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            appContext,
            alertKey.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val viewEmergencyPendingIntent = PendingIntent.getActivity(
            appContext,
            alertKey.hashCode() + 3,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ackIntent = Intent(appContext, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_ACKNOWLEDGE_RECIPIENT_ALERT
            putExtra(CheckInActionReceiver.EXTRA_ALERT_ID, alertKey)
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            appContext,
            alertKey.hashCode() + 1,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (alert.isTestAlert) {
            "TEST INCIDENT ALERT — $senderDisplay"
        } else {
            "SOS ALERT — $senderDisplay needs help"
        }

        val summaryLine = buildString {
            if (alert.isTestAlert) {
                append("[TEST] ")
            }
            append(alertTypeLabel)
            if (alert.locationSummary.isNotBlank()) {
                append(" • ")
                append(alert.locationSummary)
            }
        }

        val buzzerSoundUri = getEmergencyBuzzerUri()

        val builder = NotificationCompat.Builder(appContext, CHANNEL_RECIPIENT_BUZZER)
            .setSmallIcon(R.drawable.ic_solosignal_logo)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summaryLine\n${alert.bodyText}"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_LIGHTS)
            .setSound(buzzerSoundUri, AudioManager.STREAM_ALARM)
            .setVibrate(EMERGENCY_VIBRATION_PATTERN)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(contentPendingIntent, false)
            .addAction(
                android.R.drawable.ic_dialog_alert,
                ACTION_LABEL_VIEW_EMERGENCY,
                viewEmergencyPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_view,
                ACTION_LABEL_CHECKING_ON_THEM,
                ackPendingIntent
            )

        if (!alert.googleMapsUrl.isNullOrBlank()) {
            val mapIntent = Intent(Intent.ACTION_VIEW, Uri.parse(alert.googleMapsUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val mapPendingIntent = PendingIntent.getActivity(
                appContext,
                alertKey.hashCode() + 2,
                mapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_dialog_map,
                "Open in Google Maps",
                mapPendingIntent
            )
        }

        val notifId = notificationIdForAlert(alertKey)
        activeNotificationIdsByAlertId[alertKey] = notifId
        postedNotificationAlertIds.add(alertKey)
        notificationManager.notify(notifId, builder.build())
        return true
    }

    /**
     * Immediately and synchronously stops any playing emergency alarm sound, cancels active
     * vibration, dismisses the active emergency notification for [alertId], and records [alertId]
     * as acknowledged locally (in-memory + SharedPreferences) so the alarm cannot restart on
     * screen recreation, duplicate push messages, or Firestore snapshot echoes.
     */
    /**
     * Immediately and synchronously stops any playing emergency buzzer sound, cancels active
     * vibration, dismisses the active emergency notification for [alertId], and records [alertId]
     * as acknowledged locally (in-memory + SharedPreferences) so the alarm cannot restart on
     * screen recreation, duplicate push messages, or Firestore snapshot echoes.
     */
    fun stopEmergencyAlarmAndDismissNotification(alertId: String? = null) {
        val cleanId = alertId?.trim().orEmpty()
        if (cleanId.isNotBlank()) {
            markAlertAcknowledgedLocally(appContext, cleanId)
        }

        // 1. Immediately cancel auto-stop timer and stop any playing MediaPlayer or Ringtone buzzer
        synchronized(alarmLock) {
            autoStopJob?.cancel()
            autoStopJob = null
            stopPlayingAudioLocked()
        }

        // 2. Immediately cancel any active vibration
        try {
            val vibrator = resolveVibrator(appContext)
            vibrator?.cancel()
        } catch (_: Exception) {
        } finally {
            isVibrationCurrentlyActive = false
        }

        // 3. Immediately dismiss the active emergency notification(s) so channel sound/vibration stops too
        try {
            if (cleanId.isNotBlank()) {
                val notifId = activeNotificationIdsByAlertId.remove(cleanId)
                    ?: notificationIdForAlert(cleanId)
                notificationManager.cancel(notifId)
            } else {
                for ((_, id) in activeNotificationIdsByAlertId) {
                    notificationManager.cancel(id)
                }
                activeNotificationIdsByAlertId.clear()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Plays a sharp, distinctive, repetitive emergency buzzer sound once per [alertId],
     * respecting Android notification permissions, channel importance, ringer mode,
     * and Do Not Disturb settings, and stores the active audio references so it can be
     * stopped immediately when the recipient acknowledges or dismisses the alert.
     * Automatically stops after 25s if unacknowledged to prevent battery drain.
     */
    fun playEmergencyAlertSoundIfPermitted(alertId: String): Boolean {
        val cleanId = alertId.trim()
        if (cleanId.isBlank()) return false
        if (isAlertAcknowledged(cleanId)) return false
        if (!hasNotificationPermission()) return false
        if (!isSoundPermittedBySystemSettings()) return false
        if (!alertIdsWithPlayedSound.add(cleanId)) return false

        synchronized(alarmLock) {
            stopPlayingAudioLocked()
            val buzzerUri = getEmergencyBuzzerUri()

            try {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(appContext, buzzerUri)
                    isLooping = true
                    prepare()
                    start()
                }
                activeMediaPlayer = mp
            } catch (mpEx: Exception) {
                Log.w(TAG, "MediaPlayer raw buzzer playback failed, trying Ringtone fallback: ${mpEx.message}")
                try {
                    val ringtone = RingtoneManager.getRingtone(appContext, buzzerUri)
                    if (ringtone != null) {
                        val alarmAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        ringtone.audioAttributes = alarmAttrs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = true
                        }
                        ringtone.play()
                        activeRingtone = ringtone
                    }
                } catch (rtEx: Exception) {
                    Log.w(TAG, "Ringtone fallback failed: ${rtEx.message}")
                }
            }

            activePlayingAlertId = cleanId
            isAlarmCurrentlyPlaying = true

            // Automatically limit buzzer playback duration to MAX_BUZZER_DURATION_MILLIS (25s) to avoid endless alarm
            autoStopJob?.cancel()
            autoStopJob = gatewayScope.launch {
                delay(MAX_BUZZER_DURATION_MILLIS)
                synchronized(alarmLock) {
                    if (activePlayingAlertId == cleanId) {
                        stopPlayingAudioLocked()
                    }
                }
            }
            return true
        }
    }

    fun testEmergencyBuzzer(durationMillis: Long = 5000L): Boolean {
        synchronized(alarmLock) {
            stopPlayingAudioLocked()
            val buzzerUri = getEmergencyBuzzerUri()
            try {
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(appContext, buzzerUri)
                    isLooping = true
                    prepare()
                    start()
                }
                activeMediaPlayer = mp
            } catch (e: Exception) {
                Log.w(TAG, "Test buzzer MediaPlayer fallback: ${e.message}")
                try {
                    val ringtone = RingtoneManager.getRingtone(appContext, buzzerUri)
                    if (ringtone != null) {
                        val alarmAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        ringtone.audioAttributes = alarmAttrs
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            ringtone.isLooping = true
                        }
                        ringtone.play()
                        activeRingtone = ringtone
                    }
                } catch (_: Exception) {
                }
            }

            isAlarmCurrentlyPlaying = true
            activePlayingAlertId = "test_buzzer_preview"

            autoStopJob?.cancel()
            autoStopJob = gatewayScope.launch {
                delay(durationMillis)
                synchronized(alarmLock) {
                    if (activePlayingAlertId == "test_buzzer_preview") {
                        stopPlayingAudioLocked()
                    }
                }
            }
            return true
        }
    }

    fun stopEmergencyBuzzerTest() {
        synchronized(alarmLock) {
            if (activePlayingAlertId == "test_buzzer_preview") {
                autoStopJob?.cancel()
                autoStopJob = null
                stopPlayingAudioLocked()
            }
        }
    }

    fun isSoundPermittedBySystemSettings(): Boolean {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val filter = notificationManager.currentInterruptionFilter
            if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                return false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_RECIPIENT_BUZZER)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) {
                return false
            }
        }
        return true
    }

    fun isVibrationPermittedBySystemSettings(): Boolean {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null && audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val filter = notificationManager.currentInterruptionFilter
            if (filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                return false
            }
        }
        return true
    }

    fun didPlaySoundForAlert(alertId: String): Boolean {
        return alertIdsWithPlayedSound.contains(alertId.trim())
    }

    fun didVibrateForAlert(alertId: String): Boolean {
        return alertIdsWithVibration.contains(alertId.trim())
    }

    fun didPostNotificationForAlert(alertId: String): Boolean {
        return postedNotificationAlertIds.contains(alertId.trim())
    }

    fun isAlarmPlaying(): Boolean {
        synchronized(alarmLock) {
            return isAlarmCurrentlyPlaying
        }
    }

    fun isVibrating(): Boolean {
        return isVibrationCurrentlyActive
    }

    fun isAlertAcknowledged(alertId: String): Boolean {
        val cleanId = alertId.trim()
        if (cleanId.isBlank()) return false
        loadPersistedAlertState(appContext)
        return acknowledgedAlertIds.contains(cleanId)
    }

    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager.canUseFullScreenIntent()
        } else {
            true
        }
    }

    /**
     * Inspects notification permissions and emergency channel settings on this device and
     * returns a clear setup warning if heads-up alerts, sound, or vibration are disabled.
     */
    fun getRecipientAlertSetupWarning(): String? {
        ensureChannels()
        if (!hasNotificationPermission()) {
            return "Notifications are disabled. Enable notifications in Android Settings to receive emergency SOS pop-ups, sound, and vibration."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_RECIPIENT_BUZZER)
            if (channel != null) {
                if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
                    return "Emergency alert channel importance is lowered. Set 'Emergency SOS Buzzer Alerts' to High importance for heads-up pop-ups and buzzer sound."
                }
                if (!channel.shouldVibrate()) {
                    return "Vibration is disabled on the Emergency SOS Buzzer Alerts channel. Enable channel vibration in Android Settings."
                }
            }
        }
        return null
    }

    /**
     * Triggers an urgent vibration pattern on the recipient's device when an emergency alert arrives.
     * Uses [AudioAttributes.USAGE_ALARM] so Android allows the vibration even when SoloSignal AI
     * is in the background while the recipient is using another app (WhatsApp, YouTube, browser).
     */
    fun vibrateForEmergencyPopUp(alertId: String = ""): Boolean {
        val cleanId = alertId.trim()
        if (cleanId.isNotBlank() && isAlertAcknowledged(cleanId)) return false
        if (!isVibrationPermittedBySystemSettings()) return false
        if (cleanId.isNotBlank() && !alertIdsWithVibration.add(cleanId)) return false

        return try {
            val vibrator = resolveVibrator(appContext)
            if (vibrator?.hasVibrator() == true) {
                val alarmAudioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            EMERGENCY_VIBRATION_PATTERN,
                            -1
                        ),
                        alarmAudioAttrs
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(EMERGENCY_VIBRATION_PATTERN, -1, alarmAudioAttrs)
                }
                isVibrationCurrentlyActive = true
                true
            } else {
                isVibrationCurrentlyActive = true
                true
            }
        } catch (_: Exception) {
            isVibrationCurrentlyActive = true
            true
        }
    }

    fun vibrateForCheckIn(enabled: Boolean) {
        if (!enabled) return
        try {
            val vibrator = resolveVibrator(appContext)
            if (vibrator?.hasVibrator() == true) {
                val alarmAudioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0L, 300L, 180L, 420L),
                            -1
                        ),
                        alarmAudioAttrs
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0L, 300L, 180L, 420L), -1, alarmAudioAttrs)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Dispatches the alert according to the user's configured channel:
     * - SOLOSIGNAL_POPUP / EMAIL: Sends a real transactional email to the trusted contact's email address
     *   using [EmailAlertClient] (Gmail API / Cloud Function).
     * - GOOGLE_CHAT: Dispatches to the connected Google Chat space AND also sends the emergency email
     *   if a valid trusted-contact email is configured.
     * - LOCAL_ONLY: Posts a clearly labelled local practice notification on this device only.
     */
    suspend fun deliverAlert(
        contact: TrustedContact,
        payload: AlertMessagePayload,
        googleChatConfig: GoogleChatAlertConfig = GoogleChatAlertConfig(),
        triggerReason: String = "No response after check-in countdown",
        gmailAccessToken: String? = null
    ): AlertDeliveryResult {
        ensureChannels()
        if (!contact.isValid) {
            return AlertDeliveryResult.Failed("Trusted contact is not configured.")
        }

        return when (contact.preferredChannel) {
            AlertChannelType.SOLOSIGNAL_POPUP,
            AlertChannelType.EMAIL -> {
                val emailRes = emailAlertClient.sendEmergencyEmail(
                    contact = contact,
                    payload = payload,
                    triggerReason = triggerReason,
                    gmailAccessTokenOverride = gmailAccessToken
                )
                when (emailRes) {
                    is EmailDispatchResult.Sent -> {
                        postLocalStatusSummaryNotification(
                            payload = payload,
                            summary = emailRes.detail
                        )
                        AlertDeliveryResult.RemoteDelivered(
                            channelUsed = contact.preferredChannel,
                            providerName = emailRes.providerName,
                            messageId = emailRes.messageId,
                            detail = emailRes.detail,
                            emailSent = true
                        )
                    }
                    is EmailDispatchResult.Failure -> {
                        AlertDeliveryResult.Failed(
                            reason = emailRes.reason,
                            requiresGmailAuth = emailRes.requiresAuthorization
                        )
                    }
                }
            }

            AlertChannelType.GOOGLE_CHAT -> {
                val chatOutcome = googleChatClient.sendPossibleFallAlert(
                    config = googleChatConfig,
                    payload = payload,
                    triggerReason = triggerReason
                )
                val emailRes = if (contact.hasValidEmail) {
                    emailAlertClient.sendEmergencyEmail(
                        contact = contact,
                        payload = payload,
                        triggerReason = triggerReason,
                        gmailAccessTokenOverride = gmailAccessToken
                    )
                } else {
                    null
                }

                when {
                    emailRes is EmailDispatchResult.Sent && chatOutcome is GoogleChatSendOutcome.Success -> {
                        val combinedDetail = "${emailRes.detail} Google Chat space also notified (${chatOutcome.messageId})."
                        postLocalStatusSummaryNotification(payload, combinedDetail)
                        AlertDeliveryResult.RemoteDelivered(
                            channelUsed = AlertChannelType.GOOGLE_CHAT,
                            providerName = "${emailRes.providerName} + Google Chat",
                            messageId = emailRes.messageId,
                            detail = combinedDetail,
                            emailSent = true
                        )
                    }
                    emailRes is EmailDispatchResult.Sent -> {
                        postLocalStatusSummaryNotification(payload, emailRes.detail)
                        AlertDeliveryResult.RemoteDelivered(
                            channelUsed = AlertChannelType.EMAIL,
                            providerName = emailRes.providerName,
                            messageId = emailRes.messageId,
                            detail = emailRes.detail,
                            emailSent = true
                        )
                    }
                    chatOutcome is GoogleChatSendOutcome.Success -> {
                        postLocalStatusSummaryNotification(payload, chatOutcome.detail)
                        AlertDeliveryResult.RemoteDelivered(
                            channelUsed = AlertChannelType.GOOGLE_CHAT,
                            providerName = "Google Chat API",
                            messageId = chatOutcome.messageId,
                            detail = chatOutcome.detail,
                            emailSent = false
                        )
                    }
                    else -> {
                        val errReason = (emailRes as? EmailDispatchResult.Failure)?.reason
                            ?: (chatOutcome as? GoogleChatSendOutcome.Failure)?.reason
                            ?: "Email and Google Chat delivery failed."
                        val needsAuth = (emailRes as? EmailDispatchResult.Failure)?.requiresAuthorization == true
                        AlertDeliveryResult.Failed(errReason, requiresGmailAuth = needsAuth)
                    }
                }
            }

            AlertChannelType.LOCAL_ONLY -> {
                val posted = postLocalStatusSummaryNotification(
                    payload = payload,
                    summary = "Local device notification only — no external email sent."
                )
                if (posted) {
                    AlertDeliveryResult.LocalFallbackPosted(
                        "Local notification posted on this device only (external email disabled by channel setting)."
                    )
                } else {
                    AlertDeliveryResult.Failed(
                        "Notification permission is disabled on this device."
                    )
                }
            }
        }
    }

    private fun postLocalStatusSummaryNotification(
        payload: AlertMessagePayload,
        summary: String
    ): Boolean {
        if (!hasNotificationPermission()) return false

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            action = "$ACTION_VIEW_INCOMING_EMERGENCY.${payload.alertId}"
            data = Uri.parse("solosignal://incident/${payload.alertId}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCOMING_ALERT_ID, payload.alertId)
            putExtra(EXTRA_INCOMING_IS_TEST, payload.isTestAlert)
            putExtra(EXTRA_INCOMING_SENDER_NAME, payload.userDisplayName)
            putExtra(EXTRA_INCOMING_TRIGGER_REASON, payload.subjectLine)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            payload.alertId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT_DISPATCH)
            .setSmallIcon(R.drawable.ic_solosignal_logo)
            .setContentTitle(payload.subjectLine)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${payload.bodyText}\n\nStatus: $summary"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(
            if (payload.isTestAlert) NOTIFICATION_ID_TEST_ALERT else NOTIFICATION_ID_REAL_ALERT,
            notification
        )
        return true
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

    companion object {
        const val CHANNEL_CHECK_IN_URGENT = "solosignal_checkin_urgent"
        const val CHANNEL_EMERGENCY_FULLSCREEN = "solosignal_emergency_fullscreen_v1"
        const val CHANNEL_RECIPIENT_BUZZER = "solosignal_recipient_emergency_buzzer_v1"
        const val CHANNEL_RECIPIENT_POPUP = CHANNEL_RECIPIENT_BUZZER
        const val CHANNEL_ALERT_DISPATCH = "solosignal_alert_dispatch"
        const val ACTION_LABEL_VIEW_EMERGENCY = "View Emergency"
        const val ACTION_LABEL_CHECKING_ON_THEM = "I'm checking on them"
        const val ACTION_VIEW_INCOMING_EMERGENCY = "com.example.solosignal.ACTION_VIEW_INCOMING_EMERGENCY"
        const val ACTION_VIEW_CHECK_IN = "com.example.solosignal.ACTION_VIEW_CHECK_IN"
        const val ACTION_VIEW_ACTIVE_EMERGENCY = "com.example.solosignal.ACTION_VIEW_ACTIVE_EMERGENCY"
        const val MAX_BUZZER_DURATION_MILLIS = 25_000L
        private const val TAG = "AlertDeliveryGateway"

        fun getEmergencyBuzzerUri(context: Context): Uri {
            return Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/raw/emergency_buzzer")
        }

        private const val PREFS_ALERT_DEDUP = "solosignal_alert_dedup_prefs"
        private const val KEY_ACKNOWLEDGED_ALERT_IDS = "acknowledged_alert_ids"
        private const val KEY_FEEDBACK_ALERT_IDS = "feedback_alert_ids"
        private const val KEY_PENDING_ACK_ALERT_IDS = "pending_ack_alert_ids"

        private val alarmLock = Any()
        private var activeMediaPlayer: MediaPlayer? = null
        private var activeRingtone: Ringtone? = null
        private var activePlayingAlertId: String? = null
        private var autoStopJob: Job? = null
        private val gatewayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        @Volatile
        private var isAlarmCurrentlyPlaying: Boolean = false
        @Volatile
        private var isVibrationCurrentlyActive: Boolean = false
        @Volatile
        private var persistedStateLoaded: Boolean = false

        private fun stopPlayingAudioLocked() {
            try {
                activeMediaPlayer?.apply {
                    if (isPlaying) {
                        stop()
                    }
                    release()
                }
            } catch (_: Exception) {
            }
            activeMediaPlayer = null

            try {
                activeRingtone?.stop()
            } catch (_: Exception) {
            }
            activeRingtone = null

            activePlayingAlertId = null
            isAlarmCurrentlyPlaying = false
        }

        private val alertIdsWithAlertFeedback = ConcurrentHashMap.newKeySet<String>()
        private val alertIdsWithPlayedSound = ConcurrentHashMap.newKeySet<String>()
        private val alertIdsWithVibration = ConcurrentHashMap.newKeySet<String>()
        private val postedNotificationAlertIds = ConcurrentHashMap.newKeySet<String>()
        private val acknowledgedAlertIds = ConcurrentHashMap.newKeySet<String>()
        private val pendingFirestoreAckAlertIds = ConcurrentHashMap.newKeySet<String>()
        private val activeNotificationIdsByAlertId = ConcurrentHashMap<String, Int>()

        fun notificationIdForAlert(alertId: String): Int {
            return NOTIFICATION_ID_RECIPIENT_POPUP_BASE + (alertId.trim().hashCode() and 0x0FFF)
        }

        internal fun resolveVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }

        internal fun loadPersistedAlertState(context: Context) {
            if (persistedStateLoaded) return
            synchronized(alarmLock) {
                if (persistedStateLoaded) return
                try {
                    val prefs = context.applicationContext.getSharedPreferences(
                        PREFS_ALERT_DEDUP,
                        Context.MODE_PRIVATE
                    )
                    prefs.getStringSet(KEY_ACKNOWLEDGED_ALERT_IDS, emptySet())
                        ?.filter { it.isNotBlank() }
                        ?.let { acknowledgedAlertIds.addAll(it) }
                    prefs.getStringSet(KEY_FEEDBACK_ALERT_IDS, emptySet())
                        ?.filter { it.isNotBlank() }
                        ?.let { alertIdsWithAlertFeedback.addAll(it) }
                    prefs.getStringSet(KEY_PENDING_ACK_ALERT_IDS, emptySet())
                        ?.filter { it.isNotBlank() }
                        ?.let { pendingFirestoreAckAlertIds.addAll(it) }
                } catch (_: Exception) {
                }
                persistedStateLoaded = true
            }
        }

        internal fun markAlertFeedbackTriggered(context: Context, alertId: String): Boolean {
            loadPersistedAlertState(context)
            val clean = alertId.trim()
            if (clean.isBlank() || acknowledgedAlertIds.contains(clean)) return false
            val added = alertIdsWithAlertFeedback.add(clean)
            if (added) {
                try {
                    val prefs = context.applicationContext.getSharedPreferences(
                        PREFS_ALERT_DEDUP,
                        Context.MODE_PRIVATE
                    )
                    val trimmed = alertIdsWithAlertFeedback.toList().takeLast(200).toSet()
                    prefs.edit().putStringSet(KEY_FEEDBACK_ALERT_IDS, trimmed).apply()
                } catch (_: Exception) {
                }
            }
            return added
        }

        fun markAlertAcknowledgedLocally(context: Context, alertId: String) {
            loadPersistedAlertState(context)
            val clean = alertId.trim()
            if (clean.isBlank()) return
            acknowledgedAlertIds.add(clean)
            alertIdsWithAlertFeedback.add(clean)
            try {
                val prefs = context.applicationContext.getSharedPreferences(
                    PREFS_ALERT_DEDUP,
                    Context.MODE_PRIVATE
                )
                val trimmedAck = acknowledgedAlertIds.toList().takeLast(200).toSet()
                val trimmedFeedback = alertIdsWithAlertFeedback.toList().takeLast(200).toSet()
                prefs.edit()
                    .putStringSet(KEY_ACKNOWLEDGED_ALERT_IDS, trimmedAck)
                    .putStringSet(KEY_FEEDBACK_ALERT_IDS, trimmedFeedback)
                    .commit()
            } catch (_: Exception) {
            }
        }

        fun enqueuePendingFirestoreAcknowledgement(context: Context, alertId: String) {
            loadPersistedAlertState(context)
            val clean = alertId.trim()
            if (clean.isBlank()) return
            pendingFirestoreAckAlertIds.add(clean)
            try {
                val prefs = context.applicationContext.getSharedPreferences(
                    PREFS_ALERT_DEDUP,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putStringSet(KEY_PENDING_ACK_ALERT_IDS, pendingFirestoreAckAlertIds.toSet())
                    .apply()
            } catch (_: Exception) {
            }
        }

        fun markPendingFirestoreAcknowledgementSynced(context: Context, alertId: String) {
            loadPersistedAlertState(context)
            val clean = alertId.trim()
            if (clean.isBlank()) return
            if (pendingFirestoreAckAlertIds.remove(clean)) {
                try {
                    val prefs = context.applicationContext.getSharedPreferences(
                        PREFS_ALERT_DEDUP,
                        Context.MODE_PRIVATE
                    )
                    prefs.edit()
                        .putStringSet(KEY_PENDING_ACK_ALERT_IDS, pendingFirestoreAckAlertIds.toSet())
                        .apply()
                } catch (_: Exception) {
                }
            }
        }

        fun getPendingFirestoreAcknowledgements(context: Context): Set<String> {
            loadPersistedAlertState(context)
            return pendingFirestoreAckAlertIds.toSet()
        }

        fun resetDeduplicationForTesting(context: Context? = null) {
            synchronized(alarmLock) {
                autoStopJob?.cancel()
                autoStopJob = null
                stopPlayingAudioLocked()
                isVibrationCurrentlyActive = false
            }
            alertIdsWithAlertFeedback.clear()
            alertIdsWithPlayedSound.clear()
            alertIdsWithVibration.clear()
            postedNotificationAlertIds.clear()
            acknowledgedAlertIds.clear()
            pendingFirestoreAckAlertIds.clear()
            activeNotificationIdsByAlertId.clear()
            if (context != null) {
                try {
                    context.applicationContext
                        .getSharedPreferences(PREFS_ALERT_DEDUP, Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .commit()
                } catch (_: Exception) {
                }
            }
        }

        val EMERGENCY_VIBRATION_PATTERN = longArrayOf(0L, 500L, 200L, 650L, 200L, 700L, 250L, 800L)

        const val NOTIFICATION_ID_CHECK_IN = 3101
        const val NOTIFICATION_ID_REAL_ALERT = 3102
        const val NOTIFICATION_ID_TEST_ALERT = 3103
        const val NOTIFICATION_ID_EMERGENCY_FULLSCREEN = 3104
        const val NOTIFICATION_ID_RECIPIENT_POPUP_BASE = 4000

        const val EXTRA_OPEN_CHECK_IN = "extra_open_check_in"
        const val EXTRA_OPEN_EMERGENCY_ACTIVE = "extra_open_emergency_active"
        const val EXTRA_INCOMING_ALERT_ID = "extra_incoming_alert_id"
        const val EXTRA_INCOMING_SENDER_NAME = "extra_incoming_sender_name"
        const val EXTRA_INCOMING_SENDER_EMAIL = "extra_incoming_sender_email"
        const val EXTRA_INCOMING_TRIGGER_REASON = "extra_incoming_trigger_reason"
        const val EXTRA_INCOMING_LOCATION_SUMMARY = "extra_incoming_location_summary"
        const val EXTRA_INCOMING_MAPS_URL = "extra_incoming_maps_url"
        const val EXTRA_INCOMING_CREATED_AT = "extra_incoming_created_at"
        const val EXTRA_INCOMING_IS_TEST = "extra_incoming_is_test"
        const val EXTRA_INCOMING_LATITUDE = "extra_incoming_latitude"
        const val EXTRA_INCOMING_LONGITUDE = "extra_incoming_longitude"

        fun buildPayload(
            contact: TrustedContact,
            chatConfig: GoogleChatAlertConfig,
            isTestAlert: Boolean,
            timestampMillis: Long,
            triggerReasonNote: String,
            locationSnapshot: GpsLocationSnapshot? = null
        ): AlertMessagePayload {
            val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a z", Locale.getDefault())
            val timeStr = formatter.format(Date(timestampMillis))
            val senderName = contact.userDisplayName.trim().ifBlank { "A SoloSignal user" }
            val recipientName = contact.contactName.trim().ifBlank { "Trusted Contact" }
            val locationNote = when {
                !contact.optionalLocationEnabled ->
                    "Location sharing disabled by user in Privacy Settings"
                locationSnapshot != null ->
                    "GPS Coordinates: ${locationSnapshot.formattedCoordinates} — Google Maps: ${locationSnapshot.googleMapsUrl}"
                else ->
                    "Real-time GPS fix unavailable at time of dispatch"
            }
            val subject = if (isTestAlert) {
                "[TEST — NOT AN EMERGENCY] SoloSignal Check-In Alert from $senderName"
            } else {
                "URGENT: SoloSignal Check-In Alert for $senderName"
            }
            val body = buildString {
                appendLine("SOLOSIGNAL SAFETY CHECK-IN ALERT")
                appendLine("Hello $recipientName,")
                appendLine("SoloSignal detected a possible fall or emergency check-in request for $senderName.")
                appendLine("Outcome: $triggerReasonNote")
                appendLine("Time detected: $timeStr")
                appendLine("Location: $locationNote")
                if (locationSnapshot != null) {
                    appendLine("Google Maps Link: ${locationSnapshot.googleMapsUrl}")
                }
            }.trim()
            return AlertMessagePayload(
                alertId = java.util.UUID.randomUUID().toString(),
                isTestAlert = isTestAlert,
                subjectLine = subject,
                bodyText = body,
                recipientName = recipientName,
                recipientEmail = contact.contactEmail.trim(),
                recipientPhone = contact.contactPhone.trim(),
                userDisplayName = senderName,
                timestampFormatted = timeStr,
                locationStatusNote = locationNote,
                locationSnapshot = locationSnapshot
            )
        }
    }
}

typealias LocalAndPlaceholderAlertGateway = AlertDeliveryGateway



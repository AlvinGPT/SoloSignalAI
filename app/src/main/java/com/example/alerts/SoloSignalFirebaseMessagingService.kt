package com.example.alerts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.AlertFirestoreRepository
import com.example.data.PreferencesRepository
import com.example.domain.IncomingRecipientAlert
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Real Firebase Cloud Messaging service for SoloSignal AI.
 *
 * Responsibilities:
 * 1. `onNewToken`: Persists the refreshed FCM registration token locally and updates the
 *    authenticated user's private `/users/{uid}/devices/{deviceId}` registration in Firestore.
 * 2. `onMessageReceived`: Handles incoming high-priority `SOLOSIGNAL_EMERGENCY_ALERT` FCM messages
 *    when SoloSignal is in the foreground or background, triggers vibration and sound, posts a
 *    prominent emergency notification with a direct tap intent to open the Emergency Alert screen
 *    plus an "Open in Google Maps" action button, and confirms delivery in Firestore.
 */
class SoloSignalFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM registration token refreshed")
        val appContext = applicationContext
        serviceScope.launch {
            try {
                val prefsRepo = PreferencesRepository(appContext)
                prefsRepo.saveFcmToken(token)
                val deviceId = prefsRepo.getOrCreateDeviceInstallationId()

                val currentUser = try {
                    Firebase.auth.currentUser
                } catch (_: Exception) {
                    null
                }
                if (currentUser != null) {
                    val notificationsEnabled = isNotificationPermissionGranted()
                    val firestoreRepo = AlertFirestoreRepository(appContext)
                    val regResult = firestoreRepo.registerOrUpdateRecipientDevice(
                        deviceId = deviceId,
                        fcmToken = token,
                        notificationsEnabled = notificationsEnabled
                    )
                    val email = currentUser.email?.trim()?.lowercase().orEmpty()
                    val (activeCount, anyNotif) = regResult.getOrDefault(1 to notificationsEnabled)
                    if (email.isNotBlank()) {
                        firestoreRepo.syncIncomingLinksForRecipient(
                            recipientEmail = email,
                            activeDeviceCount = activeCount,
                            notificationsEnabled = anyNotif
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update refreshed FCM token: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val alertId = data["alertId"]?.trim().orEmpty()
        if (alertId.isBlank() && remoteMessage.notification == null) {
            return
        }

        val messageType = data["type"]?.trim().orEmpty()
        val resolvedAlertId = alertId.ifBlank { "fcm_${System.currentTimeMillis()}" }
        val gateway = AlertDeliveryGateway(applicationContext)
        gateway.ensureChannels()
        val controller = com.example.sensor.SafetyMonitoringController.getInstance(applicationContext)

        if (messageType == "SENDER_MEET_FALLBACK") {
            val recipientName = data["recipientName"]?.trim()?.ifBlank { "Trusted Contact" } ?: "Trusted Contact"
            val meetUrl = data["meetUrl"]?.trim()?.takeIf { it.isNotBlank() }
            val escalationStatus = data["escalationStatus"]?.trim()?.ifBlank {
                if (meetUrl != null) AlertFirestoreRepository.ESCALATION_MEET_CREATED
                else AlertFirestoreRepository.ESCALATION_MEET_FAILED
            } ?: AlertFirestoreRepository.ESCALATION_MEET_FAILED
            val meetError = data["meetError"]?.trim()?.takeIf { it.isNotBlank() }

            controller.onSenderMeetFallbackReceived(
                alertId = resolvedAlertId,
                recipientName = recipientName,
                escalationStatus = escalationStatus,
                meetUrl = meetUrl,
                meetError = meetError
            )
            return
        }

        val senderName = data["senderName"]?.trim()?.ifBlank { "SoloSignal User" }
            ?: remoteMessage.notification?.title
            ?: "SoloSignal User"
        val senderEmail = data["senderEmail"]?.trim().orEmpty()
        val recipientName = data["recipientName"]?.trim()?.ifBlank { "Trusted Contact" } ?: "Trusted Contact"
        val recipientEmail = data["recipientEmail"]?.trim().orEmpty()
        val isTestAlert = data["isTestAlert"]?.equals("true", ignoreCase = true) == true
        val subjectLine = data["subjectLine"]?.trim()?.ifBlank {
            remoteMessage.notification?.title ?: "SoloSignal Emergency Check-In Alert"
        } ?: "SoloSignal Emergency Check-In Alert"
        val triggerReason = data["triggerReason"]?.trim()?.ifBlank {
            "Confirmed emergency check-in alert"
        } ?: "Confirmed emergency check-in alert"
        val locationSummary = data["locationSummary"]?.trim()?.ifBlank {
            "Tap to view location details"
        } ?: "Tap to view location details"
        val googleMapsUrl = data["googleMapsUrl"]?.trim()?.takeIf { it.isNotBlank() }
        val latitude = data["latitude"]?.toDoubleOrNull()
        val longitude = data["longitude"]?.toDoubleOrNull()
        val meetUrl = data["meetUrl"]?.trim()?.takeIf { it.isNotBlank() }
        val escalationStatus = data["escalationStatus"]?.trim()?.ifBlank {
            if (meetUrl != null) AlertFirestoreRepository.ESCALATION_MEET_CREATED else "NONE"
        } ?: "NONE"
        val meetError = data["meetError"]?.trim()?.takeIf { it.isNotBlank() }
        val createdAtMillis = data["createdAtMillis"]?.toLongOrNull() ?: System.currentTimeMillis()
        val deliveryState = data["deliveryState"]?.trim()?.ifBlank {
            if (meetUrl != null) {
                AlertFirestoreRepository.STATE_ESCALATED_VIDEO_CALL
            } else {
                AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT
            }
        } ?: AlertFirestoreRepository.STATE_DELIVERED_TO_RECIPIENT
        val bodyText = remoteMessage.notification?.body?.takeIf { it.isNotBlank() }
            ?: "$senderName triggered an emergency alert ($triggerReason). Location: $locationSummary"

        if (deliveryState == AlertFirestoreRepository.STATE_ACKNOWLEDGED_BY_RECIPIENT ||
            gateway.isAlertAcknowledged(resolvedAlertId)
        ) {
            gateway.stopEmergencyAlarmAndDismissNotification(resolvedAlertId)
            return
        }

        val incomingAlert = IncomingRecipientAlert(
            alertId = resolvedAlertId,
            senderName = senderName,
            senderEmail = senderEmail,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
            isTestAlert = isTestAlert,
            subjectLine = subjectLine,
            bodyText = bodyText,
            triggerReason = triggerReason,
            deliveryState = deliveryState,
            deliveryStatusDetail = if (meetUrl != null) {
                "No response after 15s — Emergency Google Meet video call link received."
            } else {
                "Push notification received via Firebase Cloud Messaging."
            },
            googleMapsUrl = googleMapsUrl,
            locationSummary = locationSummary,
            createdAtMillis = createdAtMillis,
            latitude = latitude,
            longitude = longitude,
            escalationStatus = escalationStatus,
            meetUrl = meetUrl,
            meetError = meetError
        )

        controller.onIncomingEmergencyAlertReceived(incomingAlert)
    }

    private fun isNotificationPermissionGranted(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return granted && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }

    companion object {
        private const val TAG = "SoloSignalFCM"
    }
}

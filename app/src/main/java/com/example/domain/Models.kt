package com.example.domain

import kotlin.math.sqrt

/**
 * Navigation route constants for SoloSignal AI.
 */
object AppRoutes {
    const val ROUTE_ONBOARDING = "onboarding"
    const val ROUTE_HOME = "home"
    const val ROUTE_ACTIVITY = "activity"
    const val ROUTE_CONTACTS = "contacts"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_CHECK_IN = "check_in"
    const val ROUTE_EMERGENCY_ALERT = "emergency_alert"
}

/**
 * Single motion sample captured from Sensor.TYPE_ACCELEROMETER (plus optional Sensor.TYPE_GYROSCOPE)
 * or a synthetic replay trace.
 * Uses a monotonic timestamp in nanoseconds (SensorEvent.timestamp or SystemClock.elapsedRealtimeNanos).
 * Accelerometer units: m/s² (1 g ≈ 9.80665 m/s²).
 * Gyroscope units: rad/s (converted to deg/s via gyroMagnitudeDegPerSec).
 */
data class MotionSample(
    val timestampNanos: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val sensorType: String = "TYPE_ACCELEROMETER",
    val orientationDeg: Float? = null,
    val isSynthetic: Boolean = false,
    val gx: Float = 0f,
    val gy: Float = 0f,
    val gz: Float = 0f,
    val hasGyroscope: Boolean = (gx != 0f || gy != 0f || gz != 0f)
) {
    val magnitude: Float = sqrt(ax * ax + ay * ay + az * az)
    val magnitudeG: Float = magnitude / GRAVITY_EARTH_MS2
    val gyroMagnitudeRadPerSec: Float = sqrt(gx * gx + gy * gy + gz * gz)
    val gyroMagnitudeDegPerSec: Float = Math.toDegrees(gyroMagnitudeRadPerSec.toDouble()).toFloat()

    companion object {
        const val GRAVITY_EARTH_MS2 = 9.80665f
    }
}

/**
 * Windowed motion features computed over a bounded rolling ring buffer.
 */
data class MotionFeatures(
    val windowSampleCount: Int = 0,
    val windowDurationMs: Long = 0L,
    val currentMagnitude: Float = 0f,
    val deltaMagnitude: Float = 0f,
    val peakMagnitude: Float = 0f,
    val minMagnitude: Float = 0f,
    val meanMagnitude: Float = 0f,
    val stdDevMagnitude: Float = 0f,
    val suddenChangeCount: Int = 0,
    val orientationChangeDeg: Float = 0f,
    val postPeakStillnessStdDev: Float = 0f,
    val hasPostPeakWindow: Boolean = false,
    val sampleQualityGood: Boolean = false,
    val evaluationSummary: String = "Waiting for sensor samples",
    val peakDynamicAccelerationMs2: Float = 0f,
    val peakGyroDegPerSec: Float = 0f,
    val impactDurationMs: Long = 0L,
    val impactImpulseMs2Ms: Float = 0f,
    val rejectedAsTablePlacement: Boolean = false,
    val rejectedAsRhythmicActivity: Boolean = false
)

/**
 * Confirmation state machine defined in sensor.md Section 6.
 */
enum class MonitoringState(val displayLabel: String) {
    STOPPED("Monitoring is off"),
    MONITORING("Monitoring is on"),
    CANDIDATE_EVENT("Possible fall detected"),
    CHECK_IN("Checking in with you"),
    CANCELLED("Check-in cancelled — you're okay"),
    ALERT_PENDING("Sending…"),
    ALERT_SENT("Email sent"),
    ALERT_FAILED("Delivery failed")
}

/**
 * Honest, distinct end-to-end delivery states tracing the full pipeline.
 */
enum class DeliveryConfigStatus(val label: String, val detail: String) {
    NOT_CONFIGURED(
        label = "Not configured",
        detail = "Add a trusted contact with a valid email address to enable emergency email and phone pop-up alerts."
    ),
    READY_TO_TEST(
        label = "Ready to test",
        detail = "Trusted contact saved. Ready to send real emergency emails and phone pop-up alerts."
    ),
    SENDING(
        label = "Sending…",
        detail = "Capturing GPS location, recording emergency event, and dispatching alert to your trusted contact..."
    ),
    ALERT_PENDING(
        label = "Sending…",
        detail = "Dispatching emergency push notification and email request to the backend..."
    ),
    REQUEST_ACCEPTED(
        label = "Request accepted",
        detail = "The cloud backend recorded the emergency request. Waiting for push/email provider confirmation."
    ),
    EMAIL_SENT(
        label = "Email sent",
        detail = "Transactional email provider accepted the emergency email and returned a verified message ID."
    ),
    EMAIL_FAILED(
        label = "Email failed",
        detail = "Emergency record was created in Firestore, but the email provider could not send the email."
    ),
    SENT_BY_PROVIDER(
        label = "Sent by provider",
        detail = "Messaging provider accepted the alert and returned a verified message ID."
    ),
    DELIVERED_TO_RECIPIENT(
        label = "Delivered to recipient's phone",
        detail = "The recipient's phone received the alert and displayed a high-priority pop-up notification."
    ),
    ACKNOWLEDGED_BY_RECIPIENT(
        label = "Contact is checking on you",
        detail = "Contact is checking on you — your emergency contact acknowledged the alert."
    ),
    VIDEO_CALL_ESCALATED(
        label = "Video-call fallback triggered",
        detail = "Trusted contact did not respond within 15 seconds. An emergency Google Meet video-call link was sent."
    ),
    VIDEO_CALL_FALLBACK_FAILED(
        label = "Video-call fallback failed (Alert active)",
        detail = "Trusted contact did not respond within 15 seconds. Google Meet link creation failed, but your emergency alert remains active."
    ),
    TEST_SENT(
        label = "Test sent (Local only)",
        detail = "A clearly labelled local test notification was posted on this device. No external email was sent."
    ),
    ALERT_SENT(
        label = "Sent by provider",
        detail = "Confirmed delivery response and message ID received from the alert provider."
    ),
    ALERT_FAILED(
        label = "Delivery failed",
        detail = "Could not deliver remote alert. Check recipient registration, email configuration, or network connection."
    )
}

/**
 * Real verification and alert-readiness states for a saved Trusted Contact.
 * A contact is READY_FOR_ALERTS only when the account relationship is authorized and
 * at least one valid recipient device is registered with notifications enabled.
 */
enum class TrustedContactVerificationStatus(val label: String, val defaultDetail: String) {
    INVITATION_NEEDED(
        label = "Invite",
        defaultDetail = "This person hasn't joined SoloSignal yet. Invite them to install the app and complete setup, or choose another trusted contact who is already registered."
    ),
    INVITATION_PENDING(
        label = "Pending",
        defaultDetail = "Invitation sent. Waiting for them to sign in and accept your request."
    ),
    REGISTERED(
        label = "Registered",
        defaultDetail = "Existing SoloSignal account found. Link this account to enable emergency alerts."
    ),
    CONTACT_REQUEST_PENDING(
        label = "Pending",
        defaultDetail = "Link request sent to their account. Waiting for acceptance."
    ),
    ACCEPTED(
        label = "Connected",
        defaultDetail = "Account linked. Verifying device notification setup..."
    ),
    DEVICE_NOT_REGISTERED(
        label = "Offline",
        defaultDetail = "Account linked, but their phone is currently offline or not registered for push alerts."
    ),
    NOTIFICATIONS_DISABLED(
        label = "Offline",
        defaultDetail = "Account linked, but notifications are disabled on their device."
    ),
    READY_FOR_ALERTS(
        label = "Connected",
        defaultDetail = "Connected and ready to receive emergency alerts."
    ),
    REVOKED(
        label = "Invite",
        defaultDetail = "Contact authorization was removed."
    );

    companion object {
        const val UNREGISTERED_CONTACT_EXPLANATION =
            "This person hasn't joined SoloSignal yet. Invite them to install the app and complete setup, or choose another trusted contact who is already registered."

        fun fromStorageKey(raw: String?): TrustedContactVerificationStatus {
            if (raw.isNullOrBlank()) return INVITATION_NEEDED
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: INVITATION_NEEDED
        }
    }
}

/**
 * Represents a privacy-preserving trusted contact link / request stored in Firestore.
 */
data class TrustedContactRequestItem(
    val linkId: String,
    val senderUid: String,
    val senderName: String,
    val senderEmail: String,
    val recipientName: String,
    val recipientEmail: String,
    val recipientPhone: String = "",
    val relationship: String = "Family",
    val recipientUid: String? = null,
    val status: TrustedContactVerificationStatus = TrustedContactVerificationStatus.INVITATION_NEEDED,
    val statusDetail: String = status.defaultDetail,
    val recipientHasRegisteredAccount: Boolean = false,
    val recipientAccepted: Boolean = false,
    val recipientRegisteredDeviceCount: Int = 0,
    val recipientNotificationsEnabled: Boolean = false,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Summary of a recipient device registered for FCM push notifications on the signed-in user's account.
 */
data class RecipientDeviceInfo(
    val deviceId: String,
    val deviceName: String,
    val notificationsEnabled: Boolean,
    val updatedAtMillis: Long
)

/**
 * Real connection state for Google Chat Alerts under Settings -> Trusted Contacts.
 */
enum class GoogleChatConnectionStatus(val label: String, val defaultDetail: String) {
    NOT_CONFIGURED(
        label = "Not configured",
        defaultDetail = "Optional: Connect a Google Chat space if you also want team/space alerts."
    ),
    CONNECTING(
        label = "Connecting...",
        defaultDetail = "Verifying Firebase Authentication token, backend endpoint, and Google Chat space access..."
    ),
    CONNECTED(
        label = "Connected",
        defaultDetail = "Connected to Google Chat. Initial connection message was delivered successfully."
    ),
    CONNECTION_FAILED(
        label = "Connection failed",
        defaultDetail = "Could not verify backend or send the connection message to the selected Google Chat space."
    )
}

/**
 * Configuration for official Google Chat API alerts via a secure server-side integration.
 */
data class GoogleChatAlertConfig(
    val enabled: Boolean = false,
    val backendEndpointUrl: String = "",
    val spaceName: String = "",
    val spaceDisplayLabel: String = "",
    val connectionStatus: GoogleChatConnectionStatus = GoogleChatConnectionStatus.NOT_CONFIGURED,
    val statusDetail: String = GoogleChatConnectionStatus.NOT_CONFIGURED.defaultDetail,
    val initialConnectionMessageSent: Boolean = false,
    val connectedDestinationKey: String = "",
    val lastVerifiedEpochMillis: Long = 0L
) {
    val normalizedEndpointUrl: String
        get() = backendEndpointUrl.trim()

    val normalizedSpaceName: String
        get() = spaceName.trim().let { raw ->
            if (raw.isNotEmpty() && !raw.startsWith("spaces/")) {
                "spaces/$raw"
            } else {
                raw
            }
        }

    val currentDestinationKey: String
        get() = "${normalizedEndpointUrl.lowercase()}|$normalizedSpaceName"

    val isDestinationFormatValid: Boolean
        get() = isValidHttpsBackendUrl(normalizedEndpointUrl) &&
            isValidChatSpaceName(normalizedSpaceName)

    val hasSentInitialMessageForCurrentDestination: Boolean
        get() = initialConnectionMessageSent &&
            connectedDestinationKey.isNotEmpty() &&
            connectedDestinationKey == currentDestinationKey

    val isTrulyConnected: Boolean
        get() = connectionStatus == GoogleChatConnectionStatus.CONNECTED &&
            hasSentInitialMessageForCurrentDestination

    companion object {
        const val INITIAL_CONNECTION_MESSAGE =
            "Hi! SoloSignal AI is connected. This chat will receive possible-fall alerts when the user needs assistance. This is a connection test, not an emergency."

        const val DEFAULT_CLOUD_FUNCTION_TEMPLATE =
            "https://us-central1-gen-lang-client-0124462176.cloudfunctions.net/sendSoloSignalGoogleChatAlert"

        private val SPACE_NAME_REGEX = Regex("^spaces/[A-Za-z0-9_-]{3,}$")

        fun isValidHttpsBackendUrl(url: String): Boolean {
            if (url.isBlank()) return false
            val lower = url.lowercase()
            if (!lower.startsWith("https://")) return false
            if (lower.contains("chat.googleapis.com")) return false
            if (lower.contains("key=") || lower.contains("token=")) return false
            return url.length > 12 && url.contains(".")
        }

        fun isValidChatSpaceName(space: String): Boolean {
            val normalized = if (space.isNotBlank() && !space.startsWith("spaces/")) {
                "spaces/$space"
            } else {
                space
            }
            return SPACE_NAME_REGEX.matches(normalized)
        }
    }
}

/**
 * Snapshot of real device GPS coordinates captured for an emergency alert or location test.
 */
data class GpsLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timestampMillis: Long,
    val provider: String,
    val nearbyEmergencyContext: String? = null
) {
    val googleMapsUrl: String
        get() = "https://www.google.com/maps?q=$latitude,$longitude"

    val formattedCoordinates: String
        get() = String.format(java.util.Locale.US, "%.5f, %.5f (±%.0fm)", latitude, longitude, accuracyMeters)
}

enum class AlertChannelType(val label: String, val description: String) {
    SOLOSIGNAL_POPUP(
        label = "Email + Phone Pop-up Alert (Recommended)",
        description = "Sends an immediate emergency push notification to your authorized trusted contact's phone and dispatches an emergency email."
    ),
    EMAIL(
        label = "Email Alert (Transactional Email)",
        description = "Sends a real emergency email with your GPS Google Maps link to your trusted contact's email address."
    ),
    GOOGLE_CHAT(
        label = "Google Chat Space + Email",
        description = "Sends connection and possible-fall alerts to a Google Chat space and emails your trusted contact."
    ),
    LOCAL_ONLY(
        label = "Local Device Notification Only",
        description = "Runs the check-in and posts a practice notification on this phone only (no external email sent)."
    )
}

/**
 * Trusted contact configuration with real account/device verification status.
 * Do not use the presence of an email address or phone number alone as proof that
 * the person can receive a push notification.
 */
data class TrustedContact(
    val userDisplayName: String = "",
    val contactName: String = "",
    val contactEmail: String = "",
    val contactPhone: String = "",
    val relationship: String = "Family",
    val preferredChannel: AlertChannelType = AlertChannelType.SOLOSIGNAL_POPUP,
    val consentGranted: Boolean = true,
    val optionalLocationEnabled: Boolean = true,
    val phonePopUpAlertsEnabled: Boolean = true,
    val sosAudioEnabled: Boolean = false,
    val sosAudioConsentGranted: Boolean = false,
    val verificationStatus: TrustedContactVerificationStatus = TrustedContactVerificationStatus.INVITATION_NEEDED,
    val verificationDetail: String = TrustedContactVerificationStatus.INVITATION_NEEDED.defaultDetail,
    val recipientHasRegisteredAccount: Boolean = false,
    val recipientAccepted: Boolean = false,
    val recipientRegisteredDeviceCount: Int = 0,
    val recipientNotificationsEnabled: Boolean = false,
    val linkId: String = "",
    val recipientUid: String = ""
) {
    val hasValidEmail: Boolean
        get() = isValidEmail(contactEmail.trim())

    val hasValidPhone: Boolean
        get() = isValidPhone(contactPhone.trim())

    val isValid: Boolean
        get() = contactName.trim().length >= 2 &&
            (hasValidEmail || hasValidPhone) &&
            consentGranted

    /**
     * A contact is ready for emergency push alerts ONLY when the account relationship is
     * authorized (accepted) and at least one valid recipient device is registered.
     */
    val isReadyForPushAlerts: Boolean
        get() = isValid &&
            hasValidEmail &&
            recipientAccepted &&
            recipientRegisteredDeviceCount >= 1 &&
            verificationStatus == TrustedContactVerificationStatus.READY_FOR_ALERTS

    companion object {
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val PHONE_REGEX = Regex("^[+0-9()\\-\\s]{7,25}$")

        fun isValidEmail(email: String): Boolean {
            return email.isNotBlank() && EMAIL_REGEX.matches(email.trim())
        }

        fun isValidPhone(phone: String): Boolean {
            return phone.isNotBlank() && PHONE_REGEX.matches(phone.trim())
        }
    }
}

/**
 * Formal SOS workflow state machine covering motion screening, check-in confirmation,
 * 15-second rolling audio finalization, Firebase Storage upload, and server-side email dispatch.
 */
enum class SosWorkflowState(val label: String, val detail: String) {
    IDLE(
        label = "Idle",
        detail = "Fall monitoring is currently paused."
    ),
    MONITORING(
        label = "Monitoring",
        detail = "Motion screening active."
    ),
    POSSIBLE_FALL_DETECTED(
        label = "Possible fall detected",
        detail = "Candidate impact and posture change detected."
    ),
    AWAITING_USER_CONFIRMATION(
        label = "Awaiting user confirmation",
        detail = "Waiting for user response before countdown expires."
    ),
    SOS_TRIGGERED(
        label = "Preparing alert",
        detail = "Countdown expired without confirmation — starting SOS workflow."
    ),
    AUDIO_FINALIZING(
        label = "Preparing alert",
        detail = "Finalizing 15-second pre-trigger SOS audio clip."
    ),
    UPLOADING_AUDIO(
        label = "Uploading audio",
        detail = "Uploading SOS audio recording to protected Firebase Storage."
    ),
    SENDING_EMAIL(
        label = "Sending email",
        detail = "Dispatching emergency SOS email to trusted contact."
    ),
    ALERT_SENT(
        label = "SOS email sent",
        detail = "Emergency SOS email accepted by delivery provider."
    ),
    ALERT_FAILED(
        label = "SOS email delivery failed or could not be confirmed",
        detail = "Emergency email delivery failed or could not be confirmed."
    ),
    CANCELLED(
        label = "Cancelled",
        detail = "User confirmed 'I'm OK' — temporary SOS audio discarded."
    )
}

/**
 * Explicit user-facing status for the opt-in 15-second SOS rolling audio buffer and upload pipeline.
 */
enum class SosAudioStatus(val label: String, val detail: String) {
    DISABLED(
        label = "SOS audio disabled",
        detail = "15-second SOS audio capture is turned off."
    ),
    AUDIO_MONITORING_ENABLED(
        label = "Audio monitoring enabled",
        detail = "15-second rolling audio buffer active in memory only. Oldest audio is continuously overwritten unless an SOS is triggered."
    ),
    MICROPHONE_PERMISSION_REQUIRED(
        label = "Microphone permission required",
        detail = "Grant Android microphone permission to enable the 15-second rolling SOS audio buffer."
    ),
    AUDIO_MONITORING_UNAVAILABLE(
        label = "Audio monitoring unavailable",
        detail = "Microphone is unavailable or in use by another app. Fall detection and emergency alerts remain active without audio."
    ),
    SOS_AUDIO_CAPTURED(
        label = "SOS audio captured",
        detail = "Recent pre-fall audio preserved from the 15-second rolling memory buffer."
    ),
    UPLOAD_IN_PROGRESS(
        label = "Upload in progress",
        detail = "Uploading SOS audio recording to protected Firebase Storage."
    ),
    AUDIO_UPLOAD_FAILED(
        label = "Audio upload failed",
        detail = "Could not upload SOS audio to cloud storage. Emergency alert will still be sent."
    ),
    SOS_EMAIL_SENT(
        label = "SOS email sent",
        detail = "Emergency email sent to your trusted contact."
    ),
    SOS_EMAIL_FAILED(
        label = "SOS email delivery failed or could not be confirmed",
        detail = "Emergency email delivery failed or could not be confirmed."
    );

    val firestoreValue: String
        get() = name
}

/**
 * Metadata for a finalized SOS audio clip captured from the 15-second rolling buffer.
 */
data class SosAudioClipMetadata(
    val alertId: String,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val durationSeconds: Double,
    val preTriggerSeconds: Double,
    val postTriggerSeconds: Double = 0.0,
    val sampleRateHz: Int = 16000,
    val mimeType: String = "audio/mp4",
    val containerExtension: String = "m4a",
    val fileExtension: String = containerExtension,
    val sizeBytes: Long = 0L,
    val localFilePath: String? = null,
    val storageObjectPath: String? = null,
    val downloadUrl: String? = null,
    val attachedToEmail: Boolean = false,
    val base64Payload: String? = null,
    val base64Content: String? = base64Payload,
    val isPartialBuffer: Boolean = preTriggerSeconds < 14.5,
    val statusNote: String = ""
) {
    val formattedDuration: String
        get() = String.format(java.util.Locale.US, "%.1fs", durationSeconds)
}

/**
 * Real-time incoming alert received by the user as a Trusted Contact via FCM or Firestore.
 */
data class IncomingRecipientAlert(
    val alertId: String,
    val senderName: String,
    val senderEmail: String,
    val recipientName: String,
    val recipientEmail: String,
    val isTestAlert: Boolean,
    val subjectLine: String,
    val bodyText: String,
    val triggerReason: String,
    val deliveryState: String,
    val deliveryStatusDetail: String,
    val googleMapsUrl: String?,
    val locationSummary: String,
    val createdAtMillis: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val escalationStatus: String = "NONE",
    val escalationDeadlineMillis: Long? = null,
    val meetUrl: String? = null,
    val meetError: String? = null
)

/**
 * Frozen feature summary when a candidate event triggers the check-in state machine.
 */
data class CandidateEventSummary(
    val eventId: String,
    val detectedAtEpochMillis: Long,
    val peakMagnitude: Float,
    val minMagnitude: Float,
    val suddenChangeCount: Int,
    val orientationChangeDeg: Float,
    val postImpactStillnessStdDev: Float,
    val triggerSource: String,
    val summaryNote: String,
    val peakDynamicAccelerationMs2: Float = 0f,
    val peakGyroDegPerSec: Float = 0f,
    val impactDurationMs: Long = 0L,
    val detectionDelayMs: Long = 0L
)

/**
 * Categories of events recorded in the local Activity History.
 */
enum class SafetyEventType(val title: String) {
    POSSIBLE_FALL_DETECTED("Possible fall event"),
    CHECK_IN_CANCELLED("Cancelled by user (I'm okay)"),
    HELP_REQUESTED_NOW("Immediate help alert requested"),
    COUNTDOWN_EXPIRED("No response — countdown expired"),
    TEST_ALERT_TRIGGERED("TEST — not an emergency"),
    RECIPIENT_ALERT_RECEIVED("Received trusted-contact pop-up alert"),
    RECIPIENT_ALERT_ACKNOWLEDGED("Acknowledged emergency alert"),
    VIDEO_CALL_FALLBACK_TRIGGERED("No response after 15s — Video-call fallback triggered"),
    VIDEO_CALL_FALLBACK_FAILED("No response after 15s — Video-call fallback failed"),
    SOS_AUDIO_CAPTURED("SOS Audio Captured (15s Rolling Buffer)"),
    SOS_AUDIO_UPLOADED("SOS Audio Uploaded to Cloud Storage"),
    TRUSTED_CONTACT_VERIFIED("Trusted contact relationship updated"),
    GOOGLE_CHAT_CONNECTED("Google Chat space connected"),
    MONITORING_STARTED("Monitoring started"),
    MONITORING_STOPPED("Monitoring stopped")
}

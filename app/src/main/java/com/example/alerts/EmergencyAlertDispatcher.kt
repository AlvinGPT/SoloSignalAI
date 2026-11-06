package com.example.alerts

import android.util.Log
import com.example.data.FirestoreEmergencyAlert
import com.example.domain.AlertChannelType
import com.example.domain.CandidateEventSummary
import com.example.domain.DeliveryConfigStatus
import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
import com.example.domain.MlExplainabilityRecord
import com.example.domain.SosAudioClipMetadata
import com.example.domain.TrustedContact
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Unified alert classification for SoloSignal AI.
 * All three paths (Test Alert, Manual SOS, and Automatic Fall Alert) execute through
 * the same verified recipient resolution and notification dispatch pipeline.
 */
enum class EmergencyAlertDispatchType(
    val isTestAlert: Boolean,
    val wireKey: String
) {
    TEST(isTestAlert = true, wireKey = "TEST"),
    MANUAL_SOS(isTestAlert = false, wireKey = "MANUAL_SOS"),
    AUTOMATIC_FALL(isTestAlert = false, wireKey = "AUTOMATIC_FALL")
}

/**
 * Structured diagnostic log stages required for end-to-end correlation of every alert.
 */
enum class EmergencyDispatchLogStage {
    TEST_ALERT_BUTTON_PRESSED,
    MANUAL_SOS_BUTTON_PRESSED,
    FALL_EVENT_CONFIRMED,
    CHECK_IN_TIMEOUT,
    EMERGENCY_DISPATCH_STARTED,
    RECIPIENT_RESOLVED,
    ALERT_DOCUMENT_CREATED,
    FCM_DISPATCH_STARTED,
    FCM_DISPATCH_ACCEPTED,
    DISPATCH_FAILED
}

data class EmergencyDispatchLogEntry(
    val correlationId: String,
    val stage: EmergencyDispatchLogStage,
    val alertType: EmergencyAlertDispatchType,
    val alertId: String = "",
    val recipientUid: String? = null,
    val detail: String = "",
    val timestampMillis: Long = System.currentTimeMillis()
)

/**
 * Thread-safe diagnostic logger that assigns a single correlation ID per alert and records
 * every stage of the pipeline without logging full FCM tokens, passwords, private medical details,
 * or authentication secrets.
 */
object EmergencyDispatchDiagnostics {
    private const val TAG = "SoloSignalDispatch"
    private const val MAX_ENTRIES = 250
    private val entries = CopyOnWriteArrayList<EmergencyDispatchLogEntry>()

    fun newCorrelationId(prefix: String = "corr"): String {
        return "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(10)}_${System.currentTimeMillis()}"
    }

    fun log(
        correlationId: String,
        stage: EmergencyDispatchLogStage,
        alertType: EmergencyAlertDispatchType,
        alertId: String = "",
        recipientUid: String? = null,
        detail: String = ""
    ): EmergencyDispatchLogEntry {
        val safeRecipientUid = recipientUid?.trim()?.takeIf { it.isNotBlank() }
        val sanitizedDetail = sanitizeSensitiveText(detail)
        val entry = EmergencyDispatchLogEntry(
            correlationId = correlationId,
            stage = stage,
            alertType = alertType,
            alertId = alertId,
            recipientUid = safeRecipientUid,
            detail = sanitizedDetail
        )
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            try {
                entries.removeAt(0)
            } catch (_: Exception) {
                break
            }
        }
        val logMsg = buildString {
            append("[corr=").append(correlationId).append("] ")
            append(stage.name)
            append(" | type=").append(alertType.wireKey)
            if (alertId.isNotBlank()) append(" | alertId=").append(alertId)
            if (safeRecipientUid != null) append(" | recipientUid=").append(safeRecipientUid)
            if (sanitizedDetail.isNotBlank()) append(" | ").append(sanitizedDetail)
        }
        try {
            if (stage == EmergencyDispatchLogStage.DISPATCH_FAILED) {
                Log.e(TAG, logMsg)
            } else {
                Log.i(TAG, logMsg)
            }
        } catch (_: Throwable) {
            // Safe fallback when running in local JVM unit tests where android.util.Log is unmocked
        }
        return entry
    }

    fun getEntriesForCorrelationId(correlationId: String): List<EmergencyDispatchLogEntry> {
        return entries.filter { it.correlationId == correlationId }
    }

    fun getAllEntries(): List<EmergencyDispatchLogEntry> = entries.toList()

    fun clearForTesting() {
        entries.clear()
    }

    internal fun sanitizeSensitiveText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(Regex("Bearer\\s+[A-Za-z0-9._\\-~+/]+=*", RegexOption.IGNORE_CASE), "Bearer [REDACTED]")
            .replace(Regex("ya29\\.[A-Za-z0-9._\\-]+"), "[REDACTED_TOKEN]")
            .take(500)
    }
}

data class ResolvedEmergencyRecipient(
    val contact: TrustedContact,
    val recipientUid: String?,
    val recipientEmail: String,
    val hasValidFcmRegistration: Boolean,
    val fcmReadinessNote: String
)

sealed class EmergencyDispatchOutcome {
    data class Dispatched(
        val correlationId: String,
        val alertId: String,
        val alertType: EmergencyAlertDispatchType,
        val recipientUid: String?,
        val recipientEmail: String,
        val firestoreCreated: Boolean,
        val fcmAccepted: Boolean,
        val emailSent: Boolean,
        val deliveryStatus: DeliveryConfigStatus,
        val statusDetail: String,
        val payload: AlertMessagePayload,
        val requiresGmailAuth: Boolean = false
    ) : EmergencyDispatchOutcome()

    data class DuplicateSuppressed(
        val correlationId: String,
        val alertId: String,
        val alertType: EmergencyAlertDispatchType
    ) : EmergencyDispatchOutcome()

    data class Failed(
        val correlationId: String,
        val alertId: String,
        val alertType: EmergencyAlertDispatchType,
        val deliveryStatus: DeliveryConfigStatus,
        val reason: String,
        val requiresGmailAuth: Boolean = false
    ) : EmergencyDispatchOutcome()
}

/**
 * Single unified emergency notification dispatcher shared by:
 * - `TEST` ("Send Test Alert")
 * - `MANUAL_SOS` (Home screen red SOS button)
 * - `AUTOMATIC_FALL` (Check-in countdown timeout or user tapping "Send SOS Now" / "I need help")
 *
 * Uses the exact same verified recipient resolution, Firestore document creation, and FCM/Email
 * dispatch foundation across all three alert types while preserving alert context and preventing
 * duplicate SOS dispatches.
 */
class EmergencyAlertDispatcher(
    private val resolveContact: suspend () -> TrustedContact,
    private val resolveRecipientUid: suspend (TrustedContact) -> String?,
    private val captureLocation: suspend (Boolean) -> Pair<GpsLocationSnapshot?, String>,
    private val formatPayload: (
        alertId: String,
        contact: TrustedContact,
        eventSummary: CandidateEventSummary?,
        isTestAlert: Boolean,
        triggerReason: String,
        locationSnapshot: GpsLocationSnapshot?,
        locationDiagnostics: String?,
        audioClip: SosAudioClipMetadata?,
        audioStatusKey: String,
        audioStatusNote: String?,
        medicalIdSummary: String?
    ) -> AlertMessagePayload,
    private val createFirestoreAlert: suspend (
        alertId: String,
        contact: TrustedContact,
        payload: AlertMessagePayload,
        triggerReason: String,
        channelUsed: String,
        recipientUid: String?,
        mlExplainability: MlExplainabilityRecord?
    ) -> Result<FirestoreEmergencyAlert>,
    private val deliverRemoteAlert: suspend (
        contact: TrustedContact,
        payload: AlertMessagePayload,
        googleChatConfig: GoogleChatAlertConfig,
        triggerReason: String,
        gmailAccessToken: String
    ) -> AlertDeliveryResult,
    private val updateFirestoreEmailStatus: suspend (
        alertId: String,
        emailSent: Boolean,
        providerName: String,
        providerMessageId: String?,
        errorMessage: String?,
        statusDetail: String
    ) -> Unit = { _, _, _, _, _, _ -> }
) {
    private val dispatchMutex = Mutex()
    private val dispatchedEmergencyAlertIds = ConcurrentHashMap.newKeySet<String>()
    private val inFlightEmergencyKeys = ConcurrentHashMap.newKeySet<String>()

    fun hasDispatchedAlertId(alertId: String): Boolean = dispatchedEmergencyAlertIds.contains(alertId)

    fun resetDeduplicationForTesting() {
        dispatchedEmergencyAlertIds.clear()
        inFlightEmergencyKeys.clear()
    }

    /**
     * Executes the unified emergency dispatch pipeline inside [NonCancellable] so UI screen transitions
     * (such as dismissing `CheckInOverlay` or pausing `MainActivity`) never abort an in-flight alert.
     */
    suspend fun dispatch(
        alertType: EmergencyAlertDispatchType,
        correlationId: String = EmergencyDispatchDiagnostics.newCorrelationId(),
        alertIdOverride: String? = null,
        triggerReason: String,
        candidateSummary: CandidateEventSummary? = null,
        googleChatConfig: GoogleChatAlertConfig = GoogleChatAlertConfig(),
        gmailAccessToken: String = "",
        audioClip: SosAudioClipMetadata? = null,
        audioStatusKey: String = "DISABLED",
        audioStatusNote: String? = null,
        medicalIdSummary: String? = null,
        mlExplainability: MlExplainabilityRecord? = null,
        onProgressStateChanged: (DeliveryConfigStatus, String) -> Unit = { _, _ -> }
    ): EmergencyDispatchOutcome = withContext(NonCancellable) {
        val resolvedAlertId = when {
            !alertIdOverride.isNullOrBlank() -> alertIdOverride.trim()
            alertType == EmergencyAlertDispatchType.TEST ->
                "test_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
            alertType == EmergencyAlertDispatchType.MANUAL_SOS ->
                "sos_${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
            else -> {
                val eventSuffix = candidateSummary?.eventId?.takeIf { it.isNotBlank() }
                    ?: "${UUID.randomUUID().toString().take(12)}_${System.currentTimeMillis()}"
                "alert_$eventSuffix"
            }
        }

        // Deduplicate concurrent or repeated dispatches for the same non-test alert ID or event ID
        val dedupKey = if (alertType == EmergencyAlertDispatchType.AUTOMATIC_FALL &&
            !candidateSummary?.eventId.isNullOrBlank()
        ) {
            "event_${candidateSummary!!.eventId}"
        } else {
            resolvedAlertId
        }

        val acquired = dispatchMutex.withLock {
            if (alertType != EmergencyAlertDispatchType.TEST) {
                if (dispatchedEmergencyAlertIds.contains(resolvedAlertId) ||
                    dispatchedEmergencyAlertIds.contains(dedupKey) ||
                    !inFlightEmergencyKeys.add(dedupKey)
                ) {
                    false
                } else {
                    true
                }
            } else {
                true
            }
        }

        if (!acquired) {
            EmergencyDispatchDiagnostics.log(
                correlationId = correlationId,
                stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                alertType = alertType,
                alertId = resolvedAlertId,
                detail = "DUPLICATE_SUPPRESSED: Alert $dedupKey is already in flight or dispatched."
            )
            return@withContext EmergencyDispatchOutcome.DuplicateSuppressed(
                correlationId = correlationId,
                alertId = resolvedAlertId,
                alertType = alertType
            )
        }

        try {
            EmergencyDispatchDiagnostics.log(
                correlationId = correlationId,
                stage = EmergencyDispatchLogStage.EMERGENCY_DISPATCH_STARTED,
                alertType = alertType,
                alertId = resolvedAlertId,
                detail = "reason='$triggerReason'"
            )

            onProgressStateChanged(
                DeliveryConfigStatus.SENDING,
                DeliveryConfigStatus.SENDING.detail
            )

            // 1. Resolve connected emergency contact & recipient UID identically for all alert types
            val contact = resolveContact()
            if (!contact.isValid) {
                val missingReason =
                    "No valid trusted contact saved. Add a trusted contact with a valid email address first."
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                    alertType = alertType,
                    alertId = resolvedAlertId,
                    detail = "MISSING_EMERGENCY_CONTACT: $missingReason"
                )
                onProgressStateChanged(DeliveryConfigStatus.NOT_CONFIGURED, missingReason)
                return@withContext EmergencyDispatchOutcome.Failed(
                    correlationId = correlationId,
                    alertId = resolvedAlertId,
                    alertType = alertType,
                    deliveryStatus = DeliveryConfigStatus.NOT_CONFIGURED,
                    reason = missingReason
                )
            }

            val resolvedRecipientUid = try {
                resolveRecipientUid(contact)?.trim()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                contact.recipientUid.trim().takeIf { it.isNotBlank() }
            } ?: contact.recipientUid.trim().takeIf { it.isNotBlank() }

            val effectiveContact = if (!resolvedRecipientUid.isNullOrBlank() && contact.recipientUid != resolvedRecipientUid) {
                contact.copy(
                    recipientUid = resolvedRecipientUid,
                    recipientHasRegisteredAccount = true
                )
            } else {
                contact
            }

            val hasValidFcmRegistration = effectiveContact.isReadyForPushAlerts ||
                (effectiveContact.recipientAccepted && effectiveContact.recipientRegisteredDeviceCount > 0)

            val fcmReadinessNote = when {
                hasValidFcmRegistration ->
                    "Recipient device registered (${effectiveContact.recipientRegisteredDeviceCount.coerceAtLeast(1)} active device(s))."
                !resolvedRecipientUid.isNullOrBlank() && effectiveContact.recipientRegisteredDeviceCount <= 0 ->
                    "Recipient account resolved, but no active FCM device token is registered."
                resolvedRecipientUid.isNullOrBlank() ->
                    "Recipient UID not linked or no FCM token registered; using email/Firestore fallback."
                else ->
                    "Recipient push status: ${effectiveContact.verificationStatus.label}"
            }

            EmergencyDispatchDiagnostics.log(
                correlationId = correlationId,
                stage = EmergencyDispatchLogStage.RECIPIENT_RESOLVED,
                alertType = alertType,
                alertId = resolvedAlertId,
                recipientUid = resolvedRecipientUid,
                detail = "hasValidFcmRegistration=$hasValidFcmRegistration, status=${effectiveContact.verificationStatus.name}, note=$fcmReadinessNote"
            )

            // 2. Capture GPS location if enabled (never blocks or launches permission dialogs)
            val (capturedSnapshot, locDiag) = if (effectiveContact.optionalLocationEnabled) {
                try {
                    captureLocation(true)
                } catch (e: Exception) {
                    null to "GPS location unavailable (${e.javaClass.simpleName})"
                }
            } else {
                null to "Location not included (location sharing disabled in Privacy Settings)"
            }

            // 3. Build unified payload (preserving TEST vs MANUAL_SOS vs AUTOMATIC_FALL context)
            val payload = formatPayload(
                resolvedAlertId,
                effectiveContact,
                candidateSummary,
                alertType.isTestAlert,
                triggerReason,
                capturedSnapshot,
                locDiag,
                audioClip,
                audioStatusKey,
                audioStatusNote,
                medicalIdSummary
            )

            // 4. Create Firestore emergency_alerts document using the working Test-Alert foundation
            val firestoreResult = try {
                createFirestoreAlert(
                    resolvedAlertId,
                    effectiveContact,
                    payload,
                    triggerReason,
                    effectiveContact.preferredChannel.name,
                    resolvedRecipientUid,
                    if (!alertType.isTestAlert) mlExplainability else null
                )
            } catch (e: Exception) {
                Result.failure(e)
            }

            val firestoreCreated = firestoreResult.isSuccess
            if (firestoreCreated) {
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.ALERT_DOCUMENT_CREATED,
                    alertType = alertType,
                    alertId = resolvedAlertId,
                    recipientUid = resolvedRecipientUid,
                    detail = "Firestore emergency_alerts/$resolvedAlertId created (state=REQUEST_ACCEPTED)"
                )
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.FCM_DISPATCH_STARTED,
                    alertType = alertType,
                    alertId = resolvedAlertId,
                    recipientUid = resolvedRecipientUid,
                    detail = "Real-time Firestore & Cloud Function FCM trigger initiated ($fcmReadinessNote)"
                )
                val pushNote = if (hasValidFcmRegistration) {
                    "Push alert dispatched to ${effectiveContact.contactName}'s registered device(s)."
                } else {
                    "Note: $fcmReadinessNote"
                }
                onProgressStateChanged(
                    DeliveryConfigStatus.REQUEST_ACCEPTED,
                    "Emergency request accepted in Cloud Firestore ($resolvedAlertId). $pushNote"
                )
            } else {
                val fsErr = firestoreResult.exceptionOrNull()?.message ?: "Firestore write failed"
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                    alertType = alertType,
                    alertId = resolvedAlertId,
                    recipientUid = resolvedRecipientUid,
                    detail = "FIRESTORE_CREATE_FAILED: $fsErr"
                )
            }

            // 5. Execute remote delivery (Cloud Function FCM + Gmail API + optional Google Chat)
            if (!firestoreCreated) {
                EmergencyDispatchDiagnostics.log(
                    correlationId = correlationId,
                    stage = EmergencyDispatchLogStage.FCM_DISPATCH_STARTED,
                    alertType = alertType,
                    alertId = resolvedAlertId,
                    recipientUid = resolvedRecipientUid,
                    detail = "Attempting direct Cloud Function / Email delivery after Firestore error"
                )
            }

            val deliveryResult = try {
                deliverRemoteAlert(
                    effectiveContact,
                    payload,
                    googleChatConfig,
                    triggerReason,
                    gmailAccessToken
                )
            } catch (e: Exception) {
                AlertDeliveryResult.Failed(
                    reason = "Delivery exception: ${e.message ?: e.javaClass.simpleName}",
                    requiresGmailAuth = false
                )
            }

            when (deliveryResult) {
                is AlertDeliveryResult.RemoteDelivered -> {
                    if (alertType != EmergencyAlertDispatchType.TEST) {
                        dispatchedEmergencyAlertIds.add(resolvedAlertId)
                        dispatchedEmergencyAlertIds.add(dedupKey)
                    }
                    val status = if (deliveryResult.emailSent) {
                        DeliveryConfigStatus.EMAIL_SENT
                    } else {
                        DeliveryConfigStatus.SENT_BY_PROVIDER
                    }
                    val combinedDetail = if (hasValidFcmRegistration) {
                        "${deliveryResult.detail} High-priority FCM push alert also sent to ${effectiveContact.contactName}'s phone."
                    } else {
                        "${deliveryResult.detail} ($fcmReadinessNote)"
                    }
                    if (firestoreCreated) {
                        try {
                            updateFirestoreEmailStatus(
                                resolvedAlertId,
                                deliveryResult.emailSent,
                                deliveryResult.providerName,
                                deliveryResult.messageId,
                                null,
                                combinedDetail
                            )
                        } catch (_: Exception) {
                        }
                    }
                    EmergencyDispatchDiagnostics.log(
                        correlationId = correlationId,
                        stage = EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED,
                        alertType = alertType,
                        alertId = resolvedAlertId,
                        recipientUid = resolvedRecipientUid,
                        detail = "provider=${deliveryResult.providerName}, messageId=${deliveryResult.messageId}, hasValidFcm=$hasValidFcmRegistration"
                    )
                    onProgressStateChanged(status, combinedDetail)
                    return@withContext EmergencyDispatchOutcome.Dispatched(
                        correlationId = correlationId,
                        alertId = resolvedAlertId,
                        alertType = alertType,
                        recipientUid = resolvedRecipientUid,
                        recipientEmail = effectiveContact.contactEmail.trim().lowercase(),
                        firestoreCreated = firestoreCreated,
                        fcmAccepted = hasValidFcmRegistration || firestoreCreated,
                        emailSent = deliveryResult.emailSent,
                        deliveryStatus = status,
                        statusDetail = combinedDetail,
                        payload = payload,
                        requiresGmailAuth = false
                    )
                }

                is AlertDeliveryResult.LocalFallbackPosted -> {
                    if (alertType != EmergencyAlertDispatchType.TEST) {
                        dispatchedEmergencyAlertIds.add(resolvedAlertId)
                        dispatchedEmergencyAlertIds.add(dedupKey)
                    }
                    EmergencyDispatchDiagnostics.log(
                        correlationId = correlationId,
                        stage = EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED,
                        alertType = alertType,
                        alertId = resolvedAlertId,
                        recipientUid = resolvedRecipientUid,
                        detail = "localFallbackPosted=${deliveryResult.detail}"
                    )
                    onProgressStateChanged(DeliveryConfigStatus.TEST_SENT, deliveryResult.detail)
                    return@withContext EmergencyDispatchOutcome.Dispatched(
                        correlationId = correlationId,
                        alertId = resolvedAlertId,
                        alertType = alertType,
                        recipientUid = resolvedRecipientUid,
                        recipientEmail = effectiveContact.contactEmail.trim().lowercase(),
                        firestoreCreated = firestoreCreated,
                        fcmAccepted = firestoreCreated,
                        emailSent = false,
                        deliveryStatus = DeliveryConfigStatus.TEST_SENT,
                        statusDetail = deliveryResult.detail,
                        payload = payload,
                        requiresGmailAuth = false
                    )
                }

                is AlertDeliveryResult.Failed -> {
                    if (firestoreCreated) {
                        if (alertType != EmergencyAlertDispatchType.TEST) {
                            dispatchedEmergencyAlertIds.add(resolvedAlertId)
                            dispatchedEmergencyAlertIds.add(dedupKey)
                        }
                        val fullReason = if (hasValidFcmRegistration) {
                            "Emergency push alert dispatched to ${effectiveContact.contactName}'s phone via FCM/Firestore ($resolvedAlertId). Email notice: ${deliveryResult.reason}"
                        } else {
                            "Emergency record accepted in Cloud Firestore ($resolvedAlertId). $fcmReadinessNote Email notice: ${deliveryResult.reason}"
                        }
                        try {
                            updateFirestoreEmailStatus(
                                resolvedAlertId,
                                false,
                                "Gmail API",
                                null,
                                deliveryResult.reason,
                                fullReason
                            )
                        } catch (_: Exception) {
                        }
                        if (hasValidFcmRegistration || !resolvedRecipientUid.isNullOrBlank()) {
                            EmergencyDispatchDiagnostics.log(
                                correlationId = correlationId,
                                stage = EmergencyDispatchLogStage.FCM_DISPATCH_ACCEPTED,
                                alertType = alertType,
                                alertId = resolvedAlertId,
                                recipientUid = resolvedRecipientUid,
                                detail = "Firestore/FCM push dispatch accepted ($resolvedAlertId); auxiliary email failed: ${deliveryResult.reason}"
                            )
                        } else {
                            EmergencyDispatchDiagnostics.log(
                                correlationId = correlationId,
                                stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                                alertType = alertType,
                                alertId = resolvedAlertId,
                                recipientUid = resolvedRecipientUid,
                                detail = "RECIPIENT_TOKEN_MISSING_AND_EMAIL_FAILED: $fcmReadinessNote | ${deliveryResult.reason}"
                            )
                        }
                        onProgressStateChanged(DeliveryConfigStatus.REQUEST_ACCEPTED, fullReason)
                        return@withContext EmergencyDispatchOutcome.Dispatched(
                            correlationId = correlationId,
                            alertId = resolvedAlertId,
                            alertType = alertType,
                            recipientUid = resolvedRecipientUid,
                            recipientEmail = effectiveContact.contactEmail.trim().lowercase(),
                            firestoreCreated = true,
                            fcmAccepted = hasValidFcmRegistration,
                            emailSent = false,
                            deliveryStatus = DeliveryConfigStatus.REQUEST_ACCEPTED,
                            statusDetail = fullReason,
                            payload = payload,
                            requiresGmailAuth = deliveryResult.requiresGmailAuth
                        )
                    } else {
                        val fsErrMsg = firestoreResult.exceptionOrNull()?.message
                        val combinedError = if (!fsErrMsg.isNullOrBlank()) {
                            "Firestore error: $fsErrMsg. ${deliveryResult.reason}"
                        } else {
                            deliveryResult.reason
                        }
                        EmergencyDispatchDiagnostics.log(
                            correlationId = correlationId,
                            stage = EmergencyDispatchLogStage.DISPATCH_FAILED,
                            alertType = alertType,
                            alertId = resolvedAlertId,
                            recipientUid = resolvedRecipientUid,
                            detail = "BACKEND_DISPATCH_FAILED: $combinedError"
                        )
                        onProgressStateChanged(DeliveryConfigStatus.ALERT_FAILED, combinedError)
                        return@withContext EmergencyDispatchOutcome.Failed(
                            correlationId = correlationId,
                            alertId = resolvedAlertId,
                            alertType = alertType,
                            deliveryStatus = DeliveryConfigStatus.ALERT_FAILED,
                            reason = combinedError,
                            requiresGmailAuth = deliveryResult.requiresGmailAuth
                        )
                    }
                }
            }
        } finally {
            dispatchMutex.withLock {
                inFlightEmergencyKeys.remove(dedupKey)
            }
        }
    }
}

package com.example.alerts

import com.example.BuildConfig
import com.example.domain.TrustedContact
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Truthful result of an emergency or test email delivery attempt.
 * Only reports [Sent] when Google Gmail API or the authenticated Firebase Cloud Function
 * returns HTTP 2xx and a verified Gmail provider message ID.
 */
sealed class EmailDispatchResult {
    data class Sent(
        val providerName: String,
        val messageId: String,
        val recipientEmail: String,
        val detail: String,
        val duplicatePrevented: Boolean = false
    ) : EmailDispatchResult()

    data class Failure(
        val reason: String,
        val providerName: String = "Gmail API",
        val httpCode: Int? = null,
        val requiresAuthorization: Boolean = false
    ) : EmailDispatchResult()
}

/**
 * Handles real emergency email delivery via Google's ecosystem (Gmail API & Firebase Cloud Functions)
 * for confirmed SoloSignal emergency alerts and explicit tests.
 *
 * Pipeline & Security Guarantees:
 * 1. Validates that the saved trusted contact has a valid email address before attempting to send.
 * 2. Enforces idempotency per `alertId` so a single confirmed emergency never sends duplicate emails.
 * 3. Primary Google delivery paths:
 *    A. Gmail REST API (`https://gmail.googleapis.com/gmail/v1/users/me/messages/send`) using
 *       authorized Google OAuth access token (scope: `https://www.googleapis.com/auth/gmail.send`).
 *    B. Authenticated Firebase Cloud Function (`sendSoloSignalEmergencyEmail`) with the
 *       signed-in user's Firebase ID token (`Authorization: Bearer <idToken>`).
 * 4. Never fakes success: only returns [EmailDispatchResult.Sent] when Google's Gmail API accepts the email
 *    and returns a verified message ID.
 * 5. If Gmail API authorization is not yet configured, returns a clear, honest setup-required message.
 */
class EmailAlertClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {
    private val sentReceiptsByAlertId = ConcurrentHashMap<String, EmailDispatchResult.Sent>()

    suspend fun sendEmergencyEmail(
        contact: TrustedContact,
        payload: AlertMessagePayload,
        triggerReason: String,
        senderEmailOverride: String? = null,
        gmailAccessTokenOverride: String? = null
    ): EmailDispatchResult = withContext(Dispatchers.IO) {
        val recipientEmail = contact.contactEmail.trim().lowercase().ifBlank {
            payload.recipientEmail.trim().lowercase()
        }

        // 1. Verify recipient email address before attempting to send
        if (!TrustedContact.isValidEmail(recipientEmail)) {
            return@withContext EmailDispatchResult.Failure(
                reason = "Trusted contact does not have a valid email address configured.",
                providerName = "Validation",
                httpCode = 400
            )
        }

        // 2. Idempotency check: prevent duplicate email sends for the same alertId
        val existingReceipt = sentReceiptsByAlertId[payload.alertId]
        if (existingReceipt != null) {
            return@withContext existingReceipt.copy(duplicatePrevented = true)
        }

        val senderEmail = senderEmailOverride?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: try {
                Firebase.auth.currentUser?.email?.trim()?.lowercase().orEmpty()
            } catch (_: Exception) {
                ""
            }

        val effectiveSender = senderEmail.ifBlank { "me" }

        val htmlBody = buildHtmlEmailBody(
            payload = payload,
            recipientEmail = recipientEmail,
            senderEmail = senderEmail,
            triggerReason = triggerReason
        )

        val gmailToken = gmailAccessTokenOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: try {
                BuildConfig.GMAIL_ACCESS_TOKEN.trim()
            } catch (_: Exception) {
                ""
            }

        // 3. Path A: Direct Gmail REST API delivery if OAuth token is available
        if (gmailToken.isNotBlank() && isConfiguredSecret(gmailToken, "YOUR_GMAIL_ACCESS_TOKEN")) {
            val directGmailResult = sendViaGmailApi(
                accessToken = gmailToken,
                senderEmail = effectiveSender,
                recipientEmail = recipientEmail,
                replyToEmail = senderEmail,
                subject = payload.subjectLine,
                plainText = payload.bodyText,
                htmlBody = htmlBody,
                audioClip = payload.audioClip
            )
            if (directGmailResult is EmailDispatchResult.Sent) {
                sentReceiptsByAlertId[payload.alertId] = directGmailResult
                return@withContext directGmailResult
            }
        }

        // 4. Path B: Authenticated Firebase Cloud Function (`sendSoloSignalEmergencyEmail`)
        val backendUrl = try {
            BuildConfig.SOLOSIGNAL_EMAIL_BACKEND_URL.trim()
        } catch (_: Exception) {
            ""
        }

        var cloudFunctionError: String? = null
        val idToken = fetchFirebaseIdToken()
        if (backendUrl.startsWith("https://") && !idToken.isNullOrBlank()) {
            val cfResult = trySendViaCloudFunction(
                backendUrl = backendUrl,
                idToken = idToken,
                gmailAccessToken = gmailToken.takeIf { isConfiguredSecret(it, "YOUR_GMAIL_ACCESS_TOKEN") },
                payload = payload,
                recipientEmail = recipientEmail,
                triggerReason = triggerReason
            )
            when (cfResult) {
                is EmailDispatchResult.Sent -> {
                    sentReceiptsByAlertId[payload.alertId] = cfResult
                    return@withContext cfResult
                }
                is EmailDispatchResult.Failure -> {
                    cloudFunctionError = cfResult.reason
                }
            }
        }

        // 5. Honest failure: Gmail API OAuth authorization or backend deployment required
        val failureReason = buildString {
            append("Gmail API sending authorization required. ")
            if (!cloudFunctionError.isNullOrBlank()) {
                append("Backend status: $cloudFunctionError. ")
            }
            append("Please authorize your Google account in Settings -> Trusted Contacts to send emergency alert emails (scope: https://www.googleapis.com/auth/gmail.send).")
        }

        EmailDispatchResult.Failure(
            reason = failureReason,
            providerName = "Gmail API",
            httpCode = 401,
            requiresAuthorization = true
        )
    }

    /**
     * Sends an email directly via the official Gmail REST API (`POST https://gmail.googleapis.com/gmail/v1/users/me/messages/send`)
     * using the RFC 2822 base64url formatted message.
     */
    internal fun sendViaGmailApi(
        accessToken: String,
        senderEmail: String,
        recipientEmail: String,
        replyToEmail: String,
        subject: String,
        plainText: String,
        htmlBody: String,
        audioClip: com.example.domain.SosAudioClipMetadata? = null
    ): EmailDispatchResult {
        return try {
            val rawMessage = buildRfc2822Message(
                senderEmail = senderEmail,
                recipientEmail = recipientEmail,
                replyToEmail = replyToEmail,
                subject = subject,
                plainText = plainText,
                htmlBody = htmlBody,
                audioClip = audioClip
            )
            val base64UrlRaw = Base64.getUrlEncoder().withoutPadding().encodeToString(
                rawMessage.toByteArray(StandardCharsets.UTF_8)
            )

            val jsonBody = JSONObject().apply {
                put("raw", base64UrlRaw)
            }

            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    null
                }

                if (response.isSuccessful && json != null) {
                    val messageId = json.optString("id").trim()
                    if (messageId.isNotBlank()) {
                        return EmailDispatchResult.Sent(
                            providerName = "Gmail API",
                            messageId = messageId,
                            recipientEmail = recipientEmail,
                            detail = "Emergency email sent to $recipientEmail via Gmail API (ID: $messageId)."
                        )
                    }
                }

                val errDetail = json?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Gmail API returned HTTP ${response.code}: ${rawBody.take(160)}"

                EmailDispatchResult.Failure(
                    reason = "Gmail API sending error: $errDetail",
                    providerName = "Gmail API",
                    httpCode = response.code,
                    requiresAuthorization = response.code == 401 || response.code == 403
                )
            }
        } catch (e: Exception) {
            EmailDispatchResult.Failure(
                reason = "Gmail API network error (${e.javaClass.simpleName}: ${e.localizedMessage ?: "connection failed"})",
                providerName = "Gmail API"
            )
        }
    }

    private fun trySendViaCloudFunction(
        backendUrl: String,
        idToken: String,
        gmailAccessToken: String?,
        payload: AlertMessagePayload,
        recipientEmail: String,
        triggerReason: String
    ): EmailDispatchResult {
        return try {
            val bodyJson = JSONObject().apply {
                put("alertId", payload.alertId)
                put("isTestAlert", payload.isTestAlert)
                put("senderName", payload.userDisplayName)
                put("recipientName", payload.recipientName)
                put("recipientEmail", recipientEmail)
                put("subjectLine", payload.subjectLine)
                put("bodyText", payload.bodyText)
                put("triggerReason", triggerReason)
                put("timestampFormatted", payload.timestampFormatted)
                put("locationSummary", payload.locationStatusNote)
                put("audioStatus", payload.audioStatusKey)
                put("audioStatusNote", payload.audioStatusNote)
                if (!gmailAccessToken.isNullOrBlank()) {
                    put("gmailAccessToken", gmailAccessToken)
                }
                if (payload.locationSnapshot != null) {
                    put("googleMapsUrl", payload.locationSnapshot.googleMapsUrl)
                    put("latitude", payload.locationSnapshot.latitude)
                    put("longitude", payload.locationSnapshot.longitude)
                }
                val clip = payload.audioClip
                if (clip != null) {
                    put("audioDurationSeconds", clip.durationSeconds)
                    put("audioMimeType", clip.mimeType)
                    put("audioFileName", "sos_recording.${clip.containerExtension}")
                    if (!clip.storageObjectPath.isNullOrBlank()) {
                        put("storageObjectPath", clip.storageObjectPath)
                    }
                    if (!clip.downloadUrl.isNullOrBlank()) {
                        put("audioDownloadUrl", clip.downloadUrl)
                    }
                    if (!clip.base64Payload.isNullOrBlank() && clip.sizeBytes <= 4 * 1024 * 1024L) {
                        put("audioAttachmentBase64", clip.base64Payload)
                    }
                }
            }

            val requestBuilder = Request.Builder()
                .url(backendUrl)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")

            if (!gmailAccessToken.isNullOrBlank()) {
                requestBuilder.addHeader("X-Gmail-Access-Token", gmailAccessToken)
            }

            val request = requestBuilder
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    null
                }

                if (response.isSuccessful && json != null && json.optBoolean("emailSent", false)) {
                    val msgId = json.optString("messageId").trim()
                    val provider = json.optString("providerName", "Gmail API").trim()
                    if (msgId.isNotBlank()) {
                        return EmailDispatchResult.Sent(
                            providerName = provider,
                            messageId = msgId,
                            recipientEmail = recipientEmail,
                            detail = json.optString(
                                "detail",
                                "Email sent to $recipientEmail via $provider (ID: $msgId)."
                            )
                        )
                    }
                }

                val errMsg = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Cloud Function returned HTTP ${response.code}"
                EmailDispatchResult.Failure(
                    reason = errMsg,
                    providerName = "Gmail API",
                    httpCode = response.code
                )
            }
        } catch (e: Exception) {
            EmailDispatchResult.Failure(
                reason = "Cloud Function unreachable (${e.javaClass.simpleName})",
                providerName = "Gmail API"
            )
        }
    }

    internal fun buildRfc2822Message(
        senderEmail: String,
        recipientEmail: String,
        replyToEmail: String,
        subject: String,
        plainText: String,
        htmlBody: String,
        audioClip: com.example.domain.SosAudioClipMetadata? = null
    ): String {
        val mixedBoundary = "__solosignal_mixed_${System.currentTimeMillis()}__"
        val altBoundary = "__solosignal_boundary_${System.currentTimeMillis()}__"
        val encodedSubject = "=?utf-8?B?" + Base64.getEncoder().encodeToString(
            subject.toByteArray(StandardCharsets.UTF_8)
        ) + "?="

        val encodedPlainText = Base64.getEncoder().encodeToString(
            plainText.toByteArray(StandardCharsets.UTF_8)
        )
        val encodedHtml = Base64.getEncoder().encodeToString(
            htmlBody.toByteArray(StandardCharsets.UTF_8)
        )

        val hasAudioAttachment = audioClip != null &&
            !audioClip.base64Payload.isNullOrBlank() &&
            audioClip.sizeBytes <= 4 * 1024 * 1024L

        return buildString {
            append("From: ").append(senderEmail).append("\r\n")
            append("To: ").append(recipientEmail).append("\r\n")
            if (TrustedContact.isValidEmail(replyToEmail)) {
                append("Reply-To: ").append(replyToEmail).append("\r\n")
            }
            append("Subject: ").append(encodedSubject).append("\r\n")
            append("MIME-Version: 1.0\r\n")

            if (hasAudioAttachment && audioClip != null) {
                val fileName = "sos_recording.${audioClip.containerExtension}"
                append("Content-Type: multipart/mixed; boundary=\"").append(mixedBoundary).append("\"\r\n\r\n")

                append("--").append(mixedBoundary).append("\r\n")
                append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n")
            } else {
                append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n")
            }

            append("--").append(altBoundary).append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(encodedPlainText).append("\r\n\r\n")

            append("--").append(altBoundary).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n\r\n")
            append(encodedHtml).append("\r\n\r\n")

            append("--").append(altBoundary).append("--\r\n")

            if (hasAudioAttachment && audioClip != null) {
                val fileName = "sos_recording.${audioClip.containerExtension}"
                append("\r\n--").append(mixedBoundary).append("\r\n")
                append("Content-Type: ").append(audioClip.mimeType).append("; name=\"").append(fileName).append("\"\r\n")
                append("Content-Disposition: attachment; filename=\"").append(fileName).append("\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n\r\n")
                append(audioClip.base64Payload).append("\r\n\r\n")
                append("--").append(mixedBoundary).append("--\r\n")
            }
        }
    }

    internal fun buildHtmlEmailBody(
        payload: AlertMessagePayload,
        recipientEmail: String,
        senderEmail: String,
        triggerReason: String
    ): String {
        val badgeColor = if (payload.isTestAlert) "#0EA5E9" else "#DC2626"
        val badgeText = if (payload.isTestAlert) {
            "TEST ALERT — NOT AN EMERGENCY"
        } else {
            "SOS ALERT — POSSIBLE FALL DETECTED"
        }
        val mapsUrl = payload.locationSnapshot?.googleMapsUrl
        val mapsButton = if (!mapsUrl.isNullOrBlank()) {
            """
            <p style="margin: 16px 0;">
              <a href="$mapsUrl" style="background-color: #141418; color: #BAFFD8; padding: 12px 20px; border-radius: 999px; text-decoration: none; font-weight: 700; display: inline-block;">
                Open GPS Location in Google Maps
              </a>
            </p>
            """.trimIndent()
        } else {
            ""
        }

        val audioUrl = payload.audioClip?.downloadUrl
        val audioButton = if (!audioUrl.isNullOrBlank()) {
            """
            <p style="margin: 12px 0;">
              <a href="$audioUrl" style="background-color: #DC2626; color: #FFFFFF; padding: 12px 20px; border-radius: 999px; text-decoration: none; font-weight: 700; display: inline-block;">
                Listen to 15s SOS Audio Recording
              </a>
            </p>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 580px; margin: 0 auto; padding: 24px; background-color: #F6F4FA; border-radius: 20px; color: #141418;">
              <div style="background-color: $badgeColor; color: #FFFFFF; display: inline-block; padding: 6px 14px; border-radius: 999px; font-size: 12px; font-weight: 700;">
                $badgeText
              </div>
              <h2 style="margin: 16px 0 8px 0; font-size: 20px; color: #141418;">${payload.subjectLine}</h2>
              <p style="font-size: 15px; color: #2B2B33;">
                Hello <strong>${payload.recipientName}</strong> ($recipientEmail),
              </p>
              <div style="background-color: #FFFFFF; border-radius: 16px; padding: 16px; margin: 14px 0; border: 1px solid #E2DFEC;">
                <p style="margin: 6px 0;"><strong>Person monitored:</strong> ${payload.userDisplayName} ${if (senderEmail.isNotBlank()) "($senderEmail)" else ""}</p>
                <p style="margin: 6px 0;"><strong>Trigger reason:</strong> $triggerReason</p>
                <p style="margin: 6px 0;"><strong>Alert time:</strong> ${payload.timestampFormatted}</p>
                <p style="margin: 6px 0;"><strong>Location:</strong> ${payload.locationStatusNote}</p>
                <p style="margin: 6px 0;"><strong>Audio recording:</strong> ${payload.audioStatusNote}</p>
                $mapsButton
                $audioButton
              </div>
              <pre style="white-space: pre-wrap; font-family: inherit; font-size: 14px; color: #2B2B33; background: #FFFFFF; padding: 14px; border-radius: 12px; border: 1px solid #E2DFEC;">${payload.bodyText}</pre>
              <p style="font-size: 12px; color: #646474; margin-top: 14px;">
                This is an automated alert generated by a fall-detection prototype. The detection may be incorrect, and the alert does not establish that an injury has occurred.
              </p>
            </div>
        """.trimIndent()
    }

    private fun isConfiguredSecret(value: String, placeholder: String): Boolean {
        return value.isNotBlank() &&
            value != placeholder &&
            !value.startsWith("YOUR_") &&
            !value.startsWith("MY_") &&
            value != "null"
    }

    private suspend fun fetchFirebaseIdToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            val currentUser = Firebase.auth.currentUser
            if (currentUser == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            currentUser.getIdToken(false)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.token)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        } catch (_: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    companion object {
        private fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

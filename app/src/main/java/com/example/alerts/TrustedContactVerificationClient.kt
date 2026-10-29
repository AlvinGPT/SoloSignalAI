package com.example.alerts

import com.example.BuildConfig
import com.example.data.AlertFirestoreRepository
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.domain.TrustedContactVerificationStatus
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
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
 * Privacy-preserving backend verification and contact-request client for SoloSignal AI.
 *
 * Security & Account Privacy Guarantees:
 * 1. Never queries a public directory of users from the client.
 * 2. Calls the authenticated Firebase Cloud Function (`verifyOrRequestTrustedContact`) using
 *    the sender's Firebase Authentication ID token (`Authorization: Bearer <idToken>`).
 * 3. If the Cloud Function endpoint is reachable, uses the server-side Firebase Admin Auth lookup
 *    and device readiness inspection to determine whether the recipient is registered and ready.
 * 4. If the Cloud Function is not deployed yet, uses the privacy-preserving invitation-and-acceptance
 *    link workflow in `/trusted_contact_links/{linkId}` without falsely claiming account existence
 *    has been verified until the recipient signs in and accepts on their device.
 */
class TrustedContactVerificationClient(
    private val firestoreRepository: AlertFirestoreRepository,
    private val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {

    suspend fun verifyOrRequestContact(
        contact: TrustedContact,
        sendPermissionRequest: Boolean = false,
        markInvitationSent: Boolean = false,
        localDeviceCount: Int = 0,
        localNotificationsEnabled: Boolean = false
    ): Result<TrustedContactRequestItem> = withContext(Dispatchers.IO) {
        val recipientEmail = contact.contactEmail.trim().lowercase()
        if (!TrustedContact.isValidEmail(recipientEmail)) {
            return@withContext Result.failure(
                IllegalArgumentException("Enter a valid email address to verify or invite a trusted contact.")
            )
        }

        // Optional Cloud Function lookup for server-side Firebase Admin Auth inspection
        val idToken = fetchFirebaseIdToken()
        val verifyEndpoint = resolveVerifyEndpointUrl()
        val remoteResult = if (!idToken.isNullOrBlank() && verifyEndpoint.startsWith("https://")) {
            callCloudVerifyEndpoint(
                endpointUrl = verifyEndpoint,
                idToken = idToken,
                contact = contact,
                sendPermissionRequest = sendPermissionRequest,
                markInvitationSent = markInvitationSent
            )
        } else {
            null
        }

        // Merge any positive account/device discovery from remoteResult into the contact before Firestore link sync
        val enrichedContact = if (remoteResult != null) {
            contact.copy(
                recipientHasRegisteredAccount = contact.recipientHasRegisteredAccount ||
                    remoteResult.recipientHasRegisteredAccount,
                recipientAccepted = contact.recipientAccepted || remoteResult.recipientAccepted,
                recipientRegisteredDeviceCount = maxOf(
                    contact.recipientRegisteredDeviceCount,
                    remoteResult.recipientRegisteredDeviceCount
                ),
                recipientNotificationsEnabled = contact.recipientNotificationsEnabled ||
                    remoteResult.recipientNotificationsEnabled,
                recipientUid = contact.recipientUid.ifBlank { remoteResult.recipientUid.orEmpty() }
            )
        } else {
            contact
        }

        // Authoritative Firestore link & registration lookup in the app's database
        val firestoreResult = firestoreRepository.createOrUpdateTrustedContactLink(
            contact = enrichedContact,
            sendPermissionRequest = sendPermissionRequest,
            markInvitationSent = markInvitationSent,
            localDeviceCount = localDeviceCount,
            localNotificationsEnabled = localNotificationsEnabled
        )

        if (firestoreResult.isSuccess) {
            return@withContext firestoreResult
        }

        // If Firestore write failed but remoteResult succeeded, return remoteResult; otherwise propagate failure
        if (remoteResult != null) {
            Result.success(remoteResult)
        } else {
            firestoreResult
        }
    }

    private fun resolveVerifyEndpointUrl(): String {
        val baseEmailUrl = try {
            BuildConfig.SOLOSIGNAL_EMAIL_BACKEND_URL.trim()
        } catch (_: Exception) {
            ""
        }
        if (baseEmailUrl.startsWith("https://") && baseEmailUrl.contains("sendSoloSignalEmergencyEmail")) {
            return baseEmailUrl.replace(
                "sendSoloSignalEmergencyEmail",
                "verifyOrRequestTrustedContact"
            )
        }
        return "https://us-central1-gen-lang-client-0124462176.cloudfunctions.net/verifyOrRequestTrustedContact"
    }

    private fun callCloudVerifyEndpoint(
        endpointUrl: String,
        idToken: String,
        contact: TrustedContact,
        sendPermissionRequest: Boolean,
        markInvitationSent: Boolean
    ): TrustedContactRequestItem? {
        return try {
            val currentUser = Firebase.auth.currentUser ?: return null
            val senderUid = currentUser.uid
            val senderEmail = currentUser.email?.trim()?.lowercase().orEmpty()
            val senderName = contact.userDisplayName.trim().ifBlank {
                currentUser.displayName?.trim().orEmpty().ifBlank { "SoloSignal User" }
            }

            val bodyJson = JSONObject().apply {
                put("recipientEmail", contact.contactEmail.trim().lowercase())
                put("recipientName", contact.contactName.trim().ifBlank { "Trusted Contact" })
                put("recipientPhone", contact.contactPhone.trim())
                put("relationship", contact.relationship.trim().ifBlank { "Family" })
                put("senderName", senderName)
                put("sendPermissionRequest", sendPermissionRequest)
                put("markInvitationSent", markInvitationSent)
                put("shareLocation", contact.optionalLocationEnabled)
                put("popUpAlertsEnabled", contact.phonePopUpAlertsEnabled)
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val rawBody = response.body?.string().orEmpty()
                val json = JSONObject(rawBody)
                if (!json.optBoolean("ok", false)) return null

                val statusStr = json.optString("status", "INVITATION_NEEDED")
                val statusEnum = TrustedContactVerificationStatus.fromStorageKey(statusStr)
                val statusDetail = json.optString("statusDetail", statusEnum.defaultDetail)
                val linkId = json.optString(
                    "linkId",
                    firestoreRepository.buildLinkId(senderUid, contact.contactEmail)
                )

                TrustedContactRequestItem(
                    linkId = linkId,
                    senderUid = senderUid,
                    senderName = senderName,
                    senderEmail = senderEmail,
                    recipientName = contact.contactName.trim().ifBlank { "Trusted Contact" },
                    recipientEmail = contact.contactEmail.trim().lowercase(),
                    recipientPhone = contact.contactPhone.trim(),
                    relationship = contact.relationship.trim().ifBlank { "Family" },
                    status = statusEnum,
                    statusDetail = statusDetail,
                    recipientHasRegisteredAccount = json.optBoolean("recipientHasRegisteredAccount", false),
                    recipientAccepted = json.optBoolean("recipientAccepted", false),
                    recipientRegisteredDeviceCount = json.optInt("recipientRegisteredDeviceCount", 0),
                    recipientNotificationsEnabled = json.optBoolean("recipientNotificationsEnabled", false)
                )
            }
        } catch (_: Exception) {
            null
        }
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
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}

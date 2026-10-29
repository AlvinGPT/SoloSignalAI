package com.example.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.R
import com.example.alerts.AlertMessagePayload
import com.example.domain.IncomingRecipientAlert
import com.example.domain.RecipientDeviceInfo
import com.example.domain.TrustedContact
import com.example.domain.TrustedContactRequestItem
import com.example.domain.TrustedContactVerificationStatus
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(
    exception: Exception,
    operationType: OperationType,
    path: String?
): String {
    val auth = try {
        FirebaseAuth.getInstance()
    } catch (_: Exception) {
        null
    }
    val currentUser = auth?.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class FirestoreUserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val trustedContactName: String? = null,
    val trustedContactEmail: String? = null,
    val trustedContactPhone: String? = null,
    val trustedContactRelationship: String? = null,
    val trustedContactVerificationStatus: String? = null,
    val preferredChannel: String? = null,
    val shareLocation: Boolean? = true,
    val popUpAlertsEnabled: Boolean? = true,
    val sosAudioEnabled: Boolean? = false,
    val sosAudioConsentGranted: Boolean? = false,
    val sosAudioRetentionDays: Int? = 7,
    val confirmationTimeoutSeconds: Int? = 20,
    val fcmDeviceCount: Int? = 0,
    val notificationsEnabled: Boolean? = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreRegisteredContact(
    @DocumentId val id: String = "",
    val emailKey: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val hasRegisteredAccount: Boolean = true,
    val activeDeviceCount: Int = 0,
    val notificationsEnabled: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FirestoreDeviceRegistration(
    @DocumentId val id: String = "",
    val deviceId: String = "",
    val userId: String = "",
    val fcmToken: String = "",
    val deviceName: String = "",
    val notificationsEnabled: Boolean = false,
    val appVersion: String? = null,
    val sdkInt: Int? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): RecipientDeviceInfo {
        val epoch = (updatedAt ?: createdAt)?.toDate()?.time ?: System.currentTimeMillis()
        return RecipientDeviceInfo(
            deviceId = deviceId.ifBlank { id },
            deviceName = deviceName.ifBlank { "Android Device" },
            notificationsEnabled = notificationsEnabled,
            updatedAtMillis = epoch
        )
    }
}

@IgnoreExtraProperties
data class FirestoreTrustedContactLink(
    @DocumentId val id: String = "",
    val linkId: String? = null,
    val senderUid: String = "",
    val senderName: String = "",
    val senderEmail: String = "",
    val recipientName: String = "",
    val recipientEmail: String = "",
    val recipientPhone: String? = null,
    val relationship: String = "Family",
    val recipientUid: String? = null,
    val status: String = "INVITATION_NEEDED",
    val statusDetail: String = "",
    val recipientHasRegisteredAccount: Boolean = false,
    val recipientAccepted: Boolean = false,
    val recipientRegisteredDeviceCount: Int = 0,
    val recipientNotificationsEnabled: Boolean = false,
    val shareLocation: Boolean? = true,
    val popUpAlertsEnabled: Boolean? = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toDomain(): TrustedContactRequestItem {
        val statusEnum = TrustedContactVerificationStatus.fromStorageKey(status)
        val epoch = (updatedAt ?: createdAt)?.toDate()?.time ?: System.currentTimeMillis()
        return TrustedContactRequestItem(
            linkId = id.ifBlank { linkId.orEmpty() },
            senderUid = senderUid,
            senderName = senderName,
            senderEmail = senderEmail,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
            recipientPhone = recipientPhone.orEmpty(),
            relationship = relationship.ifBlank { "Family" },
            recipientUid = recipientUid,
            status = statusEnum,
            statusDetail = statusDetail.ifBlank { statusEnum.defaultDetail },
            recipientHasRegisteredAccount = recipientHasRegisteredAccount,
            recipientAccepted = recipientAccepted,
            recipientRegisteredDeviceCount = recipientRegisteredDeviceCount,
            recipientNotificationsEnabled = recipientNotificationsEnabled,
            updatedAtMillis = epoch
        )
    }
}

@IgnoreExtraProperties
data class FirestoreEmergencyAlert(
    @DocumentId val id: String = "",
    val alertId: String? = null,
    val senderUid: String = "",
    val senderName: String = "",
    val senderEmail: String = "",
    val recipientName: String = "",
    val recipientEmail: String = "",
    val recipientPhone: String? = null,
    val recipientUid: String? = null,
    val linkId: String? = null,
    val isTestAlert: Boolean = false,
    val subjectLine: String = "",
    val bodyText: String = "",
    val triggerReason: String = "",
    val deliveryState: String = "REQUEST_ACCEPTED",
    val deliveryStatusDetail: String = "",
    val providerMessageId: String? = null,
    val fcmDeliveryStatus: String? = null,
    val fcmDeliveredCount: Int? = null,
    val emailDeliveryStatus: String? = null,
    val emailProvider: String? = null,
    val emailProviderMessageId: String? = null,
    val emailError: String? = null,
    val channelUsed: String = "SOLOSIGNAL_POPUP",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val googleMapsUrl: String? = null,
    val escalationStatus: String? = "NONE",
    val escalationDeadlineMillis: Long? = null,
    val meetUrl: String? = null,
    val meetSpaceName: String? = null,
    val meetError: String? = null,
    val triggeredAt: Timestamp? = null,
    val audioStatus: String? = null,
    val audioDurationSeconds: Double? = null,
    val storageObjectPath: String? = null,
    val audioDownloadUrl: String? = null,
    val emailStatus: String? = null,
    val emailSentAt: Timestamp? = null,
    val failureReason: String? = null,
    val locationSummary: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toIncomingRecipientAlert(): IncomingRecipientAlert {
        val epoch = (createdAt ?: updatedAt)?.toDate()?.time ?: System.currentTimeMillis()
        return IncomingRecipientAlert(
            alertId = id.ifBlank { alertId.orEmpty() },
            senderName = senderName,
            senderEmail = senderEmail,
            recipientName = recipientName,
            recipientEmail = recipientEmail,
            isTestAlert = isTestAlert,
            subjectLine = subjectLine,
            bodyText = bodyText,
            triggerReason = triggerReason,
            deliveryState = deliveryState,
            deliveryStatusDetail = deliveryStatusDetail,
            googleMapsUrl = googleMapsUrl,
            locationSummary = locationSummary,
            createdAtMillis = epoch,
            latitude = latitude,
            longitude = longitude,
            escalationStatus = escalationStatus?.ifBlank { "NONE" } ?: "NONE",
            escalationDeadlineMillis = escalationDeadlineMillis,
            meetUrl = meetUrl?.takeIf { it.isNotBlank() },
            meetError = meetError?.takeIf { it.isNotBlank() }
        )
    }
}

class AlertFirestoreRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    /**
     * Deterministic, valid Firestore document ID for a sender -> recipient email relationship.
     */
    fun buildLinkId(senderUid: String, recipientEmail: String): String {
        val cleanEmail = recipientEmail.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
        val safeUid = senderUid.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return "link_${safeUid}_$cleanEmail".take(120)
    }

    /**
     * Deterministic, valid Firestore document ID for exact-email registration lookup.
     */
    fun buildEmailKey(email: String): String {
        val cleanEmail = email.trim().lowercase().replace(Regex("[^a-z0-9]"), "_")
        return "reg_$cleanEmail".take(120)
    }

    /**
     * Registers or refreshes a recipient device's FCM token in the private `/users/{uid}/devices/{deviceId}`
     * subcollection, cleans up stale tokens (>60 days old), updates `/registered_contacts/{emailKey}`,
     * and returns the total active device count.
     */
    suspend fun registerOrUpdateRecipientDevice(
        deviceId: String,
        fcmToken: String,
        notificationsEnabled: Boolean,
        appVersion: String = "1.0"
    ): Result<Pair<Int, Boolean>> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val safeDeviceId = deviceId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120).ifBlank {
            "dev_default"
        }
        val safeToken = fcmToken.trim().take(4096)
        if (safeToken.length < 10) {
            return Result.failure(IllegalArgumentException("Invalid FCM token length"))
        }

        val deviceName = "${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}"
            .trim()
            .ifBlank { "Android Device" }
            .take(120)

        val devicesCol = db.collection(COLLECTION_USERS).document(uid).collection(SUBCOLLECTION_DEVICES)
        val deviceRef = devicesCol.document(safeDeviceId)

        return try {
            val existing = deviceRef.get().await()
            if (!existing.exists()) {
                val createPayload = mapOf(
                    "deviceId" to safeDeviceId,
                    "userId" to uid,
                    "fcmToken" to safeToken,
                    "deviceName" to deviceName,
                    "notificationsEnabled" to notificationsEnabled,
                    "appVersion" to appVersion.take(40),
                    "sdkInt" to Build.VERSION.SDK_INT.coerceIn(21, 60),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                deviceRef.set(createPayload).await()
            } else {
                val updatePayload = mapOf(
                    "fcmToken" to safeToken,
                    "deviceName" to deviceName,
                    "notificationsEnabled" to notificationsEnabled,
                    "appVersion" to appVersion.take(40),
                    "sdkInt" to Build.VERSION.SDK_INT.coerceIn(21, 60),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                deviceRef.update(updatePayload).await()
            }

            // Query all devices for this user, remove stale tokens (>60 days old), and compute summary
            val allDevicesSnap = devicesCol.get().await()
            val staleCutoffMillis = System.currentTimeMillis() - STALE_DEVICE_TTL_MILLIS
            var activeCount = 0
            var anyNotificationsEnabled = false

            for (doc in allDevicesSnap.documents) {
                val updatedTs = doc.getTimestamp("updatedAt")?.toDate()?.time ?: System.currentTimeMillis()
                if (doc.id != safeDeviceId && updatedTs < staleCutoffMillis) {
                    try {
                        doc.reference.delete().await()
                    } catch (_: Exception) {
                    }
                } else {
                    activeCount += 1
                    if (doc.getBoolean("notificationsEnabled") == true) {
                        anyNotificationsEnabled = true
                    }
                }
            }

            // Update owner's UserProfile summary (without exposing raw tokens)
            val userDocRef = db.collection(COLLECTION_USERS).document(uid)
            val userSnap = userDocRef.get().await()
            val user = auth.currentUser
            val userEmail = user?.email?.trim()?.lowercase()?.ifBlank { "user@solosignal.app" } ?: "user@solosignal.app"
            val userDisplay = user?.displayName?.trim()?.ifBlank { "SoloSignal User" } ?: "SoloSignal User"

            if (!userSnap.exists()) {
                userDocRef.set(
                    mapOf(
                        "userId" to uid,
                        "email" to userEmail.take(254),
                        "displayName" to userDisplay.take(120),
                        "fcmDeviceCount" to activeCount.coerceIn(0, 50),
                        "notificationsEnabled" to anyNotificationsEnabled,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                userDocRef.update(
                    mapOf(
                        "fcmDeviceCount" to activeCount.coerceIn(0, 50),
                        "notificationsEnabled" to anyNotificationsEnabled,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            // Also publish exact-email registration record so contacts who add this email see that this account is registered & signed in
            publishCurrentUserRegistration(
                activeDeviceCount = activeCount.coerceAtLeast(1),
                notificationsEnabled = anyNotificationsEnabled
            )

            Result.success(activeCount to anyNotificationsEnabled)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Publishes the signed-in user's verified email and stable Firebase Auth UID to
     * `/registered_contacts/{emailKey}` so other users who enter this email can detect that
     * the account already exists on SoloSignal AI without exposing a public user list.
     */
    suspend fun publishCurrentUserRegistration(
        activeDeviceCount: Int = 1,
        notificationsEnabled: Boolean = true
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("Not signed in"))
        val rawEmail = user.email?.trim()?.lowercase().orEmpty()
        if (rawEmail.isBlank() || !rawEmail.contains("@")) {
            return Result.success(Unit)
        }
        val userDisplay = user.displayName?.trim()?.ifBlank { "SoloSignal User" } ?: "SoloSignal User"
        val emailKey = buildEmailKey(rawEmail)
        val regRef = db.collection(COLLECTION_REGISTERED_CONTACTS).document(emailKey)
        return try {
            regRef.set(
                mapOf(
                    "emailKey" to emailKey,
                    "userId" to uid,
                    "email" to rawEmail.take(254),
                    "displayName" to userDisplay.take(120),
                    "hasRegisteredAccount" to true,
                    "activeDeviceCount" to activeDeviceCount.coerceIn(0, 50),
                    "notificationsEnabled" to notificationsEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, regRef.path)
            Result.failure(e)
        }
    }

    /**
     * Removes a device registration on sign-out or token invalidation, and updates the
     * user's active device count in `/registered_contacts/{emailKey}`.
     */
    suspend fun unregisterRecipientDevice(deviceId: String): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val safeDeviceId = deviceId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120)
        if (safeDeviceId.isBlank()) return Result.success(Unit)
        val devicesCol = db.collection(COLLECTION_USERS)
            .document(uid)
            .collection(SUBCOLLECTION_DEVICES)
        val deviceRef = devicesCol.document(safeDeviceId)
        return try {
            deviceRef.delete().await()
            val remainingSnap = devicesCol.get().await()
            var remainingCount = 0
            var anyNotif = false
            for (doc in remainingSnap.documents) {
                remainingCount += 1
                if (doc.getBoolean("notificationsEnabled") == true) {
                    anyNotif = true
                }
            }
            publishCurrentUserRegistration(
                activeDeviceCount = remainingCount,
                notificationsEnabled = anyNotif
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Synchronizes all incoming trusted-contact links addressed to this authenticated recipient's email
     * whenever the recipient signs in, accepts a request, or updates their FCM/notification status.
     */
    suspend fun syncIncomingLinksForRecipient(
        recipientEmail: String,
        activeDeviceCount: Int,
        notificationsEnabled: Boolean
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val normalizedEmail = recipientEmail.trim().lowercase()
        if (normalizedEmail.isBlank()) return Result.success(Unit)

        // Ensure the user's registration record is always up to date when syncing incoming links
        publishCurrentUserRegistration(
            activeDeviceCount = activeDeviceCount.coerceAtLeast(1),
            notificationsEnabled = notificationsEnabled
        )

        return try {
            val snap = db.collection(COLLECTION_CONTACT_LINKS)
                .whereEqualTo("recipientEmail", normalizedEmail)
                .get()
                .await()

            for (doc in snap.documents) {
                val link = doc.toObject(FirestoreTrustedContactLink::class.java) ?: continue
                val isSelfLink = link.senderUid == uid
                val accepted = link.recipientAccepted || isSelfLink

                val newStatus: TrustedContactVerificationStatus
                val newDetail: String

                if (accepted) {
                    when {
                        activeDeviceCount <= 0 -> {
                            newStatus = TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED
                            newDetail = "Account linked, but their phone is currently offline."
                        }
                        !notificationsEnabled -> {
                            newStatus = TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED
                            newDetail = "Account linked, but notifications are disabled on their device."
                        }
                        else -> {
                            newStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS
                            newDetail = "Connected to ${link.recipientName} ($normalizedEmail) on $activeDeviceCount active device(s)."
                        }
                    }
                } else {
                    newStatus = if (link.status == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING.name ||
                        link.status == TrustedContactVerificationStatus.INVITATION_PENDING.name
                    ) {
                        TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING
                    } else {
                        TrustedContactVerificationStatus.REGISTERED
                    }
                    newDetail = "${link.recipientName} ($normalizedEmail) has an active SoloSignal account. Waiting for link acceptance."
                }

                doc.reference.update(
                    mapOf(
                        "recipientUid" to uid,
                        "recipientHasRegisteredAccount" to true,
                        "recipientAccepted" to accepted,
                        "recipientRegisteredDeviceCount" to activeDeviceCount.coerceIn(0, 50),
                        "recipientNotificationsEnabled" to notificationsEnabled,
                        "status" to newStatus.name,
                        "statusDetail" to newDetail.take(600),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, COLLECTION_CONTACT_LINKS)
            Result.failure(e)
        }
    }

    /**
     * Fetches an existing outgoing contact link for `(senderUid, recipientEmail)` if present.
     */
    suspend fun getOutgoingContactLink(
        senderUid: String,
        recipientEmail: String
    ): Result<TrustedContactRequestItem?> {
        val normalized = recipientEmail.trim().lowercase()
        if (senderUid.isBlank() || normalized.isBlank()) return Result.success(null)
        val linkId = buildLinkId(senderUid, normalized)
        val docRef = db.collection(COLLECTION_CONTACT_LINKS).document(linkId)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                Result.success(snap.toObject(FirestoreTrustedContactLink::class.java)?.toDomain())
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Creates or updates a privacy-preserving trusted-contact relationship/invitation in `/trusted_contact_links/{linkId}`.
     * Uses stable Firebase Auth `userId` (`uid`) + verified `email` to identify registered accounts across:
     * 1. `/registered_contacts/{emailKey}` (exact-email directory record written on sign-in)
     * 2. Existing `/trusted_contact_links/{linkId}` (outgoing link from current user)
     * 3. Reverse `/trusted_contact_links` where `recipientEmail == senderEmail` and `senderEmail == recipientEmail`
     *    (when the contact on the other phone already added the current user)
     *
     * Accurately distinguishes:
     * - **Connected**: `READY_FOR_ALERTS` / `ACCEPTED` (`recipientAccepted == true` and reachable)
     * - **Offline**: `DEVICE_NOT_REGISTERED` / `NOTIFICATIONS_DISABLED` (`recipientAccepted == true` and unreachable)
     * - **Pending**: `CONTACT_REQUEST_PENDING` / `INVITATION_PENDING` (request/invitation sent and awaiting acceptance)
     * - **Registered**: `REGISTERED` (registered SoloSignal account found; ready to link without asking to install)
     * - **Invite**: `INVITATION_NEEDED` (no existing account or accepted invitation found)
     */
    suspend fun createOrUpdateTrustedContactLink(
        contact: TrustedContact,
        sendPermissionRequest: Boolean = false,
        markInvitationSent: Boolean = false,
        localDeviceCount: Int = 0,
        localNotificationsEnabled: Boolean = false
    ): Result<TrustedContactRequestItem> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val currentUser = auth.currentUser
        val senderEmail = currentUser?.email?.trim()?.lowercase()?.ifBlank { "user@solosignal.app" } ?: "user@solosignal.app"
        val senderName = contact.userDisplayName.trim().ifBlank {
            currentUser?.displayName?.trim()?.ifBlank { "SoloSignal User" } ?: "SoloSignal User"
        }.take(120)

        val recipientEmail = contact.contactEmail.trim().lowercase().take(254)
        val recipientName = contact.contactName.trim().ifBlank { "Trusted Contact" }.take(120)
        val relationship = contact.relationship.trim().ifBlank { "Family" }.take(60)
        val linkId = buildLinkId(uid, recipientEmail)
        val docRef = db.collection(COLLECTION_CONTACT_LINKS).document(linkId)

        return try {
            val existingSnap = docRef.get().await()
            val existing = if (existingSnap.exists()) {
                existingSnap.toObject(FirestoreTrustedContactLink::class.java)
            } else {
                null
            }

            // Source 1: Exact-email registration record in `/registered_contacts/{emailKey}`
            val registeredContact = if (recipientEmail.contains("@")) {
                try {
                    val emailKey = buildEmailKey(recipientEmail)
                    val regSnap = db.collection(COLLECTION_REGISTERED_CONTACTS).document(emailKey).get().await()
                    if (regSnap.exists()) {
                        regSnap.toObject(FirestoreRegisteredContact::class.java)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            // Source 2: Reverse link in `/trusted_contact_links` where the contact on the other phone added this user
            val reverseLink = if (recipientEmail.contains("@") && senderEmail.contains("@")) {
                try {
                    val incomingSnap = db.collection(COLLECTION_CONTACT_LINKS)
                        .whereEqualTo("recipientEmail", senderEmail)
                        .get()
                        .await()
                    incomingSnap.documents
                        .mapNotNull { it.toObject(FirestoreTrustedContactLink::class.java) }
                        .firstOrNull { it.senderEmail.trim().equals(recipientEmail, ignoreCase = true) && it.senderUid.isNotBlank() }
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val isSelfAccount = recipientEmail.isNotBlank() && recipientEmail == senderEmail
            val resolvedRecipientUid = when {
                isSelfAccount -> uid
                !registeredContact?.userId.isNullOrBlank() -> registeredContact?.userId
                !existing?.recipientUid.isNullOrBlank() -> existing?.recipientUid
                !reverseLink?.senderUid.isNullOrBlank() -> reverseLink?.senderUid
                contact.recipientUid.isNotBlank() -> contact.recipientUid
                else -> null
            }

            val hasVerifiedRegisteredIdentity = isSelfAccount ||
                (registeredContact?.hasRegisteredAccount == true && !registeredContact.userId.isBlank()) ||
                (!resolvedRecipientUid.isNullOrBlank()) ||
                (existing?.recipientHasRegisteredAccount == true) ||
                (contact.recipientHasRegisteredAccount && contact.contactEmail.trim().equals(recipientEmail, ignoreCase = true))

            val isAccepted = isSelfAccount ||
                (existing?.recipientAccepted == true) ||
                (contact.recipientAccepted && contact.contactEmail.trim().equals(recipientEmail, ignoreCase = true))

            val deviceCount = when {
                isSelfAccount -> localDeviceCount
                    .coerceAtLeast(existing?.recipientRegisteredDeviceCount ?: 0)
                    .coerceAtLeast(registeredContact?.activeDeviceCount ?: 0)
                    .coerceAtLeast(1)
                registeredContact != null -> maxOf(
                    registeredContact.activeDeviceCount,
                    existing?.recipientRegisteredDeviceCount ?: 0
                )
                reverseLink != null -> maxOf(
                    existing?.recipientRegisteredDeviceCount ?: 0,
                    1
                )
                else -> existing?.recipientRegisteredDeviceCount ?: contact.recipientRegisteredDeviceCount
            }

            val notifEnabled = when {
                isSelfAccount -> localNotificationsEnabled
                registeredContact != null -> registeredContact.notificationsEnabled || (existing?.recipientNotificationsEnabled == true)
                reverseLink != null -> existing?.recipientNotificationsEnabled ?: true
                else -> (existing?.recipientNotificationsEnabled == true) || contact.recipientNotificationsEnabled
            }

            val resolvedStatus: TrustedContactVerificationStatus
            val resolvedDetail: String

            when {
                isAccepted -> {
                    when {
                        deviceCount <= 0 -> {
                            resolvedStatus = TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED
                            resolvedDetail = "Account linked, but $recipientName is currently offline."
                        }
                        !notifEnabled -> {
                            resolvedStatus = TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED
                            resolvedDetail = "Account linked, but notifications are off on $recipientName's device."
                        }
                        else -> {
                            resolvedStatus = TrustedContactVerificationStatus.READY_FOR_ALERTS
                            resolvedDetail = "Connected to $recipientName ($recipientEmail)."
                        }
                    }
                }
                hasVerifiedRegisteredIdentity -> {
                    val alreadyRequested = existing?.status == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING.name ||
                        existing?.status == TrustedContactVerificationStatus.INVITATION_PENDING.name ||
                        contact.verificationStatus == TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING
                    if (sendPermissionRequest || alreadyRequested) {
                        resolvedStatus = TrustedContactVerificationStatus.CONTACT_REQUEST_PENDING
                        resolvedDetail = "Link request sent to $recipientName ($recipientEmail). Waiting for acceptance."
                    } else {
                        resolvedStatus = TrustedContactVerificationStatus.REGISTERED
                        resolvedDetail = "$recipientName ($recipientEmail) already uses SoloSignal. Link their account to connect."
                    }
                }
                markInvitationSent ||
                    existing?.status == TrustedContactVerificationStatus.INVITATION_PENDING.name ||
                    contact.verificationStatus == TrustedContactVerificationStatus.INVITATION_PENDING -> {
                    resolvedStatus = TrustedContactVerificationStatus.INVITATION_PENDING
                    resolvedDetail = "Invitation sent to $recipientEmail. Waiting for $recipientName to sign in and accept."
                }
                else -> {
                    resolvedStatus = TrustedContactVerificationStatus.INVITATION_NEEDED
                    resolvedDetail = TrustedContactVerificationStatus.UNREGISTERED_CONTACT_EXPLANATION
                }
            }

            if (!existingSnap.exists()) {
                val createMap = mutableMapOf<String, Any?>(
                    "linkId" to linkId,
                    "senderUid" to uid,
                    "senderName" to senderName,
                    "senderEmail" to senderEmail,
                    "recipientName" to recipientName,
                    "recipientEmail" to recipientEmail,
                    "recipientPhone" to contact.contactPhone.trim().take(40).ifBlank { null },
                    "relationship" to relationship,
                    "recipientUid" to resolvedRecipientUid,
                    "status" to resolvedStatus.name,
                    "statusDetail" to resolvedDetail.take(600),
                    "recipientHasRegisteredAccount" to hasVerifiedRegisteredIdentity,
                    "recipientAccepted" to isAccepted,
                    "recipientRegisteredDeviceCount" to deviceCount.coerceIn(0, 50),
                    "recipientNotificationsEnabled" to notifEnabled,
                    "shareLocation" to contact.optionalLocationEnabled,
                    "popUpAlertsEnabled" to contact.phonePopUpAlertsEnabled,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                @Suppress("UNCHECKED_CAST")
                docRef.set(createMap.filterValues { it != null } as Map<String, Any>).await()
            } else {
                val updateMap = mutableMapOf<String, Any?>(
                    "senderName" to senderName,
                    "recipientName" to recipientName,
                    "recipientPhone" to contact.contactPhone.trim().take(40).ifBlank { null },
                    "relationship" to relationship,
                    "status" to resolvedStatus.name,
                    "statusDetail" to resolvedDetail.take(600),
                    "recipientHasRegisteredAccount" to hasVerifiedRegisteredIdentity,
                    "recipientAccepted" to isAccepted,
                    "recipientRegisteredDeviceCount" to deviceCount.coerceIn(0, 50),
                    "recipientNotificationsEnabled" to notifEnabled,
                    "shareLocation" to contact.optionalLocationEnabled,
                    "popUpAlertsEnabled" to contact.phonePopUpAlertsEnabled,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!resolvedRecipientUid.isNullOrBlank()) {
                    updateMap["recipientUid"] = resolvedRecipientUid
                }
                @Suppress("UNCHECKED_CAST")
                docRef.update(updateMap.filterValues { it != null } as Map<String, Any>).await()
            }

            Result.success(
                TrustedContactRequestItem(
                    linkId = linkId,
                    senderUid = uid,
                    senderName = senderName,
                    senderEmail = senderEmail,
                    recipientName = recipientName,
                    recipientEmail = recipientEmail,
                    recipientPhone = contact.contactPhone.trim(),
                    relationship = relationship,
                    recipientUid = resolvedRecipientUid,
                    status = resolvedStatus,
                    statusDetail = resolvedDetail,
                    recipientHasRegisteredAccount = hasVerifiedRegisteredIdentity,
                    recipientAccepted = isAccepted,
                    recipientRegisteredDeviceCount = deviceCount,
                    recipientNotificationsEnabled = notifEnabled
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Called by the recipient on their phone when they tap "Accept & Authorize Emergency Alerts".
     */
    suspend fun acceptIncomingContactRequest(
        linkId: String,
        activeDeviceCount: Int,
        notificationsEnabled: Boolean
    ): Result<TrustedContactVerificationStatus> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_CONTACT_LINKS).document(linkId)
        val status = when {
            activeDeviceCount <= 0 -> TrustedContactVerificationStatus.DEVICE_NOT_REGISTERED
            !notificationsEnabled -> TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED
            else -> TrustedContactVerificationStatus.READY_FOR_ALERTS
        }
        val detail = when (status) {
            TrustedContactVerificationStatus.READY_FOR_ALERTS ->
                "Recipient accepted the request and has $activeDeviceCount registered device(s) ready for emergency push alerts."
            TrustedContactVerificationStatus.NOTIFICATIONS_DISABLED ->
                "Recipient accepted the request, but Android notification permission is disabled on their device."
            else ->
                "Recipient accepted the request, but their device is not yet registered for FCM push notifications."
        }

        val updates = mapOf(
            "recipientUid" to uid,
            "recipientHasRegisteredAccount" to true,
            "recipientAccepted" to true,
            "recipientRegisteredDeviceCount" to activeDeviceCount.coerceIn(0, 50),
            "recipientNotificationsEnabled" to notificationsEnabled,
            "status" to status.name,
            "statusDetail" to detail.take(600),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Removes a trusted contact link and revokes associated alert authorization.
     */
    suspend fun revokeAndDeleteTrustedContactLink(linkId: String): Result<Unit> {
        requireUserId()
        if (linkId.isBlank()) return Result.success(Unit)
        val docRef = db.collection(COLLECTION_CONTACT_LINKS).document(linkId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeOutgoingContactLinks(senderUid: String): Flow<List<TrustedContactRequestItem>> {
        return db.collection(COLLECTION_CONTACT_LINKS)
            .whereEqualTo("senderUid", senderUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreTrustedContactLink::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { it.toDomain() }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_CONTACT_LINKS)
                }
                throw error
            }
    }

    fun observeIncomingContactRequests(recipientEmail: String): Flow<List<TrustedContactRequestItem>> {
        val normalized = recipientEmail.trim().lowercase()
        return db.collection(COLLECTION_CONTACT_LINKS)
            .whereEqualTo("recipientEmail", normalized)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreTrustedContactLink::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { it.toDomain() }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_CONTACT_LINKS)
                }
                throw error
            }
    }

    fun observeUserRegisteredDevices(uid: String): Flow<List<RecipientDeviceInfo>> {
        return db.collection(COLLECTION_USERS)
            .document(uid)
            .collection(SUBCOLLECTION_DEVICES)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreDeviceRegistration::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).map { it.toDomain() }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, "$COLLECTION_USERS/$uid/$SUBCOLLECTION_DEVICES")
                }
                throw error
            }
    }

    suspend fun upsertUserProfile(contact: TrustedContact): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val user = auth.currentUser
        val email = user?.email?.trim()?.lowercase()?.ifBlank { "user@solosignal.app" } ?: "user@solosignal.app"
        val displayName = contact.userDisplayName.trim().ifBlank {
            user?.displayName?.trim()?.ifBlank { "SoloSignal User" } ?: "SoloSignal User"
        }.take(120)

        val docRef = db.collection(COLLECTION_USERS).document(uid)
        return try {
            val existingSnap = docRef.get().await()
            val payload = mutableMapOf<String, Any?>(
                "userId" to uid,
                "email" to email.take(254),
                "displayName" to displayName,
                "trustedContactName" to contact.contactName.trim().take(120).ifBlank { null },
                "trustedContactEmail" to contact.contactEmail.trim().lowercase().take(254).ifBlank { null },
                "trustedContactPhone" to contact.contactPhone.trim().take(40).ifBlank { null },
                "trustedContactRelationship" to contact.relationship.trim().take(60).ifBlank { null },
                "trustedContactVerificationStatus" to contact.verificationStatus.name,
                "preferredChannel" to contact.preferredChannel.name,
                "shareLocation" to contact.optionalLocationEnabled,
                "popUpAlertsEnabled" to contact.phonePopUpAlertsEnabled,
                "sosAudioEnabled" to contact.sosAudioEnabled,
                "sosAudioConsentGranted" to contact.sosAudioConsentGranted,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!existingSnap.exists()) {
                payload["createdAt"] = FieldValue.serverTimestamp()
                @Suppress("UNCHECKED_CAST")
                val filtered = payload.filterValues { it != null } as Map<String, Any>
                docRef.set(filtered).await()
            } else {
                payload.remove("userId")
                @Suppress("UNCHECKED_CAST")
                val filtered = payload.filterValues { it != null } as Map<String, Any>
                docRef.set(filtered, SetOptions.merge()).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(
                e,
                OperationType.WRITE,
                "$COLLECTION_USERS/$uid"
            )
            Result.failure(e)
        }
    }

    suspend fun loadUserProfile(): Result<FirestoreUserProfile?> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_USERS).document(uid)
        return try {
            val snap = docRef.get().await()
            if (snap.exists()) {
                Result.success(snap.toObject(FirestoreUserProfile::class.java))
            } else {
                Result.success(null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteUserProfileContact(): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_USERS).document(uid)
        val updates = mapOf<String, Any?>(
            "trustedContactName" to null,
            "trustedContactEmail" to null,
            "trustedContactPhone" to null,
            "trustedContactRelationship" to null,
            "trustedContactVerificationStatus" to null,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createEmergencyAlert(
        alertId: String,
        contact: TrustedContact,
        payload: AlertMessagePayload,
        triggerReason: String,
        channelUsed: String,
        recipientUid: String? = null
    ): Result<FirestoreEmergencyAlert> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val currentUser = auth.currentUser
        val senderEmail = currentUser?.email?.trim()?.lowercase()?.ifBlank { "sender@solosignal.app" }
            ?: "sender@solosignal.app"
        val senderName = payload.userDisplayName.trim().ifBlank {
            currentUser?.displayName?.trim()?.ifBlank { "SoloSignal User" } ?: "SoloSignal User"
        }.take(120)

        val recipientName = payload.recipientName.trim().ifBlank {
            contact.contactName.trim().ifBlank { "Trusted Contact" }
        }.take(120)

        val recipientEmail = contact.contactEmail.trim().lowercase().ifBlank {
            senderEmail
        }.take(254)

        val safeAlertId = alertId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(120).ifBlank {
            "alert_${System.currentTimeMillis()}"
        }

        val providerId = "firestore:$COLLECTION_ALERTS/$safeAlertId"
        val initialDetail = "Emergency request recorded ($providerId). Dispatching push and email to $recipientEmail..."

        val initialEscalationStatus = if (payload.isTestAlert) {
            ESCALATION_NONE
        } else {
            ESCALATION_WAITING_FOR_ACK
        }
        val initialEscalationDeadline = if (payload.isTestAlert) {
            null
        } else {
            System.currentTimeMillis() + EMERGENCY_ESCALATION_TIMEOUT_MS
        }

        val rawMap = mapOf<String, Any?>(
            "alertId" to safeAlertId,
            "senderUid" to uid,
            "senderName" to senderName,
            "senderEmail" to senderEmail.take(254),
            "recipientName" to recipientName,
            "recipientEmail" to recipientEmail,
            "recipientPhone" to contact.contactPhone.trim().take(40).ifBlank { null },
            "recipientUid" to recipientUid?.take(128)?.ifBlank { null },
            "linkId" to contact.linkId.take(160).ifBlank { null },
            "isTestAlert" to payload.isTestAlert,
            "subjectLine" to payload.subjectLine.take(300).ifBlank { "SoloSignal Check-In Alert" },
            "bodyText" to payload.bodyText.take(4000).ifBlank { "Possible fall check-in alert." },
            "triggerReason" to triggerReason.take(500).ifBlank { "Safety check-in alert" },
            "deliveryState" to STATE_REQUEST_ACCEPTED,
            "deliveryStatusDetail" to initialDetail.take(600),
            "providerMessageId" to providerId.take(300),
            "fcmDeliveryStatus" to if (contact.isReadyForPushAlerts) "PENDING" else "NO_REGISTERED_DEVICE",
            "emailDeliveryStatus" to EMAIL_STATUS_PENDING,
            "channelUsed" to channelUsed.take(80).ifBlank { "SOLOSIGNAL_POPUP" },
            "latitude" to payload.locationSnapshot?.latitude,
            "longitude" to payload.locationSnapshot?.longitude,
            "googleMapsUrl" to payload.locationSnapshot?.googleMapsUrl?.take(500),
            "escalationStatus" to initialEscalationStatus,
            "escalationDeadlineMillis" to initialEscalationDeadline,
            "triggeredAt" to FieldValue.serverTimestamp(),
            "audioStatus" to (payload.audioClip?.let { "SOS_AUDIO_CAPTURED" } ?: payload.audioStatusKey),
            "audioDurationSeconds" to payload.audioClip?.durationSeconds,
            "storageObjectPath" to payload.audioClip?.storageObjectPath?.take(300)?.ifBlank { null },
            "audioDownloadUrl" to payload.audioClip?.downloadUrl?.take(1024)?.ifBlank { null },
            "emailStatus" to EMAIL_STATUS_PENDING,
            "locationSummary" to payload.locationStatusNote.take(500).ifBlank { "Location not included" },
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        @Suppress("UNCHECKED_CAST")
        val writeData = rawMap.filterValues { it != null } as Map<String, Any>
        val docRef = db.collection(COLLECTION_ALERTS).document(safeAlertId)

        return try {
            docRef.set(writeData).await()
            Result.success(
                FirestoreEmergencyAlert(
                    id = safeAlertId,
                    alertId = safeAlertId,
                    senderUid = uid,
                    senderName = senderName,
                    senderEmail = senderEmail,
                    recipientName = recipientName,
                    recipientEmail = recipientEmail,
                    recipientPhone = contact.contactPhone.trim().ifBlank { null },
                    recipientUid = recipientUid,
                    linkId = contact.linkId.ifBlank { null },
                    isTestAlert = payload.isTestAlert,
                    subjectLine = payload.subjectLine,
                    bodyText = payload.bodyText,
                    triggerReason = triggerReason,
                    deliveryState = STATE_REQUEST_ACCEPTED,
                    deliveryStatusDetail = initialDetail,
                    providerMessageId = providerId,
                    emailDeliveryStatus = EMAIL_STATUS_PENDING,
                    channelUsed = channelUsed,
                    latitude = payload.locationSnapshot?.latitude,
                    longitude = payload.locationSnapshot?.longitude,
                    googleMapsUrl = payload.locationSnapshot?.googleMapsUrl,
                    escalationStatus = initialEscalationStatus,
                    escalationDeadlineMillis = initialEscalationDeadline,
                    audioStatus = payload.audioClip?.let { "SOS_AUDIO_CAPTURED" } ?: payload.audioStatusKey,
                    audioDurationSeconds = payload.audioClip?.durationSeconds,
                    storageObjectPath = payload.audioClip?.storageObjectPath,
                    audioDownloadUrl = payload.audioClip?.downloadUrl,
                    emailStatus = EMAIL_STATUS_PENDING,
                    locationSummary = payload.locationStatusNote
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateAlertAudioStatus(
        alertId: String,
        audioStatus: String,
        durationSeconds: Double? = null,
        storageObjectPath: String? = null,
        audioDownloadUrl: String? = null,
        failureReason: String? = null
    ): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val updates = mutableMapOf<String, Any?>(
            "audioStatus" to audioStatus.take(60),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (durationSeconds != null && durationSeconds >= 0.0) {
            updates["audioDurationSeconds"] = durationSeconds.coerceIn(0.0, 60.0)
        }
        if (!storageObjectPath.isNullOrBlank()) {
            updates["storageObjectPath"] = storageObjectPath.take(300)
        }
        if (!audioDownloadUrl.isNullOrBlank()) {
            updates["audioDownloadUrl"] = audioDownloadUrl.take(1024)
        }
        if (!failureReason.isNullOrBlank()) {
            updates["failureReason"] = failureReason.take(600)
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            docRef.update(updates.filterValues { it != null } as Map<String, Any>).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateAlertEmailStatus(
        alertId: String,
        emailSent: Boolean,
        providerName: String,
        providerMessageId: String? = null,
        errorMessage: String? = null,
        statusDetail: String
    ): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val emailStatus = if (emailSent) EMAIL_STATUS_SENT else EMAIL_STATUS_FAILED

        val updates = mutableMapOf<String, Any?>(
            "emailDeliveryStatus" to emailStatus,
            "emailStatus" to emailStatus,
            "emailProvider" to providerName.take(80),
            "emailStatusDetail" to statusDetail.take(600),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        // Advance overall alert deliveryState only if email was successfully sent.
        // If email failed, preserve the existing deliveryState (e.g. REQUEST_ACCEPTED or DELIVERED_TO_RECIPIENT)
        // so an auxiliary email issue never incorrectly marks an accepted alert as failed.
        if (emailSent) {
            updates["deliveryState"] = STATE_EMAIL_SENT
            updates["deliveryStatusDetail"] = statusDetail.take(600)
            updates["emailSentAt"] = FieldValue.serverTimestamp()
        }
        if (!providerMessageId.isNullOrBlank()) {
            updates["emailProviderMessageId"] = providerMessageId.take(300)
            updates["providerMessageId"] = providerMessageId.take(300)
        }
        if (!errorMessage.isNullOrBlank()) {
            updates["emailError"] = errorMessage.take(600)
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            docRef.update(updates.filterValues { it != null } as Map<String, Any>).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateAlertProviderStatus(
        alertId: String,
        deliveryState: String,
        statusDetail: String,
        providerMessageId: String? = null,
        channelUsed: String? = null
    ): Result<Unit> {
        requireUserId()
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val updates = mapOf<String, Any?>(
            "deliveryState" to deliveryState,
            "deliveryStatusDetail" to statusDetail.take(600),
            "providerMessageId" to providerMessageId?.take(300),
            "channelUsed" to channelUsed?.take(80),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }

        return try {
            @Suppress("UNCHECKED_CAST")
            docRef.update(updates as Map<String, Any>).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun confirmRecipientPhonePopUpDelivery(
        alertId: String,
        statusDetail: String = "High-priority phone pop-up alert displayed on recipient's device."
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val updates = mapOf<String, Any>(
            "deliveryState" to STATE_DELIVERED_TO_RECIPIENT,
            "deliveryStatusDetail" to statusDetail.take(600),
            "recipientUid" to uid,
            "fcmDeliveryStatus" to "DELIVERED",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun acknowledgeAlertAsRecipient(
        alertId: String,
        statusDetail: String = "Recipient acknowledged the alert and is checking on the sender."
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val updates = mapOf<String, Any>(
            "deliveryState" to STATE_ACKNOWLEDGED_BY_RECIPIENT,
            "deliveryStatusDetail" to statusDetail.take(600),
            "recipientUid" to uid,
            "escalationStatus" to ESCALATION_CANCELLED_BY_ACK,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun cancelAlertEscalationAsSender(
        alertId: String,
        statusDetail: String = "Emergency alert cancelled or resolved by sender."
    ): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        val updates = mapOf<String, Any>(
            "deliveryState" to STATE_CANCELLED,
            "deliveryStatusDetail" to statusDetail.take(600),
            "escalationStatus" to ESCALATION_CANCELLED_BY_USER,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getAlertById(alertId: String): Result<FirestoreEmergencyAlert?> {
        requireUserId()
        val docRef = db.collection(COLLECTION_ALERTS).document(alertId)
        return try {
            val snap = docRef.get().await()
            val item = snap.toObject(
                FirestoreEmergencyAlert::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeSenderAlerts(senderUid: String): Flow<List<FirestoreEmergencyAlert>> {
        return db.collection(COLLECTION_ALERTS)
            .whereEqualTo("senderUid", senderUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreEmergencyAlert::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_ALERTS)
                }
                throw error
            }
    }

    fun observeRecipientAlertsByEmail(recipientEmail: String): Flow<List<FirestoreEmergencyAlert>> {
        val normalized = recipientEmail.trim().lowercase()
        return db.collection(COLLECTION_ALERTS)
            .whereEqualTo("recipientEmail", normalized)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreEmergencyAlert::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_ALERTS)
                }
                throw error
            }
    }

    fun observeRecipientAlertsByUid(recipientUid: String): Flow<List<FirestoreEmergencyAlert>> {
        return db.collection(COLLECTION_ALERTS)
            .whereEqualTo("recipientUid", recipientUid)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    FirestoreEmergencyAlert::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, COLLECTION_ALERTS)
                }
                throw error
            }
    }

    fun observeRegisteredContactByEmail(email: String): Flow<FirestoreRegisteredContact?> {
        val emailKey = buildEmailKey(email)
        return db.collection(COLLECTION_REGISTERED_CONTACTS)
            .document(emailKey)
            .snapshots()
            .map { snapshot ->
                if (snapshot.exists()) {
                    snapshot.toObject(
                        FirestoreRegisteredContact::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                } else {
                    null
                }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.GET, "$COLLECTION_REGISTERED_CONTACTS/$emailKey")
                }
                emit(null)
            }
    }

    companion object {
        const val COLLECTION_USERS = "users"
        const val SUBCOLLECTION_DEVICES = "devices"
        const val COLLECTION_REGISTERED_CONTACTS = "registered_contacts"
        const val COLLECTION_CONTACT_LINKS = "trusted_contact_links"
        const val COLLECTION_ALERTS = "emergency_alerts"

        private const val STALE_DEVICE_TTL_MILLIS = 60L * 24L * 60L * 60L * 1000L

        const val STATE_SENDING = "SENDING"
        const val STATE_REQUEST_ACCEPTED = "REQUEST_ACCEPTED"
        const val STATE_EMAIL_SENT = "EMAIL_SENT"
        const val STATE_EMAIL_FAILED = "EMAIL_FAILED"
        const val STATE_SENT_BY_PROVIDER = "SENT_BY_PROVIDER"
        const val STATE_DELIVERED_TO_RECIPIENT = "DELIVERED_TO_RECIPIENT"
        const val STATE_ACKNOWLEDGED_BY_RECIPIENT = "ACKNOWLEDGED_BY_RECIPIENT"
        const val STATE_ESCALATED_VIDEO_CALL = "ESCALATED_VIDEO_CALL"
        const val STATE_CANCELLED = "CANCELLED"
        const val STATE_FAILED = "FAILED"

        const val EMERGENCY_ESCALATION_TIMEOUT_MS = 15_000L

        const val ESCALATION_NONE = "NONE"
        const val ESCALATION_WAITING_FOR_ACK = "WAITING_FOR_ACK"
        const val ESCALATION_CANCELLED_BY_ACK = "CANCELLED_BY_ACK"
        const val ESCALATION_CANCELLED_BY_USER = "CANCELLED_BY_USER"
        const val ESCALATION_MEET_TRIGGERING = "MEET_TRIGGERING"
        const val ESCALATION_MEET_CREATED = "MEET_CREATED"
        const val ESCALATION_MEET_FAILED = "MEET_FAILED"

        const val EMAIL_STATUS_PENDING = "PENDING"
        const val EMAIL_STATUS_SENDING = "SENDING_EMAIL"
        const val EMAIL_STATUS_SENT = "EMAIL_SENT"
        const val EMAIL_STATUS_FAILED = "EMAIL_FAILED"
    }
}

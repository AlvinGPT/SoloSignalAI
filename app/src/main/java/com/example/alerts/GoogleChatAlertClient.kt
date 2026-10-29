package com.example.alerts

import com.example.domain.GoogleChatAlertConfig
import com.example.domain.GpsLocationSnapshot
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

sealed class GoogleChatSendOutcome {
    data class Success(
        val spaceName: String,
        val messageId: String,
        val detail: String,
        val timestampMillis: Long = System.currentTimeMillis()
    ) : GoogleChatSendOutcome()

    data class Failure(
        val reason: String,
        val httpCode: Int? = null
    ) : GoogleChatSendOutcome()
}

class GoogleChatAlertClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun sendInitialConnectionMessage(
        config: GoogleChatAlertConfig,
        userDisplayName: String
    ): GoogleChatSendOutcome {
        return sendToBackend(
            config = config,
            messageType = "INITIAL_CONNECTION",
            messageText = GoogleChatAlertConfig.INITIAL_CONNECTION_MESSAGE,
            userDisplayName = userDisplayName,
            locationSnapshot = null
        )
    }

    suspend fun sendPossibleFallAlert(
        config: GoogleChatAlertConfig,
        payload: AlertMessagePayload,
        triggerReason: String = ""
    ): GoogleChatSendOutcome {
        return sendToBackend(
            config = config,
            messageType = "POSSIBLE_FALL_ALERT",
            messageText = payload.bodyText,
            userDisplayName = payload.userDisplayName,
            locationSnapshot = payload.locationSnapshot
        )
    }

    private suspend fun sendToBackend(
        config: GoogleChatAlertConfig,
        messageType: String,
        messageText: String,
        userDisplayName: String,
        locationSnapshot: GpsLocationSnapshot?
    ): GoogleChatSendOutcome = withContext(Dispatchers.IO) {
        val endpointUrl = config.normalizedEndpointUrl
        val spaceName = config.normalizedSpaceName

        if (endpointUrl.isBlank() || spaceName.isBlank()) {
            return@withContext GoogleChatSendOutcome.Failure(
                "Backend Cloud Function URL and Google Chat Space ID (spaces/...) are required."
            )
        }

        if (endpointUrl.lowercase().contains("chat.googleapis.com")) {
            return@withContext GoogleChatSendOutcome.Failure(
                "Direct calls to chat.googleapis.com from Android are blocked for security. Use your Firebase Cloud Function backend URL so service-account credentials stay on the server."
            )
        }

        if (!GoogleChatAlertConfig.isValidHttpsBackendUrl(endpointUrl)) {
            return@withContext GoogleChatSendOutcome.Failure(
                "Enter a valid HTTPS backend endpoint URL without embedded secret keys."
            )
        }

        if (!GoogleChatAlertConfig.isValidChatSpaceName(spaceName)) {
            return@withContext GoogleChatSendOutcome.Failure(
                "Enter a valid Google Chat Space resource name (e.g., spaces/AAAA1234567)."
            )
        }

        val idToken = fetchFirebaseIdToken()
            ?: return@withContext GoogleChatSendOutcome.Failure(
                "Sign in with Google (Firebase Authentication) is required to authenticate with the Google Chat backend."
            )

        val requestJson = JSONObject().apply {
            put("messageType", messageType)
            put("spaceName", spaceName)
            put("spaceDisplayLabel", config.spaceDisplayLabel.trim())
            put("userDisplayName", userDisplayName)
            put("text", messageText)
            put("timestampEpochMillis", System.currentTimeMillis())
            if (locationSnapshot != null) {
                put(
                    "location",
                    JSONObject().apply {
                        put("latitude", locationSnapshot.latitude)
                        put("longitude", locationSnapshot.longitude)
                        put("accuracyMeters", locationSnapshot.accuracyMeters.toDouble())
                        put("googleMapsUrl", locationSnapshot.googleMapsUrl)
                        if (!locationSnapshot.nearbyEmergencyContext.isNullOrBlank()) {
                            put("nearbyEmergencyContext", locationSnapshot.nearbyEmergencyContext)
                        }
                    }
                )
            }
        }

        try {
            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val serverError = try {
                        JSONObject(rawBody).optString("error").takeIf { it.isNotBlank() }
                    } catch (_: Exception) {
                        null
                    }
                    val detail = serverError
                        ?: "Backend returned HTTP ${response.code}. Verify your Cloud Function is deployed and authorized for $spaceName."
                    return@withContext GoogleChatSendOutcome.Failure(
                        reason = detail,
                        httpCode = response.code
                    )
                }

                val json = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    return@withContext GoogleChatSendOutcome.Failure(
                        reason = "Backend returned a non-JSON response. Ensure the endpoint points to the SoloSignal sendGoogleChatAlert Cloud Function."
                    )
                }

                val isOk = json.optBoolean("ok", false)
                if (!isOk) {
                    val err = json.optString(
                        "error",
                        "Backend did not confirm message delivery to Google Chat space $spaceName."
                    )
                    return@withContext GoogleChatSendOutcome.Failure(reason = err)
                }

                val chatMsgName = json.optString("chatMessageName", "$spaceName/messages/verified")
                val statusMsg = json.optString(
                    "message",
                    "Message delivered to Google Chat space $spaceName."
                )
                GoogleChatSendOutcome.Success(
                    spaceName = spaceName,
                    messageId = chatMsgName,
                    detail = statusMsg
                )
            }
        } catch (e: Exception) {
            GoogleChatSendOutcome.Failure(
                reason = "Could not reach backend (${e.javaClass.simpleName}: ${e.localizedMessage ?: "network error"}). Deploy the Firebase Cloud Function in /backend/functions and verify the URL."
            )
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
}

package com.example.alerts

import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class MeetFallbackOutcome(
    val triggered: Boolean,
    val escalationStatus: String,
    val meetUrl: String? = null,
    val meetSpaceName: String? = null,
    val meetError: String? = null,
    val detail: String = ""
)

/**
 * Client for invoking the authenticated `checkOrTriggerEmergencyMeetFallback` Firebase Cloud Function.
 *
 * The Cloud Function executes an idempotent server-side Firestore transaction on `emergency_alerts/{alertId}`:
 * - If the recipient acknowledged within 15 seconds (`ACKNOWLEDGED_BY_RECIPIENT` / `CANCELLED_BY_ACK`)
 *   or the emergency was cancelled (`CANCELLED`), it cancels the fallback.
 * - Otherwise, it creates a Google Meet space on the server using the official Google Meet REST API
 *   (`POST https://meet.googleapis.com/v2/spaces`) and dispatches the meeting link via FCM to the
 *   trusted contact and the fallen user.
 * - Never embeds service-account credentials in the Android client.
 */
class MeetFallbackClient(
    private val customEndpointUrl: String = ""
) {
    /**
     * Optional test hook for unit and Robolectric tests to verify the 15-second timeout and fallback
     * flow without requiring a live network connection.
     */
    var testOverrideHandler: (suspend (alertId: String) -> MeetFallbackOutcome)? = null

    suspend fun checkOrTriggerMeetFallback(alertId: String): MeetFallbackOutcome {
        val cleanAlertId = alertId.trim()
        if (cleanAlertId.isBlank()) {
            return MeetFallbackOutcome(
                triggered = false,
                escalationStatus = "MEET_FAILED",
                meetError = "Missing alertId",
                detail = "Cannot trigger Google Meet fallback without a valid alertId."
            )
        }

        testOverrideHandler?.let { handler ->
            return handler(cleanAlertId)
        }

        return withContext(Dispatchers.IO) {

        val currentUser = try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        } ?: return@withContext MeetFallbackOutcome(
            triggered = false,
            escalationStatus = "MEET_FAILED",
            meetError = "Not signed in to Firebase Authentication",
            detail = "Cannot invoke Google Meet fallback Cloud Function while signed out."
        )

        val idToken = try {
            currentUser.getIdToken(false).await().token
        } catch (e: Exception) {
            null
        } ?: return@withContext MeetFallbackOutcome(
            triggered = false,
            escalationStatus = "MEET_FAILED",
            meetError = "Could not obtain Firebase ID token",
            detail = "Could not authenticate request to Google Meet fallback Cloud Function."
        )

        val endpoint = customEndpointUrl.trim().ifBlank { DEFAULT_MEET_FALLBACK_ENDPOINT }
        val payload = JSONObject().apply {
            put("alertId", cleanAlertId)
        }

        var conn: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $idToken")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()

            val json = try {
                if (body.isNotBlank()) JSONObject(body) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }

            if (code in 200..299) {
                val triggered = json.optBoolean("triggered", false)
                val escalationStatus = json.optString("escalationStatus", if (triggered) "MEET_CREATED" else "MEET_FAILED")
                val meetUrl = json.optString("meetUrl", "").trim().takeIf { it.isNotBlank() }
                val meetSpaceName = json.optString("meetSpaceName", "").trim().takeIf { it.isNotBlank() }
                val meetError = json.optString("meetError", "").trim().takeIf { it.isNotBlank() }
                val detail = json.optString("detail", "").trim()
                MeetFallbackOutcome(
                    triggered = triggered,
                    escalationStatus = escalationStatus,
                    meetUrl = meetUrl,
                    meetSpaceName = meetSpaceName,
                    meetError = meetError,
                    detail = detail
                )
            } else {
                val errMsg = json.optString("error", "Cloud Function HTTP $code").trim()
                MeetFallbackOutcome(
                    triggered = false,
                    escalationStatus = "MEET_FAILED",
                    meetError = errMsg,
                    detail = "Trusted contact did not respond within 15s. Google Meet creation failed ($errMsg). Existing emergency notification remains active."
                )
            }
        } catch (e: Exception) {
            val errMsg = e.message ?: "Network error contacting Cloud Function"
            MeetFallbackOutcome(
                triggered = false,
                escalationStatus = "MEET_FAILED",
                meetError = errMsg,
                detail = "Trusted contact did not respond within 15s. Google Meet creation failed ($errMsg). Existing emergency notification remains active."
            )
        } finally {
            conn?.disconnect()
        }
        }
    }

    companion object {
        const val DEFAULT_MEET_FALLBACK_ENDPOINT =
            "https://us-central1-gen-lang-client-0124462176.cloudfunctions.net/checkOrTriggerEmergencyMeetFallback"
    }
}

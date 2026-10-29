package com.example.data

import com.example.domain.SosAudioClipMetadata
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

typealias SosAudioUploadResult = SosAudioStorageUploadResult

sealed class SosAudioStorageUploadResult {
    data class Uploaded(
        val updatedMetadata: SosAudioClipMetadata,
        val storageObjectPath: String,
        val protectedDownloadUrl: String
    ) : SosAudioStorageUploadResult() {
        val downloadUrl: String
            get() = protectedDownloadUrl
    }

    data class Failed(
        val reason: String,
        val fallbackClip: SosAudioClipMetadata
    ) : SosAudioStorageUploadResult()
}

/**
 * Uploads finalized SOS audio clips (`sos_audio/{userId}/{alertId}/sos_recording.m4a`)
 * to Firebase Storage over HTTPS using the authenticated user's Firebase ID token.
 *
 * Security & reliability guarantees:
 * - Enforces per-user object path isolation (`sos_audio/{userId}/{alertId}/...`).
 * - Attaches custom metadata (`userId`, `alertId`, `capturedAt`, `durationSeconds`, `contentType`).
 * - Bounded upload timeout so a slow network never blocks emergency alert dispatch indefinitely.
 * - Supports deleting uploaded objects when the user deletes their recordings or personal data.
 */
class SosAudioStorageUploader(
    private val storageBucket: String = DEFAULT_STORAGE_BUCKET,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {

    fun buildStorageObjectPath(
        userId: String,
        alertId: String,
        extension: String = "m4a"
    ): String {
        val safeUid = userId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "anonymous" }
        val safeAlertId = alertId.trim().replace(Regex("[^a-zA-Z0-9_\\-]"), "_").ifBlank { "alert" }
        val safeExt = extension.trim().lowercase(Locale.US).ifBlank { "m4a" }
        return "sos_audio/$safeUid/$safeAlertId/sos_recording.$safeExt"
    }

    suspend fun uploadSosAudioRecording(
        alertId: String,
        localFile: File,
        clipMetadata: SosAudioClipMetadata,
        userIdOverride: String? = null
    ): SosAudioStorageUploadResult {
        val clipWithFile = clipMetadata.copy(
            alertId = alertId,
            localFilePath = localFile.absolutePath
        )
        return uploadFinalizedAudio(
            clip = clipWithFile,
            userIdOverride = userIdOverride
        )
    }

    suspend fun deleteSosAudioRecording(storageObjectPath: String): Boolean {
        return deleteUploadedAudioObject(storageObjectPath)
    }

    suspend fun uploadFinalizedAudio(
        clip: SosAudioClipMetadata,
        userIdOverride: String? = null,
        idTokenOverride: String? = null,
        deleteLocalAfterUpload: Boolean = false
    ): SosAudioStorageUploadResult = withContext(Dispatchers.IO) {
        val localPath = clip.localFilePath
        if (localPath.isNullOrBlank()) {
            return@withContext SosAudioStorageUploadResult.Failed(
                reason = "Local SOS audio file path is missing.",
                fallbackClip = clip
            )
        }
        val audioFile = File(localPath)
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            return@withContext SosAudioStorageUploadResult.Failed(
                reason = "Local SOS audio file does not exist or is empty.",
                fallbackClip = clip
            )
        }

        val currentUser = try {
            Firebase.auth.currentUser
        } catch (_: Throwable) {
            null
        }
        val uid = userIdOverride?.takeIf { it.isNotBlank() } ?: currentUser?.uid.orEmpty()
        if (uid.isBlank()) {
            return@withContext SosAudioStorageUploadResult.Failed(
                reason = "User is not authenticated with Firebase Auth.",
                fallbackClip = clip
            )
        }

        val idToken = idTokenOverride?.takeIf { it.isNotBlank() } ?: try {
            currentUser?.getIdToken(false)?.await()?.token.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (idToken.isBlank()) {
            return@withContext SosAudioStorageUploadResult.Failed(
                reason = "Could not obtain Firebase Authentication ID token for Storage upload.",
                fallbackClip = clip
            )
        }

        val objectPath = buildStorageObjectPath(uid, clip.alertId, clip.containerExtension)
        val encodedName = URLEncoder.encode(objectPath, "UTF-8")
        val uploadUrl = "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o?uploadType=media&name=$encodedName"

        val requestBody = audioFile.asRequestBody(clip.mimeType.toMediaType())
        val request = Request.Builder()
            .url(uploadUrl)
            .addHeader("Authorization", "Bearer $idToken")
            .addHeader("Content-Type", clip.mimeType)
            .addHeader("X-Goog-Meta-userId", uid)
            .addHeader("X-Goog-Meta-alertId", clip.alertId)
            .addHeader("X-Goog-Meta-capturedAt", clip.capturedAtMillis.toString())
            .addHeader(
                "X-Goog-Meta-durationSeconds",
                String.format(Locale.US, "%.2f", clip.durationSeconds)
            )
            .post(requestBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext SosAudioStorageUploadResult.Failed(
                        reason = "Firebase Storage returned HTTP ${response.code}.",
                        fallbackClip = clip
                    )
                }

                val json = try {
                    JSONObject(rawBody)
                } catch (_: Exception) {
                    JSONObject()
                }
                val downloadToken = json.optString("downloadTokens", "")
                    .split(",")
                    .firstOrNull()
                    ?.trim()
                    .orEmpty()

                val protectedUrl = if (downloadToken.isNotBlank()) {
                    "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o/$encodedName?alt=media&token=$downloadToken"
                } else {
                    "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o/$encodedName?alt=media"
                }

                if (deleteLocalAfterUpload) {
                    try {
                        audioFile.delete()
                    } catch (_: Exception) {
                    }
                }

                val updated = clip.copy(
                    storageObjectPath = objectPath,
                    downloadUrl = protectedUrl,
                    localFilePath = if (deleteLocalAfterUpload) null else clip.localFilePath,
                    statusNote = "Uploaded to Firebase Storage ($objectPath)"
                )

                SosAudioStorageUploadResult.Uploaded(
                    updatedMetadata = updated,
                    storageObjectPath = objectPath,
                    protectedDownloadUrl = protectedUrl
                )
            }
        } catch (e: Exception) {
            SosAudioStorageUploadResult.Failed(
                reason = "Storage upload error: ${e.localizedMessage ?: "network unavailable"}",
                fallbackClip = clip
            )
        }
    }

    suspend fun deleteUploadedAudioObject(storageObjectPath: String): Boolean = withContext(Dispatchers.IO) {
        if (storageObjectPath.isBlank()) return@withContext false
        val idToken = try {
            Firebase.auth.currentUser?.getIdToken(false)?.await()?.token.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        if (idToken.isBlank()) return@withContext false

        val encodedName = URLEncoder.encode(storageObjectPath.trim(), "UTF-8")
        val deleteUrl = "https://firebasestorage.googleapis.com/v0/b/$storageBucket/o/$encodedName"
        val request = Request.Builder()
            .url(deleteUrl)
            .addHeader("Authorization", "Bearer $idToken")
            .delete()
            .build()
        return@withContext try {
            httpClient.newCall(request).execute().use { it.isSuccessful || it.code == 404 }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DEFAULT_STORAGE_BUCKET = "gen-lang-client-0124462176.firebasestorage.app"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        }
    }
}

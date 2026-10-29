package com.example.domain

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Patterns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONObject

/**
 * Versioned, cross-device compatible QR payload for SoloSignal AI Emergency Contact sharing.
 *
 * Security & Privacy invariants:
 * - Never includes passwords, OAuth/Firebase auth tokens, or Firebase project secrets.
 * - Includes an expiring `inviteToken` (`issuedAtMillis` / `expiresAtMillis`) so stale QR codes
 *   are rejected automatically.
 * - Uses the existing authenticated account identity (`uid`, `email`, `displayName`, and optional `phone`)
 *   so the scanning phone can verify recipient account & FCM device readiness through the existing
 *   SoloSignal AI verification pipeline.
 */
data class QrContactPayload(
    val schema: String = QrContactCodec.SCHEMA_ID,
    val version: Int = QrContactCodec.CURRENT_VERSION,
    val inviteToken: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val uid: String,
    val displayName: String,
    val email: String,
    val phone: String = ""
) {
    val hasValidIdentifier: Boolean
        get() = email.isNotBlank() || phone.isNotBlank()

    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis > expiresAtMillis || expiresAtMillis <= issuedAtMillis
    }

    /**
     * Masks email/phone for safe display or diagnostics without logging raw personal details.
     */
    fun redactedIdentifierSummary(): String {
        val emailPart = if (email.contains("@")) {
            val parts = email.split("@", limit = 2)
            val local = parts[0]
            val maskedLocal = if (local.length <= 2) "${local.firstOrNull() ?: '*'}*" else "${local.take(2)}***"
            "$maskedLocal@${parts[1]}"
        } else {
            ""
        }
        val phonePart = if (phone.length >= 4) {
            "***-${phone.takeLast(4)}"
        } else {
            ""
        }
        return listOf(emailPart, phonePart).filter { it.isNotBlank() }.joinToString(" • ")
    }
}

sealed class QrContactValidationResult {
    data class Valid(val payload: QrContactPayload) : QrContactValidationResult()
    data class Expired(val message: String) : QrContactValidationResult()
    data class UnsupportedVersion(val version: Int, val message: String) : QrContactValidationResult()
    data class SelfScanRejected(val message: String) : QrContactValidationResult()
    data class DuplicateContact(
        val payload: QrContactPayload,
        val message: String
    ) : QrContactValidationResult()
    data class InvalidFormat(val message: String) : QrContactValidationResult()
}

object QrContactCodec {
    const val SCHEMA_ID = "solosignal.qr.contact"
    const val URI_PREFIX = "solosignal://contact?v=1&payload="
    const val CURRENT_VERSION = 1
    const val DEFAULT_TTL_MILLIS = 15 * 60 * 1000L // 15 minutes

    /**
     * Creates a fresh [QrContactPayload] for the signed-in SoloSignal AI user.
     */
    fun createPersonalPayload(
        uid: String,
        displayName: String,
        email: String,
        phone: String = "",
        nowMillis: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS,
        inviteToken: String = generateInviteToken()
    ): QrContactPayload {
        val cleanEmail = email.trim().lowercase()
        val cleanPhone = sanitizePhone(phone)
        val resolvedName = displayName.trim().ifBlank {
            if (cleanEmail.contains("@")) cleanEmail.substringBefore("@") else "SoloSignal User"
        }
        return QrContactPayload(
            schema = SCHEMA_ID,
            version = CURRENT_VERSION,
            inviteToken = inviteToken,
            issuedAtMillis = nowMillis,
            expiresAtMillis = nowMillis + ttlMillis,
            uid = uid.trim(),
            displayName = resolvedName,
            email = cleanEmail,
            phone = cleanPhone
        )
    }

    fun generateInviteToken(): String {
        return "inv_" + UUID.randomUUID().toString().replace("-", "").take(16)
    }

    /**
     * Encodes [QrContactPayload] into a compact, URL-safe SoloSignal QR URI string.
     */
    fun encode(payload: QrContactPayload): String {
        val json = toJsonObject(payload).toString()
        val encoded = Base64.encodeToString(
            json.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$URI_PREFIX$encoded"
    }

    fun toJsonObject(payload: QrContactPayload): JSONObject {
        return JSONObject().apply {
            put("schema", payload.schema)
            put("v", payload.version)
            put("token", payload.inviteToken)
            put("iat", payload.issuedAtMillis)
            put("exp", payload.expiresAtMillis)
            put("uid", payload.uid)
            put("name", payload.displayName)
            put("email", payload.email)
            put("phone", payload.phone)
        }
    }

    /**
     * Decodes and validates a scanned QR string against schema, version, field integrity,
     * expiration window, self-scan checks, and existing contact duplicates.
     */
    fun decodeAndValidate(
        rawQrContent: String?,
        scannerUid: String? = null,
        scannerEmail: String? = null,
        existingContact: TrustedContact? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): QrContactValidationResult {
        val trimmed = rawQrContent?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return QrContactValidationResult.InvalidFormat(
                "Empty QR code. Align a valid SoloSignal AI emergency contact QR code inside the frame."
            )
        }

        val jsonText = try {
            when {
                trimmed.startsWith("solosignal://contact", ignoreCase = true) -> {
                    val payloadParam = extractPayloadQueryParam(trimmed)
                        ?: return QrContactValidationResult.InvalidFormat(
                            "Invalid SoloSignal QR link. Ask the other person to refresh their QR code."
                        )
                    val decodedBytes = Base64.decode(
                        payloadParam,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                    )
                    String(decodedBytes, StandardCharsets.UTF_8)
                }
                trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
                else -> {
                    return QrContactValidationResult.InvalidFormat(
                        "Unsupported QR code. Only SoloSignal AI emergency contact QR codes can be scanned here."
                    )
                }
            }
        } catch (_: Exception) {
            return QrContactValidationResult.InvalidFormat(
                "Damaged or unreadable QR payload. Ask the other person to regenerate their QR code."
            )
        }

        val json = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return QrContactValidationResult.InvalidFormat(
                "Malformed QR data format. Only valid SoloSignal AI contact codes are supported."
            )
        }

        val schema = json.optString("schema", "")
        if (schema != SCHEMA_ID) {
            return QrContactValidationResult.InvalidFormat(
                "Unrecognized QR code type. Please scan a SoloSignal AI personal QR code."
            )
        }

        val version = json.optInt("v", -1)
        if (version <= 0) {
            return QrContactValidationResult.InvalidFormat(
                "Missing QR version field. Please refresh and scan again."
            )
        }
        if (version > CURRENT_VERSION) {
            return QrContactValidationResult.UnsupportedVersion(
                version = version,
                message = "This QR code was created by a newer version of SoloSignal AI (v$version). Please update the app."
            )
        }

        val token = json.optString("token", "").trim()
        val iat = json.optLong("iat", 0L)
        val exp = json.optLong("exp", 0L)
        val uid = json.optString("uid", "").trim()
        val name = json.optString("name", "").trim()
        val email = json.optString("email", "").trim().lowercase()
        val phone = sanitizePhone(json.optString("phone", ""))

        if (token.length < 6 || iat <= 0L || exp <= iat) {
            return QrContactValidationResult.InvalidFormat(
                "QR code is missing a valid invitation token or timestamp."
            )
        }

        if (name.isBlank() || name.length > 80) {
            return QrContactValidationResult.InvalidFormat(
                "QR code does not contain a valid contact display name."
            )
        }

        val hasValidEmail = email.isNotBlank() && isPlausibleEmail(email)
        val hasValidPhone = phone.isNotBlank() && isPlausiblePhone(phone)

        if (!hasValidEmail && !hasValidPhone) {
            return QrContactValidationResult.InvalidFormat(
                "QR code must include a valid SoloSignal AI account email or phone number."
            )
        }
        if (email.isNotBlank() && !hasValidEmail) {
            return QrContactValidationResult.InvalidFormat(
                "QR code contains an invalid email address."
            )
        }
        if (phone.isNotBlank() && !hasValidPhone) {
            return QrContactValidationResult.InvalidFormat(
                "QR code contains an invalid phone number format."
            )
        }

        val payload = QrContactPayload(
            schema = schema,
            version = version,
            inviteToken = token,
            issuedAtMillis = iat,
            expiresAtMillis = exp,
            uid = uid,
            displayName = name,
            email = email,
            phone = phone
        )

        // Check expiration
        if (payload.isExpired(nowMillis)) {
            return QrContactValidationResult.Expired(
                "This QR code has expired. Ask ${payload.displayName} to tap 'Refresh Code' and scan again."
            )
        }

        // Prevent scanning one's own personal QR code
        val isSelfUid = !scannerUid.isNullOrBlank() && payload.uid.isNotBlank() &&
            scannerUid.trim() == payload.uid
        val isSelfEmail = !scannerEmail.isNullOrBlank() && payload.email.isNotBlank() &&
            scannerEmail.trim().equals(payload.email, ignoreCase = true)
        if (isSelfUid || isSelfEmail) {
            return QrContactValidationResult.SelfScanRejected(
                "You scanned your own SoloSignal AI QR code. Please scan your trusted contact's QR code instead."
            )
        }

        // Check duplicate against already-saved emergency contact
        if (existingContact != null && existingContact.isValid) {
            val sameEmail = payload.email.isNotBlank() &&
                existingContact.contactEmail.trim().equals(payload.email, ignoreCase = true)
            val samePhone = payload.phone.isNotBlank() &&
                existingContact.contactPhone.isNotBlank() &&
                normalizeDigits(existingContact.contactPhone) == normalizeDigits(payload.phone)
            if (sameEmail || samePhone) {
                return QrContactValidationResult.DuplicateContact(
                    payload = payload,
                    message = "${payload.displayName} is already saved as your emergency contact."
                )
            }
        }

        return QrContactValidationResult.Valid(payload)
    }

    private fun extractPayloadQueryParam(uriString: String): String? {
        val queryStart = uriString.indexOf('?')
        if (queryStart < 0 || queryStart == uriString.lastIndex) return null
        val query = uriString.substring(queryStart + 1)
        for (pair in query.split('&')) {
            val kv = pair.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("payload", ignoreCase = true)) {
                return kv[1].trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun sanitizePhone(raw: String): String {
        val kept = buildString {
            raw.trim().forEachIndexed { index, ch ->
                if (ch.isDigit() || (ch == '+' && index == 0) || ch == '-' || ch == ' ' || ch == '(' || ch == ')') {
                    append(ch)
                }
            }
        }.trim()
        return kept
    }

    private fun normalizeDigits(phone: String): String {
        return phone.filter { it.isDigit() }
    }

    private fun isPlausibleEmail(email: String): Boolean {
        val pattern = Patterns.EMAIL_ADDRESS
        return if (pattern != null) {
            pattern.matcher(email).matches()
        } else {
            val atIndex = email.indexOf('@')
            val dotIndex = email.lastIndexOf('.')
            atIndex > 0 && dotIndex > atIndex + 1 && dotIndex < email.lastIndex
        }
    }

    private fun isPlausiblePhone(phone: String): Boolean {
        val digits = normalizeDigits(phone)
        return digits.length in 7..15
    }
}

object QrBitmapGenerator {
    /**
     * Generates a crisp square QR code [Bitmap] for [content] using ZXing [QRCodeWriter].
     */
    fun generateBitmap(content: String, sizePx: Int = 640): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            val darkColor = Color.rgb(24, 26, 32)
            val lightColor = Color.WHITE
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) darkColor else lightColor
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun generateImageBitmap(content: String, sizePx: Int = 640): ImageBitmap? {
        return generateBitmap(content, sizePx)?.asImageBitmap()
    }
}

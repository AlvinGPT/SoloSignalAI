package com.example.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import org.json.JSONObject

/**
 * Interface representing a backend translation provider.
 * Supports either a secure Google Cloud Translation v3 Cloud Run / Cloud Functions proxy
 * or an AI4Bharat IndicTrans2 server endpoint.
 *
 * Security Requirement:
 * Secrets and API keys are strictly forbidden from mobile client code.
 * All dynamic translation queries are proxied via authenticated backend services.
 */
interface DynamicTranslationProvider {
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<String>
}

/**
 * Safe backend translation client implementation.
 * Connects to a secure backend proxy endpoint if configured.
 * Safely falls back to local pretranslated text on network error, offline mode, or timeouts.
 */
class BackendTranslationClient(
    private val backendProxyUrl: String? = null
) : DynamicTranslationProvider {

    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (backendProxyUrl.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("No backend translation proxy configured."))
        }

        try {
            val url = URL(backendProxyUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")

            val payload = JSONObject().apply {
                put("q", text)
                put("source", sourceLanguage)
                put("target", targetLanguage)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            if (conn.responseCode in 200..299) {
                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseBody)
                val translatedText = json.optString("translatedText", text)
                Result.success(translatedText)
            } else {
                Result.failure(Exception("HTTP ${conn.responseCode}: ${conn.responseMessage}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * High-level Dynamic Translation Service for SoloSignal AI.
 *
 * Offline-First & Resilient:
 * - Features an in-memory LRU cache to minimize network calls and preserve offline usability.
 * - Guarantees that emergency flows and high-stress UI elements NEVER block on remote translation.
 * - Immediately falls back to local string templates upon any network or timeout failure.
 */
object DynamicTranslationService {

    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > 200
            }
        }
    )
    private var provider: DynamicTranslationProvider = BackendTranslationClient()

    fun setProvider(customProvider: DynamicTranslationProvider) {
        provider = customProvider
    }

    /**
     * Translates dynamic text (such as AI explainability reasoning or non-critical descriptions).
     * If the target language is English or matches the source, returns the text immediately.
     * If network fails or offline, returns [fallbackText] with zero UI interruptions.
     */
    suspend fun translateDynamicText(
        text: String,
        targetLanguage: String,
        sourceLanguage: String = "en",
        fallbackText: String = text
    ): String {
        if (text.isBlank() || targetLanguage.equals("en", ignoreCase = true) || targetLanguage.equals(sourceLanguage, ignoreCase = true)) {
            return text
        }

        val cacheKey = "${sourceLanguage}_${targetLanguage}_$text"
        val cached = cache.get(cacheKey)
        if (cached != null) {
            return cached
        }

        val result = try {
            provider.translate(text, sourceLanguage, targetLanguage)
        } catch (_: Throwable) {
            Result.failure(Exception("Dynamic translation unavailable"))
        }

        return if (result.isSuccess) {
            val translated = result.getOrNull() ?: fallbackText
            cache.put(cacheKey, translated)
            translated
        } else {
            // Graceful non-blocking offline fallback
            fallbackText
        }
    }

    fun clearCache() {
        cache.clear()
    }
}

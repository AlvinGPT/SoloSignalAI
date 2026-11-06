package com.example.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val isRtl: Boolean = false
)

object LocaleHelper {

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("en", "English", "English"),
        SupportedLanguage("ar", "العربية", "Arabic", isRtl = true),
        SupportedLanguage("ml", "മലയാളം", "Malayalam"),
        SupportedLanguage("hi", "हिन्दी", "Hindi"),
        SupportedLanguage("ta", "தமிழ்", "Tamil"),
        SupportedLanguage("te", "తెలుగు", "Telugu"),
        SupportedLanguage("kn", "ಕನ್ನಡ", "Kannada"),
        SupportedLanguage("bn", "বাংলা", "Bengali"),
        SupportedLanguage("mr", "मराठी", "Marathi"),
        SupportedLanguage("gu", "ગુજરાતી", "Gujarati"),
        SupportedLanguage("pa", "ਪੰਜਾਬੀ", "Punjabi"),
        SupportedLanguage("ur", "اردو", "Urdu", isRtl = true)
    )

    fun isSupported(code: String): Boolean {
        return SUPPORTED_LANGUAGES.any { it.code.equals(code, ignoreCase = true) }
    }

    fun getLanguageByCode(code: String): SupportedLanguage {
        return SUPPORTED_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: SUPPORTED_LANGUAGES.first() // default English
    }

    /**
     * Applies the chosen locale to the app at runtime.
     * Uses Android 13+ native LocaleManager when available,
     * and updates resource configuration for backwards compatibility.
     */
    fun applyLocale(context: Context, languageCode: String) {
        val targetCode = if (isSupported(languageCode)) languageCode else "en"
        val locale = Locale.forLanguageTag(targetCode)
        Locale.setDefault(locale)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            localeManager?.applicationLocales = LocaleList.forLanguageTags(targetCode)
        } else {
            @Suppress("DEPRECATION")
            val resources = context.resources
            val configuration = resources.configuration
            configuration.setLocale(locale)
            configuration.setLayoutDirection(locale)
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
    }

    /**
     * Wraps a base context with the specified locale configuration.
     * Used in Activity.attachBaseContext() across all Android API versions.
     */
    fun wrap(context: Context, languageCode: String): Context {
        val targetCode = if (isSupported(languageCode)) languageCode else "en"
        val locale = Locale.forLanguageTag(targetCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * Resolves the currently active language code from system or app configuration.
     */
    fun getActiveLanguageCode(context: Context, preferredCode: String? = null): String {
        if (!preferredCode.isNullOrBlank() && isSupported(preferredCode)) {
            return preferredCode
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val appLocales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (appLocales != null && !appLocales.isEmpty) {
                val primaryTag = appLocales.get(0).language
                if (isSupported(primaryTag)) return primaryTag
            }
        }
        val currentLang = context.resources.configuration.locales.get(0)?.language ?: "en"
        return if (isSupported(currentLang)) currentLang else "en"
    }
}

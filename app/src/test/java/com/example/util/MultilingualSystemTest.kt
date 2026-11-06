package com.example.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultilingualSystemTest {

    @Before
    fun setUp() {
        DynamicTranslationService.clearCache()
        DynamicTranslationService.setProvider(BackendTranslationClient(null)) // offline / unconfigured provider
    }

    @Test
    fun localeHelper_supportsMandatoryMalayalam() {
        assertTrue("Malayalam must be supported", LocaleHelper.isSupported("ml"))
        val lang = LocaleHelper.getLanguageByCode("ml")
        assertEquals("ml", lang.code)
        assertEquals("Malayalam", lang.englishName)
        assertTrue(lang.nativeName.isNotBlank())
        assertFalse("Malayalam is LTR", lang.isRtl)
    }

    @Test
    fun localeHelper_supportsAll12RequiredLanguagesIncludingArabic() {
        val required = listOf("en", "ar", "ml", "hi", "ta", "te", "kn", "bn", "mr", "gu", "pa", "ur")
        for (code in required) {
            assertTrue("Language $code must be supported", LocaleHelper.isSupported(code))
        }
        assertEquals(12, LocaleHelper.SUPPORTED_LANGUAGES.size)
    }

    @Test
    fun localeHelper_placesArabicSecondDirectlyAfterEnglish() {
        val languages = LocaleHelper.SUPPORTED_LANGUAGES
        assertEquals("English must be first", "en", languages[0].code)
        assertEquals("Arabic must be second directly after English", "ar", languages[1].code)
        assertEquals("العربية", languages[1].nativeName)
        assertEquals("Arabic", languages[1].englishName)
        assertTrue("Arabic must be marked RTL", languages[1].isRtl)
    }

    @Test
    fun localeHelper_identifiesUrduAsRtl() {
        val urdu = LocaleHelper.getLanguageByCode("ur")
        assertEquals("ur", urdu.code)
        assertEquals("Urdu", urdu.englishName)
        assertTrue("Urdu must be marked RTL", urdu.isRtl)
    }

    @Test
    fun localeHelper_fallsBackToEnglishForUnknownLocale() {
        val fallback = LocaleHelper.getLanguageByCode("xyz_invalid")
        assertEquals("en", fallback.code)
        assertEquals("English", fallback.nativeName)
    }

    @Test
    fun dynamicTranslation_returnsSourceWhenTargetIsEnglish() = runBlocking {
        val text = "Possible fall detected"
        val result = DynamicTranslationService.translateDynamicText(
            text = text,
            targetLanguage = "en",
            sourceLanguage = "en"
        )
        assertEquals(text, result)
    }

    @Test
    fun dynamicTranslation_safelyFallsBackOfflineWithoutThrowing() = runBlocking {
        val fallback = "Local Fallback Translation"
        val result = DynamicTranslationService.translateDynamicText(
            text = "Possible fall detected",
            targetLanguage = "ml",
            sourceLanguage = "en",
            fallbackText = fallback
        )
        // With no backend configured or network offline, it must return fallback cleanly
        assertEquals(fallback, result)
    }

    @Test
    fun dynamicTranslation_usesMockProviderSuccessfully() = runBlocking {
        val expected = "Mock Malayalam Output"
        val mockProvider = object : DynamicTranslationProvider {
            override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): Result<String> {
                return if (targetLanguage == "ml") {
                    Result.success(expected)
                } else {
                    Result.failure(Exception("Not supported"))
                }
            }
        }
        DynamicTranslationService.setProvider(mockProvider)

        val result = DynamicTranslationService.translateDynamicText(
            text = "Test successful",
            targetLanguage = "ml",
            sourceLanguage = "en",
            fallbackText = "Fallback"
        )
        assertEquals(expected, result)
    }
}

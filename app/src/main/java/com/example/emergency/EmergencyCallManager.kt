package com.example.emergency

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import android.telephony.emergency.EmergencyNumber
import androidx.annotation.RequiresApi
import java.util.Locale

/**
 * Emergency services category supported by SoloSignal AI.
 * Post-fall health triage prioritizes [AMBULANCE] (paramedics/medical emergency).
 */
enum class EmergencyServiceType(
    val displayLabel: String,
    val shortLabel: String,
    val description: String
) {
    AMBULANCE(
        displayLabel = "Ambulance / Medical",
        shortLabel = "Ambulance",
        description = "Paramedics & emergency medical responders"
    ),
    POLICE(
        displayLabel = "Police / Law Enforcement",
        shortLabel = "Police",
        description = "Police & immediate emergency response"
    ),
    FIRE(
        displayLabel = "Fire & Rescue",
        shortLabel = "Fire",
        description = "Fire rescue & hazard containment"
    ),
    GENERAL(
        displayLabel = "Universal Emergency",
        shortLabel = "Emergency",
        description = "Central national emergency dispatch"
    )
}

/**
 * Confidence and detection provenance for the resolved emergency number.
 */
enum class DetectionSource(
    val label: String,
    val badgeColorHex: Long
) {
    ANDROID_TELEPHONY_REGISTRY("Android Emergency Registry", 0xFF0D9488),
    CELLULAR_NETWORK("Current Cellular Network", 0xFF0284C7),
    SIM_CARD("SIM Card Profile", 0xFF0284C7),
    DEVICE_LOCALE("Device Region Settings (Unverified)", 0xFFD97706),
    USER_SELECTED("User Selected Country", 0xFF2563EB),
    MANUAL_ENTRY("Custom Number Entered", 0xFF2563EB)
}

/**
 * Standard country emergency profile storing verified national emergency numbers.
 */
data class CountryEmergencyProfile(
    val countryIso: String,
    val countryName: String,
    val ambulance: String,
    val police: String,
    val fire: String,
    val general: String
) {
    fun getNumberForService(serviceType: EmergencyServiceType): String {
        return when (serviceType) {
            EmergencyServiceType.AMBULANCE -> ambulance.ifBlank { general }
            EmergencyServiceType.POLICE -> police.ifBlank { general }
            EmergencyServiceType.FIRE -> fire.ifBlank { general }
            EmergencyServiceType.GENERAL -> general.ifBlank { ambulance }
        }
    }
}

/**
 * Outcome of emergency number resolution.
 * If [isReliable] is false and [number] is blank, the app MUST ask the user to confirm
 * their country or enter the local number rather than guessing.
 */
data class ResolvedEmergencyNumber(
    val number: String,
    val countryIso: String,
    val countryName: String,
    val serviceType: EmergencyServiceType,
    val detectionSource: DetectionSource,
    val isReliable: Boolean,
    val explanation: String
) {
    val isDetermined: Boolean
        get() = number.isNotBlank()
}

/**
 * Result of attempting to open the Android system phone dialer.
 */
sealed class EmergencyDialerResult {
    data class DialerOpened(val number: String) : EmergencyDialerResult()
    data class Failure(val reason: String, val suggestedNumber: String) : EmergencyDialerResult()
}

/**
 * Emergency calling management for SoloSignal AI.
 *
 * Guarantees:
 * 1. Complies with Android security restrictions: never attempts ACTION_CALL for emergency numbers.
 * 2. Pre-populates ACTION_DIAL with tel:<number> for explicit user review and confirmation.
 * 3. Never claims an emergency call was placed merely because the dialer was opened.
 * 4. Resolves country-specific numbers using priority:
 *    - Android TelephonyManager.getEmergencyNumberList() (API 29+)
 *    - Current cellular network country ISO
 *    - SIM country ISO (if not roaming)
 *    - Maintained country-specific emergency number database
 *    - Explicit user country selection / manual number entry
 * 5. Never guesses 112 or 911 when the country is unknown or unverified.
 * 6. Operates 100% offline without Firebase, AI, or internet connectivity.
 */
object EmergencyCallManager {

    /**
     * Curated, verified database of national emergency numbers across major jurisdictions.
     * Note: UAE specifically uses: Ambulance 998, Police 999, Fire 997.
     */
    val COUNTRY_DATABASE: Map<String, CountryEmergencyProfile> = listOf(
        CountryEmergencyProfile("AE", "United Arab Emirates", ambulance = "998", police = "999", fire = "997", general = "999"),
        CountryEmergencyProfile("US", "United States", ambulance = "911", police = "911", fire = "911", general = "911"),
        CountryEmergencyProfile("CA", "Canada", ambulance = "911", police = "911", fire = "911", general = "911"),
        CountryEmergencyProfile("GB", "United Kingdom", ambulance = "999", police = "999", fire = "999", general = "999"),
        CountryEmergencyProfile("AU", "Australia", ambulance = "000", police = "000", fire = "000", general = "000"),
        CountryEmergencyProfile("NZ", "New Zealand", ambulance = "111", police = "111", fire = "111", general = "111"),
        CountryEmergencyProfile("DE", "Germany", ambulance = "112", police = "110", fire = "112", general = "112"),
        CountryEmergencyProfile("FR", "France", ambulance = "15", police = "17", fire = "18", general = "112"),
        CountryEmergencyProfile("IT", "Italy", ambulance = "118", police = "113", fire = "115", general = "112"),
        CountryEmergencyProfile("ES", "Spain", ambulance = "061", police = "091", fire = "080", general = "112"),
        CountryEmergencyProfile("NL", "Netherlands", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("CH", "Switzerland", ambulance = "144", police = "117", fire = "118", general = "112"),
        CountryEmergencyProfile("AT", "Austria", ambulance = "144", police = "133", fire = "122", general = "112"),
        CountryEmergencyProfile("BE", "Belgium", ambulance = "112", police = "101", fire = "112", general = "112"),
        CountryEmergencyProfile("SE", "Sweden", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("NO", "Norway", ambulance = "113", police = "112", fire = "110", general = "112"),
        CountryEmergencyProfile("DK", "Denmark", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("FI", "Finland", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("IE", "Ireland", ambulance = "999", police = "999", fire = "999", general = "112"),
        CountryEmergencyProfile("PL", "Poland", ambulance = "999", police = "997", fire = "998", general = "112"),
        CountryEmergencyProfile("PT", "Portugal", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("GR", "Greece", ambulance = "166", police = "100", fire = "199", general = "112"),
        CountryEmergencyProfile("IN", "India", ambulance = "108", police = "100", fire = "101", general = "112"),
        CountryEmergencyProfile("JP", "Japan", ambulance = "119", police = "110", fire = "119", general = "119"),
        CountryEmergencyProfile("KR", "South Korea", ambulance = "119", police = "112", fire = "119", general = "119"),
        CountryEmergencyProfile("SG", "Singapore", ambulance = "995", police = "999", fire = "995", general = "995"),
        CountryEmergencyProfile("CN", "China", ambulance = "120", police = "110", fire = "119", general = "120"),
        CountryEmergencyProfile("HK", "Hong Kong", ambulance = "999", police = "999", fire = "999", general = "999"),
        CountryEmergencyProfile("TW", "Taiwan", ambulance = "119", police = "110", fire = "119", general = "119"),
        CountryEmergencyProfile("SA", "Saudi Arabia", ambulance = "997", police = "999", fire = "998", general = "911"),
        CountryEmergencyProfile("QA", "Qatar", ambulance = "999", police = "999", fire = "999", general = "999"),
        CountryEmergencyProfile("KW", "Kuwait", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("BH", "Bahrain", ambulance = "999", police = "999", fire = "999", general = "999"),
        CountryEmergencyProfile("OM", "Oman", ambulance = "9999", police = "9999", fire = "9999", general = "9999"),
        CountryEmergencyProfile("TR", "Turkey", ambulance = "112", police = "112", fire = "112", general = "112"),
        CountryEmergencyProfile("IL", "Israel", ambulance = "101", police = "100", fire = "102", general = "112"),
        CountryEmergencyProfile("ZA", "South Africa", ambulance = "10177", police = "10111", fire = "10177", general = "112"),
        CountryEmergencyProfile("BR", "Brazil", ambulance = "192", police = "190", fire = "193", general = "190"),
        CountryEmergencyProfile("MX", "Mexico", ambulance = "911", police = "911", fire = "911", general = "911"),
        CountryEmergencyProfile("AR", "Argentina", ambulance = "107", police = "911", fire = "100", general = "911"),
        CountryEmergencyProfile("PH", "Philippines", ambulance = "911", police = "911", fire = "911", general = "911"),
        CountryEmergencyProfile("MY", "Malaysia", ambulance = "999", police = "999", fire = "999", general = "999"),
        CountryEmergencyProfile("TH", "Thailand", ambulance = "1669", police = "191", fire = "199", general = "191"),
        CountryEmergencyProfile("ID", "Indonesia", ambulance = "118", police = "110", fire = "113", general = "112"),
        CountryEmergencyProfile("VN", "Vietnam", ambulance = "115", police = "113", fire = "114", general = "115"),
        CountryEmergencyProfile("EG", "Egypt", ambulance = "123", police = "122", fire = "180", general = "122")
    ).associateBy { it.countryIso }

    /**
     * Resolves the verified emergency number based on strict priority rules.
     */
    fun resolveEmergencyNumber(
        context: Context,
        preferredService: EmergencyServiceType = EmergencyServiceType.AMBULANCE,
        userSelectedCountryIso: String? = null,
        userCustomNumber: String? = null
    ): ResolvedEmergencyNumber {
        // Option A: Explicit custom number override by user
        if (!userCustomNumber.isNullOrBlank()) {
            val clean = userCustomNumber.trim()
            val countryName = userSelectedCountryIso?.let { COUNTRY_DATABASE[it.uppercase()]?.countryName }
                ?: "Custom Emergency Number"
            return ResolvedEmergencyNumber(
                number = clean,
                countryIso = userSelectedCountryIso?.uppercase() ?: "CUSTOM",
                countryName = countryName,
                serviceType = preferredService,
                detectionSource = DetectionSource.MANUAL_ENTRY,
                isReliable = true,
                explanation = "Direct user-entered emergency number: $clean"
            )
        }

        // Option B: Explicit country selection by user
        if (!userSelectedCountryIso.isNullOrBlank()) {
            val profile = COUNTRY_DATABASE[userSelectedCountryIso.uppercase()]
            if (profile != null) {
                val num = profile.getNumberForService(preferredService)
                return ResolvedEmergencyNumber(
                    number = num,
                    countryIso = profile.countryIso,
                    countryName = profile.countryName,
                    serviceType = preferredService,
                    detectionSource = DetectionSource.USER_SELECTED,
                    isReliable = true,
                    explanation = "Selected country: ${profile.countryName} ($num)"
                )
            }
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        // Priority 1: Android TelephonyManager.getEmergencyNumberList() (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && telephonyManager != null) {
            val telephonyMatch = resolveFromAndroidTelephonyRegistry(telephonyManager, preferredService)
            if (telephonyMatch != null) {
                return telephonyMatch
            }
        }

        // Priority 2: Current Cellular Network Country ISO (highest priority network context)
        val networkCountryIso = telephonyManager?.networkCountryIso?.trim()?.uppercase().orEmpty()
        if (networkCountryIso.length == 2 && COUNTRY_DATABASE.containsKey(networkCountryIso)) {
            val profile = COUNTRY_DATABASE[networkCountryIso]!!
            val num = profile.getNumberForService(preferredService)
            return ResolvedEmergencyNumber(
                number = num,
                countryIso = profile.countryIso,
                countryName = profile.countryName,
                serviceType = preferredService,
                detectionSource = DetectionSource.CELLULAR_NETWORK,
                isReliable = true,
                explanation = "Connected network: ${profile.countryName} (ISO $networkCountryIso)"
            )
        }

        // Priority 3: SIM Card Country ISO (valid only if NOT roaming, as roaming means device is abroad)
        val isRoaming = telephonyManager?.isNetworkRoaming == true
        val simCountryIso = telephonyManager?.simCountryIso?.trim()?.uppercase().orEmpty()
        if (!isRoaming && simCountryIso.length == 2 && COUNTRY_DATABASE.containsKey(simCountryIso)) {
            val profile = COUNTRY_DATABASE[simCountryIso]!!
            val num = profile.getNumberForService(preferredService)
            return ResolvedEmergencyNumber(
                number = num,
                countryIso = profile.countryIso,
                countryName = profile.countryName,
                serviceType = preferredService,
                detectionSource = DetectionSource.SIM_CARD,
                isReliable = true,
                explanation = "SIM subscription country: ${profile.countryName} (ISO $simCountryIso)"
            )
        }

        // Priority 4: Device Locale region fallback (marked as unverified / requires confirmation)
        val localeCountryIso = Locale.getDefault().country?.trim()?.uppercase().orEmpty()
        if (localeCountryIso.length == 2 && COUNTRY_DATABASE.containsKey(localeCountryIso)) {
            val profile = COUNTRY_DATABASE[localeCountryIso]!!
            val num = profile.getNumberForService(preferredService)
            return ResolvedEmergencyNumber(
                number = num,
                countryIso = profile.countryIso,
                countryName = profile.countryName,
                serviceType = preferredService,
                detectionSource = DetectionSource.DEVICE_LOCALE,
                isReliable = false,
                explanation = "Inferred from device region settings (${profile.countryName}). Please confirm if travelling."
            )
        }

        // Priority 5: Country or number cannot be determined reliably (no SIM, airplane mode, unknown region)
        // Rule: NEVER GUESS. Prompt the user to select country or enter local number.
        return ResolvedEmergencyNumber(
            number = "",
            countryIso = "",
            countryName = "Country Unidentified",
            serviceType = preferredService,
            detectionSource = DetectionSource.USER_SELECTED,
            isReliable = false,
            explanation = "Unable to determine current country. Please select your country or enter the local emergency number."
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveFromAndroidTelephonyRegistry(
        telephonyManager: TelephonyManager,
        preferredService: EmergencyServiceType
    ): ResolvedEmergencyNumber? {
        return try {
            val emergencyNumberMap = telephonyManager.emergencyNumberList
            if (emergencyNumberMap.isNullOrEmpty()) return null

            val allNumbers = emergencyNumberMap.values.flatten()
            if (allNumbers.isEmpty()) return null

            val targetCategory = when (preferredService) {
                EmergencyServiceType.AMBULANCE -> EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_AMBULANCE
                EmergencyServiceType.POLICE -> EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE
                EmergencyServiceType.FIRE -> EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_FIRE_BRIGADE
                EmergencyServiceType.GENERAL -> EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_UNSPECIFIED
            }

            // Find matching category first
            val matchingEntry = allNumbers.firstOrNull { entry ->
                entry.emergencyServiceCategories.contains(targetCategory) &&
                    !entry.number.isNullOrBlank()
            } ?: allNumbers.firstOrNull { !it.number.isNullOrBlank() }

            if (matchingEntry != null && !matchingEntry.number.isNullOrBlank()) {
                val iso = matchingEntry.countryIso?.trim()?.uppercase().orEmpty()
                val profile = COUNTRY_DATABASE[iso]
                val countryName = profile?.countryName
                    ?: if (iso.isNotBlank()) "Country ($iso)" else "Registered Mobile Network"

                ResolvedEmergencyNumber(
                    number = matchingEntry.number,
                    countryIso = iso,
                    countryName = countryName,
                    serviceType = preferredService,
                    detectionSource = DetectionSource.ANDROID_TELEPHONY_REGISTRY,
                    isReliable = true,
                    explanation = "Recognized by Android Telephony Emergency Registry"
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Safely initiates ACTION_DIAL with tel:<number>.
     *
     * IMPORTANT:
     * - Does NOT use ACTION_CALL (forbidden for emergency numbers by Android platform security).
     * - Pre-populates the phone's dialer screen with the given number.
     * - The user must press the Call button on their dialer screen.
     */
    fun openDialerWithEmergencyNumber(context: Context, number: String): EmergencyDialerResult {
        val cleanNumber = number.trim()
        if (cleanNumber.isBlank()) {
            return EmergencyDialerResult.Failure(
                reason = "No emergency number specified. Please enter a valid number.",
                suggestedNumber = ""
            )
        }

        return try {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(cleanNumber)}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Verify a dialer activity is installed/available
            val activities = context.packageManager.queryIntentActivities(dialIntent, 0)
            if (activities.isEmpty()) {
                EmergencyDialerResult.Failure(
                    reason = "No phone dialer application was found on this device. Please call $cleanNumber directly using another phone.",
                    suggestedNumber = cleanNumber
                )
            } else {
                context.startActivity(dialIntent)
                EmergencyDialerResult.DialerOpened(cleanNumber)
            }
        } catch (sec: SecurityException) {
            EmergencyDialerResult.Failure(
                reason = "Device security restricted opening the dialer: ${sec.localizedMessage}. Please dial $cleanNumber manually.",
                suggestedNumber = cleanNumber
            )
        } catch (ex: Exception) {
            EmergencyDialerResult.Failure(
                reason = "Failed to launch phone dialer: ${ex.localizedMessage}. Please dial $cleanNumber manually.",
                suggestedNumber = cleanNumber
            )
        }
    }
}

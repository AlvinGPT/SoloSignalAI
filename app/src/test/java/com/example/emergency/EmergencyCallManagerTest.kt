package com.example.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyCallManagerTest {

    @Test
    fun countryDatabase_containsAccurateUaeNumbers() {
        val uaeProfile = EmergencyCallManager.COUNTRY_DATABASE["AE"]
        assertNotNull("UAE profile must exist in country database", uaeProfile)
        assertEquals("United Arab Emirates", uaeProfile!!.countryName)
        assertEquals("998", uaeProfile.ambulance)
        assertEquals("999", uaeProfile.police)
        assertEquals("997", uaeProfile.fire)
        assertEquals("999", uaeProfile.general)

        assertEquals("998", uaeProfile.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("999", uaeProfile.getNumberForService(EmergencyServiceType.POLICE))
        assertEquals("997", uaeProfile.getNumberForService(EmergencyServiceType.FIRE))
        assertEquals("999", uaeProfile.getNumberForService(EmergencyServiceType.GENERAL))
    }

    @Test
    fun countryDatabase_containsAccurateMajorJurisdictionNumbers() {
        // United States
        val us = EmergencyCallManager.COUNTRY_DATABASE["US"]!!
        assertEquals("911", us.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("911", us.getNumberForService(EmergencyServiceType.POLICE))

        // United Kingdom
        val uk = EmergencyCallManager.COUNTRY_DATABASE["GB"]!!
        assertEquals("999", uk.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("999", uk.getNumberForService(EmergencyServiceType.POLICE))

        // France
        val fr = EmergencyCallManager.COUNTRY_DATABASE["FR"]!!
        assertEquals("15", fr.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("17", fr.getNumberForService(EmergencyServiceType.POLICE))
        assertEquals("18", fr.getNumberForService(EmergencyServiceType.FIRE))

        // Germany
        val de = EmergencyCallManager.COUNTRY_DATABASE["DE"]!!
        assertEquals("112", de.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("110", de.getNumberForService(EmergencyServiceType.POLICE))

        // India
        val inProfile = EmergencyCallManager.COUNTRY_DATABASE["IN"]!!
        assertEquals("108", inProfile.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("100", inProfile.getNumberForService(EmergencyServiceType.POLICE))
        assertEquals("101", inProfile.getNumberForService(EmergencyServiceType.FIRE))

        // Australia
        val au = EmergencyCallManager.COUNTRY_DATABASE["AU"]!!
        assertEquals("000", au.getNumberForService(EmergencyServiceType.AMBULANCE))

        // Japan
        val jp = EmergencyCallManager.COUNTRY_DATABASE["JP"]!!
        assertEquals("119", jp.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("110", jp.getNumberForService(EmergencyServiceType.POLICE))
    }

    @Test
    fun countryEmergencyProfile_handlesMissingServiceGracefully() {
        val testProfile = CountryEmergencyProfile(
            countryIso = "XX",
            countryName = "Testland",
            ambulance = "",
            police = "100",
            fire = "101",
            general = "112"
        )

        // If ambulance is blank, falls back to general
        assertEquals("112", testProfile.getNumberForService(EmergencyServiceType.AMBULANCE))
        assertEquals("100", testProfile.getNumberForService(EmergencyServiceType.POLICE))
        assertEquals("101", testProfile.getNumberForService(EmergencyServiceType.FIRE))
    }

    @Test
    fun emergencyDialerResult_failureRepresentsUnspecifiedNumberGracefully() {
        // Test failure instantiation without calling Android context
        val failure = EmergencyDialerResult.Failure(
            reason = "No emergency number specified.",
            suggestedNumber = ""
        )
        assertEquals("No emergency number specified.", failure.reason)
        assertEquals("", failure.suggestedNumber)
    }

    @Test
    fun resolvedEmergencyNumber_isDeterminedFlagMatchesNumberPresence() {
        val resolvedWithNumber = ResolvedEmergencyNumber(
            number = "998",
            countryIso = "AE",
            countryName = "United Arab Emirates",
            serviceType = EmergencyServiceType.AMBULANCE,
            detectionSource = DetectionSource.CELLULAR_NETWORK,
            isReliable = true,
            explanation = "Connected network"
        )
        assertTrue(resolvedWithNumber.isDetermined)

        val resolvedWithoutNumber = ResolvedEmergencyNumber(
            number = "",
            countryIso = "",
            countryName = "Country Unidentified",
            serviceType = EmergencyServiceType.AMBULANCE,
            detectionSource = DetectionSource.USER_SELECTED,
            isReliable = false,
            explanation = "Unable to determine country"
        )
        assertFalse(resolvedWithoutNumber.isDetermined)
    }
}

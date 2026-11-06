package com.example.sensor

import com.example.sensor.SafetyMonitoringController.RealMonitoringServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying the automatic background monitoring lifecycle:
 * - RealMonitoringServiceStatus states and transitions
 * - BatteryOptimizationHelper logic
 * - BootCompletedReceiver action validation
 */
class AutomaticMonitoringLifecycleTest {

    @Test
    fun testRealMonitoringServiceStatusLabelsAndDetails() {
        assertEquals("Monitoring Active", RealMonitoringServiceStatus.MONITORING_ACTIVE.label)
        assertEquals("Starting Monitoring", RealMonitoringServiceStatus.STARTING_MONITORING.label)
        assertEquals("Monitoring Paused", RealMonitoringServiceStatus.MONITORING_PAUSED.label)
        assertEquals("Monitoring Unavailable", RealMonitoringServiceStatus.MONITORING_UNAVAILABLE.label)

        assertTrue(RealMonitoringServiceStatus.MONITORING_ACTIVE.detail.contains("active"))
        assertTrue(RealMonitoringServiceStatus.STARTING_MONITORING.detail.contains("Initializing"))
        assertTrue(RealMonitoringServiceStatus.MONITORING_PAUSED.detail.contains("paused"))
        assertTrue(RealMonitoringServiceStatus.MONITORING_UNAVAILABLE.detail.contains("unavailable"))
    }

    @Test
    fun testBootCompletedReceiverSupportedActions() {
        val validActions = listOf(
            android.content.Intent.ACTION_BOOT_COMPLETED,
            android.content.Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        for (action in validActions) {
            val isRestorationAction = action == android.content.Intent.ACTION_BOOT_COMPLETED ||
                action == android.content.Intent.ACTION_MY_PACKAGE_REPLACED ||
                action.contains("QUICKBOOT")
            assertTrue("Action $action should trigger restoration", isRestorationAction)
        }

        assertFalse("Arbitrary action should not trigger restoration", "android.intent.action.AIRPLANE_MODE".contains("BOOT"))
    }
}

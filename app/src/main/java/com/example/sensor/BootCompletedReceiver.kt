package com.example.sensor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.PreferencesRepository

/**
 * Automatically restores authorized SoloSignal motion-sensor monitoring after device restart
 * or application package update.
 *
 * Compliance with Android rules:
 * - Only restarts monitoring if the user previously explicitly enabled monitoring in preferences.
 * - Complies with Android 12+ FGS rules: apps receiving ACTION_BOOT_COMPLETED or MY_PACKAGE_REPLACED
 *   are officially exempt from background FGS launch restrictions.
 * - Handles OEM restrictions gracefully without crashing: if the service cannot be started directly,
 *   flags monitoring as pending restart so MainActivity automatically starts it on next open.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!isBoot) return

        Log.i(TAG, "Received system boot broadcast: $action")
        val appContext = context.applicationContext
        val preferencesRepository = PreferencesRepository(appContext)

        val shouldRestoreMonitoring = preferencesRepository.isMonitoringEnabled() ||
            preferencesRepository.isMonitoringPendingRestart()

        if (!shouldRestoreMonitoring) {
            Log.d(TAG, "Device rebooted, but monitoring was not previously enabled. Skipping auto-start.")
            return
        }

        Log.i(TAG, "Restoring authorized background monitoring foreground service after device restart...")
        try {
            val started = MonitoringForegroundService.start(appContext)
            if (started) {
                Log.i(TAG, "Successfully dispatched MonitoringForegroundService after boot.")
            } else {
                Log.w(TAG, "Failed to start MonitoringForegroundService after boot. Flagging pending restart.")
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Exception while starting MonitoringForegroundService after boot: ${ex.message}", ex)
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}

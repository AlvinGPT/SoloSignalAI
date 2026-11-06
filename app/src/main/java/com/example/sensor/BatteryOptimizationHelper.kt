package com.example.sensor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Utility for inspecting and requesting battery optimization whitelist status.
 *
 * Background:
 * Android Doze Mode and OEM battery optimizations (e.g. Samsung, Xiaomi, Huawei)
 * aggressively terminate foreground services and pause sensor listeners unless
 * the application is granted exemption from battery optimizations.
 */
object BatteryOptimizationHelper {

    /**
     * Checks if the app is currently exempt from Android battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Creates an intent to directly prompt the user to whitelist the app from battery optimizations.
     * Falls back to general battery optimization settings if the direct dialog is restricted.
     */
    fun createBatteryOptimizationIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
            }
            try {
                return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } catch (_: Exception) {
            }
        }
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Starts the activity to request battery optimization exemption.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = createBatteryOptimizationIntent(context)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }
}

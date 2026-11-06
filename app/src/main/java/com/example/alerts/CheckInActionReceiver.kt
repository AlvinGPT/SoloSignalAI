package com.example.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.AlertFirestoreRepository
import com.example.sensor.SafetyMonitoringController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification action buttons:
 * - "I'm okay" (cancels check-in)
 * - "Need help now" (sends alert immediately)
 * - "Stop monitoring" (stops background monitoring service)
 * - "I'm checking on them" (recipient acknowledges incoming emergency pop-up alert)
 */
class CheckInActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val controller = SafetyMonitoringController.getInstance(context.applicationContext)
        when (action) {
            ACTION_IM_OKAY -> controller.cancelCheckInByUser("Notification action: I'm okay")
            ACTION_SEND_HELP_NOW -> {
                val pendingResult = goAsync()
                controller.requestImmediateHelp(
                    "Notification action: Need help now",
                    onComplete = {
                        try {
                            pendingResult.finish()
                        } catch (_: Exception) {
                        }
                    }
                )
            }
            ACTION_STOP_MONITORING -> controller.stopMonitoring()
            ACTION_ACKNOWLEDGE_RECIPIENT_ALERT -> {
                val alertId = intent.getStringExtra(EXTRA_ALERT_ID).orEmpty().trim()
                // 1. Immediately stop alarm sound, cancel vibration, and dismiss notification
                AlertDeliveryGateway(context.applicationContext)
                    .stopEmergencyAlarmAndDismissNotification(alertId)
                if (alertId.isNotBlank()) {
                    controller.acknowledgeIncomingAlert(alertId)
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val res = AlertFirestoreRepository(context.applicationContext)
                                .acknowledgeAlertAsRecipient(alertId)
                            if (res.isSuccess) {
                                AlertDeliveryGateway.markPendingFirestoreAcknowledgementSynced(
                                    context.applicationContext,
                                    alertId
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_IM_OKAY = "com.example.solosignal.ACTION_IM_OKAY"
        const val ACTION_SEND_HELP_NOW = "com.example.solosignal.ACTION_SEND_HELP_NOW"
        const val ACTION_STOP_MONITORING = "com.example.solosignal.ACTION_STOP_MONITORING"
        const val ACTION_ACKNOWLEDGE_RECIPIENT_ALERT = "com.example.solosignal.ACTION_ACKNOWLEDGE_RECIPIENT_ALERT"
        const val EXTRA_ALERT_ID = "extra_alert_id"
    }
}

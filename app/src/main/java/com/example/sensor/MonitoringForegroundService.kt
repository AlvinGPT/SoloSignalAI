package com.example.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Log
import com.example.MainActivity
import com.example.R
import com.example.alerts.CheckInActionReceiver
import com.example.data.PreferencesRepository
import com.example.domain.MonitoringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that keeps SoloSignal motion-sensor monitoring active in the background
 * while explicitly enabled by the user, and displays a truthful persistent notification
 * with a direct "Stop monitoring" action. When SOS Audio Recording is enabled and
 * RECORD_AUDIO permission is granted, also includes FOREGROUND_SERVICE_TYPE_MICROPHONE
 * and discloses the 15-second in-memory rolling audio buffer.
 */
class MonitoringForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        Log.i(TAG, "MonitoringForegroundService created")
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        Log.i(TAG, "MonitoringForegroundService destroyed")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isServiceRunning.value = true
        if (intent?.action == ACTION_STOP_MONITORING_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _isServiceRunning.value = false
            return START_NOT_STICKY
        }

        val prefsRepo = PreferencesRepository(applicationContext)
        val isMonitoringAuthorized = prefsRepo.isMonitoringEnabled()

        // If system recreated service (intent == null) but user has disabled monitoring, do not restart
        if (intent == null && !isMonitoringAuthorized) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            _isServiceRunning.value = false
            return START_NOT_STICKY
        }

        val controller = SafetyMonitoringController.getInstance(applicationContext)
        controller.ensureAuthenticatedSessionInitialized()
        if (intent?.action != ACTION_ENSURE_ALERT_LISTENER &&
            (isMonitoringAuthorized || intent != null) &&
            controller.monitoringState.value == MonitoringState.STOPPED
        ) {
            controller.startMonitoring(fromUserExplicitAction = false)
        }

        val isFallMonitoringActive = controller.monitoringState.value != MonitoringState.STOPPED
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val isSosAudioBufferActive = isFallMonitoringActive &&
            hasMicPerm &&
            controller.isSosAudioMonitoringActive()

        ensureNotificationChannel()
        val notification = buildPersistentNotification(
            isFallMonitoringActive = isFallMonitoringActive,
            isSosAudioBufferActive = isSosAudioBufferActive
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val fgsType = if (isSosAudioBufferActive) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                try {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } catch (_: Exception) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isSosAudioBufferActive) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }

        return START_STICKY
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SoloSignal Active Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent status indicator shown while background motion screening, 15s SOS audio buffer, and emergency alert reception are active."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(
        isFallMonitoringActive: Boolean = true,
        isSosAudioBufferActive: Boolean = false
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, CheckInActionReceiver::class.java).apply {
            action = CheckInActionReceiver.ACTION_STOP_MONITORING
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            204,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            isSosAudioBufferActive -> "SoloSignal monitoring + 15s SOS audio buffer active"
            isFallMonitoringActive -> "SoloSignal monitoring is active"
            else -> "SoloSignal emergency alert listener active"
        }
        val body = when {
            isSosAudioBufferActive -> "Motion screening & 15s in-memory rolling audio buffer active. Audio is overwritten continuously unless an SOS is confirmed."
            isFallMonitoringActive -> "Background motion monitoring and emergency alert reception are active."
            else -> "Ready to display immediate emergency alerts from your linked contacts."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_solosignal_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_solosignal_logo,
                "Stop monitoring",
                stopPendingIntent
            )
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "MonitoringFGS"
        const val CHANNEL_ID = "solosignal_monitoring_service_channel"
        const val NOTIFICATION_ID = 4101
        const val ACTION_STOP_MONITORING_SERVICE = "com.example.sensor.STOP_MONITORING_SERVICE"
        const val ACTION_ENSURE_ALERT_LISTENER = "com.example.sensor.ENSURE_ALERT_LISTENER"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun start(context: Context): Boolean = startServiceSafely(context)

        fun startAlertListenerOnly(context: Context): Boolean =
            startServiceSafely(context, ACTION_ENSURE_ALERT_LISTENER)

        fun stop(context: Context) = stopServiceSafely(context)

        fun startServiceSafely(context: Context, action: String? = null): Boolean {
            return try {
                val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                    if (!action.isNullOrBlank()) {
                        this.action = action
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun stopServiceSafely(context: Context) {
            try {
                val intent = Intent(context, MonitoringForegroundService::class.java).apply {
                    action = ACTION_STOP_MONITORING_SERVICE
                }
                context.stopService(intent)
            } catch (_: Exception) {
                // Ignore if service was not running
            }
        }
    }
}

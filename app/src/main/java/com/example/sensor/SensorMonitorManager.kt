package com.example.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.domain.MotionSample

data class HardwareSensorStatus(
    val hasAccelerometer: Boolean,
    val accelerometerName: String,
    val hasLinearAcceleration: Boolean,
    val hasRotationVector: Boolean,
    val hasGyroscope: Boolean
)

class SensorMonitorManager(
    context: Context,
    private val onRealSampleReceived: (MotionSample) -> Unit
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val linearAcceleration: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val hasAccelerometer: Boolean
        get() = accelerometer != null

    val accelerometerMaxRange: Float?
        get() = accelerometer?.maximumRange

    val hasGyroscope: Boolean
        get() = gyroscope != null

    private var isListenerRegistered = false
    private var lastSampleTimestampNanos = 0L
    private var lastRealSampleWallClockMillis = 0L
    private var latestGxRadPerSec = 0f
    private var latestGyRadPerSec = 0f
    private var latestGzRadPerSec = 0f
    private var latestGyroTimestampNanos = 0L

    fun inspectSensorAvailability(): HardwareSensorStatus {
        return HardwareSensorStatus(
            hasAccelerometer = accelerometer != null,
            accelerometerName = accelerometer?.name ?: "No hardware accelerometer reported",
            hasLinearAcceleration = linearAcceleration != null,
            hasRotationVector = rotationVector != null,
            hasGyroscope = gyroscope != null
        )
    }

    fun startListening(): Boolean {
        val manager = sensorManager ?: return false
        val accel = accelerometer ?: return false
        if (isListenerRegistered) return true

        val registered = manager.registerListener(
            this,
            accel,
            SensorManager.SENSOR_DELAY_GAME
        )
        if (registered && gyroscope != null) {
            manager.registerListener(
                this,
                gyroscope,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        isListenerRegistered = registered
        if (registered) {
            MonitoringForegroundService.startServiceSafely(appContext)
        }
        return registered
    }

    fun stopListening() {
        if (isListenerRegistered) {
            sensorManager?.unregisterListener(this)
            isListenerRegistered = false
        }
        lastSampleTimestampNanos = 0L
        lastRealSampleWallClockMillis = 0L
        latestGxRadPerSec = 0f
        latestGyRadPerSec = 0f
        latestGzRadPerSec = 0f
        latestGyroTimestampNanos = 0L
        MonitoringForegroundService.stopServiceSafely(appContext)
    }

    fun isActivelyListening(): Boolean = isListenerRegistered

    fun isActivelyProducingReadings(): Boolean {
        if (!isListenerRegistered) return false
        if (lastRealSampleWallClockMillis == 0L) return false
        return (System.currentTimeMillis() - lastRealSampleWallClockMillis) < 3_000L
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val sensorEvent = event ?: return
        if (sensorEvent.values.size < 3) return

        if (sensorEvent.sensor.type == Sensor.TYPE_GYROSCOPE) {
            latestGxRadPerSec = sensorEvent.values[0]
            latestGyRadPerSec = sensorEvent.values[1]
            latestGzRadPerSec = sensorEvent.values[2]
            latestGyroTimestampNanos = sensorEvent.timestamp
            return
        }

        if (sensorEvent.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // Target ~50 Hz sampling (20 ms interval; throttle bursts < 15 ms)
        if (lastSampleTimestampNanos != 0L &&
            (sensorEvent.timestamp - lastSampleTimestampNanos) in 1 until 15_000_000L
        ) {
            return
        }
        lastSampleTimestampNanos = sensorEvent.timestamp
        lastRealSampleWallClockMillis = System.currentTimeMillis()

        val gyroFresh = latestGyroTimestampNanos != 0L &&
            kotlin.math.abs(sensorEvent.timestamp - latestGyroTimestampNanos) <= 250_000_000L

        val sample = MotionSample(
            timestampNanos = sensorEvent.timestamp,
            ax = sensorEvent.values[0],
            ay = sensorEvent.values[1],
            az = sensorEvent.values[2],
            sensorType = if (gyroFresh) {
                "TYPE_ACCELEROMETER + TYPE_GYROSCOPE"
            } else {
                "TYPE_ACCELEROMETER (includes gravity)"
            },
            isSynthetic = false,
            gx = if (gyroFresh) latestGxRadPerSec else 0f,
            gy = if (gyroFresh) latestGyRadPerSec else 0f,
            gz = if (gyroFresh) latestGzRadPerSec else 0f,
            hasGyroscope = gyroFresh
        )
        onRealSampleReceived(sample)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}

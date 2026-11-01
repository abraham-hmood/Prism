package com.prism.launcher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Fires [onShake] once per physical shake gesture, using a gravity-subtracted acceleration
 * magnitude threshold with a cooldown so a single shake can't fire twice.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /**
     * Whether this device can detect a shake at all.
     *
     * A TV or a headless box has no accelerometer, and a screen whose only exit is a gesture the
     * hardware cannot make is a trap. Callers that lock something behind a shake must offer another
     * way out when this is false.
     */
    val isAvailable: Boolean get() = accelerometer != null

    private var lastShakeTime = 0L

    fun start() {
        accelerometer?.let {
            // UI rate, not GAME rate. A shake is a large, slow gesture; sampling at 50 Hz for
            // every moment the launcher is open costs battery and buys nothing a 16 Hz sample
            // cannot detect.
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gX = event.values[0] / SensorManager.GRAVITY_EARTH
        val gY = event.values[1] / SensorManager.GRAVITY_EARTH
        val gZ = event.values[2] / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return
            lastShakeTime = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val SHAKE_THRESHOLD_GRAVITY = 2.7f
        private const val SHAKE_COOLDOWN_MS = 1200L
    }
}

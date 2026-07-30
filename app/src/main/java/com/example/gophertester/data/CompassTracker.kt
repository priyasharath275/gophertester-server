package com.example.gophertester.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

data class CompassReading(
    val azimuthDeg: Float,          // 0..360 magnetic heading
    val accuracyStatus: Int? = null // SensorManager.SENSOR_STATUS_*
)

class CompassTracker(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val latest = MutableStateFlow<CompassReading?>(null)

    private val accelVals = FloatArray(3)
    private val magVals = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false

    fun start() {
        if (rot != null) {
            sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME)
        } else {
            acc?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val R = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(R, e.values)
                val o = FloatArray(3)
                SensorManager.getOrientation(R, o)
                var deg = Math.toDegrees(o[0].toDouble()).toFloat()
                if (deg < 0f) deg += 360f
                latest.value = CompassReading(deg, e.accuracy)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(e.values, 0, accelVals, 0, 3); haveAccel = true; maybeFromAccelMag()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, magVals, 0, 3); haveMag = true; maybeFromAccelMag()
            }
        }
    }

    private fun maybeFromAccelMag() {
        if (!haveAccel || !haveMag) return
        val R = FloatArray(9)
        val I = FloatArray(9)
        if (SensorManager.getRotationMatrix(R, I, accelVals, magVals)) {
            val o = FloatArray(3)
            SensorManager.getOrientation(R, o)
            var deg = Math.toDegrees(o[0].toDouble()).toFloat()
            if (deg < 0f) deg += 360f
            latest.value = CompassReading(deg, null)
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
}

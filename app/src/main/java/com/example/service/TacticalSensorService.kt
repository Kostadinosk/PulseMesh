package com.example.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.*

data class TacticalSensorState(
    val deviceHeadingDeg: Float = 0f,
    val barometricPressureHpa: Float? = null,
    val barometricAltitudeMeters: Double? = null,
    val hasCompassSensor: Boolean = false,
    val hasBarometerSensor: Boolean = false,
    val stepCount: Int = 0,
    val hasStepSensor: Boolean = false
)

/**
 * Live Compass Heading (`Sensor.TYPE_ROTATION_VECTOR` / Accelerometer + Magnetometer fallback)
 * and Barometric Altitude (`Sensor.TYPE_PRESSURE`) Tracker.
 */
class TacticalSensorService(context: Context) : SensorEventListener {

    companion object {
        /**
         * Computes the initial forward compass bearing (0..360 degrees clockwise from True North)
         * from `(lat1, lon1)` to `(lat2, lon2)`.
         */
        fun calculateBearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaLon = Math.toRadians(lon2 - lon1)

            val y = sin(deltaLon) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLon)
            val thetaDeg = Math.toDegrees(atan2(y, x))
            return (((thetaDeg + 360.0) % 360.0)).toFloat()
        }

        /**
         * Computes the relative bearing (0..360 degrees relative to the phone's current heading).
         */
        fun calculateRelativeBearingDegrees(absoluteBearingDeg: Float, deviceHeadingDeg: Float): Float {
            return (((absoluteBearingDeg - deviceHeadingDeg) % 360f) + 360f) % 360f
        }

        /**
         * Returns a directional arrow (`↑`, `↗`, `→`, `↘`, `↓`, `↙`, `←`, `↖`) for a relative bearing.
         */
        fun bearingToCompassArrow(relativeBearingDeg: Float): String {
            val norm = ((relativeBearingDeg % 360f) + 360f) % 360f
            return when {
                norm >= 337.5f || norm < 22.5f -> "↑"
                norm < 67.5f -> "↗"
                norm < 112.5f -> "→"
                norm < 157.5f -> "↘"
                norm < 202.5f -> "↓"
                norm < 247.5f -> "↙"
                norm < 292.5f -> "←"
                else -> "↖"
            }
        }

        /**
         * Returns the 8-wind cardinal direction (`N`, `NE`, `E`, `SE`, `S`, `SW`, `W`, `NW`).
         */
        fun bearingToCardinal(bearingDeg: Float): String {
            val norm = ((bearingDeg % 360f) + 360f) % 360f
            return when {
                norm >= 337.5f || norm < 22.5f -> "N"
                norm < 67.5f -> "NE"
                norm < 112.5f -> "E"
                norm < 157.5f -> "SE"
                norm < 202.5f -> "S"
                norm < 247.5f -> "SW"
                norm < 292.5f -> "W"
                else -> "NW"
            }
        }

        /**
         * Converts barometric pressure in hPa (millibars) to meters Above Sea Level (ASL)
         * using the standard international barometric formula.
         */
        fun pressureHpaToAltitudeMeters(pressureHpa: Float): Double {
            if (pressureHpa <= 0f) return 0.0
            return SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa).toDouble()
        }

        /**
         * Dead-Reckoning Step Advance:
         * Given current `(lat, lon)`, compass heading in degrees (0..360 clockwise from North),
         * and stride length in meters (default `0.74m`), returns the new `(lat, lon)` after 1 step.
         */
        fun stepAdvanceLatLon(
            lat: Double,
            lon: Double,
            headingDeg: Float,
            strideMeters: Double = 0.74
        ): Pair<Double, Double> {
            val earthRadiusM = 6378137.0
            val headingRad = Math.toRadians(headingDeg.toDouble())
            val dNorth = strideMeters * cos(headingRad)
            val dEast = strideMeters * sin(headingRad)
            val dLat = Math.toDegrees(dNorth / earthRadiusM)
            val cosLat = cos(Math.toRadians(lat.coerceIn(-85.0, 85.0))).coerceAtLeast(0.01)
            val dLon = Math.toDegrees(dEast / (earthRadiusM * cosLat))
            return Pair((lat + dLat).coerceIn(-85.0511, 85.0511), ((lon + dLon + 540.0) % 360.0) - 180.0)
        }
    }

    var onStepDetected: ((headingDeg: Float) -> Unit)? = null

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val pressureSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val stepDetectorSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private var lastAccelStepMs: Long = 0L

    private val _sensorState = MutableStateFlow(
        TacticalSensorState(
            hasCompassSensor = rotationVectorSensor != null || (accelSensor != null && magSensor != null),
            hasBarometerSensor = pressureSensor != null,
            hasStepSensor = stepDetectorSensor != null || accelSensor != null
        )
    )
    val sensorState: StateFlow<TacticalSensorState> = _sensorState.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val gravityReadings = FloatArray(3)
    private val geomagneticReadings = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var isRegistered = false

    fun start() {
        val mgr = sensorManager ?: return
        if (isRegistered) return
        runCatching {
            if (rotationVectorSensor != null) {
                mgr.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
            } else {
                accelSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
                magSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
            pressureSensor?.let {
                mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            stepDetectorSensor?.let {
                mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            if (stepDetectorSensor == null && rotationVectorSensor != null) {
                accelSensor?.let { mgr.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            }
            isRegistered = true
        }
    }

    fun stop() {
        val mgr = sensorManager ?: return
        if (!isRegistered) return
        runCatching {
            mgr.unregisterListener(this)
            isRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                runCatching {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    val azimuthDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
                    _sensorState.update { it.copy(deviceHeadingDeg = azimuthDeg) }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravityReadings, 0, minOf(3, event.values.size))
                hasGravity = true
                updateHeadingFromAccelMag()
                if (stepDetectorSensor == null && event.values.size >= 3) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    val mag = sqrt(ax * ax + ay * ay + az * az)
                    val now = System.currentTimeMillis()
                    if (mag > 13.2f && now - lastAccelStepMs > 380L) {
                        lastAccelStepMs = now
                        recordStepEvent()
                    }
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                recordStepEvent()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagneticReadings, 0, minOf(3, event.values.size))
                hasGeomagnetic = true
                updateHeadingFromAccelMag()
            }
            Sensor.TYPE_PRESSURE -> {
                val hPa = event.values.firstOrNull() ?: return
                if (hPa in 300f..1150f) {
                    val altMeters = pressureHpaToAltitudeMeters(hPa)
                    _sensorState.update {
                        it.copy(
                            barometricPressureHpa = hPa,
                            barometricAltitudeMeters = altMeters
                        )
                    }
                }
            }
        }
    }

    private fun updateHeadingFromAccelMag() {
        if (!hasGravity || !hasGeomagnetic) return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravityReadings, geomagneticReadings)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            val azimuthDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
            _sensorState.update { it.copy(deviceHeadingDeg = azimuthDeg) }
        }
    }

    private fun recordStepEvent() {
        val heading = _sensorState.value.deviceHeadingDeg
        _sensorState.update { it.copy(stepCount = it.stepCount + 1) }
        onStepDetected?.invoke(heading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

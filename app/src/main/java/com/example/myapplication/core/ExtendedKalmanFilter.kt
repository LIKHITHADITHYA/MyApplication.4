package com.example.myapplication.core

import android.location.Location
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ExtendedKalmanFilter {
    // State vector: [latitude, longitude, velocityNorth, velocityEast]
    private var x: Matrix = Matrix(4, 1) // Initialized to zeros
    // Covariance matrix
    private var P: Matrix = Matrix.identity(4) // Initial uncertainty
    // Process noise covariance (tune these based on system dynamics)
    private var Q: Matrix = Matrix(4, 4).apply {
        this[0, 0] = 0.0001 // Latitude noise
        this[1, 1] = 0.0001 // Longitude noise
        this[2, 2] = 0.01   // Velocity North noise
        this[3, 3] = 0.01   // Velocity East noise
    }
    // Measurement noise covariance (tune based on GPS accuracy)
    private var R: Matrix = Matrix(2, 2).apply {
        this[0,0] = 10.0 // Latitude measurement noise
        this[1,1] = 10.0 // Longitude measurement noise
    }

    private var lastTimestamp: Long = 0L
    private var isInitialized = false

    companion object {
        private const val NS2S = 1.0 / 1_000_000_000.0 // Nanoseconds to seconds
    }

    fun predict(acceleration: FloatArray, gyroscope: FloatArray, timestamp: Long) {
        if (!isInitialized) {
            // Log.d("EKF", "Predict called before initialization")
            return
        }

        val dt = (timestamp - lastTimestamp) * NS2S
        if (dt <= 0) { // Ensure time has progressed
            // Log.d("EKF", "dt is zero or negative in predict: $dt")
            return
        }
        lastTimestamp = timestamp

        val accelN = if (acceleration.size > 0) acceleration[0].toDouble() else 0.0
        val accelE = if (acceleration.size > 1) acceleration[1].toDouble() else 0.0

        val oldLat = x[0, 0]
        val oldLon = x[1, 0]
        val oldVelN = x[2, 0]
        val oldVelE = x[3, 0]

        x[0, 0] = oldLat + oldVelN * dt + 0.5 * accelN * dt * dt
        x[1, 0] = oldLon + oldVelE * dt + 0.5 * accelE * dt * dt
        x[2, 0] = oldVelN + accelN * dt
        x[3, 0] = oldVelE + accelE * dt

        val F = Matrix.identity(4)
        F[0, 2] = dt
        F[1, 3] = dt

        P = F * P * F.transpose() + Q
        // Log.d("EKF", "Predicted state: ${x.transpose()}, P: $P")
    }

    fun update(location: Location) {
        if (!isInitialized) {
            x[0, 0] = location.latitude
            x[1, 0] = location.longitude
            if (location.hasSpeed() && location.hasBearing()) {
                val speed = location.speed.toDouble()
                val bearingRad = Math.toRadians(location.bearing.toDouble())
                x[2, 0] = speed * cos(bearingRad) // Velocity North
                x[3, 0] = speed * sin(bearingRad) // Velocity East
            } else {
                x[2, 0] = 0.0
                x[3, 0] = 0.0
            }
            lastTimestamp = location.time 
            isInitialized = true
            // Log.d("EKF", "Initialized with location: Lat=${x[0,0]}, Lon=${x[1,0]}, VelN=${x[2,0]}, VelE=${x[3,0]}")
            return
        }

        val z = Matrix(2, 1)
        z[0, 0] = location.latitude
        z[1, 0] = location.longitude

        val H = Matrix(2, 4)
        H[0, 0] = 1.0 
        H[1, 1] = 1.0 

        val y = z - (H * x)
        val S = H * P * H.transpose() + R
        val K = P * H.transpose() * S.inverse()
        x += (K * y)
        P = (Matrix.identity(4) - (K * H)) * P
        lastTimestamp = location.time
        // Log.d("EKF", "Updated state: ${x.transpose()}, P: $P")
    }

    fun getLocation(): Location {
        val fusedLocation = Location("EKF")
        if (!isInitialized) {
            fusedLocation.time = System.currentTimeMillis()
            return fusedLocation
        }
        fusedLocation.latitude = x[0, 0]
        fusedLocation.longitude = x[1, 0]

        val velocityN = x[2, 0]
        val velocityE = x[3, 0]

        fusedLocation.speed = sqrt(velocityN * velocityN + velocityE * velocityE).toFloat()

        var bearingDegrees = Math.toDegrees(atan2(velocityE, velocityN))
        if (bearingDegrees < 0) {
            bearingDegrees += 360.0
        }
        fusedLocation.bearing = bearingDegrees.toFloat()
        fusedLocation.time = lastTimestamp 

        // Log.d("EKF", "GetLocation: Lat=${fusedLocation.latitude}, Lon=${fusedLocation.longitude}, Speed=${fusedLocation.speed}, Bearing=${fusedLocation.bearing}")
        return fusedLocation
    }

    fun isInitialized(): Boolean = isInitialized
}

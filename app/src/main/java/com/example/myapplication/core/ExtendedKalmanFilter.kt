package com.example.myapplication.core

import android.location.Location

class ExtendedKalmanFilter {
    // State vector: [latitude, longitude, velocityNorth, velocityEast]
    private var x: Matrix = Matrix(4, 1)
    // Covariance matrix
    private var P: Matrix = Matrix.identity(4)
    // Process noise covariance
    private var Q: Matrix = Matrix.identity(4)
    // Measurement noise covariance
    private var R: Matrix = Matrix.identity(2)
    private var lastTimestamp: Long = 0L

    fun predict(acceleration: FloatArray, gyroscope: FloatArray, timestamp: Long) {
        if (lastTimestamp == 0L) {
            lastTimestamp = timestamp
            return
        }

        val dt = (timestamp - lastTimestamp) * NS2S
        lastTimestamp = timestamp

        // State transition matrix (simplified)
        val F = Matrix.identity(4)
        F[0, 2] = dt
        F[1, 3] = dt

        // Update state
        x = F * x

        // Update covariance
        P = F * P * F.transpose() + Q
    }

    fun update(location: Location) {
        // Measurement matrix
        val H = Matrix(2, 4)
        H[0, 0] = 1.0
        H[1, 1] = 1.0

        // Measurement residual
        val z = Matrix(2, 1)
        z[0, 0] = location.latitude
        z[1, 0] = location.longitude
        val y = z - (H * x)

        // Innovation covariance
        val S = H * P * H.transpose() + R
        
        // Kalman gain
        val K = P * H.transpose() * S.inverse()

        // Update state
        x += K * y

        // Update covariance
        P = (Matrix.identity(4) - (K * H)) * P
    }

    fun getLocation(): Location {
        val location = Location("EKF")
        location.latitude = x[0, 0]
        location.longitude = x[1, 0]
        return location
    }

    companion object {
        private const val NS2S = 1.0 / 1000000000.0 // Changed from 1.0f to 1.0
    }
}

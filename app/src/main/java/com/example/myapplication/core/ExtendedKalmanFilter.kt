package com.example.myapplication.core

import android.util.Log
import com.example.myapplication.data.VehicleState
import org.ejml.simple.SimpleMatrix

class ExtendedKalmanFilter {
    private var x: SimpleMatrix // State vector [lat, lon, speed, bearing]
    private var P: SimpleMatrix // Covariance matrix
    private var Q: SimpleMatrix // Process noise covariance
    private var R: SimpleMatrix // Measurement noise covariance
    private var H: SimpleMatrix // Measurement matrix
    private var lastTimestamp: Long = 0
    private var initialized = false

    private companion object {
        private const val TAG = "ExtendedKalmanFilter"
    }

    init {
        // Initial state: [lat, lon, speed, bearing]
        x = SimpleMatrix(4, 1)
        P = SimpleMatrix.diag(1.0, 1.0, 1.0, 1.0)       // Initial uncertainty
        Q = SimpleMatrix.diag(0.1, 0.1, 0.5, 0.1)      // Process noise: tuned for typical vehicle dynamics
        R = SimpleMatrix.diag(10.0, 10.0, 1.0, 10.0)     // Measurement noise: GPS typical errors (lat/lon in meters, speed m/s, bearing degrees)
        
        // Measurement matrix: maps state to measurement [lat, lon, speed, bearing]
        H = SimpleMatrix.identity(4)
    }

    fun isInitialized(): Boolean = initialized

    fun predict(accelerometerData: FloatArray, gyroscopeData: FloatArray, timestamp: Long) {
        if (!initialized) {
            Log.d(TAG, "Predict call skipped: EKF not initialized with a GPS location yet.")
            return
        }

        val dt = (timestamp - this.lastTimestamp) / 1000.0 // Delta time in seconds
        if (dt <= 0) {
            Log.w(TAG, "Predict call skipped: dt is zero or negative ($dt s). Current timestamp: $timestamp, last: ${this.lastTimestamp}")
            return // Avoid division by zero or negative time
        }

        val ax = accelerometerData[0] // Assuming ax is longitudinal acceleration (m/s^2)
        // val ay = accelerometerData[1] // Lateral acceleration (not used in this simplified model)
        // val omega = gyroscopeData[2]    // Yaw rate (rad/s), for bearing prediction

        // State transition matrix A
        val A = SimpleMatrix.identity(4)
        A.set(0, 2, dt) // lat = lat + speed * dt (simplified, should be speed * cos(bearing) * dt / R_earth for actual distance)
        A.set(1, 2, dt) // lon = lon + speed * dt (simplified, should be speed * sin(bearing) * dt / (R_earth * cos(lat)) for actual distance)
        // Speed is predicted as constant (x.set(2,0, x.get(2,0) + ax*dt) removed due to noise)
        // Bearing is predicted as constant (can be enhanced with gyroscope: x.set(3,0, x.get(3,0) + omega*dt) );

        // Predict state: x_pred = A * x
        x = A.mult(x)
        
        // Predict covariance: P_pred = A * P * A^T + Q
        P = A.mult(P).mult(A.transpose()).plus(Q)

        // No need to update lastTimestamp here as predict is driven by sensor timestamps,
        // and 'dt' for GPS update should be relative to the *last GPS timestamp*.
        // this.lastTimestamp = timestamp // This would make dt for GPS update very small if sensors are faster.
        Log.d(TAG, "Predict: dt=$dt, ax=$ax. New predicted state x: [${x.get(0,0)}, ${x.get(1,0)}, ${x.get(2,0)}, ${x.get(3,0)}]")
    }

    fun update(vehicleState: VehicleState) {
        val currentTime = vehicleState.timestamp
        if (!initialized) {
            Log.d(TAG, "Initializing EKF with first GPS location.")
            x.set(0, 0, vehicleState.latitude)
            x.set(1, 0, vehicleState.longitude)
            x.set(2, 0, vehicleState.speed)
            x.set(3, 0, vehicleState.bearing)
            P = SimpleMatrix.diag(1.0, 1.0, 1.0, 1.0) // Reset covariance for initial fix
            this.lastTimestamp = currentTime
            initialized = true
            return
        }

        val dt = (currentTime - this.lastTimestamp) / 1000.0 // Delta time in seconds
        if (dt < 0) {
             Log.w(TAG, "Update call received with timestamp older than last update. Skipping. Current: $currentTime, Last: ${this.lastTimestamp}")
            return // Stale update
        }
        // If dt is very large, it might indicate GPS outage. Consider resetting P or increasing Q temporarily.
        if (dt > 10.0) { // e.g., if more than 10s since last GPS update
            Log.w(TAG, "Large dt ($dt s) since last GPS update. Process noise Q might be too small.")
            // Optionally, increase process noise Q here to reflect higher uncertainty
            // P = P.plus(Q.scale(dt)) // Example: Scale Q by dt or a factor of dt
        }


        // Measurement vector z
        val z = SimpleMatrix(4, 1)
        z.set(0, 0, vehicleState.latitude)
        z.set(1, 0, vehicleState.longitude)
        z.set(2, 0, vehicleState.speed)
        z.set(3, 0, vehicleState.bearing)

        // Measurement residual (innovation): y = z - H * x
        val y = z.minus(H.mult(x))

        // Innovation covariance: S = H * P * H^T + R
        val S = H.mult(P).mult(H.transpose()).plus(R)

        // Kalman gain: K = P * H^T * S^-1
        val K = P.mult(H.transpose()).mult(S.invert())

        // Update state estimate: x_new = x + K * y
        x = x.plus(K.mult(y))

        // Update covariance estimate: P_new = (I - K * H) * P
        val I = SimpleMatrix.identity(x.numRows())
        P = (I.minus(K.mult(H))).mult(P)

        this.lastTimestamp = currentTime
        Log.d(TAG, "Update: dt=$dt. New updated state x: [${x.get(0,0)}, ${x.get(1,0)}, ${x.get(2,0)}, ${x.get(3,0)}]")
    }

    fun getState(): VehicleState {
        return VehicleState(
            latitude = x.get(0, 0),
            longitude = x.get(1, 0),
            speed = x.get(2, 0),
            bearing = x.get(3, 0),
            timestamp = this.lastTimestamp
        )
    }
}
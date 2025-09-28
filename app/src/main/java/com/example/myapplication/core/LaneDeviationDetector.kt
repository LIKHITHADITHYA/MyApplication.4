package com.example.myapplication.core

import android.content.Context
import android.util.Log
import com.example.myapplication.util.AppPreferences
import kotlin.math.abs

class LaneDeviationDetector(context: Context) {

    private val TAG = "LaneDeviationDetector"

    private val lateralAccelerationThreshold: Float = AppPreferences.getLaneDevAccThreshold(context)
    private val durationThresholdMs: Int = AppPreferences.getLaneDevDurationMs(context)
    private val MIN_EVENT_INTERVAL_MS = 3000 // To prevent immediate re-triggering

    private var potentialDeviationStartTime: Long = 0
    private var lastDeviationEventTime: Long = 0

    fun updateAccelerationData(accelerometerData: FloatArray, timestamp: Long): Boolean {
        if (accelerometerData.size < 2) return false

        val lateralAcceleration = accelerometerData[1]
        val currentTime = System.currentTimeMillis() // For event interval

        if (abs(lateralAcceleration) > lateralAccelerationThreshold) {
            if (potentialDeviationStartTime == 0L) {
                potentialDeviationStartTime = timestamp // Sensor event timestamp for duration check
            }
        } else {
            potentialDeviationStartTime = 0L
        }

        if (potentialDeviationStartTime > 0 && (timestamp - potentialDeviationStartTime) > durationThresholdMs) {
            if ((currentTime - lastDeviationEventTime) > MIN_EVENT_INTERVAL_MS) {
                Log.d(TAG, "Lane deviation detected! Lat Acc: $lateralAcceleration (Thresh: $lateralAccelerationThreshold, Dur: $durationThresholdMs ms)")
                potentialDeviationStartTime = 0L
                lastDeviationEventTime = currentTime
                return true
            }
        }
        return false
    }
}

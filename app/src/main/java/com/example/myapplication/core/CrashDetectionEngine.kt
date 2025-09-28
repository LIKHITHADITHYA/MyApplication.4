package com.example.myapplication.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.myapplication.util.AppPreferences // Assuming KEY_CRASH_THRESHOLD_G is accessible
import kotlin.math.sqrt

class CrashDetectionEngine(private val context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val TAG = "CrashDetectionEngine"
    private val prefs: SharedPreferences = context.getSharedPreferences("com.example.myapplication.prefs", Context.MODE_PRIVATE)

    private var crashThresholdGForce: Float

    private val MIN_EVENT_INTERVAL_MS = 10000 // 10 seconds
    private var lastCrashEventTime: Long = 0

    init {
        // Initialize threshold from preferences
        crashThresholdGForce = AppPreferences.getCrashThresholdG(context)
        // Register listener for preference changes
        prefs.registerOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Initialized with crash threshold: $crashThresholdGForce G")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "crash_threshold_g") { // Using the actual key string from AppPreferences
            crashThresholdGForce = AppPreferences.getCrashThresholdG(context) // Re-fetch the typed value
            Log.i(TAG, "Crash threshold preference changed. New threshold: $crashThresholdGForce G")
        }
    }

    fun updateAccelerationData(accelerometerData: FloatArray, timestamp: Long): Boolean {
        val x = accelerometerData[0]
        val y = accelerometerData[1]
        val z = accelerometerData[2]

        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        val currentTime = System.currentTimeMillis()

        if (magnitude > crashThresholdGForce) {
            if ((currentTime - lastCrashEventTime) > MIN_EVENT_INTERVAL_MS) {
                Log.e(TAG, "CRASH DETECTED! Magnitude: $magnitude m/s^2 (Threshold: $crashThresholdGForce m/s^2)")
                lastCrashEventTime = currentTime
                return true
            }
        }
        return false
    }

    /**
     * Call this method when the engine is no longer needed to unregister the listener
     * and prevent memory leaks. For example, in ViewModel's onCleared().
     */
    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        Log.d(TAG, "Unregistered preference change listener.")
    }
}

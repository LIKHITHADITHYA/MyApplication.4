package com.example.myapplication.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import android.widget.Toast

class ProximityAlertReceiver : BroadcastReceiver() {

    private val TAG = "ProximityAlertReceiver"

    override fun onReceive(context: Context?, intent: Intent?) {
        val isEntering = intent?.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false) ?: false
        val lat = intent?.getDoubleExtra("latitude", 0.0) ?: 0.0
        val lon = intent?.getDoubleExtra("longitude", 0.0) ?: 0.0

        if (isEntering) {
            Log.d(TAG, "Entering proximity alert zone at lat=$lat, lon=$lon")
            Toast.makeText(context, "Entering proximity zone!", Toast.LENGTH_LONG).show()
            // Here you might trigger a more prominent alert, haptic feedback, or a visual indicator
        } else {
            Log.d(TAG, "Exiting proximity alert zone at lat=$lat, lon=$lon")
            Toast.makeText(context, "Exiting proximity zone.", Toast.LENGTH_LONG).show()
        }
    }
}

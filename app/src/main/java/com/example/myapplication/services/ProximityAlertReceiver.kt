package com.example.myapplication.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProximityAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // This is where you'll handle the proximity alert.
        // For now, we'll just log a message.
        Log.d("ProximityAlertReceiver", "Proximity alert triggered!")
    }
}

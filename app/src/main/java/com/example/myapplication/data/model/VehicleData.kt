package com.example.myapplication.data.model

import android.location.Location

data class VehicleData(
    val deviceId: String,
    val timestamp: Long,
    val location: Location,
    val speed: Float, // in m/s
    val bearing: Float // in degrees
)
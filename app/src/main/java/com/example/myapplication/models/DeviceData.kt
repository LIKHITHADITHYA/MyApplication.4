package com.example.myapplication.models

data class DeviceData(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val velocity: Float,
    val bearing: Float,
    val timestamp: Long
)

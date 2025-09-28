package com.example.myapplication.ui // Adjust package as necessary, e.g., .data or .core

data class VehicleUiState(
    val latitude: Double,
    val longitude: Double,
    val speed: Double,
    val bearing: Double,
    val timestamp: Long
)

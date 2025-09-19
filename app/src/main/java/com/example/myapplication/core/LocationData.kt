package com.example.myapplication.core

import java.io.Serializable

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val speed: Float
) : Serializable

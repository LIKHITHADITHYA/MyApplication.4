package com.example.myapplication.core

import android.location.Location

class KalmanFilter(private var processNoise: Float) {
    private var isInitialized = false
    private var variance: Float = -1f
    private var lastTimestamp: Long = 0L
    private var latitude: Double = 0.0
    private var longitude: Double = 0.0

    fun process(newLocation: Location): Location {
        if (!isInitialized) {
            latitude = newLocation.latitude
            longitude = newLocation.longitude
            variance = newLocation.accuracy * newLocation.accuracy
            lastTimestamp = newLocation.time
            isInitialized = true
            return newLocation
        }

        val elapsedTimestamp = newLocation.time - lastTimestamp
        if (elapsedTimestamp > 0) {
            variance += elapsedTimestamp * processNoise * processNoise / 1000
        }

        val kalmanGain = variance / (variance + newLocation.accuracy * newLocation.accuracy)
        latitude += kalmanGain * (newLocation.latitude - latitude)
        longitude += kalmanGain * (newLocation.longitude - longitude)
        variance = (1 - kalmanGain) * variance
        lastTimestamp = newLocation.time
        
        val filteredLocation = Location(newLocation)
        filteredLocation.latitude = latitude
        filteredLocation.longitude = longitude
        return filteredLocation
    }
}

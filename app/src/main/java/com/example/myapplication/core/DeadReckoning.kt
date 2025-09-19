package com.example.myapplication.core

import android.hardware.SensorEvent
import android.location.Location

class DeadReckoning(private var initialLocation: Location) {
    private var lastTimestamp: Long = 0L

    fun predictLocation(event: SensorEvent): Location {
        if (lastTimestamp == 0L) {
            lastTimestamp = event.timestamp
            return initialLocation
        }

        val dt = (event.timestamp - lastTimestamp) * NS2S
        lastTimestamp = event.timestamp

        val axisX = event.values[0]
        val axisY = event.values[1]
        val axisZ = event.values[2]

        // This is a very simplified model. A real implementation would need to account for orientation.
        val displacementX = axisX * dt * dt / 2
        val displacementY = axisY * dt * dt / 2

        val newLocation = Location(initialLocation)
        // Note: This doesn't account for coordinate system transformations.
        // It's a placeholder for a more complex implementation.
        newLocation.latitude += displacementY
        newLocation.longitude += displacementX

        return newLocation
    }

    companion object {
        private const val NS2S = 1.0f / 1000000000.0f
    }
}

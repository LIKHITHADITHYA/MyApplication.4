package com.example.myapplication

import com.example.myapplication.models.DeviceData
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class CollisionPredictionEngine {

    companion object {
        private const val SAFETY_RADIUS = 10.0 // meters
        private const val TIME_HORIZON = 5.0  // seconds
    }

    /**
     * Predicts a potential collision between two devices based on their current trajectories.
     *
     * @param myData The data for our device.
     * @param otherData The data for the other device.
     * @return True if a collision is predicted, false otherwise.
     */
    fun predictCollision(myData: DeviceData, otherData: DeviceData): Boolean {
        val ttc = calculateTTC(myData, otherData)
        return ttc != null && ttc > 0 && ttc < TIME_HORIZON
    }

    /**
     * Calculates the Time-to-Collision (TTC) between two devices.
     *
     * This is a simplified calculation that assumes constant velocity and bearing.
     *
     * @param myData The data for our device.
     * @param otherData The data for the other device.
     * @return The TTC in seconds, or null if the paths do not intersect.
     */
    private fun calculateTTC(myData: DeviceData, otherData: DeviceData): Double? {
        val myFuturePosition = predictPosition(myData, TIME_HORIZON)
        val otherFuturePosition = predictPosition(otherData, TIME_HORIZON)

        // This is a simplified check. A more robust solution would involve
        // solving for the intersection of the two vectors.
        val distanceAtHorizon = haversineDistance(
            myFuturePosition.first, myFuturePosition.second,
            otherFuturePosition.first, otherFuturePosition.second
        )

        if (distanceAtHorizon < SAFETY_RADIUS) {
            // A more accurate TTC would be calculated here.
            // For simplicity, we'll return a value within the horizon if a potential
            // collision is detected.
            return TIME_HORIZON / 2
        }

        return null
    }

    private fun predictPosition(data: DeviceData, time: Double): Pair<Double, Double> {
        val distance = data.velocity * time
        val bearingRad = Math.toRadians(data.bearing.toDouble())

        val latRad = Math.toRadians(data.latitude)
        val lonRad = Math.toRadians(data.longitude)

        val newLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(distance / 6371000) +
                    Math.cos(latRad) * Math.sin(distance / 6371000) * Math.cos(bearingRad)
        )
        val newLonRad = lonRad + Math.atan2(
            Math.sin(bearingRad) * Math.sin(distance / 6371000) * Math.cos(latRad),
            Math.cos(distance / 6371000) - Math.sin(latRad) * Math.sin(newLatRad)
        )

        return Pair(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad))
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).pow(2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).pow(2)
        val c = 2 * Math.atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

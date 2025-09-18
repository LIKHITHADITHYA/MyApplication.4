package com.example.myapplication.core

import com.example.myapplication.data.model.VehicleData
import kotlin.math.*

sealed class Alert {
    object Safe : Alert()
    data class Caution(val message: String) : Alert()
    data class Danger(val message: String, val recommendedSpeed: Float) : Alert()
}

class CollisionDetectionEngine {

    companion object {
        private const val EARTH_RADIUS_METERS = 6371e3
        private const val TTC_DANGER_THRESHOLD = 5.0 // seconds
        private const val TTC_CAUTION_THRESHOLD = 10.0 // seconds
        private const val SAFE_DISTANCE_THRESHOLD_MIN = 10.0 // meters
    }

    fun checkForCollision(myState: VehicleData, otherState: VehicleData): Alert {
        val distance = haversineDistance(
            myState.location.latitude, myState.location.longitude,
            otherState.location.latitude, otherState.location.longitude
        )

        if (distance < SAFE_DISTANCE_THRESHOLD_MIN) {
            return Alert.Danger("Too close!", 0f)
        }

        // Simplified TTC calculation (assumes linear paths)
        val relativeSpeed = myState.speed - otherState.speed
        if (relativeSpeed <= 0) return Alert.Safe // Not getting closer

        val ttc = distance / relativeSpeed
        if (ttc < TTC_DANGER_THRESHOLD) {
            return Alert.Danger("Collision risk!", calculateRecommendedSpeed(myState.speed))
        } else if (ttc < TTC_CAUTION_THRESHOLD) {
            return Alert.Caution("Proximity warning!")
        }

        return Alert.Safe
    }

    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val originLat = Math.toRadians(lat1)
        val destinationLat = Math.toRadians(lat2)

        val a = sin(dLat / 2).pow(2) + cos(originLat) * cos(destinationLat) * sin(dLon / 2).pow(2)
        val c = 2 * asin(sqrt(a))
        return EARTH_RADIUS_METERS * c
    }

    private fun calculateRecommendedSpeed(currentSpeed: Float): Float {
        // Simple recommendation: reduce speed by 20%
        return currentSpeed * 0.8f
    }
}
package com.example.myapplication.util

import android.location.Location
import com.example.myapplication.data.VehicleData
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class CollisionAlert(
    val collidingPeer: VehicleData,
    val timeToCollisionSeconds: Double,
    val myPredictedLocation: Location,
    val peerPredictedLocation: Location
)

object CollisionPredictor {

    private const val EARTH_RADIUS_METERS = 6371000.0
    private const val SAFETY_RADIUS_METERS = 10.0 // As per your requirements
    private const val TIME_HORIZON_SECONDS = 5.0  // As per your requirements
    private const val TIME_STEP_SECONDS = 0.5     // Prediction granularity

    /**
     * Predicts a future position based on current location, speed, bearing, and time duration.
     * Uses spherical law of cosines for calculations.
     *
     * @param currentLat Current latitude in degrees.
     * @param currentLon Current longitude in degrees.
     * @param speedMps Speed in meters per second.
     * @param bearingDegrees Bearing in degrees (0-360, North is 0).
     * @param timeSeconds Time duration into the future in seconds.
     * @return A Location object representing the predicted position.
     */
    private fun predictFuturePosition(
        currentLat: Double,
        currentLon: Double,
        speedMps: Float,
        bearingDegrees: Float,
        timeSeconds: Double
    ): Location {
        val distanceMeters = speedMps * timeSeconds

        val latRad = Math.toRadians(currentLat)
        val lonRad = Math.toRadians(currentLon)
        val bearingRad = Math.toRadians(bearingDegrees.toDouble())

        val angularDistance = distanceMeters / EARTH_RADIUS_METERS

        val futureLatRad = asin(
            sin(latRad) * cos(angularDistance) +
                    cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )

        val futureLonRad = lonRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(futureLatRad)
        )

        val futureLocation = Location("prediction").apply {
            latitude = Math.toDegrees(futureLatRad)
            longitude = Math.toDegrees(futureLonRad)
        }
        return futureLocation
    }

    /**
     * Checks for a potential collision between myVehicle and a peerVehicle.
     *
     * @param myVehicleData Current data for your vehicle.
     * @param peerVehicleData Current data for the peer vehicle.
     * @return CollisionAlert if a potential collision is detected within the time horizon,
     *         null otherwise.
     */
    fun checkPotentialCollision(
        myVehicleData: VehicleData,
        peerVehicleData: VehicleData
    ): CollisionAlert? {
        // Avoid predicting collision with oneself or if peer data is stale (optional check)
        if (myVehicleData.deviceId == peerVehicleData.deviceId) {
            return null
        }

        // Optional: Add a staleness check for peerVehicleData.timestamp
        // val dataStalenessThresholdMs = 3000 // e.g., 3 seconds
        // if (System.currentTimeMillis() - peerVehicleData.timestamp > dataStalenessThresholdMs) {
        //     Log.w("CollisionPredictor", "Peer data for ${peerVehicleData.deviceId} is stale, skipping prediction.")
        //     return null
        // }

        var currentTimeStep = 0.0
        while (currentTimeStep <= TIME_HORIZON_SECONDS) {
            val myFuturePos = predictFuturePosition(
                myVehicleData.latitude,
                myVehicleData.longitude,
                myVehicleData.speed,
                myVehicleData.bearing,
                currentTimeStep
            )

            val peerFuturePos = predictFuturePosition(
                peerVehicleData.latitude,
                peerVehicleData.longitude,
                peerVehicleData.speed,
                peerVehicleData.bearing,
                currentTimeStep
            )

            val distanceBetween = myFuturePos.distanceTo(peerFuturePos)

            if (distanceBetween < SAFETY_RADIUS_METERS) {
                return CollisionAlert(
                    collidingPeer = peerVehicleData,
                    timeToCollisionSeconds = currentTimeStep,
                    myPredictedLocation = myFuturePos,
                    peerPredictedLocation = peerFuturePos
                )
            }
            currentTimeStep += TIME_STEP_SECONDS
        }
        return null // No collision predicted within the horizon
    }
}

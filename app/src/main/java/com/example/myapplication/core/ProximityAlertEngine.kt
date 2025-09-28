package com.example.myapplication.core

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.myapplication.data.VehicleData // Assuming VehicleData is in this package or imported
import com.example.myapplication.util.AppPreferences

class ProximityAlertEngine(private val context: Context) {

    private val TAG = "ProximityAlertEngine"

    private val DANGER_ZONE_METERS: Float = AppPreferences.getProximityDangerZoneM(context)
    private val CAUTION_ZONE_METERS: Float = AppPreferences.getProximityCautionZoneM(context)

    enum class ProximityStatus {
        DANGER_ZONE,
        CAUTION_ZONE,
        ALL_CLEAR
    }

    // Adjusted to take List<VehicleData> to be consistent with ViewModel
    fun checkProximity(myVehicle: VehicleData, otherVehicles: List<VehicleData>): ProximityStatus {
        if (otherVehicles.isEmpty()) {
            return ProximityStatus.ALL_CLEAR
        }

        val myLocation = Location("").apply {
            latitude = myVehicle.latitude
            longitude = myVehicle.longitude
        }

        var closestOverallStatus: ProximityStatus = ProximityStatus.ALL_CLEAR

        for (otherVehicle in otherVehicles) {
            if (otherVehicle.deviceId == myVehicle.deviceId) continue // Skip self

            val otherLocation = Location("").apply {
                latitude = otherVehicle.latitude
                longitude = otherVehicle.longitude
            }
            val distance = myLocation.distanceTo(otherLocation)

            // Using fixed distances from AppPreferences now.
            // Dynamic safe zone based on speed could be an additional layer or alternative.
            val currentVehicleStatus = when {
                distance <= DANGER_ZONE_METERS -> ProximityStatus.DANGER_ZONE
                distance <= CAUTION_ZONE_METERS -> ProximityStatus.CAUTION_ZONE
                else -> ProximityStatus.ALL_CLEAR
            }

            if (currentVehicleStatus == ProximityStatus.DANGER_ZONE) {
                Log.d(TAG, "DANGER ZONE: Vehicle ${otherVehicle.deviceId} is ${"%.1f".format(distance)}m away. (Thresh: $DANGER_ZONE_METERS m)")
                return ProximityStatus.DANGER_ZONE // Immediate return if any vehicle is in DANGER_ZONE
            }
            if (currentVehicleStatus == ProximityStatus.CAUTION_ZONE) {
                closestOverallStatus = ProximityStatus.CAUTION_ZONE // Mark that at least one is in caution
                Log.d(TAG, "CAUTION ZONE: Vehicle ${otherVehicle.deviceId} is ${"%.1f".format(distance)}m away. (Thresh: $CAUTION_ZONE_METERS m)")
            }
        }
        return closestOverallStatus
    }

    // getDynamicSafeZone can be kept for other purposes or if you want to combine fixed + dynamic logic
    private fun getDynamicSafeZone(speedKmh: Float): Double {
        // Example: 3-second rule (speed in m/s * 3 seconds)
        // Speed in m/s = speedKmh * 1000 / 3600 = speedKmh / 3.6
        val speedMs = speedKmh / 3.6
        val safeDistance = speedMs * 3 // Adjust multiplier as needed (e.g., 2-second rule, 3-second rule)
        // Log.d(TAG, "Dynamic safe distance at ${speedKmh.format(1)} km/h: ${safeDistance.format(1)} m")
        return safeDistance.coerceAtLeast(15.0) // Minimum 15m safe distance
    }
}

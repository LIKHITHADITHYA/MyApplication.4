package com.example.myapplication.core

import android.content.Context
import android.location.Location
import android.util.Log
import com.example.myapplication.VehicleData
import com.example.myapplication.models.DeviceData

class ProximityAlertEngine(private val context: Context) {

    private val TAG = "ProximityAlertEngine"

    enum class ProximityStatus {
        DANGER_ZONE,
        CAUTION_ZONE,
        ALL_CLEAR
    }

    fun checkProximity(myVehicle: VehicleData, otherDevices: List<DeviceData>): ProximityStatus {
        if (otherDevices.isEmpty()) {
            return ProximityStatus.ALL_CLEAR
        }

        val myLocation = Location("").apply {
            latitude = myVehicle.latitude
            longitude = myVehicle.longitude
        }

        var closestStatus: ProximityStatus = ProximityStatus.ALL_CLEAR

        for (otherDevice in otherDevices) {
            val otherLocation = Location("").apply {
                latitude = otherDevice.latitude
                longitude = otherDevice.longitude
            }
            val distance = myLocation.distanceTo(otherLocation)
            val dynamicSafeZone = getDynamicSafeZone(myVehicle.speed)

            val status = when {
                distance <= 10 -> ProximityStatus.DANGER_ZONE // 10 meters for danger
                distance <= dynamicSafeZone -> ProximityStatus.CAUTION_ZONE
                else -> ProximityStatus.ALL_CLEAR
            }

            // Prioritize DANGER > CAUTION > ALL_CLEAR
            if (status == ProximityStatus.DANGER_ZONE) {
                Log.d(TAG, "DANGER ZONE: Vehicle ${otherDevice.deviceId} is ${"%.1f".format(distance)}m away.")
                return ProximityStatus.DANGER_ZONE // Immediate return on danger
            }
            if (status == ProximityStatus.CAUTION_ZONE) {
                closestStatus = ProximityStatus.CAUTION_ZONE
                Log.d(TAG, "CAUTION ZONE: Vehicle ${otherDevice.deviceId} is ${"%.1f".format(distance)}m away.")
            }
        }
        return closestStatus
    }

    private fun getDynamicSafeZone(speedKmh: Float): Double {
        return if (speedKmh > 30) {
            50.0 // meters for caution zone
        } else {
            25.0 // meters for caution zone
        }
    }
}

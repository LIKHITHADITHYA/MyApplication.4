package com.example.myapplication.core

import com.example.myapplication.VehicleData

class ProximityAlertEngine {

    enum class ProximityStatus {
        ALL_CLEAR, CAUTION_ZONE, DANGER_ZONE
    }

    private fun calculateDistance(data1: VehicleData, data2: VehicleData): Double {
        val lat1 = data1.latitude
        val lon1 = data1.longitude
        val lat2 = data2.latitude
        val lon2 = data2.longitude

        val r = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun checkProximity(myData: VehicleData, otherData: List<VehicleData>): ProximityStatus {
        for (otherVehicle in otherData) {
            val distance = calculateDistance(myData, otherVehicle)
            val dynamicSafeZone = getDynamicSafeZone(myData.speed)

            when {
                distance <= 5.0 -> return ProximityStatus.DANGER_ZONE
                distance <= dynamicSafeZone -> return ProximityStatus.CAUTION_ZONE
            }
        }
        return ProximityStatus.ALL_CLEAR
    }

    private fun getDynamicSafeZone(speedMps: Float): Double {
        // Convert speed from m/s to km/h for the existing logic
        val speedKmh = speedMps * 3.6f
        return if (speedKmh > 30) {
            20.0 // meters
        } else {
            10.0 // meters
        }
    }
}

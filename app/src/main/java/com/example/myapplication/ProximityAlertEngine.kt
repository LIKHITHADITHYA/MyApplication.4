package com.example.myapplication

import com.example.myapplication.models.DeviceData

class ProximityAlertEngine {

    enum class Zone {
        SAFE, CAUTION, DANGER
    }

    private fun calculateDistance(data1: DeviceData, data2: DeviceData): Double {
        val lat1 = data1.latitude
        val lon1 = data1.longitude
        val lat2 = data2.latitude
        val lon2 = data2.longitude

        val r = 6371000 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun checkProximity(myData: DeviceData, otherData: DeviceData): Zone {
        val distance = calculateDistance(myData, otherData)
        val safeZoneDistance = getDynamicSafeZone(myData.velocity)

        return when {
            distance <= 5 -> Zone.DANGER
            distance <= safeZoneDistance -> Zone.CAUTION
            else -> Zone.SAFE
        }
    }

    private fun getDynamicSafeZone(speedKmh: Float): Double {
        return if (speedKmh > 30) {
            20.0 // meters
        } else {
            10.0 // meters
        }
    }
}

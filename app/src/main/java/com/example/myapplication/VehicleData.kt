package com.example.myapplication

import java.io.Serializable

data class VehicleData(
    val deviceId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val accelerometerData: FloatArray,
    val gyroscopeData: FloatArray
) : Serializable {
    // Overriding equals and hashCode for FloatArray comparison
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VehicleData

        if (deviceId != other.deviceId) return false
        if (timestamp != other.timestamp) return false
        if (latitude != other.latitude) return false
        if (longitude != other.longitude) return false
        if (speed != other.speed) return false
        if (!accelerometerData.contentEquals(other.accelerometerData)) return false
        if (!gyroscopeData.contentEquals(other.gyroscopeData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + speed.hashCode()
        result = 31 * result + accelerometerData.contentHashCode()
        result = 31 * result + gyroscopeData.contentHashCode()
        return result
    }
}
package com.example.myapplication.data

import com.google.android.gms.maps.model.LatLng
import java.io.Serializable
import java.util.UUID

/**
 * A serializable wrapper for Google's LatLng class to allow it to be sent
 * via P2P communication.
 */
data class LatLngSerializable(
    val latitude: Double,
    val longitude: Double
) : Serializable {
    fun toLatLng(): LatLng {
        return LatLng(latitude, longitude)
    }
}

/**
 * Extension function to convert a standard LatLng to our serializable version.
 */
fun LatLng.toSerializable(): LatLngSerializable {
    return LatLngSerializable(latitude, longitude)
}

/**
 * Represents the complete data packet for a vehicle to be shared over P2P.
 * This class is serializable to be sent through streams.
 *
 * @param deviceId A unique identifier for the device sending the data.
 * @param latitude Current latitude.
 * @param longitude Current longitude.
 * @param speed Current speed in meters per second.
 * @param bearing Current bearing in degrees.
 * @param timestamp The timestamp of when the data was generated.
 * @param route An optional list of LatLng points representing the intended future path.
 * @param isSOS Flag indicating if this is an SOS broadcast.
 * @param sosMessage Optional message accompanying the SOS.
 * @param sosId Unique identifier for an SOS event, to help deduplicate.
 */
data class VehicleData(
    val deviceId: String,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val route: List<LatLngSerializable> = emptyList(),
    val isSOS: Boolean = false,
    val sosMessage: String? = null,
    val sosId: String? = null // Unique ID for each SOS event
) : Serializable

package com.example.myapplication

import com.example.myapplication.models.DeviceData

class DataFusionEngine {

    /**
     * Combines device data from multiple sources using a simple weighted averaging approach.
     * This is a basic implementation. A more advanced version could use algorithms like
     * covariance intersection for better accuracy.
     *
     * @param dataList A list of DeviceData objects from various sources.
     * @return A single, fused DeviceData object representing the consensus.
     */
    fun fuseData(dataList: List<DeviceData>): DeviceData? {
        if (dataList.isEmpty()) {
            return null
        }

        // Simple averaging for location and velocity.
        // A more sophisticated approach would involve weighting based on accuracy/covariance.
        val avgLatitude = dataList.map { it.latitude }.average()
        val avgLongitude = dataList.map { it.longitude }.average()
        val avgVelocity = dataList.map { it.velocity }.average()
        val avgBearing = dataList.map { it.bearing }.average()

        // For this example, we'll just use the data from the first device for other fields.
        val firstData = dataList.first()

        return DeviceData(
            deviceId = "fused_device",
            latitude = avgLatitude,
            longitude = avgLongitude,
            velocity = avgVelocity.toFloat(),
            bearing = avgBearing.toFloat(),
            timestamp = System.currentTimeMillis()
        )
    }
}

package com.example.myapplication.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SpeedLimitManager(private val context: Context) {

    private val TAG = "SpeedLimitManager"

    suspend fun getSpeedLimit(latitude: Double, longitude: Double): Int? {
        return withContext(Dispatchers.IO) {
            val urlString = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$latitude&lon=$longitude&zoom=18&addressdetails=1"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "MyApplication/1.0")

            try {
                val inputStream = connection.inputStream
                val response = inputStream.bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(response)
                val addressDetails = jsonObject.optJSONObject("address")
                val maxspeed = addressDetails?.optString("maxspeed")

                if (maxspeed != null && maxspeed.isNotEmpty()) {
                    val speedValue = maxspeed.split(" ")[0].toIntOrNull()
                    Log.d(TAG, "Max speed from API: $maxspeed")
                    speedValue
                } else {
                    Log.d(TAG, "No maxspeed found for the current location.")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting speed limit: ${e.message}")
                null
            } finally {
                connection.disconnect()
            }
        }
    }
}

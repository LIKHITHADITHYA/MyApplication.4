package com.example.myapplication.core

import android.location.Location

class CollisionPredictor(private val safetyRadius: Float) {

    fun predict(myLocation: Location, otherLocation: Location): Float {
        val distance = myLocation.distanceTo(otherLocation)
        val relativeSpeed = myLocation.speed - otherLocation.speed

        if (relativeSpeed <= 0) {
            return Float.POSITIVE_INFINITY // Not approaching
        }

        val ttc = (distance - safetyRadius) / relativeSpeed
        return if (ttc >= 0) ttc else Float.POSITIVE_INFINITY
    }
}

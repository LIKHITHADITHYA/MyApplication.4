package com.example.myapplication.core

import android.content.Context
import android.util.Log
import com.example.myapplication.util.AppPreferences

class FatigueMonitorEngine(context: Context) {
    private val TAG = "FatigueMonitorEngine"

    private val laneDeviationWeight: Int = AppPreferences.getFatigueLaneDevWeight(context)
    private val sharpSpeedChangeWeight: Int = AppPreferences.getFatigueSpeedChangeWeight(context)
    private val fatigueThreshold: Int = AppPreferences.getFatigueScoreThreshold(context)

    private val EVENT_WINDOW_MS = 60 * 1000 // Analyze events in the last 1 minute
    private val MIN_ALERT_INTERVAL_MS = 5 * 60 * 1000 // Don't alert more than once every 5 mins

    private data class FatigueEvent(val timestamp: Long, val weight: Int)

    private val events = mutableListOf<FatigueEvent>()
    private var fatigueScore = 0
    private var lastFatigueAlertTime: Long = 0

    @Synchronized
    fun recordLaneDeviation() {
        cleanupOldEvents()
        events.add(FatigueEvent(System.currentTimeMillis(), laneDeviationWeight))
        recalculateFatigueScore()
        Log.d(TAG, "Lane deviation recorded (weight $laneDeviationWeight). Current fatigue score: $fatigueScore")
    }

    @Synchronized
    fun recordSharpSpeedChange() {
        cleanupOldEvents()
        events.add(FatigueEvent(System.currentTimeMillis(), sharpSpeedChangeWeight))
        recalculateFatigueScore()
        Log.d(TAG, "Sharp speed change recorded (weight $sharpSpeedChangeWeight). Current fatigue score: $fatigueScore")
    }

    private fun cleanupOldEvents() {
        val currentTime = System.currentTimeMillis()
        events.removeAll { (currentTime - it.timestamp) > EVENT_WINDOW_MS }
    }

    private fun recalculateFatigueScore() {
        fatigueScore = events.sumOf { it.weight }
    }

    @Synchronized
    fun isFatigueDetected(): Boolean {
        val currentTime = System.currentTimeMillis()
        if (fatigueScore >= fatigueThreshold) {
            if ((currentTime - lastFatigueAlertTime) > MIN_ALERT_INTERVAL_MS) {
                Log.w(TAG, "Fatigue DETECTED! Score: $fatigueScore (Threshold: $fatigueThreshold)")
                lastFatigueAlertTime = currentTime
                return true
            }
        }
        return false
    }

    @Synchronized
    fun resetFatigueScore() {
        events.clear()
        fatigueScore = 0
        Log.d(TAG, "Fatigue score reset.")
    }
}

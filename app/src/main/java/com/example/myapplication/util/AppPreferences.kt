package com.example.myapplication.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.UUID

object AppPreferences {
    private const val PREFS_NAME = "com.example.myapplication.prefs"
    private const val KEY_USER_NICKNAME = "user_nickname"
    private const val KEY_APP_INSTANCE_ID = "app_instance_id"

    // --- Crash Detection ---
    private const val KEY_CRASH_THRESHOLD_G = "crash_threshold_g"
    private const val DEFAULT_CRASH_THRESHOLD_G = 25.0f

    // --- Lane Deviation ---
    private const val KEY_LANE_DEV_ACC_THRESHOLD = "lane_dev_acc_threshold"
    private const val DEFAULT_LANE_DEV_ACC_THRESHOLD = 1.8f
    private const val KEY_LANE_DEV_DURATION_MS = "lane_dev_duration_ms"
    private const val DEFAULT_LANE_DEV_DURATION_MS = 400

    // --- Fatigue Monitoring ---
    private const val KEY_FATIGUE_LANE_DEV_WEIGHT = "fatigue_lane_dev_weight"
    private const val DEFAULT_FATIGUE_LANE_DEV_WEIGHT = 3
    private const val KEY_FATIGUE_SPEED_CHANGE_WEIGHT = "fatigue_speed_change_weight"
    private const val DEFAULT_FATIGUE_SPEED_CHANGE_WEIGHT = 2
    private const val KEY_FATIGUE_SCORE_THRESHOLD = "fatigue_score_threshold"
    private const val DEFAULT_FATIGUE_SCORE_THRESHOLD = 10

    // --- Proximity Alerts ---
    private const val KEY_PROXIMITY_DANGER_ZONE_M = "proximity_danger_zone_m"
    private const val DEFAULT_PROXIMITY_DANGER_ZONE_M = 30f
    private const val KEY_PROXIMITY_CAUTION_ZONE_M = "proximity_caution_zone_m"
    private const val DEFAULT_PROXIMITY_CAUTION_ZONE_M = 60f

    // --- Emergency Contact ---
    private const val KEY_EMERGENCY_CONTACT_NAME = "emergency_contact_name"
    private const val KEY_EMERGENCY_CONTACT_PHONE = "emergency_contact_phone"
    private const val DEFAULT_EMERGENCY_CONTACT_NAME = ""
    private const val DEFAULT_EMERGENCY_CONTACT_PHONE = ""


    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- User Nickname ---
    fun getUserNickname(context: Context): String = getPreferences(context).getString(KEY_USER_NICKNAME, "") ?: ""
    fun setUserNickname(context: Context, nickname: String) = getPreferences(context).edit { putString(KEY_USER_NICKNAME, nickname) }

    // --- App Instance ID ---
    fun getAppInstanceId(context: Context): String {
        val prefs = getPreferences(context)
        var uuid = prefs.getString(KEY_APP_INSTANCE_ID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_APP_INSTANCE_ID, uuid) }
        }
        return uuid
    }

    // --- Getters and Setters for Thresholds ---
    fun getCrashThresholdG(context: Context): Float = getPreferences(context).getFloat(KEY_CRASH_THRESHOLD_G, DEFAULT_CRASH_THRESHOLD_G)
    fun setCrashThresholdG(context: Context, value: Float) = getPreferences(context).edit { putFloat(KEY_CRASH_THRESHOLD_G, value) }

    fun getLaneDevAccThreshold(context: Context): Float = getPreferences(context).getFloat(KEY_LANE_DEV_ACC_THRESHOLD, DEFAULT_LANE_DEV_ACC_THRESHOLD)
    fun setLaneDevAccThreshold(context: Context, value: Float) = getPreferences(context).edit { putFloat(KEY_LANE_DEV_ACC_THRESHOLD, value) }

    fun getLaneDevDurationMs(context: Context): Int = getPreferences(context).getInt(KEY_LANE_DEV_DURATION_MS, DEFAULT_LANE_DEV_DURATION_MS)
    fun setLaneDevDurationMs(context: Context, value: Int) = getPreferences(context).edit { putInt(KEY_LANE_DEV_DURATION_MS, value) }

    fun getFatigueLaneDevWeight(context: Context): Int = getPreferences(context).getInt(KEY_FATIGUE_LANE_DEV_WEIGHT, DEFAULT_FATIGUE_LANE_DEV_WEIGHT)
    fun setFatigueLaneDevWeight(context: Context, value: Int) = getPreferences(context).edit { putInt(KEY_FATIGUE_LANE_DEV_WEIGHT, value) }

    fun getFatigueSpeedChangeWeight(context: Context): Int = getPreferences(context).getInt(KEY_FATIGUE_SPEED_CHANGE_WEIGHT, DEFAULT_FATIGUE_SPEED_CHANGE_WEIGHT)
    fun setFatigueSpeedChangeWeight(context: Context, value: Int) = getPreferences(context).edit { putInt(KEY_FATIGUE_SPEED_CHANGE_WEIGHT, value) }

    fun getFatigueScoreThreshold(context: Context): Int = getPreferences(context).getInt(KEY_FATIGUE_SCORE_THRESHOLD, DEFAULT_FATIGUE_SCORE_THRESHOLD)
    fun setFatigueScoreThreshold(context: Context, value: Int) = getPreferences(context).edit { putInt(KEY_FATIGUE_SCORE_THRESHOLD, value) }

    fun getProximityDangerZoneM(context: Context): Float = getPreferences(context).getFloat(KEY_PROXIMITY_DANGER_ZONE_M, DEFAULT_PROXIMITY_DANGER_ZONE_M)
    fun setProximityDangerZoneM(context: Context, value: Float) = getPreferences(context).edit { putFloat(KEY_PROXIMITY_DANGER_ZONE_M, value) }

    fun getProximityCautionZoneM(context: Context): Float = getPreferences(context).getFloat(KEY_PROXIMITY_CAUTION_ZONE_M, DEFAULT_PROXIMITY_CAUTION_ZONE_M)
    fun setProximityCautionZoneM(context: Context, value: Float) = getPreferences(context).edit { putFloat(KEY_PROXIMITY_CAUTION_ZONE_M, value) }

    // --- Emergency Contact ---
    fun getEmergencyContactName(context: Context): String = getPreferences(context).getString(KEY_EMERGENCY_CONTACT_NAME, DEFAULT_EMERGENCY_CONTACT_NAME) ?: DEFAULT_EMERGENCY_CONTACT_NAME
    fun getEmergencyContactPhone(context: Context): String = getPreferences(context).getString(KEY_EMERGENCY_CONTACT_PHONE, DEFAULT_EMERGENCY_CONTACT_PHONE) ?: DEFAULT_EMERGENCY_CONTACT_PHONE

    fun setEmergencyContact(context: Context, name: String, phone: String) {
        getPreferences(context).edit {
            putString(KEY_EMERGENCY_CONTACT_NAME, name)
            putString(KEY_EMERGENCY_CONTACT_PHONE, phone)
        }
    }
    fun clearEmergencyContact(context: Context) {
        getPreferences(context).edit {
            remove(KEY_EMERGENCY_CONTACT_NAME)
            remove(KEY_EMERGENCY_CONTACT_PHONE)
        }
    }
}

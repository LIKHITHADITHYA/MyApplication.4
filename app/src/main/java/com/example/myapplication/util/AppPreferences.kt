package com.example.myapplication.util

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object AppPreferences {
    private const val PREFS_NAME = "com.example.myapplication.prefs"
    private const val KEY_USER_NICKNAME = "user_nickname"
    private const val KEY_APP_INSTANCE_ID = "app_instance_id"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getUserNickname(context: Context): String {
        return getPreferences(context).getString(KEY_USER_NICKNAME, "") ?: ""
    }

    fun setUserNickname(context: Context, nickname: String) {
        getPreferences(context).edit().putString(KEY_USER_NICKNAME, nickname).apply()
    }

    fun getAppInstanceId(context: Context): String {
        val prefs = getPreferences(context)
        var uuid = prefs.getString(KEY_APP_INSTANCE_ID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_APP_INSTANCE_ID, uuid).apply()
        }
        return uuid
    }
}
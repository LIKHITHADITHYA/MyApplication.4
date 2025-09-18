package com.example.myapplication.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.myapplication.util.AppPreferences

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname

    init {
        Log.d("SettingsViewModel", "Initializing and loading nickname.")
        loadNickname()
    }

    private fun loadNickname() {
        val app = getApplication<Application>()
        if (app == null) {
            Log.e("SettingsViewModel", "Application context is null in loadNickname.")
            _nickname.value = "Error: Context null"
            return
        }
        try {
            // Explicitly log the AppPreferences object itself to see if it's accessible.
            // This line is for deep diagnostics; AppPreferences as a Kotlin object shouldn't be null.
            Log.d("SettingsViewModel", "AppPreferences object: ${AppPreferences::class.java.name}") 
            val currentNickname = AppPreferences.getUserNickname(app)
            _nickname.value = currentNickname
            Log.d("SettingsViewModel", "Loaded nickname: $currentNickname")
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error loading nickname: ${e.message}", e)
            _nickname.value = "Error loading nickname"
        }
    }

    fun saveNickname(newNickname: String) {
        val app = getApplication<Application>()
        if (app == null) {
            Log.e("SettingsViewModel", "Application context is null in saveNickname.")
            return
        }
        try {
            AppPreferences.setUserNickname(app, newNickname)
            _nickname.value = newNickname // Update LiveData after saving
            Log.d("SettingsViewModel", "Saved nickname: $newNickname")
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error saving nickname: ${e.message}", e)
        }
    }
}
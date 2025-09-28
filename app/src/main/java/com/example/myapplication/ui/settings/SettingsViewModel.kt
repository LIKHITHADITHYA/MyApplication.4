package com.example.myapplication.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// Simple Event wrapper
open class Event<out T>(private val content: T) {
    var hasBeenHandled = false
        private set // Allow external read but not write

    @Suppress("unused") // Might be used by observers
    fun peekContent(): T = content
}

enum class P2pAction {
    DISCOVER_PEERS,
    CREATE_GROUP,
    REMOVE_GROUP
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname

    // LiveData for GNSS toggle action request
    // Parameter: Boolean - represents the desired state (true to start, false to stop)
    private val _gnssToggleRequest = MutableLiveData<Boolean>()

    // LiveData for P2P action requests
    private val _p2pActionRequest = MutableLiveData<Event<P2pAction>>()
    val p2pActionRequest: LiveData<Event<P2pAction>> = _p2pActionRequest

    init {
        Log.d("SettingsViewModel", "Initializing and loading nickname.")
        loadNickname()
    }

    private fun loadNickname() {
        val app = getApplication<Application>()
        try {
            // Using com.example.myapplication.util.AppPreferences directly as it's an object
            val currentNickname = com.example.myapplication.util.AppPreferences.getUserNickname(app)
            _nickname.value = currentNickname
            Log.d("SettingsViewModel", "Loaded nickname: $currentNickname")
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error loading nickname: ${e.message}", e)
            _nickname.value = "Error loading nickname"
        }
    }

    fun saveNickname(newNickname: String) {
        val app = getApplication<Application>()
        try {
            com.example.myapplication.util.AppPreferences.setUserNickname(app, newNickname)
            _nickname.value = newNickname // Update LiveData after saving
            Log.d("SettingsViewModel", "Saved nickname: $newNickname")
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "Error saving nickname: ${e.message}", e)
        }
    }

    // Called by SettingsFragment when the toggle GNSS button is clicked
    fun onToggleGnssClicked(isCurrentlyActive: Boolean) {
        // Request to toggle: if it's active, request to stop (false). If inactive, request to start (true).
        _gnssToggleRequest.value = !isCurrentlyActive
    }

    // Called by SettingsFragment for P2P discovery
    fun onDiscoverPeersClicked() {
        _p2pActionRequest.value = Event(P2pAction.DISCOVER_PEERS)
    }

    // Called by SettingsFragment to create a P2P group
    fun onCreateGroupClicked() {
        _p2pActionRequest.value = Event(P2pAction.CREATE_GROUP)
    }

    // Called by SettingsFragment to remove a P2P group
    fun onRemoveGroupClicked() {
        _p2pActionRequest.value = Event(P2pAction.REMOVE_GROUP)
    }
}

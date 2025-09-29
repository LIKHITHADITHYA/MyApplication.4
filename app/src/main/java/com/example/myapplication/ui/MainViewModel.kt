package com.example.myapplication.ui

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.VehicleData
import com.example.myapplication.services.NavigationService
import com.example.myapplication.util.CollisionAlert
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor() : ViewModel() {

    private val _navigationService = MutableStateFlow<NavigationService?>(null)

    // Service connection to manage the lifecycle of the NavigationService binding
    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NavigationService.LocalBinder
            _navigationService.value = binder?.getService()
            Log.d("MainViewModel", "NavigationService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _navigationService.value = null
            Log.d("MainViewModel", "NavigationService disconnected")
        }
    }

    // Expose flows from the NavigationService, providing defaults or empty states
    val userLocation: StateFlow<LatLng?> = _navigationService.combine(
        // Placeholder flow to ensure combine works even if service is initially null
        MutableStateFlow<LatLng?>(null)
    ) { service, _ ->
        service?.userLocationFlow?.value
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentSpeed: StateFlow<Float> = _navigationService.combine(
        MutableStateFlow(0.0f)
    ) { service, _ ->
        service?.currentSpeedFlow?.value ?: 0.0f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0f)

    val userHeading: StateFlow<Float> = _navigationService.combine(
        MutableStateFlow(0.0f)
    ) { service, _ ->
        service?.userHeadingFlow?.value ?: 0.0f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0f)

    val gnssStatusText: StateFlow<String> = _navigationService.combine(
        MutableStateFlow("GNSS Initializing...")
    ) { service, _ ->
        service?.gnssStatusTextFlow?.value ?: "GNSS Initializing..."
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "GNSS Initializing...")

    val connectivityStatusText: StateFlow<String> = _navigationService.combine(
        MutableStateFlow("P2P Initializing...")
    ) { service, _ ->
        service?.connectivityStatusTextFlow?.value ?: "P2P Initializing..."
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "P2P Initializing...")

    val nearbyVehicles: StateFlow<List<VehicleData>> = _navigationService.combine(
        MutableStateFlow<List<VehicleData>>(emptyList())
    ) { service, _ ->
        service?.nearbyVehiclesFlow?.value ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val collisionAlert: StateFlow<CollisionAlert?> = _navigationService.combine(
        MutableStateFlow<CollisionAlert?>(null)
    ) { service, _ ->
        service?.collisionAlertFlow?.value
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    // Function to be called by UI to send SOS data via service
    fun sendSosSignal(sosData: VehicleData) {
        _navigationService.value?.sendSosBroadcast(sosData)
    }

    override fun onCleared() {
        super.onCleared()
        // Unbind from service if necessary, though context unbinds typically handle this.
        // If you manually bind with context.bindService, you should call context.unbindService here.
        Log.d("MainViewModel", "MainViewModel cleared")
    }
}

package com.example.myapplication.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.myapplication.core.Alert
import com.example.myapplication.core.ProximityAlertEngine
import com.example.myapplication.models.DeviceData // Added import
import com.google.android.gms.maps.model.LatLng
import com.example.myapplication.ui.gps.NearbyVehicle

class DashboardViewModel : ViewModel() {

    private val _gnssData = MutableLiveData<String>()
    val gnssData: LiveData<String> = _gnssData

    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _alertStatus = MutableLiveData<Alert>()
    val alertStatus: LiveData<Alert> = _alertStatus

    private val _currentSpeed = MutableLiveData<Float>()
    val currentSpeed: LiveData<Float> = _currentSpeed

    private val _nearbyVehicles = MutableLiveData<List<NearbyVehicle>>()
    val nearbyVehicles: LiveData<List<NearbyVehicle>> = _nearbyVehicles

    private val _motionEvent = MutableLiveData<String>()
    val motionEvent: LiveData<String> = _motionEvent

    private val _currentAltitude = MutableLiveData<Float>()
    val currentAltitude: LiveData<Float> = _currentAltitude

    private val _currentHeading = MutableLiveData<Float>()
    val currentHeading: LiveData<Float> = _currentHeading

    private val _infrastructureMessage = MutableLiveData<String?>()
    val infrastructureMessage: LiveData<String?> = _infrastructureMessage

    private val _currentUserLocation = MutableLiveData<LatLng?>()
    val currentUserLocation: LiveData<LatLng?> = _currentUserLocation

    private val _proximityStatus = MutableLiveData<ProximityAlertEngine.ProximityStatus>()
    val proximityStatus: LiveData<ProximityAlertEngine.ProximityStatus> = _proximityStatus

    // Added for Fused Group Data
    private val _fusedGroupData = MutableLiveData<DeviceData?>()
    val fusedGroupData: LiveData<DeviceData?> = _fusedGroupData


    fun updateGnssData(data: String) {
        _gnssData.postValue(data)
    }

    fun updateConnectionStatus(status: String) {
        _connectionStatus.postValue(status)
    }

    fun updateAlertStatus(alert: Alert) {
        _alertStatus.postValue(alert)
    }

    fun updateCurrentSpeed(speed: Float) {
        _currentSpeed.postValue(speed)
    }

    fun updateNearbyVehicles(vehicles: List<NearbyVehicle>) {
        _nearbyVehicles.postValue(vehicles)
    }

    fun updateMotionEvent(event: String) {
        _motionEvent.postValue(event)
    }

    fun updateCurrentAltitude(altitude: Float) {
        _currentAltitude.postValue(altitude)
    }

    fun updateCurrentHeading(heading: Float) {
        _currentHeading.postValue(heading)
    }

    fun updateInfrastructureMessage(message: String?) {
        _infrastructureMessage.postValue(message)
    }

    fun updateCurrentUserLocation(location: LatLng?) {
        _currentUserLocation.postValue(location)
    }

    fun updateProximityStatus(status: ProximityAlertEngine.ProximityStatus) {
        _proximityStatus.postValue(status)
    }

    // Added for Fused Group Data
    fun updateFusedGroupData(data: DeviceData?) {
        _fusedGroupData.postValue(data)
    }
}

package com.example.myapplication.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.myapplication.core.Alert // Assuming Alert is in this package
import com.google.android.gms.maps.model.LatLng // Added for currentUserLocation
// If NearbyVehicle is in a different package, you might need to import it too.
// For example: import com.example.myapplication.model.NearbyVehicle

class DashboardViewModel : ViewModel() {

    private val _gnssData = MutableLiveData<String>()
    val gnssData: LiveData<String> = _gnssData

    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _alertStatus = MutableLiveData<Alert>()
    val alertStatus: LiveData<Alert> = _alertStatus

    // This holds the current speed of the user's device in m/s
    private val _currentSpeed = MutableLiveData<Float>()
    val currentSpeed: LiveData<Float> = _currentSpeed

    // This holds the list of other vehicles detected nearby
    private val _nearbyVehicles = MutableLiveData<List<NearbyVehicle>>()
    val nearbyVehicles: LiveData<List<NearbyVehicle>> = _nearbyVehicles

    // This holds the latest detected motion event (e.g., "Braking", "Sharp Turn")
    private val _motionEvent = MutableLiveData<String>()
    val motionEvent: LiveData<String> = _motionEvent

    // This holds the current altitude in meters
    private val _currentAltitude = MutableLiveData<Float>()
    val currentAltitude: LiveData<Float> = _currentAltitude

    // This holds the current heading in degrees (0-360, 0 = North)
    private val _currentHeading = MutableLiveData<Float>()
    val currentHeading: LiveData<Float> = _currentHeading

    // This holds the latest message from V2I communication
    private val _infrastructureMessage = MutableLiveData<String?>()
    val infrastructureMessage: LiveData<String?> = _infrastructureMessage

    // IMPORTANT: This LiveData needs to be updated by your location service/provider
    // with the user's current geographical coordinates for proximity alerts to work.
    private val _currentUserLocation = MutableLiveData<LatLng?>()
    val currentUserLocation: LiveData<LatLng?> = _currentUserLocation


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

    // Function to update the V2I message
    fun updateInfrastructureMessage(message: String?) {
        _infrastructureMessage.postValue(message)
    }

    // Function to update the user's current location
    fun updateCurrentUserLocation(location: LatLng?) {
        _currentUserLocation.postValue(location)
    }
}
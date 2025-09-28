package com.example.myapplication.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.CollisionPredictionEngine
import com.example.myapplication.core.CrashDetectionEngine
import com.example.myapplication.core.FatigueMonitorEngine
import com.example.myapplication.core.LaneDeviationDetector
import com.example.myapplication.core.ProximityAlertEngine
import com.example.myapplication.core.SpeedLimitManager
import com.example.myapplication.data.VehicleData
import com.example.myapplication.data.toSerializable
import com.example.myapplication.util.AppPreferences
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

// Data class for SMS details
data class SosSmsDetails(val phoneNumber: String, val message: String)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // GNSS Data LiveData
    private val _currentUserLocation = MutableLiveData<LatLng?>()
    val currentUserLocation: LiveData<LatLng?> = _currentUserLocation

    private val _currentSpeed = MutableLiveData<Float>(0f)
    val currentSpeed: LiveData<Float> = _currentSpeed

    private val _currentBearing = MutableLiveData<Float>(0f)
    val currentBearing: LiveData<Float> = _currentBearing

    private val _currentUserAltitude = MutableLiveData<Double?>()
    val currentUserAltitude: LiveData<Double?> = _currentUserAltitude

    private val _currentUserAccuracy = MutableLiveData<Float?>()
    val currentUserAccuracy: LiveData<Float?> = _currentUserAccuracy

    private val _hdop = MutableLiveData<Float?>() // Horizontal Dilution of Precision
    val hdop: LiveData<Float?> = _hdop

    private val _vdop = MutableLiveData<Float?>() // Vertical Dilution of Precision
    val vdop: LiveData<Float?> = _vdop

    private val _pdop = MutableLiveData<Float?>() // Position Dilution of Precision
    val pdop: LiveData<Float?> = _pdop

    private val _gnssStatusString = MutableLiveData<String?>()
    val gnssStatusString: LiveData<String?> = _gnssStatusString

    // IMU Data LiveData
    private val _accelerometerData = MutableLiveData<FloatArray?>()
    val accelerometerData: LiveData<FloatArray?> = _accelerometerData

    private val _magnetometerData = MutableLiveData<FloatArray?>()
    val magnetometerData: LiveData<FloatArray?> = _magnetometerData

    // P2P and Connectivity LiveData
    private val _connectionStatus = MutableLiveData<String>()
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _nearbyVehicles = MutableLiveData<List<VehicleData>>(emptyList())
    val nearbyVehicles: LiveData<List<VehicleData>> = _nearbyVehicles

    // Alerts and Events LiveData
    private val _alertStatus = MutableLiveData<Alert>()
    val alertStatus: LiveData<Alert> = _alertStatus

    private val _proximityAlert = MutableLiveData<ProximityAlertEngine.ProximityStatus>()
    val proximityAlert: LiveData<ProximityAlertEngine.ProximityStatus> = _proximityAlert

    private val _speedLimit = MutableLiveData<Int?>()
    val speedLimit: LiveData<Int?> = _speedLimit

    private val _isSpeeding = MutableLiveData<Boolean>(false)
    val isSpeeding: LiveData<Boolean> = _isSpeeding

    private val _laneDeviationDetected = MutableLiveData<Event<Unit>>()
    val laneDeviationDetected: LiveData<Event<Unit>> = _laneDeviationDetected

    private val _crashDetected = MutableLiveData<Event<Unit>>()
    val crashDetected: LiveData<Event<Unit>> = _crashDetected

    private val _incomingSosEvent = MutableLiveData<Event<VehicleData>>()
    val incomingSosEvent: LiveData<Event<VehicleData>> = _incomingSosEvent

    private val _fatigueAlertEvent = MutableLiveData<Event<Unit>>()
    val fatigueAlertEvent: LiveData<Event<Unit>> = _fatigueAlertEvent

    private val _sosSmsAttemptEvent = MutableLiveData<Event<SosSmsDetails>>()
    val sosSmsAttemptEvent: LiveData<Event<SosSmsDetails>> = _sosSmsAttemptEvent

    // Route LiveData
    private val _route = MutableLiveData<List<LatLng>>(emptyList())
    val route: LiveData<List<LatLng>> = _route

    // Core components
    private val speedLimitManager = SpeedLimitManager(application)
    private val laneDeviationDetector = LaneDeviationDetector(application)
    private val crashDetectionEngine = CrashDetectionEngine(application)
    private val proximityAlertEngine = ProximityAlertEngine(application)
    private val collisionPredictionEngine = CollisionPredictionEngine()
    private val fatigueMonitorEngine = FatigueMonitorEngine(application)

    var navigationService: com.example.myapplication.services.NavigationService? = null
    private val deviceId: String = AppPreferences.getAppInstanceId(application.applicationContext)
    private var lastRecordedSpeedKmph: Float = 0f
    private val SHARP_SPEED_CHANGE_THRESHOLD_KMH = 30f

    private val processedSosIds = HashSet<String>()
    private val MAX_PROCESSED_SOS_IDS = 100

    // init {
    //    AppPreferences.getAppInstanceId(application.applicationContext) // Redundant, deviceId is already initialized
    // }

    fun updateUserLocation(
        location: LatLng?,
        speedKmph: Float,
        bearing: Float,
        altitude: Double?,
        accuracy: Float?
    ) {
        _currentUserLocation.postValue(location)
        _currentSpeed.postValue(speedKmph)
        _currentBearing.postValue(bearing)
        _currentUserAltitude.postValue(altitude)
        _currentUserAccuracy.postValue(accuracy)

        if (location != null) {
            checkSpeedingStatus(speedKmph, location.latitude, location.longitude)
            checkProximityAndCollisions()
            if (abs(speedKmph - lastRecordedSpeedKmph) > SHARP_SPEED_CHANGE_THRESHOLD_KMH) {
                Log.d(
                    "DashboardViewModel",
                    "Sharp speed change: ${lastRecordedSpeedKmph.format(1)} -> ${speedKmph.format(1)} km/h"
                )
                fatigueMonitorEngine.recordSharpSpeedChange()
                checkFatigueStatus()
            }
            lastRecordedSpeedKmph = speedKmph
        }
    }

    fun updateDopValues(hdop: Float?, vdop: Float?, pdop: Float?) {
        _hdop.postValue(hdop)
        _vdop.postValue(vdop)
        _pdop.postValue(pdop)
    }

    fun updateAccelerometerData(data: FloatArray, timestamp: Long) {
        _accelerometerData.postValue(data.clone())
        if (crashDetectionEngine.updateAccelerationData(data, timestamp)) {
            _crashDetected.postValue(Event(Unit))
            triggerSOS("Crash detected! Sensor impact.")
        }
        if (laneDeviationDetector.updateAccelerationData(data, timestamp)) {
            _laneDeviationDetected.postValue(Event(Unit))
            fatigueMonitorEngine.recordLaneDeviation()
            checkFatigueStatus()
        }
    }

    fun updateMagnetometerData(data: FloatArray, timestamp: Long) {
        _magnetometerData.postValue(data.clone())
    }

    fun updateNearbyVehiclesList(vehicles: List<VehicleData>) {
        _nearbyVehicles.postValue(vehicles)
        checkProximityAndCollisions()
        vehicles.firstOrNull { it.isSOS && it.deviceId != deviceId && it.sosId != null }
            ?.let { sosVehicle ->
                // sosId is known to be non-null here due to the predicate above
                if (processedSosIds.add(sosVehicle.sosId!!)) {
                    _incomingSosEvent.postValue(Event(sosVehicle))
                    Log.i(
                        "DashboardViewModel",
                        "New SOS: ${sosVehicle.sosId} from ${sosVehicle.deviceId}"
                    )
                    if (processedSosIds.size <= MAX_PROCESSED_SOS_IDS) {
                    } else {
                        processedSosIds.remove(processedSosIds.firstOrNull())
                    }
                } else {
                    Log.d("DashboardViewModel", "Duplicate SOS ignored: ${sosVehicle.sosId}")
                }
            }
    }

    private fun checkFatigueStatus() {
        if (fatigueMonitorEngine.isFatigueDetected()) {
            _fatigueAlertEvent.postValue(Event(Unit))
        }
    }

    private fun checkSpeedingStatus(currentSpeedKmph: Float, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            val limit = speedLimitManager.getSpeedLimit(latitude, longitude)
            _speedLimit.postValue(limit)
            _isSpeeding.postValue(limit != null && currentSpeedKmph > limit)
        }
    }

    private fun checkProximityAndCollisions() {
        val myData = getLatestVehicleData() ?: return
        val others = _nearbyVehicles.value?.filter { it.deviceId != deviceId } ?: emptyList()
        if (others.isEmpty()) {
            _proximityAlert.postValue(ProximityAlertEngine.ProximityStatus.ALL_CLEAR)
            return
        }
        val proximityStatus = proximityAlertEngine.checkProximity(myData, others)
        _proximityAlert.postValue(proximityStatus)
        others.forEach {
            if (collisionPredictionEngine.predictCollision(myData, it)) {
                Log.w("DashboardViewModel", "Collision predicted with ${it.deviceId}")
                return@forEach
            }
        }
    }

    private fun triggerSOS(reason: String) {
        Log.d("DashboardViewModel", "Internal Triggering SOS: $reason")
        val newSosId = UUID.randomUUID().toString()
        val sosDataToBroadcast =
            getLatestVehicleData(isSOS = true, sosMessage = reason, sosId = newSosId)

        sosDataToBroadcast?.let { navigationService?.sendSosBroadcast(it) }
            ?: Log.e("DashboardViewModel", "Cannot broadcast SOS: no location data.")

        val contactPhone = AppPreferences.getEmergencyContactPhone(getApplication())
        val contactName = AppPreferences.getEmergencyContactName(getApplication())

        if (contactPhone.isNotBlank()) {
            val locationString =
                _currentUserLocation.value?.let { "My last known location: https://maps.google.com/?q=${it.latitude},${it.longitude}" }
                    ?: "Location not available."
            val userNickname = AppPreferences.getUserNickname(getApplication())
            val displayName = if (userNickname.isBlank()) "user" else userNickname
            val smsMessage = "SOS from $displayName: $reason. $locationString"

            _sosSmsAttemptEvent.postValue(Event(SosSmsDetails(contactPhone, smsMessage)))
            Log.i(
                "DashboardViewModel",
                "Attempting to send SOS SMS to $contactName ($contactPhone): \"$smsMessage\""
            )
        } else {
            Log.w(
                "DashboardViewModel",
                "No emergency contact phone number set. Cannot send SOS SMS."
            )
        }
    }

    fun manuallyTriggerSOS(customMessage: String?) {
        triggerSOS(customMessage ?: "Manual SOS by user.")
    }

    fun setPlannedRoute(newRoute: List<LatLng>) {
        _route.postValue(newRoute)
    }

    fun getLatestVehicleData(
        isSOS: Boolean = false,
        sosMessage: String? = null,
        sosId: String? = null
    ): VehicleData? {
        val location = _currentUserLocation.value ?: return null
        // Ensure consistent float division
        val speedMs = (_currentSpeed.value ?: 0f) / 3.6f
        val bearing = _currentBearing.value ?: 0f
        return VehicleData(
            deviceId = deviceId,
            latitude = location.latitude,
            longitude = location.longitude,
            speed = speedMs,
            bearing = bearing,
            timestamp = System.currentTimeMillis(),
            route = (_route.value ?: emptyList()).map { it.toSerializable() },
            isSOS = isSOS,
            sosMessage = sosMessage,
            sosId = sosId
        )
    }

    fun updateGeneralStatusString(status: String?) {
        _gnssStatusString.postValue(status)
    }

    fun updateP2PStatus(status: String) {
        _connectionStatus.postValue(status)
    }

    override fun onCleared() {
        super.onCleared()
        crashDetectionEngine.destroy()
        Log.d("DashboardViewModel", "Core engines cleaned up.")
    }
}

class Event<T>(private val content: T) {
    private var hasBeenHandled = false

    // Expanded form to ensure compiler understands the if/else expression
    fun getContentIfNotHandled(): T? {
        if (hasBeenHandled) {
            return null
        } else {
            hasBeenHandled = true
            return content
        }
    }

    fun peekContent(): T = content
}

fun Float.format(digits: Int) = "%.${digits}f".format(this)
fun Double.format(digits: Int) = "%.${digits}f".format(this)

data class Alert(val message: String, val level: Int)

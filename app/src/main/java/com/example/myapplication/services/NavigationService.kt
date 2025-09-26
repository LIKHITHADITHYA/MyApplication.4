package com.example.myapplication.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.myapplication.CollisionPredictionEngine
import com.example.myapplication.DataFusionEngine
import com.example.myapplication.MainActivity
import com.example.myapplication.P2pCommunicationManager
import com.example.myapplication.R
import com.example.myapplication.StableLatLng // Added for PeerDeviceDisplay
import com.example.myapplication.VehicleData
import com.example.myapplication.core.ExtendedKalmanFilter
import com.example.myapplication.core.ProximityAlertEngine
import com.example.myapplication.models.DeviceData
import com.example.myapplication.PeerDeviceDisplay // Using PeerDeviceDisplay from MainActivity context
import com.example.myapplication.data.VehicleState // Added import for EKF
import com.example.myapplication.utils.NotificationHelper
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NavigationService : Service(), SensorEventListener, P2pCommunicationManager.OnDataReceivedListener {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- StateFlows for UI ---
    private val _currentSpeedFlow = MutableStateFlow(0.0f)
    val currentSpeedFlow: StateFlow<Float> = _currentSpeedFlow.asStateFlow()

    private val _userLocationFlow = MutableStateFlow<LatLng?>(null)
    val userLocationFlow: StateFlow<LatLng?> = _userLocationFlow.asStateFlow()

    private val _userHeadingFlow = MutableStateFlow(0.0f)
    val userHeadingFlow: StateFlow<Float> = _userHeadingFlow.asStateFlow()

    // Combined status text for GNSS (e.g., "Signal: Strong", "Searching...", "Permission Denied")
    private val _gnssStatusTextFlow = MutableStateFlow("GNSS: Initializing...")
    val gnssStatusTextFlow: StateFlow<String> = _gnssStatusTextFlow.asStateFlow()

    // Combined status text for Connectivity (e.g., "Connected Peers: 4", "P2P Not Available")
    private val _connectivityStatusTextFlow = MutableStateFlow("P2P: Initializing...")
    val connectivityStatusTextFlow: StateFlow<String> = _connectivityStatusTextFlow.asStateFlow()
    
    private val _isP2pEnabledFlow = MutableStateFlow(false)
    val isP2pEnabledFlow: StateFlow<Boolean> = _isP2pEnabledFlow.asStateFlow()

    private val _nearbyVehiclesFlow = MutableStateFlow<List<PeerDeviceDisplay>>(emptyList())
    val nearbyVehiclesFlow: StateFlow<List<PeerDeviceDisplay>> = _nearbyVehiclesFlow.asStateFlow()
    
    private val _isGnssCurrentlyActiveFlow = MutableStateFlow(false)
    val isGnssCurrentlyActiveFlow: StateFlow<Boolean> = _isGnssCurrentlyActiveFlow.asStateFlow()

    // For more detailed alerts/data if needed by UI beyond simple text
    private val _proximityAlertStatusFlow = MutableStateFlow(ProximityAlertEngine.ProximityStatus.ALL_CLEAR)
    val proximityAlertStatusFlow: StateFlow<ProximityAlertEngine.ProximityStatus> = _proximityAlertStatusFlow.asStateFlow()

    // --- End StateFlows ---

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    lateinit var p2pCommunicationManager: P2pCommunicationManager // Retaining public access if needed by other components

    private lateinit var notificationHelper: NotificationHelper
    private lateinit var proximityAlertEngine: ProximityAlertEngine
    private lateinit var collisionPredictionEngine: CollisionPredictionEngine
    private lateinit var dataFusionEngine: DataFusionEngine
    private var currentUserVehicleData: VehicleData? = null

    private val ekf = ExtendedKalmanFilter()
    private val otherVehicles = ConcurrentHashMap<String, VehicleData>()

    private var lastAccelerometerReading = FloatArray(3)
    private var lastGyroscopeReading = FloatArray(3)

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var groupOwnerAddress: InetAddress? = null
    private var isGroupOwner: Boolean = false

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    private val locationCallback = object : LocationCallback() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location -> // location is android.location.Location
                Log.d(TAG, "RAW Location Speed from FusedLocationProvider: ${location.speed} m/s, Accuracy: ${location.speedAccuracyMetersPerSecond}")

                // Create VehicleState for EKF
                val currentVehicleState = VehicleState(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speed = location.speed.toDouble(), // Convert Float to Double
                    bearing = location.bearing.toDouble(), // Convert Float to Double
                    timestamp = location.time // Already Long
                )
                ekf.update(currentVehicleState) // Pass the new VehicleState object
                
                val fusedLocation = ekf.getState() // fusedLocation is com.example.myapplication.data.VehicleState
                Log.d(TAG, "EKF Fused Location Speed: ${fusedLocation.speed} m/s (from EKF internal state)")

                // Adjust reportedSpeed: fusedLocation.speed is Double, SPEED_THRESHOLD_MS is Float
                val reportedSpeed = if (fusedLocation.speed < SPEED_THRESHOLD_MS.toDouble()) 0.0f else fusedLocation.speed.toFloat()
                Log.d(TAG, "Reported Speed (EKF, after threshold $SPEED_THRESHOLD_MS m/s): $reportedSpeed m/s")

                val newLatLng = LatLng(fusedLocation.latitude, fusedLocation.longitude)
                val gnssString = "Lat: ${String.format(Locale.US, "%.4f", newLatLng.latitude)}, Lon: ${String.format(Locale.US, "%.4f", newLatLng.longitude)}"
                
                // Use accuracy from the original android.location.Location object
                val gnssAccuracy = location.accuracy 
                val gnssStatusText = when {
                    gnssAccuracy <= 10 -> "Signal: Strong (Acc: ${String.format(Locale.US, "%.1f", gnssAccuracy)}m)"
                    gnssAccuracy <= 25 -> "Signal: Moderate (Acc: ${String.format(Locale.US, "%.1f", gnssAccuracy)}m)"
                    else -> "Signal: Weak (Acc: ${String.format(Locale.US, "%.1f", gnssAccuracy)}m)"
                }
                _gnssStatusTextFlow.value = gnssStatusText
                
                Log.d(TAG, "locationCallback: Updating ViewModel - Speed=$reportedSpeed, GNSS='$gnssString', Status='$gnssStatusText'")
                _userLocationFlow.value = newLatLng
                _currentSpeedFlow.value = reportedSpeed // reportedSpeed is Float
                _userHeadingFlow.value = fusedLocation.bearing.toFloat() // fusedLocation.bearing is Double, convert to Float

                currentUserVehicleData = VehicleData(
                    deviceId = "myDevice", // TODO: Get actual device ID
                    timestamp = fusedLocation.timestamp, // from VehicleState (Long)
                    latitude = fusedLocation.latitude,   // from VehicleState (Double)
                    longitude = fusedLocation.longitude, // from VehicleState (Double)
                    speed = reportedSpeed,               // Float
                    bearing = fusedLocation.bearing.toFloat(), // Convert Double to Float
                    accelerometerData = lastAccelerometerReading,
                    gyroscopeData = lastGyroscopeReading
                )

                if (!isGroupOwner && groupOwnerAddress != null && ::p2pCommunicationManager.isInitialized) {
                    currentUserVehicleData?.let { p2pCommunicationManager.sendData(it, groupOwnerAddress!!) }
                }
                checkProximityAndNotify()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service creating...")
        _connectivityStatusTextFlow.value = "P2P: Initializing..."
        _gnssStatusTextFlow.value = "GNSS: Initializing..."
        _currentSpeedFlow.value = 0.0f

        try {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            notificationHelper = NotificationHelper(this)
            proximityAlertEngine = ProximityAlertEngine(this)
            collisionPredictionEngine = CollisionPredictionEngine()
            dataFusionEngine = DataFusionEngine()
        } catch (e: Exception) {
            Log.e(TAG, "!!!!!! CRITICAL ERROR DURING CORE INITIALIZATION !!!!!!", e)
            _gnssStatusTextFlow.value = "GNSS: Error"
            _connectivityStatusTextFlow.value = "P2P: Error"
            stopSelf()
            return
        }

        try {
            val foregroundNotification = createForegroundServiceNotification().build()
            startForeground(FOREGROUND_SERVICE_NOTIFICATION_ID, foregroundNotification)
        } catch (e: Exception) {
            Log.e(TAG, "!!!!!! CRITICAL ERROR DURING startForeground CALL !!!!!!", e)
            stopSelf()
            throw e
        }

        serviceScope.launch {
            Log.d(TAG, "onCreate (Coroutine): Initializing P2P Communication...")
            try {
                wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as? WifiP2pManager
                wifiP2pChannel = wifiP2pManager?.initialize(this@NavigationService, Looper.getMainLooper(), null)

                if (wifiP2pManager == null || wifiP2pChannel == null) {
                    Log.w(TAG, "onCreate (Coroutine): Wi-Fi Direct feature NOT available.")
                    _connectivityStatusTextFlow.value = "P2P: Not Available"
                    _isP2pEnabledFlow.value = false
                    return@launch
                }
                _isP2pEnabledFlow.value = true
                p2pCommunicationManager = P2pCommunicationManager(this@NavigationService, wifiP2pManager!!, wifiP2pChannel!!, this@NavigationService)
                p2pCommunicationManager.registerReceiver()
                p2pCommunicationManager.discoverPeers() // Initial discovery
                _connectivityStatusTextFlow.value = "P2P: Discovering..."
                Log.d(TAG, "onCreate (Coroutine): P2pCommunicationManager initialized and discovering peers.")
            } catch (e: Exception) {
                Log.e(TAG, "!!!!!! CRITICAL ERROR DURING P2P INITIALIZATION (Coroutine) !!!!!!", e)
                 _connectivityStatusTextFlow.value = "P2P: Init Failed"
                 _isP2pEnabledFlow.value = false
            }
        }

        startLocationUpdates()
        registerSensorListeners()
        Log.d(TAG, "onCreate: Service creation complete.")
    }

    fun startLocationUpdates() {
        Log.d(TAG, "startLocationUpdates: Attempting to start location updates.")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startLocationUpdates: Location permissions NOT granted.")
            _isGnssCurrentlyActiveFlow.value = false
            _gnssStatusTextFlow.value = "GNSS: Permission Denied"
            _currentSpeedFlow.value = 0.0f
            return
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.d(TAG, "startLocationUpdates: Location updates requested.")
            _isGnssCurrentlyActiveFlow.value = true
            _gnssStatusTextFlow.value = "GNSS: Awaiting Fix..."
            _currentSpeedFlow.value = 0.0f // Speed is 0 until first fix
        } catch (e: Exception) {
            Log.e(TAG, "startLocationUpdates: Error requesting location updates: ${e.message}", e)
            _isGnssCurrentlyActiveFlow.value = false
            _gnssStatusTextFlow.value = "GNSS: Error"
            _currentSpeedFlow.value = 0.0f
        }
    }

    fun stopLocationUpdates() {
        Log.d(TAG, "stopLocationUpdates: Stopping location updates.")
        _isGnssCurrentlyActiveFlow.value = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
            Log.e(TAG, "stopLocationUpdates: Error removing location updates: ${e.message}", e)
        }
        _currentSpeedFlow.value = 0.0f
        _gnssStatusTextFlow.value = "GNSS: Stopped"
        _userLocationFlow.value = null // Clear location when stopped
    }

    private fun registerSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            var sensorUpdated = false
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    lastAccelerometerReading = it.values.clone()
                    sensorUpdated = true
                }
                Sensor.TYPE_GYROSCOPE -> {
                    lastGyroscopeReading = it.values.clone()
                    sensorUpdated = true
                }
            }
            if (sensorUpdated && ekf.isInitialized()) {
                ekf.predict(lastAccelerometerReading, lastGyroscopeReading, it.timestamp)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: Service bound.")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "onUnbind: Service unbound.")
        return super.onUnbind(intent)
    }

    private fun createForegroundServiceNotification(): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                FOREGROUND_SERVICE_CHANNEL_ID,
                "Navigation Foreground Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingIntentFlags)
        return NotificationCompat.Builder(this, FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle("V2V Sentinel Active")
            .setContentText("Collision avoidance system is running.")
            .setSmallIcon(R.drawable.ic_notification_icon) // Ensure this drawable exists
            .setContentIntent(pendingIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service destroying...")
        serviceScope.cancel()
        _isGnssCurrentlyActiveFlow.value = false
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {
             Log.e(TAG, "onDestroy: Error removing location updates: ${e.message}", e)
        }
        if (::p2pCommunicationManager.isInitialized) {
            p2pCommunicationManager.close()
        }
        sensorManager.unregisterListener(this)
        _gnssStatusTextFlow.value = "GNSS: Service Off"
        _connectivityStatusTextFlow.value = "P2P: Service Off"
        _currentSpeedFlow.value = 0.0f
        _userLocationFlow.value = null
        _nearbyVehiclesFlow.value = emptyList()
        Log.d(TAG, "onDestroy: Service destroyed.")
    }

    override fun onVehicleDataReceived(data: VehicleData) {
        Log.d(TAG, "onVehicleDataReceived: VehicleData from ${data.deviceId}")
        otherVehicles[data.deviceId] = data
        updateNearbyVehiclesFlow()
        checkProximityAndNotify()
         // Update connectivity status text with peer count
        val peerCount = otherVehicles.size
        _connectivityStatusTextFlow.value = if (peerCount > 0) "Peers: $peerCount" else "P2P: No Peers"
    }

    private fun updateNearbyVehiclesFlow() {
        val nearbyVehiclesList = otherVehicles.values.mapNotNull { vehicleData ->
            PeerDeviceDisplay(
                id = vehicleData.deviceId,
                position = StableLatLng(vehicleData.latitude, vehicleData.longitude)
                // icon = Icons.Filled.DirectionsCar // Placeholder, will be set in ViewModel
            )
        }
        Log.d(TAG, "updateNearbyVehiclesFlow: Updating with ${nearbyVehiclesList.size} nearby vehicles.")
        _nearbyVehiclesFlow.value = nearbyVehiclesList
    }
    
    // P2P Connection Info Update
    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        Log.d(TAG, "onConnectionInfoAvailable: Group Formed: ${wifiP2pInfo.groupFormed}, Is Group Owner: ${wifiP2pInfo.isGroupOwner}")
        this.isGroupOwner = wifiP2pInfo.isGroupOwner
        this.groupOwnerAddress = wifiP2pInfo.groupOwnerAddress
        
        if (::p2pCommunicationManager.isInitialized) {
            if (wifiP2pInfo.groupFormed) {
                _isP2pEnabledFlow.value = true // Should be true if group is formed
                if (wifiP2pInfo.isGroupOwner) {
                    Log.d(TAG, "onConnectionInfoAvailable: P2P group formed, I am OWNER. Starting data reception.")
                    p2pCommunicationManager.startReceivingData()
                    _connectivityStatusTextFlow.value = "P2P: Group Owner (Peers: ${otherVehicles.size})"
                } else if (groupOwnerAddress != null) {
                    Log.d(TAG, "onConnectionInfoAvailable: P2P group formed, I am CLIENT. GO: $groupOwnerAddress.")
                     _connectivityStatusTextFlow.value = "P2P: Client (Peers: ${otherVehicles.size})"
                } else {
                     Log.w(TAG, "onConnectionInfoAvailable: P2P group formed, I am CLIENT, but GO address is NULL. Rediscovering.")
                     p2pCommunicationManager.discoverPeers()
                     _connectivityStatusTextFlow.value = "P2P: Reconnecting..."
                }
            } else {
                Log.d(TAG, "onConnectionInfoAvailable: P2P group NOT formed. Rediscovering.")
                p2pCommunicationManager.discoverPeers()
                _connectivityStatusTextFlow.value = "P2P: Discovering..."
                _nearbyVehiclesFlow.value = emptyList() // Clear peers if group not formed
                otherVehicles.clear()
            }
        } else {
            Log.w(TAG, "onConnectionInfoAvailable: P2PManager NOT initialized.")
            _connectivityStatusTextFlow.value = "P2P: Error"
            _isP2pEnabledFlow.value = false
        }
    }


    private fun checkProximityAndNotify() {
        val myDataSnapshot = currentUserVehicleData ?: return

        val myDeviceData = DeviceData(
            deviceId = myDataSnapshot.deviceId,
            latitude = myDataSnapshot.latitude,
            longitude = myDataSnapshot.longitude,
            velocity = myDataSnapshot.speed,
            bearing = myDataSnapshot.bearing,
            timestamp = myDataSnapshot.timestamp
        )

        if (otherVehicles.isEmpty()) {
            _proximityAlertStatusFlow.value = ProximityAlertEngine.ProximityStatus.ALL_CLEAR
            notificationHelper.cancelNotification(PROXIMITY_ALERT_NOTIFICATION_ID)
            notificationHelper.cancelNotification(COLLISION_ALERT_NOTIFICATION_ID)
            // Update connectivity status if peers become 0
            if (_isP2pEnabledFlow.value) { // only if P2P is supposed to be active
                 _connectivityStatusTextFlow.value = if (isGroupOwner) "P2P: Group Owner (No Peers)" else "P2P: Client (No Peers)"
            }
            return
        }

        val otherDeviceDataList = otherVehicles.values.mapNotNull { vehicleData ->
            if (vehicleData.deviceId == myDeviceData.deviceId) null
            else DeviceData(
                deviceId = vehicleData.deviceId,
                latitude = vehicleData.latitude,
                longitude = vehicleData.longitude,
                velocity = vehicleData.speed,
                bearing = vehicleData.bearing,
                timestamp = vehicleData.timestamp
            )
        }

        if (otherDeviceDataList.isEmpty()) {
            _proximityAlertStatusFlow.value = ProximityAlertEngine.ProximityStatus.ALL_CLEAR
            notificationHelper.cancelNotification(PROXIMITY_ALERT_NOTIFICATION_ID)
            notificationHelper.cancelNotification(COLLISION_ALERT_NOTIFICATION_ID)
             if (_isP2pEnabledFlow.value) {
                 _connectivityStatusTextFlow.value = if (isGroupOwner) "P2P: Group Owner (No Peers)" else "P2P: Client (No Peers)"
            }
            return
        }
        
        // Update connectivity status text with peer count
        val peerCount = otherVehicles.size // Recalculate based on current otherVehicles
        if (_isP2pEnabledFlow.value) {
            _connectivityStatusTextFlow.value = if (isGroupOwner) "P2P: Group Owner (Peers: $peerCount)" else "P2P: Client (Peers: $peerCount)"
        }

        var collisionPredictedThisCycle = false
        for (otherDevice in otherDeviceDataList) {
            if (collisionPredictionEngine.predictCollision(myDeviceData, otherDevice)) {
                Log.w(TAG, "Collision predicted with ${otherDevice.deviceId}")
                notificationHelper.sendAdvancedNotification(
                    notificationId = COLLISION_ALERT_NOTIFICATION_ID,
                    title = "IMMINENT COLLISION WARNING!",
                    message = "High risk of collision with ${otherDevice.deviceId}. Take evasive action!",
                    soundResId = 0, // TODO: Define sound resource
                    enableFlashlight = true
                )
                // _proximityAlertStatusFlow.value = ProximityAlertEngine.ProximityStatus.DANGER_ZONE // Implied by collision
                notificationHelper.cancelNotification(PROXIMITY_ALERT_NOTIFICATION_ID)
                collisionPredictedThisCycle = true
                break
            }
        }

        if (collisionPredictedThisCycle) {
            return
        }

        val proximityStatus = proximityAlertEngine.checkProximity(myDataSnapshot, otherDeviceDataList)
        _proximityAlertStatusFlow.value = proximityStatus

        when (proximityStatus) {
            ProximityAlertEngine.ProximityStatus.DANGER_ZONE -> {
                notificationHelper.sendAdvancedNotification(
                    notificationId = PROXIMITY_ALERT_NOTIFICATION_ID,
                    title = "DANGER ZONE!",
                    message = "Vehicle nearby in DANGER zone. Immediate caution advised.",
                    soundResId = 0, // TODO: Define sound resource
                    enableFlashlight = true
                )
                notificationHelper.cancelNotification(COLLISION_ALERT_NOTIFICATION_ID)
            }
            ProximityAlertEngine.ProximityStatus.CAUTION_ZONE -> {
                notificationHelper.sendAdvancedNotification(
                    notificationId = PROXIMITY_ALERT_NOTIFICATION_ID,
                    title = "CAUTION ZONE",
                    message = "Vehicle approaching in CAUTION zone. Be aware.",
                    soundResId = 0, // TODO: Define sound resource
                    enableFlashlight = false
                )
                notificationHelper.cancelNotification(COLLISION_ALERT_NOTIFICATION_ID)
            }
            ProximityAlertEngine.ProximityStatus.ALL_CLEAR -> {
                notificationHelper.cancelNotification(PROXIMITY_ALERT_NOTIFICATION_ID)
                notificationHelper.cancelNotification(COLLISION_ALERT_NOTIFICATION_ID)
            }
        }
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val FOREGROUND_SERVICE_NOTIFICATION_ID = 1
        private const val PROXIMITY_ALERT_NOTIFICATION_ID = 2
        private const val COLLISION_ALERT_NOTIFICATION_ID = 3
        private const val FOREGROUND_SERVICE_CHANNEL_ID = "NAVIGATION_FOREGROUND_SERVICE_CHANNEL"
        private const val SPEED_THRESHOLD_MS = 0.1f // Speed threshold in meters/second
    }
}

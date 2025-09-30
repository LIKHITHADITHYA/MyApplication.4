package com.example.myapplication.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.P2pCommunicationManager // Ensure this is your correct interface/class
import com.example.myapplication.R
import com.example.myapplication.data.VehicleData
import com.example.myapplication.util.AppPreferences
import com.example.myapplication.util.CollisionAlert
import com.example.myapplication.util.CollisionPredictor
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class NavigationService : Service(), SensorEventListener,
    P2pCommunicationManager.OnDataReceivedListener {

    @Inject
    lateinit var fusedLocationClient: FusedLocationProviderClient

    @Inject
    lateinit var sensorManager: SensorManager

    @Inject
    lateinit var locationManager: LocationManager

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var p2pCommunicationManagerFactory: P2pCommunicationManager.Factory

    private val binder = LocalBinder()
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var currentHdop: Float? = null

    private var p2pCommunicationManager: P2pCommunicationManager? = null
    private val otherVehicles = ConcurrentHashMap<String, VehicleData>()
    private var groupOwnerAddress: InetAddress? = null
    private var isThisDeviceGroupOwner: Boolean = false
    private lateinit var deviceId: String
    private var myCurrentVehicleData: VehicleData? = null // For collision prediction

    private var dataSendingScheduler: ScheduledExecutorService? = null
    private var isLocationUpdatesActiveCurrently = false

    private enum class LocationUpdateIntervalType { ACTIVE, IDLE }

    private var currentIntervalType: LocationUpdateIntervalType = LocationUpdateIntervalType.ACTIVE
    private var lastKnownSpeedMps: Float = 0f
    // lastKnownLatLng, lastKnownBearing, lastKnownTimestamp are updated by location callback

    private val _currentSpeedFlow = MutableStateFlow(0.0f)
    val currentSpeedFlow: StateFlow<Float> = _currentSpeedFlow.asStateFlow()
    private val _userLocationFlow = MutableStateFlow<LatLng?>(null)
    val userLocationFlow: StateFlow<LatLng?> = _userLocationFlow.asStateFlow()
    private val _userHeadingFlow = MutableStateFlow(0.0f)
    val userHeadingFlow: StateFlow<Float> = _userHeadingFlow.asStateFlow()
    private val _gnssStatusTextFlow = MutableStateFlow("Initializing GNSS...")
    val gnssStatusTextFlow: StateFlow<String> = _gnssStatusTextFlow.asStateFlow()
    private val _connectivityStatusTextFlow = MutableStateFlow("Initializing P2P...")
    val connectivityStatusTextFlow: StateFlow<String> = _connectivityStatusTextFlow.asStateFlow()
    private val _nearbyVehiclesFlow = MutableStateFlow<List<VehicleData>>(emptyList())
    val nearbyVehiclesFlow: StateFlow<List<VehicleData>> = _nearbyVehiclesFlow.asStateFlow()
    private val _collisionAlertFlow = MutableStateFlow<CollisionAlert?>(null)
    val collisionAlertFlow: StateFlow<CollisionAlert?> = _collisionAlertFlow.asStateFlow()

    companion object {
        private const val TAG = "NavigationService"
        private const val NOTIFICATION_ID = 12345
        private const val NOTIFICATION_CHANNEL_ID = "NavigationServiceChannel"
        private const val NOTIFICATION_CHANNEL_NAME = "Navigation Service"
        private const val P2P_DATA_SEND_INTERVAL_MS = 2000L
        private const val ACTIVE_LOCATION_INTERVAL_MS = 2000L
        private const val FASTEST_ACTIVE_LOCATION_INTERVAL_MS = 1000L
        private const val IDLE_LOCATION_INTERVAL_MS = 10000L
        private const val FASTEST_IDLE_LOCATION_INTERVAL_MS = 5000L
        private const val STATIONARY_SPEED_THRESHOLD_MPS = 0.5f
    }

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    // Consider if this @RequiresApi is too restrictive for the entire onCreate.
    // If only specific calls need API 33, guard them internally.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        deviceId = AppPreferences.getAppInstanceId(this)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Initialization that should happen once when service is created
        createNotificationChannel()
        if (p2pCommunicationManager == null) { // Check if already initialized
            setupP2pManager() // This itself is @RequiresApi(TIRAMISU)
        }
        registerSensorListeners()
        startNmeaListener() // This is @RequiresApi(N)
        startP2pDataSendingScheduler()

        Log.d(TAG, "NavigationService Created with Device ID: $deviceId")
        _gnssStatusTextFlow.value = "Awaiting Fix"
        // startServiceInForeground() and updateLocationRequestIfNeeded() moved to onStartCommand
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startServiceInForeground() {
        val pendingIntent: PendingIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cooperative Navigation Active")
            .setContentText("Sharing location and sensor data.")
            .setSmallIcon(R.drawable.ic_notification_icon) // Ensure this drawable exists
            .setContentIntent(pendingIntent)
            .setOngoing(true).build()
        try {
            val foregroundServiceType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Changed to Q for FOREGROUND_SERVICE_TYPE_LOCATION
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0 // No specific type for older versions
                }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e); stopSelf()
        }
    }

    // This method needs to ensure the listener is correctly passed to P2pCommunicationManager.
    // Its @RequiresApi(TIRAMISU) might be problematic if P2P is needed on older devices.
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupP2pManager() {
        try {
            // CRITICAL: Ensure 'this' service instance is passed as the listener
            // to p2pCommunicationManagerFactory.create OR p2pCommunicationManager.
            // Example: p2pCommunicationManager = p2pCommunicationManagerFactory.create(this, this)
            // OR
            // p2pCommunicationManager = p2pCommunicationManagerFactory.create(this)
            // p2pCommunicationManager?.setListener(this)
            p2pCommunicationManager = p2pCommunicationManagerFactory.create(this)
            // If the factory doesn't take the listener, you MUST have a way to set it, e.g.:
            // p2pCommunicationManager?.setDataReceivedListener(this) // This is a placeholder, adapt to your P2pCommunicationManager

            p2pCommunicationManager?.registerReceiver()
            p2pCommunicationManager?.discoverPeers()
            _connectivityStatusTextFlow.value = "P2P Discovering..."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup P2P Manager: ${e.message}", e)
            _connectivityStatusTextFlow.value = "P2P Init Failed: ${e.localizedMessage}"
        }
    }

    private fun updateLocationRequestIfNeeded(newType: LocationUpdateIntervalType) {
        if (newType == currentIntervalType && isLocationUpdatesActiveCurrently) return
        currentIntervalType = newType
        val priority = Priority.PRIORITY_HIGH_ACCURACY
        val interval =
            if (newType == LocationUpdateIntervalType.ACTIVE) ACTIVE_LOCATION_INTERVAL_MS else IDLE_LOCATION_INTERVAL_MS
        val fastestInterval =
            if (newType == LocationUpdateIntervalType.ACTIVE) FASTEST_ACTIVE_LOCATION_INTERVAL_MS else FASTEST_IDLE_LOCATION_INTERVAL_MS
        val locationRequest =
            LocationRequest.Builder(priority, interval).setMinUpdateIntervalMillis(fastestInterval)
                .build()
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.removeLocationUpdates(locationCallback) // Ensure previous updates are stopped
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
                isLocationUpdatesActiveCurrently = true
            } else {
                isLocationUpdatesActiveCurrently = false
                _userLocationFlow.value = null; _currentSpeedFlow.value =
                    0f; _userHeadingFlow.value =
                    0f
                _gnssStatusTextFlow.value = "GNSS Permission Denied"
            }
        } catch (e: SecurityException) {
            isLocationUpdatesActiveCurrently = false
            _userLocationFlow.value = null; _currentSpeedFlow.value = 0f; _userHeadingFlow.value =
                0f
            _gnssStatusTextFlow.value = "GNSS Error (Security)"
            Log.e(TAG, "SecurityException in updateLocationRequestIfNeeded: ${e.message}")
        }
    }

    private fun isLocationClientUpdating(): Boolean = isLocationUpdatesActiveCurrently

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                lastKnownSpeedMps = location.speed
                // lastKnownLatLng, lastKnownBearing, lastKnownTimestamp are effectively set via StateFlows

                _currentSpeedFlow.value = location.speed
                _userLocationFlow.value = LatLng(location.latitude, location.longitude)
                _userHeadingFlow.value = location.bearing

                myCurrentVehicleData = VehicleData(
                    deviceId = deviceId,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speed = location.speed,
                    bearing = location.bearing,
                    timestamp = location.time
                )

                val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                val currentHdopVal =
                    currentHdop // Use a local copy for consistency within this block
                _gnssStatusTextFlow.value = when {
                    accuracy <= 10f -> "Strong Signal (Acc: ${accuracy.format(1)}m)"
                    accuracy <= 25f -> "Moderate Signal (Acc: ${accuracy.format(1)}m)"
                    accuracy != Float.MAX_VALUE -> "Weak Signal (Acc: ${accuracy.format(1)}m)"
                    currentHdopVal != null && currentHdopVal <= 2.0f -> "Strong Signal (HDOP: ${
                        currentHdopVal.format(
                            1
                        )
                    })"

                    currentHdopVal != null && currentHdopVal <= 5.0f -> "Moderate Signal (HDOP: ${
                        currentHdopVal.format(
                            1
                        )
                    })"

                    currentHdopVal != null -> "Weak Signal (HDOP: ${currentHdopVal.format(1)})"
                    else -> "Awaiting Fix"
                }
                evaluateAndUpdateIntervalType()
                runCollisionChecks()
            }
        }
    }

    private fun runCollisionChecks() {
        val currentSelfData = myCurrentVehicleData
        if (currentSelfData == null || otherVehicles.isEmpty()) {
            if (_collisionAlertFlow.value != null) {
                _collisionAlertFlow.value = null
            }
            return
        }

        var activeAlert: CollisionAlert? = null
        for (peerData in otherVehicles.values) {
            val alert = CollisionPredictor.checkPotentialCollision(currentSelfData, peerData)
            if (alert != null) {
                Log.w(
                    TAG,
                    "Potential Collision Detected with ${peerData.deviceId} in ${alert.timeToCollisionSeconds}s"
                )
                activeAlert = alert
                break
            }
        }
        if (_collisionAlertFlow.value != activeAlert) { // Only update if changed
            _collisionAlertFlow.value = activeAlert
        }
    }

    @RequiresApi(Build.VERSION_CODES.N) // NMEA listener itself requires N
    private fun startNmeaListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                _gnssStatusTextFlow.value = "NMEA: Permission Denied"
                return
            }
            try {
                nmeaListener?.let { locationManager.removeNmeaListener(it) } // Remove if already added
                nmeaListener = OnNmeaMessageListener { message, _ -> parseNmeaSentence(message) }
                locationManager.addNmeaListener(ContextCompat.getMainExecutor(this), nmeaListener!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting NMEA listener: ${e.message}", e)
                _gnssStatusTextFlow.value = "NMEA: Error"
            }
        } else {
            // This case might not be reached if onCreate is restricted to TIRAMISU
            _gnssStatusTextFlow.value = "NMEA: Not Supported (SDK < N)"
        }
    }

    private fun stopNmeaListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && nmeaListener != null) {
            try {
                locationManager.removeNmeaListener(nmeaListener!!)
                nmeaListener = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping NMEA listener: ${e.message}", e)
            }
        }
    }

    private fun parseNmeaSentence(nmeaSentence: String) {
        val parts = nmeaSentence.split(","); if (parts.isEmpty()) return
        var changedDop = false
        try {
            val newHdop = when (parts[0]) {
                "\$GPGSA", "\$GNGSA" -> parts.getOrNull(16)?.toFloatOrNull()
                "\$GPGGA", "\$GNGGA" -> parts.getOrNull(8)?.toFloatOrNull()
                else -> null
            }
            newHdop?.let {
                if (it > 0 && it != currentHdop) { // Ensure HDOP is valid and changed
                    currentHdop = it
                    changedDop = true
                }
            }

            if (changedDop && _userLocationFlow.value == null) { // Only update status via HDOP if no location fix
                currentHdop?.let { hdopValue -> // Use hdopValue for clarity
                    _gnssStatusTextFlow.value = when {
                        hdopValue <= 2.0f -> "Strong (HDOP: ${hdopValue.format(1)})" // Corrected line
                        hdopValue <= 5.0f -> "Moderate (HDOP: ${hdopValue.format(1)})" // Corrected line
                        else -> "Weak (HDOP: ${hdopValue.format(1)})" // Corrected line
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "NMEA parse error for '$nmeaSentence': ${e.message}")
        }
    }


    private fun evaluateAndUpdateIntervalType() {
        val desiredType =
            if (lastKnownSpeedMps < STATIONARY_SPEED_THRESHOLD_MPS && otherVehicles.isEmpty()) LocationUpdateIntervalType.IDLE else LocationUpdateIntervalType.ACTIVE
        updateLocationRequestIfNeeded(desiredType)
    }

    private fun registerSensorListeners() {
        accelerometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        magnetometer?.let {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }
    }

    private fun unregisterSensorListeners() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) { /* Handle if needed */
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Handle if needed */
    }

    private fun startP2pDataSendingScheduler() {
        dataSendingScheduler?.shutdownNow() // Ensure only one scheduler runs
        dataSendingScheduler = Executors.newSingleThreadScheduledExecutor()
        dataSendingScheduler?.scheduleAtFixedRate(
            { sendRegularVehicleDataUpdate() },
            0,
            P2P_DATA_SEND_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopP2pDataSendingScheduler() {
        dataSendingScheduler?.shutdownNow()
        dataSendingScheduler = null
    }

    private fun sendRegularVehicleDataUpdate() {
        myCurrentVehicleData?.let { myData ->
            p2pCommunicationManager?.let { p2pManager -> // Use local variable for clarity
                val currentGOAddress = groupOwnerAddress // Cache for thread safety
                if (isThisDeviceGroupOwner && currentGOAddress != null) {
                    p2pManager.broadcastDataToClients(myData)
                } else if (!isThisDeviceGroupOwner && currentGOAddress != null) {
                    p2pManager.sendDataToGroupOwner(myData, currentGOAddress)
                }
            }
        }
    }

    fun sendSosBroadcast(sosData: VehicleData) {
        p2pCommunicationManager?.let { p2pManager ->
            updateLocationRequestIfNeeded(LocationUpdateIntervalType.ACTIVE) // Ensure active location for SOS
            val currentGOAddress = groupOwnerAddress
            if (isThisDeviceGroupOwner && currentGOAddress != null) {
                p2pManager.broadcastDataToClients(sosData)
            } else if (!isThisDeviceGroupOwner && currentGOAddress != null) {
                p2pManager.sendDataToGroupOwner(sosData, currentGOAddress)
            } else {
                Log.w(TAG, "Cannot send SOS: P2P group not formed or GO address unknown.")
            }
        } ?: Log.e(TAG, "P2P Manager not available to send SOS.")
    }

    // --- Custom Location Methods ---

    /**
     * Sets a custom location for the service.
     * This will update the userLocationFlow, currentSpeedFlow, userHeadingFlow,
     * and myCurrentVehicleData with the provided coordinates.
     * Real GPS updates, if active, may override this location.
     *
     * @param latitude The latitude of the custom location.
     * @param longitude The longitude of the custom location.
     * @param provider Optional provider name for the mock location.
     * @param locationName Optional name for the custom location, used in GNSS status.
     * @param speedMps Optional speed in meters per second for the mock location (defaults to 0f).
     * @param bearing Optional bearing in degrees for the mock location (defaults to 0f).
     */
    fun setCustomLocation(
        latitude: Double,
        longitude: Double,
        provider: String = "manual",
        locationName: String = "Custom Location",
        speedMps: Float = 0f,
        bearing: Float = 0f
    ) {
        Log.i(TAG, "Setting custom location: $locationName ($latitude, $longitude)")

        _userLocationFlow.value = LatLng(latitude, longitude)
        _currentSpeedFlow.value = speedMps
        _userHeadingFlow.value = bearing
        lastKnownSpeedMps = speedMps // Keep this in sync for evaluateAndUpdateIntervalType

        myCurrentVehicleData = VehicleData(
            deviceId = if (::deviceId.isInitialized) deviceId else "unknown_device_custom_loc",
            latitude = latitude,
            longitude = longitude,
            speed = speedMps,
            bearing = bearing,
            timestamp = System.currentTimeMillis()
        )

        _gnssStatusTextFlow.value = "Custom Location: $locationName"

        // It's important that evaluateAndUpdateIntervalType and runCollisionChecks are called
        // as the state of the vehicle (location, speed) has changed.
        evaluateAndUpdateIntervalType()
        runCollisionChecks()
    }

    /**
     * Sets the service's current reported location to Hyderabad, India.
     * This is a convenience method that calls setCustomLocation with Hyderabad's coordinates.
     */
    fun setHyderabadAsCurrentLocation() {
        // Coordinates for Hyderabad, India (approximate: 17.3850° N, 78.4867° E)
        val hyderabadLatitude = 17.3850
        val hyderabadLongitude = 78.4867
        setCustomLocation(
            latitude = hyderabadLatitude,
            longitude = hyderabadLongitude,
            locationName = "Hyderabad"
            // speedMps and bearing will default to 0f as per setCustomLocation definition
        )
        Log.i(TAG, "Current location set to Hyderabad.")
    }

    // --- End Custom Location Methods ---

    // --- P2pCommunicationManager.OnDataReceivedListener Callbacks ---
    override fun onVehicleDataReceived(data: VehicleData) {
        if (data.deviceId != deviceId) { // Ensure not processing own rebroadcasted data
            otherVehicles[data.deviceId] = data
            _nearbyVehiclesFlow.value = otherVehicles.values.toList() // Update StateFlow
            updateConnectivityStatusText() // Update status based on potentially new peer count
            evaluateAndUpdateIntervalType() // Location needs might change with peers
            runCollisionChecks()
        }
    }

    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        this.isThisDeviceGroupOwner = wifiP2pInfo.isGroupOwner
        this.groupOwnerAddress =
            if (wifiP2pInfo.groupFormed) wifiP2pInfo.groupOwnerAddress else null

        updateConnectivityStatusText(wifiP2pInfo) // Update status based on connection info

        if (wifiP2pInfo.groupFormed) {
            Log.i(
                TAG,
                "P2P Group formed. GO: $isThisDeviceGroupOwner, GO Address: $groupOwnerAddress"
            )
            if (isThisDeviceGroupOwner) {
                p2pCommunicationManager?.startServerToReceiveData()
            }
            // If client, P2pCommunicationManager should handle connection to GO's server
        } else {
            Log.i(TAG, "P2P Group NOT formed or dissolved.")
            otherVehicles.clear()
            _nearbyVehiclesFlow.value = emptyList()
            // Consider if discoverPeers() should be called here, but be mindful of loops.
        }
        evaluateAndUpdateIntervalType()
    }

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        Log.d(TAG, "P2P Event: Peers changed. Count: ${peers.size}")
        val currentConnectivityStatus = _connectivityStatusTextFlow.value

        if (groupOwnerAddress == null && p2pCommunicationManager != null) { // If not in a group
            if (peers.isNotEmpty()) {
                // More robust peer selection could be implemented here
                val peerToConnect = peers.firstOrNull { it.status == WifiP2pDevice.AVAILABLE }
                peerToConnect?.let {
                    Log.i(TAG, "Attempting P2P connection to: ${it.deviceName}")
                    p2pCommunicationManager?.connectToDevice(it)
                    _connectivityStatusTextFlow.value =
                        "P2P Connecting to ${it.deviceName.take(15)}..."
                } ?: run {
                    if (!currentConnectivityStatus.contains("Creating group")) {
                        Log.i(
                            TAG,
                            "No available peers to connect to. Attempting to create P2P group."
                        )
                        p2pCommunicationManager?.createGroup()
                        _connectivityStatusTextFlow.value = "P2P Creating group..."
                    }
                }
            } else { // No peers found
                if (!currentConnectivityStatus.contains("Creating group") && !currentConnectivityStatus.contains(
                        "Connecting"
                    )
                ) { // Avoid if already trying
                    Log.i(TAG, "No peers found. Attempting to create P2P group.")
                    p2pCommunicationManager?.createGroup()
                    _connectivityStatusTextFlow.value = "P2P Creating group..."
                }
            }
        } else if (groupOwnerAddress != null) {
            // Already in a group, connectivity status text will be updated by updateConnectivityStatusText()
            updateConnectivityStatusText()
        }

        // Update discovery status text only if we are truly in a discovering state
        if (groupOwnerAddress == null && currentConnectivityStatus.startsWith("P2P Discovering")) {
            _connectivityStatusTextFlow.value =
                if (peers.isEmpty()) "P2P Disc. (No peers)" else "P2P Disc. (Peers: ${peers.size})"
        }
    }

    override fun onP2pStatusChanged(isEnabled: Boolean) {
        if (isEnabled) {
            Log.i(TAG, "P2P Wi-Fi Direct is now enabled.")
            if (p2pCommunicationManager == null) {
                // Guard the setupP2pManager call if it's restricted by API level
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setupP2pManager()
                } else {
                    _connectivityStatusTextFlow.value = "P2P Setup Skipped (SDK < TIRAMISU)"
                    // Handle the case where P2P cannot be set up on older devices
                    // if setupP2pManager is strictly for TIRAMISU+
                    return
                }
            }
            // Start discovery only if the manager is successfully set up
            p2pCommunicationManager?.discoverPeers()

            val currentStatus = _connectivityStatusTextFlow.value
            if (!currentStatus.contains("Connecting") && !currentStatus.contains("Group Owner") && !currentStatus.contains(
                    "Client"
                ) && !currentStatus.contains("Creating group") && currentStatus != "P2P Setup Skipped (SDK < TIRAMISU)"
            ) {
                _connectivityStatusTextFlow.value = "P2P Discovering..."
            }
        } else {
            Log.w(TAG, "P2P Wi-Fi Direct is now disabled.")
            _connectivityStatusTextFlow.value = "P2P Wi-Fi Direct Disabled"
            otherVehicles.clear(); _nearbyVehiclesFlow.value = emptyList()
            groupOwnerAddress = null; isThisDeviceGroupOwner = false
            p2pCommunicationManager?.stopServerAndDisconnect() // Clean up P2P state
        }
        evaluateAndUpdateIntervalType()
    }

    private fun updateConnectivityStatusText(wifiP2pInfo: WifiP2pInfo? = null) {
        val currentGroupFormed = wifiP2pInfo?.groupFormed ?: (groupOwnerAddress != null)
        val currentIsGo = wifiP2pInfo?.isGroupOwner ?: isThisDeviceGroupOwner
        val currentStatus = _connectivityStatusTextFlow.value

        val newStatus = when {
            currentGroupFormed && currentIsGo -> "P2P Group Owner (Peers: ${otherVehicles.size})"
            currentGroupFormed && !currentIsGo -> "P2P Client (Peers: ${otherVehicles.size})"
            // Preserve critical states, otherwise update based on discovery/initialization
            currentStatus.startsWith("P2P Wi-Fi Direct Disabled") -> currentStatus
            currentStatus.startsWith("P2P Init Failed") -> currentStatus
            currentStatus.contains("Connecting to") -> currentStatus
            currentStatus.contains("Creating group") -> currentStatus
            else -> if (p2pCommunicationManager == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && _connectivityStatusTextFlow.value == "P2P Setup Skipped (SDK < TIRAMISU)") {
                "P2P Setup Skipped (SDK < TIRAMISU)"
            } else if (p2pCommunicationManager == null) {
                "P2P Initializing..." // If manager still null
            } else {
                "P2P Discovering..." // Default active state
            }
        }
        if (_connectivityStatusTextFlow.value != newStatus) {
            _connectivityStatusTextFlow.value = newStatus
        }
    }


    override fun onBind(intent: Intent?): IBinder = binder // Nullable intent for Service

    // Remove @RequiresApi(Build.VERSION_CODES.TIRAMISU) from onStartCommand
    // as startForeground and location updates should not be restricted this way.
    // Guard specific TIRAMISU+ calls internally if needed.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(
            TAG,
            "NavigationService onStartCommand. Updates active: $isLocationUpdatesActiveCurrently"
        )

        // These should generally run every time the service is (re)started with startService()
        // to ensure it's properly set up as a foreground service and starts its work.
        startServiceInForeground() // Ensure it becomes a foreground service
        updateLocationRequestIfNeeded(currentIntervalType) // Start or restart location updates

        // Initialize P2P manager if not already done and if API level permits for its setup
        if (p2pCommunicationManager == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Assuming setupP2pManager needs TIRAMISU
                setupP2pManager()
            } else {
                // Log or set status if P2P setup is skipped on older devices due to API restrictions
                Log.w(
                    TAG,
                    "P2P Manager setup skipped on SDK ${Build.VERSION.SDK_INT} due to TIRAMISU requirement on setupP2pManager."
                )
                _connectivityStatusTextFlow.value = "P2P Requires Android 13+"
            }
        }

        return START_STICKY // Or START_REDELIVER_INTENT, depending on desired restart behavior
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NavigationService onDestroy")
        try {
            if (isLocationUpdatesActiveCurrently) { // Only remove if active
                fusedLocationClient.removeLocationUpdates(locationCallback)
                isLocationUpdatesActiveCurrently = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates in onDestroy: ${e.message}")
        }
        unregisterSensorListeners()
        stopNmeaListener()
        p2pCommunicationManager?.close() // Ensure this properly unregisters, disconnects, closes sockets
        p2pCommunicationManager = null // Nullify the reference
        stopP2pDataSendingScheduler() // Ensure scheduler is stopped
        dataSendingScheduler = null

        _gnssStatusTextFlow.value = "GNSS Service Stopped"
        _connectivityStatusTextFlow.value = "P2P Service Stopped"
        Log.i(TAG, "All resources in NavigationService should be released.")
    }
}

private fun P2pCommunicationManager?.stopServerAndDisconnect() {
    TODO("Not yet implemented")
}

// Extension function for formatting Float to a specific number of decimal digits.
fun Float.format(digits: Int): String = "%.${digits}f".format(this)


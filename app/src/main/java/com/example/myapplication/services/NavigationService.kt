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
import com.example.myapplication.P2pCommunicationManager
import com.example.myapplication.R
import com.example.myapplication.data.VehicleData
import com.example.myapplication.util.AppPreferences
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
    P2pCommunicationManager.OnDataReceivedListener { // Implements the defined listener

    @Inject lateinit var fusedLocationClient: FusedLocationProviderClient
    @Inject lateinit var sensorManager: SensorManager
    @Inject lateinit var locationManager: LocationManager
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var p2pCommunicationManagerFactory: P2pCommunicationManager.Factory

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

    private var dataSendingScheduler: ScheduledExecutorService? = null
    private var isLocationUpdatesActiveCurrently = false

    private enum class LocationUpdateIntervalType { ACTIVE, IDLE }
    private var currentIntervalType: LocationUpdateIntervalType = LocationUpdateIntervalType.ACTIVE
    private var lastKnownSpeedMps: Float = 0f
    private var lastKnownLatLng: LatLng? = null
    private var lastKnownBearing: Float = 0f
    private var lastKnownTimestamp: Long = 0L

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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        deviceId = AppPreferences.getAppInstanceId(this)
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        createNotificationChannel()
        setupP2pManager() // This will now use the factory and pass 'this' as listener
        updateLocationRequestIfNeeded(LocationUpdateIntervalType.ACTIVE)
        registerSensorListeners()
        startNmeaListener()
        startServiceInForeground()
        startP2pDataSendingScheduler()

        Log.d(TAG, "NavigationService Created with Device ID: $deviceId")
        _gnssStatusTextFlow.value = "Awaiting Fix"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            channel.description = "Channel for ongoing navigation and P2P communication status."
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created: $NOTIFICATION_CHANNEL_ID")
        }
    }

    private fun startServiceInForeground() {
        val pendingIntent: PendingIntent =
            Intent(this, MainActivity::class.java).let { notificationIntent ->
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cooperative Navigation Active")
            .setContentText("Sharing location and sensor data.")
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        try {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
            Log.d(TAG, "Foreground service started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}", e)
            stopSelf()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun setupP2pManager() {
        try {
            p2pCommunicationManager = p2pCommunicationManagerFactory.create(this) // 'this' is the OnDataReceivedListener
            p2pCommunicationManager?.registerReceiver()
            p2pCommunicationManager?.discoverPeers()
            _connectivityStatusTextFlow.value = "P2P Discovering..."
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup P2P Manager: ${e.message}", e)
            _connectivityStatusTextFlow.value = "P2P Init Failed: ${e.localizedMessage}"
        }
    }

    private fun updateLocationRequestIfNeeded(newType: LocationUpdateIntervalType) {
        if (newType == currentIntervalType && isLocationClientUpdating()) return
        currentIntervalType = newType
        val priority = Priority.PRIORITY_HIGH_ACCURACY
        val interval = if (newType == LocationUpdateIntervalType.ACTIVE) ACTIVE_LOCATION_INTERVAL_MS else IDLE_LOCATION_INTERVAL_MS
        val fastestInterval = if (newType == LocationUpdateIntervalType.ACTIVE) FASTEST_ACTIVE_LOCATION_INTERVAL_MS else FASTEST_IDLE_LOCATION_INTERVAL_MS

        val locationRequest = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .build()

        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                isLocationUpdatesActiveCurrently = true
                Log.d(TAG, "Requested location updates: $newType, Interval: $interval ms, Priority: $priority")
                _gnssStatusTextFlow.value = if (_userLocationFlow.value == null) "Awaiting Fix" else _gnssStatusTextFlow.value
            } else {
                isLocationUpdatesActiveCurrently = false
                Log.e(TAG, "Location permission not granted. Cannot request updates.")
                _userLocationFlow.value = null; _currentSpeedFlow.value = 0f; _userHeadingFlow.value = 0f
                _gnssStatusTextFlow.value = "GNSS Permission Denied"
            }
        } catch (e: SecurityException) {
            isLocationUpdatesActiveCurrently = false
            Log.e(TAG, "SecurityException while updating location requests: ${e.message}")
            _gnssStatusTextFlow.value = "GNSS Error (Security)"
        }
    }

    private fun isLocationClientUpdating(): Boolean = isLocationUpdatesActiveCurrently

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                lastKnownSpeedMps = location.speed
                lastKnownLatLng = LatLng(location.latitude, location.longitude)
                lastKnownBearing = location.bearing
                lastKnownTimestamp = location.time

                _currentSpeedFlow.value = location.speed
                _userLocationFlow.value = lastKnownLatLng
                _userHeadingFlow.value = location.bearing

                val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE
                _gnssStatusTextFlow.value = when {
                    accuracy <= 10f -> "Strong Signal (Accuracy: ${accuracy.format(1)}m)"
                    accuracy <= 25f -> "Moderate Signal (Accuracy: ${accuracy.format(1)}m)"
                    accuracy != Float.MAX_VALUE -> "Weak Signal (Accuracy: ${accuracy.format(1)}m)"
                    currentHdop != null && currentHdop!! <= 2.0f -> "Strong Signal (HDOP: ${currentHdop!!.format(1)})"
                    currentHdop != null && currentHdop!! <= 5.0f -> "Moderate Signal (HDOP: ${currentHdop!!.format(1)})"
                    currentHdop != null -> "Weak Signal (HDOP: ${currentHdop!!.format(1)})"
                    else -> "Awaiting Fix"
                }
                evaluateAndUpdateIntervalType()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun startNmeaListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "ACCESS_FINE_LOCATION permission not granted, cannot start NMEA listener.")
                return
            }
            nmeaListener = OnNmeaMessageListener { message, _ -> parseNmeaSentence(message) }
            locationManager.addNmeaListener(ContextCompat.getMainExecutor(this), nmeaListener!!)
            Log.d(TAG, "NMEA listener started.")
        }
    }

    private fun stopNmeaListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && nmeaListener != null) {
            locationManager.removeNmeaListener(nmeaListener!!); nmeaListener = null
            Log.d(TAG, "NMEA listener stopped.")
        }
    }

    private fun parseNmeaSentence(nmeaSentence: String) {
        val parts = nmeaSentence.split(",")
        if (parts.isEmpty()) return
        var changedDop = false
        try {
            when (parts[0]) {
                "\$GPGSA", "\$GNGSA" -> {
                    if (parts.size > 16) {
                        val newHdop = parts.getOrNull(16)?.toFloatOrNull()
                        if (newHdop != null && newHdop != currentHdop && newHdop > 0) { currentHdop = newHdop; changedDop = true }
                    }
                }
                "\$GPGGA", "\$GNGGA" -> {
                    if (parts.size > 8) {
                        val newHdop = parts.getOrNull(8)?.toFloatOrNull()
                        if (newHdop != null && newHdop != currentHdop && newHdop > 0) { currentHdop = newHdop; changedDop = true }
                    }
                }
            }
            if (changedDop && _userLocationFlow.value == null) { // Only update from HDOP if no fused location yet
                Log.d(TAG, "DOP Update: HDOP=$currentHdop")
                currentHdop?.let {
                    _gnssStatusTextFlow.value = when {
                        it <= 2.0f -> "Strong Signal (HDOP: ${it.format(1)})"
                        it <= 5.0f -> "Moderate Signal (HDOP: ${it.format(1)})"
                        else -> "Weak Signal (HDOP: ${it.format(1)})"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing NMEA sentence \'$nmeaSentence\': ${e.message}")
        }
    }

    private fun evaluateAndUpdateIntervalType() {
        val isStationary = lastKnownSpeedMps < STATIONARY_SPEED_THRESHOLD_MPS
        val peersNearby = otherVehicles.isNotEmpty()
        val desiredType = if (isStationary && !peersNearby) LocationUpdateIntervalType.IDLE else LocationUpdateIntervalType.ACTIVE
        updateLocationRequestIfNeeded(desiredType)
    }

    private fun registerSensorListeners() {
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        magnetometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onSensorChanged(event: SensorEvent?) {}
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startP2pDataSendingScheduler() {
        dataSendingScheduler?.shutdownNow()
        dataSendingScheduler = Executors.newSingleThreadScheduledExecutor()
        dataSendingScheduler?.scheduleAtFixedRate({
            sendRegularVehicleDataUpdate()
        }, 0, P2P_DATA_SEND_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun sendRegularVehicleDataUpdate() {
        lastKnownLatLng?.let { latLng ->
            val myData = VehicleData(deviceId, latLng.latitude, latLng.longitude, lastKnownSpeedMps, lastKnownBearing, lastKnownTimestamp, emptyList(), false, null, null)
            p2pCommunicationManager?.let {
                if (isThisDeviceGroupOwner && groupOwnerAddress != null) it.broadcastDataToClients(myData) // GO broadcasts
                else if (!isThisDeviceGroupOwner && groupOwnerAddress != null) it.sendDataToGroupOwner(myData, groupOwnerAddress!!) // Client sends to GO
            }
        }
    }

    fun sendSosBroadcast(sosData: VehicleData) {
        p2pCommunicationManager?.let {
            updateLocationRequestIfNeeded(LocationUpdateIntervalType.ACTIVE)
            Log.d(TAG, "Attempting to send SOS broadcast as ${if (isThisDeviceGroupOwner) "Group Owner" else "Client"}")
            if (isThisDeviceGroupOwner && groupOwnerAddress != null) it.broadcastDataToClients(sosData)
            else if (!isThisDeviceGroupOwner && groupOwnerAddress != null) it.sendDataToGroupOwner(sosData, groupOwnerAddress!!)
            else Log.w(TAG, "Cannot send SOS: P2P group not formed or GO address unknown.")
        } ?: Log.e(TAG, "P2P Manager not available to send SOS.")
    }

    // --- P2pCommunicationManager.OnDataReceivedListener Implementation --- 
    override fun onVehicleDataReceived(data: VehicleData) {
        if (data.deviceId != deviceId) {
            otherVehicles[data.deviceId] = data
            _nearbyVehiclesFlow.value = otherVehicles.values.toList()
            Log.d(TAG, "P2P Data: Received VehicleData from ${data.deviceId}. Total unique peers: ${otherVehicles.size}")
            updateConnectivityStatusText()
            evaluateAndUpdateIntervalType() // Re-evaluate if peers nearby changes things
        }
    }

    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        Log.d(TAG, "P2P Event: Connection Info Available. Group Formed: ${wifiP2pInfo.groupFormed}, Is GO: ${wifiP2pInfo.isGroupOwner}")
        this.isThisDeviceGroupOwner = wifiP2pInfo.isGroupOwner
        this.groupOwnerAddress = if(wifiP2pInfo.groupFormed) wifiP2pInfo.groupOwnerAddress else null
        
        updateConnectivityStatusText(wifiP2pInfo)

        if (wifiP2pInfo.groupFormed) {
            if (isThisDeviceGroupOwner) {
                p2pCommunicationManager?.startServerToReceiveData()
                 _connectivityStatusTextFlow.value = "P2P Group Owner (Peers: ${otherVehicles.size})"
            } else {
                // Client logic: if groupOwnerAddress is available, can try to send data
                // Server (GO) will accept connections from clients
                 _connectivityStatusTextFlow.value = "P2P Client (Connected to GO)"
            }
        } else {
            // Group not formed or dissolved
            otherVehicles.clear()
            _nearbyVehiclesFlow.value = emptyList()
            _connectivityStatusTextFlow.value = "P2P Discovering..."
            // p2pCommunicationManager?.discoverPeers() // Optionally restart discovery if group dissolved. Be careful of loops.
        }
        evaluateAndUpdateIntervalType()
    }
    
    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        Log.d(TAG, "P2P Event: Peers changed. Count: ${peers.size}")
        // You might want to update some UI element or internal state based on available peers
        // For now, just logging. If you need to connect to a peer from this list, initiate here.
        // Note: 'otherVehicles' map is populated on actual VehicleData reception, not just peer discovery.
        if (peers.isEmpty() && groupOwnerAddress == null) {
             _connectivityStatusTextFlow.value = "P2P Discovering (No peers found yet)..."
        }
    }

    override fun onP2pStatusChanged(isEnabled: Boolean) {
        Log.d(TAG, "P2P Event: Wi-Fi P2P status changed. Enabled: $isEnabled")
        if (isEnabled) {
            if(p2pCommunicationManager == null) { // Safety check if called before setupP2pManager finishes
                 setupP2pManager()
            } else {
                p2pCommunicationManager?.discoverPeers() // Start discovery if P2P is enabled
            }
            _connectivityStatusTextFlow.value = "P2P Discovering..."
        } else {
            _connectivityStatusTextFlow.value = "P2P Wi-Fi Direct Disabled"
            otherVehicles.clear()
            _nearbyVehiclesFlow.value = emptyList()
            groupOwnerAddress = null
            isThisDeviceGroupOwner = false
        }
    }
    // --- End P2pCommunicationManager.OnDataReceivedListener --- 

    private fun updateConnectivityStatusText(wifiP2pInfo: WifiP2pInfo? = null) {
        val currentGroupFormed = wifiP2pInfo?.groupFormed ?: (groupOwnerAddress != null)
        val currentIsGo = wifiP2pInfo?.isGroupOwner ?: isThisDeviceGroupOwner

        val statusText = if (currentGroupFormed) {
            if (currentIsGo) "P2P Group Owner (Peers: ${otherVehicles.size})"
            else "P2P Client (Connected, Peers: ${otherVehicles.size})"
        } else {
             if (_connectivityStatusTextFlow.value.startsWith("P2P Wi-Fi Direct Disabled")) {
                 _connectivityStatusTextFlow.value // Keep this status
             } else if (_connectivityStatusTextFlow.value.startsWith("P2P Init Failed")){
                 _connectivityStatusTextFlow.value // Keep error
             } else {
                 "P2P Discovering..."
             }
        }
        _connectivityStatusTextFlow.value = statusText
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "NavigationService onStartCommand, flags: $flags, startId: $startId")
        if (!isLocationUpdatesActiveCurrently) {
            Log.d(TAG, "Re-initiating foreground requirements in onStartCommand")
            createNotificationChannel()
            startServiceInForeground()
            updateLocationRequestIfNeeded(currentIntervalType)
            // If P2P manager wasn't setup (e.g. service killed and restarted by system before onCreate finishes fully)
            if (p2pCommunicationManager == null) setupP2pManager()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NavigationService Destroying...")
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdatesActiveCurrently = false
        } catch (e: Exception) { Log.e(TAG, "Error removing location updates on destroy: ${e.message}") }
        sensorManager.unregisterListener(this)
        stopNmeaListener()
        p2pCommunicationManager?.close()
        dataSendingScheduler?.shutdownNow()
        _gnssStatusTextFlow.value = "GNSS Service Stopped"
        _connectivityStatusTextFlow.value = "P2P Service Stopped"
        Log.d(TAG, "NavigationService Destroyed")
    }
}

fun Float.format(digits: Int) = "%.${digits}f".format(this)

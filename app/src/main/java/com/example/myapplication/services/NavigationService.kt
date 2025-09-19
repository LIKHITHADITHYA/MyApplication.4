package com.example.myapplication.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
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
import com.example.myapplication.GnssDataProvider
import com.example.myapplication.MainActivity
import com.example.myapplication.P2pCommunicationManager
import com.example.myapplication.VehicleData
import com.example.myapplication.core.ExtendedKalmanFilter
import com.example.myapplication.core.ProximityAlertEngine
import com.example.myapplication.models.DeviceData
import com.example.myapplication.ui.gps.NearbyVehicle
import com.example.myapplication.ui.main.DashboardViewModel
import com.example.myapplication.utils.NotificationHelper
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NavigationService : Service(), SensorEventListener, P2pCommunicationManager.OnDataReceivedListener {

    private val binder = LocalBinder()
    private var viewModel: DashboardViewModel? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: android.hardware.SensorManager
    lateinit var p2pCommunicationManager: P2pCommunicationManager

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
    private var isGnssCurrentlyActive = false // Added for isGnssActivePublic

    inner class LocalBinder : Binder() {
        fun getService(): NavigationService = this@NavigationService
    }

    fun setViewModel(viewModel: DashboardViewModel) {
        this.viewModel = viewModel
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                Log.d(TAG, "New GPS location: ${location.latitude}, ${location.longitude}, Time: ${location.time}")

                ekf.update(location) // EKF handles initialization
                val fusedLocation = ekf.getLocation() // Gets EKF fused location, speed, bearing
                Log.d(TAG, "EKF Fused location: ${fusedLocation.latitude}, ${fusedLocation.longitude}, Speed: ${fusedLocation.speed}, Bearing: ${fusedLocation.bearing}, Time: ${fusedLocation.time}")

                val newLatLng = LatLng(fusedLocation.latitude, fusedLocation.longitude)

                viewModel?.updateCurrentUserLocation(newLatLng)
                viewModel?.updateCurrentSpeed(fusedLocation.speed)
                viewModel?.updateCurrentHeading(fusedLocation.bearing)
                viewModel?.updateGnssData("Lat: ${String.format("%.4f", newLatLng.latitude)}, Lon: ${String.format("%.4f", newLatLng.longitude)}")
                viewModel?.updateConnectionStatus("Connected - EKF Fused")

                currentUserVehicleData = VehicleData(
                    deviceId = "myDevice",
                    timestamp = fusedLocation.time, // Use EKF time
                    latitude = fusedLocation.latitude,
                    longitude = fusedLocation.longitude,
                    speed = fusedLocation.speed,
                    bearing = fusedLocation.bearing,
                    accelerometerData = lastAccelerometerReading,
                    gyroscopeData = lastGyroscopeReading
                )

                if (!isGroupOwner && groupOwnerAddress != null) {
                    currentUserVehicleData?.let { p2pCommunicationManager.sendData(it, groupOwnerAddress!!) }
                }
                checkProximityAndNotify()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service creating...")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager

        notificationHelper = NotificationHelper(this)
        proximityAlertEngine = ProximityAlertEngine(this)
        collisionPredictionEngine = CollisionPredictionEngine()
        dataFusionEngine = DataFusionEngine()

        createNotificationChannel()
        val notification = createNotification().build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Service started in foreground.")

        serviceScope.launch {
            wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            wifiP2pChannel = wifiP2pManager?.initialize(this@NavigationService, Looper.getMainLooper(), null)

            if (wifiP2pManager == null || wifiP2pChannel == null) {
                Log.w(TAG, "Wi-Fi Direct feature NOT available on this device.")
                return@launch
            }

            p2pCommunicationManager = P2pCommunicationManager(this@NavigationService, wifiP2pManager!!, wifiP2pChannel!!, this@NavigationService)
            p2pCommunicationManager.registerReceiver()
            p2pCommunicationManager.discoverPeers()
            Log.d(TAG, "P2pCommunicationManager initialized.")
        }

        startLocationUpdates()
        registerSensorListeners()
    }

    fun startLocationUpdates() { // Changed to public
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000) // 1 second interval
            .setMinUpdateIntervalMillis(500) // Minimum interval 0.5 seconds
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Location permissions not granted.")
            isGnssCurrentlyActive = false
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d(TAG, "Starting location updates")
        isGnssCurrentlyActive = true // Added
    }

    fun stopLocationUpdates() { // Added method
        isGnssCurrentlyActive = false // Added
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Stopping location updates")
    }

    fun isGnssActivePublic(): Boolean { // Added method
        return isGnssCurrentlyActive
    }

    private fun registerSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) // Consider SENSOR_DELAY_FASTEST for EKF
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) // Consider SENSOR_DELAY_FASTEST for EKF
        }
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
                // Log.d(TAG, "IMU Update: Accel=${lastAccelerometerReading.joinToString(",")}, Gyro=${lastGyroscopeReading.joinToString(",")}, Timestamp=${it.timestamp}")
                ekf.predict(lastAccelerometerReading, lastGyroscopeReading, it.timestamp)
                // Optionally, could update viewmodel with EKF state more frequently here if needed,
                // but it might be too much. Current design updates ViewModel on GPS fix (onLocationResult).
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder = binder

    private fun createNotification(): NotificationCompat.Builder {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingIntentFlags)
        return NotificationCompat.Builder(this, "NAVIGATION_SERVICE_CHANNEL")
            .setContentTitle("Navigation Service")
            .setContentText("Sharing location data...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("NAVIGATION_SERVICE_CHANNEL", "Navigation Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        serviceScope.cancel()
        isGnssCurrentlyActive = false // Added
        fusedLocationClient.removeLocationUpdates(locationCallback)
        p2pCommunicationManager.close()
        sensorManager.unregisterListener(this) // Unregister sensor listeners
        Log.d(TAG, "Service destroyed.")
    }

    override fun onVehicleDataReceived(data: VehicleData) {
        Log.d(TAG, "Received VehicleData via P2P: ${data.deviceId}, Bearing: ${data.bearing}")
        otherVehicles[data.deviceId] = data
        updateNearbyVehiclesInViewModel()
        checkProximityAndNotify()
    }

    private fun updateNearbyVehiclesInViewModel() {
        val nearbyVehiclesList = otherVehicles.values.map { vehicleData ->
            NearbyVehicle(
                id = vehicleData.deviceId,
                location = LatLng(vehicleData.latitude, vehicleData.longitude),
                name = "Vehicle ${vehicleData.deviceId}",
                heading = vehicleData.bearing
            )
        }
        viewModel?.updateNearbyVehicles(nearbyVehiclesList)
    }

    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        Log.d(TAG, "Connection Info Available: Group Owner: ${wifiP2pInfo.isGroupOwner}")
        this.isGroupOwner = wifiP2pInfo.isGroupOwner
        this.groupOwnerAddress = wifiP2pInfo.groupOwnerAddress
        if (wifiP2pInfo.groupFormed && wifiP2pInfo.isGroupOwner) {
            p2pCommunicationManager.startReceivingData()
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
            viewModel?.updateProximityStatus(ProximityAlertEngine.ProximityStatus.ALL_CLEAR)
            viewModel?.updateFusedGroupData(myDeviceData) // Fused data is just self if no others and self exists
            notificationHelper.cancelNotification(PROXIMITY_NOTIFICATION_ID)
            notificationHelper.cancelNotification(COLLISION_NOTIFICATION_ID)
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
            viewModel?.updateProximityStatus(ProximityAlertEngine.ProximityStatus.ALL_CLEAR)
            viewModel?.updateFusedGroupData(myDeviceData) // Fused data is just self
            notificationHelper.cancelNotification(PROXIMITY_NOTIFICATION_ID)
            notificationHelper.cancelNotification(COLLISION_NOTIFICATION_ID)
            return
        }

        val allDeviceDataForFusion = mutableListOf<DeviceData>()
        allDeviceDataForFusion.add(myDeviceData)
        allDeviceDataForFusion.addAll(otherDeviceDataList)

        val fusedDeviceData = dataFusionEngine.fuseData(allDeviceDataForFusion) 
        if (fusedDeviceData != null) {
            Log.i(TAG, "DataFusionEngine output: $fusedDeviceData")
            viewModel?.updateFusedGroupData(fusedDeviceData)
        } else {
            Log.w(TAG, "DataFusionEngine returned null for a list of size ${allDeviceDataForFusion.size}")
            viewModel?.updateFusedGroupData(null) 
        }

        var collisionPredictedThisCycle = false
        for (otherDevice in otherDeviceDataList) {
            if (collisionPredictionEngine.predictCollision(myDeviceData, otherDevice)) {
                Log.w(TAG, "Collision predicted with ${otherDevice.deviceId}")
                notificationHelper.sendNotification(
                    COLLISION_NOTIFICATION_ID,
                    "IMMINENT COLLISION WARNING!",
                    "High risk of collision with ${otherDevice.deviceId}. Take evasive action!"
                )
                collisionPredictedThisCycle = true
                break 
            }
        }

        if (collisionPredictedThisCycle) {
            notificationHelper.cancelNotification(PROXIMITY_NOTIFICATION_ID)
            return 
        }

        val proximityStatus = proximityAlertEngine.checkProximity(myDataSnapshot, otherDeviceDataList)
        viewModel?.updateProximityStatus(proximityStatus)

        when (proximityStatus) {
            ProximityAlertEngine.ProximityStatus.DANGER_ZONE -> {
                notificationHelper.sendNotification(
                    PROXIMITY_NOTIFICATION_ID,
                    "DANGER ZONE!",
                    "Vehicle nearby in DANGER zone. Immediate caution advised."
                )
            }
            ProximityAlertEngine.ProximityStatus.CAUTION_ZONE -> {
                notificationHelper.sendNotification(
                    PROXIMITY_NOTIFICATION_ID,
                    "CAUTION ZONE",
                    "Vehicle approaching in CAUTION zone. Be aware."
                )
            }
            ProximityAlertEngine.ProximityStatus.ALL_CLEAR -> {
                notificationHelper.cancelNotification(PROXIMITY_NOTIFICATION_ID)
            }
        }
        notificationHelper.cancelNotification(COLLISION_NOTIFICATION_ID) 
    }

    companion object {
        private const val TAG = "NavigationService"
        private const val NOTIFICATION_ID = 1
        private const val PROXIMITY_NOTIFICATION_ID = 2
        private const val COLLISION_NOTIFICATION_ID = 3
    }
}

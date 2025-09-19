package com.example.myapplication.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.example.myapplication.DataFusionEngine
import com.example.myapplication.GnssDataProvider
import com.example.myapplication.MainActivity
import com.example.myapplication.VehicleData
import com.example.myapplication.core.CollisionPredictionEngine
import com.example.myapplication.core.ExtendedKalmanFilter
import com.example.myapplication.core.SensorFusionEngine
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class NavigationService : Service(), SensorEventListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private lateinit var gnssDataProvider: GnssDataProvider
    private lateinit var sensorManager: SensorManager

    private val ekf = ExtendedKalmanFilter()
    private val sensorFusionEngine = SensorFusionEngine()
    private val collisionPredictionEngine = CollisionPredictionEngine()
    private val proximityAlertEngine = ProximityAlertEngine()
    private val dataFusionEngine = DataFusionEngine() // Assume initialized elsewhere or with default data

    private var lastAccelerometerReading = FloatArray(3)
    private var lastGyroscopeReading = FloatArray(3)

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private lateinit var wifiDirectReceiver: BroadcastReceiver

    private var lastKnownLocation: Location? = null

    // Constants
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val PROXIMITY_ALERT_INTENT = "com.example.myapplication.PROXIMITY_ALERT_INTENT"
        private const val COLLISION_WARNING_CHANNEL_ID = "COLLISION_WARNING_CHANNEL"
        private const val PROXIMITY_WARNING_CHANNEL_ID = "PROXIMITY_WARNING_CHANNEL"
        private const val TTC_WARNING_THRESHOLD = 5.0 // Time To Collision in seconds
    }

    // Location Callback
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { location ->
                Log.d("NavigationService", "New location: ${location.latitude}, ${location.longitude}")
                lastKnownLocation = location

                ekf.update(location)
                val fusedLocation = ekf.getLocation()
                Log.d("NavigationService", "Fused location: ${fusedLocation.latitude}, ${fusedLocation.longitude}")

                // Simulate data from other devices
                val otherDeviceData1 = VehicleData(1, fusedLocation.latitude + 0.0001, fusedLocation.longitude + 0.0001, 10f, 45f)
                val otherDeviceData2 = VehicleData(2, fusedLocation.latitude - 0.00005, fusedLocation.longitude + 0.00005, 15f, 135f)
                val otherDevicesData = listOf(otherDeviceData1, otherDeviceData2)

                // Data Fusion
                val fusedOtherDeviceData = dataFusionEngine.fuseData(otherDevicesData)

                // Collision Prediction
                val myDeviceData = VehicleData(0, fusedLocation.latitude, fusedLocation.longitude, fusedLocation.speed, fusedLocation.bearing)
                val ttc = collisionPredictionEngine.predictCollision(myDeviceData, fusedOtherDeviceData)

                if (ttc != null && ttc < TTC_WARNING_THRESHOLD) {
                    // Trigger collision warning notification
                    showCollisionWarningNotification("Collision Warning! TTC: %.1f seconds".format(ttc))
                }

                // Proximity Alerting
                val proximityStatus = proximityAlertEngine.checkProximity(myDeviceData, fusedOtherDeviceData)
                when (proximityStatus) {
                    ProximityAlertEngine.ProximityStatus.DANGER_ZONE -> showProximityWarningNotification("Danger Zone! Another vehicle is very close.")
                    ProximityAlertEngine.ProximityStatus.CAUTION_ZONE -> showProximityWarningNotification("Caution Zone! Another vehicle is nearby.")
                    ProximityAlertEngine.ProximityStatus.ALL_CLEAR -> Unit // No notification needed
                }

                // Add proximity alert only once for the initial location
                if (ActivityCompat.checkSelfPermission(
                        this@NavigationService,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // This creates a PendingIntent that will be broadcast when the device enters/exits the proximity area
                    // For a real app, you'd calculate a meaningful proximity location and radius
                    if (lastKnownLocation != null) {
                         // Add proximity alert only if not already added and we have a location
                        val intent = Intent(PROXIMITY_ALERT_INTENT)
                        val pendingIntent = getProximityAlertPendingIntent()
                        locationManager.addProximityAlert(
                            lastKnownLocation!!.latitude,
                            lastKnownLocation!!.longitude,
                            100f, // Radius in meters
                            -1, // Never expire
                            pendingIntent
                        )
                        Log.d("NavigationService", "Proximity alert added for ${lastKnownLocation!!.latitude}, ${lastKnownLocation!!.longitude}")
                    }
                }

                logData(location)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("NavigationService", "Service creating...")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Initialize notification channels
        createNotificationChannel()
        createCollisionWarningNotificationChannel()
        createProximityWarningNotificationChannel()

        // Start service in foreground
        val notification = createNotification().build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d("NavigationService", "Service started in foreground (type: location).")

        serviceScope.launch {
            // Initialize GNSS Data Provider on a background thread
            gnssDataProvider = GnssDataProvider(this@NavigationService)
            gnssDataProvider.start()
            Log.d("NavigationService", "GnssDataProvider initialized and started on background thread.")

            // Initialize Wi-Fi Direct on a background thread
            initWifiDirect()
            Log.d("NavigationService", "Background initialization tasks finished.")
        }

        startLocationUpdates()
        registerSensorListeners()
    }

    private fun initWifiDirect() {
        wifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        wifiP2pChannel = wifiP2pManager?.initialize(this, Looper.getMainLooper(), null)

        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.w("NavigationService", "Wi-Fi Direct feature NOT available on this device.")
            return
        }
        Log.d("NavigationService", "Wi-Fi P2P Manager and Channel initialized and assigned successfully.")

        wifiDirectReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.d("NavigationService", "Wi-Fi Direct feature IS reported as available.")
                            // Wifi P2P is enabled
                        } else {
                            Log.d("NavigationService", "Wi-Fi Direct feature IS NOT reported as available.")
                            // Wi-Fi P2P is not enabled
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Request available peers from the WifiP2pManager. This is an asynchronous call.
                        // We do not have permission to access location data for Wi-Fi Direct,
                        // which is required for requesting peers on API 29+.
                        if (ActivityCompat.checkSelfPermission(
                                this@NavigationService,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                                this@NavigationService,
                                Manifest.permission.NEARBY_WIFI_DEVICES
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            Log.w("NavigationService", "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct peer request.")
                            return
                        }
                        wifiP2pManager?.requestPeers(wifiP2pChannel) { peers ->
                            Log.d("NavigationService", "Peer list: ${peers.deviceList.size} peers found.")
                            // Process peer list
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        // Respond to new connection or disconnections
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        // Respond to this device's wifi state changing
                    }
                }
            }
        }
        registerReceiver(wifiDirectReceiver, IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        })
        Log.d("NavigationService", "Wi-Fi Direct receiver registered.")

        // Initiate peer discovery (optional, can be triggered by user)
        discoverPeers()
    }


    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 1000
        ) // Update every 1 second
            .setMinUpdateIntervalMillis(500) // Smallest displacement between updates
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("NavigationService", "Location permissions not granted. Cannot start location updates.")
            return
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        Log.d("NavigationService", "Starting location updates")
    }

    private fun registerSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w("NavigationService", "Accelerometer not available.")

        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w("NavigationService", "Gyroscope not available.")
    }

    private fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("NavigationService", "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct discovery.")
            // TODO: Consider informing the user or taking them to settings
            return
        }

        wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("NavigationService", "Peer discovery initiated successfully.")
            }

            override fun onFailure(reason: Int) {
                Log.w("NavigationService", "Peer discovery initiation failed: $reason")
            }
        })
    }

    private fun showCollisionWarningNotification(message: String) {
        val notification = NotificationCompat.Builder(this, COLLISION_WARNING_CHANNEL_ID)
            .setContentTitle("Collision Alert!")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()

        getSystemService(NotificationManager::class.java).notify(2, notification)
    }

    private fun showProximityWarningNotification(message: String) {
        val notification = NotificationCompat.Builder(this, PROXIMITY_WARNING_CHANNEL_ID)
            .setContentTitle("Proximity Alert")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        getSystemService(NotificationManager::class.java).notify(3, notification)
    }

    // Helper function for creating proximity alert PendingIntent with appropriate flags
    private fun getProximityAlertPendingIntent(): PendingIntent {
        val intent = Intent(PROXIMITY_ALERT_INTENT)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // FLAG_MUTABLE is needed for addProximityAlert to allow the system to fill in extra data
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> lastAccelerometerReading = it.values
                Sensor.TYPE_GYROSCOPE -> lastGyroscopeReading = it.values
            }
            ekf.predict(lastAccelerometerReading, lastGyroscopeReading, it.timestamp)
            val predictedLocation = ekf.getLocation()
            Log.d("NavigationService", "EKF prediction: ${predictedLocation.latitude}, ${predictedLocation.longitude}")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logData(location: Location) {
        val logFile = File(getExternalFilesDir(null), "navigation_log.txt")
        // Ensure parent directory exists
        logFile.parentFile?.mkdirs()
        logFile.appendText("Loc: ${location.latitude}, ${location.longitude}, Spd: ${location.speed}
")
    }

    private fun createNotification(): NotificationCompat.Builder {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingIntentFlags
        )
        return NotificationCompat.Builder(this, "NAVIGATION_SERVICE_CHANNEL")
            .setContentTitle("Navigation Service")
            .setContentText("Running...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("NAVIGATION_SERVICE_CHANNEL", "Navigation Service", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createCollisionWarningNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Collision Warnings"
            val descriptionText = "Notifications for potential collisions"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(COLLISION_WARNING_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createProximityWarningNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Proximity Warnings"
            val descriptionText = "Notifications for proximity alerts"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(PROXIMITY_WARNING_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d("NavigationService", "Service destroying...")
        serviceScope.cancel() // Cancel all coroutines in the scope
        gnssDataProvider.stop()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (wifiP2pManager != null && wifiP2pChannel != null) {
            try {
                unregisterReceiver(wifiDirectReceiver)
                Log.d("NavigationService", "Wi-Fi Direct receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w("NavigationService", "Receiver not registered or already unreg: ${e.message}")
            }
        } else {
            Log.d("NavigationService", "Wi-Fi Direct not initialized; skipping unregistration.")
        }

        val pendingIntent = getProximityAlertPendingIntent()
        locationManager.removeProximityAlert(pendingIntent)
        Log.d("NavigationService", "Proximity alert removed.")

        Log.d("NavigationService", "Service destroyed.")
    }
}

package com.example.myapplication.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.core.CollisionDetectionEngine
import com.example.myapplication.core.SensorFusionEngine
import com.google.android.gms.location.*
import java.io.File

class NavigationService : Service(), SensorEventListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var wifiP2pManager: WifiP2pManager? = null // Changed to nullable
    private var wifiP2pChannel: WifiP2pManager.Channel? = null // Changed to nullable

    private val sensorFusionEngine = SensorFusionEngine()
    private val collisionDetectionEngine = CollisionDetectionEngine()

    private val NOTIFICATION_ID = 1

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let {
                Log.d("NavigationService", "New location: ${it.latitude}, ${it.longitude}")
                val fusedLocation = sensorFusionEngine.processNewLocation(it)
                logData(fusedLocation)
            }
        }
    }

    private val wifiDirectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    Log.d("NavigationService", "Wi-Fi P2P State: ${if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) "Enabled" else "Disabled"}")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    Log.d("NavigationService", "Wi-Fi P2P Peers changed")
                    requestPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    Log.d("NavigationService", "Wi-Fi P2P Connection changed")
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    Log.d("NavigationService", "Wi-Fi P2P This Device changed")
                }
            }
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener {
        Log.d("NavigationService", "Peer list: ${it.deviceList.size} peers found.")
    }

    private fun requestPeers() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) {
            Log.e("NavigationService", "requestPeers: P2P Manager or Channel is null.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("NavigationService", "requestPeers: ACCESS_FINE_LOCATION permission not granted.")
            return
        }
        @SuppressLint("MissingPermission")
        manager.requestPeers(channel, peerListListener)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("NavigationService", "Service creating...")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // Initialize Wi-Fi Direct (P2P)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            Log.d("NavigationService", "Wi-Fi Direct feature IS reported as available.")
            val tempSystemService = getSystemService(Context.WIFI_P2P_SERVICE)

            if (tempSystemService == null) {
                Log.e("NavigationService", "getSystemService(WIFI_P2P_SERVICE) returned NULL straight away.")
            } else if (tempSystemService !is WifiP2pManager) {
                Log.e("NavigationService", "getSystemService(WIFI_P2P_SERVICE) returned a non-WifiP2pManager object: ${tempSystemService.javaClass.name}")
            } else {
                // tempSystemService is now confirmed to be a non-null WifiP2pManager
                val actualManager = tempSystemService // Already cast by 'is WifiP2pManager'
                Log.d("NavigationService", "getSystemService returned WifiP2pManager instance: $actualManager")
                
                try {
                    val tempChannel = actualManager.initialize(this, mainLooper, null)
                    if (tempChannel == null) {
                        Log.e("NavigationService", "actualManager.initialize() returned NULL for the channel.")
                    } else {
                        this.wifiP2pManager = actualManager
                        this.wifiP2pChannel = tempChannel
                        Log.d("NavigationService", "Wi-Fi P2P Manager and Channel initialized and assigned successfully.")
                        registerWifiDirectReceiver()
                        discoverPeers()
                    }
                } catch (e: Exception) {
                    Log.e("NavigationService", "Exception during actualManager.initialize(): ${e.message}", e)
                     // This catch block can help if initialize() itself throws an unexpected error on a seemingly non-null manager
                }
            }
        } else {
            Log.w("NavigationService", "Wi-Fi Direct feature NOT available on this device.")
        }

        startLocationUpdates()
        registerSensorListeners()
        Log.d("NavigationService", "Service created method finished.")
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        Log.d("NavigationService", "Starting location updates")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(200)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    private fun registerSensorListeners() {
        // ... (sensor registration code remains the same)
        Log.d("NavigationService", "Registering sensor listeners for all sensors.")
        val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        for (sensor in sensors) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            // Log.d("NavigationService", "Registered listener for: ${sensor.name}") // Can be verbose
        }
    }

    private fun registerWifiDirectReceiver() {
        if (wifiP2pManager == null || wifiP2pChannel == null) {
            Log.w("NavigationService", "Skipping Wi-Fi Direct receiver registration: P2P not fully initialized.")
            return
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        registerReceiver(wifiDirectReceiver, filter)
        Log.d("NavigationService", "Wi-Fi Direct receiver registered.")
    }

    private fun discoverPeers() {
        val manager = wifiP2pManager
        val channel = wifiP2pChannel
        if (manager == null || channel == null) {
            Log.w("NavigationService", "Cannot discover peers: P2P not fully initialized.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("NavigationService", "discoverPeers: ACCESS_FINE_LOCATION permission not granted.")
            return
        }
        @SuppressLint("MissingPermission")
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("NavigationService", "Peer discovery initiated.")
            }
            override fun onFailure(reason: Int) {
                Log.e("NavigationService", "Peer discovery initiation failed: $reason")
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ... (onStartCommand code remains the same)
        Log.d("NavigationService", "Service starting command...")
        createNotificationChannel()
        val notification = createNotification().build()
        
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundServiceType)
        Log.d("NavigationService", "Service started in foreground (type: $foregroundServiceType).")
        return START_STICKY
    }

    private fun createNotification(): NotificationCompat.Builder {
        // ... (createNotification code remains the same)
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
        // ... (createNotificationChannel code remains the same)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("NAVIGATION_SERVICE_CHANNEL", "Navigation Service", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let { /* Log.v("NavigationService", "Sensor: ${it.sensor.name}, Vals: ${it.values.joinToString()}") */ }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null

    private fun logData(location: Location) {
        // ... (logData code remains the same)
        val logFile = File(getExternalFilesDir(null), "navigation_log.txt")
        logFile.appendText("Loc: ${location.latitude}, ${location.longitude}, Spd: ${location.speed}\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("NavigationService", "Service destroying...")
        fusedLocationClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
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
        Log.d("NavigationService", "Service destroyed.")
    }
}
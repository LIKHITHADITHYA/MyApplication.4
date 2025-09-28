package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.myapplication.data.VehicleData

// Implement the listener interface
class GnssDataSharingService : Service(), P2pCommunicationManager.OnDataReceivedListener {

    // Hilt would inject these if GnssDataSharingService was an @AndroidEntryPoint
    // and P2pCommunicationManager.Factory was injected instead.
    // For now, manual initialization is kept based on current code structure.
    private lateinit var p2pCommunicationManager: P2pCommunicationManager
    private lateinit var wifiP2pManager: WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null // Channel can be null if P2P init fails

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "Wi-Fi P2P Manager not available. Service cannot start P2P features.")
            stopSelf() // Stop service if P2P manager is essential and not available
            return
        }

        channel = wifiP2pManager.initialize(this, mainLooper, null)
        if (channel == null) {
            Log.e(TAG, "Failed to initialize Wi-Fi P2P Channel. P2P features will be disabled.")
            // Decide if service should stop or continue with P2P disabled
        }

        // Corrected instantiation of P2pCommunicationManager
        // Assuming P2pCommunicationManager is NOT managed by Hilt here, and direct instantiation is intended.
        // If Hilt is desired, this service should be an @AndroidEntryPoint and inject P2pCommunicationManager.Factory
        p2pCommunicationManager = P2pCommunicationManager(applicationContext, wifiP2pManager, mainLooper, this)
        
        p2pCommunicationManager.registerReceiver()
        Log.d(TAG, "GnssDataSharingService created.")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Changed from unregisterReceiver to close
        p2pCommunicationManager.close()
        Log.d(TAG, "GnssDataSharingService destroyed.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GNSS Data Sharing")
            .setContentText("Sharing location and sensor data with nearby devices.")
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this mipmap exists
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        p2pCommunicationManager.discoverPeers()
        Log.d(TAG, "GnssDataSharingService is running and discovering peers.")

        return START_STICKY
    }

    // --- P2pCommunicationManager.OnDataReceivedListener implementations --- 
    override fun onVehicleDataReceived(data: VehicleData) {
        Log.d(TAG, "Received VehicleData from another device: ${data.deviceId}")
        // TODO: Handle the received data (e.g., forward to DataFusionEngine or UI)
    }

    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        Log.d(TAG, "Connection info available. Is Group Owner: ${wifiP2pInfo.isGroupOwner}, Group Formed: ${wifiP2pInfo.groupFormed}")
        // TODO: Handle connection changes. E.g., if group owner, start server. If client, connect to GO.
        // This logic is now primarily within P2pCommunicationManager itself based on our previous refactoring.
        // This callback is for the service to react to these changes if needed for its own logic.
    }

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        Log.d(TAG, "Peers changed. Found ${peers.size} peers.")
        // TODO: Update UI or service state based on available peers if needed.
        // P2pCommunicationManager already handles logging this internally.
    }

    override fun onP2pStatusChanged(isEnabled: Boolean) {
        Log.d(TAG, "P2P status changed. Enabled: $isEnabled")
        if (isEnabled) {
            Log.d(TAG, "Wi-Fi P2P is enabled. Discovering peers.")
            p2pCommunicationManager.discoverPeers()
        } else {
            Log.w(TAG, "Wi-Fi P2P is disabled. Peer discovery and P2P communication will not work.")
            // TODO: Handle P2P being disabled (e.g., stop P2P related operations, notify UI)
        }
    }
    // --- End P2pCommunicationManager.OnDataReceivedListener implementations --- 

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "GNSS Data Sharing Service Channel", // More descriptive name
                NotificationManager.IMPORTANCE_LOW 
            )
            serviceChannel.description = "Channel for GNSS Data Sharing Service notifications."
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val TAG = "GnssDataSharingService"
        private const val CHANNEL_ID = "GnssDataSharingServiceChannel"
        private const val NOTIFICATION_ID = 1 // Ensure this ID is unique if you have other foreground services
    }
}

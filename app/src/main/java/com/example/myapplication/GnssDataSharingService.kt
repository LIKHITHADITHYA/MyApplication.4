package com.example.myapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

// Implement the listener interface
class GnssDataSharingService : Service(), P2pCommunicationManager.OnDataReceivedListener {

    private lateinit var p2pCommunicationManager: P2pCommunicationManager
    private lateinit var wifiP2pManager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        // Removed redundant Context qualifier
        wifiP2pManager = getSystemService(WIFI_P2P_SERVICE) as WifiP2pManager
        channel = wifiP2pManager.initialize(this, mainLooper, null)
        // Pass 'this' as the dataListener
        p2pCommunicationManager = P2pCommunicationManager(this, wifiP2pManager, channel, this)
        p2pCommunicationManager.registerReceiver()
        Log.d(TAG, "GnssDataSharingService created.")
    }

    override fun onDestroy() {
        super.onDestroy()
        p2pCommunicationManager.unregisterReceiver()
        Log.d(TAG, "GnssDataSharingService destroyed.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notificationIntent = Intent(this, MainActivity::class.java)

        // Use FLAG_IMMUTABLE directly as minSdk is 24 (higher than M)
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            pendingIntentFlags
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GNSS Data Sharing")
            .setContentText("Sharing location and sensor data with nearby devices.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        // Start discovering peers
        p2pCommunicationManager.discoverPeers()
        Log.d(TAG, "GnssDataSharingService is running and discovering peers.")

        return START_STICKY
    }

    // Add required methods from the listener interface
    override fun onVehicleDataReceived(data: VehicleData) {
        Log.d(TAG, "Received VehicleData from another device: ${data.deviceId}")
        // TODO: Handle the received data
    }

    override fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) {
        Log.d(TAG, "Connection info available. Is Group Owner: ${wifiP2pInfo.isGroupOwner}")
        // TODO: Handle connection changes if needed by this service
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "GNSS Data Sharing",
                NotificationManager.IMPORTANCE_LOW // no sound/vibration
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        private const val TAG = "GnssDataSharingService"
        private const val CHANNEL_ID = "GnssDataSharingServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}

package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.NetworkInfo // Required for checking connection status
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import androidx.core.app.ActivityCompat

@SuppressLint("MissingPermission")
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager?,
    private val channel: WifiP2pManager.Channel,
    private val listener: P2pCommunicationManager.OnDataReceivedListener // Changed to use the listener interface
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action: String = intent.action ?: return
        if (manager == null) {
            Log.w("WifiDirectReceiver", "WifiP2pManager is null in BroadcastReceiver.")
            // Optionally notify listener about P2P being unavailable
            // listener.onP2pStatusChanged(false) 
            return
        }

        when (action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.d("WifiDirectReceiver", "P2P State Changed: ${if (isEnabled) "Enabled" else "Disabled"}")
                listener.onP2pStatusChanged(isEnabled)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d("WifiDirectReceiver", "P2P Peers Changed Action Received.")
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("WifiDirectReceiver", "Permissions (ACCESS_FINE_LOCATION or NEARBY_WIFI_DEVICES) missing to request peers.")
                    // Notify listener that peers cannot be fetched due to permissions
                    listener.onPeersChanged(emptyList()) // Or a specific error/status update
                    return
                }
                manager.requestPeers(channel) { peers ->
                    listener.onPeersChanged(peers.deviceList.toList())
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo: NetworkInfo? = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                Log.d("WifiDirectReceiver", "P2P Connection Changed. Connected: ${networkInfo?.isConnected}")
                if (networkInfo?.isConnected == true) {
                    manager.requestConnectionInfo(channel, listener::onConnectionInfoAvailable)
                } else {
                    Log.d("WifiDirectReceiver", "P2P Disconnected or connection failed.")
                    // Optionally, create a WifiP2pInfo object indicating disconnection or pass null
                    // listener.onConnectionInfoAvailable(null) // Or a specific method for disconnection
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device: WifiP2pDevice? = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                Log.d("WifiDirectReceiver", "This device details changed: ${device?.deviceName}, Status: ${device?.status}")
                // You might want to add a method to OnDataReceivedListener for this if P2pCommunicationManager needs to react
                // For example: listener.onThisDeviceChanged(device)
            }
        }
    }
}

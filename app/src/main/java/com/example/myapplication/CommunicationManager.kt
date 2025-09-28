package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.myapplication.models.DeviceData
import com.google.gson.Gson
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

// Local BroadcastReceiver for CommunicationManager
private class CommunicationManagerWifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val onPeersChangedCallback: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionInfoAvailableCallback: (WifiP2pInfo) -> Unit,
    private val onP2pStateChangedCallback: (Boolean) -> Unit,
    private val onThisDeviceChangedCallback: (WifiP2pDevice?) -> Unit
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                onP2pStateChangedCallback(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w("CommManagerReceiver", "Permissions missing to request peers.")
                    // Optionally, inform the main CommunicationManager class about the missing permission
                    onPeersChangedCallback(emptyList()) // Notify with empty list or a specific error state
                    return
                }
                manager.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                    onPeersChangedCallback(peers?.deviceList?.toList() ?: emptyList())
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    manager.requestConnectionInfo(channel, onConnectionInfoAvailableCallback)
                } else {
                    Log.d("CommManagerReceiver", "P2P Disconnected or connection failed.")
                    // Optionally, notify that connection is lost via a callback if needed
                }
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                onThisDeviceChangedCallback(device)
            }
        }
    }
}

class CommunicationManager(private val context: Context) {

    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    var onPeersChanged: (List<WifiP2pDevice>) -> Unit = {}
    var onDataReceived: (DeviceData) -> Unit = {}
    var onP2pStateChanged: (Boolean) -> Unit = {} // Callback for P2P state changes
    var onThisDeviceChanged: (WifiP2pDevice?) -> Unit = {} // Callback for this device's changes

    private val receiver = CommunicationManagerWifiDirectBroadcastReceiver(
        manager = wifiP2pManager,
        channel = channel,
        onPeersChangedCallback = { peers -> onPeersChanged(peers) },
        onConnectionInfoAvailableCallback = { info ->
            if (info.groupFormed && info.isGroupOwner) {
                Log.d("CommManager", "Device is group owner. Starting server.")
                startServer()
            } else if (info.groupFormed) {
                Log.d("CommManager", "Device is client in P2P group. Group owner address: ${info.groupOwnerAddress?.hostAddress}")
                // Client logic: connect to groupOwnerAddress if needed for sending data directly
            }
        },
        onP2pStateChangedCallback = { isEnabled ->
            onP2pStateChanged(isEnabled)
            Log.d("CommManager", "P2P State changed: ${if(isEnabled) "Enabled" else "Disabled"}")
            if(!isEnabled) {
                // Handle P2P being disabled, e.g., clear peer list, stop server/client ops
                onPeersChanged(emptyList())
            }
        },
        onThisDeviceChangedCallback = { device ->
            onThisDeviceChanged(device)
            Log.d("CommManager", "This device details changed: ${device?.deviceName}, Status: ${device?.status}")
        }
    )

    fun register() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, intentFilter)
        Log.d("CommManager", "Receiver registered.")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(receiver)
            Log.d("CommManager", "Receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.e("CommManager", "Receiver not registered or already unregistered: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("CommManager", "Permissions (ACCESS_FINE_LOCATION or NEARBY_WIFI_DEVICES) not granted for discovering peers.")
            // Optionally, trigger a request for permissions or notify the UI/user
            onP2pStateChanged(false) // Indicate P2P might not be usable
            return
        }
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("CommManager", "Peer discovery initiated.")
            }
            override fun onFailure(reason: Int) {
                Log.e("CommManager", "Peer discovery failed: Code $reason")
                 // Translate reason code to human-readable string if possible
                val reasonText = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P is not supported on this device"
                    WifiP2pManager.ERROR -> "Internal error"
                    WifiP2pManager.BUSY -> "Framework is busy and unable to service the request"
                    else -> "Unknown error"
                }
                Log.e("CommManager", "Peer discovery failed: $reasonText")
                onP2pStateChanged(false) // Indicate P2P discovery failed
            }
        })
    }

    fun broadcastData(data: DeviceData) {
        executor.execute {
            wifiP2pManager.requestConnectionInfo(channel) { info ->
                if (info != null && info.groupFormed && info.groupOwnerAddress != null) {
                    val hostAddress = info.groupOwnerAddress.hostAddress
                    if (hostAddress != null) {
                        try {
                            Log.d("CommManager", "Broadcasting data to $hostAddress")
                            val socket = Socket()
                            // For broadcasting, if this device is the Group Owner, it should send to all connected clients.
                            // If it's a client, it typically sends to the Group Owner.
                            // This implementation sends to the GO address. True broadcast to multiple clients from GO needs different logic.
                            socket.connect(InetSocketAddress(hostAddress, 8888), 500)
                            socket.getOutputStream().write(gson.toJson(data).toByteArray())
                            socket.close()
                            Log.d("CommManager", "Data sent to $hostAddress")
                        } catch (e: IOException) {
                            Log.e("CommManager", "Client error sending data: ${e.message}")
                        }
                    }
                } else {
                    Log.w("CommManager", "Cannot broadcast data: Not connected or no group owner address.")
                }
            }
        }
    }

    private fun startServer() {
        executor.execute {
            try {
                val serverSocket = ServerSocket(8888)
                Log.d("CommManager", "Server started on port 8888.")
                while (Thread.currentThread().isAlive && !serverSocket.isClosed) {
                    try {
                        val client = serverSocket.accept()
                        Log.d("CommManager", "Client connected: ${client.inetAddress.hostAddress}")
                        val inputStream = client.getInputStream()
                        // It's good practice to read the data in a loop or with a defined size limit
                        // to handle partial reads or very large data.
                        val dataString = inputStream.bufferedReader().use { it.readText() }
                        if (dataString.isNotEmpty()){
                            val data = gson.fromJson(dataString, DeviceData::class.java)
                            onDataReceived(data)
                            Log.d("CommManager", "Data received from ${client.inetAddress.hostAddress}")
                        }
                        client.close()
                    } catch (e: IOException) {
                        if (serverSocket.isClosed) {
                            Log.i("CommManager", "Server socket closed, exiting accept loop.")
                            break
                        } else {
                            Log.e("CommManager", "Server error accepting or reading from client: ${e.message}")
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("CommManager", "Server error: ${e.message}")
            } finally {
                Log.d("CommManager", "Server thread finishing.")
            }
        }
    }
}

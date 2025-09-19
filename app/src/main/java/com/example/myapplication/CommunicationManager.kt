package com.example.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
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

class CommunicationManager(private val context: Context) {

    private val wifiP2pManager: WifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
    private val channel: WifiP2pManager.Channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), null)
    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    var onPeersChanged: (List<WifiP2pDevice>) -> Unit = {}
    var onDataReceived: (DeviceData) -> Unit = {}

    private val receiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel,
        onPeersChanged = { peers -> onPeersChanged(peers) },
        onConnectionInfoAvailable = { info ->
            if (info.groupFormed && info.isGroupOwner) {
                startServer()
            } else if (info.groupFormed) {
                // Client logic will be handled implicitly by connection
            }
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
    }

    fun unregister() {
        context.unregisterReceiver(receiver)
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d("CommManager", "Peer discovery initiated.")
            }
            override fun onFailure(reason: Int) {
                Log.e("CommManager", "Peer discovery failed: $reason")
            }
        })
    }

    fun broadcastData(data: DeviceData) {
        executor.execute {
            wifiP2pManager.requestConnectionInfo(channel) { info ->
                if (info != null && info.groupFormed) {
                    val hostAddress = info.groupOwnerAddress.hostAddress
                    if (hostAddress != null) {
                        try {
                            val socket = Socket()
                            socket.connect(InetSocketAddress(hostAddress, 8888), 500)
                            socket.getOutputStream().write(gson.toJson(data).toByteArray())
                            socket.close()
                        } catch (e: IOException) {
                            Log.e("CommManager", "Client error: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun startServer() {
        executor.execute {
            try {
                val serverSocket = ServerSocket(8888)
                while (true) {
                    val client = serverSocket.accept()
                    val inputStream = client.getInputStream()
                    val data = gson.fromJson(inputStream.reader(), DeviceData::class.java)
                    onDataReceived(data)
                    client.close()
                }
            } catch (e: IOException) {
                Log.e("CommManager", "Server error: ${e.message}")
            }
        }
    }
}

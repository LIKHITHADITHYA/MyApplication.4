package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.IntentCompat
import java.io.EOFException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class P2pCommunicationManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val dataListener: OnDataReceivedListener
) {

    interface OnDataReceivedListener {
        fun onVehicleDataReceived(data: VehicleData) // Assuming VehicleData is defined elsewhere and imported if necessary
        fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo)
    }

    private val tag = "P2pCommunicationManager"
    private val serverPort = 8888

    private var serverSocket: ServerSocket? = null
    private val clientSockets = mutableListOf<Socket>()

    private var clientSocket: Socket? = null
    private var clientOutputStream: ObjectOutputStream? = null
    private val clientSocketLock = Any()

    private var isGroupOwner: Boolean = false

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        Log.d(tag, "Wi-Fi Direct is ENABLED.")
                    } else {
                        Log.d(tag, "Wi-Fi Direct is DISABLED.")
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (hasLocationPermissions()) {
                        try {
                            manager.requestPeers(channel, peerListListener)
                        } catch (e: SecurityException) {
                            Log.e(tag, "SecurityException in requestPeers: ${e.message}")
                        }
                    } else {
                        Log.w(tag, "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct peer request.")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager.requestConnectionInfo(channel) { info ->
                        info?.let { dataListener.onConnectionInfoAvailable(it) }
                        Log.d(tag, "Connection info available: Group Owner: ${info?.isGroupOwner}, Group Owner Address: ${info?.groupOwnerAddress}")
                        if (info?.groupFormed == true) {
                            if (info.isGroupOwner) {
                                isGroupOwner = true
                                startReceivingData()
                                Log.d(tag, "Acted as Group Owner and started data receiving server.")
                            } else {
                                isGroupOwner = false
                                Log.d(tag, "Acted as Client.")
                            }
                        } else {
                            Log.d(tag, "Wi-Fi Direct disconnected or group not formed. Stopping data transfer roles.")
                            stopReceivingData()
                            synchronized(clientSocketLock) {
                                stopSendingData()
                            }
                            isGroupOwner = false
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    Log.d(tag, "Device status changed: ${device?.status}")
                }
            }
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peers ->
        val refreshedPeers = peers.deviceList
        if (refreshedPeers.isNotEmpty()) {
            val device = refreshedPeers.first()
            Log.d(tag, "Found peer: ${device.deviceName} - ${device.deviceAddress}. Attempting to connect.")
            connectToPeer(device)
        } else {
            Log.d(tag, "No peers found.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun registerReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
        Log.d(tag, "Wi-Fi Direct receiver registered.")
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(receiver)
        Log.d(tag, "Wi-Fi Direct receiver unregistered.")
    }

    fun discoverPeers() {
        if (!hasLocationPermissions()) {
            Log.w(tag, "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct discovery.")
            return
        }
        try {
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Peer discovery initiated successfully.")
                }
                override fun onFailure(reason: Int) {
                    Log.e(tag, "Peer discovery initiation failed: $reason")
                }
            })
        } catch (e: SecurityException) {
             Log.e(tag, "SecurityException in discoverPeers: ${e.message}")
        }
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        if (!hasLocationPermissions()) {
            Log.w(tag, "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct connection.")
            return
        }
        try {
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "Connection initiated successfully to ${device.deviceAddress}.")
                }
                override fun onFailure(reason: Int) {
                    Log.e(tag, "Connection initiation failed to ${device.deviceAddress}: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException in connect: ${e.message}")
        }
    }

    fun createGroup() {
        if (!hasLocationPermissions()) {
            Log.w(tag, "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct group creation.")
            return
        }
        try {
            manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "P2P group creation initiated successfully.")
                }
                override fun onFailure(reason: Int) {
                    Log.e(tag, "P2P group creation failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException in createGroup: ${e.message}")
        }
    }

    fun removeGroup() {
        if (!hasLocationPermissions()) {
            Log.w(tag, "Missing LOCATION or NEARBY_WIFI_DEVICES permission for Wi-Fi Direct group removal (checking anyway).")
        }
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(tag, "P2P group removal initiated successfully.")
                }
                override fun onFailure(reason: Int) {
                    Log.e(tag, "P2P group removal failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(tag, "SecurityException in removeGroup: ${e.message}")
        }
    }

    fun sendData(data: VehicleData, hostAddress: InetAddress) {
        thread {
            try {
                synchronized(clientSocketLock) {
                    if (clientSocket == null || clientSocket!!.isClosed) {
                        clientSocket = Socket()
                        clientSocket!!.connect(InetSocketAddress(hostAddress, serverPort), 5000)
                        clientOutputStream = ObjectOutputStream(clientSocket!!.getOutputStream())
                        Log.d(tag, "Connected to Group Owner for sending data: $hostAddress")
                    }
                    clientOutputStream?.writeObject(data)
                    clientOutputStream?.flush()
                    Log.d(tag, "VehicleData sent successfully.")
                }
            } catch (e: Exception) {
                Log.e(tag, "Error sending data: ${e.message}")
                e.printStackTrace()
                synchronized(clientSocketLock) {
                    stopSendingData()
                }
            }
        }
    }

    fun startReceivingData() {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            Log.d(tag, "Server socket already running.")
            return
        }
        thread {
            try {
                serverSocket = ServerSocket(serverPort)
                Log.d(tag, "P2P Server started on port $serverPort.")
                while (!Thread.currentThread().isInterrupted && serverSocket?.isClosed == false) {
                    try {
                        val client = serverSocket!!.accept()
                        Log.d(tag, "Client connected from ${client.inetAddress}.")
                        synchronized(clientSockets) {
                            clientSockets.add(client)
                        }
                        thread { handleClient(client) }
                    } catch (e: Exception) {
                        if (serverSocket?.isClosed == false) {
                            Log.e(tag, "Error accepting client connection: ${e.message}")
                        } else {
                            Log.d(tag, "Server socket closed, exiting accept loop.")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error in server setup: ${e.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            val inputStream = ObjectInputStream(client.getInputStream())
            while (client.isConnected && !client.isClosed) {
                try {
                    val receivedData = inputStream.readObject() as? VehicleData
                    receivedData?.let { data ->
                        Log.d(tag, "Received VehicleData: ${data.deviceId}")
                        dataListener.onVehicleDataReceived(data)
                    }
                } catch (_: EOFException) {
                    Log.d(tag, "Client disconnected: ${client.inetAddress}")
                    break 
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling client ${client.inetAddress}: ${e.message}")
        } finally {
            try {
                client.close()
                Log.d(tag, "Closed connection for ${client.inetAddress}")
            } catch (e: Exception) {
                Log.e(tag, "Error closing client socket: ${e.message}")
            }
            synchronized(clientSockets) {
                clientSockets.remove(client)
            }
        }
    }

    fun stopReceivingData() {
        try {
            if (serverSocket != null && !serverSocket!!.isClosed) {
                serverSocket?.close()
                serverSocket = null
                Log.d(tag, "P2P Server stopped.")
            }
            synchronized(clientSockets) {
                clientSockets.toList().forEach { 
                    try {
                        it.close() 
                    } catch (e: Exception) {
                        Log.e(tag, "Error closing client socket during stopReceivingData: ${e.message}")
                    }
                }
                clientSockets.clear()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping server: ${e.message}")
        }
    }

    fun stopSendingData() {
        try {
            clientOutputStream?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing client socket/stream: ${e.message}")
        } finally {
            clientOutputStream = null
            clientSocket = null
            Log.d(tag, "P2P client socket closed.")
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fineLocationGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nearbyWifiDevicesGranted = ActivityCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            fineLocationGranted && nearbyWifiDevicesGranted
        } else {
            fineLocationGranted
        }
    }

    fun close() {
        unregisterReceiver()
        stopReceivingData()
        synchronized(clientSocketLock) {
            stopSendingData()
        }
    }
}

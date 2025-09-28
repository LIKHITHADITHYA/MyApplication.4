package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.myapplication.data.VehicleData // Assuming your VehicleData class path
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import javax.inject.Named // Added import for @Named

class P2pCommunicationManager @AssistedInject constructor(
    @ApplicationContext private val context: Context, // Injected by Hilt
    private val wifiP2pManager: WifiP2pManager?,      // Injected by Hilt
    @Named("mainLooper") private val looper: Looper,   // Injected by Hilt with qualifier
    @Assisted private val listener: OnDataReceivedListener // Provided at runtime by NavigationService
) {

    private var channel: WifiP2pManager.Channel?
    private val intentFilter = IntentFilter()
    private var receiver: WifiDirectBroadcastReceiver? = null // This will now refer to the standalone class
    private val executorService = Executors.newFixedThreadPool(2) // For server and client threads
    private var serverSocket: ServerSocket? = null
    private var clientSockets = mutableListOf<Socket>()

    interface OnDataReceivedListener {
        fun onVehicleDataReceived(data: VehicleData)
        fun onConnectionInfoAvailable(wifiP2pInfo: WifiP2pInfo) // Consistent name
        fun onPeersChanged(peers: List<WifiP2pDevice>)
        fun onP2pStatusChanged(isEnabled: Boolean)
    }

    @AssistedFactory
    interface Factory {
        fun create(listener: OnDataReceivedListener): P2pCommunicationManager
    }

    init {
        if (wifiP2pManager == null) {
            Log.e(TAG, "WifiP2pManager is null. P2P features will not work.")
            this.channel = null
        } else {
            // The looper passed to initialize should be the injected one.
            this.channel = wifiP2pManager.initialize(context, this.looper) { 
                Log.d(TAG, "P2P Channel disconnected. Trying to re-initialize or handle.")
            }
            if (this.channel == null) {
                Log.e(
                    TAG,
                    "Failed to initialize WifiP2pManager.Channel. P2P features may not work."
                )
            }
        }

        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    fun registerReceiver() {
        if (wifiP2pManager == null || channel == null) {
            Log.e(TAG, "Cannot register P2P receiver: WifiP2pManager or Channel is null.")
            return
        }
        if (receiver == null) {
            receiver = WifiDirectBroadcastReceiver(wifiP2pManager, channel!!, this.listener)
            context.registerReceiver(receiver, intentFilter)
            Log.d(TAG, "Wi-Fi Direct receiver registered.")
        } else {
            Log.d(TAG, "Wi-Fi Direct receiver already registered.")
        }
    }

    fun discoverPeers() {
        if (wifiP2pManager == null || channel == null) {
            Log.w(TAG, "Cannot discover peers: WifiP2pManager or Channel is null.")
            listener.onP2pStatusChanged(false) 
            return
        }
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Permissions for discovering peers not granted.")
            return
        }
        wifiP2pManager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery initiated.")
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Peer discovery failed: $reasonCode")
                listener.onP2pStatusChanged(false) 
            }
        })
    }

    val peerListListener = WifiP2pManager.PeerListListener {
        listener.onPeersChanged(it.deviceList.toList())
    }

    val connectionInfoListener = WifiP2pManager.ConnectionInfoListener {
        listener.onConnectionInfoAvailable(it)
        if (it.groupFormed && it.isGroupOwner) {
            startServerToReceiveData()
        } else if (it.groupFormed) {
            // Client connected to a group owner
        }
    }

    fun startServerToReceiveData() {
        if (serverSocket != null && !serverSocket!!.isClosed) {
            Log.d(TAG, "P2P Server already running.")
            return
        }
        executorService.execute {
            try {
                serverSocket = ServerSocket(GROUP_OWNER_PORT)
                Log.d(TAG, "P2P Server started on port $GROUP_OWNER_PORT, waiting for clients.")
                while (!Thread.currentThread().isInterrupted && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        synchronized(clientSockets) {
                            clientSockets.add(clientSocket)
                        }
                        Log.d(TAG, "P2P Client connected from ${clientSocket.inetAddress}")
                        handleClientSocket(clientSocket)
                    } catch (e: IOException) {
                        if (serverSocket?.isClosed == true) {
                            Log.i(TAG, "Server socket closed, exiting accept loop.")
                            break
                        } else {
                            Log.e(TAG, "P2P Server accept error: ${e.message}", e)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "P2P Server failed to start or run: ${e.message}", e)
            } finally {
                Log.d(TAG, "P2P Server thread finishing.")
            }
        }
    }

    private fun handleClientSocket(socket: Socket) {
        executorService.execute {
            try {
                ObjectInputStream(socket.getInputStream()).use { ois ->
                    while (socket.isConnected && !socket.isClosed) {
                        val data = ois.readObject() as? VehicleData
                        data?.let {
                            Log.d(TAG, "P2P Data received from client ${socket.inetAddress}: $it")
                            listener.onVehicleDataReceived(it)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "P2P Error reading from client ${socket.inetAddress}: ${e.message}", e)
            } finally {
                try {
                    socket.close()
                    synchronized(clientSockets) {
                        clientSockets.remove(socket)
                    }
                    Log.d(TAG, "P2P Client socket closed: ${socket.inetAddress}")
                } catch (e: IOException) {
                    Log.e(TAG, "P2P Error closing client socket: ${e.message}")
                }
            }
        }
    }

    fun sendDataToGroupOwner(data: VehicleData, groupOwnerAddress: InetAddress) {
        executorService.execute {
            try {
                Socket().use { socket ->
                    socket.connect(
                        java.net.InetSocketAddress(groupOwnerAddress, GROUP_OWNER_PORT),
                        5000
                    )
                    Log.d(TAG, "P2P Sending data to GO: $data")
                    ObjectOutputStream(socket.getOutputStream()).use { oos ->
                        oos.writeObject(data)
                        oos.flush()
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "P2P Client error sending data to GO: ${e.message}", e)
            }
        }
    }

    fun broadcastDataToClients(data: VehicleData) {
        synchronized(clientSockets) {
            val socketsToRemove = mutableListOf<Socket>()
            clientSockets.forEach { socket ->
                if (socket.isConnected && !socket.isClosed) {
                    executorService.execute {
                        try {
                            ObjectOutputStream(socket.getOutputStream()).use { oos ->
                                oos.writeObject(data)
                                oos.flush()
                                Log.d(TAG, "P2P Broadcasted data to client ${socket.inetAddress}")
                            }
                        } catch (e: IOException) {
                            Log.e(
                                TAG,
                                "P2P Error broadcasting to client ${socket.inetAddress}: ${e.message}"
                            )
                            socketsToRemove.add(socket) 
                        }
                    }
                } else {
                    socketsToRemove.add(socket)
                }
            }
            clientSockets.removeAll(socketsToRemove.toSet())
            socketsToRemove.forEach {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing failed client socket", e)
                }
            }
        }
    }

    fun close() {
        Log.d(TAG, "Closing P2pCommunicationManager.")
        try {
            if (receiver != null) {
                context.unregisterReceiver(receiver)
                receiver = null
                Log.d(TAG, "Wi-Fi Direct receiver unregistered.")
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Error unregistering P2P receiver: ${e.message}")
        }
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
        serverSocket = null
        synchronized(clientSockets) {
            clientSockets.forEach {
                try {
                    it.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing client socket $it", e)
                }
            }
            clientSockets.clear()
        }
        executorService.shutdownNow()
        Log.d(TAG, "P2pCommunicationManager closed.")
    }

    companion object {
        internal const val TAG = "P2pCommunicationManager"
        private const val GROUP_OWNER_PORT = 8988
    }
}

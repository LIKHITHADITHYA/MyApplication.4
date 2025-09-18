package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.io.ObjectOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

class P2pCommunicationManager(
    private val context: Context,
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel
) {

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager.requestPeers(channel, peerListListener)
                }
            }
        }
    }

    private val peerListListener = WifiP2pManager.PeerListListener { peers ->
        val refreshedPeers = peers.deviceList
        // For simplicity, we'll try to connect to the first available peer.
        if (refreshedPeers.isNotEmpty()) {
            val device = refreshedPeers.first()
            connectToPeer(device)
        }
    }

    fun registerReceiver() {
        context.registerReceiver(receiver, intentFilter)
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(receiver)
    }

    fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Peer discovery initiated successfully.
            }

            override fun onFailure(reason: Int) {
                // Peer discovery initiation failed.
            }
        })
    }

    private fun connectToPeer(device: WifiP2pDevice) {
        val config = android.net.wifi.p2p.WifiP2pConfig()
        config.deviceAddress = device.deviceAddress
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Connection initiated successfully.
            }

            override fun onFailure(reason: Int) {
                // Connection initiation failed.
            }
        })
    }

    fun sendData(data: VehicleData, hostAddress: String) {
        thread {
            try {
                val socket = Socket()
                socket.bind(null)
                socket.connect(InetSocketAddress(hostAddress, 8888), 500)
                val outputStream = ObjectOutputStream(socket.getOutputStream())
                outputStream.writeObject(data)
                outputStream.close()
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
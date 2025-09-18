package com.example.myapplication.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.myapplication.core.Alert

class DashboardViewModel : ViewModel() {

    private val _gnssData = MutableLiveData<String>("Waiting for GNSS data...")
    val gnssData: LiveData<String> = _gnssData

    private val _connectionStatus = MutableLiveData<String>("Connection Status: Disconnected")
    val connectionStatus: LiveData<String> = _connectionStatus

    private val _alertStatus = MutableLiveData<Alert>(Alert.Safe)
    val alertStatus: LiveData<Alert> = _alertStatus

    // TODO: Add methods to update LiveData based on service updates and P2P communication

    fun updateGnssData(data: String) {
        _gnssData.postValue(data)
    }

    fun updateConnectionStatus(status: String) {
        _connectionStatus.postValue(status)
    }

    fun updateAlert(alert: Alert) {
        _alertStatus.postValue(alert)
    }
}
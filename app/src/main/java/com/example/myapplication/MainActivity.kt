package com.example.myapplication

import android.Manifest
import android.content.ComponentName
// Removed: import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels // For by viewModels()
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
// Removed: import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.example.myapplication.services.NavigationService
import com.example.myapplication.ui.main.DashboardViewModel
import com.example.myapplication.ui.settings.P2pAction
import com.example.myapplication.ui.settings.SettingsViewModel
import com.google.android.material.bottomnavigation.BottomNavigationView
// Removed: import android.content.Context.BIND_AUTO_CREATE // Added for clarity

// Removed: private val P2pCommunicationManager.createGroup: Any

class MainActivity : AppCompatActivity() {

    private val requestCodePermissions = 101 // Renamed
    private lateinit var navController: NavController

    private var navigationService: NavigationService? = null
    private var isServiceBound = false

    // ViewModels
    private val dashboardViewModel: DashboardViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as NavigationService.LocalBinder
            navigationService = binder.getService()
            isServiceBound = true
            Log.d("MainActivity", "NavigationService connected")
            // Pass ViewModel to the service
            navigationService?.setViewModel(dashboardViewModel)
            // Update initial state for UI if needed (e.g., GNSS button state)
             updateGnssButtonState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            navigationService = null
            isServiceBound = false
            Log.d("MainActivity", "NavigationService disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Setup Navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_nav_view)
        NavigationUI.setupWithNavController(bottomNavigationView, navController)

        requestPermissions() // Request permissions

        // Start and bind to NavigationService
        // Intent to start the service (ensures it runs even if activity is unbound)
        Intent(this, NavigationService::class.java).also { intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        // Intent to bind to the service
        Intent(this, NavigationService::class.java).also { intent ->
            bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE) // Using fully qualified name
        }

        observeSettingsViewModel()
        observeDashboardViewModel() // For updating GNSS button state
    }

    private fun observeSettingsViewModel() {
        settingsViewModel.gnssToggleRequest.observe(this) { shouldStart ->
            if (!isServiceBound || navigationService == null) {
                Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
                return@observe
            }
            if (shouldStart) {
                Log.d("MainActivity", "Requesting to START GNSS")
                navigationService?.startLocationUpdatesPublic()
                Toast.makeText(this, "Starting GNSS...", Toast.LENGTH_SHORT).show()
            } else {
                Log.d("MainActivity", "Requesting to STOP GNSS")
                navigationService?.stopLocationUpdatesPublic() // Corrected
                Toast.makeText(this, "Stopping GNSS...", Toast.LENGTH_SHORT).show()
            }
            // The button state in SettingsFragment will update via observing DashboardViewModel
        }

        settingsViewModel.p2pActionRequest.observe(this) { event ->
            event.getContentIfNotHandled()?.let { action ->
                if (!isServiceBound || navigationService == null || navigationService?.p2pCommunicationManager == null) {
                    Toast.makeText(this, "P2P Service not ready", Toast.LENGTH_SHORT).show()
                    return@let
                }
                Log.d("MainActivity", "P2P Action requested: $action")
                when (action) {
                    P2pAction.DISCOVER_PEERS -> {
                        navigationService?.p2pCommunicationManager?.discoverPeers()
                        Toast.makeText(this, "Discovering peers...", Toast.LENGTH_SHORT).show()
                    }
                    P2pAction.CREATE_GROUP -> {
                        navigationService?.p2pCommunicationManager?.createGroup() // Corrected
                        Toast.makeText(this, "Creating P2P group...", Toast.LENGTH_SHORT).show()
                    }
                    P2pAction.REMOVE_GROUP -> {
                        navigationService?.p2pCommunicationManager?.removeGroup() // Corrected
                        Toast.makeText(this, "Removing P2P group...", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun observeDashboardViewModel() {
        // Observe a more direct GNSS state if available, or derive it.
        // This helps SettingsFragment update its button if MainActivity initiated a change.
        dashboardViewModel.currentUserLocation.observe(this) {
             updateGnssButtonState()
        }
        // We also need to observe the service's actual state for GNSS activity.
        // This is still a bit indirect.
    }

    private fun updateGnssButtonState() {
        // This method is called when service connects or relevant LiveData changes.
        // It's to ensure the SettingsFragment's view model accurately reflects the service state.
        // However, SettingsFragment directly observes DashboardViewModel.currentUserLocation.
        // A dedicated LiveData<Boolean> for isGnssActive in DashboardViewModel,
        // updated by NavigationService, would be cleaner.
        // For now, SettingsFragment should react to DashboardViewModel.currentUserLocation.
        // This function in MainActivity serves as a placeholder for potentially forcing a refresh
        // or if MainActivity itself hosted some UI elements.
        Log.d("MainActivity", "updateGnssButtonState called. Current service GNSS active: ${navigationService?.isGnssActivePublic() ?: "service null"}")
        // SettingsFragment should update its own UI based on DashboardViewModel.
        // No direct UI update from MainActivity here.
    }


    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            Log.d("MainActivity", "NavigationService unbound")
        }
    }

    // Permission handling (existing code)
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Corrected API Level
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, "com.google.android.gms.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add("com.google.android.gms.permission.ACTIVITY_RECOGNITION")
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), requestCodePermissions) // Updated constant name
        } else {
            Log.d("MainActivity", "All required permissions are already granted.")
            // Consider starting/binding service here if permissions are critical for initialization
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestCodePermissions) { // Updated constant name
            var allGranted = true
            grantResults.forEachIndexed { index, result ->
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    Log.w("MainActivity", "Permission denied: ${permissions[index]}")
                }
            }
            if (allGranted) {
                Log.d("MainActivity", "All requested permissions granted by user.")
                // If service start/bind was deferred, do it now.
            } else {
                Log.w("MainActivity", "One or more permissions were denied by the user.")
                Toast.makeText(this, "Some permissions were denied. App functionality may be limited.", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun NavigationService?.startLocationUpdatesPublic() {
    this?.startLocationUpdates()
}

private fun NavigationService?.stopLocationUpdatesPublic() {
    this?.stopLocationUpdates()
}

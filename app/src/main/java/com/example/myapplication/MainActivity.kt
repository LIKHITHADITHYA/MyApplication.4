package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.util.Log

class MainActivity : AppCompatActivity() {

    private val REQUEST_CODE_PERMISSIONS = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request permissions when the activity is created
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            // This permission is for Android 12 (API 31) and above.
            // Earlier versions might not need explicit runtime permission for Wi-Fi Direct.
            // Make sure your build.gradle (module app) has targetSdkVersion 31 or higher
            // to correctly handle this permission.
            permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // Add other dangerous permissions here if needed for early startup,
        // e.g., Manifest.permission.READ_EXTERNAL_STORAGE for log files, but WRITE_EXTERNAL_STORAGE
        // is generally not needed on modern Android for app-specific files.

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            // Permissions already granted, proceed with starting the service or other operations
            Log.d("MainActivity", "All required permissions are already granted.")
            // You might want to start your NavigationService here if it's not started elsewhere
            // For example: startService(Intent(this, NavigationService::class.java))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            var allGranted = true
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false
                    break
                }
            }
            if (allGranted) {
                Log.d("MainActivity", "All requested permissions granted by user.")
                // Permissions granted, you can now safely start your NavigationService
                // startService(Intent(this, NavigationService::class.java))
            } else {
                Log.w("MainActivity", "One or more permissions denied by user.")
                // Handle denied permissions (e.g., show a message, disable features)
            }
        }
    }
}

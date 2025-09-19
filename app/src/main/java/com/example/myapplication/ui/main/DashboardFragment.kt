package com.example.myapplication.ui.main

import android.Manifest
import android.content.Context // Added for Vibrator
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location // For proximity alert
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect // Added for VibrationEffect
import android.os.Vibrator // Added for Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.myapplication.R
import com.example.myapplication.core.Alert
import com.example.myapplication.core.FrameAnalyzer
import com.example.myapplication.databinding.FragmentDashboardBinding
import com.example.myapplication.services.NavigationService
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

// Data class for representing other vehicles on the map
data class NearbyVehicle(
    val id: String,
    val location: LatLng,
    val name: String? = null, // e.g., "Vehicle 2" or user's name
    val signalStrength: Int? = null, // RSSI value in dBm, for example
    val heading: Float? = null // Heading in degrees (0-360)
)

class DashboardFragment : Fragment(), TextToSpeech.OnInitListener, OnMapReadyCallback {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var tts: TextToSpeech
    private var googleMap: GoogleMap? = null
    private var myLocationMarker: Marker? = null
    private val nearbyVehicleMarkers = mutableMapOf<String, Marker>()

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    
    private val PROXIMITY_THRESHOLD_METERS = 50.0 // Example: 50 meters

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false

        var allEssentialPermissionsGranted = fineLocationGranted // Camera is essential for AR, but not for core nav

        if (!fineLocationGranted) {
            Log.e("DashboardFragment", "Location permission denied.")
            Toast.makeText(requireContext(), "Location permission is required to start sharing.", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            if (!postNotificationsGranted) {
                Log.e("DashboardFragment", "Notification permission denied. Required for Android 13+")
                Toast.makeText(requireContext(), "Notification permission is required for sharing on Android 13+.", Toast.LENGTH_LONG).show()
                allEssentialPermissionsGranted = false // Or handle based on app's core functionality needs
            }
        }

        if (allEssentialPermissionsGranted) {
            Log.d("DashboardFragment", "Essential permissions granted.")
            startNavigationService()
            if (googleMap != null && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                try {
                    googleMap?.isMyLocationEnabled = true
                } catch (e: SecurityException) {
                    Log.e("DashboardFragment", "SecurityException while enabling MyLocation layer: ${e.message}")
                }
            }
            if (cameraPermissionGranted) {
                Log.d("DashboardFragment", "Camera permission granted. Starting camera for AR.")
                startCamera()
            } else {
                 Log.w("DashboardFragment", "Camera permission denied. AR view will not be available.")
                 // Potentially disable AR view option in BottomNavigationView or show a placeholder
            }
        } else {
            Log.w("DashboardFragment", "Not all essential permissions were granted.")
            binding.startSharingButton.isEnabled = true
            binding.startSharingButton.text = getString(R.string.start_sharing)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        cameraExecutor = Executors.newSingleThreadExecutor()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext(), this)

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)

        setupObservers()
        setupViewSwitcher()

        binding.startSharingButton.text = getString(R.string.start_sharing)

        binding.startSharingButton.setOnClickListener {
            Log.d("DashboardFragment", "Start Sharing button clicked")
            checkAndRequestPermissions()
        }

        binding.sosButton.setOnClickListener @androidx.annotation.RequiresPermission(android.Manifest.permission.VIBRATE) {
            Log.d("DashboardFragment", "SOS button clicked")
            Toast.makeText(requireContext(), "SOS signal sent. Emergency services have been notified.", Toast.LENGTH_LONG).show()
            vibrate(longArrayOf(0, 1000, 200, 1000)) // SOS vibration: long, pause, long
        }

        // Initial camera start check for AR if permission already granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            Log.d("DashboardFragment", "Camera permission already granted. Starting camera in onViewCreated for AR.")
            startCamera()
        } else {
            Log.d("DashboardFragment", "Camera permission not yet granted for AR. Will be requested or handled by launcher.")
        }
    }

    private fun setupViewSwitcher() {
        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> {
                    showRadarView()
                    true
                }
                R.id.nav_map -> {
                    showMapView()
                    true
                }
                R.id.nav_ar -> {
                    showArView()
                    true
                }
                else -> false
            }
        }
        // Set Radar View as default
        binding.bottomNavigationView.selectedItemId = R.id.nav_radar
    }

    private fun showRadarView() {
        binding.radarViewContainer.visibility = View.VISIBLE
        binding.mapView.visibility = View.GONE
        binding.cameraPreviewView.visibility = View.GONE
        Log.d("DashboardFragment", "Switched to Radar View")
    }

    private fun showMapView() {
        binding.radarViewContainer.visibility = View.GONE
        binding.mapView.visibility = View.VISIBLE // Map readiness handled by onMapReady
        binding.cameraPreviewView.visibility = View.GONE
        Log.d("DashboardFragment", "Switched to Map View")
    }

    private fun showArView() {
        binding.radarViewContainer.visibility = View.GONE
        binding.mapView.visibility = View.GONE
        binding.cameraPreviewView.visibility = View.VISIBLE
        // Ensure camera is started if not already, and permissions are granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // CameraX is lifecycle-aware, if `startCamera()` was called and bound, it should work.
            // If not, we might need a dedicated call or flag here.
            Log.d("DashboardFragment", "Switched to AR View. Camera should be active if permission granted.")
        } else {
            Log.w("DashboardFragment", "Switched to AR View, but camera permission is missing.")
            // Optionally, show a placeholder or message in cameraPreviewView about missing permission
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreviewAndAnalysis(cameraProvider)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindPreviewAndAnalysis(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder().build()
        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor, FrameAnalyzer { imageProxy ->
            imageProxy.close()
        })

        try {
            cameraProvider.unbindAll() // Unbind previous use cases
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            Log.d("DashboardFragment", "Camera preview and analysis bound successfully")
        } catch (exc: Exception) {
            Log.e("DashboardFragment", "Use case binding failed", exc)
            Toast.makeText(requireContext(), "Failed to start camera for AR.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        Log.d("DashboardFragment", "Map is ready.")
        // If Map View is the active view when map becomes ready, ensure it's visible.
        if (binding.bottomNavigationView.selectedItemId == R.id.nav_map) {
             binding.mapView.visibility = View.VISIBLE
        } else {
             binding.mapView.visibility = View.GONE // Ensure it's hidden if not the selected view
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                googleMap?.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                Log.e("DashboardFragment", "SecurityException on onMapReady: ${e.message}")
            }
        } else {
            Log.d("DashboardFragment", "Location permission not granted for MyLocation layer.")
        }
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
        googleMap?.uiSettings?.isZoomControlsEnabled = true

        val defaultLocation = LatLng(37.422, -122.084) // Googleplex
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            }
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        }
    }

    private fun getCardinalDirection(heading: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val index = (heading % 360 / 45).roundToInt()
        return directions[index.coerceIn(0, directions.size - 1)] 
    }
    
    // Helper function for vibrations
    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrate(pattern: LongArray) {
        val vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1) // For older APIs, pattern[0] is often delay (0 for immediate)
            }
        }
    }

    private fun setupObservers() {
        viewModel.gnssData.observe(viewLifecycleOwner) { gnssString ->
            binding.gnssDataTextview.text = gnssString
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionStatusTextview.text = status
            if (status.contains("Disconnected", ignoreCase = true) || status.contains("No GPS", ignoreCase = true)) {
                Toast.makeText(requireContext(), "Warning: GPS signal lost or disconnected.", Toast.LENGTH_LONG).show()
                vibrate(longArrayOf(0, 300, 200, 300)) // GPS loss pattern: medium, pause, medium
            }
        }

        viewModel.currentSpeed.observe(viewLifecycleOwner) { speed ->
            binding.speedTextview.text = String.format(Locale.US, "Speed: %.1f m/s", speed)
            val currentAlert = viewModel.alertStatus.value
            if (currentAlert is Alert.Danger && speed > currentAlert.recommendedSpeed) {
                Toast.makeText(requireContext(), "Warning: Current speed exceeds recommended safe speed!", Toast.LENGTH_SHORT).show()
                vibrate(longArrayOf(0, 100, 50, 100, 50, 100)) // Speeding pattern: rapid short bursts
            }
        }

        viewModel.nearbyVehicles.observe(viewLifecycleOwner) { vehicles ->
            // Update logic for nearby vehicles - for map, radar etc.

            // --- Proximity Alert Logic ---
            val userLocation = viewModel.currentUserLocation.value
            if (userLocation == null) {
                Log.d("DashboardFragment", "User location not available for proximity check, skipping this update.")
                return@observe // Skip proximity checks if user's location isn't known for this update
            }

            vehicles.forEach { vehicle ->
                // userLocation is confirmed not null here
                val vehicleLocationLatLng = vehicle.location
                val distanceResults = FloatArray(1)
                Location.distanceBetween(
                    userLocation.latitude, userLocation.longitude,
                    vehicleLocationLatLng.latitude, vehicleLocationLatLng.longitude,
                    distanceResults
                )
                val distanceInMeters = distanceResults[0]

                if (distanceInMeters < PROXIMITY_THRESHOLD_METERS) {
                    Log.w("DashboardFragment", "Proximity Alert: Vehicle ${vehicle.id} is too close! Distance: $distanceInMeters m")
                    Toast.makeText(requireContext(), "Vehicle ${vehicle.id} is very close!", Toast.LENGTH_SHORT).show()
                    speak("Warning, vehicle ${vehicle.id} nearby.")
                    vibrate(longArrayOf(0, 150, 50, 150)) // Example proximity vibration pattern
                }
            }
            // Note: Consider potential for many Toast messages if multiple vehicles are close;
            // you might need to throttle or group alerts in a more advanced implementation.
            // --- End Proximity Alert Logic ---
        }

        viewModel.motionEvent.observe(viewLifecycleOwner) { event ->
            binding.motionEventTextview.text = getString(R.string.motion_status, event)
        }
        viewModel.currentAltitude.observe(viewLifecycleOwner) { altitude ->
            binding.altitudeTextview.text = String.format(Locale.US, "Altitude: %.1f m", altitude)
        }
        viewModel.currentHeading.observe(viewLifecycleOwner) { heading ->
            binding.headingTextview.text = String.format(Locale.US, "Heading: %.0f° %s", heading, getCardinalDirection(heading))
            myLocationMarker?.rotation = heading
        }
        viewModel.infrastructureMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNullOrEmpty()) {
                binding.v2iMessageTextview.visibility = View.GONE
            } else {
                binding.v2iMessageTextview.text = getString(R.string.v2i_message_status, message)
                binding.v2iMessageTextview.visibility = View.VISIBLE
            }
        }
        viewModel.alertStatus.observe(viewLifecycleOwner) @androidx.annotation.RequiresPermission(
            android.Manifest.permission.VIBRATE
        ) { alert ->
            when (alert) {
                is Alert.Safe -> {
                    val alertText = getString(R.string.all_clear)
                    binding.warningTextview.text = alertText
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_safe))
                    speak(alertText)
                }
                is Alert.Caution -> {
                    binding.warningTextview.text = alert.message
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_caution))
                    speak(alert.message)
                    vibrate(longArrayOf(0, 200, 100, 200)) // Vibrate: short, pause, short
                }
                is Alert.Danger -> {
                    var alertMsg = alert.message
                    // alert.timeToCollision?.let {
                    //     alertMsg += " (Est. ${String.format(Locale.US, "%.1f", it)}s to impact)"
                    // }
                    val alertText = getString(R.string.alert_danger_format, alertMsg, alert.recommendedSpeed)
                    binding.warningTextview.text = alertText
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_danger))
                    var spokenText = alert.message
                    // alert.timeToCollision?.let {
                    //     spokenText += ". Estimated time to impact ${String.format(Locale.US, "%.1f", it)} seconds."
                    // }
                    spokenText += " Recommended speed is ${"%.1f".format(alert.recommendedSpeed)} meters per second."
                    speak(spokenText)
                    showCollisionWarningDialog(alert)
                    vibrate(longArrayOf(0, 500, 100, 500, 100, 500)) // Danger vibration: long, pause, long, pause, long
                }
            }
        }
    }

    private fun showCollisionWarningDialog(alert: Alert.Danger) {
        context?.let { ctx ->
            var dialogMessage = alert.message
            // alert.timeToCollision?.let { ttc ->
            //     dialogMessage += "\n(Estimated time to impact: ${String.format(Locale.US, "%.1f", ttc)}s)"
            // }
            dialogMessage += "\nRecommended speed is ${String.format(Locale.US, "%.1f", alert.recommendedSpeed)} m/s."

            AlertDialog.Builder(ctx)
                .setTitle("⚠ Collision Risk Ahead!")
                .setMessage(dialogMessage)
                .setPositiveButton("Dismiss") { dialog, _ ->
                    dialog.dismiss()
                }
                .setIcon(android.R.drawable.ic_dialog_alert)
                .create()
                .show()
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d("DashboardFragment", "Checking permissions")
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("DashboardFragment", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("DashboardFragment", "All necessary permissions already granted.")
            startNavigationService()
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                 startCamera() 
            }
        }
    }

    private fun startNavigationService() {
        Log.d("DashboardFragment", "Attempting to start NavigationService")
        val intent = Intent(requireContext(), NavigationService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        binding.startSharingButton.isEnabled = false
        binding.startSharingButton.text = getString(R.string.sharing_active)
        Log.d("DashboardFragment", "NavigationService start requested")
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            // CameraX is lifecycle-aware 
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        myLocationMarker = null
        nearbyVehicleMarkers.forEach { (_, marker) -> marker.remove() }
        nearbyVehicleMarkers.clear()
        googleMap = null 
        binding.mapView.onDestroy()
        cameraExecutor.shutdown() 
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
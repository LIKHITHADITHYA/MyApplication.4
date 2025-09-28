package com.example.myapplication.ui.gps

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentGpsBinding
import com.example.myapplication.ui.main.DashboardViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import java.util.Locale
import kotlin.math.roundToInt

// Data class for representing other vehicles on the map
data class NearbyVehicle(
    val id: String,
    val location: LatLng,
    val name: String? = null, // e.g., "Vehicle 2" or user's name
    val signalStrength: Int? = null, // RSSI value in dBm, for example
    val heading: Float? = null // Heading in degrees (0-360)
)

class GpsFragment : Fragment(), OnMapReadyCallback, TextToSpeech.OnInitListener {

    private var _binding: FragmentGpsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels() // Shared ViewModel

    private var googleMap: GoogleMap? = null
    private var myLocationMarker: Marker? = null
    private val nearbyVehicleMarkers = mutableMapOf<String, Marker>()
    private lateinit var tts: TextToSpeech
    private val proximityThresholdMeters = 50.0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGpsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tts = TextToSpeech(requireContext(), this)

        binding.mapViewGps.onCreate(savedInstanceState)
        binding.mapViewGps.getMapAsync(this)

        setupObservers()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        Log.d("GpsFragment", "Map is ready.")

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                googleMap?.isMyLocationEnabled = true
            } catch (e: SecurityException) {
                Log.e("GpsFragment", "SecurityException on onMapReady: ${e.message}")
            }
        } else {
            Log.d("GpsFragment", "Location permission not granted for MyLocation layer.")
        }
        googleMap?.uiSettings?.isMyLocationButtonEnabled = true
        googleMap?.uiSettings?.isZoomControlsEnabled = true

        val defaultLocation = LatLng(37.422, -122.084) 
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
    }

    private fun setupObservers() {
        viewModel.currentUserLocation.observe(viewLifecycleOwner) { latLng ->
            updateMyLocationMarker(latLng)
        }

        viewModel.nearbyVehicles.observe(viewLifecycleOwner) { vehiclesData ->
            try {
                // Explicitly cast to the expected type to catch ClassCastException early
                @Suppress("UNCHECKED_CAST")
                val vehicles = vehiclesData as? List<NearbyVehicle>
                if (vehicles != null) {
                    updateNearbyVehicleMarkers(vehicles)
                    checkProximityAlerts(vehicles)
                } else if (vehiclesData != null) {
                    // Log if data is not null but cast failed
                    Log.e("GpsFragment", "NearbyVehicles LiveData emitted data of unexpected type: ${vehiclesData::class.java.name}")
                    Toast.makeText(context, "Error: Vehicle data type mismatch.", Toast.LENGTH_LONG).show()
                }
            } catch (e: ClassCastException) {
                Log.e("GpsFragment", "Critical Error: Could not cast nearbyVehicles LiveData. Expected List<com.example.myapplication.ui.gps.NearbyVehicle> but received different type. Check data source (e.g., NavigationService).", e)
                Toast.makeText(context, "Error processing vehicle data. Please check logs.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("GpsFragment", "Error observing nearbyVehicles: ${e.message}", e)
            }
        }

        viewModel.currentBearing.observe(viewLifecycleOwner) { heading -> 
            myLocationMarker?.rotation = heading
        }
    }

    private fun updateMyLocationMarker(location: LatLng?) {
        if (googleMap == null) return
        if (location == null) {
            myLocationMarker?.remove()
            myLocationMarker = null
            return
        }
        if (myLocationMarker == null) {
            myLocationMarker = googleMap?.addMarker(
                MarkerOptions()
                    .position(location)
                    .title("My Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
        } else {
            myLocationMarker?.position = location
        }
    }

    private fun updateNearbyVehicleMarkers(vehicles: List<NearbyVehicle>) {
        if (googleMap == null) return

        val newVehicleIds = vehicles.map { it.id }.toSet()
        val markersToRemove = nearbyVehicleMarkers.keys.filterNot { it in newVehicleIds }
        markersToRemove.forEach {
            nearbyVehicleMarkers[it]?.remove()
            nearbyVehicleMarkers.remove(it)
        }

        vehicles.forEach { vehicle ->
            val marker = nearbyVehicleMarkers[vehicle.id]
            if (marker == null) {
                val newMarker = googleMap?.addMarker(
                    MarkerOptions()
                        .position(vehicle.location)
                        .title(vehicle.name ?: "Vehicle ${vehicle.id}")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .alpha(0.8f)
                )
                if (newMarker != null) {
                    nearbyVehicleMarkers[vehicle.id] = newMarker
                    vehicle.heading?.let { newMarker.rotation = it }
                }
            } else {
                marker.position = vehicle.location
                marker.title = vehicle.name ?: "Vehicle ${vehicle.id}"
                vehicle.heading?.let { marker.rotation = it }
            }
        }
    }

    private fun checkProximityAlerts(vehicles: List<NearbyVehicle>) {
        val userLocation = viewModel.currentUserLocation.value ?: return

        vehicles.forEach { vehicle ->
            val distanceResults = FloatArray(1)
            Location.distanceBetween(
                userLocation.latitude, userLocation.longitude,
                vehicle.location.latitude, vehicle.location.longitude,
                distanceResults
            )
            val distanceInMeters = distanceResults[0]

            if (distanceInMeters < proximityThresholdMeters) {
                val message = "Warning, vehicle ${vehicle.name ?: vehicle.id} nearby."
                Log.w("GpsFragment", "Proximity Alert: Vehicle ${vehicle.id} is too close! Distance: $distanceInMeters m")
                Toast.makeText(requireContext(), "Vehicle ${vehicle.name ?: vehicle.id} is very close!", Toast.LENGTH_SHORT).show()
                speak(message)
                vibrate(longArrayOf(0, 150, 50, 150))
            }
        }
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
        if (::tts.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrate(pattern: LongArray) {
        val vibrator = context?.getSystemService(Vibrator::class.java)
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapViewGps.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapViewGps.onPause()
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
        binding.mapViewGps.onDestroy()
        _binding = null
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapViewGps.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapViewGps.onSaveInstanceState(outState)
    }
}

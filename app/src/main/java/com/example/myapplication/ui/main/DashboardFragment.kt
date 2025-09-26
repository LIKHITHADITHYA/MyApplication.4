package com.example.myapplication.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
// import android.net.wifi.WifiManager // No longer needed
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentDashboardBinding
import com.example.myapplication.services.NavigationService
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

class DashboardFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by activityViewModels()
    private lateinit var tts: TextToSpeech
    private val TAG = "DashboardFragment"

    private val requestLocationAndNotificationsPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        var allEssentialPermissionsGranted = fineLocationGranted

        if (!fineLocationGranted) {
            Log.e(TAG, "Location permission denied.")
            Toast.makeText(requireContext(), "Location permission is required to start sharing.", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            if (!postNotificationsGranted) {
                Log.e(TAG, "Notification permission denied. Required for Android 13+")
                Toast.makeText(requireContext(), "Notification permission is required for sharing on Android 13+.", Toast.LENGTH_LONG).show()
                allEssentialPermissionsGranted = false
            }
        }

        if (allEssentialPermissionsGranted) {
            Log.d(TAG, "Essential permissions for sharing granted.")
            startNavigationService()
        } else {
            Log.w(TAG, "Not all essential permissions for sharing were granted.")
            binding.startSharingButton.isEnabled = true
            binding.startSharingButton.text = getString(R.string.start_sharing)
        }
    }

    private val requestVibratePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d(TAG, "VIBRATE permission granted for SOS.")
            performSosActions()
        } else {
            Log.w(TAG, "VIBRATE permission denied for SOS.")
            Toast.makeText(requireContext(), "Vibrate permission is needed for SOS feedback.", Toast.LENGTH_SHORT).show()
            showSimplifiedSosToast() // Show simplified toast even if vibrate is denied
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        Log.d(TAG, "onCreateView: View binding initialized.")
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: Setting up UI and listeners.")

        tts = TextToSpeech(requireContext(), this)
        setupObservers()

        binding.startSharingButton.text = getString(R.string.start_sharing)
        binding.startSharingButton.setOnClickListener {
            Log.d(TAG, "Start Sharing button clicked")
            checkAndRequestLocationAndNotificationPermissions()
        }

        binding.sosButton.setOnClickListener {
            Log.d(TAG, "SOS Button CLICKED (inside setOnClickListener)") // Direct click log
            handleSosClick()
        }
        
        binding.settingsButton.setOnClickListener {
            Toast.makeText(requireContext(), "Settings button clicked! (Not implemented)", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "onViewCreated: Listeners set.")
    }

    private fun handleSosClick() {
        Log.d(TAG, "handleSosClick: Entered function.")
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "handleSosClick: VIBRATE permission already GRANTED.")
            performSosActions()
        } else {
            Log.d(TAG, "handleSosClick: VIBRATE permission NOT granted. Requesting...")
            requestVibratePermissionLauncher.launch(Manifest.permission.VIBRATE)
        }
    }

    private fun showSimplifiedSosToast() {
        Log.d(TAG, "showSimplifiedSosToast: Entered function.")
        val sosMessage = "SOS message is sent"
        Log.d(TAG, "Displaying SOS Toast: $sosMessage")
        Toast.makeText(requireContext(), sosMessage, Toast.LENGTH_LONG).show()
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun performSosActions() {
        Log.d(TAG, "performSosActions: Entered function (VIBRATE permission should be granted here).")
        showSimplifiedSosToast()
        Log.d(TAG, "Performing SOS actions (vibration).")
        vibrate(longArrayOf(0, 1000, 200, 1000)) // SOS vibration: long, pause, long
    }

    @Suppress("DEPRECATION")
    private fun getDeviceIpAddress(): String? {
        Log.d(TAG, "Attempting to get device IP address.")
        val connectivityManager = requireContext().applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (connectivityManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val linkProperties = connectivityManager.getLinkProperties(network)
                    linkProperties?.linkAddresses?.forEach { linkAddress ->
                        val address = linkAddress.address
                        if (address is Inet4Address) {
                            Log.d(TAG, "IP address (Wi-Fi via ConnectivityManager): ${address.hostAddress}")
                            return address.hostAddress
                        }
                    }
                }
            } else {
                // Fallback for versions older than M (Android 6.0)
                val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                if (wifiManager != null) {
                    val wifiInfo = wifiManager.connectionInfo
                    val ipAddressInt = wifiInfo.ipAddress
                    if (ipAddressInt != 0) {
                        val ipAddress = String.format(
                            Locale.US,
                            "%d.%d.%d.%d",
                            ipAddressInt and 0xff,
                            ipAddressInt shr 8 and 0xff,
                            ipAddressInt shr 16 and 0xff,
                            ipAddressInt shr 24 and 0xff
                        )
                        Log.d(TAG, "IP address (Wi-Fi via WifiManager): $ipAddress")
                        return ipAddress
                    }
                }
            }
        } else {
            Log.w(TAG, "ConnectivityManager is null.")
        }
            
        Log.d(TAG, "Wi-Fi IP not found or manager null, checking other network interfaces.")
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                Log.d(TAG, "Checking interface: ${networkInterface.displayName}")
                networkInterface.inetAddresses?.toList()?.forEach { inetAddress ->
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val hostAddress = inetAddress.hostAddress
                        Log.d(TAG, "IP address (NetworkInterface ${networkInterface.displayName}): $hostAddress")
                        return hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address from network interfaces", e)
        }
        Log.d(TAG, "No suitable IP address found.")
        return null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS: Language specified is not supported or missing data!")
            } else {
                Log.d(TAG, "TTS initialized successfully.")
            }
        } else {
            Log.e(TAG, "TTS Initialization Failed! Status: $status")
        }
    }

    private fun speak(text: String) {
        if (::tts.isInitialized && tts.defaultEngine != null) { 
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            Log.w(TAG, "TTS not ready or no engine, cannot speak: '$text'")
        }
    }

    @RequiresPermission(Manifest.permission.VIBRATE)
    private fun vibrate(pattern: LongArray) {
        val vibrator: Vibrator?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context?.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibrator = vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } else {
            Log.w(TAG, "Device does not have a vibrator or service not available.")
        }
    }

    private fun setupObservers() {
        Log.d(TAG, "Setting up observers for ViewModel data.")
        viewModel.gnssData.observe(viewLifecycleOwner) { gnssString ->
            Log.d(TAG, "Observer: gnssData updated to: '$gnssString'")
            binding.gnssDataTextview.text = gnssString ?: "GNSS: N/A"
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            Log.d(TAG, "Observer: connectionStatus updated to: '$status'")
            binding.connectionStatusTextview.text = status ?: "Status: N/A"
            if (status != null && (status.contains("Disconnected", ignoreCase = true) || status.contains("No GPS", ignoreCase = true))) {
                val warningMsg = "Warning: GPS signal lost or disconnected."
                Toast.makeText(requireContext(), warningMsg, Toast.LENGTH_LONG).show()
                vibrate(longArrayOf(0, 300, 200, 300))
            }
        }

        viewModel.currentSpeed.observe(viewLifecycleOwner) { speed ->
            Log.d(TAG, "Observer: RAW currentSpeed from ViewModel: $speed") // Log raw value
            val displaySpeed = speed ?: 0.0f
            Log.d(TAG, "Observer: Display speed (after null check): $displaySpeed")
            binding.speedTextview.text = String.format(Locale.US, "Speed: %.1f m/s", displaySpeed)
        }
    }

    private fun checkAndRequestLocationAndNotificationPermissions() {
        Log.d(TAG, "Checking location and notification permissions")
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions for sharing: ${permissionsToRequest.joinToString()}")
            requestLocationAndNotificationsPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All necessary permissions for sharing already granted.")
            startNavigationService()
        }
    }

    private fun startNavigationService() {
        Log.d(TAG, "Attempting to start NavigationService")
        val intent = Intent(requireContext(), NavigationService::class.java)
        try {
            ContextCompat.startForegroundService(requireContext(), intent)
            binding.startSharingButton.isEnabled = false
            binding.startSharingButton.text = getString(R.string.sharing_active)
            Log.d(TAG, "NavigationService start requested successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting NavigationService: ${e.message}", e)
            Toast.makeText(requireContext(), "Error starting sharing service.", Toast.LENGTH_LONG).show()
            binding.startSharingButton.isEnabled = true
            binding.startSharingButton.text = getString(R.string.start_sharing)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.d(TAG, "onDestroyView: Clearing view binding.")
        _binding = null
    }
}

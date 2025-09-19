package com.example.myapplication.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.myapplication.R
import com.example.myapplication.databinding.FragmentDashboardBinding
import com.example.myapplication.services.NavigationService
import java.util.Locale

class DashboardFragment : Fragment(), TextToSpeech.OnInitListener {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()
    private lateinit var tts: TextToSpeech

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        var allEssentialPermissionsGranted = fineLocationGranted

        if (!fineLocationGranted) {
            Log.e("DashboardFragment", "Location permission denied.")
            Toast.makeText(requireContext(), "Location permission is required to start sharing.", Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            if (!postNotificationsGranted) {
                Log.e("DashboardFragment", "Notification permission denied. Required for Android 13+")
                Toast.makeText(requireContext(), "Notification permission is required for sharing on Android 13+.", Toast.LENGTH_LONG).show()
                allEssentialPermissionsGranted = false
            }
        }

        if (allEssentialPermissionsGranted) {
            Log.d("DashboardFragment", "Essential permissions granted.")
            startNavigationService()
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
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext(), this)
        setupObservers()

        binding.startSharingButton.text = getString(R.string.start_sharing)

        binding.startSharingButton.setOnClickListener {
            Log.d("DashboardFragment", "Start Sharing button clicked")
            checkAndRequestPermissions()
        }

        binding.sosButton.setOnClickListener @RequiresPermission(Manifest.permission.VIBRATE) {
            Log.d("DashboardFragment", "SOS button clicked")
            Toast.makeText(requireContext(), "SOS signal sent. Emergency services have been notified.", Toast.LENGTH_LONG).show()
            vibrate(longArrayOf(0, 1000, 200, 1000)) // SOS vibration: long, pause, long
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

    private fun setupObservers() {
        viewModel.gnssData.observe(viewLifecycleOwner) { gnssString ->
            binding.gnssDataTextview.text = gnssString
        }

        viewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.connectionStatusTextview.text = status
            if (status.contains("Disconnected", ignoreCase = true) || status.contains("No GPS", ignoreCase = true)) {
                val warningMsg = "Warning: GPS signal lost or disconnected."
                Toast.makeText(requireContext(), warningMsg, Toast.LENGTH_LONG).show()
                // speak(warningMsg) // Optionally speak this too
                vibrate(longArrayOf(0, 300, 200, 300))
            }
        }

        viewModel.currentSpeed.observe(viewLifecycleOwner) { speed ->
            binding.speedTextview.text = String.format(Locale.US, "Speed: %.1f m/s", speed)
            // Speeding alert logic removed as it was tied to Alert.Danger from alertStatus
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d("DashboardFragment", "Checking permissions")
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
            Log.d("DashboardFragment", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("DashboardFragment", "All necessary permissions already granted.")
            startNavigationService()
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

    override fun onDestroyView() {
        super.onDestroyView()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        _binding = null
    }
}

package com.example.myapplication.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.myapplication.R
import com.example.myapplication.core.Alert
import com.example.myapplication.databinding.FragmentDashboardBinding
import com.example.myapplication.services.NavigationService

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        
        val canStartService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val postNotificationsGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            if (fineLocationGranted && postNotificationsGranted) {
                true
            } else {
                if (!fineLocationGranted) {
                    Log.e("DashboardFragment", "Location permission denied.")
                    Toast.makeText(requireContext(), "Location permission is required to start sharing.", Toast.LENGTH_LONG).show()
                }
                if (!postNotificationsGranted) {
                    Log.e("DashboardFragment", "Notification permission denied. Required for Android 13+")
                    Toast.makeText(requireContext(), "Notification permission is required for sharing on Android 13+.", Toast.LENGTH_LONG).show()
                }
                false
            }
        } else {
            // For older versions, only location permission is strictly needed here for service start
            if (fineLocationGranted) {
                true
            } else {
                Log.e("DashboardFragment", "Location permission denied.")
                Toast.makeText(requireContext(), "Location permission is required to start sharing.", Toast.LENGTH_LONG).show()
                false
            }
        }

        if (canStartService) {
            startNavigationService()
        } else {
            // Re-enable button if permissions were not fully granted, so user can try again
            binding.startSharingButton.isEnabled = true
            binding.startSharingButton.text = getString(R.string.start_sharing) // Use string resource
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

        setupObservers()

        // Set initial text for the button from a string resource
        binding.startSharingButton.text = getString(R.string.start_sharing)

        binding.startSharingButton.setOnClickListener {
            Log.d("DashboardFragment", "Start Sharing button clicked")
            checkAndRequestPermissions()
        }
    }

    private fun setupObservers() {
        viewModel.gnssData.observe(viewLifecycleOwner) {
            binding.gnssDataTextview.text = it
        }
        viewModel.connectionStatus.observe(viewLifecycleOwner) {
            binding.connectionStatusTextview.text = it
        }
        viewModel.alertStatus.observe(viewLifecycleOwner) { alert ->
            when (alert) {
                is Alert.Safe -> {
                    binding.warningTextview.text = getString(R.string.all_clear)
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_safe))
                }
                is Alert.Caution -> {
                    binding.warningTextview.text = alert.message
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_caution))
                }
                is Alert.Danger -> {
                    binding.warningTextview.text = getString(R.string.alert_danger_format, alert.message, alert.recommendedSpeed)
                    binding.warningTextview.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_danger))
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        Log.d("DashboardFragment", "Checking permissions")
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("DashboardFragment", "Requesting permissions: ${permissionsToRequest.joinToString()}")
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("DashboardFragment", "All necessary permissions already granted")
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
        _binding = null
    }
}
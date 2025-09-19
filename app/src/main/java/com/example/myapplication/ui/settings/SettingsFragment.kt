package com.example.myapplication.ui.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // For DashboardViewModel
import androidx.fragment.app.viewModels
import com.example.myapplication.databinding.FragmentSettingsBinding
import com.example.myapplication.ui.main.DashboardViewModel // For observing statuses
import com.example.myapplication.util.AppPreferences

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // ViewModel for settings-specific logic and actions initiated from this screen
    private val settingsViewModel: SettingsViewModel by viewModels()
    // Shared ViewModel for observing global statuses like GNSS and P2P connection
    private val dashboardViewModel: DashboardViewModel by activityViewModels()

    private var isGnssCurrentlyActive = false // Local state derived from dashboardViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe nickname LiveData from SettingsViewModel (existing logic)
        settingsViewModel.nickname.observe(viewLifecycleOwner) { currentNickname ->
            binding.nicknameEditText.setText(currentNickname)
        }

        // Handle save button click (existing logic)
        binding.saveNicknameButton.setOnClickListener {
            val newNickname = binding.nicknameEditText.text.toString().trim()
            if (newNickname.isNotEmpty()) {
                settingsViewModel.saveNickname(newNickname)
                Toast.makeText(requireContext(), "Nickname saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Nickname cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        val appInstanceId = AppPreferences.getAppInstanceId(requireContext())
        Log.d("SettingsFragment", "App Instance ID: $appInstanceId (This is for info, not directly used by buttons)")

        setupNewClickListeners()
        observeDashboardViewModelStates()
    }

    private fun setupNewClickListeners() {
        binding.toggleGnssButton.setOnClickListener {
            // The action to toggle GNSS will be handled by SettingsViewModel,
            // which will then signal MainActivity (or a shared service controller)
            settingsViewModel.onToggleGnssClicked(isGnssCurrentlyActive)
        }

        binding.discoverPeersButton.setOnClickListener {
            settingsViewModel.onDiscoverPeersClicked()
            Toast.makeText(requireContext(), "Attempting to discover peers...", Toast.LENGTH_SHORT).show()
        }

        binding.createGroupButton.setOnClickListener {
            settingsViewModel.onCreateGroupClicked()
            Toast.makeText(requireContext(), "Attempting to create group...", Toast.LENGTH_SHORT).show()
        }

        binding.removeGroupButton.setOnClickListener {
            settingsViewModel.onRemoveGroupClicked()
            Toast.makeText(requireContext(), "Attempting to remove group...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeDashboardViewModelStates() {
        // Observe a proxy for GNSS activity status.
        // A dedicated LiveData<Boolean> in DashboardViewModel for GNSS active state would be better.
        dashboardViewModel.currentUserLocation.observe(viewLifecycleOwner) { location ->
            // This is a rough proxy. GNSS might be "starting" but no location yet.
            // Or location could be null if GNSS was stopped.
            // For a toggle button, we need a more definitive state.
            // Let's assume for now if location is non-null, GNSS is "active" from user's perspective.
            if (location != null) {
                if (!isGnssCurrentlyActive) { // Update only on state change
                    isGnssCurrentlyActive = true
                    binding.gnssStatusTextView.text = "GNSS Status: Active (Location)"
                    binding.toggleGnssButton.text = "Stop GNSS"
                }
            } else {
                if (isGnssCurrentlyActive) { // Update only on state change
                    isGnssCurrentlyActive = false
                    binding.gnssStatusTextView.text = "GNSS Status: Inactive / No Fix"
                    binding.toggleGnssButton.text = "Start GNSS"
                }
            }
        }
        // Ideally, DashboardViewModel would expose a LiveData<Boolean> like isNavigationServiceActive
        // or isGnssProvidingUpdates. The SettingsFragment would observe that to set `isGnssCurrentlyActive`
        // and button states. The button click would then call `settingsViewModel.onToggleGnssClicked(isGnssCurrentlyActiveFromObserver)`

        dashboardViewModel.connectionStatus.observe(viewLifecycleOwner) { status ->
            binding.p2pStatusTextView.text = "P2P/Conn Status: $status"
            // Enable/disable P2P buttons based on whether P2P is likely available.
            // This is a heuristic; a more direct status from P2pCommunicationManager via DashboardViewModel would be better.
            val p2pLikelyAvailable = status != null &&
                                     !status.contains("NOT available", ignoreCase = true) &&
                                     !status.contains("disabled", ignoreCase = true)

            binding.discoverPeersButton.isEnabled = p2pLikelyAvailable
            binding.createGroupButton.isEnabled = p2pLikelyAvailable
            // Remove group might be enabled even if not connected, to clean up.
            // binding.removeGroupButton.isEnabled = p2pLikelyAvailable 
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

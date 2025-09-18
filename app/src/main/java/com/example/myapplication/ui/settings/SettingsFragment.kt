package com.example.myapplication.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.myapplication.databinding.FragmentSettingsBinding
import com.example.myapplication.util.AppPreferences

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Observe nickname LiveData from ViewModel
        viewModel.nickname.observe(viewLifecycleOwner) { currentNickname ->
            binding.nicknameEditText.setText(currentNickname)
        }

        // Handle save button click
        binding.saveNicknameButton.setOnClickListener {
            val newNickname = binding.nicknameEditText.text.toString().trim()
            if (newNickname.isNotEmpty()) {
                viewModel.saveNickname(newNickname)
                Toast.makeText(requireContext(), "Nickname saved", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Nickname cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Display App Instance ID (Read-only)
        // We'll add a TextView for this in fragment_settings.xml in a subsequent step
        // and then expose LiveData from SettingsViewModel for it.
        // For now, let's log it to ensure AppPreferences is working from the fragment context.
        val appInstanceId = AppPreferences.getAppInstanceId(requireContext())
        // binding.appInstanceIdTextView.text = "App ID: $appInstanceId" // Placeholder for future UI
        android.util.Log.d("SettingsFragment", "App Instance ID: $appInstanceId")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
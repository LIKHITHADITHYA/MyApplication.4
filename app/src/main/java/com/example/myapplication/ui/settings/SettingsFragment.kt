package com.example.myapplication.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels // Import for by viewModels()
import com.example.myapplication.databinding.FragmentSettingsBinding // Assuming you'll create this binding

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
        // Setup observers for ViewModel LiveData when you add them
        // viewModel.indoorModeEnabled.observe(viewLifecycleOwner) { enabled ->
        //    // Update UI based on indoorModeEnabled
        // }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
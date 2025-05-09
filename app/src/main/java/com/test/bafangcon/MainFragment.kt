package com.test.bafangcon

import android.Manifest
import android.content.Intent
// Remove Intent import if no longer needed
// import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast // Import Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.test.bafangcon.databinding.FragmentMainBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull // Import for timeout
import kotlinx.coroutines.Job // Import Job
import kotlinx.coroutines.flow.first

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DeviceViewModel by activityViewModels()

    // Job references to manage the waiting coroutines
    private var controllerLaunchJob: Job? = null
    private var meterLaunchJob: Job? = null
    private var personalizedLaunchJob: Job? = null

    companion object {
        private const val DATA_WAIT_TIMEOUT_MS = 5000L // 5 seconds timeout
        private const val TAG = "MainFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        checkRequiredPermissions() // Re-check permissions on resume
    }

    // --- MODIFIED setupListeners ---
    private fun setupListeners() {
        binding.disconnectButton.setOnClickListener {
            // Cancel any pending activity launch jobs on disconnect
            controllerLaunchJob?.cancel()
            meterLaunchJob?.cancel()
            personalizedLaunchJob?.cancel()
            viewModel.disconnect()
            // Navigation back to ScanFragment is handled in observeViewModel
        }

        binding.requestControllerButton.setOnClickListener {
            if (hasConnectPermission()) {
                Log.d(TAG, "Requesting Controller Info...")
                viewModel.requestControllerInfo() // Send request

                // Cancel previous wait job for this specific type
                controllerLaunchJob?.cancel()
                controllerLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                    Log.d(TAG, "Waiting for Controller Info...")
                    // Wait for the first non-null value within the timeout
                    val result = withTimeoutOrNull(DATA_WAIT_TIMEOUT_MS) {
                        viewModel.controllerInfo.filterNotNull().first()
                    }
                    // --- Directly handle navigation ---
                    if (result != null) {
                        // Data received within timeout
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            Log.d(TAG, "Controller info received, navigating to ControllerInfoFragment.")
                            (activity as? RootActivity)?.navigateToControllerInfoFragment() // Navigate using RootActivity
                        } else {
                            Log.d(TAG, "Controller info received, but fragment not started.")
                        }
                    } else {
                        // Timeout occurred
                        Log.w(TAG,"Timeout waiting for Controller Info.")
                        // Show feedback if timeout occurs
                        Toast.makeText(requireContext(), "Timeout getting Controller data", Toast.LENGTH_SHORT).show()
                    }
                    // --- End direct handling ---
                }
            } else {
                showPermissionMissingSnackbar("request controller info")
            }
        }

        binding.requestMeterButton.setOnClickListener {
            if (hasConnectPermission()) {
                Log.d(TAG,"Requesting Meter Info...")
                viewModel.requestMeterInfo()

                meterLaunchJob?.cancel()
                meterLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                    Log.d(TAG,"Waiting for Meter Info...")
                    val result = withTimeoutOrNull(DATA_WAIT_TIMEOUT_MS) {
                        viewModel.meterInfo.filterNotNull().first()
                    }
                    // --- Directly handle navigation ---
                    if (result != null) {
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            Log.d(TAG, "Meter info received, navigating to MeterInfoFragment.")
                           (activity as? RootActivity)?.navigateToMeterInfoFragment()
                        } else {
                            Log.d(TAG, "Meter info received, but fragment not started.")
                        }
                    } else {
                        Log.w(TAG,"Timeout waiting for Meter Info.")
                        Toast.makeText(requireContext(), "Timeout getting Meter data", Toast.LENGTH_SHORT).show()
                    }
                    // --- End direct handling ---
                }
            } else {
                showPermissionMissingSnackbar("request meter info")
            }
        }


        binding.requestPersonalizedButton.setOnClickListener {
            if (hasConnectPermission()) {
                Log.d(TAG, "Requesting Personalized Info...")
                viewModel.requestPersonalizedInfo()

                personalizedLaunchJob?.cancel()
                personalizedLaunchJob = viewLifecycleOwner.lifecycleScope.launch {
                    Log.d(TAG, "Waiting for Personalized Info...")
                    val result = withTimeoutOrNull(DATA_WAIT_TIMEOUT_MS) {
                        viewModel.personalizedInfo.filterNotNull().first()
                    }
                    // --- Directly handle navigation ---
                    if (result != null) {
                        if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            Log.d(TAG, "Personalized info received, navigating to GearsInfoFragment.")

                            (activity as? RootActivity)?.navigateToGearsInfoFragment()

                        } else {
                            Log.d(TAG, "Personalized info received, but fragment not started.")
                        }
                    } else {
                        Log.w(TAG,"Timeout waiting for Personalized Info.")
                        Toast.makeText(requireContext(), "Timeout getting Personalized data", Toast.LENGTH_SHORT).show()
                    }
                    // --- End direct handling ---
                }
            } else {
                showPermissionMissingSnackbar("request personalized info")
            }
        }


        binding.lightSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Check permission when the switch is toggled
            if (hasConnectPermission()) {
                viewModel.setLightState(isChecked)
            } else {
                // Revert switch state and show message if permission is missing
                binding.lightSwitch.isChecked = !isChecked
                showPermissionMissingSnackbar("control light")
            }
        }
    }



    // --- Other functions (checkRequiredPermissions, setInteractionEnabled, etc.) remain the same ---
    private fun checkRequiredPermissions() {
        // Check specifically for BLUETOOTH_CONNECT if on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG,"BLUETOOTH_CONNECT permission missing onResume.")
                // Don't automatically show snackbar on resume, but disable interaction
                setInteractionEnabled(false)
                // Optionally show a non-intrusive indicator
                showPermissionMissingSnackbar("interact with connected device")
                binding.connectionStatusTextView.text = getString(R.string.status_connected_permission_missing)
            } else {
                // Permission exists, update UI based on connection state
                updateUiState(viewModel.connectionState.value, true)
            }
        } else {
            setInteractionEnabled(true) // No specific connect permission needed before S
        }
    }

    private fun setInteractionEnabled(enabled: Boolean) {
        val isConnected = viewModel.connectionState.value == BleConnectionState.CONNECTED
        val enableButtons = enabled && isConnected

        binding.disconnectButton.isEnabled = isConnected // Disconnect always possible if connected
        binding.requestControllerButton.isEnabled = enableButtons
        binding.requestMeterButton.isEnabled = enableButtons
        binding.requestPersonalizedButton.isEnabled = enableButtons
        binding.lightSwitch.isEnabled = enableButtons
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed before Android 12
        }
    }

    private fun showPermissionMissingSnackbar(actionDesc: String) {
        if (view == null) return
        Snackbar.make(binding.root, "Bluetooth Connect permission needed to $actionDesc.", Snackbar.LENGTH_LONG)
            .setAction("Settings") {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", requireActivity().packageName, null)
                    intent.data = uri
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG,"Could not open app settings", e)
                    Toast.makeText(requireContext(), "Could not open app settings", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Connection State
                launch {
                    viewModel.connectionState.collect { state ->
                        // Update UI based on state and permission status
                        updateUiState(state, hasConnectPermission())

                        if (state == BleConnectionState.DISCONNECTED || state == BleConnectionState.FAILED) {
                            // Cancel any pending activity launch jobs if connection drops
                            controllerLaunchJob?.cancel()
                            meterLaunchJob?.cancel()
                            personalizedLaunchJob?.cancel()
                            // Navigate back to ScanFragment if not already there
                            if (activity is RootActivity && parentFragmentManager.findFragmentById(R.id.fragment_container) !is ScanFragment) {
                                (activity as RootActivity).navigateToScanFragment()
                            }
                        }
                    }
                }

                // Observe Data StateFlows for UI display updates
                launch { viewModel.controllerInfo.collect { info -> updateControllerInfoUI(info) } }
                launch { viewModel.meterInfo.collect { info -> updateMeterInfoUI(info) } }
                launch { viewModel.personalizedInfo.collect { info -> updatePersonalizedInfoUI(info) } } // Placeholder update
            }
        }
    }

    // Update UI based on Connection State and Permissions
    private fun updateUiState(state: BleConnectionState, hasPermission: Boolean) {
        val isConnected = state == BleConnectionState.CONNECTED
        setInteractionEnabled(hasPermission && isConnected) // Use helper

        binding.connectionStatusTextView.text = when(state) {
            BleConnectionState.CONNECTED -> getString(R.string.status_connected, "E-Bike") + if(!hasPermission) " (Permission Missing!)" else ""
            BleConnectionState.DISCONNECTED -> getString(R.string.status_disconnected)
            BleConnectionState.CONNECTING -> getString(R.string.connecting)
            BleConnectionState.FAILED -> getString(R.string.status_failed)
            BleConnectionState.SCANNING -> "Unexpected: Scanning" // Should not be in this fragment when scanning
        }
    }

    // Update Display TextViews (remain the same)
    private fun updateControllerInfoUI(info: ControllerInfo?) {
        binding.controllerInfoTextView.text = info?.let {
            "HW: ${it.hardVersion}, SW: ${it.softVersion}, V: ${String.format("%.2f", it.voltage*0.01)}, PWR: ${it.soc}%" // Format voltage
        } ?: "Controller: N/A"
    }

    private fun updateMeterInfoUI(info: MeterInfo?) {
        binding.meterInfoTextView.text = info?.let {
            "Model: ${it.model}, ODO: ${it.totalMileage}km, Gear: ${it.currentGear}/${it.totalGear}"
        } ?: "Meter: N/A"

        // Update controls that depend on MeterInfo (like the light switch)
        // Temporarily remove the listener to prevent triggering commands when setting the state
        binding.lightSwitch.setOnCheckedChangeListener(null)

        // Set the switch's state based on the received MeterInfo
        binding.lightSwitch.isChecked = info?.light == 1 // 1 means light is ON

        // Re-attach the listener with the original logic
        binding.lightSwitch.setOnCheckedChangeListener { _, isChecked ->
            // This is the same logic as in setupListeners()
            if (hasConnectPermission()) {
                viewModel.setLightState(isChecked)
            } else {
                // Revert switch state visually if permission is missing and show message
                binding.lightSwitch.isChecked = !isChecked
                showPermissionMissingSnackbar("control light")
            }
        }
    }

    private fun updatePersonalizedInfoUI(info: PersonalizedInfo?) {
        // Example: You could enable/disable the 'Gears' button based on whether info is available
        // binding.requestPersonalizedButton.isEnabled = info != null && viewModel.connectionState.value == BleConnectionState.CONNECTED && hasConnectPermission()
        // Or display a summary if needed
    }


    override fun onDestroyView() {
        super.onDestroyView()
        // Cancel jobs when view is destroyed to prevent leaks
        controllerLaunchJob?.cancel()
        meterLaunchJob?.cancel()
        personalizedLaunchJob?.cancel()
        _binding = null
    }
}
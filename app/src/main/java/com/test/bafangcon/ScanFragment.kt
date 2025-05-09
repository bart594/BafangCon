package com.test.bafangcon

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.test.bafangcon.databinding.FragmentScanBinding
import kotlinx.coroutines.launch

class ScanFragment : Fragment() {

    private var _binding: FragmentScanBinding? = null
    private val binding get() = _binding!! // Only valid between onCreateView and onDestroyView

    // Use activityViewModels to share the ViewModel with MainActivity/RootActivity
    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var deviceScanAdapter: DeviceScanAdapter

    // Permission Launcher
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // Check if *all* requested permissions were granted
            if (permissions.all { it.value }) {
                Log.d("ScanFragment", "All permissions granted by user.")
                // Permissions granted, start scan
                viewModel.startScan()
            } else {
                // At least one permission denied
                Log.w("ScanFragment", "Permissions denied by user.")
                Snackbar.make(binding.root, R.string.permissions_denied, Snackbar.LENGTH_LONG).show()
                updateUiState(BleConnectionState.DISCONNECTED) // Reset state visually
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        // If already scanning when resuming, ensure permissions are still valid
        if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
            checkRequiredPermissions(showAlertAndRequest = false) // Re-check without showing alert immediately
        }
    }

    // Modify checkAndRequestPermissions slightly
    private fun checkAndRequestPermissions(showAlert: Boolean = true) {
        val missingPermissions = viewModel.requiredPermissions.filter {
            requireContext().checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // All permissions granted
            // Only start scan if not already scanning (check state)
            if (viewModel.connectionState.value != BleConnectionState.SCANNING &&
                viewModel.connectionState.value != BleConnectionState.CONNECTING) {
                viewModel.startScan()
            }
        } else {
            // Stop scan if it was running without permissions
            if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
                viewModel.stopScan()
                updateUiState(BleConnectionState.DISCONNECTED) // Reflect stopped state
            }
            // Request missing permissions
            if (showAlert) { // Only show alert/request if explicitly told to
                Log.w("ScanFragment", "Requesting missing permissions: $missingPermissions")
                // Show rationale if needed before launching
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            } else {
                Log.w("ScanFragment", "Permissions missing on resume check: $missingPermissions")
            }
        }
    }


    private fun setupRecyclerView() {
        deviceScanAdapter = DeviceScanAdapter { device ->
            // User selected a device
            if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
                viewModel.stopScan() // Stop scanning before attempting connection
            }
            viewModel.connect(device)
            // Status update (CONNECTING) will be handled by observing connectionState
        }
        binding.scanRecyclerView.apply {
            adapter = deviceScanAdapter
            layoutManager = LinearLayoutManager(requireContext())
            // Optional: Add dividers
            addItemDecoration(DividerItemDecoration(requireContext(), LinearLayoutManager.VERTICAL))
            // Prevent clicks while connecting (handled also by disabling scan button)
            // isEnabled = viewModel.connectionState.value != BleConnectionState.CONNECTING
        }
    }

    private fun setupListeners() {
        binding.scanButton.setOnClickListener {
            when (viewModel.connectionState.value) {
                BleConnectionState.SCANNING -> {
                    // If scanning, stop the scan
                    viewModel.stopScan()
                }
                BleConnectionState.CONNECTING -> {
                    // Should not happen as button is disabled, but good practice
                    Log.d("ScanFragment", "Scan button clicked while connecting, ignoring.")
                }
                else -> {
                    // If disconnected or failed, check permissions and start scan
                    checkRequiredPermissions(showAlertAndRequest = true)
                }
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            // repeatOnLifecycle ensures collection stops when the fragment is STOPPED
            // and restarts when STARTED.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Scan Results
                launch {
                    viewModel.scanResults.collect { results ->
                        // Update the adapter's list
                        deviceScanAdapter.submitList(results.toList().sortedByDescending { it.rssi })
                    }
                }

                // Observe Connection State for UI updates and navigation
                launch {
                    viewModel.connectionState.collect { state ->
                        updateUiState(state) // Update button text, progress bar visibility etc.

                        // Navigate only when connection is fully established
                        if (state == BleConnectionState.CONNECTED) {
                            Log.d("ScanFragment", "Connection successful, navigating to MainFragment.")
                            (activity as? RootActivity)?.navigateToMainFragment()
                        }
                        // Optionally show a persistent error message if connection fails *after* trying
                        else if (state == BleConnectionState.FAILED) {
                            // Check if we were previously in CONNECTING state to avoid showing
                            // this error just because the initial state might be FAILED.
                            // This requires more complex state tracking, maybe in ViewModel.
                            // For now, a simple Toast might suffice, but could be annoying.
                            // Consider showing it only if the user explicitly tried to connect.
                            // Toast.makeText(context, R.string.connection_failed, Toast.LENGTH_SHORT).show()
                            Log.w("ScanFragment", "Connection state is FAILED.")
                        }
                    }
                }
            }
        }
    }

    // Updates the Scan Button text, ProgressBar visibility based on state
    private fun updateUiState(state: BleConnectionState) {
        binding.scanProgressBar.isVisible = state == BleConnectionState.SCANNING || state == BleConnectionState.CONNECTING
        binding.scanButton.text = getString(if (state == BleConnectionState.SCANNING) R.string.scan_stop else R.string.scan_start)
        // Disable scan button AND recyclerview interaction while connecting
        val isConnecting = state == BleConnectionState.CONNECTING
        binding.scanButton.isEnabled = !isConnecting
        binding.scanRecyclerView.isEnabled = !isConnecting // Prevent clicks during connection attempt

        binding.scanStatusText.isVisible = true
        binding.scanStatusText.text = when(state) {
            BleConnectionState.SCANNING -> getString(R.string.status_scanning)
            BleConnectionState.CONNECTING -> getString(R.string.connecting) // Maybe add device name later
            BleConnectionState.DISCONNECTED -> getString(R.string.scan_stopped) // Or "Ready to Scan"
            BleConnectionState.CONNECTED -> getString(R.string.connected) // Should navigate away
            BleConnectionState.FAILED -> getString(R.string.connection_failed) // Indicate failure clearly
        }
        // Hide status text if disconnected and no results yet
        if(state == BleConnectionState.DISCONNECTED && deviceScanAdapter.itemCount == 0) {
            binding.scanStatusText.isVisible = false
        }
    }


    /**
     * Checks if necessary permissions are granted. If not, and [showAlertAndRequest] is true,
     * it triggers the permission request flow.
     * If permissions are missing but [showAlertAndRequest] is false (e.g., onResume check),
     * it just logs the issue and potentially stops an ongoing scan.
     */
    private fun checkRequiredPermissions(showAlertAndRequest: Boolean) {
        val missingPermissions = viewModel.requiredPermissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // All permissions are granted.
            Log.d("ScanFragment", "All required permissions are granted.")
            // If triggered by user action (showAlertAndRequest=true), start scan.
            // Avoid auto-starting scan onResume if permissions were already granted.
            if (showAlertAndRequest) {
                // Ensure we are not already scanning or connecting
                if (viewModel.connectionState.value != BleConnectionState.SCANNING &&
                    viewModel.connectionState.value != BleConnectionState.CONNECTING) {
                    viewModel.startScan()
                }
            }
        } else {
            // Permissions are missing.
            Log.w("ScanFragment", "Missing permissions: $missingPermissions")
            // If currently scanning, stop it because permissions are missing.
            if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
                Log.w("ScanFragment", "Stopping scan because permissions are missing.")
                viewModel.stopScan()
                // Update UI immediately to reflect stopped state if needed
                updateUiState(BleConnectionState.DISCONNECTED)
            }

            // If triggered by user action (like button press), show rationale and request.
            if (showAlertAndRequest) {
                // TODO: Consider showing a rationale dialog before requesting if !shouldShowRequestPermissionRationale
                Log.d("ScanFragment", "Requesting missing permissions...")
                requestPermissionsLauncher.launch(missingPermissions.toTypedArray())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Important: Stop scanning if the view is destroyed to prevent leaks/battery drain
        // Check the state before stopping, might have already stopped or connected.
        if (viewModel.connectionState.value == BleConnectionState.SCANNING) {
            Log.d("ScanFragment", "Stopping scan in onDestroyView")
            viewModel.stopScan()
        }
        _binding = null // Avoid memory leaks by nulling the binding reference
    }
}

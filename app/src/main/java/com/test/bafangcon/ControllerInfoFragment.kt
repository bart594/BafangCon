package com.test.bafangcon

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels // Import activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.test.bafangcon.databinding.FragmentControllerInfoBinding  // Keep using this binding
import kotlinx.coroutines.launch
import java.util.*
import kotlin.collections.ArrayList

// Changed from AppCompatActivity to Fragment
class ControllerInfoFragment : Fragment() {

    // Standard Fragment binding pattern
    private var _binding: FragmentControllerInfoBinding? = null
    private val binding get() = _binding!!

    // Use activityViewModels to share with RootActivity and other fragments
    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    // Store the list items for the adapter
    private val displayList = ArrayList<InfoItem>()
    // Store a temporary, editable copy of the ControllerInfo
    private var editableInfo: ControllerInfo? = null

    companion object {
        private const val TAG = "ControllerInfoFragment" // Updated TAG
        const val KEY_TIRE_CIRCUMFERENCE = "Tire Circumference (mm)"
        // Define keys for other fields if they become editable
    }

    // Inflate the layout
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControllerInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Setup views and observers after the view is created
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set the title via the hosting activity's action bar
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Controller Information"

        setupRecyclerView()
        setupButtons()
        observeViewModel() // Start observing data - should now work correctly
    }

    private fun observeViewModel() {
        Log.d(TAG, "observeViewModel: Setting up observers.")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "observeViewModel: Lifecycle STARTED. Collecting ControllerInfo.")

                viewModel.controllerInfo.collect { currentControllerInfoFromVm ->
                    Log.i(TAG, "Collector Received ControllerInfo from VM: ${currentControllerInfoFromVm.toString().take(100)}...")

                    if (currentControllerInfoFromVm != null) {
                        // Always re-initialize editableInfo from the ViewModel's current state
                        editableInfo = currentControllerInfoFromVm.copy(
                            // Ensure deep copy of mutable arrays if they exist
                            gearSpeedLimit = currentControllerInfoFromVm.gearSpeedLimit.copyOf(),
                            gearCurrentLimit = currentControllerInfoFromVm.gearCurrentLimit.copyOf()
                        )
                        Log.d(TAG, "Re-initialized editableInfo: ${editableInfo.toString().take(100)}...")
                        populateList(editableInfo!!)
                    } else {
                        editableInfo = null
                        displayList.clear()
                        infoAdapter.submitList(emptyList())
                        Log.d(TAG, "ViewModel's ControllerInfo is null. Cleared editableInfo and list.")
                    }
                }
            }
            Log.d(TAG, "observeViewModel: repeatOnLifecycle block finished.")
        }
        Log.d(TAG, "observeViewModel: Coroutine launch call finished.")
    }


    private fun setupRecyclerView() {
        infoAdapter = InfoAdapter { position, newValue ->
            if (editableInfo == null) {
                Log.w(TAG, "Adapter callback: editableInfo is null. Ignoring change.")
                return@InfoAdapter
            }
            if (position >= 0 && position < displayList.size) {
                val item = displayList[position]
                item.value = newValue
                updateEditableInfo(item.key, newValue)
            }
        }
        binding.infoRecyclerView.apply {
            adapter = infoAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun setupButtons() {
        binding.cancelButton.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.popBackStack()
        }

        binding.updateButton.setOnClickListener {
            hideKeyboard()
            if (editableInfo == null) {
                Toast.makeText(requireContext(), "No data loaded to update", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Update clicked but editableInfo is null!")
                return@setOnClickListener
            }

            val infoToUpdate = editableInfo!! // Shadow with non-null
            var isValid = true
            val validationErrors = mutableListOf<String>()

            // --- Perform Validation ---
            val tireCircValue = infoToUpdate.tireCircumference
            if (tireCircValue < 0 || tireCircValue > 3000) { // Example validation range
                isValid = false
                validationErrors.add("Tire Circumference must be 0-3000 mm.")
            }
            // Add more validations if other fields become editable

            if (isValid) {
                Log.d(TAG, "Validation passed. Updating Tire Circumference to: ${infoToUpdate.tireCircumference}")
                viewModel.updateTireCircumference(infoToUpdate.tireCircumference)
                Toast.makeText(requireContext(), "Update command sent", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                val errorMsg = "Validation failed:\n${validationErrors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }


    // Populates the RecyclerView list from the editable PersonalizedInfo object
    // (Identical to your Activity version, should work fine)
    private fun populateList(info: ControllerInfo) {
        Log.i(TAG, "populateList: START - Populating with info: $info")
        displayList.clear()

        // Add items in desired order, marking editable ones
        displayList.add(InfoItem("Hardware Version", info.hardVersion))
        displayList.add(InfoItem("Software Version", info.softVersion))
        displayList.add(InfoItem("Model", info.model))
        displayList.add(InfoItem("Serial Number", info.sn))
        displayList.add(InfoItem("Customer Number", info.customerNo))
        displayList.add(InfoItem("Manufacturer", info.manufacturer))
        displayList.add(InfoItem("Power (%)", info.soc.toString()))
        displayList.add(InfoItem("Single Mileage (0.01km)", String.format(Locale.US, "%.2f", info.singleMileage * 0.01)))
        displayList.add(InfoItem("Total Mileage (0.01km)", String.format(Locale.US, "%.2f", info.totalMileage * 0.01)))
        displayList.add(InfoItem("Remaining Mileage (0.01km)", String.format(Locale.US, "%.2f", info.emainingMileage * 0.01)))
        displayList.add(InfoItem("Cadence (RPM)", info.cadence.toString()))
        displayList.add(InfoItem("Moment (mV)", info.moment.toString()))
        displayList.add(InfoItem("Speed (0.01km/h)", String.format(Locale.US, "%.2f", info.speed * 0.01))) // Check unit/scaling
        displayList.add(InfoItem("Current (0.01A)", String.format(Locale.US, "%.2f", info.electricCurrent * 0.01)))
        displayList.add(InfoItem("Voltage (0.01V)", String.format(Locale.US, "%.2f", info.voltage * 0.01)))
        displayList.add(InfoItem("Controller Temp (°C)", info.controllerTemperature.toString()))
        displayList.add(InfoItem("Motor Temp (°C)", if (info.motorTemperature == 255) "N/A" else info.motorTemperature.toString()))
        displayList.add(InfoItem("Boost State", if (info.boostState == 1) "Active" else "Inactive"))
        displayList.add(InfoItem("Speed Limit (0.01km/h)", String.format(Locale.US, "%.2f", info.speedLimit * 0.01))) // Check unit/scaling
        displayList.add(InfoItem("Wheel Diameter (raw)", info.wheelDiameter.toString()))
        // --- Editable Tire Circumference ---
        displayList.add(InfoItem(KEY_TIRE_CIRCUMFERENCE, info.tireCircumference.toString(), EditableType.EDIT_TEXT_NUMBER, info.tireCircumference))
        // -----------------------------------
        displayList.add(InfoItem("Calories (kcal)", info.calories.toString()))
        displayList.add(InfoItem("Current Gear", info.currentGear.toString()))
        displayList.add(InfoItem("Total Gears", info.totalGear.toString()))
        displayList.add(InfoItem("Wheel Speed (RPM?)", info.wheelSpeed.toString()))
        displayList.add(InfoItem("Wheel Counter", info.wheelCounter.toString()))
        displayList.add(InfoItem("Last Sensor Time (ms?)", info.lastTestSenserTime.toString()))
        displayList.add(InfoItem("Crank Pulse Counter", info.crankCadencePulseCounter.toString()))
        displayList.add(InfoItem("Motor Var Speed Master Gear", info.motorVariableSpeedMasterGear.toString()))
        displayList.add(InfoItem("Motor Speed Current Gear", info.motorSpeedCurrentGear.toString()))
        displayList.add(InfoItem("Cruise Control", if (info.cruiseControl == 1) "On" else "Off"))
        displayList.add(InfoItem("Boot Default Gear Setting", if (info.bootDefaultGear == 1) "On" else "Off"))
        displayList.add(InfoItem("Boot Default Gear Value", info.bootDefaultGearValue.toString()))
        displayList.add(InfoItem("Motor Starting Angle (raw)", info.motorStartingAngle.toString()))
        displayList.add(InfoItem("Acceleration Setting (raw)", info.accelerationSettings.toString()))
        displayList.add(InfoItem("Gear Speed Limit (raw)", info.gearSpeedLimit.toHexString())) // Use helper
        displayList.add(InfoItem("Gear Current Limit (raw)", info.gearCurrentLimit.toHexString())) // Use helper
        displayList.add(InfoItem("Buzzer Switch", if (info.buzzerSwitch == 1) "On" else "Off"))
        displayList.add(InfoItem("Protocol Version", info.controllerProtocolVersion.toString()))

        Log.i(TAG, "populateList: Populated displayList with ${displayList.size} items.")

        if (displayList.isNotEmpty() || editableInfo != null) { // Submit even if list is empty to clear recycler
            infoAdapter.submitList(ArrayList(displayList)) // Submit a copy
            Log.i(TAG, "populateList: END - Submitted list to adapter.")
        } else {
            Log.w(TAG, "populateList: END - displayList is empty and no editableInfo, not submitting.")
        }
    }

    private fun updateEditableInfo(key: String, newValue: String) {
        if (editableInfo == null) {
            Log.e(TAG, "updateEditableInfo called but editableInfo is null!")
            return
        }
        Log.d(TAG, "Updating editableInfo: key='$key', newValue='$newValue'")

        try {
            when (key) {
                KEY_TIRE_CIRCUMFERENCE -> {
                    val intValue = newValue.toIntOrNull()
                    // Store potentially invalid value; validation happens on Update click
                    editableInfo?.tireCircumference = intValue ?: editableInfo!!.tireCircumference // Keep old if invalid parse
                }
                // Add cases for other editable fields if needed in the future
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number format error during update for $key: $newValue")
        }
    }

    // Use requireContext() and requireActivity() in Fragments
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = requireActivity().currentFocus // Get activity's current focus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
        } else {
            // Fallback if no view has focus (less common)
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    // Helper extension (keep as is)
    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { String.format("%02X", it) }

    // Clean up binding in onDestroyView
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important for fragment lifecycle
    }

} // End of ControllerInfoFragment class
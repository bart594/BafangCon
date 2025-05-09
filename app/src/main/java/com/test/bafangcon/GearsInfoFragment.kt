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
import kotlin.collections.ArrayList

// Changed from AppCompatActivity to Fragment
class GearsInfoFragment : Fragment() {

    // Standard Fragment binding pattern
    private var _binding: FragmentControllerInfoBinding ? = null
    private val binding get() = _binding!!

    // Use activityViewModels to share with RootActivity and other fragments
    private val viewModel: DeviceViewModel by activityViewModels()
    private lateinit var infoAdapter: InfoAdapter

    // Store the currently displayed list items
    private val displayList = ArrayList<InfoItem>()
    // Store a temporary, editable copy of the PersonalizedInfo
    private var editableInfo: PersonalizedInfo? = null

    // Keys for identifying items during edit callback
    companion object {
        const val KEY_PROTOCOL = "Protocol Version"
        private const val TAG = "GearsInfoFragment" // Updated TAG
        fun keyAngle(gear: Int) = "Motor Angle Gear ${gear + 1}"
        fun keyAccel(gear: Int) = "Acceleration Gear ${gear + 1}"
        fun keySpeed(gear: Int) = "Speed Limit Gear ${gear + 1}"
        fun keyCurrent(gear: Int) = "Current Limit Gear ${gear + 1}"
    }

    // Inflate the layout
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControllerInfoBinding .inflate(inflater, container, false)
        return binding.root
    }

    // Setup views and observers after the view is created
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set the title via the hosting activity's action bar
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Personalized Gear Settings"

        setupRecyclerView()
        setupButtons()
        observeViewModel() // Start observing data
    }

    private fun observeViewModel() {
        Log.d(TAG, "observeViewModel: Setting up observers.")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Log.d(TAG, "observeViewModel: Lifecycle STARTED. Collecting PersonalizedInfo.")

                viewModel.personalizedInfo.collect { currentPersonalizedInfoFromVm ->
                    Log.i(TAG, "Collector Received PersonalizedInfo from VM: ${currentPersonalizedInfoFromVm.toString().take(100)}...")

                    if (currentPersonalizedInfoFromVm != null) {
                        // Always re-initialize editableInfo from the ViewModel's current state
                        editableInfo = currentPersonalizedInfoFromVm.copy(
                            // Ensure arrays are copied deeply
                            motorStartingAngle = currentPersonalizedInfoFromVm.motorStartingAngle.copyOf(),
                            accelerationSettings = currentPersonalizedInfoFromVm.accelerationSettings.copyOf(),
                            gearSpeedLimit = currentPersonalizedInfoFromVm.gearSpeedLimit.copyOf(),
                            gearCurrentLimit = currentPersonalizedInfoFromVm.gearCurrentLimit.copyOf()
                        )
                        Log.d(TAG, "Re-initialized editableInfo: ${editableInfo.toString().take(100)}...")
                        populateList(editableInfo!!)
                    } else {
                        editableInfo = null
                        displayList.clear()
                        infoAdapter.submitList(emptyList())
                        Log.d(TAG, "ViewModel's PersonalizedInfo is null. Cleared editableInfo and list.")
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

    // Updates the editableInfo object based on changes from the adapter
    private fun updateEditableInfo(key: String, newValue: String) {
        if (editableInfo == null) {
            Log.e(TAG, "updateEditableInfo called but editableInfo is null!")
            return
        }
        Log.d(TAG, "Updating editableInfo: key='$key', newValue='$newValue'")

        try {
            when {
                key.startsWith("Motor Angle Gear") -> {
                    val index = key.removePrefix("Motor Angle Gear ").trim().toIntOrNull()?.minus(1)
                    val shortValue = newValue.toShortOrNull()
                    if (index != null && index in editableInfo!!.motorStartingAngle.indices) {
                        editableInfo?.motorStartingAngle?.set(index, shortValue ?: editableInfo!!.motorStartingAngle[index])
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
                key.startsWith("Acceleration Gear") -> {
                    val index = key.removePrefix("Acceleration Gear ").trim().toIntOrNull()?.minus(1)
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 255)?.toByte()
                    if (index != null && index in editableInfo!!.accelerationSettings.indices) {
                        editableInfo?.accelerationSettings?.set(index, byteValue ?: editableInfo!!.accelerationSettings[index])
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
                key.startsWith("Speed Limit Gear") -> {
                    val index = key.removePrefix("Speed Limit Gear ").removeSuffix(" (%)").trim().toIntOrNull()?.minus(1)
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 100)?.toByte() // 0-100%
                    if (index != null && index in editableInfo!!.gearSpeedLimit.indices) {
                        editableInfo?.gearSpeedLimit?.set(index, byteValue ?: editableInfo!!.gearSpeedLimit[index])
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
                key.startsWith("Current Limit Gear") -> {
                    val index = key.removePrefix("Current Limit Gear ").removeSuffix(" (%)").trim().toIntOrNull()?.minus(1)
                    val byteValue = newValue.toIntOrNull()?.coerceIn(0, 100)?.toByte() // 0-100%
                    if (index != null && index in editableInfo!!.gearCurrentLimit.indices) {
                        editableInfo?.gearCurrentLimit?.set(index, byteValue ?: editableInfo!!.gearCurrentLimit[index])
                    } else { Log.w(TAG, "Invalid index or value for $key") }
                }
            }
        } catch (e: NumberFormatException) {
            Log.w(TAG, "Number format error during update for $key: $newValue")
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
                Toast.makeText(requireContext(), "No data to update", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Update clicked but editableInfo is null!")
                return@setOnClickListener
            }

            val infoToUpdate = editableInfo!! // Shadow with non-null
            var isValid = true
            val validationErrors = mutableListOf<String>()

            // --- Perform Validation ---
            infoToUpdate.motorStartingAngle.forEachIndexed { index, value ->
                if (value !in 0..3600) { // Example range (0-360.0 degrees, stored as value*10)
                    isValid = false
                    validationErrors.add("Angle Gear ${index + 1} must be 0-3600 (0-360.0°).")
                }
            }
            infoToUpdate.accelerationSettings.forEachIndexed { index, value ->
                // Assuming 1-10 is a valid range for acceleration settings (e.g. levels)
                if ((value.toInt() and 0xFF) !in 0..100) { // Example: 1-100 as a general byte range
                    isValid = false
                    validationErrors.add("Accel Gear ${index + 1} must be 0-100.")
                }
            }
            infoToUpdate.gearSpeedLimit.forEachIndexed { index, value ->
                if ((value.toInt() and 0xFF) !in 0..100) { // 0-100%
                    isValid = false
                    validationErrors.add("Speed Limit Gear ${index + 1} must be 0-100%.")
                }
            }
            infoToUpdate.gearCurrentLimit.forEachIndexed { index, value ->
                if ((value.toInt() and 0xFF) !in 0..100) { // 0-100%
                    isValid = false
                    validationErrors.add("Current Limit Gear ${index + 1} must be 0-100%.")
                }
            }
            // --- End Validation ---

            if (isValid) {
                Log.d(TAG, "Validation passed. Sending updated personalized settings: ${infoToUpdate.toString().take(100)}...")
                viewModel.sendPersonalizedSettings(
                    infoToUpdate.motorStartingAngle,
                    infoToUpdate.accelerationSettings,
                    infoToUpdate.gearSpeedLimit,
                    infoToUpdate.gearCurrentLimit
                )
                Toast.makeText(requireContext(), "Update command sent", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } else {
                val errorMsg = "Validation failed:\n${validationErrors.joinToString("\n")}"
                Log.w(TAG, errorMsg)
                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // Populates the RecyclerView list from the PersonalizedInfo object
    private fun populateList(info: PersonalizedInfo) {
        Log.i(TAG, "populateList: START - Populating with info: ${info.toString().take(100)}...")
        displayList.clear()

        displayList.add(InfoItem(KEY_PROTOCOL, info.controllerProtocolVersion.toString())) // Display only

        for (i in 0 until 10) { // Assuming 10 gears for all settings
            displayList.add(InfoItem(keyAngle(i), info.motorStartingAngle.getOrElse(i) { 0 }.toString(), EditableType.EDIT_TEXT_NUMBER))
            displayList.add(InfoItem(keyAccel(i), (info.accelerationSettings.getOrElse(i) { 0 }.toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
            displayList.add(InfoItem(keySpeed(i), (info.gearSpeedLimit.getOrElse(i) { 0 }.toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
            displayList.add(InfoItem(keyCurrent(i), (info.gearCurrentLimit.getOrElse(i) { 0 }.toInt() and 0xFF).toString(), EditableType.EDIT_TEXT_NUMBER))
        }

        Log.i(TAG, "populateList: Populated displayList with ${displayList.size} items.")
        infoAdapter.submitList(ArrayList(displayList)) // Submit a copy
        Log.i(TAG, "populateList: END - Submitted list to adapter.")
    }

    // Use requireContext() and requireActivity() in Fragments
    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val currentFocusView = requireActivity().currentFocus
        if (currentFocusView != null) {
            imm.hideSoftInputFromWindow(currentFocusView.windowToken, 0)
        } else {
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
        }
    }

    // Clean up binding in onDestroyView
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // Important for fragment lifecycle
    }

} // End of GearsInfoFragment class
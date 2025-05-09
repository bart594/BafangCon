package com.test.bafangcon

import android.Manifest
import android.app.Application
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch


class DeviceViewModel(application: Application) : AndroidViewModel(application) {

    // Keep repository instance private
    private val bleRepository = BleRepository(application.applicationContext)

    val connectionState: StateFlow<BleConnectionState> = bleRepository.connectionState
    val scanResults: StateFlow<Set<DiscoveredBluetoothDevice>> = bleRepository.scanResults

    // --- Add StateFlows for parsed data ---
    val controllerInfo: StateFlow<ControllerInfo?> = bleRepository.controllerInfo
    val meterInfo: StateFlow<MeterInfo?> = bleRepository.meterInfo
    val personalizedInfo: StateFlow<PersonalizedInfo?> = bleRepository.personalizedInfo

    // Expose required permissions based on Android version
    val requiredPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }


    fun requestControllerInfo() {
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Controller Info...")
            // Call the internal repository method with the correct command ID
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_CONTROLLER)
        }
    }

    fun requestMeterInfo() {
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Meter Info...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_METER)
        }
    }

    fun requestPersonalizedInfo() {
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Personalized Info...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_PERSONALIZED)
        }
    }

    fun requestBatteryInfo() {
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Battery Info...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_BATTERY)
        }
    }

    fun requestSensorInfo() {
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Sensor Info...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_SENSOR)
        }
    }

    fun requestConfigInfo() { // For CMD_ID_CONFIG (0xA1)
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting Config Info (A1)...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_CONFIG)
        }
    }

    fun requestCanInfo() { // For CMD_ID_CAN (0xA2)
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Requesting CAN Info (A2)...")
            bleRepository.sendReadRequestCommand(BleRepository.CMD_ID_CAN)
        }
    }

    fun startScan() {
        viewModelScope.launch {
            bleRepository.startScan()
        }
    }

    fun stopScan() {
        viewModelScope.launch {
            bleRepository.stopScan()
        }
    }

    fun connect(device: DiscoveredBluetoothDevice) {
        viewModelScope.launch {
            bleRepository.connectDevice(device.address)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            bleRepository.disconnect()
        }
    }

    // Example: Send a command (adapt payload as needed)
    fun sendTestData() {
        // Example: Request Basic Info (Cmd 0x0B) - Frame: 02 01 0B 00 CS 03
        // CS = 01+0B+00 = 0C
        val command = byteArrayOf(0x02, 0x01, 0x0B, 0x00, 0x0C, 0x03)
        bleRepository.sendCommand(command)
    }


    fun sendPersonalizedSettings(
        motorAngles: ShortArray,
        accelerations: ByteArray,
        speedLimits: ByteArray,
        currentLimits: ByteArray
    ) {
        viewModelScope.launch {
            bleRepository.sendPersonalizedSettings(motorAngles, accelerations, speedLimits, currentLimits)
        }
    }

    fun setLightState(isOn: Boolean) {
        viewModelScope.launch {
            bleRepository.setLightState(isOn)
        }
    }

    // Add function for Tire Circumference
    fun updateTireCircumference(circumferenceMm: Int) {
        viewModelScope.launch {
            bleRepository.setTireCircumference(circumferenceMm)
        }
    }

    fun updateMeterTotalGear(gears: Int) {
        // Basic validation, more robust validation in Fragment before calling
        if (gears in MeterInfo.MIN_TOTAL_GEAR..MeterInfo.MAX_TOTAL_GEAR) {
            viewModelScope.launch {
                Log.d("DeviceViewModel", "Updating Meter Total Gear to: $gears")
                // Offset 160, U16
                bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 160.toByte(), gears.toShort())
            }
        } else {
            Log.w("DeviceViewModel", "Invalid value for updateMeterTotalGear: $gears")
        }
    }

    fun updateMeterSportModel(model: Int) {
        // Expecting MeterInfo.SPORT_MODEL_NORMAL or MeterInfo.SPORT_MODEL_SPORT
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Updating Meter Sport Model to: $model")
            // Offset 162, U8
            bleRepository.sendSingleByteUpdate(BleRepository.CMD_ID_METER, 162.toByte(), model.toByte())
        }
    }

    fun updateMeterBoostState(state: Int) {
        // Expecting MeterInfo.BOOST_STATE_OFF or MeterInfo.BOOST_STATE_ON
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Updating Meter Boost State to: $state")
            // Offset 163, U8
            bleRepository.sendSingleByteUpdate(BleRepository.CMD_ID_METER, 163.toByte(), state.toByte())
        }
    }

    fun updateMeterCurrentGear(gear: Int) { // If editable
        if (gear in MeterInfo.MIN_CURRENT_GEAR..MeterInfo.MAX_CURRENT_GEAR) {
            viewModelScope.launch {
                Log.d("DeviceViewModel", "Updating Meter Current Gear to: $gear")
                // Offset 164, U16
                bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 164.toByte(), gear.toShort())
            }
        } else {
            Log.w("DeviceViewModel", "Invalid value for updateMeterCurrentGear: $gear")
        }
    }

    fun updateMeterAutoShutdown(minutes: Int) {
        // Allow 255 (Never) or values derived from maxAutoShutdown
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Updating Meter Auto Shutdown to: $minutes")
            // Offset 180, U16
            bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 180.toByte(), minutes.toShort())
        }
    }

    fun updateMeterMaxAutoShutdown(minutes: Int) {
        if (minutes in MeterInfo.MIN_MAX_AUTOSHUTDOWN..MeterInfo.MAX_MAX_AUTOSHUTDOWN) {
            viewModelScope.launch {
                Log.d("DeviceViewModel", "Updating Meter Max Auto Shutdown to: $minutes")
                // Offset 182, U16
                bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 182.toByte(), minutes.toShort())
            }
        } else {
            Log.w("DeviceViewModel", "Invalid value for updateMeterMaxAutoShutdown: $minutes")
        }
    }

    fun updateMeterUnitSwitch(unit: Int) {
        // Expecting MeterInfo.UNIT_KMH or MeterInfo.UNIT_MPH
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Updating Meter Unit Switch to: $unit")
            // Offset 184, U16
            bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 184.toByte(), unit.toShort())
        }
    }

    fun updateMeterLight(state: Int) {
        // Expecting 0 or 1
        viewModelScope.launch {
            Log.d("DeviceViewModel", "Updating Meter Light State to: $state")
            // Offset 166, U16 (Even though it's on/off, the field is U16)
            bleRepository.sendShortUpdate(BleRepository.CMD_ID_METER, 166.toByte(), state.toShort())
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleRepository.cleanup()
    }
}
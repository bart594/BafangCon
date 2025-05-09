package com.test.bafangcon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList // Import LinkedList for Queue
import java.util.Queue
import java.util.concurrent.atomic.AtomicBoolean // Import AtomicBoolean


class BleRepository(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null // NUS RX Char
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null // NUS TX Char

    // --- Command Queue ---
    private val commandQueue: Queue<ByteArray> = LinkedList()
    private val isWriting = AtomicBoolean(false) // Flag to indicate ongoing write

    // --- StateFlows for Parsed Data ---
    private val _controllerInfo = MutableStateFlow<ControllerInfo?>(null)
    val controllerInfo: StateFlow<ControllerInfo?> = _controllerInfo.asStateFlow()

    private val _meterInfo = MutableStateFlow<MeterInfo?>(null)
    val meterInfo: StateFlow<MeterInfo?> = _meterInfo.asStateFlow()

    // Add StateFlow for PersonalizedInfo
    private val _personalizedInfo = MutableStateFlow<PersonalizedInfo?>(null)
    val personalizedInfo: StateFlow<PersonalizedInfo?> = _personalizedInfo.asStateFlow()
    // --------------------------------------
    // --- General BLE State ---
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scanResults = MutableStateFlow<Set<DiscoveredBluetoothDevice>>(emptySet())
    val scanResults: StateFlow<Set<DiscoveredBluetoothDevice>> = _scanResults.asStateFlow()

    // --- Coroutine Scope ---
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scanJob: Job? = null
    private var connectJob: Job? = null

    // --- Fragmentation Buffering State ---
    private var isAssemblingFragmentedFrame: Boolean = false
    private val fragmentedFrameBuffer = ByteArrayOutputStream()
    private var expectedFragmentedFrameLength: Int = 0 // Store expected total length
    // -----------------------------------
    // ..

    companion object {
        private const val TAG = "BleRepository"
        private const val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds
        private const val CONNECTION_TIMEOUT: Long = 10000 // Connection attempt timeout

     //  Constants
        private const val FRAME_START_BYTE_1: Byte = 0x55
        private const val FRAME_START_BYTE_2: Byte = -0x56 // 0xAA
        private const val FIXED_VALUE_1: Byte = 0x01
        private const val FIXED_VALUE_2: Byte = 0x11        // Indicates a request/command
        private const val READ_INDICATOR: Byte = 0x01       // Respond command for read 0X2 for write
        private const val WRITE_INDICATOR: Byte = 0x02      // Differentiates write commands


        private const val READ_FRAME_SIZE: Int = 10
        private const val FRAME_END_BYTE_FE: Byte = -0x02          // 0xFE
        private const val FRAME_END_BYTE_FF: Byte = -0x01           // 0xFF (As per example)
        private const val WRITE_FRAME_END_BYTE: Byte = -0x02        // 0xFE

        // Fixed size for these specific write commands
        private const val WRITE_CMD_FRAME_SIZE: Int = 10

        // --- Received Frame Structure Indices/Offsets
        // Byte Pos | Field                  | Index | Example (Partial SoftVer)
        //----------|------------------------|-------|---------------------------
        // 1-2      | Start of Frame         | 0-1   | 55 aa
        // 3        | Total Payload Length   | 2     | 18 (Hex = 24 Dec)
        // 4        | Command ID / Type      | 3     | a5
        // 5        | Fixed Param 1? Echo?   | 4     | 11  Target ID
        // 6        | Fixed Param 2? Status? | 5     | 04   Response ?
        // 7        | Start Position         | 6     | 18 (Hex = 24 Dec)
        // 8 to CRC | Payload Data           | 7+    | 44 50 ...
   // last 2 bytes  | CRC                    | 7+    | ...c4 e9
        //--------------------------------------------------------------------
        private const val RSP_TOTAL_PAYLOAD_LEN_INDEX = 2
        private const val RSP_TYPE_INDEX = 3        // Command ID/Type from device
        private const val RSP_CMD_ECHO_INDEX = 4    // Often 0x11?
        private const val RSP_STATUS_INDEX = 5      // Often 0x04?
        private const val RSP_START_POS_INDEX = 6   // Start position for partial data
        private const val RSP_PAYLOAD_START_INDEX = 7 // Index where actual data begins
        private const val RSP_MIN_HEADER_SIZE = 7 // Minimum bytes needed to read up to payload start
        private const val RSP_TRAILER_SIZE = 2 // Size after payload (CRC_L CRC_H FE)

        // --- Command IDs (Used as Byte 5 in SENT frame) ---
        const val CMD_ID_CONTROLLER: Byte = -0x5d // 0xA3
        const val CMD_ID_METER: Byte = -0x5b      // 0xA5
        const val CMD_ID_PERSONALIZED: Byte = -0x57 // 0xA9
        const val CMD_ID_BATTERY: Byte = -0x5c    // 0xA4
        const val CMD_ID_SENSOR: Byte = -0x59     // 0xA7
        const val CMD_ID_CONFIG: Byte = -0x5f     // 0xA1
        const val CMD_ID_CAN: Byte = -0x5e        // 0xA2

        // --- Write Target Offsets (Based on MeterInfo analysis) ---
        const val METER_OFFSET_LIGHT: Byte = -0x5a      // 0xA6
        // Add other offsets if needed (e.g., for ControllerInfo writes)
        // const val CONTROLLER_OFFSET_XXX: Byte = ...


        // --- Expected Response Payload Sizes (Mapping CMD ID to Size) ---
        // Using a map for easier lookup
        private val EXPECTED_RESPONSE_PAYLOAD_SIZES  = mapOf(
            CMD_ID_CONTROLLER to BfMeterConfig.BfControllerInfo_Total_Size, // 237
            CMD_ID_METER to BfMeterConfig.BfMeterInfo_Total_Size,           // 198
            CMD_ID_PERSONALIZED to BfMeterConfig.BfPersonalizedInfo_Total_Size, // 115
            CMD_ID_BATTERY to BfMeterConfig.BfBattery_Total_Size,         // 244 (Add to BfMeterConfig.kt)
            CMD_ID_SENSOR to BfMeterConfig.BfSensorInfo_Total_Size,         // 164 (Add to BfMeterConfig.kt)
            CMD_ID_CONFIG to BfMeterConfig.BfIotConfigInfo_Total_Size,      // 237 (Add to BfMeterConfig.kt)
            CMD_ID_CAN to BfMeterConfig.BfIotCanInfo_Total_Size             // 97  (Add to BfMeterConfig.kt)
            // Add mappings for other commands if their response size is known/fixed
        )

    }

    // --- Scanning ---
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasRequiredPermissions()) {
            Log.d(TAG, "Scanning failed: Missing Permissions")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.d(TAG, "Scanning failed: Bluetooth not enabled")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (_connectionState.value == BleConnectionState.SCANNING) {
            Log.d(TAG, "Scan already in progress")
            return
        }

        _scanResults.value = emptySet()
        _connectionState.value = BleConnectionState.SCANNING
        Log.d(TAG, "Starting BLE Scan for ALL nearby devices...")

        scanJob?.cancel()
        scanJob = coroutineScope.launch {
            try {
                val filters: List<ScanFilter>? = null // No filtering
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setReportDelay(0)
                    .build()

                bluetoothLeScanner?.startScan(filters, settings, leScanCallback)
                Log.d(TAG, "Scanner started (no filters).")

                delay(SCAN_PERIOD)
                if (_connectionState.value == BleConnectionState.SCANNING) {
                    stopScan() // Stop scan after timeout
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting scan: ${e.message}", e)
                _connectionState.value = BleConnectionState.FAILED
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasRequiredPermissions()) {
            return
        }
        if (_connectionState.value != BleConnectionState.SCANNING) {
            return
        }

        scanJob?.cancel()
        scanJob = null
        try {
            bluetoothLeScanner?.stopScan(leScanCallback)
            Log.d(TAG,"Scan stopped.")
            if (_connectionState.value == BleConnectionState.SCANNING) {
                _connectionState.value = BleConnectionState.DISCONNECTED
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping scan: ${e.message}", e)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (_connectionState.value != BleConnectionState.SCANNING) return

            val discoveredDevice = DiscoveredBluetoothDevice(
                name = result.device.name ?: "Unknown Device",
                address = result.device.address,
                rssi = result.rssi
            )
            _scanResults.update { it + discoveredDevice }
        }
        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            if (_connectionState.value != BleConnectionState.SCANNING) return

            val newDevices = results.mapNotNull { result ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    DiscoveredBluetoothDevice(
                        name = "Unknown Device",
                        address = result.device.address,
                        rssi = result.rssi
                    )
                } else {
                        DiscoveredBluetoothDevice(
                        name = result.device.name ?: "Unknown Device",
                        address = result.device.address,
                        rssi = result.rssi
                    )
                }
            }.toSet()
            _scanResults.update { it + newDevices }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                else -> "Unknown scan error: $errorCode"
            }
            Log.e(TAG, "onScanFailed: $errorText")
            _connectionState.value = BleConnectionState.FAILED
            scanJob?.cancel()
            scanJob = null
        }
    }

    // --- Connection ---
    @SuppressLint("MissingPermission")
    fun connectDevice(deviceAddress: String) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG,"Connection failed: Missing Permissions")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG,"Connection failed: Bluetooth not enabled")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG,"Connection failed: Device not found")
            _connectionState.value = BleConnectionState.FAILED
            return
        }
        if (_connectionState.value == BleConnectionState.CONNECTING || _connectionState.value == BleConnectionState.CONNECTED) {
            Log.d(TAG,"Already connecting or connected to ${currentGatt?.device?.address}")
            if (currentGatt?.device?.address != deviceAddress) { disconnect() } else { return }
        }

        stopScan() // Stop scanning before connecting

        Log.d(TAG,"Connecting to ${device.name ?: deviceAddress}...")
        _connectionState.value = BleConnectionState.CONNECTING
        clearCommandQueue()

        connectJob?.cancel()
        connectJob = coroutineScope.launch(Dispatchers.IO) {
            try {
                currentGatt?.close()
                currentGatt = null
                writeCharacteristic = null
                notifyCharacteristic = null

                currentGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (currentGatt == null) { throw Exception("connectGatt returned null") }

                withTimeout(CONNECTION_TIMEOUT) {
                    _connectionState.first { it != BleConnectionState.CONNECTING }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Connection attempt timed out for $deviceAddress")
                handleDisconnectOrFailure()
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed for $deviceAddress: ${e.message}", e)
                handleDisconnectOrFailure()
            } finally {
                connectJob = null
            }
        }
    }

    // --- Disconnection ---
    @SuppressLint("MissingPermission")
    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val deviceAddress = currentGatt?.device?.address
        if (currentGatt != null && hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.d(TAG,"Disconnecting from ${currentGatt?.device?.name ?: deviceAddress}...")
            clearCommandQueue() // Clear queue before disconnecting
            currentGatt?.disconnect()
            // Safeguard close
            coroutineScope.launch {
                delay(500)
                if (currentGatt != null) {
                    Log.w(TAG, "Forcing GATT close after disconnect timeout")
                    currentGatt?.close()
                    handleDisconnectOrFailure()
                }
            }
        } else {
            handleDisconnectOrFailure() // Reset state if already disconnected/null
        }
    }
    @SuppressLint("MissingPermission")
    private fun handleDisconnectOrFailure() {
        currentGatt?.close() // Ensure closed if not null
        currentGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        clearCommandQueue() // Clear queue and reset flag
        // Reset parsed data StateFlows
        _controllerInfo.value = null
        _meterInfo.value = null
        if (_connectionState.value != BleConnectionState.DISCONNECTED) {
            _connectionState.value = BleConnectionState.DISCONNECTED // Default to disconnected state
        }
        connectJob?.cancel() // Ensure any lingering connect job is cancelled
    }

    // --- GATT Callback ---
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = gatt.device.name ?: deviceAddress
            coroutineScope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Successfully connected to $deviceName")
                            clearCommandQueue() // Ensure queue is clear
                            delay(600)
                            if (!gatt.discoverServices()) {
                                Log.e(TAG, "Failed to start service discovery for $deviceName")
                                gatt.disconnect()
                            } else {
                                Log.d(TAG, "Started service discovery for $deviceName")
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Successfully disconnected from $deviceName")
                            handleDisconnectOrFailure() // Use helper to reset state
                        }
                        else -> Log.w(TAG, "Unhandled connection state change: $newState for $deviceName")
                    }
                } else {
                    Log.e(TAG, "GATT Error onConnectionStateChange for $deviceName. Status: $status, NewState: $newState")
                    handleDisconnectOrFailure() // Use helper to reset state
                    if (newState == BluetoothProfile.STATE_CONNECTING || _connectionState.value == BleConnectionState.CONNECTING) {
                        _connectionState.value = BleConnectionState.FAILED
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            coroutineScope.launch {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Services discovered successfully for $deviceName.")
                    val service = gatt.getService(BleConstants.SERVICE_UUID) // Check for NUS
                    if (service == null) {
                        Log.e(TAG, "Nordic UART Service ${BleConstants.SERVICE_UUID} not found on $deviceName")
                        gatt.disconnect()
                        _connectionState.value = BleConnectionState.FAILED
                        return@launch
                    }
                    writeCharacteristic = service.getCharacteristic(BleConstants.UART_WRITE_UUID)
                    notifyCharacteristic = service.getCharacteristic(BleConstants.UART_NOTIFY_UUID)
                    if (writeCharacteristic == null || notifyCharacteristic == null) {
                        Log.e(TAG, "Required NUS characteristics (TX/RX) not found in service on $deviceName")
                        gatt.disconnect()
                        _connectionState.value = BleConnectionState.FAILED
                        return@launch
                    }
                    enableNotifications(gatt, notifyCharacteristic!!)
                } else {
                    Log.e(TAG, "Service discovery failed for $deviceName with status: $status")
                    gatt.disconnect()
                    _connectionState.value = BleConnectionState.FAILED
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val cccdUuid = BleConstants.CCCD_UUID
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) { /* Error handling */ Log.e(TAG, "CCCD descriptor not found"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            if (!gatt.setCharacteristicNotification(characteristic, true)) { /* Error handling */ Log.e(TAG, "Failed to enable local notification"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to CCCD ${descriptor.uuid}")
            val payload = when {
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY > 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE > 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> { /* Error handling */ Log.e(TAG, "Characteristic supports neither Notify nor Indicate"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; return }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeDescriptor(descriptor, payload); Log.d(TAG, "writeDescriptor (Tiramisu+) result code: $result"); if(result != BluetoothStatusCodes.SUCCESS) { Log.e(TAG, "gatt.writeDescriptor failed immediately with code: $result") }
            } else {
                descriptor.value = payload; if (!gatt.writeDescriptor(descriptor)) { /* Error handling */ Log.e(TAG, "Legacy gatt.writeDescriptor failed"); gatt.disconnect(); _connectionState.value = BleConnectionState.FAILED; }
            }
        }



        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            coroutineScope.launch {
                if (descriptor.uuid == BleConstants.CCCD_UUID && descriptor.characteristic.uuid == BleConstants.UART_NOTIFY_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Notifications enabled successfully for $deviceName")
                        _connectionState.value = BleConnectionState.CONNECTED
                        //connectJob?.cancel()
                        processNextCommand() // Try sending queued commands now
                    } else {
                        Log.e(TAG, "Failed to write CCCD for $deviceName. Status: $status")
                        gatt.disconnect()
                       // _connectionState.value = BleConnectionState.FAILED
                    }
                } else { Log.w(TAG, "onDescriptorWrite for unknown descriptor: ${descriptor.uuid}") }
            }
        }




        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            val dataWrittenHex = characteristic.value?.toHexString() ?: "N/A"
            coroutineScope.launch {
                if (characteristic.uuid == BleConstants.UART_WRITE_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "Successfully wrote to NUS RX (${characteristic.uuid}) on $deviceName: $dataWrittenHex")
                    } else {
                        Log.e(TAG, "Failed to write to NUS RX (${characteristic.uuid}) on $deviceName. Status: $status ")
                    }
                    isWriting.set(false) // Signal write completion
                    processNextCommand() // Process next command
                } else {
                    Log.w(TAG, "onCharacteristicWrite for unknown characteristic: ${characteristic.uuid}")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { handleCharacteristicChange(characteristic) }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { handleCharacteristicChange(characteristic, value) }
        private fun handleCharacteristicChange(characteristic: BluetoothGattCharacteristic, value: ByteArray? = null) {
            val data = value ?: characteristic.value
            if (characteristic.uuid == BleConstants.UART_NOTIFY_UUID) {
                if (data != null) { processBleNotificationData(data) } // Call refactored parsing function
                else { Log.w(TAG, "Received null data on ${characteristic.uuid}") }
            } else { Log.w(TAG, "onCharacteristicChanged for unexpected characteristic: ${characteristic.uuid}") }
        }
        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val deviceName = gatt.device.name ?: gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) { Log.i(TAG, "MTU changed to $mtu for $deviceName"); }
            else { Log.w(TAG, "MTU change failed for $deviceName. Status: $status"); }
        }
    } // End gattCallback

// --- Data Sending and Queue Handling ---

    /**
     * Creates a write command frame (55 AA ... ChecksumL ChecksumH or 55 AA ... Checksum FE).
     * Uses AA55Pack checksum logic for multi-byte payloads, and original 8-bit checksum
     * plus FE trailer for single-byte payloads.
     *
     * @param targetInfoType The Command ID byte indicating the target data structure (e.g., CMD_ID_METER).
     * @param startPosition The offset within the target structure to write to.
     * @param payloadData The byte array containing the data to write.
     * @return The command frame byte array.
     */
    private fun createWriteRequestFrame(targetInfoType: Byte, startPosition: Byte, payloadData: ByteArray): ByteArray {
        // --- Calculate Frame Sizes ---
        val payloadLength = payloadData.size
        if (payloadLength < 1) {
            Log.e(TAG, "Write payload cannot be empty.")
            return byteArrayOf()
        }

        val headerSize = 7 // 55 AA Len 11 Cmd 02 StartPos
        val isMultiBytePayload = payloadLength > 1
        val checksumSize = if (isMultiBytePayload) 2 else 1 // 2 bytes for AA55, 1 for original
        val trailerSize = if (isMultiBytePayload) 0 else 1 // 0 bytes for AA55 (checksum replaces FE), 1 byte (FE) for original

        val totalFrameSize = headerSize + payloadLength + checksumSize + trailerSize

        // Create the frame array
        val frame = ByteArray(totalFrameSize)

        // --- Fill Frame Header ---
        var index = 0
        frame[index++] = FRAME_START_BYTE_1      // 55
        frame[index++] = FRAME_START_BYTE_2      // AA
        frame[index++] = payloadLength.toByte()  // Actual payload length
        frame[index++] = FIXED_VALUE_2           // 11
        frame[index++] = targetInfoType          // e.g., A5
        frame[index++] = WRITE_INDICATOR         // 02
        frame[index++] = startPosition           // e.g., A4, A6

        // --- Add Payload Data ---
        System.arraycopy(payloadData, 0, frame, index, payloadLength)
        index += payloadLength // index now points to where checksum starts

        // --- Calculate and Add Checksum ---
        val checksumEndIndex = index - 1 // Index of the last payload byte
        var sum: Int = 0
        // Sum bytes from index 2 (Length) up to the end of the payload
        for (i in 2..checksumEndIndex) {
            sum += (frame[i].toInt() and 0xFF)
        }
        sum = sum and -1 // Mask sum to 32 bits just in case

        if (isMultiBytePayload) {
            // --- Use AA55Pack 16-bit checksum logic ---
            // Checksum = ( 0xFFFFF ^ Sum ) & 0xFFFF --> effectively (~Sum) & 0xFFFF
            val calculatedChecksum = sum.inv() and 0xFFFF
            Log.v(TAG, "AA55 Checksum Calc: Sum=0x${sum.toString(16)}, CS=0x${calculatedChecksum.toString(16).padStart(4, '0')}")

            frame[index++] = (calculatedChecksum and 0xFF).toByte()       // LSB
            frame[index++] = ((calculatedChecksum shr 8) and 0xFF).toByte() // MSB
            // No FE byte added here for multi-byte AA55 logic
        } else {
            // --- Use original 8-bit checksum for single-byte payloads ---
            // Checksum = ( 0xFF - ( Sum & 0xFF ) ) & 0xFF
            val sumLsb = sum and 0xFF
            val checksum = (0xFF - sumLsb) and 0xFF
            Log.v(TAG, "8-Bit Checksum Calc: Sum=0x${sum.toString(16)}, CS=0x${checksum.toString(16).padStart(2, '0')}")

            frame[index++] = checksum.toByte()
            frame[index++] = WRITE_FRAME_END_BYTE      // Add FE trailer ONLY for single-byte payloads
        }

        // --- Final Check ---
        if (index != totalFrameSize) {
            Log.e(TAG, "Frame construction size mismatch! Expected $totalFrameSize, Got $index. PayloadLen=$payloadLength, isMulti=$isMultiBytePayload")
            return byteArrayOf() // Return empty on internal error
        }

        Log.d(TAG, "Created Write Frame: ${frame.toHexString()}")
        return frame
    }

    /** Generic function to send a single byte update */
    internal  fun sendSingleByteUpdate(targetType: Byte, offset: Byte, value: Byte) {
        val payload = byteArrayOf(value)
        val command = createWriteRequestFrame(targetType, offset, payload)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Single Byte Write: Type=0x${targetType.toHexString()}, Offset=0x${offset.toHexString()}, Value=0x${value.toHexString()}")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create single byte write frame.")
        }
    }
    /** Generic function to send a short (16-bit) update - uses multi-byte CRC */
    internal  fun sendShortUpdate(targetType: Byte, offset: Byte, value: Short) {
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
        val command = createWriteRequestFrame(targetType, offset, payload)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Short Write: Type=0x${targetType.toHexString()}, Offset=0x${offset.toHexString()}, Value=$value (0x${value.toString(16)})")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create short write frame.")
        }
    }

    /**
     * Sends the complete 50-byte personalized settings block using the standard
     * write frame creation which handles the AA55 checksum.
     */
    fun sendPersonalizedSettings(
        motorAngles: ShortArray, // 10 shorts (20 bytes)
        accelerations: ByteArray, // 10 bytes
        speedLimits: ByteArray, // 10 bytes
        currentLimits: ByteArray // 10 bytes
    ) {
        // 1. Validate input array sizes
        if (motorAngles.size != 10 || accelerations.size != 10 ||
            speedLimits.size != 10 || currentLimits.size != 10) {
            Log.e(TAG, "Invalid array sizes for personalized settings write.")
            return
        }

        // 2. Prepare Payload Buffer (50 bytes)
        val payloadSize = 50
        val payloadBuffer = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)

        // Motor Start Angle (Bytes 0-19)
        for (angle in motorAngles) {
            payloadBuffer.putShort(angle)
        }
        // Acceleration (Bytes 20-29)
        payloadBuffer.put(accelerations)
        // Speed Limit (Bytes 30-39)
        payloadBuffer.put(speedLimits)
        // Current Limit (Bytes 40-49)
        payloadBuffer.put(currentLimits)

        val payload = payloadBuffer.array()

        // 3. Create the command frame using the generic function
        //    Target Type: CMD_ID_PERSONALIZED (0xA9)
        //    Start Position: 0x41 (65) - This seems to be the fixed start for this block
        //    Payload: The 50 bytes we just assembled
        Log.d(TAG, "Creating Personalized Settings write frame...")
        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_PERSONALIZED,
            startPosition = 0x41.toByte(), // 65 decimal
            payloadData = payload
        )

        // 4. Queue the command if frame creation was successful
        if (commandFrame.isNotEmpty()) {
            Log.d(TAG,"Queueing Personalized Write: ${commandFrame.toHexString()}")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG,"Failed to create personalized write frame.")
        }
    }

    /** Sets the Headlight state (Assumes 1 byte payload) */
    fun setLightState(isOn: Boolean) {
        Log.d(TAG, "Setting Light State to: ${if(isOn) "ON" else "OFF"}")
        val value = if (isOn) 0x01.toByte() else 0x00.toByte()
        val payload = byteArrayOf(value) // Create 1-byte payload
        val command = createWriteRequestFrame(
            CMD_ID_METER,               // Target Meter Info
            METER_OFFSET_LIGHT,         // Start Position 0xA6
            payload                     // Pass payload byte array
        )
        Log.d(TAG,"Queueing Set Light Cmd: ${command.toHexString()}")
        sendCommand(command)
    }

    fun setTireCircumference(circumferenceMm: Int) {
        if (circumferenceMm !in 0..3000) { Log.w(TAG, "Invalid tire circumference: $circumferenceMm"); return }
        val value = circumferenceMm.toShort()
        val payload = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()

        val commandFrame = createWriteRequestFrame(
            targetInfoType = CMD_ID_CONTROLLER,
            startPosition = 184.toByte(),
            payloadData = payload
        )
        //  Queue the command if frame creation was successful
        if (commandFrame.isNotEmpty()) {
            Log.d(TAG,"Queueing Personalized Write: ${commandFrame.toHexString()}")
            sendCommand(commandFrame)
        } else {
            Log.e(TAG,"Failed to create personalized write frame.")
        }

    }

    /**
     * Creates a 10-byte custom command frame (55 AA ... FF) to request a specific
     * data segment.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     * @param startPosition The starting byte offset of the data to read (0-255).
     * @param requestedLength The number of bytes to read (0-255).
     * @return The 10-byte command frame, or an empty ByteArray on error.
     */
    @SuppressLint("SuspiciousIndentation")
    private fun createReadRequestFrame(commandId: Byte, startPosition: Int, requestedLength: Int): ByteArray {
        // Input validation
        if (startPosition !in 0..255 || requestedLength !in 0..255) {
            Log.e(
                TAG,
                "Invalid startPosition ($startPosition) or requestedLength ($requestedLength). Must be 0-255."
            )
            return byteArrayOf()
        }

        // Create the 10-byte frame array
        val frame = ByteArray(READ_FRAME_SIZE) // Should be 10

        // Fill fixed and variable parts (Indices 0-7)
        frame[0] = FRAME_START_BYTE_1          // 55
        frame[1] = FRAME_START_BYTE_2          // AA
        frame[2] = FIXED_VALUE_1               // 01
        frame[3] = FIXED_VALUE_2               // 11
        frame[4] = commandId                   // e.g., A5
        frame[5] = READ_INDICATOR              // 01 Respond command for read 0X2 for write
        frame[6] = startPosition.toByte()      // Starting byte offset
        frame[7] = requestedLength.toByte()    // Length to read

        // Calculate Checksum (Bytes 3 to 8 / Indices 2 to 7)
        // Checksum = ( 0xFF - ( Sum(Bytes 3 to 8) & 0xFF ) ) & 0xFF
        var sum: Int = 0
        for (i in 2..7) { // Sum bytes at indices 2, 3, 4, 5, 6, 7
            sum += (frame[i].toInt() and 0xFF)
        }
        val sumLsb = sum and 0xFF
        val checksum = (0xFF - sumLsb) and 0xFF
        frame[8] = checksum.toByte() // Checksum byte at index 8

        // End Frame
        if (startPosition > 0) {
            frame[9] = FRAME_END_BYTE_FF   // FF
        } else {
            frame[9] = FRAME_END_BYTE_FE      // FE
        }
        return frame
    }

    // --- Public Function to Send Custom Request ---
    /**
     * Queues a command to request a specific segment of data from the device.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     * @param start The starting byte offset (0-based) of the data segment.
     * @param length The number of bytes to request.
     */
    fun requestDataSegment(commandId: Byte, start: Int, length: Int) {
        Log.d(TAG, "Requesting data segment: Type=0x${commandId.toHexString()}, Start=$start, Length=$length")
        val command = createReadRequestFrame(commandId, start, length)
        if (command.isNotEmpty()) {
            Log.d(TAG,"Queueing Custom Req: ${command.toHexString()}")
            sendCommand(command)
        } else {
            Log.d(TAG,"Failed to create custom request frame (invalid params?).")
        }
    }

    /**
     * Creates and queues a standard 10-byte read request frame.
     * This is intended to be called by the ViewModel.
     *
     * @param commandId The target device/info type (e.g., CMD_ID_METER).
     */
    internal fun sendReadRequestCommand(commandId: Byte) {
        Log.d(TAG, "Preparing read request for Cmd ID: 0x${commandId.toHexString()}")

        val expectedResponseLength = EXPECTED_RESPONSE_PAYLOAD_SIZES[commandId]
        if (expectedResponseLength == null) {
            Log.w(TAG, "Cannot send read request: Unknown expected response size for Cmd ID 0x${commandId.toHexString()}")
            // Optionally: Send a default length request? Or fail? Let's fail for now.
            // Add Log or some error feedback if needed
            return
        }

        val responseLengthByte = (expectedResponseLength ?: 0).let {
            if (it > 255) 0xFF else it // Cap at 255 if size is too large
        }.toInt()
        val command = createReadRequestFrame(commandId,0, responseLengthByte )

        if (command.isNotEmpty()) {
            Log.d(TAG, "Queueing Read Request: ${command.toHexString()}")
            // Use the *private* sendCommand helper
            sendCommand(command)
        } else {
            Log.e(TAG, "Failed to create read request frame for Cmd ID 0x${commandId.toHexString()}")
        }
    }


   fun sendCommand(data: ByteArray) {
        if (_connectionState.value != BleConnectionState.CONNECTED) {   Log.d(TAG,"Cannot queue command: Not connected."); return }
        if (writeCharacteristic == null) {   Log.d(TAG,"Cannot queue command: Write Characteristic not available."); return }
        val writeProperty = writeCharacteristic!!.properties
        if (writeProperty and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == 0) {
            Log.d(TAG,"Error: Write characteristic does not support writing."); return
        }
        synchronized(commandQueue) { commandQueue.offer(data); Log.d(TAG, "Command queued. Queue size: ${commandQueue.size}") }
        processNextCommand()
    }

    @SuppressLint("MissingPermission")
    private fun processNextCommand() {
        coroutineScope.launch(Dispatchers.IO) {
            if (_connectionState.value != BleConnectionState.CONNECTED) { Log.w(TAG, "processNextCommand: Not connected, clearing queue."); clearCommandQueue(); return@launch }
            if (isWriting.get()) { Log.d(TAG, "processNextCommand: Write already in progress."); return@launch }

            var commandToSend: ByteArray? = null
            synchronized(commandQueue) {
                if (commandQueue.isNotEmpty()) {
                    if (isWriting.compareAndSet(false, true)) { commandToSend = commandQueue.poll() }
                    else { Log.d(TAG,"processNextCommand: Busy flag was already set, skipping."); return@launch }
                } else { Log.d(TAG,"processNextCommand: Queue is empty."); return@launch }
            }

            if (commandToSend != null) {
                Log.d(TAG, "Processing command: ${commandToSend!!.toHexString()}. Queue size: ${commandQueue.size}")

                // *** CORRECTED PERMISSION CHECK ***
                var permissionCheckPassed = true // Assume true initially
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Only check BLUETOOTH_CONNECT on API 31+
                    if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        Log.e(TAG, "processNextCommand: Missing BLUETOOTH_CONNECT permission (API 31+).")
                        permissionCheckPassed = false
                    }
                }
                // No specific *write* permission check needed for API < 31 here.
                // If connectGatt succeeded, BLUETOOTH permission was implicitly handled.

                if (!permissionCheckPassed) {
                    isWriting.set(false) // Reset busy flag as we are aborting
                    // Command is lost because permission is missing
                    return@launch // Abort processing this command
                }
                // **********************************


                if (currentGatt == null || writeCharacteristic == null) { Log.e(TAG, "processNextCommand: GATT or Write Characteristic became null."); isWriting.set(false); return@launch }
                // Redundant check, already covered above, but keeping for safety:
                // if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) { Log.e(TAG, "processNextCommand: Missing BLUETOOTH_CONNECT permission."); addLog("Send Error: Missing permission."); isWriting.set(false); return@launch }

                val writeType = when {
                    writeCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE > 0 -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    writeCharacteristic!!.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE > 0 -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else -> { Log.e(TAG, "processNextCommand: Write characteristic lost write property?"); return@launch }
                }
                Log.d(TAG,"Sending: ${commandToSend!!.toHexString()}")
                val success: Boolean = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        currentGatt?.writeCharacteristic(writeCharacteristic!!, commandToSend!!, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        writeCharacteristic!!.value = commandToSend!!; writeCharacteristic!!.writeType = writeType; currentGatt?.writeCharacteristic(writeCharacteristic!!) ?: false
                    }
                } catch (e: Exception) { Log.e(TAG, "Exception during writeCharacteristic: ${e.message}", e); false }

                if (!success) {
                    Log.e(TAG, "writeCharacteristic initiation failed for ${writeCharacteristic!!.uuid}")
                    isWriting.set(false)
                    processNextCommand() // Try next command immediately
                } else { Log.d(TAG, "Write initiated for: ${commandToSend!!.toHexString()}. Waiting for callback.") }
            }
        }
    }
    private fun clearCommandQueue() { synchronized(commandQueue) { commandQueue.clear() }; isWriting.set(false); Log.d(TAG, "Command queue cleared.") }

    // This function now directly processes the 55 AA ... FE frame
    private fun processBleNotificationData(data: ByteArray) {
        val rawHexString = data.toHexString()
        Log.d(TAG,"Raw BLE Notify: $rawHexString (Size: ${data.size})")

        // --- Frame Assembly Logic ---
        if (data.isNotEmpty()) {
            fragmentedFrameBuffer.write(data)
            if (isAssemblingFragmentedFrame || fragmentedFrameBuffer.size() <= RSP_MIN_HEADER_SIZE) {
                Log.d(TAG,"Fragment Assembly: Appended data (New Buffer Size: ${fragmentedFrameBuffer.size()})")
            }
        } else {
            Log.d(TAG,"Fragment Assembly: Received empty notification.")
            return
        }

        // Loop to process buffer content
        while (true) {
            val currentBufferBytes = fragmentedFrameBuffer.toByteArray()
            val currentBufferSize = currentBufferBytes.size

            if (!isAssemblingFragmentedFrame) {
                // --- Try to start a new frame ---
                if (currentBufferSize < RSP_MIN_HEADER_SIZE) { // Need header including StartPos
                    if (currentBufferSize > 0)  Log.d(TAG,"Fragment Assembly: Buffer too short for header ($currentBufferSize bytes). Waiting...")
                    break // Wait for more data
                }

                if (currentBufferBytes[0] == FRAME_START_BYTE_1 && currentBufferBytes[1] == FRAME_START_BYTE_2) {
                    // Found start sequence 55 AA
                    val declaredTotalPayloadLength = currentBufferBytes[RSP_TOTAL_PAYLOAD_LEN_INDEX].toInt() and 0xFF
                    val rspType = currentBufferBytes[RSP_TYPE_INDEX]

                    // Calculate expected total frame size based on DECLARED payload length
                    // Total Frame Size = SOF(2) + DeclaredPayloadLen(1) + HeaderUntilPayload(4) + Payload(N) +Trailer(2)

                    expectedFragmentedFrameLength = RSP_PAYLOAD_START_INDEX + declaredTotalPayloadLength + RSP_TRAILER_SIZE

                    isAssemblingFragmentedFrame = true // Mark as assembling
                    Log.d(TAG,"Fragment Assembly: START detected (Type=0x${rspType.toHexString()}, Declared Payload Len=$declaredTotalPayloadLength), Expecting Total Frame Length: $expectedFragmentedFrameLength (Buffer Size: $currentBufferSize)")
                    // Fall through to check if length is already met

                } else {
                    // Discard invalid start byte
                    Log.d(TAG,"Fragment Assembly: Discarding invalid byte at buffer start: 0x${currentBufferBytes[0].toHexString()}")
                    val remainingInBuffer = currentBufferBytes.sliceArray(1 until currentBufferSize)
                    fragmentedFrameBuffer.reset()
                    if (remainingInBuffer.isNotEmpty()) { fragmentedFrameBuffer.write(remainingInBuffer); continue }
                    else { break }
                }
            } // End if (!isAssemblingFragmentedFrame)

            // --- Continue assembling or process complete frame ---
            if (isAssemblingFragmentedFrame) {
                if (expectedFragmentedFrameLength <= 0) {
                    Log.d(TAG,"Fragment Assembly: Error - Assembling but expected length is unknown (<=0). Resetting.")
                    resetFragmentedFrameState()
                    fragmentedFrameBuffer.reset()
                    break
                }

                if (currentBufferSize >= expectedFragmentedFrameLength) {
                    // --- Received expected number of bytes (or more) ---
                    val completeFrame = currentBufferBytes.sliceArray(0 until expectedFragmentedFrameLength)
                    Log.d(TAG,"Fragment Assembly: Declared length ($expectedFragmentedFrameLength) reached. Processing frame.")

                    // Process the complete frame
                    parseCompleteFrame(completeFrame) // Parse based on extracted length

                    // --- Handle leftover bytes ---
                    val leftoverBytes = currentBufferBytes.sliceArray(expectedFragmentedFrameLength until currentBufferSize)
                    resetFragmentedFrameState()
                    fragmentedFrameBuffer.reset()
                    if (leftoverBytes.isNotEmpty()) {
                        Log.d(TAG,"Fragment Assembly: Found ${leftoverBytes.size} leftover bytes. Re-buffering.")
                        fragmentedFrameBuffer.write(leftoverBytes)
                        continue // Immediately process leftovers
                    } else {
                        Log.d(TAG,"Fragment Assembly: No leftover bytes.")
                        break // Finished processing
                    }
                    // -----------------------------

                } else {
                    // Not enough data yet
                    Log.d(TAG,"Fragment Assembly: Waiting for more data (Have $currentBufferSize/$expectedFragmentedFrameLength bytes)...")
                    break // Exit loop, wait for next ble notification
                }
            } else {
                break // Should not happen
            }

        } // End while(true)
    }

    // --- New function to reset fragmentation state ---
    private fun resetFragmentedFrameState() {
        isAssemblingFragmentedFrame = false
        fragmentedFrameBuffer.reset()
        expectedFragmentedFrameLength = 0 // Reset expected length
        // Log.d(TAG, "Fragmentation state reset.") // Optional log
    }


    // --- Modified parseCompleteFrame (Handles Full vs Partial based on StartPos) ---
    private fun parseCompleteFrame(data: ByteArray) {
        val frameHexString = data.toHexString()
        val baseLog = "Processing Assembled Frame: $frameHexString (Size: ${data.size})"

        // --- 1. Basic Frame Structure and Size Validation ---
        // Minimum size for AA55 header, length, target, startID, cmd, startPos + 2 CRC bytes = 9
        if (data.size < 9) {
            Log.w(TAG, "$baseLog (Frame Too Short: ${data.size} bytes, need at least 9)")
            return
        }
        // Check start bytes
        if (data[0] != FRAME_START_BYTE_1 || data[1] != FRAME_START_BYTE_2) {
            Log.w(TAG, "$baseLog (Invalid Frame Start)")
            return
        }

        // Extract header fields needed for validation and parsing
        val declaredTotalPayloadLength = data[RSP_TOTAL_PAYLOAD_LEN_INDEX].toInt() and 0xFF
        val rspType = data[RSP_TYPE_INDEX] // Command ID/Type from device
        // val rspCmdEcho = data[RSP_CMD_ECHO_INDEX] // Often 0x11? (Not used in checksum)
        // val rspStatus = data[RSP_STATUS_INDEX] // Often 0x04? (Not used in checksum)
        val rspStartPos = data[RSP_START_POS_INDEX].toInt() and 0xFF // Start position for partial data

        // --- 2. Checksum Validation (AA55Pack Logic) ---
        val checksumStartIndex = 2 // Start summing from the Length byte (index 2)
        val checksumEndIndex = data.size - 3 // Sum up to the byte *before* the first checksum byte

          // Calculate the sum
        var sum = 0
        for (i in checksumStartIndex..checksumEndIndex) {
            sum += (data[i].toInt() and 0xFF)
        }
        // Mask sum to 32 bits just in case, though unlikely to overflow standard Int
        sum = sum and -1 // Equivalent to 0xFFFFFFFF in this context

        Log.v(TAG, "Checksum Calc - Sum (Decimal): ${sum}, (Hex): 0x${sum.toString(16)}")

        // Calculate the expected checksum value (16-bit NOT of the lower 16 bits of the sum)
        val calculatedChecksum = sum.inv() and 0xFFFF
        Log.v(TAG, "Checksum Calc - Expected Value (Decimal): ${calculatedChecksum}, (Hex): 0x${calculatedChecksum.toString(16).padStart(4, '0')}")

        // Extract calculated checksum bytes (Little-Endian)
        val calculatedLsb = (calculatedChecksum and 0xFF).toByte()
        val calculatedMsb = ((calculatedChecksum shr 8) and 0xFF).toByte()
        Log.v(TAG, "Checksum Calc - Expected Bytes (Hex LE): ${calculatedLsb.toHexString()} ${calculatedMsb.toHexString()}")

        // Extract received checksum bytes
        val receivedChecksumLsb = data[data.size - 2]
        val receivedChecksumMsb = data[data.size - 1]
        Log.v(TAG, "Checksum Calc - Received Bytes (Hex): ${receivedChecksumLsb.toHexString()} ${receivedChecksumMsb.toHexString()}")

        // Compare
        val isValid = (calculatedLsb == receivedChecksumLsb) && (calculatedMsb == receivedChecksumMsb)

        if (!isValid) {
            Log.e(TAG, "$baseLog (Checksum INVALID. Calculated: ${calculatedLsb.toHexString()} ${calculatedMsb.toHexString()}, Received: ${receivedChecksumLsb.toHexString()} ${receivedChecksumMsb.toHexString()})")
            // Optionally: Notify UI or higher layer about checksum error
            // _errorStateFlow.value = BleError.ChecksumMismatch
            return // Discard invalid frame
        } else {
            Log.d(TAG, "$baseLog (Checksum VALID)")
        }
        // --- End Checksum Validation ---


        // --- 3. Determine Actual Payload Boundaries (Based on validated frame) ---
        val payloadStartIndex = RSP_PAYLOAD_START_INDEX // Data starts after the header (index 7)
        val payloadEndIndex = data.size - RSP_TRAILER_SIZE // Data ends before the 2 CRC bytes

        if (payloadStartIndex > payloadEndIndex) { // Should not happen if minimum size check passed
            Log.w(TAG, "$baseLog (Invalid payload indices: Start=$payloadStartIndex, End=$payloadEndIndex)")
            return
        }

        val payloadData = data.sliceArray(payloadStartIndex until payloadEndIndex)
        val actualPayloadSize = payloadData.size // This is the reliable payload size now

        // Log header info AFTER validation
        Log.d(TAG,"Validated Frame Header: Type=0x${rspType.toHexString()} StartPos=$rspStartPos (Actual Payload Size=$actualPayloadSize)")

        // 4. Differentiate Full vs Partial based on Start Position
        if (rspStartPos == 0) {
            // --- Full Data Frame ---
            Log.d(TAG,"Detected Full Data Frame (StartPos = 0).")
            var handled = false
            // Optional: Verify if actual payload size matches expected full size for this type
            val expectedFullSize = EXPECTED_RESPONSE_PAYLOAD_SIZES[rspType]
            if (expectedFullSize != null && actualPayloadSize != expectedFullSize) {
                Log.w(TAG,"Warning: Full frame size mismatch! Type=0x${rspType.toHexString()}, Got=$actualPayloadSize, Expected=$expectedFullSize")
                // Decide whether to proceed with parsing or discard
            }

            when (rspType) {
                CMD_ID_CONTROLLER -> {
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = ControllerInfo.parseControllerInfoPayload(payloadData)
                    if (info != null) {
                        _controllerInfo.value = info
                        Log.d(TAG,"Parsed Full Controller Data: ${info.toString()}") // Use data class toString
                    } else {
                        Log.w(TAG,"Failed to parse Full Controller Info payload.")
                    }
                }
                CMD_ID_METER -> {
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = MeterInfo.parseMeterInfoPayload(payloadData)
                    if (info != null) {
                        _meterInfo.value = info
                        Log.d(TAG,"Parsed Full Meter Data: ${info.toString()}") // Use data class toString
                    } else {
                        Log.w(TAG,"Failed to parse Full Meter Info payload.")
                    }
                }
                CMD_ID_PERSONALIZED -> { // Add this case
                    handled = true
                    // Use the static parser from the data class's companion object
                    val info = PersonalizedInfo.parsePersonalizedInfoPayload(payloadData)
                    if (info != null) {
                        _personalizedInfo.value = info // Update StateFlow
                        Log.d(TAG,"Parsed Full Personalized Data: ${info.toString()}") // Use data class toString
                    } else {
                        Log.w(TAG,"Failed to parse Full Personalized Info payload (size: $actualPayloadSize).")
                    }
                }
                CMD_ID_BATTERY -> {
                    handled = true
                    // TODO: Create BatteryInfo.kt and call BatteryInfo.parseBatteryInfoPayload(payloadData)
                    Log.d(TAG,"Received Full Battery Payload: ${payloadData.toHexString()}")
                }
                CMD_ID_SENSOR -> {
                    handled = true
                    // TODO: Create SensorInfo.kt and call SensorInfo.parseSensorInfoPayload(payloadData)
                    Log.d(TAG,("Received Full Sensor Payload: ${payloadData.toHexString()}"))
                }
                CMD_ID_CONFIG -> { // Assuming this maps to IotConfigInfo
                    handled = true
                    // TODO: Create IotConfigInfo.kt and call IotConfigInfo.parseIotConfigInfoPayload(payloadData)
                    Log.d(TAG,("Received Full Config Payload (A1): ${payloadData.toHexString()}"))
                }
                CMD_ID_CAN -> { // Assuming this maps to IotCanInfo
                    handled = true
                    // TODO: Create IotCanInfo.kt and call IotCanInfo.parseIotCanInfoPayload(payloadData)
                    Log.d(TAG,("Received Full CAN Payload (A2): ${payloadData.toHexString()}"))
                }

                else -> {  Log.w(TAG,("Received Full Frame with Unknown Type: 0x${rspType.toHexString()}")) }
            }
            if (!handled && payloadData.isNotEmpty()) {
                Log.w(TAG,("Unhandled Full Payload (Type 0x${rspType.toHexString()}, Size $actualPayloadSize): ${payloadData.toHexString()}"))
            }

        } else {
            // --- Partial Data Frame ---
            Log.d(TAG,"Detected Partial Data Frame (StartPos = $rspStartPos).") // Changed level to Debug
            var handled = false

            when (rspType) {
                CMD_ID_CONTROLLER -> {
                    handled = true
                    Log.d(TAG,"Partial Controller Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")

                    // --- Apply Partial Update ---
                    // 1. Get the current state (make a copy to ensure StateFlow emission)
                    val currentInfo = _controllerInfo.value?.copy(
                        // Deep copy arrays if necessary, although updatePartial modifies the copy directly
                        gearSpeedLimit = _controllerInfo.value?.gearSpeedLimit?.copyOf() ?: ByteArray(10),
                        gearCurrentLimit = _controllerInfo.value?.gearCurrentLimit?.copyOf() ?: ByteArray(10)
                        // rawData doesn't need deep copy here as updatePartial doesn't use it
                    )

                    if (currentInfo != null) {
                        // 2. Apply the update to the copy
                        val success = currentInfo.updatePartial(payloadData, rspStartPos)
                        if (success) {
                            // 3. Emit the modified copy
                            _controllerInfo.value = currentInfo
                            Log.d(TAG,"Successfully applied partial update to ControllerInfo.")
                        } else {
                            Log.w(TAG,"Failed to apply partial update to ControllerInfo (offset $rspStartPos).")
                        }
                    } else {
                        // Cannot apply partial update if we don't have the full data yet.
                        Log.w(TAG, "Received partial Controller update (offset $rspStartPos), but no full data available to update.")
                    }
                    // -------------------------
                }
                CMD_ID_METER -> {
                    handled = true
                    Log.d(TAG,"Partial Meter Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")

                    // --- Apply Partial Update ---
                    // 1. Get current state (make a copy)
                    val currentInfo = _meterInfo.value?.copy() // MeterInfo has no arrays needing deep copy currently

                    if (currentInfo != null) {
                        // 2. Apply update to the copy
                        val success = currentInfo.updatePartial(payloadData, rspStartPos)
                        if (success) {
                            // 3. Emit modified copy
                            _meterInfo.value = currentInfo
                            Log.d(TAG,"Successfully applied partial update to MeterInfo.")
                        } else {
                            Log.w(TAG,"Failed to apply partial update to MeterInfo (offset $rspStartPos).")
                        }
                    } else {
                        Log.w(TAG, "Received partial Meter update (offset $rspStartPos), but no full data available to update.")
                    }
                    // -------------------------
                }
                CMD_ID_PERSONALIZED -> {
                    handled = true
                    Log.d(TAG,"Partial Personalized Data (Start=$rspStartPos, Len=$actualPayloadSize): ${payloadData.toHexString()}")
                    // TODO: Implement partial update logic for PersonalizedInfo if needed
                }
                // Add cases for other types if partial responses are expected/handled
                else -> {
                    Log.w(TAG,("Received Partial Frame with Unknown Type: 0x${rspType.toHexString()}"))
                }
            }
            if (!handled && payloadData.isNotEmpty()) {
                Log.w(TAG,"Unhandled Partial Payload (Type 0x${rspType.toHexString()}, Start=$rspStartPos, Size $actualPayloadSize): ${payloadData.toHexString()}")
            }
        }
    } // End parseCompleteFrame


    // --- Permissions ---
    private fun hasPermission(permission: String): Boolean { return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) }
        else { listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION) }
        return requiredPermissions.all { hasPermission(it) }
    }

    // --- Cleanup ---
    fun cleanup() { Log.d(TAG, "Cleaning up BleRepository"); disconnect(); scanJob?.cancel(); coroutineScope.cancel() }
    private fun Byte.toHexString(): String = String.format("%02X", this)



    // Helper extension function for logging byte arrays
    fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }

} // End of BleRepository class
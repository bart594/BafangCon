package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException


data class ControllerInfo(


    // ... (existing fields remain var)
    var hardVersion: String = "",           // Offset 0, Size 24
    var softVersion: String = "",           // Offset 24, Size 24
    var model: String = "",                 // Offset 48, Size 24
    var sn: String = "",                    // Offset 72, Size 40
    var customerNo: String = "",            // Offset 112, Size 16
    var manufacturer: String = "",          // Offset 128, Size 16
    // Skip 16 bytes (Offset 144 to 159)
    var soc: Int = 0,                       // Offset 160, Size 2 (U16)
    var singleMileage: Int = 0,             // Offset 162, Size 2 (U16)
    var totalMileage: Int = 0,              // Offset 164, Size 2 (U16)
    var emainingMileage: Int = 0,           // Offset 166, Size 2 (U16)
    var cadence: Int = 0,                   // Offset 168, Size 2 (U16)
    var moment: Int = 0,                    // Offset 170, Size 2 (U16)
    var speed: Int = 0,                     // Offset 172, Size 2 (U16)
    var electricCurrent: Int = 0,           // Offset 174, Size 2 (U16)
    var voltage: Int = 0,                   // Offset 176, Size 2 (U16)
    var controllerTemperature: Int = 0,     // Offset 178, Size 2 (U16) -> Needs -40 adjustment
    var motorTemperature: Int = 0,          // Offset 180, Size 2 (U16) -> Needs -40 adjustment / 255 check
    var boostState: Int = 0,                // Offset 182, Size 2 (U16)
    var speedLimit: Int = 0,                // Offset 184, Size 2 (U16)
    var wheelDiameter: Int = 0,             // Offset 186, Size 2 (U16)
    var tireCircumference: Int = 0,         // Offset 188, Size 2 (U16)
    var calories: Int = 0,                  // Offset 190, Size 2 (U16)
    var currentGear: Int = 0,               // Offset 192, Size 2 (U16)
    var totalGear: Int = 0,                 // Offset 194, Size 2 (U16)
    var wheelSpeed: Int = 0,                // Offset 196, Size 2 (U16)
    var wheelCounter: Int = 0,              // Offset 198, Size 2 (U16)
    var lastTestSenserTime: Int = 0,        // Offset 200, Size 2 (U16)
    var crankCadencePulseCounter: Int = 0,  // Offset 202, Size 2 (U16)
    var motorVariableSpeedMasterGear: Int = 0,// Offset 204, Size 2 (U16)
    var motorSpeedCurrentGear: Int = 0,     // Offset 206, Size 2 (U16)
    var cruiseControl: Int = 0,             // Offset 208, Size 1 (U8)
    var bootDefaultGear: Int = 0,           // Offset 209, Size 1 (U8)
    var bootDefaultGearValue: Int = 0,      // Offset 210, Size 1 (U8)
    var motorStartingAngle: Int = 0,        // Offset 211, Size 2 (U16)
    var accelerationSettings: Int = 0,      // Offset 213, Size 1 (U8)
    var gearSpeedLimit: ByteArray = ByteArray(10), // Offset 214, Size 10
    var gearCurrentLimit: ByteArray = ByteArray(10), // Offset 224, Size 10
    var buzzerSwitch: Int = 0,              // Offset 234, Size 1 (U8)
    // Skip 1 byte (Offset 235)
    var controllerProtocolVersion: Int = 0, // Offset 236, Size 1 (U8)
    // Total Size = 237 bytes

    var rawData: ByteArray? = null
    // ... (keep existing equals, hashCode, toString)
) {

    // Nested class to hold info about each field
    data class FieldInfo(
        val name: String,
        val offset: Int,
        val size: Int,
        // Lambda to parse the value from a ByteBuffer containing ONLY the field's data
        val parser: (ByteBuffer) -> Any,
        // Lambda to update the field on a ControllerInfo instance
        val updater: (ControllerInfo, Any) -> Unit
    )

    /**
     * Attempts to update a field based on partial payload data.
     * NOTE: This modifies the current instance. The caller (ViewModel/Repository)
     * is responsible for emitting this updated instance to trigger UI updates.
     *
     * @param partialPayload The byte array containing the data for *only* the field(s) starting at startOffset.
     * @param startOffset The starting offset of this partial data within the *full* ControllerInfo payload structure.
     * @return True if a matching field was found and updated, false otherwise.
     */
    fun updatePartial(partialPayload: ByteArray, startOffset: Int): Boolean {
        val fieldInfo = fieldOffsetMap[startOffset]
            ?: run {
                Log.w(TAG, "updatePartial: No field definition found for offset $startOffset")
                return false
            }

        // Basic validation: Does the received payload size match the expected field size?
        if (partialPayload.size != fieldInfo.size) {
            Log.w(TAG, "updatePartial: Size mismatch for field '${fieldInfo.name}' at offset $startOffset. Expected ${fieldInfo.size}, got ${partialPayload.size}. payload: $partialPayload\"")
            // Decide: Attempt parsing anyway? Or return false? Let's be strict for now.
            return false
        }

        return try {
            val buffer = ByteBuffer.wrap(partialPayload).order(ByteOrder.LITTLE_ENDIAN)
            val newValue = fieldInfo.parser(buffer)
            fieldInfo.updater(this, newValue) // Update the current instance
            Log.d(TAG, "updatePartial: Updated field '${fieldInfo.name}' (offset $startOffset) with value '$newValue'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "updatePartial: Error parsing/updating field '${fieldInfo.name}' at offset $startOffset: ${e.message}", e)
            false
        }
    }

    companion object {

        private const val TAG = "MeterInfo" // Combined Parser/DataClass TAG
        // --- Offset Map ---
        // Key: Starting byte offset in the payload
        // Value: FieldInfo object describing the field
        val fieldOffsetMap: Map<Int, FieldInfo> = mapOf(
            0 to FieldInfo("hardVersion", 0, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.hardVersion = v as String }),
            24 to FieldInfo("softVersion", 24, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.softVersion = v as String }),
            48 to FieldInfo("model", 48, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.model = v as String }),
            72 to FieldInfo("sn", 72, 40, { bb -> ByteBufferUtils.getAsciiString(bb, 40) }, { info, v -> info.sn = v as String }),
            112 to FieldInfo("customerNo", 112, 16, { bb -> ByteBufferUtils.getAsciiString(bb, 16) }, { info, v -> info.customerNo = v as String }),
            128 to FieldInfo("manufacturer", 128, 16, { bb -> ByteBufferUtils.getAsciiString(bb, 16) }, { info, v -> info.manufacturer = v as String }),
            // Offset 144 - 159: Skipped (16 bytes)
            160 to FieldInfo("soc", 160, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.soc = v as Int }),
            162 to FieldInfo("singleMileage", 162, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.singleMileage = v as Int }),
            164 to FieldInfo("totalMileage", 164, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.totalMileage = v as Int }),
            166 to FieldInfo("emainingMileage", 166, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.emainingMileage = v as Int }),
            168 to FieldInfo("cadence", 168, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.cadence = v as Int }),
            170 to FieldInfo("moment", 170, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.moment = v as Int }),
            172 to FieldInfo("speed", 172, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.speed = v as Int }),
            174 to FieldInfo("electricCurrent", 174, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.electricCurrent = v as Int }),
            176 to FieldInfo("voltage", 176, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.voltage = v as Int }),
            178 to FieldInfo("controllerTemperature", 178, 2,
                { bb -> ByteBufferUtils.getU16(bb) - 40 }, // Apply adjustment during parse
                { info, v -> info.controllerTemperature = v as Int }
            ),
            180 to FieldInfo("motorTemperature", 180, 2,
                { bb -> // Apply adjustment/check during parse
                    val raw = ByteBufferUtils.getU16(bb)
                    if (raw == 65535 || raw == 255) 255 else raw - 40
                },
                { info, v -> info.motorTemperature = v as Int }
            ),
            182 to FieldInfo("boostState", 182, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.boostState = v as Int }),
            184 to FieldInfo("speedLimit", 184, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.speedLimit = v as Int }),
            186 to FieldInfo("wheelDiameter", 186, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.wheelDiameter = v as Int }),
            188 to FieldInfo("tireCircumference", 188, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.tireCircumference = v as Int }),
            190 to FieldInfo("calories", 190, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.calories = v as Int }),
            192 to FieldInfo("currentGear", 192, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.currentGear = v as Int }),
            194 to FieldInfo("totalGear", 194, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.totalGear = v as Int }),
            196 to FieldInfo("wheelSpeed", 196, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.wheelSpeed = v as Int }),
            198 to FieldInfo("wheelCounter", 198, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.wheelCounter = v as Int }),
            200 to FieldInfo("lastTestSenserTime", 200, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.lastTestSenserTime = v as Int }),
            202 to FieldInfo("crankCadencePulseCounter", 202, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.crankCadencePulseCounter = v as Int }),
            204 to FieldInfo("motorVariableSpeedMasterGear", 204, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.motorVariableSpeedMasterGear = v as Int }),
            206 to FieldInfo("motorSpeedCurrentGear", 206, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.motorSpeedCurrentGear = v as Int }),
            208 to FieldInfo("cruiseControl", 208, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.cruiseControl = v as Int }),
            209 to FieldInfo("bootDefaultGear", 209, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.bootDefaultGear = v as Int }),
            210 to FieldInfo("bootDefaultGearValue", 210, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.bootDefaultGearValue = v as Int }),
            211 to FieldInfo("motorStartingAngle", 211, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.motorStartingAngle = v as Int }),
            213 to FieldInfo("accelerationSettings", 213, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.accelerationSettings = v as Int }),
            214 to FieldInfo("gearSpeedLimit", 214, 10,
                { bb -> ByteArray(10).also { bb.get(it) } }, // Read 10 bytes into a new array
                { info, v -> info.gearSpeedLimit = v as ByteArray }
            ),
            224 to FieldInfo("gearCurrentLimit", 224, 10,
                { bb -> ByteArray(10).also { bb.get(it) } }, // Read 10 bytes into a new array
                { info, v -> info.gearCurrentLimit = v as ByteArray }
            ),
            234 to FieldInfo("buzzerSwitch", 234, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.buzzerSwitch = v as Int }),
            // Offset 235: Skipped (1 byte)
            236 to FieldInfo("controllerProtocolVersion", 236, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.controllerProtocolVersion = v as Int })
        )


        // Moved from BleRepository
        fun parseControllerInfoPayload(payload: ByteArray): ControllerInfo? {
            // Use BfMeterConfig constant directly
            if (payload.size < BfMeterConfig.BfControllerInfo_Total_Size) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= ${BfMeterConfig.BfControllerInfo_Total_Size}")
                return null
            }
            val info = ControllerInfo()
            info.rawData = payload // Store raw data

            try {
                // Use the imported helpers directly
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                info.hardVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.softVersion = ByteBufferUtils.getAsciiString(buffer, 24)
                info.model = ByteBufferUtils.getAsciiString(buffer, 24)
                info.sn = ByteBufferUtils.getAsciiString(buffer, 40)
                info.customerNo = ByteBufferUtils.getAsciiString(buffer, 16)
                info.manufacturer = ByteBufferUtils.getAsciiString(buffer, 16)

                // Skip 16 bytes (Original logic: buffer.position(buffer.position() + 16))
                if (buffer.remaining() >= 16) {
                    buffer.position(buffer.position() + 16)
                } else {
                    Log.w(TAG, "Not enough data to skip 16 bytes after manufacturer string.")
                    // Decide how to handle: return null or try parsing remaining fields?
                    // Let's try parsing remaining fields, they might still be useful.
                }

                info.soc = ByteBufferUtils.getU16(buffer)
                info.singleMileage = ByteBufferUtils.getU16(buffer)
                info.totalMileage = ByteBufferUtils.getU16(buffer)
                info.emainingMileage = ByteBufferUtils.getU16(buffer)
                info.cadence = ByteBufferUtils.getU16(buffer)
                info.moment = ByteBufferUtils.getU16(buffer)
                info.speed = ByteBufferUtils.getU16(buffer)
                info.electricCurrent = ByteBufferUtils.getU16(buffer)
                info.voltage = ByteBufferUtils.getU16(buffer)

                // Temperature calculation remains the same
                info.controllerTemperature = ByteBufferUtils.getU16(buffer) - 40
                val motorTempRaw = ByteBufferUtils.getU16(buffer)
                info.motorTemperature = if (motorTempRaw == 65535 || motorTempRaw == 255) 255 else motorTempRaw - 40

                info.boostState = ByteBufferUtils.getU16(buffer)
                info.speedLimit = ByteBufferUtils.getU16(buffer)
                info.wheelDiameter = ByteBufferUtils.getU16(buffer)
                info.tireCircumference = ByteBufferUtils.getU16(buffer)
                info.calories = ByteBufferUtils.getU16(buffer)
                info.currentGear = ByteBufferUtils.getU16(buffer)
                info.totalGear = ByteBufferUtils.getU16(buffer)
                info.wheelSpeed = ByteBufferUtils.getU16(buffer)
                info.wheelCounter = ByteBufferUtils.getU16(buffer)
                info.lastTestSenserTime = ByteBufferUtils.getU16(buffer)
                info.crankCadencePulseCounter = ByteBufferUtils.getU16(buffer)
                info.motorVariableSpeedMasterGear = ByteBufferUtils.getU16(buffer)
                info.motorSpeedCurrentGear = ByteBufferUtils.getU16(buffer)

                info.cruiseControl = ByteBufferUtils.getU8(buffer)
                info.bootDefaultGear = ByteBufferUtils.getU8(buffer)
                info.bootDefaultGearValue = ByteBufferUtils.getU8(buffer)
                info.motorStartingAngle = ByteBufferUtils.getU16(buffer) // Was U16 in original parse logic
                info.accelerationSettings = ByteBufferUtils.getU8(buffer) // Was U8 in original parse logic

                // Read byte arrays for gear limits
                if (buffer.remaining() >= info.gearSpeedLimit.size) {
                    buffer.get(info.gearSpeedLimit)
                } else { Log.w(TAG, "Not enough data for gearSpeedLimit array.") }

                if (buffer.remaining() >= info.gearCurrentLimit.size) {
                    buffer.get(info.gearCurrentLimit)
                } else { Log.w(TAG, "Not enough data for gearCurrentLimit array.") }


                info.buzzerSwitch = ByteBufferUtils.getU8(buffer)

                // Skip 1 byte (Original logic: buffer.position(buffer.position() + 1))
                if (buffer.remaining() >= 1) {
                    buffer.position(buffer.position() + 1)
                } else { Log.w(TAG, "Not enough data to skip 1 byte before protocol version.") }


                info.controllerProtocolVersion = ByteBufferUtils.getU8(buffer)

                Log.d(TAG, "Successfully parsed ControllerInfo. Remaining buffer: ${buffer.remaining()}")
                return info

            } catch (e: BufferUnderflowException) { // Catch specific exceptions if possible
                Log.e(TAG, "Error parsing ControllerInfo payload (BufferUnderflow): ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ControllerInfo payload: ${e.message}", e)
                return null
            }
        }
    }
}
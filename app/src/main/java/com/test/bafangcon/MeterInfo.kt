package com.test.bafangcon

import android.util.Log
import com.test.bafangcon.utils.ByteBufferUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException


data class MeterInfo(
    // --- Fields with their offsets and sizes ---
    var hardVersion: String = "",           // Offset 0, Size 24
    var softVersion: String = "",           // Offset 24, Size 24
    var model: String = "",                 // Offset 48, Size 24
    var sn: String = "",                    // Offset 72, Size 40
    var customerNo: String = "",            // Offset 112, Size 16
    var manufacturer: String = "",          // Offset 128, Size 16
    // Skip 16 bytes (Offset 144 to 159)
    var totalGear: Int = 0,                 // Offset 160, Size 2 (U16)
    var sportModel: Int = 0,                // Offset 162, Size 1 (U8)
    var boostState: Int = 0,                // Offset 163, Size 1 (U8)
    var currentGear: Int = 0,               // Offset 164, Size 2 (U16)
    var light: Int = 0,                     // Offset 166, NOW TREATED AS U8 (Size 1 byte)
    var totalMileage: Int = 0,              // Offset 168, Size 2 (U16)
    var singleMileage: Int = 0,             // Offset 170, Size 2 (U16)
    var maxSpeed: Int = 0,                  // Offset 172, Size 2 (U16)
    var averageSpeed: Int = 0,              // Offset 174, Size 2 (U16)
    var maintenanceMileage: Long = 0,       // Offset 176, Size 4 (U32)
    var autoShutDown: Int = 0,              // Offset 180, Size 2 (U16)
    var maxAutoShutDown: Int = 0,           // Offset 182, Size 2 (U16)
    var unitSwitch: Int = 0,                // Offset 184, Size 2 (U16)
    var totalRideTime: Long = 0,            // Offset 186, Size 4 (U32)
    var totalCalories: Long = 0,            // Offset 190, Size 4 (U32)
    var singleMileage2: Long = 0,           // Offset 194, Size 4 (U32)
    // Total Size = 198 bytes

    var rawData: ByteArray? = null
    // ... (keep existing equals, hashCode, toString)
) {

    // Nested class to hold info about each field (same as in ControllerInfo)
    // If this becomes common, consider moving it to a shared utils file.
    data class FieldInfo(
        val name: String,
        val offset: Int,
        val size: Int,
        val parser: (ByteBuffer) -> Any, // Parses field's bytes -> Kotlin type
        val updater: (MeterInfo, Any) -> Unit // Updates field on MeterInfo instance
    )

    /**
     * Attempts to update a field based on partial payload data.
     * NOTE: This modifies the current instance.
     *
     * @param partialPayload The byte array containing data for the field(s).
     * @param startOffset The starting offset within the full MeterInfo payload.
     * @return True if a matching field was found and updated, false otherwise.
     */
    @OptIn(ExperimentalStdlibApi::class)
    fun updatePartial(partialPayload: ByteArray, startOffset: Int): Boolean {
        val fieldInfo = fieldOffsetMap[startOffset]
            ?: run {
                Log.w(TAG, "updatePartial: No field definition found for offset $startOffset")
                return false
            }

        if (partialPayload.size != fieldInfo.size) {
            val partialPayloadHeX = partialPayload?.toHexString() ?: "N/A"
            Log.w(TAG, "updatePartial: Size mismatch for field '${fieldInfo.name}' at offset $startOffset. Expected ${fieldInfo.size}, got ${partialPayload.size}. payload: $partialPayloadHeX")
            return false // Be strict about size matching
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

        // --- Offset Map for MeterInfo ---
        val fieldOffsetMap: Map<Int, FieldInfo> = mapOf(
            0 to FieldInfo("hardVersion", 0, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.hardVersion = v as String }),
            24 to FieldInfo("softVersion", 24, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.softVersion = v as String }),
            48 to FieldInfo("model", 48, 24, { bb -> ByteBufferUtils.getAsciiString(bb, 24) }, { info, v -> info.model = v as String }),
            72 to FieldInfo("sn", 72, 40, { bb -> ByteBufferUtils.getAsciiString(bb, 40) }, { info, v -> info.sn = v as String }),
            112 to FieldInfo("customerNo", 112, 16, { bb -> ByteBufferUtils.getAsciiString(bb, 16) }, { info, v -> info.customerNo = v as String }),
            128 to FieldInfo("manufacturer", 128, 16, { bb -> ByteBufferUtils.getAsciiString(bb, 16) }, { info, v -> info.manufacturer = v as String }),
            // Offset 144 - 159: Skipped (16 bytes)
            160 to FieldInfo("totalGear", 160, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.totalGear = v as Int }),
            162 to FieldInfo("sportModel", 162, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.sportModel = v as Int }),
            163 to FieldInfo("boostState", 163, 1, { bb -> ByteBufferUtils.getU8(bb) }, { info, v -> info.boostState = v as Int }),
            164 to FieldInfo("currentGear", 164, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.currentGear = v as Int }),
            166 to FieldInfo("light", 166, 1, // <-- SIZE is now 1
                { bb -> ByteBufferUtils.getU8(bb) }, // <-- PARSER is now getU8
                { info, v -> info.light = v as Int }
            ),
            168 to FieldInfo("totalMileage", 168, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.totalMileage = v as Int }),
            170 to FieldInfo("singleMileage", 170, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.singleMileage = v as Int }),
            172 to FieldInfo("maxSpeed", 172, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.maxSpeed = v as Int }),
            174 to FieldInfo("averageSpeed", 174, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.averageSpeed = v as Int }),
            176 to FieldInfo("maintenanceMileage", 176, 4, { bb -> ByteBufferUtils.getU32(bb) }, { info, v -> info.maintenanceMileage = v as Long }),
            180 to FieldInfo("autoShutDown", 180, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.autoShutDown = v as Int }),
            182 to FieldInfo("maxAutoShutDown", 182, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.maxAutoShutDown = v as Int }),
            184 to FieldInfo("unitSwitch", 184, 2, { bb -> ByteBufferUtils.getU16(bb) }, { info, v -> info.unitSwitch = v as Int }),
            186 to FieldInfo("totalRideTime", 186, 4, { bb -> ByteBufferUtils.getU32(bb) }, { info, v -> info.totalRideTime = v as Long }),
            190 to FieldInfo("totalCalories", 190, 4, { bb -> ByteBufferUtils.getU32(bb) }, { info, v -> info.totalCalories = v as Long }),
            194 to FieldInfo("singleMileage2", 194, 4, { bb -> ByteBufferUtils.getU32(bb) }, { info, v -> info.singleMileage2 = v as Long })
        )



        // Moved from BleRepository
        fun parseMeterInfoPayload(payload: ByteArray): MeterInfo? {
            // Use BfMeterConfig constant directly
            if (payload.size < BfMeterConfig.BfMeterInfo_Total_Size) {
                Log.w(TAG, "Payload too short: ${payload.size}, expected >= ${BfMeterConfig.BfMeterInfo_Total_Size}")
                return null
            }
            val info = MeterInfo()
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
                    // Continue parsing remaining fields
                }


                info.totalGear = ByteBufferUtils.getU16(buffer)
                info.sportModel = ByteBufferUtils.getU8(buffer)
                info.boostState = ByteBufferUtils.getU8(buffer)
                info.currentGear = ByteBufferUtils.getU16(buffer)
                info.light = ByteBufferUtils.getU8(buffer) // Read light as U8 (1 byte)
                // Explicitly skip the next byte (offset 167) assumed to be padding in full payload
                if (buffer.remaining() >= 1) {
                    buffer.position(buffer.position() + 1) // Skip byte 167
                    Log.v(TAG, "Skipped byte at offset 167 after reading light.")
                } else {
                    Log.w(TAG, "No byte to skip after reading light at offset 166.")
                }
                info.totalMileage = ByteBufferUtils.getU16(buffer)
                info.singleMileage = ByteBufferUtils.getU16(buffer)
                info.maxSpeed = ByteBufferUtils.getU16(buffer)
                info.averageSpeed = ByteBufferUtils.getU16(buffer)

                // U32 fields
                info.maintenanceMileage = ByteBufferUtils.getU32(buffer)
                info.autoShutDown = ByteBufferUtils.getU16(buffer) // Was U16 in original
                info.maxAutoShutDown = ByteBufferUtils.getU16(buffer) // Was U16 in original
                info.unitSwitch = ByteBufferUtils.getU16(buffer) // Was U16 in original

                // U32 fields continued
                info.totalRideTime = ByteBufferUtils.getU32(buffer)
                info.totalCalories = ByteBufferUtils.getU32(buffer)
                info.singleMileage2 = ByteBufferUtils.getU32(buffer)

                Log.d(TAG, "Successfully parsed MeterInfo. Remaining buffer: ${buffer.remaining()}")
                return info

            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing MeterInfo payload (BufferUnderflow): ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing MeterInfo payload: ${e.message}", e)
                return null
            }
        }
        // Define constants for editable field ranges/values
        const val MIN_TOTAL_GEAR = 3
        const val MAX_TOTAL_GEAR = 5
        const val SPORT_MODEL_NORMAL = 1 // Assuming 1 = Normal/Off state for switch
        const val SPORT_MODEL_SPORT = 2  // Assuming 2 = Sport/On state for switch
        const val BOOST_STATE_OFF = 0
        const val BOOST_STATE_ON = 1
        const val MIN_CURRENT_GEAR = 0 // If editable
        const val MAX_CURRENT_GEAR = 5 // If editable
        const val AUTOSHUTDOWN_NEVER = 255
        const val AUTOSHUTDOWN_DEFAULT_ON = 10 // Default minutes if turning on from Never
        const val MIN_MAX_AUTOSHUTDOWN = 0
        const val MAX_MAX_AUTOSHUTDOWN = 30
        const val UNIT_KMH = 0
        const val UNIT_MPH = 1
    }
}
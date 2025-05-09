package com.test.bafangcon

import android.util.Log // Import Log
import com.test.bafangcon.utils.ByteBufferUtils // Import helpers
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.BufferUnderflowException // Keep exception import

// Based on BfPersonalizedInfo structure
data class PersonalizedInfo(
    // ... (keep existing fields)
    var controllerProtocolVersion: Int = 0,
    var motorStartingAngle: ShortArray = ShortArray(10),
    var accelerationSettings: ByteArray = ByteArray(10),
    var gearSpeedLimit: ByteArray = ByteArray(10),
    var gearCurrentLimit: ByteArray = ByteArray(10),
    var rawData: ByteArray? = null
    // ... (keep existing equals, hashCode, toString, and helper extension)
) {
    companion object {
        private const val TAG = "PersonalizedInfoParser" // Specific TAG

        // Moved from BleRepository
        fun parsePersonalizedInfoPayload(payload: ByteArray): PersonalizedInfo? {
            // Use BfMeterConfig constant directly
            // Note: Original code used '<' check. Ensure this is correct.
            // If exactly 115 bytes are required, use '!='. Let's stick with '<' for now.
            if (payload.size < BfMeterConfig.BfPersonalizedInfo_Total_Size) {
                Log.w(TAG, "Personalized payload too short: ${payload.size}, expected >= ${BfMeterConfig.BfPersonalizedInfo_Total_Size}")
                return null
            }
            val info = PersonalizedInfo()
            info.rawData = payload // Store raw data

            try {
                val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                // --- Mimic BfPersonalizedInfo.setBuffer logic ---
                // Skip the first 64 bytes - make sure this is correct!
                val bytesToSkip = 64
                if (buffer.remaining() >= bytesToSkip) {
                    buffer.position(buffer.position() + bytesToSkip)
                } else {
                    Log.w(TAG, "Not enough data to skip initial $bytesToSkip bytes.")
                    // If skipping fails, the rest of the parsing will likely fail or be incorrect.
                    // Returning null might be safer here.
                    return null
                }


                info.controllerProtocolVersion = ByteBufferUtils.getU8(buffer) // Read protocol version

                // Read Motor Starting Angle (20 bytes -> 10 shorts)
                val motorAngleBytes = ByteArray(20)
                if (buffer.remaining() >= motorAngleBytes.size) {
                    buffer.get(motorAngleBytes)
                    val angleBuffer = ByteBuffer.wrap(motorAngleBytes).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until 10) {
                        // Check remaining before reading each short inside the loop
                        if (angleBuffer.remaining() >= 2) {
                            info.motorStartingAngle[i] = angleBuffer.short // Read as Short (16-bit)
                        } else {
                            Log.w(TAG, "Buffer underflow while reading motor angle index $i")
                            // Handle error: break, set default, return null? Let's break.
                            break
                        }
                    }
                } else { Log.w(TAG, "Not enough data for motorStartingAngle array.") }


                // Read Acceleration Settings (10 bytes)
                if (buffer.remaining() >= info.accelerationSettings.size) {
                    buffer.get(info.accelerationSettings)
                } else { Log.w(TAG, "Not enough data for accelerationSettings array.") }

                // Read Gear Speed Limit (10 bytes)
                if (buffer.remaining() >= info.gearSpeedLimit.size) {
                    buffer.get(info.gearSpeedLimit)
                } else { Log.w(TAG, "Not enough data for gearSpeedLimit array.") }

                // Read Gear Current Limit (10 bytes)
                if (buffer.remaining() >= info.gearCurrentLimit.size) {
                    buffer.get(info.gearCurrentLimit)
                } else { Log.w(TAG, "Not enough data for gearCurrentLimit array.") }

                Log.d(TAG, "Successfully parsed PersonalizedInfo. Remaining buffer: ${buffer.remaining()}")
                return info

            } catch (e: BufferUnderflowException) {
                Log.e(TAG, "Error parsing PersonalizedInfo payload (BufferUnderflow): ${e.message}")
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing PersonalizedInfo payload: ${e.message}", e)
                return null
            }
        }
    }
    // Helper extension for simple hex string (can be kept here or moved to a general utils file)
    private fun ByteArray.toHexString(): String =
        joinToString(separator = " ") { String.format("%02X", it) }
}
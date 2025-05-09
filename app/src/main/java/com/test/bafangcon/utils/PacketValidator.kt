package com.test.bafangcon.utils // Or your chosen package

import android.util.Log

object PacketValidator {

    private const val TAG = "PacketValidator"

    // Command IDs corresponding to the *response* packet (at index 3)
    // These determine the expected response format.
    private const val CMD_ID_A1 = 0xA1
    private const val CMD_ID_A3 = 0xA3
    private const val CMD_ID_A4 = 0xA4
    private const val CMD_ID_A5 = 0xA5
    private const val CMD_ID_A7 = 0xA7
    private const val CMD_ID_A9 = 0xA9
    // Add other response command IDs if they follow Format A or B

    private const val START_BYTE_1: Byte = 0x55
    private const val START_BYTE_2: Byte = 0xAA.toByte() // -86 in decimal
    private const val END_BYTE_FE: Byte = 0xFE.toByte() // -2 in decimal

    // --- CRC-16-CCITT (Kermit) Parameters ---
    private const val CRC16_KERMIT_POLY = 0x1021 // Polynomial
    private const val CRC16_KERMIT_INIT = 0x0000 // Initial Value
    private const val CRC16_KERMIT_REFIN = true // Reflect Input Bytes
    private const val CRC16_KERMIT_REFOUT = true // Reflect Output CRC
    private const val CRC16_KERMIT_XOROUT = 0x0000 // Final XOR Value
    // Reflected polynomial for efficient calculation when RefIn/RefOut are true
    private const val CRC16_KERMIT_POLY_REFLECTED = 0x8408


    /**
     * Validates the checksum/CRC of a received packet based on its Command ID.
     * Assumes basic length and start bytes have been checked externally if desired.
     *
     * @param packet The complete received byte array.
     * @return true if the packet structure and checksum/CRC are valid for its type, false otherwise.
     */
    fun isValidReceivedPacket(packet: ByteArray): Boolean {
        // Minimum length check is crucial here as format depends on it
        if (packet.size < 9) { // Minimum length: Header(7) + Checksum(1)+FE(1) OR Header(7) + CRC(2)
            Log.w(TAG, "Packet too short for validation (Length: ${packet.size})")
            return false
        }

        // Check Start Bytes (optional redundancy if checked before calling)
        if (packet[0] != START_BYTE_1 || packet[1] != START_BYTE_2) {
            Log.w(TAG, "Invalid start bytes: 0x${packet[0].toHexString()} 0x${packet[1].toHexString()}")
            return false
        }

        // Byte 4 (index 3) is the Command ID of the *response*
        val commandId = packet[3].toInt() and 0xFF

        // --- Determine Checksum Format and Validate ---
        return when (commandId) {
            CMD_ID_A1, CMD_ID_A4, CMD_ID_A7 -> {
                // Format A: 1-byte checksum (0xFF - Sum) + FE terminator
                validateFormatA(packet)
            }
            CMD_ID_A3, CMD_ID_A5, CMD_ID_A9 -> {
                // Format B: 2-byte CRC-16 (Kermit, Little Endian)
                validateFormatB(packet)
            }
            else -> {
                Log.w(TAG, "Unknown Command ID for response format validation: 0x${commandId.toString(16)}")
                // Decide how to handle unknown: assume invalid or skip check? Let's assume invalid.
                false
            }
        }
    }

    /**
     * Validates Format A packets (1-byte sum checksum + FE).
     */
    private fun validateFormatA(packet: ByteArray): Boolean {
        // Check minimum length again (redundant but safe)
        if (packet.size < 9) {
            Log.w(TAG, "Format A packet too short (Length: ${packet.size}).")
            return false
        }

        // Check FE terminator
        if (packet[packet.size - 1] != END_BYTE_FE) {
            Log.w(TAG, "Format A missing FE terminator. Last byte: 0x${packet[packet.size - 1].toHexString()}")
            return false
        }

        // Data for checksum runs from Byte 3 (index 2) to Byte before checksum (index length-3)
        val dataEndIndex = packet.size - 3
        if (dataEndIndex < 2) {
            Log.e(TAG, "Format A invalid data range for checksum (EndIndex: $dataEndIndex).")
            return false // Should not happen if length check passed
        }

        // Calculate expected checksum
        val calculatedChecksum = calculateSumChecksum(packet, 2, dataEndIndex)

        // Get received checksum
        val receivedChecksum = packet[packet.size - 2].toInt() and 0xFF

        // Compare
        val isValid = calculatedChecksum == receivedChecksum
        if (!isValid) {
            Log.w(TAG, "Format A Checksum mismatch. Range=[2..$dataEndIndex]. Expected: 0x${calculatedChecksum.toString(16)}" +
                    ", Received: 0x${receivedChecksum.toString(16)}")
        } else {
            Log.d(TAG, "Format A Checksum OK: 0x${calculatedChecksum.toString(16)}")
        }
        return isValid
    }

    /**
     * Validates Format B packets (2-byte CRC-16 Kermit, Little Endian).
     */
    private fun validateFormatB(packet: ByteArray): Boolean {
        // Check minimum length again (redundant but safe)
        if (packet.size < 9) {
            Log.w(TAG, "Format B packet too short (Length: ${packet.size}).")
            return false
        }

        // Data for CRC runs from Byte 3 (index 2) to Byte before CRC (index length-3)
        val dataEndIndex = packet.size - 3
        if (dataEndIndex < 2) {
            Log.e(TAG, "Format B invalid data range for CRC (EndIndex: $dataEndIndex).")
            return false // Should not happen if length check passed
        }

        // Calculate expected CRC
        val calculatedCRC = calculateCRC16Kermit(packet, 2, dataEndIndex)

        // Get received CRC (Little Endian: LSB first, MSB second)
        val receivedLSB = packet[packet.size - 2].toInt() and 0xFF
        val receivedMSB = packet[packet.size - 1].toInt() and 0xFF
        val receivedCRC = (receivedMSB shl 8) or receivedLSB

        // Compare
        val isValid = calculatedCRC == receivedCRC
        if (!isValid) {
            Log.w(TAG, "Format B CRC mismatch. Range=[2..$dataEndIndex]. Expected: 0x${calculatedCRC.toString(16).padStart(4, '0')}" +
                    ", Received: 0x${receivedCRC.toString(16).padStart(4, '0')}")
        } else {
            Log.d(TAG, "Format B CRC OK: 0x${calculatedCRC.toString(16).padStart(4, '0')}")
        }
        return isValid
    }


    /**
     * Calculates the 1-byte checksum: (0xFF - Sum(Bytes)) & 0xFF.
     *
     * @param data      The byte array containing the data.
     * @param startIndex The starting index (inclusive) for the sum.
     * @param endIndex   The ending index (inclusive) for the sum.
     * @return The calculated 8-bit checksum.
     */
    private fun calculateSumChecksum(data: ByteArray, startIndex: Int, endIndex: Int): Int {
        var sum = 0
        for (i in startIndex..endIndex) {
            sum += (data[i].toInt() and 0xFF) // Treat bytes as unsigned
        }
        // Original logic: (0xFF - (sum & 0xFF)) & 0xFF
        // Equivalent to: (-sum - 1) & 0xFF in two's complement
        return (0xFF - (sum and 0xFF)) and 0xFF
    }

    /**
     * Calculates CRC-16-CCITT (Kermit variant).
     * Poly=0x1021, Init=0x0000, RefIn=True, RefOut=True, XorOut=0x0000
     *
     * @param data       The byte array containing the data.
     * @param startIndex The starting index (inclusive) for the CRC calculation.
     * @param endIndex   The ending index (inclusive) for the CRC calculation.
     * @return The calculated 16-bit CRC value.
     */
    fun calculateCRC16Kermit(data: ByteArray, startIndex: Int, endIndex: Int): Int {
        var crc = CRC16_KERMIT_INIT
        for (i in startIndex..endIndex) {
            // Reflect input byte if RefIn is true
            val currentByte = if (CRC16_KERMIT_REFIN) reflectByte(data[i]) else data[i]
            // XOR byte into LSB of CRC (using unsigned byte value)
            crc = crc xor (currentByte.toInt() and 0xFF)
            // Process 8 bits
            for (j in 0 until 8) {
                // If LSB is 1, shift right and XOR with reflected polynomial
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor CRC16_KERMIT_POLY_REFLECTED
                } else {
                    // Otherwise, just shift right
                    crc ushr 1
                }
            }
        }
        // Reflect output CRC if RefOut is true
        if (CRC16_KERMIT_REFOUT) {
            crc = reflectShort(crc.toShort()).toInt() and 0xFFFF // Reflect and ensure it stays within 16 bits
        }
        // Final XOR (is 0x0000 for Kermit, so no change)
        return (crc xor CRC16_KERMIT_XOROUT) and 0xFFFF
    }

    /**
     * Reflects the bits of an 8-bit byte.
     */
    private fun reflectByte(b: Byte): Byte {
        var reflection = 0x00
        val intValue = b.toInt() // Work with Int for easier bit manipulation
        for (bit in 0 until 8) {
            if (((intValue ushr bit) and 1) != 0) {
                reflection = reflection or (1 shl (7 - bit))
            }
        }
        return reflection.toByte()
    }

    /**
     * Reflects the bits of a 16-bit short.
     */
    private fun reflectShort(s: Short): Short {
        var reflection = 0x0000
        val intValue = s.toInt() // Work with Int
        for (bit in 0 until 16) {
            if (((intValue ushr bit) and 1) != 0) {
                reflection = reflection or (1 shl (15 - bit))
            }
        }
        return reflection.toShort()
    }

    // Helper extension for logging byte arrays (optional, can be in BleRepository too)
    private fun Byte.toHexString(): String = String.format("%02X", this)
    fun ByteArray.toHexString(): String =
        joinToString(separator = " ", prefix = "0x") { String.format("%02X", it) }

}
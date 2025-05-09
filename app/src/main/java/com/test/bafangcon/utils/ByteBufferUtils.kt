package com.test.bafangcon.utils

import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.BufferUnderflowException

object ByteBufferUtils {
    private const val TAG = "ByteBufferUtils" // Tag for logging within helpers

    // --- ByteBuffer Helper Functions (moved from BleRepository) ---

    /**
     * Reads an ASCII string from the buffer, stopping at the first null terminator
     * or the specified length. Cleans non-ASCII characters.
     */
    fun getAsciiString(buffer: ByteBuffer, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        try {
            // Check if enough bytes are remaining BEFORE trying to read
            if (buffer.remaining() < length) {
                Log.w(TAG, "getAsciiString: Not enough bytes remaining. Need $length, have ${buffer.remaining()} at pos ${buffer.position()}")
                // Read only what's available, potentially returning partial string
                val availableLength = buffer.remaining()
                if (availableLength <= 0) return ""
                buffer.get(bytes, 0, availableLength)
                // Process the partially read bytes
                var firstNullPartial = bytes.sliceArray(0 until availableLength).indexOf(0.toByte())
                if (firstNullPartial == -1) firstNullPartial = availableLength
                return String(bytes, 0, firstNullPartial, StandardCharsets.US_ASCII)
                    .trim()
                    .replace("[^\\x00-\\x7F]".toRegex(), "") // Remove non-ASCII
            }

            // Proceed with normal read if enough bytes available
            buffer.get(bytes)
            var firstNull = bytes.indexOf(0.toByte())
            if (firstNull == -1) firstNull = length
            return String(bytes, 0, firstNull, StandardCharsets.US_ASCII)
                .trim()
                .replace("[^\\x00-\\x7F]".toRegex(), "") // Remove non-ASCII
            // .replace("[{}]".toRegex(), "") // Example: Remove specific unwanted chars if needed
        } catch (e: BufferUnderflowException) {
            // This catch might be redundant now due to the remaining() check, but kept for safety
            Log.e(TAG, "Error getting ASCII string (length $length): Buffer underflow at pos ${buffer.position()}")
            return "" // Return empty on error
        } catch (e: Exception) {
            Log.e(TAG, "Error getting ASCII string (length $length): ${e.message}")
            return "" // Return empty on other errors
        }
    }

    /** Reads an unsigned 8-bit integer. */
    fun getU8(buffer: ByteBuffer): Int {
        try {
            if (buffer.remaining() < 1) {
                Log.w(TAG, "getU8: Buffer underflow at pos ${buffer.position()}")
                return 0
            }
            return buffer.get().toInt() and 0xFF
        } catch (e: Exception) { // Catch generic exception as well
            Log.e(TAG, "getU8 error", e)
            return 0
        }
    }

    /** Reads an unsigned 16-bit integer (Little Endian). */
    fun getU16(buffer: ByteBuffer): Int {
        try {
            if (buffer.remaining() < 2) {
                Log.w(TAG, "getU16: Buffer underflow at pos ${buffer.position()}")
                return 0
            }
            // Ensure buffer order is correct before reading (caller should set it)
            return buffer.short.toInt() and 0xFFFF
        } catch (e: Exception) {
            Log.e(TAG, "getU16 error", e)
            return 0
        }
    }

    /** Reads an unsigned 32-bit integer (Little Endian). */
    fun getU32(buffer: ByteBuffer): Long {
        try {
            if (buffer.remaining() < 4) {
                Log.w(TAG, "getU32: Buffer underflow at pos ${buffer.position()}")
                return 0L
            }
            // Ensure buffer order is correct before reading (caller should set it)
            return buffer.int.toLong() and 0xFFFFFFFFL
        } catch (e: Exception) {
            Log.e(TAG, "getU32 error", e)
            return 0L
        }
    }
}
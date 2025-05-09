object BfMeterConfig {
    // Expected *Payload* sizes (Data after header index 5, before CS/FE)
    // Note: These might need adjustment if header size changes. Let's recalculate based on dump.
    // Controller: Total packet ~248 bytes? Header=6, CS=2, FE=1 -> 248-9=239? Let's stick to the original file's value for now.
    // Meter: Total packet ~209 bytes? Header=6, CS=2, FE=1 -> 209-9=200? Let's stick to the original file's value for now.

    const val BfControllerInfo_Total_Size = 237
    const val BfMeterInfo_Total_Size = 198
    const val BfPersonalizedInfo_Total_Size = 115
    const val BfBattery_Total_Size = 244 // Add if missing
    const val BfSensorInfo_Total_Size = 164 // Add if missing
    const val BfIotConfigInfo_Total_Size = 237 // Add if missing (Note: same as Controller?)
    const val BfIotCanInfo_Total_Size = 97  // Add if missing
        // Add other sizes if needed


    // Frame markers
    const val FRAME_START_BYTE_1: Byte = 0x55
    const val FRAME_START_BYTE_2: Byte = 0xAA.toByte()
    const val FRAME_END_BYTE: Byte = -0x02 // 0xFE
}
package com.test.bafangcon

// Data holder for the InfoAdapter
data class InfoItem(
    val key: String,
    var value: String, // Current value (can be updated by EditText/Switch)
    val editableType: EditableType = EditableType.DISPLAY_ONLY,
    val originalValue: Any? = null // Store original numeric/boolean value if needed
)

enum class EditableType {
    DISPLAY_ONLY,
    EDIT_TEXT_NUMBER,
    SWITCH // Add more types if needed (e.g., SPINNER)
}

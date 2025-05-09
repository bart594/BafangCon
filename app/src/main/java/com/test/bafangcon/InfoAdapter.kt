package com.test.bafangcon

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.test.bafangcon.databinding.ListItemInfoDisplayBinding
import com.test.bafangcon.databinding.ListItemInfoEdittextBinding
import com.test.bafangcon.databinding.ListItemInfoSwitchBinding

// Import Switch binding if you add that view type
// import com.yourapp.databinding.ListItemInfoSwitchBinding

class InfoAdapter(
    private val onValueChanged: (position: Int, newValue: String) -> Unit
) : ListAdapter<InfoItem, InfoAdapter.BaseViewHolder>(InfoDiffCallback) {


    companion object {
        private const val VIEW_TYPE_DISPLAY = 0
        private const val VIEW_TYPE_EDIT_TEXT = 1
        private const val VIEW_TYPE_SWITCH = 2
        private const val TAG = "InfoAdapter" // Tag for logging
    }

    // Base ViewHolder
    abstract class BaseViewHolder(binding: ViewBinding) : RecyclerView.ViewHolder(binding.root) {
        abstract fun bind(item: InfoItem)
    }

    // ViewHolder for Display Only items
    inner class DisplayViewHolder(private val binding: ListItemInfoDisplayBinding) : BaseViewHolder(binding) {
        override fun bind(item: InfoItem) {
            Log.d(TAG, "Binding Display: ${item.key} = ${item.value}") // Add log
            binding.itemKey.text = item.key
            binding.itemValue.text = item.value
        }
    }

    // ViewHolder for EditText items
    inner class EditTextViewHolder(private val binding: ListItemInfoEdittextBinding) : BaseViewHolder(binding) {
        private var textWatcher: TextWatcher? = null

        override fun bind(item: InfoItem) {
            binding.itemKey.text = item.key

            // Remove previous watcher to avoid unwanted callbacks during binding
            textWatcher?.let { binding.itemValueEdit.removeTextChangedListener(it) }

            binding.itemValueEdit.setText(item.value)
            // Set input type based on your needs (e.g., number)
            binding.itemValueEdit.inputType = InputType.TYPE_CLASS_NUMBER // Or TYPE_CLASS_TEXT etc.

            // Add new watcher
            textWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    // Check position validity before calling back
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        onValueChanged(adapterPosition, s.toString()) // Send String
                    } else {
                        Log.w(TAG, "afterTextChanged called with NO_POSITION for key: ${item.key}")
                    }
                }
            }
            binding.itemValueEdit.addTextChangedListener(textWatcher)

            // Clear focus when done editing to prevent issues
            binding.itemValueEdit.setOnEditorActionListener { v, actionId, event ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    v.clearFocus()
                    // Hide keyboard (optional helper needed)
                    // KeyboardUtils.hideKeyboard(v)
                }
                false // Return false to allow default handling
            }
        }
    }

    // ViewHolder for Switch items
    inner class SwitchViewHolder(private val binding: ListItemInfoSwitchBinding) : BaseViewHolder(binding) {
        override fun bind(item: InfoItem) {
            Log.d(TAG, "Binding Switch: ${item.key} = ${item.value}") // Add log
            binding.itemKey.text = item.key
            // Remove listener before setting state
            binding.itemValueSwitch.setOnCheckedChangeListener(null)
            val isInitiallyChecked = item.value.equals("1") || item.value.equals("true", ignoreCase = true)
            binding.itemValueSwitch.isChecked = isInitiallyChecked

            // Add listener back
            binding.itemValueSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    val newValueString = if (isChecked) "1" else "0"
                    onValueChanged(adapterPosition, newValueString) // Send String "1" or "0"
                } else {
                    Log.w(TAG, "onCheckedChangeListener called with NO_POSITION for key: ${item.key}")
                }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).editableType) {
            EditableType.EDIT_TEXT_NUMBER -> VIEW_TYPE_EDIT_TEXT
            EditableType.SWITCH -> VIEW_TYPE_SWITCH
            else -> VIEW_TYPE_DISPLAY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_EDIT_TEXT -> {
                val binding = ListItemInfoEdittextBinding.inflate(inflater, parent, false)
                EditTextViewHolder(binding)
            }
            VIEW_TYPE_SWITCH -> { // Inflate switch layout
                val binding = ListItemInfoSwitchBinding.inflate(inflater, parent, false)
                SwitchViewHolder(binding)
            }
            else -> { // VIEW_TYPE_DISPLAY
                val binding = ListItemInfoDisplayBinding.inflate(inflater, parent, false)
                DisplayViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        val item = getItem(position)
        Log.d(TAG, "onBindViewHolder: position=$position, key='${item.key}', type=${item.editableType}") // Log binding call
        holder.bind(item) // Delegate binding to the specific ViewHolder implementation
    }

    // DiffUtil Callback
    object InfoDiffCallback : DiffUtil.ItemCallback<InfoItem>() {
        override fun areItemsTheSame(oldItem: InfoItem, newItem: InfoItem): Boolean {
            // Assume key is unique identifier
            return oldItem.key == newItem.key
        }

        override fun areContentsTheSame(oldItem: InfoItem, newItem: InfoItem): Boolean {
            // Compare all relevant fields
            return oldItem == newItem
        }
    }
}
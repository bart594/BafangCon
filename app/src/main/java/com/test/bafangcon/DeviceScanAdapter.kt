package com.test.bafangcon

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.test.bafangcon.databinding.ListItemDeviceBinding // Use ViewBinding

class DeviceScanAdapter(
    private val onItemClicked: (DiscoveredBluetoothDevice) -> Unit
) : ListAdapter<DiscoveredBluetoothDevice, DeviceScanAdapter.DeviceViewHolder>(DeviceDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ListItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding, onItemClicked)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DeviceViewHolder(
        private val binding: ListItemDeviceBinding,
        private val onItemClicked: (DiscoveredBluetoothDevice) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("MissingPermission") // Permission check done before scanning/connecting
        fun bind(device: DiscoveredBluetoothDevice) {
            binding.deviceName.text = device.name ?: "Unknown Device"
            binding.deviceAddress.text = device.address
            binding.deviceRssi.text = "RSSI: ${device.rssi} dBm"
            binding.root.setOnClickListener {
                onItemClicked(device)
            }
        }
    }

    object DeviceDiffCallback : DiffUtil.ItemCallback<DiscoveredBluetoothDevice>() {
        override fun areItemsTheSame(oldItem: DiscoveredBluetoothDevice, newItem: DiscoveredBluetoothDevice): Boolean {
            return oldItem.address == newItem.address
        }

        override fun areContentsTheSame(oldItem: DiscoveredBluetoothDevice, newItem: DiscoveredBluetoothDevice): Boolean {
            return oldItem == newItem // Relies on data class equals
        }
    }
}
package com.xgwnje.visionguard.receiver.ui.viewmodel

// ┌─────────────────────────────────────────────────────────┐
// │ DeviceViewModel.kt                                      │
// │ 角色：设备列表 ViewModel，桥接 Service ↔ DeviceListScreen│
// │ 对外：devices StateFlow, sendCommand(), commandAck Flow  │
// └─────────────────────────────────────────────────────────┘

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xgwnje.visionguard.receiver.data.model.DeviceInfo
import com.xgwnje.visionguard.receiver.service.AlertForegroundService
import com.xgwnje.visionguard.receiver.data.model.CommandResult
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class DeviceViewModel(private val service: AlertForegroundService) : ViewModel() {

    val devices: StateFlow<List<DeviceInfo>> = service.devices

    val commandAck: SharedFlow<CommandResult> = service.commandAck

    fun sendCommand(targetDeviceId: String, command: String, targetSourceId: String? = null) {
        service.sendCommand(targetDeviceId, command, targetSourceId)
    }

    fun sendSetConfig(targetDeviceId: String, key: String, value: String, targetSourceId: String? = null) {
        service.sendSetConfig(targetDeviceId, key, value, targetSourceId)
    }

    fun moveDevice(fromIndex: Int, toIndex: Int) {
        service.moveDevice(fromIndex, toIndex)
    }

    suspend fun unbindDevice(deviceId: String): Boolean = service.unbindDevice(deviceId)

    class Factory(private val service: AlertForegroundService) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DeviceViewModel(service) as T
    }
}

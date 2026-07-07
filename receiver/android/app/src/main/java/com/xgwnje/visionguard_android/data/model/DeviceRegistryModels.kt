package com.xgwnje.visionguard_android.data.model

data class RemovedDevice(
    val device: DeviceInfo,
    val index: Int
)

data class DeviceRemovalResult(
    val devices: List<DeviceInfo>,
    val removed: RemovedDevice?
)

data class DeviceRegistrySyncUpdate(
    val state: DeviceRegistrySyncState,
    val visibleDevices: List<DeviceInfo>,
    val devicesToPersist: List<DeviceInfo>? = null
)

data class DeviceRegistrySyncState(
    val knownDevices: List<DeviceInfo> = emptyList(),
    val realtimeDevices: List<DeviceInfo> = emptyList(),
    val registryLoaded: Boolean = false
) {
    fun onRegistryLoaded(savedDevices: List<DeviceInfo>): DeviceRegistrySyncUpdate {
        val mergedDevices = mergeKnownDevices(savedDevices, realtimeDevices)
        return DeviceRegistrySyncUpdate(
            state = copy(
                knownDevices = mergedDevices,
                registryLoaded = true
            ),
            visibleDevices = mergedDevices,
            devicesToPersist = mergedDevices.takeIf {
                savedDevices.isEmpty() && mergedDevices.isNotEmpty()
            }
        )
    }

    fun onRealtimeDevices(devices: List<DeviceInfo>): DeviceRegistrySyncUpdate {
        val mergedDevices = mergeKnownDevices(knownDevices, devices)
        return DeviceRegistrySyncUpdate(
            state = copy(
                knownDevices = if (registryLoaded) mergedDevices else knownDevices,
                realtimeDevices = devices
            ),
            visibleDevices = mergedDevices,
            devicesToPersist = mergedDevices.takeIf { registryLoaded }
        )
    }

    fun onManualDevices(devices: List<DeviceInfo>): DeviceRegistrySyncUpdate =
        DeviceRegistrySyncUpdate(
            state = copy(
                knownDevices = devices,
                registryLoaded = true
            ),
            visibleDevices = devices,
            devicesToPersist = devices
        )
}

fun mergeKnownDevices(
    knownDevices: List<DeviceInfo>,
    realtimeDevices: List<DeviceInfo>
): List<DeviceInfo> {
    val known = distinctDevicesById(knownDevices)
    val realtimeById = distinctDevicesById(realtimeDevices).associateBy { it.deviceId }
    val knownIds = known.map { it.deviceId }.toSet()

    val mergedKnown = known.map { knownDevice ->
        realtimeById[knownDevice.deviceId] ?: knownDevice.copy(
            online = false,
            isMonitoring = false
        )
    }
    val firstSeen = realtimeById.values.filter { it.deviceId !in knownIds }

    return mergedKnown + firstSeen
}

fun moveDeviceInOrder(
    devices: List<DeviceInfo>,
    fromIndex: Int,
    toIndex: Int
): List<DeviceInfo> {
    if (fromIndex !in devices.indices || toIndex !in devices.indices || fromIndex == toIndex) {
        return devices
    }
    return devices.toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

fun removeOfflineDeviceById(
    devices: List<DeviceInfo>,
    deviceId: String
): DeviceRemovalResult {
    val index = devices.indexOfFirst { it.deviceId == deviceId }
    if (index < 0 || devices[index].online) {
        return DeviceRemovalResult(devices = devices, removed = null)
    }
    val removed = RemovedDevice(device = devices[index], index = index)
    val remaining = devices.toMutableList().apply { removeAt(index) }
    return DeviceRemovalResult(devices = remaining, removed = removed)
}

fun restoreRemovedDevice(
    devices: List<DeviceInfo>,
    removed: RemovedDevice
): List<DeviceInfo> {
    if (devices.any { it.deviceId == removed.device.deviceId }) return devices
    return devices.toMutableList().apply {
        add(removed.index.coerceIn(0, size), removed.device)
    }
}

private fun distinctDevicesById(devices: List<DeviceInfo>): List<DeviceInfo> {
    val byId = linkedMapOf<String, DeviceInfo>()
    devices.forEach { device ->
        val id = device.deviceId.trim()
        if (id.isNotEmpty() && id !in byId) {
            byId[id] = device.copy(deviceId = id)
        }
    }
    return byId.values.toList()
}

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
            visibleDevices = sortDevicesForDisplay(mergedDevices),
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
            visibleDevices = sortDevicesForDisplay(mergedDevices),
            devicesToPersist = mergedDevices.takeIf { registryLoaded }
        )
    }

    fun onManualDevices(devices: List<DeviceInfo>): DeviceRegistrySyncUpdate =
        DeviceRegistrySyncUpdate(
            state = copy(
                knownDevices = devices,
                registryLoaded = true
            ),
            visibleDevices = sortDevicesForDisplay(devices),
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

/** 显示分组：监控中 > 在线 > 离线。ordinal 即分组优先级。 */
enum class DeviceDisplayGroup {
    MONITORING,
    ONLINE,
    OFFLINE
}

fun deviceDisplayGroup(device: DeviceInfo): DeviceDisplayGroup = when {
    !device.online -> DeviceDisplayGroup.OFFLINE
    device.isMonitoring -> DeviceDisplayGroup.MONITORING
    else -> DeviceDisplayGroup.ONLINE
}

/**
 * 显示顺序：监控中设备置顶（覆盖手动排序），在线设备在离线设备之前，
 * 组内保持手动相对顺序（sortedBy 稳定排序）。
 * 持久化的 knownDevices 始终保持纯手动顺序，不写入分组结果。
 */
fun sortDevicesForDisplay(devices: List<DeviceInfo>): List<DeviceInfo> =
    devices.sortedBy { deviceDisplayGroup(it).ordinal }

/**
 * 同组内拖拽：将可见列表（已按 sortDevicesForDisplay 排序）的移动映射回手动顺序。
 * 跨组拖拽直接返回原手动顺序（调用方视为无效移动）。
 */
fun moveDeviceWithinGroup(
    manualOrder: List<DeviceInfo>,
    visibleOrder: List<DeviceInfo>,
    fromIndex: Int,
    toIndex: Int
): List<DeviceInfo> {
    if (fromIndex !in visibleOrder.indices || toIndex !in visibleOrder.indices || fromIndex == toIndex) {
        return manualOrder
    }
    val group = deviceDisplayGroup(visibleOrder[fromIndex])
    if (deviceDisplayGroup(visibleOrder[toIndex]) != group) return manualOrder

    val reorderedVisible = moveDeviceInOrder(visibleOrder, fromIndex, toIndex)
    val groupDevices = reorderedVisible.filter { deviceDisplayGroup(it) == group }
    var next = 0
    return manualOrder.map { device ->
        if (deviceDisplayGroup(device) == group && next < groupDevices.size) {
            groupDevices[next++]
        } else {
            device
        }
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

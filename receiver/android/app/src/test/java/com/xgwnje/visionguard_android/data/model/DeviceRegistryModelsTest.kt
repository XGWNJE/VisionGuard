package com.xgwnje.visionguard_android.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRegistryModelsTest {

    @Test
    fun mergeKnownDevicesKeepsManualOrderAndMarksMissingDevicesOffline() {
        val known = listOf(
            device("door", "Door", online = true, isMonitoring = true),
            device("yard", "Yard", online = true, isMonitoring = false)
        )
        val realtime = listOf(
            device("yard", "Yard Live", online = true, isMonitoring = true)
        )

        val merged = mergeKnownDevices(known, realtime)

        assertEquals(listOf("door", "yard"), merged.map { it.deviceId })
        assertEquals(false, merged[0].online)
        assertEquals(false, merged[0].isMonitoring)
        assertEquals("Door", merged[0].deviceName)
        assertEquals(true, merged[1].online)
        assertEquals(true, merged[1].isMonitoring)
        assertEquals("Yard Live", merged[1].deviceName)
    }

    @Test
    fun mergeKnownDevicesAppendsFirstSeenDevicesToManualOrder() {
        val known = listOf(device("door", "Door"))
        val realtime = listOf(
            device("new", "New Camera", online = true),
            device("door", "Door", online = true)
        )

        val merged = mergeKnownDevices(known, realtime)

        assertEquals(listOf("door", "new"), merged.map { it.deviceId })
        assertEquals(true, merged[0].online)
        assertEquals(true, merged[1].online)
    }

    @Test
    fun registrySyncDoesNotPersistRealtimeOrderBeforeSavedOrderLoads() {
        val realtime = listOf(
            device("yard", "Yard Live", online = true),
            device("door", "Door Live", online = true)
        )

        val update = DeviceRegistrySyncState().onRealtimeDevices(realtime)

        assertEquals(listOf("yard", "door"), update.visibleDevices.map { it.deviceId })
        assertNull(update.devicesToPersist)
        assertEquals(emptyList<DeviceInfo>(), update.state.knownDevices)
        assertEquals(realtime, update.state.realtimeDevices)
    }

    @Test
    fun registrySyncRestoresManualOrderWhenSavedOrderLoadsAfterRealtimeList() {
        val stateAfterRealtime = DeviceRegistrySyncState()
            .onRealtimeDevices(
                listOf(
                    device("yard", "Yard Live", online = true),
                    device("door", "Door Live", online = true)
                )
            )
            .state
        val savedManualOrder = listOf(
            device("door", "Door Saved", online = true),
            device("yard", "Yard Saved", online = true)
        )

        val update = stateAfterRealtime.onRegistryLoaded(savedManualOrder)

        assertEquals(listOf("door", "yard"), update.visibleDevices.map { it.deviceId })
        assertEquals("Door Live", update.visibleDevices[0].deviceName)
        assertEquals("Yard Live", update.visibleDevices[1].deviceName)
        assertNull(update.devicesToPersist)
        assertEquals(true, update.state.registryLoaded)
    }

    @Test
    fun registrySyncPersistsFirstRealtimeDevicesWhenSavedRegistryIsEmpty() {
        val stateAfterRealtime = DeviceRegistrySyncState()
            .onRealtimeDevices(listOf(device("door", "Door Live", online = true)))
            .state

        val update = stateAfterRealtime.onRegistryLoaded(emptyList())

        assertEquals(listOf("door"), update.visibleDevices.map { it.deviceId })
        assertEquals(update.visibleDevices, update.devicesToPersist)
    }

    @Test
    fun registrySyncPersistsManualOrderAfterRegistryLoaded() {
        val loaded = DeviceRegistrySyncState()
            .onRegistryLoaded(
                listOf(
                    device("door", "Door"),
                    device("yard", "Yard")
                )
            )
            .state

        val update = loaded.onRealtimeDevices(
            listOf(
                device("yard", "Yard Live"),
                device("door", "Door Live")
            )
        )

        assertEquals(listOf("door", "yard"), update.visibleDevices.map { it.deviceId })
        assertEquals(update.visibleDevices, update.devicesToPersist)
        assertEquals(update.visibleDevices, update.state.knownDevices)
    }

    @Test
    fun moveDeviceInOrderUsesManualIndicesWithoutOnlineGrouping() {
        val devices = listOf(
            device("door", "Door", online = false),
            device("yard", "Yard", online = true),
            device("gate", "Gate", online = true)
        )

        val moved = moveDeviceInOrder(devices, fromIndex = 2, toIndex = 0)

        assertEquals(listOf("gate", "door", "yard"), moved.map { it.deviceId })
    }

    @Test
    fun removeOfflineDeviceRejectsOnlineDeviceAndRestoresAtOriginalIndex() {
        val devices = listOf(
            device("door", "Door", online = false),
            device("yard", "Yard", online = true)
        )

        assertNull(removeOfflineDeviceById(devices, "yard").removed)

        val removal = removeOfflineDeviceById(devices, "door")
        assertEquals(listOf("yard"), removal.devices.map { it.deviceId })
        assertEquals("door", removal.removed?.device?.deviceId)
        assertEquals(0, removal.removed?.index)

        val restored = restoreRemovedDevice(removal.devices, removal.removed!!)

        assertEquals(listOf("door", "yard"), restored.map { it.deviceId })
    }

    @Test
    fun mergeKnownDevicesIgnoresBlankAndDuplicateDeviceIds() {
        val known = listOf(device("door", "Door"), device("", "Blank"))
        val realtime = listOf(
            device("door", "Door Duplicate"),
            device("door", "Door Second Duplicate"),
            device("", "Realtime Blank")
        )

        val merged = mergeKnownDevices(known, realtime)

        assertEquals(listOf("door"), merged.map { it.deviceId })
        assertTrue(merged.single().deviceName.startsWith("Door"))
    }

    private fun device(
        id: String,
        name: String,
        online: Boolean = true,
        isMonitoring: Boolean = false
    ): DeviceInfo =
        DeviceInfo(
            deviceId = id,
            deviceName = name,
            online = online,
            isMonitoring = isMonitoring,
            isReady = true,
            lastSeen = "2026-07-08T10:00:00.000+08:00"
        )
}

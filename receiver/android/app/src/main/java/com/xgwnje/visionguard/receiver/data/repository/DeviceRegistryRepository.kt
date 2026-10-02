package com.xgwnje.visionguard.receiver.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xgwnje.visionguard.receiver.data.model.DeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map



class DeviceRegistryRepository(private val context: Context) {
    private val store = ScopedDataStores.get(context, "registry")
    suspend fun clearAccountData() { store.edit { it.clear() } }

    private object Keys {
        val KNOWN_DEVICES = stringPreferencesKey("known_devices")
    }

    private val gson = Gson()
    private val deviceListType = object : TypeToken<List<DeviceInfo>>() {}.type

    val devicesFlow: Flow<List<DeviceInfo>> = store.data.map { prefs ->
        decodeDevices(prefs[Keys.KNOWN_DEVICES])
    }

    suspend fun saveDevices(devices: List<DeviceInfo>) {
        val normalized = normalizeDevicesForStorage(devices)
        val json = gson.toJson(normalized)
        store.edit { prefs ->
            prefs[Keys.KNOWN_DEVICES] = json
        }
    }

    private fun decodeDevices(json: String?): List<DeviceInfo> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val decoded = gson.fromJson<List<DeviceInfo>>(json, deviceListType)
            normalizeDevicesForStorage(decoded ?: emptyList())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeDevicesForStorage(devices: List<DeviceInfo>): List<DeviceInfo> {
        val byId = linkedMapOf<String, DeviceInfo>()
        devices.forEach { device ->
            val id = device.deviceId.trim()
            if (id.isNotEmpty() && id !in byId) {
                byId[id] = device.copy(deviceId = id)
            }
        }
        return byId.values.take(MAX_KNOWN_DEVICES)
    }

    private companion object {
        const val MAX_KNOWN_DEVICES = 200
    }
}

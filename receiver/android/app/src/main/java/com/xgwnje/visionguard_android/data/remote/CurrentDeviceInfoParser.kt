package com.xgwnje.visionguard_android.data.remote

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xgwnje.visionguard_android.data.model.DeviceInfo

/** Rejects device records that do not implement the current device-list contract. */
internal fun parseCurrentDeviceInfo(element: JsonElement, gson: Gson): DeviceInfo? {
    if (!element.isJsonObject) return null
    val obj = element.asJsonObject

    if (!CURRENT_STRING_FIELDS.all(obj::hasString)) return null
    if (!CURRENT_BOOLEAN_FIELDS.all(obj::hasBoolean)) return null
    if (!CURRENT_NUMBER_FIELDS.all(obj::hasNumber)) return null
    if (!obj.hasStringArray("modelOptions")) return null
    if (!obj.hasStringArray("capabilities")) return null
    if (!obj.hasStringMap("components")) return null
    if (!obj.hasCurrentSources("sources")) return null

    return runCatching { gson.fromJson(element, DeviceInfo::class.java) }.getOrNull()
}

private val CURRENT_STRING_FIELDS = listOf(
    "deviceId", "deviceName", "lastSeen", "targets", "modelKey", "clientType"
)

private val CURRENT_BOOLEAN_FIELDS = listOf(
    "online", "isMonitoring", "isReady", "canSwitchModelWhileMonitoring", "hasPendingConfigChanges"
)

private val CURRENT_NUMBER_FIELDS = listOf("cooldown", "confidence", "targetSamplingRate")

private fun JsonObject.hasString(name: String): Boolean =
    get(name)?.let { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isString } == true

private fun JsonObject.hasBoolean(name: String): Boolean =
    get(name)?.let { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean } == true

private fun JsonObject.hasNumber(name: String): Boolean =
    get(name)?.let { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isNumber } == true

private fun JsonObject.hasStringArray(name: String): Boolean {
    val value = get(name) ?: return false
    return value.isJsonArray && value.asJsonArray.all {
        !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isString
    }
}

private fun JsonObject.hasStringMap(name: String): Boolean {
    val value = get(name) ?: return false
    return value.isJsonObject && value.asJsonObject.entrySet().all { (_, state) ->
        !state.isJsonNull && state.isJsonPrimitive && state.asJsonPrimitive.isString
    }
}

private fun JsonObject.hasCurrentSources(name: String): Boolean {
    val value = get(name) ?: return false
    return value.isJsonArray && value.asJsonArray.all { source ->
        source.isJsonObject && source.asJsonObject.let {
            it.hasString("sourceId") &&
                it.hasString("sourceName") &&
                it.hasBoolean("isMonitoring") &&
                it.hasBoolean("isReady") &&
                it.hasString("modelKey")
        }
    }
}

package com.xgwnje.visionguard.receiver.data.remote

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.xgwnje.visionguard.receiver.data.model.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

/** Account registration determines membership; WS snapshots supply current runtime state. */
internal fun parseAccountDeviceInfo(element: JsonElement, accountId: String, gson: Gson): DeviceInfo? {
    if (!element.isJsonObject) return null
    val obj = element.asJsonObject
    val fields = listOf("accountId", "deviceId", "deviceName", "component", "role", "nodeType", "platform")
    if (fields.any { obj[it]?.let { value -> value.isJsonPrimitive && value.asJsonPrimitive.isString } != true }) return null
    if (obj["accountId"].asString != accountId) return null
    val role = obj["role"].asString
    if (role !in listOf("detector", "notifier")) return null
    val identity = DeviceInfo(
        deviceId = obj["deviceId"].asString, deviceName = obj["deviceName"].asString,
        online = false, isMonitoring = false, isReady = false, lastSeen = "",
        clientType = when (val component = obj["component"].asString) {
            "windows-inference" -> "windows"
            else -> component
        },
        role = role, nodeType = obj["nodeType"].asString, platform = obj["platform"].asString,
        component = obj["component"].asString,
        capabilities = if (obj["component"].asString == "android-camera") listOf("video-publish") else emptyList()
    )
    if (identity.deviceId.isBlank() || identity.deviceName.isBlank()) return null
    return parseCurrentDeviceInfo(element, gson)?.copy(deviceName = identity.deviceName, component = identity.component) ?: identity
}

internal fun mergeAccountDevices(registered: List<DeviceInfo>, runtime: List<DeviceInfo>): List<DeviceInfo> {
    val live = runtime.associateBy { it.deviceId }
    return registered.distinctBy { it.deviceId }.map { identity ->
        live[identity.deviceId]?.copy(deviceName = identity.deviceName, component = identity.component,
            role = identity.role, nodeType = identity.nodeType, platform = identity.platform) ?: identity.copy(
            online = false, isMonitoring = false, isReady = false, isStreaming = false)
    }
}

internal suspend fun fetchAccountDevices(
    http: OkHttpClient, request: Request, accountId: String, requestScope: String, currentScope: () -> String?
): List<DeviceInfo>? {
    val result = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = JsonParser.parseString(response.body?.string() ?: return@withContext null).asJsonObject
            if (body["ok"]?.asBoolean != true || !body.has("devices") || !body["devices"].isJsonArray) return@withContext null
            val gson = Gson()
            body["devices"].asJsonArray.mapNotNull { parseAccountDeviceInfo(it, accountId, gson) }
        }
    }
    return result.takeIf { currentScope() == requestScope }
}

internal suspend fun unbindAccountDevice(http: OkHttpClient, request: Request, requestScope: String, currentScope: () -> String?): Boolean {
    if (currentScope() != requestScope) return false
    val success = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            response.isSuccessful && JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject["ok"]?.asBoolean == true
        }
    }
    return success && currentScope() == requestScope
}

/** Serializes directory reads with confirmed deletions, including the cache/UI update. */
internal class AccountDeviceDirectory(
    private val http: OkHttpClient,
    private val currentScope: () -> String?,
    private val apply: suspend () -> Unit
) {
    var devices: List<DeviceInfo>? = null
        private set
    private val mutex = Mutex()

    suspend fun refresh(request: Request, accountId: String, requestScope: String): Boolean = mutex.withLock {
        read(request, accountId, requestScope)
    }

    suspend fun unbind(delete: Request, refresh: Request, accountId: String, requestScope: String,
                       visible: List<DeviceInfo>, deviceId: String): Boolean = mutex.withLock {
        if (!unbindAccountDevice(http, delete, requestScope, currentScope)) return@withLock false
        devices = (devices ?: visible).filter { it.deviceId != deviceId }
        apply()
        read(refresh, accountId, requestScope)
        true
    }

    private suspend fun read(request: Request, accountId: String, requestScope: String): Boolean = try {
        val registered = fetchAccountDevices(http, request, accountId, requestScope, currentScope)
        if (registered == null) false else {
            devices = registered
            apply()
            true
        }
    } catch (_: Exception) { false }
}

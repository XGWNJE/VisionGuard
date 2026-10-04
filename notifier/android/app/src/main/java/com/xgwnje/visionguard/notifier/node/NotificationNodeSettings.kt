package com.xgwnje.visionguard.notifier.node

import android.content.Context
import com.xgwnje.visionguard.account.AccountStore

data class NodeConnection(val endpoint: String = "", val deviceId: String = "",
    val name: String = "通知节点", val token: String = "")

class NotificationNodeSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("notification-settings-" + AccountStore.cacheKey(context), Context.MODE_PRIVATE)
    val timeZone: String? get() = prefs.getString("time_zone", null)?.takeIf(::validAlarmTimeZone)
    fun saveTimeZone(value: String): Boolean = validAlarmTimeZone(value) && prefs.edit().putString("time_zone", value).commit()
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun read(): NodeConnection = AccountStore.get(context).session.value?.let {
        NodeConnection(it.webSocketUrl, it.deviceId, it.deviceName, it.token)
    } ?: NodeConnection()
    fun clearAccountData() { check(prefs.edit().clear().commit()) }
    companion object {
        fun valid(value: NodeConnection): Boolean = value.token.isNotBlank() && value.deviceId.isNotBlank() &&
            runCatching { AccountStore.normalizeEndpoint(value.endpoint.removeSuffix("/ws").replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")) }.isSuccess
    }
}

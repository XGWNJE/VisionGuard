package com.xgwnje.visionguard.receiver.data.repository

// ┌─────────────────────────────────────────────────────────┐
// │ SettingsRepository.kt                                   │
// │ 角色：DataStore 持久化封装                              │
// │ 持久化：设备 ID、报警历史与来源静音设置                  │
// │ 服务、账号与本机身份来自 AccountStore；数据按账号隔离     │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xgwnje.visionguard.receiver.data.model.AlertMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID



class SettingsRepository(private val context: Context) {
    private val store = ScopedDataStores.get(context, "settings")
    suspend fun clearAccountData() { store.edit { it.clear() } }

    private object Keys {
        val ALERTS_HISTORY = stringPreferencesKey("alerts_history") // Gson JSON，最多 50 条
        val MUTED_ALERT_SOURCES = stringPreferencesKey("muted_alert_sources")
    }

    suspend fun ensureDeviceId(): String = com.xgwnje.visionguard.account.AccountStore.get(context).session.value?.deviceId ?: error("请先登录")

    /** 持久化报警历史列表（上限 50 条，避免 DataStore 过大） */
    suspend fun saveAlerts(alerts: List<AlertMessage>) {
        val toSave = alerts.take(50)
        val json = Gson().toJson(toSave)
        store.edit { prefs -> prefs[Keys.ALERTS_HISTORY] = json }
    }

    /** 从 DataStore 恢复报警历史 */
    suspend fun loadAlerts(): List<AlertMessage> {
        val json = store.data.map { it[Keys.ALERTS_HISTORY] ?: "[]" }.first()
        return try {
            val type = object : TypeToken<List<AlertMessage>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    val mutedAlertSourcesFlow: Flow<Set<String>> = store.data.map { prefs ->
        parseStringSet(prefs[Keys.MUTED_ALERT_SOURCES])
    }

    suspend fun setAlertSourceMuted(key: String, muted: Boolean) {
        require(key.isNotBlank()) { "source key must not be blank" }
        store.edit { prefs ->
            val current = parseStringSet(prefs[Keys.MUTED_ALERT_SOURCES]).toMutableSet()
            if (muted) current.add(key) else current.remove(key)
            prefs[Keys.MUTED_ALERT_SOURCES] = Gson().toJson(current)
        }
    }

    private fun parseStringSet(json: String?): Set<String> = try {
        val type = object : TypeToken<Set<String>>() {}.type
        Gson().fromJson<Set<String>>(json ?: "[]", type) ?: emptySet()
    } catch (_: Exception) { emptySet() }
}

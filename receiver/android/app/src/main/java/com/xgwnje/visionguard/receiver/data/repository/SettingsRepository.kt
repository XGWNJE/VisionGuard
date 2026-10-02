package com.xgwnje.visionguard.receiver.data.repository

// ┌─────────────────────────────────────────────────────────┐
// │ SettingsRepository.kt                                   │
// │ 角色：DataStore 持久化封装                              │
// │ 持久化：设备 ID、报警历史与来源静音设置                  │
// │ serverUrl / apiKey 已移至 AppConstants 硬编码             │
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vg_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEVICE_ID   = stringPreferencesKey("device_id")
        val ALERTS_HISTORY = stringPreferencesKey("alerts_history") // Gson JSON，最多 50 条
        val MUTED_ALERT_SOURCES = stringPreferencesKey("muted_alert_sources")
    }

    /** 确保 deviceId 存在（首次启动生成），返回最终值 */
    suspend fun ensureDeviceId(): String {
        val configured = com.xgwnje.visionguard.receiver.BuildConfig.DEVICE_ID
        if (configured.isNotBlank()) return configured
        var id = ""
        context.dataStore.edit { prefs ->
            if (prefs[Keys.DEVICE_ID].isNullOrEmpty()) {
                prefs[Keys.DEVICE_ID] = UUID.randomUUID().toString()
            }
            id = prefs[Keys.DEVICE_ID] ?: ""
        }
        return id
    }

    /** 持久化报警历史列表（上限 50 条，避免 DataStore 过大） */
    suspend fun saveAlerts(alerts: List<AlertMessage>) {
        val toSave = alerts.take(50)
        val json = Gson().toJson(toSave)
        context.dataStore.edit { prefs -> prefs[Keys.ALERTS_HISTORY] = json }
    }

    /** 从 DataStore 恢复报警历史 */
    suspend fun loadAlerts(): List<AlertMessage> {
        val json = context.dataStore.data.map { it[Keys.ALERTS_HISTORY] ?: "[]" }.first()
        return try {
            val type = object : TypeToken<List<AlertMessage>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    val mutedAlertSourcesFlow: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        parseStringSet(prefs[Keys.MUTED_ALERT_SOURCES])
    }

    suspend fun setAlertSourceMuted(key: String, muted: Boolean) {
        require(key.isNotBlank()) { "source key must not be blank" }
        context.dataStore.edit { prefs ->
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

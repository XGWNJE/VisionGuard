package com.xgwnje.visionguard.notifier

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AlertEndType { MANUAL, AUTO, ERROR }


data class AlertRecord(
    val keyword: String,
    val sourceApp: String?,
    val timestamp: Long,
    val endType: AlertEndType,
    val id: String = UUID.randomUUID().toString()
)


data class AlertQueueItem(
    val id: String,
    val keyword: String,
    val sourcePackage: String?,
    val sourceApp: String?,
    val firstTriggeredAt: Long,
    val lastTriggeredAt: Long,
    val ringtoneUri: String?,
    val loopLimit: Int,
    val playedLoops: Int,
    val occurrenceCount: Int,
    val snippet: String? = null
)

data class AlertQueueTransition(
    val success: Boolean,
    val finished: AlertQueueItem?,
    val next: AlertQueueItem?,
    val remaining: Int
)

class SharedPreferencesHelper(context: Context) {
    internal val prefs: SharedPreferences = context.getSharedPreferences("vg_notifier_prefs-" + com.xgwnje.visionguard.account.AccountStore.cacheKey(context), Context.MODE_PRIVATE)
    fun clearAccountData() = synchronized(alertQueueLock) { check(prefs.edit().clear().commit()) }
    companion object {
        private val alertQueueLock = Any()
        private const val KEY_RINGTONE_URI = "ringtone_uri"
        private const val KEY_ALERT_QUEUE = "alert_queue"
        private const val ALERT_QUEUE_VERSION = 1
        private const val KEY_DEFAULT_LOOP_COUNT = "default_loop_count"
        private const val KEY_ALERT_HISTORY = "alert_history"
        private const val KEY_SERVICE_OUTAGE = "service_outage"
        private const val MAX_ALERT_HISTORY = 100
        private const val KEY_RINGTONE_LIBRARY = "ringtone_library"
        const val MAX_ALERT_QUEUE_SIZE = 20
        const val MIN_LOOP_COUNT = 1
        const val MAX_LOOP_COUNT = 10
        const val DEFAULT_LOOP_COUNT = MAX_LOOP_COUNT
        internal fun normalizeLoopCount(count: Int): Int = if (count == 0) DEFAULT_LOOP_COUNT else count.coerceIn(MIN_LOOP_COUNT, MAX_LOOP_COUNT)
    }

    fun saveRingtoneValue(value: String?) {
        prefs.edit().putString(KEY_RINGTONE_URI, value).apply()
        Log.i("SharedPreferencesHelper", "默认铃声已保存: $value")
    }

    fun getRingtoneValue(): String? {
        return prefs.getString(KEY_RINGTONE_URI, null)
    }

    fun getAlertQueue(): List<AlertQueueItem> = synchronized(alertQueueLock) {
        readAlertQueueLocked()
    }

    fun getActiveAlert(): AlertQueueItem? = getAlertQueue().firstOrNull()

    fun serviceOutageId(): String? = synchronized(alertQueueLock) { prefs.getString(KEY_SERVICE_OUTAGE, null) }
    fun markServiceRecovered(): Boolean = synchronized(alertQueueLock) { !prefs.contains(KEY_SERVICE_OUTAGE) || commitCritical(prefs.edit().remove(KEY_SERVICE_OUTAGE), KEY_SERVICE_OUTAGE) }

    fun acceptRemoteAlert(id: String, label: String, source: String, summary: String,
                          timestamp: Long, expiresAt: Long, now: Long = System.currentTimeMillis(), serviceOutage: Boolean = false): Boolean =
        synchronized(alertQueueLock) {
            if (serviceOutage && prefs.contains(KEY_SERVICE_OUTAGE)) return@synchronized true
            if (id.isBlank() || id.length > 128 || expiresAt <= now || timestamp > now + 5_000 ||
                expiresAt <= timestamp || expiresAt - timestamp > 30_000) return@synchronized false
            val key = "remote_receipts"
            val accepted = try { JSONObject(prefs.getString(key, "{}") ?: "{}") }
                catch (_: Exception) { return@synchronized false }
            accepted.keys().asSequence().toList().filter { accepted.optLong(it) <= now }.forEach { accepted.remove(it) }
            if (accepted.has(id)) return@synchronized true
            val queue = readAlertQueueLocked().toMutableList()
            if (queue.size >= MAX_ALERT_QUEUE_SIZE || accepted.length() >= 1_000) return@synchronized false
            // A previously accepted item can remain in the queue beyond the transport deadline.
            if (queue.any { it.id == id }) return@synchronized true
            queue += AlertQueueItem(id, label, "visionguard", source.take(160), timestamp, timestamp,
                getRingtoneValue(), getDefaultLoopCount(), 0, 1, summary.take(100))
            accepted.put(id, expiresAt)
            val editor = prefs.edit().putString(KEY_ALERT_QUEUE, encodeAlertQueue(queue)).putString(key, accepted.toString())
            if (serviceOutage) editor.putString(KEY_SERVICE_OUTAGE, id)
            commitCritical(editor, KEY_ALERT_QUEUE, key, KEY_SERVICE_OUTAGE)
        }

    fun updateActiveAlertPlayedLoops(expectedId: String, playedLoops: Int): Boolean =
        synchronized(alertQueueLock) {
            val queue = readAlertQueueLocked().toMutableList()
            val active = queue.firstOrNull() ?: return@synchronized false
            if (active.id != expectedId) return@synchronized false
            queue[0] = active.copy(playedLoops = playedLoops.coerceAtLeast(0))
            commitCritical(prefs.edit().putString(KEY_ALERT_QUEUE, encodeAlertQueue(queue)), KEY_ALERT_QUEUE)
        }

    fun finishActiveAlert(expectedId: String, endType: AlertEndType): AlertQueueTransition =
        synchronized(alertQueueLock) {
            val queue = readAlertQueueLocked().toMutableList()
            val active = queue.firstOrNull()
                ?: return@synchronized AlertQueueTransition(false, null, null, 0)
            if (active.id != expectedId) {
                return@synchronized AlertQueueTransition(false, null, active, queue.size)
            }
            queue.removeAt(0)
            val history = prependHistoryRecord(
                readAlertHistoryArray(),
                AlertRecord(active.keyword, active.sourceApp, active.firstTriggeredAt, endType, active.id)
            )
            val committed = prefs.edit()
                .putString(KEY_ALERT_QUEUE, encodeAlertQueue(queue))
                .putString(KEY_ALERT_HISTORY, history.toString())
                .let { commitCritical(it, KEY_ALERT_QUEUE, KEY_ALERT_HISTORY) }
            AlertQueueTransition(
                success = committed,
                finished = if (committed) active else null,
                next = if (committed) queue.firstOrNull() else active,
                remaining = if (committed) queue.size else queue.size + 1
            )
        }

    private fun readAlertQueueLocked(): List<AlertQueueItem> {
        return try {
            val root = JSONObject(prefs.getString(KEY_ALERT_QUEUE, null) ?: return emptyList())
            val items = root.optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until minOf(items.length(), MAX_ALERT_QUEUE_SIZE)) {
                    val obj = items.getJSONObject(i)
                    val keyword = obj.getString("keyword")
                    add(
                        AlertQueueItem(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            keyword = keyword,
                            sourcePackage = obj.nullableString("sourcePackage"),
                            sourceApp = obj.nullableString("sourceApp"),
                            firstTriggeredAt = obj.optLong("firstTriggeredAt", 0L),
                            lastTriggeredAt = obj.optLong(
                                "lastTriggeredAt", obj.optLong("firstTriggeredAt", 0L)
                            ),
                            ringtoneUri = obj.nullableString("ringtoneUri"),
                            loopLimit = normalizeLoopCount(
                                obj.optInt("loopLimit", DEFAULT_LOOP_COUNT)
                            ),
                            playedLoops = obj.optInt("playedLoops", 0).coerceAtLeast(0),
                            occurrenceCount = obj.optInt("occurrenceCount", 1).coerceAtLeast(1),
                            snippet = obj.nullableString("snippet")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SharedPreferencesHelper", "读取报警队列失败，按空队列处理", e)
            emptyList()
        }
    }

    private fun encodeAlertQueue(queue: List<AlertQueueItem>): String {
        val items = JSONArray()
        queue.take(MAX_ALERT_QUEUE_SIZE).forEach { item ->
            items.put(JSONObject().apply {
                put("id", item.id)
                put("keyword", item.keyword)
                put("sourcePackage", item.sourcePackage ?: JSONObject.NULL)
                put("sourceApp", item.sourceApp ?: JSONObject.NULL)
                put("firstTriggeredAt", item.firstTriggeredAt)
                put("lastTriggeredAt", item.lastTriggeredAt)
                put("ringtoneUri", item.ringtoneUri ?: JSONObject.NULL)
                put("loopLimit", normalizeLoopCount(item.loopLimit))
                put("playedLoops", item.playedLoops.coerceAtLeast(0))
                put("occurrenceCount", item.occurrenceCount.coerceAtLeast(1))
                put("snippet", item.snippet ?: JSONObject.NULL)
            })
        }
        return JSONObject().put("version", ALERT_QUEUE_VERSION).put("items", items).toString()
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    fun saveDefaultLoopCount(count: Int) {
        val normalized = normalizeLoopCount(count)
        prefs.edit().putInt(KEY_DEFAULT_LOOP_COUNT, normalized).apply()
        Log.i("SharedPreferencesHelper", "默认循环次数已保存: $normalized")
    }

    fun getDefaultLoopCount(): Int {
        val stored = prefs.getInt(KEY_DEFAULT_LOOP_COUNT, DEFAULT_LOOP_COUNT)
        val normalized = normalizeLoopCount(stored)
        if (stored != normalized) {
            prefs.edit().putInt(KEY_DEFAULT_LOOP_COUNT, normalized).apply()
        }
        return normalized
    }

    private fun readJsonStringMap(key: String): Map<String, String> {
        return try {
            val obj = JSONObject(prefs.getString(key, null) ?: "{}")
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            Log.e("SharedPreferencesHelper", "读取 JSON 映射失败: $key", e)
            emptyMap()
        }
    }

    private fun commitCritical(editor: SharedPreferences.Editor, vararg keys: String): Boolean {
        val before = keys.associateWith { prefs.getString(it, null) }
        if (editor.commit()) return true
        val rollback = prefs.edit()
        before.forEach { (key, value) -> rollback.putString(key, value) }
        rollback.commit()
        return false
    }

    private fun prependHistoryRecord(arr: JSONArray, record: AlertRecord): JSONArray {
        val obj = JSONObject().apply {
            put("id", record.id)
            put("keyword", record.keyword)
            put("sourceApp", record.sourceApp ?: JSONObject.NULL)
            put("timestamp", record.timestamp)
            put("endType", record.endType.name)
        }
        return JSONArray().put(obj).also { result ->
            for (i in 0 until minOf(arr.length(), MAX_ALERT_HISTORY - 1)) {
                result.put(arr.get(i))
            }
        }
    }

    fun getAlertHistory(): List<AlertRecord> {
        val arr = readAlertHistoryArray()
        val list = mutableListOf<AlertRecord>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                list.add(
                    AlertRecord(
                        keyword = obj.getString("keyword"),
                        sourceApp = if (obj.isNull("sourceApp")) null else obj.getString("sourceApp"),
                        timestamp = obj.getLong("timestamp"),
                        endType = runCatching { AlertEndType.valueOf(obj.getString("endType")) }
                            .getOrDefault(AlertEndType.MANUAL),
                        id = obj.optString("id").ifBlank { "legacy-$i-${obj.getLong("timestamp")}" }
                    )
                )
            } catch (e: Exception) {
                Log.e("SharedPreferencesHelper", "解析报警记录失败 (index=$i)", e)
            }
        }
        return list
    }

    fun clearAlertHistory() {
        prefs.edit().remove(KEY_ALERT_HISTORY).apply()
        Log.i("SharedPreferencesHelper", "报警记录已清空。")
    }

    private fun readAlertHistoryArray(): JSONArray {
        return try {
            JSONArray(prefs.getString(KEY_ALERT_HISTORY, null) ?: "[]")
        } catch (e: Exception) {
            Log.e("SharedPreferencesHelper", "读取报警记录失败，按空处理", e)
            JSONArray()
        }
    }

    fun getRingtoneLibraryMap(): Map<String, String> {
        return readJsonStringMap(KEY_RINGTONE_LIBRARY)
    }

    fun putRingtoneLibraryEntry(fileName: String, displayName: String) {
        val map = getRingtoneLibraryMap().toMutableMap()
        map[fileName] = displayName
        prefs.edit().putString(KEY_RINGTONE_LIBRARY, JSONObject(map as Map<*, *>).toString()).apply()
        Log.i("SharedPreferencesHelper", "铃声库条目已保存: $fileName -> $displayName")
    }

    fun removeRingtoneLibraryEntry(fileName: String) {
        val map = getRingtoneLibraryMap().toMutableMap()
        if (map.remove(fileName) != null) {
            prefs.edit().putString(KEY_RINGTONE_LIBRARY, JSONObject(map as Map<*, *>).toString()).apply()
            Log.i("SharedPreferencesHelper", "铃声库条目已移除: $fileName")
        }
    }

}

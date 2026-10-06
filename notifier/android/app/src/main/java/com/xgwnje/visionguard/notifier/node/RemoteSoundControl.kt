package com.xgwnje.visionguard.notifier.node

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.xgwnje.visionguard.account.DisplayNamePolicy
import com.xgwnje.visionguard.account.RemoteConfigPolicy
import com.xgwnje.visionguard.notifier.RingtoneLibrary
import com.xgwnje.visionguard.notifier.SharedPreferencesHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Remote IDs expose no filesystem path. Every mutating operation rechecks the current library. */
class RemoteSoundControl(private val context: Context, private val preferences: SharedPreferencesHelper) {
    private val ownerScope = com.xgwnje.visionguard.account.AccountStore.cacheKey(context)
    private fun checkOwner() { check(com.xgwnje.visionguard.account.AccountStore.get(context).session.value != null && com.xgwnje.visionguard.account.AccountStore.cacheKey(context) == ownerScope) { "登录已变更，操作取消" } }
    private fun id(name: String) = "library:" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun files(): Map<String, String> = preferences.getRingtoneLibraryMap().filter { (name, _) ->
        val file = File(RingtoneLibrary.libraryDir(context), name)
        file.canonicalFile.parentFile == RingtoneLibrary.libraryDir(context).canonicalFile && file.isFile
    }
    private fun fileName(key: String): String = files().keys.firstOrNull { id(it) == key } ?: error("音频条目已不存在")
    private fun selected(): String {
        val value = preferences.getRingtoneValue()
        return when {
            value == null -> "system-default"
            value == RingtoneLibrary.SILENT_VALUE -> "silent"
            value.startsWith("content://") -> "system-current"
            RingtoneLibrary.isPresetValue(value) -> "preset:" + value.substringAfterLast('/')
            else -> files().keys.firstOrNull { File(RingtoneLibrary.libraryDir(context), it).absolutePath == value }?.let(::id) ?: "system-default"
        }
    }
    fun snapshot(): JSONObject {
        val entries = JSONArray().put(JSONObject().put("id", "system-default").put("name", "系统默认闹钟").put("mutable", false))
            .put(JSONObject().put("id", "silent").put("name", "静音").put("mutable", false))
        if (preferences.getRingtoneValue()?.startsWith("content://") == true) entries.put(JSONObject().put("id", "system-current").put("name", "本机选择的系统铃声").put("mutable", false))
        RingtoneLibrary.PRESETS.forEach { entries.put(JSONObject().put("id", "preset:" + it.rawName).put("name", it.displayName).put("mutable", false)) }
        val library = files()
        val selection = selected()
        // Keep the selected item visible even when pre-existing libraries exceed the new bound.
        library.entries.sortedWith(compareBy<Map.Entry<String, String>> { id(it.key) != selection }.thenBy { it.key })
            .take(RemoteConfigPolicy.MAX_AUDIO_ENTRIES).forEach { (name, label) ->
                entries.put(JSONObject().put("id", id(name)).put("name", DisplayNamePolicy.generated(label, "音频")).put("mutable", true))
            }
        val preview = RingtoneLibrary.previewingFileName?.let { if (it.startsWith("preset:")) it else if (library.containsKey(it)) id(it) else "" } ?: ""
        return JSONObject().put("soundLoopCount", preferences.getDefaultLoopCount()).put("soundSelection", selection)
            .put("audioEntries", entries).put("audioPreview", preview).put("audioLibraryFull", library.size >= RemoteConfigPolicy.MAX_AUDIO_ENTRIES)
    }
    fun apply(key: String, raw: String): String {
        checkOwner()
        when (key) {
            "soundLoopCount" -> preferences.saveDefaultLoopCount(RemoteConfigPolicy.loopCount(raw))
            "soundSelection" -> {
                require(RemoteConfigPolicy.audioId(raw)) { "无效的音频条目" }
                val value = when {
                    raw == "system-default" -> null
                    raw == "silent" -> RingtoneLibrary.SILENT_VALUE
                    raw == "system-current" -> preferences.getRingtoneValue()?.takeIf { it.startsWith("content://") } ?: error("请在本机选择系统铃声")
                    raw.startsWith("preset:") -> RingtoneLibrary.presetValue(context, RingtoneLibrary.PRESETS.firstOrNull { it.rawName == raw.removePrefix("preset:") } ?: error("预设音频不存在"))
                    else -> File(RingtoneLibrary.libraryDir(context), fileName(raw)).absolutePath
                }
                preferences.saveRingtoneValue(value)
            }
            "audioRename" -> {
                val value = JSONObject(raw); val name = fileName(value.getString("id"))
                preferences.putRingtoneLibraryEntry(name, DisplayNamePolicy.normalize(value.getString("name")))
            }
            "audioDelete" -> {
                require(raw != selected()) { "请先选择其他默认音频" }
                require(!RingtoneLibrary.isRecording) { "录音中不能删除音频" }
                val name = fileName(raw); val file = File(RingtoneLibrary.libraryDir(context), name)
                preferences.withUnusedRingtone(file.absolutePath) {
                    if (RingtoneLibrary.previewingFileName == name) RingtoneLibrary.stopPreview()
                    val label = preferences.getRingtoneLibraryMap().getValue(name)
                    preferences.removeRingtoneLibraryEntry(name)
                    if (!RingtoneLibrary.deleteFile(context, name)) {
                        preferences.putRingtoneLibraryEntry(name, label)
                        error("删除文件失败，条目已保留")
                    }
                }
            }
            "audioPreview" -> {
                require(preferences.getActiveAlert() == null && !RingtoneLibrary.isRecording) { "报警或录音期间不能试听" }
                RingtoneLibrary.stopPreview()
                val success = if (raw.startsWith("preset:")) RingtoneLibrary.togglePresetPreview(context,
                    RingtoneLibrary.PRESETS.firstOrNull { it.rawName == raw.removePrefix("preset:") } ?: error("预设不存在"))
                    else RingtoneLibrary.togglePreview(context, fileName(raw))
                check(success) { "无法开始试听" }
                return "设备已开始试听；是否可听需在本机确认"
            }
            "audioStopPreview" -> { require(raw.isEmpty()); RingtoneLibrary.stopPreview(); return "已停止试听" }
            "audioImport" -> { importAudio(raw); return "音频已导入" }
            else -> error("不支持的声音配置")
        }
        return "配置已保存；声音策略用于后续入队报警"
    }
    private fun importAudio(raw: String) {
        require(!RingtoneLibrary.isRecording) { "录音期间不能导入" }
        require(preferences.getRingtoneLibraryMap().size < RemoteConfigPolicy.MAX_AUDIO_ENTRIES) { "音频库最多 100 项" }
        require(raw.length <= ((RemoteConfigPolicy.MAX_AUDIO_BYTES + 2) / 3) * 4 + 1024) { "音频文件超限" }
        val value = JSONObject(raw)
        val name = DisplayNamePolicy.normalize(value.getString("name"))
        val extension = when (value.getString("mime")) { "audio/wav" -> ".wav"; "audio/mpeg" -> ".mp3"; "audio/ogg" -> ".ogg"; "audio/mp4" -> ".m4a"; else -> error("不支持的音频格式") }
        val encoded = value.getString("data")
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.isNotEmpty() && bytes.size <= RemoteConfigPolicy.MAX_AUDIO_BYTES && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) { "音频数据无效或超过 512 KiB" }
        val file = File(RingtoneLibrary.libraryDir(context), UUID.randomUUID().toString() + extension)
        try {
            file.outputStream().use { it.write(bytes); it.flush(); (it as java.io.FileOutputStream).fd.sync() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                require(duration in 1L..60_000L && retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "音频须可解码且不超过 60 秒" }
            } finally { retriever.release() }
            checkOwner()
            preferences.putRingtoneLibraryEntry(file.name, name)
        } catch (error: Exception) { file.delete(); throw error }
    }
}

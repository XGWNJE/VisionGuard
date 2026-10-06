package com.xgwnje.visionguard.account

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fixed directories only. Persistent sounds, alarms, models and settings are never scanned. */
object TemporaryCache {
    val exportLock = Any()
    private const val DAY = 24 * 60 * 60 * 1000L
    fun exportPrefix(context: Context) = "vg_notifier_log_" + AccountStore.cacheKey(context) + "_"
    fun reserveExport(context: Context): File = synchronized(exportLock) {
        exports(context, true)
        val files = context.cacheDir.canonicalFile.listFiles().orEmpty().filter { it.name.startsWith(exportPrefix(context)) && it.name.endsWith(".txt") }
        check(files.size < 20 && files.sumOf { it.length() } + 3L * 1024 * 1024 <= 40L * 1024 * 1024) { "诊断导出已达上限，请等待过期缓存回收" }
        File(context.cacheDir.canonicalFile, exportPrefix(context) + java.util.UUID.randomUUID() + ".txt")
    }
    suspend fun maintain(context: Context, clean: Boolean, active: () -> Boolean = { true }): JSONObject {
        val token = AccountStore.get(context).session.value?.token ?: error("请先登录")
        return ClientUpdater.withCacheLock {
            val valid = { active() && AccountStore.get(context).session.value?.token == token }
            check(valid()) { "登录已变更，操作取消" }
            synchronized(exportLock) {
                val export = exports(context, clean, valid)
                val updates = scan(File(context.cacheDir.canonicalFile, "client-updates"), "updates", "安装包暂存（7 天）", clean, valid = valid) { file ->
                    TemporaryCachePolicy.expiredUpdate(file.name, file.lastModified(), System.currentTimeMillis(), ClientUpdater.isProtected(file))
                }
                JSONObject().put("categories", JSONArray().put(export.getJSONObject("category")).put(updates.getJSONObject("category")))
                    .apply {
                        for (key in listOf("removedFiles", "removedBytes", "failedFiles")) put(key, export.getLong(key) + updates.getLong(key))
                        put("releasedBytes", JSONObject.NULL)
                    }
            }
        }
    }
    private fun exports(context: Context, clean: Boolean, valid: () -> Boolean = { true }): JSONObject {
        val prefix = exportPrefix(context)
        return scan(context.cacheDir.canonicalFile, "diagnostic-exports", "当前账号诊断导出（24 小时）", clean,
            valid = valid, include = { it.name.startsWith(prefix) && it.name.endsWith(".txt") }) { System.currentTimeMillis() - it.lastModified() > DAY }
    }
    private fun safe(directory: File): Boolean {
        var current: File? = directory.absoluteFile
        while (current != null) { if (!current.isDirectory || current.canonicalFile != current.absoluteFile) return false; current = current.parentFile }
        return true
    }
    private fun scan(directory: File, id: String, label: String, clean: Boolean, valid: () -> Boolean = { true }, include: (File) -> Boolean = { true }, eligible: (File) -> Boolean): JSONObject {
        var files = 0L; var bytes = 0L; var candidates = 0L; var candidateBytes = 0L; var removed = 0L; var removedBytes = 0L; var failed = 0L
        check(valid()) { "连接已变更，操作取消" }
        if (directory.exists() && !safe(directory)) failed++
        if (safe(directory)) for (file in directory.listFiles().orEmpty()) {
            // Account export scope is enforced before reporting sizes as well as before deletion.
            if (!include(file)) continue
            if (!file.isFile || file.canonicalFile != file.absoluteFile) continue
            val length = file.length(); files++; bytes += length
            if (!eligible(file)) continue
            candidates++; candidateBytes += length
            if (clean) { check(valid()) { "连接已变更，操作取消" }; if (file.delete()) { removed++; removedBytes += length } else failed++ }
        }
        return JSONObject().put("category", JSONObject().put("id", id).put("label", label).put("files", files).put("bytes", bytes)
            .put("cleanableFiles", candidates).put("cleanableBytes", candidateBytes)).put("removedFiles", removed).put("removedBytes", removedBytes)
            .put("releasedBytes", JSONObject.NULL).put("failedFiles", failed) // Android cannot reliably report filesystem block reclamation.
    }
}

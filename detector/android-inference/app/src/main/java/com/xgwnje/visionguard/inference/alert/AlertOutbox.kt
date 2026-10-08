package com.xgwnje.visionguard.inference.alert

import com.google.gson.Gson
import java.io.File
import java.time.Instant

/**
 * 报警持久发件箱：入队即写盘，只有收到统一服务 `alert-ack` 才移除。
 *
 * 与 Windows `Services/AlertOutbox.cs` 语义一致：
 * - 按 服务 / 账号 / 设备 分区（由调用方给出分区后的文件路径）；
 * - 上限 500 条，超出丢最旧；
 * - 存活判定要求 `expiresAt > 现在`，过期项在快照时惰性清理并落盘；
 * - 文件损坏时整文件改名为 `.corrupt-<时间戳>` 并报警告，不静默丢数据。
 */
class AlertOutbox(
    private val file: File,
    private val nowMs: () -> Long,
    private val maxEntries: Int = MAX_ENTRIES
) {
    /** 内部记录结构；字段名即磁盘格式，改动需要同时迁移旧文件。 */
    data class Record(
        val alertId: String,
        val payloadJson: String,
        val expiresAtMs: Long,
        val queuedAtMs: Long
    )

    /** 损坏隔离后的提示信息，供界面与日志显示；正常时为 null。 */
    var recoveryWarning: String? = null
        private set

    private val gson = Gson()
    private var records: List<Record> = load()

    /** 入队；同一 alertId 覆盖旧记录，超出上限丢弃最旧。 */
    @Synchronized
    fun enqueue(alertId: String, payloadJson: String, expiresAtMs: Long) {
        val entry = Record(alertId, payloadJson, expiresAtMs, nowMs())
        val merged = ArrayList<Record>(records.size + 1)
        merged.addAll(records.filter { it.alertId != alertId })
        merged.add(entry)
        records = if (merged.size > maxEntries) merged.subList(merged.size - maxEntries, merged.size).toList() else merged
        persist()
    }

    /** 当前仍有效的记录，按入队顺序；顺带清理过期项并落盘。 */
    @Synchronized
    fun snapshot(): List<Record> {
        val now = nowMs()
        val alive = records.filter { it.expiresAtMs > now }
        if (alive.size != records.size) {
            records = alive
            persist()
        }
        return alive
    }

    /** 收到服务端确认或确定拒收原因时移除；返回是否真的移除过。 */
    @Synchronized
    fun acknowledge(alertId: String): Boolean {
        val alive = records.filter { it.alertId != alertId }
        if (alive.size == records.size) return false
        records = alive
        persist()
        return true
    }

    @Synchronized
    fun size(): Int = records.size

    private fun load(): List<Record> {
        if (!file.isFile) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyList()
            else gson.fromJson(text, Array<Record>::class.java)?.toList() ?: emptyList()
        } catch (error: Exception) {
            quarantine(error)
            emptyList()
        }
    }

    /** 原子写：先写 `.tmp`，再替换目标文件。 */
    private fun persist() {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(gson.toJson(records), Charsets.UTF_8)
        if (file.exists() && !file.delete()) throw java.io.IOException("无法替换发件箱文件")
        if (!temp.renameTo(file)) throw java.io.IOException("无法写入发件箱文件")
    }

    private fun quarantine(error: Exception) {
        val stamp = Instant.ofEpochMilli(nowMs()).toString().replace(':', '-').replace('.', '-')
        val broken = File(file.parentFile, file.name + ".corrupt-$stamp")
        recoveryWarning = if (file.renameTo(broken)) {
            "报警发件箱文件损坏（${error.message ?: "解析失败"}），已隔离为 ${broken.name}"
        } else {
            "报警发件箱文件损坏（${error.message ?: "解析失败"}），且无法隔离，发送将暂停"
        }
    }

    companion object {
        const val MAX_ENTRIES = 500
    }
}

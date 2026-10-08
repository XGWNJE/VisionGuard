package com.xgwnje.visionguard.inference.alert

import java.io.File

/**
 * 报警截图的本机缓存：收到 `alert-ack` 之后才按 alertId 读出并发送。
 *
 * 与 Windows 截图缓存的差别只在体积口径：Android 端按 条目数 / 总量 / 天数 三个边界清理，
 * 文件名必须是安全字符集，避免路径穿越。
 */
class AlertSnapshotStore(private val directory: File) {

    fun save(alertId: String, jpeg: ByteArray): Boolean {
        if (!SAFE_NAME.matches(alertId)) return false
        directory.mkdirs()
        val target = File(directory, "$alertId$EXTENSION")
        val temporary = File(directory, "$alertId$EXTENSION.tmp")
        return runCatching {
            temporary.writeBytes(jpeg)
            if (target.exists() && !target.delete()) return false
            temporary.renameTo(target)
        }.getOrDefault(false)
    }

    fun read(alertId: String): ByteArray? {
        if (!SAFE_NAME.matches(alertId)) return null
        val target = File(directory, "$alertId$EXTENSION")
        if (!target.isFile) return null
        return runCatching { target.readBytes() }.getOrNull()
    }

    fun delete(alertId: String): Boolean =
        SAFE_NAME.matches(alertId) && File(directory, "$alertId$EXTENSION").delete()

    /** 按天数、条目数与总字节数清理旧截图；返回删除数量。 */
    fun maintain(nowMs: Long, maxAgeMs: Long = MAX_AGE_MS, maxEntries: Int = MAX_ENTRIES, maxBytes: Long = MAX_BYTES): Int {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) } ?: return 0
        var removed = 0
        for (file in files) {
            if (nowMs - file.lastModified() > maxAgeMs && file.delete()) removed++
        }
        val remaining = (directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) } ?: return removed)
            .sortedByDescending { it.lastModified() }
        var bytes = 0L
        remaining.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= maxEntries || bytes > maxBytes) {
                if (file.delete()) removed++
            }
        }
        return removed
    }

    companion object {
        private const val EXTENSION = ".jpg"
        private val SAFE_NAME = Regex("^[A-Za-z0-9_-]{8,128}$")

        /** 与 Windows 一致保留 7 天；条目与总量边界按手机存储实际承受能力收紧。 */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_ENTRIES = 200
        const val MAX_BYTES = 100L * 1024 * 1024
    }
}

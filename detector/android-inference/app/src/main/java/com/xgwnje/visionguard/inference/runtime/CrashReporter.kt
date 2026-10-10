package com.xgwnje.visionguard.inference.runtime

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃与异常退出记录。
 *
 * 两条来源互补：
 * 1. 未捕获异常（Java / Kotlin）：装默认处理器，把异常与当时的运行摘要写进本机文件；
 * 2. 系统记录的进程退出原因（API 30+）：覆盖 Java 崩溃抓不到的 native 崩溃、ANR 与被系统回收，
 *    native 崩溃还能读到 tombstone，摘出与本项目 so 相关的帧。
 *
 * 记录只留在本机 `files/crashes/`，供「设置 → 维护 → 崩溃与退出记录」查看与导出反馈。
 */
object CrashReporter {

    private const val TAG = "VisionGuardCrash"
    private const val MAX_FILES = 20
    private const val TRACE_LINES = 60

    private var installed = false
    private val stampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 记录未捕获异常；返回写入的文件。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "crash handler installed")
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = File(context.filesDir, "crashes").apply { mkdirs() }
        val file = File(dir, "crash-${stampFormat.format(Date())}.txt")
        val text = buildString {
            appendLine("时间：${Date()}")
            appendLine("类型：未捕获异常")
            appendLine("线程：${thread.name}")
            appendLine("异常：${throwable.javaClass.name}: ${throwable.message}")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.SUPPORTED_ABIS.firstOrNull()} / API ${Build.VERSION.SDK_INT}")
            appendLine("进程：${android.os.Process.myPid()}")
            appendLine("── 堆栈 ──")
            appendLine(Log.getStackTraceString(throwable))
        }
        file.writeText(text)
        Log.e(TAG, "uncaught exception recorded: ${file.name}")
        trim(dir)
    }

    /** 一条退出记录：本地崩溃文件或系统退出原因。 */
    data class Entry(
        val time: Long,
        val kind: String,
        val summary: String,
        val detail: String?
    )

    /** 汇总最近的崩溃与异常退出，按时间倒序。 */
    fun history(context: Context, limit: Int = 20): List<Entry> {
        val entries = mutableListOf<Entry>()

        val crashDir = File(context.filesDir, "crashes")
        crashDir.listFiles()?.sortedByDescending { it.name }?.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull().orEmpty()
            val time = runCatching { stampFormat.parse(file.name.removePrefix("crash-").removeSuffix(".txt"))?.time }
                .getOrNull() ?: file.lastModified()
            val first = text.lineSequence().firstOrNull { it.startsWith("异常：") }?.removePrefix("异常：").orEmpty()
            entries += Entry(time, "未捕获异常", first.ifEmpty { file.name }, text.take(2000))
        }

        entries += exitReasons(context)

        return entries.sortedByDescending { it.time }.take(limit)
    }

    private fun exitReasons(context: Context): List<Entry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val entries = mutableListOf<Entry>()
        runCatching {
            manager.getHistoricalProcessExitReasons(null, 0, 12).forEach { info ->
                val kind = when (info.reason) {
                    ApplicationExitReasons.CRASH -> "进程崩溃（Java）"
                    ApplicationExitReasons.CRASH_NATIVE -> "进程崩溃（native）"
                    ApplicationExitReasons.ANR -> "无响应（ANR）"
                    ApplicationExitReasons.LOW_MEMORY -> "被系统回收（内存）"
                    ApplicationExitReasons.EXIT_SELF -> "主动退出"
                    ApplicationExitReasons.SIGNALED -> "被信号终止"
                    ApplicationExitReasons.OTHER -> "其他退出"
                    else -> "退出原因 ${info.reason}"
                }
                entries += Entry(info.timestamp, kind, info.description?.take(160) ?: kind, readTrace(info))
            }
        }.onFailure { Log.w(TAG, "exit reasons unavailable: ${it.message}") }
        return entries
    }

    /** 摘 tombstone / ANR 跟踪里与本项目相关的部分，避免整份文件进界面。 */
    private fun readTrace(info: ApplicationExitInfo): String? = runCatching {
        val stream = info.traceInputStream ?: return null
        val lines = stream.bufferedReader().use { it.readLines() }
        if (lines.isEmpty()) return null
        val interesting = lines.filter {
            it.contains("libonnxruntime") || it.contains("visionguard") ||
                it.startsWith("signal ") || it.contains("Abort message") || it.contains("backtrace:")
        }
        (if (interesting.size >= 6) interesting else lines.take(TRACE_LINES))
            .take(TRACE_LINES)
            .joinToString("\n")
    }.getOrNull()

    /** 把历史合并成一份可反馈的文本，写入 files/diagnostics/。 */
    fun export(context: Context, entries: List<Entry>): String? = runCatching {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val file = File(dir, "crashes-${stampFormat.format(Date())}.txt")
        val text = buildString {
            appendLine("VisionGuard 崩溃与退出记录")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
            appendLine("条数：${entries.size}")
            entries.forEach { entry ->
                appendLine("────────")
                appendLine("时间：${Date(entry.time)}")
                appendLine("类型：${entry.kind}")
                appendLine("摘要：${entry.summary}")
                entry.detail?.let { appendLine(it) }
            }
        }
        file.writeText(text)
        file.absolutePath
    }.getOrNull()

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.name }.orEmpty()
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /** 系统退出原因常量：API 30 起可用，单独列出避免直接引用新常量。 */
    private object ApplicationExitReasons {
        const val CRASH = 4
        const val CRASH_NATIVE = 5
        const val ANR = 6
        const val LOW_MEMORY = 3
        const val EXIT_SELF = 1
        const val SIGNALED = 2
        const val OTHER = 0
    }
}

package com.xgwnje.visionguard.notifier

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 持久化诊断日志：在写 logcat 的同时，把关键运行事件落到应用私有目录文件，
 * 供真机长测后导出分析（真机不便常驻 adb 调试）。
 *
 * - 文件：filesDir/logs/vg_notifier.log，超过 1MB 滚动为 vg_notifier.log.old（总量 ≤2MB）
 * - 每条立即 flush：进程随时可能被厂商省电策略强杀，尾部日志不能丢
 * - 隐私：不写通知正文，只记录包名与处理结果
 */
object NotificationLogger {

    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "vg_notifier.log"
    private const val LOG_FILE_OLD = "vg_notifier.log.old"
    private const val MAX_FILE_BYTES = 1 * 1024 * 1024L  // 1MB 滚动

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val exportHeaderTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var logFile: File? = null
    private var processMarkerWritten = false

    private fun ensureInit(context: Context) {
        if (logFile == null) {
            val dir = File(context.applicationContext.filesDir, LOG_DIR)
            dir.mkdirs()
            logFile = File(dir, LOG_FILE)
        }
    }

    fun d(context: Context, tag: String, msg: String) = write(context, 'D', tag, msg, null)
    fun i(context: Context, tag: String, msg: String) = write(context, 'I', tag, msg, null)
    fun w(context: Context, tag: String, msg: String) = write(context, 'W', tag, msg, null)
    fun e(context: Context, tag: String, msg: String, tr: Throwable? = null) = write(context, 'E', tag, msg, tr)

    private fun write(context: Context, level: Char, tag: String, msg: String, tr: Throwable?) {
        when (level) {
            'D' -> Log.d(tag, msg)
            'I' -> Log.i(tag, msg)
            'W' -> Log.w(tag, msg)
            else -> Log.e(tag, msg, tr)
        }
        try {
            synchronized(lock) {
                ensureInit(context)
                val file = logFile ?: return
                if (file.length() > MAX_FILE_BYTES) {
                    val old = File(file.parentFile, LOG_FILE_OLD)
                    file.renameTo(old)
                }
                val sb = StringBuilder()
                if (!processMarkerWritten) {
                    processMarkerWritten = true
                    sb.append(processMarker(context))
                }
                sb.append(timeFormat.format(Date()))
                    .append(' ').append(Process.myPid())
                    .append(' ').append(level).append('/').append(tag)
                    .append(": ").append(msg).append('\n')
                if (tr != null) {
                    sb.append(Log.getStackTraceString(tr))
                }
                file.appendText(sb.toString())
            }
        } catch (e: Exception) {
            Log.e("NotificationLogger", "写日志文件失败", e)
        }
    }

    private fun processMarker(context: Context): String {
        return "===== 进程启动 pid=${Process.myPid()} " +
                "app=${context.packageName} v${getVersionName(context)} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} " +
                "time=${exportHeaderTimeFormat.format(Date())} =====\n"
    }

    private fun getVersionName(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } ?: "?"
        } catch (e: Exception) { "?" }
    }

    /**
     * 导出诊断日志到 cacheDir，返回拼接好的文件（诊断头 + old + 当前日志）。
     * 由调用方通过 FileProvider 分享。
     */
    fun export(context: Context): File = synchronized(com.xgwnje.visionguard.account.TemporaryCache.exportLock) {
        val appContext = context.applicationContext
        val prefs = SharedPreferencesHelper(appContext)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager

        val header = buildString {
            append("===== VisionGuard 诊断信息 =====\n")
            append("导出时间: ").append(exportHeaderTimeFormat.format(Date())).append('\n')
            append("应用版本: ").append(getVersionName(appContext)).append('\n')
            append("设备: ").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE)
                .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("电池白名单: ").append(powerManager.isIgnoringBatteryOptimizations(appContext.packageName)).append('\n')
            append("节点开关: ").append(com.xgwnje.visionguard.notifier.node.NotificationNodeSettings(appContext).enabled).append('\n')
            append("未确认报警: ").append(prefs.getActiveAlert()?.keyword ?: "无").append('\n')
            append("==========================\n\n")
        }

        val exportFile = com.xgwnje.visionguard.account.TemporaryCache.reserveExport(appContext)
        synchronized(lock) {
            ensureInit(appContext)
            val dir = File(appContext.filesDir, LOG_DIR)
            exportFile.writeText(header)
            File(dir, LOG_FILE_OLD).takeIf { it.exists() }?.let {
                exportFile.appendText("----- vg_notifier.log.old -----\n")
                exportFile.appendText(it.readText())
                exportFile.appendText("\n")
            }
            logFile?.takeIf { it.exists() }?.let {
                exportFile.appendText("----- vg_notifier.log -----\n")
                exportFile.appendText(it.readText())
            }
        }
        check(exportFile.length() <= 3L * 1024 * 1024) { exportFile.delete(); "诊断导出超过 3 MB 上限" }
        exportFile
    }
}

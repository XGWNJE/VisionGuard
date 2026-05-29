package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ AutoUpdater.kt                                          │
// │ 角色：自动更新检查与下载                                  │
// │ 职责：检查新版本 → 通知/对话框 → 下载 APK → 安装         │
// └─────────────────────────────────────────────────────────┘

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.MainActivity
import com.xgwnje.visionguard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "VG_AutoUpdater"
private const val PLATFORM = "android-detector"
private const val UPDATE_NOTIF_ID = 2001

data class UpdateInfo(val version: String, val downloadUrl: String)

object AutoUpdater {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 仅检查更新，返回 UpdateInfo 或 null */
    suspend fun checkUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "${AppConstants.SERVER_URL}/api/update?platform=$PLATFORM&version=${AppConstants.VERSION}"
            val request = Request.Builder().url(url).header("X-API-Key", AppConstants.API_KEY).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return@withContext null
            if (!json.optBoolean("hasUpdate", false)) return@withContext null

            val latestVersion = json.optString("latestVersion", "")
            val downloadUrl = json.optString("downloadUrl", "")
            if (downloadUrl.isEmpty()) return@withContext null

            val fullUrl = if (downloadUrl.startsWith("http", true)) downloadUrl
                else AppConstants.SERVER_URL + downloadUrl

            Log.i(TAG, "发现新版本 $latestVersion (当前 ${AppConstants.VERSION})")
            UpdateInfo(latestVersion, fullUrl)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.message}")
            null
        }
    }

    /** 自动检查（Service 启动时）：有更新则发通知 */
    suspend fun checkAndNotify(context: Context) {
        val info = checkUpdate(context) ?: return
        showUpdateNotification(context, info)
    }

    private fun showUpdateNotification(context: Context, info: UpdateInfo) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigateTo", "settings")
            putExtra("showUpdate", true)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = NotificationHelper.FOREGROUND_CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentTitle("VisionGuard 检测端更新")
            .setContentText("发现新版本 ${info.version}，点击查看")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(UPDATE_NOTIF_ID, notification)
    }

    /** 下载 APK（公开，供手动触发） */
    @Suppress("DEPRECATION")
    fun downloadApk(context: Context, url: String, version: String) {
        val fileName = "VisionGuard-Detector-v$version.apk"
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloadId = -1L

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                c.unregisterReceiver(this)
                installApk(c, dm, downloadId, fileName)
            }
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("VisionGuard 检测端更新")
                setDescription("正在下载新版本 $version…")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("X-API-Key", AppConstants.API_KEY)
            }

            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED
            )

            downloadId = dm.enqueue(request)
            Log.i(TAG, "APK 下载已开始: id=$downloadId file=$fileName")
        } catch (e: Exception) {
            Log.w(TAG, "下载初始化失败: ${e.message}")
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun installApk(context: Context, dm: DownloadManager, downloadId: Long, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context,
                        "请在系统设置中允许 VisionGuard「安装未知应用」，\n再到「下载」目录点击 $fileName 安装。",
                        Toast.LENGTH_LONG).show()
                }
                Log.w(TAG, "无 REQUEST_INSTALL_PACKAGES 权限，安装取消")
                return
            }
        }

        try {
            val uri = dm.getUriForDownloadedFile(downloadId)
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    Log.i(TAG, "已触发 APK 安装")
                    return
                }
                Log.w(TAG, "没有可用的包安装器（可能是定制系统拦截）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动安装失败: ${e.message}")
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context,
                "安装器未响应，请打开文件管理器 →「下载」→ 点击 $fileName 手动安装",
                Toast.LENGTH_LONG).show()
        }
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "无法打开下载列表: ${e.message}")
        }
    }
}

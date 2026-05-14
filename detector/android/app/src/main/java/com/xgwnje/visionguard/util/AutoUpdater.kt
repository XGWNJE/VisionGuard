package com.xgwnje.visionguard.util

// ┌─────────────────────────────────────────────────────────┐
// │ AutoUpdater.kt                                          │
// │ 角色：自动更新检查与下载                                  │
// │ 职责：启动时检查新版本 → 下载 APK → 调用系统安装器覆盖安装 │
// └─────────────────────────────────────────────────────────┘

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "VG_AutoUpdater"
private const val CURRENT_VERSION = AppConstants.VERSION
private const val PLATFORM = "android-detector"

object AutoUpdater {

    private val http = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()

    /** 检查并执行更新（在 Service 启动时调用） */
    suspend fun checkAndUpdate(context: Context) = withContext(Dispatchers.IO) {
        try {
            val url = "${AppConstants.SERVER_URL}/api/update?platform=$PLATFORM&version=${AppConstants.VERSION}"
            val request = Request.Builder().url(url).header("X-API-Key", AppConstants.API_KEY).build()
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) return@withContext

            val body = response.body?.string() ?: return@withContext
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return@withContext
            if (!json.optBoolean("hasUpdate", false)) return@withContext

            val latestVersion = json.optString("latestVersion", "")
            val downloadUrl = json.optString("downloadUrl", "")
            if (downloadUrl.isEmpty()) return@withContext

            Log.i(TAG, "发现新版本 $latestVersion (当前 ${AppConstants.VERSION})")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "发现新版本 $latestVersion，正在下载…", Toast.LENGTH_LONG).show()
            }

            downloadApk(context, downloadUrl, latestVersion)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.message}")
        }
    }

    private fun downloadApk(context: Context, url: String, version: String) {
        // 先注册接收器，再 enqueue（防止竞态条件导致错过广播）
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var downloadId = -1L

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                c.unregisterReceiver(this)
                installApk(c, dm, downloadId)
            }
        }

        try {
            val fileName = "VisionGuard-Detector-v$version.apk"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("VisionGuard 检测端更新")
                setDescription("正在下载新版本 $version…")
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                addRequestHeader("X-API-Key", AppConstants.API_KEY)
            }

            // 先注册接收器（避免 miss broadcast）
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // 再 enqueue（API 文档保证此时注册已完成）
            downloadId = dm.enqueue(request)
            Log.i(TAG, "APK 下载已开始: id=$downloadId")
        } catch (e: Exception) {
            Log.w(TAG, "下载初始化失败: ${e.message}")
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private fun installApk(context: Context, dm: DownloadManager, downloadId: Long) {
        try {
            val uri = dm.getUriForDownloadedFile(downloadId) ?: run {
                Log.w(TAG, "无法获取 APK URI")
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "已触发 APK 安装")
        } catch (e: Exception) {
            Log.w(TAG, "安装 APK 失败: ${e.message}")
        }
    }
}

package com.xgwnje.visionguard_android.util

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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard_android.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "VG_AutoUpdater"
private const val PLATFORM = "android-receiver"

object AutoUpdater {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 检查并执行更新（在 Service 启动时调用） */
    suspend fun checkAndUpdate(context: Context) = withContext(Dispatchers.IO) {
        try {
            val version = getVersion(context)
            val url = "${AppConstants.SERVER_URL}/api/update?platform=$PLATFORM&version=$version"
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

            Log.i(TAG, "发现新版本 $latestVersion (当前 $version)")

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "发现新版本 $latestVersion，正在下载…", Toast.LENGTH_LONG).show()
            }

            downloadApk(context, downloadUrl, latestVersion)
        } catch (e: Exception) {
            Log.w(TAG, "检查更新失败: ${e.message}")
        }
    }

    /**
     * 获取当前版本号。
     * 测试时可通过 SharedPreferences 伪装旧版本触发更新：
     *   在 Device Explorer 中编辑 shared_prefs/vg_debug.xml，添加 force_version="0.0.0"
     * 生产环境该 key 不存在 → 正常返回 AppConstants.VERSION
     */
    private fun getVersion(context: Context): String {
        val prefs = context.getSharedPreferences("vg_debug", Context.MODE_PRIVATE)
        return prefs.getString("force_version", null) ?: AppConstants.VERSION
    }

    @Suppress("DEPRECATION")
    private fun downloadApk(context: Context, url: String, version: String) {
        val fileName = "VisionGuard-Receiver-v$version.apk"
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
                setTitle("VisionGuard 接收端更新")
                setDescription("正在下载新版本 $version…")
                // 使用公共 Downloads 目录，确保用户可在文件管理器中手动找到并安装
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

    private fun installApk(context: Context, dm: DownloadManager, downloadId: Long, fileName: String) {
        // 1. 检查安装未知应用权限 (Android 8+)
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

        // 2. 尝试通过 DownloadManager content URI 安装
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

        // 3. 回退：提示用户并打开系统下载列表
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

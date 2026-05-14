package com.xgwnje.visionguard.service

// ┌─────────────────────────────────────────────────────────┐
// │ ServerPushService.kt                                    │
// │ 角色：服务器通信推送服务                                 │
// │ 职责：WS 连接管理、报警上报、命令回执、网络监听          │
// └─────────────────────────────────────────────────────────┘

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.util.Log
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.data.model.AlertEvent
import com.xgwnje.visionguard.data.model.AlertMeta
import com.xgwnje.visionguard.util.NtpSync
import com.xgwnje.visionguard.data.model.Bbox
import com.xgwnje.visionguard.data.model.ServerDetection
import com.xgwnje.visionguard.data.model.WsCommandMessage
import com.xgwnje.visionguard.data.model.WsSetConfigMessage
import com.xgwnje.visionguard.data.remote.WebSocketClient
import com.xgwnje.visionguard.data.remote.WsState
import com.xgwnje.visionguard.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val TAG = "VG_ServerPush"

class ServerPushService(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope
) {

    val wsClient = WebSocketClient()

    // 报警本地队列（WS 断连时缓存，恢复后批量重发）
    private val pendingAlerts = java.util.concurrent.ConcurrentLinkedQueue<PendingAlert>()
    private data class PendingAlert(
        val alertId: String,
        val detections: List<com.xgwnje.visionguard.data.model.Detection>,
        val timestampMs: Long,
        val timings: Map<String, Long>
    )

    val connectionState: StateFlow<WsState>
        get() = wsClient.connectionState

    val onCommand: SharedFlow<WsCommandMessage>
        get() = wsClient.onCommand

    val onSetConfig: SharedFlow<WsSetConfigMessage>
        get() = wsClient.onSetConfig

    private val networkMonitor = NetworkMonitor(context)
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        // 注入网络检测器
        wsClient.networkChecker = { cm.activeNetwork != null }
    }

    /** 连接 WebSocket 服务器 */
    fun connect() {
        scope.launch {
            try {
                val deviceId = settingsRepo.ensureDeviceId()
                val deviceName = settingsRepo.getDeviceName()
                Log.i(TAG, "正在连接服务器: ${AppConstants.SERVER_URL}, deviceId=$deviceId, deviceName=$deviceName")
                wsClient.connect(AppConstants.SERVER_URL, AppConstants.API_KEY, deviceId, deviceName)

                // 注册网络监听
                networkMonitor.register(
                    onAvailable = {
                        Log.i(TAG, "网络恢复，通知 WS 客户端")
                        wsClient.onNetworkAvailable()
                    },
                    onLost = {
                        Log.w(TAG, "网络断开，通知 WS 客户端")
                        wsClient.onNetworkLost()
                    }
                )

                // 监听连接状态，恢复后重发队列中的报警
                scope.launch {
                    wsClient.connectionState.collect { state ->
                        if (state == WsState.CONNECTED) {
                            drainPendingAlerts()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接服务器失败", e)
            }
        }
    }

    /** 断开 WebSocket 连接 */
    fun disconnect() {
        Log.i(TAG, "主动断开服务器连接")
        wsClient.disconnect()
        networkMonitor.unregister()
    }

    /**
     * v4.0.0: 推送报警（WS，内嵌截图 Base64）。
     *
     * @param alertId 报警 ID
     * @param detections 检测结果
     * @param timestampMs 报警发生时间戳（毫秒）
     * @param timings 链路耗时统计
     * @param bitmap 报警帧截图（可空，断连入队时跳过）
     */
    fun pushAlert(
        alertId: String,
        detections: List<com.xgwnje.visionguard.data.model.Detection>,
        timestampMs: Long,
        timings: Map<String, Long> = emptyMap(),
        bitmap: Bitmap? = null
    ) {
        if (wsClient.connectionState.value != WsState.CONNECTED) {
            while (pendingAlerts.size >= 50) pendingAlerts.poll()
            pendingAlerts.add(PendingAlert(alertId, detections, timestampMs, timings))
            Log.w(TAG, "WS 未连接，报警入队 (队列 ${pendingAlerts.size}/50): $alertId")
            return
        }
        doPushAlert(alertId, detections, timestampMs, timings, bitmap)
    }

    private fun doPushAlert(
        alertId: String,
        detections: List<com.xgwnje.visionguard.data.model.Detection>,
        timestampMs: Long,
        timings: Map<String, Long>,
        bitmap: Bitmap? = null
    ) {
        scope.launch {
            val deviceId = settingsRepo.ensureDeviceId()
            val deviceName = settingsRepo.getDeviceName()
            val timestamp = isoFormat(timestampMs)
            val meta = com.xgwnje.visionguard.data.model.AlertMeta(
                deviceId = deviceId,
                deviceName = deviceName,
                timestamp = timestamp,
                detections = detections.map {
                    com.xgwnje.visionguard.data.model.ServerDetection(
                        label = it.label,
                        confidence = it.confidence,
                        bbox = com.xgwnje.visionguard.data.model.Bbox(it.bbox.left, it.bbox.top, it.bbox.width(), it.bbox.height())
                    )
                }
            )
            val gson = com.google.gson.Gson()
            val msg = mutableMapOf<String, Any>(
                "type" to "alert",
                "alertId" to alertId,
                "deviceId" to deviceId,
                "deviceName" to deviceName,
                "timestamp" to timestamp,
                "detections" to gson.fromJson(gson.toJson(meta.detections), List::class.java),
                "timings" to timings,
                "capturedAt" to isoFormat(NtpSync.now())
            )
            // 协议分离: alert 元数据先发(最高优先级,不含截图)
            val sent = wsClient.sendRawJson(gson.toJson(msg))
            if (sent) {
                Log.i(TAG, "报警已推送(WS): alertId=$alertId targets=${detections.size}")
                // 截图独立异步推送(OkHttp WS send 内部串行队列,后发安全)
                if (bitmap != null) {
                    launch { doPushScreenshotData(alertId, deviceId, bitmap, gson) }
                }
            } else {
                Log.w(TAG, "报警推送失败(WS): alertId=$alertId")
            }
        }
    }

    private fun doPushScreenshotData(
        alertId: String,
        deviceId: String,
        bitmap: Bitmap,
        gson: com.google.gson.Gson
    ) {
        try {
            val jpegBytes = bmpToJpeg(bitmap)
            val base64 = android.util.Base64.encodeToString(jpegBytes, android.util.Base64.NO_WRAP)
            val msg = mapOf(
                "type" to "screenshot-data",
                "alertId" to alertId,
                "deviceId" to deviceId,
                "imageBase64" to base64
            )
            val sent = wsClient.sendRawJson(gson.toJson(msg))
            if (sent) {
                Log.i(TAG, "截图已推送(WS): alertId=$alertId size=${base64.length}")
            } else {
                Log.w(TAG, "截图推送失败(WS): alertId=$alertId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "截图编码异常 alertId=$alertId: ${e.message}")
        }
    }

    private fun drainPendingAlerts() {
        var count = 0
        while (true) {
            val pending = pendingAlerts.poll() ?: break
            if (System.currentTimeMillis() - pending.timestampMs > 5 * 60 * 1000L) continue
            doPushAlert(pending.alertId, pending.detections, pending.timestampMs, pending.timings)
            count++
            if (count >= 50) break
        }
        if (count > 0) Log.i(TAG, "已从队列重发 $count 条报警")
    }

    /**
     * Bitmap → JPEG，与 Windows 端对齐：
     * - 宽度超过 960px 时等比缩放
     * - JPEG quality = 65（平衡带宽与画质）
     */
    private fun bmpToJpeg(bitmap: Bitmap): ByteArray {
        val maxW = 960
        val toCompress = if (bitmap.width > maxW) {
            val ratio = maxW.toFloat() / bitmap.width
            val newH = (bitmap.height * ratio).toInt()
            Bitmap.createScaledBitmap(bitmap, maxW, newH, true)
        } else bitmap

        val stream = ByteArrayOutputStream()
        toCompress.compress(Bitmap.CompressFormat.JPEG, 65, stream)
        val bytes = stream.toByteArray()

        if (toCompress !== bitmap) toCompress.recycle()
        return bytes
    }

    /** 发送命令回执 */
    fun sendCommandAck(command: String, success: Boolean, reason: String = "") {
        val sent = wsClient.sendCommandAck(command, success, reason)
        if (sent) {
            Log.i(TAG, "命令回执已发送: $command success=$success")
        } else {
            Log.w(TAG, "命令回执发送失败: WS 未连接")
        }
    }

    /** ISO 8601 格式当前时间（带本地时区偏移，与 Windows 端对齐） */
    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date())
    }

    /** 将毫秒时间戳转为 ISO 8601 字符串（与 Windows 端 alert.Timestamp 对齐） */
    private fun isoFormat(timestampMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        return sdf.format(Date(timestampMs))
    }
}

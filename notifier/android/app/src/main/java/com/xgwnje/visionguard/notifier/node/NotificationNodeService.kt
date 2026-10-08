package com.xgwnje.visionguard.notifier.node

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.notifier.NotificationLogger
import com.xgwnje.visionguard.notifier.MainActivity
import com.xgwnje.visionguard.notifier.AlarmPlaybackService
import com.xgwnje.visionguard.notifier.SharedPreferencesHelper
import com.xgwnje.visionguard.account.AccountStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NodeState(val status: String = "已停止", val connected: Boolean = false, val scope: String = "由控制台分配",
                     val received: Int = 0, val lastResponse: Long = 0, val timeZone: String? = null)

/** 独立前台连接；收件入持久化队列，声音仍由现有报警引擎执行。 */
class NotificationNodeService : Service() {
    companion object {
        const val NOTIFICATION_ID = 718
        private val mutableState = MutableStateFlow(NodeState())
        val state = mutableState.asStateFlow()
        fun start(context: android.content.Context) = ContextCompat.startForegroundService(context, Intent(context, NotificationNodeService::class.java))
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var account: AccountStore
    private var currentToken = ""
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    private lateinit var settings: NotificationNodeSettings
    private lateinit var alarms: SharedPreferencesHelper
    private lateinit var remoteSound: RemoteSoundControl
    private val remoteConfigMutex = kotlinx.coroutines.sync.Mutex()
    private var socket: WebSocket? = null
    private var generation = 0
    private var authenticated = false
    private var stopped = false
    private var terminal = false
    private var nextAttempt = 0L
    private var retryMs = 1_000L
    private var connectedAt = 0L
    private var lastResponse = 0L
    private val recovery = ConnectionRecovery()
    private var probeId: String? = null
    private var outageId: String? = null
    private var outageAccepted = false
    private var lastTickAt = 0L
    private lateinit var connectivity: ConnectivityManager
    private lateinit var networks: DefaultNetworkChanges<Network>
    private var networkCallbackRegistered = false
    private var underlyingCallbackRegistered = false
    private val underlyingNetworks = mutableSetOf<Network>()
    private fun networkRestored(reason: String) {
        if (stopped || terminal) return
        diagnostic(reason)
        recovery.networkChanged(SystemClock.elapsedRealtime())
        probeId = null
        reconnect("网络已恢复，正在重新连接", immediate = true)
        connect()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (stopped || !networks.available(network) || terminal) return
            // A restored or replaced route must not wait for an old failure's 30-second backoff.
            networkRestored("default-network-available")
        }
        override fun onLost(network: Network) {
            if (!stopped && networks.lost(network)) diagnostic("default-network-lost")
        }
    }
    // A VPN's default Network can stay available while its physical route is disconnected.
    // Observe route restoration only; all sockets still use the system default (including VPN).
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!stopped && underlyingNetworks.add(network) && !authenticated) networkRestored("underlying-network-available")
        }
        override fun onLost(network: Network) {
            if (!stopped && underlyingNetworks.remove(network)) diagnostic("underlying-network-lost")
        }
    }
    private fun diagnostic(event: String) {
        val power = getSystemService(android.os.PowerManager::class.java)
        NotificationLogger.i(this, "NotificationNode", "$event generation=$generation authenticated=$authenticated interactive=${power.isInteractive} idle=${power.isDeviceIdleMode}")
    }
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            val now = SystemClock.elapsedRealtime()
            if (lastTickAt != 0L && now - lastTickAt > 10_000) diagnostic("scheduler-gap elapsedMs=${now - lastTickAt}")
            lastTickAt = now
            if (!terminal) when (recovery.tick(now, socket != null, authenticated)) {
                ConnectionRecovery.Action.PROBE -> {
                    probeId = UUID.randomUUID().toString()
                    mutableState.value = mutableState.value.copy(status = "连接异常，正在主动确认", connected = false)
                }
                ConnectionRecovery.Action.RECONNECT -> reconnect("正在重新连接确认服务状态")
                ConnectionRecovery.Action.CONFIRM_FAILURE -> { confirmOutage(); reconnect("已确认连接失败，等待重试") }
                ConnectionRecovery.Action.NONE -> Unit
            }
            if (!terminal && socket == null && now >= nextAttempt) connect()
            if (authenticated && socket?.send(JSONObject().put("type", "heartbeat-notifier").put("deviceId", settings.read().deviceId)
                    .put("capabilities", org.json.JSONArray(listOf("alarm-control", "sound-config", "audio-library", "request-correlation", "cache-maintenance")))
                    .put("remoteSettings", remoteSound.snapshot())
                    .apply { probeId?.let { put("probeId", it) } }.toString()) != true) transportFailed("心跳发送失败")
            handler.postDelayed(this, 3_000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        account = AccountStore.get(this)
        settings = NotificationNodeSettings(this)
        mutableState.value = NodeState(timeZone = settings.timeZone)
        alarms = SharedPreferencesHelper(this)
        remoteSound = RemoteSoundControl(this, alarms)
        outageId = alarms.serviceOutageId(); outageAccepted = outageId != null
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("notification-node", "通知节点连接", NotificationManager.IMPORTANCE_LOW))
        val launch = PendingIntent.getActivity(this, 718, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, "notification-node")
            .setColor(ContextCompat.getColor(this, com.xgwnje.visionguard.notifier.R.color.md_theme_primary))
            .setSmallIcon(com.xgwnje.visionguard.notifier.R.drawable.ic_lucide_bell).setContentTitle("通知节点")
            .setContentText("后台接收统一服务报警").setContentIntent(launch).setOngoing(true).build())
        lastResponse = SystemClock.elapsedRealtime()
        diagnostic("service-created")
        connectivity = getSystemService(ConnectivityManager::class.java)
        networks = DefaultNetworkChanges(connectivity.activeNetwork)
        @Suppress("DEPRECATION")
        connectivity.allNetworks.forEach { network ->
            connectivity.getNetworkCapabilities(network)?.let { caps ->
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) underlyingNetworks.add(network)
            }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(networkCallback, handler)
            networkCallbackRegistered = true
        }.onFailure { diagnostic("network-observer-unavailable errorType=${it.javaClass.simpleName}") }
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), underlyingCallback, handler)
            underlyingCallbackRegistered = true
        }.onFailure { diagnostic("route-observer-unavailable errorType=${it.javaClass.simpleName}") }
        serviceScope.launch { account.session.collect { value ->
            if (value == null) { alarms.clearAccountData(); stopService(Intent(this@NotificationNodeService, AlarmPlaybackService::class.java)); stopSelf() }
            else if (currentToken.isNotEmpty() && currentToken != value.token) { reconnect("更新登录凭证"); connect() }
        } }
        serviceScope.launch { while (true) { account.ensureSession(); delay(30_000) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.enabled || !NotificationNodeSettings.valid(settings.read())) { stopSelf(); return START_NOT_STICKY }
        if (socket == null && !terminal) connect()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (alarms.getActiveAlert() != null) wakePlayback()
        return START_STICKY
    }
    private fun connect() {
        val value = settings.read()
        if (!NotificationNodeSettings.valid(value)) { stopSelf(); return }
        currentToken = value.token
        diagnostic("connect-attempt")
        val own = ++generation
        authenticated = false
        connectedAt = SystemClock.elapsedRealtime()
        recovery.attempt(connectedAt)
        probeId = null
        mutableState.value = mutableState.value.copy(status = "连接中", connected = false)
        socket = client.newWebSocket(Request.Builder().url(value.endpoint).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { handler.post {
                if (own != generation || stopped) { ws.cancel(); return@post }
                if (!ws.send(JSONObject().put("type", "auth").put("token", value.token).toString())) transportFailed("认证发送失败")
            } }
            override fun onMessage(ws: WebSocket, text: String) { handler.post {
                if (own != generation || stopped || text.length > 1_000_000) return@post
                runCatching { handleMessage(ws, JSONObject(text)) }.onFailure { error ->
                    diagnostic("invalid-message errorType=${error.javaClass.simpleName}")
                    mutableState.value = mutableState.value.copy(status = "收到无效消息")
                }
            } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { handler.post { if (own == generation && !stopped) { diagnostic("socket-failure errorType=${t.javaClass.simpleName} httpStatus=${response?.code}"); transportFailed("连接断开，正在确认") } } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { handler.post { if (own == generation && !stopped) {
                diagnostic("socket-closing code=$code")
                if (code == 4001 || code == 4003) { terminal = true; reconnect("登录已失效，请重新登录"); account.clear() }
                else { ws.close(code, reason); transportFailed("连接关闭，正在确认") }
            } } }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { handler.post { if (own == generation && !stopped) transportFailed("连接关闭，正在确认") } }
        })
    }
    private fun handleMessage(ws: WebSocket, message: JSONObject) {
        val type = message.optString("type")
        if (type == "auth-result") {
            if (!message.optBoolean("success")) { terminal = true; reconnect("登录已失效，请重新登录"); account.clear(); return }
            authenticated = true
            diagnostic("authenticated")
            retryMs = 1_000
            message.optJSONObject("notificationScope")?.let { updateScope(it) }
            message.optJSONObject("timeStandard")?.let { updateTimeStandard(it) }
        }
        if (type == "kicked" || type == "session-revoked") { terminal = true; reconnect("登录已失效，请重新登录"); account.clear(); return }
        if (!authenticated) return
        if (type == "command" && message.optString("command") in setOf("cache-inspect", "cache-clean")) {
            val own = generation
            serviceScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        require(own == generation && socket === ws && authenticated && message.optString("targetDeviceId") == settings.read().deviceId && !message.has("targetSourceId")) { "连接或目标已变更" }
                        com.xgwnje.visionguard.account.TemporaryCache.maintain(this@NotificationNodeService, message.optString("command") == "cache-clean") { own == generation && socket === ws && authenticated }
                    }
                }
                if (own == generation && socket === ws && authenticated) ws.send(JSONObject().put("type", "command-ack").put("requestId", message.optString("requestId"))
                    .put("targetDeviceId", settings.read().deviceId).put("command", message.optString("command")).put("phase", "completed").put("success", result.isSuccess)
                    .put("cache", result.getOrNull()).put("reason", if (result.isSuccess) "缓存盘点完成" else "缓存操作失败，请重新盘点").toString())
            }
            return
        }
        if (type == "set-config") {
            val own = generation
            serviceScope.launch {
                val key = message.optString("key")
                remoteConfigMutex.lock()
                val result = try { runCatching {
                    require(own == generation && socket === ws && authenticated) { "连接已变更，操作取消" }
                    require(message.optString("targetDeviceId") == settings.read().deviceId && !message.has("targetSourceId")) { "配置目标不符" }
                    if (key == "audioImport") withContext(Dispatchers.IO) { remoteSound.apply(key, message.getString("value")) }
                    else remoteSound.apply(key, message.getString("value"))
                } } finally { remoteConfigMutex.unlock() }
                if (own == generation && socket === ws && authenticated) ws.send(JSONObject().put("type", "command-ack")
                    .put("requestId", message.optString("requestId")).put("targetDeviceId", settings.read().deviceId)
                    .put("command", "set-config:" + key).put("phase", "completed").put("success", result.isSuccess)
                    .put("reason", (result.getOrNull() ?: result.exceptionOrNull()?.message ?: "保存失败").take(256)).toString())
            }
            return
        }
        if (type == "command" && authenticated && message.optString("command") == "stop-alarm") {
            val active = alarms.getActiveAlert()
            val success = active == null || alarms.finishActiveAlert(active.id, com.xgwnje.visionguard.notifier.AlertEndType.MANUAL).success
            ws.send(JSONObject().put("type", "command-ack").put("requestId", message.optString("requestId"))
                .put("targetDeviceId", settings.read().deviceId).put("command", "stop-alarm").put("phase", "completed").put("success", success)
                .put("reason", if (!success) "保存报警确认失败" else if (active == null) "当前无报警" else "当前报警已确认；如有排队报警，将继续播放").toString())
            return
        }
        if (type !in setOf("auth-result", "heartbeat-ack", "device-updated", "notification-scope", "time-standard", "alert", "stream-list")) return
        if (type == "device-updated") message.optJSONObject("device")?.let { device -> serviceScope.launch { account.updateDevice(device) } }
        if (type == "auth-result" || type == "heartbeat-ack" && (!recovery.probing || message.optString("probeId") == probeId)) {
            lastResponse = SystemClock.elapsedRealtime()
            recovery.responded(lastResponse); probeId = null
            if (alarms.markServiceRecovered()) { outageId = null; outageAccepted = false }
            mutableState.value = mutableState.value.copy(status = "已连接", connected = true, lastResponse = lastResponse)
        }
        if (type == "notification-scope") message.optJSONObject("scope")?.let { updateScope(it) }
        if (type == "time-standard") updateTimeStandard(message)
        if (type == "alert") {
            val id = message.getString("alertId")
            if (!id.matches(Regex("[A-Za-z0-9_-]{8,128}"))) return
            val kinds = mapOf("visual-detection" to "视觉检测", "sensor-detection" to "传感器检测", "connection-lost" to "连接中断", "detection-interrupted" to "检测中断")
            val label = kinds[message.getString("eventKind")] ?: return
            val timestamp = Instant.parse(message.getString("timestamp")).toEpochMilli()
            val expires = Instant.parse(message.getString("expiresAt")).toEpochMilli()
            val source = message.optString("deviceName", message.getString("deviceId")) + message.optString("sourceName").let { if (it.isBlank()) "" else " · $it" }
            val detectedObject = if (message.getString("eventKind") == "visual-detection") {
                val detections = message.optJSONArray("detections")
                com.xgwnje.visionguard.notifier.highestConfidenceObject(buildList {
                    if (detections != null && detections.length() <= 100) for (index in 0 until detections.length()) {
                        val detection = detections.optJSONObject(index) ?: continue
                        val score = (detection.opt("confidence") as? Number)?.toDouble() ?: continue
                        add(com.xgwnje.visionguard.notifier.DetectedObject(detection.optString("label"), score))
                    }
                })
            } else null
            if (alarms.acceptRemoteAlert(id, label, source, message.optString("summary"), timestamp, expires,
                    detectedObject = detectedObject)) {
                val sent = ws.send(JSONObject().put("type", "notification-receipt").put("alertId", id).toString())
                diagnostic("alert-persisted alertId=$id receiptQueued=$sent queueSize=${alarms.getAlertQueue().size}")
                wakePlayback()
                mutableState.value = mutableState.value.copy(received = mutableState.value.received + 1)
            } else {
                diagnostic("alert-rejected alertId=$id ageMs=${System.currentTimeMillis() - timestamp} expiresInMs=${expires - System.currentTimeMillis()} queueSize=${alarms.getAlertQueue().size}")
            }
        }
    }
    private fun updateScope(scope: JSONObject) {
        val count = scope.optJSONArray("targets")?.length() ?: 0
        mutableState.value = mutableState.value.copy(scope = if (scope.optString("mode") == "all") "全部检测节点" else "指定 $count 个节点或来源")
    }
    private fun updateTimeStandard(value: JSONObject) {
        val timeZone = value.getString("timeZone")
        check(settings.saveTimeZone(timeZone)) { "无法保存告警时间标准" }
        mutableState.value = mutableState.value.copy(timeZone = timeZone)
    }
    private fun wakePlayback() {
        com.xgwnje.visionguard.notifier.RingtoneLibrary.stopPreview()
        runCatching { ContextCompat.startForegroundService(this, Intent(this, AlarmPlaybackService::class.java)) }
            .onFailure { mutableState.value = mutableState.value.copy(status = "报警已保存，请打开 VisionGuard 恢复播放") }
    }
    private fun confirmOutage() {
        if (terminal || stopped) return
        if (outageId == null) outageId = "service-${UUID.randomUUID()}"
        if (outageAccepted) return
        val wall = System.currentTimeMillis()
        outageAccepted = alarms.acceptRemoteAlert(outageId!!, "服务中断", "统一服务", "主动探测无响应且重新连接失败", wall, wall + 30_000, serviceOutage = true)
        if (outageAccepted) wakePlayback()
    }
    private fun transportFailed(reason: String) {
        diagnostic("transport-failed reason=$reason")
        if (recovery.failed(SystemClock.elapsedRealtime()) == ConnectionRecovery.Action.CONFIRM_FAILURE) confirmOutage()
        reconnect(reason)
    }
    private fun reconnect(reason: String, immediate: Boolean = false) {
        diagnostic("reconnect reason=$reason retryMs=${if (immediate) 0 else retryMs}")
        ++generation
        socket?.cancel(); socket = null; authenticated = false
        nextAttempt = SystemClock.elapsedRealtime() + if (immediate) 0 else retryMs
        retryMs = if (immediate) 1_000 else (retryMs * 2).coerceAtMost(30_000)
        mutableState.value = mutableState.value.copy(status = reason, connected = false)
    }
    override fun onDestroy() {
        diagnostic("service-destroyed")
        stopped = true
        if (networkCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (underlyingCallbackRegistered) runCatching { connectivity.unregisterNetworkCallback(underlyingCallback) }
        ++generation
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        socket?.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        mutableState.value = NodeState(timeZone = settings.timeZone)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

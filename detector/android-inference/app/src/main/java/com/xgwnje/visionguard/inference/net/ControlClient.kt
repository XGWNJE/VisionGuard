package com.xgwnje.visionguard.inference.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** 控制通道状态。 */
data class ControlState(
    val connected: Boolean = false,
    val authenticated: Boolean = false,
    /** 服务端已接收本连接的心跳，报警发件箱此时才允许发送。 */
    val sourcesRegistered: Boolean = false,
    val maxSources: Int = 4,
    val statusText: String = "正在连接",
    val serverTimeIso: String? = null,
    val timeZone: String? = null
)

/** 服务端下发的媒体流条目（订阅端用它把 streamId 映射到来源身份）。 */
data class RemoteStream(
    val streamId: String,
    val publisherDeviceId: String,
    val publisherName: String,
    val targetDeviceId: String,
    val sourceId: String,
    val sourceName: String,
    val isStreaming: Boolean,
    val stopReason: String?
)

/**
 * 统一服务控制通道（`/ws`）。
 *
 * 与 Windows `Services/ServerPushService.cs` 同一契约：认证 → 每 3 秒心跳 → 收到心跳回执后才发送
 * 报警发件箱；下行命令与 `set-config` 逐条回 `command-ack`；认证超时 12 秒、幽灵阈值 45 秒，
 * 重连退避 1/2/3/5/10/20 秒。
 */
class ControlClient(
    private val account: AccountStore,
    private val heartbeatPayload: () -> JsonObject,
    private val onCommand: (command: String, targetSourceId: String?, respond: (Boolean, String, JsonObject?) -> Unit) -> Unit,
    private val onSetConfig: (key: String, value: String, targetSourceId: String?, respond: (Boolean, String) -> Unit) -> Unit,
    private val onFlushAlerts: () -> Unit,
    private val onScreenshotRequested: (alertId: String) -> Unit,
    private val onAlertAck: (alertId: String, accepted: Boolean, duplicate: Boolean, reason: String) -> Unit,
    private val onDeviceUpdated: (JsonObject) -> Unit
) {
    private val gson = Gson()
    private val mutableState = MutableStateFlow(ControlState())
    val state = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var generation = 0
    private var credential = ""
    private var openedAt = 0L
    private var lastResponseAt = 0L
    private var nextRetryAt = 0L
    private var retryIndex = 0
    private var lastHeartbeatAt = 0L
    private var lastHeartbeatSourceIds: Set<String> = emptySet()
    @Volatile private var closed = false

    /** 最近一次成功心跳里的来源身份，供报警前校验使用。 */
    @Volatile var registeredSourceIds: Set<String> = emptySet()
        private set

    init {
        scope.launch {
            account.session.collect { value ->
                if (value == null) close()
                else if (credential != value.endpoint + "|" + value.token) connect(value)
            }
        }
        scope.launch {
            while (isActive && !closed) {
                account.ensureSession()
                val current = account.session.value
                val now = SystemClock.elapsedRealtime()
                if (current != null && socket == null && now >= nextRetryAt) connect(current)
                if (socket != null) {
                    val timedOut = (!mutableState.value.authenticated && now - openedAt > AUTH_TIMEOUT_MS) ||
                        (mutableState.value.authenticated && now - lastResponseAt > GHOST_TIMEOUT_MS)
                    if (timedOut) drop()
                    else if (mutableState.value.authenticated && now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                        lastHeartbeatAt = now
                        sendHeartbeat()
                    }
                }
                delay(500)
            }
        }
    }

    private fun connect(value: AccountSession) {
        if (closed) return
        credential = value.endpoint + "|" + value.token
        val own = ++generation
        socket?.cancel()
        openedAt = SystemClock.elapsedRealtime()
        lastResponseAt = openedAt
        lastHeartbeatAt = 0L
        socket = http.newWebSocket(Request.Builder().url(value.webSocketUrl).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                handler.post {
                    if (own != generation || closed) ws.cancel()
                    else if (!ws.send(authMessage(value.token).toString())) drop()
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handler.post {
                    if (own != generation || closed || text.length > 1_000_000) return@post
                    runCatching { handleMessage(ws, value, text) }
                    lastResponseAt = SystemClock.elapsedRealtime()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handler.post { if (own == generation && !closed) drop() }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handler.post { if (own == generation && !closed) drop() }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                handler.post { if (own == generation && !closed) { ws.close(code, reason); drop() } }
            }
        })
    }

    private fun authMessage(token: String): JsonObject = JsonObject().apply {
        addProperty("type", "auth")
        addProperty("token", token)
        addProperty("version", com.xgwnje.visionguard.inference.AppConstants.VERSION)
    }

    private fun handleMessage(ws: WebSocket, value: AccountSession, text: String) {
        val body = gson.fromJson(text, JsonObject::class.java) ?: return
        when (body.string("type")) {
            "auth-result" -> {
                if (!body.boolean("success")) {
                    // 认证被拒（会话吊销或凭据无效）：与其它端一致地退回登录界面。
                    account.clear()
                    return
                }
                body.int("maxSources")?.let { limit -> update { it.copy(maxSources = limit.coerceIn(1, 16)) } }
                body.obj("timeStandard")?.let { standard ->
                    update {
                        it.copy(
                            serverTimeIso = standard.string("serverTime"),
                            timeZone = standard.string("timeZone")
                        )
                    }
                }
                update { it.copy(connected = true, authenticated = true, statusText = "已连接") }
                // 认证后立即心跳一次，让服务端尽快拿到来源列表。
                lastHeartbeatAt = SystemClock.elapsedRealtime()
                sendHeartbeat()
            }

            "heartbeat-ack" -> {
                val accepted = body.boolean("accepted")
                body.obj("timeStandard")?.let { standard ->
                    update { it.copy(serverTimeIso = standard.string("serverTime")) }
                }
                if (!accepted) {
                    body.int("maxSources")?.let { limit -> update { it.copy(maxSources = limit.coerceIn(1, 16)) } }
                    update { it.copy(sourcesRegistered = false, statusText = "来源未被服务端接受（${body.string("reason") ?: "未知原因"}）") }
                    return
                }
                update { it.copy(sourcesRegistered = true, statusText = "已连接") }
                registeredSourceIds = lastHeartbeatSourceIds
                onFlushAlerts()
            }

            "command" -> {
                if (body.string("targetDeviceId") != value.deviceId) return
                val command = body.string("command") ?: return
                val requestId = body.string("requestId")
                val targetSourceId = body.string("targetSourceId")?.takeIf { it.isNotBlank() }
                onCommand(command, targetSourceId) { success, reason, cache ->
                    respond(ws, requestId, command, success, reason, targetSourceId, cache)
                }
            }

            "set-config" -> {
                if (body.string("targetDeviceId") != value.deviceId) return
                val key = body.string("key") ?: return
                val incoming = body.string("value") ?: ""
                val requestId = body.string("requestId")
                val targetSourceId = body.string("targetSourceId")?.takeIf { it.isNotBlank() }
                onSetConfig(key, incoming, targetSourceId) { success, reason ->
                    respond(ws, requestId, "set-config:$key", success, reason, targetSourceId)
                }
            }

            "request-screenshot" -> body.string("alertId")?.let(onScreenshotRequested)

            "alert-ack" -> {
                val alertId = body.string("alertId") ?: return
                onAlertAck(alertId, body.boolean("accepted"), body.boolean("duplicate"), body.string("reason") ?: "")
            }

            "device-updated" -> body.obj("device")?.let(onDeviceUpdated)

            "kicked" -> {
                update { it.copy(statusText = "已被服务端断开：${body.string("reason") ?: "未知原因"}") }
                drop(longBackoff = true)
            }
        }
    }

    private fun respond(
        ws: WebSocket,
        requestId: String?,
        command: String,
        success: Boolean,
        reason: String,
        targetSourceId: String?,
        cache: JsonObject? = null
    ) {
        val deviceId = account.session.value?.deviceId ?: return
        val message = JsonObject().apply {
            addProperty("type", "command-ack")
            if (!requestId.isNullOrBlank()) addProperty("requestId", requestId)
            addProperty("command", command)
            addProperty("targetDeviceId", deviceId)
            if (!targetSourceId.isNullOrBlank()) addProperty("targetSourceId", targetSourceId)
            addProperty("phase", "completed")
            addProperty("success", success)
            addProperty("reason", reason.take(256))
            if (cache != null) add("cache", cache)
        }
        ws.send(gson.toJson(message))
    }

    private fun sendHeartbeat() {
        val ws = socket ?: return
        val payload = heartbeatPayload()
        payload.addProperty("type", "heartbeat")
        lastHeartbeatSourceIds = payload.arr("sources")?.mapNotNull { it.asJsonObject.string("sourceId") }?.toSet() ?: emptySet()
        if (!ws.send(gson.toJson(payload))) drop()
    }

    /** 发送一条报警；返回是否已交给套接字。 */
    fun sendAlert(payloadJson: String): Boolean = socket?.send(payloadJson) == true

    /** 报警截图在收到 `alert-ack accepted=true` 之后异步发送。 */
    fun sendScreenshot(alertId: String, jpeg: ByteArray, width: Int, height: Int): Boolean {
        val ws = socket ?: return false
        val message = JsonObject().apply {
            addProperty("type", "screenshot-data")
            addProperty("alertId", alertId)
            addProperty("imageBase64", java.util.Base64.getEncoder().encodeToString(jpeg))
            addProperty("width", width)
            addProperty("height", height)
        }
        return ws.send(gson.toJson(message))
    }

    private fun drop(longBackoff: Boolean = false) {
        ++generation
        socket?.cancel()
        socket = null
        retryIndex = if (longBackoff) 0 else retryIndex
        val delayMs = BACKOFF_MS[retryIndex.coerceIn(0, BACKOFF_MS.lastIndex)]
        retryIndex = (retryIndex + 1).coerceAtMost(BACKOFF_MS.lastIndex)
        nextRetryAt = SystemClock.elapsedRealtime() + delayMs
        registeredSourceIds = emptySet()
        update { it.copy(connected = false, authenticated = false, sourcesRegistered = false, statusText = "连接中断，正在重连") }
    }

    fun close() {
        if (closed) return
        closed = true
        ++generation
        socket?.cancel()
        socket = null
        scope.cancel()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
        update { it.copy(connected = false, authenticated = false, sourcesRegistered = false, statusText = "未连接") }
    }

    private inline fun update(transform: (ControlState) -> ControlState) {
        synchronized(mutableState) { mutableState.value = transform(mutableState.value) }
    }

    companion object {
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val AUTH_TIMEOUT_MS = 12_000L
        const val GHOST_TIMEOUT_MS = 45_000L
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 3_000, 5_000, 10_000, 20_000)
    }
}

// ── Gson 安全取值辅助：服务端字段缺失或类型不符时不抛异常 ────────────────

internal fun JsonObject.string(name: String): String? {
    val element = get(name) ?: return null
    if (element.isJsonNull) return null
    return runCatching { element.asString }.getOrNull()
}

internal fun JsonObject.boolean(name: String): Boolean =
    runCatching { get(name)?.asBoolean }.getOrNull() ?: false

internal fun JsonObject.int(name: String): Int? =
    runCatching { get(name)?.asInt }.getOrNull()

internal fun JsonObject.long(name: String): Long? =
    runCatching { get(name)?.asLong }.getOrNull()

internal fun JsonObject.obj(name: String): JsonObject? =
    runCatching { get(name)?.takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

internal fun JsonObject.arr(name: String): JsonArray? =
    runCatching { get(name)?.takeIf { it.isJsonArray }?.asJsonArray }.getOrNull()

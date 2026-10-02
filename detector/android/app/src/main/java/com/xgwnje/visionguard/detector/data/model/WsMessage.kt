package com.xgwnje.visionguard.detector.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ WsMessage.kt                                            │
// │ 角色：WebSocket 消息数据类                               │
// │ 用途：Gson 序列化/反序列化                               │
// └─────────────────────────────────────────────────────────┘

import com.xgwnje.visionguard.detector.BuildConfig

/** Android-detector → 服务器：认证 */
data class WsAuthMessage(
    val type: String = "auth",
    val apiKey: String,
    val channel: String = com.xgwnje.visionguard.detector.AppConstants.CHANNEL,
    val role: String = "detector",
    val nodeType: String = "visual",
    val platform: String = "android",
    val deviceId: String,
    val deviceName: String = "Android-Detector",
    val version: String = BuildConfig.VERSION_NAME
)

/** Android-receiver → 服务器：发送控制命令（pause / resume / stop-alarm） */
data class WsCommandMessage(
    val type: String = "command",
    val requestId: String = "",
    val targetDeviceId: String = "",
    val targetSourceId: String? = null,
    val command: String             // "pause" | "resume" | "stop-alarm"
)

/** Android-receiver → 服务器：下发参数调整（set-config） */
data class WsSetConfigMessage(
    val type: String = "set-config",
    val requestId: String = "",
    val targetDeviceId: String = "",
    val key: String = "",    // "cooldown" | "confidence" | "targets"
    val value: String = ""   // 字符串形式的值
)

/** 服务器 → Android-detector：命令回执 */
data class WsCommandAck(
    val type: String = "command-ack",
    val requestId: String = "",
    val phase: String = "completed",
    val targetDeviceId: String = "",
    val targetSourceId: String? = null,
    val command: String = "",
    val success: Boolean = false,
    val reason: String = ""
)

/** Android-detector → 服务器：心跳（含运行状态） */
data class WsHeartbeatMessage(
    val type: String = "heartbeat",
    val deviceId: String,
    val deviceName: String = "",
    val isMonitoring: Boolean = false,
    val isReady: Boolean = false,
    val cooldown: Int = 5,
    val confidence: Double = 0.45,
    val targets: String = "",
    val targetSamplingRate: Int = 3,
    val modelKey: String = "",
    val modelOptions: List<String> = emptyList(),
    val canSwitchModelWhileMonitoring: Boolean = true,
    val hasPendingConfigChanges: Boolean = false,
    val capabilities: List<String> = listOf(
        "monitor-control", "config-control", "request-correlation", "screenshot-on-demand"
    ),
    val components: Map<String, String> = mapOf("detectorApp" to "running"),
    val sources: List<Map<String, Any>> = listOf(mapOf(
        "sourceId" to "camera", "sourceName" to "摄像头", "isMonitoring" to isMonitoring,
        "isReady" to isReady, "modelKey" to modelKey
    ))
)

/** Android-detector → 服务器：日志上报 */
data class WsLogReportMessage(
    val type: String = "log-report",
    val level: String,
    val tag: String,
    val message: String,
    val timestamp: String
)

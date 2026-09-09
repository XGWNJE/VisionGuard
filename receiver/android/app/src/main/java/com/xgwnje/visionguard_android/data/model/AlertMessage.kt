package com.xgwnje.visionguard_android.data.model

// ┌─────────────────────────────────────────────────────────┐
// │ AlertMessage.kt                                         │
// │ 角色：报警事件数据类                                      │
// │ 来源：服务器 WS "alert" 消息 / 内存历史列表               │
// └─────────────────────────────────────────────────────────┘

data class BoundingBox(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f
)

data class Detection(
    val label: String = "",
    val confidence: Double = 0.0,
    val bbox: BoundingBox = BoundingBox()
)

data class AlertMessage(
    val alertId: String = "",        // Gson 解析缺失字段时防 NPE
    val deviceId: String = "",
    val deviceName: String = "",
    /**
     * V1 来源身份；旧检测端不发送时保持空值，由接收端按默认单来源处理。
     * sourceId 是稳定身份，sourceName 仅是事件发生时的名称快照。
     */
    val sourceId: String = "",
    val sourceName: String = "",
    val timestamp: String = "",      // ISO 8601
    val detections: List<Detection> = emptyList(),
    val createdAt: Long? = null,      // 服务器接收时间戳，排序权威字段
    val screenshotUrl: String = "",  // 已废弃，保留兼容（Windows 不再发此字段）
    val screenshotData: ScreenshotData? = null,
    val hasScreenshot: Boolean = false,
    val timings: Map<String, Long>? = null,
    val wsSentAt: String? = null,
    val serverReceivedAt: String? = null,
    val serverRelayedAt: String? = null,
    val screenshotBase64: String? = null,  // v4.0.0: 检测端内嵌截图 (Base64 JPEG)
    val capturedAt: String? = null,         // v4.0.0: 检测端捕获帧 NTP 时间戳
    var receivedAt: Long? = null,
    var notifiedAt: Long? = null
)

data class ScreenshotData(
    val alertId: String,
    val imageBase64: String,
    val width: Int,
    val height: Int,
    val deviceId: String = "",
    val sourceId: String = "",
    val sourceName: String = ""
)

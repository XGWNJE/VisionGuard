package com.xgwnje.visionguard_android.ui.home

import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

data class ReceiverHomeSummary(
    val todayAlertCount: Int,
    val onlineDeviceCount: Int,
    val latestAlertTimeLabel: String
)

data class ReceiverMainTab(
    val route: String,
    val label: String
)

data class AlertListChrome(
    val showStandaloneHeader: Boolean,
    val topPaddingDp: Int,
    val topOverlayReservedDp: Int,
    val bottomOverlayReservedDp: Int,
    val horizontalPaddingDp: Int
)

data class FrostedOverlaySpec(
    val topBannerAlpha: Float,
    val bottomBarAlpha: Float,
    val borderAlpha: Float,
    val shadowElevationDp: Float
)

data class AlertDetailChrome(
    val showMetadataText: Boolean,
    val supportsPinchZoom: Boolean,
    val supportsGallerySave: Boolean
)

data class UpdateDialogUiModel(
    val title: String,
    val currentVersionLabel: String,
    val latestVersionLabel: String,
    val message: String,
    val primaryActionLabel: String,
    val secondaryActionLabel: String?,
    val tone: UpdateDialogTone
)

enum class UpdateDialogTone {
    AVAILABLE,
    CURRENT,
    FAILED
}

data class AlertCardUiModel(
    val deviceName: String,
    val timeLabel: String,
    val dateTimeLabel: String,
    val detailIconContentDescription: String,
    val targetChips: List<DetectionChipUiModel>,
    val screenshotState: ScreenshotState
)

data class DeviceCardUiModel(
    val deviceName: String,
    val statusLabel: String,
    val statusTone: DeviceStatusTone,
    val controlActionLabel: String,
    val controlCommand: String,
    val controlsEnabled: Boolean,
    val lifecycleControlsEnabled: Boolean = false,
    val firstRowLayout: DeviceCardFirstRowLayout,
    val illustration: DeviceCardIllustration,
    val typeLabel: String,
    val wpfLifecycleCommand: String? = null,
    val winFormsLifecycleCommand: String? = null
)

data class DeviceCardChrome(
    val cardCornerRadiusDp: Int,
    val heroHeightDp: Int,
    val heroContentHorizontalPaddingDp: Int,
    val heroContentVerticalPaddingDp: Int,
    val titleHasContainer: Boolean,
    val statusUsesCompactPill: Boolean,
    val actionAreaHorizontalPaddingDp: Int,
    val actionAreaVerticalPaddingDp: Int,
    val columnGapDp: Int,
    val actionButtonHeightDp: Int,
    val actionContentHorizontalPaddingDp: Int,
    val heroBackgroundAlpha: Float,
    val heroBackgroundScale: Float
)

data class DeviceConfigEditorUiModel(
    val deviceName: String,
    val presentation: DeviceConfigEditorPresentation,
    val applyMode: DeviceConfigApplyMode,
    val hasChanges: Boolean,
    val applyEnabled: Boolean,
    val applyActionLabel: String,
    val cancelActionLabel: String
)

data class DeviceConfigChange(
    val key: String,
    val value: String
)

data class DetectionChipUiModel(
    val label: String,
    val confidencePercent: Int,
    val target: DetectionTarget
)

enum class DetectionTarget {
    PERSON,
    BICYCLE,
    CAR,
    MOTORCYCLE,
    BUS,
    TRUCK,
    UNKNOWN
}

enum class ScreenshotState {
    SYNCED,
    WAITING
}

enum class DeviceStatusTone {
    OFFLINE,
    RESIDENT_ONLY,
    MONITORING,
    PARTIAL_MONITORING,
    NOT_READY,
    READY
}

enum class DeviceCardFirstRowLayout {
    BALANCED_TWO_COLUMN
}

enum class DeviceCardIllustration {
    WINDOWS_DESKTOP,
    ANDROID_CAMERA,
    GENERIC_VIEWFINDER
}

enum class DeviceConfigEditorPresentation {
    BOTTOM_SHEET
}

enum class DeviceConfigApplyMode {
    BATCH
}

enum class UpdateFeedback {
    NO_UPDATE,
    CHECK_FAILED
}

fun receiverMainTabs(): List<ReceiverMainTab> =
    listOf(
        ReceiverMainTab(route = "alertList", label = "警报"),
        ReceiverMainTab(route = "deviceList", label = "设备")
    )

fun buildAlertListChrome(): AlertListChrome =
    AlertListChrome(
        showStandaloneHeader = false,
        topPaddingDp = 0,
        topOverlayReservedDp = 104,
        bottomOverlayReservedDp = 132,
        horizontalPaddingDp = 18
    )

fun buildFrostedOverlaySpec(): FrostedOverlaySpec =
    FrostedOverlaySpec(
        topBannerAlpha = 0.86f,
        bottomBarAlpha = 0.86f,
        borderAlpha = 0.58f,
        shadowElevationDp = 0f
    )

fun buildAlertDetailChrome(): AlertDetailChrome =
    AlertDetailChrome(
        showMetadataText = false,
        supportsPinchZoom = true,
        supportsGallerySave = true
    )

fun buildDeviceCardUiModel(device: DeviceInfo): DeviceCardUiModel {
    val totalSourceCount = device.sources.size
    val runningSources = device.sources.count {
        it.isMonitoring && it.isReady && it.error.isNullOrBlank()
    }
    val allSourcesRunning = totalSourceCount > 0 && runningSources == totalSourceCount
    val hasSourceControls = "source-control" in device.capabilities && device.sources.isNotEmpty()
    val statusTone = when {
        !device.online -> DeviceStatusTone.OFFLINE
        device.components["resident"] == "running" && device.components["detectorApp"] != "running" -> DeviceStatusTone.RESIDENT_ONLY
        allSourcesRunning -> DeviceStatusTone.MONITORING
        runningSources > 0 -> DeviceStatusTone.PARTIAL_MONITORING
        totalSourceCount == 0 && device.isMonitoring -> DeviceStatusTone.MONITORING
        !device.isReady -> DeviceStatusTone.NOT_READY
        else -> DeviceStatusTone.READY
    }
    val statusLabel = when (statusTone) {
        DeviceStatusTone.OFFLINE -> "离线"
        DeviceStatusTone.RESIDENT_ONLY -> "驻留在线 · 程序关闭"
        DeviceStatusTone.MONITORING -> "检测中"
        DeviceStatusTone.PARTIAL_MONITORING -> "部分检测中 $runningSources/$totalSourceCount"
        DeviceStatusTone.NOT_READY -> "未就绪"
        DeviceStatusTone.READY -> "已就绪"
    }

    return DeviceCardUiModel(
        deviceName = device.deviceName.ifBlank { "未知设备" },
        statusLabel = statusLabel,
        statusTone = statusTone,
        controlActionLabel = if (device.isMonitoring) "停止监控" else "开始监控",
        controlCommand = if (device.isMonitoring) "pause" else "resume",
        controlsEnabled = !hasSourceControls && device.online && (
            device.components["detectorApp"] == "running" || "app-lifecycle-control" !in device.capabilities
        ),
        lifecycleControlsEnabled = device.online && device.components["resident"] == "running",
        firstRowLayout = DeviceCardFirstRowLayout.BALANCED_TWO_COLUMN,
        illustration = deviceCardIllustrationOf(device.clientType),
        typeLabel = deviceTypeLabelOf(device.clientType),
        wpfLifecycleCommand = lifecycleCommand(device, "wpfApp", "wpf"),
        winFormsLifecycleCommand = lifecycleCommand(device, "winFormsApp", "winforms")
    )
}

private fun lifecycleCommand(device: DeviceInfo, component: String, suffix: String): String? {
    if ("app-lifecycle-control" !in device.capabilities) return null
    return if (device.components[component] == "running") "close-$suffix" else "open-$suffix"
}

fun buildDeviceCardChrome(): DeviceCardChrome =
    DeviceCardChrome(
        cardCornerRadiusDp = 28,
        heroHeightDp = 128,
        heroContentHorizontalPaddingDp = 22,
        heroContentVerticalPaddingDp = 20,
        titleHasContainer = false,
        statusUsesCompactPill = true,
        actionAreaHorizontalPaddingDp = 18,
        actionAreaVerticalPaddingDp = 16,
        columnGapDp = 10,
        actionButtonHeightDp = 52,
        actionContentHorizontalPaddingDp = 16,
        heroBackgroundAlpha = 0.58f,
        heroBackgroundScale = 1.08f
    )

private fun deviceCardIllustrationOf(clientType: String): DeviceCardIllustration =
    when (clientType.lowercase(Locale.US)) {
        "windows" -> DeviceCardIllustration.WINDOWS_DESKTOP
        "android-detector" -> DeviceCardIllustration.ANDROID_CAMERA
        else -> DeviceCardIllustration.GENERIC_VIEWFINDER
    }

private fun deviceTypeLabelOf(clientType: String): String =
    when (clientType.lowercase(Locale.US)) {
        "windows" -> "Windows识别端"
        "android-detector" -> "安卓识别端"
        else -> "识别端"
    }

fun buildDeviceConfigEditorUiModel(
    device: DeviceInfo,
    initialConfig: DeviceConfig,
    editedCooldown: Float,
    editedConfidence: Float,
    selectedTargets: Set<String>,
    editedTargetSamplingRate: Int = initialConfig.targetSamplingRate,
    editedModelKey: String = initialConfig.modelKey
): DeviceConfigEditorUiModel {
    val hasChanges = buildDeviceConfigChanges(
        initialConfig = initialConfig,
        editedCooldown = editedCooldown,
        editedConfidence = editedConfidence,
        selectedTargets = selectedTargets,
        editedTargetSamplingRate = editedTargetSamplingRate,
        editedModelKey = editedModelKey
    ).isNotEmpty()

    return DeviceConfigEditorUiModel(
        deviceName = device.deviceName.ifBlank { "未知设备" },
        presentation = DeviceConfigEditorPresentation.BOTTOM_SHEET,
        applyMode = DeviceConfigApplyMode.BATCH,
        hasChanges = hasChanges,
        applyEnabled = hasChanges && device.online,
        applyActionLabel = "应用更改",
        cancelActionLabel = "取消"
    )
}

fun buildDeviceConfigChanges(
    initialConfig: DeviceConfig,
    editedCooldown: Float,
    editedConfidence: Float,
    selectedTargets: Set<String>,
    editedTargetSamplingRate: Int = initialConfig.targetSamplingRate,
    editedModelKey: String = initialConfig.modelKey
): List<DeviceConfigChange> {
    val normalizedTargets = selectedTargets
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .sorted()
    val initialTargets = initialConfig.targets
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .sorted()
    val changes = mutableListOf<DeviceConfigChange>()
    val cooldown = editedCooldown.roundToInt().coerceIn(1, 300)
    val confidence = editedConfidence.coerceIn(0.10f, 0.95f)
    val targetSamplingRate = editedTargetSamplingRate.coerceIn(1, 5)
    val modelKey = editedModelKey.trim()

    if (cooldown != initialConfig.cooldown) {
        changes += DeviceConfigChange("cooldown", cooldown.toString())
    }
    if (kotlin.math.abs(confidence - initialConfig.confidence.toFloat()) >= 0.005f) {
        changes += DeviceConfigChange(
            "confidence",
            String.format(Locale.US, "%.2f", confidence)
        )
    }
    if (normalizedTargets != initialTargets) {
        changes += DeviceConfigChange("targets", normalizedTargets.joinToString(","))
    }
    if (targetSamplingRate != initialConfig.targetSamplingRate) {
        changes += DeviceConfigChange("targetSamplingRate", targetSamplingRate.toString())
    }
    if (modelKey.isNotEmpty() && modelKey != initialConfig.modelKey) {
        changes += DeviceConfigChange("modelKey", modelKey)
    }

    return changes
}

/**
 * 冷却时间的快捷档位，只用于“一点即中”。取值范围统一为 1–300 秒，
 * 任意值都能通过自定义输入表达；设备当前值一律原样显示，不吸附到档位。
 */
internal val CooldownOptions = listOf(
    5 to "5秒",
    10 to "10秒",
    30 to "30秒",
    60 to "1分钟",
    120 to "2分钟",
    300 to "5分钟"
)

internal fun cooldownLabel(seconds: Int): String =
    CooldownOptions.firstOrNull { it.first == seconds }?.second ?: "$seconds 秒"

fun buildDeviceConfigFromDevice(device: DeviceInfo): DeviceConfig =
    DeviceConfig(
        cooldown = device.cooldown,
        confidence = device.confidence,
        targets = device.targets.orEmpty(),
        targetSamplingRate = device.targetSamplingRate.takeIf { it in 1..5 } ?: 3,
        modelKey = device.modelKey.orEmpty()
    )

fun mergeSortAlerts(
    existing: List<AlertMessage>,
    incoming: List<AlertMessage>,
    limit: Int = 200
): List<AlertMessage> {
    val mergedById = linkedMapOf<String, AlertMessage>()
    (existing + incoming).forEach { alert ->
        val key = alert.alertId.ifBlank {
            "${alert.deviceId}:${alert.timestamp}:${alert.receivedAt ?: 0L}"
        }
        mergedById[key] = mergedById[key]?.let { mergeAlertMetadata(it, alert) } ?: alert
    }
    val sorted = mergedById.values
        .sortedWith(
            compareByDescending<AlertMessage> { alertSortMillis(it) }
                .thenByDescending { it.receivedAt ?: 0L }
                .thenByDescending { it.alertId }
        )
    if (sorted.size <= limit) return sorted

    // 每个来源保留少量最新记录，再用全局最新记录填满剩余容量，避免高频来源挤光其他来源。
    val sourceCount = sorted.map { alertSourceKey(it.deviceId, it.sourceId) }.distinct().size
    val reservePerSource = minOf(5, maxOf(1, limit / maxOf(1, sourceCount)))
    val reserved = sorted.groupBy { alertSourceKey(it.deviceId, it.sourceId) }
        .values.flatMap { it.take(reservePerSource) }
        .map { it.alertId.ifBlank { "${it.deviceId}:${it.timestamp}:${it.receivedAt ?: 0L}" } }
        .toHashSet()
    val selected = sorted.filter {
        it.alertId.ifBlank { "${it.deviceId}:${it.timestamp}:${it.receivedAt ?: 0L}" } in reserved
    }.toMutableList()
    if (selected.size < limit) {
        val selectedKeys = reserved.toMutableSet()
        for (alert in sorted) {
            val key = alert.alertId.ifBlank { "${alert.deviceId}:${alert.timestamp}:${alert.receivedAt ?: 0L}" }
            if (selectedKeys.add(key)) selected += alert
            if (selected.size == limit) break
        }
    }
    return selected.sortedWith(
        compareByDescending<AlertMessage> { alertSortMillis(it) }
            .thenByDescending { it.receivedAt ?: 0L }
            .thenByDescending { it.alertId }
    )
}

private fun mergeAlertMetadata(first: AlertMessage, second: AlertMessage): AlertMessage {
    val primary = if (alertSortMillis(second) > alertSortMillis(first)) second else first
    val secondary = if (primary === first) second else first
    return primary.copy(
        hasScreenshot = first.hasScreenshot || second.hasScreenshot,
        screenshotUrl = primary.screenshotUrl.ifBlank { secondary.screenshotUrl },
        screenshotData = primary.screenshotData ?: secondary.screenshotData,
        screenshotBase64 = primary.screenshotBase64?.takeIf { it.isNotBlank() }
            ?: secondary.screenshotBase64?.takeIf { it.isNotBlank() },
        receivedAt = primary.receivedAt ?: secondary.receivedAt,
        notifiedAt = primary.notifiedAt ?: secondary.notifiedAt
    )
}

private fun alertSortMillis(alert: AlertMessage): Long =
    alert.createdAt
        ?: parseIsoMillis(alert.serverReceivedAt)
        ?: parseIsoMillis(alert.timestamp)
        ?: alert.receivedAt
        ?: 0L

private fun parseIsoMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    return runCatching {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrElse {
        runCatching {
            Instant.parse(value).toEpochMilli()
        }.getOrNull()
    }
}

fun buildUpdateFeedbackText(feedback: UpdateFeedback, currentVersion: String): String =
    when (feedback) {
        UpdateFeedback.NO_UPDATE -> "当前 $currentVersion，已是最新"
        UpdateFeedback.CHECK_FAILED -> "当前 $currentVersion，检查失败"
    }

fun buildUpdateDialogText(latestVersion: String, currentVersion: String): String =
    "最新 $latestVersion\n当前 $currentVersion\n\n是否更新？"

fun buildUpdateDialogModel(latestVersion: String, currentVersion: String): UpdateDialogUiModel =
    UpdateDialogUiModel(
        title = "发现新版本",
        currentVersionLabel = "当前 $currentVersion",
        latestVersionLabel = "最新 $latestVersion",
        message = "是否更新？",
        primaryActionLabel = "更新",
        secondaryActionLabel = "稍后",
        tone = UpdateDialogTone.AVAILABLE
    )

fun buildNoUpdateDialogModel(currentVersion: String): UpdateDialogUiModel =
    UpdateDialogUiModel(
        title = "已是最新版本",
        currentVersionLabel = "当前 $currentVersion",
        latestVersionLabel = "状态：无需更新",
        message = "无需操作。",
        primaryActionLabel = "知道了",
        secondaryActionLabel = null,
        tone = UpdateDialogTone.CURRENT
    )

fun buildUpdateFailedDialogModel(currentVersion: String): UpdateDialogUiModel =
    UpdateDialogUiModel(
        title = "检查失败",
        currentVersionLabel = "当前 $currentVersion",
        latestVersionLabel = "状态：稍后再试",
        message = "暂时无法获取更新信息。",
        primaryActionLabel = "知道了",
        secondaryActionLabel = null,
        tone = UpdateDialogTone.FAILED
    )

fun buildReceiverHomeSummary(
    alerts: List<AlertMessage>,
    devices: List<DeviceInfo>,
    now: ZonedDateTime = ZonedDateTime.now()
): ReceiverHomeSummary {
    val today = now.toLocalDate()
    val parsedAlerts = alerts.mapNotNull { alert ->
        parseAlertTime(alert.timestamp, now.zone)?.let { alert to it }
    }
    val todayCount = parsedAlerts.count { (_, time) -> time.toLocalDate() == today }
    val latestTime = parsedAlerts.maxByOrNull { (_, time) -> time.toInstant().toEpochMilli() }?.second

    return ReceiverHomeSummary(
        todayAlertCount = todayCount,
        onlineDeviceCount = devices.count { it.online },
        latestAlertTimeLabel = latestTime?.format(TimeFormatter) ?: "--:--"
    )
}

fun buildAlertCardUiModel(alert: AlertMessage): AlertCardUiModel {
    val chips = alert.detections
        .take(3)
        .map { detection ->
            val target = detectionTargetOf(detection.label)
            DetectionChipUiModel(
                label = displayLabelFor(detection.label, target),
                confidencePercent = (detection.confidence * 100).roundToInt().coerceIn(0, 100),
                target = target
            )
        }
        .ifEmpty {
            listOf(
                DetectionChipUiModel(
                    label = "未知目标",
                    confidencePercent = 0,
                    target = DetectionTarget.UNKNOWN
                )
            )
        }

    return AlertCardUiModel(
        deviceName = alert.deviceName.ifBlank { "未知设备" },
        timeLabel = formatAlertTime(alert.timestamp),
        dateTimeLabel = formatAlertDateTime(alert.timestamp),
        detailIconContentDescription = "查看详情",
        targetChips = chips,
        screenshotState = if (hasScreenshotMetadata(alert)) ScreenshotState.SYNCED else ScreenshotState.WAITING
    )
}

fun formatAlertTime(timestamp: String, zoneId: ZoneId = ZoneId.systemDefault()): String =
    parseAlertTime(timestamp, zoneId)?.format(TimeFormatter) ?: "--:--"

fun formatAlertDateTime(timestamp: String, zoneId: ZoneId = ZoneId.systemDefault()): String =
    parseAlertTime(timestamp, zoneId)?.format(FullDateTimeFormatter) ?: "日期未知"

private fun parseAlertTime(timestamp: String, zoneId: ZoneId): ZonedDateTime? {
    if (timestamp.isBlank()) return null
    return runCatching {
        OffsetDateTime.parse(timestamp).atZoneSameInstant(zoneId)
    }.getOrElse {
        runCatching {
            Instant.parse(timestamp).atZone(zoneId)
        }.getOrNull()
    }
}

private fun detectionTargetOf(label: String): DetectionTarget =
    when (label.lowercase(Locale.US)) {
        "person" -> DetectionTarget.PERSON
        "bicycle" -> DetectionTarget.BICYCLE
        "car" -> DetectionTarget.CAR
        "motorcycle" -> DetectionTarget.MOTORCYCLE
        "bus" -> DetectionTarget.BUS
        "truck" -> DetectionTarget.TRUCK
        else -> DetectionTarget.UNKNOWN
    }

private fun displayLabelFor(label: String, target: DetectionTarget): String =
    when (target) {
        DetectionTarget.PERSON -> "人员"
        DetectionTarget.BICYCLE -> "自行车"
        DetectionTarget.CAR -> "汽车"
        DetectionTarget.MOTORCYCLE -> "摩托车"
        DetectionTarget.BUS -> "公共汽车"
        DetectionTarget.TRUCK -> "卡车"
        DetectionTarget.UNKNOWN -> if (label.isBlank()) "未知目标" else "未知目标"
    }

private fun hasScreenshotMetadata(alert: AlertMessage): Boolean =
    alert.hasScreenshot ||
        alert.screenshotUrl.isNotBlank() ||
        !alert.screenshotBase64.isNullOrBlank() ||
        alert.screenshotData != null

private val TimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val FullDateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

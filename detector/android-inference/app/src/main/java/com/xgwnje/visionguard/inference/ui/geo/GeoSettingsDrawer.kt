package com.xgwnje.visionguard.inference.ui.geo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.Appearance
import com.xgwnje.visionguard.account.AppearancePreference
import com.xgwnje.visionguard.account.ClientUpdateButton
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.AppConstants
import com.xgwnje.visionguard.inference.BuildConfig
import com.xgwnje.visionguard.inference.model.BundledAccelerationBenchmark
import com.xgwnje.visionguard.inference.net.ControlState
import com.xgwnje.visionguard.inference.net.MediaState
import com.xgwnje.visionguard.inference.runtime.CrashReporter
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class SettingsSection(val title: String) {
    STATE("节点状态"),
    ACCOUNT("账号与设备"),
    MODEL("模型资源"),
    APPEARANCE("外观"),
    BACKGROUND("后台与权限"),
    MAINTENANCE("维护")
}

/**
 * 全局设置：节点状态与五类设置分开，窄屏目录进入详情、宽屏侧栏导航。
 *
 * 账号、设备与连接分开表达；模型删除、改密、退出登录、缓存清理都走确认闸门。
 */
@Composable
fun GeoSettingsDrawer(
    session: AccountSession,
    state: RuntimeUiState,
    control: ControlState,
    media: MediaState,
    appearance: AppearancePreference,
    runtime: InferenceRuntime,
    onClose: () -> Unit,
    onMessage: (String?) -> Unit,
    initialSection: String? = null
) {
    var section by remember { mutableStateOf(SettingsSection.entries.firstOrNull { it.name == initialSection }) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        GeoDrawer(
            title = "全局设置",
            sectionId = "VISION / SETTINGS",
            onClose = onClose,
            width = Geo.ParamDrawer,
            scrollable = !wide
        ) {
            if (wide) {
                // 宽屏：左栏分区导航 + 右栏内容，各自独立滚动，不嵌套。
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    Column(
                        Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 8.dp)
                    ) {
                        SettingsSection.entries.forEach { entry ->
                            SettingsRailItem(entry, entry == section) { section = entry }
                        }
                    }
                    GeoDivider(vertical = true)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(Geo.GroupGap)
                    ) {
                        SettingsBody(section ?: SettingsSection.STATE, session, control, media, appearance, state, runtime, onMessage)
                    }
                }
            } else {
                if (section == null) {
                    SettingsSection.entries.forEach { entry -> SettingsRailItem(entry, false) { section = entry } }
                } else {
                    GeoKey(onClick = { section = null }, label = "返回设置目录", icon = LucideIcons.ArrowLeft)
                    SettingsBody(section ?: SettingsSection.STATE, session, control, media, appearance, state, runtime, onMessage)
                }
            }
        }
    }
}

/** 左栏分区导航：选中项带左缘主色条，与来源行同一套表达。 */
@Composable
private fun SettingsRailItem(section: SettingsSection, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 固定 48dp：左缘色条用 fillMaxHeight，行高不固定会把每一项撑满整列。
            .height(Geo.Touch)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onSelect)
            .geoFocusable(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(Geo.SourceBar)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        )
        Text(
            section.title,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/** 分区正文：宽屏两栏与窄屏拨档共用同一份实现。 */
@Composable
private fun SettingsBody(
    section: SettingsSection,
    session: AccountSession,
    control: ControlState,
    media: MediaState,
    appearance: AppearancePreference,
    state: RuntimeUiState,
    runtime: InferenceRuntime,
    onMessage: (String?) -> Unit
) {
    when (section) {
        SettingsSection.STATE -> ConnectionSection(control, media, state)
        SettingsSection.ACCOUNT -> AccountSection(session, control, media, runtime, onMessage)
        SettingsSection.MODEL -> GeoModelResources(
            state.models, state.downloads, state.sources.filter { it.state.isRunning }.map { it.params.modelKey }.toSet(), state.gpuAvailable,
            runtime::downloadModel, runtime::deleteModel, onMessage
        )
        SettingsSection.APPEARANCE -> AppearanceSection(appearance)
        SettingsSection.BACKGROUND -> BackgroundSection(state, runtime)
        SettingsSection.MAINTENANCE -> MaintenanceSection(runtime, onMessage)
    }
}

@Composable
private fun AccountSection(
    session: AccountSession,
    control: ControlState,
    media: MediaState,
    runtime: InferenceRuntime,
    onMessage: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var deviceName by remember(session.deviceName) { mutableStateOf(session.deviceName) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmPassword by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    GeoSectionLabel(null, "账号与设备", "本机保存")
    GeoInfoRow("账号名", session.username)
    GeoInfoRow("服务地址", session.endpoint)
    // 设备身份是最长的一串技术值，排在最后，日常查看时先看到人可读的三项。
    GeoInfoRow("设备身份", session.deviceId)

    GeoDivider()
    GeoSectionLabel(null, "设备名称", "${deviceName.length}/64")
    GeoFlatTextField(value = deviceName, onValueChange = { deviceName = it }, placeholder = "设备名称")
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = {
                scope.launch {
                    val failure = runCatching { runtime.account.renameDevice(deviceName) }.exceptionOrNull()?.message
                    if (failure == null) onMessage("设备名称已保存") else onMessage(failure)
                }
            },
            label = "保存名称",
            icon = LucideIcons.Check,
            enabled = deviceName.isNotBlank() && deviceName != session.deviceName
        )
        GeoKey(
            onClick = { deviceName = session.deviceName },
            label = "还原",
            icon = LucideIcons.RotateCcw,
            enabled = deviceName != session.deviceName
        )
    }

    GeoDivider()
    GeoSectionLabel(null, "修改密码", "新密码 ≥8 字符")
    GeoFlatTextField(
        value = currentPassword,
        onValueChange = { currentPassword = it },
        placeholder = "当前密码",
        keyboardType = KeyboardType.Password
    )
    GeoFlatTextField(
        value = newPassword,
        onValueChange = { newPassword = it },
        placeholder = "新密码",
        keyboardType = KeyboardType.Password
    )
    passwordError?.let { Text(it, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall) }
    GeoKey(
        onClick = { confirmPassword = true },
        label = "修改密码",
        icon = LucideIcons.LockKeyhole,
        enabled = currentPassword.isNotEmpty() && newPassword.length >= 8
    )

    GeoDivider()
    GeoSectionLabel(null, "会话", "退出会停止全部来源")
    GeoKey(
        onClick = { confirmLogout = true },
        label = "退出登录",
        icon = LucideIcons.LogOut,
        tone = GeoTone.Danger
    )

    if (confirmLogout) {
        GeoConfirm(
            title = "退出登录",
            text = "退出会停止全部来源并清除本机会话；本机参数与遮罩保留，下次登录仍可用。",
            confirmLabel = "退出",
            onCancel = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                scope.launch {
                    runtime.stopAll()
                    runtime.account.logout()
                }
            }
        )
    }
    if (confirmPassword) {
        GeoConfirm(
            title = "修改密码",
            text = "修改后所有设备都需要用新密码重新登录。",
            confirmLabel = "修改",
            onCancel = { confirmPassword = false },
            onConfirm = {
                confirmPassword = false
                scope.launch {
                    val failure = runCatching { runtime.account.changePassword(currentPassword, newPassword) }
                        .exceptionOrNull()?.message
                    if (failure == null) {
                        currentPassword = ""
                        newPassword = ""
                        passwordError = null
                        onMessage("密码已修改，请用新密码重新登录")
                    } else {
                        passwordError = failure
                    }
                }
            }
        )
    }
}

@Composable
private fun ConnectionSection(control: ControlState, media: MediaState, state: RuntimeUiState) {
    // 服务端下发 ISO-8601（UTC）：换成设备本地时间再显示，避免直接念一串 Z 时间。
    val serverTimeText = remember(control.serverTimeIso) {
        control.serverTimeIso?.let { iso ->
            runCatching {
                java.time.OffsetDateTime.parse(iso)
                    .atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
            }.getOrNull() ?: iso
        } ?: "未校时"
    }

    GeoDivider()
    GeoSectionLabel(null, "连接状态", "控制 ≠ 媒体 ≠ 回执")
    GeoInfoRow("控制通道", control.statusText + if (control.sourcesRegistered) "（来源已登记）" else "（来源未登记）")
    GeoInfoRow("媒体通道", media.statusText)
    GeoInfoRow("远程收帧", "已收 ${media.receivedFrames} · 丢弃 ${media.rejectedFrames}")
    GeoInfoRow("来源上限", "${control.maxSources} 路")
    GeoInfoRow("服务时间", serverTimeText)
    GeoInfoRow("时区", control.timeZone ?: "未下发")

    GeoDivider()
    GeoSectionLabel(null, "报警投递", "当前进程累计")
    GeoInfoRow("累计报警", "${state.alertCount} 次")
    GeoInfoRow("待服务回执", "${state.outboxSize} 条")
    GeoInfoRow("最后报警", com.xgwnje.visionguard.inference.ui.formatWallMoment(state.lastAlertAtMs))
    GeoHint("控制连接、媒体收帧与告警回执分别表示不同环节；服务回执不代表通知节点收件或声音播放。")
}

@Composable
private fun AppearanceSection(appearance: AppearancePreference) {
    GeoSectionLabel(null, "主题模式", "本机记住选择")
    GeoShifter(
        options = Appearance.entries.map { it.name to it.title },
        value = appearance.mode.name,
        onSelect = { name -> Appearance.entries.firstOrNull { it.name == name }?.let { appearance.select(it) } }
    )
    if (appearance.error.isNotEmpty()) {
        Text(appearance.error, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        ThemeSpecimen("浅色", listOf(0xFFF5F5F5, 0xFFFFFFFF, 0xFF202020, 0xFF087F83), Modifier.weight(1f))
        ThemeSpecimen("深色", listOf(0xFF121212, 0xFF1B1B1B, 0xFFF2F2F2, 0xFF4FBCC1), Modifier.weight(1f))
    }
    GeoHint("项目只有「跟随系统」会响应系统浅深色变化。")
}

@Composable
private fun ThemeSpecimen(title: String, colors: List<Long>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            colors.forEach { value ->
                Box(
                    Modifier
                        .size(width = 32.dp, height = 24.dp)
                        .background(androidx.compose.ui.graphics.Color(value.toInt()), RectangleShape)
                        .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                )
            }
        }
    }
}

@Composable
private fun BackgroundSection(state: RuntimeUiState, runtime: InferenceRuntime) {
    val context = LocalContext.current
    val cameraGranted = runtime.hasCameraPermission()
    val notificationGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val cameraStatus = state.cameraStatus

    GeoSectionLabel(null, "后台运行", "本机授权不可远程绕过")
    GeoHint(
        "有来源在运行时保持前台服务，界面退到后台或锁屏后推理继续；运行意图按来源保存，进程被系统回收后服务按意图恢复。"
    )
    GeoInfoRow("前台服务", if (state.runningSourceCount > 0) "运行中 · ${state.runningSourceCount} 路来源" else "未运行")
    GeoInfoRow(
        "通知权限",
        if (notificationGranted) "已授予" else "未授予 · Android 13 起前台服务通知需要授权",
        valueColor = if (notificationGranted) null else VisionGuardStatusColors.warning
    )
    GeoInfoRow(
        "相机权限",
        if (cameraGranted) "已授予" else "未授予 · 本机相机来源无法启动",
        valueColor = if (cameraGranted) null else VisionGuardStatusColors.warning
    )
    GeoInfoRow(
        "本机相机",
        when {
            cameraStatus.error != null -> "异常 · ${cameraStatus.error}"
            cameraStatus.running -> "采集中 · ${cameraStatus.width}×${cameraStatus.height} · 旋转 ${cameraStatus.rotation}°"
            else -> "未采集"
        },
        valueColor = if (cameraStatus.error != null) VisionGuardStatusColors.error else null
    )

    GeoDivider()
    GeoSectionLabel(null, "权限入口", "只给系统入口说明")
    GeoKey(
        onClick = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        },
        label = "打开系统应用设置",
        icon = LucideIcons.ExternalLink
    )
    GeoHint("相机与通知权限只能由本机用户在系统里授权；控制台不能远程绕过，应用也不会自行修改锁屏或唤醒设置。")
}

/** 崩溃与异常退出：本机记录 + 系统退出原因（含 native 崩溃与 ANR），可导出反馈。 */
@Composable
private fun CrashSection(onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CrashReporter.Entry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        entries = CrashReporter.history(context)
        loaded = true
    }

    GeoSectionLabel(null, "崩溃与退出记录", if (loaded) "${entries.size} 条" else "读取中")
    GeoHint("未捕获异常由本机处理器记录；native 崩溃、ANR 与内存回收从系统退出原因读取，并摘出与推理库相关的帧。")
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = { entries = CrashReporter.history(context) },
            label = "刷新",
            icon = LucideIcons.RefreshCw
        )
        GeoKey(
            onClick = {
                val path = CrashReporter.export(context, entries)
                onMessage(if (path != null) "已写入 $path" else "写入失败")
            },
            label = "写入日志",
            icon = LucideIcons.Pencil,
            enabled = entries.isNotEmpty()
        )
    }
    if (loaded && entries.isEmpty()) {
        Text("没有崩溃或异常退出记录。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    entries.take(6).forEachIndexed { index, entry ->
        val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(entry.time))
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RectangleShape)
                .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .clickable { expanded = if (expanded == index) null else index }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("$stamp · ${entry.kind}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(
                entry.summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded == index) 40 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (expanded == index) {
                entry.detail?.let { detail ->
                    Spacer(Modifier.height(Geo.Gap))
                    Text(
                        detail.take(1600),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    )
                }
            }
        }
        Spacer(Modifier.height(Geo.Gap))
    }
}

@Composable
private fun MaintenanceSection(runtime: InferenceRuntime, onMessage: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<JSONObject?>(null) }
    var confirmClean by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    if (UiPreviewFlags.available) {
        val mockData by UiPreviewFlags.mockData.collectAsState()
        GeoSectionLabel(null, "UI 验收", "仅预览构建")
        GeoShifter(
            options = listOf("off" to "真实数据", "on" to "模拟数据"),
            value = if (mockData) "on" else "off",
            onSelect = { UiPreviewFlags.setMockData(it == "on") }
        )
        GeoHint("模拟数据用一份覆盖多来源、异常态与长文本的快照渲染界面，方便检查常规、空态与边界；正式构建不显示此项。")
        GeoDivider()
    }

    GeoSectionLabel(null, "版本", "内测源码 0.x.y")
    GeoInfoRow("应用", AppConstants.APP_TITLE)
    GeoInfoRow("版本", AppConstants.VERSION)
    GeoHint("推理节点支持应用内更新；升级入口只下载并安装官方稳定版，不改变本机配置。")
    ClientUpdateButton(AppConstants.LOGIN_COMPONENT)

    GeoDivider()
    GeoSectionLabel(null, "临时缓存", "先盘点再清理")
    GeoHint("只盘点与清理截图、诊断导出与安装包暂存；模型、账号、配置与回滚资料不会被清理。")
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = {
                scope.launch {
                    busy = true
                    report = runtime.inspectTemporaryCache()
                    busy = false
                    if (report == null) onMessage("缓存盘点失败，请重试")
                }
            },
            label = "缓存盘点",
            icon = LucideIcons.Search,
            busy = busy
        )
        GeoKey(
            onClick = { confirmClean = true },
            label = "清理临时缓存",
            icon = LucideIcons.Trash2,
            tone = GeoTone.Danger
        )
    }
    report?.let { CacheReport(it) }

    GeoDivider()
    CrashSection(onMessage)

    if (isBenchmarkBuild()) {
        GeoDivider()
        AccelerationBenchmarkSection(onMessage)
    }

    if (confirmClean) {
        GeoConfirm(
            title = "清理临时缓存",
            text = "只清理已过期的截图、诊断导出与安装包暂存；模型、账号、配置、业务数据与回滚资料不会被清理。",
            confirmLabel = "清理",
            onCancel = { confirmClean = false },
            onConfirm = {
                confirmClean = false
                scope.launch {
                    busy = true
                    val result = runtime.cleanTemporaryCache()
                    busy = false
                    if (result == null) {
                        onMessage("缓存清理失败，请重试")
                    } else {
                        report = result
                        onMessage("缓存清理完成")
                    }
                }
            }
        )
    }
}

/** 测量入口是否可用：只开放给可调试构建，且排除 UI 预览包，避免随预览包流出。 */
@Composable
private fun isBenchmarkBuild(): Boolean {
    val context = LocalContext.current
    return BuildConfig.DEBUG && !context.packageName.endsWith(".uipreview")
}

/**
 * 自带的推理加速测量：模型与画面都在 APK 里，装完点一下就跑，不需要 adb。
 *
 * 只显示在可调试构建的「设置 → 维护」里；正式构建不显示，也不会自动运行。
 */
@Composable
private fun AccelerationBenchmarkSection(onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by BundledAccelerationBenchmark.state.collectAsState()

    GeoSectionLabel(null, "推理加速测量", "自带模型，无需连电脑")
    GeoHint(
        "把各推理档位（ONNX CPU / NNAPI，LiteRT CPU / GPU / NPU）在同一张真实画面上逐个跑一遍，" +
            "报单帧耗时中位数并给出是否达标（门槛：加速档 ≤ 同运行时 CPU 的 0.70×）。只在本机运行，不上传。"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = { scope.launch { BundledAccelerationBenchmark.run(context, onlyAccelerated = true) } },
            label = "快速测量",
            icon = LucideIcons.Play,
            busy = state.running,
            enabled = !state.running
        )
        GeoKey(
            onClick = { scope.launch { BundledAccelerationBenchmark.run(context, onlyAccelerated = false) } },
            label = "完整测量",
            icon = LucideIcons.Activity,
            busy = state.running,
            enabled = !state.running
        )
    }
    GeoHint("快速测量只跑加速档与其 CPU 基线；完整测量另跑逐线程 CPU 档，更慢。新机型首次跑 GPU 档可能要几十秒编译模型，属正常现象。")

    state.failure?.let { GeoHint("测量失败：$it", tone = GeoLampTone.Error) }

    if (state.running || state.current.isNotEmpty()) {
        GeoFieldLabel("进度")
        Text(
            state.current,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }

    if (state.report.isNotEmpty()) {
        GeoFieldLabel("结果")
        GeoSurface(Modifier.fillMaxWidth()) {
            Text(
                state.report,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(Geo.Gap)
            )
        }
        state.reportPath?.let { GeoHint("原始报告已写入：$it") }
        Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            GeoKey(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "推理加速测量结果")
                        putExtra(Intent.EXTRA_TEXT, state.report)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "分享测量结果"))
                    }.onFailure { onMessage("无法打开分享：${it.message}") }
                },
                label = "分享结果",
                icon = LucideIcons.ExternalLink
            )
            GeoKey(
                onClick = {
                    BundledAccelerationBenchmark.reset()
                    onMessage("已清空上次结果")
                },
                label = "清空",
                icon = LucideIcons.Trash2,
                tone = GeoTone.Danger
            )
        }
    }
}

@Composable
private fun CacheReport(report: JSONObject) {
    val categories = report.optJSONArray("categories")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (categories != null) {
            for (index in 0 until categories.length()) {
                val item = categories.optJSONObject(index) ?: continue
                val files = item.optLong("files")
                val bytes = item.optLong("bytes")
                val cleanableFiles = item.optLong("cleanableFiles")
                val cleanableBytes = item.optLong("cleanableBytes")
                val label = item.optString("label").ifEmpty { item.optString("id") }
                GeoInfoRow(
                    label.take(10),
                    "$files 项 / ${formatBytes(bytes)} · 可清理 $cleanableFiles 项 / ${formatBytes(cleanableBytes)}",
                    labelWidth = 96
                )
            }
        }
        val removedFiles = report.optLong("removedFiles")
        val removedBytes = report.optLong("removedBytes")
        val failedFiles = report.optLong("failedFiles")
        Text(
            if (removedFiles > 0 || failedFiles > 0) {
                "本次清理 $removedFiles 项 / ${formatBytes(removedBytes)}${if (failedFiles > 0) " · 失败 $failedFiles 项" else ""}"
            } else {
                "盘点结果：当前没有可清理的过期项。"
            },
            color = if (failedFiles > 0) VisionGuardStatusColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

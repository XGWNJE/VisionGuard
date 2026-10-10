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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.xgwnje.visionguard.inference.net.ControlState
import com.xgwnje.visionguard.inference.net.MediaState
import com.xgwnje.visionguard.inference.model.AccelerationDiagnostics
import com.xgwnje.visionguard.inference.model.AccelerationMode
import com.xgwnje.visionguard.inference.runtime.AcceleratorSettings
import com.xgwnje.visionguard.inference.runtime.CrashReporter
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private enum class SettingsSection(val title: String) {
    ACCOUNT("账号"),
    MODEL("模型"),
    ACCELERATION("推理"),
    APPEARANCE("外观"),
    BACKGROUND("后台"),
    MAINTENANCE("维护")
}

/**
 * 全局设置抽屉（宽 ≤760）：五个分区、独立滚动、应用级危险操作二次确认。
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
    onMessage: (String?) -> Unit
) {
    var section by remember { mutableStateOf(SettingsSection.ACCOUNT) }
    GeoDrawer(
        title = "全局设置",
        sectionId = "VISION / SETTINGS",
        onClose = onClose,
        width = Geo.ParamDrawer
    ) {
        GeoShifter(
            options = SettingsSection.entries.map { it.name to it.title },
            value = section.name,
            onSelect = { name -> SettingsSection.entries.firstOrNull { it.name == name }?.let { section = it } }
        )
        when (section) {
            SettingsSection.ACCOUNT -> AccountSection(session, control, media, runtime, onMessage)
            SettingsSection.MODEL -> ModelSection(state, runtime, onMessage)
            SettingsSection.ACCELERATION -> AccelerationSection(state, onMessage)
            SettingsSection.APPEARANCE -> AppearanceSection(appearance)
            SettingsSection.BACKGROUND -> BackgroundSection(state, runtime)
            SettingsSection.MAINTENANCE -> MaintenanceSection(runtime, onMessage)
        }
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

    GeoSectionLabel("01", "账号与设备", "本机保存")
    GeoInfoRow("设备名称", session.deviceName)
    GeoInfoRow("账号名", session.username)
    GeoInfoRow("设备身份", session.deviceId)
    GeoInfoRow("服务地址", session.endpoint)

    GeoDivider()
    GeoSectionLabel("02", "连接状态", "控制 ≠ 媒体 ≠ 回执")
    GeoInfoRow("控制通道", control.statusText + if (control.sourcesRegistered) "（来源已登记）" else "（来源未登记）")
    GeoInfoRow("媒体通道", media.statusText)
    GeoInfoRow("远程收帧", "已收 ${media.receivedFrames} · 丢弃 ${media.rejectedFrames}")
    GeoInfoRow("来源上限", "${control.maxSources} 路")
    GeoInfoRow("服务时间", control.serverTimeIso ?: "未校时")
    GeoInfoRow("时区", control.timeZone ?: "未下发")

    GeoDivider()
    GeoSectionLabel("03", "设备名称", "${deviceName.length}/64")
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
    GeoSectionLabel("04", "修改密码", "新密码 ≥8 字符")
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
    GeoSectionLabel("05", "会话", "退出会停止全部来源")
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
private fun ModelSection(
    state: RuntimeUiState,
    runtime: InferenceRuntime,
    onMessage: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val backend = state.sources.firstOrNull { it.state.isRunning }?.state?.backend
        ?: state.sources.firstOrNull()?.state?.backend.orEmpty()
    val totalMasks = state.sources.sumOf { it.masks.size }

    GeoSectionLabel("01", "推理状态", "逐来源上报")
    GeoInfoRow("推理后端", backend.ifBlank { "未开始推理" })
    GeoInfoRow("运行来源", "${state.runningSourceCount} / ${state.sources.size} 路")
    GeoInfoRow("遮罩合计", "$totalMasks 个")
    GeoInfoRow("报警发件箱", "${state.outboxSize} 条待回执")
    GeoHint("默认尝试 NNAPI 加速，会话创建失败时回落 CPU；具体以每个来源上报的后端为准。")

    GeoDivider()
    GeoSectionLabel("02", "模型资源", "按需下载")
    GeoHint("模型按需从统一服务下载，不随安装包分发；长度校验通过后才替换正式文件。使用中的模型不可删除。")
    state.models.forEach { model ->
        val used = state.sources.any { it.state.isRunning && it.params.modelKey == model.key }
        val progress = state.downloads[model.key]
        var sizeText by remember(model.key, model.downloaded) { mutableStateOf("") }
        LaunchedEffect(model.key, model.downloaded) {
            sizeText = withContext(Dispatchers.IO) {
                val file = runtime.modelStore().file(model.key)
                if (file.isFile) "${file.length() / 1024 / 1024} MiB" else ""
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = Geo.Touch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        progress != null && progress.total > 0 -> "下载 ${progress.percent}%"
                        progress != null -> "下载中…"
                        model.downloaded -> "已下载${if (sizeText.isNotEmpty()) " · $sizeText" else ""}"
                        else -> "未下载"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            when {
                progress != null -> Text(
                    if (progress.total > 0) "${progress.percent}%" else "下载中",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                model.downloaded -> GeoKey(
                    onClick = { pendingDelete = model.key },
                    label = if (used) "使用中" else "删除",
                    icon = LucideIcons.Trash2,
                    tone = GeoTone.Secondary,
                    enabled = !used
                )

                else -> GeoKey(
                    onClick = { scope.launch { runtime.downloadModel(model.key)?.let(onMessage) } },
                    label = "下载",
                    icon = LucideIcons.Download
                )
            }
        }
        progress?.let {
            if (it.total > 0) {
                Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)) {
                    Box(
                        Modifier
                            .fillMaxWidth(it.percent / 100f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RectangleShape)
                    )
                }
            }
        }
        GeoDivider()
    }

    pendingDelete?.let { key ->
        val name = state.models.firstOrNull { it.key == key }?.displayName ?: key
        GeoConfirm(
            title = "删除模型",
            text = "删除 $name 的本机文件？下次使用需要重新下载；账号、配置与遮罩不受影响。",
            confirmLabel = "删除",
            onCancel = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                scope.launch { runtime.deleteModel(key)?.let(onMessage) }
            }
        )
    }
}

@Composable
private fun AppearanceSection(appearance: AppearancePreference) {
    GeoSectionLabel("01", "主题模式", "本机记住选择")
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
    GeoHint("项目色板与统一 UI 规范同源；只有「跟随系统」会响应系统浅深色变化。")
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

    GeoSectionLabel("01", "后台运行", "本机授权不可远程绕过")
    GeoHint(
        "有来源在运行时保持前台服务，界面退到后台或锁屏后推理继续；运行意图按来源保存，进程被系统回收后服务按意图恢复。"
    )
    GeoInfoRow("前台服务", if (state.runningSourceCount > 0) "运行中 · ${state.runningSourceCount} 路来源" else "未运行")
    GeoInfoRow("运行来源", "${state.runningSourceCount} / ${state.sources.size} 路")
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
    GeoInfoRow("远程来源", "${state.sources.count { it.mode == SourceCaptureMode.RemoteStream }} 路")

    GeoDivider()
    GeoSectionLabel("02", "权限入口", "只给系统入口说明")
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

/** 推理：当前后端与单帧耗时、加速方案切换、自动探测记录。 */
@Composable
private fun AccelerationSection(state: RuntimeUiState, onMessage: (String?) -> Unit) {
    val mode by AcceleratorSettings.mode.collectAsState()
    val resolved by AcceleratorSettings.autoResolved.collectAsState()
    val running = state.sources.filter { it.state.isRunning }
    val backend = running.firstOrNull()?.state?.backend?.ifEmpty { null }
        ?: state.sources.firstOrNull()?.state?.backend?.ifEmpty { null }
    val inferenceMs = running.firstOrNull()?.state?.inferenceMs ?: 0L

    GeoSectionLabel("01", "当前推理", if (running.isEmpty()) "未运行" else "运行中")
    GeoInfoRow("生效后端", backend ?: "未开始推理")
    GeoInfoRow("单帧耗时", if (inferenceMs > 0) "$inferenceMs ms" else "—")
    GeoInfoRow("实际帧率", if (running.isEmpty()) "—" else "%.1f FPS".format(running.first().state.actualFps))
    GeoHint("方案是冷设置：切换后要停止再启动来源才生效；运行中的来源不会被隐式停止。")

    GeoDivider()
    GeoSectionLabel("02", "加速方案", "手动 / 自动")
    AccelerationMode.selectable.forEach { option ->
        val selected = option == mode
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    RectangleShape
                )
                .border(
                    if (selected) 2.dp else Geo.Border,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RectangleShape
                )
                .geoFocusable()
                .clickable {
                    AcceleratorSettings.setMode(option)
                    onMessage("加速方案已切换为 ${option.title}，停止并重新启动来源后生效")
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                option.title,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                option.detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(Geo.Gap))
    }

    GeoDivider()
    val currentModel = state.sources.firstOrNull { it.state.isRunning }?.params?.modelKey
        ?: state.sources.firstOrNull()?.params?.modelKey
    val picked = currentModel?.let { AcceleratorSettings.resolvedForModel(it) }
    val pickedMs = currentModel?.let { AcceleratorSettings.measuredMsForModel(it) }
    GeoSectionLabel("03", "自动探测", picked?.title ?: resolved?.title ?: "未记录")
    GeoHint(
        if (picked != null) {
            "自动模式已按当前模型实测择优为 ${picked.title}" + (pickedMs?.let { "（实测 $it ms / 帧）" } ?: "") + "；换模型或重新探测会重新测。"
        } else {
            "自动模式对当前模型实测两档：NNAPI · FP16 与 CPU · 4 线程各跑两帧，取更快的一档并记住；手动指定时不做探测。"
        }
    )
    GeoDivider()
    AccelerationDiagnostics(state, onMessage)

    GeoDivider()
    GeoKey(
        onClick = {
            AcceleratorSettings.forgetResolved()
            onMessage("已清除自动探测记录，下次启动来源会重新逐档探测")
        },
        label = "重新探测",
        icon = LucideIcons.RotateCcw,
        enabled = resolved != null
    )
}

/** 加速诊断：逐方案测一遍，结果显示在界面并可写入本机日志。 */
@Composable
private fun AccelerationDiagnostics(state: RuntimeUiState, onMessage: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val modelKey = state.sources.firstOrNull()?.params?.modelKey
        ?: state.sources.firstOrNull { it.state.isRunning }?.params?.modelKey
    val model = state.models.firstOrNull { it.key == modelKey }
    val modelFile = remember(modelKey) {
        modelKey?.let { java.io.File(java.io.File(context.filesDir, "models"), "$it.onnx") }
    }

    GeoSectionLabel("04", "加速诊断", "逐方案测耗时")
    Text(
        "逐个方案建一次会话并连续跑 6 帧，输出可用性、失败原因与单帧耗时；模拟器没有系统加速器，NNAPI 一档通常会失败，这是预期结果。诊断期间不要启动检测。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = {
                val file = modelFile
                if (file == null || !file.isFile) {
                    onMessage("先下载一个模型，再做加速诊断")
                    return@GeoKey
                }
                scope.launch {
                    busy = true
                    report = AccelerationDiagnostics.run(file, AcceleratorSettings.mode.value, AcceleratorSettings.autoResolved.value)
                    busy = false
                    onMessage("加速诊断完成，可截图反馈或写入本机日志")
                }
            },
            label = "运行诊断",
            icon = LucideIcons.Activity,
            tone = GeoTone.Primary,
            busy = busy
        )
        GeoKey(
            onClick = {
                val text = report
                if (text == null) {
                    onMessage("先运行诊断")
                } else {
                    val path = AccelerationDiagnostics.writeReport(context, text)
                    onMessage(if (path != null) "已写入 $path" else "写入失败")
                }
            },
            label = "写入日志",
            icon = LucideIcons.Pencil,
            enabled = report != null && !busy
        )
    }
    if (modelFile == null || !modelFile.isFile) {
        GeoHint("没有可用的模型文件：先在「模型」分区下载一个再诊断。", GeoLampTone.Warning)
    } else {
        GeoInfoRow("诊断模型", model?.displayName ?: modelFile.name)
    }
    report?.let { text ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RectangleShape)
                .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                .padding(12.dp)
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                softWrap = true
            )
        }
    }
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

    GeoSectionLabel("05", "崩溃与退出记录", if (loaded) "${entries.size} 条" else "读取中")
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
        GeoSectionLabel("00", "UI 验收", "仅预览构建")
        GeoShifter(
            options = listOf("off" to "真实数据", "on" to "模拟数据"),
            value = if (mockData) "on" else "off",
            onSelect = { UiPreviewFlags.setMockData(it == "on") }
        )
        GeoHint("模拟数据用一份覆盖多来源、异常态与长文本的快照渲染界面，方便检查常规、空态与边界；正式构建不显示此项。")
        GeoDivider()
    }

    GeoSectionLabel("01", "版本", "内测源码 0.x.y")
    GeoInfoRow("应用", AppConstants.APP_TITLE)
    GeoInfoRow("版本", AppConstants.VERSION)
    GeoHint("android-inference 当前没有更新资产；升级入口只下载并安装官方稳定版，不改变本机配置。")
    ClientUpdateButton(AppConstants.LOGIN_COMPONENT)

    GeoDivider()
    GeoSectionLabel("02", "临时缓存", "先盘点再清理")
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

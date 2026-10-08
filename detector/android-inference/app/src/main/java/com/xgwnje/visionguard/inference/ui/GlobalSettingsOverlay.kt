package com.xgwnje.visionguard.inference.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AppearancePreference
import com.xgwnje.visionguard.account.AppearanceSelector
import com.xgwnje.visionguard.account.ClientUpdateButton
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.AppConstants
import com.xgwnje.visionguard.inference.net.ControlState
import com.xgwnje.visionguard.inference.net.MediaState
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 全局设置的五个分类。 */
enum class SettingsCategory(val title: String) {
    ACCOUNT("账号与连接"),
    MODEL("推理与模型"),
    APPEARANCE("外观"),
    BACKGROUND("后台运行"),
    MAINTENANCE("版本与维护")
}

/**
 * 全局设置：覆盖当前窗口内容区，返回后保留原来的来源选择。
 *
 * 宽屏左侧分类、右侧独立滚动；窄屏分类改为可横向滚动的选择行。
 */
@Composable
fun GlobalSettingsOverlay(
    session: AccountSession,
    state: RuntimeUiState,
    control: ControlState,
    media: MediaState,
    appearance: AppearancePreference,
    runtime: InferenceRuntime,
    onBack: () -> Unit,
    onMessage: (String?) -> Unit
) {
    var category by remember { mutableStateOf(SettingsCategory.ACCOUNT) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(UiTokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.ArrowLeft, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("全局设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 720.dp
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
                        Column(
                            Modifier.width(180.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            SettingsCategory.entries.forEach { item ->
                                FilterChip(
                                    selected = category == item,
                                    onClick = { category = item },
                                    label = { Text(item.title, maxLines = 1) },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                )
                            }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            SettingsCategoryBody(category, session, state, control, media, appearance, runtime, scope, onMessage)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)
                        ) {
                            SettingsCategory.entries.forEach { item ->
                                FilterChip(
                                    selected = category == item,
                                    onClick = { category = item },
                                    label = { Text(item.title, maxLines = 1) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                )
                            }
                        }
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SettingsCategoryBody(category, session, state, control, media, appearance, runtime, scope, onMessage)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCategoryBody(
    category: SettingsCategory,
    session: AccountSession,
    state: RuntimeUiState,
    control: ControlState,
    media: MediaState,
    appearance: AppearancePreference,
    runtime: InferenceRuntime,
    scope: kotlinx.coroutines.CoroutineScope,
    onMessage: (String?) -> Unit
) {
    when (category) {
        SettingsCategory.ACCOUNT -> AccountCategory(session, control, media)
        SettingsCategory.MODEL -> ModelCategory(state, runtime, scope, onMessage)
        SettingsCategory.APPEARANCE -> SectionCard { SectionTitle("外观"); AppearanceSelector(appearance) }
        SettingsCategory.BACKGROUND -> BackgroundCategory(state, runtime)
        SettingsCategory.MAINTENANCE -> SectionCard {
            SectionTitle("版本与维护")
            InfoRow("应用", AppConstants.APP_TITLE, labelWidth = 96)
            InfoRow("版本", AppConstants.VERSION, labelWidth = 96)
            SecondaryText("内测版本源码为 0.x.y；升级入口只下载并安装官方稳定版，不改变本机配置。")
            ClientUpdateButton(AppConstants.LOGIN_COMPONENT)
        }
    }
}

@Composable
private fun AccountCategory(session: AccountSession, control: ControlState, media: MediaState) {
    SectionCard {
        SectionTitle("账号与连接")
        InfoRow("设备名称", session.deviceName, labelWidth = 96)
        SecondaryText("设备名称在右上角账号菜单里修改。")
        InfoRow("账号名", session.username, labelWidth = 96)
        InfoRow("设备身份", session.deviceId, labelWidth = 96)
        InfoRow("服务地址", session.endpoint, labelWidth = 96)
    }
    SectionCard {
        SectionTitle("连接状态")
        InfoRow("控制通道", control.statusText + if (control.sourcesRegistered) "（来源已登记）" else "（来源未登记）", labelWidth = 96)
        InfoRow("媒体通道", media.statusText, labelWidth = 96)
        InfoRow("远程收帧", "已收 ${media.receivedFrames} · 丢弃 ${media.rejectedFrames}", labelWidth = 96)
        InfoRow("来源上限", "${control.maxSources} 路", labelWidth = 96)
        InfoRow("服务时间", control.serverTimeIso ?: "未校时", labelWidth = 96)
        InfoRow("时区", control.timeZone ?: "未下发", labelWidth = 96)
    }
}

@Composable
private fun ModelCategory(
    state: RuntimeUiState,
    runtime: InferenceRuntime,
    scope: kotlinx.coroutines.CoroutineScope,
    onMessage: (String?) -> Unit
) {
    val backend = state.sources.firstOrNull { it.state.isRunning }?.state?.backend
        ?: state.sources.firstOrNull()?.state?.backend.orEmpty()
    val totalMasks = state.sources.sumOf { it.masks.size }

    SectionCard {
        SectionTitle("推理与模型")
        InfoRow("推理后端", backend.ifBlank { "未开始推理" }, labelWidth = 96)
        SecondaryText("默认尝试 NNAPI 加速，会话创建失败时回落 CPU；具体以每个来源上报的后端为准。")
        InfoRow("运行来源", "${state.runningSourceCount} / ${state.sources.size} 路", labelWidth = 96)
        InfoRow("遮罩合计", "$totalMasks 个", labelWidth = 96)
        InfoRow("检测参数", "冷改动：置信度 / 采样频率 / 报警冷却 / 模型 / 检测目标", labelWidth = 96)
        InfoRow("报警发件箱", "${state.outboxSize} 条待回执", labelWidth = 96)
        state.recoveryWarning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VisionGuardStatusColors.warning) }
    }
    SectionCard {
        SectionTitle("模型资源")
        SecondaryText("模型按需从统一服务下载，不随安装包分发；长度校验通过后才替换正式文件。")
        for (model in state.models) {
            ModelRow(model.key, model.displayName, model.downloaded, state, runtime, scope, onMessage)
        }
    }
}

@Composable
private fun ModelRow(
    key: String,
    displayName: String,
    downloaded: Boolean,
    state: RuntimeUiState,
    runtime: InferenceRuntime,
    scope: kotlinx.coroutines.CoroutineScope,
    onMessage: (String?) -> Unit
) {
    var sizeText by remember(key, downloaded) { mutableStateOf("") }
    LaunchedEffect(key, downloaded) {
        sizeText = withContext(Dispatchers.IO) {
            val file = runtime.modelStore().file(key)
            if (file.isFile) "${file.length() / 1024 / 1024} MiB" else ""
        }
    }
    val progress = state.downloads[key]
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (downloaded) "已下载${if (sizeText.isNotEmpty()) " · $sizeText" else ""}" else "未下载",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                progress != null -> Text(if (progress.total > 0) "${progress.percent}%" else "下载中…", style = MaterialTheme.typography.bodySmall)
                downloaded -> TextButton(
                    onClick = { scope.launch { runtime.deleteModel(key)?.let(onMessage) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
                else -> TextButton(
                    onClick = { scope.launch { runtime.downloadModel(key)?.let(onMessage) } },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("下载") }
            }
        }
        progress?.let {
            LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun BackgroundCategory(state: RuntimeUiState, runtime: InferenceRuntime) {
    val context = LocalContext.current
    val cameraGranted = runtime.hasCameraPermission()
    val notificationGranted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val cameraStatus = state.cameraStatus

    SectionCard {
        SectionTitle("后台运行")
        SecondaryText(
            "有来源在运行时保持前台服务，界面退到后台或锁屏后推理继续；退出界面不会停止推理。运行意图按来源保存，进程被系统回收后服务按意图恢复来源。"
        )
        InfoRow("前台服务", if (state.runningSourceCount > 0) "运行中 · ${state.runningSourceCount} 路来源" else "未运行", labelWidth = 96)
        InfoRow("运行来源", "${state.runningSourceCount} / ${state.sources.size} 路", labelWidth = 96)
        InfoRow(
            "通知权限",
            if (notificationGranted) "已授予" else "未授予 · Android 13 起前台服务通知需要授权",
            labelWidth = 96,
            valueColor = if (notificationGranted) MaterialTheme.colorScheme.onSurface else VisionGuardStatusColors.warning
        )
        InfoRow(
            "相机权限",
            if (cameraGranted) "已授予" else "未授予 · 本机相机来源无法启动",
            labelWidth = 96,
            valueColor = if (cameraGranted) MaterialTheme.colorScheme.onSurface else VisionGuardStatusColors.warning
        )
        InfoRow(
            "本机相机",
            when {
                cameraStatus.error != null -> "异常 · ${cameraStatus.error}"
                cameraStatus.running -> "采集中 · ${cameraStatus.width}×${cameraStatus.height} · 旋转 ${cameraStatus.rotation}°"
                else -> "未采集"
            },
            labelWidth = 96,
            valueColor = if (cameraStatus.error != null) VisionGuardStatusColors.error else MaterialTheme.colorScheme.onSurface
        )
        InfoRow("远程来源", "${state.sources.count { it.mode == SourceCaptureMode.RemoteStream }} 路", labelWidth = 96)
        SecondaryText("保持后台推理：授予通知与相机权限，并把应用加入电池优化白名单；Android 不会注册登录自启，也不允许远程开关主程序。")
    }
}

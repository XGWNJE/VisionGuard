package com.xgwnje.visionguard.detector

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.AccountStore
import com.xgwnje.visionguard.account.AccountLogin
import com.xgwnje.visionguard.account.ApplicationOptions
import com.xgwnje.visionguard.account.AppearancePreference
import com.xgwnje.visionguard.detector.stream.PublisherState
import com.xgwnje.visionguard.icons.LucideIcons

@Composable
internal fun CameraHome(state: PublisherState, preview: Bitmap?, streaming: Boolean, highResolution: Boolean,
    dim: Boolean, hidden: Boolean, captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, onStart: () -> Unit,
    onStop: () -> Unit, onBind: (String) -> Unit, onRefresh: () -> Unit, onResolution: (Boolean) -> Unit,
    onDim: (Boolean) -> Unit, onHidePreview: (Boolean) -> Unit, message: String = "", header: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        CameraControlPanel(modifier, state, streaming, highResolution, dim, captureSize, sentSize, message,
            onStart, onStop, onBind, onRefresh, onResolution, onDim)
    }
    // Both regions keep their geometry when preview visibility, connection or stream state changes.
    Column(Modifier.fillMaxSize()) {
        header()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.weight(3f).fillMaxHeight())
                CameraPreview(preview, streaming, hidden, sentSize, onHidePreview, Modifier.weight(2f).fillMaxHeight())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                controls(Modifier.weight(1.7f).fillMaxWidth())
                CameraPreview(preview, streaming, hidden, sentSize, onHidePreview, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CameraControlPanel(modifier: Modifier, state: PublisherState,
    streaming: Boolean, highResolution: Boolean, dim: Boolean,
    captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, message: String, onStart: () -> Unit, onStop: () -> Unit,
    onBind: (String) -> Unit, onRefresh: () -> Unit, onResolution: (Boolean) -> Unit,
    onDim: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier.semantics { contentDescription = "控制和状态区" }, colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("推流控制", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.small, color = if (streaming) colors.primary else colors.surfaceVariant,
                contentColor = if (streaming) colors.onPrimary else colors.onSurface) {
                Text(if (!state.connected) "连接中" else if (streaming) "推流中" else "未推流", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val compact = maxHeight < 260.dp
        val rowGap = if (maxHeight >= 300.dp) 12.dp else 8.dp
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(rowGap)) {
            CameraStatistics(state, compact)
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = colors.error)
            if (!state.connected || state.status.contains("失败") || state.status.contains("无法") || state.status.contains("超时")) {
                Text(state.status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            CameraTarget(state, streaming, onBind, onRefresh)
            CameraSettings(streaming, highResolution, dim, rowGap, onResolution, onDim)
            if (highResolution && captureSize != null && sentSize != null && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720)) {
                Text("当前摄像头已回退至 ${sentSize.first}×${sentSize.second}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        }
        if (streaming) Button(onStop, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
            colors = VisionGuardControlColors.button(),
            shape = MaterialTheme.shapes.small) { Icon(LucideIcons.Square, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("停止推流") }
        else Button(onStart, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
            enabled = state.connected && state.stream?.targetDeviceId != null,
            colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Icon(LucideIcons.Play, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("开始推流") }
    }
}

@Composable
private fun CameraPreview(preview: Bitmap?, streaming: Boolean, hidden: Boolean, sentSize: Pair<Int, Int>?, onHidePreview: (Boolean) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier.semantics { contentDescription = "预览区域" }, colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(LucideIcons.Camera, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Text("本机预览", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(!hidden, { onHidePreview(!it) }, modifier = Modifier.semantics { contentDescription = "本机预览开关" })
        }
        HorizontalDivider(color = colors.outlineVariant)
        Surface(Modifier.weight(1f).fillMaxWidth(), color = colors.surfaceVariant) {
            if (!hidden && preview != null) Image(preview.asImageBitmap(), "实时摄像头画面",
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (hidden) LucideIcons.EyeOff else LucideIcons.Camera, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(if (hidden) "预览已关闭" else if (streaming) "等待画面" else "暂无画面", style = MaterialTheme.typography.bodyMedium)
                if (hidden && streaming) Text("推流仍在继续", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Text(if (streaming && sentSize != null) "发送 ${sentSize.first}×${sentSize.second}" else "等比预览",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun CameraTarget(state: PublisherState, streaming: Boolean, onBind: (String) -> Unit, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf("") }
    val selected = state.targets.find { it.deviceId == state.stream?.targetDeviceId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraFieldLabel("目标")
            Box(Modifier.weight(1f)) {
                OutlinedButton({ candidate = state.stream?.targetDeviceId.orEmpty(); choosing = true }, enabled = !streaming && state.connected && state.targets.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    colors = VisionGuardControlColors.outlinedButton(contentColor = colors.onSurface), border = BorderStroke(1.dp, colors.outlineVariant)) {
                    val label = selected?.deviceName ?: when {
                        !state.connected -> "未连接"
                        state.targetsLoading -> "加载中…"
                        state.targetsLoadFailed -> "加载失败"
                        state.targets.isEmpty() -> "暂无节点"
                        else -> "选择视觉推理节点"
                    }
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(LucideIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(16.dp).rotate(90f))
                }
            }
            OutlinedIconButton(onRefresh, enabled = state.connected && !state.targetsLoading,
                modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                Icon(LucideIcons.RefreshCw, "刷新目标", Modifier.size(20.dp))
            }
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false },
        title = { Text("选择视觉推理节点") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.targets.forEach { target ->
                OutlinedButton({ candidate = target.deviceId }, enabled = !streaming && state.connected,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, if (candidate == target.deviceId) colors.primary else colors.outlineVariant),
                    colors = VisionGuardControlColors.outlinedButton(contentColor = colors.onSurface)) {
                    Column(Modifier.weight(1f)) {
                        Text(target.deviceName, style = MaterialTheme.typography.bodyMedium)
                        Text((if (target.online) "可关联" else "离线") + if (target.platform.isNotBlank()) " · ${target.platform}" else "",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (candidate == target.deviceId) Icon(LucideIcons.Check, null, Modifier.padding(start = 8.dp).size(16.dp))
                }
            }
        } },
        confirmButton = { TextButton({ onBind(candidate); choosing = false }, enabled = candidate.isNotBlank() && !streaming && state.connected) { Text("关联此节点") } },
        dismissButton = { TextButton({ choosing = false }) { Text("返回") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraSettings(streaming: Boolean, highResolution: Boolean, dim: Boolean,
    rowGap: Dp, onResolution: (Boolean) -> Unit, onDim: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraFieldLabel("规格")
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f).heightIn(min = 48.dp)) {
                listOf(false to "640×480", true to "720P").forEachIndexed { index, (resolution, title) ->
                    SegmentedButton(selected = highResolution == resolution, onClick = { onResolution(resolution) }, enabled = !streaming,
                        shape = SegmentedButtonDefaults.itemShape(index, 2, MaterialTheme.shapes.small), icon = {},
                        label = { Text(title, style = MaterialTheme.typography.bodyMedium) })
                }
            }
        }
        CameraSetting("降低亮度", dim, onDim)
    }
}

@Composable
private fun CameraSetting(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
        shape = MaterialTheme.shapes.small, color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
    }
}

@Composable
private fun CameraFieldLabel(label: String) {
    Text(label, Modifier.width((36 * LocalDensity.current.fontScale).dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CameraStatistics(state: PublisherState, compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("本机入队" to state.sentFrames, "服务收帧" to state.acknowledgedFrames).forEachIndexed { index, (label, value) ->
                if (index > 0) VerticalDivider(Modifier.height(if (compact) 24.dp else 60.dp), color = colors.outlineVariant)
                if (compact) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("$value", style = MaterialTheme.typography.titleMedium)
                } else Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("$value", style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("本机丢弃 ${state.droppedFrames}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("服务丢弃 ${state.relayDroppedFrames}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun CameraHelpButton(state: PublisherState, captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, highResolution: Boolean) {
    var open by remember { mutableStateOf(false) }
    IconButton({ open = true }, Modifier.size(48.dp)) {
        Icon(LucideIcons.CircleHelp, "相机帮助", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) Dialog(onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("相机帮助", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton({ open = false }, Modifier.heightIn(min = 48.dp)) { Text("返回") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 720.dp)
                    .padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CameraHelpSection("开始推流", "相机与视觉推理节点登录同一账号，选择目标后开始。首次使用需要允许摄像头；暂无目标时先在视觉推理节点登录，加载失败可刷新。推流期间不能更换目标或规格。")
                    Text("当前状态：${state.status}", style = MaterialTheme.typography.bodySmall)
                    CameraHelpSection("画面与规格", "640×480 / 720P 是规格上限，最高 5 帧/秒。实际采集和发送尺寸由摄像头能力决定，不支持时会回退；画面等比显示，横竖屏切换保持推流并更新方向。")
                    if (captureSize != null && sentSize != null) {
                        val fallback = highResolution && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720)
                        Text("采集 ${captureSize.first}×${captureSize.second} · 发送 ${sentSize.first}×${sentSize.second}" + if (fallback) " · 回退" else "", style = MaterialTheme.typography.bodySmall)
                    }
                    CameraHelpSection("亮度与预览", "降低亮度仅在推流时调整当前窗口；停止后恢复。关闭预览只隐藏本机画面，推流继续，区域尺寸和位置不变。外观在设置中独立选择。")
                    CameraHelpSection("统计", "本机入队表示发送队列已接纳；服务收帧表示中继已收到，不代表完成推理。本机丢弃与服务丢弃分别统计，主动限帧未采样不算丢弃。")
                    Text("本机入队 ${state.sentFrames} · 服务收帧 ${state.acknowledgedFrames}", style = MaterialTheme.typography.bodySmall)
                    Text("本机丢弃 ${state.droppedFrames} · 服务丢弃 ${state.relayDroppedFrames}", style = MaterialTheme.typography.bodySmall)
                    Text("当前主动未采样 ${state.sampledOutFrames} 帧", style = MaterialTheme.typography.bodySmall)
                    state.stream?.sourceName?.takeIf { it.isNotBlank() }?.let {
                        Text("当前推理来源：$it", style = MaterialTheme.typography.bodySmall)
                    }
                    CameraHelpSection("前台与账号", "离开应用或锁屏会停止推流，回到前台需手动开始。控制台远程开始同样需要已关联目标、相机权限和前台运行。账号入口可查看身份、修改设备名或密码及退出。")
                }
            }
        }
    }
}

@Composable
private fun CameraHelpSection(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CameraLoginScreen(account: AccountStore, appearance: AppearancePreference) {
    BoxWithConstraints(Modifier.fillMaxSize().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
        OutlinedCard(Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight),
            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(LucideIcons.Camera, null, Modifier.size(24.dp))
                Text("相机推流节点", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                ApplicationOptions(appearance, "android-camera", Modifier, compact = true)
            }
            Box(Modifier.weight(1f, fill = false)) { AccountLogin(account, "登录", "android-camera", compact = true) }
        }
    }
}

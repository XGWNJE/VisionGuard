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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.detector.stream.PublisherState
import com.xgwnje.visionguard.icons.LucideIcons

@Composable
internal fun CameraHome(state: PublisherState, preview: Bitmap?, streaming: Boolean, highResolution: Boolean,
    dim: Boolean, hidden: Boolean, captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, onStart: () -> Unit,
    onStop: () -> Unit, onBind: (String) -> Unit, onRefresh: () -> Unit, onResolution: (Boolean) -> Unit,
    onDim: (Boolean) -> Unit, onHidePreview: (Boolean) -> Unit, header: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val tablet = minOf(configuration.screenWidthDp, configuration.screenHeightDp) >= 600
    val previewWeight = if (tablet && LocalDensity.current.fontScale <= 1.3f) 2f else 1f
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        CameraControlPanel(modifier, header, state, streaming, highResolution, dim, hidden, captureSize, sentSize,
            onStart, onStop, onBind, onRefresh, onResolution, onDim, onHidePreview)
    }
    // Both regions keep their geometry when preview visibility, connection or stream state changes.
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            controls(Modifier.weight(1f).fillMaxHeight())
            CameraPreview(preview, streaming, hidden, Modifier.weight(previewWeight).fillMaxHeight())
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CameraPreview(preview, streaming, hidden, Modifier.weight(previewWeight).fillMaxWidth())
            controls(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CameraControlPanel(modifier: Modifier, header: @Composable () -> Unit, state: PublisherState,
    streaming: Boolean, highResolution: Boolean, dim: Boolean, hidden: Boolean,
    captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, onStart: () -> Unit, onStop: () -> Unit,
    onBind: (String) -> Unit, onRefresh: () -> Unit, onResolution: (Boolean) -> Unit,
    onDim: (Boolean) -> Unit, onHidePreview: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier.semantics { contentDescription = "控制和状态区" }, colors = CardDefaults.outlinedCardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
        header()
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val rowGap = if (maxHeight >= 300.dp) 12.dp else 4.dp
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(rowGap, Alignment.CenterVertically)) {
            CameraStatistics(state, sentSize, highResolution && captureSize != null && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720))
            CameraTarget(state, streaming, onBind, onRefresh)
            CameraSettings(streaming, highResolution, dim, hidden, rowGap, onResolution, onDim, onHidePreview)
        }
        }
        if (streaming) Button(onStop, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
            colors = VisionGuardControlColors.button(containerColor = colors.error, contentColor = colors.onError),
            shape = MaterialTheme.shapes.small) { Text("停止推流") }
        else Button(onStart, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp),
            enabled = state.connected && state.stream?.targetDeviceId != null,
            colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Text("开始推流") }
    }
}

@Composable
private fun CameraPreview(preview: Bitmap?, streaming: Boolean, hidden: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(modifier.semantics { contentDescription = "预览区域" }, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant),
        border = BorderStroke(1.dp, colors.outlineVariant), shape = MaterialTheme.shapes.medium) {
        if (!hidden && preview != null) Image(preview.asImageBitmap(), "实时摄像头画面",
            Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (hidden || streaming) Text(if (hidden) "预览已关闭" else "等待画面",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            else Icon(LucideIcons.ImageOff, "暂无相机预览", Modifier.size(32.dp), tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun CameraTarget(state: PublisherState, streaming: Boolean, onBind: (String) -> Unit, onRefresh: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf(false) }
    val selected = state.targets.find { it.deviceId == state.stream?.targetDeviceId }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraFieldLabel("目标")
            Box(Modifier.weight(1f)) {
                OutlinedButton({ choosing = true }, enabled = !streaming && state.connected && state.targets.isNotEmpty(),
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
                DropdownMenu(expanded = choosing && !streaming && state.connected,
                    onDismissRequest = { choosing = false }, modifier = Modifier.heightIn(max = 280.dp).widthIn(max = 400.dp)) {
                    state.targets.forEach { target ->
                        DropdownMenuItem(text = { Text((if (target.deviceId == state.stream?.targetDeviceId) "已关联 · " else "") + target.deviceName) },
                            onClick = { onBind(target.deviceId); choosing = false }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            OutlinedIconButton(onRefresh, enabled = state.connected && !state.targetsLoading,
                modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, colors.outlineVariant)) {
                Icon(LucideIcons.RefreshCw, "刷新目标", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CameraSettings(streaming: Boolean, highResolution: Boolean, dim: Boolean, hidden: Boolean,
    rowGap: Dp, onResolution: (Boolean) -> Unit, onDim: (Boolean) -> Unit, onHidePreview: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CameraFieldLabel("规格")
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "640×480", true to "720P").forEach { (resolution, title) ->
                    val selected = highResolution == resolution
                    val colors = MaterialTheme.colorScheme
                    OutlinedButton(onClick = { onResolution(resolution) }, enabled = !streaming,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = selected },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
                        colors = VisionGuardControlColors.outlinedButton(
                            containerColor = if (selected) colors.primaryContainer else colors.surface,
                            contentColor = if (selected) VisionGuardStatusColors.onSuccessContainer else colors.onSurfaceVariant)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CameraSetting("降低亮度", dim, onDim, Modifier.weight(1f))
            CameraSetting("预览", !hidden, { onHidePreview(!it) }, Modifier.weight(1f))
        }
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
private fun CameraStatistics(state: PublisherState, sentSize: Pair<Int, Int>?, fallback: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val statusColor = when {
                state.status.contains("失败") || state.status.contains("无法") || state.status.contains("超时") -> colors.error
                !state.connected || state.status.contains("等待") || state.status.contains("重连") -> VisionGuardStatusColors.warning
                state.stream?.isStreaming == true -> VisionGuardStatusColors.onSuccessContainer
                else -> colors.onSurface
            }
            Text(state.status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = statusColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sentSize?.let { Text("发送 ${it.first}×${it.second}" + if (fallback) " · 回退" else "",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("入队" to "${state.sentFrames}", "收帧" to "${state.acknowledgedFrames}",
                "丢弃" to "${state.droppedFrames}/${state.relayDroppedFrames}").forEachIndexed { index, (label, value) ->
                if (index > 0) VerticalDivider(Modifier.height(16.dp), color = colors.outlineVariant)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
                    CameraHelpSection("统计", "入队表示本机发送队列接纳；收帧只表示服务中继收件，不代表视觉推理节点已接收或完成推理。丢弃的两个数值依次为本机 / 服务；主动限帧未采样不算丢弃。")
                    Text("入队 ${state.sentFrames} · 收帧 ${state.acknowledgedFrames} · 丢弃 ${state.droppedFrames}/${state.relayDroppedFrames}", style = MaterialTheme.typography.bodySmall)
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

package com.xgwnje.visionguard.ui.console

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard.AppConstants
import com.xgwnje.visionguard.data.model.MaskRegion
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.remote.WsState
import kotlin.math.roundToInt

@Composable
fun DetectorConsoleScreen(
    state: DetectorConsoleState,
    connectionState: WsState,
    deviceName: String,
    lastFrame: Bitmap?,
    frameAspectRatio: Float,
    lastFrameLabel: String,
    lastAlertTime: String?,
    actualSamplingRate: Float,
    isCapturingFrame: Boolean,
    showMaintenance: Boolean,
    isHighEndSoc: Boolean,
    modelStatusText: String,
    onSelectOrientation: (DeploymentOrientation) -> Unit,
    onToggleMonitoring: () -> Unit,
    onRefreshFrame: () -> Unit,
    onOpenCalibration: () -> Unit,
    onRetakeCalibrationFrame: () -> Unit,
    onApplyCalibration: (List<MaskRegion>, Float) -> Unit,
    onCloseCalibration: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onCloseMaintenance: () -> Unit,
    onSaveMaintenance: (MonitorConfig, String, DeploymentOrientation) -> Unit,
    onReconnect: () -> Unit,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = DetectorBackground
    ) {
        when (state.destination) {
            ConsoleDestination.ORIENTATION_SETUP -> OrientationSetup(
                onSelectOrientation = onSelectOrientation
            )
            ConsoleDestination.RUN -> {
                when (state.deploymentOrientation) {
                    DeploymentOrientation.LANDSCAPE -> LandscapeConsole(
                        state = state,
                        connectionState = connectionState,
                        deviceName = deviceName,
                        lastFrame = lastFrame,
                        frameAspectRatio = frameAspectRatio,
                        lastFrameLabel = lastFrameLabel,
                        lastAlertTime = lastAlertTime,
                        actualSamplingRate = actualSamplingRate,
                        onToggleMonitoring = onToggleMonitoring,
                        onRefreshFrame = onRefreshFrame,
                        onOpenCalibration = onOpenCalibration,
                        onOpenMaintenance = onOpenMaintenance,
                        onReconnect = onReconnect
                    )
                    DeploymentOrientation.PORTRAIT, null -> PortraitConsole(
                        state = state,
                        connectionState = connectionState,
                        deviceName = deviceName,
                        lastFrame = lastFrame,
                        frameAspectRatio = frameAspectRatio,
                        lastFrameLabel = lastFrameLabel,
                        lastAlertTime = lastAlertTime,
                        actualSamplingRate = actualSamplingRate,
                        onToggleMonitoring = onToggleMonitoring,
                        onRefreshFrame = onRefreshFrame,
                        onOpenCalibration = onOpenCalibration,
                        onOpenMaintenance = onOpenMaintenance,
                        onReconnect = onReconnect
                    )
                }
            }
        }
    }

    if (isCapturingFrame) {
        BlockingStatusOverlay(text = "正在抓取最近帧...")
    }

    if (showMaintenance) {
        MaintenanceSheet(
            state = state,
            deviceName = deviceName,
            isHighEndSoc = isHighEndSoc,
            modelStatusText = modelStatusText,
            connectionState = connectionState,
            onSave = onSaveMaintenance,
            onDismiss = onCloseMaintenance,
            onReconnect = onReconnect,
            onCheckUpdate = onCheckUpdate
        )
    }

    if (state.destination == ConsoleDestination.RUN && !state.isMonitoring && state.canEnterCalibration) {
        // Calibration is displayed by the route by swapping the frame action callbacks; this screen keeps
        // the workspace composable available without owning navigation state.
    }
}

@Composable
fun CalibrationWorkspace(
    orientation: DeploymentOrientation,
    bitmap: Bitmap?,
    frameAspectRatio: Float,
    initialMasks: List<MaskRegion>,
    initialZoom: Float,
    isCapturing: Boolean,
    onRetake: () -> Unit,
    onApply: (List<MaskRegion>, Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val masks = remember(initialMasks) {
        mutableStateListOf<MaskRegion>().apply { addAll(initialMasks) }
    }
    var digitalZoom by remember(initialZoom) { mutableFloatStateOf(initialZoom.coerceIn(1f, 5f)) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = DetectorBackground
    ) {
        if (orientation == DeploymentOrientation.LANDSCAPE) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CalibrationCanvasPanel(
                    bitmap = bitmap,
                    frameAspectRatio = frameAspectRatio,
                    masks = masks,
                    digitalZoom = digitalZoom,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                CalibrationControls(
                    maskCount = masks.size,
                    digitalZoom = digitalZoom,
                    isCapturing = isCapturing,
                    onDigitalZoomChange = { digitalZoom = it },
                    onRetake = onRetake,
                    onUndo = { if (masks.isNotEmpty()) masks.removeAt(masks.lastIndex) },
                    onClear = { masks.clear() },
                    onCancel = onCancel,
                    onApply = { onApply(masks.toList(), digitalZoom) },
                    modifier = Modifier.width(292.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CalibrationCanvasPanel(
                    bitmap = bitmap,
                    frameAspectRatio = frameAspectRatio,
                    masks = masks,
                    digitalZoom = digitalZoom,
                    modifier = Modifier.fillMaxWidth()
                )
                CalibrationControls(
                    maskCount = masks.size,
                    digitalZoom = digitalZoom,
                    isCapturing = isCapturing,
                    onDigitalZoomChange = { digitalZoom = it },
                    onRetake = onRetake,
                    onUndo = { if (masks.isNotEmpty()) masks.removeAt(masks.lastIndex) },
                    onClear = { masks.clear() },
                    onCancel = onCancel,
                    onApply = { onApply(masks.toList(), digitalZoom) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun OrientationSetup(
    onSelectOrientation: (DeploymentOrientation) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = DetectorPrimary,
                modifier = Modifier.size(42.dp)
            )
            Text(
                text = "选择部署方向",
                style = MaterialTheme.typography.headlineSmall,
                color = DetectorPrimary,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "首次设置后会锁定对应控制台布局",
                style = MaterialTheme.typography.bodyMedium,
                color = DetectorMuted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSelectOrientation(DeploymentOrientation.PORTRAIT) },
                    colors = ButtonDefaults.buttonColors(containerColor = DetectorPrimary)
                ) {
                    Text("竖屏架设")
                }
                OutlinedButton(onClick = { onSelectOrientation(DeploymentOrientation.LANDSCAPE) }) {
                    Text("横屏架设")
                }
            }
        }
    }
}

@Composable
private fun PortraitConsole(
    state: DetectorConsoleState,
    connectionState: WsState,
    deviceName: String,
    lastFrame: Bitmap?,
    frameAspectRatio: Float,
    lastFrameLabel: String,
    lastAlertTime: String?,
    actualSamplingRate: Float,
    onToggleMonitoring: () -> Unit,
    onRefreshFrame: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onReconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TopStatusStrip(
            connectionState = connectionState,
            isMonitoring = state.isMonitoring,
            actualSamplingRate = actualSamplingRate,
            targetSamplingRate = state.appliedConfig.targetSamplingRate
        )
        FramePanel(
            bitmap = lastFrame,
            aspectRatio = frameAspectRatio,
            title = "最近帧",
            subtitle = lastFrameLabel,
            emptyText = state.emptyFrameText,
            onRefresh = onRefreshFrame,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        RuntimeSummary(
            state = state,
            deviceName = deviceName,
            lastAlertTime = lastAlertTime,
            modifier = Modifier.fillMaxWidth()
        )
        ActionDock(
            state = state,
            onToggleMonitoring = onToggleMonitoring,
            onOpenCalibration = onOpenCalibration,
            onOpenMaintenance = onOpenMaintenance,
            onReconnect = onReconnect
        )
    }
}

@Composable
private fun LandscapeConsole(
    state: DetectorConsoleState,
    connectionState: WsState,
    deviceName: String,
    lastFrame: Bitmap?,
    frameAspectRatio: Float,
    lastFrameLabel: String,
    lastAlertTime: String?,
    actualSamplingRate: Float,
    onToggleMonitoring: () -> Unit,
    onRefreshFrame: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onReconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FramePanel(
            bitmap = lastFrame,
            aspectRatio = frameAspectRatio,
            title = "最近帧",
            subtitle = lastFrameLabel,
            emptyText = state.emptyFrameText,
            onRefresh = onRefreshFrame,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        Column(
            modifier = Modifier
                .width(288.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TopStatusStrip(
                connectionState = connectionState,
                isMonitoring = state.isMonitoring,
                actualSamplingRate = actualSamplingRate,
                targetSamplingRate = state.appliedConfig.targetSamplingRate
            )
            RuntimeSummary(
                state = state,
                deviceName = deviceName,
                lastAlertTime = lastAlertTime,
                modifier = Modifier.fillMaxWidth(),
                compact = true
            )
            ActionDock(
                state = state,
                onToggleMonitoring = onToggleMonitoring,
                onOpenCalibration = onOpenCalibration,
                onOpenMaintenance = onOpenMaintenance,
                onReconnect = onReconnect,
                compact = true
            )
        }
    }
}

@Composable
private fun TopStatusStrip(
    connectionState: WsState,
    isMonitoring: Boolean,
    actualSamplingRate: Float,
    targetSamplingRate: Int
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(label = connectionLabel(connectionState), tone = connectionTone(connectionState))
            StatusPill(label = if (isMonitoring) "监控中" else "已停止", tone = if (isMonitoring) ConsoleTone.READY else ConsoleTone.MUTED)
            Text(
                text = if (isMonitoring) "实际 ${String.format("%.1f", actualSamplingRate)}/s" else "目标 ${targetSamplingRate}/s",
                style = MaterialTheme.typography.labelLarge,
                color = DetectorPrimary,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun FramePanel(
    bitmap: Bitmap?,
    aspectRatio: Float,
    title: String,
    subtitle: String,
    emptyText: String,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = DetectorPrimary,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = DetectorMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButtonSurface(
                    icon = Icons.Default.Refresh,
                    contentDescription = "刷新最近帧",
                    onClick = onRefresh
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .aspectRatio(aspectRatio.coerceIn(1f, 1.78f))
                    .clip(RoundedCornerShape(8.dp))
                    .background(DetectorSurfaceMuted),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "最近帧",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DetectorMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeSummary(
    state: DetectorConsoleState,
    deviceName: String,
    lastAlertTime: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = DetectorPrimary,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "${state.appliedConfig.modelName.uppercase()} ${state.appliedConfig.inputSize}  ${targetLabel(state.appliedConfig.targets)}  冷却${state.appliedConfig.cooldownMs / 1000}秒",
                style = MaterialTheme.typography.bodyMedium,
                color = DetectorPrimary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    label = if (state.isReady) "模型已就绪" else "模型未就绪",
                    tone = if (state.isReady) ConsoleTone.READY else ConsoleTone.WARNING
                )
                StatusPill(
                    label = if (lastAlertTime != null) "最近报警$lastAlertTime" else "暂无报警",
                    tone = ConsoleTone.MUTED
                )
            }
            state.pendingChangeText?.let {
                InlineNotice(text = it, tone = ConsoleTone.WARNING)
            }
            state.calibrationHintText?.let {
                InlineNotice(text = it, tone = ConsoleTone.WARNING)
            }
        }
    }
}

@Composable
private fun ActionDock(
    state: DetectorConsoleState,
    onToggleMonitoring: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onReconnect: () -> Unit,
    compact: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
        ) {
            Button(
                onClick = onToggleMonitoring,
                enabled = state.isReady,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 44.dp else 56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isMonitoring) DetectorAlert else DetectorPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = if (state.isMonitoring) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(state.primaryActionLabel, fontWeight = FontWeight.Black)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedConsoleButton(
                    text = "校准",
                    icon = Icons.Default.Videocam,
                    enabled = state.canEnterCalibration,
                    onClick = onOpenCalibration,
                    modifier = Modifier.weight(1f),
                    height = if (compact) 38.dp else 46.dp
                )
                OutlinedConsoleButton(
                    text = "参数",
                    icon = Icons.Default.Tune,
                    enabled = state.destination == ConsoleDestination.RUN,
                    onClick = onOpenMaintenance,
                    modifier = Modifier.weight(1f),
                    height = if (compact) 38.dp else 46.dp
                )
                OutlinedConsoleButton(
                    text = "重连",
                    icon = Icons.Default.Refresh,
                    enabled = true,
                    onClick = onReconnect,
                    modifier = Modifier.weight(1f),
                    height = if (compact) 38.dp else 46.dp
                )
            }
        }
    }
}

@Composable
private fun CalibrationCanvasPanel(
    bitmap: Bitmap?,
    frameAspectRatio: Float,
    masks: MutableList<MaskRegion>,
    digitalZoom: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "校准帧",
                style = MaterialTheme.typography.titleMedium,
                color = DetectorPrimary,
                fontWeight = FontWeight.Black
            )
            EditableFrameCanvas(
                bitmap = bitmap,
                frameAspectRatio = frameAspectRatio,
                masks = masks,
                digitalZoom = digitalZoom,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EditableFrameCanvas(
    bitmap: Bitmap?,
    frameAspectRatio: Float,
    masks: MutableList<MaskRegion>,
    digitalZoom: Float,
    modifier: Modifier = Modifier
) {
    var draggingRect by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier
            .heightIn(min = 220.dp, max = 520.dp)
            .aspectRatio(frameAspectRatio.coerceIn(0.62f, 1.78f))
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "校准帧",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "暂无校准帧",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val x = (offset.x / size.width).coerceIn(0f, 1f)
                            val y = (offset.y / size.height).coerceIn(0f, 1f)
                            draggingRect = Rect(Offset(x, y), Size(0f, 0f))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            draggingRect?.let { rect ->
                                val endX = (change.position.x / size.width).coerceIn(0f, 1f)
                                val endY = (change.position.y / size.height).coerceIn(0f, 1f)
                                draggingRect = Rect(
                                    left = minOf(rect.left, endX),
                                    top = minOf(rect.top, endY),
                                    right = maxOf(rect.left, endX),
                                    bottom = maxOf(rect.top, endY)
                                )
                            }
                        },
                        onDragEnd = {
                            draggingRect?.let { rect ->
                                if (rect.width > 0.02f && rect.height > 0.02f) {
                                    masks.add(MaskRegion(rect.left, rect.top, rect.right, rect.bottom))
                                }
                            }
                            draggingRect = null
                        },
                        onDragCancel = { draggingRect = null }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            masks.forEach { mask ->
                drawRect(
                    color = DetectorAlert.copy(alpha = 0.38f),
                    topLeft = Offset(mask.left * w, mask.top * h),
                    size = Size((mask.right - mask.left) * w, (mask.bottom - mask.top) * h)
                )
                drawRect(
                    color = DetectorAlert,
                    topLeft = Offset(mask.left * w, mask.top * h),
                    size = Size((mask.right - mask.left) * w, (mask.bottom - mask.top) * h),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            draggingRect?.let { rect ->
                drawRect(
                    color = DetectorAmber.copy(alpha = 0.32f),
                    topLeft = Offset(rect.left * w, rect.top * h),
                    size = Size((rect.right - rect.left) * w, (rect.bottom - rect.top) * h)
                )
                drawRect(
                    color = DetectorAmber,
                    topLeft = Offset(rect.left * w, rect.top * h),
                    size = Size((rect.right - rect.left) * w, (rect.bottom - rect.top) * h),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            if (digitalZoom > 1f) {
                val cropW = w / digitalZoom
                val cropH = h / digitalZoom
                drawRect(
                    color = Color.Cyan,
                    topLeft = Offset((w - cropW) / 2f, (h - cropH) / 2f),
                    size = Size(cropW, cropH),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    )
                )
            }
        }
    }
}

@Composable
private fun CalibrationControls(
    maskCount: Int,
    digitalZoom: Float,
    isCapturing: Boolean,
    onDigitalZoomChange: (Float) -> Unit,
    onRetake: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("架设校准", style = MaterialTheme.typography.titleMedium, color = DetectorPrimary, fontWeight = FontWeight.Black)
                    Text("遮罩 $maskCount 个 · 数码裁切 ${String.format("%.1f", digitalZoom)}x", style = MaterialTheme.typography.labelLarge, color = DetectorMuted)
                }
                IconButtonSurface(
                    icon = Icons.Default.Refresh,
                    contentDescription = "重新抓拍",
                    enabled = !isCapturing,
                    onClick = onRetake
                )
            }
            Column {
                Text("数码裁切", style = MaterialTheme.typography.labelLarge, color = DetectorPrimary, fontWeight = FontWeight.Black)
                Slider(
                    value = digitalZoom,
                    onValueChange = { onDigitalZoomChange(it.coerceIn(1f, 5f)) },
                    valueRange = 1f..5f,
                    steps = 35
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedConsoleButton("撤销", Icons.AutoMirrored.Filled.Undo, maskCount > 0, onUndo, Modifier.weight(1f))
                OutlinedConsoleButton("清空", Icons.Default.Delete, maskCount > 0, onClear, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedConsoleButton("取消", Icons.Default.Close, true, onCancel, Modifier.weight(1f))
                Button(
                    onClick = onApply,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DetectorPrimary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("应用配置", fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

@Composable
private fun MaintenanceSheet(
    state: DetectorConsoleState,
    deviceName: String,
    isHighEndSoc: Boolean,
    modelStatusText: String,
    connectionState: WsState,
    onSave: (MonitorConfig, String, DeploymentOrientation) -> Unit,
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
    onCheckUpdate: () -> Unit
) {
    var localConfig by remember(state.draftConfig) { mutableStateOf(state.draftConfig) }
    var localDeviceName by remember(deviceName) { mutableStateOf(deviceName) }
    var localOrientation by remember(state.deploymentOrientation) {
        mutableStateOf(state.deploymentOrientation ?: DeploymentOrientation.PORTRAIT)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(10.dp)
                    .heightIn(max = 780.dp),
                shape = RoundedCornerShape(8.dp),
                color = DetectorSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "维护参数",
                            style = MaterialTheme.typography.titleLarge,
                            color = DetectorPrimary,
                            fontWeight = FontWeight.Black
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = DetectorMuted,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onDismiss)
                                .padding(7.dp)
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        MaintenanceSection(title = "部署方向") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OrientationOption(
                                    label = "竖屏架设",
                                    selected = localOrientation == DeploymentOrientation.PORTRAIT,
                                    onSelect = { localOrientation = DeploymentOrientation.PORTRAIT },
                                    modifier = Modifier.weight(1f)
                                )
                                OrientationOption(
                                    label = "横屏架设",
                                    selected = localOrientation == DeploymentOrientation.LANDSCAPE,
                                    onSelect = { localOrientation = DeploymentOrientation.LANDSCAPE },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        MaintenanceSection(title = "设备名称") {
                            OutlinedTextField(
                                value = localDeviceName,
                                onValueChange = { localDeviceName = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        MaintenanceSection(title = "模型与输入尺寸") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                ModelChip("YOLO26n", localConfig.modelName == "yolo26n") {
                                    localConfig = localConfig.copy(modelName = "yolo26n")
                                }
                                ModelChip("YOLO26s", localConfig.modelName == "yolo26s") {
                                    localConfig = localConfig.copy(modelName = "yolo26s")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("高分辨率 640×640", style = MaterialTheme.typography.bodyMedium, color = DetectorPrimary)
                                    Text(
                                        text = if (isHighEndSoc) "精度更高，发热和耗电增加" else "当前 SoC 不在高分辨率白名单",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = DetectorMuted
                                    )
                                }
                                Switch(
                                    checked = localConfig.useHighResolution,
                                    enabled = isHighEndSoc,
                                    onCheckedChange = { checked ->
                                        localConfig = localConfig.copy(
                                            useHighResolution = checked,
                                            inputSize = if (checked) 640 else 320
                                        )
                                    }
                                )
                            }
                            Text(modelStatusText, style = MaterialTheme.typography.labelMedium, color = DetectorMuted)
                        }
                        MaintenanceSection(title = "目标类别") {
                            TargetGrid(
                                selectedTargets = localConfig.targets,
                                onToggle = { target ->
                                    localConfig = localConfig.copy(
                                        targets = if (target in localConfig.targets) {
                                            localConfig.targets - target
                                        } else {
                                            localConfig.targets + target
                                        }
                                    )
                                }
                            )
                        }
                        MaintenanceSection(title = "置信度") {
                            Text("${(localConfig.confidence * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = DetectorPrimary, fontWeight = FontWeight.Black)
                            Slider(
                                value = localConfig.confidence,
                                onValueChange = { localConfig = localConfig.copy(confidence = it) },
                                valueRange = 0.1f..0.95f,
                                steps = 16
                            )
                        }
                        MaintenanceSection(title = "目标采样率") {
                            Text("${localConfig.targetSamplingRate} 次/秒", style = MaterialTheme.typography.titleMedium, color = DetectorPrimary, fontWeight = FontWeight.Black)
                            Slider(
                                value = localConfig.targetSamplingRate.toFloat(),
                                onValueChange = { localConfig = localConfig.copy(targetSamplingRate = it.roundToInt().coerceIn(1, 5)) },
                                valueRange = 1f..5f,
                                steps = 3
                            )
                        }
                        MaintenanceSection(title = "报警冷却时间") {
                            Text("${localConfig.cooldownMs / 1000} 秒", style = MaterialTheme.typography.titleMedium, color = DetectorPrimary, fontWeight = FontWeight.Black)
                            Slider(
                                value = (localConfig.cooldownMs / 1000).toFloat(),
                                onValueChange = { localConfig = localConfig.copy(cooldownMs = it.roundToInt().coerceIn(1, 300) * 1000L) },
                                valueRange = 1f..300f,
                                steps = 298
                            )
                        }
                        MaintenanceSection(title = "服务器连接") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(connectionLabel(connectionState), connectionTone(connectionState))
                                OutlinedConsoleButton("重连", Icons.Default.Refresh, true, onReconnect, Modifier.width(112.dp))
                            }
                        }
                        MaintenanceSection(title = "版本更新") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("当前版本 ${AppConstants.VERSION}", style = MaterialTheme.typography.bodyMedium, color = DetectorPrimary)
                                OutlinedConsoleButton("检查更新", Icons.Default.Refresh, true, onCheckUpdate, Modifier.width(132.dp))
                            }
                        }
                    }
                    state.pendingChangeText?.let {
                        InlineNotice(text = it, tone = ConsoleTone.WARNING)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedConsoleButton("取消", Icons.Default.Close, true, onDismiss, Modifier.weight(1f))
                        Button(
                            onClick = { onSave(localConfig, localDeviceName, localOrientation) },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DetectorPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("保存更改", fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = DetectorPrimary,
            fontWeight = FontWeight.Black
        )
        content()
    }
}

@Composable
private fun OrientationOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) DetectorPrimarySoft else DetectorSurfaceMuted)
            .clickable(onClick = onSelect)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, color = DetectorPrimary, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ModelChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, fontWeight = FontWeight.Black) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = DetectorPrimary,
            selectedLabelColor = Color.White,
            containerColor = DetectorSurfaceMuted,
            labelColor = DetectorPrimary
        ),
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun TargetGrid(
    selectedTargets: Set<String>,
    onToggle: (String) -> Unit
) {
    val targets = listOf(
        "person" to "人",
        "car" to "汽车",
        "truck" to "卡车",
        "bus" to "客车",
        "bicycle" to "自行车",
        "motorcycle" to "摩托车"
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.chunked(3).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { (key, label) ->
                    FilterChip(
                        selected = key in selectedTargets,
                        onClick = { onToggle(key) },
                        label = { Text(label, fontWeight = FontWeight.Black) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DetectorPrimary,
                            selectedLabelColor = Color.White,
                            containerColor = DetectorSurfaceMuted,
                            labelColor = DetectorPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, tone: ConsoleTone) {
    val foreground = toneForeground(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(toneBackground(tone))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(foreground, CircleShape)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InlineNotice(text: String, tone: ConsoleTone) {
    val icon = if (tone == ConsoleTone.WARNING) Icons.Default.Warning else Icons.Default.CheckCircle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(toneBackground(tone))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = toneForeground(tone), modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = toneForeground(tone), fontWeight = FontWeight.Black)
    }
}

@Composable
private fun IconButtonSurface(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = DetectorSurfaceMuted,
        border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = DetectorPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun OutlinedConsoleButton(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 46.dp
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(modifier = Modifier.width(5.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BlockingStatusOverlay(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DetectorSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, DetectorStroke),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = DetectorPrimary,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
            )
        }
    }
}

@Composable
fun UncalibratedStartDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("尚未校准取景") },
        text = { Text("建议先校准取景。仍要开始监控吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("继续开始") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("先去校准") }
        }
    )
}

private fun connectionLabel(state: WsState): String =
    when (state) {
        WsState.CONNECTED -> "已连接"
        WsState.CONNECTING -> "连接中"
        WsState.DISCONNECTED -> "未连接"
        WsState.AUTH_FAILED -> "认证失败"
    }

private fun connectionTone(state: WsState): ConsoleTone =
    when (state) {
        WsState.CONNECTED -> ConsoleTone.READY
        WsState.CONNECTING -> ConsoleTone.WARNING
        WsState.DISCONNECTED -> ConsoleTone.MUTED
        WsState.AUTH_FAILED -> ConsoleTone.DANGER
    }

private fun toneForeground(tone: ConsoleTone): Color =
    when (tone) {
        ConsoleTone.READY -> DetectorPrimary
        ConsoleTone.WARNING -> DetectorAmber
        ConsoleTone.DANGER -> DetectorAlert
        ConsoleTone.MUTED -> DetectorMuted
    }

private fun toneBackground(tone: ConsoleTone): Color =
    when (tone) {
        ConsoleTone.READY -> DetectorPrimarySoft
        ConsoleTone.WARNING -> DetectorAmberSoft
        ConsoleTone.DANGER -> DetectorAlertSoft
        ConsoleTone.MUTED -> DetectorSurfaceMuted
    }

private fun targetLabel(targets: Set<String>): String {
    val labels = mapOf(
        "person" to "人员",
        "car" to "汽车",
        "truck" to "卡车",
        "bus" to "客车",
        "bicycle" to "自行车",
        "motorcycle" to "摩托车"
    )
    return targets.map { labels[it] ?: it }.ifEmpty { listOf("未选目标") }.joinToString("/")
}

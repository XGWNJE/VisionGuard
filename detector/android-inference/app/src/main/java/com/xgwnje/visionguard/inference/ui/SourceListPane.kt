package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView

/** 来源类型的中文名。 */
fun modeText(mode: SourceCaptureMode): String =
    if (mode == SourceCaptureMode.LocalCamera) "本机相机" else "远程镜头"

/**
 * 来源列表：一路一张卡片，点选同步主预览与检查区。
 *
 * 远程镜头槽位只在本机占位，真正绑定由控制台完成。
 */
@Composable
fun SourceListPane(
    sources: List<SourceView>,
    selectedSourceId: String?,
    maxSources: Int,
    nowElapsedMs: Long,
    onSelect: (String) -> Unit,
    onAddLocalCamera: () -> String?,
    onAddRemotePlaceholder: () -> String?,
    onMessage: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var adding by remember { mutableStateOf(false) }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
    ) {
        SectionCard {
            SectionTitle(
                text = "来源",
                action = { RowAction("添加来源") { adding = true } }
            )
            SecondaryText("共 ${sources.size} 路，服务端上限 $maxSources 路；远程镜头由控制台绑定。")
            if (sources.isEmpty()) {
                Text(
                    "还没有来源。可以添加本机相机，或添加远程镜头槽位等待控制台绑定。",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            for (source in sources) {
                SourceCard(
                    source = source,
                    selected = source.sourceId == selectedSourceId,
                    nowElapsedMs = nowElapsedMs,
                    onSelect = { onSelect(source.sourceId) }
                )
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("添加来源", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
                    Text("本机相机：用这台设备的摄像头采集并本机推理。", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "远程镜头槽位：只在本机占位，需要到 Web 控制台把相机推流节点绑定到本节点后才会收到画面。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddLocalCamera()?.let(onMessage)
                        adding = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("本机相机") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onAddRemotePlaceholder()?.let(onMessage)
                        adding = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("远程镜头槽位") }
            }
        )
    }
}

/** 单路来源卡片：缩略图、名称、类型、状态、实际帧率与运行指示灯。 */
@Composable
private fun SourceCard(
    source: SourceView,
    selected: Boolean,
    nowElapsedMs: Long,
    onSelect: () -> Unit
) {
    val image by rememberFrameBitmap(source.state.preview, maxWidth = 160)
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .width(96.dp)
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                if (image == null) {
                    EmptyFramePlaceholder(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                } else {
                    ThumbnailCanvas(
                        image = image,
                        detections = source.state.detections,
                        maskRegions = source.masks,
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(source.state.isRunning)
                    Text(
                        source.sourceName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "${modeText(source.mode)} · ${source.state.statusText}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    "实际 ${"%.2f".format(source.state.actualFps)} FPS · 目标 ${source.params.targetFps} FPS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (source.state.preview == null) "暂无画面"
                    else if (source.state.isRunning) "画面 ${relativeAge(source.state.lastProgressAtMs, nowElapsedMs)}"
                    else "最后一帧 ${relativeAge(source.state.lastProgressAtMs, nowElapsedMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (source.state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                source.state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = VisionGuardStatusColors.error, maxLines = 2)
                }
            }
        }
    }
}

/** 供检查区复用的启停按钮文案。 */
fun toggleLabel(running: Boolean): String = if (running) "停止检测" else "开始检测"

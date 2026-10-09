package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.MaskRegion

/**
 * 遮罩编辑器：在最后一帧上拖拽画矩形，保存后涂黑区域不参与识别。
 *
 * 坐标全部用相对 `[0,1]`，与 [MaskRegion] 一致；矩形限制在画面内并保证最小边长。
 */
@Composable
fun MaskEditorDialog(
    sourceName: String,
    frame: ArgbImage?,
    initial: List<MaskRegion>,
    running: Boolean,
    onDismiss: () -> Unit,
    onSave: (List<MaskRegion>) -> String?,
    onMessage: (String?) -> Unit
) {
    var masks by remember(initial) { mutableStateOf(initial) }
    var selected by remember { mutableStateOf(-1) }
    var canvasWidth by remember { mutableStateOf(0f) }
    var canvasHeight by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var dragRect by remember { mutableStateOf<MaskRegion?>(null) }
    val image by rememberFrameBitmap(frame, maxWidth = 1280)

    val ratio = if (frame != null && frame.width > 0) frame.width.toFloat() / frame.height else 4f / 3f

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("编辑遮罩 · $sourceName", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(UiTokens.Gap)
            ) {
                if (running) {
                    Text(
                        "该来源正在运行，保存会被拒绝；请先停止来源再修改遮罩。",
                        style = MaterialTheme.typography.bodySmall,
                        color = VisionGuardStatusColors.warning
                    )
                }
                Text(
                    "在画面上按住拖拽即可添加矩形。涂黑区域不会被识别，也不会出现在报警截图里；遮罩只保存在本机，不同步到控制台。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = FrameBackdrop
                ) {
                    FrameCanvas(
                        image = image,
                        detections = emptyList(),
                        maskRegions = masks,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(ratio)
                            .pointerInput(sourceName, running) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    val width = canvasWidth
                                    val height = canvasHeight
                                    if (width <= 0f || height <= 0f) return@awaitEachGesture
                                    val startX = (down.position.x / width).coerceIn(0f, 1f)
                                    val startY = (down.position.y / height).coerceIn(0f, 1f)
                                    dragStart = startX to startY
                                    dragRect = MaskRegion(startX, startY, MaskRegion.MIN_EDGE, MaskRegion.MIN_EDGE)
                                    drag(down.id) { change ->
                                        val endX = (change.position.x / width).coerceIn(0f, 1f)
                                        val endY = (change.position.y / height).coerceIn(0f, 1f)
                                        dragRect = buildRegion(startX, startY, endX, endY)
                                        change.consume()
                                    }
                                    val finished = dragRect
                                    dragStart = null
                                    dragRect = null
                                    if (finished != null) {
                                        masks = masks + finished
                                        selected = masks.lastIndex
                                        error = null
                                    }
                                }
                            }
                            .onSizeChanged { size ->
                                canvasWidth = size.width.toFloat()
                                canvasHeight = size.height.toFloat()
                            }
                    )
                }
                if (frame == null) {
                    Text(
                        "该来源还没有可编辑的画面；本机相机会在开始检测后产生画面，远程镜头需要控制台先绑定推流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("共 ${masks.size} 个遮罩", style = MaterialTheme.typography.bodyMedium)
                if (masks.isEmpty()) {
                    Text("暂无遮罩。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                masks.forEachIndexed { index, _ ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "遮罩 ${index + 1} · 已选中",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                masks = masks.toMutableList().also { list ->
                                    if (index in list.indices) list.removeAt(index)
                                }
                                if (selected >= masks.size) selected = masks.lastIndex
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("删除") }
                    }
                }
                OutlinedButton(
                    onClick = { clearing = true },
                    enabled = masks.isNotEmpty(),
                    colors = VisionGuardControlColors.outlinedButton(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("清空全部遮罩") }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val failure = onSave(masks)
                    if (failure == null) onMessage("遮罩已保存，重新启动来源后生效") else error = failure
                },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = VisionGuardControlColors.textButton()
            ) { Text("保存遮罩") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = VisionGuardControlColors.textButton()
            ) { Text("取消") }
        }
    )

    if (clearing) {
        AlertDialog(
            onDismissRequest = { clearing = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("清空全部遮罩", style = MaterialTheme.typography.titleMedium) },
            text = { Text("确认清空 ${masks.size} 个遮罩？保存后该来源会恢复全画面识别。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        masks = emptyList()
                        selected = -1
                        clearing = false
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(
                    onClick = { clearing = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("取消") }
            }
        )
    }
}

/** 归一化拖拽矩形：从左上到右下、夹在画面内、保证最小边长。 */
private fun buildRegion(startX: Float, startY: Float, endX: Float, endY: Float): MaskRegion {
    var x = minOf(startX, endX)
    var y = minOf(startY, endY)
    var width = kotlin.math.abs(endX - startX)
    var height = kotlin.math.abs(endY - startY)
    if (width < MaskRegion.MIN_EDGE) {
        width = MaskRegion.MIN_EDGE
        x = x.coerceAtMost(1f - width)
    }
    if (height < MaskRegion.MIN_EDGE) {
        height = MaskRegion.MIN_EDGE
        y = y.coerceAtMost(1f - height)
    }
    x = x.coerceIn(0f, 1f - width)
    y = y.coerceIn(0f, 1f - height)
    return MaskRegion(x, y, width, height)
}

package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.MaskRegion
import com.xgwnje.visionguard.inference.ui.FrameBackdrop
import com.xgwnje.visionguard.inference.ui.FrameCanvas
import com.xgwnje.visionguard.inference.ui.rememberFrameBitmap
import kotlin.math.abs

/** 遮罩编辑工具。 */
internal enum class MaskTool { Draw, Move }

/**
 * 遮罩草稿：与参数草稿并列地挂在来源抽屉上，切页不会丢。
 *
 * 视觉底线见 [几何空间视觉语言参考](../../../../../../../docs/73-几何空间界面规范.md)：
 * 直角画布、48dp 把手触点且贴边内移、最小边长 2%。
 */
internal class MaskDraft(val initial: List<MaskRegion>) {
    var masks by mutableStateOf(initial)
    var selected by mutableStateOf(-1)
    var tool by mutableStateOf(MaskTool.Draw)
    var error by mutableStateOf<String?>(null)
    var clearing by mutableStateOf(false)

    fun restore() {
        masks = initial
        selected = -1
        tool = MaskTool.Draw
        error = null
    }

    fun update(index: Int, transform: (MaskRegion) -> MaskRegion) {
        masks = masks.mapIndexed { i, region -> if (i == index) transform(region) else region }
    }
}

/** 遮罩页正文：工具键、画布、遮罩列表与清空确认。 */
@Composable
internal fun MaskPage(draft: MaskDraft, frame: ArgbImage?, running: Boolean) {
    val locked = running
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoLamp(if (locked) "运行中 · 只读" else "已停止 · 可编辑", if (locked) GeoLampTone.Warning else GeoLampTone.Neutral)
        Spacer(Modifier.weight(1f))
        Text(
            "${draft.masks.size} 个遮罩 / 仅保存在本机",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
    GeoHint(
        if (locked) {
            "停止检测后才能编辑和保存遮罩。"
        } else {
            "拖画矩形，选择后移动 / 拉伸；黑色区域在推理前涂黑，预览和报警截图同步遮挡。"
        },
        if (locked) GeoLampTone.Warning else GeoLampTone.Neutral
    )

    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = { draft.tool = MaskTool.Draw },
            label = "添加矩形",
            icon = LucideIcons.Plus,
            tone = if (draft.tool == MaskTool.Draw) GeoTone.Primary else GeoTone.Secondary,
            enabled = !locked && frame != null
        )
        GeoKey(
            onClick = { draft.tool = MaskTool.Move },
            label = "选择 / 移动",
            icon = LucideIcons.GripHorizontal,
            tone = if (draft.tool == MaskTool.Move) GeoTone.Primary else GeoTone.Secondary,
            enabled = !locked && frame != null
        )
        HelpTopicKey("隐私遮罩")
    }

    MaskPlane(
        frame = frame,
        masks = draft.masks,
        selected = draft.selected,
        tool = draft.tool,
        locked = locked,
        onSelect = { draft.selected = it },
        onAdd = { region ->
            draft.masks = draft.masks + region
            draft.selected = draft.masks.lastIndex
            draft.error = null
        },
        onUpdate = { index, transform ->
            draft.update(index, transform)
            draft.error = null
        }
    )
    if (frame == null) {
        Text(
            "该来源还没有可编辑的画面；本机相机会在开始检测后产生画面，远程镜头需要控制台先绑定推流。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }

    GeoSectionLabel(null, "遮罩列表", "允许零遮罩")
    MaskRack(
        masks = draft.masks,
        selected = draft.selected,
        onSelect = { draft.selected = it }
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoKey(
            onClick = {
                if (draft.selected in draft.masks.indices) {
                    draft.masks = draft.masks.filterIndexed { i, _ -> i != draft.selected }
                    draft.selected = -1
                    draft.error = null
                }
            },
            label = "删除选中",
            icon = LucideIcons.Trash2,
            enabled = !locked && draft.selected in draft.masks.indices
        )
        GeoKey(
            onClick = { draft.clearing = true },
            label = "清空全部",
            icon = LucideIcons.Trash2,
            tone = GeoTone.Danger,
            enabled = !locked && draft.masks.isNotEmpty()
        )
    }
    draft.error?.let {
        Text(it, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall)
    }

    if (draft.clearing) {
        GeoConfirm(
            title = "清空全部遮罩",
            text = "确认清空 ${draft.masks.size} 个遮罩？保存后该来源会恢复全画面识别。",
            confirmLabel = "清空",
            onCancel = { draft.clearing = false },
            onConfirm = {
                draft.masks = emptyList()
                draft.selected = -1
                draft.clearing = false
            }
        )
    }
}

/** 遮罩页页脚：还原 / 取消 / 保存，与参数页共用抽屉的页脚槽。 */
@Composable
internal fun MaskPageFooter(
    draft: MaskDraft,
    running: Boolean,
    hasFrame: Boolean,
    onCancel: () -> Unit,
    onSave: (List<MaskRegion>) -> String?,
    onMessage: (String?) -> Unit
) {
    GeoKey(
        onClick = { draft.restore() },
        label = "还原当前值",
        icon = LucideIcons.RotateCcw,
        enabled = !running
    )
    GeoKey(onClick = onCancel, label = "取消")
    GeoKey(
        onClick = {
            when {
                running -> draft.error = "请先停止来源；遮罩是冷改动"
                !draft.masks.all { validRegion(it) } -> draft.error = "遮罩越界或尺寸小于 ${(Geo.MASK_MIN_EDGE * 100).toInt()}%"
                else -> {
                    val failure = onSave(draft.masks)
                    if (failure == null) onMessage("遮罩已保存，重新启动来源后生效") else draft.error = failure
                }
            }
        },
        label = "保存遮罩",
        icon = LucideIcons.Check,
        tone = GeoTone.Primary,
        enabled = !running && hasFrame
    )
}

/** 画布：26dp 内衬，按画面比例等比，遮罩坐标相对画面矩形。 */
@Composable
private fun MaskPlane(
    frame: ArgbImage?,
    masks: List<MaskRegion>,
    selected: Int,
    tool: MaskTool,
    locked: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: (MaskRegion) -> Unit,
    onUpdate: (Int, (MaskRegion) -> MaskRegion) -> Unit
) {
    val image by rememberFrameBitmap(frame, maxWidth = 1280)
    val ratio = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 4f / 3f
    Column(Modifier.fillMaxWidth().padding(Geo.MaskInset)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val planeWidth = maxWidth
            val planeHeight = planeWidth / ratio
            var planeSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                Modifier
                    .width(planeWidth)
                    .height(planeHeight)
                    .background(FrameBackdrop, RectangleShape)
                    .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .clip(RectangleShape)
                    .onSizeChanged { planeSize = it }
                    .pointerInput(locked, tool, masks.size) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (planeSize.width <= 0 || planeSize.height <= 0) return@awaitEachGesture
                            // 选择 / 移动模式：点空白不再清掉已有选择，误触代价比「取消选择」更大。
                            if (tool == MaskTool.Move) return@awaitEachGesture
                            val startX = (down.position.x / planeSize.width).coerceIn(0f, 1f)
                            val startY = (down.position.y / planeSize.height).coerceIn(0f, 1f)
                            val travelThreshold = 16.dp.toPx()
                            var travelled = 0f
                            var region = buildRegion(startX, startY, startX, startY)
                            drag(down.id) { change ->
                                val dx = change.position.x - change.previousPosition.x
                                val dy = change.position.y - change.previousPosition.y
                                travelled += kotlin.math.sqrt(dx * dx + dy * dy)
                                val endX = (change.position.x / planeSize.width).coerceIn(0f, 1f)
                                val endY = (change.position.y / planeSize.height).coerceIn(0f, 1f)
                                region = buildRegion(startX, startY, endX, endY)
                                change.consume()
                            }
                            // 手指没有真正拖出去就当成误触：轻点不再生成最小尺寸的碎遮罩。
                            if (travelled >= travelThreshold) onAdd(region)
                        }
                    }
            ) {
                FrameCanvas(
                    image = image,
                    detections = emptyList(),
                    maskRegions = masks,
                    modifier = Modifier.fillMaxSize()
                )
                masks.forEachIndexed { index, region ->
                    MaskBox(
                        index = index,
                        region = region,
                        planeWidth = planeWidth,
                        planeHeight = planeHeight,
                        planeSize = planeSize,
                        selected = index == selected,
                        locked = locked,
                        onSelect = { onSelect(index) },
                        onUpdate = { transform -> onUpdate(index, transform) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MaskBox(
    index: Int,
    region: MaskRegion,
    planeWidth: androidx.compose.ui.unit.Dp,
    planeHeight: androidx.compose.ui.unit.Dp,
    planeSize: IntSize,
    selected: Boolean,
    locked: Boolean,
    onSelect: () -> Unit,
    onUpdate: ((MaskRegion) -> MaskRegion) -> Unit
) {
    Box(
        Modifier
            .offset(x = planeWidth * region.x, y = planeHeight * region.y)
            .width((planeWidth * region.width).coerceAtLeast(2.dp))
            .height((planeHeight * region.height).coerceAtLeast(2.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                RectangleShape
            )
            .geoFocusable()
            .focusable(enabled = !locked)
            .onFocusChanged { if (it.isFocused) onSelect() }
            .onPreviewKeyEvent { event ->
                if (locked || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionLeft -> -0.01f to 0f
                    Key.DirectionRight -> 0.01f to 0f
                    Key.DirectionUp -> 0f to -0.01f
                    Key.DirectionDown -> 0f to 0.01f
                    else -> return@onPreviewKeyEvent false
                }
                onUpdate { current ->
                    current.copy(
                        x = (current.x + delta.first).coerceIn(0f, 1f - current.width),
                        y = (current.y + delta.second).coerceIn(0f, 1f - current.height)
                    )
                }
                true
            }
            .pointerInput(locked, region, planeSize) {
                if (locked || planeSize.width <= 0 || planeSize.height <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSelect() }
                ) { change, drag ->
                    val dx = drag.x / planeSize.width
                    val dy = drag.y / planeSize.height
                    onUpdate { current ->
                        current.copy(
                            x = (current.x + dx).coerceIn(0f, 1f - current.width),
                            y = (current.y + dy).coerceIn(0f, 1f - current.height)
                        )
                    }
                    change.consume()
                }
            }
    ) {
        if (selected) {
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(MaterialTheme.colorScheme.primary, RectangleShape)
                    .padding(horizontal = 4.dp)
            )
            for (corner in MaskCorner.entries) {
                MaskHandle(
                    corner = corner,
                    region = region,
                    selectedIndex = index,
                    planeWidth = planeWidth,
                    planeHeight = planeHeight,
                    planeSize = planeSize,
                    locked = locked,
                    onSelect = onSelect,
                    onUpdate = onUpdate
                )
            }
        }
    }
}

private enum class MaskCorner(val left: Boolean, val top: Boolean) {
    TopLeft(true, true),
    TopRight(false, true),
    BottomLeft(true, false),
    BottomRight(false, false)
}

/** 四角把手：48dp 触点；贴图像边时自动内移，保证完整落在画面内。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MaskHandle(
    corner: MaskCorner,
    region: MaskRegion,
    selectedIndex: Int,
    planeWidth: androidx.compose.ui.unit.Dp,
    planeHeight: androidx.compose.ui.unit.Dp,
    planeSize: IntSize,
    locked: Boolean,
    onSelect: () -> Unit,
    onUpdate: ((MaskRegion) -> MaskRegion) -> Unit
) {
    val handle = Geo.Touch
    val half = handle / 2
    val boxWidth = planeWidth * region.width
    val boxHeight = planeHeight * region.height
    // 贴边内移：留出半个触点，避免把手被图像边缘裁掉。
    val rawX = if (corner.left) -half else boxWidth - half
    val rawY = if (corner.top) -half else boxHeight - half
    val offsetX = rawX.coerceIn(-half, (boxWidth - half).coerceAtLeast(-half))
    val offsetY = rawY.coerceIn(-half, (boxHeight - half).coerceAtLeast(-half))
    Box(
        Modifier
            .offset(x = offsetX, y = offsetY)
            .size(handle)
            .pointerInput(locked, corner, selectedIndex, region, planeSize) {
                if (locked || planeSize.width <= 0 || planeSize.height <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { onSelect() }
                ) { change, drag ->
                    onUpdate { current -> resize(current, corner, drag.x / planeSize.width, drag.y / planeSize.height) }
                    change.consume()
                }
            }
            .geoFocusable()
            .focusable(enabled = !locked)
            .onPreviewKeyEvent { event ->
                if (locked || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val delta = when (event.key) {
                    Key.DirectionLeft -> -0.01f to 0f
                    Key.DirectionRight -> 0.01f to 0f
                    Key.DirectionUp -> 0f to -0.01f
                    Key.DirectionDown -> 0f to 0.01f
                    else -> return@onPreviewKeyEvent false
                }
                onUpdate { current -> resize(current, corner, delta.first, delta.second) }
                true
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, RectangleShape)
                .border(Geo.Border, MaterialTheme.colorScheme.onPrimary, RectangleShape)
        )
    }
}

@Composable
private fun MaskRack(
    masks: List<MaskRegion>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    if (masks.isEmpty()) {
        Text(
            "暂无遮罩；允许清空全部遮罩。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        masks.forEachIndexed { index, region ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Geo.Touch)
                    .background(
                        if (index == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                    .clickable { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .width(Geo.SourceBar)
                        .height(Geo.Touch)
                        .background(if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                )
                Text(
                    "遮罩 ${(index + 1).toString().padStart(2, '0')}",
                    color = if (index == selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    listOf(region.x, region.y, region.width, region.height).joinToString(" / ") { "%.2f".format(it) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            GeoDivider()
        }
    }
}

/** 归一化拖拽矩形：夹在画面内并保证最小边长。 */
private fun buildRegion(startX: Float, startY: Float, endX: Float, endY: Float): MaskRegion {
    var x = minOf(startX, endX)
    var y = minOf(startY, endY)
    var width = abs(endX - startX)
    var height = abs(endY - startY)
    if (width < Geo.MASK_MIN_EDGE) {
        width = Geo.MASK_MIN_EDGE
        x = x.coerceAtMost(1f - width)
    }
    if (height < Geo.MASK_MIN_EDGE) {
        height = Geo.MASK_MIN_EDGE
        y = y.coerceAtMost(1f - height)
    }
    return MaskRegion(x.coerceIn(0f, 1f - width), y.coerceIn(0f, 1f - height), width, height)
}

/** 拉伸：被拖的角跟随指针，其余边保持不动，最小边长 2%。 */
private fun resize(region: MaskRegion, corner: MaskCorner, dx: Float, dy: Float): MaskRegion {
    val left = region.x
    val top = region.y
    val right = region.x + region.width
    val bottom = region.y + region.height
    val nextLeft = if (corner.left) (left + dx).coerceIn(0f, right - Geo.MASK_MIN_EDGE) else left
    val nextRight = if (corner.left) right else (right + dx).coerceIn(left + Geo.MASK_MIN_EDGE, 1f)
    val nextTop = if (corner.top) (top + dy).coerceIn(0f, bottom - Geo.MASK_MIN_EDGE) else top
    val nextBottom = if (corner.top) bottom else (bottom + dy).coerceIn(top + Geo.MASK_MIN_EDGE, 1f)
    return MaskRegion(nextLeft, nextTop, nextRight - nextLeft, nextBottom - nextTop)
}

/** 遮罩是否在画面内且不小于最小边长。 */
private fun validRegion(region: MaskRegion): Boolean =
    region.x >= 0f && region.y >= 0f &&
        region.width >= Geo.MASK_MIN_EDGE && region.height >= Geo.MASK_MIN_EDGE &&
        region.x + region.width <= 1.0001f && region.y + region.height <= 1.0001f

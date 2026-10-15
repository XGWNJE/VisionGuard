package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.icons.LucideIcons
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 抽屉：右侧平移进入，180ms；Escape 关闭；关闭后焦点回到调用处。
 * 参数 / 遮罩抽屉宽 ≤760，普通抽屉 560，窄屏按可用宽度收缩。
 */
@Composable
fun GeoDrawer(
    title: String,
    sectionId: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Geo.Drawer,
    footer: (@Composable () -> Unit)? = null,
    scrollState: ScrollState = rememberScrollState(),
    // 默认由抽屉提供滚动；两栏布局的调用方（设置抽屉宽屏）自己安排滚动，避免嵌套滚动。
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.activity.compose.BackHandler(onBack = onClose)
    val reduced = rememberReducedMotion()
    val focusRequester = remember { FocusRequester() }
    val focusRing = remember { GeoFocusRing() }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        // 等一帧再要焦点：抽屉刚组合时还没布局，直接 requestFocus 会静默失败。
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 宽度跟随父约束，而不是整屏宽度：分屏与自由窗口下不会算错。
        val drawerWidth = minOf(width, maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                // 遮罩层只响应触摸关闭，不进入键盘焦点（否则 Tab 会离开抽屉的焦点环）。
                .pointerInput(Unit) { detectTapGestures { onClose() } }
        )
        AnimatedVisibility(
            visible = shown,
            enter = if (reduced) androidx.compose.animation.EnterTransition.None else slideInHorizontally(tween(Geo.DRAWER_MS)) { it },
            exit = if (reduced) androidx.compose.animation.ExitTransition.None else slideOutHorizontally(tween(Geo.DRAWER_MS)) { it },
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Column(
                modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface, RectangleShape)
                    .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .clip(RectangleShape)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Escape -> {
                                onClose()
                                true
                            }
                            // Tab / Shift+Tab 在抽屉内环绕，不把焦点漏到主界面。
                            event.key == Key.Tab -> focusRing.move(forward = !event.isShiftPressed)
                            else -> false
                        }
                    }
            ) {
                CompositionLocalProvider(LocalGeoFocusRing provides focusRing) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            sectionId,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta, letterSpacing = 1.sp)
                        )
                        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    }
                    GeoIconKey(LucideIcons.X, "关闭面板", onClose)
                }
                GeoDivider()
                if (scrollable) {
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(Geo.GroupGap),
                        content = content
                    )
                } else {
                    Column(Modifier.weight(1f).fillMaxWidth(), content = content)
                }
                footer?.let {
                    GeoDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(Geo.Gap, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) { it() }
                }
                }
            }
        }
    }
}

/**
 * 滚筒：112 × 220，数字 40px；拖动 / ↑↓ / Home / End，端点是止挡，松手吸附。
 * [step] 用于冷却的十进制位（百 / 十 / 个）。
 */
@Composable
fun GeoWheel(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    step: Int = 1,
    unit: String = "",
    format: (Int) -> String = { it.toString() },
    size: Dp = Geo.WheelWidth,
    height: Dp = Geo.WheelHeight,
    numberSize: androidx.compose.ui.unit.TextUnit = Geo.WheelNumber,
    compact: Boolean = false
) {
    var dragPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 46.dp.toPx() }
    val snapped = animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(if (rememberReducedMotion()) 0 else 120),
        label = "geoWheelSnap"
    )
    val live = value + dragPx / stepPx
    val position = if (dragging) live.coerceIn(min - 0.2f * step, max + 0.2f * step) else snapped.value
    val active = (value + (position - value).roundToInt() * step).coerceIn(min, max)

    val currentValue = rememberUpdatedState(value)

    fun commit(target: Int) {
        val bounded = target.coerceIn(min, max)
        if (bounded != currentValue.value) onChange(bounded)
    }

    val focusRequester = remember { FocusRequester() }
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(size)
                    .height(height)
                    .background(MaterialTheme.colorScheme.surface, RectangleShape)
                    .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .clip(RectangleShape)
                    .geoFocusable()
                    .focusable(enabled = enabled)
                    .onPreviewKeyEvent { event ->
                        if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val deltas = mapOf(
                            Key.DirectionDown to step,
                            Key.DirectionUp to -step,
                            Key.PageDown to step * 3,
                            Key.PageUp to -step * 3
                        )
                        when {
                            deltas.containsKey(event.key) -> {
                                commit(value + deltas.getValue(event.key))
                                true
                            }
                            event.key == Key.MoveHome -> {
                                commit(min); true
                            }
                            event.key == Key.MoveEnd -> {
                                commit(max); true
                            }
                            else -> false
                        }
                    }
                    .pointerInput(enabled, min, max, step) {
                        if (!enabled) return@pointerInput
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                val target = (currentValue.value + (dragPx / stepPx).roundToInt() * step).coerceIn(min, max)
                                dragPx = 0f
                                dragging = false
                                commit(target)
                            },
                            onDragCancel = {
                                dragPx = 0f
                                dragging = false
                            }
                        ) { _, drag ->
                            dragPx += drag.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)
                )
                for (i in -3..3) {
                    val number = value + i * step
                    if (number < min || number > max) continue
                    val d = (number - position) / step
                    val clamped = d.coerceIn(-3f, 3f)
                    val y = sin(clamped * 0.43f) * (height * 0.45f).value
                    val scale = 1f - minOf(abs(d), 3f) * 0.15f
                    val alpha = maxOf(0.18f, 1f - abs(d) * 0.26f)
                    Text(
                        format(number),
                        color = if (number == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = numberSize, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .offset(y = y.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            }
                    )
                }
                Spacer(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(Geo.Border)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(Geo.Border)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
            if (!compact) {
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GeoIconKey(
                        LucideIcons.ChevronUp,
                        "$label 上一档",
                        onClick = { commit(value - step) },
                        enabled = enabled && value > min
                    )
                    GeoIconKey(
                        LucideIcons.ChevronDown,
                        "$label 下一档",
                        onClick = { commit(value + step) },
                        enabled = enabled && value < max
                    )
                    Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** 精确整数输入：`− 值 单位 +`，非法值不提交也不冒充已保存。 */
@Composable
fun GeoCounterInput(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    unit: String,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }

    fun commit() {
        val parsed = text.toIntOrNull()
        if (parsed == null || parsed < min || parsed > max) {
            error = "请输入 $min–$max 的整数"
            return
        }
        error = null
        if (parsed != value) onChange(parsed)
    }

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Geo.Touch)
                .background(MaterialTheme.colorScheme.surface, RectangleShape)
                .border(Geo.Border, if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, RectangleShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GeoIconKey(
                LucideIcons.Minus,
                "$label 减少",
                onClick = {
                    val next = (value - 1).coerceAtLeast(min)
                    onChange(next)
                    text = next.toString()
                    error = null
                },
                enabled = enabled && value > min,
                tone = GeoTone.Secondary,
                size = Geo.Touch
            )
            BasicTextField(
                value = text,
                onValueChange = { next ->
                    text = next.filter { it.isDigit() }
                    error = null
                },
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .weight(1f)
                    .geoFocusable()
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            focused = true
                        } else if (focused) {
                            focused = false
                            commit()
                        }
                    }
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            GeoIconKey(
                LucideIcons.Plus,
                "$label 增加",
                onClick = {
                    val next = (value + 1).coerceAtMost(max)
                    onChange(next)
                    text = next.toString()
                    error = null
                },
                enabled = enabled && value < max,
                tone = GeoTone.Secondary,
                size = Geo.Touch
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** 置信度滑轨：轨高 32、游标 20 × 42，可拖可点，附整数精确输入。 */
@Composable
fun GeoConfidenceRail(
    value: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val min = 10
    val max = 95
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                value.toString(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            )
            Text("%", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("10—95 / 整数", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(Geo.RailHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)
                .pointerInput(enabled, min, max) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start ->
                            val fraction = (start.x / size.width).coerceIn(0f, 1f)
                            onChange((min + fraction * (max - min)).roundToInt().coerceIn(min, max))
                        }
                    ) { change, _ ->
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onChange((min + fraction * (max - min)).roundToInt().coerceIn(min, max))
                    }
                }
        ) {
            val fraction = (value - min).toFloat() / (max - min)
            val cursorX = (maxWidth - Geo.CursorWidth) * fraction
            Box(
                Modifier
                    .offset(x = cursorX)
                    .width(Geo.CursorWidth)
                    .height(Geo.CursorHeight)
                    .align(Alignment.CenterStart)
                    .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, RectangleShape)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("10", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("50", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("95", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        GeoCounterInput("置信度", value, min, max, "%", enabled, onChange)
    }
}

/** 限位输入：单行、直角、聚焦主色描边，失焦 / 回车提交。 */
@Composable
fun GeoFlatTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    placeholder: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    minHeight: Dp = Geo.Touch,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
                Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
        }
        trailing?.invoke()
    }
}

/** 插片式卡位：模型 / 目标等多选与单选格。 */
@Composable
fun GeoSlotCard(
    title: String,
    subtitle: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    trailingDescription: String? = null,
    onTrailingClick: (() -> Unit)? = null
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        !enabled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier
            .heightIn(min = 72.dp)
            .background(background, RectangleShape)
            .border(if (selected) 2.dp else Geo.Border, borderColor, RectangleShape)
            .geoFocusable()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
        }
        // 右下角是这一项的产物操作（模型档位是「下载 / 删除」，见调用方）；已下载的选中项另用对勾表达选中。
        if (trailingIcon != null && onTrailingClick != null) {
            GeoIconKey(
                trailingIcon,
                trailingDescription ?: "下载 $title",
                onClick = onTrailingClick,
                modifier = Modifier.align(Alignment.BottomEnd).size(Geo.Touch)
            )
            if (selected) {
                Icon(
                    LucideIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        } else if (selected) {
            Icon(
                LucideIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp)
            )
        }
    }
}
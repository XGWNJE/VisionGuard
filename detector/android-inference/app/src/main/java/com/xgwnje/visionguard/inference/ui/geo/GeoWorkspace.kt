package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.model.ModelKeys
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.EmptyFramePlaceholder
import com.xgwnje.visionguard.inference.ui.FrameBackdrop
import com.xgwnje.visionguard.inference.ui.FrameCanvas

/** 主预览：等比画面、遮罩与检测框、画面闸门。来源检查改由参数抽屉承载，预览旁不再放入口。 */
@Composable
fun PreviewComposition(
    source: SourceView?,
    freshness: String,
    shutterHidden: Boolean,
    onToggleShutter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frame = source?.state?.preview
    val image by com.xgwnje.visionguard.inference.ui.rememberFrameBitmap(frame, maxWidth = 1600)
    // 画面尺寸跟着画面走；帧率与耗时留在控制带读数里，避免同屏重复。
    val frameMeta = source?.state?.preview?.let { "${it.width}×${it.height}" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                source?.sourceName ?: "主预览",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            frameMeta?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta),
                    maxLines = 1
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(freshness, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                    .background(FrameBackdrop, RectangleShape)
            ) {
                if (image == null || shutterHidden) {
                    EmptyFramePlaceholder(Modifier.fillMaxSize())
                } else {
                    FrameCanvas(
                        image = image,
                        detections = source?.state?.detections.orEmpty(),
                        maskRegions = source?.masks.orEmpty(),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                GeoIconKey(
                    if (shutterHidden) LucideIcons.Eye else LucideIcons.EyeOff,
                    if (shutterHidden) "展开预览" else "收起预览",
                    onClick = onToggleShutter,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                )
                if (source?.state?.error != null && !shutterHidden) {
                    Text(
                        source.state.error,
                        color = VisionGuardStatusColors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
                    )
                }
            }
        }
    }
}

/** 来源滑架：逐来源启停、抽屉入口与添加入口。 */
@Composable
fun SourceCarriage(
    sources: List<SourceView>,
    selectedSourceId: String?,
    maxSources: Int,
    nowElapsedMs: Long,
    busySourceId: String?,
    anyRunning: Boolean,
    stoppingAll: Boolean,
    onSelect: (String) -> Unit,
    onInspect: (String) -> Unit,
    onToggle: (String) -> Unit,
    onStopAll: () -> Unit,
    onAdd: () -> Unit,
    addBlockedReason: String? = null,
    addFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
    ) {
        Row(
            Modifier.fillMaxWidth().height(Geo.Touch).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "来源 / ${sources.size} · $maxSources",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            // 兜底：多路来源都在跑时给一个一键停止，避免逐条点。
            if (anyRunning) {
                GeoKey(
                    onClick = onStopAll,
                    label = "全部停止",
                    icon = LucideIcons.Pause,
                    tone = GeoTone.Danger,
                    busy = stoppingAll,
                    enabled = !stoppingAll,
                    minHeight = Geo.Touch
                )
            }
        }
        GeoDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            sources.forEachIndexed { index, source ->
                SourceStop(
                    index = index + 1,
                    source = source,
                    selected = source.sourceId == selectedSourceId,
                    busy = busySourceId == source.sourceId,
                    onSelect = { onSelect(source.sourceId) },
                    onToggle = { onToggle(source.sourceId) },
                    onInspect = { onInspect(source.sourceId) }
                )
            }
        }
        GeoDivider()
        addBlockedReason?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        GeoKey(
            onClick = onAdd,
            enabled = addBlockedReason == null,
            label = "添加来源",
            icon = LucideIcons.Plus,
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(addFocus)
        )
    }
}

@Composable
private fun SourceStop(
    index: Int,
    source: SourceView,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onInspect: () -> Unit
) {
    val running = source.state.isRunning
    val lamp = when {
        source.state.error != null -> GeoLampTone.Error
        running -> GeoLampTone.Success
        source.state.isReady -> GeoLampTone.Neutral
        else -> GeoLampTone.Warning
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Geo.SourceRow)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(Geo.SourceBar)
                .fillMaxHeight()
                .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
        )
        Text(
            index.toString().padStart(2, '0'),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                source.sourceName,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            GeoLamp(source.state.error ?: source.state.statusText, lamp)
        }
        // 逐来源启停：只影响这一路，整体启停已从主界面移除。
        GeoIconKey(
            icon = when {
                busy -> LucideIcons.LoaderCircle
                running -> LucideIcons.Pause
                else -> LucideIcons.Play
            },
            contentDescription = if (running) "停止 ${source.sourceName}" else "启动 ${source.sourceName}",
            onClick = onToggle,
            enabled = !busy,
            tone = if (running) GeoTone.Secondary else GeoTone.Primary
        )
        GeoIconKey(
            LucideIcons.ChevronRight,
            "打开 ${source.sourceName} 的来源、参数与遮罩",
            onClick = onInspect,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
    GeoDivider()
}

/**
 * 底部状态带：左段与预览同宽（仪表读数 + 参数摘要），右段与来源列表同宽（连接状态 + 应用入口）。
 *
 * 两段用与上方相同的 weight / 固定宽度划分，所以横屏上下分界严格对齐；竖屏整条不分段。
 */
@Composable
fun StatusBar(
    source: SourceView?,
    wide: Boolean,
    connectionText: String,
    connectionTone: GeoLampTone,
    onParamAnchor: (ParamAnchor) -> Unit,
    onConnection: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    helpFocus: FocusRequester,
    settingsFocus: FocusRequester,
    accountFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val running = source?.state?.isRunning == true
    val topConfidence = source?.state?.detections?.maxByOrNull { it.confidence }?.confidence
    val readouts: @Composable (Modifier) -> Unit = { barModifier ->
        // 一排等宽数据格：仪表与参数同一套「小标签 + 加粗值」样式，不再分大小两行。
        Row(barModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            Metric("实际帧率", if (running) "%.1f".format(source?.state?.actualFps ?: 0.0) else "0.0", "FPS", Modifier.weight(1f))
            Metric(
                "检测目标",
                if (running && topConfidence != null) "${(topConfidence * 100).toInt()}" else "—",
                if (running && topConfidence != null) "%" else null,
                Modifier.weight(1f)
            )
            Metric(
                "单帧耗时",
                if (running && (source?.state?.inferenceMs ?: 0L) > 0) "${source?.state?.inferenceMs}" else "—",
                if (running && (source?.state?.inferenceMs ?: 0L) > 0) "ms" else null,
                Modifier.weight(1f)
            )
            Metric(
                "置信度",
                source?.let { "${it.params.confidencePercent}" } ?: "—",
                if (source != null) "%" else null,
                Modifier.weight(1f)
            ) { onParamAnchor(ParamAnchor.MACHINES) }
            Metric(
                "采样",
                source?.let { "${it.params.targetFps}" } ?: "—",
                if (source != null) "FPS" else null,
                Modifier.weight(1f)
            ) { onParamAnchor(ParamAnchor.MACHINES) }
            Metric(
                "冷却",
                source?.let { "${it.params.cooldownSeconds}" } ?: "—",
                if (source != null) "秒" else null,
                Modifier.weight(1f)
            ) { onParamAnchor(ParamAnchor.MACHINES) }
            Metric(
                "模型",
                source?.let { shortModelLabel(it.params.modelKey) } ?: "—",
                null,
                Modifier.weight(2f)
            ) { onParamAnchor(ParamAnchor.MODELS) }
            Metric(
                "目标",
                source?.let { "${it.params.watchedClasses.size}" } ?: "—",
                if (source != null) "个" else null,
                Modifier.weight(1f)
            ) { onParamAnchor(ParamAnchor.TARGETS) }
        }
    }
    val entries: @Composable (Modifier) -> Unit = { entryModifier ->
        Row(
            entryModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Geo.Gap)
        ) {
            // 竖线落在右段最左：与上方来源列表的左边框是同一条线。
            Spacer(
                Modifier
                    .width(Geo.Border)
                    .height(Geo.Touch)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                Modifier
                    .clickable(interactionSource = null, indication = null, onClick = onConnection)
                    .heightIn(min = Geo.Touch)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                GeoLamp(connectionText, connectionTone)
            }
            Spacer(Modifier.weight(1f))
            GeoIconKey(LucideIcons.CircleHelp, "使用帮助", onHelp, modifier = Modifier.focusRequester(helpFocus))
            GeoIconKey(LucideIcons.Settings, "全局设置", onSettings, modifier = Modifier.focusRequester(settingsFocus))
            GeoIconKey(LucideIcons.UserRound, "账号菜单", onAccount, modifier = Modifier.focusRequester(accountFocus))
        }
    }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        // 与上方两块面板同底色，只用一条顶线与一条竖线分段。
        GeoDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Geo.StatusBar)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Geo.Gap)
        ) {
            readouts(Modifier.weight(1f))
            if (wide) {
                // 与上方「来源列表」同宽：上下分界因此对齐。
                Box(Modifier.width(Geo.SourceRail)) { entries(Modifier.fillMaxWidth()) }
            } else {
                entries(Modifier)
            }
        }
    }
}

/** 状态带里的模型短名：`YOLO26M · 320 CPU` → `M·320 CPU`，避免在等宽格里被截断。 */
private fun shortModelLabel(key: String): String =
    ModelKeys.displayName(key).removePrefix("YOLO26").replace(" · ", "·")

/** 底部状态带的数据格：小标签在上、加粗值在下；可点的格子跳到参数抽屉对应区块。 */
@Composable
private fun Metric(
    label: String,
    value: String,
    unit: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
    }
    Column(modifier.then(clickableModifier).heightIn(min = Geo.Touch), verticalArrangement = Arrangement.Center) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            unit?.let {
                Spacer(Modifier.width(2.dp))
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta)
                )
            }
        }
    }
}

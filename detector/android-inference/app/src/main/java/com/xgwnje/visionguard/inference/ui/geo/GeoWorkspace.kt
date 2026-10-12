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

/** 来源滑架：序号、名称、运行状态与添加入口。 */
@Composable
fun SourceCarriage(
    sources: List<SourceView>,
    selectedSourceId: String?,
    maxSources: Int,
    nowElapsedMs: Long,
    onSelect: (String) -> Unit,
    onInspect: (String) -> Unit,
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
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "来源 / ${sources.size} · $maxSources",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text("详情", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta))
        }
        GeoDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            sources.forEachIndexed { index, source ->
                SourceStop(
                    index = index + 1,
                    source = source,
                    selected = source.sourceId == selectedSourceId,
                    nowElapsedMs = nowElapsedMs,
                    onSelect = { onSelect(source.sourceId) },
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
    nowElapsedMs: Long,
    onSelect: () -> Unit,
    onInspect: () -> Unit
) {
    val lamp = when {
        source.state.error != null -> GeoLampTone.Error
        source.state.isRunning -> GeoLampTone.Success
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
        GeoIconKey(
            LucideIcons.ChevronRight,
            "检查 ${source.sourceName}",
            onClick = onInspect,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
    GeoDivider()
}

/** 操作台：后端 / 帧率 / 目标读数、启停压板与参数、遮罩方键。 */
@Composable
fun ActionDeck(
    source: SourceView?,
    busy: Boolean,
    wide: Boolean,
    onToggle: () -> Unit,
    onParams: () -> Unit,
    onMasks: () -> Unit,
    paramsFocus: FocusRequester,
    masksFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    val running = source?.state?.isRunning == true
    val topConfidence = source?.state?.detections?.maxByOrNull { it.confidence }?.confidence
    val readouts: @Composable (Modifier) -> Unit = { readoutModifier ->
        Row(readoutModifier, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            Readout("实际帧率", if (running) "%.1f".format(source?.state?.actualFps ?: 0.0) else "0.0", "FPS", Modifier.weight(1f))
            Readout(
                "检测目标",
                if (running && topConfidence != null) "${(topConfidence * 100).toInt()}" else "—",
                if (running && topConfidence != null) "%" else null,
                Modifier.weight(1f)
            )
            Readout(
                "单帧耗时",
                if (running && (source?.state?.inferenceMs ?: 0L) > 0) "${source?.state?.inferenceMs}" else "—",
                if (running && (source?.state?.inferenceMs ?: 0L) > 0) "ms" else null,
                Modifier.weight(1f)
            )
        }
    }
    val keys: @Composable (Modifier) -> Unit = { keyModifier ->
        Row(keyModifier, horizontalArrangement = Arrangement.spacedBy(Geo.Gap), verticalAlignment = Alignment.CenterVertically) {
            GeoKey(
                onClick = onToggle,
                tone = GeoTone.Primary,
                // 首次启动前没有任何成功帧，不能用「已处理帧」当门槛：未运行时始终可尝试启动，
                // 缺权限或未绑定由 runtime 返回原因。
                enabled = source != null,
                busy = busy,
                icon = if (running) LucideIcons.Pause else LucideIcons.Play,
                label = if (busy) "处理中…" else if (running) "停止检测" else "开始检测",
                labelStyle = MaterialTheme.typography.titleMedium,
                minHeight = Geo.PaddleHeight,
                modifier = Modifier.weight(1f)
            )
            GeoKey(
                onClick = onParams,
                enabled = source != null,
                icon = LucideIcons.SlidersHorizontal,
                label = "参数",
                minWidth = Geo.SquareKey,
                minHeight = Geo.PaddleHeight,
                modifier = Modifier.focusRequester(paramsFocus)
            )
            GeoKey(
                onClick = onMasks,
                enabled = source != null,
                icon = LucideIcons.Scan,
                label = "遮罩",
                minWidth = Geo.SquareKey,
                minHeight = Geo.PaddleHeight,
                modifier = Modifier.focusRequester(masksFocus)
            )
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = Geo.ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(Geo.Gap)
    ) {
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                readouts(Modifier.width(360.dp))
                keys(Modifier.weight(1f))
            }
        } else {
            readouts(Modifier.fillMaxWidth())
            keys(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Readout(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            unit?.let {
                Spacer(Modifier.width(2.dp))
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 状态带：参数摘要与技术标识，右端放应用级入口（连接状态、帮助、设置、账号）。
 *
 * owner 要求不留头部标题行、把高度让给画面，所以应用级入口落在这一行里。
 * 运行状态（运行中只读 / 已停止）只在预览标题行说一次，这里不再重复。
 */
@Composable
fun ParameterStrip(
    source: SourceView?,
    connectionText: String,
    connectionTone: GeoLampTone,
    onParams: (ParamAnchor) -> Unit,
    onConnection: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    helpFocus: FocusRequester,
    settingsFocus: FocusRequester,
    accountFocus: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Geo.StatusBar)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = Geo.ScreenPadding, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Geo.Gap)
        ) {
            // 推理后端是技术标识而不是实时读数：放在摘要行里，长名字不再被读数格截断。
            source?.state?.backend?.takeIf { it.isNotEmpty() }?.let { backend ->
                Text(
                    "后端 $backend",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            source?.let { view ->
                StripItem("置信度", "${view.params.confidencePercent}%") { onParams(ParamAnchor.MACHINES) }
                StripItem("采样", "${view.params.targetFps} FPS") { onParams(ParamAnchor.MACHINES) }
                StripItem("冷却", "${view.params.cooldownSeconds} 秒") { onParams(ParamAnchor.MACHINES) }
                StripItem(null, ModelKeys.displayName(view.params.modelKey)) { onParams(ParamAnchor.MODELS) }
                StripItem(null, "${view.params.watchedClasses.size} 个目标") { onParams(ParamAnchor.TARGETS) }
            }
        }
        Box(Modifier.clickable(onClick = onConnection).padding(horizontal = 8.dp)) {
            GeoLamp(connectionText, connectionTone)
        }
        GeoIconKey(LucideIcons.CircleHelp, "使用帮助", onHelp, modifier = Modifier.focusRequester(helpFocus))
        GeoIconKey(LucideIcons.Settings, "全局设置", onSettings, modifier = Modifier.focusRequester(settingsFocus))
        GeoIconKey(LucideIcons.UserRound, "账号菜单", onAccount, modifier = Modifier.focusRequester(accountFocus))
    }
}

@Composable
private fun StripItem(label: String?, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = Geo.Touch)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (label != null) {
            Text("$label ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import com.xgwnje.visionguard.inference.runtime.ModelState
import kotlinx.coroutines.launch

private val cooldownPresets = listOf(5, 10, 30, 60)

/**
 * 参数抽屉：整组参数先落成草稿，校验通过才提交；运行中只读。
 *
 * 尺寸按[几何空间界面规范](../../../../../../../docs/73-几何空间界面规范.md)：滚筒 112×220、
 * 冷却每位 60×146、滑轨轨高 32、游标 20×42、目标 5 列且格高 ≥62、抽屉宽 ≤760。
 */
@Composable
fun GeoParameterDrawer(
    sourceName: String,
    initial: DetectionParams,
    running: Boolean,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    onDismiss: () -> Unit,
    onDownloadModel: suspend (String) -> String?,
    onSave: (DetectionParams) -> String?,
    onMessage: (String?) -> Unit
) {
    var confidence by remember(initial) { mutableStateOf(initial.confidencePercent) }
    var targetFps by remember(initial) { mutableStateOf(initial.targetFps) }
    var cooldown by remember(initial) { mutableStateOf(initial.cooldownSeconds) }
    var modelKey by remember(initial) { mutableStateOf(initial.modelKey) }
    var watched by remember(initial) { mutableStateOf(initial.watchedClasses) }
    var search by remember { mutableStateOf("") }
    var reviewed by remember(initial) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val locked = running

    val confidenceError = DetectionParams.validateConfidence(confidence)
    val fpsError = DetectionParams.validateTargetFps(targetFps)
    val cooldownError = DetectionParams.validateCooldown(cooldown)
    val modelDownloaded = models.firstOrNull { it.key == modelKey }?.downloaded == true
    val targetsText = watched.joinToString(",")
    val targetsValid = watched.isNotEmpty() && watched.all { CocoClassMap.EnZh.containsKey(it) }
    val targetsWithinLimit = watched.size <= DetectionParams.MAX_TARGET_COUNT &&
        targetsText.length <= DetectionParams.MAX_TARGETS_TEXT_LENGTH
    val canSave = !locked && !saving && confidenceError == null && fpsError == null && cooldownError == null &&
        modelDownloaded && targetsValid && targetsWithinLimit && reviewed

    fun restore() {
        confidence = initial.confidencePercent
        targetFps = initial.targetFps
        cooldown = initial.cooldownSeconds
        modelKey = initial.modelKey
        watched = initial.watchedClasses
        search = ""
        reviewed = true
        error = null
    }

    fun save() {
        if (locked) {
            error = "请先停止来源；参数是冷改动，停止后保存、下次启动生效"
            return
        }
        val failure = onSave(
            initial.copy(
                confidencePercent = confidence,
                targetFps = targetFps,
                cooldownSeconds = cooldown,
                modelKey = modelKey,
                watchedClasses = watched
            )
        )
        if (failure == null) onMessage("检测规则已保存，重新启动来源后生效") else error = failure
    }

    GeoDrawer(
        title = "检测参数 · $sourceName",
        sectionId = "VISION / CONTROL",
        onClose = onDismiss,
        width = Geo.ParamDrawer,
        footer = {
            GeoKey(
                onClick = { restore() },
                label = "还原当前值",
                icon = LucideIcons.RotateCcw,
                enabled = !saving
            )
            GeoKey(onClick = onDismiss, label = "取消", enabled = !saving)
            GeoKey(
                onClick = { save() },
                label = "保存参数",
                icon = LucideIcons.Check,
                tone = GeoTone.Primary,
                enabled = canSave,
                busy = saving
            )
        }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GeoLamp(if (locked) "运行中 · 只读" else "已停止 · 可编辑", if (locked) GeoLampTone.Warning else GeoLampTone.Neutral)
            Spacer(Modifier.weight(1f))
            Text(
                "草稿 / 保存后下次启动生效",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (locked) GeoHint("停止检测后，才能调整参数；运行中的来源不会被编辑操作隐式停止。", GeoLampTone.Warning)

        GeoSectionLabel("01", "采样频率", "${DetectionParams.MIN_TARGET_FPS}—${DetectionParams.MAX_TARGET_FPS} FPS")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
            GeoWheel(
                label = "采样频率",
                value = targetFps,
                min = DetectionParams.MIN_TARGET_FPS,
                max = DetectionParams.MAX_TARGET_FPS,
                enabled = !locked,
                unit = "FPS",
                onChange = {
                    targetFps = it
                    error = null
                }
            )
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        targetFps.toString(),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("FPS", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "上下拖动\n松手吸附",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        fpsError?.let { FieldError(it) }

        GeoDivider()
        GeoSectionLabel("02", "置信度", "${DetectionParams.MIN_CONFIDENCE_PERCENT}—${DetectionParams.MAX_CONFIDENCE_PERCENT}%")
        GeoConfidenceRail(
            value = confidence,
            enabled = !locked,
            onChange = {
                confidence = it
                error = null
            }
        )
        confidenceError?.let { FieldError(it) }

        GeoDivider()
        GeoSectionLabel("03", "报警冷却", "${DetectionParams.MIN_COOLDOWN_SECONDS}—${DetectionParams.MAX_COOLDOWN_SECONDS} 秒")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            for (step in listOf(100, 10, 1)) {
                GeoWheel(
                    label = "冷却${step}位",
                    value = cooldown,
                    min = DetectionParams.MIN_COOLDOWN_SECONDS,
                    max = DetectionParams.MAX_COOLDOWN_SECONDS,
                    enabled = !locked,
                    step = step,
                    format = { ((it / step) % 10).toString() },
                    size = Geo.DigitWidth,
                    height = Geo.DigitHeight,
                    numberSize = Geo.DigitNumber,
                    compact = true,
                    onChange = {
                        cooldown = it
                        error = null
                    }
                )
            }
            Text("秒", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            cooldownPresets.forEach { preset ->
                GeoKey(
                    onClick = {
                        cooldown = preset
                        error = null
                    },
                    label = "${preset}s",
                    tone = if (cooldown == preset) GeoTone.Primary else GeoTone.Secondary,
                    enabled = !locked,
                    minWidth = 72.dp
                )
            }
        }
        GeoCounterInput(
            label = "冷却时间",
            value = cooldown,
            min = DetectionParams.MIN_COOLDOWN_SECONDS,
            max = DetectionParams.MAX_COOLDOWN_SECONDS,
            unit = "秒",
            enabled = !locked,
            onChange = {
                cooldown = it
                error = null
            }
        )
        cooldownError?.let { FieldError(it) }

        GeoDivider()
        GeoSectionLabel("04", "模型插槽", "仅已下载模型可选")
        ModelCartridges(
            models = models,
            downloads = downloads,
            selected = modelKey,
            enabled = !locked,
            onSelect = {
                modelKey = it
                reviewed = it == initial.modelKey
                error = null
            },
            onDownload = { key -> scope.launch { onDownloadModel(key)?.let(onMessage) } }
        )
        if (!reviewed) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Geo.Touch)
                    .clickable(enabled = !locked) { reviewed = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
                        .background(MaterialTheme.colorScheme.surface, RectangleShape),
                    contentAlignment = Alignment.Center
                ) { }
                Spacer(Modifier.width(8.dp))
                Text("我已根据新模型重新核对检测目标", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!modelDownloaded) FieldError("当前模型未下载")

        GeoDivider()
        GeoSectionLabel("05", "检测目标", "至少一项")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            GeoFlatTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = "目标搜索 · 中文 / 英文",
                leading = LucideIcons.Search,
                enabled = !locked,
                modifier = Modifier.weight(1f),
                trailing = {
                    GeoIconKey(
                        LucideIcons.X,
                        "清空搜索",
                        onClick = { search = "" },
                        enabled = search.isNotEmpty() && !locked
                    )
                }
            )
        }
        if (watched.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Geo.Gap)
            ) {
                watched.sorted().forEach { key ->
                    SelectedTargetChip(
                        label = CocoClassMap.displayName(key),
                        enabled = !locked,
                        onRemove = { watched = watched - key }
                    )
                }
            }
        }
        TargetGrid(
            watched = watched,
            search = search,
            enabled = !locked,
            onToggle = { key ->
                watched = if (watched.contains(key)) watched - key else watched + key
                error = null
            }
        )
        Text(
            if (targetsValid && targetsWithinLimit) {
                "已选择 ${watched.size} 项 · ${targetsText.length} / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
            } else {
                "至少选择一个检测目标，且不得超过 ${DetectionParams.MAX_TARGET_COUNT} 项 / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
            },
            color = if (targetsValid && targetsWithinLimit) MaterialTheme.colorScheme.onSurfaceVariant else VisionGuardStatusColors.error,
            style = MaterialTheme.typography.bodySmall
        )

        error?.let { FieldError(it) }
    }
}

@Composable
private fun FieldError(text: String) {
    Text(text, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall)
}

/** 模型插槽：三列插片，未下载只给下载入口；选中与已下载分别表达。 */
@Composable
private fun ModelCartridges(
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        models.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                row.forEach { model ->
                    val progress = downloads[model.key]
                    GeoSlotCard(
                        title = model.displayName,
                        subtitle = when {
                            progress != null && progress.total > 0 -> "下载 ${progress.percent}%"
                            progress != null -> "下载中…"
                            model.downloaded -> "已下载"
                            else -> "未下载"
                        },
                        selected = model.key == selected,
                        enabled = enabled && model.downloaded,
                        onClick = { onSelect(model.key) },
                        modifier = Modifier.weight(1f),
                        trailingIcon = if (!model.downloaded && progress == null) LucideIcons.Download else null,
                        onTrailingClick = { onDownload(model.key) }
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    models.forEach { model ->
        val progress = downloads[model.key] ?: return@forEach
        if (progress.total <= 0) return@forEach
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.percent / 100f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RectangleShape)
            )
        }
    }
}

@Composable
private fun SelectedTargetChip(label: String, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = Geo.Touch)
            .background(MaterialTheme.colorScheme.primaryContainer, RectangleShape)
            .clickable(enabled = enabled, onClick = onRemove)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(
            LucideIcons.X,
            contentDescription = "取消目标 $label",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** 目标排片：五列、格高 ≥62dp，选中用「选中表面」令牌。 */
@Composable
private fun TargetGrid(
    watched: Set<String>,
    search: String,
    enabled: Boolean,
    onToggle: (String) -> Unit
) {
    val query = search.trim()
    val candidates = if (query.isEmpty()) {
        CocoClassMap.EnglishNames
    } else {
        CocoClassMap.EnglishNames.filter { name ->
            name.contains(query, ignoreCase = true) ||
                CocoClassMap.displayName(name).contains(query, ignoreCase = true)
        }
    }
    Text(
        "已选 ${watched.size} 项 · 匹配 ${candidates.size} 项",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
    if (candidates.isEmpty()) {
        Text("没有匹配的目标。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        candidates.chunked(Geo.TARGET_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                row.forEach { key ->
                    TargetTile(
                        title = CocoClassMap.displayName(key),
                        subtitle = key,
                        selected = watched.contains(key),
                        enabled = enabled,
                        onClick = { onToggle(key) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(Geo.TARGET_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TargetTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .heightIn(min = Geo.TargetCellMinHeight)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                RectangleShape
            )
            .border(
                if (selected) 2.dp else Geo.Border,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RectangleShape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = Geo.FontMeta),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                LucideIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopEnd).size(14.dp)
            )
        }
    }
}

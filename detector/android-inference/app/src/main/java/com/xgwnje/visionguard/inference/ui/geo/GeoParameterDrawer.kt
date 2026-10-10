package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import com.xgwnje.visionguard.inference.runtime.ModelState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.formatElapsedMoment
import com.xgwnje.visionguard.inference.ui.formatWallMoment
import kotlinx.coroutines.launch

/** 参数抽屉的分区：来源检查与检测参数合并到同一个抽屉，用拨档切换。 */
enum class ParamSection(val title: String) {
    SOURCE("来源"),
    DETECTION("检测参数")
}

/** 抽屉内可用底部信息带直接定位的锚点。 */
enum class ParamAnchor {
    NAME, LENS, MAINTENANCE, MACHINES, MODELS, TARGETS
}

private val cooldownPresets = listOf(5, 10, 30, 60)
private val commonTargets = listOf("person", "bicycle", "car", "motorcycle", "bus", "truck", "cat", "dog", "backpack", "handbag")

/**
 * 参数抽屉：上拨档切「来源」与「检测参数」两页，各自尽量一屏放完。
 *
 * 来源页承载来源身份、运行状态、名称、镜头与删除；检测参数页承载采样 / 置信度 / 冷却 /
 * 模型 / 目标。底部信息带的参数项通过 [scrollTo] 直接定位到对应区块。
 */
@Composable
fun GeoParameterDrawer(
    source: SourceView?,
    nowWallMs: Long,
    nowElapsedMs: Long,
    initialSection: ParamSection,
    scrollTo: ParamAnchor?,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    onDismiss: () -> Unit,
    onRename: (String) -> String?,
    onSetLens: (Int) -> String?,
    onDelete: () -> Unit,
    onDownloadModel: suspend (String) -> String?,
    onSave: (DetectionParams) -> String?,
    onMessage: (String?) -> Unit
) {
    if (source == null) return
    var section by remember { mutableStateOf(initialSection) }
    val scrollState = rememberScrollState()
    // 普通 Map：布局回调写入不能进 State，否则会触发重组与布局互相触发的死循环。
    val anchors = remember { mutableMapOf<ParamAnchor, Float>() }

    // 底部信息带点进来时定位到对应区块：等两帧让目标分区完成测量再滚动。
    LaunchedEffect(section, scrollTo) {
        if (scrollTo == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val target = anchors[scrollTo] ?: return@LaunchedEffect
        scrollState.animateScrollTo(target.toInt().coerceAtLeast(0))
    }

    GeoDrawer(
        title = "来源与参数 · ${source.sourceName}",
        sectionId = "VISION / CONTROL",
        onClose = onDismiss,
        width = Geo.ParamDrawer,
        scrollState = scrollState,
        footer = {
            if (section == ParamSection.DETECTION) {
                GeoKey(onClick = onDismiss, label = "取消")
            } else {
                GeoKey(onClick = onDismiss, label = "关闭")
            }
        }
    ) {
        GeoShifter(
            options = ParamSection.entries.map { it.name to it.title },
            value = section.name,
            onSelect = { name -> ParamSection.entries.firstOrNull { it.name == name }?.let { section = it } }
        )
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Geo.GroupGap)
        ) {
            when (section) {
                ParamSection.SOURCE -> SourceSection(
                    source = source,
                    nowWallMs = nowWallMs,
                    nowElapsedMs = nowElapsedMs,
                    anchors = anchors,
                    onRename = onRename,
                    onSetLens = onSetLens,
                    onDelete = onDelete,
                    onMessage = onMessage
                )

                ParamSection.DETECTION -> DetectionSection(
                    source = source,
                    initial = source.params,
                    running = source.state.isRunning,
                    models = models,
                    downloads = downloads,
                    anchors = anchors,
                    onDownloadModel = onDownloadModel,
                    onSave = onSave,
                    onMessage = onMessage
                )
            }
        }
    }
}

// ── 来源页 ────────────────────────────────────────────────────────────

@Composable
private fun SourceSection(
    source: SourceView,
    nowWallMs: Long,
    nowElapsedMs: Long,
    anchors: MutableMap<ParamAnchor, Float>,
    onRename: (String) -> String?,
    onSetLens: (Int) -> String?,
    onDelete: () -> Unit,
    onMessage: (String?) -> Unit
) {
    var name by remember(source.sourceId, source.sourceName) { mutableStateOf(source.sourceName) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = source.state.isRunning

    GeoSectionLabel("01", "运行状态", if (running) "运行中" else "已停止")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoLamp(
            source.state.error ?: source.state.statusText,
            when {
                source.state.error != null -> GeoLampTone.Error
                running -> GeoLampTone.Success
                source.state.isReady -> GeoLampTone.Neutral
                else -> GeoLampTone.Warning
            }
        )
        Spacer(Modifier.weight(1f))
        Text(
            if (source.mode == SourceCaptureMode.LocalCamera) "本机相机" else "远程镜头",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
    // 两列信息网格：避免每行只放一条键值造成大片留白。
    val rows = listOf(
        "来源身份" to source.sourceId,
        "推理后端" to source.state.backend.ifEmpty { "未报告" },
        "实际帧率" to "%.1f FPS".format(source.state.actualFps),
        "单帧耗时" to "${source.state.inferenceMs} ms",
        "最后画面" to formatElapsedMoment(source.state.lastProgressAtMs, nowWallMs, nowElapsedMs),
        "最后报警" to formatWallMoment(source.state.lastAlertAtMs),
        "成功帧数" to "${source.state.processedFrames}",
        "失败帧数" to "${source.state.failedFrames}"
    )
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                pair.forEach { (label, value) -> GeoInfoCell(label, value, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        source.remotePublisherName?.let { GeoInfoRow("推流来源", it) }
    }

    GeoDivider()
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.NAME] = it.positionInParent().y }) {
        GeoSectionLabel("02", "来源名称", "${name.length}/64")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            GeoFlatTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "来源名称",
                modifier = Modifier.weight(1f)
            )
            GeoKey(
                onClick = {
                    val failure = onRename(name)
                    onMessage(failure ?: "来源名称已保存")
                },
                label = "保存",
                icon = LucideIcons.Check,
                enabled = name.isNotBlank() && name != source.sourceName
            )
            GeoKey(
                onClick = { name = source.sourceName },
                label = "还原",
                icon = LucideIcons.RotateCcw,
                enabled = name != source.sourceName
            )
        }
    }

    if (source.mode == SourceCaptureMode.LocalCamera) {
        GeoDivider()
        Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.LENS] = it.positionInParent().y }) {
            GeoSectionLabel("03", "镜头", if (running) "运行中不可切换" else "前 / 后置")
            GeoShifter(
                options = listOf("1" to "前置", "0" to "后置"),
                value = source.cameraLensFacing.toString(),
                onSelect = { selected -> onSetLens(selected.toInt())?.let(onMessage) },
                enabled = !running
            )
        }
    }

    GeoDivider()
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.MAINTENANCE] = it.positionInParent().y }) {
        GeoSectionLabel("04", "维护", "二次确认")
        GeoHint(
            if (running) "先停止来源，再删除。删除会同时移除本机参数与遮罩。" else "删除会同时移除本机参数与遮罩；远程绑定可由控制台恢复。",
            if (running) GeoLampTone.Warning else GeoLampTone.Neutral
        )
        Spacer(Modifier.height(Geo.Gap))
        GeoKey(
            onClick = { confirmDelete = true },
            label = "删除来源",
            icon = LucideIcons.Trash2,
            tone = GeoTone.Danger,
            enabled = !running
        )
    }

    if (confirmDelete) {
        GeoConfirm(
            title = "删除来源",
            text = "将删除「${source.sourceName}」及其本机参数与遮罩，远程绑定可由控制台恢复。",
            confirmLabel = "删除",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

/** 键值单元：标签在上、值在下，用于两列信息网格。 */
@Composable
private fun GeoInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── 检测参数页 ────────────────────────────────────────────────────────

@Composable
private fun DetectionSection(
    source: SourceView,
    initial: DetectionParams,
    running: Boolean,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    anchors: MutableMap<ParamAnchor, Float>,
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
    var showAllTargets by remember { mutableStateOf(false) }
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

    fun save() {
        if (locked) {
            error = "请先停止来源；参数是冷改动，停止后保存、下次启动生效"
            return
        }
        saving = true
        val failure = onSave(
            initial.copy(
                confidencePercent = confidence,
                targetFps = targetFps,
                cooldownSeconds = cooldown,
                modelKey = modelKey,
                watchedClasses = watched
            )
        )
        saving = false
        if (failure == null) onMessage("检测规则已保存，重新启动来源后生效") else error = failure
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoLamp(if (locked) "运行中 · 只读" else "已停止 · 可编辑", if (locked) GeoLampTone.Warning else GeoLampTone.Neutral)
        Spacer(Modifier.weight(1f))
        Text("草稿 / 保存后下次启动生效", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (locked) GeoHint("停止检测后，才能调整参数；运行中的来源不会被编辑操作隐式停止。", GeoLampTone.Warning)

    // 三个物理控件并排；窄屏自动纵向堆叠，避免把一屏拉长。
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.MACHINES] = it.positionInParent().y }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
                    MachineBlock("01", "采样频率", "${DetectionParams.MIN_TARGET_FPS}—${DetectionParams.MAX_TARGET_FPS} FPS", Modifier.weight(1f)) {
                        GeoWheel(
                            label = "采样频率",
                            value = targetFps,
                            min = DetectionParams.MIN_TARGET_FPS,
                            max = DetectionParams.MAX_TARGET_FPS,
                            enabled = !locked,
                            unit = "FPS",
                            height = 168.dp,
                            onChange = { targetFps = it; error = null }
                        )
                        fpsError?.let { FieldError(it) }
                    }
                    MachineBlock("02", "置信度", "${DetectionParams.MIN_CONFIDENCE_PERCENT}—${DetectionParams.MAX_CONFIDENCE_PERCENT}%", Modifier.weight(1.4f)) {
                        GeoConfidenceRail(
                            value = confidence,
                            enabled = !locked,
                            onChange = { confidence = it; error = null }
                        )
                        confidenceError?.let { FieldError(it) }
                    }
                    MachineBlock("03", "报警冷却", "${DetectionParams.MIN_COOLDOWN_SECONDS}—${DetectionParams.MAX_COOLDOWN_SECONDS} 秒", Modifier.weight(1.2f)) {
                        CooldownControl(
                            cooldown = cooldown,
                            enabled = !locked,
                            onChange = { cooldown = it; error = null }
                        )
                        cooldownError?.let { FieldError(it) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
                    MachineBlock("01", "采样频率", "1—5 FPS", Modifier.fillMaxWidth()) {
                        GeoWheel(
                            label = "采样频率",
                            value = targetFps,
                            min = DetectionParams.MIN_TARGET_FPS,
                            max = DetectionParams.MAX_TARGET_FPS,
                            enabled = !locked,
                            unit = "FPS",
                            height = 168.dp,
                            onChange = { targetFps = it; error = null }
                        )
                        fpsError?.let { FieldError(it) }
                    }
                    MachineBlock("02", "置信度", "10—95%", Modifier.fillMaxWidth()) {
                        GeoConfidenceRail(value = confidence, enabled = !locked, onChange = { confidence = it; error = null })
                        confidenceError?.let { FieldError(it) }
                    }
                    MachineBlock("03", "报警冷却", "1—300 秒", Modifier.fillMaxWidth()) {
                        CooldownControl(cooldown = cooldown, enabled = !locked, onChange = { cooldown = it; error = null })
                        cooldownError?.let { FieldError(it) }
                    }
                }
            }
        }
    }

    GeoDivider()
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.MODELS] = it.positionInParent().y }) {
        GeoSectionLabel("04", "模型插槽", "仅已下载可选")
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
                        .background(MaterialTheme.colorScheme.surface, RectangleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text("我已根据新模型重新核对检测目标", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (!modelDownloaded) FieldError("当前模型未下载")
    }

    GeoDivider()
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.TARGETS] = it.positionInParent().y }) {
        GeoSectionLabel("05", "检测目标", "至少一项")
        GeoFlatTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = "目标搜索 · 中文 / 英文",
            leading = LucideIcons.Search,
            enabled = !locked,
            trailing = {
                GeoIconKey(LucideIcons.X, "清空搜索", onClick = { search = "" }, enabled = search.isNotEmpty() && !locked)
            }
        )
        if (watched.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                watched.sorted().forEach { key ->
                    SelectedTargetChip(
                        label = CocoClassMap.displayName(key),
                        enabled = !locked,
                        onRemove = { watched = watched - key }
                    )
                }
            }
        }
        val query = search.trim()
        val candidates = if (query.isEmpty()) commonTargets else {
            CocoClassMap.EnglishNames.filter { name ->
                name.contains(query, ignoreCase = true) || CocoClassMap.displayName(name).contains(query, ignoreCase = true)
            }
        }
        when {
            candidates.isEmpty() -> Text(
                "没有匹配的目标。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            showAllTargets && query.isEmpty() -> TargetPool(
                keys = CocoClassMap.EnglishNames,
                watched = watched,
                enabled = !locked,
                onToggle = { key ->
                    watched = if (watched.contains(key)) watched - key else watched + key
                    error = null
                }
            )
            else -> TargetGrid(
                keys = candidates,
                watched = watched,
                enabled = !locked,
                onToggle = { key ->
                    watched = if (watched.contains(key)) watched - key else watched + key
                    error = null
                }
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (targetsValid && targetsWithinLimit) {
                    "已选 ${watched.size} 项 · ${targetsText.length} / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
                } else {
                    "至少选择一个目标，且不超过 ${DetectionParams.MAX_TARGET_COUNT} 项 / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
                },
                color = if (targetsValid && targetsWithinLimit) MaterialTheme.colorScheme.onSurfaceVariant else VisionGuardStatusColors.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            if (query.isEmpty()) {
                GeoKey(
                    onClick = { showAllTargets = !showAllTargets },
                    label = if (showAllTargets) "收起" else "全部 ${CocoClassMap.EnglishNames.size} 类",
                    icon = if (showAllTargets) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                    enabled = !locked
                )
            }
        }
    }

    error?.let { FieldError(it) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap, Alignment.End)) {
        GeoKey(
            onClick = {
                confidence = initial.confidencePercent
                targetFps = initial.targetFps
                cooldown = initial.cooldownSeconds
                modelKey = initial.modelKey
                watched = initial.watchedClasses
                search = ""
                reviewed = true
                error = null
            },
            label = "还原当前值",
            icon = LucideIcons.RotateCcw,
            enabled = !saving
        )
        GeoKey(
            onClick = { save() },
            label = "保存参数",
            icon = LucideIcons.Check,
            tone = GeoTone.Primary,
            enabled = canSave,
            busy = saving
        )
    }
}

@Composable
private fun MachineBlock(index: String, title: String, trailing: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        GeoSectionLabel(index, title, trailing)
        content()
    }
}

@Composable
private fun CooldownControl(cooldown: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            for (step in listOf(100, 10, 1)) {
                GeoWheel(
                    label = "冷却${step}位",
                    value = cooldown,
                    min = DetectionParams.MIN_COOLDOWN_SECONDS,
                    max = DetectionParams.MAX_COOLDOWN_SECONDS,
                    enabled = enabled,
                    step = step,
                    format = { ((it / step) % 10).toString() },
                    size = 52.dp,
                    height = 128.dp,
                    numberSize = 30.sp,
                    compact = true,
                    onChange = onChange
                )
            }
            Text("秒", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            cooldownPresets.forEach { preset ->
                GeoKey(
                    onClick = { onChange(preset) },
                    label = "${preset}s",
                    tone = if (cooldown == preset) GeoTone.Primary else GeoTone.Secondary,
                    enabled = enabled,
                    minWidth = 64.dp
                )
            }
        }
        GeoCounterInput(
            label = "冷却时间",
            value = cooldown,
            min = DetectionParams.MIN_COOLDOWN_SECONDS,
            max = DetectionParams.MAX_COOLDOWN_SECONDS,
            unit = "秒",
            enabled = enabled,
            onChange = onChange
        )
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
        Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)) {
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

/** 目标排片：五列，格高 ≥62dp。 */
@Composable
private fun TargetGrid(keys: List<String>, watched: Set<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        keys.chunked(Geo.TARGET_COLUMNS).forEach { row ->
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

/** 全部 80 类：固定高度内独立滚动，不把抽屉撑长。 */
@Composable
private fun TargetPool(keys: List<String>, watched: Set<String>, enabled: Boolean, onToggle: (String) -> Unit) {
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .height(264.dp)
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape),
        verticalArrangement = Arrangement.spacedBy(Geo.Gap),
        contentPadding = PaddingValues(Geo.Gap)
    ) {
        items(keys.chunked(Geo.TARGET_COLUMNS)) { row ->
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
            .geoFocusable()
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

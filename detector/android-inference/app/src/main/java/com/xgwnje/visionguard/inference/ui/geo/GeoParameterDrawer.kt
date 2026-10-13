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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import com.xgwnje.visionguard.inference.model.ModelKeys
import com.xgwnje.visionguard.inference.runtime.ModelState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.formatElapsedMoment
import com.xgwnje.visionguard.inference.ui.formatWallMoment
import kotlinx.coroutines.launch

/** 来源抽屉的分区：来源检查、检测参数与遮罩合并到同一个抽屉，用拨档切换。 */
enum class EditorPage(val title: String) {
    SOURCE("来源"),
    DETECTION("检测参数"),
    MASK("遮罩")
}

/** 抽屉内可用底部信息带直接定位的锚点。 */
enum class ParamAnchor {
    NAME, LENS, MAINTENANCE, MACHINES, MODELS, TARGETS
}

private val cooldownPresets = listOf(5, 30, 60)
private val confidencePresets = listOf(25, 50, 75)
private val fpsPresets = listOf(1, 3, 5)
private val commonTargets = listOf("person", "bicycle", "car", "motorcycle", "bus", "truck", "cat", "dog", "backpack", "handbag")

/**
 * 来源抽屉：拨档切「来源」「检测参数」「遮罩」三页。
 *
 * 检测参数页按统一节奏排布：三个机器控件逐段等高（主控件 / 操作行 / 快捷行），
 * 下面是模型插槽与检测目标两列。参数与遮罩各有一份草稿，切页不会丢。
 */
@Composable
fun GeoSourceDrawer(
    source: SourceView?,
    nowWallMs: Long,
    nowElapsedMs: Long,
    initialPage: EditorPage,
    scrollTo: ParamAnchor?,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    /** 本机是否有可用的 GPU 加速器；没有时 GPU 档不可选。 */
    gpuAvailable: Boolean,
    /** 正在被运行中来源使用的档位键：这些档位不允许删除。 */
    usedModelKeys: Set<String>,
    /** 遮罩页的画面与运行态。 */
    maskFrame: ArgbImage?,
    maskRunning: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> String?,
    onSetLens: (Int) -> String?,
    onDelete: () -> Unit,
    /** 远程来源解除发布端绑定：成功返回 null，失败返回可读文案。 */
    onUnbind: suspend (String) -> String?,
    onDownloadModel: suspend (String) -> String?,
    onDeleteModel: suspend (String) -> String?,
    onSave: (DetectionParams) -> String?,
    onSaveMasks: (List<MaskRegion>) -> String?,
    /** 参数说明跳帮助页：参数把帮助条目标题交给主界面。 */
    onHelp: (String) -> Unit,
    onMessage: (String?) -> Unit
) {
    if (source == null) return
    val initial = source.params
    var page by remember { mutableStateOf(initialPage) }
    // 遮罩草稿与参数草稿并列，切页/切来源都会重建。
    val maskDraft = remember(source.sourceId, source.masks) { MaskDraft(source.masks) }
    val scrollState = rememberScrollState()
    // 普通 Map：布局回调写入不能进 State，否则会触发重组与布局互相触发的死循环。
    val anchors = remember { mutableMapOf<ParamAnchor, Float>() }
    val scope = rememberCoroutineScope()

    var confidence by remember(source.sourceId, initial) { mutableStateOf(initial.confidencePercent) }
    var targetFps by remember(source.sourceId, initial) { mutableStateOf(initial.targetFps) }
    var cooldown by remember(source.sourceId, initial) { mutableStateOf(initial.cooldownSeconds) }
    // 本机没有 GPU 加速器时 GPU 档不可选：草稿直接落到同尺寸的 CPU 档，并要求用户复核一次。
    val autoAdjustedFrom = initial.modelKey.takeIf { ModelKeys.supportsGpu(it) && !gpuAvailable }
    var modelKey by remember(source.sourceId, initial, gpuAvailable) {
        mutableStateOf(
            if (autoAdjustedFrom != null) ModelKeys.cpuTierOf(autoAdjustedFrom) ?: ModelKeys.defaultKey
            else initial.modelKey
        )
    }
    var watched by remember(source.sourceId, initial) { mutableStateOf(initial.watchedClasses) }
    var reviewed by remember(source.sourceId, initial, gpuAvailable) { mutableStateOf(autoAdjustedFrom == null) }
    var pendingDeleteModel by remember { mutableStateOf<String?>(null) }
    var search by remember(source.sourceId) { mutableStateOf("") }
    var showAllTargets by remember(source.sourceId) { mutableStateOf(false) }
    var error by remember(source.sourceId) { mutableStateOf<String?>(null) }
    var saving by remember(source.sourceId) { mutableStateOf(false) }

    val locked = source.state.isRunning
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

    fun resetDraft() {
        confidence = initial.confidencePercent
        targetFps = initial.targetFps
        cooldown = initial.cooldownSeconds
        modelKey = initial.modelKey
        watched = initial.watchedClasses
        search = ""
        showAllTargets = false
        reviewed = true
        error = null
    }

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

    // 底部状态带点进来时定位到对应区块：等两帧让目标分区完成测量再滚动。
    LaunchedEffect(page, scrollTo) {
        if (scrollTo == null) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        val target = anchors[scrollTo] ?: return@LaunchedEffect
        scrollState.animateScrollTo(target.toInt().coerceAtLeast(0))
    }

    GeoDrawer(
        title = "来源 · ${source.sourceName}",
        sectionId = "VISION / SOURCE",
        onClose = onDismiss,
        width = Geo.ParamDrawer,
        scrollState = scrollState,
        footer = {
            when (page) {
                EditorPage.SOURCE -> GeoKey(onClick = onDismiss, label = "关闭")

                EditorPage.DETECTION -> {
                    GeoKey(
                        onClick = { resetDraft() },
                        label = "还原",
                        icon = LucideIcons.RotateCcw,
                        enabled = !saving
                    )
                    GeoKey(onClick = onDismiss, label = "取消")
                    GeoKey(
                        onClick = { save() },
                        label = "保存参数",
                        icon = LucideIcons.Check,
                        tone = GeoTone.Primary,
                        enabled = canSave,
                        busy = saving
                    )
                }

                EditorPage.MASK -> MaskPageFooter(
                    draft = maskDraft,
                    running = maskRunning,
                    hasFrame = maskFrame != null,
                    onCancel = onDismiss,
                    onSave = onSaveMasks,
                    onMessage = onMessage
                )
            }
        }
    ) {
        CompositionLocalProvider(LocalGeoHelp provides onHelp) {
        GeoShifter(
            options = EditorPage.entries.map { it.name to it.title },
            value = page.name,
            onSelect = { name -> EditorPage.entries.firstOrNull { it.name == name }?.let { page = it } }
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Geo.BlockGap)) {
            when (page) {
                EditorPage.SOURCE -> SourceSection(
                    source = source,
                    nowWallMs = nowWallMs,
                    nowElapsedMs = nowElapsedMs,
                    anchors = anchors,
                    onRename = onRename,
                    onSetLens = onSetLens,
                    onDelete = onDelete,
                    onUnbind = onUnbind,
                    onMessage = onMessage
                )

                EditorPage.DETECTION -> DetectionSection(
                    locked = locked,
                    models = models,
                    downloads = downloads,
                    gpuAvailable = gpuAvailable,
                    usedModelKeys = usedModelKeys,
                    autoAdjustedFrom = autoAdjustedFrom,
                    anchors = anchors,
                    confidence = confidence,
                    onConfidence = { confidence = it; error = null },
                    confidenceError = confidenceError,
                    targetFps = targetFps,
                    onTargetFps = { targetFps = it; error = null },
                    fpsError = fpsError,
                    cooldown = cooldown,
                    onCooldown = { cooldown = it; error = null },
                    cooldownError = cooldownError,
                    modelKey = modelKey,
                    onSelectModel = { key -> modelKey = key; reviewed = key == initial.modelKey; error = null },
                    reviewed = reviewed,
                    onReview = { reviewed = true },
                    modelDownloaded = modelDownloaded,
                    watched = watched,
                    onToggleTarget = { key ->
                        watched = if (watched.contains(key)) watched - key else watched + key
                        error = null
                    },
                    targetsText = targetsText,
                    targetsValid = targetsValid,
                    targetsWithinLimit = targetsWithinLimit,
                    search = search,
                    onSearch = { search = it },
                    showAllTargets = showAllTargets,
                    onToggleAllTargets = { showAllTargets = !showAllTargets },
                    error = error,
                    onDownloadModel = { key -> scope.launch { onDownloadModel(key)?.let(onMessage) } },
                    onRequestDeleteModel = { key -> pendingDeleteModel = key }
                )

                EditorPage.MASK -> MaskPage(draft = maskDraft, frame = maskFrame, running = maskRunning)
            }
        }
        }
    }

    pendingDeleteModel?.let { key ->
        GeoConfirm(
            title = "删除模型",
            text = "删除 ${ModelKeys.displayName(key)} 的本机文件？下次使用需要重新下载；账号、配置与遮罩不受影响。",
            confirmLabel = "删除",
            onCancel = { pendingDeleteModel = null },
            onConfirm = {
                pendingDeleteModel = null
                scope.launch { onDeleteModel(key)?.let(onMessage) }
            }
        )
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
    /** 远程来源解除发布端绑定：成功返回 null，失败返回可读文案。 */
    onUnbind: suspend (String) -> String?,
    onMessage: (String?) -> Unit
) {
    var name by remember(source.sourceId, source.sourceName) { mutableStateOf(source.sourceName) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmUnbind by remember(source.sourceId) { mutableStateOf(false) }
    var unbinding by remember(source.sourceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val running = source.state.isRunning

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoSectionLabel("01", "运行状态", if (running) "运行中" else "已停止", Modifier.weight(1f))
        HelpTopicKey("运行与启停")
    }
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
    // 两列信息网格：同一行两格等高，避免一行一条键值造成大片留白。
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

    if (source.mode == SourceCaptureMode.RemoteStream) {
        GeoDivider()
        Column(Modifier.fillMaxWidth()) {
            GeoSectionLabel("03", "远程绑定", "发布端级")
            val publisherLabel = source.remotePublisherName?.takeIf { it.isNotBlank() } ?: "该发布端"
            // 老来源可能没记下发布端身份：没有 publisherDeviceId 就发不出解绑请求，只提示到控制台处理。
            if (source.remotePublisherDeviceId.isNullOrBlank()) {
                GeoHint("这张远程来源没有记录发布端身份，无法在本机解除绑定；请在控制台处理。", GeoLampTone.Warning)
            } else {
                GeoHint("绑定按发布端生效：解除后「$publisherLabel」推给本机的所有来源都会停止，来源会从列表消失；对方重新关联后会再次出现。")
                Spacer(Modifier.height(Geo.Gap))
                GeoKey(
                    onClick = { confirmUnbind = true },
                    label = "解除绑定",
                    icon = LucideIcons.CloudOff,
                    tone = GeoTone.Danger,
                    busy = unbinding
                )
            }
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
    if (confirmUnbind) {
        GeoConfirm(
            title = "解除绑定",
            text = "解除「${source.sourceName}」的推流绑定？该发布端的全部来源都会停止推给本机，来源随即从列表消失；对方重新关联后会再次出现。",
            confirmLabel = "解除",
            cancelEnabled = !unbinding,
            onCancel = { if (!unbinding) confirmUnbind = false },
            onConfirm = {
                confirmUnbind = false
                unbinding = true
                scope.launch {
                    val failure = onUnbind(source.remotePublisherDeviceId.orEmpty())
                    unbinding = false
                    failure?.let(onMessage)
                }
            }
        )
    }
}

/** 键值单元：标签在上、值在下，等高。 */
@Composable
private fun GeoInfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .height(Geo.InfoCell)
            .background(MaterialTheme.colorScheme.surface, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center
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
    locked: Boolean,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    gpuAvailable: Boolean,
    usedModelKeys: Set<String>,
    autoAdjustedFrom: String?,
    anchors: MutableMap<ParamAnchor, Float>,
    confidence: Int,
    onConfidence: (Int) -> Unit,
    confidenceError: String?,
    targetFps: Int,
    onTargetFps: (Int) -> Unit,
    fpsError: String?,
    cooldown: Int,
    onCooldown: (Int) -> Unit,
    cooldownError: String?,
    modelKey: String,
    onSelectModel: (String) -> Unit,
    reviewed: Boolean,
    onReview: () -> Unit,
    modelDownloaded: Boolean,
    watched: Set<String>,
    onToggleTarget: (String) -> Unit,
    targetsText: String,
    targetsValid: Boolean,
    targetsWithinLimit: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    showAllTargets: Boolean,
    onToggleAllTargets: () -> Unit,
    error: String?,
    onDownloadModel: (String) -> Unit,
    onRequestDeleteModel: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoLamp(if (locked) "运行中 · 只读" else "已停止 · 可编辑", if (locked) GeoLampTone.Warning else GeoLampTone.Neutral)
        Spacer(Modifier.weight(1f))
        Text("草稿 / 保存后下次启动生效", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (locked) {
        GeoHint("停止检测后，才能调整参数；参数是冷改动，保存后下次启动生效。", GeoLampTone.Warning)
    }
    if (error != null) {
        Text(error, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall)
    }

    // 三个机器控件：主控件区 / 操作行 / 快捷行 逐段等高，横向并排；窄屏纵向堆叠。
    Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.MACHINES] = it.positionInParent().y }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 660.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
                    MachineBlock(
                        index = "01",
                        title = "采样频率",
                        trailing = "${DetectionParams.MIN_TARGET_FPS}—${DetectionParams.MAX_TARGET_FPS} FPS",
                        error = fpsError,
                        modifier = Modifier.weight(1f)
                    ) {
                        WheelControl(value = targetFps, min = DetectionParams.MIN_TARGET_FPS, max = DetectionParams.MAX_TARGET_FPS, enabled = !locked, unit = "FPS", onChange = onTargetFps)
                        QuickKeys(options = fpsPresets.map { it to "$it" }, selected = targetFps, enabled = !locked) { onTargetFps(it) }
                    }
                    MachineBlock(
                        index = "02",
                        title = "置信度",
                        trailing = "${DetectionParams.MIN_CONFIDENCE_PERCENT}—${DetectionParams.MAX_CONFIDENCE_PERCENT}%",
                        error = confidenceError,
                        modifier = Modifier.weight(1.25f)
                    ) {
                        ConfidenceControl(value = confidence, enabled = !locked, onChange = onConfidence)
                        CounterAction(value = confidence, unit = "%", min = DetectionParams.MIN_CONFIDENCE_PERCENT, max = DetectionParams.MAX_CONFIDENCE_PERCENT, enabled = !locked, onChange = onConfidence)
                    }
                    MachineBlock(
                        index = "03",
                        title = "报警冷却",
                        trailing = "${DetectionParams.MIN_COOLDOWN_SECONDS}—${DetectionParams.MAX_COOLDOWN_SECONDS} 秒",
                        error = cooldownError,
                        modifier = Modifier.weight(1.25f)
                    ) {
                        DigitsControl(value = cooldown, enabled = !locked, onChange = onCooldown)
                        QuickKeys(options = cooldownPresets.map { it to "${it}s" }, selected = cooldown, enabled = !locked) { onCooldown(it) }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
                    MachineBlock("01", "采样频率", "${DetectionParams.MIN_TARGET_FPS}—${DetectionParams.MAX_TARGET_FPS} FPS", fpsError, Modifier.fillMaxWidth()) {
                        WheelControl(value = targetFps, min = DetectionParams.MIN_TARGET_FPS, max = DetectionParams.MAX_TARGET_FPS, enabled = !locked, unit = "FPS", onChange = onTargetFps)
                        QuickKeys(options = fpsPresets.map { it to "$it" }, selected = targetFps, enabled = !locked) { onTargetFps(it) }
                    }
                    MachineBlock("02", "置信度", "${DetectionParams.MIN_CONFIDENCE_PERCENT}—${DetectionParams.MAX_CONFIDENCE_PERCENT}%", confidenceError, Modifier.fillMaxWidth()) {
                        ConfidenceControl(value = confidence, enabled = !locked, onChange = onConfidence)
                        CounterAction(value = confidence, unit = "%", min = DetectionParams.MIN_CONFIDENCE_PERCENT, max = DetectionParams.MAX_CONFIDENCE_PERCENT, enabled = !locked, onChange = onConfidence)
                    }
                    MachineBlock("03", "报警冷却", "${DetectionParams.MIN_COOLDOWN_SECONDS}—${DetectionParams.MAX_COOLDOWN_SECONDS} 秒", cooldownError, Modifier.fillMaxWidth()) {
                        DigitsControl(value = cooldown, enabled = !locked, onChange = onCooldown)
                        QuickKeys(options = cooldownPresets.map { it to "${it}s" }, selected = cooldown, enabled = !locked) { onCooldown(it) }
                    }
                }
            }
        }
    }

    GeoDivider()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 660.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.GroupGap)) {
                Column(Modifier.weight(0.85f).onGloballyPositioned { anchors[ParamAnchor.MODELS] = it.positionInParent().y }) {
                    ModelSlotBlock(
                        models = models,
                        downloads = downloads,
                        gpuAvailable = gpuAvailable,
                        usedModelKeys = usedModelKeys,
                        selected = modelKey,
                        enabled = !locked,
                        autoAdjustedFrom = autoAdjustedFrom,
                        reviewed = reviewed,
                        modelDownloaded = modelDownloaded,
                        locked = locked,
                        onSelect = onSelectModel,
                        onDownload = onDownloadModel,
                        onDelete = onRequestDeleteModel,
                        onReview = onReview
                    )
                }
                Column(Modifier.weight(1.15f).onGloballyPositioned { anchors[ParamAnchor.TARGETS] = it.positionInParent().y }) {
                    TargetBlock(
                        watched = watched,
                        onToggleTarget = onToggleTarget,
                        targetsText = targetsText,
                        targetsValid = targetsValid,
                        targetsWithinLimit = targetsWithinLimit,
                        search = search,
                        onSearch = onSearch,
                        showAllTargets = showAllTargets,
                        onToggleAllTargets = onToggleAllTargets,
                        locked = locked
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.MODELS] = it.positionInParent().y }) {
                ModelSlotBlock(
                    models = models,
                    downloads = downloads,
                    gpuAvailable = gpuAvailable,
                    usedModelKeys = usedModelKeys,
                    selected = modelKey,
                    enabled = !locked,
                    autoAdjustedFrom = autoAdjustedFrom,
                    reviewed = reviewed,
                    modelDownloaded = modelDownloaded,
                    locked = locked,
                    onSelect = onSelectModel,
                    onDownload = onDownloadModel,
                    onDelete = onRequestDeleteModel,
                    onReview = onReview
                )
            }
            Spacer(Modifier.height(Geo.GroupGap))
            Column(Modifier.fillMaxWidth().onGloballyPositioned { anchors[ParamAnchor.TARGETS] = it.positionInParent().y }) {
                TargetBlock(
                    watched = watched,
                    onToggleTarget = onToggleTarget,
                    targetsText = targetsText,
                    targetsValid = targetsValid,
                    targetsWithinLimit = targetsWithinLimit,
                    search = search,
                    onSearch = onSearch,
                    showAllTargets = showAllTargets,
                    onToggleAllTargets = onToggleAllTargets,
                    locked = locked
                )
            }
        }
    }
}

/**
 * 模型插槽：下载、删除与选中都在这一处，参数页之外不再有第二份模型清单。
 *
 * 未下载的档位不可选中（先下载再选）；使用中的档位不给删除入口；设备没有 GPU 加速器时
 * GPU 档直接标成不可用，草稿已经自动落到同尺寸 CPU 档。
 */
@Composable
private fun ModelSlotBlock(
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    gpuAvailable: Boolean,
    usedModelKeys: Set<String>,
    selected: String,
    enabled: Boolean,
    autoAdjustedFrom: String?,
    reviewed: Boolean,
    modelDownloaded: Boolean,
    locked: Boolean,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReview: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoSectionLabel("04", "模型插槽", "下载 / 删除 / 选中", Modifier.weight(1f))
        HelpTopicKey("模型档位与下载")
    }
    ModelCartridges(
        models = models,
        downloads = downloads,
        gpuAvailable = gpuAvailable,
        usedModelKeys = usedModelKeys,
        selected = selected,
        enabled = enabled,
        onSelect = onSelect,
        onDownload = onDownload,
        onDelete = onDelete
    )
    autoAdjustedFrom?.let {
        Spacer(Modifier.height(Geo.Gap))
        Text(
            "本机不支持 GPU，已把 ${ModelKeys.displayName(it)} 换成同尺寸的 CPU 档。",
            color = VisionGuardStatusColors.warning,
            style = MaterialTheme.typography.bodySmall
        )
    }
    ModelFootnotes(reviewed = reviewed, modelDownloaded = modelDownloaded, locked = locked, onReview = onReview)
}

/** 机器控件区块：标题 + 主控件区 + 操作行 + 快捷行，逐段等高。 */
@Composable
private fun MachineBlock(
    index: String,
    title: String,
    trailing: String,
    error: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GeoSectionLabel(index, title, trailing, Modifier.weight(1f))
            HelpTopicKey("检测参数")
        }
        content()
        if (error != null) {
            Text(error, color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/** 采样滚筒：主控件区固定高度，上下键贴在右侧。 */
@Composable
private fun WheelControl(value: Int, min: Int, max: Int, enabled: Boolean, unit: String, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Geo.BlockControl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GeoWheel(
            label = "采样频率",
            value = value,
            min = min,
            max = max,
            enabled = enabled,
            unit = unit,
            height = Geo.BlockControl,
            numberSize = 34.sp,
            onChange = onChange
        )
    }
}

/** 置信度：主控件区里放数值、滑轨与刻度。 */
@Composable
private fun ConfidenceControl(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().height(Geo.BlockControl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$value",
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
        Spacer(Modifier.height(Geo.Gap))
        GeoConfidenceRail(value = value, enabled = enabled, onChange = onChange)
        Row(Modifier.fillMaxWidth()) {
            Text("${DetectionParams.MIN_CONFIDENCE_PERCENT}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text("${DetectionParams.MAX_CONFIDENCE_PERCENT}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 冷却：三位滚筒在主控件区居中。 */
@Composable
private fun DigitsControl(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Geo.BlockControl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (step in listOf(100, 10, 1)) {
            GeoWheel(
                label = "冷却${step}位",
                value = value,
                min = DetectionParams.MIN_COOLDOWN_SECONDS,
                max = DetectionParams.MAX_COOLDOWN_SECONDS,
                enabled = enabled,
                step = step,
                format = { ((it / step) % 10).toString() },
                size = 54.dp,
                height = Geo.BlockControl,
                numberSize = 32.sp,
                compact = true,
                onChange = onChange
            )
            Spacer(Modifier.width(Geo.Gap))
        }
    }
}

/** 操作行：加减计数，与读数行同高。 */
@Composable
private fun CounterAction(value: Int, unit: String, min: Int, max: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().height(Geo.BlockAction)) {
        GeoCounterInput(
            label = unit,
            value = value,
            min = min,
            max = max,
            unit = unit,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            onChange = onChange
        )
    }
}

/** 快捷行：三个等宽预设，与相邻控件同高。 */
@Composable
private fun QuickKeys(options: List<Pair<Int, String>>, selected: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(Geo.BlockQuick), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        options.forEach { (value, label) ->
            GeoKey(
                onClick = { onPick(value) },
                label = label,
                tone = if (value == selected) GeoTone.Primary else GeoTone.Secondary,
                enabled = enabled,
                minHeight = Geo.BlockQuick,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 插片上的短名：`M·320 GPU`。 */
private fun shortModelName(key: String): String =
    ModelKeys.displayName(key).removePrefix("YOLO26").replace(" · ", "·")

/** 模型插片：两列、等高；下载、删除与选中都在插片上。 */
@Composable
private fun ModelCartridges(
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    gpuAvailable: Boolean,
    usedModelKeys: Set<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        models.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                row.forEach { model ->
                    val progress = downloads[model.key]
                    // GPU 档在本机没有加速器时不给选，也不给下载（下了也跑不了）。
                    val gpuBlocked = ModelKeys.supportsGpu(model.key) && !gpuAvailable
                    val inUse = model.key in usedModelKeys
                    val name = shortModelName(model.key)
                    GeoSlotCard(
                        title = name,
                        subtitle = when {
                            gpuBlocked -> "本机不支持 GPU"
                            progress != null && progress.total > 0 -> "下载 ${progress.percent}%"
                            progress != null -> "下载中…"
                            model.downloaded -> "已下载 · ${model.target.label}" + if (inUse) " · 使用中" else ""
                            else -> "未下载"
                        },
                        selected = model.key == selected,
                        enabled = enabled && model.downloaded && !gpuBlocked,
                        onClick = { onSelect(model.key) },
                        modifier = Modifier.weight(1f).height(Geo.ModelTile),
                        trailingIcon = when {
                            gpuBlocked || progress != null -> null
                            !model.downloaded -> LucideIcons.Download
                            inUse -> null
                            else -> LucideIcons.Trash2
                        },
                        trailingDescription = if (model.downloaded) "删除 $name" else "下载 $name",
                        onTrailingClick = when {
                            gpuBlocked || progress != null -> null
                            !model.downloaded -> ({ onDownload(model.key) })
                            inUse -> null
                            else -> ({ onDelete(model.key) })
                        }
                    )
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ModelFootnotes(reviewed: Boolean, modelDownloaded: Boolean, locked: Boolean, onReview: () -> Unit) {
    if (!reviewed) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Geo.Touch)
                .clickable(enabled = !locked) { onReview() },
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
    if (!modelDownloaded) {
        Text("当前模型未下载", color = VisionGuardStatusColors.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** 检测目标：搜索、已选、常用排片与全部展开。 */
@Composable
private fun TargetBlock(
    watched: Set<String>,
    onToggleTarget: (String) -> Unit,
    targetsText: String,
    targetsValid: Boolean,
    targetsWithinLimit: Boolean,
    search: String,
    onSearch: (String) -> Unit,
    showAllTargets: Boolean,
    onToggleAllTargets: () -> Unit,
    locked: Boolean
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GeoSectionLabel("05", "检测目标", "至少一项", Modifier.weight(1f))
        HelpTopicKey("检测目标")
    }
    GeoFlatTextField(
        value = search,
        onValueChange = onSearch,
        placeholder = "目标搜索 · 中文 / 英文",
        leading = LucideIcons.Search,
        enabled = !locked,
        trailing = {
            GeoIconKey(LucideIcons.X, "清空搜索", onClick = { onSearch("") }, enabled = search.isNotEmpty() && !locked)
        }
    )
    if (watched.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            watched.sorted().forEach { key ->
                SelectedTargetChip(
                    label = CocoClassMap.displayName(key),
                    enabled = !locked,
                    onRemove = { onToggleTarget(key) }
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
            onToggle = onToggleTarget
        )
        else -> TargetGrid(keys = candidates, watched = watched, enabled = !locked, onToggle = onToggleTarget)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (targetsValid && targetsWithinLimit) {
                "已选 ${watched.size} 项 · ${targetsText.length} / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
            } else {
                "至少一项，且不超过 ${DetectionParams.MAX_TARGET_COUNT} 项 / ${DetectionParams.MAX_TARGETS_TEXT_LENGTH} UTF-16"
            },
            color = if (targetsValid && targetsWithinLimit) MaterialTheme.colorScheme.onSurfaceVariant else VisionGuardStatusColors.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        if (query.isEmpty()) {
            GeoKey(
                onClick = onToggleAllTargets,
                label = if (showAllTargets) "收起" else "全部 ${CocoClassMap.EnglishNames.size} 类",
                icon = if (showAllTargets) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                enabled = !locked,
                minHeight = Geo.BlockQuick
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
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onRemove)
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

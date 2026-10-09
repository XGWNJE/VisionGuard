package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.AccountHeader
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AppearancePreference
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceView

private enum class MainPane(val title: String) {
    PREVIEW("预览"),
    SOURCES("来源"),
    INSPECTION("检查")
}

private enum class Overlay { NONE, SETTINGS, HELP }

/**
 * 主界面：主预览 / 来源列表 / 来源检查三个分区。
 *
 * 平板（可用宽度 ≥ 840dp 且字号 ≤ 1.3）预览、来源与检查并排；手机上分区切换保证三个分区都能到达。
 */
@Composable
fun MainScreen(
    runtime: InferenceRuntime,
    session: AccountSession,
    appearance: AppearancePreference,
    onToggleSource: suspend (String) -> String?
) {
    val state by runtime.state.collectAsState()
    val control by runtime.controlState.collectAsState()
    val media by runtime.mediaState.collectAsState()

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var pane by remember { mutableStateOf(MainPane.PREVIEW) }
    var parameterEditorFor by remember { mutableStateOf<String?>(null) }
    var maskEditorFor by remember { mutableStateOf<String?>(null) }

    val nowWallMs = rememberSecondTick()
    val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
    val onMessage: (String?) -> Unit = { runtime.setMessage(it) }

    when (overlay) {
        Overlay.SETTINGS -> {
            GlobalSettingsOverlay(
                session = session,
                state = state,
                control = control,
                media = media,
                appearance = appearance,
                runtime = runtime,
                onBack = { overlay = Overlay.NONE },
                onMessage = onMessage
            )
            return
        }

        Overlay.HELP -> {
            HelpPage(onBack = { overlay = Overlay.NONE })
            return
        }

        Overlay.NONE -> Unit
    }

    val selected = state.sources.firstOrNull { it.sourceId == state.selectedSourceId }
    val maxSources = control.maxSources

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AccountHeader(
                store = runtime.account,
                session = session,
                actions = {
                    IconButton(onClick = { overlay = Overlay.HELP }, modifier = Modifier.size(48.dp)) {
                        Icon(LucideIcons.CircleHelp, "帮助", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { overlay = Overlay.SETTINGS }, modifier = Modifier.size(48.dp)) {
                        Icon(LucideIcons.SlidersHorizontal, "全局设置", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                compact = true,
                beforeLogout = { runtime.stopAll() }
            )

            state.message?.let { message ->
                MessageBar(message, onDismiss = runtime::clearMessage)
            }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val wide = (maxWidth >= 840.dp || landscape && maxWidth >= 600.dp) && LocalDensity.current.fontScale <= 1.3f
                if (wide) {
                    TabletPanes(
                        state = state,
                        selected = selected,
                        maxSources = maxSources,
                        nowWallMs = nowWallMs,
                        nowElapsedMs = nowElapsedMs,
                        pane = pane,
                        onPane = { pane = it },
                        runtime = runtime,
                        onMessage = onMessage,
                        onEditParams = { parameterEditorFor = it },
                        onEditMasks = { maskEditorFor = it },
                        onToggleSource = onToggleSource
                    )
                } else {
                    PhonePanes(
                        state = state,
                        selected = selected,
                        maxSources = maxSources,
                        nowWallMs = nowWallMs,
                        nowElapsedMs = nowElapsedMs,
                        pane = pane,
                        onPane = { pane = it },
                        runtime = runtime,
                        onMessage = onMessage,
                        onEditParams = { parameterEditorFor = it },
                        onEditMasks = { maskEditorFor = it },
                        onToggleSource = onToggleSource
                    )
                }
            }
        }
    }

    val editingParams = state.sources.firstOrNull { it.sourceId == parameterEditorFor }
    if (editingParams != null) {
        ParameterEditorDialog(
            sourceName = editingParams.sourceName,
            initial = editingParams.params,
            running = editingParams.state.isRunning,
            models = state.models,
            downloads = state.downloads,
            onDismiss = { parameterEditorFor = null },
            onDownloadModel = { runtime.downloadModel(it) },
            onSave = { params ->
                val failure = runtime.updateParams(editingParams.sourceId, params)
                if (failure == null) parameterEditorFor = null
                failure
            },
            onMessage = onMessage
        )
    }

    val editingMasks = state.sources.firstOrNull { it.sourceId == maskEditorFor }
    if (editingMasks != null) {
        MaskEditorDialog(
            sourceName = editingMasks.sourceName,
            frame = editingMasks.state.preview,
            initial = editingMasks.masks,
            running = editingMasks.state.isRunning,
            onDismiss = { maskEditorFor = null },
            onSave = { masks ->
                val failure = runtime.updateMasks(editingMasks.sourceId, masks)
                if (failure == null) maskEditorFor = null
                failure
            },
            onMessage = onMessage
        )
    }
}

@Composable
private fun TabletPanes(
    state: RuntimeUiState,
    selected: SourceView?,
    maxSources: Int,
    nowWallMs: Long,
    nowElapsedMs: Long,
    pane: MainPane,
    onPane: (MainPane) -> Unit,
    runtime: InferenceRuntime,
    onMessage: (String?) -> Unit,
    onEditParams: (String) -> Unit,
    onEditMasks: (String) -> Unit,
    onToggleSource: suspend (String) -> String?
) {
    Column(Modifier.fillMaxSize().padding(UiTokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
        PaneTabs(pane, onPane)
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
            Column(Modifier.weight(1.4f).fillMaxSize()) {
                MainPreviewCard(state, selected, nowElapsedMs)
            }
            Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
                Box(Modifier.weight(1f)) {
                    SourceListPane(
                        sources = state.sources,
                        selectedSourceId = state.selectedSourceId,
                        maxSources = maxSources,
                        nowElapsedMs = nowElapsedMs,
                        onSelect = runtime::selectSource,
                        onAddLocalCamera = runtime::addLocalCameraSource,
                        onAddRemotePlaceholder = runtime::addRemotePlaceholderSource,
                        onMessage = onMessage
                    )
                }
                Box(Modifier.weight(1.6f)) {
                    SourceInspectionPane(
                        source = selected,
                        nowWallMs = nowWallMs,
                        nowElapsedMs = nowElapsedMs,
                        onRename = { runtime.renameSource(selected?.sourceId.orEmpty(), it) },
                        onToggle = { onToggleSource(selected?.sourceId.orEmpty()) },
                        onSetLens = { runtime.setCameraLensFacing(selected?.sourceId.orEmpty(), it) },
                        onDelete = { runtime.removeSource(selected?.sourceId.orEmpty()) },
                        onMessage = onMessage,
                        onEditParams = { selected?.let { source -> onEditParams(source.sourceId) } },
                        onEditMasks = { selected?.let { source -> onEditMasks(source.sourceId) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhonePanes(
    state: RuntimeUiState,
    selected: SourceView?,
    maxSources: Int,
    nowWallMs: Long,
    nowElapsedMs: Long,
    pane: MainPane,
    onPane: (MainPane) -> Unit,
    runtime: InferenceRuntime,
    onMessage: (String?) -> Unit,
    onEditParams: (String) -> Unit,
    onEditMasks: (String) -> Unit,
    onToggleSource: suspend (String) -> String?
) {
    Column(Modifier.fillMaxSize().padding(UiTokens.ScreenPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)) {
        PaneTabs(pane, onPane)
        when (pane) {
            MainPane.PREVIEW -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MainPreviewCard(state, selected, nowElapsedMs)
            }

            MainPane.SOURCES -> SourceListPane(
                sources = state.sources,
                selectedSourceId = state.selectedSourceId,
                maxSources = maxSources,
                nowElapsedMs = nowElapsedMs,
                onSelect = runtime::selectSource,
                onAddLocalCamera = runtime::addLocalCameraSource,
                onAddRemotePlaceholder = runtime::addRemotePlaceholderSource,
                onMessage = onMessage
            )

            MainPane.INSPECTION -> SourceInspectionPane(
                source = selected,
                nowWallMs = nowWallMs,
                nowElapsedMs = nowElapsedMs,
                onRename = { runtime.renameSource(selected?.sourceId.orEmpty(), it) },
                onToggle = { onToggleSource(selected?.sourceId.orEmpty()) },
                onSetLens = { runtime.setCameraLensFacing(selected?.sourceId.orEmpty(), it) },
                onDelete = { runtime.removeSource(selected?.sourceId.orEmpty()) },
                onMessage = onMessage,
                onEditParams = { selected?.let { source -> onEditParams(source.sourceId) } },
                onEditMasks = { selected?.let { source -> onEditMasks(source.sourceId) } }
            )
        }
    }
}

@Composable
private fun PaneTabs(pane: MainPane, onPane: (MainPane) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
        MainPane.entries.forEach { item ->
            FilterChip(
                selected = pane == item,
                onClick = { onPane(item) },
                label = { Text(item.title, maxLines = 1) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            )
        }
    }
}

/** 主预览卡：最后一帧、检测框与中文标签，含明确的画面新鲜度与状态标识。 */
@Composable
private fun MainPreviewCard(
    state: RuntimeUiState,
    selected: SourceView?,
    nowElapsedMs: Long
) {
    val running = selected?.state?.isRunning == true
    val frame = selected?.state?.preview
    val image by rememberFrameBitmap(frame, maxWidth = 1600)
    val age = relativeAge(selected?.state?.lastProgressAtMs, nowElapsedMs)
    val staleSeconds = ((nowElapsedMs - (selected?.state?.lastProgressAtMs ?: nowElapsedMs)) / 1000)
    val stale = running && frame != null && staleSeconds >= 5

    SectionCard {
        SectionTitle(selected?.sourceName ?: "主预览")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
            StatusDot(running)
            Text(
                when {
                    selected == null -> "没有来源"
                    running && frame == null -> "运行中 · 等待首帧"
                    running -> "运行中 · 画面 $age"
                    frame != null -> "已停止 · 显示最后一帧（$age）"
                    else -> "已停止 · 暂无画面"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    stale -> VisionGuardStatusColors.warning
                    !running && frame != null -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            selected?.state?.preview?.let { current ->
                Text(
                    "${current.width}×${current.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (stale) {
            Text(
                "画面已 ${staleSeconds} 秒没有更新，显示的仍是最后一帧，不是实时视频。",
                style = MaterialTheme.typography.bodySmall,
                color = VisionGuardStatusColors.warning
            )
        }
        Surface(
            // 主预览放在可滚动列里，高度不受父约束，用固定比例盒而不是 weight（weight 在无界高度下会塌成 0）。
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            color = FrameBackdrop
        ) {
            if (image == null) {
                EmptyFramePlaceholder(Modifier.fillMaxSize())
            } else {
                FrameCanvas(
                    image = image,
                    detections = selected?.state?.detections.orEmpty(),
                    maskRegions = selected?.masks.orEmpty(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        val ruleText = selected?.let { source ->
            val model = com.xgwnje.visionguard.inference.model.ModelKeys.displayName(source.params.modelKey)
            val targets = source.params.watchedClasses.joinToString("、") {
                com.xgwnje.visionguard.inference.detect.CocoClassMap.displayName(it)
            }
            "$model · 目标 $targets · 置信度 ${source.params.confidencePercent}% · 采样 ${source.params.targetFps} FPS · 冷却 ${source.params.cooldownSeconds}s"
        }.orEmpty()
        if (ruleText.isNotEmpty()) {
            Text(ruleText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        selected?.state?.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = VisionGuardStatusColors.error)
        }
        if (state.sources.isEmpty()) {
            Text("还没有来源：到“来源”分区添加本机相机，或等待控制台绑定远程镜头。", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageBar(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = UiTokens.ScreenPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)
        ) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = VisionGuardControlColors.textButton()
            ) { Text("知道了") }
        }
    }
}

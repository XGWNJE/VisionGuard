package com.xgwnje.visionguard.inference.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AppearancePreference
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.MessageTone
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.geo.ActionDeck
import com.xgwnje.visionguard.inference.ui.geo.AddSourceDrawer
import com.xgwnje.visionguard.inference.ui.geo.EmptyWorkspace
import com.xgwnje.visionguard.inference.ui.geo.Geo
import com.xgwnje.visionguard.inference.ui.geo.GeoDivider
import com.xgwnje.visionguard.inference.ui.geo.GeoHint
import com.xgwnje.visionguard.inference.ui.geo.GeoLampTone
import com.xgwnje.visionguard.inference.ui.geo.GeoMessageRibbon
import com.xgwnje.visionguard.inference.ui.geo.GeoNodeHeader
import com.xgwnje.visionguard.inference.ui.geo.ParameterStrip
import com.xgwnje.visionguard.inference.ui.geo.PreviewComposition
import com.xgwnje.visionguard.inference.ui.geo.SourceCarriage
import com.xgwnje.visionguard.inference.ui.geo.SourceInspectDrawer
import kotlinx.coroutines.launch

private enum class Overlay { NONE, SETTINGS, HELP, INSPECT, ADD }

/**
 * 主界面：几何空间布局。
 *
 * 宽屏（≥840dp，或横屏短边 ≥600dp 且字号 ≤1.3）预览与来源滑架并排，操作台与信息带通栏；
 * 窄屏改为上下堆叠，全部内容仍可到达。依据见 `docs/73-几何空间界面规范.md`。
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
    var parameterEditorFor by remember { mutableStateOf<String?>(null) }
    var maskEditorFor by remember { mutableStateOf<String?>(null) }
    var shutterHidden by remember { mutableStateOf(false) }
    var busySourceId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val nowWallMs = rememberSecondTick()
    val nowElapsedMs = android.os.SystemClock.elapsedRealtime()
    val onMessage: (String?) -> Unit = { runtime.setMessage(it) }

    val selected = state.sources.firstOrNull { it.sourceId == state.selectedSourceId }
    val maxSources = control.maxSources
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val (connectionText, connectionTone) = when {
        control.authenticated -> "服务已连接" to GeoLampTone.Success
        control.connected -> control.statusText to GeoLampTone.Warning
        else -> "服务已断开" to GeoLampTone.Error
    }

    Box(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                GeoNodeHeader(
                    connectionText = connectionText,
                    connectionTone = connectionTone,
                    onConnection = { overlay = Overlay.SETTINGS },
                    onHelp = { overlay = Overlay.HELP },
                    onSettings = { overlay = Overlay.SETTINGS },
                    onAccount = { overlay = Overlay.SETTINGS }
                )
                GeoDivider()
    
                state.message?.let { message ->
                    GeoMessageRibbon(
                        text = message,
                        tone = when (state.messageTone) {
                            MessageTone.Error -> GeoLampTone.Error
                            MessageTone.Warning -> GeoLampTone.Warning
                            MessageTone.Success -> GeoLampTone.Success
                            MessageTone.Info -> GeoLampTone.Neutral
                        },
                        onDismiss = { runtime.clearMessage() }
                    )
                }
    
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = (maxWidth >= 840.dp || landscape && maxWidth >= 600.dp) &&
                        LocalDensity.current.fontScale <= 1.3f
                    if (state.sources.isEmpty()) {
                        EmptyWorkspace(onAdd = { overlay = Overlay.ADD })
                    } else {
                        Workspace(
                            state = state,
                            selected = selected,
                            wide = wide,
                            busy = busySourceId == selected?.sourceId,
                            shutterHidden = shutterHidden,
                            nowElapsedMs = nowElapsedMs,
                            maxSources = maxSources,
                            onSelect = runtime::selectSource,
                            onAdd = { overlay = Overlay.ADD },
                            onToggleShutter = { shutterHidden = !shutterHidden },
                            onInspect = { overlay = Overlay.INSPECT },
                            onToggle = {
                                val source = selected
                                if (source != null && busySourceId == null) {
                                    busySourceId = source.sourceId
                                    scope.launch {
                                        val failure = onToggleSource(source.sourceId)
                                        if (failure != null) runtime.setMessage(failure, MessageTone.Warning)
                                        busySourceId = null
                                    }
                                }
                            },
                            onParams = { parameterEditorFor = selected?.sourceId },
                            onMasks = { maskEditorFor = selected?.sourceId }
                        )
                    }
                }
            }
        }

        when (overlay) {
            Overlay.SETTINGS -> GlobalSettingsOverlay(
                session = session,
                state = state,
                control = control,
                media = media,
                appearance = appearance,
                runtime = runtime,
                onBack = { overlay = Overlay.NONE },
                onMessage = onMessage
            )
    
            Overlay.HELP -> HelpPage(onBack = { overlay = Overlay.NONE })
    
            Overlay.INSPECT -> SourceInspectDrawer(
                source = selected,
                nowWallMs = nowWallMs,
                nowElapsedMs = nowElapsedMs,
                onRename = { runtime.renameSource(selected?.sourceId.orEmpty(), it) },
                onSetLens = { runtime.setCameraLensFacing(selected?.sourceId.orEmpty(), it) },
                onDelete = {
                    scope.launch {
                        runtime.removeSource(selected?.sourceId.orEmpty())?.let(runtime::setMessage)
                        overlay = Overlay.NONE
                    }
                },
                onClose = { overlay = Overlay.NONE },
                onMessage = onMessage
            )
    
            Overlay.ADD -> AddSourceDrawer(
                sources = state.sources,
                maxSources = maxSources,
                localLimit = 1,
                onAddLocal = runtime::addLocalCameraSource,
                onAddRemote = runtime::addRemotePlaceholderSource,
                onClose = { overlay = Overlay.NONE },
                onMessage = onMessage
            )
    
            Overlay.NONE -> Unit
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
}

/** 工作区：预览、来源滑架、操作台与技术信息带。 */
@Composable
private fun Workspace(
    state: RuntimeUiState,
    selected: SourceView?,
    wide: Boolean,
    busy: Boolean,
    shutterHidden: Boolean,
    nowElapsedMs: Long,
    maxSources: Int,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onToggleShutter: () -> Unit,
    onInspect: () -> Unit,
    onToggle: () -> Unit,
    onParams: () -> Unit,
    onMasks: () -> Unit
) {
    val freshness = when {
        selected == null -> "没有来源"
        selected.state.isRunning && selected.state.preview == null -> "运行中 · 等待首帧"
        selected.state.isRunning -> "运行中 · 画面 ${relativeAge(selected.state.lastProgressAtMs, nowElapsedMs)}"
        selected.state.preview != null -> "已停止 · 最后一帧（${relativeAge(selected.state.lastProgressAtMs, nowElapsedMs)}）"
        else -> "已停止 · 暂无画面"
    }
    Column(
        Modifier.fillMaxSize().padding(Geo.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Geo.Gap)
    ) {
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                PreviewComposition(
                    source = selected,
                    freshness = freshness,
                    shutterHidden = shutterHidden,
                    onToggleShutter = onToggleShutter,
                    onInspect = onInspect,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                SourceCarriage(
                    sources = state.sources,
                    selectedSourceId = state.selectedSourceId,
                    maxSources = maxSources,
                    nowElapsedMs = nowElapsedMs,
                    onSelect = onSelect,
                    onAdd = onAdd,
                    modifier = Modifier.width(300.dp).fillMaxHeight()
                )
            }
        } else {
            PreviewComposition(
                source = selected,
                freshness = freshness,
                shutterHidden = shutterHidden,
                onToggleShutter = onToggleShutter,
                onInspect = onInspect,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            SourceCarriage(
                sources = state.sources,
                selectedSourceId = state.selectedSourceId,
                maxSources = maxSources,
                nowElapsedMs = nowElapsedMs,
                onSelect = onSelect,
                onAdd = onAdd,
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
        }
        ActionDeck(
            source = selected,
            busy = busy,
            wide = wide,
            onToggle = onToggle,
            onParams = onParams,
            onMasks = onMasks
        )
        ParameterStrip(source = selected, onParams = onParams)
        selected?.state?.error?.let { GeoHint(it, GeoLampTone.Error) }
        selected?.state?.performanceWarning?.let { GeoHint(it, GeoLampTone.Warning) }
        if (state.recoveryWarning != null) GeoHint(state.recoveryWarning, GeoLampTone.Warning)
    }
}

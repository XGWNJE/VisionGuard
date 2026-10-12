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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.xgwnje.visionguard.inference.ui.geo.Geo
import com.xgwnje.visionguard.inference.ui.geo.GeoDivider
import com.xgwnje.visionguard.inference.ui.geo.GeoHelpDrawer
import com.xgwnje.visionguard.inference.ui.geo.GeoHint
import com.xgwnje.visionguard.inference.ui.geo.GeoLampTone
import com.xgwnje.visionguard.inference.ui.geo.GeoMaskDrawer
import com.xgwnje.visionguard.inference.ui.geo.GeoMessageRibbon
import com.xgwnje.visionguard.inference.ui.geo.GeoParameterDrawer
import com.xgwnje.visionguard.inference.ui.geo.MockWorkspaceData
import com.xgwnje.visionguard.inference.ui.geo.ParamAnchor
import com.xgwnje.visionguard.inference.ui.geo.ParamSection
import com.xgwnje.visionguard.inference.ui.geo.UiPreviewFlags
import com.xgwnje.visionguard.inference.ui.geo.GeoSettingsDrawer
import com.xgwnje.visionguard.inference.ui.geo.ParameterStrip
import com.xgwnje.visionguard.inference.ui.geo.PreviewComposition
import com.xgwnje.visionguard.inference.ui.geo.SourceCarriage
import kotlinx.coroutines.launch

private enum class Overlay { NONE, SETTINGS, HELP, ADD }

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
    val realState by runtime.state.collectAsState()
    val mockData by UiPreviewFlags.mockData.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { UiPreviewFlags.attach(context) }
    // 预览构建可以选择用模拟数据渲染整屏，正式构建里 mockData 恒为 false。
    val control by runtime.controlState.collectAsState()
    val media by runtime.mediaState.collectAsState()

    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var parameterEditorFor by remember { mutableStateOf<String?>(null) }
    var maskEditorFor by remember { mutableStateOf<String?>(null) }
    var shutterHidden by remember { mutableStateOf(false) }
    var paramSection by remember { mutableStateOf(ParamSection.DETECTION) }
    var paramAnchor by remember { mutableStateOf<ParamAnchor?>(null) }
    var busySourceId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val nowWallMs = rememberSecondTick()
    val nowElapsedMs = android.os.SystemClock.elapsedRealtime()

    // 预览构建可以选择用模拟数据渲染整屏，正式构建里 mockData 恒为 false。
    val state = if (mockData) MockWorkspaceData.state(nowElapsedMs, nowWallMs) else realState
    // 预览构建可以选择用模拟数据渲染整屏，正式构建里 mockData 恒为 false。
    val onMessage: (String?) -> Unit = { runtime.setMessage(it) }

    // 每个入口记住自己的焦点位置：关闭抽屉后把焦点还回去（键盘与无障碍可达性）。
    val helpFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val accountFocus = remember { FocusRequester() }
    val paramsFocus = remember { FocusRequester() }
    val masksFocus = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    var callerFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val restoreFocus: () -> Unit = {
        callerFocus?.let { requester -> runCatching { requester.requestFocus() } }
        callerFocus = null
    }
    val closeOverlay: () -> Unit = {
        overlay = Overlay.NONE
        restoreFocus()
    }

    val selected = state.sources.firstOrNull { it.sourceId == state.selectedSourceId }
    val maxSources = control.maxSources
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val (connectionText, connectionTone) = when {
        control.authenticated -> "服务已连接" to GeoLampTone.Success
        control.connected -> control.statusText to GeoLampTone.Warning
        else -> "服务已断开" to GeoLampTone.Error
    }

    // 根层兜底：抽屉或确认框打开时按 Escape 一律关闭，不依赖焦点是否已经进抽屉。
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val modalOpen = overlay != Overlay.NONE || parameterEditorFor != null || maskEditorFor != null
                if (modalOpen && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    parameterEditorFor = null
                    maskEditorFor = null
                    closeOverlay()
                    true
                } else {
                    false
                }
            }
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
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
                    // 无来源时同样进主工作区：画面区显示占位，来源滑架只剩添加来源，不另设独立空来源界面。
                    Workspace(
                        state = state,
                        selected = selected,
                        wide = wide,
                        busy = selected != null && busySourceId == selected.sourceId,
                        shutterHidden = shutterHidden,
                        nowElapsedMs = nowElapsedMs,
                        maxSources = maxSources,
                        onSelect = runtime::selectSource,
                        onAdd = { callerFocus = addFocus; overlay = Overlay.ADD },
                        onToggleShutter = { shutterHidden = !shutterHidden },
                        onSourceInspect = { id ->
                            runtime.selectSource(id)
                            callerFocus = paramsFocus
                            paramSection = ParamSection.SOURCE
                            paramAnchor = null
                            parameterEditorFor = id
                        },
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
                        onParams = {
                            callerFocus = paramsFocus
                            paramSection = ParamSection.DETECTION
                            paramAnchor = ParamAnchor.MACHINES
                            parameterEditorFor = selected?.sourceId
                        },
                        onMasks = { callerFocus = masksFocus; maskEditorFor = selected?.sourceId },
                        connectionText = connectionText,
                        connectionTone = connectionTone,
                        onConnection = { callerFocus = settingsFocus; overlay = Overlay.SETTINGS },
                        onHelp = { callerFocus = helpFocus; overlay = Overlay.HELP },
                        onSettings = { callerFocus = settingsFocus; overlay = Overlay.SETTINGS },
                        onAccount = { callerFocus = accountFocus; overlay = Overlay.SETTINGS },
                        helpFocus = helpFocus,
                        settingsFocus = settingsFocus,
                        accountFocus = accountFocus,
                        paramsFocus = paramsFocus,
                        masksFocus = masksFocus,
                        addFocus = addFocus,
                        onParamAnchor = { anchor ->
                            callerFocus = paramsFocus
                            paramSection = ParamSection.DETECTION
                            paramAnchor = anchor
                            parameterEditorFor = state.selectedSourceId
                        }
                    )
                }
            }
        }

        when (overlay) {
            Overlay.SETTINGS -> GeoSettingsDrawer(
                session = session,
                state = state,
                control = control,
                media = media,
                appearance = appearance,
                runtime = runtime,
                onClose = { overlay = Overlay.NONE },
                onMessage = onMessage
            )
    
            Overlay.HELP -> GeoHelpDrawer(onClose = { overlay = Overlay.NONE })
    
            Overlay.ADD -> AddSourceDrawer(
                sources = state.sources,
                maxSources = maxSources,
                localLimit = 1,
                onAddLocal = runtime::addLocalCameraSource,
                onClose = closeOverlay,
                onMessage = onMessage
            )
    
            Overlay.NONE -> Unit
        }
    
        val editingParams = state.sources.firstOrNull { it.sourceId == parameterEditorFor }
        if (editingParams != null) {
            GeoParameterDrawer(
                source = editingParams,
                nowWallMs = nowWallMs,
                nowElapsedMs = nowElapsedMs,
                initialSection = paramSection,
                scrollTo = paramAnchor,
                models = state.models,
                downloads = state.downloads,
                gpuAvailable = state.gpuAvailable,
                usedModelKeys = state.sources.filter { it.state.isRunning }.map { it.params.modelKey }.toSet(),
                onDismiss = { parameterEditorFor = null; paramAnchor = null; restoreFocus() },
                onRename = { runtime.renameSource(editingParams.sourceId, it) },
                onSetLens = { runtime.setCameraLensFacing(editingParams.sourceId, it) },
                onDelete = {
                    scope.launch {
                        runtime.removeSource(editingParams.sourceId)?.let(runtime::setMessage)
                        parameterEditorFor = null
                    }
                },
                onDownloadModel = { runtime.downloadModel(it) },
                onDeleteModel = { runtime.deleteModel(it) },
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
            GeoMaskDrawer(
                sourceName = editingMasks.sourceName,
                frame = editingMasks.state.preview,
                initial = editingMasks.masks,
                running = editingMasks.state.isRunning,
                onDismiss = { maskEditorFor = null; restoreFocus() },
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
    onSourceInspect: (String) -> Unit,
    onParamAnchor: (ParamAnchor) -> Unit,
    onToggle: () -> Unit,
    onParams: () -> Unit,
    onMasks: () -> Unit,
    connectionText: String,
    connectionTone: GeoLampTone,
    onConnection: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    helpFocus: FocusRequester,
    settingsFocus: FocusRequester,
    accountFocus: FocusRequester,
    paramsFocus: FocusRequester,
    masksFocus: FocusRequester,
    addFocus: FocusRequester
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
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                SourceCarriage(
                    sources = state.sources,
                    selectedSourceId = state.selectedSourceId,
                    maxSources = maxSources,
                    nowElapsedMs = nowElapsedMs,
                    onSelect = onSelect,
                    onInspect = onSourceInspect,
                    onAdd = onAdd,
                    addFocus = addFocus,
                    modifier = Modifier.width(300.dp).fillMaxHeight()
                )
            }
        } else {
            PreviewComposition(
                source = selected,
                freshness = freshness,
                shutterHidden = shutterHidden,
                onToggleShutter = onToggleShutter,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            SourceCarriage(
                sources = state.sources,
                selectedSourceId = state.selectedSourceId,
                maxSources = maxSources,
                nowElapsedMs = nowElapsedMs,
                onSelect = onSelect,
                onInspect = onSourceInspect,
                onAdd = onAdd,
                addFocus = addFocus,
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
        }
        ActionDeck(
            source = selected,
            busy = busy,
            wide = wide,
            onToggle = onToggle,
            onParams = onParams,
            onMasks = onMasks,
            paramsFocus = paramsFocus,
            masksFocus = masksFocus
        )
        ParameterStrip(
            source = selected,
            connectionText = connectionText,
            connectionTone = connectionTone,
            onParams = onParamAnchor,
            onConnection = onConnection,
            onHelp = onHelp,
            onSettings = onSettings,
            onAccount = onAccount,
            helpFocus = helpFocus,
            settingsFocus = settingsFocus,
            accountFocus = accountFocus
        )
        selected?.state?.error?.let { GeoHint(it, GeoLampTone.Error) }
        selected?.state?.performanceWarning?.let { GeoHint(it, GeoLampTone.Warning) }
        if (state.recoveryWarning != null) GeoHint(state.recoveryWarning, GeoLampTone.Warning)
    }
}

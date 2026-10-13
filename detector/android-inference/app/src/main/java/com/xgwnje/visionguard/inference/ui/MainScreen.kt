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
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.geo.EditorPage
import com.xgwnje.visionguard.inference.ui.geo.Geo
import com.xgwnje.visionguard.inference.ui.geo.GeoDivider
import com.xgwnje.visionguard.inference.ui.geo.GeoHelpDrawer
import com.xgwnje.visionguard.inference.ui.geo.GeoLampTone
import com.xgwnje.visionguard.inference.ui.geo.GeoModal
import com.xgwnje.visionguard.inference.ui.geo.GeoNotice
import com.xgwnje.visionguard.inference.ui.geo.GeoSourceDrawer
import com.xgwnje.visionguard.inference.ui.geo.MockWorkspaceData
import com.xgwnje.visionguard.inference.ui.geo.ParamAnchor
import com.xgwnje.visionguard.inference.ui.geo.UiPreviewFlags
import com.xgwnje.visionguard.inference.ui.geo.GeoSettingsDrawer
import com.xgwnje.visionguard.inference.ui.geo.PreviewComposition
import com.xgwnje.visionguard.inference.ui.geo.SourceCarriage
import com.xgwnje.visionguard.inference.ui.geo.StatusBar
import kotlinx.coroutines.launch

private enum class Overlay { NONE, SETTINGS, HELP }

/**
 * 主界面：几何空间布局。
 *
 * 宽屏（≥840dp，或横屏短边 ≥600dp 且字号 ≤1.3）预览与来源滑架并排，操作台与信息带通栏；
 * 窄屏改为上下堆叠，全部内容仍可到达。视觉底线见 `docs/73-几何空间界面规范.md`。
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
    // 来源、检测参数与遮罩合并成一个抽屉：记住为哪个来源打开、停在哪一页。
    var editorFor by remember { mutableStateOf<String?>(null) }
    var editorPage by remember { mutableStateOf(EditorPage.SOURCE) }
    var shutterHidden by remember { mutableStateOf(false) }
    var paramAnchor by remember { mutableStateOf<ParamAnchor?>(null) }
    var busySourceId by remember { mutableStateOf<String?>(null) }
    var stoppingAll by remember { mutableStateOf(false) }
    // 参数旁的「?」跳帮助页时展开的条目标题。
    var helpTopic by remember { mutableStateOf<String?>(null) }
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
    // 本机相机默认就在来源列表里：进入界面时若还没有就登记一条，不需要手动添加。
    // 远程来源仍由控制台把相机推流节点绑定到本节点后下发，本机不自行创建槽位。
    LaunchedEffect(Unit) {
        if (!mockData && runtime.state.value.sources.none { it.mode == SourceCaptureMode.LocalCamera }) {
            runtime.addLocalCameraSource()?.let { failure -> runtime.setMessage(failure) }
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val (connectionText, connectionTone) = when {
        control.authenticated -> "服务已连接" to GeoLampTone.Success
        control.connected -> control.statusText to GeoLampTone.Warning
        else -> "服务已断开" to GeoLampTone.Error
    }

    // 所有提示收进一个悬浮区：运行时消息（可关）、当前来源的错误、性能与恢复警告（状态驱动）。
    val notices = buildList {
        state.message?.let {
            add(
                GeoNotice(
                    id = "message",
                    text = it,
                    tone = when (state.messageTone) {
                        MessageTone.Error -> GeoLampTone.Error
                        MessageTone.Warning -> GeoLampTone.Warning
                        MessageTone.Success -> GeoLampTone.Success
                        MessageTone.Info -> GeoLampTone.Neutral
                    },
                    dismissible = true
                )
            )
        }
        selected?.state?.error?.let { add(GeoNotice("source-error", it, GeoLampTone.Error)) }
        selected?.state?.performanceWarning?.let { add(GeoNotice("perf", it, GeoLampTone.Warning)) }
        state.recoveryWarning?.let { add(GeoNotice("recovery", it, GeoLampTone.Warning)) }
    }.distinctBy { it.text }

    // 根层兜底：抽屉或确认框打开时按 Escape 一律关闭，不依赖焦点是否已经进抽屉。
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                val modalOpen = overlay != Overlay.NONE || editorFor != null
                // 有确认框开着时只让确认框自己处理 Escape，避免一次按键把抽屉也关掉。
                if (modalOpen && GeoModal.confirmDepth == 0 && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    editorFor = null
                    closeOverlay()
                    true
                } else {
                    false
                }
            }
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = (maxWidth >= 840.dp || landscape && maxWidth >= 600.dp) &&
                        LocalDensity.current.fontScale <= 1.3f
                    // 无来源时同样进主工作区：画面区显示占位，来源滑架只剩添加来源，不另设独立空来源界面。
                    Workspace(
                        state = state,
                        selected = selected,
                        wide = wide,
                        busySourceId = busySourceId,
                        shutterHidden = shutterHidden,
                        nowElapsedMs = nowElapsedMs,
                        maxSources = maxSources,
                        stoppingAll = stoppingAll,
                        notices = notices,
                        onDismissNotice = { _ -> runtime.clearMessage() },
                        onSelect = runtime::selectSource,
                        onToggleShutter = { shutterHidden = !shutterHidden },
                        onSourceInspect = { id ->
                            runtime.selectSource(id)
                            editorPage = EditorPage.SOURCE
                            paramAnchor = null
                            editorFor = id
                        },
                        onToggleSource = { id ->
                            if (busySourceId == null) {
                                busySourceId = id
                                scope.launch {
                                    val failure = onToggleSource(id)
                                    if (failure != null) runtime.setMessage(failure, MessageTone.Warning)
                                    busySourceId = null
                                }
                            }
                        },
                        onStopAll = {
                            if (!stoppingAll) {
                                stoppingAll = true
                                scope.launch {
                                    runtime.stopAll()
                                    stoppingAll = false
                                }
                            }
                        },
                        connectionText = connectionText,
                        connectionTone = connectionTone,
                        onConnection = { callerFocus = settingsFocus; overlay = Overlay.SETTINGS },
                        onHelp = { callerFocus = helpFocus; helpTopic = null; overlay = Overlay.HELP },
                        onSettings = { callerFocus = settingsFocus; overlay = Overlay.SETTINGS },
                        onAccount = { callerFocus = accountFocus; overlay = Overlay.SETTINGS },
                        helpFocus = helpFocus,
                        settingsFocus = settingsFocus,
                        accountFocus = accountFocus,
                        onParamAnchor = { anchor ->
                            callerFocus = null
                            paramAnchor = anchor
                            editorPage = EditorPage.DETECTION
                            editorFor = state.selectedSourceId
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
                onClose = closeOverlay,
                onMessage = onMessage,
                onOpenParams = {
                    overlay = Overlay.NONE
                    callerFocus = null
                    paramAnchor = null
                    editorPage = EditorPage.DETECTION
                    editorFor = state.selectedSourceId
                }
            )
    
            Overlay.HELP -> GeoHelpDrawer(initialTopic = helpTopic, onClose = { helpTopic = null; closeOverlay() })
    
            Overlay.NONE -> Unit
        }
    
        // 正在编辑的来源被移除（远程解绑或本机删除）时收起抽屉，不留一个空壳在屏幕上。
        LaunchedEffect(state.sources.map { it.sourceId }, editorFor) {
            if (editorFor != null && state.sources.none { it.sourceId == editorFor }) {
                editorFor = null
                paramAnchor = null
            }
        }

        val editing = state.sources.firstOrNull { it.sourceId == editorFor }
        if (editing != null) {
            GeoSourceDrawer(
                source = editing,
                nowWallMs = nowWallMs,
                nowElapsedMs = nowElapsedMs,
                initialPage = editorPage,
                scrollTo = paramAnchor,
                models = state.models,
                downloads = state.downloads,
                gpuAvailable = state.gpuAvailable,
                usedModelKeys = state.sources.filter { it.state.isRunning }.map { it.params.modelKey }.toSet(),
                maskFrame = editing.state.preview,
                maskRunning = editing.state.isRunning,
                onDismiss = { editorFor = null; paramAnchor = null; restoreFocus() },
                onRename = { runtime.renameSource(editing.sourceId, it) },
                onSetLens = { runtime.setCameraLensFacing(editing.sourceId, it) },
                onDelete = {
                    scope.launch {
                        runtime.removeSource(editing.sourceId)?.let(runtime::setMessage)
                        editorFor = null
                    }
                },
                onUnbind = { publisherDeviceId ->
                    val failure = runtime.unbindRemotePublisher(publisherDeviceId)
                    if (failure == null) {
                        // 来源随服务端 stream-list 消失，本机不等推送就先收起抽屉，不留一个空壳。
                        editorFor = null
                        paramAnchor = null
                        runtime.setMessage("已解除绑定，该发布端的来源会停止推给本机")
                    }
                    failure
                },
                onDownloadModel = { runtime.downloadModel(it) },
                onDeleteModel = { runtime.deleteModel(it) },
                onSave = { params ->
                    val failure = runtime.updateParams(editing.sourceId, params)
                    if (failure == null) editorFor = null
                    failure
                },
                onSaveMasks = { masks ->
                    val failure = runtime.updateMasks(editing.sourceId, masks)
                    if (failure == null) editorFor = null
                    failure
                },
                onHelp = { topic ->
                    // 参数说明跳帮助页：先收起来源抽屉，再展开对应条目。
                    editorFor = null
                    paramAnchor = null
                    helpTopic = topic
                    callerFocus = helpFocus
                    overlay = Overlay.HELP
                },
                onMessage = onMessage
            )
        }
    }
}

/** 工作区：上两栏（预览 / 来源列表）+ 底部状态带；上下用同一组宽度，分界严格对齐。 */
@Composable
private fun Workspace(
    state: RuntimeUiState,
    selected: SourceView?,
    wide: Boolean,
    busySourceId: String?,
    shutterHidden: Boolean,
    nowElapsedMs: Long,
    maxSources: Int,
    stoppingAll: Boolean,
    notices: List<GeoNotice>,
    onDismissNotice: (String) -> Unit,
    onSelect: (String) -> Unit,
    onToggleShutter: () -> Unit,
    onSourceInspect: (String) -> Unit,
    onToggleSource: (String) -> Unit,
    onStopAll: () -> Unit,
    onParamAnchor: (ParamAnchor) -> Unit,
    connectionText: String,
    connectionTone: GeoLampTone,
    onConnection: () -> Unit,
    onHelp: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit,
    helpFocus: FocusRequester,
    settingsFocus: FocusRequester,
    accountFocus: FocusRequester
) {
    val freshness = when {        selected == null -> "没有来源"
        selected.state.isRunning && selected.state.preview == null -> "运行中 · 等待首帧"
        selected.state.isRunning -> "运行中 · 画面 ${relativeAge(selected.state.lastProgressAtMs, nowElapsedMs)}"
        selected.state.preview != null -> "已停止 · 最后一帧（${relativeAge(selected.state.lastProgressAtMs, nowElapsedMs)}）"
        else -> "已停止 · 暂无画面"
    }
    val anyRunning = state.sources.any { it.state.isRunning }
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
                    notices = notices,
                    onDismissNotice = onDismissNotice,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                SourceCarriage(
                    sources = state.sources,
                    selectedSourceId = state.selectedSourceId,
                    maxSources = maxSources,
                    busySourceId = busySourceId,
                    anyRunning = anyRunning,
                    stoppingAll = stoppingAll,
                    onSelect = onSelect,
                    onInspect = onSourceInspect,
                    onToggle = onToggleSource,
                    onStopAll = onStopAll,
                    modifier = Modifier.width(Geo.SourceRail).fillMaxHeight()
                )
            }
        } else {
            PreviewComposition(
                source = selected,
                freshness = freshness,
                shutterHidden = shutterHidden,
                onToggleShutter = onToggleShutter,
                notices = notices,
                onDismissNotice = onDismissNotice,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            SourceCarriage(
                sources = state.sources,
                selectedSourceId = state.selectedSourceId,
                maxSources = maxSources,
                busySourceId = busySourceId,
                anyRunning = anyRunning,
                stoppingAll = stoppingAll,
                onSelect = onSelect,
                onInspect = onSourceInspect,
                onToggle = onToggleSource,
                onStopAll = onStopAll,
                modifier = Modifier.fillMaxWidth().height(220.dp)
            )
        }
        StatusBar(
            source = selected,
            wide = wide,
            connectionText = connectionText,
            connectionTone = connectionTone,
            onParamAnchor = onParamAnchor,
            onConnection = onConnection,
            onHelp = onHelp,
            onSettings = onSettings,
            onAccount = onAccount,
            helpFocus = helpFocus,
            settingsFocus = settingsFocus,
            accountFocus = accountFocus
        )
    }
}

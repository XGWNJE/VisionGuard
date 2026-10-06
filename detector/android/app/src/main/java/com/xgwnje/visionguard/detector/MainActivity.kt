package com.xgwnje.visionguard.detector

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.xgwnje.visionguard.account.*
import com.xgwnje.visionguard.detector.stream.*
import com.xgwnje.visionguard.detector.ui.theme.VisionguardTheme
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val policy = ForegroundStreamPolicy()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (window.decorView.display?.displayId == displayId) updateCameraRotation()
        }
    }
    private var publisher: CameraPublisher? = null
    @Volatile private var cameraGeneration = 0
    private var streaming by mutableStateOf(false)
    private var preview by mutableStateOf<Bitmap?>(null)
    private var captureSize by mutableStateOf<Pair<Int, Int>?>(null)
    private var sentSize by mutableStateOf<Pair<Int, Int>?>(null)
    private var highResolution by mutableStateOf(false)
    private var dimScreen by mutableStateOf(false)
    private var hidePreview by mutableStateOf(false)
    private var priorBrightness = -1f
    private var analyzedFrames = 0L
    private var creditBlockedFrames = 0L
    private var diagnosticAt = 0L
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (policy.permissionStartPending && policy.foreground) startCamera()
        } else {
            policy.cancelPermissionRequest()
            Toast.makeText(this, "需要允许摄像头后才能推流", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        priorBrightness = window.attributes.screenBrightness
        val account = AccountStore.get(this)
        setContent {
            val session by account.session.collectAsState()
            val appearance = rememberAppearance()
            val darkTheme = appearance.dark()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            VisionguardTheme(darkTheme = darkTheme) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                if (session == null) ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-camera")
                Box(Modifier.weight(1f)) {
                if (session == null) AccountLogin(account, "相机推流节点", "android-camera")
                else key(session!!.scope) {
                    val prefs = remember { getSharedPreferences("camera-options-" + AccountStore.cacheKey(this@MainActivity), MODE_PRIVATE) }
                    fun saveCameraOption(key: String, raw: String): String {
                        com.xgwnje.visionguard.account.RemoteConfigPolicy.cameraValue(key, raw, streaming)
                        val preference = when (key) { "cameraResolution" -> "720p"; "cameraDimScreen" -> "dim"; else -> "hidePreview" }
                        val enabled = if (key == "cameraResolution") raw == "720p" else raw == "true"
                        check(prefs.edit().putBoolean(preference, enabled).commit()) { "保存相机配置失败" }
                        when (key) { "cameraResolution" -> highResolution = enabled; "cameraDimScreen" -> { dimScreen = enabled; applyScreen() }; else -> hidePreview = enabled }
                        return "配置已保存"
                    }
                    val connection = remember { CameraPublisher(account,
                        cacheMaintenance = { clean, active -> com.xgwnje.visionguard.account.TemporaryCache.maintain(this@MainActivity, clean, active) },
                        remoteSettings = { org.json.JSONObject().put("cameraResolution", if (highResolution) "720p" else "480p")
                            .put("cameraDimScreen", dimScreen).put("cameraHidePreview", hidePreview) },
                        onSetConfig = ::saveCameraOption,
                        controlState = { mapOf("cameraApp" to if (policy.foreground) "foreground" else "background",
                            "cameraPermission" to if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) "granted" else "required") },
                        onStreamCommand = { command, done ->
                            if (command == "stop-stream") { stopCamera("user"); done(true, "已停止推流") }
                            else startCamera(done)
                        }) }
                    DisposableEffect(connection) {
                        publisher = connection
                        highResolution = prefs.getBoolean("720p", false)
                        dimScreen = prefs.getBoolean("dim", false)
                        hidePreview = prefs.getBoolean("hidePreview", false)
                        onDispose { stopCamera("user"); connection.close(); publisher = null }
                    }
                    val state by connection.state.collectAsState()
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        AccountHeader(account, session!!, actions = { ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-camera", Modifier) }, beforeLogout = {
                            stopCamera("user"); connection.close(); prefs.edit().clear().commit()
                        })
                        CameraHome(state, preview, streaming, highResolution, dimScreen, hidePreview, captureSize, sentSize,
                            onStart = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                                else { policy.requestPermission(); permission.launch(Manifest.permission.CAMERA) }
                            }, onStop = { stopCamera("user") }, onBind = connection::bind, onRefresh = connection::refreshTargets,
                            onResolution = { saveCameraOption("cameraResolution", if (it) "720p" else "480p") },
                            onDim = { saveCameraOption("cameraDimScreen", it.toString()) },
                            onHidePreview = { saveCameraOption("cameraHidePreview", it.toString()) })
                    }
                }
                }
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        // Keep Compose's configuration current without disposing the camera or publisher.
        super.onConfigurationChanged(newConfig)
        updateCameraRotation()
    }
    private fun updateCameraRotation() {
        // Display changes also cover 180-degree turns that don't change portrait/landscape.
        window.decorView.display?.let { imageAnalysis?.targetRotation = it.rotation }
    }
    override fun onResume() {
        super.onResume(); policy.resumed()
        if (policy.permissionStartPending && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
    }
    override fun onPause() {
        policy.leftForeground()
        if (streaming) stopCamera(if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) "locked" else "background")
        super.onPause()
    }
    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        policy.cancelPermissionRequest(); super.onStop()
    }
    private fun startCamera(completed: ((Boolean, String) -> Unit)? = null) {
        val state = publisher?.state?.value
        if (!policy.foreground) { completed?.invoke(false, "请先在设备上打开相机应用并保持前台"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { completed?.invoke(false, "请先在设备上授予摄像头权限"); return }
        if (state?.connected != true || state.stream?.targetDeviceId == null) { policy.cancelPermissionRequest(); completed?.invoke(false, "请先关联视觉节点并连接服务"); return }
        if (streaming) { completed?.invoke(true, "已在推流"); return }
        if (!policy.start()) { completed?.invoke(false, "当前状态不允许启动推流"); return }
        streaming = true; captureSize = null; sentSize = null; applyScreen(); publisher?.start()
        val own = ++cameraGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (own != cameraGeneration || !streaming || !policy.foreground) { completed?.invoke(false, "启动已取消，或相机应用已离开前台"); return@addListener }
            runCatching {
                val provider = future.get(); cameraProvider = provider
                val target = if (highResolution) Size(1280, 720) else Size(640, 480)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetRotation(window.decorView.display?.rotation ?: Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(if (highResolution) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
                val framePacer = com.xgwnje.visionguard.detector.stream.FramePacer()
                analysis.setAnalyzer(executor) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        analyzedFrames++
                        if (BuildConfig.DEBUG && now - diagnosticAt >= 5000) {
                            MediaDiagnostics.log { "event=cameraSample callbacks=$analyzedFrames creditBlocked=$creditBlockedFrames windowMs=${if(diagnosticAt==0L) 0 else now-diagnosticAt} width=${image.width} height=${image.height}" }
                            analyzedFrames = 0; creditBlockedFrames = 0; diagnosticAt = now
                        }
                        val active = publisher
                        if (own != cameraGeneration || !streaming || active == null) return@setAnalyzer
                        if (!framePacer.due(now, active.framesPerSecond)) { active.sampledOut(); return@setAnalyzer }
                        if (!active.canPublish()) { creditBlockedFrames++; active.dropped(); return@setAnalyzer }
                        // Skip before conversion/compression; callback jitter must not lower the requested cadence.
                        framePacer.sampled(now)
                        val frame = CameraFrameCodec.encode(image, if (highResolution) 1280 else 640, if (highResolution) 720 else 480)
                        val captured = image.width to image.height
                        runOnUiThread { if (own == cameraGeneration && streaming) { captureSize = captured; sentSize = frame.width to frame.height } }
                        if (active.publish(frame) && !hidePreview) {
                            val previewStarted = SystemClock.elapsedRealtimeNanos()
                            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                            val oriented = if (frame.rotation == 0 || bitmap == null) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true)
                            runOnUiThread { if (own == cameraGeneration && streaming) preview = oriented }
                            MediaDiagnostics.log { "event=preview workUs=${(SystemClock.elapsedRealtimeNanos()-previewStarted)/1000}" }
                        }
                    } catch (_: Exception) {
                        if (own == cameraGeneration) publisher?.dropped()
                        runOnUiThread { if (own == cameraGeneration) Toast.makeText(this, "画面处理失败，已跳过这一帧", Toast.LENGTH_SHORT).show() }
                    } finally { image.close() }
                }
                provider.unbindAll(); provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                imageAnalysis = analysis
                updateCameraRotation()
                completed?.invoke(true, "摄像头已启动，等待画面通道；收帧状态请查看推流统计")
            }.onFailure { stopCamera("user"); completed?.invoke(false, "无法启动摄像头"); Toast.makeText(this, "无法启动摄像头", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun stopCamera(reason: String) {
        ++cameraGeneration; policy.stop(); streaming = false
        imageAnalysis = null
        cameraProvider?.unbindAll(); publisher?.stop(reason); preview = null; captureSize = null; sentSize = null; applyScreen()
    }
    private fun applyScreen() {
        if (streaming) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = if (streaming && dimScreen) 0.03f else priorBrightness }
    }
    override fun onDestroy() { stopCamera("background"); publisher?.close(); executor.shutdown(); super.onDestroy() }
}

@Composable
private fun CameraHome(state: PublisherState, preview: Bitmap?, streaming: Boolean, highResolution: Boolean,
    dim: Boolean, hidden: Boolean, captureSize: Pair<Int, Int>?, sentSize: Pair<Int, Int>?, onStart: () -> Unit, onStop: () -> Unit, onBind: (String) -> Unit,
    onRefresh: () -> Unit, onResolution: (Boolean) -> Unit, onDim: (Boolean) -> Unit, onHidePreview: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val statusColor = when {
        state.status.contains("失败") || state.status.contains("无法") || state.status.contains("超时") -> colors.error
        !state.connected || state.status.contains("等待") || state.status.contains("重连") -> VisionGuardStatusColors.warning
        streaming -> VisionGuardStatusColors.onSuccessContainer
        else -> colors.onSurface
    }
    var choosingTarget by remember { mutableStateOf(false) }
    var statisticsOpen by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Use the space remaining after account header and system insets; keep start/stop visible.
    val landscapePreviewHeight = (maxHeight - (180 * fontScale).dp).coerceAtLeast(96.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("相机推流节点", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Text(state.status, color = statusColor, style = MaterialTheme.typography.bodySmall)
        }
        VisionGuardColumns(primaryWeight = 1.5f, primary = {
            val previewModifier = if (landscape) Modifier.height(landscapePreviewHeight) else Modifier.aspectRatio(4f / 3f)
            if (!hidden) OutlinedCard(Modifier.fillMaxWidth().then(previewModifier), shape = MaterialTheme.shapes.medium, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant), border = BorderStroke(1.dp, colors.outlineVariant)) {
                if (preview != null) Image(preview.asImageBitmap(), "实时摄像头画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (streaming) "等待实时画面" else "开始后显示实时画面", Modifier.padding(16.dp), color = colors.onSurfaceVariant) }
            }
            if (streaming) Button(onStop, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                colors = VisionGuardControlColors.button(containerColor = colors.error, contentColor = colors.onError)) { Text("停止推流") }
            else {
                Button(onStart, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.connected && state.stream?.targetDeviceId != null, colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Text("开始推流") }
                if (!state.connected || state.stream?.targetDeviceId == null) Text(if (!state.connected) "连接统一服务后可以开始。" else "先选择目标视觉节点，再开始推流。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }, secondary = {
            OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outlineVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("目标视觉节点", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onRefresh, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("刷新") } }
                    when {
                        !state.connected -> Text("连接统一服务后获取目标视觉节点。", color = colors.onSurfaceVariant)
                        state.targetsLoading -> Text("正在加载视觉节点…", color = colors.onSurfaceVariant)
                        state.targetsLoadFailed -> Text("暂时无法加载视觉节点，请点击刷新重试。", color = colors.error)
                        state.targets.isEmpty() -> Text("当前账号下没有视觉节点，请先在视觉节点登录同一账号。", color = colors.onSurfaceVariant)
                    }
                    if (streaming) Text("停止推流后可更换目标节点。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    val selectedTarget = state.targets.find { it.deviceId == state.stream?.targetDeviceId }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(selectedTarget?.deviceName ?: "尚未选择", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ choosingTarget = true }, enabled = !streaming && state.connected && state.targets.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("选择") }
                    }
                    state.stream?.sourceName?.takeIf { it.isNotBlank() }?.let { Text("推理来源：$it") }
                }
            }
            Text("推流规格", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "640×480", true to "720P").forEach { (selectedResolution, title) ->
                    FilterChip(selected = highResolution == selectedResolution, onClick = { onResolution(selectedResolution) }, enabled = !streaming, label = { Text(title) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text(if (streaming) "最高 5 帧/秒 · 停止后可调整" else "最高 5 帧/秒", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (captureSize != null && sentSize != null) {
                Text("实际采集 ${captureSize.first}×${captureSize.second} · 发送 ${sentSize.first}×${sentSize.second}", style = MaterialTheme.typography.bodySmall)
                if (highResolution && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720))
                    Text("此摄像头已按可用规格回退。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CameraSetting("推流时降低亮度", "只调整当前窗口亮度，外观独立选择。", dim, true, onDim)
            CameraSetting("收起画面预览", "推流继续进行，隐藏本机预览。", hidden, true, onHidePreview)
            TextButton({ statisticsOpen = !statisticsOpen }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (statisticsOpen) "收起推流统计" else "推流统计") }
            if (statisticsOpen) OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, colors.outlineVariant), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本机入队 ${state.sentFrames}", style = MaterialTheme.typography.labelLarge)
                    Text("服务收帧 ${state.acknowledgedFrames}", style = MaterialTheme.typography.labelLarge)
                    Text("本机丢弃 ${state.droppedFrames} · 服务丢弃 ${state.relayDroppedFrames}", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (statisticsOpen) Text("主动未采样 ${state.sampledOutFrames} 帧；服务收帧不代表 Windows 已接收。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("离开应用或锁屏后停止推流。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        })
    }
    }
    if (choosingTarget) AlertDialog(
        onDismissRequest = { choosingTarget = false },
        shape = MaterialTheme.shapes.large,
        containerColor = colors.surface,
        title = { Text("选择目标视觉节点", style = MaterialTheme.typography.titleMedium) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.targets.forEach { target ->
                    val selected = state.stream?.targetDeviceId == target.deviceId
                    OutlinedButton({ onBind(target.deviceId); choosingTarget = false }, enabled = !streaming, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                        colors = VisionGuardControlColors.outlinedButton(containerColor = if (selected) colors.primaryContainer else colors.surface, contentColor = VisionGuardStatusColors.onSuccessContainer),
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)) {
                        Text((if (state.stream?.targetDeviceId == target.deviceId) "已关联 · " else "选择 · ") + target.deviceName)
                    }
                }

            if (state.targets.isEmpty()) Text("当前账号下没有视觉节点，请刷新重试。", color = colors.onSurfaceVariant)
        } },
        confirmButton = { TextButton({ choosingTarget = false }, Modifier.heightIn(min = 48.dp)) { Text("关闭") } }
    )

}

@Composable
private fun CameraSetting(label: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

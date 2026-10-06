package com.xgwnje.visionguard.detector

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
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
    private var nextFrameAt = 0L
    private var analyzedFrames = 0L
    private var creditBlockedFrames = 0L
    private var diagnosticAt = 0L
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && policy.foreground) startCamera()
        else Toast.makeText(this, "需要允许摄像头后才能推流", Toast.LENGTH_LONG).show()
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
                ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-camera")
                Box(Modifier.weight(1f)) {
                if (session == null) AccountLogin(account, "相机推流节点", "android-camera")
                else key(session!!.scope) {
                    val connection = remember { CameraPublisher(account) }
                    val prefs = remember { getSharedPreferences("camera-options-" + AccountStore.cacheKey(this@MainActivity), MODE_PRIVATE) }
                    DisposableEffect(connection) {
                        publisher = connection
                        highResolution = prefs.getBoolean("720p", false)
                        dimScreen = prefs.getBoolean("dim", false)
                        hidePreview = prefs.getBoolean("hidePreview", false)
                        onDispose { stopCamera("user"); connection.close(); publisher = null }
                    }
                    val state by connection.state.collectAsState()
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        AccountHeader(account, session!!, beforeLogout = {
                            stopCamera("user"); connection.close(); prefs.edit().clear().commit()
                        })
                        CameraHome(state, preview, streaming, highResolution, dimScreen, hidePreview, captureSize, sentSize,
                            onStart = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                                else permission.launch(Manifest.permission.CAMERA)
                            }, onStop = { stopCamera("user") }, onBind = connection::bind, onRefresh = connection::refreshTargets,
                            onResolution = { highResolution = it; prefs.edit().putBoolean("720p", it).apply() },
                            onDim = { dimScreen = it; prefs.edit().putBoolean("dim", it).apply(); applyScreen() },
                            onHidePreview = { hidePreview = it; prefs.edit().putBoolean("hidePreview", it).apply() })
                    }
                }
                }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); policy.resumed() }
    override fun onPause() {
        policy.leftForeground()
        stopCamera(if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) "locked" else "background")
        super.onPause()
    }
    private fun startCamera() {
        if (!policy.start() || publisher?.state?.value?.connected != true) return
        if (publisher?.state?.value?.stream?.targetDeviceId == null) { policy.stop(); return }
        streaming = true; nextFrameAt = 0; captureSize = null; sentSize = null; applyScreen(); publisher?.start()
        val own = ++cameraGeneration
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (own != cameraGeneration || !streaming || !policy.foreground) return@addListener
            runCatching {
                val provider = future.get(); cameraProvider = provider
                val target = if (highResolution) Size(1280, 720) else Size(640, 480)
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(if (highResolution) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()).build()
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
                        if (now < nextFrameAt) { active.sampledOut(); return@setAnalyzer }
                        if (!active.canPublish()) { creditBlockedFrames++; active.dropped(); return@setAnalyzer }
                        // Pace actual samples; waiting for credit must not consume the next sampling interval.
                        nextFrameAt = now + 200
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
            }.onFailure { stopCamera("user"); Toast.makeText(this, "无法启动摄像头", Toast.LENGTH_LONG).show() }
        }, ContextCompat.getMainExecutor(this))
    }
    private fun stopCamera(reason: String) {
        ++cameraGeneration; policy.stop(); streaming = false
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("相机推流节点", style = MaterialTheme.typography.titleLarge)
        Text(state.status, color = statusColor, style = MaterialTheme.typography.titleSmall)
        Text("保持应用在前台；离开应用或锁屏后停止推流。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, colors = CardDefaults.outlinedCardColors(containerColor = colors.surface), border = BorderStroke(1.dp, colors.outlineVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("目标视觉节点", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onRefresh, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("刷新") } }
                when {
                    !state.connected -> Text("连接统一服务后获取目标视觉节点。", color = colors.onSurfaceVariant)
                    state.targetsLoading -> Text("正在加载视觉节点…", color = colors.onSurfaceVariant)
                    state.targetsLoadFailed -> Text("暂时无法加载视觉节点，请点击刷新重试。", color = colors.error)
                    state.targets.isEmpty() -> Text("当前账号下没有视觉节点，请先在视觉节点登录同一账号。", color = colors.onSurfaceVariant)
                }
                if (streaming) Text("停止推流后可更换目标节点。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                state.targets.forEach { target ->
                    val selected = state.stream?.targetDeviceId == target.deviceId
                    OutlinedButton({ onBind(target.deviceId) }, enabled = !streaming, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
                        colors = VisionGuardControlColors.outlinedButton(containerColor = if (selected) colors.primaryContainer else colors.surface, contentColor = VisionGuardStatusColors.onSuccessContainer),
                        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)) {
                        Text((if (state.stream?.targetDeviceId == target.deviceId) "已关联 · " else "选择 · ") + target.deviceName)
                    }
                }
                state.stream?.sourceName?.takeIf { it.isNotBlank() }?.let { Text("推理来源：$it") }
            }
        }
        if (!hidden) OutlinedCard(Modifier.fillMaxWidth().aspectRatio(4f / 3f), shape = MaterialTheme.shapes.medium, colors = CardDefaults.outlinedCardColors(containerColor = colors.surfaceVariant), border = BorderStroke(1.dp, colors.outlineVariant)) {
            if (preview != null) Image(preview.asImageBitmap(), "实时摄像头画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (streaming) "等待实时画面" else "开始后显示实时画面", Modifier.padding(16.dp), color = colors.onSurfaceVariant) }
        }
        if (streaming) Button(onStop, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small,
            colors = VisionGuardControlColors.button(containerColor = colors.error, contentColor = colors.onError)) { Text("停止推流") }
        else {
            Button(onStart, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.connected && state.stream?.targetDeviceId != null, colors = VisionGuardControlColors.button(), shape = MaterialTheme.shapes.small) { Text("开始推流") }
            if (!state.connected || state.stream?.targetDeviceId == null) Text(if (!state.connected) "连接统一服务后可以开始。" else "先选择目标视觉节点，再开始推流。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        CameraSetting(if (highResolution) "最高 720P · 5 帧/秒" else "最高 640×480 · 5 帧/秒", "推流时保持画面规格；停止后可调整。", highResolution, !streaming, onResolution)
        if (captureSize != null && sentSize != null) {
            Text("实际采集 ${captureSize.first}×${captureSize.second} · 发送 ${sentSize.first}×${sentSize.second}", style = MaterialTheme.typography.bodySmall)
            if (highResolution && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720))
                Text("此摄像头已按可用规格回退。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CameraSetting("推流时降低亮度", "只调整当前窗口亮度，外观独立选择。", dim, true, onDim)
        CameraSetting("收起画面预览", "推流继续进行，隐藏本机预览。", hidden, true, onHidePreview)
        OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, colors.outlineVariant), colors = CardDefaults.outlinedCardColors(containerColor = colors.surface)) {
            FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("本机入队 ${state.sentFrames}", style = MaterialTheme.typography.labelLarge)
                Text("服务收帧 ${state.acknowledgedFrames}", style = MaterialTheme.typography.labelLarge)
                Text("本机丢弃 ${state.droppedFrames} · 服务丢弃 ${state.relayDroppedFrames}", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text("主动未采样 ${state.sampledOutFrames} 帧；服务收帧不代表 Windows 已接收。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
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

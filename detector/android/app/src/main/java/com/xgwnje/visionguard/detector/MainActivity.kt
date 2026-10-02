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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
            VisionguardTheme(darkTheme = if (streaming && dimScreen) true else androidx.compose.foundation.isSystemInDarkTheme()) {
                if (session == null) AccountLogin(account, "VisionGuard 镜头推流", "android-camera")
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
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
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
                        val active = publisher
                        if (own != cameraGeneration || !streaming || now < nextFrameAt || active == null) return@setAnalyzer
                        nextFrameAt = now + 200
                        if (!active.canPublish()) { active.dropped(); return@setAnalyzer }
                        val frame = CameraFrameCodec.encode(image, if (highResolution) 1280 else 640, if (highResolution) 720 else 480)
                        val captured = image.width to image.height
                        runOnUiThread { if (own == cameraGeneration && streaming) { captureSize = captured; sentSize = frame.width to frame.height } }
                        if (active.publish(frame) && !hidePreview) {
                            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
                            val oriented = if (frame.rotation == 0 || bitmap == null) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(frame.rotation.toFloat()) }, true)
                            runOnUiThread { if (own == cameraGeneration && streaming) preview = oriented }
                        }
                    } catch (_: Exception) {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("镜头推流", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(state.status, color = MaterialTheme.colorScheme.primary)
        Text("保持应用在前台；离开应用或锁屏后停止推流。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row { Text("目标视觉节点", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); TextButton(onRefresh) { Text("刷新") } }
                if (state.targets.isEmpty()) Text("请先在视觉推理节点登录同一账号。")
                state.targets.forEach { target ->
                    OutlinedButton({ onBind(target.deviceId) }, enabled = !streaming, modifier = Modifier.fillMaxWidth()) {
                        Text((if (state.stream?.targetDeviceId == target.deviceId) "已关联 · " else "选择 · ") + target.deviceName)
                    }
                }
                state.stream?.sourceName?.takeIf { it.isNotBlank() }?.let { Text("推理来源：$it") }
            }
        }
        if (!hidden) OutlinedCard(Modifier.fillMaxWidth().height(220.dp)) {
            if (preview != null) Image(preview.asImageBitmap(), "实时摄像头画面", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxSize()) { Text(if (streaming) "等待实时画面" else "开始后显示实时画面", Modifier.padding(20.dp)) }
        }
        if (streaming) Button(onStop, Modifier.fillMaxWidth().height(50.dp)) { Text("停止推流") }
        else Button(onStart, Modifier.fillMaxWidth().height(50.dp), enabled = state.connected && state.stream?.targetDeviceId != null) { Text("开始推流") }
        Row { Text(if (highResolution) "最高720P · 5 帧/秒" else "最高640×480 · 5 帧/秒", Modifier.weight(1f)); Switch(highResolution, onResolution, enabled = !streaming) }
        if (captureSize != null && sentSize != null) {
            Text("实际采集 ${captureSize.first}×${captureSize.second} · 发送 ${sentSize.first}×${sentSize.second}", style = MaterialTheme.typography.bodySmall)
            if (highResolution && (maxOf(captureSize.first, captureSize.second) < 1280 || minOf(captureSize.first, captureSize.second) < 720))
                Text("此摄像头已按可用规格回退。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row { Text("省电暗色界面", Modifier.weight(1f)); Switch(dim, onDim) }
        Row { Text("收起画面预览", Modifier.weight(1f)); Switch(hidden, onHidePreview) }
        Text("画面发送 ${state.sentFrames} · 服务确认 ${state.acknowledgedFrames} · 丢弃 ${state.droppedFrames}", style = MaterialTheme.typography.bodySmall)
        Text("服务确认表示视频已送达统一服务；检测结果请在控制台查看。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

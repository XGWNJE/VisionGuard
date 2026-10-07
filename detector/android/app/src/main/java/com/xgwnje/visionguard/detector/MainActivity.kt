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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                        CameraHome(state, preview, streaming, highResolution, dimScreen, hidePreview, captureSize, sentSize,
                            onStart = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
                                else { policy.requestPermission(); permission.launch(Manifest.permission.CAMERA) }
                            }, onStop = { stopCamera("user") }, onBind = connection::bind, onRefresh = connection::refreshTargets,
                            onResolution = { saveCameraOption("cameraResolution", if (it) "720p" else "480p") },
                            onDim = { saveCameraOption("cameraDimScreen", it.toString()) },
                            onHidePreview = { saveCameraOption("cameraHidePreview", it.toString()) },
                            header = {
                                AccountHeader(account, session!!, actions = {
                                    Row { CameraHelpButton(state, captureSize, sentSize, highResolution); ApplicationOptions(appearance, BuildConfig.VERSION_NAME, "android-camera", Modifier, compact = true) }
                                }, compact = true, beforeLogout = {
                                    stopCamera("user"); connection.close(); prefs.edit().clear().commit()
                                })
                            })
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

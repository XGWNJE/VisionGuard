package com.xgwnje.visionguard.inference

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard.account.AccountLogin
import com.xgwnje.visionguard.account.AccountStore
import com.xgwnje.visionguard.account.ApplicationOptions
import com.xgwnje.visionguard.account.dark
import com.xgwnje.visionguard.account.rememberAppearance
import com.xgwnje.visionguard.inference.model.AccelerationBenchmark
import com.xgwnje.visionguard.inference.model.AccelerationMeasure
import com.xgwnje.visionguard.inference.model.InferenceProvider
import com.xgwnje.visionguard.inference.model.OnnxEngine
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.MessageTone
import com.xgwnje.visionguard.inference.service.InferenceService
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.ui.MainScreen
import com.xgwnje.visionguard.inference.ui.theme.VisionguardTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视觉推理节点（Android）入口。
 *
 * 只负责会话门、主题、权限与前台服务：
 * - Android 13+ 启动前台服务前确认 `POST_NOTIFICATIONS`；
 * - 启动本机相机来源前确认 `CAMERA`，授权后继续启动该来源；
 * - 显式请求时跑一次推理加速测量（见 `runAccelerationBenchmarkIfRequested`）。
 */
class MainActivity : ComponentActivity() {

    /** 相机授权成功后要继续启动的来源。 */
    private var pendingCameraSource: String? = null

    private var notificationAsked = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = pendingCameraSource
        pendingCameraSource = null
        if (pending != null) {
            if (granted) {
                val runtime = InferenceRuntime.get(this)
                runtime.setMessage("已授予相机权限，正在启动来源", MessageTone.Success)
                lifecycleScope.launch { runtime.startSource(pending)?.let(runtime::setMessage) }
            } else {
                InferenceRuntime.get(this).setMessage("请先授予相机权限", MessageTone.Warning)
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) InferenceRuntime.get(this).setMessage("未授予通知权限，前台服务通知可能不显示", MessageTone.Warning)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (runAccelerationBenchmarkIfRequested()) return
        enableEdgeToEdge()
        applyImmersiveMode()
        val account = AccountStore.get(this)
        val runtime = InferenceRuntime.get(this)
        setContent {
            val session by account.session.collectAsState()
            val appearance = rememberAppearance()
            val darkTheme = appearance.dark()
            SideEffect {
                applyImmersiveMode()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            VisionguardTheme(darkTheme = darkTheme) {
                Column(Modifier.fillMaxSize()) {
                    if (session == null) {
                        ApplicationOptions(appearance, AppConstants.LOGIN_COMPONENT)
                        AccountLogin(
                            store = account,
                            title = AppConstants.APP_TITLE,
                            component = AppConstants.LOGIN_COMPONENT,
                            beforeLogin = {
                                runtime.stopAll()
                                InferenceService.stop(this@MainActivity)
                            }
                        )
                    } else {
                        key(session!!.scope) {
                            val state by runtime.state.collectAsState()
                            // 有来源在运行时保持前台服务；Android 13+ 先确认通知权限。
                            LaunchedEffect(state.runningSourceCount) {
                                if (state.runningSourceCount > 0) {
                                    requestNotificationPermissionIfNeeded()
                                    // 远控恢复可能在应用处于后台时触发，系统会拒绝启动前台服务：
                                    // 这时明确提示，而不是让异常打断界面线程。
                                    if (!InferenceService.start(this@MainActivity)) {
                                        runtime.setMessage("系统不允许在后台启动前台服务，请回到应用后重新开始检测", MessageTone.Error)
                                    }
                                } else {
                                    InferenceService.stop(this@MainActivity)
                                }
                            }
                            MainScreen(
                                runtime = runtime,
                                session = session!!,
                                appearance = appearance,
                                onToggleSource = ::toggleSource
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 全屏沉浸：不显示系统状态栏，把高度让给画面；用户从边缘下滑仍可临时呼出。
     * 回到前台或窗口重新获得焦点时再隐藏一次，避免系统恢复状态栏。
     */
    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * 显式请求时跑一次推理加速测量，不进入界面。
     *
     * 只用于测量，正常启动不会命中；且只在 Debug 构建生效，UI 预览 / 正式 Release 即使
     * 收到该 extra 也不会执行（预览构建虽然 debuggable，但 `isDebuggableRelease` 明确排除）。
     *
     * 触发方式：
     * ```
     * adb shell am start -n <包名>/com.xgwnje.visionguard.inference.MainActivity \
     *     --ez benchmark true [--es models yolo26n_320_int8.onnx] [--es frame frames/scene.jpg]
     * ```
     * 模型与画面放 `files/bench-models/`、`files/bench-frames/`（`run-as` 推送），读数同时写
     * logcat 的 `VGInferenceBench` 标签与 `files/diagnostics/acceleration-bench-*.txt`。
     */
    private fun runAccelerationBenchmarkIfRequested(): Boolean {
        if (!isBenchmarkBuild() || intent?.getBooleanExtra(EXTRA_BENCHMARK, false) != true) return false

        val requested = intent?.getStringExtra(EXTRA_BENCHMARK_MODELS)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val frameName = intent?.getStringExtra(EXTRA_BENCHMARK_FRAME)?.trim().orEmpty()

        Thread {
            val dir = File(filesDir, AccelerationBenchmark.MODEL_DIR)
            val requestedNames = if (requested.isEmpty()) {
                AccelerationMeasure.modelsIn(dir).map { it.name }
            } else {
                requested
            }
            val device = "${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}"
            val results = mutableListOf<AccelerationMeasure.Result>()
            val failures = mutableListOf<String>()

            // 真实画面：与正式链路同一套 letterbox 预处理；读不到就退回合成输入并记一条失败。
            val frame = if (frameName.isEmpty()) null else {
                AccelerationBenchmark.loadFrame(File(filesDir, frameName))
                    ?: File(filesDir, frameName).let {
                        failures += "画面 ${it.name}：文件不存在或无法解码"
                        null
                    }
            }

            if (requestedNames.isEmpty()) {
                failures += "没有待测模型：$dir（用 run-as 推送到该目录）"
            }
            requestedNames.forEach { name ->
                // 同一个模型基名下的 .onnx 与 .tflite 会各跑自己运行时的档位。
                val base = name.substringBeforeLast('.')
                val onnx = File(dir, "$base.onnx")
                val tflite = File(dir, "$base.tflite")
                val usable = listOfNotNull(
                    onnx.takeIf { it.isFile && it.length() >= OnnxEngine.MIN_MODEL_BYTES },
                    tflite.takeIf { it.isFile && it.length() >= OnnxEngine.MIN_MODEL_BYTES }
                )
                if (usable.isEmpty()) {
                    failures += "$base：既没有可用的 .onnx 也没有 .tflite"
                    return@forEach
                }
                val (rows, errors) = AccelerationBenchmark.run(
                    modelName = base,
                    onnxFile = onnx.takeIf { it.isFile },
                    tfliteFile = tflite.takeIf { it.isFile },
                    providers = InferenceProvider.benchmarkOrder,
                    argb = frame
                )
                results += rows
                failures += errors
            }

            val report = AccelerationMeasure.Report(device, results, failures)
            val path = AccelerationBenchmark.persist(File(filesDir, "diagnostics"), report)
            Log.i(AccelerationBenchmark.TAG, "输入：${if (frame != null) "$frameName（真实画面）" else "合成画面"}")
            Log.i(AccelerationBenchmark.TAG, "报告：${path ?: "写入失败"}")
            report.lines().forEach { Log.i(AccelerationBenchmark.TAG, it) }
            runOnUiThread {
                Toast.makeText(this, "推理加速测量完成：${results.size} 条读数，${failures.size} 项失败；详见 logcat", Toast.LENGTH_LONG).show()
            }
            finish()
        }.start()
        return true
    }

    /** 本机相机来源先确认相机权限，其余来源直接启停。 */
    private suspend fun toggleSource(sourceId: String): String? {
        val runtime = InferenceRuntime.get(this)
        val source = runtime.state.value.sources.firstOrNull { it.sourceId == sourceId }
        if (source?.state?.isRunning == true) {
            runtime.stopSource(sourceId)
            return null
        }
        if (source?.mode == SourceCaptureMode.LocalCamera && !runtime.hasCameraPermission()) {
            if (pendingCameraSource != null) return "正在等待相机权限结果"
            pendingCameraSource = sourceId
            cameraPermission.launch(Manifest.permission.CAMERA)
            return "请在系统弹窗中授予相机权限"
        }
        return runtime.startSource(sourceId)
    }

    /** 只在首次需要时弹一次通知权限，避免每次重进界面都打扰用户。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notificationAsked) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        notificationAsked = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * 测量入口只在 Debug 构建开放。
     *
     * UI 预览构建是 debuggable 的 Release，不能靠 `debuggable` 标志判断；这里直接看
     * `BuildConfig.DEBUG`，并额外排除预览包名，避免测量入口随预览包流出。
     */
    private fun isBenchmarkBuild(): Boolean =
        BuildConfig.DEBUG && !applicationContext.packageName.endsWith(".uipreview")

    companion object {
        /** `--ez benchmark true`：只跑一次加速测量，不进入界面。 */
        const val EXTRA_BENCHMARK = "benchmark"

        /** `--es models a.onnx,b.onnx`：只测这些模型；省略时测目录内全部。 */
        const val EXTRA_BENCHMARK_MODELS = "models"

        /** `--es frame bench-frames/scene.jpg`：真实画面路径（相对 files/）；省略时用合成画面。 */
        const val EXTRA_BENCHMARK_FRAME = "frame"
    }
}

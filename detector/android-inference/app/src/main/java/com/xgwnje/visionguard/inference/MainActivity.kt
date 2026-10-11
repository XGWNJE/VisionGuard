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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.xgwnje.visionguard.inference.model.BundledAccelerationBenchmark
import com.xgwnje.visionguard.inference.model.InferenceProvider
import com.xgwnje.visionguard.inference.model.OnnxEngine
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.runtime.MessageTone
import com.xgwnje.visionguard.inference.service.InferenceService
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.ui.BenchmarkScreen
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
        // 测量构建先给测量入口：测量只用本机算力与包内模型，不应被登录条件挡住；
        // 点「进入完整应用」再走原有登录流程。正式构建没有这个首屏。
        val benchmarkFirst = isBenchmarkBuild()
        setContent {
            var showFullApp by rememberSaveable { mutableStateOf(!benchmarkFirst) }
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
                    if (!showFullApp) {
                        BenchmarkScreen(onOpenFullApp = { showFullApp = true })
                    } else if (session == null) {
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
     * 显式请求时跑一次自带的推理加速测量，不进入界面。
     *
     * 界面入口在「设置 → 维护 → 推理加速测量」，正常使用走那里；这个 intent 入口只是
     * 让自动化验证能在不登录的情况下跑同一套逻辑（模型同样取自 APK assets）。
     *
     * ```
     * adb shell am start -n <包名>/com.xgwnje.visionguard.inference.MainActivity \
     *     --ez benchmark true [--ez benchmark_full true] [--ei benchmark_parallel 8]
     * ```
     *
     * `benchmark_parallel` 大于 1 时改为跑**多路压力**：同时开这么多路跑同一模型。
     */
    private fun runAccelerationBenchmarkIfRequested(): Boolean {
        if (!isBenchmarkBuild() || intent?.getBooleanExtra(EXTRA_BENCHMARK, false) != true) return false
        val full = intent?.getBooleanExtra(EXTRA_BENCHMARK_FULL, false) == true
        val parallelStreams = intent?.getIntExtra(EXTRA_BENCHMARK_PARALLEL, 0) ?: 0
        val parallelModels = intent?.getStringExtra(EXTRA_BENCHMARK_MODELS).orEmpty()

        lifecycleScope.launch {
            BundledAccelerationBenchmark.run(
                this@MainActivity,
                onlyAccelerated = !full,
                parallelStreams = parallelStreams,
                parallelModels = parallelModels
            )
            val state = BundledAccelerationBenchmark.state.value
            Log.i(BundledAccelerationBenchmark.TAG, "报告：${state.reportPath ?: "写入失败"}")
            state.report.lines().forEach { Log.i(BundledAccelerationBenchmark.TAG, it) }
            Toast.makeText(
                this@MainActivity,
                "推理加速测量完成：${if (state.failure == null) "见 logcat 与设置页" else state.failure}",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
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
        /** `--ez benchmark true`：只跑一次加速测量，不进入界面（自动化验证用）。 */
        const val EXTRA_BENCHMARK = "benchmark"

        /** `--ez benchmark_full true`：连逐线程 CPU 档一起跑；省略时只跑加速档与基线。 */
        const val EXTRA_BENCHMARK_FULL = "benchmark_full"

        /** `--ei benchmark_parallel N`：多路压力模式，同时开 N 路跑同一模型（N > 0 时生效）。 */
        const val EXTRA_BENCHMARK_PARALLEL = "benchmark_parallel"

        /** `--es benchmark_models a,b`：只跑名字包含这些子串的模型（空表示全部）。 */
        const val EXTRA_BENCHMARK_MODELS = "benchmark_models"
    }
}

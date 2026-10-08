package com.xgwnje.visionguard.inference

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard.account.AccountLogin
import com.xgwnje.visionguard.account.AccountStore
import com.xgwnje.visionguard.account.ApplicationOptions
import com.xgwnje.visionguard.account.dark
import com.xgwnje.visionguard.account.rememberAppearance
import com.xgwnje.visionguard.inference.runtime.InferenceRuntime
import com.xgwnje.visionguard.inference.service.InferenceService
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.ui.MainScreen
import com.xgwnje.visionguard.inference.ui.theme.VisionguardTheme
import kotlinx.coroutines.launch

/**
 * 视觉推理节点（Android）入口。
 *
 * 只负责会话门、主题、权限与前台服务：
 * - Android 13+ 启动前台服务前确认 `POST_NOTIFICATIONS`；
 * - 启动本机相机来源前确认 `CAMERA`，授权后继续启动该来源。
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
                runtime.setMessage("已授予相机权限，正在启动来源")
                lifecycleScope.launch { runtime.startSource(pending)?.let(runtime::setMessage) }
            } else {
                InferenceRuntime.get(this).setMessage("请先授予相机权限")
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) InferenceRuntime.get(this).setMessage("未授予通知权限，前台服务通知可能不显示")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val account = AccountStore.get(this)
        val runtime = InferenceRuntime.get(this)
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
                                    InferenceService.start(this@MainActivity)
                                    requestNotificationPermissionIfNeeded()
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
}

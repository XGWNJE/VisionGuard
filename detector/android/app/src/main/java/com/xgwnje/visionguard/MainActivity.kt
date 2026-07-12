package com.xgwnje.visionguard

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.xgwnje.visionguard.data.model.DeploymentOrientation
import com.xgwnje.visionguard.data.model.MonitorConfig
import com.xgwnje.visionguard.data.remote.WsState
import com.xgwnje.visionguard.data.repository.SettingsRepository
import com.xgwnje.visionguard.inference.SocWhitelist
import com.xgwnje.visionguard.service.DetectorForegroundService
import com.xgwnje.visionguard.ui.console.CalibrationWorkspace
import com.xgwnje.visionguard.ui.console.DetectorConsoleScreen
import com.xgwnje.visionguard.ui.console.UncalibratedStartDialog
import com.xgwnje.visionguard.ui.console.buildDetectorConsoleState
import com.xgwnje.visionguard.ui.theme.VisionguardTheme
import com.xgwnje.visionguard.util.AutoUpdater
import com.xgwnje.visionguard.util.UpdateInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<DetectorForegroundService?>(null)
    private var isBound by mutableStateOf(false)
    private var serviceStartRequested = false
    private var cameraPermissionGranted by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? DetectorForegroundService.LocalBinder
            service = localBinder?.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true || hasCameraPermission()
        cameraPermissionGranted = cameraGranted
        if (cameraGranted) {
            startAndBindService()
        } else {
            Log.w("VG_MainActivity", "Camera permission denied; detector service not started")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        cameraPermissionGranted = hasCameraPermission()

        setContent {
            VisionguardTheme {
                MainScreen(
                    service = service,
                    isBound = isBound,
                    hasCameraPermission = cameraPermissionGranted,
                    onRequestPermission = { requestPermissionsOrStartService() }
                )
            }
        }

        requestPermissionsOrStartService()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            service = null
        }
    }

    private fun requestPermissionsOrStartService() {
        val permissions = mutableListOf<String>()
        if (!hasCameraPermission()) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            cameraPermissionGranted = true
            startAndBindService()
        }
    }

    private fun startAndBindService() {
        if (!hasCameraPermission()) {
            Log.w("VG_MainActivity", "Camera permission missing; detector service not started")
            return
        }
        ensureServiceRunning()
        bindService()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun ensureServiceRunning() {
        if (serviceStartRequested) return
        serviceStartRequested = true
        val intent = Intent(this, DetectorForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun bindService() {
        if (isBound) return
        val intent = Intent(this, DetectorForegroundService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}

@Composable
private fun MainScreen(
    service: DetectorForegroundService?,
    isBound: Boolean,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember(context.applicationContext) {
        SettingsRepository(context.applicationContext)
    }

    if (!hasCameraPermission) {
        PermissionMissingScreen(onRequestPermission = onRequestPermission, modifier = modifier)
        return
    }

    if (!isBound || service == null) {
        BoxLoading(modifier = modifier)
        return
    }

    val draftConfig by repository.monitorConfigFlow.collectAsState(initial = MonitorConfig())
    val deviceName by repository.deviceNameFlow.collectAsState(initial = SettingsRepository.DEFAULT_DEVICE_NAME)
    val deploymentOrientation by repository.deploymentOrientationFlow.collectAsState(initial = null)
    val calibrationDone by repository.calibrationDoneFlow.collectAsState(initial = false)

    val connectionState by service.connectionState.collectAsState()
    val isMonitoring by service.isMonitoring.collectAsState()
    val lastFrame by service.lastAlertFrame.collectAsState()
    val frameAspectRatio by service.frameAspectRatio.collectAsState()
    val isReady by service.isReady.collectAsState()
    val actualSamplingRate by service.actualSamplingRate.collectAsState()
    val lastAlertPushTime by service.lastAlertPushTime.collectAsState()
    val appliedConfig by service.currentConfigFlow.collectAsState()

    var showCalibration by remember { mutableStateOf(false) }
    var showMaintenance by remember { mutableStateOf(false) }
    var isCapturingFrame by remember { mutableStateOf(false) }
    var showUncalibratedDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(deploymentOrientation) {
        applyDeploymentPresentation(activity, deploymentOrientation)
    }

    LaunchedEffect(lastFrame, isCapturingFrame) {
        if (isCapturingFrame && lastFrame != null) {
            isCapturingFrame = false
        }
    }

    LaunchedEffect(isCapturingFrame) {
        if (isCapturingFrame) {
            delay(5000)
            isCapturingFrame = false
        }
    }

    BackHandler(enabled = showCalibration) {
        showCalibration = false
    }

    val consoleState = buildDetectorConsoleState(
        deploymentOrientation = deploymentOrientation,
        draftConfig = draftConfig,
        appliedConfig = appliedConfig,
        isMonitoring = isMonitoring,
        isReady = isReady,
        hasCalibrationFrame = calibrationDone || lastFrame != null
    )

    val modelStatusText = remember(draftConfig.modelName, draftConfig.inputSize, context.filesDir) {
        val modelFile = context.filesDir.resolve("models/${draftConfig.modelName}_${draftConfig.inputSize}.onnx")
        if (modelFile.exists() && modelFile.length() > 0) {
            "模型文件：已下载"
        } else {
            "模型文件：未下载（启动监控时自动下载）"
        }
    }

    fun startMonitoring() {
        service.startMonitoring(draftConfig)
    }

    val activeOrientation = deploymentOrientation
    if (showCalibration && activeOrientation != null) {
        CalibrationWorkspace(
            orientation = activeOrientation,
            bitmap = lastFrame,
            frameAspectRatio = frameAspectRatio,
            initialMasks = draftConfig.maskRegions,
            initialZoom = draftConfig.digitalZoom,
            isCapturing = isCapturingFrame,
            onRetake = {
                isCapturingFrame = true
                service.capturePreviewFrame()
            },
            onApply = { masks, zoom ->
                scope.launch {
                    repository.saveMonitorConfig(draftConfig.copy(maskRegions = masks, digitalZoom = zoom))
                    repository.setCalibrationDone(true)
                    showCalibration = false
                    Toast.makeText(context, "校准配置已保存", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = { showCalibration = false },
            modifier = modifier
        )
    } else {
        DetectorConsoleScreen(
            state = consoleState,
            connectionState = connectionState,
            deviceName = deviceName,
            lastFrame = lastFrame,
            frameAspectRatio = frameAspectRatio,
            lastFrameLabel = lastFrameLabel(calibrationDone, lastAlertPushTime),
            lastAlertTime = lastAlertPushTime,
            actualSamplingRate = actualSamplingRate,
            isCapturingFrame = isCapturingFrame,
            showMaintenance = showMaintenance,
            isHighEndSoc = SocWhitelist.isHighEndSoc(),
            modelStatusText = modelStatusText,
            onSelectOrientation = { orientation ->
                scope.launch {
                    repository.setDeploymentOrientation(orientation)
                    applyDeploymentPresentation(activity, orientation)
                }
            },
            onToggleMonitoring = {
                if (isMonitoring) {
                    service.stopMonitoring()
                } else if (consoleState.requiresUncalibratedStartConfirmation) {
                    showUncalibratedDialog = true
                } else {
                    startMonitoring()
                }
            },
            onRefreshFrame = {
                isCapturingFrame = true
                if (isMonitoring) service.requestSnapshot() else service.capturePreviewFrame()
            },
            onOpenCalibration = {
                if (consoleState.canEnterCalibration) {
                    showCalibration = true
                    isCapturingFrame = true
                    service.capturePreviewFrame()
                }
            },
            onRetakeCalibrationFrame = {
                isCapturingFrame = true
                service.capturePreviewFrame()
            },
            onApplyCalibration = { masks, zoom ->
                scope.launch {
                    repository.saveMonitorConfig(draftConfig.copy(maskRegions = masks, digitalZoom = zoom))
                    repository.setCalibrationDone(true)
                    showCalibration = false
                }
            },
            onCloseCalibration = { showCalibration = false },
            onOpenMaintenance = { showMaintenance = true },
            onCloseMaintenance = { showMaintenance = false },
            onSaveMaintenance = { newConfig, newDeviceName, newOrientation ->
                scope.launch {
                    repository.saveMonitorConfig(newConfig)
                    repository.setDeviceName(newDeviceName)
                    repository.setDeploymentOrientation(newOrientation)
                    applyDeploymentPresentation(activity, newOrientation)
                    showMaintenance = false
                    Toast.makeText(context, "已保存，停止后重新开启生效", Toast.LENGTH_SHORT).show()
                }
            },
            onReconnect = { service.reconnect() },
            onCheckUpdate = {
                if (!isCheckingUpdate) {
                    isCheckingUpdate = true
                    scope.launch {
                        val result = AutoUpdater.checkUpdate(context)
                        isCheckingUpdate = false
                        if (result != null) {
                            updateInfo = result
                            showUpdateDialog = true
                        } else {
                            Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            modifier = modifier
        )
    }

    if (showUncalibratedDialog) {
        UncalibratedStartDialog(
            onConfirm = {
                showUncalibratedDialog = false
                startMonitoring()
            },
            onDismiss = { showUncalibratedDialog = false }
        )
    }

    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本") },
            text = { Text("发现新版本 ${updateInfo!!.version}\n当前版本 ${AppConstants.VERSION}\n\n是否立即下载更新？") },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    AutoUpdater.downloadApk(context, updateInfo!!.downloadUrl, updateInfo!!.version)
                }) { Text("更新") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
            }
        )
    }
}

@Composable
private fun PermissionMissingScreen(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要摄像头权限",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "授权后才能启动检测端服务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(onClick = onRequestPermission) {
            Text("重新授权")
        }
    }
}

@Composable
private fun BoxLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "加载中...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun applyDeploymentPresentation(activity: Activity?, orientation: DeploymentOrientation?) {
    if (activity == null) return

    orientation?.let {
        activity.requestedOrientation = when (it) {
            DeploymentOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            DeploymentOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
        if (orientation == DeploymentOrientation.LANDSCAPE) {
            hide(WindowInsetsCompat.Type.systemBars())
        } else {
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private fun lastFrameLabel(calibrationDone: Boolean, lastAlertPushTime: String?): String =
    when {
        lastAlertPushTime != null -> lastAlertPushTime
        calibrationDone -> "校准帧"
        else -> "非实时预览"
    }

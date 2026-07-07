package com.xgwnje.visionguard_android

// ┌─────────────────────────────────────────────────────────┐
// │ MainActivity.kt                                         │
// │ 角色：NavHost 宿主 + 通知权限申请 + Service 绑定          │
// │ 路由：main(alertList / deviceList) → alertDetail         │
// │ 连接状态集成到顶部 ConnectionBanner，无独立连接页         │
// └─────────────────────────────────────────────────────────┘

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.home.buildFrostedOverlaySpec
import com.xgwnje.visionguard_android.ui.home.receiverMainTabs
import com.xgwnje.visionguard_android.ui.screen.AlertDetailScreen
import com.xgwnje.visionguard_android.ui.screen.AlertListScreen
import com.xgwnje.visionguard_android.ui.screen.DeviceListScreen
import com.xgwnje.visionguard_android.ui.theme.ReceiverBackground
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.VisionGuard_AndroidTheme

class MainActivity : ComponentActivity() {

    private var boundService: AlertForegroundService? = null
    private var serviceBound by mutableStateOf(false)
    private var pendingAlertId by mutableStateOf<String?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as AlertForegroundService.AlertServiceBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            boundService = null
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户选择，不强制要求 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 读取通知点击传入的 alertId
        pendingAlertId = intent.getStringExtra("alertId")

        // 申请通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 直接启动并绑定服务（服务内部读 AppConstants 连接）
        startAndBindService()

        setContent {
            VisionGuard_AndroidTheme {
                if (!serviceBound || boundService == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    VisionGuardNavHost(
                        service = boundService!!,
                        initialAlertId = pendingAlertId
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop 模式下通知点击会触发此处
        val alertId = intent.getStringExtra("alertId")
        if (alertId != null) {
            pendingAlertId = alertId
        }
    }

    private fun startAndBindService() {
        val svc = Intent(this, AlertForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(connection)
        }
    }
}

// ── 导航主机 ──────────────────────────────────────────────────

@Composable
fun VisionGuardNavHost(
    service: AlertForegroundService,
    initialAlertId: String? = null
) {
    val navController = rememberNavController()

    // 若从通知点击进入，自动导航到报警详情
    LaunchedEffect(initialAlertId) {
        if (!initialAlertId.isNullOrEmpty()) {
            navController.navigate("alertDetail/$initialAlertId") {
                popUpTo("main") { saveState = true }
            }
        }
    }

    NavHost(navController = navController, startDestination = "main") {

        composable("main") {
            MainScreen(
                service = service,
                onAlertClick = { alertId ->
                    navController.navigate("alertDetail/$alertId")
                }
            )
        }

        composable("alertDetail/{alertId}") { backStack ->
            val alertId = backStack.arguments?.getString("alertId") ?: ""
            AlertDetailScreen(
                service = service,
                alertId = alertId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ── 主界面（底部 Tab 导航）────────────────────────────────────

@Composable
fun MainScreen(
    service: AlertForegroundService,
    onAlertClick: (String) -> Unit
) {
    val tabNavController = rememberNavController()
    val navBackStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReceiverBackground)
    ) {
        NavHost(
            navController = tabNavController,
            startDestination = "alertList",
            modifier = Modifier.fillMaxSize()
        ) {
            composable("alertList") {
                AlertListScreen(
                    service = service,
                    onAlertClick = onAlertClick
                )
            }
            composable("deviceList") {
                DeviceListScreen(service = service)
            }
        }

        ReceiverBottomBar(
            selectedRoute = currentDest?.route ?: "alertList",
            onNavigate = { route ->
                tabNavController.navigate(route) {
                    popUpTo(tabNavController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ReceiverBottomBar(
    selectedRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val overlay = buildFrostedOverlaySpec()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 18.dp, bottom = 14.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(76.dp),
            shape = RoundedCornerShape(40.dp),
            color = ReceiverSurface.copy(alpha = overlay.bottomBarAlpha),
            border = BorderStroke(1.dp, Color.White.copy(alpha = overlay.borderAlpha)),
            tonalElevation = 0.dp,
            shadowElevation = overlay.shadowElevationDp.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp)
            ) {
                receiverMainTabs().forEach { tab ->
                    ReceiverBottomBarItem(
                        selected = selectedRoute == tab.route,
                        icon = receiverTabIcon(tab.route),
                        label = tab.label,
                        onClick = { onNavigate(tab.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun receiverTabIcon(route: String): ImageVector =
    when (route) {
        "alertList" -> Icons.AutoMirrored.Filled.ListAlt
        "deviceList" -> Icons.Default.PhoneAndroid
        else -> Icons.AutoMirrored.Filled.ListAlt
    }

@Composable
private fun ReceiverBottomBarItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemColor = if (selected) ReceiverPrimary.copy(alpha = 0.92f) else Color.Transparent
    val contentColor = if (selected) Color.White else ReceiverMuted

    val shape = RoundedCornerShape(34.dp)

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = itemColor
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

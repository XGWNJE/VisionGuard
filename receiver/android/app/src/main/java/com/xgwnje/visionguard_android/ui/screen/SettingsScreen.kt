package com.xgwnje.visionguard_android.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.util.AutoUpdater
import com.xgwnje.visionguard_android.util.UpdateInfo
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    service: AlertForegroundService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val connectionState by service.connectionState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge
        )

        // 服务器连接状态
        SectionTitle("服务器连接")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val stateLabel = when (connectionState) {
                WsState.CONNECTED -> "已连接"
                WsState.CONNECTING -> "连接中"
                WsState.DISCONNECTED -> "未连接"
                WsState.AUTH_FAILED -> "认证失败"
            }
            Text(
                text = "状态: $stateLabel",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (connectionState == WsState.DISCONNECTED || connectionState == WsState.AUTH_FAILED) {
                Button(onClick = { service.reconnect() }) {
                    Text("重连")
                }
            }
        }

        // 版本更新
        SectionTitle("版本更新")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前版本 ${AppConstants.VERSION}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Button(
                onClick = {
                    if (isChecking) return@Button
                    isChecking = true
                    scope.launch {
                        val result = AutoUpdater.checkUpdate(context)
                        isChecking = false
                        if (result != null) {
                            updateInfo = result
                            showUpdateDialog = true
                        } else {
                            android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isChecking
            ) {
                Text(if (isChecking) "检查中…" else "检查更新")
            }
        }

        if (showUpdateDialog && updateInfo != null) {
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("发现新版本") },
                text = {
                    Text("发现新版本 ${updateInfo!!.version}\n当前版本 ${AppConstants.VERSION}\n\n是否立即下载更新？")
                },
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

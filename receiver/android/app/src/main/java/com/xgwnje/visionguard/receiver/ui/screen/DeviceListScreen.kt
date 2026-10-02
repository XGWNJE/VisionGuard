package com.xgwnje.visionguard.receiver.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard.receiver.data.remote.WsState
import com.xgwnje.visionguard.receiver.service.AlertForegroundService
import com.xgwnje.visionguard.receiver.ui.component.DeviceCard
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceConfigFromDevice
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlert
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverBackground
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurface
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurfaceMuted
import com.xgwnje.visionguard.receiver.ui.viewmodel.DeviceViewModel
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun DeviceListScreen(
    service: AlertForegroundService
) {
    val deviceVm: DeviceViewModel = viewModel(factory = DeviceViewModel.Factory(service))
    val devices by deviceVm.devices.collectAsState()
    val wsState by service.connectionState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingUnbind by remember { mutableStateOf<com.xgwnje.visionguard.receiver.data.model.DeviceInfo?>(null) }
    var unbinding by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val onlineCount = remember(devices) { devices.count { it.online } }
    val statusTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromDeviceIndex = from.index - 1
        val toDeviceIndex = to.index - 1
        if (fromDeviceIndex in devices.indices && toDeviceIndex in devices.indices) {
            deviceVm.moveDevice(fromDeviceIndex, toDeviceIndex)
        }
    }

    LaunchedEffect(Unit) {
        deviceVm.commandAck.collect { result ->
            val target = result.targetSourceId?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val message = if (result.success) {
                "已执行：${result.command}$target"
            } else {
                "执行失败：${result.command}$target · ${result.reason}"
            }
            snackbarHost.showSnackbar(message)
        }
    }

    pendingUnbind?.let { device ->
        AlertDialog(
            onDismissRequest = { if (!unbinding) pendingUnbind = null },
            title = { Text("解绑设备") },
            text = { Text("解绑 ${device.deviceName} 后，该设备需要重新登录。已有事件记录会保留。") },
            confirmButton = { TextButton(enabled = !unbinding, onClick = {
                unbinding = true
                scope.launch {
                    val success = deviceVm.unbindDevice(device.deviceId)
                    pendingUnbind = null
                    unbinding = false
                    snackbarHost.showSnackbar(if (success) "已解绑：${device.deviceName}" else "解绑失败，请重试")
                }
            }) { Text(if (unbinding) "正在解绑…" else "确认解绑") } },
            dismissButton = { TextButton(enabled = !unbinding, onClick = { pendingUnbind = null }) { Text("取消") } }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReceiverBackground)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            state = lazyListState,
            contentPadding = PaddingValues(
                top = statusTopPadding + 24.dp,
                bottom = navigationBottomPadding + 122.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                DeviceListHeader(
                    onlineCount = onlineCount,
                    totalCount = devices.size
                )
            }

            if (devices.isEmpty()) {
                item {
                    EmptyDeviceState(
                        connected = wsState == WsState.CONNECTED
                    )
                }
            } else {
                itemsIndexed(
                    items = devices,
                    key = { _, device -> device.deviceId }
                ) { _, device ->
                    ReorderableItem(reorderableState, key = device.deviceId) {
                        Column {
                            DeviceCard(
                                device = device,
                                initialConfig = buildDeviceConfigFromDevice(device),
                                onCommand = { command, sourceId ->
                                    deviceVm.sendCommand(device.deviceId, command, sourceId)
                                },
                                onSetConfig = { key, value, sourceId ->
                                    deviceVm.sendSetConfig(device.deviceId, key, value, sourceId)
                                },
                                dragHandleModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(
                                            HapticFeedbackType.GestureThresholdActivate
                                        )
                                    },
                                    onDragStopped = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    }
                                )
                            )
                            if (!device.online) TextButton(
                                onClick = { pendingUnbind = device }, modifier = Modifier.align(Alignment.End)
                            ) { Text("解绑设备") }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp)
                .padding(bottom = navigationBottomPadding + 106.dp)
        ) { data ->
            CommandSnackbar(
                message = data.visuals.message,
                isError = data.visuals.message.startsWith("命令失败")
            )
        }
    }
}

@Composable
private fun DeviceListHeader(
    onlineCount: Int,
    totalCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "设备",
                style = MaterialTheme.typography.titleLarge,
                color = ReceiverPrimary,
                fontWeight = FontWeight.Black
            )
            Text(
                text = if (totalCount > 0) "$onlineCount 台在线 / 共 $totalCount 台" else "等待检测端上线",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDeviceState(connected: Boolean) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp),
        shape = RoundedCornerShape(28.dp),
        color = ReceiverSurface,
        border = BorderStroke(1.dp, Color.White),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.PowerSettingsNew,
                contentDescription = null,
                tint = ReceiverMuted,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (connected) "暂无设备在线" else "等待连接",
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverPrimary,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (connected) "检测端上线后会显示在这里" else "接收端重连后自动刷新设备",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted
            )
        }
    }
}

@Composable
private fun CommandSnackbar(
    message: String,
    isError: Boolean
) {
    Snackbar(
        shape = RoundedCornerShape(26.dp),
        containerColor = if (isError) ReceiverAlertSoft else ReceiverSurfaceMuted,
        contentColor = if (isError) ReceiverAlert else ReceiverPrimary,
        actionContentColor = ReceiverPrimary
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black
        )
    }
}

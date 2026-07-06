package com.xgwnje.visionguard_android.ui.screen

// ┌─────────────────────────────────────────────────────────┐
// │ AlertListScreen.kt                                      │
// │ 角色：主界面，显示接收状态与实时报警列表                    │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard_android.AppConstants
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.component.AlertCard
import com.xgwnje.visionguard_android.ui.component.ConnectionBanner
import com.xgwnje.visionguard_android.ui.home.buildAlertListChrome
import com.xgwnje.visionguard_android.ui.home.buildNoUpdateDialogModel
import com.xgwnje.visionguard_android.ui.home.buildUpdateDialogModel
import com.xgwnje.visionguard_android.ui.home.buildUpdateFailedDialogModel
import com.xgwnje.visionguard_android.ui.home.UpdateDialogUiModel
import com.xgwnje.visionguard_android.ui.home.UpdateDialogTone
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimarySoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurfaceMuted
import com.xgwnje.visionguard_android.ui.viewmodel.AlertViewModel
import com.xgwnje.visionguard_android.util.AutoUpdater
import com.xgwnje.visionguard_android.util.UpdateCheckResult
import com.xgwnje.visionguard_android.util.UpdateInfo
import kotlinx.coroutines.launch

private data class PendingUpdateDialog(
    val model: UpdateDialogUiModel,
    val updateInfo: UpdateInfo? = null
)

@Composable
fun AlertListScreen(
    service: AlertForegroundService,
    onAlertClick: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alertVm: AlertViewModel = viewModel(factory = AlertViewModel.Factory(service))
    val alerts by alertVm.alerts.collectAsState()
    val devices by service.devices.collectAsState()
    val wsState by service.connectionState.collectAsState()
    val validAlerts = remember(alerts) { alerts.filter { it.alertId.isNotEmpty() } }
    val onlineDeviceCount = remember(devices) { devices.count { it.online } }
    var pendingUpdateDialog by remember { mutableStateOf<PendingUpdateDialog?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val chrome = remember { buildAlertListChrome() }
    val statusTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = chrome.horizontalPaddingDp.dp)
                .padding(top = chrome.topPaddingDp.dp),
            contentPadding = PaddingValues(
                top = statusTopPadding + chrome.topOverlayReservedDp.dp,
                bottom = chrome.bottomOverlayReservedDp.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (validAlerts.isEmpty()) {
                item {
                    EmptyAlertState()
                }
            } else {
                items(
                    items = validAlerts,
                    key = { it.alertId }
                ) { alert ->
                    AlertCard(
                        alert = alert,
                        onClick = { onAlertClick(alert.alertId) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = chrome.horizontalPaddingDp.dp)
                .padding(top = statusTopPadding + 12.dp)
        ) {
            ConnectionBanner(
                state = wsState,
                onlineCount = onlineDeviceCount,
                isCheckingUpdate = isCheckingUpdate,
                onClick = {
                    if (!isCheckingUpdate) {
                        isCheckingUpdate = true
                        scope.launch {
                            when (val result = AutoUpdater.checkUpdateResult()) {
                                is UpdateCheckResult.Available -> {
                                    pendingUpdateDialog = PendingUpdateDialog(
                                        model = buildUpdateDialogModel(
                                            latestVersion = result.info.version,
                                            currentVersion = AppConstants.VERSION
                                        ),
                                        updateInfo = result.info
                                    )
                                }
                                UpdateCheckResult.NoUpdate -> {
                                    pendingUpdateDialog = PendingUpdateDialog(
                                        model = buildNoUpdateDialogModel(AppConstants.VERSION)
                                    )
                                }
                                UpdateCheckResult.Failed -> {
                                    pendingUpdateDialog = PendingUpdateDialog(
                                        model = buildUpdateFailedDialogModel(AppConstants.VERSION)
                                    )
                                }
                            }
                            isCheckingUpdate = false
                        }
                    }
                }
            )
        }
    }

    pendingUpdateDialog?.let { dialog ->
        ReceiverUpdateDialog(
            model = dialog.model,
            onConfirm = {
                pendingUpdateDialog = null
                dialog.updateInfo?.let { info ->
                    AutoUpdater.downloadApk(context, info.downloadUrl, info.version)
                }
            },
            onDismiss = { pendingUpdateDialog = null }
        )
    }
}

@Composable
private fun ReceiverUpdateDialog(
    model: UpdateDialogUiModel,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = ReceiverSurface,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = ReceiverPrimarySoft,
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = updateDialogIcon(model.tone),
                                contentDescription = null,
                                tint = ReceiverPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ReceiverPrimary,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.weight(1f)
                    )
                    model.secondaryActionLabel?.let { closeDescription ->
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = closeDescription,
                            tint = ReceiverMuted,
                            modifier = Modifier
                                .size(32.dp)
                                .clickable(onClick = onDismiss)
                                .padding(4.dp)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    VersionPill(text = model.currentVersionLabel)
                    VersionPill(text = model.latestVersionLabel)
                }

                Text(
                    text = model.message,
                    style = MaterialTheme.typography.labelLarge,
                    color = ReceiverMuted,
                    textAlign = TextAlign.Start
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    model.secondaryActionLabel?.let { secondaryLabel ->
                        DialogActionPill(
                            text = secondaryLabel,
                            selected = false,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    DialogActionPill(
                        text = model.primaryActionLabel,
                        selected = true,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun updateDialogIcon(tone: UpdateDialogTone): ImageVector =
    when (tone) {
        UpdateDialogTone.AVAILABLE -> Icons.Default.SystemUpdateAlt
        UpdateDialogTone.CURRENT -> Icons.Default.CheckCircleOutline
        UpdateDialogTone.FAILED -> Icons.Default.ErrorOutline
    }

@Composable
private fun VersionPill(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = ReceiverSurfaceMuted,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun DialogActionPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) ReceiverPrimary else ReceiverSurfaceMuted
    val contentColor = if (selected) Color.White else ReceiverPrimary

    Surface(
        modifier = modifier
            .height(52.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = containerColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Black
            )
        }
    }
}

@Composable
private fun EmptyAlertState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = ReceiverSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = null,
                tint = ReceiverMuted,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无报警记录",
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "连接保持后，新报警会自动出现在这里",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted
            )
        }
    }
}

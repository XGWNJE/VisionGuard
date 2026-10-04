package com.xgwnje.visionguard.receiver.ui.screen

import com.xgwnje.visionguard.icons.LucideIcons

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard.receiver.AppConstants
import com.xgwnje.visionguard.receiver.service.AlertForegroundService
import com.xgwnje.visionguard.receiver.ui.component.AlertCard
import com.xgwnje.visionguard.receiver.ui.component.ConnectionBanner
import com.xgwnje.visionguard.receiver.ui.home.buildAlertListChrome
import com.xgwnje.visionguard.receiver.ui.home.buildAlertSourceOptions
import com.xgwnje.visionguard.receiver.ui.home.filterAlertsBySource
import com.xgwnje.visionguard.receiver.ui.home.buildNoUpdateDialogModel
import com.xgwnje.visionguard.receiver.ui.home.buildUpdateDialogModel
import com.xgwnje.visionguard.receiver.ui.home.buildUpdateFailedDialogModel
import com.xgwnje.visionguard.receiver.ui.home.UpdateDialogUiModel
import com.xgwnje.visionguard.receiver.ui.home.UpdateDialogTone
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimaryText
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverInk
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimarySoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOutline
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOnPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurface
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurfaceMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlert
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard.receiver.ui.viewmodel.AlertViewModel
import com.xgwnje.visionguard.receiver.util.AutoUpdater
import com.xgwnje.visionguard.receiver.util.UpdateCheckResult
import com.xgwnje.visionguard.receiver.util.UpdateInfo
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
    val mutedSources by alertVm.mutedAlertSources.collectAsState()
    val devices by service.devices.collectAsState()
    val wsState by service.connectionState.collectAsState()
    var selectedSourceKey by remember { mutableStateOf<String?>(null) }
    val sourceOptions = remember(alerts) { buildAlertSourceOptions(alerts) }
    val validAlerts = remember(alerts, selectedSourceKey) {
        filterAlertsBySource(alerts.filter { it.alertId.isNotEmpty() }, selectedSourceKey)
    }
    val onlineDeviceCount = remember(devices) { devices.count { it.online } }
    var pendingUpdateDialog by remember { mutableStateOf<PendingUpdateDialog?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val chrome = remember { buildAlertListChrome() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = chrome.horizontalPaddingDp.dp)
                .padding(top = chrome.topPaddingDp.dp),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = chrome.bottomOverlayReservedDp.dp
            ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
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
                Spacer(modifier = Modifier.height(12.dp))
            }
            if (sourceOptions.isNotEmpty()) {
                item {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedSourceKey == null,
                                onClick = { selectedSourceKey = null },
                                label = { Text("全部来源") },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp),
                                shape = MaterialTheme.shapes.small
                            )
                        }
                        items(sourceOptions, key = { it.key }) { source ->
                            val muted = source.key in mutedSources
                            FilterChip(
                                selected = selectedSourceKey == source.key,
                                onClick = { selectedSourceKey = source.key },
                                label = { Text(source.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp),
                                shape = MaterialTheme.shapes.small,
                                trailingIcon = {
                                    val actionLabel = if (muted) "取消静音" else "静音此来源"
                                    IconButton(
                                        onClick = { alertVm.setSourceMuted(source.key, !muted) },
                                        modifier = Modifier.size(48.dp).semantics { contentDescription = actionLabel }
                                    ) {
                                        Icon(
                                            imageVector = if (muted) LucideIcons.BellOff else LucideIcons.Bell,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
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
            shape = MaterialTheme.shapes.large,
            color = ReceiverSurface,
            border = BorderStroke(1.dp, ReceiverOutline),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (model.tone == UpdateDialogTone.FAILED) ReceiverAlertSoft else ReceiverPrimarySoft,
                        border = BorderStroke(1.dp, ReceiverOutline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = updateDialogIcon(model.tone),
                                contentDescription = null,
                                tint = if (model.tone == UpdateDialogTone.FAILED) ReceiverAlert else ReceiverPrimaryText,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = model.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ReceiverInk,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    model.secondaryActionLabel?.let { closeDescription ->
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = closeDescription }
                        ) {
                            Icon(LucideIcons.X, contentDescription = null, tint = ReceiverMuted, modifier = Modifier.size(24.dp))
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        UpdateDialogTone.AVAILABLE -> LucideIcons.Download
        UpdateDialogTone.CURRENT -> LucideIcons.CircleCheck
        UpdateDialogTone.FAILED -> LucideIcons.CircleAlert
    }

@Composable
private fun VersionPill(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ReceiverSurfaceMuted,
        border = BorderStroke(1.dp, ReceiverOutline)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverInk,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
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
    val contentColor = if (selected) ReceiverOnPrimary else ReceiverInk

    val shape = MaterialTheme.shapes.small

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = text, onClick = onClick),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, ReceiverOutline)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
private fun EmptyAlertState() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 188.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ReceiverSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReceiverOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = LucideIcons.BellOff,
                contentDescription = null,
                tint = ReceiverMuted,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "暂无报警记录",
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverInk
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

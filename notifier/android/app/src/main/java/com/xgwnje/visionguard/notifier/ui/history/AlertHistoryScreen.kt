package com.xgwnje.visionguard.notifier.ui.history

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.notifier.AlertEndType
import com.xgwnje.visionguard.notifier.AlertRecord
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModel
import com.xgwnje.visionguard.notifier.ui.settings.SettingsViewModelFactory

/** 报警结束后的真实记录；清空仅删除历史，不影响活动报警。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AlertHistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    val historyVersion by viewModel.alertHistoryVersion
    val records = remember(historyVersion) { viewModel.getAlertHistory() }
    var showClearConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "page-header") {
            NotifierPageHeader("报警记录", onNavigateBack) {
                TextButton(
                    onClick = { showClearConfirm = true },
                    enabled = records.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("清空") }
            }
        }
        item(key = "record-count") {
            Text("${records.size} 条记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (records.isEmpty()) {
            item(key = "empty-state") {
                NotifierPanel {
                    NotifierEmptyState("暂无报警记录", "报警手动确认、自动结束或播放失败后，会在这里保存记录。")
                }
            }
        } else {
            items(records, key = { "record:${it.id}" }) { record ->
                AlertRecordRow(record, modifier = Modifier.animateItemPlacement())
            }
        }
    }

    if (showClearConfirm) NotifierDialog(
        title = "清空报警记录",
        onDismiss = { showClearConfirm = false },
        focusOnDismiss = true,
        confirmButton = {
            TextButton(
                onClick = { viewModel.clearAlertHistory(); showClearConfirm = false },
                modifier = Modifier.heightIn(min = 48.dp).semantics { traversalIndex = 1f },
                shape = MaterialTheme.shapes.small,
                colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("清空") }
        },
        dismissButton = { TextButton({ showClearConfirm = false }, Modifier.heightIn(min = 48.dp).semantics { traversalIndex = 0f }, shape = MaterialTheme.shapes.small) { Text("取消") } }
    ) {
        Text("确认清空全部 ${records.size} 条报警记录？此操作不可撤销。", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AlertRecordRow(record: AlertRecord, modifier: Modifier = Modifier) {
    val timeZone = rememberAlarmTimeZone()
    val timeText = remember(record.timestamp, timeZone) { formatAlarmTime(record.timestamp, "yyyy-MM-dd HH:mm:ss", timeZone) }
    NotifierPanel(modifier) {
        Text(record.keyword, style = MaterialTheme.typography.titleSmall)
        val failed = record.endType == AlertEndType.ERROR
        NotifierStatus(
            label = when (record.endType) {
                AlertEndType.AUTO -> "自动结束"
                AlertEndType.MANUAL -> "手动确认"
                AlertEndType.ERROR -> "播放失败"
            },
            container = if (failed) MaterialTheme.colorScheme.errorContainer else VisionGuardStatusColors.successContainer,
            content = if (failed) MaterialTheme.colorScheme.onErrorContainer else VisionGuardStatusColors.onSuccessContainer
        )
        Text(record.sourceApp ?: "未知来源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(timeText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

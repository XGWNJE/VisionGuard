package com.xgwnje.visionguard.notifier.ui.dialogs

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.xgwnje.visionguard.notifier.node.alarmTimeStandardLabel
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.ui.NotifierStatus
import com.xgwnje.visionguard.notifier.ui.rememberAlarmTimeZone

/** 强提醒保持不可从外部或返回键关闭；停止动作仍由调用方确认并保存。 */
@Composable
fun AlarmDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    matchedKeyword: String?,
    sourceApp: String? = null,
    snippet: String? = null,
    eventTimeMillis: Long? = null,
    confirmationError: String? = null
) {
    val timeZone = rememberAlarmTimeZone()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
            // Honor the actual window bounds; full width is set on the window below.
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(dialogWindow) {
            dialogWindow?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && dialogWindow != null) {
                val lp = dialogWindow.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                dialogWindow.attributes = lp
            }
        }
        Box(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    NotifierStatus("节点报警", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
                    Text(matchedKeyword ?: "未知报警", style = MaterialTheme.typography.headlineMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AlertMeta("来源", sourceApp?.takeIf { it.isNotBlank() } ?: "未知来源")
                    AlertMeta("内容", snippet?.takeIf { it.isNotBlank() } ?: "无附加内容")
                    AlertMeta("时间", formatAlarmTime(eventTimeMillis ?: System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss", timeZone))
                    AlertMeta("时间标准", alarmTimeStandardLabel(timeZone))
                    confirmationError?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    }
                    Button(onConfirm, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                        Text("已知晓，停止报警")
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertMeta(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

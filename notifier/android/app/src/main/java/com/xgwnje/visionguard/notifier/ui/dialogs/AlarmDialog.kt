package com.xgwnje.visionguard.notifier.ui.dialogs

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.xgwnje.visionguard.notifier.node.alarmTimeStandardLabel
import com.xgwnje.visionguard.notifier.node.formatAlarmTime
import com.xgwnje.visionguard.notifier.DetectedObject
import com.xgwnje.visionguard.notifier.ui.NotifierDialog
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
    confirmationError: String? = null,
    detectedObject: DetectedObject? = null
) {
    var detailsOpen by remember(matchedKeyword, eventTimeMillis) { mutableStateOf(false) }
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
        AlarmSurface(onConfirm, { detailsOpen = true }, matchedKeyword, detectedObject, confirmationError)
        if (detailsOpen) AlarmDetails(matchedKeyword, detectedObject, sourceApp, snippet, eventTimeMillis) { detailsOpen = false }
    }
}

@Composable
internal fun AlarmDetails(matchedKeyword: String?, detectedObject: DetectedObject?, sourceApp: String?,
                         snippet: String?, eventTimeMillis: Long?, onClose: () -> Unit) {
    val timeZone = rememberAlarmTimeZone()
    NotifierDialog("告警详情", onClose,
        confirmButton = { TextButton(onClose, Modifier.heightIn(min = 48.dp)) { Text("关闭") } }) {
        AlertMeta("事件", matchedKeyword ?: "未知报警")
        detectedObject?.let {
            AlertMeta("目标", it.displayName)
            AlertMeta("置信度", "${kotlin.math.round(it.confidence * 1000) / 10}%")
        }
        AlertMeta("来源", sourceApp?.takeIf { it.isNotBlank() } ?: "未知来源")
        AlertMeta("内容", snippet?.takeIf { it.isNotBlank() } ?: "无附加内容")
        AlertMeta("时间", formatAlarmTime(eventTimeMillis ?: System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss", timeZone))
        AlertMeta("时间标准", alarmTimeStandardLabel(timeZone))
    }
}

@Composable
private fun AlertMeta(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

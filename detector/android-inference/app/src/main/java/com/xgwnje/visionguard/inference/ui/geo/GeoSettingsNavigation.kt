package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.inference.runtime.ModelState
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import kotlinx.coroutines.launch

/** 自然宽度导航，不把长标题压进等宽窄格。 */
@Composable
internal fun GeoPageNavigation(options: List<Pair<String, String>>, value: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, title) ->
            GeoKey(onClick = { onSelect(key) }, label = title, tone = if (key == value) GeoTone.Primary else GeoTone.Secondary)
        }
    }
}

@Composable
internal fun GeoUnsavedChanges(canSave: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onContinue: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        title = { Text("保留修改？") },
        text = { Text(if (canSave) "检测规则或遮罩尚未保存。" else "尚有未保存修改；请先停止来源并完成有效配置后保存。") },
        confirmButton = { TextButton(onClick = onSave, enabled = canSave) { Text("保存并离开") } },
        dismissButton = { Row { TextButton(onClick = onDiscard) { Text("放弃修改") }; TextButton(onClick = onContinue) { Text("继续编辑") } } }
    )
}

/** 全局模型文件入口；不改变现有模型身份和下载机制。 */
@Composable
internal fun GeoModelResources(
    models: List<ModelState>, downloads: Map<String, ModelDownloadState>, used: Set<String>, gpuAvailable: Boolean,
    onDownload: suspend (String) -> String?, onDelete: suspend (String) -> String?, onMessage: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<String?>(null) }
    GeoSectionLabel(null, "模型资源", "已下载 ${models.count { it.downloaded }} / ${models.size}")
    Text("下载与删除在此管理，检测规则中选择已下载模型。", style = MaterialTheme.typography.bodySmall)
    models.forEach { model ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.displayName, style = MaterialTheme.typography.titleMedium)
            val progress = downloads[model.key]
            Text(if (model.key in used) "使用中，不能删除" else if (model.downloaded) "已下载" else "未下载", style = MaterialTheme.typography.bodySmall)
            // 进度对象的现有文案在下载状态变化时直接展示，下载逻辑仍由运行时负责。
            if (progress != null) Text(if (progress.total > 0) "下载 ${progress.percent}%" else "下载中…", style = MaterialTheme.typography.bodySmall)
            if (model.downloaded) GeoKey(onClick = { deleting = model.key }, label = "删除模型", tone = GeoTone.Danger, enabled = model.key !in used)
            else GeoKey(onClick = { scope.launch { onDownload(model.key)?.let(onMessage) } }, label = if (progress != null) "下载中…" else "下载模型", enabled = progress == null)
        }
        GeoDivider()
    }
    deleting?.let { key -> GeoConfirm(title = "删除模型", text = "删除该模型的本机文件？下次使用需要重新下载，账号、配置与遮罩保留。", confirmLabel = "删除", onCancel = { deleting = null }, onConfirm = { deleting = null; scope.launch { onDelete(key)?.let(onMessage) } }) }
}

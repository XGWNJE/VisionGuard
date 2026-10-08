package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.model.ModelKeys
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import com.xgwnje.visionguard.inference.runtime.ModelState
import kotlinx.coroutines.launch

/** 参数取值范围的界面提示。 */
object DetectionParamsRange {
    val CONFIDENCE = "${DetectionParams.MIN_CONFIDENCE_PERCENT}–${DetectionParams.MAX_CONFIDENCE_PERCENT} 整数百分比"
    val FPS = "${DetectionParams.MIN_TARGET_FPS}–${DetectionParams.MAX_TARGET_FPS} 帧/秒"
    val COOLDOWN = "${DetectionParams.MIN_COOLDOWN_SECONDS}–${DetectionParams.MAX_COOLDOWN_SECONDS} 秒"
}

private val cooldownPresets = listOf(5, 10, 30, 60)

/**
 * 参数编辑器：整组参数先落成草稿，校验通过才调用 `updateParams`。
 *
 * 模型只能选已下载的；未下载的模型在这里提供下载入口，不写入配置。
 */
@Composable
fun ParameterEditorDialog(
    sourceName: String,
    initial: DetectionParams,
    running: Boolean,
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    onDismiss: () -> Unit,
    onDownloadModel: suspend (String) -> String?,
    onSave: (DetectionParams) -> String?,
    onMessage: (String?) -> Unit
) {
    var confidence by remember(initial) { mutableStateOf(initial.confidencePercent) }
    var targetFps by remember(initial) { mutableStateOf(initial.targetFps) }
    var cooldown by remember(initial) { mutableStateOf(initial.cooldownSeconds) }
    var modelKey by remember(initial) { mutableStateOf(initial.modelKey) }
    var watched by remember(initial) { mutableStateOf(initial.watchedClasses) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val confidenceError = DetectionParams.validateConfidence(confidence)
    val fpsError = DetectionParams.validateTargetFps(targetFps)
    val cooldownError = DetectionParams.validateCooldown(cooldown)
    val downloaded = models.filter { it.downloaded }.map { it.key }
    val modelValid = downloaded.contains(modelKey)
    val targetsValid = watched.isNotEmpty() && watched.all { CocoClassMap.EnZh.containsKey(it) }
    val canSave = confidenceError == null && fpsError == null && cooldownError == null &&
        modelValid && targetsValid && watched.joinToString(",").length <= DetectionParams.MAX_TARGETS_TEXT_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("调整检测规则 · $sourceName", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
            ) {
                if (running) {
                    Text(
                        "该来源正在运行，保存会被拒绝；请先停止来源再保存参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = VisionGuardStatusColors.warning
                    )
                }

                ModelPicker(
                    models = models,
                    downloads = downloads,
                    selected = modelKey,
                    onSelect = { modelKey = it },
                    onDownload = { key -> scope.launch { onDownloadModel(key)?.let(onMessage) } }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text("检测目标（可多选）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "常用目标优先显示；也可以按中文或英文搜索全部 80 个 COCO 类。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)
                ) {
                    CocoClassMap.TargetClassNames.forEach { name ->
                        FilterChip(
                            selected = watched.contains(name),
                            onClick = {
                                watched = if (watched.contains(name)) watched - name else watched + name
                            },
                            label = { Text(CocoClassMap.displayName(name), maxLines = 1) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("搜索全部目标") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    colors = VisionGuardControlColors.outlinedField()
                )
                val query = search.trim()
                val candidates = if (query.isEmpty()) {
                    CocoClassMap.EnglishNames
                } else {
                    CocoClassMap.EnglishNames.filter { name ->
                        CocoClassMap.displayName(name).contains(query, ignoreCase = true) ||
                            name.contains(query, ignoreCase = true)
                    }
                }
                Text(
                    "已选 ${watched.size} 项 · 匹配 ${candidates.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    candidates.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = watched.contains(name),
                                onCheckedChange = { checked ->
                                    watched = if (checked) watched + name else watched - name
                                },
                                colors = VisionGuardControlColors.checkbox()
                            )
                            Text(
                                "${CocoClassMap.displayName(name)} · $name",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (candidates.isEmpty()) Text("没有匹配的目标。", style = MaterialTheme.typography.bodyMedium)
                }
                if (!targetsValid) Text("至少选择一个检测目标。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                SliderRow("采样频率", "$targetFps 帧/秒", DetectionParamsRange.FPS, fpsError) {
                    Slider(
                        value = targetFps.toFloat(),
                        onValueChange = { targetFps = it.toInt() },
                        valueRange = DetectionParams.MIN_TARGET_FPS.toFloat()..DetectionParams.MAX_TARGET_FPS.toFloat(),
                        steps = DetectionParams.MAX_TARGET_FPS - DetectionParams.MIN_TARGET_FPS - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SliderRow("置信度", "$confidence%", DetectionParamsRange.CONFIDENCE, confidenceError) {
                    Slider(
                        value = confidence.toFloat(),
                        onValueChange = { confidence = it.toInt() },
                        valueRange = DetectionParams.MIN_CONFIDENCE_PERCENT.toFloat()..DetectionParams.MAX_CONFIDENCE_PERCENT.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SliderRow("报警冷却", "$cooldown 秒", DetectionParamsRange.COOLDOWN, cooldownError) {
                    Slider(
                        value = cooldown.toFloat(),
                        onValueChange = { cooldown = it.toInt() },
                        valueRange = DetectionParams.MIN_COOLDOWN_SECONDS.toFloat()..DetectionParams.MAX_COOLDOWN_SECONDS.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
                    cooldownPresets.forEach { preset ->
                        OutlinedButton(
                            onClick = { cooldown = preset },
                            colors = VisionGuardControlColors.outlinedButton(),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("${preset}s") }
                    }
                }

                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val failure = onSave(
                        initial.copy(
                            confidencePercent = confidence,
                            targetFps = targetFps,
                            cooldownSeconds = cooldown,
                            modelKey = modelKey,
                            watchedClasses = watched
                        )
                    )
                    if (failure == null) onMessage("检测规则已保存，重新启动来源后生效") else error = failure
                },
                enabled = canSave,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = VisionGuardControlColors.textButton()
            ) { Text("保存参数") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        confidence = initial.confidencePercent
                        targetFps = initial.targetFps
                        cooldown = initial.cooldownSeconds
                        modelKey = initial.modelKey
                        watched = initial.watchedClasses
                        search = ""
                        error = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("还原当前值") }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("取消") }
            }
        }
    )
}

@Composable
private fun ModelPicker(
    models: List<ModelState>,
    downloads: Map<String, ModelDownloadState>,
    selected: String,
    onSelect: (String) -> Unit,
    onDownload: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Text("模型", style = MaterialTheme.typography.titleSmall)
    Box {
        OutlinedButton(
            onClick = { open = true },
            colors = VisionGuardControlColors.outlinedButton(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Text(
                "${ModelKeys.displayName(selected)}${if (models.firstOrNull { it.key == selected }?.downloaded == true) "" else "（未下载）"}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${model.displayName}${if (model.downloaded) " · 已下载" else " · 未下载"}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    enabled = model.downloaded,
                    onClick = {
                        onSelect(model.key)
                        open = false
                    }
                )
            }
        }
    }
    SecondaryText("只有已下载的模型可以被选中。模型按需从统一服务下载，不随安装包分发。")
    for (model in models) {
        val progress = downloads[model.key] ?: continue
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.weight(1f)
            )
            Text(
                if (progress.total > 0) "${progress.percent}%" else "下载中…",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
    val missing = models.filter { !it.downloaded && downloads[it.key] == null }
    if (missing.isNotEmpty()) {
        Text("未下载模型", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        missing.forEach { model ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(model.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onDownload(model.key) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("下载") }
            }
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    range: String,
    error: String?,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        content()
        Text(
            error ?: "取值范围：$range",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

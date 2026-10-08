package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.VisionGuardControlColors
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.inference.detect.CocoClassMap
import com.xgwnje.visionguard.inference.model.ModelKeys
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceConfig
import com.xgwnje.visionguard.inference.source.SourceView
import kotlinx.coroutines.launch

/**
 * 来源检查区，顺序与 Windows 端一致：
 * 当前来源 → 运行状态与启停 → 检测规则 → 采集范围与遮罩 → 来源管理。
 */
@Composable
fun SourceInspectionPane(
    source: SourceView?,
    nowWallMs: Long,
    nowElapsedMs: Long,
    onRename: (String) -> String?,
    onToggle: suspend () -> String?,
    onSetLens: (Int) -> String?,
    onDelete: suspend () -> String?,
    onMessage: (String?) -> Unit,
    onEditParams: () -> Unit,
    onEditMasks: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (source == null) {
        Box(modifier.fillMaxWidth().padding(UiTokens.CardPadding)) {
            Text("请先选择一个来源。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
    ) {
        CurrentSourceSection(source, onRename, onMessage)
        RuntimeSection(source, nowWallMs, nowElapsedMs, onToggle, onMessage)
        RuleSection(source, onEditParams)
        CaptureSection(source, onSetLens, onMessage, onEditMasks)
        ManageSection(source, onDelete, onMessage)
    }
}

@Composable
private fun CurrentSourceSection(source: SourceView, onRename: (String) -> String?, onMessage: (String?) -> Unit) {
    var draft by remember(source.sourceId) { mutableStateOf(source.sourceName) }
    var error by remember(source.sourceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(source.sourceName) { if (draft == source.sourceName) error = null }

    SectionCard {
        SectionTitle("当前来源")
        InfoRow("来源身份", source.sourceId)
        InfoRow("来源类型", modeText(source.mode))
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                error = SourceConfig.validateName(it)
            },
            label = { Text("来源名称") },
            supportingText = { Text("最多 ${SourceConfig.MAX_NAME_LENGTH} 个字符，修改后立即保存") },
            isError = error != null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            colors = VisionGuardControlColors.outlinedField()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val failure = onRename(draft)
                    if (failure == null) onMessage("来源名称已保存") else error = failure
                },
                enabled = error == null && SourceConfig.normalizeName(draft) != source.sourceName,
                colors = VisionGuardControlColors.button(),
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("保存名称") }
            TextButton(
                onClick = { draft = source.sourceName; error = null },
                enabled = draft != source.sourceName,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = VisionGuardControlColors.textButton()
            ) { Text("还原") }
        }
    }
}

@Composable
private fun RuntimeSection(
    source: SourceView,
    nowWallMs: Long,
    nowElapsedMs: Long,
    onToggle: suspend () -> String?,
    onMessage: (String?) -> Unit
) {
    var busy by remember(source.sourceId) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SectionCard {
        SectionTitle("运行状态")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(source.state.isRunning)
            Text(
                if (source.state.isRunning) "运行中" else if (source.state.monitoringExpected) "期望运行（未就绪）" else "已停止",
                style = MaterialTheme.typography.titleSmall
            )
        }
        InfoRow("状态文字", source.state.statusText)
        InfoRow("实际帧率", "${"%.2f".format(source.state.actualFps)} FPS / 目标 ${source.params.targetFps} FPS")
        InfoRow("推理后端", source.state.backend.ifBlank { "未开始" })
        InfoRow("推理耗时", if (source.state.inferenceMs > 0) "${source.state.inferenceMs} ms" else "无数据")
        InfoRow(
            "最后画面",
            "${formatElapsedMoment(source.state.lastProgressAtMs, nowWallMs, nowElapsedMs)}（${relativeAge(source.state.lastProgressAtMs, nowElapsedMs)}）"
        )
        InfoRow("最后报警", formatElapsedMoment(source.state.lastAlertAtMs, nowWallMs, nowElapsedMs))
        InfoRow("处理帧数", "成功 ${source.state.processedFrames} · 失败 ${source.state.failedFrames}")
        source.state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VisionGuardStatusColors.error) }
        source.state.performanceWarning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = VisionGuardStatusColors.warning)
        }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    try {
                        onToggle()?.let(onMessage)
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            colors = if (source.state.isRunning) {
                VisionGuardControlColors.button(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                VisionGuardControlColors.button()
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text(if (busy) "处理中…" else toggleLabel(source.state.isRunning)) }
        SecondaryText("参数是冷改动：运行中的来源不能改参数或遮罩，需要先停止。")
    }
}

@Composable
private fun RuleSection(source: SourceView, onEditParams: () -> Unit) {
    SectionCard {
        SectionTitle("检测规则", action = { RowAction("调整规则", onClick = onEditParams) })
        InfoRow("模型", ModelKeys.displayName(source.params.modelKey), labelWidth = 64)
        InfoRow(
            "检测目标",
            source.params.watchedClasses.joinToString("、") { CocoClassMap.displayName(it) }.ifBlank { "未选择" },
            labelWidth = 64
        )
        InfoRow("置信度", "${source.params.confidencePercent}%（${DetectionParamsRange.CONFIDENCE}）", labelWidth = 64)
        InfoRow("采样频率", "${source.params.targetFps} 帧/秒（${DetectionParamsRange.FPS}）", labelWidth = 64)
        InfoRow("报警冷却", "${source.params.cooldownSeconds} 秒（${DetectionParamsRange.COOLDOWN}）", labelWidth = 64)
        Text(
            "参数改动在下次启动该来源时生效，控制台也可以远程改这五项。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CaptureSection(
    source: SourceView,
    onSetLens: (Int) -> String?,
    onMessage: (String?) -> Unit,
    onEditMasks: () -> Unit
) {
    SectionCard {
        SectionTitle("采集范围", action = { RowAction("编辑遮罩", onClick = onEditMasks) })
        if (source.mode == SourceCaptureMode.LocalCamera) {
            InfoRow("镜头", if (source.cameraLensFacing == SourceConfig.LENS_FRONT) "前置" else "后置")
            Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
                OutlinedButton(
                    onClick = { onSetLens(SourceConfig.LENS_BACK)?.let(onMessage) },
                    colors = VisionGuardControlColors.outlinedButton(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("后置") }
                OutlinedButton(
                    onClick = { onSetLens(SourceConfig.LENS_FRONT)?.let(onMessage) },
                    colors = VisionGuardControlColors.outlinedButton(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("前置") }
            }
            InfoRow(
                "相机状态",
                buildString {
                    append(if (source.state.isRunning) "采集中" else "已停止")
                    append(" · ${source.state.preview?.width ?: 0}×${source.state.preview?.height ?: 0}")
                },
                labelWidth = 64
            )
            SecondaryText("切换镜头需要先停止该来源，运行中会被拒绝。")
        } else {
            InfoRow("推流设备", source.remotePublisherName?.ifBlank { null } ?: "控制台未下发设备名", labelWidth = 64)
            SecondaryText("远程镜头由 Web 控制台绑定相机推流节点后自动入列，本机不主动添加或删除绑定。")
        }
        InfoRow("遮罩数量", "${source.masks.size} 个", labelWidth = 64)
        SecondaryText("遮罩只保存在本机来源配置里，不通过 set-config 远程同步。")
    }
}

@Composable
private fun ManageSection(source: SourceView, onDelete: suspend () -> String?, onMessage: (String?) -> Unit) {
    var confirming by remember(source.sourceId) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    SectionCard {
        SectionTitle("来源管理")
        SecondaryText("删除会同时移除该来源的本机参数与遮罩；远程绑定由控制台恢复。")
        OutlinedButton(
            onClick = { confirming = true },
            enabled = !source.state.isRunning,
            colors = VisionGuardControlColors.outlinedButton(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) { Text("删除来源") }
        if (source.state.isRunning) SecondaryText("运行中的来源不能删除，请先停止。")
    }

    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            shape = MaterialTheme.shapes.large,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("删除来源", style = MaterialTheme.typography.titleMedium) },
            text = { Text("确认删除“${source.sourceName}”？该来源的参数与遮罩会一并移除。", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        scope.launch { onDelete()?.let(onMessage) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = false },
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = VisionGuardControlColors.textButton()
                ) { Text("取消") }
            }
        )
    }
}

package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView
import com.xgwnje.visionguard.inference.ui.formatElapsedMoment
import com.xgwnje.visionguard.inference.ui.formatWallMoment

/** 来源检查：身份、状态分层、名称槽、镜头拨档与删除闸门。 */
@Composable
fun SourceInspectDrawer(
    source: SourceView?,
    nowWallMs: Long,
    nowElapsedMs: Long,
    onRename: (String) -> String?,
    onSetLens: (Int) -> String?,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    onMessage: (String?) -> Unit
) {
    if (source == null) return
    var name by remember(source.sourceId, source.sourceName) { mutableStateOf(source.sourceName) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = source.state.isRunning
    GeoDrawer(title = "来源检查 · ${source.sourceName}", sectionId = "VISION / SOURCE", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GeoInfoRow("来源身份", source.sourceId)
            GeoInfoRow("类型", if (source.mode == SourceCaptureMode.LocalCamera) "本机相机" else "远程镜头")
            GeoInfoRow("运行状态", source.state.statusText)
            GeoInfoRow("推理后端", source.state.backend.ifEmpty { "未报告" })
            GeoInfoRow("实际帧率", "%.1f FPS".format(source.state.actualFps))
            GeoInfoRow("单帧耗时", "${source.state.inferenceMs} ms")
            GeoInfoRow("最后画面", formatElapsedMoment(source.state.lastProgressAtMs, nowWallMs, nowElapsedMs))
            GeoInfoRow("最后报警", formatWallMoment(source.state.lastAlertAtMs))
            GeoInfoRow("处理帧数", "${source.state.processedFrames} 成功 / ${source.state.failedFrames} 失败")
            source.remotePublisherName?.let { GeoInfoRow("推流来源", it) }
        }

        GeoDivider()
        GeoSectionLabel("01", "来源名称", "${name.length}/64")
        GeoFlatTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "来源名称",
            enabled = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            GeoKey(
                onClick = {
                    val failure = onRename(name)
                    onMessage(failure ?: "来源名称已保存")
                },
                label = "保存名称",
                icon = LucideIcons.Check
            )
            GeoKey(
                onClick = { name = source.sourceName },
                label = "还原",
                icon = LucideIcons.RotateCcw,
                enabled = name != source.sourceName
            )
        }

        if (source.mode == SourceCaptureMode.LocalCamera) {
            GeoDivider()
            GeoSectionLabel("02", "镜头", if (running) "运行中不可切换" else "前 / 后置")
            GeoShifter(
                options = listOf("1" to "前置", "0" to "后置"),
                value = source.cameraLensFacing.toString(),
                onSelect = { selected ->
                    onSetLens(selected.toInt())?.let(onMessage)
                },
                enabled = !running
            )
        }

        GeoDivider()
        GeoSectionLabel("03", "维护", "二次确认")
        GeoHint(
            if (running) "先停止来源，再删除。删除会同时移除本机参数与遮罩。" else "删除会同时移除本机参数与遮罩；远程绑定可由控制台恢复。",
            if (running) GeoLampTone.Warning else GeoLampTone.Neutral
        )
        GeoKey(
            onClick = { confirmDelete = true },
            label = "删除来源",
            icon = LucideIcons.Trash2,
            tone = GeoTone.Danger,
            enabled = !running
        )
        Spacer(Modifier.height(Geo.Gap))
    }

    if (confirmDelete) {
        GeoConfirm(
            title = "删除来源",
            text = "将删除「${source.sourceName}」及其本机参数与遮罩，远程绑定可由控制台恢复。",
            confirmLabel = "删除",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

/** 添加来源：本机相机与远程槽位二选一，达到上限时拒绝。 */
@Composable
fun AddSourceDrawer(
    sources: List<SourceView>,
    maxSources: Int,
    localLimit: Int,
    onAddLocal: () -> String?,
    onAddRemote: () -> String?,
    onClose: () -> Unit,
    onMessage: (String?) -> Unit
) {
    var type by remember { mutableStateOf(if (sources.any { it.mode == SourceCaptureMode.LocalCamera }) "remote" else "local") }
    val hasLocal = sources.any { it.mode == SourceCaptureMode.LocalCamera }
    val full = sources.size >= maxSources
    val localFull = hasLocal
    val canAdd = !full && !(type == "local" && localFull)
    GeoDrawer(
        title = "添加来源",
        sectionId = "VISION / SOURCE",
        onClose = onClose,
        width = Geo.Drawer,
        footer = {
            GeoKey(onClick = onClose, label = "取消")
            GeoKey(
                onClick = {
                    val failure = if (type == "local") onAddLocal() else onAddRemote()
                    if (failure == null) onClose() else onMessage(failure)
                },
                label = "添加来源",
                icon = LucideIcons.Plus,
                tone = GeoTone.Primary,
                enabled = canAdd
            )
        }
    ) {
        GeoSectionLabel("01", "来源类型", "二选一")
        GeoShifter(
            options = listOf("local" to "本机相机", "remote" to "远程槽位"),
            value = type,
            onSelect = { type = it },
            enabled = !full
        )
        GeoHint(
            when {
                type == "local" && localFull -> "本机相机已存在；固定身份 camera 只能一条。"
                type == "local" -> "用本机镜头采集与推理，仍需本机相机权限。"
                else -> "这里只创建槽位；相机推流节点需要在控制台绑定。"
            },
            if (type == "local" && localFull) GeoLampTone.Warning else GeoLampTone.Neutral
        )
        GeoSectionLabel("02", "用量", "1-$maxSources")
        GeoInfoRow("已有来源", "${sources.size} / $maxSources")
        GeoInfoRow("本机相机", if (hasLocal) "1 / 1（已占用）" else "0 / 1")
        GeoInfoRow("本机相机上限", "$localLimit 路")
        if (full) GeoHint("已达到服务端来源上限 $maxSources 路。", GeoLampTone.Warning)
        Spacer(Modifier.height(Geo.Gap))
    }
}

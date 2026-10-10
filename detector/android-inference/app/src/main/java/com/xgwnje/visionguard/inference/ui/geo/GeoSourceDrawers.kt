package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceView

/**
 * 添加来源：本机只添加本机相机。
 *
 * 远程来源不再由本节点自行创建槽位：相机推流接入后按绑定出现，或在控制台设定后下发到本机。
 */
@Composable
fun AddSourceDrawer(
    sources: List<SourceView>,
    maxSources: Int,
    localLimit: Int,
    onAddLocal: () -> String?,
    onClose: () -> Unit,
    onMessage: (String?) -> Unit
) {
    val hasLocal = sources.any { it.mode == SourceCaptureMode.LocalCamera }
    val full = sources.size >= maxSources
    val canAdd = !full && !hasLocal
    GeoDrawer(
        title = "添加本机来源",
        sectionId = "VISION / SOURCE",
        onClose = onClose,
        width = Geo.Drawer,
        footer = {
            GeoKey(onClick = onClose, label = "取消")
            GeoKey(
                onClick = {
                    val failure = onAddLocal()
                    if (failure == null) onClose() else onMessage(failure)
                },
                label = "添加本机相机",
                icon = LucideIcons.Plus,
                tone = GeoTone.Primary,
                enabled = canAdd
            )
        }
    ) {
        GeoSectionLabel("01", "本机相机", if (hasLocal) "已存在" else "可添加")
        GeoHint(
            when {
                hasLocal -> "本机相机固定一条，已在来源列表中。"
                full -> "已达服务端来源上限 $maxSources 路，先移除其他来源。"
                else -> "使用本机镜头采集与推理，需要相机权限；本机相机上限 $localLimit 路。"
            },
            if (hasLocal || full) GeoLampTone.Warning else GeoLampTone.Neutral
        )
        GeoSectionLabel("02", "用量", "1-$maxSources")
        GeoInfoRow("已有来源", "${sources.size} / $maxSources")
        GeoInfoRow("本机相机", if (hasLocal) "1 / $localLimit（已占用）" else "0 / $localLimit")
        GeoInfoRow("远程来源", "${sources.count { it.mode != SourceCaptureMode.LocalCamera }} 路")
        GeoSectionLabel("03", "远程来源", "推流或控制台设定")
        Text(
            "本节点不自行创建远程槽位：相机推流接入后按绑定出现在来源列表，或在控制台为节点设定后下发；绑定与解绑都在控制台完成。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(Geo.Gap))
    }
}

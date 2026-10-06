package com.xgwnje.visionguard.receiver.ui.component

import com.xgwnje.visionguard.icons.LucideIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.xgwnje.visionguard.account.VisionGuardControlColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard.receiver.data.model.DeviceConfig
import com.xgwnje.visionguard.receiver.data.model.DeviceInfo
import com.xgwnje.visionguard.receiver.data.model.targetEnZhPairs
import com.xgwnje.visionguard.receiver.ui.home.DeviceCardChrome
import com.xgwnje.visionguard.receiver.ui.home.DeviceCardUiModel
import com.xgwnje.visionguard.receiver.ui.home.DeviceStatusTone
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceCardChrome
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceCardUiModel
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceConfigChanges
import com.xgwnje.visionguard.receiver.ui.home.buildDeviceConfigEditorUiModel
import com.xgwnje.visionguard.receiver.ui.home.CooldownOptions
import com.xgwnje.visionguard.receiver.ui.home.cooldownLabel
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlert
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAmber
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAmberSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimaryText
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverInk
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimarySoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOutline
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOnPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurface
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurfaceMuted
import kotlin.math.roundToInt

@Composable
fun DeviceCard(
    device: DeviceInfo,
    initialConfig: DeviceConfig?,
    onCommand: (String, String?) -> Unit,
    onSetConfig: (key: String, value: String, sourceId: String?) -> Unit,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    val model = remember(device) { buildDeviceCardUiModel(device) }
    val chrome = remember { buildDeviceCardChrome() }
    var showConfigEditor by remember { mutableStateOf(false) }
    var configSourceId by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(chrome.cardCornerRadiusDp.dp),
        color = ReceiverSurface,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DeviceCardHero(
                model = model,
                chrome = chrome,
                dragHandleModifier = dragHandleModifier
            )
            if (model.showLegacyControls || model.detectorLifecycleCommand != null) {
                DeviceCardActions(
                    model = model,
                    chrome = chrome,
                    onCommand = { command -> onCommand(command, null) },
                    onConfigClick = { configSourceId = null; showConfigEditor = true }
                )
            }
            if (device.sourceLimitExceeded) {
                val limit = device.maxSources?.let { "最多 $it 路" } ?: "服务端上限"
                Text(
                    text = "来源数量超过服务端上限（$limit），超出部分未被上报；下面显示的是上一次成功上报的来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            if (device.sources.isNotEmpty()) {
                SourceControlList(
                    device = device,
                    onCommand = onCommand,
                    onConfig = { sourceId -> configSourceId = sourceId; showConfigEditor = true }
                )
            }
        }
    }

    if (showConfigEditor) {
        val source = device.sources.firstOrNull { it.sourceId == configSourceId }
        val deviceConfig = initialConfig ?: DeviceConfig()
        DeviceConfigBottomSheet(
            device = device,
            sourceId = configSourceId,
            initialConfig = source?.let {
                DeviceConfig(
                    it.cooldown ?: deviceConfig.cooldown,
                    it.confidence ?: deviceConfig.confidence,
                    it.targets ?: deviceConfig.targets,
                    it.targetSamplingRate ?: deviceConfig.targetSamplingRate,
                    it.modelKey.ifBlank { deviceConfig.modelKey }
                )
            } ?: deviceConfig,
            onSetConfig = { key, value -> onSetConfig(key, value, configSourceId) },
            onDismiss = { showConfigEditor = false }
        )
    }
}

@Composable
private fun DeviceCardHero(
    model: DeviceCardUiModel,
    chrome: DeviceCardChrome,
    dragHandleModifier: Modifier
) {
    val heroShape = RoundedCornerShape(
        topStart = chrome.cardCornerRadiusDp.dp,
        topEnd = chrome.cardCornerRadiusDp.dp
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = chrome.heroHeightDp.dp)
            .clip(heroShape)
            .background(ReceiverSurfaceMuted)
            .padding(horizontal = chrome.heroContentHorizontalPaddingDp.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = model.deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = ReceiverInk,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Text(model.typeLabel, style = MaterialTheme.typography.bodySmall, color = ReceiverMuted)
            DeviceStatusPill(
                label = model.statusLabel,
                tone = model.statusTone
            )
        }
        DeviceDragHandle(
            modifier = Modifier.padding(start = 12.dp),
            dragHandleModifier = dragHandleModifier
        )
    }
}

@Composable
private fun DeviceDragHandle(
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.small

    Surface(
        modifier = modifier
            .size(48.dp)
            .clip(shape)
            .then(dragHandleModifier),
        shape = shape,
        color = ReceiverSurface,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = LucideIcons.GripHorizontal,
                contentDescription = "拖拽排序",
                tint = ReceiverMuted,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun DeviceStatusPill(
    label: String,
    tone: DeviceStatusTone
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = statusContainer(tone),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusForeground(tone), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = statusForeground(tone),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeviceCardActions(
    model: DeviceCardUiModel,
    chrome: DeviceCardChrome,
    onCommand: (String) -> Unit,
    onConfigClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = chrome.actionAreaHorizontalPaddingDp.dp,
                vertical = chrome.actionAreaVerticalPaddingDp.dp
            ),
        verticalArrangement = Arrangement.spacedBy(chrome.columnGapDp.dp)
    ) {
        if (model.showLegacyControls) {
            Row(horizontalArrangement = Arrangement.spacedBy(chrome.columnGapDp.dp)) {
                DeviceActionButton(
                    label = model.controlActionLabel,
                    icon = if (model.controlCommand == "pause") LucideIcons.Pause else LucideIcons.Play,
                    enabled = model.controlsEnabled,
                    emphasized = model.controlCommand == "resume",
                    danger = model.controlCommand == "pause",
                    heightDp = chrome.actionButtonHeightDp,
                    contentHorizontalPaddingDp = chrome.actionContentHorizontalPaddingDp,
                    onClick = { onCommand(model.controlCommand) },
                    modifier = Modifier.weight(1f)
                )
                DeviceActionButton(
                    label = "参数调节",
                    icon = LucideIcons.SlidersHorizontal,
                    enabled = model.controlsEnabled,
                    emphasized = false,
                    danger = false,
                    heightDp = chrome.actionButtonHeightDp,
                    contentHorizontalPaddingDp = chrome.actionContentHorizontalPaddingDp,
                    onClick = onConfigClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // Windows 只剩一个检测端，因此这里只有一个生命周期入口。
        model.detectorLifecycleCommand?.let { command ->
            Row(horizontalArrangement = Arrangement.spacedBy(chrome.columnGapDp.dp)) {
                DeviceActionButton(
                    label = if (command.startsWith("open")) "打开视觉节点" else "关闭视觉节点",
                    icon = if (command.startsWith("open")) LucideIcons.Play else LucideIcons.Pause,
                    enabled = model.lifecycleControlsEnabled, emphasized = command.startsWith("open"), danger = false,
                    heightDp = chrome.actionButtonHeightDp, contentHorizontalPaddingDp = 8,
                    onClick = { onCommand(command) }, modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SourceControlList(
    device: DeviceInfo,
    onCommand: (String, String?) -> Unit,
    onConfig: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        device.sources.forEach { source ->
            Surface(
                shape = MaterialTheme.shapes.medium, color = ReceiverSurfaceMuted,
                border = BorderStroke(1.dp, ReceiverOutline)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = source.sourceName,
                            style = MaterialTheme.typography.titleSmall,
                            color = ReceiverInk,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        SourceIconAction(
                            icon = if (source.isMonitoring) LucideIcons.Pause else LucideIcons.Play,
                            label = if (source.isMonitoring) "停止${source.sourceName}" else "启动${source.sourceName}",
                            enabled = device.online && "source-control" in device.capabilities &&
                                source.isReady && source.error.isNullOrBlank(),
                            emphasized = !source.isMonitoring,
                            danger = source.isMonitoring,
                            onClick = { onCommand(if (source.isMonitoring) "pause" else "resume", source.sourceId) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        SourceIconAction(
                            icon = LucideIcons.SlidersHorizontal,
                            label = "调节${source.sourceName}参数",
                            // 检测端拒绝运行中修改来源配置。
                            enabled = device.online && "source-control" in device.capabilities &&
                                source.isReady && !source.isMonitoring,
                            emphasized = false,
                            danger = false,
                            onClick = { onConfig(source.sourceId) }
                        )
                    }
                    val status = when {
                        !device.online -> "节点离线 · 等待重新连接"
                        source.error?.isNotBlank() == true -> source.error
                        !source.isReady -> "目标暂不可用"
                        source.isMonitoring -> "检测中 · ${source.actualFps?.let { "%.1f FPS".format(it) } ?: "频率计算中"}"
                        else -> "已停止 · ${source.modelKey.ifBlank { "未选模型" }}"
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = when { !device.online -> ReceiverMuted; source.error?.isNotBlank() == true -> ReceiverAlert; !source.isReady -> ReceiverAmber; source.isMonitoring -> ReceiverPrimaryText; else -> ReceiverMuted },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (device.online && "source-control" in device.capabilities && source.isMonitoring) {
                        Text(
                            text = "停止此来源监控后可调节参数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReceiverMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    emphasized: Boolean,
    danger: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        !enabled -> ReceiverSurfaceMuted
        emphasized -> ReceiverPrimary
        danger -> ReceiverAlertSoft
        else -> ReceiverSurface
    }
    val contentColor = when {
        !enabled -> ReceiverMuted
        emphasized -> ReceiverOnPrimary
        danger -> ReceiverAlert
        else -> ReceiverInk
    }
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.small)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = containerColor
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DeviceActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    emphasized: Boolean,
    danger: Boolean,
    heightDp: Int,
    contentHorizontalPaddingDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        !enabled -> ReceiverSurfaceMuted
        emphasized -> ReceiverPrimary
        danger -> ReceiverAlertSoft
        else -> ReceiverSurfaceMuted
    }
    val contentColor = when {
        !enabled -> ReceiverMuted
        emphasized -> ReceiverOnPrimary
        danger -> ReceiverAlert
        else -> ReceiverInk
    }

    val shape = MaterialTheme.shapes.small

    Surface(
        modifier = modifier
            .heightIn(min = heightDp.coerceAtLeast(48).dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPaddingDp.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@Composable
private fun DeviceConfigBottomSheet(
    device: DeviceInfo,
    sourceId: String?,
    initialConfig: DeviceConfig,
    onSetConfig: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    var cooldown by remember(initialConfig.cooldown) {
        // 保留设备实际值：吸附到档位会让“未修改也下发新冷却值”。
        mutableStateOf(initialConfig.cooldown.coerceIn(1, 300))
    }
    var confidence by remember(initialConfig.confidence) { mutableStateOf(initialConfig.confidence.toFloat()) }
    var selectedTargets by remember(initialConfig.targets) {
        mutableStateOf(
            initialConfig.targets
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
        )
    }
    var targetSamplingRate by remember(initialConfig.targetSamplingRate) {
        mutableStateOf(initialConfig.targetSamplingRate.coerceIn(1, 5))
    }
    val source = device.sources.firstOrNull { it.sourceId == sourceId }
    val sourceConfigEnabled = sourceId == null || source?.let { it.isReady && !it.isMonitoring } == true
    val monitoring = source?.isMonitoring ?: device.isMonitoring
    val modelOptions = device.modelOptions.orEmpty()
    val modelSelectionEnabled = modelOptions.isNotEmpty() &&
        sourceConfigEnabled && (!monitoring || device.canSwitchModelWhileMonitoring)
    var selectedModelKey by remember(initialConfig.modelKey, modelOptions) {
        mutableStateOf(
            initialConfig.modelKey
                .takeIf { it.isNotBlank() && it in modelOptions }
                ?: if (modelSelectionEnabled) modelOptions.firstOrNull().orEmpty() else initialConfig.modelKey
        )
    }
    val modelKeyForChanges = if (modelSelectionEnabled) selectedModelKey else initialConfig.modelKey
    val editorModel = buildDeviceConfigEditorUiModel(
        device = device,
        initialConfig = initialConfig,
        editedCooldown = cooldown.toFloat(),
        editedConfidence = confidence,
        selectedTargets = selectedTargets,
        editedTargetSamplingRate = targetSamplingRate,
        editedModelKey = modelKeyForChanges
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .imePadding(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss)
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .heightIn(max = 780.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = ReceiverSurface,
                border = BorderStroke(1.dp, ReceiverOutline),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BottomSheetHandle()
                    ConfigSheetHeader(
                        deviceName = editorModel.deviceName + (source?.let { " · ${it.sourceName}" } ?: ""),
                        statusLabel = if (sourceId == null) buildDeviceCardUiModel(device).statusLabel else when {
                            source == null -> "来源已移除"
                            source.isMonitoring -> "此来源检测中"
                            !source.isReady -> "此来源未就绪"
                            else -> "此来源已停止"
                        },
                        onDismiss = onDismiss
                    )
                    if (!sourceConfigEnabled) {
                        Text(
                            text = if (source == null) "来源已移除，请关闭后刷新设备列表。" else "停止此来源并等待目标就绪后可应用参数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = ReceiverAmber
                        )
                    }
                    if (device.hasPendingConfigChanges) {
                        PendingConfigNotice()
                    }

                    Column(
                        modifier = Modifier,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CooldownEditor(
                            value = cooldown,
                            onChange = { cooldown = it }
                        )
                        SamplingRateEditor(
                            value = targetSamplingRate,
                            onChange = { targetSamplingRate = it }
                        )
                        ModelKeyEditor(
                            selectedModelKey = selectedModelKey,
                            modelOptions = modelOptions,
                            enabled = modelSelectionEnabled,
                            disabledReason = if (sourceId == null || sourceConfigEnabled) null else when {
                                source == null -> "来源已移除"
                                source.isMonitoring -> "停止此来源后可切换模型"
                                else -> "目标就绪后可切换模型"
                            },
                            onChange = { selectedModelKey = it }
                        )
                        ConfidenceEditor(
                            value = confidence,
                            onChange = { confidence = it }
                        )
                        TargetsEditor(
                            selectedTargets = selectedTargets,
                            onToggle = { target ->
                                selectedTargets = if (target in selectedTargets) {
                                    selectedTargets - target
                                } else {
                                    selectedTargets + target
                                }
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SheetActionButton(
                            text = editorModel.cancelActionLabel,
                            selected = false,
                            enabled = true,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        )
                        SheetActionButton(
                            text = editorModel.applyActionLabel,
                            selected = true,
                            enabled = editorModel.applyEnabled && sourceConfigEnabled,
                            onClick = {
                                buildDeviceConfigChanges(
                                    initialConfig = initialConfig,
                                    editedCooldown = cooldown.toFloat(),
                                    editedConfidence = confidence,
                                    selectedTargets = selectedTargets,
                                    editedTargetSamplingRate = targetSamplingRate,
                                    editedModelKey = modelKeyForChanges
                                ).forEach { change ->
                                    onSetConfig(change.key, change.value)
                                }
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingConfigNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ReceiverAmberSoft,
        border = BorderStroke(1.dp, ReceiverAmber.copy(alpha = 0.24f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = "已保存，停止后重新开启生效",
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverAmber,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun BottomSheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 4.dp)
                .background(ReceiverMuted, RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun ConfigSheetHeader(
    deviceName: String,
    statusLabel: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = ReceiverPrimarySoft,
            border = BorderStroke(1.dp, ReceiverOutline),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = LucideIcons.SlidersHorizontal,
                    contentDescription = null,
                    tint = ReceiverPrimaryText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "参数调节",
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverInk,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "$deviceName · $statusLabel",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted
            )
        }
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "关闭参数调节" }
        ) {
            Icon(LucideIcons.X, contentDescription = null, tint = ReceiverMuted, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CooldownEditor(
    value: Int,
    onChange: (Int) -> Unit
) {
    var customOpen by remember { mutableStateOf(false) }
    val isPreset = CooldownOptions.any { it.first == value }
    ConfigSection(
        icon = LucideIcons.Timer,
        title = "警报推送冷却时间",
        valueLabel = cooldownLabel(value)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CooldownOptions.forEach { (seconds, label) ->
                QuickValueChip(
                    text = label,
                    selected = value == seconds,
                    onClick = { onChange(seconds); customOpen = false }
                )
            }
            QuickValueChip(
                text = if (isPreset) "自定义" else "$value 秒",
                selected = !isPreset,
                onClick = { customOpen = !customOpen }
            )
        }
        if (customOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { onChange(value - 1) }, enabled = value > 1,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("减少") }
                ReceiverSlider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
                    valueRange = 1f..300f, steps = 298, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange(value + 1) }, enabled = value < 300,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("增加") }
            }
        }
    }
}

@Composable
private fun SamplingRateEditor(
    value: Int,
    onChange: (Int) -> Unit
) {
    ConfigSection(
        icon = LucideIcons.Timer,
        title = "目标采样率",
        valueLabel = "$value 次/秒"
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (1..5).forEach { rate ->
                QuickValueChip(
                    text = "$rate 次/秒",
                    selected = value == rate,
                    onClick = { onChange(rate) }
                )
            }
        }
    }
}

@Composable
private fun ModelKeyEditor(
    selectedModelKey: String,
    modelOptions: List<String>,
    enabled: Boolean,
    disabledReason: String?,
    onChange: (String) -> Unit
) {
    val disabledText = when {
        modelOptions.isEmpty() -> "设备暂未上报模型列表"
        !enabled -> disabledReason ?: "停止监控后可切换模型"
        else -> null
    }
    ConfigSection(
        icon = LucideIcons.SlidersHorizontal,
        title = "模型选择",
        valueLabel = selectedModelKey.ifBlank { "不可用" }
    ) {
        disabledText?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modelOptions.forEach { option ->
                QuickValueChip(
                    text = option.replace("_", " "),
                    selected = selectedModelKey == option,
                    enabled = enabled,
                    onClick = { onChange(option) }
                )
            }
        }
    }
}

@Composable
private fun ConfidenceEditor(
    value: Float,
    onChange: (Float) -> Unit
) {
    val quickValues = listOf(0.30f, 0.45f, 0.60f, 0.75f, 0.90f)

    ConfigSection(
        icon = LucideIcons.Percent,
        title = "置信度阈值",
        valueLabel = "${(value * 100).roundToInt()}%"
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickValues.forEach { quickValue ->
                QuickValueChip(
                    text = "${(quickValue * 100).roundToInt()}%",
                    selected = kotlin.math.abs(value - quickValue) < 0.01f,
                    onClick = { onChange(quickValue) }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ReceiverSlider(
            value = value,
            onValueChange = onChange,
            valueRange = 0.10f..0.95f,
            steps = 84,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TargetsEditor(
    selectedTargets: Set<String>,
    onToggle: (String) -> Unit
) {
    ConfigSection(
        icon = LucideIcons.CircleCheck,
        title = "监控目标",
        valueLabel = "${selectedTargets.size} 项"
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            targetEnZhPairs.forEach { (en, zh) ->
                QuickValueChip(
                    text = zh,
                    selected = en in selectedTargets,
                    onClick = { onToggle(en) }
                )
            }
        }
    }
}

@Composable
private fun ConfigSection(
    icon: ImageVector,
    title: String,
    valueLabel: String,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ReceiverSurfaceMuted,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ReceiverPrimaryText,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ReceiverInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReceiverInk,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
            content()
        }
    }
}

@Composable
private fun QuickValueChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.small,
        color = if (!enabled) ReceiverSurfaceMuted else if (selected) ReceiverPrimarySoft else ReceiverSurface,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) ReceiverMuted else if (selected) ReceiverPrimaryText else ReceiverInk,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ReceiverSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = ReceiverPrimary,
            activeTrackColor = ReceiverPrimary,
            inactiveTrackColor = ReceiverSurface,
            activeTickColor = ReceiverOnPrimary,
            inactiveTickColor = ReceiverMuted.copy(alpha = 0.35f)
        ),
        modifier = modifier.heightIn(min = 48.dp)
    )
}

@Composable
private fun SheetActionButton(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected && enabled) ReceiverPrimary else ReceiverSurfaceMuted
    val contentColor = if (!enabled) ReceiverMuted else if (selected) ReceiverOnPrimary else ReceiverInk

    val shape = MaterialTheme.shapes.small

    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, ReceiverOutline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
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
private fun statusForeground(tone: DeviceStatusTone): Color =
    when (tone) {
        DeviceStatusTone.OFFLINE -> ReceiverMuted
        DeviceStatusTone.RESIDENT_ONLY -> ReceiverAmber
        DeviceStatusTone.MONITORING -> ReceiverPrimaryText
        DeviceStatusTone.PARTIAL_MONITORING -> ReceiverAmber
        DeviceStatusTone.NOT_READY -> ReceiverAmber
        DeviceStatusTone.READY -> ReceiverPrimaryText
    }

@Composable
private fun statusContainer(tone: DeviceStatusTone): Color =
    when (tone) {
        DeviceStatusTone.OFFLINE -> ReceiverSurface
        DeviceStatusTone.RESIDENT_ONLY -> ReceiverAmberSoft
        DeviceStatusTone.MONITORING -> ReceiverPrimarySoft
        DeviceStatusTone.PARTIAL_MONITORING -> ReceiverAmberSoft
        DeviceStatusTone.NOT_READY -> ReceiverAmberSoft
        DeviceStatusTone.READY -> ReceiverPrimarySoft
    }

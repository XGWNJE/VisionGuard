package com.xgwnje.visionguard_android.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xgwnje.visionguard_android.R
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.xgwnje.visionguard_android.data.model.targetEnZhPairs
import com.xgwnje.visionguard_android.ui.home.DeviceCardChrome
import com.xgwnje.visionguard_android.ui.home.DeviceCardIllustration
import com.xgwnje.visionguard_android.ui.home.DeviceCardUiModel
import com.xgwnje.visionguard_android.ui.home.DeviceStatusTone
import com.xgwnje.visionguard_android.ui.home.buildDeviceCardChrome
import com.xgwnje.visionguard_android.ui.home.buildDeviceCardUiModel
import com.xgwnje.visionguard_android.ui.home.buildDeviceConfigChanges
import com.xgwnje.visionguard_android.ui.home.buildDeviceConfigEditorUiModel
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlert
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverAmber
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimarySoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurfaceMuted
import kotlin.math.roundToInt

@Composable
fun DeviceCard(
    device: DeviceInfo,
    initialConfig: DeviceConfig?,
    onCommand: (String) -> Unit,
    onSetConfig: (key: String, value: String) -> Unit
) {
    val model = remember(device) { buildDeviceCardUiModel(device) }
    val chrome = remember { buildDeviceCardChrome() }
    var showConfigEditor by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(chrome.cardCornerRadiusDp.dp),
        color = ReceiverSurface,
        border = BorderStroke(1.dp, Color.White),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DeviceCardHero(model = model, chrome = chrome)
            DeviceCardActions(
                model = model,
                chrome = chrome,
                onCommand = onCommand,
                onConfigClick = { showConfigEditor = true }
            )
        }
    }

    if (showConfigEditor) {
        DeviceConfigBottomSheet(
            device = device,
            initialConfig = initialConfig ?: DeviceConfig(),
            onSetConfig = onSetConfig,
            onDismiss = { showConfigEditor = false }
        )
    }
}

@Composable
private fun DeviceCardHero(
    model: DeviceCardUiModel,
    chrome: DeviceCardChrome
) {
    val heroShape = RoundedCornerShape(
        topStart = chrome.cardCornerRadiusDp.dp,
        topEnd = chrome.cardCornerRadiusDp.dp
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(chrome.heroHeightDp.dp)
            .clip(heroShape)
            .background(ReceiverSurfaceMuted)
    ) {
        DeviceCardBackgroundBitmap(
            illustration = model.illustration,
            alpha = chrome.heroBackgroundAlpha,
            scale = chrome.heroBackgroundScale,
            modifier = Modifier.matchParentSize()
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to ReceiverSurface.copy(alpha = 0.86f),
                            0.54f to ReceiverSurface.copy(alpha = 0.54f),
                            1f to Color.Transparent
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.62f)
                .padding(
                    horizontal = chrome.heroContentHorizontalPaddingDp.dp,
                    vertical = chrome.heroContentVerticalPaddingDp.dp
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = model.deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = ReceiverPrimary,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            DeviceStatusPill(
                label = model.statusLabel,
                tone = model.statusTone
            )
        }
    }
}

@Composable
private fun DeviceCardBackgroundBitmap(
    illustration: DeviceCardIllustration,
    alpha: Float,
    scale: Float,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(id = illustrationDrawableRes(illustration)),
        contentDescription = null,
        alignment = Alignment.CenterEnd,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
    )
}

@Composable
private fun DeviceStatusPill(
    label: String,
    tone: DeviceStatusTone
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = statusContainer(tone),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
                fontWeight = FontWeight.Black,
                maxLines = 1,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = chrome.actionAreaHorizontalPaddingDp.dp,
                vertical = chrome.actionAreaVerticalPaddingDp.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(chrome.columnGapDp.dp)
    ) {
        DeviceActionButton(
            label = model.controlActionLabel,
            icon = if (model.controlCommand == "pause") Icons.Default.Pause else Icons.Default.PlayArrow,
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
            icon = Icons.Default.Tune,
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
        emphasized -> ReceiverPrimary
        danger -> ReceiverAlertSoft
        else -> ReceiverSurfaceMuted
    }
    val contentColor = when {
        emphasized -> Color.White
        danger -> ReceiverAlert
        else -> ReceiverPrimary
    }

    Surface(
        modifier = modifier
            .height(heightDp.dp)
            .alpha(if (enabled) 1f else 0.48f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape((heightDp / 2).dp),
        color = containerColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = contentHorizontalPaddingDp.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DeviceConfigBottomSheet(
    device: DeviceInfo,
    initialConfig: DeviceConfig,
    onSetConfig: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    var cooldown by remember(initialConfig.cooldown) { mutableStateOf(initialConfig.cooldown.toFloat()) }
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
    val editorModel = buildDeviceConfigEditorUiModel(
        device = device,
        initialConfig = initialConfig,
        editedCooldown = cooldown,
        editedConfidence = confidence,
        selectedTargets = selectedTargets
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f)),
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
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(max = 780.dp),
                shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                color = ReceiverSurface,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.74f)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BottomSheetHandle()
                    ConfigSheetHeader(
                        deviceName = editorModel.deviceName,
                        statusLabel = buildDeviceCardUiModel(device).statusLabel,
                        onDismiss = onDismiss
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CooldownEditor(
                            value = cooldown,
                            onChange = { cooldown = it }
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
                            enabled = editorModel.applyEnabled,
                            onClick = {
                                buildDeviceConfigChanges(
                                    initialConfig = initialConfig,
                                    editedCooldown = cooldown,
                                    editedConfidence = confidence,
                                    selectedTargets = selectedTargets
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
private fun BottomSheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 5.dp)
                .background(ReceiverSurfaceMuted, RoundedCornerShape(3.dp))
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(18.dp),
            color = ReceiverPrimarySoft,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.74f)),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = ReceiverPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "参数调节",
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverPrimary,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "$deviceName · $statusLabel",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "关闭",
            tint = ReceiverMuted,
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onDismiss)
                .padding(6.dp)
        )
    }
}

@Composable
private fun CooldownEditor(
    value: Float,
    onChange: (Float) -> Unit
) {
    ConfigSection(
        icon = Icons.Default.Timer,
        title = "冷却时间",
        valueLabel = "${value.roundToInt()} 秒"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StepperButton(
                icon = Icons.Default.Remove,
                enabled = value > 1f,
                onClick = { onChange((value - 5f).coerceAtLeast(1f)) }
            )
            ReceiverSlider(
                value = value,
                onValueChange = onChange,
                valueRange = 1f..300f,
                modifier = Modifier.weight(1f)
            )
            StepperButton(
                icon = Icons.Default.Add,
                enabled = value < 300f,
                onClick = { onChange((value + 5f).coerceAtMost(300f)) }
            )
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
        icon = Icons.Default.Percent,
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
            steps = 16,
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
        icon = Icons.Default.CheckCircleOutline,
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
        shape = RoundedCornerShape(28.dp),
        color = ReceiverSurfaceMuted.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.74f)),
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
                    tint = ReceiverPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = ReceiverPrimary,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = ReceiverPrimary,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.End
                )
            }
            content()
        }
    }
}

@Composable
private fun StepperButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = ReceiverSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ReceiverPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun QuickValueChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (selected) ReceiverPrimary else ReceiverSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color.White else ReceiverPrimary,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
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
            activeTickColor = Color.White,
            inactiveTickColor = ReceiverMuted.copy(alpha = 0.35f)
        ),
        modifier = modifier
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
    val containerColor = if (selected) ReceiverPrimary else ReceiverSurfaceMuted
    val contentColor = if (selected) Color.White else ReceiverPrimary

    Surface(
        modifier = modifier
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.46f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = containerColor,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.76f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                fontWeight = FontWeight.Black
            )
        }
    }
}

private fun illustrationDrawableRes(illustration: DeviceCardIllustration): Int =
    when (illustration) {
        DeviceCardIllustration.WINDOWS_DESKTOP -> R.drawable.device_bg_windows
        DeviceCardIllustration.ANDROID_CAMERA -> R.drawable.device_bg_android_detector
        DeviceCardIllustration.GENERIC_VIEWFINDER -> R.drawable.device_bg_generic
    }

private fun statusForeground(tone: DeviceStatusTone): Color =
    when (tone) {
        DeviceStatusTone.OFFLINE -> ReceiverMuted
        DeviceStatusTone.MONITORING -> ReceiverPrimary
        DeviceStatusTone.NOT_READY -> ReceiverAmber
        DeviceStatusTone.READY -> ReceiverPrimary
    }

private fun statusContainer(tone: DeviceStatusTone): Color =
    when (tone) {
        DeviceStatusTone.OFFLINE -> ReceiverSurface.copy(alpha = 0.74f)
        DeviceStatusTone.MONITORING -> ReceiverPrimarySoft.copy(alpha = 0.78f)
        DeviceStatusTone.NOT_READY -> ReceiverAmber.copy(alpha = 0.14f)
        DeviceStatusTone.READY -> ReceiverPrimarySoft.copy(alpha = 0.78f)
    }

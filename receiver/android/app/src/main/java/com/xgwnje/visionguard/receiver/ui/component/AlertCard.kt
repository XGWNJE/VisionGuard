package com.xgwnje.visionguard.receiver.ui.component

import com.xgwnje.visionguard.icons.LucideIcons

// ┌─────────────────────────────────────────────────────────┐
// │ AlertCard.kt                                            │
// │ 角色：报警列表卡片，只展示关键信息并引导进入详情            │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.receiver.data.model.AlertMessage
import com.xgwnje.visionguard.receiver.ui.home.AlertCardUiModel
import com.xgwnje.visionguard.receiver.ui.home.DetectionChipUiModel
import com.xgwnje.visionguard.receiver.ui.home.DetectionTarget
import com.xgwnje.visionguard.receiver.ui.home.buildAlertCardUiModel
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlert
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAmber
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimaryText
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOutline
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOnPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurface
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurfaceMuted

@Composable
fun AlertCard(
    alert: AlertMessage,
    onClick: () -> Unit
) {
    val model = remember(alert) { buildAlertCardUiModel(alert) }

    val shape = RoundedCornerShape(12.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "查看报警详情", onClick = onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = ReceiverSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, ReceiverOutline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceColumn(model = model, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(12.dp))
                DetailCueIcon(model = model)
            }
            AlertInfoColumn(model = model)
        }
    }
}

@Composable
private fun DeviceColumn(
    model: AlertCardUiModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = model.deviceName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AlertInfoColumn(
    model: AlertCardUiModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = model.dateTimeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverMuted,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            model.targetChips.forEach { chip ->
                DetectionChip(chip = chip)
            }
        }
    }
}

@Composable
private fun DetectionChip(chip: DetectionChipUiModel) {
    val foreground = targetColor(chip.target)
    val background = if (chip.target == DetectionTarget.PERSON) ReceiverAlertSoft else ReceiverSurfaceMuted

    Surface(
        shape = MaterialTheme.shapes.small,
        color = background,
        border = BorderStroke(1.dp, ReceiverOutline)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = targetIcon(chip.target),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (chip.confidencePercent > 0)
                    "${chip.label} ${chip.confidencePercent}%"
                else
                    chip.label,
                style = MaterialTheme.typography.labelLarge,
                color = foreground,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun DetailCueIcon(model: AlertCardUiModel) {
    Box(
        modifier = Modifier
            .widthIn(min = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.small,
            color = ReceiverSurfaceMuted,
            border = BorderStroke(1.dp, ReceiverOutline)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LucideIcons.ChevronRight,
                    contentDescription = model.detailIconContentDescription,
                    tint = ReceiverPrimaryText,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private fun targetIcon(target: DetectionTarget): ImageVector =
    when (target) {
        DetectionTarget.PERSON -> LucideIcons.UserRound
        DetectionTarget.BICYCLE -> LucideIcons.Bike
        DetectionTarget.CAR -> LucideIcons.Car
        DetectionTarget.MOTORCYCLE -> LucideIcons.Motorbike
        DetectionTarget.BUS -> LucideIcons.BusFront
        DetectionTarget.TRUCK -> LucideIcons.Truck
        DetectionTarget.UNKNOWN -> LucideIcons.CircleHelp
    }

@Composable
private fun targetColor(target: DetectionTarget): Color =
    when (target) {
        DetectionTarget.PERSON -> ReceiverAlert
        DetectionTarget.MOTORCYCLE -> ReceiverAmber
        DetectionTarget.UNKNOWN -> ReceiverMuted
        else -> ReceiverPrimaryText
    }

package com.xgwnje.visionguard_android.ui.component

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.TwoWheeler
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.ui.home.AlertCardUiModel
import com.xgwnje.visionguard_android.ui.home.DetectionChipUiModel
import com.xgwnje.visionguard_android.ui.home.DetectionTarget
import com.xgwnje.visionguard_android.ui.home.buildAlertCardUiModel
import com.xgwnje.visionguard_android.ui.home.formatAlertTime
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlert
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverAmber
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurfaceMuted

@Composable
fun AlertCard(
    alert: AlertMessage,
    onClick: () -> Unit
) {
    val model = remember(alert) { buildAlertCardUiModel(alert) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = ReceiverSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color.White)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceColumn(model = model, modifier = Modifier.weight(0.34f))
            Spacer(modifier = Modifier.width(14.dp))
            AlertInfoColumn(model = model, modifier = Modifier.weight(0.56f))
            Spacer(modifier = Modifier.width(10.dp))
            DetailCueIcon(model = model)
        }
    }
}

@Composable
private fun DeviceColumn(
    model: AlertCardUiModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = model.deviceName,
            style = MaterialTheme.typography.titleLarge,
            color = ReceiverPrimary,
            fontWeight = FontWeight.Black,
            maxLines = 1,
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
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = model.dateTimeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverMuted,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 2
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
        shape = RoundedCornerShape(22.dp),
        color = background,
        border = BorderStroke(1.dp, Color.White)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = targetIcon(chip.target),
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = if (chip.confidencePercent > 0)
                    "${chip.label} ${chip.confidencePercent}%"
                else
                    chip.label,
                style = MaterialTheme.typography.labelLarge,
                color = foreground,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DetailCueIcon(model: AlertCardUiModel) {
    Box(
        modifier = Modifier
            .widthIn(min = 52.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(20.dp),
            color = ReceiverSurfaceMuted,
            border = BorderStroke(1.dp, Color.White)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = model.detailIconContentDescription,
                    tint = ReceiverPrimary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

private fun targetIcon(target: DetectionTarget): ImageVector =
    when (target) {
        DetectionTarget.PERSON -> Icons.Default.Person
        DetectionTarget.BICYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
        DetectionTarget.CAR -> Icons.Default.DirectionsCar
        DetectionTarget.MOTORCYCLE -> Icons.Default.TwoWheeler
        DetectionTarget.BUS -> Icons.Default.DirectionsBus
        DetectionTarget.TRUCK -> Icons.Default.LocalShipping
        DetectionTarget.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
    }

private fun targetColor(target: DetectionTarget): Color =
    when (target) {
        DetectionTarget.PERSON -> ReceiverAlert
        DetectionTarget.MOTORCYCLE -> ReceiverAmber
        DetectionTarget.UNKNOWN -> ReceiverMuted
        else -> ReceiverPrimary
    }

internal fun formatTimestamp(iso: String): String = formatAlertTime(iso)

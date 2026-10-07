package com.xgwnje.visionguard.notifier.ui.dialogs

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.DetectedObject
import com.xgwnje.visionguard.notifier.R
import kotlin.math.PI
import kotlin.math.sin

/** The entire alert surface is the acknowledgement target. The nested details action consumes its own tap. */
@Composable
internal fun AlarmSurface(
    onConfirm: () -> Unit, onDetails: () -> Unit, matchedKeyword: String?,
    detectedObject: DetectedObject?, confirmationError: String?, modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val target = detectedObject?.takeIf { it.valid }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background)
        .clickable(onClickLabel = "确认停止报警", role = Role.Button, onClick = onConfirm)
        .safeDrawingPadding().padding(24.dp)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight && LocalDensity.current.fontScale <= 1.3f
        val artworkHeight = minOf(maxWidth, maxHeight * .52f).coerceAtLeast(160.dp)
        val title = target?.displayName ?: matchedKeyword?.takeIf { it.isNotBlank() } ?: "未知报警"
        Column(Modifier.widthIn(max = 960.dp).fillMaxSize().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (wide) Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                AlarmArtwork(target, Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AlarmTargetTitle(title, target != null)
                }
            } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                AlarmTargetTitle(title, target != null)
                AlarmArtwork(target, Modifier.fillMaxWidth().height(artworkHeight))
            }
            confirmationError?.let {
                Text(it, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center, color = colors.error)
            }
            Text("轻点画面 · 确认停止", Modifier.padding(top = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            TextButton(onDetails, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                Icon(LucideIcons.ChevronRight, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("查看详情")
            }
        }
    }
}

@Composable
private fun AlarmTargetTitle(title: String, isObject: Boolean) {
    Text(if (isObject) "发现目标" else "节点告警", style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    Text(title, Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { heading() },
        style = if (isObject && title.length <= 4) MaterialTheme.typography.displayLarge.copy(
            fontSize = 64.sp, lineHeight = 76.sp, fontWeight = FontWeight.Bold) else MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center)
}

@Composable
private fun AlarmArtwork(target: DetectedObject?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val resource = when (target?.label) {
        "person" -> R.drawable.alarm_person
        "car" -> R.drawable.alarm_car
        else -> null
    }
    val motionEnabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val phase = if (motionEnabled && resource != null) {
        val transition = rememberInfiniteTransition(label = "目标扫描")
        val value by transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "扫描光带")
        value
    } else .5f
    var visible by remember(target?.label) { mutableStateOf(!motionEnabled) }
    LaunchedEffect(target?.label) { visible = true }
    val reveal by animateFloatAsState(if (visible) 1f else 0f, tween(420), label = "目标浮现")
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val objectScale = if (target?.label == "car" && maxWidth > maxHeight * 1.4f) 1.55f else 1f
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * .48f
            drawCircle(Brush.radialGradient(listOf(colors.primary.copy(alpha = .15f), Color.Transparent),
                center = center, radius = radius), radius = radius)
        }
        if (resource != null) Image(painterResource(resource), null,
            Modifier.fillMaxSize().padding(16.dp).graphicsLayer {
                alpha = reveal
                scaleX = (.94f + .06f * reveal) * objectScale
                scaleY = scaleX
            })
        else Icon(LucideIcons.BellRing, null, Modifier.size(96.dp), tint = colors.primary)
        if (motionEnabled && resource != null) Canvas(Modifier.fillMaxSize()) {
            val beamHeight = 18.dp.toPx()
            val top = size.height * (.12f + .76f * phase)
            val alpha = (sin(phase * PI).toFloat() * .22f).coerceAtLeast(0f)
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, colors.primary.copy(alpha = alpha), Color.Transparent),
                startY = top, endY = top + beamHeight), Offset(size.width * .08f, top), Size(size.width * .84f, beamHeight))
        }
        if (resource != null) Text("目标示意", Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

package com.xgwnje.visionguard.inference.ui.geo

import android.provider.Settings
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.icons.LucideIcons

/** 几何界面的尺寸与动效资源。 */
object Geo {
    /** 通用触点下限。 */
    val Touch = 48.dp

    val ScreenPadding = 16.dp
    val Gap = 8.dp
    val GroupGap = 16.dp

    /** 启停压板与参数 / 遮罩方键：横屏控制带里的主操作，仍高于 48dp 触点下限。 */
    val PaddleHeight = 56.dp
    val SquareKey = 56.dp
    val PressShift = 3.dp

    /** 横屏右侧来源轨宽度：底部状态带右段用同一常量，两处分界因此对齐。 */
    val SourceRail = 300.dp

    /** 底部状态带：仪表读数 + 参数摘要 + 应用级入口，横屏按上方的分界切成两段。 */
    val StatusBar = 64.dp

    /** 来源行：紧凑两行，4 路以上仍能同屏。 */
    val SourceRow = 64.dp
    val SourceBar = 3.dp

    /** 采样滚筒。 */
    val WheelWidth = 112.dp
    val WheelHeight = 220.dp
    val WheelNumber = 40.sp

    /** 置信度滑轨。 */
    val RailHeight = 32.dp
    val CursorWidth = 20.dp
    val CursorHeight = 42.dp

    /** 目标排片。 */
    val TargetCellMinHeight = 62.dp
    const val TARGET_COLUMNS = 5
    const val TARGET_LIMIT = 256

    /**
     * 参数区块的统一节奏：主控件区 / 操作行 / 快捷行。
     *
     * 并排的几个控件逐段等高，视觉上不会出现「有的地方留白大、有的地方留白小」。
     */
    val BlockControl = 132.dp
    val BlockAction = 42.dp
    /** 快捷行按 48dp 触点下限，不再为了紧凑牺牲可点区域。 */
    val BlockQuick = 48.dp

    /** 模型插片与信息格。 */
    val ModelTile = 84.dp
    val BlockGap = 12.dp
    val InfoCell = 56.dp

    /** 抽屉宽度。 */
    val ParamDrawer = 760.dp
    val Drawer = 560.dp

    /** 遮罩画布内衬与最小边长。 */
    val MaskInset = 26.dp
    const val MASK_MIN_EDGE = 0.02f

    /** 动效时长（毫秒）。 */
    const val SHIFTER_MS = 160
    const val DRAWER_MS = 180
    const val PRESS_MS = 110

    /** 字号下限：可点标签 ≥14，辅助与技术值 12。 */
    val FontMeta = 12.sp

    /** 线宽。 */
    val Border = 1.dp
}

/** 系统“减弱动态”开关：开启后取消位移动画，只保留直接反馈。 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** 平面几何表面：直角、1dp 描边、无阴影。 */@Composable
fun GeoSurface(
    modifier: Modifier = Modifier,
    fill: Color = MaterialTheme.colorScheme.surface,
    bordered: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .background(fill, RectangleShape)
            .then(if (bordered) Modifier.border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape) else Modifier),
        content = content
    )
}

/** 两段之间的分隔线。 */
@Composable
fun GeoDivider(modifier: Modifier = Modifier, vertical: Boolean = false) {
    if (vertical) {
        Spacer(modifier.width(Geo.Border).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
    } else {
        Spacer(modifier.height(Geo.Border).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
    }
}

/** 操作键语义。 */
enum class GeoTone { Primary, Secondary, Danger }

/**
 * 当前打开的确认框层数。
 *
 * 根层 Escape 只在没有确认框时关抽屉；否则确认框和抽屉会被同一次按键一起关掉。
 */
object GeoModal {
    var confirmDepth by mutableStateOf(0)
}

private data class GeoKeyColors(val container: Color, val content: Color, val border: Color)

@Composable
private fun geoKeyColors(tone: GeoTone, enabled: Boolean): GeoKeyColors {
    val scheme = MaterialTheme.colorScheme
    if (!enabled) {
        return GeoKeyColors(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outlineVariant)
    }
    return when (tone) {
        GeoTone.Primary -> GeoKeyColors(scheme.primary, scheme.onPrimary, scheme.primary)
        GeoTone.Secondary -> GeoKeyColors(scheme.surface, scheme.onSurface, scheme.outlineVariant)
        GeoTone.Danger -> GeoKeyColors(scheme.errorContainer, scheme.error, scheme.error)
    }
}

/**
 * 压板式操作键：按下位移 3dp，直角、无涟漪、无阴影。
 */
@Composable
fun GeoKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: GeoTone = GeoTone.Secondary,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
    label: String? = null,
    iconSize: Dp = 22.dp,
    labelStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    minWidth: Dp = Geo.Touch,
    minHeight: Dp = Geo.Touch,
    contentAlignment: Alignment = Alignment.Center
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val shift by animateDpAsState(
        targetValue = if (pressed && enabled && !busy && !reduced) Geo.PressShift else 0.dp,
        animationSpec = tween(Geo.PRESS_MS),
        label = "geoKeyPress"
    )
    val colors = geoKeyColors(tone, enabled && !busy)
    Box(
        modifier
            .offset(y = shift)
            .requiredHeightIn(min = minHeight)
            .widthIn(min = minWidth)
            .background(colors.container, RectangleShape)
            .border(Geo.Border, colors.border, RectangleShape)
            .geoFocusable()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !busy,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = contentAlignment
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).wrapContentWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val effectiveIcon = if (busy) LucideIcons.LoaderCircle else icon
            effectiveIcon?.let {
                Icon(it, contentDescription = null, tint = colors.content, modifier = Modifier.size(iconSize))
                if (label != null) Spacer(Modifier.width(8.dp))
            }
            label?.let { Text(it, color = colors.content, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** 48dp 图标键：只放图标，触点固定。 */
@Composable
fun GeoIconKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: GeoTone = GeoTone.Secondary,
    size: Dp = Geo.Touch
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = geoKeyColors(tone, enabled)
    val fill = if (pressed && enabled) MaterialTheme.colorScheme.surfaceVariant else colors.container
    Box(
        modifier
            .size(size)
            .background(fill, RectangleShape)
            .border(Geo.Border, colors.border, RectangleShape)
            .geoFocusable()
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.content, modifier = Modifier.size(size * 0.46f))
    }
}

/** 状态灯：图标 + 文案 + 语义色，三者一起表达。 */
enum class GeoLampTone { Neutral, Success, Warning, Error }

@Composable
fun GeoLamp(text: String, tone: GeoLampTone = GeoLampTone.Neutral, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (icon, color) = when (tone) {
        GeoLampTone.Success -> LucideIcons.CircleCheck to VisionGuardStatusColors.success
        GeoLampTone.Warning -> LucideIcons.CircleHelp to VisionGuardStatusColors.warning
        GeoLampTone.Error -> LucideIcons.CircleAlert to VisionGuardStatusColors.error
        GeoLampTone.Neutral -> LucideIcons.Radio to scheme.onSurfaceVariant
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 说明条：等待 / 警告 / 错误各自带语义色与图标，不覆盖主操作。 */
@Composable
fun GeoHint(text: String, tone: GeoLampTone = GeoLampTone.Neutral, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (icon, textColor, surface) = when (tone) {
        GeoLampTone.Warning, GeoLampTone.Error -> Triple(
            if (tone == GeoLampTone.Error) LucideIcons.CircleAlert else LucideIcons.LockKeyhole,
            VisionGuardStatusColors.warning,
            scheme.tertiaryContainer
        )
        GeoLampTone.Success -> Triple(LucideIcons.CircleCheck, VisionGuardStatusColors.success, scheme.surfaceVariant)
        GeoLampTone.Neutral -> Triple(LucideIcons.CircleHelp, scheme.onSurfaceVariant, scheme.surfaceVariant)
    }
    val finalText = if (tone == GeoLampTone.Error) VisionGuardStatusColors.error else textColor
    Row(
        modifier.fillMaxWidth().background(surface, RectangleShape).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = finalText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = finalText, style = MaterialTheme.typography.bodySmall)
    }
}

/** 分区标题：`01 / 采样频率` 与右侧边界说明。 */
@Composable
fun GeoSectionLabel(index: String?, title: String, trailing: String? = null, modifier: Modifier = Modifier) {    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        if (index != null) {
            Text(index, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        trailing?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

/** 参数说明跳转：由抽屉层提供实现，深层区块直接放「?」入口。 */
val LocalGeoHelp = compositionLocalOf<(String) -> Unit> { {} }

/** 参数说明入口：点「?」把帮助条目标题交给抽屉层，由主界面切到帮助页。 */
@Composable
fun HelpTopicKey(topic: String, modifier: Modifier = Modifier) {
    val onHelp = LocalGeoHelp.current
    GeoIconKey(
        LucideIcons.CircleHelp,
        "说明：$topic",
        onClick = { onHelp(topic) },
        modifier = modifier
    )
}

/** 字段标签。 */
@Composable
fun GeoFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = modifier)
}

/** 键值信息行：左标签固定宽度，右值可换行。 */
@Composable
fun GeoInfoRow(label: String, value: String, labelWidth: Int = 96, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(labelWidth.dp)
        )
        Text(
            value,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 主界面提示：运行时消息、来源错误与恢复警告统一成一条，悬浮展示、不占布局。 */
data class GeoNotice(
    val id: String,
    val text: String,
    val tone: GeoLampTone,
    /** 运行时消息可关闭；状态驱动的提示（来源错误、性能与恢复警告）不给关闭键。 */
    val dismissible: Boolean = false
)

/**
 * 悬浮提示区：叠在主预览面板上层，不挤压画面、来源列表与状态带的布局。
 *
 * 多条按顺序堆叠；长文最多三行，超出省略。
 */
@Composable
fun GeoNoticeStack(notices: List<GeoNotice>, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Geo.Gap)) {
        notices.forEach { notice ->
            val scheme = MaterialTheme.colorScheme
            val (icon, content, surface) = when (notice.tone) {
                GeoLampTone.Error -> Triple(LucideIcons.CircleAlert, VisionGuardStatusColors.error, scheme.errorContainer)
                GeoLampTone.Warning -> Triple(LucideIcons.CircleHelp, VisionGuardStatusColors.warning, scheme.tertiaryContainer)
                GeoLampTone.Success -> Triple(LucideIcons.CircleCheck, VisionGuardStatusColors.success, scheme.surfaceVariant)
                GeoLampTone.Neutral -> Triple(LucideIcons.CircleHelp, scheme.onSurfaceVariant, scheme.surfaceVariant)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(surface, RectangleShape)
                    .border(Geo.Border, scheme.outlineVariant, RectangleShape)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    notice.text,
                    color = content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (notice.dismissible) {
                    GeoIconKey(LucideIcons.X, "关闭提示", onClick = { onDismiss(notice.id) })
                }
            }
        }
    }
}

/**
 * 拨档：轨道上二档 / 三档平移，160ms 水平移位；禁用时给出原因由调用方显示。
 */
@Composable
fun GeoShifter(
    options: List<Pair<String, String>>,
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = Geo.Touch
) {
    val index = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    val reduced = rememberReducedMotion()
    val position by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = tween(if (reduced) 0 else Geo.SHIFTER_MS),
        label = "geoShifter"
    )
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant, RectangleShape)
            .border(Geo.Border, MaterialTheme.colorScheme.outlineVariant, RectangleShape)
    ) {
        val carriageWidth = maxWidth / options.size
        Box(
            Modifier
                .offset(x = carriageWidth * position)
                .width(carriageWidth)
                .fillMaxHeight()
                .background(
                    if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    RectangleShape
                )
        )
        Row(Modifier.fillMaxWidth().height(height)) {
            options.forEachIndexed { optionIndex, option ->
                val selected = optionIndex == index
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .geoFocusable()
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(option.first) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option.second,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

package com.xgwnje.visionguard_android.ui.component

// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionBanner.kt                                     │
// │ 角色：接收端 WebSocket 状态胶囊，不代表检测端在线数量      │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard_android.data.remote.WsState
import com.xgwnje.visionguard_android.ui.home.buildFrostedOverlaySpec
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlert
import com.xgwnje.visionguard_android.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverAmber
import com.xgwnje.visionguard_android.ui.theme.ReceiverAmberSoft
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurfaceMuted

@Composable
fun ConnectionBanner(
    state: WsState,
    onlineCount: Int,
    isCheckingUpdate: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val spec = connectionSpec(state, onlineCount)
    val overlay = buildFrostedOverlaySpec()
    val subtitle = if (isCheckingUpdate) "正在检查更新" else spec.subtitle
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = spec.containerColor.copy(alpha = overlay.topBannerAlpha),
        label = "connection_container"
    )
    val shape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = overlay.shadowElevationDp.dp, shape = shape, clip = false)
            .clip(shape)
            .background(containerColor)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = overlay.borderAlpha)), shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = !isCheckingUpdate
                    ) {
                        onClick()
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = spec.foregroundColor
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleMedium,
                color = ReceiverPrimary,
                fontWeight = FontWeight.Black,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class ConnectionSpec(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val containerColor: Color,
    val foregroundColor: Color
)

private fun connectionSpec(state: WsState, onlineCount: Int): ConnectionSpec =
    when (state) {
        WsState.CONNECTED -> ConnectionSpec(
            title = "已连接",
            subtitle = if (onlineCount > 0) "$onlineCount 台设备在线" else "自动接收报警",
            icon = Icons.Default.CheckCircleOutline,
            containerColor = ReceiverSurface,
            foregroundColor = ReceiverPrimary
        )
        WsState.CONNECTING -> ConnectionSpec(
            title = "连接中",
            subtitle = "正在重建通道",
            icon = Icons.Default.Sync,
            containerColor = ReceiverAmberSoft,
            foregroundColor = ReceiverAmber
        )
        WsState.DISCONNECTED -> ConnectionSpec(
            title = "连接断开",
            subtitle = "自动重连中",
            icon = Icons.Default.CloudOff,
            containerColor = ReceiverSurfaceMuted,
            foregroundColor = ReceiverMuted
        )
        WsState.AUTH_FAILED -> ConnectionSpec(
            title = "认证失败",
            subtitle = "检查 API Key",
            icon = Icons.Default.ErrorOutline,
            containerColor = ReceiverAlertSoft,
            foregroundColor = ReceiverAlert
        )
    }

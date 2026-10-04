package com.xgwnje.visionguard.receiver.ui.component

import com.xgwnje.visionguard.icons.LucideIcons

// ┌─────────────────────────────────────────────────────────┐
// │ ConnectionBanner.kt                                     │
// │ 角色：接收端 WebSocket 状态胶囊，不代表检测端在线数量      │
// └─────────────────────────────────────────────────────────┘

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.receiver.data.remote.WsState
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlert
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAlertSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAmber
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverAmberSoft
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverMuted
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOutline
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverOnPrimary
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurface
import com.xgwnje.visionguard.receiver.ui.theme.ReceiverSurfaceMuted

@Composable
fun ConnectionBanner(
    state: WsState,
    onlineCount: Int,
    isCheckingUpdate: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val spec = connectionSpec(state, onlineCount)
    val subtitle = if (isCheckingUpdate) "正在检查更新" else spec.subtitle
    val containerColor by animateColorAsState(
        targetValue = spec.containerColor,
        label = "connection_container"
    )
    val shape = MaterialTheme.shapes.medium

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(BorderStroke(1.dp, ReceiverOutline), shape)
            .then(
                if (onClick != null) {
                    Modifier.semantics { contentDescription = "${spec.title}，$subtitle" }.clickable(
                        enabled = !isCheckingUpdate,
                        role = Role.Button,
                        onClickLabel = "检查更新"
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = spec.foregroundColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = ReceiverMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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

@Composable
private fun connectionSpec(state: WsState, onlineCount: Int): ConnectionSpec =
    when (state) {
        WsState.CONNECTED -> ConnectionSpec(
            title = "已连接",
            subtitle = if (onlineCount > 0) "$onlineCount 台设备在线" else "自动接收报警",
            icon = LucideIcons.CircleCheck,
            containerColor = ReceiverSurface,
            foregroundColor = ReceiverPrimary
        )
        WsState.CONNECTING -> ConnectionSpec(
            title = "连接中",
            subtitle = "正在重建通道",
            icon = LucideIcons.RefreshCw,
            containerColor = ReceiverAmberSoft,
            foregroundColor = ReceiverAmber
        )
        WsState.DISCONNECTED -> ConnectionSpec(
            title = "连接断开",
            subtitle = "自动重连中",
            icon = LucideIcons.CloudOff,
            containerColor = ReceiverSurfaceMuted,
            foregroundColor = ReceiverMuted
        )
        WsState.AUTH_FAILED -> ConnectionSpec(
            title = "认证失败",
            subtitle = "请重新登录账号",
            icon = LucideIcons.CircleAlert,
            containerColor = ReceiverAlertSoft,
            foregroundColor = ReceiverAlert
        )
    }

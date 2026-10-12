package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons

private val helpFolds = listOf(
    "运行与启停" to
        "每个来源独立运行；切换预览不改变运行状态。参数、镜头与遮罩是冷改动：先停止来源再保存，下次启动生效。停止只影响当前来源。",
    "来源与远程绑定" to
        "本机相机只有一条，点「添加来源」直接添加；远程镜头由控制台把相机推流节点绑定到本节点，本机不冒充完成绑定，也不接管相机的推流设置。",
    "遮罩" to
        "遮罩用相对坐标保存在本机，黑色区域在推理前涂黑，预览与报警截图同步遮挡；遮罩不通过远控同步，也不随账号上传。",
    "连接、回执与声音" to
        "控制通道与媒体通道的“已连接”只说明连接建立；报警接受回执说明服务已接受；收件不等于声音播放。本节点只做推理与报警上报，声音由本机通知节点负责。",
    "操作手势" to
        "滚筒支持拖动、上下键与 Home / End，端点有止挡、松手吸附、不循环；滑轨可拖可点并按整数精调；系统开启“减弱动态”时省去位移与过渡动画，只保留直接反馈。",
    "权限与后台" to
        "相机与通知权限只能由本机用户在系统里授权，控制台不能远程绕过。有来源在运行时保持前台服务，退到后台或锁屏后继续推理；运行意图按来源保存，主动停止会清除意图。"
)

/**
 * 帮助抽屉：按主题折叠，键盘可达，不混入实现调试信息。
 */
@Composable
fun GeoHelpDrawer(onClose: () -> Unit) {
    GeoDrawer(
        title = "使用帮助",
        sectionId = "VISION / HELP",
        onClose = onClose,
        width = Geo.Drawer
    ) {
        helpFolds.forEach { (title, text) ->
            HelpFold(title, text)
        }
    }
}

@Composable
private fun HelpFold(title: String, text: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Geo.Touch)
                .clickable { open = !open }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (open) LucideIcons.ChevronUp else LucideIcons.ChevronDown,
                contentDescription = if (open) "收起 $title" else "展开 $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (open) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        GeoDivider()
        Spacer(Modifier.size(0.dp))
    }
}

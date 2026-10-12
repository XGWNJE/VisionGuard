package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

/**
 * 帮助条目：抽屉按主题折叠。
 *
 * 条目标题同时是来源抽屉里「?」入口的跳转目标，改标题要同步 [HelpTopicKey] 的调用点。
 */
internal val helpTopics = listOf(
    "运行与启停" to
        "每个来源独立启停，只影响被点的那一路；来源列表表头的「全部停止」一次停掉所有运行中的来源。参数、镜头与遮罩是冷改动：先停止来源再保存，下次启动生效。",
    "检测参数" to
        "置信度是判定为目标的分数下限（10–95%）：越低越容易检出，误报也越多；采样是每秒推理次数（1–5 FPS）：越高越跟得上，也更耗电；报警冷却限制同一来源连续报警的最短间隔（1–300 秒）。三者都是冷改动。",
    "模型档位与下载" to
        "模型按尺寸与后端分档：320 更快、640 更准；GPU 档走 LiteRT GPU，本机不支持时回落到同尺寸的 CPU 档。档位要在「模型插槽」里下载后才能选，正在被运行中来源使用的档位不能删除。",
    "检测目标" to
        "只上报选中的类别，至少保留一项；最多 256 项、总长 4096 字符。搜索支持中文与英文名，选中项可逐个移除。",
    "隐私遮罩" to
        "遮罩用相对坐标保存在本机：黑色区域在推理前涂黑，预览与报警截图同步遮挡，不同步到控制台、也不随账号上传。拖动超过一小段距离才会新建矩形，最小边长是画面短边的 2%。",
    "远程来源与绑定" to
        "本机相机只有一条；远程镜头由控制台把相机推流节点绑定到本节点，本机不冒充完成绑定，也不接管相机的推流设置。解绑后来源会从列表消失。",
    "连接、回执与声音" to
        "控制通道与媒体通道的「已连接」只说明连接建立；报警接受回执说明服务已接受；收件不等于声音播放。本节点只做推理与报警上报，声音由本机通知节点负责。",
    "权限与后台" to
        "相机与通知权限只能由本机用户在系统里授权，控制台不能远程绕过。有来源运行时保持前台服务，退到后台或锁屏后继续推理；运行意图按来源保存，主动停止会清除意图。"
)

/**
 * 帮助抽屉：按主题折叠，键盘可达，不混入实现调试信息。
 *
 * [initialTopic] 从来源抽屉里参数旁的「?」进来时，直接展开对应条目。
 */
@Composable
fun GeoHelpDrawer(initialTopic: String? = null, onClose: () -> Unit) {
    GeoDrawer(
        title = "使用帮助",
        sectionId = "VISION / HELP",
        onClose = onClose,
        width = Geo.Drawer
    ) {
        helpTopics.forEach { (title, text) ->
            HelpFold(title = title, text = text, expanded = title == initialTopic)
        }
    }
}

@Composable
private fun HelpFold(title: String, text: String, expanded: Boolean) {
    var open by remember(title, expanded) { mutableStateOf(expanded) }
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
    }
}

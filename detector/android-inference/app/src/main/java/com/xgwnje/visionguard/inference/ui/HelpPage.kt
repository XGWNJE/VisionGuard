package com.xgwnje.visionguard.inference.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons

/**
 * 帮助页：说明检测链、遮罩语义、参数生效时机、远控与报警边界，以及尚未真机验证的部分。
 *
 * 纯说明文字，独立滚动，不挤占主界面。
 */
@Composable
fun HelpPage(onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(UiTokens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.Gap)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(LucideIcons.ArrowLeft, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("帮助", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(UiTokens.GroupGap)
            ) {
                SectionCard {
                    SectionTitle("检测链")
                    HelpParagraph("采集 → 遮罩 → 预处理 → 推理 → 解析 → 报警。")
                    HelpParagraph("采集：本机相机用 CameraX 只保留最新帧；远程镜头通过统一服务媒体通道收 JPEG 帧，头部声明的尺寸与 JPEG 不一致时整帧丢弃，不拿错位画面推理。")
                    HelpParagraph("遮罩：推理前就地涂黑，同一张已涂黑的画面同时用于推理、报警截图与本机预览。")
                    HelpParagraph("预处理：等比缩放到模型输入并居中补黑边，输出 RGB、/255 的张量。")
                    HelpParagraph("推理：本机 ONNX Runtime，默认尝试 NNAPI，失败或会话创建失败时回落 CPU 并保留原因。")
                    HelpParagraph("解析：按输出展平长度识别模型形态，统一最多返回 5 个框，坐标映射回原帧并裁掉黑边。")
                    HelpParagraph("报警：逐来源独立冷却，只统计成功帧，时间戳使用 NTP 校正时间。")
                }

                SectionCard {
                    SectionTitle("来源与遮罩")
                    HelpParagraph("来源类型只有两种：本机相机采集，或订阅同账号相机推流节点的远程镜头。本节点不发布视频，也不取代相机推流节点。")
                    HelpParagraph("远程镜头由 Web 控制台把相机推流节点绑定到本节点后自动入列，服务端移除后本地卡片同步删除；本机只提供占位槽位。")
                    HelpParagraph("遮罩只属于本机来源配置，不通过 set-config 远程同步，换账号也不会串用。")
                    HelpParagraph("涂黑区域不会被识别，也不会出现在报警截图里。")
                }

                SectionCard {
                    SectionTitle("参数生效时机")
                    HelpParagraph("置信度、采样频率、报警冷却、模型、检测目标都是冷改动：运行中的来源拒绝修改，改完需要重新启动该来源才生效。")
                    HelpParagraph("模型只能选本端清单里已下载的文件；未下载时先在设置或参数编辑器里下载。")
                    HelpParagraph("取值边界：置信度 10–95 整数百分比、采样 1–5 帧/秒、报警冷却 1–300 整数秒、检测目标最长 4096 字符。")
                }

                SectionCard {
                    SectionTitle("控制台远控")
                    HelpParagraph("设备级 pause 停止全部来源、resume 启动全部已配置来源；带 targetSourceId 的 pause/resume 只作用于该来源。")
                    HelpParagraph("远控可以改 confidence、cooldown、targets、targetSamplingRate、modelKey 五个参数键，运行中同样拒绝，理由与本机界面一致。")
                    HelpParagraph("stop-alarm 恒回“当前无报警”：本节点不接生命周期命令，也不提供远程开关主程序。")
                }

                SectionCard {
                    SectionTitle("报警与截图")
                    HelpParagraph("报警先写入按 服务/账号/设备 分区的持久发件箱，上限 500 条，超出丢最旧；只有收到统一服务 alert-ack 才移除。")
                    HelpParagraph("alert-id-conflict 与 invalid-or-expired-event 视为确定拒收并移除，其它原因保留重试。")
                    HelpParagraph("实时期限为事件发生后 30 秒；截图在 accepted=true 之后异步发送，宽度不超过 960、JPEG 质量 65。")
                    HelpParagraph("本机截图缓存按 7 天 / 200 条 / 100 MB 清理。")
                }

                SectionCard {
                    SectionTitle("后台运行")
                    HelpParagraph("有来源在运行时保持前台服务，界面退到后台或锁屏后推理继续；界面销毁不停止推理。")
                    HelpParagraph("运行意图按来源持久化，进程被系统回收后服务按运行意图恢复来源；用户主动停止会清除意图。")
                    HelpParagraph("Android 端不注册登录自启，不提供远程开关主程序的生命周期命令，也不改系统锁屏或唤醒设置。")
                }

                SectionCard {
                    SectionTitle("尚未验证")
                    HelpParagraph("以下边界必须真机验证，构建或模拟器通过都不代表已验收：")
                    HelpParagraph("· NNAPI 在各厂商芯片上的实际加速与回退行为；")
                    HelpParagraph("· 持续推理的发热与功耗；")
                    HelpParagraph("· 长时间锁屏后相机与前台服务的存活；")
                    HelpParagraph("· 远程镜头在弱网下的帧率与丢帧；")
                    HelpParagraph("· 平板横竖屏布局的目检。")
                }

                SectionCard {
                    SectionTitle("界面口径")
                    HelpParagraph("预览显示的是最后一帧处理结果，等画面、不裁切；来源停止后保留最后画面并明确标注，不会用旧帧冒充实时视频。")
                    HelpParagraph("手机用分区切换保证三个分区都能到达；平板（可用短边 ≥ 600dp）把主预览、来源列表与检查区并排放置。")
                }
            }
        }
    }
}

@Composable
private fun HelpParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

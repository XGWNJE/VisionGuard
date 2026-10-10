package com.xgwnje.visionguard.inference.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.inference.model.BundledAccelerationBenchmark
import com.xgwnje.visionguard.inference.ui.geo.Geo
import com.xgwnje.visionguard.inference.ui.geo.GeoDivider
import com.xgwnje.visionguard.inference.ui.geo.GeoFieldLabel
import com.xgwnje.visionguard.inference.ui.geo.GeoHint
import com.xgwnje.visionguard.inference.ui.geo.GeoKey
import com.xgwnje.visionguard.inference.ui.geo.GeoLampTone
import com.xgwnje.visionguard.inference.ui.geo.GeoSectionLabel
import com.xgwnje.visionguard.inference.ui.geo.GeoSurface
import com.xgwnje.visionguard.inference.ui.geo.GeoTone
import kotlinx.coroutines.launch

/**
 * 测量专用首屏（只在可调试的测量构建里出现）。
 *
 * 为什么单独做一个首屏：测量占用的这台设备必须能登录才能进主界面，而登录要求服务可达、
 * 账号可用；这些条件在测量现场不一定满足。测量本身只用本机算力与包内模型，与账号无关，
 * 所以把入口提到登录之前，同时保留「进入完整应用」的入口。
 */
@Composable
fun BenchmarkScreen(onOpenFullApp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by BundledAccelerationBenchmark.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Geo.Gap),
        verticalArrangement = Arrangement.spacedBy(Geo.Gap)
    ) {
        Text(
            "推理加速测量",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        GeoHint(
            "这个构建把模型与测试画面打进了安装包，只用本机算力，不需要登录、不需要连电脑。" +
                "测完把结果发回即可；需要看完整界面时再进入完整应用登录。"
        )

        GeoDivider()
        GeoSectionLabel("01", "开始测量", "只在本机运行")
        GeoHint(
            "把各推理档位（ONNX CPU / NNAPI，LiteRT CPU / GPU / NPU）在同一张真实画面上逐个跑一遍，" +
                "报单帧耗时中位数，并按各自运行时的 CPU 基线给出是否达标（门槛 ≤ 0.70×）。"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
            GeoKey(
                onClick = { scope.launch { BundledAccelerationBenchmark.run(context, onlyAccelerated = true) } },
                label = "快速测量",
                icon = LucideIcons.Play,
                busy = state.running,
                enabled = !state.running
            )
            GeoKey(
                onClick = { scope.launch { BundledAccelerationBenchmark.run(context, onlyAccelerated = false) } },
                label = "完整测量",
                icon = LucideIcons.Activity,
                busy = state.running,
                enabled = !state.running
            )
        }
        GeoHint("快速测量只跑加速档与其 CPU 基线；完整测量另跑逐线程 CPU 档，更慢。新机型首次跑 GPU 档可能要几十秒编译模型，属正常现象。")

        state.failure?.let { GeoHint("测量失败：$it", tone = GeoLampTone.Error) }

        if (state.running || state.current.isNotEmpty()) {
            GeoFieldLabel("进度")
            Text(
                state.current,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (state.report.isNotEmpty()) {
            GeoDivider()
            GeoSectionLabel("02", "结果", "可分享发回")
            GeoSurface(Modifier.fillMaxWidth()) {
                Text(
                    state.report,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(Geo.Gap)
                )
            }
            state.reportPath?.let { GeoHint("原始报告已写入：$it") }
            Row(horizontalArrangement = Arrangement.spacedBy(Geo.Gap)) {
                GeoKey(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "推理加速测量结果")
                            putExtra(Intent.EXTRA_TEXT, state.report)
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(intent, "分享测量结果"))
                        }
                    },
                    label = "分享结果",
                    icon = LucideIcons.ExternalLink
                )
                GeoKey(
                    onClick = { BundledAccelerationBenchmark.reset() },
                    label = "清空",
                    icon = LucideIcons.Trash2,
                    tone = GeoTone.Danger
                )
            }
        }

        GeoDivider()
        GeoSectionLabel("03", "完整应用", "需要登录")
        GeoHint("完整界面（来源、参数、遮罩、设置）需要登录统一服务；测量不需要。")
        GeoKey(
            onClick = onOpenFullApp,
            label = "进入完整应用",
            icon = LucideIcons.ChevronRight
        )
    }
}

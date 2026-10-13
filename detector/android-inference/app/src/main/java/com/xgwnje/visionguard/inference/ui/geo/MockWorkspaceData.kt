package com.xgwnje.visionguard.inference.ui.geo

import com.xgwnje.visionguard.inference.capture.LocalCameraStatus
import com.xgwnje.visionguard.inference.detect.ArgbImage
import com.xgwnje.visionguard.inference.detect.Detection
import com.xgwnje.visionguard.inference.detect.DetectionBox
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion
import com.xgwnje.visionguard.inference.runtime.MessageTone
import com.xgwnje.visionguard.inference.model.ModelInferenceTarget
import com.xgwnje.visionguard.inference.runtime.ModelDownloadState
import com.xgwnje.visionguard.inference.runtime.ModelState
import com.xgwnje.visionguard.inference.runtime.RuntimeUiState
import com.xgwnje.visionguard.inference.source.SourceCaptureMode
import com.xgwnje.visionguard.inference.source.SourceRuntimeState
import com.xgwnje.visionguard.inference.source.SourceView

/**
 * UI 验收用的模拟数据。
 *
 * 用覆盖常规、空态、异常与边界的一份快照喂给界面，可以在没有账号、相机、服务与真实推流时
 * 检查多来源列表、超长名称截断、未就绪 / 错误 / 等待状态、模型下载进度与检测框遮罩渲染。
 * 只在可调试的预览构建里可选，见「设置 → 维护 → UI 验收模拟数据」。
 */
object MockWorkspaceData {

    private const val FRAME_WIDTH = 640
    private const val FRAME_HEIGHT = 360

    /** 合成画面只生成一次：每帧重建会让预览位图反复转换，反而看不出真实观感。 */
    private val frame: ArgbImage by lazy { buildFrame() }

    fun state(nowElapsedMs: Long, nowWallMs: Long): RuntimeUiState {
        val sources = listOf(
            SourceView(
                sourceId = "camera",
                sourceName = "本机相机",
                mode = SourceCaptureMode.LocalCamera,
                params = DetectionParams(
                    confidencePercent = 45,
                    targetFps = 3,
                    cooldownSeconds = 5,
                    modelKey = "yolo26m_320_int8",
                    watchedClasses = setOf("person", "car", "dog")
                ),
                masks = listOf(MaskRegion(0.06f, 0.10f, 0.18f, 0.42f)),
                cameraLensFacing = 1,
                remotePublisherName = null,
                remotePublisherDeviceId = null,
                state = SourceRuntimeState(
                    isRunning = true,
                    isReady = true,
                    actualFps = 3.0,
                    backend = "CPU",
                    inferenceMs = 42,
                    lastProgressAtMs = nowElapsedMs - 780,
                    lastProgressWallMs = nowWallMs - 780,
                    lastAlertAtMs = nowElapsedMs - 9_400,
                    monitoringExpected = true,
                    processedFrames = 1_284,
                    failedFrames = 3,
                    preview = frame,
                    detections = listOf(
                        Detection(0, "person", 0.87f, DetectionBox(268f, 88f, 96f, 212f)),
                        Detection(2, "car", 0.62f, DetectionBox(360f, 168f, 208f, 120f)),
                        Detection(16, "dog", 0.41f, DetectionBox(392f, 236f, 120f, 88f))
                    ),
                    statusText = "运行中"
                )
            ),
            SourceView(
                sourceId = "signal-1",
                sourceName = "远程镜头 · 东门",
                mode = SourceCaptureMode.RemoteStream,
                params = DetectionParams(
                    confidencePercent = 60,
                    targetFps = 2,
                    cooldownSeconds = 30,
                    modelKey = "yolo26m_640_int8",
                    watchedClasses = setOf("person")
                ),
                masks = emptyList(),
                cameraLensFacing = 0,
                remotePublisherName = "推流节点 · 东门",
                remotePublisherDeviceId = "mock-publisher-east-gate",
                state = SourceRuntimeState(
                    isReady = true,
                    backend = "CPU",
                    processedFrames = 8_642,
                    failedFrames = 12,
                    lastProgressAtMs = nowElapsedMs - 42_000,
                    lastProgressWallMs = nowWallMs - 42_000,
                    statusText = "已停止"
                )
            ),
            SourceView(
                sourceId = "signal-2",
                sourceName = "远程镜头 · 北侧仓库超长名称用于检查文本截断与布局",
                mode = SourceCaptureMode.RemoteStream,
                params = DetectionParams(
                    confidencePercent = 45,
                    targetFps = 3,
                    cooldownSeconds = 5,
                    modelKey = "yolo26m_640",
                    watchedClasses = setOf("person", "bicycle", "car", "motorcycle", "bus", "truck", "cat", "dog", "backpack", "handbag")
                ),
                masks = emptyList(),
                cameraLensFacing = 0,
                remotePublisherName = "推流节点 · 北侧仓库（备用）",
                remotePublisherDeviceId = "mock-publisher-north-store",
                state = SourceRuntimeState(
                    isRunning = true,
                    monitoringExpected = true,
                    error = "媒体流中断：等待推流节点重连",
                    failedFrames = 217,
                    lastProgressAtMs = nowElapsedMs - 96_000,
                    lastProgressWallMs = nowWallMs - 96_000,
                    statusText = "媒体流中断"
                )
            ),
            SourceView(
                sourceId = "signal-3",
                sourceName = "远程镜头 · 待绑定",
                mode = SourceCaptureMode.RemoteStream,
                params = DetectionParams(
                    confidencePercent = 45,
                    targetFps = 3,
                    cooldownSeconds = 5,
                    modelKey = "yolo26m_320_int8",
                    watchedClasses = setOf("person")
                ),
                masks = emptyList(),
                cameraLensFacing = 0,
                remotePublisherName = null,
                remotePublisherDeviceId = null,
                state = SourceRuntimeState(
                    performanceWarning = "推理耗时波动较大",
                    statusText = "等待推流"
                )
            )
        )

        val models = listOf(
            ModelState("yolo26m_320", "YOLO26M · 320 GPU", downloaded = true, target = ModelInferenceTarget.Gpu),
            ModelState("yolo26m_320_int8", "YOLO26M · 320 CPU", downloaded = true),
            ModelState("yolo26m_640", "YOLO26M · 640 GPU", downloaded = false, target = ModelInferenceTarget.Gpu),
            ModelState("yolo26m_640_int8", "YOLO26M · 640 CPU", downloaded = true)
        )

        return RuntimeUiState(
            sources = sources,
            selectedSourceId = "camera",
            models = models,
            gpuAvailable = true,
            downloads = mapOf("yolo26m_640" to ModelDownloadState(41_000_000, 81_853_048)),
            outboxSize = 2,
            alertCount = 27,
            lastAlertAtMs = nowElapsedMs - 9_400,
            cameraStatus = LocalCameraStatus(running = true, width = 1920, height = 1080, rotation = 90),
            runningSourceCount = 2,
            message = "UI 验收模拟数据：多来源、异常态与长文本，不可用于真实检测",
            messageTone = MessageTone.Warning
        )
    }

    /** 合成一帧深色画面：不追求好看，只让预览、检测框与遮罩的叠加关系看得清。 */
    private fun buildFrame(): ArgbImage {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT)
        for (y in 0 until FRAME_HEIGHT) {
            for (x in 0 until FRAME_WIDTH) {
                val band = ((x * 32 / FRAME_WIDTH) + (y * 16 / FRAME_HEIGHT)) % 2
                val base = if (band == 0) 0x1E2A2E else 0x223034
                val vignette = 1f - ((x - FRAME_WIDTH / 2f).let { it * it } / (FRAME_WIDTH * FRAME_WIDTH * 0.9f))
                val r = (((base shr 16) and 0xFF) * vignette).toInt().coerceIn(0, 255)
                val g = (((base shr 8) and 0xFF) * vignette).toInt().coerceIn(0, 255)
                val b = ((base and 0xFF) * vignette).toInt().coerceIn(0, 255)
                pixels[y * FRAME_WIDTH + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ArgbImage(FRAME_WIDTH, FRAME_HEIGHT, pixels)
    }
}

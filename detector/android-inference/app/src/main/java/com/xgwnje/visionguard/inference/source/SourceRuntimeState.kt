package com.xgwnje.visionguard.inference.source

import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion
import java.time.Instant

/**
 * 一路来源对外暴露的运行状态，同时用于心跳上报与界面显示。
 *
 * 字段口径与 Windows `MonitorSourceStatus` / 心跳 `sources[]` 一致：
 * `actualFps` 只统计成功帧，`lastProgressAt` 只在成功处理后推进，`monitoringExpected` 表达运行意图。
 */
data class SourceRuntimeState(
    val isRunning: Boolean = false,
    val isReady: Boolean = false,
    val error: String? = null,
    val actualFps: Double = 0.0,
    val backend: String = "",
    val inferenceMs: Long = 0,
    /** 最后成功处理时间，本机单调时钟（与控制流同源，供界面算新鲜度）。 */
    val lastProgressAtMs: Long? = null,
    /** 同一时刻的 NTP 校正墙钟，只用于心跳上报 ISO 时间。 */
    val lastProgressWallMs: Long? = null,
    /** 最后一次报警时间，本机单调时钟。 */
    val lastAlertAtMs: Long? = null,
    val monitoringExpected: Boolean = false,
    val performanceWarning: String? = null,
    val processedFrames: Long = 0,
    val failedFrames: Long = 0,
    val preview: com.xgwnje.visionguard.inference.detect.ArgbImage? = null,
    val detections: List<com.xgwnje.visionguard.inference.detect.Detection> = emptyList(),
    val statusText: String = "未就绪"
)

/** 界面与心跳共用的来源视图。 */
data class SourceView(
    val sourceId: String,
    val sourceName: String,
    val mode: SourceCaptureMode,
    val params: DetectionParams,
    val masks: List<MaskRegion>,
    val cameraLensFacing: Int,
    val remotePublisherName: String?,
    val state: SourceRuntimeState
)

internal fun Long.toIsoOrNull(): String? = if (this <= 0L) null else Instant.ofEpochMilli(this).toString()

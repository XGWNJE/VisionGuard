package com.xgwnje.visionguard.inference.source

import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion

/** 来源类型：本机相机采集，或订阅同账号相机推流节点的远程镜头。 */
enum class SourceCaptureMode { LocalCamera, RemoteStream }

/**
 * 一路推理来源的完整配置。
 *
 * - `sourceId` 稳定不可改、只允许 `^[A-Za-z0-9_-]{1,64}$`，与统一服务校验一致；
 * - `sourceName` 可改，最长 64 个 UTF-16 单元，禁止控制字符；
 * - [masks] 属于本机来源配置，不通过 `set-config` 远程同步（与 Windows 语义一致）；
 * - `params` 是冷改动：运行中不允许修改，改动在下次启动该来源时生效。
 */
data class SourceConfig(
    val sourceId: String,
    val sourceName: String,
    val mode: SourceCaptureMode,
    val params: DetectionParams,
    val masks: List<MaskRegion> = emptyList(),
    val cameraLensFacing: Int = LENS_BACK,
    val remotePublisherDeviceId: String? = null,
    val remotePublisherName: String? = null
) {
    companion object {
        /** CameraX 镜头朝向：默认后置。 */
        const val LENS_BACK = 0
        const val LENS_FRONT = 1

        /** 本机相机来源的固定身份，避免与远程来源的稳定身份冲突。 */
        const val LOCAL_CAMERA_SOURCE_ID = "camera"

        const val MAX_NAME_LENGTH = 64
        private val SOURCE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun isValidSourceId(value: String): Boolean = SOURCE_ID_PATTERN.matches(value)

        /** 校验并归一化来源名；非法时返回原因，合法时返回 null。 */
        fun validateName(value: String): String? {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return "来源名称不能为空"
            if (trimmed.length > MAX_NAME_LENGTH) return "来源名称最多 $MAX_NAME_LENGTH 个字符"
            if (trimmed.any { it.code in 0x00..0x1F }) return "来源名称不能包含控制字符"
            return null
        }

        fun normalizeName(value: String): String = value.trim()

        /** 新来源默认名：本机相机为“本机相机”，远程镜头优先使用对端上报的名字。 */
        fun defaultName(mode: SourceCaptureMode, remoteName: String?): String = when {
            mode == SourceCaptureMode.LocalCamera -> "本机相机"
            !remoteName.isNullOrBlank() -> normalizeName(remoteName).take(MAX_NAME_LENGTH)
            else -> "远程镜头"
        }

        /** 远程来源的稳定身份直接沿用服务端下发的 `sourceId`，保证重连后不新增来源。 */
        fun forRemoteStream(
            streamSourceId: String,
            streamSourceName: String?,
            publisherDeviceId: String?,
            publisherName: String?,
            params: DetectionParams,
            masks: List<MaskRegion>
        ): SourceConfig = SourceConfig(
            sourceId = streamSourceId,
            sourceName = defaultName(SourceCaptureMode.RemoteStream, streamSourceName),
            mode = SourceCaptureMode.RemoteStream,
            params = params,
            masks = masks,
            remotePublisherDeviceId = publisherDeviceId,
            remotePublisherName = publisherName
        )

        fun forLocalCamera(params: DetectionParams, masks: List<MaskRegion>, lensFacing: Int): SourceConfig = SourceConfig(
            sourceId = LOCAL_CAMERA_SOURCE_ID,
            sourceName = defaultName(SourceCaptureMode.LocalCamera, null),
            mode = SourceCaptureMode.LocalCamera,
            params = params,
            masks = masks,
            cameraLensFacing = lensFacing
        )
    }
}

package com.xgwnje.visionguard.inference.source

import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 来源身份与名称规则：与服务端 `sourceId` 校验和命名规范保持一致。 */
class SourceConfigTest {

    private val params = DetectionParams(modelKey = "yolo26n_320")

    @Test
    fun `来源身份只接受服务端允许的字符集`() {
        assertTrue(SourceConfig.isValidSourceId("camera"))
        assertTrue(SourceConfig.isValidSourceId("signal-1"))
        assertTrue(SourceConfig.isValidSourceId("front_door_2"))
        assertFalse(SourceConfig.isValidSourceId(""))
        assertFalse(SourceConfig.isValidSourceId("有中文"))
        assertFalse(SourceConfig.isValidSourceId("has space"))
        assertFalse(SourceConfig.isValidSourceId("a".repeat(65)))
    }

    @Test
    fun `来源名称为空 超长或含控制字符时拒绝`() {
        assertNull(SourceConfig.validateName("正门入口"))
        assertNull(SourceConfig.validateName(" 前后空白会被去掉 "))
        assertEquals("来源名称不能为空", SourceConfig.validateName("   "))
        assertNotNull(SourceConfig.validateName("a".repeat(SourceConfig.MAX_NAME_LENGTH + 1)))
        assertNotNull(SourceConfig.validateName("换行\n名字"))
        assertNotNull(SourceConfig.validateName("制表\t名字"))
    }

    @Test
    fun `名称归一化只去除首尾空白`() {
        assertEquals("正门 入口", SourceConfig.normalizeName("  正门 入口  "))
    }

    @Test
    fun `本机相机来源使用固定身份与默认名`() {
        val config = SourceConfig.forLocalCamera(params, emptyList(), SourceConfig.LENS_FRONT)
        assertEquals("camera", config.sourceId)
        assertEquals("本机相机", config.sourceName)
        assertEquals(SourceCaptureMode.LocalCamera, config.mode)
        assertEquals(SourceConfig.LENS_FRONT, config.cameraLensFacing)
    }

    @Test
    fun `远程来源沿用服务端身份避免重连后新增来源`() {
        val config = SourceConfig.forRemoteStream(
            streamSourceId = "signal-3",
            streamSourceName = "仓库通道",
            publisherDeviceId = "dev-camera",
            publisherName = "移动巡检相机",
            params = params,
            masks = listOf(MaskRegion(0.1f, 0.1f, 0.2f, 0.2f))
        )
        assertEquals("signal-3", config.sourceId)
        assertEquals("仓库通道", config.sourceName)
        assertEquals(SourceCaptureMode.RemoteStream, config.mode)
        assertEquals("dev-camera", config.remotePublisherDeviceId)
        assertEquals(1, config.masks.size)
    }

    @Test
    fun `远程来源缺少对端名称时使用回退名并不超长`() {
        assertEquals("远程镜头", SourceConfig.defaultName(SourceCaptureMode.RemoteStream, null))
        val long = "名".repeat(100)
        assertEquals(SourceConfig.MAX_NAME_LENGTH, SourceConfig.defaultName(SourceCaptureMode.RemoteStream, long).length)
    }
}

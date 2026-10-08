package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检测参数边界与目标串解析：与 Windows 端同一取值口径。 */
class DetectionParamsTest {

    @Test
    fun `默认值与本端声明一致`() {
        val params = DetectionParams(modelKey = "yolo26n_320")
        assertEquals(45, params.confidencePercent)
        assertEquals(3, params.targetFps)
        assertEquals(5, params.cooldownSeconds)
        assertEquals(setOf("person"), params.watchedClasses)
        assertEquals(0.45f, params.confidence, 1e-6f)
    }

    @Test
    fun `置信度换算为阈值`() {
        assertEquals(0.1f, DetectionParams(modelKey = "m", confidencePercent = 10).confidence, 1e-6f)
        assertEquals(0.95f, DetectionParams(modelKey = "m", confidencePercent = 95).confidence, 1e-6f)
    }

    @Test
    fun `边界校验只放行约定范围`() {
        assertNull(DetectionParams.validateConfidence(10))
        assertNull(DetectionParams.validateConfidence(95))
        assertNotNull(DetectionParams.validateConfidence(9))
        assertNotNull(DetectionParams.validateConfidence(96))

        assertNull(DetectionParams.validateTargetFps(1))
        assertNull(DetectionParams.validateTargetFps(5))
        assertNotNull(DetectionParams.validateTargetFps(0))
        assertNotNull(DetectionParams.validateTargetFps(6))

        assertNull(DetectionParams.validateCooldown(1))
        assertNull(DetectionParams.validateCooldown(300))
        assertNotNull(DetectionParams.validateCooldown(0))
        assertNotNull(DetectionParams.validateCooldown(301))
    }

    @Test
    fun `空目标串归一化为 person`() {
        assertEquals(setOf("person"), DetectionParams.parseWatchedClasses(null))
        assertEquals(setOf("person"), DetectionParams.parseWatchedClasses(""))
        assertEquals(setOf("person"), DetectionParams.parseWatchedClasses("  "))
    }

    @Test
    fun `多目标按逗号解析并去重`() {
        assertEquals(setOf("person", "car"), DetectionParams.parseWatchedClasses("person, car,person"))
    }

    @Test
    fun `未知类名或超长串整体拒绝`() {
        assertNull(DetectionParams.parseWatchedClasses("person,unknown-class"))
        assertNull(DetectionParams.parseWatchedClasses("人"))
        assertNull(DetectionParams.parseWatchedClasses("a".repeat(DetectionParams.MAX_TARGETS_TEXT_LENGTH + 1)))
    }

    @Test
    fun `目标串往返可解析`() {
        val params = DetectionParams(modelKey = "m", watchedClasses = setOf("person", "truck"))
        val text = params.watchedClassesText()
        assertTrue(text.contains("person") && text.contains("truck"))
        assertEquals(params.watchedClasses, DetectionParams.parseWatchedClasses(text))
    }
}

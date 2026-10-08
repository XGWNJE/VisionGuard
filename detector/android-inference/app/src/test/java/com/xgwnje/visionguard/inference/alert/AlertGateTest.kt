package com.xgwnje.visionguard.inference.alert

import com.xgwnje.visionguard.inference.detect.Detection
import com.xgwnje.visionguard.inference.detect.DetectionBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun detection(label: String = "person", confidence: Float = 0.9f) =
    Detection(classId = 0, label = label, confidence = confidence, box = DetectionBox(1f, 2f, 3f, 4f))

/** 逐来源报警冷却：与 Windows `Services/AlertService.cs` 的 `_lastAlertTime` 语义一致。 */
class AlertGateTest {

    @Test
    fun `没有检测框时不报警`() {
        val gate = AlertGate()
        assertFalse(gate.shouldAlert(emptyList(), nowMs = 1_000, cooldownSeconds = 5))
        assertEquals(null, gate.lastAlertAtMs())
    }

    @Test
    fun `首次检测立即报警并记录时间`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert(listOf(detection()), nowMs = 1_000, cooldownSeconds = 5))
        assertEquals(1_000L, gate.lastAlertAtMs())
    }

    @Test
    fun `冷却期内不重复报警`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert(listOf(detection()), 1_000, 5))
        assertFalse(gate.shouldAlert(listOf(detection()), 5_999, 5))
        assertTrue(gate.shouldAlert(listOf(detection()), 6_000, 5))
    }

    @Test
    fun `冷却判定使用传入时间而不是系统时钟`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert(listOf(detection()), 100_000, 1))
        // 时间回退（NTP 校正前后）时不应误判为已过冷却
        assertFalse(gate.shouldAlert(listOf(detection()), 50_000, 1))
    }

    @Test
    fun `重置后可以立即再次报警`() {
        val gate = AlertGate()
        assertTrue(gate.shouldAlert(listOf(detection()), 1_000, 300))
        gate.reset()
        assertTrue(gate.shouldAlert(listOf(detection()), 2_000, 300))
    }
}

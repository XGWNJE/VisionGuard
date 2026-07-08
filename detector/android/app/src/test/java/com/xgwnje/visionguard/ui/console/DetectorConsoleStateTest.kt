package com.xgwnje.visionguard.ui.console

import com.xgwnje.visionguard.data.model.MaskRegion
import com.xgwnje.visionguard.data.model.MonitorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorConsoleStateTest {

    @Test
    fun monitoringKeepsCalibrationColdAndShowsPendingDraft() {
        val applied = MonitorConfig(modelName = "yolo26n", inputSize = 320)
        val draft = applied.copy(modelName = "yolo26s", inputSize = 640, useHighResolution = true)

        val state = buildDetectorConsoleState(
            deploymentOrientation = DeploymentOrientation.PORTRAIT,
            draftConfig = draft,
            appliedConfig = applied,
            isMonitoring = true,
            isReady = true,
            hasCalibrationFrame = true
        )

        assertTrue(state.hasPendingChanges)
        assertFalse(state.canEnterCalibration)
        assertEquals("有参数待生效，停止后重新开启应用", state.pendingChangeText)
        assertEquals("停止监控", state.primaryActionLabel)
        assertEquals(ConsoleDestination.RUN, state.destination)
    }

    @Test
    fun uncalibratedStoppedConsoleAllowsStartWithConfirmation() {
        val config = MonitorConfig(maskRegions = emptyList())

        val state = buildDetectorConsoleState(
            deploymentOrientation = DeploymentOrientation.LANDSCAPE,
            draftConfig = config,
            appliedConfig = config,
            isMonitoring = false,
            isReady = true,
            hasCalibrationFrame = false
        )

        assertTrue(state.canStartMonitoring)
        assertTrue(state.requiresUncalibratedStartConfirmation)
        assertEquals("建议先校准取景", state.calibrationHintText)
        assertEquals("暂无最近帧，停止后可校准取景", state.emptyFrameText)
    }

    @Test
    fun orientationIsRequiredBeforeConsole() {
        val config = MonitorConfig()

        val state = buildDetectorConsoleState(
            deploymentOrientation = null,
            draftConfig = config,
            appliedConfig = config,
            isMonitoring = false,
            isReady = true,
            hasCalibrationFrame = false
        )

        assertEquals(ConsoleDestination.ORIENTATION_SETUP, state.destination)
        assertFalse(state.canStartMonitoring)
        assertFalse(state.canEnterCalibration)
    }

    @Test
    fun existingCalibrationSuppressesStartConfirmation() {
        val config = MonitorConfig(
            maskRegions = listOf(MaskRegion(left = 0.1f, top = 0.1f, right = 0.2f, bottom = 0.2f))
        )

        val state = buildDetectorConsoleState(
            deploymentOrientation = DeploymentOrientation.PORTRAIT,
            draftConfig = config,
            appliedConfig = config,
            isMonitoring = false,
            isReady = true,
            hasCalibrationFrame = false
        )

        assertFalse(state.requiresUncalibratedStartConfirmation)
        assertEquals(null, state.calibrationHintText)
    }

    @Test
    fun remoteConfigAckTextMatchesColdApplyRule() {
        assertEquals("已保存，重启监控后生效", remoteConfigAckText(isMonitoring = true))
        assertEquals("已保存，下次启动监控时生效", remoteConfigAckText(isMonitoring = false))
    }
}

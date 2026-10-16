package com.xgwnje.visionguard.notifier

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xgwnje.visionguard.notifier.ui.dialogs.AlarmSurface
import com.xgwnje.visionguard.notifier.ui.dialogs.AlarmDialog
import com.xgwnje.visionguard.notifier.ui.main.NotificationHome
import com.xgwnje.visionguard.notifier.node.NodeState
import com.xgwnje.visionguard.notifier.ui.theme.NotificationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotifierInterfaceTest {
    @get:Rule val ui = createComposeRule()
    private val item = AlertQueueItem("first", "视觉检测", "visionguard", "门厅 · 入口相机",
        1_792_216_328_000, 1_792_216_328_000, RingtoneLibrary.SILENT_VALUE, 10, 0, 1, "模拟事件", DetectedObject("person", .962))

    @Test fun onlyExplicitButtonAcknowledgesTheDisplayedId() {
        val confirmed = mutableListOf<String>()
        ui.setContent { NotificationTheme { AlarmSurface(item, 2, false, null, { confirmed += it }) } }
        ui.onNodeWithText("发现人员").performTouchInput { click() }
        ui.onNodeWithText("模拟事件").performTouchInput { click() }
        ui.onNodeWithText("排队 2 条").performTouchInput { click() }
        ui.runOnIdle { assertTrue(confirmed.isEmpty()) }
        ui.onNodeWithText("确认当前报警").performClick()
        ui.runOnIdle { assertEquals(listOf("first"), confirmed) }
    }

    @Test fun inFlightConfirmationIsDisabledAndFailureCanBeRetried() {
        val busy = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var confirmed = 0
        ui.setContent { NotificationTheme { AlarmSurface(item, 0, busy.value, error.value, { confirmed++ }) } }
        ui.onNodeWithText("确认中…").assertIsNotEnabled()
        ui.runOnIdle { busy.value = false; error.value = "确认保存失败，请重试" }
        ui.onNodeWithText("确认保存失败，请重试").assertIsDisplayed()
        ui.onNodeWithText("确认当前报警").assertIsEnabled().performClick()
        ui.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun fullScreenOverlayBlocksUnderlyingControlsAndBack() {
        var underlyingClicks = 0
        var confirmed = 0
        ui.setContent { NotificationTheme {
            Box(Modifier.fillMaxSize()) {
                Button({ underlyingClicks++ }, Modifier.align(Alignment.Center)) { Text("底层操作") }
                AlarmDialog(item, 0, false, null) { confirmed++ }
            }
        } }
        ui.onRoot().performTouchInput { click(center) }
        androidx.test.espresso.Espresso.pressBackUnconditionally()
        ui.onNodeWithText("当前报警").assertIsDisplayed()
        ui.runOnIdle { assertEquals(0, underlyingClicks); assertEquals(0, confirmed) }
        ui.onNodeWithText("确认当前报警").performTouchInput { click() }
        ui.runOnIdle { assertEquals(1, confirmed) }
    }

    @Test fun nonVisualAlarmDoesNotInventTargetOrConfidence() {
        ui.setContent { NotificationTheme { AlarmSurface(item.copy(keyword = "服务中断", detectedObject = null, sourceApp = null, snippet = null), 0, false, null, {}) } }
        ui.onAllNodesWithText("服务中断").assertCountEquals(2)
        ui.onNodeWithText("置信度", substring = true).assertDoesNotExist()
        ui.onAllNodesWithText("未报告").assertCountEquals(2)
    }

    @Test fun workbenchExposesOfflineRecoveryPermissionAndEmptyState() {
        var reconnects = 0
        var permissions = 0
        ui.setContent { NotificationTheme {
            NotificationHome(NodeState(status = "连接失败，等待重试"), true, false, emptyList(), "Asia/Shanghai",
                { reconnects++ }, { permissions++ }, {})
        } }
        ui.onNodeWithText("连接失败，等待重试").assertIsDisplayed()
        ui.onNodeWithText("重新连接").performClick()
        ui.onNodeWithText("去授权").performScrollTo().performClick()
        ui.onNodeWithText("暂无报警记录").performScrollTo().assertIsDisplayed()
        ui.runOnIdle { assertEquals(1, reconnects); assertEquals(1, permissions) }
    }

    @Test fun staleConfirmationAndHistoryCleanupLeaveNextAlertIntact() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("interface-test-$name", mode)
        }
        val prefs = SharedPreferencesHelper(isolated)
        prefs.clearAccountData()
        assertTrue(prefs.acceptRemoteAlert("first", "检测", "节点", "模拟", 1000, 31000, 2000))
        assertTrue(prefs.acceptRemoteAlert("next", "检测", "节点", "模拟", 1000, 31000, 2000))
        assertTrue(prefs.finishActiveAlert("first", AlertEndType.MANUAL).success)
        assertFalse(prefs.finishActiveAlert("first", AlertEndType.MANUAL).success)
        assertEquals("next", prefs.getActiveAlert()?.id)
        prefs.clearAlertHistory()
        assertEquals("next", prefs.getActiveAlert()?.id)
        assertTrue(prefs.getAlertHistory().isEmpty())
        assertTrue(prefs.finishActiveAlert("next", AlertEndType.AUTO).success)
        assertEquals(AlertEndType.AUTO, prefs.getAlertHistory().single().endType)
    }

    @Test fun failedConfirmationKeepsCurrentItemUntilSuccessfulRetry() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var rejectCommit = false
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val actual = base.getSharedPreferences("interface-failure-$name", mode)
                return object : SharedPreferences by actual {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = actual.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                            override fun commit() = if (rejectCommit) false else editor.commit()
                        }
                    }
                }
            }
        }
        val prefs = SharedPreferencesHelper(isolated)
        prefs.clearAccountData()
        assertTrue(prefs.acceptRemoteAlert("first", "检测", "节点", "模拟", 1000, 31000, 2000))
        rejectCommit = true
        assertFalse(prefs.finishActiveAlert("first", AlertEndType.MANUAL).success)
        assertEquals("first", prefs.getActiveAlert()?.id)
        assertTrue(prefs.getAlertHistory().isEmpty())
        rejectCommit = false
        assertTrue(prefs.finishActiveAlert("first", AlertEndType.MANUAL).success)
        assertTrue(prefs.getAlertQueue().isEmpty())
    }
}

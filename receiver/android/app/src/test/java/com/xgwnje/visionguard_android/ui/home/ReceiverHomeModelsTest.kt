package com.xgwnje.visionguard_android.ui.home

import com.xgwnje.visionguard_android.data.model.AlertMessage
import com.xgwnje.visionguard_android.data.model.BoundingBox
import com.xgwnje.visionguard_android.data.model.Detection
import com.xgwnje.visionguard_android.data.model.DeviceConfig
import com.xgwnje.visionguard_android.data.model.DeviceInfo
import com.google.gson.Gson
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverHomeModelsTest {

    @Test
    fun alertListChromeOmitsStandaloneTitleHeader() {
        val chrome = buildAlertListChrome()

        assertEquals(false, chrome.showStandaloneHeader)
    }

    @Test
    fun alertListChromeDoesNotReserveTopGapBecauseBannerOverlaysContent() {
        val chrome = buildAlertListChrome()

        assertEquals(0, chrome.topPaddingDp)
    }

    @Test
    fun frostedOverlayUsesConsistentTranslucencyForTopAndBottomChrome() {
        val overlay = buildFrostedOverlaySpec()

        assertEquals(0.86f, overlay.topBannerAlpha, 0.001f)
        assertEquals(overlay.topBannerAlpha, overlay.bottomBarAlpha, 0.001f)
        assertEquals(0f, overlay.shadowElevationDp, 0.001f)
    }

    @Test
    fun receiverMainTabsOnlyExposeAlertAndDevice() {
        val tabs = receiverMainTabs()

        assertEquals(listOf("alertList", "deviceList"), tabs.map { it.route })
        assertEquals(listOf("警报", "设备"), tabs.map { it.label })
    }

    @Test
    fun alertDetailChromeKeepsOnlyScreenshotViewerBehavior() {
        val chrome = buildAlertDetailChrome()

        assertEquals(false, chrome.showMetadataText)
        assertEquals(true, chrome.supportsPinchZoom)
        assertEquals(true, chrome.supportsGallerySave)
    }

    @Test
    fun deviceCardModelUsesPlainStatusCopyAndControlAction() {
        val monitoring = DeviceInfo(
            deviceId = "d1",
            deviceName = "Win-门口",
            online = true,
            isMonitoring = true,
            isReady = true,
            lastSeen = ""
        )
        val offline = monitoring.copy(online = false, isMonitoring = false)

        val monitoringModel = buildDeviceCardUiModel(monitoring)
        val offlineModel = buildDeviceCardUiModel(offline)

        assertEquals("Win-门口", monitoringModel.deviceName)
        assertEquals("监控中", monitoringModel.statusLabel)
        assertEquals(DeviceStatusTone.MONITORING, monitoringModel.statusTone)
        assertEquals("停止监控", monitoringModel.controlActionLabel)
        assertEquals("pause", monitoringModel.controlCommand)
        assertEquals(true, monitoringModel.controlsEnabled)

        assertEquals("离线", offlineModel.statusLabel)
        assertEquals(DeviceStatusTone.OFFLINE, offlineModel.statusTone)
        assertEquals(false, offlineModel.controlsEnabled)
    }

    @Test
    fun deviceCardModelUsesBalancedHeaderAndTypeIllustration() {
        val windowsDevice = DeviceInfo(
            deviceId = "win-1",
            deviceName = "Win-门口",
            online = true,
            isMonitoring = true,
            isReady = true,
            lastSeen = "",
            clientType = "windows"
        )
        val androidDetector = windowsDevice.copy(
            deviceId = "android-1",
            deviceName = "Android-仓库",
            clientType = "android-detector"
        )
        val unknown = windowsDevice.copy(
            deviceId = "unknown-1",
            deviceName = "未知设备",
            clientType = "custom-detector"
        )

        val windowsModel = buildDeviceCardUiModel(windowsDevice)
        val androidModel = buildDeviceCardUiModel(androidDetector)
        val unknownModel = buildDeviceCardUiModel(unknown)

        assertEquals(DeviceCardFirstRowLayout.BALANCED_TWO_COLUMN, windowsModel.firstRowLayout)
        assertEquals(DeviceCardIllustration.WINDOWS_DESKTOP, windowsModel.illustration)
        assertEquals("Windows识别端", windowsModel.typeLabel)

        assertEquals(DeviceCardIllustration.ANDROID_CAMERA, androidModel.illustration)
        assertEquals("安卓识别端", androidModel.typeLabel)

        assertEquals(DeviceCardIllustration.GENERIC_VIEWFINDER, unknownModel.illustration)
        assertEquals("识别端", unknownModel.typeLabel)
    }

    @Test
    fun deviceCardChromeSeparatesHeroBackgroundFromActionArea() {
        val chrome = buildDeviceCardChrome()

        assertEquals(28, chrome.cardCornerRadiusDp)
        assertEquals(128, chrome.heroHeightDp)
        assertEquals(22, chrome.heroContentHorizontalPaddingDp)
        assertEquals(false, chrome.titleHasContainer)
        assertEquals(true, chrome.statusUsesCompactPill)
        assertEquals(18, chrome.actionAreaHorizontalPaddingDp)
        assertEquals(16, chrome.actionAreaVerticalPaddingDp)
        assertEquals(52, chrome.actionButtonHeightDp)
        assertEquals(0.58f, chrome.heroBackgroundAlpha, 0.001f)
        assertEquals(1.08f, chrome.heroBackgroundScale, 0.001f)
    }

    @Test
    fun deviceConfigEditorUsesBottomSheetAndBatchApply() {
        val device = DeviceInfo(
            deviceId = "d1",
            deviceName = "P1-9",
            online = true,
            isMonitoring = false,
            isReady = true,
            lastSeen = ""
        )
        val initialConfig = DeviceConfig(cooldown = 5, confidence = 0.45, targets = "person")

        val model = buildDeviceConfigEditorUiModel(
            device = device,
            initialConfig = initialConfig,
            editedCooldown = 30f,
            editedConfidence = 0.60f,
            selectedTargets = setOf("person", "car")
        )

        assertEquals("P1-9", model.deviceName)
        assertEquals(DeviceConfigEditorPresentation.BOTTOM_SHEET, model.presentation)
        assertEquals(DeviceConfigApplyMode.BATCH, model.applyMode)
        assertEquals(true, model.hasChanges)
        assertEquals(true, model.applyEnabled)
        assertEquals("应用更改", model.applyActionLabel)
    }

    @Test
    fun deviceConfigEditorDisablesApplyWhenNothingChanged() {
        val device = DeviceInfo(
            deviceId = "d1",
            deviceName = "P1-9",
            online = true,
            isMonitoring = false,
            isReady = true,
            lastSeen = ""
        )
        val initialConfig = DeviceConfig(cooldown = 5, confidence = 0.45, targets = "person,car")

        val model = buildDeviceConfigEditorUiModel(
            device = device,
            initialConfig = initialConfig,
            editedCooldown = 5f,
            editedConfidence = 0.45f,
            selectedTargets = setOf("car", "person")
        )

        assertEquals(false, model.hasChanges)
        assertEquals(false, model.applyEnabled)
    }

    @Test
    fun deviceConfigChangesOnlyIncludesEditedValues() {
        val initialConfig = DeviceConfig(cooldown = 5, confidence = 0.45, targets = "person")

        val changes = buildDeviceConfigChanges(
            initialConfig = initialConfig,
            editedCooldown = 30f,
            editedConfidence = 0.45f,
            selectedTargets = setOf("person", "car"),
            editedTargetSamplingRate = 3,
            editedModelKey = ""
        )

        assertEquals(listOf("cooldown", "targets"), changes.map { it.key })
        assertEquals(listOf("30", "car,person"), changes.map { it.value })
    }

    @Test
    fun alertMergeSortKeepsNewestServerCreatedAtFirstAndDeduplicatesScreenshots() {
        val newestLocal = AlertMessage(
            alertId = "new-local",
            deviceId = "win",
            timestamp = "2026-07-07T12:00:00.000+08:00",
            createdAt = 1_000L
        )
        val olderHistory = AlertMessage(
            alertId = "old-history",
            deviceId = "android",
            timestamp = "2026-07-07T11:58:00.000+08:00",
            createdAt = 900L
        )
        val screenshotUpdate = newestLocal.copy(
            hasScreenshot = true,
            screenshotUrl = "/screenshots/new-local.jpg"
        )

        val merged = mergeSortAlerts(
            existing = listOf(newestLocal),
            incoming = listOf(olderHistory, screenshotUpdate)
        )

        assertEquals(listOf("new-local", "old-history"), merged.map { it.alertId })
        assertEquals(true, merged.first().hasScreenshot)
        assertEquals("/screenshots/new-local.jpg", merged.first().screenshotUrl)
    }

    @Test
    fun deviceConfigChangesIncludesDiscreteCooldownSamplingRateAndModel() {
        val initialConfig = DeviceConfig(
            cooldown = 10,
            confidence = 0.45,
            targets = "person",
            targetSamplingRate = 3,
            modelKey = "yolo26n_320"
        )

        val changes = buildDeviceConfigChanges(
            initialConfig = initialConfig,
            editedCooldown = 100f,
            editedConfidence = 0.45f,
            selectedTargets = setOf("person"),
            editedTargetSamplingRate = 5,
            editedModelKey = "yolo26s_640"
        )

        assertEquals(listOf("cooldown", "targetSamplingRate", "modelKey"), changes.map { it.key })
        assertEquals(listOf("100", "5", "yolo26s_640"), changes.map { it.value })
    }

    @Test
    fun deviceConfigFromDeviceToleratesLegacyDeviceListWithoutModelFields() {
        val legacyDevice = Gson().fromJson(
            """
            {
              "deviceId": "legacy-win",
              "deviceName": "Old Windows",
              "online": true,
              "isMonitoring": false,
              "isReady": true,
              "lastSeen": "",
              "cooldown": 30,
              "confidence": 0.45,
              "targets": "person",
              "clientType": "windows"
            }
            """.trimIndent(),
            DeviceInfo::class.java
        )

        val config = buildDeviceConfigFromDevice(legacyDevice)

        assertEquals(30, config.cooldown)
        assertEquals(3, config.targetSamplingRate)
        assertEquals("", config.modelKey)
    }

    @Test
    fun updateFeedbackTextAlwaysIncludesCurrentVersion() {
        val currentVersion = "4.2.1"

        assertEquals(
            "当前 4.2.1，已是最新",
            buildUpdateFeedbackText(UpdateFeedback.NO_UPDATE, currentVersion)
        )
        assertEquals(
            "当前 4.2.1，检查失败",
            buildUpdateFeedbackText(UpdateFeedback.CHECK_FAILED, currentVersion)
        )
        assertEquals(
            "最新 4.3.0\n当前 4.2.1\n\n是否更新？",
            buildUpdateDialogText(latestVersion = "4.3.0", currentVersion = currentVersion)
        )
    }

    @Test
    fun updateDialogModelUsesReceiverChromeCopy() {
        val model = buildUpdateDialogModel(latestVersion = "4.3.0", currentVersion = "4.2.1")

        assertEquals("发现新版本", model.title)
        assertEquals("当前 4.2.1", model.currentVersionLabel)
        assertEquals("最新 4.3.0", model.latestVersionLabel)
        assertEquals("是否更新？", model.message)
        assertEquals("更新", model.primaryActionLabel)
        assertEquals("稍后", model.secondaryActionLabel)
        assertEquals(UpdateDialogTone.AVAILABLE, model.tone)
    }

    @Test
    fun noUpdateDialogModelReportsCurrentVersion() {
        val model = buildNoUpdateDialogModel(currentVersion = "4.2.1")

        assertEquals("已是最新版本", model.title)
        assertEquals("当前 4.2.1", model.currentVersionLabel)
        assertEquals("状态：无需更新", model.latestVersionLabel)
        assertEquals("无需操作。", model.message)
        assertEquals("知道了", model.primaryActionLabel)
        assertEquals(null, model.secondaryActionLabel)
        assertEquals(UpdateDialogTone.CURRENT, model.tone)
    }

    @Test
    fun updateFailedDialogModelReportsCurrentVersion() {
        val model = buildUpdateFailedDialogModel(currentVersion = "4.2.1")

        assertEquals("检查失败", model.title)
        assertEquals("当前 4.2.1", model.currentVersionLabel)
        assertEquals("状态：稍后再试", model.latestVersionLabel)
        assertEquals("暂时无法获取更新信息。", model.message)
        assertEquals("知道了", model.primaryActionLabel)
        assertEquals(null, model.secondaryActionLabel)
        assertEquals(UpdateDialogTone.FAILED, model.tone)
    }

    @Test
    fun summaryCountsTodayAlertsOnlineDevicesAndLatestAlertTime() {
        val now = ZonedDateTime.parse("2026-07-07T10:30:00+08:00")
        val alerts = listOf(
            AlertMessage(
                alertId = "a-latest",
                deviceName = "Win-客厅摄像头",
                timestamp = "2026-07-07T09:48:15.000+08:00"
            ),
            AlertMessage(
                alertId = "a-yesterday",
                deviceName = "Win-侧门监控",
                timestamp = "2026-07-06T23:59:59.000+08:00"
            )
        )
        val devices = listOf(
            DeviceInfo("d1", "客厅", online = true, isMonitoring = true, isReady = true, lastSeen = ""),
            DeviceInfo("d2", "仓库", online = false, isMonitoring = false, isReady = true, lastSeen = ""),
            DeviceInfo("d3", "侧门", online = true, isMonitoring = false, isReady = true, lastSeen = "")
        )

        val summary = buildReceiverHomeSummary(alerts, devices, now)

        assertEquals(1, summary.todayAlertCount)
        assertEquals(2, summary.onlineDeviceCount)
        assertEquals("09:48", summary.latestAlertTimeLabel)
    }

    @Test
    fun summaryUsesEmptyLatestTimeWhenThereAreNoAlerts() {
        val now = ZonedDateTime.parse("2026-07-07T10:30:00+08:00")

        val summary = buildReceiverHomeSummary(emptyList(), emptyList(), now)

        assertEquals(0, summary.todayAlertCount)
        assertEquals(0, summary.onlineDeviceCount)
        assertEquals("--:--", summary.latestAlertTimeLabel)
    }

    @Test
    fun alertCardModelLimitsChipsAndMarksSyncedScreenshot() {
        val alert = AlertMessage(
            alertId = "a1",
            deviceName = "Android-仓库门口",
            timestamp = "2026-07-07T12:48:00.000+08:00",
            hasScreenshot = true,
            detections = listOf(
                detection("person", 0.914),
                detection("car", 0.744),
                detection("bicycle", 0.661),
                detection("truck", 0.552)
            )
        )

        val model = buildAlertCardUiModel(alert)

        assertEquals("Android-仓库门口", model.deviceName)
        assertEquals("12:48", model.timeLabel)
        assertEquals("2026.07.07 12:48", model.dateTimeLabel)
        assertEquals("查看详情", model.detailIconContentDescription)
        assertEquals(ScreenshotState.SYNCED, model.screenshotState)
        assertEquals(3, model.targetChips.size)
        assertEquals("人员", model.targetChips[0].label)
        assertEquals(91, model.targetChips[0].confidencePercent)
        assertEquals(DetectionTarget.PERSON, model.targetChips[0].target)
    }

    @Test
    fun alertCardModelUsesWaitingScreenshotStateWhenNoScreenshotMetadataExists() {
        val alert = AlertMessage(
            alertId = "a2",
            deviceName = "Win-侧门监控",
            timestamp = "2026-07-07T12:35:00.000+08:00",
            hasScreenshot = false,
            detections = listOf(detection("unknown", 0.5))
        )

        val model = buildAlertCardUiModel(alert)

        assertEquals(ScreenshotState.WAITING, model.screenshotState)
        assertEquals(DetectionTarget.UNKNOWN, model.targetChips[0].target)
        assertEquals("未知目标", model.targetChips[0].label)
    }

    private fun detection(label: String, confidence: Double): Detection =
        Detection(
            label = label,
            confidence = confidence,
            bbox = BoundingBox(x = 0f, y = 0f, w = 0.2f, h = 0.2f)
        )
}

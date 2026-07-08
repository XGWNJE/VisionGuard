package com.xgwnje.visionguard.ui.console

import com.xgwnje.visionguard.data.model.MonitorConfig

typealias DeploymentOrientation = com.xgwnje.visionguard.data.model.DeploymentOrientation

enum class ConsoleDestination {
    ORIENTATION_SETUP,
    RUN
}

enum class ConsoleTone {
    READY,
    WARNING,
    DANGER,
    MUTED
}

data class DetectorConsoleState(
    val destination: ConsoleDestination,
    val deploymentOrientation: DeploymentOrientation?,
    val draftConfig: MonitorConfig,
    val appliedConfig: MonitorConfig,
    val isMonitoring: Boolean,
    val isReady: Boolean,
    val hasCalibrationFrame: Boolean,
    val hasPendingChanges: Boolean,
    val canStartMonitoring: Boolean,
    val canEnterCalibration: Boolean,
    val requiresUncalibratedStartConfirmation: Boolean,
    val primaryActionLabel: String,
    val pendingChangeText: String?,
    val calibrationHintText: String?,
    val emptyFrameText: String
)

fun buildDetectorConsoleState(
    deploymentOrientation: DeploymentOrientation?,
    draftConfig: MonitorConfig,
    appliedConfig: MonitorConfig,
    isMonitoring: Boolean,
    isReady: Boolean,
    hasCalibrationFrame: Boolean
): DetectorConsoleState {
    val destination = if (deploymentOrientation == null) {
        ConsoleDestination.ORIENTATION_SETUP
    } else {
        ConsoleDestination.RUN
    }
    val hasCalibration = hasCalibrationFrame || draftConfig.maskRegions.isNotEmpty()
    val hasPendingChanges = isMonitoring && draftConfig != appliedConfig
    val canUseConsole = destination == ConsoleDestination.RUN && isReady
    return DetectorConsoleState(
        destination = destination,
        deploymentOrientation = deploymentOrientation,
        draftConfig = draftConfig,
        appliedConfig = appliedConfig,
        isMonitoring = isMonitoring,
        isReady = isReady,
        hasCalibrationFrame = hasCalibrationFrame,
        hasPendingChanges = hasPendingChanges,
        canStartMonitoring = canUseConsole && !isMonitoring,
        canEnterCalibration = canUseConsole && !isMonitoring,
        requiresUncalibratedStartConfirmation = canUseConsole && !isMonitoring && !hasCalibration,
        primaryActionLabel = if (isMonitoring) "停止监控" else "开始监控",
        pendingChangeText = if (hasPendingChanges) "有参数待生效，停止后重新开启应用" else null,
        calibrationHintText = if (!hasCalibration) "建议先校准取景" else null,
        emptyFrameText = "暂无最近帧，停止后可校准取景"
    )
}

fun remoteConfigAckText(isMonitoring: Boolean): String =
    if (isMonitoring) "已保存，重启监控后生效" else "已保存，下次启动监控时生效"

package com.xgwnje.visionguard_android.ui.home

import com.xgwnje.visionguard_android.data.model.AlertMessage

data class AlertSourceOption(val key: String, val label: String)

fun alertSourceKey(deviceId: String, sourceId: String): String =
    "$deviceId/${sourceId.ifBlank { "default" }}"

fun AlertMessage.sourcePreferenceKey(): String = alertSourceKey(deviceId, sourceId)

fun buildAlertSourceOptions(alerts: List<AlertMessage>): List<AlertSourceOption> = alerts
    .filter { it.deviceId.isNotBlank() }
    .groupBy { it.sourcePreferenceKey() }
    .map { (key, events) ->
        val latest = events.maxByOrNull { it.createdAt ?: it.receivedAt ?: 0L } ?: events.first()
        val sourceLabel = latest.sourceName.ifBlank { if (latest.sourceId.isBlank()) "默认来源" else latest.sourceId }
        AlertSourceOption(key, "${latest.deviceName.ifBlank { latest.deviceId }} · $sourceLabel")
    }
    .sortedBy { it.label }

fun filterAlertsBySource(alerts: List<AlertMessage>, selectedKey: String?): List<AlertMessage> =
    if (selectedKey == null) alerts else alerts.filter { it.sourcePreferenceKey() == selectedKey }

fun shouldNotifyForAlert(alert: AlertMessage, mutedKeys: Set<String>): Boolean =
    alert.alertId.isNotBlank() && alert.deviceId.isNotBlank() && alert.sourcePreferenceKey() !in mutedKeys

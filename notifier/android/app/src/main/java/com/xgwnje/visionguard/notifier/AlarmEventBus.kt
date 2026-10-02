package com.xgwnje.visionguard.notifier

import kotlinx.coroutines.flow.MutableSharedFlow

data class AlertEvent(val id: String, val keyword: String, val sourceApp: String?, val snippet: String?, val queueSize: Int, val occurrenceCount: Int)
object AlarmEventBus {
    val keywordAlert = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 1)
    val alertStateChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

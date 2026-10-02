package com.xgwnje.visionguard.receiver.data.model

data class StreamInfo(val streamId: String, val publisherDeviceId: String, val publisherName: String,
    val targetDeviceId: String? = null, val sourceId: String? = null, val sourceName: String = "",
    val isStreaming: Boolean = false)

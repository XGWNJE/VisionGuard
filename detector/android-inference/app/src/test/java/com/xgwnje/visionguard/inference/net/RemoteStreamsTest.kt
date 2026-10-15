package com.xgwnje.visionguard.inference.net

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteStreamsTest {
    @Test
    fun `绑定即进入目录 不依赖推流或模型状态`() {
        val body = JsonParser.parseString("""{"streams":[
            {"streamId":"web","publisherDeviceId":"browser","publisherName":"Web","targetDeviceId":"android","sourceId":"web-camera","sourceName":"Web 镜头","isStreaming":false},
            {"streamId":"win1","publisherDeviceId":"windows","targetDeviceId":"android","sourceId":"default","sourceName":"窗口一","isStreaming":true},
            {"streamId":"win2","publisherDeviceId":"windows","targetDeviceId":"android","sourceId":"window-2","sourceName":"窗口二","isStreaming":false},
            {"streamId":"other","targetDeviceId":"elsewhere","sourceId":"private"}
        ]}""").asJsonObject
        val streams = parseRemoteStreams("android", body)
        assertEquals(listOf("web-camera", "default", "window-2"), streams.map { it.sourceId })
        assertEquals("browser", streams.first().publisherDeviceId)
        assertEquals(false, streams.first().isStreaming)
        assertEquals(emptyList<RemoteStream>(), parseRemoteStreams("android", JsonParser.parseString("""{"streams":[]}""").asJsonObject))
    }
}

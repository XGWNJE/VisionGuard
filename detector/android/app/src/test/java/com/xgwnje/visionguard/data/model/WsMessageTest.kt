package com.xgwnje.visionguard.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class WsMessageTest {
    @Test
    fun relayedCommandRoundTripsRequestIdentity() {
        val gson = Gson()
        val command = gson.fromJson(
            """{"type":"command","requestId":"request-12345678","targetDeviceId":"detector-1","command":"resume"}""",
            WsCommandMessage::class.java
        )
        val ack = WsCommandAck(
            requestId = command.requestId,
            targetDeviceId = command.targetDeviceId,
            command = command.command,
            success = true
        )

        assertEquals("request-12345678", gson.toJsonTree(ack).asJsonObject["requestId"].asString)
        assertEquals("completed", ack.phase)
    }

    @Test
    fun heartbeatDeclaresImplementedCapabilitiesAndRunningComponent() {
        val heartbeat = WsHeartbeatMessage(deviceId = "detector-1")

        assertEquals(true, "request-correlation" in heartbeat.capabilities)
        assertEquals("running", heartbeat.components["detectorApp"])
    }
}

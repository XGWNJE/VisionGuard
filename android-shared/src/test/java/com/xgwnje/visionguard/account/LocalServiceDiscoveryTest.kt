package com.xgwnje.visionguard.account

import org.junit.Assert.*
import org.junit.Test

class LocalServiceDiscoveryTest {
    @Test fun parsesTheLocalServiceBeacon() {
        val beacon = LanBeaconParser.parse("""{"service":"visionguard-local","channel":"local-service","port":4321,"version":"0.7.0"}""")
        assertEquals(LanBeacon("local-service", 4321), beacon)
    }

    @Test fun ignoresOtherServicesAndMalformedPayloads() {
        for (text in listOf(
            "",
            "not json at all",
            """{"service":"other","channel":"local-service","port":4321}""",
            """{"service":"visionguard-local","port":4321}""",
            """{"service":"visionguard-local","channel":"","port":4321}""",
            """{"service":"visionguard-local","channel":"local-service"}""",
            """{"service":"visionguard-local","channel":"local-service","port":0}""",
            """{"service":"visionguard-local","channel":"local-service","port":70000}""",
            """{"service":"visionguard-local","channel":"local-service","port":"4321"}""",
        )) {
            assertNull(text, LanBeaconParser.parse(text))
        }
    }

    @Test fun toleratesExtraFieldsAndWhitespace() {
        val beacon = LanBeaconParser.parse(" { \"version\" : \"0.7.0\" , \"service\" : \"visionguard-local\" , \"port\" : 45899 , \"channel\" : \"kpad\" } ")
        assertEquals(LanBeacon("kpad", 45899), beacon)
    }
}

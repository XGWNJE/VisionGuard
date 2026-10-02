package com.xgwnje.visionguard.account

import org.junit.Assert.*
import org.junit.Test

class AccountEndpointTest {
    @Test fun httpsAndPrivateTestServicesAreAccepted() {
        assertEquals("https://example.org", AccountStore.normalizeEndpoint(" https://example.org/ "))
        assertEquals("http://10.0.2.2:4318", AccountStore.normalizeEndpoint("http://10.0.2.2:4318"))
        assertEquals("http://192.168.1.8:4318", AccountStore.normalizeEndpoint("http://192.168.1.8:4318/"))
    }
    @Test fun publicPlaintextAndCredentialBearingUrlsAreRejected() {
        listOf("http://example.org", "http://8.8.8.8", "http://host.10.1.2.3", "https://user:secret@example.org", "https://example.org/path", "https://example.org?token=secret").forEach {
            assertTrue(it, runCatching { AccountStore.normalizeEndpoint(it) }.isFailure)
        }
    }
    @Test fun localCacheIdentitySeparatesServicesAccountsAndDevices() {
        val session = AccountSession("https://a.example", "token", "later", "account-a", "user", "device-a", "Camera", "android-camera")
        assertNotEquals(session.scope, session.copy(endpoint = "https://b.example").scope)
        assertNotEquals(session.scope, session.copy(accountId = "account-b").scope)
        assertNotEquals(session.scope, session.copy(deviceId = "device-b").scope)
        assertEquals(session.scope, session.copy(token = "rotated").scope)
        assertEquals("wss://a.example/media/ws", session.mediaUrl)
    }
}

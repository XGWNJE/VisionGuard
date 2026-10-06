package com.xgwnje.visionguard.account

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AccountSessionInvalidationTest {
    @Test fun releasePinsProductionAndRejectsPreviouslySavedIsolatedSession() {
        var encryptedReads = 0
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            check(method.name == "getString")
            when (args[0]) {
                "endpoint" -> "http://127.0.0.1:3173"
                "encrypted" -> { encryptedReads++; "test-ciphertext" }
                else -> args[1]
            }
        } as SharedPreferences
        val constructor = AccountStore::class.java.getDeclaredConstructor(SharedPreferences::class.java, java.lang.Boolean.TYPE, Function0::class.java).apply { isAccessible = true }
        val identity = { error("Identity must not be read before login") }
        val release = constructor.newInstance(prefs, false, identity)
        assertEquals(AccountStore.DEFAULT_ENDPOINT, release.savedEndpoint)
        assertNull(release.session.value)
        assertEquals("An isolated credential must not be decoded as a production session", 0, encryptedReads)
        val debug = constructor.newInstance(prefs, true, identity)
        assertEquals("http://127.0.0.1:3173", debug.savedEndpoint)
        assertEquals(1, encryptedReads)
    }

    @Test fun delayedUnauthorizedResponseOnlyClearsItsCurrentCredential() {
        listOf("current", "rotated", "other-service").forEach { scenario ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            executor.submit {
                server.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume HTTP headers */ }
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        flush()
                    }
                }
            }
            try {
                val values = mutableMapOf<String, String>()
                val removes = mutableListOf<String>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                    when (method.name) {
                        "remove" -> { removes.add(args[0] as String); editor }
                        "commit" -> { removes.forEach(values::remove); removes.clear(); true }
                        else -> error("Unexpected preferences edit: ${method.name}")
                    }
                } as SharedPreferences.Editor
                val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                    when (method.name) {
                        "getString" -> values[args[0] as String] ?: args[1]
                        "edit" -> editor
                        else -> error("Unexpected preferences read: ${method.name}")
                    }
                } as SharedPreferences
                val store = AccountStore::class.java.getDeclaredConstructor(SharedPreferences::class.java, java.lang.Boolean.TYPE, Function0::class.java).apply { isAccessible = true }.newInstance(prefs, true, { error("Identity must not be read during an existing session request") })
                @Suppress("UNCHECKED_CAST")
                val sessions = AccountStore::class.java.getDeclaredField("mutableSession").apply { isAccessible = true }.get(store) as MutableStateFlow<AccountSession?>
                val old = AccountSession("http://127.0.0.1:${server.localPort}", "old-token", "2099-01-01T00:00:00Z", "account", "user", "device", "Camera", "android-camera")
                val current = when (scenario) {
                    "rotated" -> old.copy(token = "fresh-token")
                    "other-service" -> old.copy(endpoint = "https://another.example", accountId = "other-account")
                    else -> old
                }
                sessions.value = old
                values["encrypted"] = "current-ciphertext"
                runBlocking {
                    val response = async(Dispatchers.IO) { runCatching { store.request("/api/account/session") } }
                    assertTrue("HTTP request must arrive", arrived.await(5, TimeUnit.SECONDS))
                    sessions.value = current
                    release.countDown()
                    assertTrue(response.await().isFailure)
                }
                if (scenario == "current") {
                    assertNull(store.session.value)
                    assertFalse(values.containsKey("encrypted"))
                } else {
                    assertEquals(current, store.session.value)
                    assertEquals("current-ciphertext", values["encrypted"])
                }
            } finally {
                release.countDown()
                server.close()
                executor.shutdownNow()
            }
        }
    }
}

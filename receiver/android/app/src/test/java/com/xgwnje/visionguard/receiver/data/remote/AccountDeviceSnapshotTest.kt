package com.xgwnje.visionguard.receiver.data.remote

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.xgwnje.visionguard.receiver.data.model.DeviceInfo
import com.xgwnje.visionguard.receiver.data.model.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList

class AccountDeviceSnapshotTest {
    private val gson = Gson()
    private fun identity(component: String = "android-camera", role: String = "detector", account: String = "account") = """
        {"accountId":"$account","deviceId":"camera","deviceName":"Registered Camera","component":"$component",
        "role":"$role","nodeType":"visual","platform":"android","online":false}
    """.trimIndent()

    @Test fun offlineRegistrationHasSafeCameraDefaults() {
        val device = parseAccountDeviceInfo(JsonParser.parseString(identity()), "account", gson)!!
        assertEquals("Registered Camera", device.deviceName)
        assertEquals("android-camera", device.clientType)
        assertEquals("android-camera", device.component)
        assertFalse(device.online)
        assertFalse(device.isReady)
        assertFalse(device.isMonitoring)
        assertTrue(device.sources.isEmpty())
        assertEquals(listOf("video-publish"), device.capabilities)
    }

    @Test fun accountBoundaryAndRequiredIdentityFieldsAreEnforced() {
        assertNull(parseAccountDeviceInfo(JsonParser.parseString(identity(account = "other")), "account", gson))
        assertNull(parseAccountDeviceInfo(JsonParser.parseString(identity("android-console", "console")), "account", gson))
        assertNull(parseAccountDeviceInfo(JsonParser.parseString(identity().replace("\"platform\":\"android\"", "\"platform\":null")), "account", gson))
    }

    @Test fun runtimeSourcesAreRetainedWithRegisteredNameAndCameraRole() {
        val registered = parseAccountDeviceInfo(JsonParser.parseString(identity()), "account", gson)!!
        val source = SourceInfo("source", "Camera", true, true)
        val runtime = registered.copy(deviceName = "Old Camera", online = true, isReady = true, sources = listOf(source))
        val combined = mergeAccountDevices(listOf(registered), listOf(runtime)).single()
        assertEquals("Registered Camera", combined.deviceName)
        assertTrue(combined.online)
        assertEquals(listOf(source), combined.sources)
        assertEquals("android-camera", combined.component)
    }

    @Test fun unboundIdentityCannotBeResurrectedByStaleRuntime() {
        val camera = parseAccountDeviceInfo(JsonParser.parseString(identity()), "account", gson)!!
        val inference = camera.copy(deviceId = "inference", component = "windows-inference", clientType = "windows")
        val result = mergeAccountDevices(listOf(inference), listOf(camera.copy(online = true), inference.copy(online = true)))
        assertEquals(listOf("inference"), result.map(DeviceInfo::deviceId))
        assertTrue(result.single().online)
        assertFalse(mergeAccountDevices(listOf(camera), emptyList()).single().online)
    }

    @Test fun delayedRealHttpSnapshotCannotCrossAccountOrCredentialBoundary() {
        listOf("current", "other-account", "rotated", "logged-out").forEach { scenario ->
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val endpoint = "http://127.0.0.1:${server.localPort}"
            val expectedScope = "$endpoint|account|console|old-token"
            val activeScope = AtomicReference<String?>(expectedScope)
            executor.submit {
                server.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume actual HTTP request */ }
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    val body = "{\"ok\":true,\"devices\":[${identity()}]}".toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(body); flush()
                    }
                }
            }
            try {
                runBlocking {
                    val result = async(Dispatchers.Default) {
                        fetchAccountDevices(OkHttpClient(), Request.Builder().url("$endpoint/api/devices").build(),
                            "account", expectedScope, activeScope::get)
                    }
                    assertTrue(arrived.await(5, TimeUnit.SECONDS))
                    activeScope.set(when (scenario) {
                        "other-account" -> "$endpoint|other|console|old-token"
                        "rotated" -> "$endpoint|account|console|fresh-token"
                        "logged-out" -> null
                        else -> expectedScope
                    })
                    release.countDown()
                    if (scenario == "current") assertEquals("camera", result.await()?.single()?.deviceId)
                    else assertNull("Late snapshot must be discarded: $scenario", result.await())
                }
            } finally {
                release.countDown(); server.close(); executor.shutdownNow()
            }
        }
    }

    @Test fun realDeleteUsesCapturedCredentialAndRejectsLateSuccess() {
        listOf(false, true).forEach { changeAccount ->
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newSingleThreadExecutor()
            val arrived = CountDownLatch(1)
            val release = CountDownLatch(1)
            val scope = AtomicReference<String?>("service|account|console|fixture-token")
            val received = executor.submit<List<String>> {
                server.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    arrived.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}".toByteArray(Charsets.US_ASCII)); flush()
                    }
                    headers
                }
            }
            try {
                runBlocking {
                    val result = async(Dispatchers.Default) {
                        unbindAccountDevice(OkHttpClient(), Request.Builder().url("http://127.0.0.1:${server.localPort}/api/devices/camera")
                            .header("Authorization", "Bearer fixture-token").delete().build(), "service|account|console|fixture-token", scope::get)
                    }
                    assertTrue(arrived.await(5, TimeUnit.SECONDS))
                    if (changeAccount) scope.set("service|other-account|console|new-token")
                    release.countDown()
                    assertEquals(!changeAccount, result.await())
                    val headers = received.get(5, TimeUnit.SECONDS)
                    assertEquals("DELETE /api/devices/camera HTTP/1.1", headers.first())
                    assertTrue(headers.any { it == "Authorization: Bearer fixture-token" })
                }
            } finally {
                release.countDown(); server.close(); executor.shutdownNow()
            }
        }
    }

    @Test fun confirmedDeletionUpdatesCacheDespiteFailedRefreshAndSerializesOlderGet() {
        val server = ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val oldGetArrived = CountDownLatch(1)
        val releaseOldGet = CountDownLatch(1)
        val deleteArrived = CountDownLatch(1)
        val counter = AtomicInteger()
        val methods = CopyOnWriteArrayList<String>()
        val persisted = AtomicReference<List<DeviceInfo>>(emptyList())
        val applied = CopyOnWriteArrayList<List<DeviceInfo>>()
        val endpoint = "http://127.0.0.1:${server.localPort}"
        executor.submit {
            repeat(3) {
                val socket = server.accept()
                executor.submit {
                    socket.use { client ->
                        val input = client.getInputStream().bufferedReader()
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        methods.add(headers.first().substringBefore(' '))
                        val number = counter.incrementAndGet()
                        val response: String
                        val status: String
                        when (number) {
                            1 -> {
                                oldGetArrived.countDown()
                                releaseOldGet.await(5, TimeUnit.SECONDS)
                                status = "200 OK"; response = "{\"ok\":true,\"devices\":[${identity()}]}"
                            }
                            2 -> { deleteArrived.countDown(); status = "200 OK"; response = "{\"ok\":true}" }
                            else -> { status = "500 Internal Server Error"; response = "{}" }
                        }
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        client.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        try {
            runBlocking {
                lateinit var directory: AccountDeviceDirectory
                directory = AccountDeviceDirectory(OkHttpClient(), { "scope" }) {
                    persisted.set(directory.devices!!)
                    applied.add(directory.devices!!)
                }
                val get = Request.Builder().url("$endpoint/api/devices").build()
                val olderGet = async(Dispatchers.Default) { directory.refresh(get, "account", "scope") }
                assertTrue(oldGetArrived.await(5, TimeUnit.SECONDS))
                val deletion = async(Dispatchers.Default) {
                    directory.unbind(Request.Builder().url("$endpoint/api/devices/camera").delete().build(), get,
                        "account", "scope", emptyList(), "camera")
                }
                assertFalse("DELETE must wait for the older directory GET", deleteArrived.await(150, TimeUnit.MILLISECONDS))
                releaseOldGet.countDown()
                assertTrue(olderGet.await())
                assertTrue("Confirmed DELETE stays successful even when the following GET fails", deletion.await())
                assertEquals(listOf("GET", "DELETE", "GET"), methods.toList())
                assertTrue(directory.devices!!.isEmpty())
                assertTrue(persisted.get().isEmpty())
                assertEquals(listOf(listOf("camera"), emptyList<String>()), applied.map { snapshot -> snapshot.map(DeviceInfo::deviceId) })
            }
        } finally {
            releaseOldGet.countDown(); server.close(); executor.shutdownNow()
        }
    }
}

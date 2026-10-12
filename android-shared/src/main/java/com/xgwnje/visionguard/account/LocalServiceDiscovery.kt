package com.xgwnje.visionguard.account

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** 局域网信标里客户端需要的字段。 */
data class LanBeacon(val channel: String, val port: Int)

/**
 * 本地服务发现信标解析。
 *
 * 手写解析而不是用 `org.json`：Android 的 `org.json` 在 JVM 单元测试里是 stub，调它会直接抛异常，
 * 而这段逻辑必须有单测覆盖（一条坏载荷不能变成一次错误登录）。
 */
object LanBeaconParser {
    private const val SERVICE = "visionguard-local"

    private fun text(value: String, name: String): String? =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(value)?.groupValues?.get(1)

    private fun number(value: String, name: String): Int? =
        Regex("\"$name\"\\s*:\\s*(-?\\d+)").find(value)?.groupValues?.get(1)?.toIntOrNull()

    fun parse(value: String): LanBeacon? {
        if (text(value, "service") != SERVICE) return null
        val channel = text(value, "channel")?.takeIf { it.isNotBlank() } ?: return null
        val port = number(value, "port") ?: return null
        if (port !in 1..65535) return null
        return LanBeacon(channel, port)
    }
}

/**
 * 本地服务自动识别。
 *
 * 本地服务每次启动都换端口，把地址烤进包里意味着每换一次端口就要重装一次；因此本地服务每 2 秒往
 * 局域网广播一次 UDP 信标（见 server 的 LanBeacon），客户端启动时听一下就知道服务在哪。
 * 找不到就回落到构建时内置的地址（见 [PreviewSignIn]），两条路互不依赖。
 */
object LocalServiceDiscovery {
    /** 与 server/src/config.ts 的 `VISIONGUARD_LAN_BEACON_PORT` 默认值一致。 */
    const val DISCOVERY_PORT = 45899

    private const val TAG = "LocalServiceDiscovery"
    private const val SOCKET_TIMEOUT_MS = 400

    suspend fun discover(channel: String, timeoutMs: Long = 2500): String? = withContext(Dispatchers.IO) {
        if (channel.isBlank()) return@withContext null
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(InetSocketAddress(DISCOVERY_PORT))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(1024)
            var found: String? = null
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                }
                val beacon = LanBeaconParser.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                if (beacon.channel != channel) continue
                val address = packet.address?.hostAddress ?: continue
                if (address.contains(':')) continue
                found = "http://$address:${beacon.port}"
                Log.i(TAG, "发现本地服务：$found（channel=$channel）")
            }
            found
        } catch (error: Exception) {
            Log.w(TAG, "局域网发现失败：${error.message}")
            null
        } finally {
            socket?.close()
        }
    }
}

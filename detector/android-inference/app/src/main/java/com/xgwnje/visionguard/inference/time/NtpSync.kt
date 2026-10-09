package com.xgwnje.visionguard.inference.time

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.time.Instant

/**
 * NTP 校正时钟，语义与 Windows `Utils/NtpSync.cs` 一致。
 *
 * 报警时间戳、冷却判定和发件箱过期判断共用同一个校正时间，
 * 避免设备原始时钟偏快导致新报警一产生就被判过期。
 * 偏移超过 5 分钟视为异常并拒绝，避免异常回包把时钟整体带偏。
 */
object NtpSync {

    /** 与 Windows 端一致的服务器顺序。 */
    val HOSTS = listOf("ntp.aliyun.com", "cn.pool.ntp.org", "ntp.tencent.com")

    const val TIMEOUT_MS = 3000
    const val MAX_ACCEPTED_OFFSET_MS = 5 * 60 * 1000L

    private const val NTP_PORT = 123
    private const val PACKET_SIZE = 48
    private const val TRANSMIT_TIME_OFFSET = 40
    private const val RECEIVE_TIME_OFFSET = 32
    /** LI=0、VN=3、Mode=3（客户端） */
    private const val REQUEST_FIRST_BYTE = 0x1B
    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L

    @Volatile
    private var offsetMs = 0L

    /** 最近一次成功同步得到的偏移；未同步时为 0。 */
    val currentOffsetMs: Long get() = offsetMs

    /** 校正后的 UTC 毫秒。 */
    fun utcNowMs(): Long = System.currentTimeMillis() + offsetMs

    /** 校正后的 ISO 8601 时间戳。 */
    fun utcNowIso(): String = Instant.ofEpochMilli(utcNowMs()).toString()

    /**
     * 依次尝试 [hosts]，首个成功且偏移在允许范围内的服务器生效。
     * 全部失败时保留原有偏移并返回 false，不阻塞调用方。
     */
    fun syncOnce(hosts: List<String> = HOSTS, timeoutMs: Int = TIMEOUT_MS): Boolean {
        for (host in hosts) {
            val offset = runCatching { queryOffset(host, timeoutMs) }.getOrNull() ?: continue
            if (kotlin.math.abs(offset) > MAX_ACCEPTED_OFFSET_MS) continue
            offsetMs = offset
            return true
        }
        return false
    }

    /** 单次 NTP 查询，返回本地时钟应加的偏移毫秒。 */
    internal fun queryOffset(host: String, timeoutMs: Int = TIMEOUT_MS): Long? {
        val request = ByteArray(PACKET_SIZE)
        request[0] = REQUEST_FIRST_BYTE.toByte()
        val sendTime = System.currentTimeMillis()
        writeTimestamp(request, TRANSMIT_TIME_OFFSET, sendTime)

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(request, request.size, address, NTP_PORT))

            val response = ByteArray(PACKET_SIZE)
            val packet = DatagramPacket(response, response.size)
            socket.receive(packet)
            val receiveTime = System.currentTimeMillis()
            if (packet.length < PACKET_SIZE) return null
            if ((response[0].toInt() and 0x07) != 4 && (response[0].toInt() and 0x07) != 5) return null

            val serverReceive = readTimestamp(response, RECEIVE_TIME_OFFSET) ?: return null
            val serverTransmit = readTimestamp(response, TRANSMIT_TIME_OFFSET) ?: return null
            return computeOffsetMs(sendTime, serverReceive, serverTransmit, receiveTime)
        }
    }

    /** `((t2 - t1) + (t3 - t4)) / 2`，标准 NTP 偏移估计。 */
    internal fun computeOffsetMs(t1: Long, t2: Long, t3: Long, t4: Long): Long =
        ((t2 - t1) + (t3 - t4)) / 2

    private fun writeTimestamp(buffer: ByteArray, offset: Int, epochMs: Long) {
        val seconds = epochMs / 1000 + NTP_EPOCH_OFFSET_SECONDS
        val fraction = (epochMs % 1000) * 0x100000000L / 1000
        writeUInt32(buffer, offset, seconds)
        writeUInt32(buffer, offset + 4, fraction)
    }

    private fun readTimestamp(buffer: ByteArray, offset: Int): Long? {
        val seconds = readUInt32(buffer, offset)
        val fraction = readUInt32(buffer, offset + 4)
        if (seconds == 0L && fraction == 0L) return null
        return (seconds - NTP_EPOCH_OFFSET_SECONDS) * 1000 + fraction * 1000 / 0x100000000L
    }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value shr 24).toByte()
        buffer[offset + 1] = (value shr 16).toByte()
        buffer[offset + 2] = (value shr 8).toByte()
        buffer[offset + 3] = value.toByte()
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        return value
    }
}

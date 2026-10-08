package com.xgwnje.visionguard.inference.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NTP 偏移计算：与 Windows `Utils/NtpSync.cs` 采用同一公式。 */
class NtpSyncTest {

    @Test
    fun `偏移取往返两次差值的平均`() {
        // 本地慢 2000ms：服务器接收/发送都比本地读数大 2000
        assertEquals(2_000L, NtpSync.computeOffsetMs(t1 = 1_000, t2 = 3_010, t3 = 3_020, t4 = 1_030))
    }

    @Test
    fun `本地快时偏移为负`() {
        assertEquals(-500L, NtpSync.computeOffsetMs(t1 = 10_000, t2 = 9_510, t3 = 9_520, t4 = 10_030))
    }

    @Test
    fun `网络对称时偏移为服务器与本地之差`() {
        // 单程 50ms，服务器与本地同源时钟 +1000
        assertEquals(1_000L, NtpSync.computeOffsetMs(t1 = 0, t2 = 1_050, t3 = 1_050, t4 = 100))
    }

    @Test
    fun `未同步时使用本机时钟`() {
        assertTrue(kotlin.math.abs(NtpSync.utcNowMs() - System.currentTimeMillis()) < 1_000)
    }

    @Test
    fun `允许的最大偏移为五分钟`() {
        assertEquals(5 * 60 * 1000L, NtpSync.MAX_ACCEPTED_OFFSET_MS)
    }
}

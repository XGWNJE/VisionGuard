package com.xgwnje.visionguard.inference.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 报警持久发件箱：入队、过期清理、确认移除、上限与损坏隔离。 */
class AlertOutboxTest {

    private fun tempOutbox(nowMs: () -> Long = { 1_000L }): Pair<AlertOutbox, java.io.File> {
        val directory = Files.createTempDirectory("visionguard-outbox").toFile()
        val file = java.io.File(directory, "alert-outbox.json")
        return AlertOutbox(file, nowMs) to file
    }

    @Test
    fun `入队后可快照并写盘`() {
        val (outbox, file) = tempOutbox()
        outbox.enqueue("a-1", """{"alertId":"a-1"}""", expiresAtMs = 10_000)
        assertEquals(1, outbox.snapshot().size)
        assertTrue(file.isFile)
        assertTrue(file.readText(Charsets.UTF_8).contains("a-1"))
    }

    @Test
    fun `重复 alertId 覆盖旧记录`() {
        val (outbox, _) = tempOutbox()
        outbox.enqueue("a-1", """{"v":1}""", 10_000)
        outbox.enqueue("a-1", """{"v":2}""", 10_000)
        val entries = outbox.snapshot()
        assertEquals(1, entries.size)
        assertEquals("""{"v":2}""", entries[0].payloadJson)
    }

    @Test
    fun `过期项在快照时被清理且不补传`() {
        var now = 1_000L
        val (outbox, file) = tempOutbox { now }
        outbox.enqueue("a-1", "{}", expiresAtMs = 2_000)
        now = 2_001L
        assertTrue(outbox.snapshot().isEmpty())
        assertEquals(0, outbox.size())
        assertFalse(file.readText(Charsets.UTF_8).contains("a-1"))
    }

    @Test
    fun `确认后移除并保序`() {
        val (outbox, _) = tempOutbox()
        outbox.enqueue("a-1", "{}", 10_000)
        outbox.enqueue("a-2", "{}", 10_000)
        assertTrue(outbox.acknowledge("a-1"))
        assertFalse(outbox.acknowledge("a-1"))
        assertEquals(listOf("a-2"), outbox.snapshot().map { it.alertId })
    }

    @Test
    fun `超过上限丢弃最旧记录`() {
        val (outbox, _) = tempOutbox()
        for (index in 0 until AlertOutbox.MAX_ENTRIES + 10) outbox.enqueue("a-$index", "{}", 1_000_000)
        val entries = outbox.snapshot()
        assertEquals(AlertOutbox.MAX_ENTRIES, entries.size)
        assertEquals("a-10", entries.first().alertId)
    }

    @Test
    fun `损坏文件被隔离并给出警告`() {
        val (_, file) = tempOutbox()
        file.writeText("{ not json", Charsets.UTF_8)
        val outbox = AlertOutbox(file, { 1_000L })
        assertTrue(outbox.snapshot().isEmpty())
        assertNotNull(outbox.recoveryWarning)
        val siblings = file.parentFile!!.listFiles()!!.map { it.name }
        assertTrue(siblings.any { it.startsWith("alert-outbox.json.corrupt-") })
    }

    @Test
    fun `重新打开后保留未过期记录`() {
        val directory = Files.createTempDirectory("visionguard-outbox-reopen").toFile()
        val file = java.io.File(directory, "alert-outbox.json")
        AlertOutbox(file, { 1_000L }).enqueue("a-1", """{"v":1}""", 10_000)
        val reopened = AlertOutbox(file, { 2_000L })
        assertEquals(listOf("a-1"), reopened.snapshot().map { it.alertId })
    }
}

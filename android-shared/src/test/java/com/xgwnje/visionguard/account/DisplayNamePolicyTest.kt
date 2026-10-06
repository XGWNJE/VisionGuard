package com.xgwnje.visionguard.account

import org.junit.Assert.*
import org.junit.Test

class DisplayNamePolicyTest {
    @Test fun namesUseTheServerUtf16LimitWithoutTruncatingCustomInput() {
        for (name in listOf("门".repeat(64), "A".repeat(64), "😀".repeat(32))) {
            assertEquals(name, DisplayNamePolicy.normalize(name))
        }
        for (name in listOf("门".repeat(65), "😀".repeat(33), "  ", "门\n", "a\u0000b")) {
            assertNotNull(DisplayNamePolicy.error(name))
            assertFalse(DisplayNamePolicy.acceptsDraft(name) && name.isNotBlank())
            try { DisplayNamePolicy.normalize(name); fail("Invalid name was accepted") } catch (_: IllegalArgumentException) { }
        }
        assertEquals("门厅", DisplayNamePolicy.normalize("  门厅  "))
    }
    @Test fun automaticNamesAndCodesAreBoundedAndNeverSplitAnEmoji() {
        val name = DisplayNamePolicy.generated("A".repeat(63) + "😀")
        assertEquals(63, name.length)
        assertNull(DisplayNamePolicy.error(name))
        assertEquals("导入铃声", DisplayNamePolicy.generated("\n ", "导入铃声"))
        val code = DisplayNamePolicy.deviceCode("phone/\n" + "a".repeat(80))
        assertTrue(code.length <= 40)
        assertTrue(code.matches(Regex("[\\p{L}\\p{N}._ -]{1,40}")))
    }
}

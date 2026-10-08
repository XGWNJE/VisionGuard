package com.xgwnje.visionguard.inference.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 遮罩持久化格式与合法性校验。 */
class MaskRegionTest {

    @Test
    fun `持久化串可以往返解析`() {
        val regions = listOf(MaskRegion(0.1f, 0.2f, 0.3f, 0.4f), MaskRegion(0f, 0f, 1f, 1f))
        val text = MaskRegion.formatList(regions)
        assertEquals("0.1,0.2,0.3,0.4;0,0,1,1", text)
        assertEquals(regions, MaskRegion.parseList(text))
    }

    @Test
    fun `空串与空值解析为空列表`() {
        assertTrue(MaskRegion.parseList(null).isEmpty())
        assertTrue(MaskRegion.parseList("").isEmpty())
        assertTrue(MaskRegion.parseList("  ").isEmpty())
    }

    @Test
    fun `任一项非法则整串失效`() {
        assertTrue(MaskRegion.parseList("0.1,0.2,0.3").isEmpty())
        assertTrue(MaskRegion.parseList("0.1,0.2,0.3,abc").isEmpty())
        assertTrue(MaskRegion.parseList("-0.1,0.2,0.3,0.4").isEmpty())
        assertTrue(MaskRegion.parseList("0.1,0.2,0,0.4").isEmpty())
        assertTrue(MaskRegion.parseList("0.8,0.2,0.5,0.4").isEmpty())
    }

    @Test
    fun `超出画面的区域被判定非法`() {
        assertNotNull(MaskRegion.validate(MaskRegion(0.8f, 0f, 0.3f, 0.2f)))
        assertNull(MaskRegion.validate(MaskRegion(0.5f, 0.5f, 0.5f, 0.5f)))
    }

    @Test
    fun `格式串包含完整精度而不是本地化小数点`() {
        val text = MaskRegion.formatList(listOf(MaskRegion(0.123456f, 0.5f, 0.25f, 1f / 3f)))
        assertEquals("0.1235,0.5,0.25,0.3333", text)
    }
}

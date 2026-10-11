package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发上限契约。
 *
 * 这张表是「多路多少路会卡死」的唯一依据：端侧据此限制输入，心跳上报后控制台也按它解释。
 * 数值来自真机实测（单路 ≥5 帧/秒），改动必须同时改文档 `docs/32-推理框架与模型优化.md`。
 */
class InferenceLimitsTest {

    @Test
    fun `正式档 640 三档按实测收紧`() {
        assertEquals(2, InferenceLimits.perConfiguration("yolo26m_640", gpu = false))
        assertEquals(4, InferenceLimits.perConfiguration("yolo26n_640", gpu = false))
        // 320 档到 32 路仍未触底，按在册下限记
        assertEquals(32, InferenceLimits.perConfiguration("yolo26n_320", gpu = false))
        assertEquals(32, InferenceLimits.perConfiguration("yolo26m_320", gpu = false))
    }

    @Test
    fun `GPU 档按扫描下限三十二路记`() {
        ModelKeys.gpuKeys.forEach { key ->
            assertEquals(32, InferenceLimits.perConfiguration(key, gpu = true))
        }
    }

    @Test
    fun `未在册的组合取保守值而不是放开`() {
        // 宁可让用户先跑通，也不要让来源静默卡死
        assertEquals(InferenceLimits.CONSERVATIVE_LIMIT, InferenceLimits.perConfiguration("unknown_640", gpu = false))
        assertEquals(2, InferenceLimits.perConfiguration("unknown_640", gpu = true))
        // GPU 与 CPU 是两套上限，取值不能互相顶替
        assertEquals(2, InferenceLimits.perConfiguration("yolo26m_640", gpu = false))
        assertEquals(32, InferenceLimits.perConfiguration("yolo26m_640", gpu = true))
    }

    @Test
    fun `键格式与服务端校验一致`() {
        // 服务端正则：^[A-Za-z0-9_.-]{1,48}@(cpu|gpu)$
        val pattern = Regex("^[A-Za-z0-9_.-]{1,48}@(cpu|gpu)$")
        InferenceLimits.declaredLimits().keys.forEach { key ->
            assertTrue("服务端会丢弃这个键：$key", pattern.matches(key))
        }
        assertEquals("yolo26m_640@cpu", InferenceLimits.entry("yolo26m_640", gpu = false))
        assertEquals("yolo26m_640@gpu", InferenceLimits.entry("yolo26m_640", gpu = true))
    }

    @Test
    fun `宣告的上限覆盖全部可选模型且不超服务端条目上限`() {
        val declared = InferenceLimits.declaredLimits()
        ModelKeys.keys.forEach { key ->
            assertTrue("缺 CPU 上限：$key", declared.containsKey("${key}@cpu"))
        }
        ModelKeys.gpuKeys.forEach { key ->
            assertTrue("缺 GPU 上限：$key", declared.containsKey("${key}@gpu"))
        }
        // 服务端 MAX_INFERENCE_ENTRIES = 32
        assertTrue(declared.size <= 32)
    }
}

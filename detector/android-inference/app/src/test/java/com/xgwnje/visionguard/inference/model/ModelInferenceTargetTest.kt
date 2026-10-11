package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个模型跑在哪」的判定。
 *
 * 界面按这个标签显示后端，用户据此判断有没有加速。它必须与
 * [InferenceEngineFactory] 的真实选择一致——显示 GPU 却跑 CPU（或反过来）会直接误导决策。
 */
class ModelInferenceTargetTest {

    @Test
    fun `有 GPU 产物且已下载才显示 GPU`() {
        assertEquals(ModelInferenceTarget.GpuFp32, ModelInferenceTarget.resolve("yolo26m_640", gpuReady = true))
        assertEquals("GPU", ModelInferenceTarget.GpuFp32.label)
        assertTrue(ModelInferenceTarget.GpuFp32.accelerated)
    }

    @Test
    fun `没有 GPU 产物入口时即使文件在也按 CPU 记`() {
        // 只有 gpuKeys 里的键才允许加速：文件被手工塞进去也不该显示 GPU
        assertEquals(ModelInferenceTarget.CpuFp32, ModelInferenceTarget.resolve("yolo26n_320", gpuReady = true))
        assertEquals(ModelInferenceTarget.CpuFp32, ModelInferenceTarget.resolve("yolo26n_640", gpuReady = true))
    }

    @Test
    fun `量化版一律 CPU 且带 INT8 标签`() {
        ModelKeys.keys.filter { ModelKeys.isQuantized(it) }.forEach { key ->
            val target = ModelInferenceTarget.resolve(key, gpuReady = true)
            assertEquals("量化版不该显示 GPU：$key", ModelInferenceTarget.CpuInt8, target)
            assertEquals("CPU · INT8", target.label)
            assertFalse(target.accelerated)
        }
    }

    @Test
    fun `FP32 本体没下载 GPU 产物时显示 CPU`() {
        assertEquals(ModelInferenceTarget.CpuFp32, ModelInferenceTarget.resolve("yolo26m_640", gpuReady = false))
        assertEquals("CPU", ModelInferenceTarget.CpuFp32.label)
        assertFalse(ModelInferenceTarget.CpuFp32.accelerated)
    }

    @Test
    fun `十二项清单里每项都能算出后端`() {
        assertEquals(12, ModelKeys.keys.size)
        ModelKeys.keys.forEach { key ->
            val target = ModelInferenceTarget.resolve(key, gpuReady = false)
            assertTrue("标签不能为空：$key", target.label.isNotBlank())
        }
        // 只有实测有效的那个键可能显示 GPU
        val gpuKeys = ModelKeys.keys.filter { ModelInferenceTarget.resolve(it, gpuReady = true).accelerated }
        assertEquals(ModelKeys.gpuKeys, gpuKeys)
    }
}

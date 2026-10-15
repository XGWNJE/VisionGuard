package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这个档位跑在哪」的判定。
 *
 * 界面按这个标签显示后端，用户据此判断有没有加速。它必须与 [InferenceEngineFactory] 的真实选择
 * 一致——显示 GPU 却跑 CPU（或反过来）会直接误导决策。三个条件缺一不可：是 GPU 档、截断图已就绪、
 * 本机有可用的 GPU 加速器。
 */
class ModelInferenceTargetTest {

    @Test
    fun `GPU 档在产物就绪且本机支持时才显示 GPU`() {
        val target = ModelInferenceTarget.resolve("yolo26m_640", gpuReady = true, gpuAvailable = true)
        assertEquals(ModelInferenceTarget.Gpu, target)
        assertEquals("GPU", target.label)
        assertTrue(target.accelerated)
    }

    @Test
    fun `设备没有 GPU 加速器时一律按 CPU 记`() {
        ModelKeys.keys.forEach { key ->
            val target = ModelInferenceTarget.resolve(key, gpuReady = true, gpuAvailable = false)
            assertEquals("没有加速器就不该显示 GPU：$key", ModelInferenceTarget.Cpu, target)
            assertFalse(target.accelerated)
        }
    }

    @Test
    fun `GPU 档的截断图没就绪时显示 CPU`() {
        assertEquals(
            ModelInferenceTarget.Cpu,
            ModelInferenceTarget.resolve("yolo26m_320", gpuReady = false, gpuAvailable = true)
        )
        assertEquals("CPU", ModelInferenceTarget.Cpu.label)
        assertFalse(ModelInferenceTarget.Cpu.accelerated)
    }

    @Test
    fun `CPU 档即使产物在也只是 CPU`() {
        ModelKeys.cpuKeys.forEach { key ->
            assertEquals(
                "量化档不该显示 GPU：$key",
                ModelInferenceTarget.Cpu,
                ModelInferenceTarget.resolve(key, gpuReady = true, gpuAvailable = true)
            )
        }
    }

    @Test
    fun `两档清单里每项都能算出后端`() {
        assertEquals(2, ModelKeys.keys.size)
        ModelKeys.keys.forEach { key ->
            val target = ModelInferenceTarget.resolve(key, gpuReady = false, gpuAvailable = false)
            assertTrue("标签不能为空：$key", target.label.isNotBlank())
        }
        // 只有 GPU 档可能显示 GPU
        val accelerated = ModelKeys.keys.filter {
            ModelInferenceTarget.resolve(it, gpuReady = true, gpuAvailable = true).accelerated
        }
        assertEquals(ModelKeys.gpuKeys, accelerated)
    }
}

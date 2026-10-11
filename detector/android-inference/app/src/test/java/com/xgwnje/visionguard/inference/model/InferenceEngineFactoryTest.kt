package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后端选择判定表。
 *
 * 「这条来源到底用没用 GPU」在界面上只体现为一句后端名，看不出是「没下载产物」「设备不支持」
 * 还是「这个模型没有 GPU 版本」。判定表与回落原因必须逐种钉住，否则用户没法自查为什么没加速。
 */
class InferenceEngineFactoryTest {

    private fun decide(
        wantGpu: Boolean = true,
        fileExists: Boolean = true,
        fileName: String? = "yolo26m_640_gpu.tflite",
        gpuAvailable: Boolean = true,
        accelerators: String = "GPU,CPU"
    ) = InferenceEngineFactory.decide(
        wantGpu = wantGpu,
        gpuFileExists = fileExists,
        gpuFileName = fileName,
        gpuAcceleratorAvailable = gpuAvailable,
        availableAccelerators = accelerators
    )

    @Test
    fun `产物已下载且设备支持时走 GPU`() {
        val preference = decide()
        assertTrue(preference.preferGpu)
        assertNull(preference.fallbackReason)
    }

    @Test
    fun `没有 GPU 产物时回落 CPU 并说明缺哪个文件`() {
        val preference = decide(fileExists = false, fileName = "yolo26m_640_gpu.tflite")
        assertFalse(preference.preferGpu)
        val reason = preference.fallbackReason
        assertTrue("原因里要带上文件名：$reason", reason != null && reason.contains("yolo26m_640_gpu.tflite"))
        assertTrue("原因里要说清是回落 CPU：$reason", reason != null && reason.contains("CPU"))
    }

    @Test
    fun `该模型没有 GPU 版本时原因不编造文件名`() {
        val preference = decide(fileExists = false, fileName = null)
        assertFalse(preference.preferGpu)
        assertTrue(preference.fallbackReason!!.contains("无"))
    }

    @Test
    fun `设备不支持 GPU 加速器时带上可用列表`() {
        val preference = decide(gpuAvailable = false, accelerators = "CPU")
        assertFalse(preference.preferGpu)
        val reason = preference.fallbackReason!!
        assertTrue("要给出可用加速器，便于判断是设备问题：$reason", reason.contains("CPU"))
    }

    @Test
    fun `可用列表为空时也给出可读原因`() {
        val preference = decide(gpuAvailable = false, accelerators = "")
        assertFalse(preference.preferGpu)
        assertTrue(preference.fallbackReason!!.contains("无"))
    }

    @Test
    fun `没要求 GPU 时不带回落原因`() {
        val preference = decide(wantGpu = false, fileExists = false)
        assertFalse(preference.preferGpu)
        // 正式档本来就走 CPU，这不是「回落」，不该在界面上显示成异常
        assertNull(preference.fallbackReason)
    }

    @Test
    fun `正式档位与 GPU 档位是两套引擎标识`() {
        assertEquals(InferenceProvider.ONNX_CPU4, InferenceProvider.production)
        assertEquals(InferenceProvider.LITERT_GPU, InferenceEngineFactory.GPU_PROVIDER)
        assertTrue(InferenceEngineFactory.GPU_PROVIDER.usesGpuDelegate)
        assertFalse(InferenceProvider.production.usesAccelerator)
    }
}

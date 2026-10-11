package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型清单契约。
 *
 * `ModelKeys.keys` 直接驱动参数抽屉的模型选择器（未下载的会显示下载入口），也决定默认选择的
 * 模型、心跳上报的选项与标签、以及下载路径。所以「清单里有哪些键」是**用户可见且影响发布行为**
 * 的约定：量化版没投放时不能出现在清单里，否则用户点了下载必然失败。
 */
class ModelKeysTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `可选清单只有本体，量化版未投放就不列出来`() {
        assertEquals(6, ModelKeys.baseKeys.size)
        assertEquals(ModelKeys.baseKeys, ModelKeys.keys)
        ModelKeys.keys.forEach { key ->
            assertTrue("可选清单里不该出现量化版：$key", !ModelKeys.isQuantized(key))
            assertTrue(ModelKeys.isSupported(key))
        }
        ModelKeys.baseKeys.forEach { base ->
            assertTrue(
                "服务端没有量化文件，列出来就是坏入口",
                !ModelKeys.isSupported(base + ModelKeys.INT8_SUFFIX)
            )
        }
    }

    @Test
    fun `派生清单保留本体与量化版交替的顺序`() {
        // 将来启用时把 keys 改成 derivedKeys()：顺序必须保持这样，
        // 因为 defaultModelKey 取「第一个已下载的键」，量化版排在各自本体之后，
        // 新装设备的默认模型才仍是 FP32。
        val derived = ModelKeys.derivedKeys()
        assertEquals(ModelKeys.baseKeys.size * 2, derived.size)
        ModelKeys.baseKeys.forEachIndexed { index, base ->
            assertEquals(base, derived[index * 2])
            assertEquals("${base}${ModelKeys.INT8_SUFFIX}", derived[index * 2 + 1])
        }
    }

    @Test
    fun `默认模型仍是首个 FP32 本体`() {
        assertEquals("yolo26n_320", ModelKeys.defaultKey)
        assertTrue(!ModelKeys.isQuantized(ModelKeys.defaultKey))
        assertEquals(ModelKeys.baseKeys.first(), ModelKeys.defaultKey)
    }

    @Test
    fun `量化版键可以还原成本体`() {
        ModelKeys.baseKeys.forEach { base ->
            val quantized = base + ModelKeys.INT8_SUFFIX
            assertTrue(ModelKeys.isQuantized(quantized))
            assertEquals(base, ModelKeys.baseOf(quantized))
            assertTrue(!ModelKeys.isQuantized(base))
            assertEquals(base, ModelKeys.baseOf(base))
        }
        assertTrue(!ModelKeys.isSupported("yolo26n_320_int16"))
        assertTrue(!ModelKeys.isSupported("yolo26x_320"))
    }

    @Test
    fun `展示名区分量化版`() {
        // 变体名按既有实现大写（`YOLO26N · 320`），不在这里改风格。
        assertEquals("YOLO26N · 320", ModelKeys.displayName("yolo26n_320"))
        assertEquals("YOLO26N · 320 INT8", ModelKeys.displayName("yolo26n_320_int8"))
        assertEquals("YOLO26M · 640 INT8", ModelKeys.displayName("yolo26m_640_int8"))
        assertEquals("YOLO26S · 640", ModelKeys.displayName("yolo26s_640"))
        // 无法识别的键原样返回，不做猜测。
        assertEquals("unknown_key", ModelKeys.displayName("unknown_key"))
    }

    @Test
    fun `文件名与键一致，服务端按文件名静态分发`() {
        assertEquals("yolo26n_320.onnx", ModelKeys.fileName("yolo26n_320"))
        assertEquals("yolo26n_320_int8.onnx", ModelKeys.fileName("yolo26n_320_int8"))
        // GPU 产物是同一个键的另一个文件名，不引入服务端映射表
        assertEquals("yolo26m_640_gpu.tflite", ModelKeys.fileName("yolo26m_640", ModelFormat.GPU_TFLITE))
    }

    @Test
    fun `GPU 产物只在实测有效的键上开放`() {
        // 320 档 GPU 反而比 CPU 慢，不能开放；只放 640 里实测收益最大的那一个
        assertEquals(listOf("yolo26m_640"), ModelKeys.gpuKeys)
        assertTrue(ModelKeys.supportsGpu("yolo26m_640"))
        assertTrue(!ModelKeys.supportsGpu("yolo26n_320"))
        assertTrue(!ModelKeys.supportsGpu("yolo26n_640"))
        ModelKeys.gpuKeys.forEach { key ->
            assertTrue("GPU 键必须在可选清单里：$key", ModelKeys.keys.contains(key))
        }
    }

    @Test
    fun `GPU 产物下载只在支持该组合时可用`() {
        val dir = temporary.newFolder("models-gpu")
        val store = ModelStore(dir, "http://127.0.0.1:1")
        assertEquals("yolo26m_640_gpu.tflite", store.file("yolo26m_640", ModelFormat.GPU_TFLITE).name)
        assertEquals("yolo26m_640_gpu.tflite", store.gpuFile("yolo26m_640")?.name)
        // 没有 GPU 产物的键不给文件入口，避免拿错产物去跑正式链路
        assertEquals(null, store.gpuFile("yolo26n_320"))
        assertTrue(!store.isGpuReady("yolo26n_320"))
        assertTrue(!store.isGpuReady("yolo26m_640"))
    }

    @Test
    fun `键长度不超服务端上限`() {
        // 服务端 sanitizeModelKey 限 64 字符，长键会被整条丢弃。
        ModelKeys.keys.forEach { assertTrue("键过长：$it", it.length <= 64) }
        // 端侧一次最多上报 16 个选项。
        assertTrue(ModelKeys.keys.size <= 16)
    }

    @Test
    fun `下载产物落到键同名文件`() {
        val dir = temporary.newFolder("models")
        val store = ModelStore(dir, "http://127.0.0.1:1")
        assertEquals("yolo26n_640_int8.onnx", store.file("yolo26n_640_int8").name)
        assertEquals("yolo26n_640.onnx", store.file("yolo26n_640").name)
    }
}

package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型清单契约：量化版与本体**并存**时不能悄悄改变默认行为。
 *
 * 之所以要这一组用例：`ModelKeys.keys` 是端侧的可选模型单一来源，它同时决定默认选择的模型、
 * 心跳上报的选项与标签、以及下载路径。量化版一旦插错位置，新装设备的默认模型就会从 FP32
 * 变成 INT8，而这属于发布行为变化，必须在这里先失败。
 */
class ModelKeysTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `量化版与本体并存且紧跟本体之后`() {
        assertEquals(6, ModelKeys.baseKeys.size)
        assertEquals(ModelKeys.baseKeys.size * 2, ModelKeys.keys.size)
        assertTrue(ModelKeys.keys.all { ModelKeys.isSupported(it) })

        // 每个本体之后必须紧跟它自己的量化版——defaultModelKey 取「第一个已下载的键」，
        // 顺序错了会把默认模型改成量化版。
        ModelKeys.baseKeys.forEachIndexed { index, base ->
            assertEquals(base, ModelKeys.keys[index * 2])
            assertEquals("${base}${ModelKeys.INT8_SUFFIX}", ModelKeys.keys[index * 2 + 1])
        }
    }

    @Test
    fun `默认模型仍是 FP32 本体`() {
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

package com.xgwnje.visionguard.inference.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 量化产物的形态契约：端侧按这些约定找文件、运行时按这些约定接受图。
 *
 * 这里**不加载 ONNX Runtime**：Android 版运行时只有 .so，JVM 单测加载不了它（试过把
 * AAR 里的 x86_64 库解到 `jniLibs`，测试 JVM 仍拿不到 `java.library.path`）。所以本用例
 * 只做能在任何机器上跑的检查——按 ONNX 的 protobuf 字段扫描量化探针，确认它就是 QDQ INT8
 * 图、opset 满足要求。「运行时真的接受它」由真机测量入口证明：模型建会话失败会作为失败项
 * 原样记进报告。
 */
class QuantizedModelContractTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val probeBytes: ByteArray
        get() = javaClass.getResourceAsStream("/int8-probe.onnx")?.use { it.readBytes() }
            ?: error("测试资源缺失：int8-probe.onnx")

    @Test
    fun `量化探针是 QDQ INT8 图且 opset 不低于 13`() {
        val bytes = probeBytes
        assertTrue("探针模型应非空", bytes.size > 0)

        val text = String(bytes, Charsets.ISO_8859_1)
        // QDQ 的四个算子名在 protobuf 里以明文出现；缺任何一个都不是 QDQ 图。
        listOf("QuantizeLinear", "DequantizeLinear", "Conv", "Relu").forEach { op ->
            assertTrue("量化探针应包含 $op", text.contains(op))
        }
        assertTrue("量化探针应带 int8 权重（protobuf 字段名含 int8）", text.contains("int8"))

        // opset 号以 varint 编码在 int64 字段里，这里只确认存在 opset 记录且取值 >= 13。
        // QDQ 的逐通道 DequantizeLinear 需要 opset >= 13，低于它运行时拒绝加载。
        val opsetVersions = readOpsetVersions(bytes)
        assertTrue("探针应声明 onnx opset", opsetVersions.isNotEmpty())
        assertTrue("opset 应 >= 13，实际 $opsetVersions", opsetVersions.any { it >= 13 })
    }

    @Test
    fun `量化产物命名规则与模型键一致`() {
        // CPU 档按 `<键>.onnx` 命名（键名自带 `_int8`），GPU 档按 `<基名>_gpu.tflite`；名字变了这里先失败。
        val key = ModelKeys.cpuKeys.first()
        assertEquals("yolo26m_320_int8", key)
        val base = ModelKeys.baseOf(key)
        assertEquals("yolo26m_320", base)

        val modelsDir = temporary.newFolder("models")
        java.io.File(modelsDir, "${key}.onnx").writeText("x")

        val store = ModelStore(modelsDir, serverUrl = { "http://127.0.0.1:1" })
        assertEquals("${key}.onnx", store.file(key).name)
        assertEquals("${base}_gpu.tflite", store.file(base).name)

        assertTrue(ModelKeys.isQuantized(key))
        assertTrue(ModelKeys.supportsGpu(base))
        assertEquals(base, ModelKeys.baseOf(key))
        assertTrue(ModelKeys.displayName(key).endsWith("CPU"))
        assertEquals(key, ModelKeys.cpuTierOf(base))
    }

    /**
     * 扫 protobuf 里的 `opset_import`（字段 8）取其 version（字段 2）。
     *
     * 不引入 protobuf 依赖：探针结构固定，按字段号做最小解析即可，解不出来就返回空。
     */
    private fun readOpsetVersions(bytes: ByteArray): List<Int> {
        val versions = mutableListOf<Int>()
        var index = 0
        while (index < bytes.size - 1) {
            // 找 opset_import 的标签：字段 8、wire type 2 → 0x42。
            if (bytes[index] != 0x42.toByte()) {
                index++
                continue
            }
            var cursor = index + 1
            val length = readVarint(bytes, cursor) ?: run { index++; continue }
            cursor = length.second
            val end = cursor + length.first
            if (length.first <= 0 || end > bytes.size) {
                index++
                continue
            }
            var inner = cursor
            while (inner < end - 1) {
                if (bytes[inner] == 0x10.toByte()) { // version 字段 2、wire type 0
                    val value = readVarint(bytes, inner + 1)
                    if (value != null) versions += value.first
                    break
                }
                inner++
            }
            index = end
        }
        return versions
    }

    /** 读 varint；返回取值与下一个字节位置。 */
    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var result = 0
        var shift = 0
        var index = start
        while (index < bytes.size) {
            val current = bytes[index].toInt() and 0xFF
            result = result or ((current and 0x7F) shl shift)
            index++
            if (current and 0x80 == 0) return result to index
            shift += 7
            if (shift > 28) return null
        }
        return null
    }
}

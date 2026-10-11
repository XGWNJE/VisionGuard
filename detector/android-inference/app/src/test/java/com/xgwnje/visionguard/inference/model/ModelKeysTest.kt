package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型清单契约。
 *
 * `ModelKeys.keys` 直接驱动来源参数抽屉的模型插槽（未下载的不可选、就地带下载入口），
 * 也决定默认档位、心跳上报的选项与标签、下载路径与清单外文件的清理范围。所以「清单里有哪些档位」
 * 是**用户可见且影响发布行为**的约定：服务端没有的产物不能出现在清单里，否则用户点了必然失败。
 */
class ModelKeysTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `清单是两条后端路线乘两个尺寸的四档`() {
        assertEquals(listOf("yolo26m_320", "yolo26m_640"), ModelKeys.gpuKeys)
        assertEquals(listOf("yolo26m_320_int8", "yolo26m_640_int8"), ModelKeys.cpuKeys)
        // 同一尺寸的 GPU 与 CPU 成对出现，界面顺序即此
        assertEquals(
            listOf("yolo26m_320", "yolo26m_320_int8", "yolo26m_640", "yolo26m_640_int8"),
            ModelKeys.keys
        )
    }

    @Test
    fun `GPU 档只发截断图、CPU 档只发量化本体`() {
        // GPU 档不下 ONNX 本体：CPU 路线的正路是同尺寸 INT8，本体只会白占一倍磁盘
        assertEquals(ModelFormat.GPU_TFLITE, ModelKeys.formatOf("yolo26m_640"))
        assertEquals(ModelFormat.ONNX, ModelKeys.formatOf("yolo26m_640_int8"))
        assertEquals("yolo26m_640_gpu.tflite", ModelKeys.fileName("yolo26m_640"))
        assertEquals("yolo26m_320_gpu.tflite", ModelKeys.fileName("yolo26m_320"))
        assertEquals("yolo26m_640_int8.onnx", ModelKeys.fileName("yolo26m_640_int8"))
    }

    @Test
    fun `每档都能找到同尺寸的 CPU 回落档`() {
        ModelKeys.keys.forEach { key ->
            val cpu = ModelKeys.cpuTierOf(key)
            assertTrue("缺 CPU 回落档：$key", cpu != null && ModelKeys.cpuKeys.contains(cpu))
            // 回落只换后端、不换尺寸
            assertEquals(ModelKeys.baseOf(key), ModelKeys.baseOf(cpu!!))
        }
    }

    @Test
    fun `默认档是最小的 CPU 档`() {
        assertEquals("yolo26m_320_int8", ModelKeys.defaultKey)
        assertTrue(ModelKeys.isQuantized(ModelKeys.defaultKey))
        assertTrue(!ModelKeys.supportsGpu(ModelKeys.defaultKey))
    }

    @Test
    fun `后端写在展示名里`() {
        assertEquals("YOLO26M · 320 GPU", ModelKeys.displayName("yolo26m_320"))
        assertEquals("YOLO26M · 320 CPU", ModelKeys.displayName("yolo26m_320_int8"))
        assertEquals("YOLO26M · 640 GPU", ModelKeys.displayName("yolo26m_640"))
        assertEquals("YOLO26M · 640 CPU", ModelKeys.displayName("yolo26m_640_int8"))
        // 无法识别的键原样返回，不做猜测
        assertEquals("unknown_key", ModelKeys.displayName("unknown_key"))
    }

    @Test
    fun `已下架的旧档位不再被支持`() {
        listOf(
            "yolo26n_320", "yolo26n_640", "yolo26s_320", "yolo26s_640",
            "yolo26n_320_int8", "yolo26s_640_int8", "yolo26m_320_int16", "yolo26x_320"
        ).forEach { key ->
            assertTrue("旧档位不该在清单里：$key", !ModelKeys.isSupported(key))
        }
    }

    @Test
    fun `下载产物落到对应文件名`() {
        val dir = temporary.newFolder("models")
        val store = ModelStore(dir, "http://127.0.0.1:1")
        assertEquals("yolo26m_640_gpu.tflite", store.file("yolo26m_640").name)
        assertEquals("yolo26m_320_int8.onnx", store.file("yolo26m_320_int8").name)
        // GPU 档没有 ONNX 本体入口，但 CPU 引擎要能拿到同尺寸的量化本体
        assertEquals("yolo26m_320_int8.onnx", store.cpuFile("yolo26m_320")?.name)
        assertEquals("yolo26m_640_int8.onnx", store.cpuFile("yolo26m_640_int8")?.name)
        assertEquals(null, store.gpuFile("yolo26m_640_int8"))
    }

    @Test
    fun `空目录里没有任何档位算已下载`() {
        val dir = temporary.newFolder("models-empty")
        val store = ModelStore(dir, "http://127.0.0.1:1")
        ModelKeys.keys.forEach { key ->
            assertTrue("空目录不该算已下载：$key", !store.isDownloaded(key))
        }
        assertTrue(store.downloadedKeys().isEmpty())
    }

    @Test
    fun `清单外的残留文件会被清理`() {
        val dir = temporary.newFolder("models-cleanup")
        val staleOnnx = java.io.File(dir, "yolo26n_320.onnx").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }
        val staleGpu = java.io.File(dir, "yolo26m_640_anchor_float32.tflite").apply { writeBytes(ByteArray(4096)) }
        val staleTmp = java.io.File(dir, "yolo26m_320_int8.onnx.tmp").apply { writeBytes(ByteArray(1024)) }
        val kept = java.io.File(dir, "yolo26m_320_int8.onnx").apply { writeBytes(ByteArray(2 * 1024 * 1024)) }

        val store = ModelStore(dir, "http://127.0.0.1:1")
        val (files, bytes) = runBlocking { store.cleanupUnknownFiles() }

        assertEquals(3, files)
        assertTrue(bytes > 0)
        assertTrue(!staleOnnx.exists() && !staleGpu.exists() && !staleTmp.exists())
        assertTrue("清单内的文件不能被删：${kept.name}", kept.exists())
    }

    @Test
    fun `键长度不超服务端上限`() {
        // 服务端 sanitizeModelKey 限 64 字符，长键会被整条丢弃。
        ModelKeys.keys.forEach { assertTrue("键过长：$it", it.length <= 64) }
        // 端侧一次最多上报 16 个选项。
        assertTrue(ModelKeys.keys.size <= 16)
    }
}

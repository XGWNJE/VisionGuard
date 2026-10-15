package com.xgwnje.visionguard.inference.model

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class ModelKeysTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `两个档位与空选择`() {
        assertEquals(listOf("yolo26m_320", "yolo26m_640"), ModelKeys.keys)
        assertEquals(listOf("M320", "M640"), ModelKeys.keys.map(ModelKeys::displayName))
        assertEquals("", ModelKeys.defaultKey)
        assertFalse(ModelKeys.isSupported("yolo26m_320_int8"))
        assertEquals(setOf("yolo26m_320_int8.onnx", "yolo26m_640_int8.onnx", "yolo26m_320_gpu.tflite", "yolo26m_640_gpu.tflite"), ModelKeys.fileNameSet())
    }

    @Test fun `单个产物不能开放档位 两个齐备才能选择`() {
        val dir = temporary.newFolder()
        val store = ModelStore(dir, { "http://127.0.0.1:1" })
        val key = ModelKeys.keys.first()
        assertTrue(store.downloadedKeys().isEmpty())
        store.file(key, ModelFormat.ONNX).writeBytes(ByteArray(1024 * 1024))
        assertFalse(store.isDownloaded(key))
        store.file(key, ModelFormat.GPU_TFLITE).writeBytes(ByteArray(1024 * 1024))
        assertEquals(listOf(key), store.downloadedKeys())
        assertEquals("yolo26m_320_int8.onnx", store.cpuFile(key)?.name)
    }

    @Test fun `一次下载两个产物 失败不开放档位 删除覆盖整档`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requested = mutableListOf<String>()
        var failGpu = true
        server.createContext("/models/") { exchange ->
            val name = exchange.requestURI.path.substringAfterLast('/')
            requested += name
            val fail = failGpu && name.endsWith("tflite")
            val bytes = ByteArray(if (fail) 0 else 1024 * 1024)
            exchange.sendResponseHeaders(if (fail) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val store = ModelStore(temporary.newFolder(), { "http://127.0.0.1:${server.address.port}" })
            val key = ModelKeys.keys.first()
            assertNotNull(store.download(key))
            assertFalse(store.isDownloaded(key))
            assertEquals(ModelKeys.fileNameSet().filter { it.contains("320") }.toSet(), requested.toSet())
            failGpu = false
            assertNull(store.download(key))
            assertTrue(store.isDownloaded(key))
            assertTrue(store.delete(key))
            assertFalse(store.isDownloaded(key))
            assertFalse(store.file(key, ModelFormat.ONNX).exists())
            assertFalse(store.file(key, ModelFormat.GPU_TFLITE).exists())
        } finally { server.stop(0) }
    }

    @Test fun `清理只移除清单外文件`() = runBlocking {
        val dir = temporary.newFolder()
        val kept = java.io.File(dir, "yolo26m_320_int8.onnx").apply { writeBytes(ByteArray(1024)) }
        val stale = java.io.File(dir, "yolo26n_320.onnx").apply { writeBytes(ByteArray(1024)) }
        val store = ModelStore(dir, { "http://127.0.0.1:1" })
        assertEquals(1, store.cleanupUnknownFiles().first)
        assertTrue(kept.exists())
        assertFalse(stale.exists())
    }
}

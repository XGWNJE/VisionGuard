package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Android 推理端的模型清单。
 *
 * 与 Windows modern 档同一套 YOLO26 模型（`[1,300,6]`，模型内含 NMS）：Android 的
 * ONNX Runtime 属于现代档，不需要 Win7 legacy 的 YOLOv5 模型；解析器仍保留 YOLOv5 分支，
 * 以便与 Windows 端对拍并容纳旧模型文件。
 */
object ModelKeys {
    val keys: List<String> = listOf(
        "yolo26n_320", "yolo26n_640",
        "yolo26s_320", "yolo26s_640",
        "yolo26m_320", "yolo26m_640"
    )

    val defaultKey: String = keys.first()

    fun isSupported(key: String): Boolean = keys.contains(key)

    /** 界面展示名，例如 `YOLO26n · 320`。 */
    fun displayName(key: String): String {
        val parts = key.split('_')
        if (parts.size != 2 || !parts[0].startsWith("yolo26")) return key
        val size = parts[1]
        val variant = parts[0].removePrefix("yolo26").uppercase()
        return "YOLO26$variant · $size"
    }
}

/**
 * 模型按需下载与本地缓存。
 *
 * 与 Windows `Utils/ModelManager.cs` 一致：模型不入版本控制、不随安装包分发，
 * 从统一服务的 `/models/<键>.onnx` 静态端点按需下载；下载先写 `.tmp`，校验声明长度后
 * 再替换正式文件，长度不符时删除临时文件并报可读原因。
 */
class ModelStore(private val modelsDir: File, private val serverUrl: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun file(key: String): File = File(modelsDir, "$key.onnx")

    fun isDownloaded(key: String): Boolean {
        val target = file(key)
        return target.isFile && target.length() >= OnnxEngine.MIN_MODEL_BYTES
    }

    /** 已下载的模型键，按清单顺序。 */
    fun downloadedKeys(): List<String> = ModelKeys.keys.filter { isDownloaded(it) }

    /**
     * 下载指定模型；成功返回 null，失败返回可读原因。
     * [onProgress] 参数为已接收字节与声明总字节（未知时为 -1）。
     */
    suspend fun download(key: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String? =
        withContext(Dispatchers.IO) {
            if (!ModelKeys.isSupported(key)) return@withContext "本端不支持模型 $key"
            modelsDir.mkdirs()
            val target = file(key)
            val temporary = File(modelsDir, "$key.onnx.tmp")
            val request = Request.Builder().url("$serverUrl/models/$key.onnx").get().build()

            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext "下载失败：HTTP ${response.code}"
                    val body = response.body ?: return@withContext "下载失败：响应为空"
                    val declared = body.contentLength()
                    var received = 0L
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                received += read
                                onProgress(received, declared)
                            }
                        }
                    }
                    if (declared > 0 && received != declared) {
                        temporary.delete()
                        return@withContext "下载不完整（$received/$declared 字节）"
                    }
                    if (received < OnnxEngine.MIN_MODEL_BYTES) {
                        temporary.delete()
                        return@withContext "下载内容异常（仅 $received 字节）"
                    }
                }
            } catch (error: Exception) {
                temporary.delete()
                return@withContext "下载失败：${error.message ?: error::class.java.simpleName}"
            }

            if (!temporary.renameTo(target)) {
                temporary.delete()
                return@withContext "无法保存模型文件"
            }
            null
        }

    /** 删除已下载模型，用于释放空间；不删除正在使用的模型由调用方保证。 */
    suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) { file(key).delete() }

    companion object {
        private const val DOWNLOAD_TIMEOUT_SECONDS = 600L
    }
}

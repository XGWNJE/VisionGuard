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
 *
 * 量化版**并存**而不是替换：服务端 `/models` 是静态目录，同一基名额外分发
 * `<基名>_int8.onnx` 即可，推理链与解析器都不用改（量化前后 I/O 契约一致，已核对）。
 * 之所以并存：Windows 端量化是否可用尚未确定，替换会让两端行为不一致；而且真机实测
 * 最优组合就是 **ONNX Runtime · CPU + INT8**（见 [InferenceProvider.production]），
 * 保留 FP32 作为回退有助于排查。
 */
object ModelKeys {
    /** 原始（FP32）模型键，顺序即默认选择顺序。 */
    val baseKeys: List<String> = listOf(
        "yolo26n_320", "yolo26n_640",
        "yolo26s_320", "yolo26s_640",
        "yolo26m_320", "yolo26m_640"
    )

    /** 量化版后缀。 */
    const val INT8_SUFFIX = "_int8"

    /**
     * 全部可选键：**每个 FP32 键之后紧跟它的量化版**。
     *
     * 顺序不能随意调整：[InferenceRuntime.defaultModelKey] 取"第一个已下载的键"，
     * 量化版排在各自本体之后，才能保证新装设备的默认模型仍是 FP32。
     */
    val keys: List<String> = baseKeys.flatMap { listOf(it, it + INT8_SUFFIX) }

    val defaultKey: String = baseKeys.first()

    fun isSupported(key: String): Boolean = keys.contains(key)

    /** 是否为量化版键。 */
    fun isQuantized(key: String): Boolean = key.endsWith(INT8_SUFFIX)

    /** 量化版键对应的本体键。 */
    fun baseOf(key: String): String = key.removeSuffix(INT8_SUFFIX)

    /**
     * 模型文件名。
     *
     * 服务端 `/models` 按文件名静态分发，因此量化版键天然对应 `<键>.onnx`，不需要额外映射。
     */
    fun fileName(key: String): String = "$key.onnx"

    /**
     * 界面展示名，例如 `YOLO26n · 320`、`YOLO26n · 320 INT8`。
     *
     * 无法识别的键原样返回，不做猜测。
     */
    fun displayName(key: String): String {
        val quantized = isQuantized(key)
        val parts = baseOf(key).split('_')
        if (parts.size != 2 || !parts[0].startsWith("yolo26")) return key
        val size = parts[1]
        val variant = parts[0].removePrefix("yolo26").uppercase()
        return "YOLO26$variant · $size" + if (quantized) " INT8" else ""
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

    fun file(key: String): File = File(modelsDir, ModelKeys.fileName(key))

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
            val temporary = File(modelsDir, "${ModelKeys.fileName(key)}.tmp")
            val request = Request.Builder().url("$serverUrl/models/${ModelKeys.fileName(key)}").get().build()

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

            // 与 Windows 一致：替换前先删旧文件；目标已存在时 File.renameTo 会直接失败。
            if (target.exists() && !target.delete()) {
                temporary.delete()
                return@withContext "无法替换已有模型文件"
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

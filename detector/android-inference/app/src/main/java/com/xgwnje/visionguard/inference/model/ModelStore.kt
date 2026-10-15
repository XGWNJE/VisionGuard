package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 两个用户档位；每档同时管理 INT8 CPU 本体与 GPU 截断图。 */
object ModelKeys {
    const val INT8_SUFFIX = "_int8"
    val keys = listOf("yolo26m_320", "yolo26m_640")
    val gpuKeys = keys
    val cpuKeys = keys.map { it + INT8_SUFFIX }
    val defaultKey = ""
    fun isSupported(key: String) = key in keys
    fun supportsGpu(key: String) = key in keys
    fun isQuantized(key: String) = key in cpuKeys
    fun baseOf(key: String) = key.removeSuffix(INT8_SUFFIX)
    fun cpuTierOf(key: String): String? = (baseOf(key) + INT8_SUFFIX).takeIf { it in cpuKeys }
    fun formatOf(key: String) = if (supportsGpu(key)) ModelFormat.GPU_TFLITE else ModelFormat.ONNX
    fun fileName(key: String, format: ModelFormat = formatOf(key)): String = when (format) {
        ModelFormat.ONNX -> "${cpuTierOf(key) ?: key}.onnx"
        ModelFormat.GPU_TFLITE -> "${baseOf(key)}_gpu.tflite"
    }
    fun fileNameSet(): Set<String> = keys.flatMap { key -> ModelFormat.entries.map { fileName(key, it) } }.toSet()
    fun displayName(key: String): String = when (key) {
        keys[0] -> "M320"
        keys[1] -> "M640"
        "" -> "未选择"
        else -> key
    }
}

/**
 * 模型按需下载与本地缓存。
 *
 * 与 Windows `Utils/ModelManager.cs` 一致：模型不入版本控制、不随安装包分发，
 * 从统一服务的 `/models/<文件名>` 静态端点按需下载；下载先写 `.tmp`，校验声明长度后
 * 再替换正式文件，长度不符时删除临时文件并报可读原因。清单外的历史文件由
 * [cleanupUnknownFiles] 盘点清理，避免旧档位一直占着磁盘。
 *
 * [serverUrl] 是取值函数而不是常量：模型必须从**当前登录会话所在的服务**下载。写死构建时的地址
 * 会让本地服务版绕过局域网、跑到正式服务端去下（实测就是这个问题：LAN 调试却在走公网）。
 */
class ModelStore(
    private val modelsDir: File,
    private val serverUrl: () -> String,
    private val log: (String) -> Unit = { },
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun file(key: String, format: ModelFormat = ModelKeys.formatOf(key)): File =
        File(modelsDir, ModelKeys.fileName(key, format))

    fun isDownloaded(key: String): Boolean = ModelKeys.isSupported(key) &&
        ModelFormat.entries.all { format -> file(key, format).let { it.isFile && it.length() >= format.minBytes } }

    /** 已下载的档位键，按清单顺序。 */
    fun downloadedKeys(): List<String> = ModelKeys.keys.filter { isDownloaded(it) }

    /** GPU 档是否就绪：有产物定义且截断图已下载。 */
    fun isGpuReady(key: String): Boolean = ModelKeys.supportsGpu(key) && file(key, ModelFormat.GPU_TFLITE).let { it.isFile && it.length() >= ModelFormat.GPU_TFLITE.minBytes }

    /** GPU 档的截断图文件；该键不是 GPU 档时返回 null。 */
    fun gpuFile(key: String): File? =
        if (ModelKeys.supportsGpu(key)) file(key, ModelFormat.GPU_TFLITE) else null

    /** CPU 引擎要加载的 ONNX 文件：GPU 档取同尺寸 CPU 档，CPU 档取自己；取不到返回 null。 */
    fun cpuFile(key: String): File? =
        ModelKeys.cpuTierOf(key)?.let { file(it, ModelFormat.ONNX) }

    /**
     * 下载指定档位的产物；成功返回 null，失败返回可读原因。
     * [onProgress] 参数为已接收字节与声明总字节（未知时为 -1）。
     */
    suspend fun download(
        key: String,
        format: ModelFormat? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): String? {
        if (format != null) return downloadArtifact(key, format, onProgress)
        if (!ModelKeys.isSupported(key)) return "本端不支持模型 $key"
        for (item in ModelFormat.entries) {
            val error = downloadArtifact(key, item, onProgress)
            if (error != null) return error
        }
        return null
    }

    private suspend fun downloadArtifact(
        key: String, format: ModelFormat, onProgress: (Long, Long) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        if (!ModelKeys.isSupported(key) && key !in ModelKeys.cpuKeys) return@withContext "本端不支持模型 $key"
        modelsDir.mkdirs()
        val fileName = ModelKeys.fileName(key, format)
        val target = File(modelsDir, fileName)
        val temporary = File(modelsDir, "$fileName.tmp")
        val base = serverUrl().trimEnd('/')
        val request = Request.Builder().url("$base/models/$fileName").get().build()
        log("开始下载 $fileName ← $base")

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
                if (received < format.minBytes) {
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

    /** 删除已下载的档位产物，用于释放空间；不删除正在使用的模型由调用方保证。 */
    suspend fun delete(key: String): Boolean = withContext(Dispatchers.IO) {
        ModelFormat.entries.map { file(key, it) }.map { !it.exists() || it.delete() }.all { it }
    }

    /**
     * 删除清单外的残留文件（旧档位模型、半截 `.tmp`），返回（文件数, 字节数）。
     *
     * 档位收敛后设备上会留下 `yolo26n_*` / `yolo26s_*` 与旧 GPU 产物；它们已经不可能被选中，
     * 留着只是占空间。只动 `files/models/` 这一个目录，不碰账号、配置与业务数据。
     */
    suspend fun cleanupUnknownFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val known = ModelKeys.fileNameSet()
        var files = 0
        var bytes = 0L
        modelsDir.listFiles()?.forEach { candidate ->
            if (!candidate.isFile || candidate.name in known) return@forEach
            val size = candidate.length()
            if (candidate.delete()) {
                files += 1
                bytes += size
            }
        }
        files to bytes
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_SECONDS = 600L
    }
}

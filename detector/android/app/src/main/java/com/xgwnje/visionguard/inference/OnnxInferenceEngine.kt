package com.xgwnje.visionguard.inference

import android.content.Context
import android.os.Build
import android.util.Log
import ai.onnxruntime.*
import ai.onnxruntime.providers.NNAPIFlags
import com.xgwnje.visionguard.util.InferenceDiagnostics
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * ONNX Runtime Mobile 推理引擎封装。
 *
 * 负责从服务器下载模型到内部存储并加载 ONNX 会话，
 * 提供 run() 方法执行推理。
 */
class OnnxInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VG_Inference"
        private const val ASSETS_MODEL_DIR = "models"
        private const val LOCAL_MODEL_DIR = "models"
        private const val PROFILE_SAMPLE_RUNS = 3
        private const val BACKEND_EVIDENCE_FILE = "inference-backend-evidence.json"
        private const val NNAPI_PROVIDER = "NnapiExecutionProvider"
        private val PROVIDER_PATTERN = Regex("\\\"provider\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null
    private var currentModelPath: String? = null
    private var profilingEnabled = false
    private var profileSampleCount = 0
    private var profileCompleted = false
    var backendStatus: InferenceBackendStatus = AndroidInferenceBackendPolicy.resolve(
        InferenceBackend.NNAPI,
        nnapiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    )
        private set

    /** 检查是否已加载模型 */
    val isLoaded: Boolean
        get() = session != null

    /**
     * 加载 ONNX 模型。
     *
     * @param modelFileName 模型文件名，如 "yolo26n_320.onnx"
     * @param inputSize 输入分辨率（仅用于日志记录）
     * @return 是否加载成功
     */
    fun loadModel(modelFileName: String, inputSize: Int): Boolean {
        close()
        clearBackendEvidence()

        return try {
            val localDir = File(context.filesDir, LOCAL_MODEL_DIR)
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val modelFile = File(localDir, modelFileName)

            // 如果本地不存在，或文件为空，从服务器下载
            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.i(TAG, "Model not found locally, downloading from server: $modelFileName")
                if (!downloadModel(modelFileName, modelFile)) {
                    Log.e(TAG, "Failed to download model")
                    if (modelFile.exists()) modelFile.delete()
                    return false
                }
            }

            if (!modelFile.exists() || modelFile.length() == 0L) {
                Log.e(TAG, "Model file missing or empty: ${modelFile.absolutePath}")
                return false
            }

            env = OrtEnvironment.getEnvironment()
            val requestedStatus = AndroidInferenceBackendPolicy.resolve(
                InferenceBackend.NNAPI,
                nnapiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            )
            val (newSession, resolvedStatus) = createSession(modelFile.absolutePath, requestedStatus)
            session = newSession
            backendStatus = resolvedStatus
            if (resolvedStatus.active == InferenceBackend.CPU) {
                writeBackendEvidence("", emptyMap(), nnapiUsed = false)
            }

            // 动态获取输入/输出节点名（兼容不同导出方式）
            inputName = newSession.inputNames.firstOrNull()
            outputName = newSession.outputNames.firstOrNull()

            currentModelPath = modelFile.absolutePath

            Log.i(TAG, "Model loaded: $modelFileName (inputSize=$inputSize, inputName=$inputName, outputName=$outputName)")
            Log.i(
                TAG,
                "Inference backend: requested=${backendStatus.requested} active=${backendStatus.active} " +
                    "provider=${backendStatus.actualProvider ?: "pending"} fallback=${backendStatus.fallbackReason}"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: $modelFileName", e)
            close()
            // 删除可能损坏的本地文件，下次启动会重新下载
            try {
                val modelFile = File(File(context.filesDir, LOCAL_MODEL_DIR), modelFileName)
                if (modelFile.exists()) {
                    modelFile.delete()
                    Log.w(TAG, "Deleted corrupted model file: ${modelFile.absolutePath}")
                }
            } catch (cleanupEx: Exception) {
                Log.w(TAG, "Failed to cleanup corrupted model", cleanupEx)
            }
            false
        }
    }

    /**
     * 运行 ONNX 推理。
     *
     * @param inputData CHW RGB float 数组，shape = [1, 3, inputSize, inputSize]
     * @param shape 输入张量 shape，如 longArrayOf(1, 3, 320, 320)
     * @return 原始输出 float 数组；失败返回空数组
     */
    fun run(inputData: FloatArray, shape: LongArray): FloatArray {
        val currentSession = session
        val currentEnv = env
        val currentInputName = inputName

        if (currentSession == null || currentEnv == null || currentInputName == null) {
            Log.w(TAG, "Inference called but session not loaded")
            return FloatArray(0)
        }

        return try {
            val inferenceStart = System.currentTimeMillis()

            // 确保 FloatBuffer position=0，避免某些环境下的异常
            val floatBuffer = FloatBuffer.wrap(inputData)
            floatBuffer.rewind()
            val tensor = OnnxTensor.createTensor(currentEnv, floatBuffer, shape)
            val inputs = mapOf(currentInputName to tensor)
            val results = currentSession.run(inputs)
            val inferenceMs = System.currentTimeMillis() - inferenceStart

            val outputTensor = results[0] as? OnnxTensor
            val outputArray = if (outputTensor != null) {
                val buffer = outputTensor.floatBuffer
                buffer.rewind()  // 确保从开头读取
                val arr = FloatArray(buffer.remaining())
                buffer.get(arr)
                val shapeStr = outputTensor.info.shape.contentToString()
                Log.i(TAG, "推理完成: ${inferenceMs}ms, output shape=$shapeStr")
                InferenceDiagnostics.diagnoseOnnxOutput(arr, shape[2].toInt(), "engine")
                arr
            } else {
                Log.e(TAG, "Output tensor is null or not float")
                FloatArray(0)
            }

            tensor.close()
            results.close()
            recordProfileSample()
            outputArray
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            FloatArray(0)
        }
    }

    /** 关闭会话并释放资源 */
    fun close() {
        finishProfiling()
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing session", e)
        }
        session = null
        inputName = null
        outputName = null

        try {
            env?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing environment", e)
        }
        env = null
        currentModelPath = null
    }

    private fun createSession(
        modelPath: String,
        requestedStatus: InferenceBackendStatus
    ): Pair<OrtSession, InferenceBackendStatus> {
        if (requestedStatus.active != InferenceBackend.NNAPI) {
            profilingEnabled = false
            return createSessionWithOptions(modelPath, InferenceBackend.CPU) to requestedStatus
        }

        return try {
            val profilePrefix = File(
                context.filesDir,
                "inference-profile-${System.currentTimeMillis()}"
            ).absolutePath
            profilingEnabled = true
            profileSampleCount = 0
            profileCompleted = false
            createSessionWithOptions(modelPath, InferenceBackend.NNAPI, profilePrefix) to requestedStatus
        } catch (e: Exception) {
            profilingEnabled = false
            Log.e(TAG, "NNAPI session creation failed; falling back to CPU", e)
            val fallback = requestedStatus.copy(
                active = InferenceBackend.CPU,
                fallbackReason = "NNAPI 初始化失败，已回退 CPU"
            )
            createSessionWithOptions(modelPath, InferenceBackend.CPU) to fallback
        }
    }

    private fun createSessionWithOptions(
        modelPath: String,
        backend: InferenceBackend,
        profilePrefix: String? = null
    ): OrtSession {
        val currentEnv = env ?: error("ONNX Runtime environment is not initialized")
        return OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (backend) {
                InferenceBackend.CPU -> Unit
                InferenceBackend.NNAPI -> {
                    // 防止 NNAPI 静默使用 reference CPU；不支持的节点仍由 ORT CPU EP 承接。
                    options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED, NNAPIFlags.USE_NCHW))
                    if (profilePrefix != null) {
                        options.enableProfiling(profilePrefix)
                    }
                }
                InferenceBackend.QNN -> error("QNN is not compiled into this Android runtime")
            }
            currentEnv.createSession(modelPath, options)
        }
    }

    private fun recordProfileSample() {
        if (!profilingEnabled || profileCompleted) return
        profileSampleCount++
        if (profileSampleCount < PROFILE_SAMPLE_RUNS) return
        finishProfiling()
    }

    private fun finishProfiling() {
        if (!profilingEnabled || profileCompleted) return
        val currentSession = session ?: return
        try {
            val profilePath = currentSession.endProfiling()
            profileCompleted = true
            profilingEnabled = false
            val profileFile = File(profilePath)
            if (!profileFile.isFile) {
                Log.w(TAG, "NNAPI profiling completed but file is missing: $profilePath")
                return
            }

            val profileText = profileFile.readText()
            val providers = PROVIDER_PATTERN.findAll(profileText)
                .map { it.groupValues[1] }
                .toList()
            val providerCounts = providers.groupingBy { it }.eachCount()
            val nnapiUsed = providers.any { it == NNAPI_PROVIDER }
            val updatedStatus = if (nnapiUsed) {
                backendStatus.copy(
                    actualProvider = NNAPI_PROVIDER,
                    profilePath = profilePath
                )
            } else {
                backendStatus.copy(
                    active = InferenceBackend.CPU,
                    actualProvider = providers.firstOrNull(),
                    profilePath = profilePath,
                    fallbackReason = "模型未产生 NNAPI 分区，CPU 执行"
                )
            }
            backendStatus = updatedStatus
            writeBackendEvidence(profilePath, providerCounts, nnapiUsed)
            Log.i(
                TAG,
                "Inference backend evidence: requested=${updatedStatus.requested} " +
                    "active=${updatedStatus.active} provider=${updatedStatus.actualProvider ?: "none"} " +
                    "hardwareConfirmed=${updatedStatus.hardwareExecutionConfirmed} profile=$profilePath"
            )
        } catch (e: Exception) {
            profileCompleted = true
            profilingEnabled = false
            Log.w(TAG, "Failed to finalize NNAPI profiling", e)
        }
    }

    private fun writeBackendEvidence(
        profilePath: String,
        providerCounts: Map<String, Int>,
        nnapiUsed: Boolean
    ) {
        try {
            val countsJson = providerCounts.entries.joinToString(",") { (provider, count) ->
                "\"${escapeJson(provider)}\":$count"
            }
            val evidence = """
                {
                  "requestedBackend":"${backendStatus.requested}",
                  "activeBackend":"${backendStatus.active}",
                  "actualProvider":${backendStatus.actualProvider?.let { "\"${escapeJson(it)}\"" } ?: "null"},
                  "nnapiCpuDisabled":true,
                  "hardwareExecutionConfirmed":$nnapiUsed,
                  "profilePath":"${escapeJson(profilePath)}",
                  "providerCounts":{$countsJson}
                }
            """.trimIndent()
            File(context.filesDir, BACKEND_EVIDENCE_FILE).writeText(evidence)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist backend evidence", e)
        }
    }

    private fun clearBackendEvidence() {
        try {
            File(context.filesDir, BACKEND_EVIDENCE_FILE).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear stale backend evidence", e)
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private fun downloadModel(fileName: String, destFile: File): Boolean {
        return try {
            if (destFile.exists()) destFile.delete()
            val url = "${com.xgwnje.visionguard.AppConstants.SERVER_URL}/models/$fileName"
            val request = Request.Builder().url(url).build()
            downloadHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Model download failed: HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                val tmpFile = File(destFile.parent, "$fileName.tmp")
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                tmpFile.renameTo(destFile)
            }
            Log.i(TAG, "Model downloaded: ${destFile.absolutePath} (${destFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Model download error: ${e.message}")
            false
        }
    }
}

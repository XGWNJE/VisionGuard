package com.xgwnje.visionguard.inference.source

import android.content.Context
import android.content.SharedPreferences
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.inference.detect.DetectionParams
import com.xgwnje.visionguard.inference.detect.MaskRegion

/**
 * 本机来源与检测配置持久化。
 *
 * 按 服务 / 账号 / 设备 分区（[AccountSession.scope] 的 SHA-256 摘要作为文件名），
 * 与 Windows `Utils/SettingsStore.cs` 的分区语义一致：换账号不串用配置。
 * 运行中的参数是冷改动，这里只负责读写，不做热应用。
 */
class SettingsStore(private val preferences: SharedPreferences) {

    /** 全部已登记来源，按登记顺序。 */
    fun sources(): List<SourceConfig> {
        val indexes = preferences.getString(KEY_INDEXES, null)
        val ids = if (indexes.isNullOrBlank()) emptyList() else indexes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return ids.mapNotNull { id -> readSource(id) }
    }

    /** 是否已配置本机相机来源。 */
    fun hasLocalCameraSource(): Boolean = sources().any { it.mode == SourceCaptureMode.LocalCamera }

    /** 新增或整体覆盖一路来源，并保持来源登记顺序。 */
    fun upsertSource(config: SourceConfig) {
        require(SourceConfig.isValidSourceId(config.sourceId)) { "来源身份不合法" }
        require(SourceConfig.validateName(config.sourceName) == null) { "来源名称不合法" }
        val ids = mutableListOf<String>()
        ids.addAll(sources().map { it.sourceId })
        if (!ids.contains(config.sourceId)) ids.add(config.sourceId)
        writeSource(config)
        saveIndexes(ids)
    }

    /** 仅改来源名；来源不存在时返回 false。 */
    fun renameSource(sourceId: String, name: String): Boolean {
        val current = readSource(sourceId) ?: return false
        val error = SourceConfig.validateName(name)
        require(error == null) { error ?: "" }
        writeSource(current.copy(sourceName = SourceConfig.normalizeName(name)))
        return true
    }

    fun updateParams(sourceId: String, params: DetectionParams): Boolean {
        val current = readSource(sourceId) ?: return false
        writeSource(current.copy(params = params))
        return true
    }

    /** 遮罩只属于本机来源配置，保存即覆盖整组。 */
    fun updateMasks(sourceId: String, masks: List<MaskRegion>): Boolean {
        val current = readSource(sourceId) ?: return false
        masks.forEach { require(MaskRegion.validate(it) == null) { "遮罩区域不合法" } }
        writeSource(current.copy(masks = masks))
        return true
    }

    fun updateCameraLensFacing(sourceId: String, lensFacing: Int): Boolean {
        val current = readSource(sourceId) ?: return false
        writeSource(current.copy(cameraLensFacing = lensFacing))
        return true
    }

    /** 删除来源，同时清理它的全部键。 */
    fun removeSource(sourceId: String): Boolean {
        val remaining = sources().filter { it.sourceId != sourceId }
        if (remaining.size == sources().size) return false
        val editor = preferences.edit()
        for (suffix in SUFFIXES) editor.remove("Source.$sourceId.$suffix")
        editor.remove(expectedKey(sourceId))
        editor.apply()
        saveIndexes(remaining.map { it.sourceId })
        return true
    }

    /**
     * 运行意图：进程被系统回收后由前台服务据此恢复来源。
     * 与心跳里的 `monitoringExpected` 是同一语义，主动停止时必须清除。
     */
    fun isMonitoringExpected(sourceId: String): Boolean = preferences.getBoolean(expectedKey(sourceId), false)

    fun setMonitoringExpected(sourceId: String, expected: Boolean) {
        check(preferences.edit().putBoolean(expectedKey(sourceId), expected).commit()) { "保存运行意图失败" }
    }

    private fun expectedKey(sourceId: String) = "Active.$sourceId"

    /**
     * 服务端来源列表变化时裁剪本地远程来源：
     * 只删除“由服务端绑定自动入列”的来源（已记录发布端身份），用户手建的远程槽位保留，等待被绑定。
     */
    fun removeRemoteSourcesNotIn(streamSourceIds: Set<String>): List<String> {
        val removed = mutableListOf<String>()
        for (source in sources()) {
            if (source.mode != SourceCaptureMode.RemoteStream) continue
            if (source.remotePublisherDeviceId.isNullOrBlank()) continue
            if (streamSourceIds.contains(source.sourceId)) continue
            if (removeSource(source.sourceId)) removed.add(source.sourceId)
        }
        return removed
    }

    /** 服务端下发的来源上限内允许的来源数量检查。 */
    fun canAddSource(limit: Int): Boolean = sources().size < limit.coerceIn(1, 16)

    private fun readSource(sourceId: String): SourceConfig? {
        if (!SourceConfig.isValidSourceId(sourceId)) return null
        val mode = when (preferences.getString(key(sourceId, "Mode"), null)) {
            MODE_LOCAL -> SourceCaptureMode.LocalCamera
            MODE_REMOTE -> SourceCaptureMode.RemoteStream
            else -> return null
        }
        val modelKey = preferences.getString(key(sourceId, "ModelKey"), null) ?: return null
        val params = DetectionParams(
            confidencePercent = preferences.getInt(key(sourceId, "Threshold"), DetectionParams.DEFAULT_CONFIDENCE_PERCENT),
            targetFps = preferences.getInt(key(sourceId, "Fps"), DetectionParams.DEFAULT_TARGET_FPS),
            cooldownSeconds = preferences.getInt(key(sourceId, "Cooldown"), DetectionParams.DEFAULT_COOLDOWN_SECONDS),
            modelKey = modelKey,
            watchedClasses = DetectionParams.parseWatchedClasses(preferences.getString(key(sourceId, "Targets"), null))
                ?: setOf(DetectionParams.DEFAULT_TARGET)
        )
        val name = preferences.getString(key(sourceId, "Name"), null)
            ?: SourceConfig.defaultName(mode, null)
        return SourceConfig(
            sourceId = sourceId,
            sourceName = name,
            mode = mode,
            params = params,
            masks = MaskRegion.parseList(preferences.getString(key(sourceId, "Masks"), null)),
            cameraLensFacing = preferences.getInt(key(sourceId, "Camera"), SourceConfig.LENS_BACK),
            remotePublisherDeviceId = preferences.getString(key(sourceId, "Publisher"), null),
            remotePublisherName = preferences.getString(key(sourceId, "PublisherName"), null)
        )
    }

    private fun writeSource(config: SourceConfig) {
        val editor = preferences.edit()
            .putString(key(config.sourceId, "Name"), config.sourceName)
            .putString(key(config.sourceId, "Mode"), if (config.mode == SourceCaptureMode.LocalCamera) MODE_LOCAL else MODE_REMOTE)
            .putString(key(config.sourceId, "ModelKey"), config.params.modelKey)
            .putInt(key(config.sourceId, "Threshold"), config.params.confidencePercent)
            .putInt(key(config.sourceId, "Fps"), config.params.targetFps)
            .putInt(key(config.sourceId, "Cooldown"), config.params.cooldownSeconds)
            .putString(key(config.sourceId, "Targets"), config.params.watchedClassesText())
            .putString(key(config.sourceId, "Masks"), MaskRegion.formatList(config.masks))
            .putInt(key(config.sourceId, "Camera"), config.cameraLensFacing)
        if (config.remotePublisherDeviceId != null) {
            editor.putString(key(config.sourceId, "Publisher"), config.remotePublisherDeviceId)
        }
        if (config.remotePublisherName != null) {
            editor.putString(key(config.sourceId, "PublisherName"), config.remotePublisherName)
        }
        check(editor.commit()) { "保存来源配置失败" }
    }

    private fun saveIndexes(ids: List<String>) {
        check(preferences.edit().putString(KEY_INDEXES, ids.joinToString(",")).commit()) { "保存来源列表失败" }
    }

    private fun key(sourceId: String, suffix: String) = "Source.$sourceId.$suffix"

    companion object {
        private const val KEY_INDEXES = "Source.Indexes"
        private const val MODE_LOCAL = "local-camera"
        private const val MODE_REMOTE = "remote-stream"
        private val SUFFIXES = listOf(
            "Name", "Mode", "ModelKey", "Threshold", "Fps", "Cooldown", "Targets", "Masks", "Camera", "Publisher", "PublisherName"
        )

        /** 打开当前会话分区的设置；未登录时用 signed-out 分区，避免写入他人配置。 */
        fun open(context: Context, session: AccountSession?): SettingsStore {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest((session?.scope ?: "signed-out").toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            return SettingsStore(context.getSharedPreferences("inference-settings-$digest", Context.MODE_PRIVATE))
        }
    }
}

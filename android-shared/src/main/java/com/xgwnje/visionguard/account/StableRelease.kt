package com.xgwnje.visionguard.account

import java.net.URI
import java.time.Instant

data class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: String, val state: String)
data class StableRelease(val tag: String, val published: String, val draft: Boolean, val prerelease: Boolean, val assets: List<ReleaseAsset>)
data class ClientUpdate(val version: String, val asset: ReleaseAsset)
object StableReleasePolicy {
    const val REPOSITORY = "XGWNJE/VisionGuard"
    fun version(value: String): List<Int>? {
        if (!Regex("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)").matches(value)) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }
    private fun compare(a: List<Int>, b: List<Int>): Int { a.indices.forEach { if (a[it] != b[it]) return a[it].compareTo(b[it]) }; return 0 }
    fun isNewer(candidate: String, installed: String): Boolean = compare(
        requireNotNull(version(candidate)) { "更新版本无效" },
        requireNotNull(version(installed)) { "当前客户端版本无效" }
    ) > 0
    private fun time(value: String) = runCatching { Instant.parse(value) }.getOrNull()

    /**
     * 本地服务版：只接受与当前本地服务同源的 `/releases/<资产名>`，其余仍走 GitHub 校验。
     * 同源判断含 scheme、host、port 三项，避免把别的服务或别的主机的包当成自己的。
     */
    fun acceptsLocalAsset(url: String, base: String, name: String): Boolean {
        val asset = runCatching { URI(url) }.getOrNull() ?: return false
        val origin = runCatching { URI(base) }.getOrNull() ?: return false
        return asset.scheme == "http" && asset.host == origin.host && asset.port == origin.port &&
            asset.userInfo == null && asset.query == null && asset.path == "/releases/$name"
    }

    fun select(releases: List<StableRelease>, installed: String, client: String, localBase: String? = null): ClientUpdate? {
        val current = requireNotNull(version(installed)) { "当前客户端版本无效" }
        val prefix = when (client) {
            "android-camera" -> "VisionGuard-Detector-v"
            "android-notifier" -> "VisionGuard-Notifier-v"
            "android-inference" -> "VisionGuard-Inference-v"
            else -> error("不支持此客户端更新")
        }
        val stable = releases.filter { !it.draft && !it.prerelease && version(it.tag.removePrefix("v")) != null && time(it.published) != null }.sortedByDescending { time(it.published) }
        val baseline = stable.filter { it.tag.removePrefix("v") == installed }.maxOfOrNull { time(it.published)!! }
        for (release in stable) {
            val number = release.tag.removePrefix("v"); val parsed = version(number)!!
            if (compare(parsed, current) <= 0 || baseline != null && time(release.published)!! <= baseline) continue
            if (baseline == null && parsed[0] != current[0]) continue
            val name = "$prefix$number.apk"; val assets = release.assets.filter { it.name == name }
            if (assets.isEmpty()) continue
            require(assets.size == 1) { "发行文件重复" }
            val asset = assets.single()
            require(asset.state == "uploaded" && asset.size in 1..512L * 1024 * 1024 && Regex("sha256:[0-9a-fA-F]{64}").matches(asset.digest)) { "发行文件元数据不完整" }
            if (localBase == null || !acceptsLocalAsset(asset.url, localBase, name)) {
                val uri = runCatching { URI(asset.url) }.getOrNull()
                require(uri?.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1
                    && uri.path == "/$REPOSITORY/releases/download/${release.tag}/$name") { "GitHub 发行文件元数据不完整或不可信" }
            }
            return ClientUpdate(number, asset)
        }
        return null
    }
}

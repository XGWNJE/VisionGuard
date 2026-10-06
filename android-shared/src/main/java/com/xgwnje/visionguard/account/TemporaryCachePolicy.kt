package com.xgwnje.visionguard.account

object TemporaryCachePolicy {
    const val DAY = 24 * 60 * 60 * 1000L
    private val packageName = Regex("VisionGuard-(Detector|Notifier)-v[0-9]+\\.[0-9]+\\.[0-9]+\\.apk(\\.part)?")
    fun knownUpdate(name: String) = packageName.matches(name)
    fun expiredUpdate(name: String, modified: Long, now: Long, protected: Boolean) = knownUpdate(name) && !protected && now - modified > 7 * DAY
}

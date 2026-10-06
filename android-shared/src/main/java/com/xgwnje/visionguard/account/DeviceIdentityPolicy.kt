package com.xgwnje.visionguard.account

import java.security.MessageDigest
import java.util.Locale

/** Independent of app data, app version, account, device model and display name. */
object DeviceIdentityPolicy {
    fun identity(androidId: String?, packageName: String): String {
        val value = androidId?.lowercase(Locale.ROOT).orEmpty()
        require(value.matches(Regex("[a-f0-9]{1,16}")) && value.any { it != '0' }) { "无法读取稳定设备身份，请检查系统环境" }
        return MessageDigest.getInstance("SHA-256").digest("android|$packageName|$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun model(value: String?, fallback: String = "Android设备"): String {
        val text = value.orEmpty().map { if (it.code < 32 || it.code == 127) ' ' else it }.joinToString("").trim().take(48)
        return (if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text).ifEmpty { fallback }
    }
}

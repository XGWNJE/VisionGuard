package com.xgwnje.visionguard.account

/** Matches the server's UTF-16 name and device-code limits. */
object DisplayNamePolicy {
    const val MAX_LENGTH = 64
    const val DEVICE_CODE_LENGTH = 40
    const val HINT = "名称须为 1–64 个字符，不能包含换行或控制字符"

    fun error(value: String): String? = when {
        value.any { it.code < 32 } -> HINT
        value.trim().isEmpty() -> "请输入名称"
        value.trim().length > MAX_LENGTH -> HINT
        else -> null
    }

    fun normalize(value: String): String {
        require(error(value) == null) { error(value) ?: HINT }
        return value.trim()
    }

    fun acceptsDraft(value: String): Boolean = value.length <= MAX_LENGTH && value.none { it.code < 32 }

    private fun prefix(value: String, limit: Int): String {
        val text = value.take(limit)
        return if (text.lastOrNull()?.isHighSurrogate() == true) text.dropLast(1) else text
    }

    fun generated(value: String, fallback: String = "未命名"): String =
        prefix(value.map { if (it.code < 32) ' ' else it }.joinToString("").trim(), MAX_LENGTH).ifEmpty { fallback }

    fun deviceCode(value: String): String = prefix(value.map {
        if (it.isLetterOrDigit() || it in "._ -") it else '_'
    }.joinToString("").trim(), DEVICE_CODE_LENGTH).ifEmpty { "android" }
}

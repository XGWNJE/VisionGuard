package com.xgwnje.visionguard.account

class RememberedLogin(val username: String, val password: String) {
    init {
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{2,63}").matches(username)) { "账号须为 3–64 个字母、数字、点、横线或下划线" }
        require(password.isNotEmpty() && password.length <= 256) { "密码须为 1–256 个字符" }
    }
    override fun toString() = "RememberedLogin [redacted]"
}

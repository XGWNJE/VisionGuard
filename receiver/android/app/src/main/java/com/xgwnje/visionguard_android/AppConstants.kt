package com.xgwnje.visionguard_android

// ┌─────────────────────────────────────────────────────────┐
// │ AppConstants.kt                                         │
// │ 角色：全局硬编码常量                                      │
// │ 修改方法：直接改此文件后重新编译 APK                       │
// └─────────────────────────────────────────────────────────┘

object AppConstants {
    private const val DEFAULT_SERVER_URL = "https://visionguard.xgwnje.cn"
    /** 应用版本号（与根目录 VERSION 文件保持一致） */
    const val VERSION = "4.5.0"

    /** 服务器地址（不含末尾斜杠） */
    val SERVER_URL: String = BuildConfig.SERVER_URL.ifBlank { DEFAULT_SERVER_URL }

    /** 与 Server 进程级隔离的协议通道。 */
    val CHANNEL: String = BuildConfig.CHANNEL

    /** API key injected by Gradle BuildConfig. */
    val API_KEY: String = BuildConfig.API_KEY
}

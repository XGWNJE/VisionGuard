package com.xgwnje.visionguard

// ┌─────────────────────────────────────────────────────────┐
// │ AppConstants.kt                                         │
// │ 角色：全局硬编码常量                                      │
// │ 修改方法：直接改此文件后重新编译 APK                       │
// └─────────────────────────────────────────────────────────┘

object AppConstants {
    /** 应用版本号（与根目录 VERSION 文件保持一致） */
    const val VERSION = "4.3.1"

    /** 服务器地址（不含末尾斜杠） */
    const val SERVER_URL = "https://visionguard.xgwnje.cn"

    /** API key injected by Gradle BuildConfig. */
    val API_KEY: String = BuildConfig.API_KEY
}

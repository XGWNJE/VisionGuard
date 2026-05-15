package com.xgwnje.visionguard_android

// ┌─────────────────────────────────────────────────────────┐
// │ AppConstants.kt                                         │
// │ 角色：全局硬编码常量                                      │
// │ 修改方法：直接改此文件后重新编译 APK                       │
// └─────────────────────────────────────────────────────────┘

object AppConstants {
    /** 应用版本号（与根目录 VERSION 文件保持一致） */
    const val VERSION = "4.0.1"

    /** 服务器地址（不含末尾斜杠） */
    const val SERVER_URL = "https://xgwnje.cn"

    /** API 密钥（与服务器 .env 中 API_KEY 一致） */
    const val API_KEY = "XG-VisionGuard-2024"
}

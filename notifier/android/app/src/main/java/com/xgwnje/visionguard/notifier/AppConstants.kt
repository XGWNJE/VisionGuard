package com.xgwnje.visionguard.notifier

/**
 * 本端固定标识与运行版本。
 *
 * `VERSION` 必须保持「运行期读取」的写法：`BuildConfig` 的 Java 常量会被 Kotlin 在编译期内联成字面量，
 * 一旦增量构建复用了旧 class，打包出的 APK 就会一直声明旧协议版本（2026-10-14 实测：0.7.3 的包声明 0.7.1，
 * 被线上协议版本闸门静默切断）。改成 `const val` 会重新引入这个坑。
 */
object AppConstants {
    /** 运行版本，只有根 `VERSION` 一处权威来源。 */
    val VERSION: String = BuildConfig.VERSION_NAME
}

package com.xgwnje.visionguard.notifier

/**
 * 本端固定标识与运行版本。
 *
 * `VERSION` 必须是「由 `scripts/sync-version.js` 收管的 Kotlin 字面量」：
 * 用 `BuildConfig.VERSION_NAME` 之类 Java 常量派生会被 Kotlin 在编译期内联成字面量，
 * 增量构建复用旧 class 时协议声明就冻结在旧值（2026-10-14 通知节点 0.7.3 与 0.7.4 两次实测）。
 * Kotlin 源码里的常量变化会被增量编译器跟踪并触发重编译，Java 生成的常量不会。
 */
object AppConstants {
    /** 运行版本，只有根 `VERSION` 一处权威来源。 */
    const val VERSION = "0.7.8"
}
package com.xgwnje.visionguard.inference

/** 本端固定标识：登录组件、界面标题与构建版本。 */
object AppConstants {
    /** 登录组件标识，服务端据此签发 `detector / visual / android` 身份。 */
    const val LOGIN_COMPONENT = "android-inference"

    /** 应用标题与桌面标签，遵循命名规范。 */
    const val APP_TITLE = "视觉推理节点"

    /**
     * 运行版本，由 `scripts/sync-version.js` 随根 `VERSION` 改写。
     *
     * 必须保持「sync-version 收管的 Kotlin 字面量」：不能用 `BuildConfig.VERSION_NAME` 之类的
     * Java 常量派生（会被 Kotlin 编译期内联，增量构建复用旧 class 时冻结在旧值，
     * 2026-10-14 通知节点两次实测）。
     */
    const val VERSION = "0.7.5"

    /** 发行内置正式服务地址。 */
    val SERVER_URL: String = BuildConfig.SERVER_URL
}

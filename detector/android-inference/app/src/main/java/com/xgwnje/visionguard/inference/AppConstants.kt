package com.xgwnje.visionguard.inference

/** 本端固定标识：登录组件、界面标题与构建版本。 */
object AppConstants {
    /** 登录组件标识，服务端据此签发 `detector / visual / android` 身份。 */
    const val LOGIN_COMPONENT = "android-inference"

    /** 应用标题与桌面标签，遵循命名规范。 */
    const val APP_TITLE = "视觉推理节点"

    /** 运行版本，只有根 `VERSION` 一处权威来源。 */
    val VERSION: String = BuildConfig.VERSION_NAME

    /** 发行内置正式服务地址。 */
    val SERVER_URL: String = BuildConfig.SERVER_URL
}

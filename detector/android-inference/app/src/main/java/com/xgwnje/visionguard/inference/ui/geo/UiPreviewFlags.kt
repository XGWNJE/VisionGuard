package com.xgwnje.visionguard.inference.ui.geo

import android.content.Context
import android.content.SharedPreferences
import com.xgwnje.visionguard.inference.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地预览用的界面开关。
 *
 * 只有可调试的预览构建才提供「UI 验收模拟数据」：用 [MockWorkspaceData] 的一份快照替换真实
 * 运行状态，方便在无账号、无相机、无推流时检查常规、空态、异常与边界呈现。
 * 正式构建里 [available] 为 false，界面不显示入口，也不会启用模拟数据。
 */
object UiPreviewFlags {

    private const val PREFS = "ui_preview_flags"
    private const val KEY_MOCK = "mock_data"

    private var prefs: SharedPreferences? = null
    private val mutableMock = MutableStateFlow(false)
    val mockData = mutableMock.asStateFlow()

    val available: Boolean get() = BuildConfig.DEBUG

    fun attach(context: Context) {
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        mutableMock.value = available && store.getBoolean(KEY_MOCK, false)
    }

    fun setMockData(enabled: Boolean) {
        if (!available) return
        mutableMock.value = enabled
        prefs?.edit()?.putBoolean(KEY_MOCK, enabled)?.apply()
    }
}

package com.xgwnje.visionguard.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 本地预览构建的内置登录。
 *
 * 预览包（`.uipreview`）是给 owner 做界面与链路验收用的，每次都手填服务地址与账号密码纯属浪费：
 * 构建时把本机隔离服务的地址与预览账号注入资源（`preview_endpoint` / `preview_username` /
 * `preview_password`），启动即静默登录，直接进主界面。
 *
 * 三条边界：
 * - 凭据只存在于预览构建：正式包的三个资源是空串，[available] 为 false，行为与以前完全一致；
 * - 需要调登录界面时，在登录页的「隔离测试设置」里打开 [debugLoginScreen]，之后的启动停在登录页；
 * - 静默登录不吞错：失败时返回可读原因，由调用方显示，用户仍能手填账号密码登录。
 */
object PreviewSignIn {

    private const val PREFS = "preview_sign_in"
    private const val KEY_DEBUG_LOGIN = "debug_login_screen"

    private var endpoint: String? = null
    private var username: String? = null
    private var password: String? = null
    private var prefs: SharedPreferences? = null

    /** 本构建是否内置了预览凭据；正式包恒为 false。 */
    val available: Boolean get() = endpoint != null && username != null && password != null

    /** 是否停在登录界面调试登录流程；只有 [available] 时有意义。 */
    var debugLoginScreen: Boolean
        get() = prefs?.getBoolean(KEY_DEBUG_LOGIN, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_DEBUG_LOGIN, value)?.apply()
        }

    /** 各端启动时用资源里的三个值登记；任一为空即视为没有内置登录。 */
    fun configure(context: Context, endpoint: String?, username: String?, password: String?) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.endpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() }
        this.username = username?.trim()?.takeIf { it.isNotEmpty() }
        // 密码是构建注入的 base64，不做 trim：首尾空白也可能是有效字符。
        this.password = password?.takeIf { it.isNotEmpty() }
        // 只记「有没有」，不记凭据本身：预览现场排查登录为什么没发生。
        if (available) Log.i(TAG, "内置预览登录已就绪（$endpoint）") else Log.i(TAG, "没有内置预览登录，按登录页处理")
    }

    /**
     * 启动时的静默登录；返回 null 表示无需登录或已成功，否则是可读失败原因。
     *
     * 已有会话（记住登录）不再重复登录：那会平白多一次网络往返，也会把设备身份刷新一遍。
     */
    suspend fun signIn(store: AccountStore, component: String): String? {
        if (debugLoginScreen) {
            // 只跳过静默登录还不够：应用启动会恢复已保存的会话，照样进主界面。停在登录页
            // 就得把本机会话一起清掉（本地清除，不依赖网络）。
            store.clear()
            Log.i(TAG, "调试登录流程已开启，已清除本机会话，停在登录页")
            return null
        }
        if (store.session.value != null) return null
        val endpoint = this.endpoint ?: return null
        val username = this.username ?: return null
        val password = this.password ?: return null
        if (debugLoginScreen) {
            Log.i(TAG, "调试登录流程已开启，停在登录页")
            return null
        }
        Log.i(TAG, "开始内置预览登录：$component @ $endpoint")
        return runCatching {
            store.login(endpoint, username, password, component, rememberCredentials = true)
            Log.i(TAG, "内置预览登录成功：$component")
            null
        }.getOrElse {
            Log.w(TAG, "内置预览登录失败：${it.message}")
            it.message?.take(160) ?: "预览自动登录失败，请手动登录"
        }
    }

    private const val TAG = "PreviewSignIn"
}

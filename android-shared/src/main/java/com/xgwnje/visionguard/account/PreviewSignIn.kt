package com.xgwnje.visionguard.account

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 本地服务版构建的内置登录。
 *
 * 预览包（`.uipreview`）是给 owner 做界面与链路验收用的，每次都手填服务地址与账号密码纯属浪费：
 * 构建时把本地服务的通道、地址与预览账号注入资源（`preview_channel` / `preview_endpoint` /
 * `preview_username` / `preview_password`），启动先按通道在局域网里找服务、找到就用它，
 * 找不到才回落到内置地址，随后静默登录，直接进主界面。
 *
 * 四条边界：
 * - 凭据只存在于预览构建：正式包的四个资源是空串，[available] 为 false，行为与以前完全一致；
 * - 需要调登录界面时，在登录页的「本地服务设置」里打开 [debugLoginScreen]，之后的启动停在登录页；
 * - 静默登录不吞错：失败时返回可读原因，由调用方显示，用户仍能手填账号密码登录；
 * - 本地服务的端口每次都会变，所以发现到的地址与本机会话里存的地址不一致时先清会话再登，
 *   否则会拿着一个已经没人监听的地址反复失败。
 */
object PreviewSignIn {

    private const val PREFS = "preview_sign_in"
    private const val KEY_DEBUG_LOGIN = "debug_login_screen"

    private var endpoint: String? = null
    private var username: String? = null
    private var password: String? = null
    private var channel: String? = null
    private var prefs: SharedPreferences? = null

    /**
     * 本构建实际连上的本地服务地址；`ClientUpdater` 用它替代 GitHub 查发行与下载组件包。
     * 正式包没有内置凭据，这里恒为 null，更新仍走 GitHub 稳定发行。
     */
    @Volatile
    var localUpdateBase: String? = null
        private set

    /** 本构建是否内置了预览凭据；正式包恒为 false。 */
    val available: Boolean get() = endpoint != null && username != null && password != null

    /** 是否停在登录界面调试登录流程；只有 [available] 时有意义。 */
    var debugLoginScreen: Boolean
        get() = prefs?.getBoolean(KEY_DEBUG_LOGIN, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_DEBUG_LOGIN, value)?.apply()
        }

    /** 各端启动时用资源里的四个值登记；凭据任一为空即视为没有内置登录。 */
    fun configure(context: Context, endpoint: String?, username: String?, password: String?, channel: String? = null) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.endpoint = endpoint?.trim()?.takeIf { it.isNotEmpty() }
        this.username = username?.trim()?.takeIf { it.isNotEmpty() }
        // 密码是构建注入的 base64，不做 trim：首尾空白也可能是有效字符。
        this.password = password?.takeIf { it.isNotEmpty() }
        this.channel = channel?.trim()?.takeIf { it.isNotEmpty() }
        // 只记「有没有」，不记凭据本身：预览现场排查登录为什么没发生。
        if (available) Log.i(TAG, "内置本地服务登录已就绪（$endpoint / channel=$channel）") else Log.i(TAG, "没有内置登录，按登录页处理")
    }

    /**
     * 启动时的登录；返回 null 表示无需登录或已成功，否则是可读失败原因。
     *
     * 已有会话且地址没变时不再重复登录：那会平白多一次网络往返，也会把设备身份刷新一遍。
     */
    suspend fun signIn(store: AccountStore, component: String): String? {
        if (debugLoginScreen) {
            // 只跳过静默登录还不够：应用启动会恢复已保存的会话，照样进主界面。停在登录页
            // 就得把本机会话一起清掉（本地清除，不依赖网络）。
            store.clear()
            Log.i(TAG, "调试登录流程已开启，已清除本机会话，停在登录页")
            return null
        }
        if (!available) return null
        val baked = endpoint ?: return null
        val account = username ?: return null
        val secret = password ?: return null
        val discovered = channel?.let { runCatching { LocalServiceDiscovery.discover(it) }.getOrNull() }
        if (discovered == null) Log.i(TAG, "未发现本地服务，使用内置地址：$baked")
        val target = discovered ?: baked
        val current = store.session.value
        if (current != null) {
            if (discovered == null || current.endpoint == discovered) {
                localUpdateBase = current.endpoint
                return null
            }
            Log.i(TAG, "本地服务地址已变化（${current.endpoint} -> $discovered），清除本机会话后重新登录")
            store.clear()
        }
        val failure = attempt(store, target, account, secret, component)
        if (failure == null) {
            localUpdateBase = target
            return null
        }
        if (target != baked) {
            Log.w(TAG, "本地服务登录失败（$failure），改用内置地址重试")
            val retry = attempt(store, baked, account, secret, component)
            if (retry == null) {
                localUpdateBase = baked
                return null
            }
            return retry
        }
        return failure
    }

    private suspend fun attempt(store: AccountStore, target: String, account: String, secret: String, component: String): String? {
        Log.i(TAG, "开始内置登录：$component @ $target")
        return runCatching {
            store.login(target, account, secret, component, rememberCredentials = true)
            Log.i(TAG, "内置登录成功：$component @ $target")
            null
        }.getOrElse {
            Log.w(TAG, "内置登录失败：${it.message}")
            it.message?.take(160) ?: "本地服务自动登录失败，请手动登录"
        }
    }

    private const val TAG = "PreviewSignIn"
}

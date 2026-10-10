package com.xgwnje.visionguard.inference.runtime

import android.content.Context
import android.content.SharedPreferences
import com.xgwnje.visionguard.inference.model.AccelerationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 加速方案选择与自动探测结果。
 *
 * 手动模式严格按所选方案建会话，失败直接把原因报给界面；自动模式复用上次探测成功的具体方案
 * （按设备与模型记在本机），没有记录时再由 [AccelerationMode.AUTO_ORDER] 顺序探测。
 * 方案是冷设置：改动在下一次启动来源时生效。
 */
object AcceleratorSettings {

    private const val PREFS = "accelerator"
    private const val KEY_MODE = "mode"
    private const val KEY_RESOLVED = "auto_resolved"

    private var prefs: SharedPreferences? = null
    private var resolvedId: String? = null

    private val mutableMode = MutableStateFlow(AccelerationMode.AUTO)
    val mode = mutableMode.asStateFlow()

    private val mutableResolved = MutableStateFlow<AccelerationMode?>(null)

    /** 自动模式上次胜出的具体方案；没有记录时为 null。 */
    val autoResolved = mutableResolved.asStateFlow()

    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        android.util.Log.i("VisionGuardAccel", "attach raw=${store.getString(KEY_MODE, null)} resolved=${store.getString(KEY_RESOLVED, null)}")
        prefs = store
        mutableMode.value = AccelerationMode.fromId(store.getString(KEY_MODE, null)) ?: AccelerationMode.AUTO
        resolvedId = store.getString(KEY_RESOLVED, null)
        mutableResolved.value = AccelerationMode.fromId(resolvedId)
    }

    fun setMode(mode: AccelerationMode) {
        mutableMode.value = mode
        prefs?.edit()?.putString(KEY_MODE, mode.id)?.apply()
    }

    /** 建会话用的方案：手动模式用所选；自动模式用上次胜出的，没有就用自动探测。 */
    fun plan(): AccelerationMode {
        val selected = mutableMode.value
        if (selected != AccelerationMode.AUTO) {
            android.util.Log.i("VisionGuardAccel", "plan manual -> ${selected.id}")
            return selected
        }
        val planned = mutableResolved.value ?: AccelerationMode.AUTO
        android.util.Log.i("VisionGuardAccel", "plan auto -> ${planned.id}")
        return planned
    }

    /** 会话建成后记录实际生效的方案，自动模式下次直接复用。 */
    fun rememberResolved(effective: AccelerationMode) {
        if (mutableMode.value != AccelerationMode.AUTO) return
        if (resolvedId == effective.id) return
        resolvedId = effective.id
        mutableResolved.value = effective
        prefs?.edit()?.putString(KEY_RESOLVED, effective.id)?.apply()
    }

    private fun modelKeyOf(key: String) = "resolved_$key"

    /** 某个模型上次自动择优的结果；不同模型的最优档可能不同，因此按模型分开记。 */
    fun resolvedForModel(modelKey: String): AccelerationMode? =
        AccelerationMode.fromId(prefs?.getString(modelKeyOf(modelKey), null))

    /** 记录某个模型的择优结果与实测耗时（毫秒），供设置页显示。 */
    fun rememberForModel(modelKey: String, mode: AccelerationMode, averageMs: Double) {
        prefs?.edit()?.putString(modelKeyOf(modelKey), "${mode.id}|${averageMs.toInt()}")?.apply()
    }

    /** 某个模型择优时测到的耗时（毫秒），没有记录返回 null。 */
    fun measuredMsForModel(modelKey: String): Int? =
        prefs?.getString(modelKeyOf(modelKey), null)?.split("|")?.getOrNull(1)?.toIntOrNull()

    /** 清掉自动探测记录，下次启动重新逐档探测。 */
    fun forgetResolved() {
        resolvedId = null
        mutableResolved.value = null
        val store = prefs ?: return
        val editor = store.edit().remove(KEY_RESOLVED)
        store.all.keys.filter { it.startsWith("resolved_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}

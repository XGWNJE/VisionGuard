package com.xgwnje.visionguard.inference.ui.geo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * 抽屉内的焦点环：按组合顺序登记可聚焦元素，Tab / Shift+Tab 在环内前后环绕。
 *
 * 只在抽屉打开时生效（[LocalGeoFocusRing] 为 null 时 [geoFocusable] 不改动元素），
 * 因此主界面的键盘遍历不受影响。
 */
class GeoFocusRing {
    private val requesters = mutableListOf<FocusRequester>()
    private var current: FocusRequester? = null

    fun register(requester: FocusRequester) {
        if (requesters.none { it === requester }) requesters.add(requester)
    }

    fun unregister(requester: FocusRequester) {
        requesters.removeAll { it === requester }
        if (current === requester) current = null
    }

    fun markFocused(requester: FocusRequester) {
        current = requester
    }

    /** 前进 / 后退一格：跳过暂时无法取焦点的元素；环为空或全部失败返回 false。 */
    fun move(forward: Boolean): Boolean {
        if (requesters.isEmpty()) return false
        var index = requesters.indexOfFirst { it === current }
        if (index < 0) index = if (forward) requesters.lastIndex else 0
        repeat(requesters.size) {
            index = if (forward) (index + 1) % requesters.size else (index - 1 + requesters.size) % requesters.size
            val target = requesters[index]
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return true
        }
        return false
    }
}

val LocalGeoFocusRing = compositionLocalOf<GeoFocusRing?> { null }

/** 把元素加入当前抽屉的焦点环；不在抽屉里时原样返回。 */
fun Modifier.geoFocusable(): Modifier = composed {
    val ring = LocalGeoFocusRing.current ?: return@composed this
    val requester = remember { FocusRequester() }
    DisposableEffect(ring) {
        ring.register(requester)
        onDispose { ring.unregister(requester) }
    }
    this
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) ring.markFocused(requester) }
}

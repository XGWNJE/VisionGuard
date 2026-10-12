package com.xgwnje.visionguard.inference.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型下载地址契约。
 *
 * 模型必须从**当前登录会话所在的服务**下载。曾经这里用的是构建时写死的 `BuildConfig.SERVER_URL`，
 * 于是本地服务版（`.uipreview`）明明登录在局域网服务上，模型却跑到正式服务端去下——调试时表现为
 * “局域网却慢得像公网”。这条用例锁住“地址在下载时才取、且跟随会话变化”。
 */
class ModelDownloadEndpointTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `模型下载跟随当前会话地址而不是构建时地址`() = runBlocking {
        val dir = temporary.newFolder("models")
        var endpoint = "http://127.0.0.1:1"
        val requested = mutableListOf<String>()
        val store = ModelStore(dir, { endpoint }, { requested += it })

        // 端口 1 必然连不上：这里要的是“用哪个地址”，失败是预期结果。
        assertNotNull(store.download("yolo26m_320_int8"))
        assertTrue(requested.single(), requested.single().endsWith("← http://127.0.0.1:1"))

        requested.clear()
        endpoint = "http://192.168.31.58:4399/"
        assertNotNull(store.download("yolo26m_320_int8"))
        // 结尾斜杠不能留在基准地址里，换会话后必须立刻改用新地址。
        assertTrue(requested.single(), requested.single().endsWith("← http://192.168.31.58:4399"))
    }
}

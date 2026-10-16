package com.xgwnje.visionguard.notifier

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xgwnje.visionguard.account.AccountSession
import com.xgwnje.visionguard.account.AccountStore
import com.xgwnje.visionguard.account.PreviewSignIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only the maintained preview package and its isolated local-service account. */
@RunWith(AndroidJUnit4::class)
class PreviewUpdateSourceTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun missingBeaconRecoversStaleSourceAndKeepsReachableSession() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires the local-service preview", context.packageName.endsWith(".uipreview"))
        val endpoint = context.getString(R.string.preview_endpoint)
        val username = context.getString(R.string.preview_username)
        val password = context.getString(R.string.preview_password)
        val channel = context.getString(R.string.preview_channel)
        assumeTrue("Requires injected local-service credentials", endpoint.startsWith("http://") && username.isNotBlank() && password.isNotBlank())
        val store = AccountStore.get(context)
        val debugLogin = PreviewSignIn.debugLoginScreen
        PreviewSignIn.configure(context, endpoint, username, password, null) // No discovery: exercise the baked-address fallback.
        PreviewSignIn.debugLoginScreen = false
        assertNull(PreviewSignIn.signIn(store, "android-notifier"))
        val original = requireNotNull(store.session.value)
        val sessions = AccountStore::class.java.getDeclaredField("mutableSession").apply { isAccessible = true }
            .get(store) as MutableStateFlow<AccountSession?>
        val staleEndpoint = "http://127.0.0.1:1"
        try {
            sessions.value = original.copy(endpoint = staleEndpoint)
            assertNull(PreviewSignIn.signIn(store, "android-notifier"))
            assertEquals(endpoint, store.session.value?.endpoint)
            assertEquals(endpoint, PreviewSignIn.localUpdateBase)
            // A stale baked address must not replace a session that still reaches the local service.
            PreviewSignIn.configure(context, staleEndpoint, username, password, null)
            assertNull(PreviewSignIn.signIn(store, "android-notifier"))
            assertEquals(endpoint, store.session.value?.endpoint)
            assertEquals(endpoint, PreviewSignIn.localUpdateBase)
        } finally {
            if (sessions.value?.endpoint == staleEndpoint) sessions.value = original
            PreviewSignIn.configure(context, endpoint, username, password, channel)
            PreviewSignIn.debugLoginScreen = debugLogin
        }
    }
}

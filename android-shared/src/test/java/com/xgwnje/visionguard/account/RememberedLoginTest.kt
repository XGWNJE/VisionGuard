package com.xgwnje.visionguard.account

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RememberedLoginTest {
    @Test fun acceptsExactBoundariesAndRedactsItsStringRepresentation() {
        val value = RememberedLogin("u".repeat(64), "p".repeat(256))
        assertEquals(64, value.username.length); assertEquals(256, value.password.length)
        assertFalse(value.toString().contains(value.password))
        listOf("", "ab", "x".repeat(65), "invalid user").forEach { username ->
            assertThrows(IllegalArgumentException::class.java) { RememberedLogin(username, "pass") }
        }
        listOf("", "p".repeat(257)).forEach { password ->
            assertThrows(IllegalArgumentException::class.java) { RememberedLogin("user", password) }
        }
    }
    @Test fun optingOutClearsOnlyTheSelectedServiceAndComponent() {
        val base = "http://127.0.0.1:3173"
        val values = mutableMapOf("remembered:$base:android-camera" to "opaque-camera-cipher",
            "remembered:$base:android-notifier" to "opaque-notifier-cipher",
            "remembered:http://127.0.0.1:3174:android-camera" to "opaque-other-service-cipher")
        val removed = mutableListOf<String>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { self, method, args ->
            when (method.name) {
                "remove" -> { removed.add(args[0] as String); self }
                "commit" -> { removed.forEach { values.remove(it) }; true }
                else -> error("Unexpected editor operation")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when(method.name) { "getString" -> values[args[0]] ?: args[1]; "edit" -> editor; else -> error("Unexpected preference operation") }
        } as SharedPreferences
        val ctor = AccountStore::class.java.getDeclaredConstructor(SharedPreferences::class.java, java.lang.Boolean.TYPE, Function0::class.java).apply { isAccessible = true }
        val store = ctor.newInstance(prefs, true, { "unused-stable-id" })
        store.rememberLogin(base + "/", "android-camera", null)
        assertFalse(values.containsKey("remembered:$base:android-camera"))
        assertEquals(2, values.size)
        assertEquals("opaque-notifier-cipher", values["remembered:$base:android-notifier"])
        assertEquals("opaque-other-service-cipher", values["remembered:http://127.0.0.1:3174:android-camera"])
    }
}

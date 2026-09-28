@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.lumicode.editor.platform

actual object LocalPrefs {
    private val store = FileKvStore {
        val xdg = posixEnv("XDG_CONFIG_HOME")
        val base = when {
            !xdg.isNullOrBlank() -> xdg
            else -> {
                val home = posixEnv("HOME") ?: return@FileKvStore null
                "$home/.config"
            }
        }
        "$base/lumicode/settings"
    }

    actual fun get(key: String): String? = store.get(key)

    actual fun set(key: String, value: String) {
        store.set(key, value)
    }
}

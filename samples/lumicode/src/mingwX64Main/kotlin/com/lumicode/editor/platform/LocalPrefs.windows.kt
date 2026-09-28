@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.lumicode.editor.platform

actual object LocalPrefs {
    private val store = FileKvStore {
        val appdata = posixEnv("APPDATA")
        if (!appdata.isNullOrBlank()) return@FileKvStore "$appdata/lumicode/settings"
        val home = posixEnv("USERPROFILE") ?: posixEnv("HOME") ?: return@FileKvStore null
        "$home/AppData/Roaming/lumicode/settings"
    }

    actual fun get(key: String): String? = store.get(key)

    actual fun set(key: String, value: String) {
        store.set(key, value)
    }
}

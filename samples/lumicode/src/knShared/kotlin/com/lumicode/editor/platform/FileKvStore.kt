@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.lumicode.editor.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.mkdir
import platform.posix.rewind
import platform.posix.SEEK_END

/**
 * Tiny key=value file store for ComposeKN (no JVM Preferences).
 * Shared by linuxX64 + mingwX64 via [knShared] srcDir.
 */
internal class FileKvStore(private val pathProvider: () -> String?) {
    private val cache = linkedMapOf<String, String>()
    private var loaded = false

    fun get(key: String): String? {
        ensureLoaded()
        return cache[key]
    }

    fun set(key: String, value: String) {
        ensureLoaded()
        cache[key] = value
        persist()
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val path = pathProvider() ?: return
        val text = readText(path) ?: return
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#')) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            cache[line.substring(0, eq)] = line.substring(eq + 1)
        }
    }

    private fun persist() {
        val path = pathProvider() ?: return
        val dir = path.substringBeforeLast('/', missingDelimiterValue = "")
            .ifEmpty { path.substringBeforeLast('\\', missingDelimiterValue = "") }
        if (dir.isNotEmpty()) mkdirp(dir)
        val body = buildString {
            appendLine("# lumicode settings")
            for ((k, v) in cache) {
                append(k)
                append('=')
                appendLine(v)
            }
        }
        writeText(path, body)
    }
}

private fun mkdirp(path: String) {
    if (path.isEmpty()) return
    val parts = path.split('/', '\\').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return
    val absolute = path.startsWith('/') || (path.length >= 2 && path[1] == ':')
    var cur = when {
        absolute && path.startsWith('/') -> ""
        absolute -> parts.first() // e.g. C:
        else -> "."
    }
    val start = if (absolute && !path.startsWith('/')) 1 else 0
    for (i in start until parts.size) {
        cur = if (cur.isEmpty() || cur == ".") {
            if (path.startsWith('/')) "/${parts[i]}" else parts[i]
        } else {
            "$cur/${parts[i]}"
        }
        mkdir(cur, 493u) // 0755; ignore EEXIST
    }
}

private fun readText(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < 0L || size > 1_000_000L) return null
        if (size == 0L) return ""
        rewind(file)
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), file)
        }
        if (read.toLong() != size) return null
        return bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

private fun writeText(path: String, text: String) {
    val file = fopen(path, "wb") ?: return
    try {
        val bytes = text.encodeToByteArray()
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
        }
    } finally {
        fclose(file)
    }
}

internal fun posixEnv(name: String): String? = memScoped {
    platform.posix.getenv(name)?.toKString()
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.composekn.resources

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv as posixGetenv
import platform.posix.readlink
import platform.posix.rewind
import platform.posix.SEEK_END

internal actual fun getenv(name: String): String? =
    posixGetenv(name)?.toKString()

internal actual fun executableDirectory(): String? = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095u)
    if (n <= 0L) return null
    buf[n.toInt()] = 0
    val path = buf.toKString()
    val slash = path.lastIndexOf('/')
    if (slash <= 0) return null
    path.substring(0, slash)
}

internal actual fun readFileBytes(path: String): ByteArray? {
    if (access(path, F_OK) != 0) return null
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size <= 0L || size > Int.MAX_VALUE) return null
        rewind(file)
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), file)
        }
        if (read.toLong() != size.toLong()) return null
        return bytes
    } finally {
        fclose(file)
    }
}

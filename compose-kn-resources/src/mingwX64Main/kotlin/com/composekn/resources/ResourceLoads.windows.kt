@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.composekn.resources

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv as posixGetenv
import platform.posix.rewind
import platform.posix.SEEK_END
import platform.windows.GetModuleFileNameW
import platform.windows.MAX_PATH
import platform.windows.WCHARVar

internal actual fun getenv(name: String): String? =
    posixGetenv(name)?.toKString()

internal actual fun executableDirectory(): String? = memScoped {
    val buf = allocArray<WCHARVar>(MAX_PATH)
    val n = GetModuleFileNameW(null, buf, MAX_PATH.toUInt())
    if (n == 0u) return null
    val path = buf.toKString()
    val slash = maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'))
    if (slash <= 0) return null
    path.substring(0, slash)
}

internal actual fun readFileBytes(path: String): ByteArray? {
    // fopen on mingw accepts UTF-8 paths in our toolchain for ASCII/short paths;
    // font files we ship use ASCII names under composeResources/font/.
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

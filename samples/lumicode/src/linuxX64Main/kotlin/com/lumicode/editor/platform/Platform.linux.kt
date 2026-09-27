package com.lumicode.editor.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.localtime
import platform.posix.strftime
import platform.posix.time
import platform.posix.time_tVar

actual fun platformLabel(): String = "Linux"

actual fun platformTag(): String = "LINUX · KN"

@OptIn(ExperimentalForeignApi::class)
actual fun clockLabel(): String = memScoped {
    val t = alloc<time_tVar>()
    time(t.ptr)
    val tm = localtime(t.ptr) ?: return@memScoped "--:--:--"
    val buf = allocArray<ByteVar>(16)
    strftime(buf, 16u, "%H:%M:%S", tm)
    buf.toKString()
}

package org.jetbrains.skiko

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.native.concurrent.ThreadLocal

private val threadCounter = atomic(0L)

@ThreadLocal
private var currentThreadId: Long = threadCounter.addAndGet(1)

private var linuxMainThreadId: Long = -1L

/** Mark the current thread as the Wayland/UI main thread. Call once before any UI coroutines start. */
fun initLinuxMainThread() {
    if (linuxMainThreadId < 0L) {
        linuxMainThreadId = currentThreadId
    }
}

internal fun isLinuxMainThread(): Boolean =
    linuxMainThreadId >= 0L && currentThreadId == linuxMainThreadId

object SkikoDispatchers {
    val Main: CoroutineDispatcher = MainUIDispatcher
    val IO: CoroutineDispatcher = Dispatchers.Default
}

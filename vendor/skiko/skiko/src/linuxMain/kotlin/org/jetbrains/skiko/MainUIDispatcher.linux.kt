package org.jetbrains.skiko

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch

/**
 * UI-thread dispatcher for Kotlin/Native Linux (Wayland event loop).
 *
 * Tasks posted from background threads are queued and must be drained via [flushMainUIDispatcher]
 * on the main thread (inside the Wayland poll loop).
 */
val MainUIDispatcher: CoroutineDispatcher
    get() = LinuxMainDispatcher

@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private object LinuxMainDispatcher : MainCoroutineDispatcher(), Delay {
    private val pending = atomic<List<Runnable>>(emptyList())
    @Volatile
    private var flushing = false

    override val immediate: MainCoroutineDispatcher
        get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isLinuxMainThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (isLinuxMainThread()) {
            block.run()
            return
        }
        enqueue(block)
    }

    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            kotlinx.coroutines.delay(timeMillis)
            dispatch(continuation.context) {
                with(continuation) { resumeUndispatched(Unit) }
            }
        }
        continuation.invokeOnCancellation { job.cancel() }
    }

    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val job = CoroutineScope(Dispatchers.Default).launch {
            kotlinx.coroutines.delay(timeMillis)
            dispatch(context, block)
        }
        return object : DisposableHandle {
            override fun dispose() {
                job.cancel()
            }
        }
    }

    private fun enqueue(block: Runnable) {
        while (true) {
            val old = pending.value
            if (pending.compareAndSet(old, old + block)) return
        }
    }

    fun flush() {
        if (!isLinuxMainThread()) return
        if (flushing) return
        flushing = true
        try {
            while (true) {
                val tasks = pending.getAndSet(emptyList())
                if (tasks.isEmpty()) return
                tasks.forEach(Runnable::run)
            }
        } finally {
            flushing = false
        }
    }
}

/** Drain UI tasks queued on [MainUIDispatcher]. Call from the Wayland poll loop. */
fun flushMainUIDispatcher() {
    LinuxMainDispatcher.flush()
}

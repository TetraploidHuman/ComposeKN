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

    /**
     * 「有人往队列里放了活」时调用（只在跨线程投递时触发，见 [enqueue]）。
     * [LinuxApplicationHost] 接到 eventfd wake，避免空闲 usleep 拖住 Main 任务。
     */
    var wakeUpHandler: (() -> Unit)? = null

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
            if (pending.compareAndSet(old, old + block)) break
        }
        // 跨线程投递：共享泵可能正睡在 poll(eventfd)/usleep 上，必须叫醒。
        wakeUpHandler?.invoke()
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

/**
 * 注册「跨线程往 UI 队列投递任务」时的唤醒回调（null = 取消）。
 *
 * 对齐 Win32 [setMainUIDispatcherWakeUpHandler]；Linux 侧接到 eventfd / 短 sleep 泵。
 */
fun setMainUIDispatcherWakeUpHandler(handler: (() -> Unit)?) {
    LinuxMainDispatcher.wakeUpHandler = handler
}

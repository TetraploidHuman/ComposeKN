package org.jetbrains.skiko

import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.Runnable

/**
 * UI-thread dispatcher for Kotlin/Native Windows (Win32 message pump loop).
 * Tasks posted from background threads are queued and must be drained via
 * [flushMainUIDispatcher] on the main thread (inside the Win32 message loop).
 */
val MainUIDispatcher: CoroutineDispatcher
    get() = WindowsMainDispatcher

@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private object WindowsMainDispatcher : MainCoroutineDispatcher(), Delay {
    private val pending = atomic<List<Runnable>>(emptyList())
    @Volatile
    private var flushing = false

    /**
     * 「有人往队列里放了活」时调用（只在跨线程投递时触发，见 [enqueue]）。
     * 窗口循环把它接到 `composekn_win32_wake()` 上：把阻塞的消息泵叫醒。
     */
    var wakeUpHandler: (() -> Unit)? = null

    override val immediate: MainCoroutineDispatcher
        get() = this

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !isWindowsMainThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (isWindowsMainThread()) {
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
        // 跨线程投递：消息循环可能正阻塞在 waitMessage() 上睡着，必须把它叫醒，
        // 否则「后台线程干活 -> 主线程刷新 UI」要等到下一条无关消息才会发生。
        wakeUpHandler?.invoke()
    }

    fun flush() {
        if (!isWindowsMainThread()) return
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

/** Drain UI tasks queued on [MainUIDispatcher]. Call from the Win32 message pump loop. */
fun flushMainUIDispatcher() {
    WindowsMainDispatcher.flush()
}

/**
 * 注册「跨线程往 UI 队列投递任务」时的唤醒回调（null = 取消）。
 *
 * 窗口循环在进入 `waitMessage()` 阻塞前接上 `Win32Window::wake`，
 * 这样后台线程完成工作后主线程会立刻醒来刷新 UI，而不用等下一次输入。
 */
fun setMainUIDispatcherWakeUpHandler(handler: (() -> Unit)?) {
    WindowsMainDispatcher.wakeUpHandler = handler
}

private val threadCounter = atomic(0L)

@kotlin.native.concurrent.ThreadLocal
private var currentThreadId: Long = threadCounter.addAndGet(1)

private var windowsMainThreadId: Long = -1L

/** Mark the current thread as the Win32/UI main thread. Call once before any UI coroutines start. */
fun initWindowsMainThread() {
    if (windowsMainThreadId < 0L) {
        windowsMainThreadId = currentThreadId
    }
}

internal fun isWindowsMainThread(): Boolean =
    windowsMainThreadId >= 0L && currentThreadId == windowsMainThreadId

object SkikoDispatchers {
    val Main: CoroutineDispatcher = MainUIDispatcher
    val IO: CoroutineDispatcher = Dispatchers.Default
}

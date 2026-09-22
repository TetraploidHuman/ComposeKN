package com.composekn.linux

import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initLinuxMainThread
import platform.posix.usleep

/**
 * 进程级多窗口宿主：一条循环轮询所有 [LinuxComposeWindow] 的 Wayland display。
 *
 * Desktop 语义对齐：
 * - 系统关窗 → 只回调 onCloseRequest（DO_NOTHING），不 destroy surface
 * - 真正销毁发生在离开 composition / [unregister] 时
 * - 泵在 [shouldContinue] 变 false 时退出
 *
 * ## v1 限制
 * - 每扇窗各自 `wl_display_connect`（无共享 display）；循环里逐窗 poll
 * - 尚无 eventfd wake：空闲时短睡 2ms（跨线程 Main 任务最多延迟约一个 tick）
 */
object LinuxApplicationHost {
    private val sessions = mutableListOf<LinuxComposeWindow>()

    val liveWindowCount: Int get() = sessions.size

    fun isRegistered(window: LinuxComposeWindow): Boolean = sessions.contains(window)

    fun register(window: LinuxComposeWindow) {
        if (sessions.contains(window)) return
        sessions.add(window)
        println("composekn: host register window count=${sessions.size}")
    }

    fun unregister(window: LinuxComposeWindow) {
        if (!sessions.remove(window)) return
        println("composekn: host unregister window count=${sessions.size}")
    }

    /**
     * 叫醒共享泵。v1 无 eventfd，依赖空闲分支的短 sleep；此处保留钩子供
     * [ComposeNativeWindowBackend.wakeApplicationPump] 调用。
     */
    fun wake() {
        // no-op：idle 循环有 usleep(2ms)；后续可加 eventfd + poll(fds)
    }

    /**
     * 阻塞共享泵。调用方须已 [initLinuxMainThread]。
     *
     * @param shouldContinue false 时退出（application 收尾）。
     */
    fun runSharedPump(shouldContinue: () -> Boolean) {
        initLinuxMainThread()
        println("composekn: host shared pump enter")
        try {
            while (shouldContinue()) {
                // 尚无窗口：仍要 flush（application 级 LaunchedEffect / 首帧 Window 创建）
                if (sessions.isEmpty()) {
                    flushMainUIDispatcher()
                    if (!shouldContinue()) break
                    if (sessions.isEmpty()) {
                        usleep(2_000u) // 2ms，避免空转；也充当无 eventfd 时的 wake 粒度
                        flushMainUIDispatcher()
                    }
                    continue
                }

                val snapshot = sessions.toList()
                for (session in snapshot) {
                    if (!sessions.contains(session)) continue
                    if (!session.pollAndDispatchForHost()) {
                        // display 失败：摘掉会话，避免空转刷日志；真正 destroy 仍由上层 dispose
                        println("composekn: host poll failed — unregistering session")
                        unregister(session)
                        session.markHostDetachedAfterPollFailure()
                    }
                }
                flushMainUIDispatcher()

                // 简单空闲等待（无共享 wl_display / eventfd）
                usleep(1_000u) // 1ms
            }
        } finally {
            println("composekn: host shared pump exit (windows=${sessions.size})")
        }
    }
}

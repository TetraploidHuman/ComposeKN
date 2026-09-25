@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.composekn.linux

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skiko.ComposeKNTray
import org.jetbrains.skiko.composekn_display_begin_poll_cycle
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initLinuxMainThread
import org.jetbrains.skiko.setMainUIDispatcherWakeUpHandler
import platform.linux.EFD_CLOEXEC
import platform.linux.EFD_NONBLOCK
import platform.linux.eventfd
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.usleep
import platform.posix.write

/**
 * 进程级多窗口宿主：一条循环轮询所有 [LinuxComposeWindow] 的 Wayland display。
 *
 * Desktop 语义对齐：
 * - 系统关窗 → 只回调 onCloseRequest（DO_NOTHING），不 destroy surface
 * - 真正销毁发生在离开 composition / [unregister] 时
 * - 泵在 [shouldContinue] 变 false 时退出
 * - Dialog 软模态：有任一 DialogWindow 时，非对话框丢弃 pointer/key/touch（对齐 Win32 EnableWindow）
 *
 * 多窗共享同一 `wl_display`（native `ComposeKNSharedDisplay`）；每轮迭代先
 * [composekn_display_begin_poll_cycle]，再逐窗 poll（仅首窗 prepare_read）。
 * wake 用 eventfd + 短超时 poll（≤2ms），不阻塞跨线程 Main 任务。
 */
object LinuxApplicationHost {
    private val sessions = mutableListOf<LinuxComposeWindow>()

    /** eventfd：跨线程 [wake] 写入，空闲 [poll] 可读即醒。失败则退回 usleep。 */
    private val wakeFd: Int = eventfd(0, EFD_CLOEXEC or EFD_NONBLOCK)

    val liveWindowCount: Int get() = sessions.size

    fun isRegistered(window: LinuxComposeWindow): Boolean = sessions.contains(window)

    fun register(window: LinuxComposeWindow) {
        if (sessions.contains(window)) return
        sessions.add(window)
        refreshWakeHandler()
        refreshDialogModality()
        println("composekn: host register window count=${sessions.size}")
    }

    fun unregister(window: LinuxComposeWindow) {
        if (!sessions.remove(window)) return
        refreshWakeHandler()
        refreshDialogModality()
        println("composekn: host unregister window count=${sessions.size}")
    }

    /**
     * Desktop DocumentModal 子集：有任一 DialogWindow 时，禁用其它窗的输入派发
     *（软模态，无 EnableWindow 等价物）。
     */
    fun refreshDialogModality() {
        val hasDialog = sessions.any { it.isDialogWindow }
        for (session in sessions) {
            session.inputEnabled = !hasDialog || session.isDialogWindow
        }
    }

    /**
     * 叫醒共享泵（eventfd write；失败时空闲分支仍有 ≤2ms poll/usleep）。
     */
    fun wake() {
        if (wakeFd < 0) return
        // eventfd 每次写 8 字节的 uint64_t 增量
        val one = ulongArrayOf(1uL)
        one.usePinned { pinned ->
            write(wakeFd, pinned.addressOf(0), 8uL)
        }
    }

    private fun drainWake() {
        if (wakeFd < 0) return
        val buf = ULongArray(1)
        buf.usePinned { pinned ->
            // 非阻塞读干即可；EAGAIN 忽略
            while (read(wakeFd, pinned.addressOf(0), 8uL) > 0) {
                // drain
            }
        }
    }

    /** 空闲等待：优先 poll(eventfd, timeoutMs)；无 fd 时 usleep。 */
    private fun idleWait(timeoutMs: Int) {
        if (wakeFd < 0) {
            usleep((timeoutMs.coerceAtLeast(0) * 1000).toUInt())
            return
        }
        memScoped {
            val pfd = alloc<pollfd>()
            pfd.fd = wakeFd
            pfd.events = POLLIN.toShort()
            pfd.revents = 0
            poll(pfd.ptr, 1u, timeoutMs)
        }
        drainWake()
    }

    private fun refreshWakeHandler() {
        if (sessions.isEmpty()) {
            // 空宿主时仍保留 handler：application 启动期可能尚无窗但已有 Main 任务
            setMainUIDispatcherWakeUpHandler { wake() }
        } else {
            setMainUIDispatcherWakeUpHandler { wake() }
        }
    }

    /**
     * 阻塞共享泵。调用方须已 [initLinuxMainThread]。
     *
     * @param shouldContinue false 时退出（application 收尾）。
     */
    fun runSharedPump(shouldContinue: () -> Boolean) {
        initLinuxMainThread()
        setMainUIDispatcherWakeUpHandler { wake() }
        println("composekn: host shared pump enter (wakeFd=$wakeFd)")
        try {
            while (shouldContinue()) {
                // 尚无窗口：仍要 flush（application 级 LaunchedEffect / 首帧 Window 创建）
                if (sessions.isEmpty()) {
                    flushMainUIDispatcher()
                    if (!shouldContinue()) break
                    if (sessions.isEmpty()) {
                        idleWait(2) // ≤2ms
                        flushMainUIDispatcher()
                    }
                    continue
                }

                val snapshot = sessions.toList()
                composekn_display_begin_poll_cycle()
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

                // StatusNotifierItem / DBusMenu 回调
                ComposeKNTray.dispatch()

                // 有窗时也短等：避免忙等吃满 CPU；eventfd 可提前醒
                idleWait(1)
            }
        } finally {
            setMainUIDispatcherWakeUpHandler(null)
            println("composekn: host shared pump exit (windows=${sessions.size})")
        }
    }
}

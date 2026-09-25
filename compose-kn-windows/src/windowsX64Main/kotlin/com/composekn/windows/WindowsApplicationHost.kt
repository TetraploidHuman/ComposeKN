package com.composekn.windows

import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.WindowsImeDocument
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.noteLastActiveCompositionWindow
import org.jetbrains.skiko.setMainUIDispatcherWakeUpHandler
import org.jetbrains.skiko.setWindowsImeCaretProvider
import org.jetbrains.skiko.setWindowsImeReconvertProvider
import org.jetbrains.skiko.setWindowsImeTextProvider
import org.jetbrains.skiko.win32Log
import kotlin.concurrent.Volatile
import platform.posix.usleep

/** 最小化时的轮询间隔（与 WindowsComposeWindow 一致）。 */
private const val HOST_MINIMIZED_POLL_MS = 200

/**
 * 进程级多窗口宿主：一条 PeekMessage 循环服务所有 [WindowsComposeWindow]。
 *
 * Desktop 语义对齐：
 * - 系统关窗 → 只回调 onCloseRequest（DO_NOTHING），不拆 HWND
 * - 真正销毁发生在离开 composition / [unregister] 时
 * - 泵在 [shouldContinue] 变 false 时退出
 * - [WindowPosition.PlatformDefault]：物理像素 cascade（[placeCascaded]）
 *
 * ## IME / wake 路由（全局回调，多窗口必须分流）
 *
 * C 侧 IME / wake provider 都是进程唯一的函数指针：
 * - **IME**：优先 [focusedSession]，否则 [lastActiveSession]
 * - **wake**：PostMessage 到 [lastActiveSession] 的 HWND
 */
object WindowsApplicationHost {
    private val sessions = mutableListOf<WindowsComposeWindow>()

    /** 当前键盘焦点所在会话（FocusEvent）；IME 全局 provider 优先用它。 */
    @Volatile
    var focusedSession: WindowsComposeWindow? = null
        private set

    /** 最近一次焦点或成功 attach 的会话（剪贴板 / wake 回退 / cascade 锚点）。 */
    @Volatile
    var lastActiveSession: WindowsComposeWindow? = null
        private set

    /**
     * 每扇窗对应的 IME 文档/光标查询（由 [WindowsComposeApplication] 在 attach 时登记）。
     * key 用窗口身份；value 三件套与 C 侧三个 provider 对齐。
     */
    private val imeCaretByWindow = mutableMapOf<WindowsComposeWindow, (Int) -> IntArray?>()
    private val imeTextByWindow = mutableMapOf<WindowsComposeWindow, () -> WindowsImeDocument?>()
    private val imeReconvertByWindow =
        mutableMapOf<WindowsComposeWindow, (String, Int, Int) -> IntArray?>()

    val liveWindowCount: Int get() = sessions.size

    fun isRegistered(window: WindowsComposeWindow): Boolean = sessions.contains(window)

    fun register(window: WindowsComposeWindow) {
        if (sessions.contains(window)) return
        sessions.add(window)
        // 不抢 lastActive：对齐 Desktop WindowLocationTracker（新窗要等获得焦点才入序），
        // 否则 PlatformDefault cascade 会锚到自己而不是父窗当前坐标。
        if (lastActiveSession == null) {
            lastActiveSession = window
        }
        refreshWakeHandler()
        refreshImeProviders()
        refreshDialogModality()
        win32Log("host: register window count=${sessions.size}")
    }

    /**
     * Desktop 对齐的 [androidx.compose.ui.window.WindowPosition.PlatformDefault]：
     * 相对最近焦点兄弟窗做**物理像素** cascade（跨 DPI 安全）。
     * 无锚点时返回 false（调用方 [WindowsComposeWindow.centerOnScreen]）。
     */
    fun placeCascaded(
        window: WindowsComposeWindow,
        widthDp: Int,
        heightDp: Int,
    ): Boolean {
        val anchor = when {
            focusedSession != null && focusedSession !== window -> focusedSession
            lastActiveSession != null && lastActiveSession !== window -> lastActiveSession
            else -> sessions.lastOrNull { it !== window }
        } ?: return false
        val dest = window.nativeWindow ?: return false
        val src = anchor.nativeWindow ?: return false
        return dest.placeCascadedFrom(src, widthDp, heightDp)
    }

    fun unregister(window: WindowsComposeWindow) {
        if (!sessions.remove(window)) return
        imeCaretByWindow.remove(window)
        imeTextByWindow.remove(window)
        imeReconvertByWindow.remove(window)
        if (focusedSession === window) focusedSession = null
        if (lastActiveSession === window) {
            lastActiveSession = sessions.lastOrNull()
        }
        refreshWakeHandler()
        refreshImeProviders()
        refreshDialogModality()
        win32Log("host: unregister window count=${sessions.size}")
    }

    /**
     * Desktop DocumentModal 子集：有任一 DialogWindow 时，禁用其它窗的输入
     *（`EnableWindow(FALSE)`），对话框本身保持可点。
     */
    fun refreshDialogModality() {
        val hasDialog = sessions.any { it.isDialogWindow }
        for (session in sessions) {
            val enabled = !hasDialog || session.isDialogWindow
            session.setEnabled(enabled)
        }
    }

    /**
     * 登记某窗口的 IME 三件套；Host 在全局回调里按焦点/最近活跃路由。
     * 传 null 表示清除该窗条目。
     */
    fun installImeProviders(
        window: WindowsComposeWindow,
        caret: ((Int) -> IntArray?)?,
        text: (() -> WindowsImeDocument?)?,
        reconvert: ((String, Int, Int) -> IntArray?)?,
    ) {
        if (caret == null) imeCaretByWindow.remove(window) else imeCaretByWindow[window] = caret
        if (text == null) imeTextByWindow.remove(window) else imeTextByWindow[window] = text
        if (reconvert == null) {
            imeReconvertByWindow.remove(window)
        } else {
            imeReconvertByWindow[window] = reconvert
        }
        refreshImeProviders()
    }

    fun noteFocus(window: WindowsComposeWindow, hasFocus: Boolean) {
        if (hasFocus) {
            focusedSession = window
            lastActiveSession = window
            window.nativeWindow?.let { noteLastActiveCompositionWindow(it) }
            refreshImeProviders()
        } else if (focusedSession === window) {
            focusedSession = null
            refreshImeProviders()
        }
    }

    /** IME 路由目标：有焦点用焦点窗，否则最近活跃。 */
    private fun imeTarget(): WindowsComposeWindow? =
        focusedSession ?: lastActiveSession ?: sessions.lastOrNull()

    private fun refreshImeProviders() {
        if (sessions.isEmpty()) {
            setWindowsImeCaretProvider(null)
            setWindowsImeTextProvider(null)
            setWindowsImeReconvertProvider(null)
            return
        }
        // 全局只有一套 IME provider：按焦点/最近活跃窗转发（见文件头注释）。
        // 全部 try/catch：这些在 WM_IME_* 的 SendMessage 里同步跑，异常 = 0x20474343。
        setWindowsImeCaretProvider { charIndex ->
            try {
                val w = imeTarget() ?: return@setWindowsImeCaretProvider null
                imeCaretByWindow[w]?.invoke(charIndex)
            } catch (t: Throwable) {
                win32Log("ime: caret provider EXCEPTION ${t::class.simpleName}: ${t.message}")
                null
            }
        }
        setWindowsImeTextProvider {
            try {
                val w = imeTarget() ?: return@setWindowsImeTextProvider null
                imeTextByWindow[w]?.invoke()
            } catch (t: Throwable) {
                win32Log("ime: text provider EXCEPTION ${t::class.simpleName}: ${t.message}")
                null
            }
        }
        setWindowsImeReconvertProvider { text, targetOffset, targetLen ->
            try {
                val w = imeTarget() ?: return@setWindowsImeReconvertProvider null
                imeReconvertByWindow[w]?.invoke(text, targetOffset, targetLen)
            } catch (t: Throwable) {
                win32Log("ime: reconvert provider EXCEPTION ${t::class.simpleName}: ${t.message}")
                null
            }
        }
    }

    /**
     * 唤醒正在 waitMessage 的共享泵。
     *
     * 多窗口时 PostMessage 到任意仍存活的 HWND 即可（线程队列共享）；
     * 优先最近活跃窗，避免总打到已最小化/即将销毁的那个。
     */
    fun wake() {
        val target = lastActiveSession ?: focusedSession ?: sessions.firstOrNull()
        target?.wakeNative()
    }

    private fun refreshWakeHandler() {
        if (sessions.isEmpty()) {
            setMainUIDispatcherWakeUpHandler(null)
        } else {
            // 全局只有一个 wake handler：跨线程 UI 任务统一叫醒共享泵。
            setMainUIDispatcherWakeUpHandler { wake() }
        }
    }

    /**
     * 阻塞共享泵。调用方须已 [initWindowsMainThread]。
     *
     * @param shouldContinue false 时退出（application 收尾 / 单窗已关光）。
     */
    fun runSharedPump(shouldContinue: () -> Boolean) {
        initWindowsMainThread()
        refreshWakeHandler()
        refreshImeProviders()
        win32Log("host: shared pump enter")
        try {
            while (shouldContinue()) {
                // 尚无窗口：仍要 flush（application 级 LaunchedEffect / 首帧 Window 创建）
                if (sessions.isEmpty()) {
                    flushMainUIDispatcher()
                    if (!shouldContinue()) break
                    if (sessions.isEmpty()) {
                        usleep(2_000u) // 2ms，避免空转占满 CPU
                        flushMainUIDispatcher()
                    }
                    continue
                }

                if (!Win32Window.pumpThread()) {
                    win32Log("host: WM_QUIT — leaving shared pump")
                    break
                }
                flushMainUIDispatcher()

                val snapshot = sessions.toList()
                for (session in snapshot) {
                    if (!sessions.contains(session)) continue
                    try {
                        session.drainEventsForHost()
                    } catch (t: Throwable) {
                        win32Log(
                            "host: drain EXCEPTION ${t::class.simpleName}: ${t.message}",
                        )
                        t.stackTraceToString().lineSequence().take(25)
                            .forEach { win32Log("    $it") }
                        // 不让单窗事件异常拆掉整条泵；下一轮继续服务其它窗。
                    }
                }
                flushMainUIDispatcher()

                var anyPendingRender = false
                var nextFrameNanos = Long.MAX_VALUE
                val now = currentNanoTime()
                for (session in sessions.toList()) {
                    val tick = try {
                        session.tickRenderForHost(now)
                    } catch (t: Throwable) {
                        win32Log(
                            "host: tick EXCEPTION ${t::class.simpleName}: ${t.message}",
                        )
                        t.stackTraceToString().lineSequence().take(25)
                            .forEach { win32Log("    $it") }
                        HostRenderTick.Idle
                    }
                    when (tick) {
                        is HostRenderTick.Rendered -> {
                            anyPendingRender = anyPendingRender || tick.stillDirty
                            if (tick.nextFrameNanos < nextFrameNanos) {
                                nextFrameNanos = tick.nextFrameNanos
                            }
                        }
                        is HostRenderTick.Wait -> {
                            anyPendingRender = true
                            if (tick.untilNanos < nextFrameNanos) {
                                nextFrameNanos = tick.untilNanos
                            }
                        }
                        HostRenderTick.Idle -> Unit
                        HostRenderTick.Minimized -> {
                            anyPendingRender = true
                        }
                    }
                }

                if (!shouldContinue()) break
                if (sessions.isEmpty()) continue

                if (!anyPendingRender) {
                    val waiter = lastActiveSession ?: sessions.first()
                    if (!waiter.waitNative(-1)) continue
                } else if (nextFrameNanos != Long.MAX_VALUE && nextFrameNanos > now) {
                    val waitMs = ((nextFrameNanos - now) / 1_000_000L).toInt().coerceAtLeast(1)
                    val waiter = lastActiveSession ?: sessions.first()
                    waiter.waitNative(waitMs)
                } else if (sessions.any { it.isMinimized }) {
                    val waiter = lastActiveSession ?: sessions.first()
                    waiter.waitNative(HOST_MINIMIZED_POLL_MS)
                }
            }
        } catch (t: Throwable) {
            win32Log(
                "host: shared pump EXCEPTION ${t::class.simpleName}: ${t.message}",
            )
            t.stackTraceToString().lineSequence().take(30).forEach { win32Log("    $it") }
            throw t
        } finally {
            setMainUIDispatcherWakeUpHandler(null)
            win32Log("host: shared pump exit (windows=${sessions.size})")
        }
    }
}

/** [WindowsComposeWindow.tickRenderForHost] 的返回值。 */
internal sealed class HostRenderTick {
    data class Rendered(val nextFrameNanos: Long, val stillDirty: Boolean) : HostRenderTick()
    data class Wait(val untilNanos: Long) : HostRenderTick()
    data object Idle : HostRenderTick()
    data object Minimized : HostRenderTick()
}

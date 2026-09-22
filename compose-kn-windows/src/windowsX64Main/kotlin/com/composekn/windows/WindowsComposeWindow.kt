package com.composekn.windows

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.Win32Event
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skiko.ClipboardImage
import org.jetbrains.skiko.Win32HitTest
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.win32Log
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.setMainUIDispatcherWakeUpHandler
import org.jetbrains.skiko.setWindowsImeCaretProvider
import com.composekn.windows.internal.MOD_ALT
import com.composekn.windows.internal.MOD_CTRL
import com.composekn.windows.internal.MOD_SHIFT
import kotlin.concurrent.Volatile

/** 最小化时的轮询间隔：不渲染，但也不能忙等（5 次/秒的唤醒，CPU ≈ 0）。 */
private const val MINIMIZED_POLL_MS = 200

private const val FALLBACK_REFRESH_HZ = 60

/**
 * 把 `GetDeviceCaps(VREFRESH)` 的原始值换成可信的刷新率。
 *
 * 虚拟机/远程桌面上它会返回 0 或 1，这类不可信的值直接退回 60Hz —— 不能让它把
 * 帧率压成 1fps。
 */
internal fun usableRefreshHz(rawHz: Int): Int =
    if (rawHz in 24..360) rawHz else FALLBACK_REFRESH_HZ

/** 由（可信的）刷新率推出帧间隔，默认 60Hz。 */
private fun frameIntervalNanos(win: Win32Window): Long =
    1_000_000_000L / usableRefreshHz(win.refreshHz)

/**
 * Windows window + SkiaLayer with rendering and input events.
 *
 * Uses the Win32 C bridge (borderless window, WM_NCHITTEST edge resizing,
 * WM_NCLBUTTONDOWN/HTCAPTION move) from skiko's windowsMain, plus the same
 * Compose scene wiring pattern as [LinuxComposeWindow].
 */
class WindowsComposeWindow(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
    /**
     * false（默认）= 系统标题栏，对齐 Compose JVM 桌面的 `Window()`：
     * 拖动/双击最大化/Aero Snap/系统菜单/触摸拖拽/无障碍全部由 OS 负责。
     * true = 无边框（对应 JVM 的 `undecorated = true`），标题栏由 Compose 自绘。
     */
    val undecorated: Boolean = false,
) {
    val layer = SkiaLayer()

    // Window state
    var isMaximized: Boolean = false
        private set
    var isMinimized: Boolean = false
        private set

    /** 当前客户区**逻辑**尺寸（未 attach 时回退到创建参数）。 */
    val logicalWidth: Int get() = win32Window?.width ?: width
    val logicalHeight: Int get() = win32Window?.height ?: height

    /** 当前物理像素 / 逻辑像素（未 attach 时为 1.0）。 */
    val dpiScale: Float get() = layer.contentScale

    /**
     * `GetDeviceCaps(VREFRESH)` 的原始值（未 attach / 不可信时为 0）。
     *
     * 注意：窗口是在 [run] 里创建的，`run` 之前取不到 —— 诊断日志必须等窗口出现
     * 再打，否则会打出「刷新率=0Hz」，看着像 VREFRESH 失败（实际只是窗口还没建）。
     */
    val rawRefreshHz: Int get() = win32Window?.refreshHz ?: 0

    /** 实际生效的刷新率（不可信值已回退到 60Hz）。 */
    val effectiveRefreshHz: Int get() = usableRefreshHz(rawRefreshHz)

    /** 底层 Win32 窗口（未 attach 时为 null）。 */
    val nativeWindow: Win32Window? get() = win32Window

    /**
     * 已渲染帧数（诊断用）。
     *
     * `@Volatile`：自检的性能测量要从后台线程读它（帧率 = 帧数 / 时间），主线程写。
     */
    @Volatile
    var frameCount: Int = 0
        private set

    /**
     * 每帧回调（诊断/自动化测试用），参数是当前帧号。
     * 在 `renderImmediately()` 之后、状态同步之前调用。
     */
    var frameHook: ((Int) -> Unit)? = null

    private var win32Window: Win32Window? = null

    /** 窗口是否真的无边框（系统标题栏模式下为 false）。 */
    val isDecorated: Boolean get() = !undecorated

    /**
     * Compose 请求的光标形状（0=箭头 1=手 2=文本 I 型 3=十字）。
     *
     * 由 PlatformContext.setPointerIcon 设置；窗口还不存在时先记下来，
     * 窗口一建好就立刻应用（窗口存在时立即 SetCursor）。
     */
    var pointerIconKind: Int = 0
        set(value) {
            field = value
            win32Window?.setCursor(value)
        }

    /**
     * 系统关窗回调（对齐 Desktop DO_NOTHING_ON_CLOSE）。
     *
     * WM_CLOSE 只推 [WindowsEvent.CloseEvent]，**不** DestroyWindow。
     * - 声明式 [androidx.compose.ui.window.Window]：设为用户的 onCloseRequest
     * - 命令式独占 [run]：默认销毁本窗口以退出循环
     */
    var onCloseRequest: (() -> Unit)? = null

    /**
     * 原生几何/放置变化提示（Move/Resize、最大化/最小化）。
     * 由 [WindowsNativeWindowHandle] 挂上，把变化写回声明式 [androidx.compose.ui.window.WindowState]。
     */
    internal var onGeometryHint: (() -> Unit)? = null

    /**
     * true = 本窗是 [androidx.compose.ui.window.DialogWindow]；Host 据此做软模态。
     */
    internal var isDialogWindow: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (isHostAttached) {
                WindowsApplicationHost.refreshDialogModality()
            }
        }

    /** 启用/禁用 HWND 输入（软模态）。 */
    fun setEnabled(enabled: Boolean) {
        win32Window?.setEnabled(enabled)
    }

    /** 是否已挂到 [WindowsApplicationHost]（共享泵）；与独占 [run] 互斥。 */
    var isHostAttached: Boolean = false
        private set

    private var hostEventHandler: ((WindowsEvent) -> Unit)? = null
    private var hostNextFrameNanos: Long = 0L
    private var hostFrames: Int = 0

    /**
     * 创建 HWND、attach SkiaLayer，并注册到共享宿主。**不**进入阻塞循环。
     *
     * 之后由 [WindowsApplicationHost.runSharedPump] 调用
     * [drainEventsForHost] / [tickRenderForHost]。
     *
     * @param show 是否立刻显示。声明式 Window 应先设位置/尺寸再 [show]，避免瞬移。
     */
    fun attachToHost(onEvent: (WindowsEvent) -> Unit, show: Boolean = true) {
        check(!isHostAttached) { "already attached to WindowsApplicationHost" }
        check(win32Window == null) { "window already created (exclusive run?)" }
        initWindowsMainThread()
        win32Log("attachToHost: create Win32 window ${width}x$height")
        val win = createAndAttachLayer()
        hostEventHandler = onEvent
        isHostAttached = true
        hostNextFrameNanos = 0L
        hostFrames = 0
        // 共享泵：wake 走 Host（可打到任一存活 HWND），不要抢全局 wake handler。
        layer.setRenderRequestHandler { WindowsApplicationHost.wake() }
        WindowsApplicationHost.register(this)
        layer.renderImmediately()
        layer.needRender()
        if (show) {
            win.show()
        }
        win32Log(
            "attachToHost: ready frameInterval=${frameIntervalNanos(win) / 1_000_000}ms " +
                "refresh=${win.refreshHz}Hz show=$show"
        )
    }

    /** 显示窗口（创建时默认隐藏，见 [attachToHost]）。 */
    fun show() {
        win32Window?.show()
    }

    /**
     * 从本窗口事件队列取出并翻译，交给宿主会话。
     *
     * Close：只调 [onCloseRequest]（及 onEvent），**不**销毁 —— 销毁在离开 composition /
     * [detachFromHost] 时发生。
     */
    fun drainEventsForHost() {
        val win = win32Window ?: return
        val handler = hostEventHandler ?: return
        translateAndDispatch(win) { event ->
            when (event) {
                is WindowsEvent.FocusEvent -> {
                    WindowsApplicationHost.noteFocus(this, event.hasFocus)
                    handler(event)
                }
                is WindowsEvent.CloseEvent -> {
                    // DO_NOTHING：系统关窗 → 回调；真正 destroy 由上层决定
                    onCloseRequest?.invoke()
                    handler(event)
                }
                else -> handler(event)
            }
        }
    }

    /**
     * 共享泵的一帧渲染 tick（按需 + 帧节流 + 最小化跳过）。
     */
    internal fun tickRenderForHost(nowNanos: Long): HostRenderTick {
        val win = win32Window ?: return HostRenderTick.Idle
        val nextMax = win.isMaximized
        val nextMin = win.isMinimized
        if (nextMax != isMaximized || nextMin != isMinimized) {
            isMaximized = nextMax
            isMinimized = nextMin
            onGeometryHint?.invoke()
        } else {
            isMaximized = nextMax
            isMinimized = nextMin
        }
        if (!layer.hasRenderRequest()) return HostRenderTick.Idle
        if (win.isMinimized) return HostRenderTick.Minimized
        if (nowNanos < hostNextFrameNanos) {
            return HostRenderTick.Wait(hostNextFrameNanos)
        }
        if (!layer.renderIfRequested()) {
            return HostRenderTick.Wait(hostNextFrameNanos)
        }
        val after = currentNanoTime()
        val interval = frameIntervalNanos(win)
        hostNextFrameNanos = maxOf(hostNextFrameNanos + interval, after)
        hostFrames++
        frameCount = hostFrames
        frameHook?.invoke(hostFrames)
        return HostRenderTick.Rendered(
            nextFrameNanos = hostNextFrameNanos,
            stillDirty = layer.hasRenderRequest(),
        )
    }

    /** 供 Host 调用的内部 API。 */
    internal fun wakeNative() {
        win32Window?.wake()
    }

    internal fun waitNative(timeoutMillis: Int): Boolean =
        win32Window?.waitMessage(timeoutMillis) ?: false

    /**
     * 从共享宿主摘掉并销毁 HWND（离开 composition / 会话收尾时调用）。
     */
    fun detachFromHost() {
        if (!isHostAttached && win32Window == null) return
        win32Log("detachFromHost: frames=$hostFrames")
        WindowsApplicationHost.unregister(this)
        isHostAttached = false
        hostEventHandler = null
        layer.setRenderRequestHandler(null)
        layer.detach()
        win32Window?.close()
        win32Window = null
    }

    /**
     * 独占消息循环（单窗口自检 / 遗留路径）。
     *
     * 多窗口请用 [attachToHost] + [WindowsApplicationHost.runSharedPump]。
     * 系统关窗默认销毁本窗口（若未另设 [onCloseRequest]）。
     */
    fun run(onEvent: (WindowsEvent) -> Unit) {
        win32Log("run: enter (exclusive pump)")
        check(!isHostAttached) { "use shared host or exclusive run, not both" }
        initWindowsMainThread()
        val win = createAndAttachLayer()
        win.show()

        // 命令式默认：关窗 = 销毁，以便本循环退出（自检 requestClose 依赖这条）。
        if (onCloseRequest == null) {
            onCloseRequest = { destroy() }
        }

        layer.setRenderRequestHandler { win.wake() }
        setMainUIDispatcherWakeUpHandler { win.wake() }

        // 先渲染一帧，再开始分发事件。
        //
        // scene.size 是在 renderDelegate.onRender -> renderFrame() 里设定的。如果第一轮
        // 就先把消息泵里的事件派发进去（Wine/真机上窗口刚建好通常会先来一串鼠标
        // Enter/Move），`sendPointerEvent` 会在 scene.size 仍是 0 时触发
        // measureAndLayout —— 根节点此时拿到的是**无界**约束，内容里任何
        // verticalScroll/LazyColumn 都会当场抛：
        //   IllegalStateException: Vertically scrollable component was measured with
        //   an infinity maximum height constraints ...
        // （画廊就是这么在启动瞬间崩掉的。）
        layer.renderImmediately()

        val frameInterval = frameIntervalNanos(win)
        win32Log(
            "run: frame interval ${frameInterval / 1_000_000} ms " +
                "(refresh=${win.refreshHz}Hz)"
        )
        // 下一帧的最早时间点（帧节流）。0 = 立刻。
        var nextFrameNanos = 0L

        // 保证消息循环至少渲染一帧：上面那一帧是为了让 scene.size 就位（避免首轮
        // 事件在 size=0 时触发 measureAndLayout），这一帧才是「进入循环后的第一帧」
        // —— 帧计数与 frameHook 从它开始。按需渲染下必须显式请求一次，否则内容
        // 若无失效，循环会立刻睡着、一帧都不出。
        layer.needRender()

        var frames = 0
        var running = true
        try {
            while (running) {
                running = win.pump()
                flushMainUIDispatcher()
                translateAndDispatch(win) { event ->
                    when (event) {
                        is WindowsEvent.CloseEvent -> {
                            onCloseRequest?.invoke()
                            onEvent(event)
                        }
                        is WindowsEvent.FocusEvent -> {
                            WindowsApplicationHost.noteFocus(this, event.hasFocus)
                            onEvent(event)
                        }
                        else -> onEvent(event)
                    }
                }
                // 事件处理可能在 UI 队列里排了新任务（输入 -> 状态变更 -> 重组）
                flushMainUIDispatcher()
                if (!running || win32Window == null) break

                if (!layer.hasRenderRequest()) {
                    // 内容没变：不重绘。真正睡着等消息/唤醒 —— 静止的窗口在这里
                    // CPU 占用是 0（以前是无条件每轮重绘，一颗核心跑满）。
                    if (!win.waitMessage(-1)) break
                    continue
                }
                if (win.isMinimized) {
                    // 最小化时不渲染（也不消费请求：恢复后立刻补上一帧），但不能忙等。
                    if (!win.waitMessage(MINIMIZED_POLL_MS)) break
                    continue
                }
                val now = currentNanoTime()
                if (now < nextFrameNanos) {
                    // 帧节流：动画/连续失效最多按显示器刷新率重绘。
                    // 等待期间到达的消息会在下一轮先被处理 —— 输入处理不受节流影响，
                    // 只是「画出来」落在这个帧边界上（和合成器驱动的桌面应用一致）。
                    val waitMs = ((nextFrameNanos - now) / 1_000_000L).toInt().coerceAtLeast(1)
                    if (!win.waitMessage(waitMs)) break
                    continue
                }
                if (!layer.renderIfRequested()) continue
                if (frames == 0) win32Log("run: first frame rendered")
                // 按固定节奏推进；落后了就以「现在」为基准重新对齐（不追赶、不堆积）。
                val after = currentNanoTime()
                nextFrameNanos = maxOf(nextFrameNanos + frameInterval, after)
                frames++
                frameCount = frames
                frameHook?.invoke(frames)
                isMaximized = win.isMaximized
                isMinimized = win.isMinimized
            }
        } catch (t: Throwable) {
            win32Log("run: EXCEPTION ${t::class.simpleName}: ${t.message}")
            t.stackTraceToString().lineSequence().take(25).forEach { win32Log("    $it") }
            throw t
        } finally {
            win32Log("run: exiting loop after $frames frames")
            // 先摘掉回调再拆窗口：它们会 PostMessage（窗口没了就成了野指针）。
            layer.setRenderRequestHandler(null)
            setMainUIDispatcherWakeUpHandler(null)
            // IME 光标回调也一样：它是注册在 C 侧的**全局**回调，窗口拆掉之后不能再被调用
            // （真机上 WM_IME_REQUEST 可能在销毁过程中还剩一条）。
            // 多窗口共享时由 Host 接管；独占路径仍在此清空。
            if (!WindowsApplicationHost.isRegistered(this) &&
                WindowsApplicationHost.liveWindowCount == 0
            ) {
                setWindowsImeCaretProvider(null)
            }
            layer.detach()
            win32Window?.close()
            win32Window = null
        }
    }

    /** 创建 Win32 窗口并 attach SkiaLayer（[run] / [attachToHost] 共用）。 */
    private fun createAndAttachLayer(): Win32Window {
        win32Log("create: Win32 window ${width}x$height undecorated=$undecorated")
        val win = Win32Window(title, width, height, undecorated)
        win32Window = win
        println("WindowsComposeWindow: created window '$title' (${win.width}x${win.height})")
        win32Log("create: window ok ${win.width}x${win.height}")
        if (pointerIconKind != 0) win.setCursor(pointerIconKind)
        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before attach/run"
        }
        layer.attachTo(win)
        return win
    }

    private fun translateAndDispatch(win: Win32Window, onEvent: (WindowsEvent) -> Unit) {
        while (true) {
            val raw = win.popEvent() ?: return
            when (raw.type) {
                Win32Event.MOUSE_MOVE -> onEvent(
                    WindowsEvent.MouseMoveEvent(
                        x = raw.x.toInt(),
                        y = raw.y.toInt(),
                        isShiftPressed = raw.modifiers and MOD_SHIFT != 0u,
                        isCtrlPressed = raw.modifiers and MOD_CTRL != 0u,
                        isAltPressed = raw.modifiers and MOD_ALT != 0u,
                    )
                )
                Win32Event.MOUSE_BUTTON -> onEvent(
                    WindowsEvent.MouseButtonEvent(
                        x = raw.x.toInt(),
                        y = raw.y.toInt(),
                        button = when (raw.button) {
                            272u -> MouseButton.Left
                            274u -> MouseButton.Right
                            else -> MouseButton.Middle
                        },
                        isPressed = raw.state == 1u,
                        isShiftPressed = raw.modifiers and MOD_SHIFT != 0u,
                        // 原来这里写的是 and 1u（= MOD_SHIFT），于是「按住 Ctrl 点击」
                        // 会被当成 Shift；改用桥接约定的位。
                        isCtrlPressed = raw.modifiers and MOD_CTRL != 0u,
                        isAltPressed = raw.modifiers and MOD_ALT != 0u,
                    )
                )
                Win32Event.MOUSE_WHEEL -> onEvent(
                    WindowsEvent.MouseWheelEvent(
                        x = raw.x.toInt(),
                        y = raw.y.toInt(),
                        // raw.b == 1 表示来自 WM_MOUSEHWHEEL（横向滚轮 / 触控板横滑）；
                        // 纵向和横向都按「一格 = 120」换算成 Compose 的滚轮单位。
                        //
                        // ⚠ 必须用**浮点除法**（120f）：zDelta 不保证是 120 的倍数，
                        // 触控板/自由滚轮会送来 40/80/17 这种值，整数除法会把它截断成 0
                        // —— 表现就是触控板「慢速完全不动、快滑一顿一顿」（HANDOVER §17.32）。
                        // 上游对齐点：AWT 的 `getPreciseWheelRotation()` 是 Double，
                        // 上游直接把它当 scrollDelta 用，全程 Float。
                        deltaX = if (raw.b == 1) raw.a / WIN32_WHEEL_DELTA.toFloat() else 0f,
                        deltaY = if (raw.b == 1) 0f else raw.a / WIN32_WHEEL_DELTA.toFloat(),
                        isShiftPressed = raw.modifiers and MOD_SHIFT != 0u,
                        isCtrlPressed = raw.modifiers and MOD_CTRL != 0u,
                        isAltPressed = raw.modifiers and MOD_ALT != 0u,
                    )
                )
                Win32Event.TOUCH_DOWN, Win32Event.TOUCH_MOVE, Win32Event.TOUCH_UP -> onEvent(
                    WindowsEvent.TouchEvent(
                        pointerId = raw.button.toLong(),
                        x = raw.x.toInt(),
                        y = raw.y.toInt(),
                        phase = when (raw.type) {
                            Win32Event.TOUCH_DOWN -> TouchPhase.Down
                            Win32Event.TOUCH_UP -> TouchPhase.Up
                            else -> TouchPhase.Move
                        },
                        // C 侧把触摸事件时间放在 a（归一化成进程内毫秒，见 win32_window.cc
                        // 的 touchTimeBase）：速度估计器必须拿到真实事件时间，否则
                        // 同一帧里的多条更新会共用「派发时刻」，算出假甩动。
                        timeMillis = raw.a.toLong(),
                    )
                )
                Win32Event.KEY -> onEvent(
                    WindowsEvent.KeyEvent(
                        virtualKeyCode = raw.button.toInt(),
                        scanCode = raw.b,
                        isKeyDown = raw.state == 1u,
                        isShiftPressed = raw.modifiers and MOD_SHIFT != 0u,
                        isCtrlPressed = raw.modifiers and MOD_CTRL != 0u,
                        isAltPressed = raw.modifiers and MOD_ALT != 0u,
                    )
                )
                Win32Event.CHAR -> if (raw.b > 0) {
                    onEvent(
                        WindowsEvent.KeyEvent(
                            virtualKeyCode = 0,
                            scanCode = 0,
                            isKeyDown = true,
                            character = Char(raw.b),
                        )
                    )
                }
                // IME 的四条事件每一条都**必须**配对弹走一个字符串（C 侧是严格
                // 一一对应的 FIFO，不管这条事件带不带文本）—— 漏弹一次，后面的
                // 组字串/提交串就会整体错位一格。
                Win32Event.IME_START -> {
                    win.imePopText()
                    onEvent(WindowsEvent.ImeStartEvent)
                }
                Win32Event.IME_UPDATE -> {
                    val text = win.imePopText() ?: ""
                    onEvent(WindowsEvent.ImeCompositionEvent(text))
                }
                Win32Event.IME_COMMIT -> {
                    val text = win.imePopText()
                    if (text != null && text.isNotEmpty()) {
                        onEvent(WindowsEvent.ImeCommitEvent(text))
                    }
                }
                Win32Event.IME_END -> {
                    win.imePopText()
                    onEvent(WindowsEvent.ImeEndEvent)
                }
                Win32Event.IME_RECONVERT_SELECT -> {
                    win.imePopText()   // 这条不带文本，但仍然配对弹一个（FIFO 约定）
                    onEvent(WindowsEvent.ImeReconvertSelectEvent(start = raw.a, end = raw.b))
                }
                // 拖放：C 侧的 IDropTarget 把每个回调推成一条事件，负载（文件/文本）
                // 放在和 IME 一样**严格 1:1 的 FIFO**里 —— 所以这里必须**每个** phase
                // 都各弹一次（有负载弹负载，没有也弹一个空的），否则后面的事件会整体
                // 错位一格。
                Win32Event.DRAG_ENTER, Win32Event.DRAG_OVER,
                Win32Event.DRAG_LEAVE, Win32Event.DRAG_DROP -> {
                    val files = win.dragPopFiles()
                    val text = win.dragPopText().takeIf { it.isNotEmpty() }
                    onEvent(
                        WindowsEvent.DragEvent(
                            phase = when (raw.type) {
                                Win32Event.DRAG_ENTER -> DragPhase.Enter
                                Win32Event.DRAG_OVER -> DragPhase.Over
                                Win32Event.DRAG_LEAVE -> DragPhase.Leave
                                else -> DragPhase.Drop
                            },
                            x = raw.x.toInt(),
                            y = raw.y.toInt(),
                            files = files,
                            text = text,
                        )
                    )
                }
                Win32Event.SIZE -> onEvent(WindowsEvent.ResizeEvent(width = raw.a, height = raw.b))
                Win32Event.MOVE -> onEvent(WindowsEvent.MoveEvent(x = raw.x.toInt(), y = raw.y.toInt()))
                Win32Event.CLOSE -> onEvent(WindowsEvent.CloseEvent)
                Win32Event.FOCUS -> onEvent(WindowsEvent.FocusEvent(hasFocus = raw.a == 1))
            }
        }
    }

    /**
     * Legacy Skia-only loop without Compose scene.
     */
    fun runSkiaOnly(drawFrame: (Canvas, Int, Int) -> Unit) {
        layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                drawFrame(canvas, width, height)
            }
        }
        run(onEvent = {})
    }

    /**
     * Minimize the window.
     */
    fun minimize() {
        win32Window?.minimize()
        isMinimized = true
    }

    /**
     * Maximize the window（不可缩放时忽略，对齐上游 `resizable = false` 的语义）。
     */
    fun maximize() {
        if (!resizable) return
        win32Window?.maximize()
        isMaximized = true
    }

    /**
     * Restore the window from maximized/minimized.
     */
    fun restore() {
        win32Window?.restore()
        isMaximized = false
        isMinimized = false
    }

    /**
     * Maximize or restore the window（不可缩放时忽略）。
     */
    fun toggleMaximized() {
        val win = win32Window ?: return
        if (win.isMaximized) {
            restore()
        } else {
            maximize()
        }
    }

    // ---------------------------------------------------------------------
    // 窗口 API（位置 / 置顶 / 全屏 / 可缩放 / 任务栏进度）
    //
    // 这些语义与上游 Compose Desktop 的 `WindowState` / `WindowPlacement` / `Window`
    // 参数一一对应（`position` / `size` / `isFullscreen` / `alwaysOnTop` / `resizable`）。
    // 上游那些类型是 JVM/AWT 就地的（内部裹着 java.awt.Window），原生宿主用不了，
    // 所以这里给同义访问器；位置/尺寸一律是**逻辑像素（dp）**，尺寸指**客户区**。
    // ---------------------------------------------------------------------

    /** 是否置顶（读 Win32 的真状态，不是我们的记账）。 */
    val alwaysOnTop: Boolean get() = win32Window?.isAlwaysOnTop ?: false

    /** 置顶 / 取消置顶。 */
    fun setAlwaysOnTop(onTop: Boolean) {
        win32Window?.setAlwaysOnTop(onTop)
    }

    /**
     * 能否拖边框改大小。
     *
     * `false` 时：`WM_NCHITTEST` 不再返回边缘命中码（拖边框无效），并且 [maximize] /
     * [toggleMaximized] 会被忽略 —— 与上游 `resizable = false` 的语义一致。
     */
    var resizable: Boolean = true
        set(value) {
            field = value
            win32Window?.setResizable(value)
        }

    /** 自检用：窗口样式里有没有 `WS_THICKFRAME`（可缩放的标志位）。 */
    val hasThickFrame: Boolean get() = win32Window?.hasThickFrame ?: false

    /**
     * 自检用：对窗口发一条**真实**的 `WM_NCHITTEST`，返回命中码（见 [Win32HitTest]）。
     *
     * `where`：0=左中 1=右中 2=上中 3=下中 4=客户区中心。
     */
    fun testHitTest(where: Int): Int = win32Window?.testHitTest(where) ?: 0

    /** 是否在无边框全屏（铺满窗口所在显示器）。 */
    val isFullscreen: Boolean get() = win32Window?.isFullscreen ?: false

    /**
     * 进 / 出全屏。进全屏会铺满窗口所在显示器并去掉非客户区；退出时还原原来的样式与位置。
     *
     * @return false = 失败（比如拿不到显示器信息）。
     */
    fun setFullscreen(fullscreen: Boolean): Boolean =
        win32Window?.setFullscreen(fullscreen) ?: false

    /** 切换全屏，返回切换后的状态。 */
    fun toggleFullscreen(): Boolean {
        val target = !isFullscreen
        return setFullscreen(target) && isFullscreen == target
    }

    /**
     * 窗口左上角在屏幕上的位置（dp）。
     *
     * 拿不到窗口时返回 `IntOffset.Zero`；最大化/全屏时给的是系统当前的窗口矩形。
     */
    val windowPosition: IntOffset
        get() {
            val frame = win32Window?.windowFrame() ?: return IntOffset.Zero
            return IntOffset(frame[0], frame[1])
        }

    /** 把窗口左上角移到屏幕坐标 (x, y)（dp）。 */
    fun setWindowPosition(x: Int, y: Int) {
        win32Window?.setWindowPosition(x, y)
    }

    /**
     * 当前**客户区**大小（dp）—— 就是 Compose 场景尺寸（[logicalWidth] / [logicalHeight]）。
     */
    val windowSize: IntSize
        get() {
            val frame = win32Window?.windowFrame() ?: return IntSize(width, height)
            return IntSize(frame[2], frame[3])
        }

    /** 把客户区设成 width x height（dp）；系统标题栏的窗口会自动把非客户区算进去。 */
    fun setWindowSize(width: Int, height: Int) {
        win32Window?.setClientSize(width, height)
    }

    /**
     * 把窗口移到主显示器工作区中间（尺寸不变）—— 上游 `WindowPosition.Aligned(Alignment.Center)`
     * 的等价物。拿不到显示器信息时什么都不做。
     */
    fun centerOnScreen() {
        val win = win32Window ?: return
        val screen = win.primaryMonitorWorkAreaDp() ?: return
        val size = windowSize
        val x = screen[0] + (screen[2] - size.width) / 2
        val y = screen[1] + (screen[3] - size.height) / 2
        win.setWindowPosition(x, y)
    }

    /** 任务栏进度能不能用（Wine / 没有 shell 时为 false）。 */
    val taskbarSupported: Boolean get() = win32Window?.taskbarSupported ?: false

    /**
     * 任务栏进度（Windows 任务栏上的绿色/红色/黄色进度条）。
     *
     * @param state 见 [TaskbarProgressState]；[TaskbarProgressState.None] 会清掉进度。
     * @param completed 0.0..1.0（会被夹到范围内）。
     * @return false = 这个环境没有任务栏（**不假装成功**）。
     */
    fun setTaskbarProgress(
        state: TaskbarProgressState,
        completed: Double = 0.0,
    ): Boolean = win32Window?.setTaskbarProgress(state.win32Value, completed) ?: false

    /** 自检用：读回宿主记的进度状态。 */
    fun taskbarProgressState(): Pair<TaskbarProgressState, Double> {
        val (state, completed) = win32Window?.taskbarProgressState() ?: (0 to 0.0)
        return TaskbarProgressState.fromWin32(state) to completed
    }

    /**
     * Request window close.
     */
    fun requestClose() {
        win32Window?.requestClose()
    }

    /**
     * 设置 Win32 窗口标题（任务栏 / 窗口管理器可见）。
     *
     * 注意 CSD 标题栏上的文字是 Compose 自己画的（取自构造参数），这里改的是
     * 「原生」标题 —— demo 用它把实测帧率显示出来。
     */
    fun setTitle(title: String) {
        win32Window?.setTitle(title)
    }

    /** 走过的 IME 消息条数（0 = 系统没发 IME 消息；自检/真机排查用）。 */
    val imeMessageCount: Int get() = win32Window?.imeMessageCount ?: 0

    /** 当前是否正在组字。 */
    val imeComposing: Boolean get() = win32Window?.imeComposing ?: false

    /** 取消正在进行的 IME 组字（文本会话结束时调用）。 */
    fun imeCancelComposition() {
        win32Window?.imeCancelComposition()
    }

    /** 自检用：注入一条「IME 提交」事件（Wine 里没有真 IME）。 */
    fun imeTestCommit(text: String) {
        win32Window?.imeTestCommit(text)
    }

    /**
     * 自检用：走**真实的** `WM_IME_REQUEST(IMR_QUERYCHARPOSITION)` 路径问一次
     * 「组字串里第 [dwCharPos] 个字符在哪」（候选窗锚点塌缩逻辑在 C 侧，这条是
     * 唯一能断言到 C 侧真答案的路）。返回 {x, y, lineHeight, 1}，客户区物理像素，
     * pt 是字符/光标**底部**。
     */
    fun imeTestQueryCharPos(dwCharPos: Int): IntArray? = win32Window?.imeTestQueryCharPos(dwCharPos)

    /** 自检用：合成一条 `WM_IME_STARTCOMPOSITION` / `WM_IME_ENDCOMPOSITION`。 */
    fun imeTestSendCompositionMessage(start: Boolean) {
        win32Window?.imeTestSendCompositionMessage(start)
    }

    /**
     * 自检用：发一条 `IMR_DOCUMENTFEED`(0) / `IMR_RECONVERTSTRING`(1) /
     * `IMR_COMPOSITIONFONT`(2)，返回 C 侧填好的字段（见 skiko 的
     * `composekn_win32_ime_test_reconvert`）。
     */
    fun imeTestReconvert(kind: Int, bufferChars: Int): IntArray? =
        win32Window?.imeTestReconvert(kind, bufferChars)

    /** 自检用：合成一条 `IMR_CONFIRMRECONVERTSTRING`；true = 我们接受了这次重转换。 */
    fun imeTestConfirmReconvert(text: String, targetOffset: Int, targetLen: Int): Boolean =
        win32Window?.imeTestConfirmReconvert(text, targetOffset, targetLen) ?: false

    /** 拖放目标注册成功没有（OleInitialize/RegisterDragDrop 失败时为 false）。 */
    val oleAvailable: Boolean get() = win32Window?.oleAvailable ?: false

    /** 最近一次回给 OLE 的 effect（`Win32Message.DROPEFFECT_NONE/COPY`）。 */
    val lastDropEffect: Int get() = win32Window?.lastDropEffect ?: 0

    /**
     * 把 Compose 对当前拖放位置的判定写回宿主（OLE 的 *pdwEffect）。
     *
     * 见 [Win32Window.setDropAccept] 的说明：IDropTarget::DragEnter 必须同步回答，
     * 所以是「ENTER 先乐观接受，Kotlin 判定完纠正后面的 OVER/DROP」。
     */
    fun setDropAccept(accept: Boolean): Boolean = win32Window?.setDropAccept(accept) ?: false

    /** 自检用：直接把一段 DIB 字节喂给解码器（绕开剪贴板）。 */
    fun testDecodeDib(dib: ByteArray): ClipboardImage? = win32Window?.testDecodeDib(dib)

    /**
     * 自检用：往剪贴板放一个 `CF_HDROP`（`paths` 是文件路径列表）——
     * 模拟"在资源管理器里 Ctrl+C 了文件"。
     */
    fun clipboardTestSetFiles(paths: List<String>): Boolean =
        win32Window?.clipboardTestSetFiles(paths) ?: false

    /** 自检用：往剪贴板放一张**只有** `CF_BITMAP`（裸 HBITMAP）的图。 */
    fun clipboardTestSetBitmap(image: ClipboardImage): Boolean =
        win32Window?.clipboardTestSetBitmap(image) ?: false

    /** 自检用：往剪贴板放一张 8bpp 调色板 `CF_DIB`。 */
    fun clipboardTestSetDib8(width: Int, height: Int, indices: ByteArray): Boolean =
        win32Window?.clipboardTestSetDib8(width, height, indices) ?: false

    /**
     * 自检用：把剪贴板里某个格式的**原始字节**按 hex 读出来。
     *
     * 用途是独立校验「我们写进去的 CF_HTML 头 / 位图头到底长什么样」—— 只做
     * `setHtml` -> `getHtml` 的往返是查不出「头里的偏移写错了」的（自己的读函数
     * 可能正好用同样错的逻辑读回来）。
     *
     * formatName：注册格式名（`"HTML Format"` / `"Rich Text Format"`）或 `#<标准格式号>`
     * （`#8` = CF_DIB、`#17` = CF_DIBV5）。
     */
    fun clipboardGetRawHex(formatName: String): String? = win32Window?.clipboardGetRawHex(formatName)

    /**
     * 自检用：直接驱动注册好的 OLE IDropTarget（构造一个真的 IDataObject）。
     *
     * phase: 0=DragEnter 1=DragOver 2=DragLeave 3=Drop；kind: 0=文件 1=文本。
     */
    fun testSimulateDrag(phase: Int, x: Int, y: Int, kind: Int): Boolean =
        win32Window?.testSimulateDrag(phase, x, y, kind) ?: false

    /**
     * 发起 OLE 拖放（模态）。自检 / Gallery 可直接调；Compose `dragAndDropSource`
     * 走同一条 C 桥。
     */
    fun doDragDrop(files: List<String>?, text: String?): Int =
        win32Window?.doDragDrop(files, text) ?: -1

    /**
     * 自检用：把一条**真实的 Win32 鼠标消息** PostMessage 到窗口自己的消息队列。
     *
     * 与 [WindowsComposeApplication.dispatchEvent] 的区别是根本性的：后者把
     * `WindowsEvent` 直接喂给 Compose，**跳过整个 C++ 宿主层**；这条会走
     * 主循环 `GetMessage -> DispatchMessage -> 真实 wndproc 分支 -> C 侧事件队列`，
     * 于是 wndproc 里的参数解码、坐标换算、消息过滤都在自动化覆盖之内
     * （HANDOVER §17.31）。
     *
     * [x]/[y] 是客户区坐标（物理像素，与真机消息一致）。
     */
    fun postTestMouseMessage(message: Int, x: Int, y: Int, wheelDelta: Int = 0): Boolean =
        win32Window?.postTestMouseMessage(message, x, y, wheelDelta) ?: false

    /** 自检用：把一条**真实的 Win32 键盘/字符消息** PostMessage 到窗口的消息队列。 */
    fun postTestKeyMessage(
        message: Int,
        vkOrChar: Int,
        scanCode: Int = 0,
        isRepeat: Boolean = false,
    ): Boolean = win32Window?.postTestKeyMessage(message, vkOrChar, scanCode, isRepeat) ?: false

    /**
     * Begin window move (for title bar drag).
     */
    fun beginMove() {
        win32Window?.beginMove()
    }

    /**
     * Destroy the window and cleanup resources.
     *
     * 若已挂共享宿主，走 [detachFromHost]（unregister + detach + close）；
     * 独占 [run] 路径只关 HWND，由 finally 做 layer detach。
     */
    fun destroy() {
        if (isHostAttached) {
            detachFromHost()
            return
        }
        win32Window?.close()
        win32Window = null
    }
}

/**
 * Draw a simple test frame.
 */
fun drawHelloFrame(canvas: Canvas, width: Int, height: Int) {
    val paint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.makeRGB(30, 120, 220) }
    canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(40f, 40f, width.toFloat() - 80f, height.toFloat() - 80f), paint)
    val font = org.jetbrains.skia.Font(null, 28f)
    val textPaint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.WHITE }
    canvas.drawString("ComposeKN / Windows", 60f, 100f, font, textPaint)
}

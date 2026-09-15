package com.composekn.windows

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.Win32Event
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
     * Run the window with event handling.
     */
    fun run(onEvent: (WindowsEvent) -> Unit) {
        win32Log("run: enter")
        initWindowsMainThread()
        win32Log("run: create Win32 window ${width}x$height")
        val win = Win32Window(title, width, height, undecorated)
        win32Window = win
        println("WindowsComposeWindow: created window '$title' (${win.width}x${win.height})")
        win32Log("run: window created ok ${win.width}x${win.height}")
        if (pointerIconKind != 0) win.setCursor(pointerIconKind)

        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before WindowsComposeWindow.run()"
        }
        win32Log("run: attachTo layer")
        layer.attachTo(win)
        win32Log("run: layer attached; entering message loop")

        // 渲染请求（Compose 失效 / 动画帧 / 显式 needRender）与跨线程 UI 任务
        // 都可能发生在循环正阻塞等消息的时候，必须能把消息泵叫醒：
        //   - 不接渲染请求  -> 动画停摆（每次循环都在睡觉，没人叫它）
        //   - 不接 UI 任务  -> 后台线程干完活要等到下一条输入消息才上屏
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
                translateAndDispatch(win, onEvent)
                // 事件处理可能在 UI 队列里排了新任务（输入 -> 状态变更 -> 重组）
                flushMainUIDispatcher()
                if (!running) break

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
            setWindowsImeCaretProvider(null)
            layer.detach()
            win.close()
        }
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
                        deltaX = if (raw.b == 1) raw.a / 120 else 0,
                        deltaY = if (raw.b == 1) 0 else raw.a / 120,
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
     * Maximize or restore the window.
     */
    fun toggleMaximized() {
        val win = win32Window ?: return
        if (win.isMaximized) {
            win.restore()
            isMaximized = false
        } else {
            win.maximize()
            isMaximized = true
        }
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

    /**
     * Begin window move (for title bar drag).
     */
    fun beginMove() {
        win32Window?.beginMove()
    }

    /**
     * Destroy the window and cleanup resources.
     */
    fun destroy() {
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

package com.composekn.windows

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.Win32Event
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.win32Log
import org.jetbrains.skiko.initWindowsMainThread
import com.composekn.windows.internal.MOD_ALT
import com.composekn.windows.internal.MOD_CTRL
import com.composekn.windows.internal.MOD_SHIFT

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

    /** 底层 Win32 窗口（未 attach 时为 null）。 */
    val nativeWindow: Win32Window? get() = win32Window

    /** 已渲染帧数（诊断用）。 */
    var frameCount: Int = 0
        private set

    /**
     * 每帧回调（诊断/自动化测试用），参数是当前帧号。
     * 在 `renderImmediately()` 之后、状态同步之前调用。
     */
    var frameHook: ((Int) -> Unit)? = null

    private var win32Window: Win32Window? = null

    /**
     * Run the window with event handling.
     */
    fun run(onEvent: (WindowsEvent) -> Unit) {
        win32Log("run: enter")
        initWindowsMainThread()
        win32Log("run: create Win32 window ${width}x$height")
        val win = Win32Window(title, width, height)
        win32Window = win
        println("WindowsComposeWindow: created window '$title' (${win.width}x${win.height})")
        win32Log("run: window created ok ${win.width}x${win.height}")

        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before WindowsComposeWindow.run()"
        }
        win32Log("run: attachTo layer")
        layer.attachTo(win)
        win32Log("run: layer attached; entering message loop")

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

        var frames = 0
        var running = true
        try {
            while (running) {
                running = win.pump()
                flushMainUIDispatcher()
                translateAndDispatch(win, onEvent)
                layer.renderImmediately()
                if (frames == 0) win32Log("run: first renderImmediately ok")
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
                        deltaX = 0,
                        deltaY = raw.a / 120,
                        isShiftPressed = raw.modifiers and MOD_SHIFT != 0u,
                        isCtrlPressed = raw.modifiers and MOD_CTRL != 0u,
                        isAltPressed = raw.modifiers and MOD_ALT != 0u,
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

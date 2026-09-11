package com.composekn.windows

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.Win32Event
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initWindowsMainThread

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

    private var win32Window: Win32Window? = null

    /**
     * Run the window with event handling.
     */
    fun run(onEvent: (WindowsEvent) -> Unit) {
        initWindowsMainThread()
        val win = Win32Window(title, width, height)
        win32Window = win
        println("WindowsComposeWindow: created window '$title' (${win.width}x${win.height})")

        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before WindowsComposeWindow.run()"
        }
        layer.attachTo(win)

        var running = true
        try {
            while (running) {
                running = win.pump()
                flushMainUIDispatcher()
                translateAndDispatch(win, onEvent)
                layer.renderImmediately()
                isMaximized = win.isMaximized
                isMinimized = win.isMinimized
            }
        } finally {
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
                        isShiftPressed = raw.modifiers and 1u != 0u,
                        isCtrlPressed = raw.modifiers and 2u != 0u,
                        isAltPressed = raw.modifiers and 4u != 0u,
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
                        isShiftPressed = raw.modifiers and 1u != 0u,
                        isCtrlPressed = raw.modifiers and 1u != 0u,
                        isAltPressed = raw.modifiers and 4u != 0u,
                    )
                )
                Win32Event.MOUSE_WHEEL -> onEvent(
                    WindowsEvent.MouseWheelEvent(
                        x = raw.x.toInt(),
                        y = raw.y.toInt(),
                        deltaX = 0,
                        deltaY = raw.a / 120,
                        isShiftPressed = raw.modifiers and 1u != 0u,
                        isCtrlPressed = raw.modifiers and 2u != 0u,
                        isAltPressed = raw.modifiers and 4u != 0u,
                    )
                )
                Win32Event.KEY -> onEvent(
                    WindowsEvent.KeyEvent(
                        virtualKeyCode = raw.button.toInt(),
                        scanCode = raw.b,
                        isKeyDown = raw.state == 1u,
                        isShiftPressed = raw.modifiers and 1u != 0u,
                        isCtrlPressed = raw.modifiers and 2u != 0u,
                        isAltPressed = raw.modifiers and 4u != 0u,
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

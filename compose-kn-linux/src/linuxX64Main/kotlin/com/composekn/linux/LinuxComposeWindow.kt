package com.composekn.linux

import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.WaylandEvent
import org.jetbrains.skiko.WaylandEventType
import org.jetbrains.skiko.WaylandWindow
import org.jetbrains.skiko.flushMainUIDispatcher
import org.jetbrains.skiko.initLinuxMainThread

/**
 * Wayland window + SkiaLayer with vsync frame callbacks and input events.
 */
class LinuxComposeWindow(
    private val title: String,
    private val width: Int = 960,
    private val height: Int = 640,
) {
    val window = WaylandWindow(title, width, height)
    val layer = SkiaLayer()

    fun run(onEvent: (WaylandEvent) -> Unit) {
        initLinuxMainThread()
        layer.attachTo(window)
        check(layer.renderDelegate != null) {
            "SkiaLayer.renderDelegate must be set before LinuxComposeWindow.run()"
        }
        window.onEvent = { event ->
            when (event.type) {
                WaylandEventType.Frame -> layer.renderImmediately()
                else -> onEvent(event)
            }
        }
        layer.needRender()
        var running = true
        while (running) {
            running = window.poll()
            flushMainUIDispatcher()
            if (window.consumeResized()) {
                layer.needRender()
            }
        }
        layer.detach()
        window.destroy()
    }

    /** Legacy Skia-only loop without Compose scene. */
    fun runSkiaOnly(drawFrame: (Canvas, Int, Int) -> Unit) {
        layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                drawFrame(canvas, width, height)
            }
        }
        run(onEvent = {})
    }
}

fun drawHelloFrame(canvas: Canvas, width: Int, height: Int) {
    val paint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.makeRGB(30, 120, 220) }
    canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(40f, 40f, width.toFloat() - 80f, height.toFloat() - 80f), paint)
    val font = org.jetbrains.skia.Font(null, 28f)
    val textPaint = org.jetbrains.skia.Paint().apply { color = org.jetbrains.skia.Color.WHITE }
    canvas.drawString("ComposeKN / Wayland", 60f, 100f, font, textPaint)
}

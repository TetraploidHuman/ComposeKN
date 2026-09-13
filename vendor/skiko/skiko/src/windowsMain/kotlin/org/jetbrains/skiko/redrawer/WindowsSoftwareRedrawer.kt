package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.context.WindowsSoftwareContextHandler
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.setWindowsRenderTick
import org.jetbrains.skiko.initWindowsMainThread

internal class WindowsSoftwareRedrawer(
    private val skiaLayer: SkiaLayer,
    private val window: Win32Window,
) : Redrawer {
    private var disposed = false
    private val contextHandler = WindowsSoftwareContextHandler(skiaLayer)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    init {
        initWindowsMainThread()
        // 缩放期间（模态循环）也能逐帧重组：见 setWindowsRenderTick 注释
        setWindowsRenderTick { renderImmediately() }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        setWindowsRenderTick(null)
        contextHandler.dispose()
    }

    override fun syncBounds() = Unit

    override fun update(nanoTime: Long) {
        skiaLayer.update(nanoTime)
    }

    override fun needRender(throttledToVsync: Boolean) {
        // Windows app loop renders every iteration; a repaint request may be
        // satisfied on the next renderImmediately() call.
    }

    override fun renderImmediately() {
        if (disposed) return
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        update(currentNanoTime())
        skiaLayer.inDrawScope {
            contextHandler.draw()
        }
    }

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

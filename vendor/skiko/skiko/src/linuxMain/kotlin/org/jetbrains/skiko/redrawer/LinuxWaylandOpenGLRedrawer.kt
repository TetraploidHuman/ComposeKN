package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.composekn_window_make_current
import org.jetbrains.skiko.composekn_window_swap_buffers
import org.jetbrains.skiko.WaylandWindow
import org.jetbrains.skiko.context.LinuxWaylandOpenGLContextHandler
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.isLinuxMainThread
import org.jetbrains.skiko.MainUIDispatcher
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Runnable

internal class LinuxWaylandOpenGLRedrawer(
    private val skiaLayer: SkiaLayer,
    private val window: WaylandWindow,
) : Redrawer {
    private var disposed = false
    private val contextHandler = LinuxWaylandOpenGLContextHandler(skiaLayer)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    init {
        // EGL context is created after the compositor sends the first xdg configure.
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        composekn_window_make_current(window.nativeHandle)
        contextHandler.dispose()
    }

    override fun syncBounds() = Unit

    override fun update(nanoTime: Long) {
        skiaLayer.update(nanoTime)
    }

    override fun needRender(throttledToVsync: Boolean) {
        if (disposed) return
        if (isLinuxMainThread()) {
            window.requestFrame()
            return
        }
        MainUIDispatcher.dispatch(EmptyCoroutineContext, Runnable {
            if (!disposed) {
                window.requestFrame()
            }
        })
    }

    override fun renderImmediately() {
        if (disposed) return
        if (window.width <= 0 || window.height <= 0) return

        if (window.consumeResized()) {
            syncBounds()
            skiaLayer.needRender()
        }
        composekn_window_make_current(window.nativeHandle)
        update(currentNanoTime())
        skiaLayer.inDrawScope {
            contextHandler.draw()
        }
        composekn_window_swap_buffers(window.nativeHandle)
    }

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

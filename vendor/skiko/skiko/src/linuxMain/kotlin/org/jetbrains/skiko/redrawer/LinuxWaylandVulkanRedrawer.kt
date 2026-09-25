@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.impl.Native
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.MainUIDispatcher
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.WaylandWindow
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.isLinuxMainThread
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Runnable

/**
 * Graphite + Vulkan on Wayland.
 *
 * C 侧（wayland_vulkan.cc）拥有 Vk swapchain 与 Graphite Context/Recorder；
 * 每帧 begin 拿到 SkCanvas*，Kotlin 把 picture 画上去，再 end（present）。
 *
 * Frame pacing follows [LinuxWaylandOpenGLRedrawer]: requestFrame + renderImmediately
 * from compositor FRAME events (not a Win32-style render loop).
 *
 * 构造失败抛 [RenderException]，由 [SkiaLayer] 回退 GLES。
 */
internal class LinuxWaylandVulkanRedrawer(
    private val skiaLayer: SkiaLayer,
    private val window: WaylandWindow,
) : Redrawer {
    private var disposed = false

    override val renderInfo: String
        get() = "GraphicsApi: ${GraphicsApi.VULKAN}\nPresentation: graphite(vulkan/wayland)\n"

    init {
        if (!window.vkCreate()) {
            throw RenderException("Cannot create Graphite/Vulkan context on Wayland")
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        window.vkDestroy()
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

        update(currentNanoTime())
        if (disposed) return

        val w = (window.width * skiaLayer.contentScale).toInt().coerceIn(1, 16384)
        val h = (window.height * skiaLayer.contentScale).toInt().coerceIn(1, 16384)
        val canvasPtr = window.vkBeginFrame(w, h)
        if (canvasPtr == Native.NullPointer) {
            throw RenderException("vk_begin_frame returned null")
        }
        val canvas = Canvas(canvasPtr, managed = false, _owner = Unit)
        skiaLayer.inDrawScope {
            canvas.clear(Color.TRANSPARENT)
            skiaLayer.draw(canvas)
        }
        if (disposed) return
        if (!window.vkEndFrame()) {
            throw RenderException("vk_end_frame failed")
        }
        // 对齐 GLES swap_buffers：present 后再要一帧，驱动 vsync / FrameRecomposer。
        if (!disposed) {
            window.requestFrame()
        }
    }

    override fun isTransparentBackgroundSupported(): Boolean = false
}

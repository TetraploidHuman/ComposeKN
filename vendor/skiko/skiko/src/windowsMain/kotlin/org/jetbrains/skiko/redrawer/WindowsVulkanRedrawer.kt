@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.impl.Native
import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.win32Log

/**
 * Graphite + Vulkan 后端。
 *
 * C 侧（win32_vulkan.cc）拥有 Vk swapchain 与 Graphite Context/Recorder；
 * 每帧 begin 拿到 SkCanvas*，Kotlin 把 picture 画上去，再 end（present）。
 *
 * 构造失败抛 [RenderException]，由 [SkiaLayer] 回退 GL / 软件路径。
 */
internal class WindowsVulkanRedrawer(
    skiaLayer: SkiaLayer,
    window: Win32Window,
) : WindowsRenderLoopRedrawer(skiaLayer, window) {
    override val renderInfo: String
        get() = "GraphicsApi: ${GraphicsApi.VULKAN}\nPresentation: graphite(vulkan)\n"
    override val presentationMode: String get() = "graphite(vulkan)"

    init {
        if (!window.vkCreate()) {
            throw RenderException("Cannot create Graphite/Vulkan context on Windows (mingw)")
        }
        installResizeTick()
        win32Log("vkredrawer: Graphite/Vulkan 后端就绪")
    }

    override fun renderOneFrame(): Long {
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
        val t0 = currentNanoTime()
        if (!window.vkEndFrame()) {
            throw RenderException("vk_end_frame failed")
        }
        return currentNanoTime() - t0
    }

    override fun disposeBackend() {
        window.vkDestroy()
    }

    override fun isTransparentBackgroundSupported(): Boolean = false
}

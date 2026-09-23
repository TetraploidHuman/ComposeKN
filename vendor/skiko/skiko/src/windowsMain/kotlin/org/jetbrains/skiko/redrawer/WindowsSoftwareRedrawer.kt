@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.context.WindowsSoftwareContextHandler
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.win32Log

/**
 * 软件（CPU raster + GDI）后端：对齐上游 skiko 的 SOFTWARE_FAST 路径。
 *
 * 呈现细节见 [WindowsSoftwareContextHandler]（Skia 用 `Surface.makeRasterDirect`
 * 直接画进 GDI 要上传的那块内存，零拷贝）。
 */
internal class WindowsSoftwareRedrawer(
    skiaLayer: SkiaLayer,
    window: Win32Window,
) : WindowsRenderLoopRedrawer(skiaLayer, window) {
    private val contextHandler = WindowsSoftwareContextHandler(skiaLayer)
    override val renderInfo: String get() = contextHandler.rendererInfo()
    override val presentationMode: String get() = contextHandler.presentationMode

    init {
        installResizeTick()
    }

    override fun renderOneFrame(): Long {
        skiaLayer.inDrawScope {
            contextHandler.draw()
        }
        // 软件路径的 present 发生在 contextHandler.flush() 里（GDI blit），
        // 由它自己记时间；这里把它报出来，好让 profile 行拆出 present。
        return contextHandler.lastBlitNanos
    }

    override fun disposeBackend() {
        contextHandler.dispose()
    }

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * Software Skia rendering: a BGRA raster surface rendered by the Compose scene
 * and blitted to the Win32 window's client area via the C bridge
 * (StretchDIBits). No GPU/DirectX context is required.
 */
internal class WindowsSoftwareContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var loggedW = -1
    private var loggedH = -1

    /**
     * raster surface 像素的直接视图（[Surface.peekPixels] 填写，**不拷贝**）。
     *
     * 以前每帧都是
     *   `Bitmap()` + `allocPixels(w*h*4)` + `surface.readPixels(bitmap)` +
     *   `bitmap.readPixels()`（再拷成一个新 ByteArray）
     * ——「每帧两次全窗口拷贝 + 两次 MB 级分配」（1100x760 时约 3.3MB × 2）。
     * 现在直接把 surface 的像素指针交给 GDI blit：Kotlin 侧零拷贝。
     *
     * 布局是同一个：N32 premul（小端 = BGRA）正是 32bpp BI_RGB 的 DIB 布局。
     */
    private val pixmap = Pixmap()

    override fun initContext(): Boolean = true // raster needs no GPU context

    private fun sizedInfo(w: Int, h: Int): ImageInfo =
        ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL, ColorSpace.sRGB)

    private fun present() {
        val w32 = layer.component as? Win32Window ?: return
        val win = w32.native ?: return
        val s = surface ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        // peekPixels 只对 raster surface 成功；失败说明状态异常 —— 这一帧不送出去
        // （宁可留着上一帧，也不要送垃圾像素）。
        if (!s.peekPixels(pixmap)) {
            win32Log("swctx.present: peekPixels 失败（surface=${s.width}x${s.height}）")
            return
        }
        if (surfaceWidth != loggedW || surfaceHeight != loggedH) {
            loggedW = surfaceWidth
            loggedH = surfaceHeight
            win32Log(
                "swctx.present: surface=${surfaceWidth}x$surfaceHeight " +
                    "window=${w32.width}x${w32.height} rowBytes=${pixmap.rowBytes}"
            )
        }
        composekn_win32_present(
            win,
            pixmap.addr,
            surfaceWidth,
            surfaceHeight,
            pixmap.rowBytesAsPixels,
        )
    }

    override fun flush(scope: LayerDrawScope) {
        present()
    }

    override fun disposeCanvas() {
        surface?.close()
        surface = null
        canvas = null
        pixmap.reset()
    }

    private fun isSizeChanged(w: Int, h: Int): Boolean =
        w != surfaceWidth || h != surfaceHeight

    override fun LayerDrawScope.initCanvas() {
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return

        if (isSizeChanged(w, h)) {
            disposeCanvas()
            val info = sizedInfo(w, h)
            surface = Surface.makeRaster(info, info.minRowBytes, null)
                ?: throw RenderException("Cannot create Windows raster surface ${w}x$h")
            canvas = surface?.canvas ?: error("Could not obtain Canvas from Surface")
            // 必须记录尺寸：present() 依赖 surfaceWidth/Height，漏掉这里会让
            // isSizeChanged() 恒为 true，每帧都白白重建一次 surface（窗口全白）。
            surfaceWidth = w
            surfaceHeight = h
        }
    }

}

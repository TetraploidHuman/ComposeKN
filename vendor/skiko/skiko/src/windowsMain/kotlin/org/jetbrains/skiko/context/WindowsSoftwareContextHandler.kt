@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * Software Skia rendering for the Win32 (mingw) target.
 *
 * 与上游 skiko 的 **SOFTWARE_FAST** 路径对齐（对照实现：
 * `awtMain/cpp/windows/SoftwareRedrawer.cc` + `context/DirectSoftwareContextHandler.kt`）：
 *
 *  1. redrawer（C 桥）持有一块「present buffer」内存（紧密 BGRA）；
 *  2. Skia 用 `Surface.makeRasterDirect` **直接画进那块内存**（上游用
 *     `SkSurfaces::WrapPixels`，语义相同）；
 *  3. present 时 GDI 从同一块内存 `StretchDIBits` 上传。
 *
 * 全程零拷贝：既没有「Skia surface -> 临时 buffer」的整窗 memcpy，也没有 MB 级分配。
 *
 * 如果拿不到 present buffer（`composekn_win32_backbuffer_pixels` 返回 null），会回退到
 * 旧的 `Surface.makeRaster` + `peekPixels` + 整窗拷贝路径，保证仍然能出画面。
 */
internal class WindowsSoftwareContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var loggedW = -1
    private var loggedH = -1

    /**
     * 回退路径用：raster surface 像素的直接视图（[Surface.peekPixels] 填写，不拷贝）。
     * 只有拿不到 present buffer 时才会用到。
     */
    private val pixmap = Pixmap()

    /** 当前 surface 是否是「直接画进 present buffer」的（零拷贝模式）。 */
    private var directSurface = false

    /**
     * 诊断用：上一次 present 里「取像素指针」（peekPixels / 直接模式下的 0）与
     * 「GDI blit」各自花了多少纳秒。
     *
     * 拆分它是为了回答「帧时间花在 Compose 光栅化还是窗口上传上」——两者优化手段不同
     * （前者要少画/脏矩形，后者要少拷一次）。
     */
    internal var lastPresentNanos: Long = 0L
        private set
    internal var lastBlitNanos: Long = 0L
        private set

    /** 呈现模式（写进性能日志，真机上用来确认零拷贝路径是否生效）。 */
    internal val presentationMode: String
        get() = if (directSurface) "direct(wrap-pixels, zero-copy)" else "copy(peekPixels+memcpy)"

    override fun initContext(): Boolean = true // raster needs no GPU context

    private fun sizedInfo(w: Int, h: Int): ImageInfo =
        ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL, ColorSpace.sRGB)

    /** 回退路径：把 surface 读出来，按行拷进 present buffer 再上传。 */
    private fun presentViaCopy(w32: Win32Window, win: kotlinx.cinterop.COpaquePointer) {
        val s = surface ?: return
        lastPresentNanos = 0L
        lastBlitNanos = 0L
        val t0 = currentNanoTime()
        // peekPixels 只对 raster surface 成功；失败说明状态异常 —— 这一帧不送出去
        // （宁可留着上一帧，也不要送垃圾像素）。
        if (!s.peekPixels(pixmap)) {
            win32Log("swctx.present: peekPixels 失败（surface=${s.width}x${s.height}）")
            return
        }
        val t1 = currentNanoTime()
        if (surfaceWidth != loggedW || surfaceHeight != loggedH) {
            loggedW = surfaceWidth
            loggedH = surfaceHeight
            win32Log(
                "swctx.present: surface=${surfaceWidth}x$surfaceHeight " +
                    "window=${w32.width}x${w32.height} rowBytes=${pixmap.rowBytes} (回退：拷贝路径)"
            )
        }
        composekn_win32_present(
            win,
            pixmap.addr,
            surfaceWidth,
            surfaceHeight,
            pixmap.rowBytesAsPixels,
        )
        val t2 = currentNanoTime()
        lastPresentNanos = t1 - t0
        lastBlitNanos = t2 - t1
    }

    override fun flush(scope: LayerDrawScope) {
        val w32 = layer.component as? Win32Window ?: return
        val win = w32.native ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        if (!directSurface) {
            presentViaCopy(w32, win)
            return
        }
        // 零拷贝：Skia 已经把这一帧画进 present buffer，直接让 GDI 上传同一块内存。
        lastPresentNanos = 0L
        val t0 = currentNanoTime()
        if (surfaceWidth != loggedW || surfaceHeight != loggedH) {
            loggedW = surfaceWidth
            loggedH = surfaceHeight
            win32Log(
                "swctx.present: surface=${surfaceWidth}x$surfaceHeight " +
                    "window=${w32.width}x${w32.height} 直接画入 present buffer（零拷贝）"
            )
        }
        w32.presentBuffer()
        lastBlitNanos = currentNanoTime() - t0
    }

    override fun disposeCanvas() {
        surface?.close()
        surface = null
        canvas = null
        directSurface = false
        pixmap.reset()
    }

    private fun isSizeChanged(w: Int, h: Int): Boolean =
        w != surfaceWidth || h != surfaceHeight

    override fun LayerDrawScope.initCanvas() {
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return
        // 已经有同样大小的 surface 就直接复用（此前漏记尺寸会导致每帧重建、窗口全白）。
        if (!isSizeChanged(w, h) && surface != null) return

        // 先关掉旧 surface：它可能正包着 C 侧那块即将被重分配的内存。
        disposeCanvas()

        val info = sizedInfo(w, h)
        val win = layer.component as? Win32Window
        val props = SurfaceProps(pixelGeometry = layer.pixelGeometry)

        // 首选：让 Skia 直接画进 present buffer（上游 SOFTWARE_FAST 的做法）。
        val direct = win?.backbufferPixels(w, h)
        if (direct != null) {
            surface = Surface.makeRasterDirect(info, direct, w * 4, props)
            directSurface = true
        } else {
            win32Log("swctx.initCanvas: 拿不到 present buffer，回退到拷贝路径")
            surface = Surface.makeRaster(info, info.minRowBytes, props)
                ?: throw RenderException("Cannot create Windows raster surface ${w}x$h")
            directSurface = false
        }
        canvas = surface?.canvas ?: error("Could not obtain Canvas from Surface")
        surfaceWidth = w
        surfaceHeight = h
    }

}

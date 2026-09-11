@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.context

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.*
import org.jetbrains.skiko.*
import org.jetbrains.skiko.composekn_win32_present

/**
 * Software Skia rendering: a BGRA raster surface rendered by the Compose scene
 * and blitted to the Win32 window's client area via the C bridge
 * (StretchDIBits). No GPU/DirectX context is required.
 */
internal class WindowsSoftwareContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pixels = ByteArray(0)

    override fun initContext(): Boolean = true // raster needs no GPU context

    private fun sizedInfo(w: Int, h: Int): ImageInfo =
        ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL, ColorSpace.sRGB)

    private fun present() {
        val win = (layer.component as? Win32Window)?.native ?: return
        if (pixels.isEmpty()) return
        pixels.usePinned { pinned ->
            composekn_win32_present(
                win,
                pinned.addressOf(0),
                surfaceWidth,
                surfaceHeight,
                surfaceWidth, // stride in pixels (4 bytes/pixel)
            )
        }
    }

    private fun grabPixels(): Boolean {
        val s = surface ?: return false
        val w = surfaceWidth
        val h = surfaceHeight
        if (w <= 0 || h <= 0) return false
        val bitmap = Bitmap()
        val ok = bitmap.allocPixels(sizedInfo(w, h)) && s.readPixels(bitmap, 0, 0)
        if (ok) {
            val bytes = bitmap.readPixels()
            if (bytes != null) pixels = bytes
        }
        bitmap.close()
        return ok
    }

    override fun flush(scope: LayerDrawScope) {
        grabPixels()
        present()
    }

    override fun disposeCanvas() {
        surface?.close()
        surface = null
        canvas = null
        pixels = ByteArray(0)
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
        }
    }

}

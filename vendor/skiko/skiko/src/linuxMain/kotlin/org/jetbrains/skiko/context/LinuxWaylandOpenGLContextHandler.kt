package org.jetbrains.skiko.context

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.composekn_create_egl_direct_context
import org.jetbrains.skiko.composekn_gl_get_draw_framebuffer_binding
import org.jetbrains.skiko.composekn_gl_viewport

internal class LinuxWaylandOpenGLContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    override fun initContext(): Boolean {
        if (context != null) return true
        try {
            val ptr = composekn_create_egl_direct_context()
            if (ptr == NullPointer) {
                println(
                    "Failed to create Skia EGL context on Wayland (see composekn: lines above). " +
                        "On NixOS use: ./scripts/run-linux-native.sh"
                )
                return false
            }
            context = DirectContext(ptr)
        } catch (e: Exception) {
            println("Failed to create Skia OpenGL context on Wayland: ${e.message}")
            return false
        }
        return true
    }

    private var currentWidth = 0
    private var currentHeight = 0

    private fun isSizeChanged(width: Int, height: Int): Boolean {
        if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            return true
        }
        return false
    }

    override fun LayerDrawScope.initCanvas() {
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return

        composekn_gl_viewport(w, h)

        if (isSizeChanged(w, h)) {
            disposeCanvas()
            val fbId = composekn_gl_get_draw_framebuffer_binding()
            renderTarget = BackendRenderTarget.makeGL(
                w,
                h,
                0,
                8,
                fbId,
                FramebufferFormat.GR_GL_RGBA8,
            )
            surface = Surface.makeFromBackendRenderTarget(
                context!!,
                renderTarget!!,
                SurfaceOrigin.BOTTOM_LEFT,
                SurfaceColorFormat.RGBA_8888,
                ColorSpace.sRGB,
                SurfaceProps(pixelGeometry = layer.pixelGeometry),
            ) ?: throw RenderException("Cannot create Wayland GL surface (fb=$fbId ${w}x$h)")
            canvas = surface?.canvas ?: error("Could not obtain Canvas from Surface")
        }
    }

    override fun flush(scope: LayerDrawScope) {
        super.flush(scope)
        surface?.flushAndSubmit()
    }
}

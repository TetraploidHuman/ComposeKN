package org.jetbrains.skiko.context

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.FramebufferFormat
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skiko.LayerDrawScope
import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.composekn_gl_get_draw_framebuffer_binding
import org.jetbrains.skiko.composekn_gl_viewport

internal class LinuxWaylandOpenGLContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    override fun initContext(): Boolean {
        if (context != null) return true
        context = LinuxSharedGpuContext.acquire() ?: run {
            println(
                "Failed to create Skia EGL context on Wayland (see composekn: lines above). " +
                    "On NixOS use: ./scripts/run-linux-native.sh",
            )
            return false
        }
        return true
    }

    override fun dispose() {
        disposeCanvas()
        if (context != null) {
            LinuxSharedGpuContext.release()
            context = null
        }
    }

    override fun LayerDrawScope.initCanvas() {
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return

        composekn_gl_viewport(w, h)

        // 共享 EGLContext：makeCurrent 切 surface 后默认 FBO 会变，必须每帧重绑。
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

    override fun flush(scope: LayerDrawScope) {
        super.flush(scope)
        surface?.flushAndSubmit()
    }
}

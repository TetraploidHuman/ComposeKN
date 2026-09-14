@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.context

import org.jetbrains.skia.*
import org.jetbrains.skiko.*

/**
 * Windows GPU 后端（OpenGL / Ganesh）的 Compose 上下文处理器。
 *
 * 与上游同构：照 `linuxMain/context/LinuxWaylandOpenGLContextHandler.kt` 的形状写，
 * 只把 EGL 换成 WGL（`Win32Window.gl*`，实现见 cpp/win32/win32_gl.cc）。
 *
 * 关键点：
 *  - Skia 的 GPU 上下文用 K/N binding 的 [DirectContext.makeGL]（内部走
 *    `GrDirectContexts::MakeGL(GrGLInterfaces::MakeWin())`），前提是**已经有 current
 *    的 WGL 上下文** —— 由 `WindowsGLRedrawer` 在渲染前 make current，本类只负责建
 *    Skia 侧上下文；
 *  - surface 直接包在**默认帧缓冲（FBO 0，双缓冲）**上，present 就是 `SwapBuffers`；
 *  - 一切失败都抛 [RenderException]/返回 false，让上层回退到软件路径（虚拟机/远程桌面/
 *    只有 GL 1.1 的老驱动）。
 */
internal class WindowsGLContextHandler(layer: SkiaLayer) : ContextHandler(layer, layer::draw) {
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var loggedSize = false

    internal val presentationMode: String get() = "opengl(wgl swap-buffers)"

    private val window: Win32Window?
        get() = layer.component as? Win32Window

    override fun initContext(): Boolean {
        if (context != null) return true
        val win = window ?: return false
        // WGL 上下文由 redrawer 建好并 make current；这里只确认它存在。
        if (!win.glMakeCurrent()) {
            win32Log("glctx.initContext: 没有可用的 current WGL 上下文")
            return false
        }
        return try {
            context = DirectContext.makeGL()
            win32Log("glctx.initContext: DirectContext.makeGL OK")
            true
        } catch (e: Exception) {
            win32Log("glctx.initContext: DirectContext.makeGL 失败: ${e.message}")
            false
        }
    }

    private fun isSizeChanged(w: Int, h: Int): Boolean =
        w != surfaceWidth || h != surfaceHeight

    override fun LayerDrawScope.initCanvas() {
        val win = window ?: return
        val w = scaledLayerWidth
        val h = scaledLayerHeight
        if (w <= 0 || h <= 0) return

        win.glViewport(w, h)
        if (!isSizeChanged(w, h)) return

        disposeCanvas()
        val fbId = win.glGetDrawFramebufferBinding()
        val target = BackendRenderTarget.makeGL(
            w,
            h,
            0,      // 不做 MSAA
            8,      // stencil bits（与 pixel format 的 24/8 对应）
            fbId,
            FramebufferFormat.GR_GL_RGBA8,
        )
        renderTarget = target
        surface = Surface.makeFromBackendRenderTarget(
            context!!,
            target,
            SurfaceOrigin.BOTTOM_LEFT,
            SurfaceColorFormat.RGBA_8888,
            ColorSpace.sRGB,
            SurfaceProps(pixelGeometry = layer.pixelGeometry),
        ) ?: throw RenderException("Cannot create Windows GL surface (fb=$fbId ${w}x$h)")
        canvas = surface?.canvas ?: error("Could not obtain Canvas from Surface")
        surfaceWidth = w
        surfaceHeight = h
        if (!loggedSize) {
            loggedSize = true
            win32Log("glctx.initCanvas: GL surface ${w}x$h fb=$fbId")
        }
    }

    override fun flush(scope: LayerDrawScope) {
        super.flush(scope)          // DirectContext.flush()
        surface?.flushAndSubmit()
    }

    override fun disposeCanvas() {
        surface?.close()
        surface = null
        renderTarget?.close()
        renderTarget = null
        canvas = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    override fun rendererInfo(): String =
        super.rendererInfo() + "Presentation: $presentationMode\n"
}

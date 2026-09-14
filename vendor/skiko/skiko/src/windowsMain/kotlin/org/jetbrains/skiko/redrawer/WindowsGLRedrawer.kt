@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.RenderException
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.context.WindowsGLContextHandler
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.win32Log

/**
 * OpenGL / Ganesh 后端（GPU）—— 对齐上游 `linuxMain/redrawer/LinuxWaylandOpenGLRedrawer.kt`：
 * 渲染前 `make current`，present 交给 `SwapBuffers`（EGL 那边是 `eglSwapBuffers`）。
 *
 * 构造时就尝试建 WGL 上下文：失败会抛 [RenderException]，由 `SkiaLayer` 捕获后
 * **自动回退到软件路径**（虚拟机 / 远程桌面 / 只有 GL 1.1 的老驱动会走到这里）。
 */
internal class WindowsGLRedrawer(
    skiaLayer: SkiaLayer,
    window: Win32Window,
) : WindowsRenderLoopRedrawer(skiaLayer, window) {
    private val contextHandler = WindowsGLContextHandler(skiaLayer)
    override val renderInfo: String get() = contextHandler.rendererInfo()
    override val presentationMode: String get() = contextHandler.presentationMode

    init {
        if (!window.glCreate()) {
            throw RenderException("Cannot create WGL context on Windows (mingw)")
        }
        window.glMakeCurrent()
        // WGL_EXT_swap_interval：present 跟垂直同步对齐（上游 EGL 路径同样设置）。
        window.glSetSwapInterval(1)
        // renderInfo 是多行的；日志一行一条，所以把换行换成 ';' 再打。
        // （换行符用下面的 NEWLINE_CHAR 常量传给 replace —— 写成字符字面量放在
        //   字符串模板里时，Kotlin 词法会报 "Too many characters in a character literal"。）
        val info = renderInfo.trim().replace(NEWLINE_CHAR, ';')
        win32Log("glredrawer: WGL 后端就绪（$info）")
    }

    override fun renderOneFrame(): Long {
        window.glMakeCurrent()
        skiaLayer.inDrawScope {
            contextHandler.draw()
        }
        val t0 = currentNanoTime()
        window.glSwapBuffers()
        return currentNanoTime() - t0
    }

    override fun disposeBackend() {
        // 释放 GL surface/context 时必须让 WGL 上下文 current（与上游一致）。
        window.glMakeCurrent()
        contextHandler.dispose()
        window.glDestroy()
    }

    /** GL 路径暂不支持透明背景（软件路径保留原行为）。 */
    override fun isTransparentBackgroundSupported(): Boolean = false
}

private const val NEWLINE_CHAR = '\n'

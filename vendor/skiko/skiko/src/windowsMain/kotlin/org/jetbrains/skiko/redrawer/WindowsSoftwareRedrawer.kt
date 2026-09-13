@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.context.WindowsSoftwareContextHandler
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.setWindowsRenderTick
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.win32Log
import kotlin.concurrent.Volatile

internal class WindowsSoftwareRedrawer(
    private val skiaLayer: SkiaLayer,
    private val window: Win32Window,
) : Redrawer {
    private var disposed = false
    private val contextHandler = WindowsSoftwareContextHandler(skiaLayer)
    override val renderInfo: String get() = contextHandler.rendererInfo()

    private var profileFrames = 0
    private var profileUpdateNanos = 0L
    private var profileDrawNanos = 0L
    private var profilePresentNanos = 0L

    /**
     * 「内容变了，需要重绘一帧」。
     *
     * 这是 Windows 上**唯一**的渲染触发源：Compose 的重组/布局/绘制失效
     * （`CanvasLayersComposeScene.invalidateLayout/invalidateDraw`）、帧时钟 awaiter
     * （`FrameRecomposer.onNewAwaiters` / `performFrame`）以及显式 `layer.needRender()`
     * 最终都会走到这里。
     *
     * 以前它是**空实现**，而窗口循环无条件每轮都 `renderImmediately()` —— 结果是一个
     * 完全静止的窗口也会把一颗核心跑到 100%（重绘 ~100+ fps，全部是白工）。
     */
    @Volatile
    var renderRequested: Boolean = false
        private set

    /** 请求渲染时回调（窗口循环把它接到「唤醒阻塞的消息泵」上）。 */
    var onRenderRequest: (() -> Unit)? = null

    init {
        initWindowsMainThread()
        // 缩放期间（模态循环）也能逐帧重组：见 setWindowsRenderTick 注释
        setWindowsRenderTick { renderImmediately() }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        setWindowsRenderTick(null)
        contextHandler.dispose()
    }

    override fun syncBounds() = Unit

    override fun update(nanoTime: Long) {
        skiaLayer.update(nanoTime)
    }

    override fun needRender(throttledToVsync: Boolean) {
        renderRequested = true
        onRenderRequest?.invoke()
    }

    /**
     * 有请求时才画一帧；返回是否真的画了。
     *
     * `renderImmediately()` 保持「无条件渲染」的语义（缩放 tick / WM_PAINT / 首帧要用），
     * 窗口循环则走这个「按需」版本。
     */
    fun renderIfRequested(): Boolean {
        if (!renderRequested) return false
        // 先清标记再渲染：渲染过程中新产生的请求（动画下一帧）会被保留下来，
        // 循环下一轮继续画。
        renderRequested = false
        renderFrame()
        return true
    }

    override fun renderImmediately() {
        renderRequested = false
        renderFrame()
    }

    private fun renderFrame() {
        if (disposed) return
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        // 每帧拆成两段，用来判断「时间花在 Compose 光栅化还是窗口上传上」：
        //   update —— Compose 场景渲染（重组/布局/绘制 -> 录制 Picture）
        //   draw   —— 回放 Picture 到 raster surface + present（GDI 上传）
        // 计时开销只有几次 QueryPerformanceCounter，始终打开，这样真机日志里
        // （composekn-startup.log）直接就有每帧耗时，不需要额外开关。
        val t0 = currentNanoTime()
        update(t0)
        val t1 = currentNanoTime()
        skiaLayer.inDrawScope {
            contextHandler.draw()
        }
        val t2 = currentNanoTime()
        profileFrames++
        profileUpdateNanos += t1 - t0
        profileDrawNanos += t2 - t1
        profilePresentNanos += contextHandler.lastBlitNanos
        if (profileFrames >= PROFILE_WINDOW_FRAMES) {
            val n = profileFrames.toDouble()
            val replayNanos = profileDrawNanos - profilePresentNanos
            val line = "profile: ${profileFrames} 帧  update=${fmtMs(profileUpdateNanos / n)}" +
                "  replay=${fmtMs(replayNanos.toDouble() / n)}" +
                "  present=${fmtMs(profilePresentNanos.toDouble() / n)}" +
                "  draw+present=${fmtMs(profileDrawNanos / n)}" +
                "  total=${fmtMs((profileUpdateNanos + profileDrawNanos) / n)}" +
                "  窗口=${w}x$h  呈现=${contextHandler.presentationMode}"
            println(line)
            win32Log(line)
            profileFrames = 0
            profileUpdateNanos = 0
            profileDrawNanos = 0
            profilePresentNanos = 0
        }
    }

    override fun isTransparentBackgroundSupported(): Boolean =
        defaultIsTransparentBackgroundSupported(skiaLayer)
}

private const val PROFILE_WINDOW_FRAMES = 120

private fun fmtMs(nanos: Double): String {
    val tenths = (nanos / 100_000.0 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}ms"
}

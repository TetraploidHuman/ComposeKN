@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko.redrawer

import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.currentNanoTime
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.setWindowsRenderTick
import org.jetbrains.skiko.win32Log
import kotlin.concurrent.Volatile

/**
 * Windows 两个后端（软件 raster / OpenGL）共用的「渲染循环」逻辑。
 *
 * 这里放的都是**与后端无关**的语义，和上游一致：
 *  - `needRender()` 是唯一的渲染触发源（Compose 失效 / 帧时钟 awaiter / 显式请求）；
 *  - 有请求才画一帧（`renderIfRequested`），没请求宿主就睡着（见 WindowsComposeWindow）；
 *  - `renderImmediately()` 保持「无条件渲染」语义（缩放 tick / WM_PAINT / 首帧用）；
 *  - 每帧耗时拆解（update / draw / present）始终记录并写日志。
 *
 * 后端相关的只有 [renderOneFrame]（画一帧 + present）与 [presentationMode]。
 */
internal abstract class WindowsRenderLoopRedrawer(
    protected val skiaLayer: SkiaLayer,
    protected val window: Win32Window,
) : Redrawer {
    private var disposed = false

    /** 「内容变了，需要重绘一帧」。 */
    @Volatile
    var renderRequested: Boolean = false
        private set

    /** 请求渲染时回调（窗口循环把它接到「唤醒阻塞的消息泵」上）。 */
    var onRenderRequest: (() -> Unit)? = null

    /** 当前呈现方式（写进性能日志，真机上用来确认走的是哪条后端）。 */
    abstract val presentationMode: String

    private var profileFrames = 0
    private var profileUpdateNanos = 0L
    private var profileDrawNanos = 0L
    private var profilePresentNanos = 0L

    init {
        initWindowsMainThread()
        // 缩放期间（模态循环）也能逐帧重组：见 setWindowsRenderTick 注释
        setWindowsRenderTick { renderImmediately() }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        setWindowsRenderTick(null)
        disposeBackend()
    }

    /** 释放后端资源（GL 上下文 / raster surface）。已在 renderTick 摘掉之后调用。 */
    protected abstract fun disposeBackend()

    override fun syncBounds() = Unit

    override fun update(nanoTime: Long) {
        skiaLayer.update(nanoTime)
    }

    override fun needRender(throttledToVsync: Boolean) {
        renderRequested = true
        onRenderRequest?.invoke()
    }

    fun hasRenderRequest(): Boolean = renderRequested

    /**
     * 有请求时才画一帧；返回是否真的画了。
     *
     * 先清标记再渲染：渲染过程中新产生的请求（动画下一帧）会被保留下来，
     * 循环下一轮继续画。
     */
    fun renderIfRequested(): Boolean {
        if (!renderRequested) return false
        renderRequested = false
        renderFrame()
        return true
    }

    override fun renderImmediately() {
        renderRequested = false
        renderFrame()
    }

    /**
     * 把一帧画到后端 surface 并 present，返回 **present 阶段**（上传/交换）的纳秒数。
     * 调用方保证：未 disposed、窗口尺寸 > 0。
     */
    protected abstract fun renderOneFrame(): Long

    private fun renderFrame() {
        if (disposed) return
        val w = window.width
        val h = window.height
        if (w <= 0 || h <= 0) return
        // 每帧拆成三段，始终记录（真机日志 composekn-startup.log 直接可读）：
        //   update —— Compose 场景渲染（重组/布局/绘制 -> 录制 Picture）
        //   draw   —— 回放 Picture 到 surface（软件路径 = CPU 光栅化；GL = GPU 提交）
        //   present—— 上传/交换（软件路径 = GDI blit；GL = SwapBuffers）
        val t0 = currentNanoTime()
        update(t0)
        val t1 = currentNanoTime()
        val presentNanos = renderOneFrame()
        val t2 = currentNanoTime()
        profileFrames++
        profileUpdateNanos += t1 - t0
        profileDrawNanos += t2 - t1
        profilePresentNanos += presentNanos
        if (profileFrames >= PROFILE_WINDOW_FRAMES) {
            val n = profileFrames.toDouble()
            val drawOnlyNanos = profileDrawNanos - profilePresentNanos
            val line = "profile: ${profileFrames} 帧  update=${fmtMs(profileUpdateNanos / n)}" +
                "  draw=${fmtMs(drawOnlyNanos.toDouble() / n)}" +
                "  present=${fmtMs(profilePresentNanos.toDouble() / n)}" +
                "  draw+present=${fmtMs(profileDrawNanos / n)}" +
                "  total=${fmtMs((profileUpdateNanos + profileDrawNanos) / n)}" +
                "  窗口=${w}x$h  呈现=$presentationMode"
            println(line)
            win32Log(line)
            profileFrames = 0
            profileUpdateNanos = 0
            profileDrawNanos = 0
            profilePresentNanos = 0
        }
    }
}

private const val PROFILE_WINDOW_FRAMES = 120

private fun fmtMs(nanos: Double): String {
    val tenths = (nanos / 100_000.0 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10}ms"
}

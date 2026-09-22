package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.redrawer.WindowsGLRedrawer
import org.jetbrains.skiko.redrawer.WindowsRenderLoopRedrawer
import org.jetbrains.skiko.redrawer.WindowsSoftwareRedrawer
import kotlinx.cinterop.toKString

/**
 * SkiaLayer for Kotlin/Native Windows (mingwX64): Win32 borderless window +
 * software raster rendering presented through GDI's StretchDIBits.
 */
actual open class SkiaLayer {
    /**
     * 渲染后端选择（与上游一致：OPENGL = GPU，SOFTWARE_* = 软件回退）。
     *
     * - [GraphicsApi.OPENGL]：WGL + Ganesh（GPU）。**创建失败会自动回退到软件路径**，
     *   并把这里改写成 [GraphicsApi.SOFTWARE_FAST]，所以读回来的值就是实际生效的后端。
     * - [GraphicsApi.SOFTWARE_FAST] / [GraphicsApi.SOFTWARE_COMPAT]：CPU raster + GDI
     *   （对齐上游 SOFTWARE_FAST 形状，见 WindowsSoftwareContextHandler）。
     *
     * 环境变量 `COMPOSEKN_RENDER_API=gl|software` 可强制指定（CI / 排查用）。
     */
    actual var renderApi: GraphicsApi = defaultWindowsRenderApi()
        set(value) {
            if (value != GraphicsApi.OPENGL &&
                value != GraphicsApi.SOFTWARE_FAST &&
                value != GraphicsApi.SOFTWARE_COMPAT
            ) {
                throw IllegalArgumentException("Unsupported GraphicsApi on Windows (mingw): $value")
            }
            field = value
        }

    actual val contentScale: Float
        get() = win32Window?.dpiScale ?: 1.0f

    actual var fullscreen: Boolean = false
        set(value) {
            if (value) throw IllegalArgumentException("fullscreen unsupported on Windows (mingw)")
            field = false
        }

    actual val component: Any?
        get() = win32Window

    actual var renderDelegate: SkikoRenderDelegate? = null

    internal var redrawer: Redrawer? = null
    private var win32Window: Win32Window? = null
    private var picture: PictureHolder? = null
    private val pictureRecorder = PictureRecorder()

    /**
     * Attach to an existing [Win32Window], or create one when [container] is a title string.
     */
    actual fun attachTo(container: Any) {
        check(win32Window == null) { "Already attached to a Win32 window" }
        SkikoNativeLinkage.ensureLinked()
        win32Window = when (container) {
            is Win32Window -> container
            is String -> Win32Window(container)
            else -> error("container must be Win32Window or window title String")
        }
        compositionWindowRegistry.add(win32Window!!)
        // 新 attach 的窗口视为最近活跃（剪贴板 / IME 全局回调优先找它）。
        lastActiveCompositionWindow = win32Window
        redrawer = createRedrawer(win32Window!!).apply {
            syncBounds()
            needRender()
        }
    }

    actual fun detach() {
        // 多窗口：detach 时必须把自己从剪贴板注册表摘掉，否则关窗后 firstOrNull
        // 仍指向已销毁的 HWND（见 compositionWindowRegistry）。
        win32Window?.let { attached ->
            compositionWindowRegistry.remove(attached)
            if (lastActiveCompositionWindow === attached) {
                lastActiveCompositionWindow =
                    compositionWindowRegistry.lastOrNull() as? Win32Window
            }
        }
        redrawer?.dispose()
        redrawer = null
        win32Window = null
        picture = null
    }

    actual fun needRender(throttledToVsync: Boolean) {
        redrawer?.needRender(throttledToVsync)
    }

    /**
     * 是否有人请求渲染（Compose 内容失效 / 动画帧 / 显式 [needRender]）。
     *
     * 窗口循环用它决定「要不要画一帧」——[WindowsSoftwareRedrawer.needRender] 不再是
     * 空实现，所以静止的窗口不会重绘，空闲时 CPU ≈ 0。
     */
    fun hasRenderRequest(): Boolean =
        (redrawer as? WindowsRenderLoopRedrawer)?.renderRequested ?: false

    /**
     * 有请求时画一帧，返回是否真的画了（见 [hasRenderRequest]）。
     */
    fun renderIfRequested(): Boolean =
        (redrawer as? WindowsRenderLoopRedrawer)?.renderIfRequested() ?: false

    /**
     * 注册「Compose 请求渲染」的回调。
     *
     * 渲染请求可能发生在窗口循环阻塞等待消息的时候（动画、后台线程完成工作触发的重组），
     * 窗口循环把它接到 `Win32Window::wake` 上，保证立刻醒来而不是等到下一条输入消息。
     */
    fun setRenderRequestHandler(handler: (() -> Unit)?) {
        (redrawer as? WindowsRenderLoopRedrawer)?.onRenderRequest = handler
    }

    /** 诊断：由宿主消息循环（按需渲染）画出的帧数。 */
    val loopFrameCount: Int
        get() = (redrawer as? WindowsRenderLoopRedrawer)?.loopFrames ?: 0

    /** 诊断：由同步渲染 tick（WM_SIZE 模态循环 / 首帧）画出的帧数。 */
    val immediateFrameCount: Int
        get() = (redrawer as? WindowsRenderLoopRedrawer)?.immediateFrames ?: 0

    /** 当前后端的诊断信息（后端类型 / 呈现方式）；写进性能日志用。 */
    val rendererInfo: String
        get() = redrawer?.renderInfo?.trim()?.replace('\n', ';') ?: "n/a"

    /**
     * 建后端：默认先试 GPU（OPENGL），失败（虚拟机/远程桌面/只有 GL 1.1 的驱动）
     * 自动回退到软件路径 —— 与上游的「主后端 + 软件回退」一致。
     */
    private fun createRedrawer(window: Win32Window): Redrawer {
        val requested = windowsRenderApiOverride() ?: renderApi
        if (requested == GraphicsApi.OPENGL) {
            try {
                val gl = WindowsGLRedrawer(this, window)
                renderApi = GraphicsApi.OPENGL
                win32Log("skialayer: 使用 GL(GPU) 后端")
                return gl
            } catch (t: Throwable) {
                win32Log(
                    "skialayer: GL 后端创建失败（${t::class.simpleName}: ${t.message}），回退软件路径"
                )
            }
        }
        renderApi = GraphicsApi.SOFTWARE_FAST
        win32Log("skialayer: 使用软件(CPU raster + GDI) 后端")
        return WindowsSoftwareRedrawer(this, window)
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()

    internal fun update(nanoTime: Long) {
        val window = win32Window ?: return
        val logicalWidth = window.width.coerceIn(0, 16384)
        val logicalHeight = window.height.coerceIn(0, 16384)
        if (logicalWidth == 0 || logicalHeight == 0) return

        val pictureWidth = (logicalWidth * contentScale).toInt().coerceIn(0, 16384)
        val pictureHeight = (logicalHeight * contentScale).toInt().coerceIn(0, 16384)
        if (pictureWidth == 0 || pictureHeight == 0) return

        val canvas = pictureRecorder.beginRecording(
            0f,
            0f,
            pictureWidth.toFloat(),
            pictureHeight.toFloat(),
        ).apply {
            clear(Color.WHITE)
        }
        renderDelegate?.onRender(canvas, pictureWidth, pictureHeight, nanoTime)
        picture = PictureHolder(pictureRecorder.finishRecordingAsPicture(), pictureWidth, pictureHeight)
    }

    internal actual fun draw(canvas: Canvas) {
        picture?.let { canvas.drawPicture(it.instance) }
    }

    actual val pixelGeometry: PixelGeometry = PixelGeometry.UNKNOWN

    internal fun inDrawScope(block: LayerDrawScope.() -> Unit) {
        val window = win32Window ?: return
        with(
            LayerDrawScope(
                pixelGeometry = pixelGeometry,
                layerWidth = window.width,
                layerHeight = window.height,
                scale = contentScale,
            )
        ) {
            block()
        }
    }

    /** Synchronous render for simple event loops. */
    fun renderImmediately() {
        redrawer?.renderImmediately()
    }
}

/**
 * `COMPOSEKN_RENDER_API=gl|opengl|software|sw|gdi`：强制指定后端（CI / 排查用）。
 * 未设置时返回 null，表示按 [SkiaLayer.renderApi] 走默认逻辑。
 */
private fun windowsRenderApiOverride(): GraphicsApi? {
    val raw = platform.posix.getenv("COMPOSEKN_RENDER_API")?.toKString()?.trim()?.lowercase() ?: return null
    return when (raw) {
        "gl", "opengl", "gpu" -> GraphicsApi.OPENGL
        "software", "sw", "gdi", "cpu" -> GraphicsApi.SOFTWARE_FAST
        else -> {
            win32Log("skialayer: 无法识别的 COMPOSEKN_RENDER_API=$raw（按默认处理）")
            null
        }
    }
}

/**
 * 默认后端 = GPU（OPENGL），与上游各平台一致：上游 Windows 桌面默认 D3D12/ANGLE、
 * macOS METAL、Linux Wayland OPENGL；软件路径是回退，不是主路线。
 */
private fun defaultWindowsRenderApi(): GraphicsApi = GraphicsApi.OPENGL

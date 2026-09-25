@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.redrawer.LinuxWaylandOpenGLRedrawer
import org.jetbrains.skiko.redrawer.LinuxWaylandVulkanRedrawer
import org.jetbrains.skiko.redrawer.Redrawer
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * SkiaLayer for Kotlin/Native Linux with Wayland.
 *
 * Default: Graphite/Vulkan → fallback GLES. Override with `COMPOSEKN_RENDER_API`.
 */
actual open class SkiaLayer {
    actual var renderApi: GraphicsApi = defaultLinuxRenderApi()
        set(value) {
            if (value != GraphicsApi.VULKAN && value != GraphicsApi.OPENGL) {
                throw IllegalArgumentException("Only VULKAN and OPENGL are supported on Linux Wayland")
            }
            field = value
        }

    actual val contentScale: Float
        get() = waylandWindow?.scale ?: 1.0f

    actual var fullscreen: Boolean = false
        set(value) {
            if (value) throw IllegalArgumentException("fullscreen unsupported on Linux Wayland")
            field = false
        }

    actual val component: Any?
        get() = waylandWindow

    actual var renderDelegate: SkikoRenderDelegate? = null

    internal var redrawer: Redrawer? = null
    private var waylandWindow: WaylandWindow? = null

    private var picture: PictureHolder? = null
    private val pictureRecorder = PictureRecorder()

    /**
     * Attach to an existing [WaylandWindow], or create one when [container] is a title string.
     */
    actual fun attachTo(container: Any) {
        check(waylandWindow == null) { "Already attached to a Wayland window" }
        SkikoNativeLinkage.ensureLinked()
        waylandWindow = when (container) {
            is WaylandWindow -> container
            is String -> WaylandWindow(container)
            else -> error("container must be WaylandWindow or window title String")
        }
        redrawer = createRedrawer(waylandWindow!!).apply {
            syncBounds()
            needRender()
        }
    }

    actual fun detach() {
        redrawer?.dispose()
        redrawer = null
        waylandWindow = null
        picture = null
    }

    actual fun needRender(throttledToVsync: Boolean) {
        redrawer?.needRender(throttledToVsync)
    }

    @Deprecated(
        message = "Use needRender() instead",
        replaceWith = ReplaceWith("needRender()")
    )
    actual fun needRedraw() = needRender()

    internal fun update(nanoTime: Long) {
        val window = waylandWindow ?: return
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
        val window = waylandWindow ?: return
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

    /** Synchronous render for simple event loops without a coroutine dispatcher pump. */
    fun renderImmediately() {
        redrawer?.renderImmediately()
    }

    /**
     * 建后端：默认 Graphite/Vulkan → GLES；可用 `COMPOSEKN_RENDER_API` 强制。
     */
    private fun createRedrawer(window: WaylandWindow): Redrawer {
        val requested = linuxRenderApiOverride() ?: renderApi
        val tryVulkan = requested == GraphicsApi.VULKAN
        val tryGl = requested == GraphicsApi.OPENGL || requested == GraphicsApi.VULKAN

        if (tryVulkan) {
            try {
                renderApi = GraphicsApi.VULKAN
                return LinuxWaylandVulkanRedrawer(this, window)
            } catch (t: Throwable) {
                println(
                    "composekn: Graphite/Vulkan unavailable (${t.message}); falling back to GLES",
                )
                window.setVulkanPreferred(false)
            }
        }
        if (tryGl) {
            renderApi = GraphicsApi.OPENGL
            window.setVulkanPreferred(false)
            return LinuxWaylandOpenGLRedrawer(this, window)
        }
        renderApi = GraphicsApi.OPENGL
        window.setVulkanPreferred(false)
        return LinuxWaylandOpenGLRedrawer(this, window)
    }
}

actual val currentSystemTheme: SystemTheme = SystemTheme.UNKNOWN

/**
 * `COMPOSEKN_RENDER_API=vulkan|vk|graphite|gl|opengl|gles`：强制指定后端。
 * 未设置时返回 null，表示按 [SkiaLayer.renderApi] 走默认逻辑。
 */
private fun linuxRenderApiOverride(): GraphicsApi? {
    val raw = getenv("COMPOSEKN_RENDER_API")?.toKString()?.trim()?.lowercase() ?: return null
    return when (raw) {
        "vulkan", "vk", "graphite", "graphite-vk" -> GraphicsApi.VULKAN
        "gl", "opengl", "gles", "gpu" -> GraphicsApi.OPENGL
        else -> null
    }
}

/** 默认后端 = Graphite/Vulkan，失败回退 GLES。 */
private fun defaultLinuxRenderApi(): GraphicsApi = GraphicsApi.VULKAN

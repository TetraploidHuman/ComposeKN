package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skiko.redrawer.LinuxWaylandOpenGLRedrawer
import org.jetbrains.skiko.redrawer.Redrawer

/**
 * SkiaLayer for Kotlin/Native Linux with Wayland + EGL + OpenGL.
 */
actual open class SkiaLayer {
    actual var renderApi: GraphicsApi = GraphicsApi.OPENGL
        set(value) {
            if (value != GraphicsApi.OPENGL) {
                throw IllegalArgumentException("Only OPENGL is supported on Linux Wayland")
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
        redrawer = LinuxWaylandOpenGLRedrawer(this, waylandWindow!!).apply {
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
}

actual val currentSystemTheme: SystemTheme = SystemTheme.UNKNOWN

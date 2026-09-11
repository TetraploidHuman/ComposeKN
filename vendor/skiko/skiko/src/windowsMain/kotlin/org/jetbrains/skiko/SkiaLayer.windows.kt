package org.jetbrains.skiko

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PixelGeometry
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skiko.redrawer.Redrawer
import org.jetbrains.skiko.redrawer.WindowsSoftwareRedrawer

/**
 * SkiaLayer for Kotlin/Native Windows (mingwX64): Win32 borderless window +
 * software raster rendering presented through GDI's StretchDIBits.
 */
actual open class SkiaLayer {
    actual var renderApi: GraphicsApi = GraphicsApi.OPENGL
        set(value) {
            if (value != GraphicsApi.OPENGL) {
                throw IllegalArgumentException("Only OPENGL (software raster) is supported on Windows (mingw)")
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
        org.jetbrains.skiko.compositionWindowRegistry.add(win32Window!!)
        redrawer = WindowsSoftwareRedrawer(this, win32Window!!).apply {
            syncBounds()
            needRender()
        }
    }

    actual fun detach() {
        redrawer?.dispose()
        redrawer = null
        win32Window = null
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

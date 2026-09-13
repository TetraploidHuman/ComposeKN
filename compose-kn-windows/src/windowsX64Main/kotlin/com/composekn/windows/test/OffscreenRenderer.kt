@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.composekn.windows.test

import com.composekn.windows.WindowsComposeApplication
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * 一帧的像素快照（ARGB，行优先）。
 *
 * 只暴露「断言友好」的查询方法：取某点颜色、数颜色种类、数某个颜色的像素数。
 */
class FrameSnapshot(
    val width: Int,
    val height: Int,
    private val argb: IntArray,
) {
    init {
        require(argb.size == width * height) {
            "pixel buffer size ${argb.size} != $width x $height"
        }
    }

    /** x/y 越界返回 0（便于写「边缘一定是背景」之类的断言）。 */
    fun colorAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return argb[y * width + x]
    }

    /** 全部像素的 ARGB 数组（副本）。 */
    fun toArgbArray(): IntArray = argb.copyOf()

    /** 不同颜色的个数（全白窗口 ≈ 1~2，正常界面通常 > 50）。 */
    fun distinctColorCount(): Int {
        val set = HashSet<Int>()
        for (c in argb) set.add(c)
        return set.size
    }

    fun countColor(color: Int, tolerance: Int = 2): Int {
        var n = 0
        for (c in argb) if (close(c, color, tolerance)) n++
        return n
    }

    /** 某一行里某个颜色的连续像素长度（按 [xStart, xEnd) 统计最大连续段）。 */
    fun longestRunInRow(y: Int, color: Int, tolerance: Int = 2): Int {
        if (y !in 0 until height) return 0
        var best = 0
        var cur = 0
        for (x in 0 until width) {
            if (close(colorAt(x, y), color, tolerance)) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 0
            }
        }
        return best
    }

    /** 第一个与 [color] 相近的像素的 x 坐标（该行），找不到返回 -1。 */
    fun firstXInRow(y: Int, color: Int, tolerance: Int = 2): Int {
        if (y !in 0 until height) return -1
        for (x in 0 until width) if (close(colorAt(x, y), color, tolerance)) return x
        return -1
    }

    /** 最后一个与 [color] 相近的像素的 x 坐标（该行），找不到返回 -1。 */
    fun lastXInRow(y: Int, color: Int, tolerance: Int = 2): Int {
        if (y !in 0 until height) return -1
        for (x in width - 1 downTo 0) if (close(colorAt(x, y), color, tolerance)) return x
        return -1
    }

    /** 第 y 行非 [background] 的像素数（判断「这一行画了东西没有」）。 */
    fun nonBackgroundCountInRow(y: Int, background: Int, tolerance: Int = 4): Int {
        if (y !in 0 until height) return 0
        var n = 0
        for (x in 0 until width) if (!close(colorAt(x, y), background, tolerance)) n++
        return n
    }

    override fun toString(): String =
        "FrameSnapshot(${width}x$height, distinct=${distinctColorCount()})"

    private fun close(a: Int, b: Int, tolerance: Int): Boolean {
        if (tolerance == 0) return a == b
        val da = kotlin.math.abs((a ushr 24) - (b ushr 24))
        val dr = kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = kotlin.math.abs((a and 0xFF) - (b and 0xFF))
        return da <= tolerance && dr <= tolerance && dg <= tolerance && db <= tolerance
    }
}

/**
 * 把 [app] 的一帧渲染到离屏 BGRA raster surface 并读回像素。
 *
 * 需要提前 [WindowsComposeApplication.setContent]（通常 withChrome = false）。
 * 每帧之间会推进 Main dispatcher（等价于窗口循环里的 flushMainUIDispatcher()）。
 */
fun renderOffscreen(
    app: WindowsComposeApplication,
    width: Int,
    height: Int,
    density: Float = 1f,
    frames: Int = 2,
    nanoStep: Long = 16_000_000L,
): FrameSnapshot = OffscreenDriver(app).render(width, height, density, frames, nanoStep)

/**
 * 离屏渲染驱动器：持有一个**单调递增**的帧时钟。
 *
 * 为什么需要它：`renderOffscreen()` 每次调用都把 nanoTime 从 0 开始，
 * 于是一连串调用给出的时间戳会来回跳。任何依赖 `withFrameNanos()` 的动画
 * （滚动惯性、进度条、涟漪）拿到负的时间差就会算错，甚至把已经滚动的内容
 * 又拉回去。多次渲染同一场景时必须复用同一个 driver。
 */
class OffscreenDriver(private val app: WindowsComposeApplication) {
    /** 下一次渲染使用的时间戳（纳秒）。 */
    var nanos: Long = 0L
        private set

    fun render(
        width: Int,
        height: Int,
        density: Float = 1f,
        frames: Int = 2,
        nanoStep: Long = 16_000_000L,
    ): FrameSnapshot {
        require(width > 0 && height > 0) { "invalid offscreen size ${width}x$height" }
        app.densityOverride = androidx.compose.ui.unit.Density(density)

        val info = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL, ColorSpace.sRGB)
        val surface = Surface.makeRaster(info, info.minRowBytes, null)
            ?: error("cannot create raster surface ${width}x$height")
        val bitmap = Bitmap()
        try {
            val bytes = renderAndRead(surface, bitmap, info, app, width, height, frames, nanoStep)
            return FrameSnapshot(width, height, bgraBytesToArgb(bytes, width, height))
        } finally {
            bitmap.close()
            surface.close()
        }
    }

    private fun renderAndRead(
        surface: Surface,
        bitmap: Bitmap,
        info: ImageInfo,
        app: WindowsComposeApplication,
        width: Int,
        height: Int,
        frames: Int,
        nanoStep: Long,
    ): ByteArray {
        repeat(frames.coerceAtLeast(1)) {
            app.pumpDispatchers()
            app.renderFrame(surface.canvas, width, height, nanos)
            nanos += nanoStep
        }
        check(bitmap.allocPixels(info)) { "bitmap.allocPixels failed" }
        check(surface.readPixels(bitmap, 0, 0)) { "surface.readPixels failed" }
        return bitmap.readPixels() ?: error("bitmap.readPixels() returned null")
    }
}

/**
 * N32 (BGRA_8888) 小端字节流 -> ARGB Int。
 * 内存顺序是 B,G,R,A —— 断言失败时先怀疑这里（selftest 里有 clear(红) 自检）。
 */
private fun bgraBytesToArgb(bytes: ByteArray, width: Int, height: Int): IntArray {
    val out = IntArray(width * height)
    var i = 0
    var o = 0
    while (i + 3 < bytes.size && o < out.size) {
        val b = bytes[i].toInt() and 0xFF
        val g = bytes[i + 1].toInt() and 0xFF
        val r = bytes[i + 2].toInt() and 0xFF
        val a = bytes[i + 3].toInt() and 0xFF
        out[o] = (a shl 24) or (r shl 16) or (g shl 8) or b
        i += 4
        o++
    }
    return out
}

/** 供断言使用的常用颜色。 */
object TestColors {
    const val OPAQUE_RED = 0xFFFF0000.toInt()
    const val OPAQUE_GREEN = 0xFF00FF00.toInt()
    const val OPAQUE_BLUE = 0xFF0000FF.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/**
 * 用纯色填满离屏 surface 再读回。
 *
 * 用途：校验 `N32(BGRA) -> ARGB` 的字节序假设。一旦这个自检失败，
 * 所有基于颜色的断言都会失效，所以它必须排在像素断言之前。
 */
fun snapshotSolidColor(width: Int, height: Int, argb: Int): FrameSnapshot {
    val info = ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL, ColorSpace.sRGB)
    val surface = Surface.makeRaster(info, info.minRowBytes, null)
        ?: error("cannot create raster surface ${width}x$height")
    val bitmap = Bitmap()
    try {
        surface.canvas.clear(argb)
        check(bitmap.allocPixels(info)) { "bitmap.allocPixels failed" }
        check(surface.readPixels(bitmap, 0, 0)) { "surface.readPixels failed" }
        val bytes = bitmap.readPixels() ?: error("bitmap.readPixels() returned null")
        return FrameSnapshot(width, height, bgraBytesToArgb(bytes, width, height))
    } finally {
        bitmap.close()
        surface.close()
    }
}

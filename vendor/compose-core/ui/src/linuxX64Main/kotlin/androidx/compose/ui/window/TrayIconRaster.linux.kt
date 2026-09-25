/*
 * Copyright 2026 The ComposeKN Authors
 *
 * Painter → BGRA（托盘图标）；对齐 Desktop toAwtImage 的光栅化思路，无 AWT。
 */

package androidx.compose.ui.window

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Windows 托盘常用 16；Linux StatusNotifier 也常用 22，统一先用 16。 */
internal val TrayIconPixelSize = Size(16f, 16f)

/**
 * 把 [Painter] 画进 [ImageBitmap] 再导出 BGRA（自上而下）。
 * Density 固定 1f（与 Desktop Tray 不用 LocalDensity 一致）。
 */
internal fun Painter.toTrayIconBgra(
    size: Size = TrayIconPixelSize,
    density: Density = Density(1f),
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): Triple<Int, Int, ByteArray> {
    val w = size.width.toInt().coerceAtLeast(1)
    val h = size.height.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    val canvas = Canvas(bitmap)
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = layoutDirection,
        canvas = canvas,
        size = Size(w.toFloat(), h.toFloat()),
    ) {
        with(this@toTrayIconBgra) {
            draw(Size(w.toFloat(), h.toFloat()))
        }
    }
    val argb = IntArray(w * h)
    bitmap.readPixels(argb)
    val bgra = ByteArray(w * h * 4)
    for (i in argb.indices) {
        val color = argb[i]
        bgra[i * 4 + 0] = (color and 0xFF).toByte()
        bgra[i * 4 + 1] = ((color shr 8) and 0xFF).toByte()
        bgra[i * 4 + 2] = ((color shr 16) and 0xFF).toByte()
        bgra[i * 4 + 3] = ((color shr 24) and 0xFF).toByte()
    }
    return Triple(w, h, bgra)
}

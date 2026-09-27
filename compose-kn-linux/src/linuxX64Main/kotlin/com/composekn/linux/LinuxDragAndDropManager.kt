@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.composekn.linux

import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import org.jetbrains.skiko.WaylandWindow

/**
 * Wayland 拖放**发出侧**，对齐 [com.composekn.windows.WindowsDragAndDropManager]：
 *
 * 1. `isRequestDragAndDropTransferRequired = true`
 * 2. 排队后由 [flushPendingOutgoingDrags] 在指针派发结束后再 `start_drag`
 * 3. [drawDragDecoration] → BGRA 图标 surface（热点取装饰中心）
 * 4. 完成态经 [pollOutgoingDragResults] 读 `drag_poll_result`（非阻塞）
 */
internal class LinuxDragAndDropManager(
    private val windowProvider: () -> WaylandWindow?,
) : PlatformDragAndDropManager {

    override val isRequestDragAndDropTransferRequired: Boolean
        get() = true

    override fun requestDragAndDropTransfer(source: PlatformDragAndDropSource, offset: Offset) {
        var isTransferStarted = false
        val startTransferScope =
            object : PlatformDragAndDropSource.StartTransferScope {
                override fun startDragAndDropTransfer(
                    transferData: DragAndDropTransferData,
                    decorationSize: Size,
                    drawDragDecoration: DrawScope.() -> Unit,
                ): Boolean {
                    isTransferStarted = startOutgoingDrag(
                        transferData = transferData,
                        decorationSize = decorationSize,
                        drawDragDecoration = drawDragDecoration,
                    )
                    return isTransferStarted
                }
            }
        with(source) {
            startTransferScope.startDragAndDropTransfer(offset) { isTransferStarted }
        }
    }

    private fun startOutgoingDrag(
        transferData: DragAndDropTransferData,
        decorationSize: Size,
        drawDragDecoration: DrawScope.() -> Unit,
    ): Boolean {
        val files = transferData.files.takeIf { it.isNotEmpty() }
        val text = transferData.text?.takeIf { it.isNotEmpty() }
        if (files == null && text == null) {
            println("composekn: drag Source 没有 files/text，跳过 start_drag")
            transferData.onTransferCompleted?.invoke(false)
            return false
        }
        val window = windowProvider()
        if (window == null) {
            println("composekn: drag 窗口尚未就绪，无法 start_drag")
            transferData.onTransferCompleted?.invoke(false)
            return false
        }

        val icon = renderDragDecoration(decorationSize, drawDragDecoration)
        pendingOutgoing.add(
            PendingOutgoingDrag(
                window = window,
                files = files,
                text = text,
                iconW = icon.width,
                iconH = icon.height,
                iconBgra = icon.bgra,
                hotX = icon.hotX,
                hotY = icon.hotY,
                onCompleted = transferData.onTransferCompleted,
            ),
        )
        println(
            "composekn: drag Source 已排队" +
                if (icon.bgra != null) " (icon ${icon.width}x${icon.height})" else "",
        )
        LinuxApplicationHost.wake()
        return true
    }

    private fun renderDragDecoration(
        decorationSize: Size,
        drawDragDecoration: DrawScope.() -> Unit,
    ): DragIcon {
        val w = decorationSize.width.roundToInt().coerceAtLeast(1)
        val h = decorationSize.height.roundToInt().coerceAtLeast(1)
        if (w <= 1 && h <= 1 && decorationSize.width < 1f) {
            return DragIcon(0, 0, null, 0, 0)
        }
        val imageBitmap = ImageBitmap(w, h)
        val canvas = Canvas(imageBitmap)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(w.toFloat(), h.toFloat()),
            block = drawDragDecoration,
        )
        val argb = IntArray(w * h)
        imageBitmap.readPixels(argb)
        val bgra = ByteArray(w * h * 4)
        for (i in argb.indices) {
            val color = argb[i]
            bgra[i * 4 + 0] = (color and 0xFF).toByte()
            bgra[i * 4 + 1] = ((color shr 8) and 0xFF).toByte()
            bgra[i * 4 + 2] = ((color shr 16) and 0xFF).toByte()
            bgra[i * 4 + 3] = ((color shr 24) and 0xFF).toByte()
        }
        return DragIcon(w, h, bgra, w / 2, h / 2)
    }

    private data class DragIcon(
        val width: Int,
        val height: Int,
        val bgra: ByteArray?,
        val hotX: Int,
        val hotY: Int,
    )

    private data class PendingOutgoingDrag(
        val window: WaylandWindow,
        val files: List<String>?,
        val text: String?,
        val iconW: Int,
        val iconH: Int,
        val iconBgra: ByteArray?,
        val hotX: Int,
        val hotY: Int,
        val onCompleted: ((Boolean) -> Unit)?,
    )

    private data class ActiveOutgoingDrag(
        val window: WaylandWindow,
        val onCompleted: ((Boolean) -> Unit)?,
    )

    companion object {
        private val pendingOutgoing = mutableListOf<PendingOutgoingDrag>()
        private val activeOutgoing = mutableListOf<ActiveOutgoingDrag>()

        /** 指针派发结束后调用：真正 `wl_data_device_start_drag`。 */
        fun flushPendingOutgoingDrags() {
            if (pendingOutgoing.isEmpty()) return
            val batch = pendingOutgoing.toList()
            pendingOutgoing.clear()
            for (pending in batch) {
                val started = pending.window.startDrag(
                    files = pending.files,
                    text = pending.text,
                    iconBgra = pending.iconBgra,
                    iconWidth = pending.iconW,
                    iconHeight = pending.iconH,
                    hotX = pending.hotX,
                    hotY = pending.hotY,
                )
                if (started) {
                    activeOutgoing.add(
                        ActiveOutgoingDrag(pending.window, pending.onCompleted),
                    )
                    println("composekn: drag start_drag 已发起")
                } else {
                    println("composekn: drag start_drag 失败")
                    pending.onCompleted?.invoke(false)
                }
            }
        }

        /** 每轮泵结束调用：消费 `drag_poll_result`。 */
        fun pollOutgoingDragResults() {
            if (activeOutgoing.isEmpty()) return
            val still = mutableListOf<ActiveOutgoingDrag>()
            for (active in activeOutgoing) {
                when (val result = active.window.dragPollResult()) {
                    -1 -> still.add(active)
                    0 -> {
                        println("composekn: drag Source 取消")
                        active.onCompleted?.invoke(false)
                    }
                    else -> {
                        println("composekn: drag Source 成功 result=$result")
                        active.onCompleted?.invoke(result > 0)
                    }
                }
            }
            activeOutgoing.clear()
            activeOutgoing.addAll(still)
        }
    }
}

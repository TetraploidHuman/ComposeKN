@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.composekn.windows

import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformDragAndDropSource
import org.jetbrains.skiko.Win32Message
import org.jetbrains.skiko.Win32Window
import org.jetbrains.skiko.win32Log

/**
 * Windows OLE 拖放**发出侧**，对齐上游 `AwtDragAndDropManager`：
 *
 * 1. `isRequestDragAndDropTransferRequired = true` —— foundation 的
 *    `Modifier.dragAndDropSource` 才会挂启动手势；
 * 2. `requestDragAndDropTransfer` → 源节点的 `startDragAndDropTransfer` →
 *    `DoDragDrop`（模态，与 AWT `TransferHandler.exportAsDrag` 一样）。
 *
 * MVP 不画自定义拖影（[drawDragDecoration] 忽略）。
 */
internal class WindowsDragAndDropManager(
    private val windowProvider: () -> Win32Window?,
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
                    isTransferStarted = startOutgoingDrag(transferData)
                    return isTransferStarted
                }
            }
        with(source) {
            startTransferScope.startDragAndDropTransfer(offset) { isTransferStarted }
        }
    }

    private fun startOutgoingDrag(transferData: DragAndDropTransferData): Boolean {
        val files = transferData.files.takeIf { it.isNotEmpty() }
        val text = transferData.text?.takeIf { it.isNotEmpty() }
        if (files == null && text == null) {
            win32Log("drag: Source 没有 files/text，跳过 DoDragDrop")
            transferData.onTransferCompleted?.invoke(false)
            return false
        }
        val window = windowProvider()
        if (window == null) {
            win32Log("drag: 窗口尚未就绪，无法 DoDragDrop")
            transferData.onTransferCompleted?.invoke(false)
            return false
        }
        // 模态：阻塞直到放下或取消（与 AWT 一致）。
        val effect = window.doDragDrop(files, text, Win32Message.DROPEFFECT_COPY)
        val success = effect > 0
        win32Log("drag: Source DoDragDrop effect=$effect success=$success")
        transferData.onTransferCompleted?.invoke(success)
        return true
    }
}

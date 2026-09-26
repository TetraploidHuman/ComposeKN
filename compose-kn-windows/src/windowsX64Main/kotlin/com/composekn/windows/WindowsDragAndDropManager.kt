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
 *    排入 [pendingOutgoing]；[flushPendingOutgoingDrags] 在指针派发结束后再
 *    `DoDragDrop`（避免在 Compose `handleEvent` 栈里跑 OLE 嵌套泵，IME 残留
 *    Escape / 空 keyState 会立刻 CANCEL）。
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
        // 不要在 pointer 派发栈里同步 DoDragDrop：嵌套 OLE 泵会立刻吃到队列里
        // IME 收起注入的 Escape / 空 keyState。排到 drainEvents 之后再跑。
        pendingOutgoing.add(
            PendingOutgoingDrag(
                window = window,
                files = files,
                text = text,
                onCompleted = transferData.onTransferCompleted,
            ),
        )
        win32Log("drag: Source 已排队，待 drain 后 DoDragDrop")
        window.wake()
        return true
    }

    private data class PendingOutgoingDrag(
        val window: Win32Window,
        val files: List<String>?,
        val text: String?,
        val onCompleted: ((Boolean) -> Unit)?,
    )

    companion object {
        private val pendingOutgoing = mutableListOf<PendingOutgoingDrag>()

        /**
         * 在共享泵 / 独占泵「本轮事件派发结束」后调用：真正进入 `DoDragDrop`。
         */
        fun flushPendingOutgoingDrags() {
            if (pendingOutgoing.isEmpty()) return
            val batch = pendingOutgoing.toList()
            pendingOutgoing.clear()
            for (pending in batch) {
                val effect = pending.window.doDragDrop(
                    pending.files,
                    pending.text,
                    Win32Message.DROPEFFECT_COPY,
                )
                val success = effect > 0
                win32Log("drag: Source DoDragDrop effect=$effect success=$success")
                pending.onCompleted?.invoke(success)
            }
        }
    }
}

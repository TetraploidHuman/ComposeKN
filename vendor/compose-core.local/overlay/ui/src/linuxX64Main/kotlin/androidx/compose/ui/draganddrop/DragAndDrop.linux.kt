/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.draganddrop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset

/**
 * 拖放传输的数据（**发起侧**：应用自己往外拖）。
 *
 * Windows 上映射为 OLE `IDataObject`：`files` → CF_HDROP，`text` → CF_UNICODETEXT。
 * 至少要有一个非空字段，否则 [WindowsDragAndDropManager] 不会启动 `DoDragDrop`。
 */
actual class DragAndDropTransferData @ExperimentalComposeUiApi constructor(
    /** 文件路径列表（Windows：CF_HDROP）。 */
    @property:ExperimentalComposeUiApi
    val files: List<String> = emptyList(),
    /** 纯文本（Windows：CF_UNICODETEXT）。 */
    @property:ExperimentalComposeUiApi
    val text: String? = null,
    /**
     * 拖放结束回调：`true` = 目标接受（effect ≠ NONE），`false` = 取消/拒绝。
     */
    @property:ExperimentalComposeUiApi
    val onTransferCompleted: ((success: Boolean) -> Unit)? = null,
)

/**
 * 一次拖放会话里由**平台**送进来的事件（接收侧）。
 *
 * 上游的对应物：desktop 的 `DragAndDropEvent(dropTargetEvent)` 里裹着 AWT 的
 * `DropTargetEvent`/`Transferable`，iOS 的裹着 `DropSessionContext`。负载怎么读是
 * **平台决定**的（上游也是平台扩展：`event.awtEventOrNull`、iOS 的 `session`），
 * 所以这里给的是 ComposeKN 原生的访问器 [files] / [text]，以及 [positionInWindow]。
 *
 * 构造者是宿主（`compose-kn-windows` 的拖放派发）：`DragAndDropEvent.forPlatformDrop(...)`。
 */
actual class DragAndDropEvent internal constructor(
    internal val position: Offset,
    private val paths: List<String>,
    private val textValue: String?,
) {
    /**
     * 本次拖放带来的**文件路径**（Windows 上是 OLE 的 `CF_HDROP`）。
     *
     * 空列表表示这次拖放没有文件（比如只是拖一段文本）。
     */
    val files: List<String> get() = paths

    /** 本次拖放带来的**文本**（Windows 上是 `CF_UNICODETEXT`）；没有则为 null。 */
    val text: String? get() = textValue

    /**
     * 事件位置（相对 Compose 根节点的像素坐标）。
     *
     * expect 里的 `positionInRoot` 是 internal 的（只有 compose-core 内部用），
     * 这个公开访问器是给应用/宿主看的。
     */
    val positionInWindow: Offset get() = position

    companion object {
        /**
         * ComposeKN 扩展：宿主构造一个**入站**拖放事件。
         *
         * 为什么需要它：`DragAndDropEvent` 的构造函数是 internal（和上游一致 ——
         * 事件只能由平台层创建），而宿主在另一个 Gradle 模块里，读不到 internal。
         * 所以由这里提供一个公开工厂，宿主只负责填负载和坐标。
         */
        fun forPlatformDrop(
            position: Offset,
            files: List<String> = emptyList(),
            text: String? = null,
        ): DragAndDropEvent = DragAndDropEvent(position, files, text)
    }
}

/**
 * Returns the position of this [DragAndDropEvent] relative to the root Compose View in the
 * layout hierarchy.
 */
internal actual val DragAndDropEvent.positionInRoot: Offset
    get() = position

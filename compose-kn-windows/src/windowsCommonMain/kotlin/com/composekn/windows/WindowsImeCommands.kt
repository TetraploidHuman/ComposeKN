@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand

/**
 * IME 事件 -> Compose 文本编辑命令。
 *
 * 对齐上游 Compose 桌面（JVM）的做法：`DesktopTextInputService2` 收到 AWT 的
 * `InputMethodEvent` 之后就是
 *
 *     request.editText {
 *         commitText(committedText, 1)
 *         if (composingText.isNotEmpty()) setComposingText(composingText, 1)
 *     }
 *
 * （见 compose-core/ui/src/desktopMain/kotlin/androidx/compose/ui/platform/
 * DesktopTextInputService2.kt 的 `inputMethodTextChanged`）。
 * 我们这边只是把「AWT 的 InputMethodEvent」换成「IMM32 拆出来的四种事件」，
 * 语义完全一致：
 *
 *   * IME_COMMIT   （GCS_RESULTSTR）-> commitText（Compose 会顺手结束组字区）
 *   * IME_UPDATE   （GCS_COMPSTR）  -> setComposingText（带下划线的组字预览）；
 *                                     空串 = 组字被清空 -> finishComposingText
 *   * IME_END      （ENDCOMPOSITION）-> finishComposingText
 *   * IME_START                      -> 不需要编辑文本（会话在输入框聚焦时就开了）
 *
 * 返回 null 表示这个事件不影响文本框内容。
 */
internal fun imeEditCommands(event: WindowsEvent): List<EditCommand>? = when (event) {
    is WindowsEvent.ImeCommitEvent ->
        // commitText(committedText, 1)：1 = 光标停在插入文本之后（与上游一致）
        event.text.takeIf { it.isNotEmpty() }?.let { listOf(CommitTextCommand(it, 1)) }

    is WindowsEvent.ImeCompositionEvent ->
        if (event.text.isEmpty()) {
            listOf(FinishComposingTextCommand())
        } else {
            listOf(SetComposingTextCommand(event.text, 1))
        }

    is WindowsEvent.ImeEndEvent -> listOf(FinishComposingTextCommand())

    else -> null
}

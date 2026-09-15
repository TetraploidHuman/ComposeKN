@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import kotlinx.coroutines.awaitCancellation

/**
 * Text input service for Windows platform.
 */
internal class WindowsTextInputService {
    var activeRequest: PlatformTextInputMethodRequest? = null
        private set

    /**
     * 文本会话结束（输入框失焦/被移除）时的回调。
     *
     * 宿主用它取消 IME 的组字（ImmNotifyIME/CPS_CANCEL）—— 不取消的话输入法会一直
     * 停在「组字中」，候选窗留在屏幕上不消失（真机反馈的「候选词卡死」之一）。
     */
    var onSessionEnded: (() -> Unit)? = null

    /**
     * Start input method and await cancellation.
     */
    suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        activeRequest = request
        try {
            awaitCancellation()
        } finally {
            if (activeRequest === request) {
                activeRequest = null
                try {
                    onSessionEnded?.invoke()
                } catch (_: Throwable) {
                    // 取消组字失败不应该影响会话清理
                }
            }
        }
    }

    /**
     * Try to commit text to the active input request.
     */
    fun tryCommitText(text: String): Boolean {
        val request = activeRequest ?: return false
        if (text.isEmpty()) return false
        request.onEditCommand(listOf(CommitTextCommand(text, 1)))
        return true
    }

    /**
     * Commit a single character.
     */
    fun commitCharacter(char: Char): Boolean {
        if (char == '\u0000') return false
        return tryCommitText(char.toString())
    }

    /**
     * 当前文本会话的光标矩形（Compose 根坐标 = 窗口客户区物理像素）；没有会话时 null。
     *
     * IME 的候选窗/组字窗要用它定位（宿主把它转成屏幕坐标后交给
     * ImmSetCandidateWindow / WM_IME_REQUEST 的 IMR_QUERYCHARPOSITION）。
     */
    fun caretRectInRoot(): Rect? = activeRequest?.focusedRectInRoot()

    /**
     * 把一组编辑命令交给当前文本会话（IME 的组字/提交都走这里）。
     *
     * 会话已经结束（输入框失焦）时返回 false，调用方据此决定是否记日志/丢弃。
     */
    fun applyEditCommands(commands: List<EditCommand>): Boolean {
        val request = activeRequest ?: return false
        request.onEditCommand(commands)
        return true
    }
}

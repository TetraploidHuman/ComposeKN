@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import com.composekn.windows.internal.winlog
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
     * 「组字串里第 [charIndex] 个字符」的矩形（Compose 根坐标）；[charIndex] < 0 表示
     * 只要当前光标矩形。
     *
     * 为什么必须区分：IME 在 `IMR_QUERYCHARPOSITION` 里用 `dwCharPos` 指定它想问哪个
     * 字符 —— **候选窗问的是组字串开头（dwCharPos=0）**。以前我们一律回"当前光标"，
     * 于是拼音越打越长、光标越靠右，候选窗就跟着一路往右滑；原生 Windows 应用里
     * 候选窗是**钉在开始组字的位置**的。
     *
     * 实现：`request.focusedRectInRoot()` 是"光标（selection.max）在 root 里的矩形"，
     * 而 `TextLayoutResult.getCursorRect(offset)` 给出同一坐标系里任意偏移的矩形 ——
     * 两者相减就得到「目标字符相对光标」的位移。文本框到 root 的变换是平移
     * （没有旋转/额外缩放），所以这个位移可以直接加到光标矩形上。
     */
    fun caretRectForCompositionChar(charIndex: Int): Rect? {
        val request = activeRequest ?: return null
        val caret = request.focusedRectInRoot() ?: return null
        if (charIndex < 0) return caret
        try {
            val composition = request.value().composition ?: return caret
            val layout = request.textLayoutResult() ?: return caret
            // ⚠ 这个函数是在 IME 的**同步**回调里跑的（WM_IME_REQUEST 的 SendMessage）：
            //   异常逃出去 = 进程崩。而且这里天然有一帧错位 ——
            //   TextFieldDelegate.onEditCommand() 会**立刻** session.updateState(newValue)
            //   （为了 setComposingText 之后 IME 马上能读到新文本，见 TextFieldDelegate.kt
            //   的注释），但 textLayoutResult 要等**下一帧排版**才更新。于是这一小段
            //   时间里 value 已经是新文本（长）、layout 还是旧的（短）。
            //   TextLayoutResult.getCursorRect() 对超出**排版**长度的偏移会抛
            //   IllegalArgumentException（真机实测：组字到 11 个字符时崩在这上面）。
            //   所以偏移一律夹进 layout 自己的文本长度 —— 夹完之后答案依然正确：
            //   错位那一帧里「组字起点」在旧排版里也还是同一个位置。
            val layoutLength = layout.layoutInput.text.length
            val value = request.value()
            val caretOffset = value.selection.max.coerceIn(0, layoutLength)
            val targetOffset = (composition.start + charIndex).coerceIn(0, layoutLength)
            if (targetOffset == caretOffset) return caret
            val localCaret = layout.getCursorRect(caretOffset)
            val localTarget = layout.getCursorRect(targetOffset)
            val dx = localTarget.left - localCaret.left
            val dy = localTarget.top - localCaret.top
            return Rect(caret.left + dx, caret.top + dy, caret.right + dx, caret.bottom + dy)
        } catch (t: Throwable) {
            // 兜底：任何意外都退回光标矩形，绝不把异常抛进 IME 的同步调用里。
            winlog("ime: caretRectForCompositionChar($charIndex) 异常，退回光标矩形：$t")
            return caret
        }
    }

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

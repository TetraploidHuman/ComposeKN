@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import com.composekn.windows.internal.winlog
import org.jetbrains.skiko.WindowsImeDocument
import kotlinx.coroutines.awaitCancellation
import kotlin.math.abs

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
     * `layout.getCursorRect(offset)` 给出同一排版里任意偏移的矩形 —— 两者相减得到
     * 「排版原点在 root 里的位置」，再加到目标字符的矩形上。文本框到 root 是平移
     * 变换，所以位移可以直接加。**位置沿用这套已验证的算法**（v0.4.11 真机确认
     * 候选窗钉住了）。
     *
     * ⚠ 但**尺寸必须取 `getCursorRect()` 的**，不能沿用 `focusedRectInRoot()` 的：
     * 真机实测后者在「组字串还是空的」和「已经有文本」两种状态下不是同一个矩形
     * （高 58px vs 42px）—— IME 用 `pt.y + cLineHeight` 摆候选窗，尺寸一变，
     * 候选窗刚弹出来就会上下跳 ~16px。
     *
     * 已知残留（±10px 级）：组字刚开始那一瞬间，位置本身还可能差 ~10px。
     * 彻底修法是用 Compose 的 `unclippedTextOffsetInRoot`（文档写明是「排版 (0,0) 点
     * 在 root 里的位置」，上游 iOS 路径就是拿它配 `getCursorRect` 用的）当原点；
     * 但它在 legacy 输入路径下的实现是
     * `textClippingRectInRoot.topLeft - innerTextFieldBounds.topLeft`，看着像"文本块相对
     * 裁剪区的位置"而不是绝对坐标，本机（Linux + Wine，没有真输入法）无法验证到底是
     * 哪种 —— 贸然换会让我们**已经钉对的**候选窗横移几十像素，所以先不动。
     */
    fun caretRectForCompositionChar(charIndex: Int): Rect? {
        val request = activeRequest ?: return null
        val caret = request.focusedRectInRoot() ?: return null
        try {
            val layout = request.textLayoutResult() ?: return caret
            val value = request.value()
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
            val caretOffset = value.selection.max.coerceIn(0, layoutLength)
            val composition = value.composition
            val targetOffset = when {
                charIndex < 0 -> caretOffset       // 只要光标
                composition == null -> caretOffset // 没在组字：按光标算（安全）
                else -> (composition.start + charIndex).coerceIn(0, layoutLength)
            }
            val localCaret = layout.getCursorRect(caretOffset)
            val localTarget = layout.getCursorRect(targetOffset)
            val dx = localTarget.left - localCaret.left
            val dy = localTarget.top - localCaret.top
            // 位置 = 光标矩形 + 排版内位移；尺寸用排版自己的字符矩形（真正的行高）。
            return Rect(
                caret.left + dx,
                caret.top + dy,
                caret.left + dx + localTarget.width,
                caret.top + dy + localTarget.height,
            )
        } catch (t: Throwable) {
            // 兜底：任何意外都退回光标矩形，绝不把异常抛进 IME 的同步调用里。
            winlog("ime: caretRectForCompositionChar($charIndex) 异常，退回光标矩形：$t")
            return caret
        }
    }

    /**
     * IMM32 的「文档快照」：`IMR_DOCUMENTFEED` / `IMR_RECONVERTSTRING` 要的东西
     * （输入法拿它做上下文候选排序和「重新转换」）。
     *
     * 没有活动文本会话时返回 null —— C 侧会把它当作"没有文档"，请求照旧不回答。
     */
    fun imeDocument(): WindowsImeDocument? {
        val request = activeRequest ?: return null
        val value = request.value()
        val composition = value.composition
        return WindowsImeDocument(
            text = value.text,
            selectionStart = value.selection.min,
            selectionEnd = value.selection.max,
            compositionStart = composition?.start ?: -1,
            compositionEnd = composition?.end ?: -1,
        )
    }

    /**
     * 「重新转换」：把输入法发来的 (字符串, 它在字符串里的目标范围) 映射回文档偏移。
     *
     * 输入法发来的东西有两种形态，两种都用"在文档里找这段字符串"解决：
     *
     *   a) 它把我们上次交给它的**那段窗口**原样发回来（最常见）：整个字符串就是文档的
     *      一段，目标范围相对它；
     *   b) 它只发来要重转换的那一小段（通常等于选区）。
     *
     * 候选按优先级挑（同优先级选离光标最近的）：
     *   1. 与当前选区**逐字相等**的那一处（用户先选中再触发重转换 = 标准操作）；
     *   2. 紧挨在光标左边结束的那一处（没选中，就在光标前重转换）；
     *   3. 其它出现位置。
     *
     * 找不到、或范围越界 → 返回 null。C 侧据此**拒绝**这次重转换（输入法取消），
     * 我们绝不在不能确定的情况下动文本 —— 宁可"重转换不生效"，也不要弄出重复文本。
     */
    fun mapReconvertRange(text: String, targetOffsetInText: Int, targetLen: Int): IntArray? {
        val request = activeRequest ?: return null
        if (text.isEmpty() || targetLen <= 0) return null
        if (targetOffsetInText < 0 || targetOffsetInText + targetLen > text.length) return null
        val value = request.value()
        val doc = value.text
        if (doc.isEmpty()) return null
        val caret = value.selection.max.coerceIn(0, doc.length)
        val selStart = value.selection.min.coerceIn(0, doc.length)
        val selEnd = value.selection.max.coerceIn(0, doc.length)
        var best = -1
        var bestScore = Int.MAX_VALUE
        var index = doc.indexOf(text)
        while (index >= 0) {
            val end = index + text.length
            val rank = when {
                selEnd > selStart && index == selStart && end == selEnd -> 0
                end == caret -> 1
                else -> 2
            }
            val score = rank * 1_000_000 + abs(index - caret)
            if (score < bestScore) {
                bestScore = score
                best = index
            }
            index = doc.indexOf(text, index + 1)
        }
        if (best < 0) return null
        val start = best + targetOffsetInText
        val end = start + targetLen
        if (start < 0 || end > doc.length) return null
        return intArrayOf(start, end)
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

package com.composekn.tests

import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import com.composekn.windows.WindowsEvent
import com.composekn.windows.imeEditCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * IME（IMM32）事件 -> Compose 文本编辑命令的映射。
 *
 * 这段逻辑决定「中文能不能打进去」，而它又是纯函数 —— 用单测锁住比只在真机上
 * 手动敲靠谱（真机上还要挑输入法、挑候选词）。
 *
 * 语义对齐 Compose 桌面（JVM）的 DesktopTextInputService2.inputMethodTextChanged：
 *   commitText(committedText, 1) + if (composingText.isNotEmpty()) setComposingText(...)
 */
class WindowsImeCommandsTest {

    @Test
    fun `commit event becomes CommitTextCommand`() {
        val commands = imeEditCommands(WindowsEvent.ImeCommitEvent("中文"))
        assertEquals(1, commands?.size)
        val commit = assertIs<CommitTextCommand>(commands!![0])
        assertEquals("中文", commit.text)
        // newCursorPosition = 1：光标停在插入文本之后（上游同款）
        assertEquals(1, commit.newCursorPosition)
    }

    @Test
    fun `empty commit produces no commands`() {
        assertNull(imeEditCommands(WindowsEvent.ImeCommitEvent("")))
    }

    @Test
    fun `composition event becomes SetComposingTextCommand`() {
        val commands = imeEditCommands(WindowsEvent.ImeCompositionEvent("ni"))
        assertEquals(1, commands?.size)
        val composing = assertIs<SetComposingTextCommand>(commands!![0])
        assertEquals("ni", composing.text)
        assertEquals(1, composing.newCursorPosition)
    }

    @Test
    fun `empty composition clears the composing region`() {
        val commands = imeEditCommands(WindowsEvent.ImeCompositionEvent(""))
        assertEquals(1, commands?.size)
        assertIs<FinishComposingTextCommand>(commands!![0])
    }

    @Test
    fun `end composition finishes composing`() {
        val commands = imeEditCommands(WindowsEvent.ImeEndEvent)
        assertEquals(1, commands?.size)
        assertIs<FinishComposingTextCommand>(commands!![0])
    }

    @Test
    fun `start composition does not touch the text`() {
        assertNull(imeEditCommands(WindowsEvent.ImeStartEvent))
    }

    @Test
    fun `unrelated events do not touch the text`() {
        assertNull(imeEditCommands(WindowsEvent.CloseEvent))
        assertNull(imeEditCommands(WindowsEvent.PaintEvent))
        assertNull(imeEditCommands(WindowsEvent.ImeStartEvent))
    }

    @Test
    fun `commit text is not truncated`() {
        // 候选词整句上屏：整串必须一次提交（不能被拆成逐字插入，
        // 否则中间状态会被 TextField 观察到）
        val sentence = "中华人民共和国"
        val commands = imeEditCommands(WindowsEvent.ImeCommitEvent(sentence))
        val commit = assertIs<CommitTextCommand>(commands!![0])
        assertEquals(sentence, commit.text)
    }
}

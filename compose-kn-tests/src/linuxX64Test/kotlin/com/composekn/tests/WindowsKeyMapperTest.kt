package com.composekn.tests

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import com.composekn.windows.createKeyEvent
import com.composekn.windows.duplicateVkEntries
import com.composekn.windows.vkPairCount
import com.composekn.windows.windowsVirtualKeyToComposeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import androidx.compose.ui.input.key.KeyEventType
import com.composekn.windows.charToCodePoint

/**
 * VK → Compose Key 映射表。
 *
 * 这些断言直接来自真机/桥接约定（`win32_window.cc` 上报的 VK 码），
 * 一旦表被改坏（尤其是重复键被 mapOf 静默覆盖）就会红。
 */
class WindowsKeyMapperTest {

    @Test
    fun `mapping table has no duplicate VK entries`() {
        // 历史 bug：0x5C 既是 VK_RWIN 又被当成反斜杠，后写入的覆盖前面的，
        // 结果「右 Win 键 → Key.Backslash」。
        assertEquals(
            emptyList(),
            duplicateVkEntries(),
            "VK 映射表存在重复键，重复的键会被静默覆盖",
        )
    }

    @Test
    fun `mapping table covers the documented key groups`() {
        // 26 字母 + 10 数字 + 10 小键盘数字 + 12 F 键 + 12 修饰键 + 导航/控制 …
        assertTrue(vkPairCount() >= 80, "映射表条目过少: ${vkPairCount()}")
    }

    @Test
    fun `windows key to compose key`() {
        assertEquals(Key.MetaRight, windowsVirtualKeyToComposeKey(0x5C))   // VK_RWIN
        assertEquals(Key.Backslash, windowsVirtualKeyToComposeKey(0xDC))  // VK_OEM_5
        assertEquals(Key.Slash, windowsVirtualKeyToComposeKey(0xBF))      // VK_OEM_2
        assertEquals(Key.Semicolon, windowsVirtualKeyToComposeKey(0xBA))
        assertEquals(Key.Equals, windowsVirtualKeyToComposeKey(0xBB))
        assertEquals(Key.Comma, windowsVirtualKeyToComposeKey(0xBC))
        assertEquals(Key.Minus, windowsVirtualKeyToComposeKey(0xBD))
        assertEquals(Key.Period, windowsVirtualKeyToComposeKey(0xBE))
        assertEquals(Key.Grave, windowsVirtualKeyToComposeKey(0xC0))
        assertEquals(Key.LeftBracket, windowsVirtualKeyToComposeKey(0xDB))
        assertEquals(Key.RightBracket, windowsVirtualKeyToComposeKey(0xDD))
        assertEquals(Key.Apostrophe, windowsVirtualKeyToComposeKey(0xDE))
    }

    @Test
    fun `left and right variants are distinct`() {
        assertNotEquals(windowsVirtualKeyToComposeKey(0x5B), windowsVirtualKeyToComposeKey(0x5C))
        assertNotEquals(windowsVirtualKeyToComposeKey(0xA0), windowsVirtualKeyToComposeKey(0xA1))
        assertNotEquals(windowsVirtualKeyToComposeKey(0xA2), windowsVirtualKeyToComposeKey(0xA3))
        assertNotEquals(windowsVirtualKeyToComposeKey(0xA4), windowsVirtualKeyToComposeKey(0xA5))
    }

    @Test
    fun `letters digits and function keys`() {
        assertEquals(Key.A, windowsVirtualKeyToComposeKey(0x41))
        assertEquals(Key.Z, windowsVirtualKeyToComposeKey(0x5A))
        assertEquals(Key.Zero, windowsVirtualKeyToComposeKey(0x30))
        assertEquals(Key.Nine, windowsVirtualKeyToComposeKey(0x39))
        assertEquals(Key.NumPad0, windowsVirtualKeyToComposeKey(0x60))
        assertEquals(Key.NumPadDivide, windowsVirtualKeyToComposeKey(0x6F))
        assertEquals(Key.F1, windowsVirtualKeyToComposeKey(0x70))
        assertEquals(Key.F12, windowsVirtualKeyToComposeKey(0x7B))
    }

    @Test
    fun `editing and navigation keys`() {
        assertEquals(Key.Backspace, windowsVirtualKeyToComposeKey(0x08))
        assertEquals(Key.Tab, windowsVirtualKeyToComposeKey(0x09))
        assertEquals(Key.Enter, windowsVirtualKeyToComposeKey(0x0D))
        assertEquals(Key.Escape, windowsVirtualKeyToComposeKey(0x1B))
        assertEquals(Key.Spacebar, windowsVirtualKeyToComposeKey(0x20))
        assertEquals(Key.Delete, windowsVirtualKeyToComposeKey(0x2E))
        assertEquals(Key.MoveHome, windowsVirtualKeyToComposeKey(0x24))
        assertEquals(Key.MoveEnd, windowsVirtualKeyToComposeKey(0x23))
        assertEquals(Key.DirectionLeft, windowsVirtualKeyToComposeKey(0x25))
        assertEquals(Key.DirectionDown, windowsVirtualKeyToComposeKey(0x28))
        assertEquals(Key.PageUp, windowsVirtualKeyToComposeKey(0x21))
        assertEquals(Key.PageDown, windowsVirtualKeyToComposeKey(0x22))
    }

    @Test
    fun `unknown vk falls back to Key_Unknown`() {
        assertEquals(Key.Unknown, windowsVirtualKeyToComposeKey(0xFFFF))
        assertEquals(Key.Unknown, windowsVirtualKeyToComposeKey(0))
    }

    @Test
    fun `createKeyEvent carries type codePoint and modifiers`() {
        val down = createKeyEvent(
            vk = 0x41,
            scanCode = 30,
            isKeyDown = true,
            character = 'a',
            isCtrlPressed = true,
            isShiftPressed = true,
        )
        assertEquals(Key.A, down.key)
        assertEquals(KeyEventType.KeyDown, down.type)
        assertEquals('a'.code, down.utf16CodePoint)
        assertTrue(down.isCtrlPressed)
        assertTrue(down.isShiftPressed)
        assertNotEquals(true, down.isAltPressed)

        val up = createKeyEvent(vk = 0x41, scanCode = 30, isKeyDown = false, character = 'a')
        assertEquals(KeyEventType.KeyUp, up.type)
        // 抬起时不应该带字符（否则会重复输入）
        assertEquals(0, up.utf16CodePoint)
    }

    @Test
    fun `charToCodePoint`() {
        assertEquals(65, charToCodePoint('A'))
        assertEquals(0x4E2D, charToCodePoint('中'))
        assertEquals(0, charToCodePoint('\u0000'))
    }
}

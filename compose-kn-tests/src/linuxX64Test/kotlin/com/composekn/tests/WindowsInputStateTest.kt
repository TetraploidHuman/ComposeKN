package com.composekn.tests

import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import com.composekn.windows.MouseButton
import com.composekn.windows.WindowsInputState
import com.composekn.windows.internal.MK_ALT
import com.composekn.windows.internal.MK_CONTROL
import com.composekn.windows.internal.MK_SHIFT
import com.composekn.windows.internal.Win32Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 指针/修饰键状态机。
 */
class WindowsInputStateTest {

    @Test
    fun `position is tracked`() {
        val state = WindowsInputState()
        state.updatePosition(12, 34)
        assertEquals(12f, state.pointerX)
        assertEquals(34f, state.pointerY)
    }

    @Test
    fun `buttons map to primary secondary tertiary`() {
        val state = WindowsInputState()
        assertFalse(state.buttons().isPrimaryPressed)

        state.updateButton(MouseButton.Left, true)
        assertTrue(state.buttons().isPrimaryPressed)
        assertFalse(state.buttons().isSecondaryPressed)

        state.updateButton(MouseButton.Right, true)
        assertTrue(state.buttons().isSecondaryPressed)
        // 左键仍处于按下状态（多键同时按）
        assertTrue(state.buttons().isPrimaryPressed)

        state.updateButton(MouseButton.Left, false)
        assertFalse(state.buttons().isPrimaryPressed)
        assertTrue(state.buttons().isSecondaryPressed)

        state.updateButton(MouseButton.Middle, true)
        assertTrue(state.buttons().isTertiaryPressed)
    }

    @Test
    fun `extra buttons are ignored`() {
        val state = WindowsInputState()
        state.updateButton(MouseButton.Extra1, true)
        state.updateButton(MouseButton.Extra2, true)
        assertFalse(state.buttons().isPrimaryPressed)
        assertFalse(state.buttons().isSecondaryPressed)
        assertFalse(state.buttons().isTertiaryPressed)
    }

    @Test
    fun `bridge modifier bitmask decoding`() {
        val state = WindowsInputState()

        state.updateModifiers(Win32Modifier.CTRL)
        assertTrue(state.modifiers.isCtrlPressed)
        assertFalse(state.modifiers.isShiftPressed)
        assertFalse(state.modifiers.isAltPressed)
        assertFalse(state.modifiers.isMetaPressed)

        state.updateModifiers(Win32Modifier.SHIFT)
        assertTrue(state.modifiers.isShiftPressed)
        assertFalse(state.modifiers.isCtrlPressed)

        state.updateModifiers(Win32Modifier.ALT or Win32Modifier.META)
        assertTrue(state.modifiers.isAltPressed)
        assertTrue(state.modifiers.isMetaPressed)
        assertFalse(state.modifiers.isCtrlPressed)

        state.updateModifiers(0)
        assertFalse(state.modifiers.isCtrlPressed)
        assertFalse(state.modifiers.isAltPressed)
    }

    @Test
    fun `mouse wParam modifier decoding`() {
        val state = WindowsInputState()

        state.updateModifiersFromMouse(MK_CONTROL)
        assertTrue(state.modifiers.isCtrlPressed)
        // 老实现用桥接位去读 MK_*，Ctrl 会被判成 Shift
        assertFalse(state.modifiers.isShiftPressed)

        state.updateModifiersFromMouse(MK_SHIFT or MK_ALT)
        assertTrue(state.modifiers.isShiftPressed)
        assertTrue(state.modifiers.isAltPressed)
        assertFalse(state.modifiers.isCtrlPressed)
    }
}

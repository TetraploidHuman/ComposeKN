package com.composekn.windows

import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers

/**
 * Manages input state for Windows platform.
 */
internal class WindowsInputState {
    var pointerX = 0f
        private set
    var pointerY = 0f
        private set

    private var primaryPressed = false
    private var secondaryPressed = false
    private var tertiaryPressed = false

    var modifiers = PointerKeyboardModifiers()
        private set

    /**
     * Returns current pointer button state.
     */
    fun buttons(): PointerButtons = PointerButtons(
        isPrimaryPressed = primaryPressed,
        isSecondaryPressed = secondaryPressed,
        isTertiaryPressed = tertiaryPressed,
    )

    /**
     * Updates pointer position.
     */
    fun updatePosition(x: Int, y: Int) {
        pointerX = x.toFloat()
        pointerY = y.toFloat()
    }

    /**
     * Updates mouse button state.
     */
    fun updateButton(button: MouseButton, pressed: Boolean) {
        when (button) {
            MouseButton.Left -> primaryPressed = pressed
            MouseButton.Right -> secondaryPressed = pressed
            MouseButton.Middle -> tertiaryPressed = pressed
            else -> { /* Ignore extra buttons */ }
        }
    }

    /**
     * Updates keyboard modifiers from Windows key state.
     */
    fun updateModifiers(wParam: Int) {
        modifiers = PointerKeyboardModifiers(
            isShiftPressed = (wParam and 0x1000) != 0, // VK_SHIFT
            isCtrlPressed = (wParam and 0x2000) != 0, // VK_CONTROL
            isAltPressed = (wParam and 0x4000) != 0, // VK_MENU
            isMetaPressed = (wParam and 0x8000) != 0, // VK_LWIN
        )
    }

    /**
     * Updates keyboard modifiers from mouse message flags.
     */
    fun updateModifiersFromMouse(wParam: Int) {
        modifiers = PointerKeyboardModifiers(
            isShiftPressed = (wParam and MK_SHIFT) != 0,
            isCtrlPressed = (wParam and MK_CONTROL) != 0,
            isAltPressed = (wParam and MK_ALT) != 0,
            isMetaPressed = false,
        )
    }

    companion object {
        private const val MK_SHIFT = 0x0004
        private const val MK_CONTROL = 0x0008
        private const val MK_ALT = 0x0020
    }
}

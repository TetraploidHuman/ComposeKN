package com.composekn.windows

/**
 * Represents a Windows platform event.
 */
sealed class WindowsEvent {
    /**
     * Keyboard event.
     */
    data class KeyEvent(
        val virtualKeyCode: Int,
        val scanCode: Int,
        val isKeyDown: Boolean,
        val character: Char = '\u0000',
        val isExtendedKey: Boolean = false,
        val isAltPressed: Boolean = false,
        val isCtrlPressed: Boolean = false,
        val isShiftPressed: Boolean = false,
    ) : WindowsEvent()

    /**
     * Mouse move event.
     */
    data class MouseMoveEvent(
        val x: Int,
        val y: Int,
        val isLeftButton: Boolean = false,
        val isRightButton: Boolean = false,
        val isMiddleButton: Boolean = false,
        val isShiftPressed: Boolean = false,
        val isCtrlPressed: Boolean = false,
        val isAltPressed: Boolean = false,
    ) : WindowsEvent()

    /**
     * Mouse button event.
     */
    data class MouseButtonEvent(
        val x: Int,
        val y: Int,
        val button: MouseButton,
        val isPressed: Boolean,
        val isLeftButton: Boolean = false,
        val isRightButton: Boolean = false,
        val isMiddleButton: Boolean = false,
        val isShiftPressed: Boolean = false,
        val isCtrlPressed: Boolean = false,
        val isAltPressed: Boolean = false,
    ) : WindowsEvent()

    /**
     * Mouse wheel event.
     */
    data class MouseWheelEvent(
        val x: Int,
        val y: Int,
        val deltaX: Int,
        val deltaY: Int,
        val isShiftPressed: Boolean = false,
        val isCtrlPressed: Boolean = false,
        val isAltPressed: Boolean = false,
    ) : WindowsEvent()

    /**
     * Window resize event.
     */
    data class ResizeEvent(
        val width: Int,
        val height: Int,
    ) : WindowsEvent()

    /**
     * Window move event.
     */
    data class MoveEvent(
        val x: Int,
        val y: Int,
    ) : WindowsEvent()

    /**
     * Window close event.
     */
    object CloseEvent : WindowsEvent()

    /**
     * Window focus event.
     */
    data class FocusEvent(
        val hasFocus: Boolean,
    ) : WindowsEvent()

    /**
     * Paint event.
     */
    object PaintEvent : WindowsEvent()
}

/**
 * Mouse button enumeration.
 */
enum class MouseButton {
    Left,
    Right,
    Middle,
    Extra1,
    Extra2,
}

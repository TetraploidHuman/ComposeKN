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
     * Touch (or pen) event produced by WM_POINTER*.
     *
     * 关键点：触摸必须作为 PointerType.Touch 派发，不能当鼠标 —— Compose 的
     * scrollable 拒绝鼠标拖拽滚动
     * （foundation/gestures/AbstractScrollableNode.kt: canDrag = { it != PointerType.Mouse }），
     * 所以「触摸当鼠标」时点击能用、滑动不滚（真机反馈的问题）。
     */
    data class TouchEvent(
        /** Win32 指针 id（WM_POINTER 的 GET_POINTERID_WPARAM），多指时用来区分手指。 */
        val pointerId: Long,
        val x: Int,
        val y: Int,
        val phase: TouchPhase,
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

/**
 * 触摸触点状态（对应 WM_POINTERDOWN / WM_POINTERUPDATE / WM_POINTERUP）。
 */
enum class TouchPhase {
    Down,
    Move,
    Up,
}

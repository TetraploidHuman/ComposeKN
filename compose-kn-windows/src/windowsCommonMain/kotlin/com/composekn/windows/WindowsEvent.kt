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
     * IME（IMM32）组字开始。
     *
     * 文本框是 Compose 自绘的，系统侧没有 EDIT 控件，所以组字/提交只能由宿主
     * 从 IMM32 取出来交给 Compose 的文本输入层（见 WindowsTextInputService）。
     * 这条事件目前只用于诊断（Compose 侧的文本会话在输入框获得焦点时就已经开了）。
     */
    object ImeStartEvent : WindowsEvent()

    /**
     * IME 组字串更新（WM_IME_COMPOSITION + GCS_COMPSTR）。
     *
     * [text] 是当前实际输入的内容（含拼音等未转换部分），要交给 Compose 的
     * `setComposingText` 显示成带下划线的「组字中」文本；空串表示组字被清空。
     */
    data class ImeCompositionEvent(val text: String) : WindowsEvent()

    /**
     * IME 提交（WM_IME_COMPOSITION + GCS_RESULTSTR）：用户选定了候选词/上屏。
     * [text] 是要插入文本框的最终文本。
     */
    data class ImeCommitEvent(val text: String) : WindowsEvent()

    /** IME 组字结束（WM_IME_ENDCOMPOSITION，未提交）——需要把组字状态清掉。 */
    object ImeEndEvent : WindowsEvent()

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

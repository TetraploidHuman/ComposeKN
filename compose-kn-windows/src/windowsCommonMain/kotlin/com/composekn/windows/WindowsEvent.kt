package com.composekn.windows

/**
 * Win32 的 `WHEEL_DELTA`：一个带刻度滚轮的**一格**。
 *
 * 换算出 Compose 的「格」时要除以它 —— 而且必须用**浮点**除法，因为
 * `WM_MOUSEWHEEL` 的 `zDelta` 不保证是它的整数倍（见
 * [WindowsEvent.MouseWheelEvent] 的说明）。
 */
const val WIN32_WHEEL_DELTA = 120

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
     *
     * `deltaX`/`deltaY` 的单位是**「格」（= 1/120 个 WHEEL_DELTA）**，而且必须是**浮点**：
     *
     * Win32 的 `WM_MOUSEWHEEL` 只保证「一个带刻度的滚轮一格 = 120」，`zDelta` 本身可以是
     * **任意整数**（微软文档明确要求应用不要假设它是 120 的倍数）—— 触控板 / 自由滚轮会送来
     * 40、80、17 这种值。这里跟上游 Compose Desktop 的数据模型对齐：上游用的是 AWT 的
     * `MouseWheelEvent.getPreciseWheelRotation(): Double`，也就是「格」的浮点值
     * （`ComposeSceneMediator.desktop.kt: onMouseWheelEvent()` 直接把它塞进 `scrollDelta`，
     * 而 `MouseWheelScrollingLogic` / `DesktopScrollable.desktop.kt` 全程按 Float 处理）。
     *
     * 所以这里**不能**是 Int：以前宿主做的是 `rawDelta / 120` 的**整数除法**，
     * 一格以内的增量（40/80/…）会被截断成 0 —— 触控板「慢速完全不动、快滑一顿一顿」
     * 就是这么来的（HANDOVER §17.32）。0.333 格是合法输入。
     */
    data class MouseWheelEvent(
        val x: Int,
        val y: Int,
        val deltaX: Float,
        val deltaY: Float,
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
        /**
         * **真实事件时间**（C 侧由 `POINTER_INFO.dwTime` / `GetMessageTime()` 归一化出来的
         * 进程内毫秒）。必须原样喂给 `sendPointerEvent`，不能省：Compose 的速度估计器
         * 按时间轴做二次拟合，用「派发时刻」代替事件时间会把同一帧里到达的多条更新
         * 压成同一个时间戳，从而算出凭空的甩动速度（真机表现：松手后内容自己跳一段，
         * 见 HANDOVER §17.24）。
         *
         * 0 表示调用方没提供（合成事件 / 自检）：速度估计器拿到一串同时间戳的数据点，
         * 结果是无甩动 —— 确定且可复现，正是自检需要的语义。
         */
        val timeMillis: Long = 0L,
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
     * 「重新转换」（再変換）：输入法确认了要重转换的范围，应用要先把
     * `[start, end)` 这段**原文本变成选区**，接下来那段组字才会替换它
     * （不做这一步就会变成"原文还在、组字又插一份"）。
     *
     * 范围是文档坐标下的 UTF-16 code unit 偏移（由 C 侧的映射器给出，映射不了时
     * C 侧根本不会答应这次重转换，所以这里拿到的一定是能对上的范围）。
     */
    data class ImeReconvertSelectEvent(val start: Int, val end: Int) : WindowsEvent()

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

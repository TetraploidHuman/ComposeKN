@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import com.composekn.windows.internal.Win32Modifier

/**
 * Manages input state for Windows platform.
 */
class WindowsInputState {
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
     * Updates keyboard modifiers.
     */
    fun updateModifiers(
        isShiftPressed: Boolean,
        isCtrlPressed: Boolean,
        isAltPressed: Boolean,
        isMetaPressed: Boolean = false,
    ) {
        modifiers = PointerKeyboardModifiers(
            isShiftPressed = isShiftPressed,
            isCtrlPressed = isCtrlPressed,
            isAltPressed = isAltPressed,
            isMetaPressed = isMetaPressed,
        )
    }

    /**
     * Updates keyboard modifiers from the Win32 modifier bitmask produced by the
     * C bridge (bit0 = Shift, bit1 = Ctrl, bit2 = Alt, bit3 = Win).
     *
     * 旧实现读的是 0x1000/0x2000/0x4000 —— 那既不是 MK_* 也不是桥接用的位，
     * 任何输入都只会得到「全 false」。
     */
    fun updateModifiers(flags: Int) = updateModifiers(
        isShiftPressed = (flags and Win32Modifier.SHIFT) != 0,
        isCtrlPressed = (flags and Win32Modifier.CTRL) != 0,
        isAltPressed = (flags and Win32Modifier.ALT) != 0,
        isMetaPressed = (flags and Win32Modifier.META) != 0,
    )

    /**
     * Updates keyboard modifiers from a mouse message's wParam (MK_* flags).
     */
    fun updateModifiersFromMouse(wParam: Int) = updateModifiers(
        isShiftPressed = (wParam and MK_SHIFT) != 0,
        isCtrlPressed = (wParam and MK_CONTROL) != 0,
        isAltPressed = (wParam and MK_ALT) != 0,
        isMetaPressed = false,
    )

    /**
     * 当前按下的触摸触点（指针 id -> 位置，物理像素）。
     *
     * Compose 的多指 API 要求每次事件都带上**全部**活动触点（抬起的那个
     * 用 pressed=false 再带最后一次），所以必须自己维护这张表。
     */
    private val activeTouches = mutableMapOf<Long, Offset>()

    val activeTouchCount: Int get() = activeTouches.size

    /**
     * 「没有 DOWN 的 MOVE/UP」被丢掉的次数（诊断用）。
     *
     * 这不是异常输入：**笔在悬停时**（不接触数字转换器）Windows 会持续发
     * `WM_POINTERUPDATE`，到达这里时同样是 `TouchPhase.Move`。这类事件必须在
     * 触点表之外丢弃，理由见 [updateTouch]。
     */
    var droppedUntrackedTouchCount: Int = 0
        private set

    /** 打开后每一条触摸事件都会 println 一行（自检/真机排查触摸问题时用）。 */
    var debugTouchTrace: Boolean = false

    /**
     * 更新触点表，返回本次事件应该发给 Compose 的完整触点列表。
     *
     * **只有 `DOWN` 会新建触点**：没有先见过 DOWN 的 MOVE/UP 一律丢弃。
     *
     * 为什么必须丢（v0.5.10 的真机日志抓到的现场，见 HANDOVER §17.29）：笔悬停时
     * Windows 会一直发 `WM_POINTERUPDATE`（`POINTER_FLAG_INRANGE` 有、`INCONTACT`
     * 没有）。老实现把**任何**没见过的 id 都当成"按下"加进表里
     * （`pressed = phase != Up`），于是 Compose 那边凭空多出一根**按住的手指**：
     * `PointerInputChangeEventProducer` 对没见过的 id 取 `previousDown = false`
     * → `changedToDown` 成立 → 命中测试与手势识别器都以为有人按下。
     * 真机后果：悬停期间控件处于"被按住"状态（`fastAll { changedToUp() }` 不再成立
     * → 点击被吞）、单指手势被当成双指、速度估计器被喂进悬停轨迹。
     */
    fun updateTouch(event: WindowsEvent.TouchEvent): List<ComposeScenePointer> {
        val position = Offset(event.x.toFloat(), event.y.toFloat())
        val tracked = activeTouches.containsKey(event.pointerId)
        if (!tracked && event.phase != TouchPhase.Down) {
            // 悬停（或丢了 DOWN 的残事件）：不建触点、不派发。
            droppedUntrackedTouchCount++
            return emptyList()
        }
        // 抬起事件也要更新位置：Compose 要求 Release 事件带上该触点的最终位置。
        activeTouches[event.pointerId] = position
        val pressed = event.phase != TouchPhase.Up
        val pointers = activeTouches.map { (id, pos) ->
            ComposeScenePointer(
                id = PointerId(id),
                position = pos,
                pressed = if (id == event.pointerId) pressed else true,
                type = PointerType.Touch,
            )
        }
        if (event.phase == TouchPhase.Up) {
            // 抬起之后这个触点就不再是"活动"的了，下一个事件不能再带上它。
            activeTouches.remove(event.pointerId)
        }
        return pointers
    }

    companion object {
        private const val MK_SHIFT = 0x0004
        private const val MK_CONTROL = 0x0008
        private const val MK_ALT = 0x0020
    }
}

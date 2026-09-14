@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.scene.ComposeScene
import com.composekn.windows.internal.*

/**
 * Dispatch a Windows key event to the Compose scene.
 */
internal fun ComposeScene.dispatchWindowsKeyEvent(
    event: WindowsEvent.KeyEvent,
    inputState: WindowsInputState,
) {
    val key = windowsKeyToComposeKey(event.virtualKeyCode)
    val type = if (event.isKeyDown) KeyEventType.KeyDown else KeyEventType.KeyUp
    val codePoint = if (event.isKeyDown) event.character.code else 0

    val keyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
        isCtrlPressed = event.isCtrlPressed,
        isAltPressed = event.isAltPressed,
        isShiftPressed = event.isShiftPressed,
        isMetaPressed = false,
        nativeEvent = event,
    )
    sendKeyEvent(keyEvent)
}

/**
 * Dispatch a Windows mouse event to the Compose scene.
 */
internal fun ComposeScene.dispatchWindowsMouseEvent(
    event: WindowsEvent.MouseMoveEvent,
    inputState: WindowsInputState,
) {
    inputState.updatePosition(event.x, event.y)
    sendPointerEvent(
        eventType = PointerEventType.Move,
        position = Offset(event.x.toFloat(), event.y.toFloat()),
        buttons = inputState.buttons(),
        keyboardModifiers = inputState.modifiers,
        nativeEvent = event,
    )
}

/**
 * Dispatch a Windows mouse button event to the Compose scene.
 */
internal fun ComposeScene.dispatchWindowsMouseButtonEvent(
    event: WindowsEvent.MouseButtonEvent,
    inputState: WindowsInputState,
) {
    inputState.updatePosition(event.x, event.y)
    inputState.updateButton(event.button, event.isPressed)

    val pointerButton = when (event.button) {
        MouseButton.Left -> PointerButton.Primary
        MouseButton.Right -> PointerButton.Secondary
        MouseButton.Middle -> PointerButton.Tertiary
        else -> PointerButton(event.button.ordinal)
    }

    sendPointerEvent(
        eventType = if (event.isPressed) PointerEventType.Press else PointerEventType.Release,
        position = Offset(event.x.toFloat(), event.y.toFloat()),
        buttons = inputState.buttons(),
        keyboardModifiers = inputState.modifiers,
        nativeEvent = event,
        button = pointerButton,
    )
}

/**
 * Dispatch a Windows touch event to the Compose scene.
 *
 * 触摸必须走 [PointerType.Touch] 的多指 API，不能走鼠标那条路：
 * Compose 的 scrollable 明确拒绝鼠标拖拽滚动，只有 Touch 才会被手势识别器
 * 接受（拖动滚动 + 松手后的甩动惯性）。真机上的「点击正常、滑动不滚」
 * 就是「触摸被当成鼠标」造成的。
 */
internal fun ComposeScene.dispatchWindowsTouchEvent(
    event: WindowsEvent.TouchEvent,
    inputState: WindowsInputState,
) {
    // 多指 API 要求每次带上全部活动触点，状态表在 inputState 里维护。
    val pointers = inputState.updateTouch(event)
    if (pointers.isEmpty()) return

    val eventType = when (event.phase) {
        TouchPhase.Down -> PointerEventType.Press
        TouchPhase.Move -> PointerEventType.Move
        TouchPhase.Up -> PointerEventType.Release
    }
    val result = sendPointerEvent(
        eventType = eventType,
        pointers = pointers,
        buttons = PointerButtons(),
        keyboardModifiers = inputState.modifiers,
        nativeEvent = event,
        button = null,
    )
    // 诊断：result 的 bit0 = 派发到了某个 pointerInput 节点，bit1 = 移动被消费，
    // bit2 = 变化被消费。「能点到但滑不动」时这一行就能区分是「事件没到控件」
    // （bit0=0）还是「到了但手势没认出来」（bit0=1 而 bit1=0）。
    // 实测：正常拖动 Move 的 result=7；触摸被当成鼠标时全是 1。
    if (inputState.debugTouchTrace) {
        println(
            "TOUCHDBG phase=${event.phase} id=${event.pointerId} pos=${event.x},${event.y} " +
                "pointers=${pointers.size} result=$result",
        )
    }
}

/**
 * Dispatch a Windows mouse wheel event to the Compose scene.
 */
internal fun ComposeScene.dispatchWindowsMouseWheelEvent(
    event: WindowsEvent.MouseWheelEvent,
    inputState: WindowsInputState,
) {
    // 符号约定（血泪教训，别改成取负）：
    //   Win32 WM_MOUSEWHEEL 的 delta/120 > 0 = 滚轮向远离用户方向 = 向上滚（看更早的内容）。
    //   scrollable 内部 `reverseDirection` 对 verticalScroll/LazyColumn 默认是 true，
    //   `canConsumeDelta` / `dispatchMouseWheelScroll` 都会先做一次 reverseIfNeeded()：
    //     scrollDelta.y < 0 → 向上滚；scrollDelta.y > 0 → 向下滚。
    //   因此这里**原样透传**。曾经写成 -deltaY（以为「正数 = 向下」），结果
    //   canConsume=false（value=0 时判定「无法向上滚」）→ 滚轮整体失效，
    //   由自检 interaction/wheel-scroll 抓到（HANDOVER §14.4）。
    val scrollDelta = Offset(
        event.deltaX.toFloat(),
        event.deltaY.toFloat(),
    )

    sendPointerEvent(
        eventType = PointerEventType.Scroll,
        position = Offset(event.x.toFloat(), event.y.toFloat()),
        scrollDelta = scrollDelta,
        buttons = inputState.buttons(),
        keyboardModifiers = inputState.modifiers,
        nativeEvent = event,
    )
}

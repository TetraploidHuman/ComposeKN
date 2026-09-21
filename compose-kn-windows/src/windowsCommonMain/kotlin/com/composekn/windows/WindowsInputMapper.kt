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
        // 真实事件时间（毫秒）。**不要删**：Compose 的甩动速度估计器按时间轴拟合，
        // 省略这个参数时会退回 sendPointerEvent 的默认值 `currentTimeMillis()` ——
        // 那是「派发时刻」，而窗口循环是先把消息泵里的触摸事件一次全部派发再渲染，
        // 于是同一帧里到达的几条更新共用一个毫秒。时间轴被压扁 → 算出假的甩动速度
        // → 真机上「松手后列表自己跳一段」（HANDOVER §17.24）。
        timeMillis = event.timeMillis,
        nativeEvent = event,
        button = null,
    )
    // 诊断：result 的 bit0 = 派发到了某个 pointerInput 节点，bit1 = 移动被消费，
    // bit2 = 变化被消费。「能点到但滑不动」时这一行就能区分是「事件没到控件」
    // （bit0=0）还是「到了但手势没认出来」（bit0=1 而 bit1=0）。
    // 实测：正常拖动 Move 的 result=7；触摸被当成鼠标时全是 1。
    if (inputState.debugTouchTrace) {
        println(
            "TOUCHDBG phase=${event.phase} id=${event.pointerId} t=${event.timeMillis} " +
                "pos=${event.x},${event.y} pointers=${pointers.size} result=$result",
        )
    }
}

/**
 * Dispatch a Windows mouse wheel event to the Compose scene.
 *
 * 三条通道合成一个 `scrollDelta`：
 *
 *  1. **竖直滚轮**（`WM_MOUSEWHEEL`）→ `Offset(0, deltaY)`；
 *  2. **横向滚轮 / 触控板横滑**（`WM_MOUSEHWHEEL`）→ `Offset(deltaX, 0)`；
 *  3. **Shift + 竖直滚轮** → `Offset(deltaY, 0)` —— 这一条**是对齐上游 Compose Desktop 的
 *     关键**：`ComposeSceneMediator.desktop.kt: onMouseWheelEvent()` 里
 *     `scrollDelta = if (event.isShiftDown) Offset(wheelRotation, 0f) else Offset(0f, wheelRotation)`。
 *     为什么非要有它：`MouseWheelScrollingLogic.canConsumeDelta()` 用
 *     `Scrollable.toSingleAxisDeltaFromAngle()`（阈值 PI/4）把二维 delta 投到滚动轴上 ——
 *     **横向滚动条对纯竖直的 delta 直接返回 0**（不消费）。所以 Shift+滚轮（或真的横向滚轮/
 *     触控板横滑）是"横向列表能用滚轮滚"的**唯一**途径，缺了它 `LazyRow` 就只能靠触摸
 *     （真机反馈「横向列表没法用滚轮滚动」，HANDOVER §17.30）。
 *
 * 精度：delta 是**浮点「格」**（1 格 = 120 个 WHEEL_DELTA 单位）。Win32 只保证
 * 「带刻度滚轮一格 = 120」，zDelta 本身可以是任意整数（触控板/自由滚轮），所以换算是
 * `zDelta / 120f` 而不是整数除法 —— 换算后的 0.333 格必须原样到达 Compose
 * （HANDOVER §17.32）。
 *
 * 符号约定（血泪教训，别改成取负）：
 *   Win32 WM_MOUSEWHEEL 的 delta/120 > 0 = 滚轮向远离用户方向 = 向上滚（看更早的内容）。
 *   scrollable 内部 `reverseDirection` 对 verticalScroll/LazyColumn 默认是 true，
 *   `canConsumeDelta` / `dispatchMouseWheelScroll` 都会先做一次 reverseIfNeeded()：
 *     scrollDelta.y < 0 → 向上滚；scrollDelta.y > 0 → 向下滚。
 *   因此这里**原样透传**（Shift 那条也只是换轴、不取负，与上游一致）。曾经写成 -deltaY
 *   （以为「正数 = 向下」），结果 canConsume=false（value=0 时判定「无法向上滚」）→
 *   滚轮整体失效，由自检 interaction/wheel-scroll 抓到（HANDOVER §14.4）。
 */
internal fun ComposeScene.dispatchWindowsMouseWheelEvent(
    event: WindowsEvent.MouseWheelEvent,
    inputState: WindowsInputState,
) {
    val shiftPressed = event.isShiftPressed || inputState.modifiers.isShiftPressed
    val horizontalWheel = event.deltaX != 0f
    // delta 是**浮点**的「格」：触控板/自由滚轮的 WM_MOUSEWHEEL zDelta 不一定被 120 整除
    // （40/80/17…），宿主换算出来就是 0.333 这种值。这里原样透传给 Compose 的
    // `MouseWheelScrollingLogic`（它和 `DesktopScrollable.desktop.kt` 全程按 Float 处理，
    // 上游 AWT 用的就是 `getPreciseWheelRotation(): Double`）——**不要**取整、不要乘系数。
    val scrollDelta = when {
        horizontalWheel -> Offset(event.deltaX, 0f)
        shiftPressed -> Offset(event.deltaY, 0f)
        else -> Offset(0f, event.deltaY)
    }

    sendPointerEvent(
        eventType = PointerEventType.Scroll,
        position = Offset(event.x.toFloat(), event.y.toFloat()),
        scrollDelta = scrollDelta,
        buttons = inputState.buttons(),
        keyboardModifiers = inputState.modifiers,
        nativeEvent = event,
    )
}

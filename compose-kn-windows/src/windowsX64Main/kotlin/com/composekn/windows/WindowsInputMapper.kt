@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
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
 * Dispatch a Windows mouse wheel event to the Compose scene.
 */
internal fun ComposeScene.dispatchWindowsMouseWheelEvent(
    event: WindowsEvent.MouseWheelEvent,
    inputState: WindowsInputState,
) {
    val scrollDelta = Offset(
        event.deltaX.toFloat(),
        -event.deltaY.toFloat(),
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

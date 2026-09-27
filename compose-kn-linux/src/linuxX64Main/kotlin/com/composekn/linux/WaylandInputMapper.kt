@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.composekn.linux

import androidx.compose.ui.draganddrop.DragAndDropEvent
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
import org.jetbrains.skiko.WaylandEvent
import org.jetbrains.skiko.WaylandEventType
import org.jetbrains.skiko.WaylandWindow

internal class WaylandInputState {
    var pointerX = 0f
    var pointerY = 0f
    private var primaryPressed = false
    private var secondaryPressed = false
    private var tertiaryPressed = false
    var modifiers = PointerKeyboardModifiers()

    fun buttons(): PointerButtons = PointerButtons(
        isPrimaryPressed = primaryPressed,
        isSecondaryPressed = secondaryPressed,
        isTertiaryPressed = tertiaryPressed,
    )

    fun updateButton(button: Int, pressed: Boolean) {
        when (button) {
            272 -> primaryPressed = pressed
            273 -> secondaryPressed = pressed
            274 -> tertiaryPressed = pressed
        }
    }

    fun syncWaylandModifiers(wlModifiers: Int) {
        modifiers = PointerKeyboardModifiers(
            isShiftPressed = (wlModifiers and WL_KEYBOARD_MODIFIER_SHIFT) != 0,
            isCtrlPressed = (wlModifiers and WL_KEYBOARD_MODIFIER_CTRL) != 0,
            isAltPressed = (wlModifiers and WL_KEYBOARD_MODIFIER_ALT) != 0,
            isMetaPressed = (wlModifiers and WL_KEYBOARD_MODIFIER_MOD4) != 0,
        )
    }
}

// wl_keyboard.modifiers bitmask (wayland-client-protocol.h)
private const val WL_KEYBOARD_MODIFIER_SHIFT = 0x1
private const val WL_KEYBOARD_MODIFIER_CTRL = 0x4
private const val WL_KEYBOARD_MODIFIER_ALT = 0x8
private const val WL_KEYBOARD_MODIFIER_MOD4 = 0x40

internal fun ComposeScene.dispatchWaylandKeyEvent(
    event: WaylandEvent,
    inputState: WaylandInputState,
    backNavigationInput: LinuxBackNavigationInput,
) {
    inputState.syncWaylandModifiers(event.modifiers)
    val key = waylandKeyToComposeKey(event.keyCode, event.keysym)
    val type = if (event.pressed) KeyEventType.KeyDown else KeyEventType.KeyUp
    val codePoint = if (event.pressed) keysymToCodePoint(event.keysym) else 0
    val keyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
        isCtrlPressed = inputState.modifiers.isCtrlPressed,
        isAltPressed = inputState.modifiers.isAltPressed,
        isShiftPressed = inputState.modifiers.isShiftPressed,
        isMetaPressed = inputState.modifiers.isMetaPressed,
        nativeEvent = event,
    )
    if (backNavigationInput.onKeyEvent(keyEvent)) {
        return
    }
    sendKeyEvent(keyEvent)
}

internal fun ComposeScene.dispatchWaylandEvent(
    event: WaylandEvent,
    contentScale: Float,
    inputState: WaylandInputState,
) {
    val position = Offset(event.x * contentScale, event.y * contentScale)
    inputState.syncWaylandModifiers(event.modifiers)
    when (event.type) {
        WaylandEventType.PointerEnter,
        WaylandEventType.PointerMotion -> {
            inputState.pointerX = position.x
            inputState.pointerY = position.y
            sendPointerEvent(
                eventType = PointerEventType.Move,
                position = position,
                buttons = inputState.buttons(),
                keyboardModifiers = inputState.modifiers,
                nativeEvent = event,
            )
        }
        WaylandEventType.PointerLeave -> {
            sendPointerEvent(
                eventType = PointerEventType.Exit,
                position = position,
                buttons = inputState.buttons(),
                keyboardModifiers = inputState.modifiers,
                nativeEvent = event,
            )
        }
        WaylandEventType.PointerButton -> {
            inputState.pointerX = position.x
            inputState.pointerY = position.y
            inputState.updateButton(event.button, event.pressed)
            val pointerButton = when (event.button) {
                272 -> PointerButton.Primary
                273 -> PointerButton.Secondary
                274 -> PointerButton.Tertiary
                else -> PointerButton(event.button)
            }
            sendPointerEvent(
                eventType = if (event.pressed) PointerEventType.Press else PointerEventType.Release,
                position = position,
                buttons = inputState.buttons(),
                keyboardModifiers = inputState.modifiers,
                nativeEvent = event,
                button = pointerButton,
            )
        }
        WaylandEventType.PointerAxis -> {
            // 符号约定与 Windows 一致（见 WindowsInputMapper.dispatchWindowsMouseWheelEvent）：
            // wl_pointer.axis 的 value 是「沿轴的相对位移向量」，负数 = 向下滚；
            // scrollable 的 reverseDirection 会再反转一次，所以这里原样透传。
            val scrollDelta = when (event.axis) {
                0 -> Offset(0f, event.axisValue * contentScale)
                1 -> Offset(event.axisValue * contentScale, 0f)
                else -> Offset.Zero
            }
            sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = position,
                scrollDelta = scrollDelta,
                buttons = inputState.buttons(),
                keyboardModifiers = inputState.modifiers,
                nativeEvent = event,
            )
        }
        WaylandEventType.TouchDown,
        WaylandEventType.TouchMotion,
        WaylandEventType.TouchUp -> {
            inputState.pointerX = position.x
            inputState.pointerY = position.y
            val pointerEventType = when (event.type) {
                WaylandEventType.TouchDown -> PointerEventType.Press
                WaylandEventType.TouchMotion -> PointerEventType.Move
                else -> PointerEventType.Release
            }
            sendPointerEvent(
                eventType = pointerEventType,
                position = position,
                buttons = PointerButtons(),
                keyboardModifiers = inputState.modifiers,
                nativeEvent = event,
                type = PointerType.Touch,
            )
        }
        WaylandEventType.Key -> Unit
        WaylandEventType.Focus -> Unit
        WaylandEventType.DragEnter,
        WaylandEventType.DragOver,
        WaylandEventType.DragLeave,
        WaylandEventType.DragDrop -> Unit
        WaylandEventType.Scale,
        WaylandEventType.Frame -> Unit
    }
}

/**
 * 入站拖放：对齐 Windows `dispatchWindowsDragEvent`。
 *
 * Enter/Over 时尚无真实负载（Wayland 只在 drop 时 receive），用 event.button 位
 * （1=uri 2=text）填占位，让 `shouldStartDragAndDrop` 能接受；Drop 时再 pop 真数据。
 *
 * @return 是否接受（写回 `dnd_set_accept`）。
 */
@Suppress("DEPRECATION")
internal fun ComposeScene.dispatchWaylandDragEvent(
    event: WaylandEvent,
    contentScale: Float,
    window: WaylandWindow,
): Boolean {
    val root = rootDragAndDropNode
    val position = Offset(event.x * contentScale, event.y * contentScale)
    val hasUri = (event.button and 1) != 0
    val hasText = (event.button and 2) != 0

    val files: List<String>
    val text: String?
    if (event.type == WaylandEventType.DragDrop) {
        files = window.dndPopFiles()
        text = window.dndPopText()
    } else {
        // 占位：非空 list / 非 null text，真实路径/内容在 Drop 才有
        files = if (hasUri) listOf("") else emptyList()
        text = if (hasText) "" else null
    }

    val dragEvent = DragAndDropEvent.forPlatformDrop(
        position = position,
        files = files,
        text = text,
    )
    return when (event.type) {
        WaylandEventType.DragEnter -> {
            val accepted = root.acceptDragAndDropTransfer(dragEvent)
            if (accepted) {
                root.onStarted(dragEvent)
                root.onEntered(dragEvent)
            }
            accepted
        }
        WaylandEventType.DragOver -> {
            root.onMoved(dragEvent)
            root.hasEligibleDropTarget
        }
        WaylandEventType.DragLeave -> {
            root.onExited(dragEvent)
            root.onEnded(dragEvent)
            false
        }
        WaylandEventType.DragDrop -> {
            val consumed = root.onDrop(dragEvent)
            root.onEnded(dragEvent)
            if (!consumed) {
                println("composekn: drag onDrop 未被任何 dragAndDropTarget 消费")
            }
            false
        }
        else -> false
    }
}

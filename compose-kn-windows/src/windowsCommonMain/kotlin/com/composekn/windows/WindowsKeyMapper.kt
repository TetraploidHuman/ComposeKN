@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.composekn.windows

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent

/**
 * Maps Windows virtual key codes to Compose Key values.
 */
fun windowsVirtualKeyToComposeKey(vk: Int): Key {
    return windowsVkToKeyMap[vk] ?: Key.Unknown
}

fun windowsKeyToComposeKey(vk: Int): Key {
    return windowsVirtualKeyToComposeKey(vk)
}

/**
 * 表里重复出现的 VK（正常必须是空的）。
 *
 * 用 `mapOf(...)` 字面量时重复键会被静默覆盖 —— 历史上 0x5C 同时写了
 * MetaRight 和 Backslash，导致「右 Win 键映射成反斜杠」。这里保留原始
 * pair 列表，让 :compose-kn-tests 能直接断言「无重复」。
 */
fun duplicateVkEntries(): List<Int> =
    vkPairs.groupingBy { it.first }.eachCount().filterValues { it > 1 }.keys.sorted()

fun vkPairCount(): Int = vkPairs.size

// Windows Virtual Key codes to Compose Key mapping
private val vkPairs: List<Pair<Int, Key>> = listOf(
    // Modifier keys
    0x10 to Key.ShiftLeft,       // VK_SHIFT
    0x11 to Key.CtrlLeft,        // VK_CONTROL
    0x12 to Key.AltLeft,         // VK_MENU
    0x5B to Key.MetaLeft,        // VK_LWIN
    0x5C to Key.MetaRight,       // VK_RWIN
    0xA0 to Key.ShiftLeft,       // VK_LSHIFT
    0xA1 to Key.ShiftRight,      // VK_RSHIFT
    0xA2 to Key.CtrlLeft,        // VK_LCONTROL
    0xA3 to Key.CtrlRight,       // VK_RCONTROL
    0xA4 to Key.AltLeft,         // VK_LMENU
    0xA5 to Key.AltRight,        // VK_RMENU

    // Control keys
    0x08 to Key.Backspace,       // VK_BACK
    0x09 to Key.Tab,             // VK_TAB
    0x0D to Key.Enter,           // VK_RETURN
    0x13 to Key.Break,      // VK_PAUSE
    0x14 to Key.CapsLock,        // VK_CAPITAL
    0x1B to Key.Escape,          // VK_ESCAPE
    0x20 to Key.Spacebar,        // VK_SPACE

    // Navigation keys
    0x21 to Key.PageUp,          // VK_PRIOR
    0x22 to Key.PageDown,        // VK_NEXT
    0x23 to Key.MoveEnd,         // VK_END
    0x24 to Key.MoveHome,        // VK_HOME
    0x25 to Key.DirectionLeft,   // VK_LEFT
    0x26 to Key.DirectionUp,     // VK_UP
    0x27 to Key.DirectionRight,  // VK_RIGHT
    0x28 to Key.DirectionDown,   // VK_DOWN
    0x2D to Key.Insert,          // VK_INSERT
    0x2E to Key.Delete,          // VK_DELETE

    // Number keys (0-9)
    0x30 to Key.Zero,
    0x31 to Key.One,
    0x32 to Key.Two,
    0x33 to Key.Three,
    0x34 to Key.Four,
    0x35 to Key.Five,
    0x36 to Key.Six,
    0x37 to Key.Seven,
    0x38 to Key.Eight,
    0x39 to Key.Nine,

    // Letter keys (A-Z)
    0x41 to Key.A,
    0x42 to Key.B,
    0x43 to Key.C,
    0x44 to Key.D,
    0x45 to Key.E,
    0x46 to Key.F,
    0x47 to Key.G,
    0x48 to Key.H,
    0x49 to Key.I,
    0x4A to Key.J,
    0x4B to Key.K,
    0x4C to Key.L,
    0x4D to Key.M,
    0x4E to Key.N,
    0x4F to Key.O,
    0x50 to Key.P,
    0x51 to Key.Q,
    0x52 to Key.R,
    0x53 to Key.S,
    0x54 to Key.T,
    0x55 to Key.U,
    0x56 to Key.V,
    0x57 to Key.W,
    0x58 to Key.X,
    0x59 to Key.Y,
    0x5A to Key.Z,

    // Numpad keys
    0x60 to Key.NumPad0,
    0x61 to Key.NumPad1,
    0x62 to Key.NumPad2,
    0x63 to Key.NumPad3,
    0x64 to Key.NumPad4,
    0x65 to Key.NumPad5,
    0x66 to Key.NumPad6,
    0x67 to Key.NumPad7,
    0x68 to Key.NumPad8,
    0x69 to Key.NumPad9,
    0x6A to Key.NumPadMultiply,
    0x6B to Key.NumPadAdd,
    0x6C to Key.NumPadEnter,
    0x6D to Key.NumPadSubtract,
    0x6E to Key.NumPadDot,
    0x6F to Key.NumPadDivide,

    // Function keys
    0x70 to Key.F1,
    0x71 to Key.F2,
    0x72 to Key.F3,
    0x73 to Key.F4,
    0x74 to Key.F5,
    0x75 to Key.F6,
    0x76 to Key.F7,
    0x77 to Key.F8,
    0x78 to Key.F9,
    0x79 to Key.F10,
    0x7A to Key.F11,
    0x7B to Key.F12,

    // Special keys
    // VK_OEM_5 = 0xDC 才是反斜杠；0x5C 是 VK_RWIN。
    // 旧表里 0x5C 被写了两次（MetaRight + Backslash），后者覆盖前者，
    // 结果是「右 Win 键 → 反斜杠」且 MetaRight 永远取不到。
    0xDC to Key.Backslash,
    0xBF to Key.Slash,
    0xBA to Key.Semicolon,
    0xBB to Key.Equals,
    0xBC to Key.Comma,
    0xBD to Key.Minus,
    0xBE to Key.Period,
    0xC0 to Key.Grave,
    0xDB to Key.LeftBracket,
    0xDD to Key.RightBracket,
    0xDE to Key.Apostrophe,
)

/** 查表用的实现（必须声明在 [vkPairs] 之后：顶层属性按文本顺序初始化）。 */
internal val windowsVkToKeyMap: Map<Int, Key> =
    HashMap<Int, Key>(vkPairs.size * 2).apply { vkPairs.forEach { put(it.first, it.second) } }

// Convert character from WM_CHAR to code point
fun charToCodePoint(char: Char): Int {
    return char.code
}

// Create key event from Windows key message
fun createKeyEvent(
    vk: Int,
    scanCode: Int,
    isKeyDown: Boolean,
    character: Char = '\u0000',
    isExtendedKey: Boolean = false,
    isAltPressed: Boolean = false,
    isCtrlPressed: Boolean = false,
    isShiftPressed: Boolean = false,
): KeyEvent {
    val key = windowsKeyToComposeKey(vk)
    val type = if (isKeyDown) KeyEventType.KeyDown else KeyEventType.KeyUp
    val codePoint = if (isKeyDown) charToCodePoint(character) else 0

    return KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
        isCtrlPressed = isCtrlPressed,
        isAltPressed = isAltPressed,
        isShiftPressed = isShiftPressed,
        isMetaPressed = false,
        nativeEvent = null,
    )
}

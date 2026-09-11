package com.composekn.linux

import androidx.compose.ui.input.key.Key

/**
 * Maps Linux evdev scancodes to macOS virtual key codes used by [Key] on linuxX64.
 * Layout-aware characters come from XKB keysyms; physical identity uses evdev → macOS.
 */
internal fun evdevToMacKeyCode(evdev: Int): Long? = evdevToMacKeyCodeMap[evdev]

internal fun keysymToCodePoint(keysym: Int): Int {
    if (keysym == 0) return 0
    // Unicode scalar values produced by xkbcommon for typed keys.
    if (keysym in 0x20..0x10FFFF && keysym < 0xFE00) {
        return keysym
    }
    return 0
}

internal fun waylandKeyToComposeKey(evdev: Int, keysym: Int): Key {
    keysymToComposeKey[keysym]?.let { return it }
    evdevToMacKeyCode(evdev)?.let { return Key(it) }
    return Key.Unknown
}

// macOS key codes from androidx.compose.ui.input.key.Key (linuxX64 actual).
private val evdevToMacKeyCodeMap: Map<Int, Long> = mapOf(
    1 to 53L,    // Escape
    2 to 18L, 3 to 19L, 4 to 20L, 5 to 21L, 6 to 23L, 7 to 22L, 8 to 26L, 9 to 28L, 10 to 25L, 11 to 29L, // 0-9
    12 to 27L,   // -
    13 to 24L,   // =
    14 to 51L,   // Backspace
    15 to 48L,   // Tab
    16 to 12L, 17 to 13L, 18 to 14L, 19 to 15L, 20 to 17L, 21 to 16L, 22 to 32L, 23 to 34L, 24 to 31L, 25 to 35L, // Q-P
    26 to 33L,   // [
    27 to 30L,   // ]
    28 to 36L,   // Enter
    29 to 59L,   // Left Ctrl
    30 to 0L, 31 to 1L, 32 to 2L, 33 to 3L, 34 to 5L, 35 to 4L, 36 to 38L, 37 to 40L, 38 to 37L, // A-L
    39 to 41L,   // ;
    40 to 39L,   // '
    41 to 50L,   // `
    42 to 56L,   // Left Shift
    43 to 44L,   // backslash
    44 to 6L, 45 to 7L, 46 to 8L, 47 to 9L, 48 to 11L, 49 to 45L, 50 to 46L, // Z-M
    51 to 43L,   // ,
    52 to 47L,   // .
    53 to 42L,   // /
    54 to 60L,   // Right Shift
    55 to 67L,   // KpMultiply
    56 to 58L,   // Left Alt
    57 to 49L,   // Space
    58 to 57L,   // CapsLock
    59 to 122L, 60 to 120L, 61 to 99L, 62 to 118L, 63 to 96L, 64 to 97L, // F1-F6
    65 to 98L, 66 to 100L, 67 to 101L, 68 to 109L, // F7-F10
    87 to 103L, 88 to 111L, // F11-F12
    71 to 89L, 72 to 91L, 73 to 92L, // Kp7-Kp9
    75 to 86L, 76 to 87L, 77 to 88L, // Kp4-Kp6
    79 to 83L, 80 to 84L, 81 to 85L, // Kp1-Kp3
    82 to 82L, // Kp0
    78 to 69L,   // KpAdd
    96 to 76L,   // KpEnter
    98 to 75L,   // KpDivide
    74 to 78L,   // KpSubtract
    83 to 65L,   // KpDot (NumPadDot placeholder uses unsupported; map to closest)
    97 to 62L,   // Right Ctrl
    100 to 61L,  // Right Alt
    102 to 115L, // Home
    103 to 126L, // Up
    104 to 116L, // PageUp
    105 to 123L, // Left
    106 to 124L, // Right
    107 to 119L, // End
    108 to 125L, // Down
    109 to 121L, // PageDown
    110 to 114L, // Insert
    111 to 117L, // Delete
    125 to 55L,  // Left Meta
    126 to 54L,  // Right Meta
)

// XKB keysyms for keys whose identity is layout-independent.
private val keysymToComposeKey: Map<Int, Key> = mapOf(
    0xFF08 to Key.Backspace,
    0xFF09 to Key.Tab,
    0xFF0D to Key.Enter,
    0xFF1B to Key.Escape,
    0xFF51 to Key.DirectionLeft,
    0xFF52 to Key.DirectionUp,
    0xFF53 to Key.DirectionRight,
    0xFF54 to Key.DirectionDown,
    0xFF50 to Key.MoveHome,
    0xFF57 to Key.MoveEnd,
    0xFF55 to Key.PageUp,
    0xFF56 to Key.PageDown,
    0xFF63 to Key.Insert,
    0xFFFF to Key.Delete,
    0xFFBE to Key.F1, 0xFFBF to Key.F2, 0xFFC0 to Key.F3, 0xFFC1 to Key.F4,
    0xFFC2 to Key.F5, 0xFFC3 to Key.F6, 0xFFC4 to Key.F7, 0xFFC5 to Key.F8,
    0xFFC6 to Key.F9, 0xFFC7 to Key.F10, 0xFFC8 to Key.F11, 0xFFC9 to Key.F12,
    0xFFE1 to Key.ShiftLeft, 0xFFE2 to Key.ShiftRight,
    0xFFE3 to Key.CtrlLeft, 0xFFE4 to Key.CtrlRight,
    0xFFE9 to Key.AltLeft, 0xFFEA to Key.AltRight,
    0xFFEB to Key.MetaLeft, 0xFFEC to Key.MetaRight,
    0xFFE5 to Key.CapsLock,
    0xFF9E to Key.NumPadSubtract,
    0xFFAA to Key.NumPadMultiply,
    0xFFAB to Key.NumPadAdd,
    0xFFAF to Key.NumPadDivide,
    0xFF8D to Key.NumPadEnter,
    0xFFB0 to Key.NumPad0, 0xFFB1 to Key.NumPad1, 0xFFB2 to Key.NumPad2, 0xFFB3 to Key.NumPad3,
    0xFFB4 to Key.NumPad4, 0xFFB5 to Key.NumPad5, 0xFFB6 to Key.NumPad6, 0xFFB7 to Key.NumPad7,
    0xFFB8 to Key.NumPad8, 0xFFB9 to Key.NumPad9,
)

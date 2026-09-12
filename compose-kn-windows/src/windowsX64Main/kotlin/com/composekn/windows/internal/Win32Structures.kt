package com.composekn.windows.internal

import kotlinx.cinterop.*

// Windows message constants
const val WM_DESTROY = 0x0002
const val WM_CLOSE = 0x0010
const val WM_PAINT = 0x000F
const val WM_SIZE = 0x0005
const val WM_MOVE = 0x0003
const val WM_KEYDOWN = 0x0100
const val WM_KEYUP = 0x0101
const val WM_CHAR = 0x0102
const val WM_SYSKEYDOWN = 0x0104
const val WM_SYSKEYUP = 0x0105
const val WM_MOUSEMOVE = 0x0200
const val WM_LBUTTONDOWN = 0x0201
const val WM_LBUTTONUP = 0x0202
const val WM_RBUTTONDOWN = 0x0204
const val WM_RBUTTONUP = 0x0205
const val WM_MBUTTONDOWN = 0x0207
const val WM_MBUTTONUP = 0x0208
const val WM_MOUSEWHEEL = 0x020A
const val WM_SETFOCUS = 0x0007
const val WM_KILLFOCUS = 0x0008

// Window styles
const val WS_OVERLAPPEDWINDOW = 0x00CF0000u
const val WS_VISIBLE = 0x10000000u
const val WS_POPUP: UInt = 0x80000000u

// Show window commands
const val SW_SHOW = 5
const val SW_HIDE = 0
const val SW_MINIMIZE = 6
const val SW_MAXIMIZE = 3
const val SW_RESTORE = 9

// Virtual key codes
const val VK_BACK = 0x08
const val VK_TAB = 0x09
const val VK_RETURN = 0x0D
const val VK_SHIFT = 0x10
const val VK_CONTROL = 0x11
const val VK_MENU = 0x12
const val VK_PAUSE = 0x13
const val VK_CAPITAL = 0x14
const val VK_ESCAPE = 0x1B
const val VK_SPACE = 0x20
const val VK_PRIOR = 0x21 // Page Up
const val VK_NEXT = 0x22 // Page Down
const val VK_END = 0x23
const val VK_HOME = 0x24
const val VK_LEFT = 0x25
const val VK_UP = 0x26
const val VK_RIGHT = 0x27
const val VK_DOWN = 0x28
const val VK_INSERT = 0x2D
const val VK_DELETE = 0x2E
const val VK_LWIN = 0x5B
const val VK_RWIN = 0x5C
const val VK_NUMPAD0 = 0x60
const val VK_NUMPAD9 = 0x69
const val VK_MULTIPLY = 0x6A
const val VK_ADD = 0x6B
const val VK_SEPARATOR = 0x6C
const val VK_SUBTRACT = 0x6D
const val VK_DECIMAL = 0x6E
const val VK_DIVIDE = 0x6F
const val VK_F1 = 0x70
const val VK_F12 = 0x7B
const val VK_LSHIFT = 0xA0
const val VK_RSHIFT = 0xA1
const val VK_LCONTROL = 0xA2
const val VK_RCONTROL = 0xA3
const val VK_LMENU = 0xA4
const val VK_RMENU = 0xA5

// Mouse button states
const val MK_LBUTTON = 0x0001
const val MK_RBUTTON = 0x0002
const val MK_MBUTTON = 0x0010
const val MK_SHIFT = 0x0004
const val MK_CONTROL = 0x0008
const val MK_ALT = 0x0020

// Get key state
val GET_X_LPARAM: (Int) -> Int = { it and 0xFFFF }
val GET_Y_LPARAM: (Int) -> Int = { (it ushr 16) and 0xFFFF }
val GET_WHEEL_DELTA_WPARAM: (Int) -> Int = { (it shr 16).toShort().toInt() }

// Window class styles
const val CS_HREDRAW = 0x0002
const val CS_VREDRAW = 0x0001

// Color constants
const val COLOR_WINDOW = 5
const val COLOR_BTNFACE = 15

// System metrics
const val SM_CXSCREEN = 0
const val SM_CYSCREEN = 1

// DPI awareness
const val PROCESS_DPI_UNAWARE = 0
const val PROCESS_SYSTEM_DPI_AWARE = 1
const val PROCESS_PER_MONITOR_DPI_AWARE = 2

// Hit test results
const val HTCLIENT = 1
const val HTCAPTION = 2
const val HTCLOSE = 20
const val HTMAXBUTTON = 9
const val HTMINBUTTON = 8
const val HTSYSMENU = 3

// Window messages for non-client area
const val WM_NCHITTEST = 0x0084
const val WM_NCCALCSIZE = 0x0083
const val WM_NCPAINT = 0x0085
const val WM_NCACTIVATE = 0x0086

// Timer
const val WM_TIMER = 0x0113

// Custom messages
const val WM_APP = 0x8000
const val WM_COMPOSE_INVALIDATE = WM_APP + 1


/** 启动诊断日志：写入 exe 同目录的 composekn-startup.log。 */
fun winlog(message: String) {
    try {
        org.jetbrains.skiko.win32Log(message)
    } catch (t: Throwable) {
        // 日志失败不影响主流程
    }
}

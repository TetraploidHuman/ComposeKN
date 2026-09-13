package com.composekn.tests

import com.composekn.windows.internal.GET_WHEEL_DELTA_WPARAM
import com.composekn.windows.internal.GET_X_LPARAM
import com.composekn.windows.internal.GET_Y_LPARAM
import com.composekn.windows.internal.HTCAPTION
import com.composekn.windows.internal.HTCLIENT
import com.composekn.windows.internal.MK_ALT
import com.composekn.windows.internal.MK_CONTROL
import com.composekn.windows.internal.MK_SHIFT
import com.composekn.windows.internal.VK_BACK
import com.composekn.windows.internal.VK_DELETE
import com.composekn.windows.internal.VK_ESCAPE
import com.composekn.windows.internal.VK_F12
import com.composekn.windows.internal.VK_LWIN
import com.composekn.windows.internal.VK_OEM_5_DOC
import com.composekn.windows.internal.VK_RWIN
import com.composekn.windows.internal.VK_TAB
import com.composekn.windows.internal.WM_CHAR
import com.composekn.windows.internal.WM_CLOSE
import com.composekn.windows.internal.WM_KEYDOWN
import com.composekn.windows.internal.WM_LBUTTONDOWN
import com.composekn.windows.internal.WM_MOUSEWHEEL
import com.composekn.windows.internal.WM_NCCALCSIZE
import com.composekn.windows.internal.WM_NCHITTEST
import com.composekn.windows.internal.WM_PAINT
import com.composekn.windows.internal.WM_SETFOCUS
import com.composekn.windows.internal.WM_SIZE
import com.composekn.windows.internal.WM_TIMER
import com.composekn.windows.internal.Win32Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Win32 常量与消息参数解码。
 *
 * `GET_X_LPARAM` / `GET_Y_LPARAM` 是签名/负坐标的经典坑：WM_MOUSEMOVE 的
 * lParam 是两个 16 位有符号值拼成的 32 位值，必须做符号扩展。
 */
class Win32StructuresTest {

    @Test
    fun `message ids match the Windows headers`() {
        assertEquals(0x0002, com.composekn.windows.internal.WM_DESTROY)
        assertEquals(0x0010, WM_CLOSE)
        assertEquals(0x000F, WM_PAINT)
        assertEquals(0x0005, WM_SIZE)
        assertEquals(0x0003, com.composekn.windows.internal.WM_MOVE)
        assertEquals(0x0100, WM_KEYDOWN)
        assertEquals(0x0102, WM_CHAR)
        assertEquals(0x0200, com.composekn.windows.internal.WM_MOUSEMOVE)
        assertEquals(0x0201, WM_LBUTTONDOWN)
        assertEquals(0x020A, WM_MOUSEWHEEL)
        assertEquals(0x0007, WM_SETFOCUS)
        assertEquals(0x0113, WM_TIMER)
        assertEquals(0x0084, WM_NCHITTEST)
        assertEquals(0x0083, WM_NCCALCSIZE)
    }

    @Test
    fun `hit test results`() {
        assertEquals(1, HTCLIENT)
        assertEquals(2, HTCAPTION)
    }

    @Test
    fun `virtual key codes match winuser h`() {
        assertEquals(0x08, VK_BACK)
        assertEquals(0x09, VK_TAB)
        assertEquals(0x1B, VK_ESCAPE)
        assertEquals(0x2E, VK_DELETE)
        assertEquals(0x5B, VK_LWIN)
        assertEquals(0x5C, VK_RWIN)
        assertEquals(0xDC, VK_OEM_5_DOC)
        assertEquals(0x7B, VK_F12)
    }

    @Test
    fun `mouse wParam modifier flags are not the bridge bits`() {
        // MK_* 与桥接 bit 是两套编码：MK_SHIFT=0x4 而 Win32Modifier.SHIFT=0x1。
        // 历史上 WindowsComposeWindow 把「按住 Ctrl 点击」判成了 Shift。
        assertEquals(0x0004, MK_SHIFT)
        assertEquals(0x0008, MK_CONTROL)
        assertEquals(0x0020, MK_ALT)
        assertEquals(0x1, Win32Modifier.SHIFT)
        assertEquals(0x2, Win32Modifier.CTRL)
        assertEquals(0x4, Win32Modifier.ALT)
        assertEquals(0x8, Win32Modifier.META)
        assertTrue(MK_SHIFT != Win32Modifier.SHIFT)
        assertTrue(MK_ALT != Win32Modifier.ALT)
        // 4 个位互不相同
        assertEquals(4, setOf(Win32Modifier.SHIFT, Win32Modifier.CTRL, Win32Modifier.ALT, Win32Modifier.META).size)
    }

    @Test
    fun `lparam decoding`() {
        // x = 300, y = 400
        val packed = (400 shl 16) or 300
        assertEquals(300, GET_X_LPARAM(packed))
        assertEquals(400, GET_Y_LPARAM(packed))
        // 0xFFFF (short -1) 必须保持负数
        assertEquals(-1, GET_X_LPARAM(0xFFFF))
        assertEquals(-1, GET_Y_LPARAM(0xFFFF shl 16))
    }

    @Test
    fun `wheel delta decoding`() {
        // 向前滚一格: HIWORD = +120
        assertEquals(120, GET_WHEEL_DELTA_WPARAM(120 shl 16))
        // 向后滚一格: HIWORD = -120 (0xFF88)
        assertEquals(-120, GET_WHEEL_DELTA_WPARAM(0xFF88 shl 16))
    }
}

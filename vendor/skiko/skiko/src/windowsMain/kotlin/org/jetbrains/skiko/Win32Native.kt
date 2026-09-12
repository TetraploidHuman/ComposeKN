@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.*

/**
 * Kotlin/Native Windows (mingw) Win32 bridge bindings — mirrors the Wayland
 * bridge pattern: externals declared here resolve to the C bridge compiled by
 * skiko's compileNativeBridges into the final executable.
 */
@SymbolName("composekn_win32_create")
internal external fun composekn_win32_create(title: String, width: Int, height: Int): COpaquePointer?

@SymbolName("composekn_win32_destroy")
internal external fun composekn_win32_destroy(window: COpaquePointer?)

@SymbolName("composekn_win32_pump")
internal external fun composekn_win32_pump(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_pop_event_flat")
internal external fun composekn_win32_pop_event_flat(
    window: COpaquePointer?,
    type: IntArray?,
    x: FloatArray?,
    y: FloatArray?,
    button: UIntArray?,
    state: UIntArray?,
    a: IntArray?,
    b: IntArray?,
    modifiers: UIntArray?,
): Boolean

@SymbolName("composekn_win32_present")
internal external fun composekn_win32_present(
    window: COpaquePointer?,
    pixels: COpaquePointer?,
    width: Int,
    height: Int,
    stridePx: Int,
)

@SymbolName("composekn_win32_width")
internal external fun composekn_win32_width(window: COpaquePointer?): Int

@SymbolName("composekn_win32_height")
internal external fun composekn_win32_height(window: COpaquePointer?): Int

@SymbolName("composekn_win32_show")
internal external fun composekn_win32_show(window: COpaquePointer?, cmd: Int)

@SymbolName("composekn_win32_is_maximized")
internal external fun composekn_win32_is_maximized(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_is_minimized")
internal external fun composekn_win32_is_minimized(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_request_close")
internal external fun composekn_win32_request_close(window: COpaquePointer?)

@SymbolName("composekn_win32_set_title")
internal external fun composekn_win32_set_title(window: COpaquePointer?, title: String)

@SymbolName("composekn_win32_clipboard_get_text")
internal external fun composekn_win32_clipboard_get_text(
    window: COpaquePointer?,
    buffer: ByteArray?,
    bufferSize: ULong,
    ok: CPointer<BooleanVar>?,
)

@SymbolName("composekn_win32_clipboard_set_text")
internal external fun composekn_win32_clipboard_set_text(window: COpaquePointer?, text: String)

/** SW_SHOWMINIMIZED etc. */
const val SW_WINDOWS_SHOW = 5
const val SW_WINDOWS_MAXIMIZE = 3
const val SW_WINDOWS_MINIMIZE = 6
const val SW_WINDOWS_RESTORE = 9

/**
 * High-level wrapper over the Win32 C bridge. All calls are main-thread only.
 */
class Win32Window internal constructor(internal val native: COpaquePointer) : AutoCloseable {
    /** Create a window with the given title and client size. */
    constructor(title: String, width: Int = 960, height: Int = 640) :
        this(ensureCreated(title, width, height))

    val dpiScale: Float = 1.0f

    val width: Int
        get() = composekn_win32_width(native)
    val height: Int
        get() = composekn_win32_height(native)

    val isMaximized: Boolean
        get() = composekn_win32_is_maximized(native)
    val isMinimized: Boolean
        get() = composekn_win32_is_minimized(native)

    var clipboard: String?
        set(value) {
            if (value != null) composekn_win32_clipboard_set_text(native, value)
        }
        get() {
            return memScoped {
                val ok = alloc<BooleanVar>()
                val buffer = ByteArray(8192)
                composekn_win32_clipboard_get_text(native, buffer, buffer.size.convert(), ok.ptr)
                if (ok.value) StringBytesDecoding(buffer) else null
            }
        }

    fun pump(): Boolean = composekn_win32_pump(native)

    fun popEvent(): Win32Event? {
        val type = IntArray(1)
        val x = FloatArray(1)
        val y = FloatArray(1)
        val button = UIntArray(1)
        val state = UIntArray(1)
        val a = IntArray(1)
        val b = IntArray(1)
        val modifiers = UIntArray(1)
        if (!composekn_win32_pop_event_flat(native, type, x, y, button, state, a, b, modifiers)) {
            return null
        }
        return Win32Event(
            type = type[0],
            x = x[0],
            y = y[0],
            button = button[0],
            state = state[0],
            a = a[0],
            b = b[0],
            modifiers = modifiers[0],
        )
    }

    fun minimize() = composekn_win32_show(native, SW_WINDOWS_MINIMIZE)
    fun maximize() = composekn_win32_show(native, SW_WINDOWS_MAXIMIZE)
    fun restore() = composekn_win32_show(native, SW_WINDOWS_RESTORE)
    fun show() = composekn_win32_show(native, SW_WINDOWS_SHOW)

    fun requestClose() = composekn_win32_request_close(native)
    fun setTitle(title: String) = composekn_win32_set_title(native, title)

    /**
     * Begin a native move drag from a WM_NCLBUTTONDOWN/HTCAPTION synthetic event.
     * Uses ReleaseCapture + SendMessage so Compose-injected press can drive it.
     */
    fun beginMove() = composekn_win32_begin_move(native)

    override fun close() {
        composekn_win32_destroy(native)
    }
}

@SymbolName("composekn_win32_begin_move")
internal external fun composekn_win32_begin_move(window: COpaquePointer?)

@SymbolName("composekn_win32_log")
internal external fun composekn_win32_log(message: String)

/** Append a line to composekn-startup.log (next to the exe). */
fun win32Log(message: String) = composekn_win32_log(message)

/**
 * Flat Win32 event delivered from the C bridge.
 */
data class Win32Event(
    val type: Int,
    val x: Float,
    val y: Float,
    val button: UInt,
    val state: UInt,
    val a: Int,
    val b: Int,
    val modifiers: UInt,
) {
    companion object {
        const val MOUSE_MOVE = 1
        const val MOUSE_BUTTON = 2
        const val MOUSE_WHEEL = 3
        const val KEY = 4
        const val CHAR = 5
        const val SIZE = 6
        const val MOVE = 7
        const val CLOSE = 8
        const val FOCUS = 9
        const val QUIT = 10
    }
}

private fun ensureCreated(title: String, width: Int, height: Int): COpaquePointer {
    val native = composekn_win32_create(title, width, height)
    checkNotNull(native) { "Failed to create Win32 window (title=$title)" }
    return native
}

private fun StringBytesDecoding(bytes: ByteArray): String {
    val end = bytes.indexOf(0)
    val range = if (end >= 0) bytes.copyOf(end) else bytes
    return range.decodeToString()
}

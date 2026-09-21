@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import org.jetbrains.skia.impl.NativePointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.native.SymbolName

/** Wayland input / frame events from native event queue. */
enum class WaylandEventType(val nativeValue: Int) {
    PointerEnter(1),
    PointerLeave(2),
    PointerMotion(3),
    PointerButton(4),
    PointerAxis(5),
    Key(6),
    Frame(7),
    Scale(8),
    TouchDown(9),
    TouchMotion(10),
    TouchUp(11),
}

data class WaylandEvent(
    val type: WaylandEventType,
    val x: Float = 0f,
    val y: Float = 0f,
    val button: Int = 0,
    val pressed: Boolean = false,
    val axis: Int = 0,
    val axisValue: Float = 0f,
    val keyCode: Int = 0,
    val keysym: Int = 0,
    val modifiers: Int = 0,
    val scale: Float = 1f,
)

@Suppress("KotlinNativeMissingLibrary")
@SymbolName("composekn_window_create")
internal external fun composekn_window_create(
    title: CPointer<ByteVar>,
    width: Int,
    height: Int,
): COpaquePointer?

@SymbolName("composekn_window_destroy")
internal external fun composekn_window_destroy(window: COpaquePointer)

@SymbolName("composekn_window_poll")
internal external fun composekn_window_poll(window: COpaquePointer): Boolean

@SymbolName("composekn_window_pop_event_flat")
internal external fun composekn_window_pop_event_flat(
    window: COpaquePointer,
    type: CPointer<IntVar>,
    x: CPointer<FloatVar>,
    y: CPointer<FloatVar>,
    button: CPointer<UIntVar>,
    state: CPointer<UIntVar>,
    axis: CPointer<IntVar>,
    axisValue: CPointer<FloatVar>,
    keyCode: CPointer<UIntVar>,
    keysym: CPointer<UIntVar>,
    modifiers: CPointer<UIntVar>,
    scale: CPointer<FloatVar>,
): Boolean

@SymbolName("composekn_window_request_frame")
internal external fun composekn_window_request_frame(window: COpaquePointer)

@SymbolName("composekn_window_frame_pending")
internal external fun composekn_window_frame_pending(window: COpaquePointer): Boolean

@SymbolName("composekn_window_make_current")
internal external fun composekn_window_make_current(window: COpaquePointer)

@SymbolName("composekn_window_swap_buffers")
internal external fun composekn_window_swap_buffers(window: COpaquePointer)

@SymbolName("composekn_window_set_swap_interval")
internal external fun composekn_window_set_swap_interval(window: COpaquePointer, interval: Int)

@SymbolName("composekn_window_width")
internal external fun composekn_window_width(window: COpaquePointer): Int

@SymbolName("composekn_window_height")
internal external fun composekn_window_height(window: COpaquePointer): Int

@SymbolName("composekn_window_scale")
internal external fun composekn_window_scale(window: COpaquePointer): Float

@SymbolName("composekn_window_consume_resized")
internal external fun composekn_window_consume_resized(window: COpaquePointer): Boolean

@SymbolName("composekn_gl_get_draw_framebuffer_binding")
internal external fun composekn_gl_get_draw_framebuffer_binding(): Int

@SymbolName("composekn_gl_viewport")
internal external fun composekn_gl_viewport(width: Int, height: Int)

@SymbolName("composekn_create_egl_direct_context")
internal external fun composekn_create_egl_direct_context(): NativePointer

@SymbolName("composekn_clipboard_get_text")
internal external fun composekn_clipboard_get_text(buffer: CPointer<ByteVar>, size: Int): Boolean

@SymbolName("composekn_clipboard_set_text")
internal external fun composekn_clipboard_set_text(text: CPointer<ByteVar>)

@SymbolName("composekn_window_uses_server_decoration")
internal external fun composekn_window_uses_server_decoration(window: COpaquePointer): Boolean

@SymbolName("composekn_window_minimize")
internal external fun composekn_window_minimize(window: COpaquePointer)

@SymbolName("composekn_window_toggle_maximized")
internal external fun composekn_window_toggle_maximized(window: COpaquePointer)

@SymbolName("composekn_window_is_maximized")
internal external fun composekn_window_is_maximized(window: COpaquePointer): Boolean

@SymbolName("composekn_window_request_close")
internal external fun composekn_window_request_close(window: COpaquePointer)

@SymbolName("composekn_window_begin_move")
internal external fun composekn_window_begin_move(window: COpaquePointer)

@SymbolName("composekn_window_begin_resize")
internal external fun composekn_window_begin_resize(window: COpaquePointer, edges: UInt)

@SymbolName("composekn_window_pop_ime_event")
internal external fun composekn_window_pop_ime_event(
    window: COpaquePointer,
    kind: CPointer<IntVar>,
    text: CPointer<ByteVar>,
    textSize: Int,
    a: CPointer<UIntVar>,
    b: CPointer<UIntVar>,
): Boolean

@SymbolName("composekn_text_input_set_enabled")
internal external fun composekn_text_input_set_enabled(window: COpaquePointer, enabled: Boolean)

@SymbolName("composekn_text_input_set_cursor_rectangle")
internal external fun composekn_text_input_set_cursor_rectangle(
    window: COpaquePointer,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
)

@SymbolName("composekn_text_input_set_surrounding_text")
internal external fun composekn_text_input_set_surrounding_text(
    window: COpaquePointer,
    text: CPointer<ByteVar>,
    cursor: Int,
    anchor: Int,
)

@SymbolName("composekn_text_input_set_content_type")
internal external fun composekn_text_input_set_content_type(
    window: COpaquePointer,
    hint: Int,
    purpose: Int,
)

/** Wayland wl_data_device clipboard bridge (ComposeKN window). */
object WaylandClipboard {
    private const val BUFFER_SIZE = 65536

    fun getText(): String? = memScoped {
        val buffer = allocArray<ByteVar>(BUFFER_SIZE)
        if (!composekn_clipboard_get_text(buffer, BUFFER_SIZE)) {
            return null
        }
        buffer.toKString().takeIf { it.isNotEmpty() }
    }

    fun setText(text: String) = memScoped {
        composekn_clipboard_set_text(text.cstr.ptr)
    }

    // ---- 富文本格式：Wayland 侧**还没实现** ----
    //
    // 不是"忘了写"：Wayland 的剪贴板是 `wl_data_source` 一次声明**多个 MIME 类型**
    // （text/plain;charset=utf-8、text/html、image/png…），然后在 `send` 回调里按
    // 对方要的 MIME 回数据；接收侧是 `wl_data_offer.receive(mime)`。现在的 C 桥
    // （wayland_window.cc）只实现了单一 text/plain 那条路，所以这里先把接口留出来、
    // 让 compose-core 那份**共享**的 PlatformClipboard 两边都能编过：
    //   * 读：回 null（= 没有这个格式）；
    //   * 写：只写文本，HTML/RTF/位图**忽略**。
    // 补齐要动 C 侧协议（多 MIME + send 回调），单独一轮做 —— 见 HANDOVER §17.34。
    /** 文件列表（Wayland 侧是 `text/uri-list` 那条，还没接）。 */
    fun getFiles(): List<String> = emptyList()
    fun getHtml(): String? = null
    fun getRtf(): String? = null
    fun getImage(): ClipboardImage? = null
    fun setRich(text: String?, html: String?, rtf: String?, image: ClipboardImage?) {
        if (text != null) setText(text)
    }
}

internal fun COpaquePointer.drainWaylandEvents(): List<WaylandEvent> = memScoped {
    val type = alloc<IntVar>()
    val x = alloc<FloatVar>()
    val y = alloc<FloatVar>()
    val button = alloc<UIntVar>()
    val state = alloc<UIntVar>()
    val axis = alloc<IntVar>()
    val axisValue = alloc<FloatVar>()
    val keyCode = alloc<UIntVar>()
    val keysym = alloc<UIntVar>()
    val modifiers = alloc<UIntVar>()
    val scale = alloc<FloatVar>()
    buildList {
        while (
            composekn_window_pop_event_flat(
                this@drainWaylandEvents,
                type.ptr,
                x.ptr,
                y.ptr,
                button.ptr,
                state.ptr,
                axis.ptr,
                axisValue.ptr,
                keyCode.ptr,
                keysym.ptr,
                modifiers.ptr,
                scale.ptr,
            )
        ) {
            val eventType = WaylandEventType.entries.firstOrNull { it.nativeValue == type.value }
                ?: WaylandEventType.Frame
            add(
                WaylandEvent(
                    type = eventType,
                    x = x.value,
                    y = y.value,
                    button = button.value.toInt(),
                    pressed = state.value != 0u,
                    axis = axis.value,
                    axisValue = axisValue.value,
                    keyCode = keyCode.value.toInt(),
                    keysym = keysym.value.toInt(),
                    modifiers = modifiers.value.toInt(),
                    scale = scale.value,
                )
            )
        }
    }
}

/** IME (zwp_text_input_v3) event delivered to the Compose layer. */
sealed class WaylandImeEvent {
    /** The surface received text-input focus. */
    object Enter : WaylandImeEvent()

    /** The surface lost text-input focus. */
    object Leave : WaylandImeEvent()

    /** Composing (preedit) text shown by the IME; `text` with a cursor span. */
    data class Preedit(val text: String, val cursorBegin: Int, val cursorEnd: Int) : WaylandImeEvent()

    /** Final committed text to insert at the cursor. */
    data class Commit(val text: String) : WaylandImeEvent()

    /** Delete `beforeLength` chars before and `afterLength` after the cursor (preedit cleanup). */
    data class Delete(val beforeLength: Int, val afterLength: Int) : WaylandImeEvent()

    /** End of a batch of IME edits (safe point to flush). */
    object Done : WaylandImeEvent()
}

internal fun COpaquePointer.drainImeEvents(): List<WaylandImeEvent> = memScoped {
    val kind = alloc<IntVar>()
    val a = alloc<UIntVar>()
    val b = alloc<UIntVar>()
    val textBuf = allocArray<ByteVar>(IME_TEXT_BUFFER)
    buildList {
        while (
            composekn_window_pop_ime_event(
                this@drainImeEvents,
                kind.ptr,
                textBuf,
                IME_TEXT_BUFFER,
                a.ptr,
                b.ptr,
            )
        ) {
            val text = textBuf.toKString()
            when (kind.value) {
                COMPOSEKN_IME_ENTER -> add(WaylandImeEvent.Enter)
                COMPOSEKN_IME_LEAVE -> add(WaylandImeEvent.Leave)
                COMPOSEKN_IME_PREEDIT -> add(WaylandImeEvent.Preedit(text, a.value.toInt(), b.value.toInt()))
                COMPOSEKN_IME_COMMIT -> add(WaylandImeEvent.Commit(text))
                COMPOSEKN_IME_DELETE -> add(WaylandImeEvent.Delete(a.value.toInt(), b.value.toInt()))
                COMPOSEKN_IME_DONE -> add(WaylandImeEvent.Done)
            }
        }
    }
}

internal const val IME_TEXT_BUFFER = 8192

internal const val COMPOSEKN_IME_ENTER = 1
internal const val COMPOSEKN_IME_LEAVE = 2
internal const val COMPOSEKN_IME_PREEDIT = 3
internal const val COMPOSEKN_IME_COMMIT = 4
internal const val COMPOSEKN_IME_DELETE = 5
internal const val COMPOSEKN_IME_DONE = 6

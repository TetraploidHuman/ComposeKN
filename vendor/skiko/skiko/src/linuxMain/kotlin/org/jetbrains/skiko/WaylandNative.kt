@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import org.jetbrains.skia.impl.NativePointer
import kotlinx.cinterop.*
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
    /** Keyboard focus enter/leave; [WaylandEvent.pressed] = hasFocus. */
    Focus(12),
    /** Inbound DnD (wl_data_device). */
    DragEnter(13),
    DragOver(14),
    DragLeave(15),
    DragDrop(16),
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

@SymbolName("composekn_display_begin_poll_cycle")
internal external fun composekn_display_begin_poll_cycle_native()

/**
 * Reset the shared wl_display poll-cycle flag. Call once per host-loop iteration
 * before polling any windows so only the first poll does prepare_read/read_events.
 */
fun composekn_display_begin_poll_cycle() = composekn_display_begin_poll_cycle_native()

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

@SymbolName("composekn_clipboard_set_rich")
internal external fun composekn_clipboard_set_rich(
    utf8Text: CPointer<ByteVar>?,
    utf8Html: CPointer<ByteVar>?,
    utf8Rtf: CPointer<ByteVar>?,
    imageW: Int,
    imageH: Int,
    bgra: CPointer<UByteVar>?,
    utf8Files: CPointer<ByteVar>?,
)

@SymbolName("composekn_clipboard_get_html")
internal external fun composekn_clipboard_get_html(buf: CPointer<ByteVar>?, size: Int): Int

@SymbolName("composekn_clipboard_get_rtf")
internal external fun composekn_clipboard_get_rtf(buf: CPointer<ByteVar>?, size: Int): Int

@SymbolName("composekn_clipboard_get_files")
internal external fun composekn_clipboard_get_files(buf: CPointer<ByteVar>?, size: Int): Int

@SymbolName("composekn_clipboard_get_image")
internal external fun composekn_clipboard_get_image(
    bgra: CPointer<UByteVar>?,
    size: Int,
    outDims: CPointer<IntVar>?,
): Int

@SymbolName("composekn_window_start_drag")
internal external fun composekn_window_start_drag(
    window: COpaquePointer?,
    utf8Files: CPointer<ByteVar>?,
    utf8Text: CPointer<ByteVar>?,
    iconW: Int,
    iconH: Int,
    iconBgra: CPointer<UByteVar>?,
    hotX: Int,
    hotY: Int,
): Boolean

@SymbolName("composekn_window_drag_poll_result")
internal external fun composekn_window_drag_poll_result(window: COpaquePointer?): Int

@SymbolName("composekn_window_dnd_set_accept")
internal external fun composekn_window_dnd_set_accept(window: COpaquePointer?, accept: Boolean)

@SymbolName("composekn_window_dnd_pop_files")
internal external fun composekn_window_dnd_pop_files(
    window: COpaquePointer?,
    buf: CPointer<ByteVar>?,
    size: Int,
): Int

@SymbolName("composekn_window_dnd_pop_text")
internal external fun composekn_window_dnd_pop_text(
    window: COpaquePointer?,
    buf: CPointer<ByteVar>?,
    size: Int,
): Int

@SymbolName("composekn_window_uses_server_decoration")
internal external fun composekn_window_uses_server_decoration(window: COpaquePointer): Boolean

@SymbolName("composekn_window_minimize")
internal external fun composekn_window_minimize(window: COpaquePointer)

@SymbolName("composekn_window_toggle_maximized")
internal external fun composekn_window_toggle_maximized(window: COpaquePointer)

@SymbolName("composekn_window_is_maximized")
internal external fun composekn_window_is_maximized(window: COpaquePointer): Boolean

@SymbolName("composekn_window_set_fullscreen")
internal external fun composekn_window_set_fullscreen(window: COpaquePointer, enable: Boolean)

@SymbolName("composekn_window_is_fullscreen")
internal external fun composekn_window_is_fullscreen(window: COpaquePointer): Boolean

@SymbolName("composekn_window_set_resizable")
internal external fun composekn_window_set_resizable(window: COpaquePointer, resizable: Boolean)

@SymbolName("composekn_window_request_close")
internal external fun composekn_window_request_close(window: COpaquePointer)

@SymbolName("composekn_window_is_close_requested")
internal external fun composekn_window_is_close_requested(window: COpaquePointer): Boolean

@SymbolName("composekn_window_consume_close_requested")
internal external fun composekn_window_consume_close_requested(window: COpaquePointer): Boolean

@SymbolName("composekn_window_set_title")
internal external fun composekn_window_set_title(window: COpaquePointer, title: CPointer<ByteVar>)

@SymbolName("composekn_window_request_size")
internal external fun composekn_window_request_size(window: COpaquePointer, width: Int, height: Int)

@SymbolName("composekn_window_begin_move")
internal external fun composekn_window_begin_move(window: COpaquePointer)

@SymbolName("composekn_window_set_parent")
internal external fun composekn_window_set_parent(child: COpaquePointer, parent: COpaquePointer?)

@SymbolName("composekn_window_set_always_on_top")
internal external fun composekn_window_set_always_on_top(window: COpaquePointer, onTop: Boolean): Boolean

@SymbolName("composekn_window_always_on_top_supported")
internal external fun composekn_window_always_on_top_supported(): Boolean

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

// ---------------------------------------------------------------------------
// Graphite + Vulkan
// ---------------------------------------------------------------------------

@SymbolName("composekn_window_vk_create")
internal external fun composekn_window_vk_create(window: COpaquePointer?): Boolean

@SymbolName("composekn_window_vk_begin_frame")
internal external fun composekn_window_vk_begin_frame(
    window: COpaquePointer?,
    width: Int,
    height: Int,
): NativePointer

@SymbolName("composekn_window_vk_end_frame")
internal external fun composekn_window_vk_end_frame(window: COpaquePointer?): Boolean

@SymbolName("composekn_window_vk_destroy")
internal external fun composekn_window_vk_destroy(window: COpaquePointer?)

@SymbolName("composekn_window_set_vulkan_preferred")
internal external fun composekn_window_set_vulkan_preferred(window: COpaquePointer?, preferred: Boolean)

/** Wayland wl_data_device clipboard bridge (ComposeKN window). */
object WaylandClipboard {
    private const val BUFFER_SIZE = 65536
    private const val PROBE_SIZE = 64 * 1024

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

    fun getFiles(): List<String> {
        val joined = popString(::composekn_clipboard_get_files) ?: return emptyList()
        return joined.split('\n').filter { it.isNotEmpty() }
    }

    fun getHtml(): String? = popString(::composekn_clipboard_get_html)

    fun getRtf(): String? = popString(::composekn_clipboard_get_rtf)

    fun getImage(): ClipboardImage? = memScoped {
        val out = allocArray<IntVar>(2)
        val needed = composekn_clipboard_get_image(null, 0, out)
        if (needed <= 0) return@memScoped null
        val width = out[0]
        val height = out[1]
        if (width <= 0 || height <= 0) return@memScoped null
        val pixels = ByteArray(needed)
        val written = pixels.usePinned { pinned ->
            composekn_clipboard_get_image(
                pinned.addressOf(0).reinterpret<UByteVar>(),
                needed,
                out,
            )
        }
        if (written != needed) return@memScoped null
        ClipboardImage(width, height, pixels)
    }

    /**
     * Offer multiple MIME types in one wl_data_source transaction
     * (text / html / rtf / image/bmp / text/uri-list).
     */
    fun setRich(
        text: String?,
        html: String?,
        rtf: String?,
        image: ClipboardImage?,
        files: List<String>? = null,
    ) {
        val imagePixels = image?.pixels
        val filesJoined = files?.takeIf { it.isNotEmpty() }?.joinToString("\n")
        useCStringOrNull(text) { textPtr ->
            useCStringOrNull(html) { htmlPtr ->
                useCStringOrNull(rtf) { rtfPtr ->
                    useCStringOrNull(filesJoined) { filesPtr ->
                        if (imagePixels == null) {
                            composekn_clipboard_set_rich(
                                textPtr, htmlPtr, rtfPtr, 0, 0, null, filesPtr,
                            )
                        } else {
                            imagePixels.usePinned { pinned ->
                                composekn_clipboard_set_rich(
                                    textPtr, htmlPtr, rtfPtr,
                                    image!!.width, image.height,
                                    pinned.addressOf(0).reinterpret<UByteVar>(),
                                    filesPtr,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun popString(pop: (CPointer<ByteVar>?, Int) -> Int): String? = memScoped {
        val probe = allocArray<ByteVar>(PROBE_SIZE)
        val needed = pop(probe, PROBE_SIZE)
        if (needed <= 0) return@memScoped null
        if (needed < PROBE_SIZE) {
            return@memScoped byteArrayFrom(probe, needed).decodeToString()
        }
        val buffer = allocArray<ByteVar>(needed + 1)
        val written = pop(buffer, needed + 1)
        if (written <= 0) null else byteArrayFrom(buffer, written).decodeToString()
    }
}

internal inline fun <R> useCStringOrNull(value: String?, block: (CPointer<ByteVar>?) -> R): R {
    if (value == null) return block(null)
    val bytes = value.encodeToByteArray()
    val buf = ByteArray(bytes.size + 1)
    bytes.copyInto(buf)
    return buf.usePinned { block(it.addressOf(0)) }
}

private fun byteArrayFrom(ptr: CPointer<ByteVar>, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    return ptr.readBytes(length)
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

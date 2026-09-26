@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import org.jetbrains.skia.impl.NativePointer

/**
 * Wayland + EGL/Vulkan window for Kotlin/Native Linux desktop.
 */
class WaylandWindow(
    val title: String,
    val initialWidth: Int = 800,
    val initialHeight: Int = 600,
) {
    private val native: COpaquePointer = memScoped {
        composekn_window_create(title.cstr.ptr, initialWidth, initialHeight)
    } ?: error("Failed to create Wayland window. Is WAYLAND_DISPLAY set?")

    val width: Int get() = composekn_window_width(native)
    val height: Int get() = composekn_window_height(native)
    val scale: Float get() = composekn_window_scale(native)

    internal val nativeHandle: COpaquePointer = native

    var onEvent: ((WaylandEvent) -> Unit)? = null

    /** IME (text-input-v3) event callback, invoked during [poll]. */
    var onImeEvent: ((WaylandImeEvent) -> Unit)? = null

    /** Returns true once after the compositor changed the surface size. */
    fun consumeResized(): Boolean = composekn_window_consume_resized(native)

    /**
     * True after compositor/app requested close; surface stays alive until [destroy]
     * (Desktop DO_NOTHING_ON_CLOSE).
     */
    fun isCloseRequested(): Boolean = composekn_window_is_close_requested(native)

    /**
     * Returns true once per close request so the host can fire onCloseRequest
     * without destroying. Clears the pending-notify flag.
     */
    fun consumeCloseRequested(): Boolean = composekn_window_consume_close_requested(native)

    /** Update xdg_toplevel title. */
    fun setTitle(title: String) = memScoped {
        composekn_window_set_title(native, title.cstr.ptr)
    }

    /**
     * 请求客户端尺寸（逻辑像素）。Wayland 上通过 min=max + geometry 提示 compositor；
     * 见 [composekn_window_request_size]。
     */
    fun requestSize(width: Int, height: Int) {
        composekn_window_request_size(native, width, height)
    }

    /** 与 Win32 [setClientSize] 对齐的别名。 */
    fun setClientSize(width: Int, height: Int) = requestSize(width, height)

    /**
     * Process Wayland events and dispatch input/frame/IME callbacks.
     * Returns false only on display failure. Close requests do not stop polling —
     * use [consumeCloseRequested] for Desktop DO_NOTHING semantics.
     */
    fun poll(): Boolean {
        if (!composekn_window_poll(native)) {
            return false
        }
        val handler = onEvent
        if (handler != null) {
            for (event in native.drainWaylandEvents()) {
                handler(event)
            }
        } else {
            native.drainWaylandEvents()
        }
        val imeHandler = onImeEvent
        if (imeHandler != null) {
            for (event in native.drainImeEvents()) {
                imeHandler(event)
            }
        } else {
            native.drainImeEvents()
        }
        return true
    }

    // ---- IME (zwp_text_input_v3) ----

    /** Enable (or disable) the text input for the window's keyboard focus. */
    fun textInputSetEnabled(enabled: Boolean) = composekn_text_input_set_enabled(native, enabled)

    /** Update the cursor rectangle (surface-local, unscaled) so the IME can anchor its panel. */
    fun textInputSetCursorRectangle(x: Int, y: Int, width: Int, height: Int) =
        composekn_text_input_set_cursor_rectangle(native, x, y, width, height)

    /** Report the text around the cursor (for context-aware IME editing). */
    fun textInputSetSurroundingText(text: String, cursor: Int, anchor: Int) = memScoped {
        composekn_text_input_set_surrounding_text(native, text.cstr.ptr, cursor, anchor)
    }

    fun requestFrame() {
        composekn_window_request_frame(native)
    }

    fun framePending(): Boolean = composekn_window_frame_pending(native)

    // ---- Graphite + Vulkan ----

    fun vkCreate(): Boolean = composekn_window_vk_create(native)

    /** Next-frame backbuffer SkCanvas*; failure returns a null pointer. */
    fun vkBeginFrame(width: Int, height: Int): NativePointer =
        composekn_window_vk_begin_frame(native, width, height)

    fun vkEndFrame(): Boolean = composekn_window_vk_end_frame(native)

    fun vkDestroy(): Unit = composekn_window_vk_destroy(native)

    /** Clear so GLES fallback can create wl_egl_window on this surface. */
    fun setVulkanPreferred(preferred: Boolean) =
        composekn_window_set_vulkan_preferred(native, preferred)

    val usesServerDecoration: Boolean
        get() = composekn_window_uses_server_decoration(native)

    val isMaximized: Boolean
        get() = composekn_window_is_maximized(native)

    val isFullscreen: Boolean
        get() = composekn_window_is_fullscreen(native)

    fun minimize() = composekn_window_minimize(native)

    fun toggleMaximized() = composekn_window_toggle_maximized(native)

    fun setFullscreen(enable: Boolean) = composekn_window_set_fullscreen(native, enable)

    fun setResizable(resizable: Boolean) = composekn_window_set_resizable(native, resizable)

    fun requestClose() = composekn_window_request_close(native)

    fun beginMove() = composekn_window_begin_move(native)

    /**
     * `xdg_toplevel_set_parent`：Dialog / Aligned / PlatformDefault 的可移植提示。
     * [parent]=null 清除 transient。标准协议无绝对坐标。
     */
    fun setParent(parent: WaylandWindow?) {
        composekn_window_set_parent(native, parent?.nativeHandle)
    }

    /**
     * Wayland 无标准 always-on-top；返回是否被 compositor 接受（当前恒 false）。
     */
    fun setAlwaysOnTop(onTop: Boolean): Boolean =
        composekn_window_set_always_on_top(native, onTop)

    fun alwaysOnTopSupported(): Boolean = composekn_window_always_on_top_supported()

    fun beginResize(edges: UInt) = composekn_window_begin_resize(native, edges)

    fun destroy() {
        composekn_window_destroy(native)
    }
}

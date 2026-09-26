#ifndef COMPOSEKN_WAYLAND_BRIDGE_H
#define COMPOSEKN_WAYLAND_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ComposeKNWindow ComposeKNWindow;

/** Event types delivered via composekn_window_pop_event. */
typedef enum ComposeKNEventType {
    COMPOSEKN_EVENT_POINTER_ENTER = 1,
    COMPOSEKN_EVENT_POINTER_LEAVE = 2,
    COMPOSEKN_EVENT_POINTER_MOTION = 3,
    COMPOSEKN_EVENT_POINTER_BUTTON = 4,
    COMPOSEKN_EVENT_POINTER_AXIS = 5,
    COMPOSEKN_EVENT_KEY = 6,
    COMPOSEKN_EVENT_FRAME = 7,
    COMPOSEKN_EVENT_SCALE = 8,
    COMPOSEKN_EVENT_TOUCH_DOWN = 9,
    COMPOSEKN_EVENT_TOUCH_MOTION = 10,
    COMPOSEKN_EVENT_TOUCH_UP = 11,
    /** Keyboard focus: state=1 enter, state=0 leave (mirrors Win32 FocusEvent). */
    COMPOSEKN_EVENT_FOCUS = 12,
} ComposeKNEventType;

/* IME (zwp_text_input_v3) event kinds, delivered via composekn_window_pop_ime_event. */
typedef enum ComposeKNImeKind {
    COMPOSEKN_IME_ENTER = 1,
    COMPOSEKN_IME_LEAVE = 2,
    COMPOSEKN_IME_PREEDIT = 3,  /* text = preedit string; a = cursor_begin; b = cursor_end */
    COMPOSEKN_IME_COMMIT = 4,   /* text = committed (final) string */
    COMPOSEKN_IME_DELETE = 5,   /* a = before_length; b = after_length (delete around cursor) */
    COMPOSEKN_IME_DONE = 6,     /* end of a batch of IME events */
} ComposeKNImeKind;

typedef struct ComposeKNEvent {
    int32_t type;
    float x;
    float y;
    uint32_t button;
    uint32_t state; /* 0=released 1=pressed for button/key */
    int32_t axis;   /* 0=vertical 1=horizontal scroll */
    float axis_value;
    uint32_t key_code;
    uint32_t keysym;
    uint32_t modifiers;
    float scale;
} ComposeKNEvent;

ComposeKNWindow* composekn_window_create(const char* title, int width, int height);
void composekn_window_destroy(ComposeKNWindow* window);

/**
 * Reset the shared-display poll-cycle flag. Call once per host-loop iteration
 * before polling any windows so only the first poll does prepare_read/read_events.
 */
void composekn_display_begin_poll_cycle(void);

/**
 * Process pending Wayland events.
 * Returns false only on display/read failure or null window.
 * Close requests do NOT stop polling (Desktop DO_NOTHING); use
 * composekn_window_consume_close_requested / is_close_requested instead.
 */
bool composekn_window_poll(ComposeKNWindow* window);

/** Pop the next input/resize/frame event, if any. Returns false when the queue is empty. */
bool composekn_window_pop_event(ComposeKNWindow* window, ComposeKNEvent* out);

/** Flat Kotlin-friendly event pop (avoids cinterop struct layout issues). */
bool composekn_window_pop_event_flat(
    ComposeKNWindow* window,
    int32_t* type,
    float* x,
    float* y,
    uint32_t* button,
    uint32_t* state,
    int32_t* axis,
    float* axis_value,
    uint32_t* key_code,
    uint32_t* keysym,
    uint32_t* modifiers,
    float* scale
);

/** Request a wl_surface frame callback (vsync). Emits COMPOSEKN_EVENT_FRAME when due. */
void composekn_window_request_frame(ComposeKNWindow* window);

/** True when a frame callback is pending and the surface should be redrawn. */
bool composekn_window_frame_pending(ComposeKNWindow* window);

void composekn_window_make_current(ComposeKNWindow* window);
void composekn_window_swap_buffers(ComposeKNWindow* window);
void composekn_window_set_swap_interval(ComposeKNWindow* window, int interval);

int composekn_window_width(ComposeKNWindow* window);
int composekn_window_height(ComposeKNWindow* window);
float composekn_window_scale(ComposeKNWindow* window);

/** Physical buffer size in pixels (for Vulkan swapchain / EGL window). */
int composekn_window_buffer_width(ComposeKNWindow* window);
int composekn_window_buffer_height(ComposeKNWindow* window);

/** Opaque wl_display* / wl_surface* for VK_KHR_wayland_surface. */
void* composekn_window_wl_display(ComposeKNWindow* window);
void* composekn_window_wl_surface(ComposeKNWindow* window);

/**
 * When true, EGL must not attach (Vulkan owns the wl_surface).
 * Set by composekn_window_vk_create on success.
 */
void composekn_window_set_vulkan_preferred(ComposeKNWindow* window, bool preferred);
bool composekn_window_vulkan_preferred(ComposeKNWindow* window);

// ---------------------------------------------------------------------------
// Graphite + Vulkan（GPU 后端，与 GLES 并存；失败时上层回退 GLES）
//
// C 侧拥有 VkInstance/Device/Swapchain + skgpu::graphite::Context/Recorder。
// 每帧 begin → 返回 SkCanvas*，Kotlin 画完后 end（present）。
// 未编 SK_VULKAN+SK_GRAPHITE 时全部返回失败/nullptr。
// ---------------------------------------------------------------------------

/** 建 Vulkan 设备 + Graphite Context + Wayland surface/swapchain。失败 → false。 */
bool composekn_window_vk_create(ComposeKNWindow* window);

/**
 * 获取下一帧 backbuffer 的 SkCanvas*（不转移所有权；仅在 end 前有效）。
 * width/height 为像素尺寸；传 0 或与 buffer 不符时用 window buffer 尺寸。
 */
void* composekn_window_vk_begin_frame(ComposeKNWindow* window, int width, int height);

/** snap + present。成功 true。 */
bool composekn_window_vk_end_frame(ComposeKNWindow* window);

/** 销毁 Graphite/Vulkan 资源。 */
void composekn_window_vk_destroy(ComposeKNWindow* window);

/** Returns true once after each xdg configure that changed the surface size. */
bool composekn_window_consume_resized(ComposeKNWindow* window);

/** Returns GL_DRAW_FRAMEBUFFER_BINDING for the current OpenGL context. */
int composekn_gl_get_draw_framebuffer_binding(void);

/** Create a Skia GrDirectContext using the current EGL GL bindings. Returns 0 on failure. */
void* composekn_create_egl_direct_context(void);

void composekn_gl_viewport(int width, int height);

/** Read system clipboard text into buffer (UTF-8). Returns false when empty. */
bool composekn_clipboard_get_text(char* buffer, size_t buffer_size);

/** Write UTF-8 text to the system clipboard. */
void composekn_clipboard_set_text(const char* text);

/** True when the compositor provides server-side window decorations. */
bool composekn_window_uses_server_decoration(ComposeKNWindow* window);

void composekn_window_minimize(ComposeKNWindow* window);
void composekn_window_toggle_maximized(ComposeKNWindow* window);
bool composekn_window_is_maximized(ComposeKNWindow* window);
void composekn_window_set_fullscreen(ComposeKNWindow* window, bool enable);
bool composekn_window_is_fullscreen(ComposeKNWindow* window);
/** false：xdg min=max 锁当前尺寸；true：清除约束。 */
void composekn_window_set_resizable(ComposeKNWindow* window, bool resizable);
void composekn_window_request_close(ComposeKNWindow* window);

/** True after compositor/app requested close (surface still alive until destroy). */
bool composekn_window_is_close_requested(ComposeKNWindow* window);

/**
 * Returns true once per close request so Kotlin can fire onCloseRequest
 * without destroying (Desktop DO_NOTHING_ON_CLOSE). Clears the pending-notify flag.
 */
bool composekn_window_consume_close_requested(ComposeKNWindow* window);

/** Update xdg_toplevel title (UTF-8). */
void composekn_window_set_title(ComposeKNWindow* window, const char* title);

/**
 * Request a client size (logical pixels). Best-effort on Wayland:
 * temporarily sets xdg_toplevel min=max to [w,h], updates window geometry,
 * commits; min/max are cleared after the next configure so interactive resize
 * can resume (when the app allows it).
 */
void composekn_window_request_size(ComposeKNWindow* window, int w, int h);

void composekn_window_begin_move(ComposeKNWindow* window);
void composekn_window_begin_resize(ComposeKNWindow* window, uint32_t edges);

/**
 * xdg_toplevel_set_parent — Dialog / Aligned / PlatformDefault 的可移植提示。
 * parent=NULL 清除 transient 关系。标准 xdg-shell **没有**绝对坐标 API；
 * compositor 通常会把 transient 窗相对 parent 居中或叠放。
 */
void composekn_window_set_parent(ComposeKNWindow* child, ComposeKNWindow* parent);

/**
 * Wayland 无标准 always-on-top。返回 false（unsupported）；Kotlin 侧可记账。
 * 预留入口，避免日后接 compositor 扩展时改 API。
 */
bool composekn_window_set_always_on_top(ComposeKNWindow* window, bool on_top);
bool composekn_window_always_on_top_supported(void);

/* ---- IME (zwp_text_input_v3) ---- */

/**
 * Pop the next IME event from the queue. Returns false when the queue is empty.
 * For PREEDIT/COMMIT the (UTF-8) text is written into `text` (NUL-terminated when it
 * fits within text_size); `a`/`b` carry cursor_begin/cursor_end (PREEDIT) or
 * before/after delete lengths (DELETE).
 */
bool composekn_window_pop_ime_event(
    ComposeKNWindow* window,
    int32_t* kind,
    char* text,
    size_t text_size,
    uint32_t* a,
    uint32_t* b);

/* App -> IME requests. All are safe no-ops when the compositor has no
 * zwp_text_input_manager_v3 or the text input has not been created yet. */
void composekn_text_input_set_enabled(ComposeKNWindow* window, bool enabled);
void composekn_text_input_set_cursor_rectangle(
    ComposeKNWindow* window, int x, int y, int width, int height);
void composekn_text_input_set_surrounding_text(
    ComposeKNWindow* window, const char* text, int cursor, int anchor);
void composekn_text_input_set_content_type(ComposeKNWindow* window, int hint, int purpose);

/* ---- xdg-desktop-portal FileChooser / StatusNotifierItem（libdbus）---- */

bool composekn_linux_file_dialog_available(void);
/**
 * 同步弹出 portal 文件对话框。两段式缓冲对齐 Win32：
 *   0=取消；-1=错误；>0=写入 UTF-8 字节数（多路径 '\n' 分隔）；
 *   buffer 太小则返回需要的字节数。
 * mode：0=Open，非 0=Save。
 */
int32_t composekn_linux_file_dialog(
    int32_t mode,
    const char* title,
    const char* initialDir,
    const char* initialName,
    bool allowMultiple,
    const char* filterUtf8,
    char* buffer,
    int32_t bufferSize);

typedef void (*ComposeKNLinuxTrayCallback)(int32_t kind, int32_t arg, void* user);

bool composekn_linux_tray_available(void);
bool composekn_linux_tray_create(
    const char* tooltip_utf8, ComposeKNLinuxTrayCallback cb, void* user);
void composekn_linux_tray_set_tooltip(const char* tooltip_utf8);
/** itemsUtf8：每行一项；空行 = 分隔；前缀 '-' = 禁用；格式见 ComposeKNTray.linux。 */
void composekn_linux_tray_set_menu(const char* itemsUtf8);
bool composekn_linux_tray_set_icon(int32_t w, int32_t h, const uint8_t* bgra);
void composekn_linux_tray_notify(
    const char* title_utf8, const char* body_utf8, int32_t type);
/** 泵 dbus（应在 UI 循环里周期性调用）。 */
void composekn_linux_tray_dispatch(void);
void composekn_linux_tray_destroy(void);

#ifdef __cplusplus
}
#endif

#endif

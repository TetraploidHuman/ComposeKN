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

/** Process pending Wayland events. Returns false when the window should close. */
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
void composekn_window_request_close(ComposeKNWindow* window);
void composekn_window_begin_move(ComposeKNWindow* window);
void composekn_window_begin_resize(ComposeKNWindow* window, uint32_t edges);

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

#ifdef __cplusplus
}
#endif

#endif

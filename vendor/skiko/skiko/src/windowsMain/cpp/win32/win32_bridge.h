#ifndef COMPOSEKN_WIN32_BRIDGE_H
#define COMPOSEKN_WIN32_BRIDGE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ComposeKNWin32Window ComposeKNWin32Window;

/** Event types delivered via composekn_win32_pop_event. Mirrors the Wayland bridge design. */
typedef enum ComposeKNWin32EventType {
    COMPOSEKN_WIN32_EVENT_MOUSE_MOVE = 1,
    COMPOSEKN_WIN32_EVENT_MOUSE_BUTTON = 2,
    COMPOSEKN_WIN32_EVENT_MOUSE_WHEEL = 3,
    COMPOSEKN_WIN32_EVENT_KEY = 4,
    COMPOSEKN_WIN32_EVENT_CHAR = 5,
    COMPOSEKN_WIN32_EVENT_SIZE = 6,
    COMPOSEKN_WIN32_EVENT_MOVE = 7,
    COMPOSEKN_WIN32_EVENT_CLOSE = 8,
    COMPOSEKN_WIN32_EVENT_FOCUS = 9,
    COMPOSEKN_WIN32_EVENT_QUIT = 10,
} ComposeKNWin32EventType;

typedef struct ComposeKNWin32Event {
    int32_t type;
    float x;          /* mouse: client coords; move: window x */
    float y;          /* mouse: client coords; move: window y */
    uint32_t button;  /* mouse: button id; key: virtual key code */
    uint32_t state;   /* 0=released 1=pressed */
    int32_t a;        /* key: flags; wheel: delta (120 = line) ; focus: 1=acquired */
    int32_t b;        /* char: unicode codepoint; key: scan code */
    uint32_t modifiers;
} ComposeKNWin32Event;

/** Append a line to the startup diagnostic log (composekn-startup.log). */
void composekn_win32_log(const char* message);

/** 物理像素 / 逻辑像素（= dpi / 96.0）。 */
float composekn_win32_dpi_scale(ComposeKNWin32Window* window);

/** Begin native move drag (ReleaseCapture + WM_NCLBUTTONDOWN/HTCAPTION). */
void composekn_win32_begin_move(ComposeKNWin32Window* window);

/** Create a top-level Win32 window. Returns NULL on failure. */
ComposeKNWin32Window* composekn_win32_create(const char* title, int width, int height);
void composekn_win32_destroy(ComposeKNWin32Window* window);

/** Pump pending Win32 messages. Returns false when the app should quit. */
bool composekn_win32_pump(ComposeKNWin32Window* window);

/** Pop one event, false if queue empty. Flat signature avoids struct-layout issues. */
bool composekn_win32_pop_event_flat(
    ComposeKNWin32Window* window,
    int32_t* type,
    float* x,
    float* y,
    uint32_t* button,
    uint32_t* state,
    int32_t* a,
    int32_t* b,
    uint32_t* modifiers
);

/** Present an 8-bit BGRA pixel buffer as the window client area. stride is in pixels. */
void composekn_win32_present(ComposeKNWin32Window* window, const void* pixels, int width, int height, int stride);

int composekn_win32_width(ComposeKNWin32Window* window);
int composekn_win32_height(ComposeKNWin32Window* window);

/** ShowWindow wrapper: cmd 3=SW_MAXIMIZE 6=SW_MINIMIZE 9=SW_RESTORE 5=SW_SHOW */
void composekn_win32_show(ComposeKNWin32Window* window, int cmd);
bool composekn_win32_is_maximized(ComposeKNWin32Window* window);
bool composekn_win32_is_minimized(ComposeKNWin32Window* window);
void composekn_win32_request_close(ComposeKNWin32Window* window);
void composekn_win32_set_title(ComposeKNWin32Window* window, const char* title);

void composekn_win32_clipboard_get_text(ComposeKNWin32Window* window, char* buffer, size_t buffer_size, bool* ok);
void composekn_win32_clipboard_set_text(ComposeKNWin32Window* window, const char* text);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* COMPOSEKN_WIN32_BRIDGE_H */

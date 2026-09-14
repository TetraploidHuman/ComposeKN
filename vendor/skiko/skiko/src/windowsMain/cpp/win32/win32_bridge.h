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
    /*
     * 触摸（WM_POINTER -> Compose PointerType.Touch）。
     *
     * 为什么必须单独一条通道：Compose 的 scrollable 明确拒绝鼠标拖拽滚动
     * （foundation/gestures/AbstractScrollableNode.kt: canDrag = { type != Mouse }），
     * 所以「把触摸当鼠标」的窗口上，手指拖动永远不会滚动列表。
     * button = 指针 id，state = 1(按下/移动) / 0(抬起)，x/y = 客户区物理像素。
     */
    COMPOSEKN_WIN32_EVENT_TOUCH_DOWN = 11,
    COMPOSEKN_WIN32_EVENT_TOUCH_MOVE = 12,
    COMPOSEKN_WIN32_EVENT_TOUCH_UP = 13,
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

/**
 * 主显示器刷新率（Hz）。用于把渲染节流到显示器节奏；拿不到/不可信时返回 0。
 */
int32_t composekn_win32_refresh_hz(ComposeKNWin32Window* window);

/** 注册 C 侧（wndproc/WM_SIZE 内）同步回调，用于缩放期间逐帧重组。 */
typedef void (*ComposeKNRenderTickFn)(void* user);
void composekn_win32_set_render_tick(ComposeKNRenderTickFn fn, void* user);

/** Begin native move drag (ReleaseCapture + WM_NCLBUTTONDOWN/HTCAPTION). */
void composekn_win32_begin_move(ComposeKNWin32Window* window);

/** Create a top-level Win32 window. Returns NULL on failure. */
ComposeKNWin32Window* composekn_win32_create(const char* title, int width, int height);
void composekn_win32_destroy(ComposeKNWin32Window* window);

/** Pump pending Win32 messages. Returns false when the app should quit. */
bool composekn_win32_pump(ComposeKNWin32Window* window);

/**
 * Block until the window's message queue is non-empty, or timeout_ms elapses
 * (< 0 = wait forever). Returns false when the app should quit.
 *
 * 渲染循环用它替代「PeekMessage 忙等」：没有渲染任务时线程真正睡着（CPU ≈ 0），
 * 消息一到立刻醒来，所以输入延迟不受影响。
 */
bool composekn_win32_wait_message(ComposeKNWin32Window* window, int32_t timeout_ms);

/**
 * Wake a thread blocked in composekn_win32_wait_message: posts a private no-op
 * message (swallowed by the wndproc, never enters the event queue).
 */
void composekn_win32_wake(ComposeKNWin32Window* window);

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

/**
 * 后备缓冲（present buffer）：一块宽*高*4 字节、紧密排布的 BGRA 内存，**由 C 侧持有**。
 *
 * 这是对齐上游 skiko SOFTWARE_FAST 的关键（见 awtMain/cpp/windows/SoftwareRedrawer.cc
 * 的 `SkSurfaces::WrapPixels`）：Skia 直接画进这块内存，present 时 GDI 直接从同一块
 * 内存上传 —— 全程零拷贝。以前是 Skia 自己 allocPixels 一个 surface，每帧再
 * peekPixels + 按行 memcpy 到这块内存（多一次整窗拷贝）。
 *
 * 尺寸变化时可能重新分配（返回的指针会变），调用方必须重建包装它的 Skia surface。
 * 返回 nullptr 表示失败（调用方应回退到 `composekn_win32_present` 路径）。
 */
void* composekn_win32_backbuffer_pixels(ComposeKNWin32Window* window, int width, int height);

/** 把当前后备缓冲上传到窗口客户区（StretchDIBits，不再拷贝像素）。 */
void composekn_win32_present_buffer(ComposeKNWin32Window* window);

/**
 * 本进程累计 CPU 时间（内核 + 用户），单位纳秒；失败返回 -1。
 *
 * 用途：真机上没法方便地读任务管理器时，让 demo 自己把 CPU 占用写进日志
 * （1000ms/秒 = 满一个逻辑核）。
 */
int64_t composekn_win32_process_cpu_nanos(ComposeKNWin32Window* window);

/** 逻辑处理器数量（把进程 CPU 换算成「占整机百分比」用）。 */
int32_t composekn_win32_processor_count(void);

/** 原生 HWND（void*）。给 win32_gl.cc 用：ComposeKNWin32Window 在头文件里是不透明类型。 */
void* composekn_win32_hwnd(ComposeKNWin32Window* window);

// ---------------------------------------------------------------------------
// OpenGL / WGL（GPU 后端，对齐上游 linuxMain 的 EGL 版）
//
// C 侧只管平台上下文；Skia 的 GPU 上下文在 Kotlin 侧用 DirectContext.makeGL() 建。
// ---------------------------------------------------------------------------

/** 建 WGL 双缓冲上下文并 make current。失败返回 false（上层回退软件路径）。 */
bool composekn_win32_gl_create(ComposeKNWin32Window* window);

/** make current（幂等；渲染/销毁前调用，与上游 Linux GL redrawer 一致）。 */
bool composekn_win32_gl_make_current(ComposeKNWin32Window* window);

/** glViewport(0, 0, w, h)。 */
void composekn_win32_gl_viewport(int width, int height);

/** 当前 draw framebuffer 绑定（默认帧缓冲通常是 0）。 */
int composekn_win32_gl_get_draw_framebuffer_binding(void);

/** WGL_EXT_swap_interval（1 = 垂直同步）。 */
void composekn_win32_gl_set_swap_interval(int interval);

/** present：SwapBuffers。 */
void composekn_win32_gl_swap_buffers(ComposeKNWin32Window* window);

/** 销毁上下文并释放 DC。 */
void composekn_win32_gl_destroy(ComposeKNWin32Window* window);

int composekn_win32_width(ComposeKNWin32Window* window);
int composekn_win32_height(ComposeKNWin32Window* window);

/**
 * 「最大化时客户区超出显示器工作区」的检出次数（正常必须为 0）。
 *
 * 真机 bug 的回归断言：无边框窗口（WM_NCCALCSIZE 返回 0，客户区 = 窗口矩形）在最大化
 * 时会被 Windows 按「不可见缩放边框」扩到屏幕外，客户区跟着超出屏幕，最右侧的控件
 * （关闭按钮）被裁掉，且**只在最大化时出现**。见 win32_window.cc 的 WM_NCCALCSIZE。
 */
int32_t composekn_win32_client_overflow_count(ComposeKNWin32Window* window);

/** 触摸通道是否启用（COMPOSEKN_TOUCH=0 可关掉，退回系统「触摸提升成鼠标」的老行为）。 */
bool composekn_win32_touch_enabled(ComposeKNWin32Window* window);

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

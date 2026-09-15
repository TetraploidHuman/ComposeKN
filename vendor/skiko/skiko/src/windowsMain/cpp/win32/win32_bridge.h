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
    /*
     * IME（IMM32）。这四条事件本身不携带文本（事件结构体只有 int32 字段），
     * 与之一一对应、顺序一致的 UTF-8 文本要用 composekn_win32_ime_pop_text 取。
     *
     * 为什么单独开一条通道：文本框是 Compose 自绘的，系统侧没有 EDIT 控件，
     * 组字串/提交串只能由宿主从 IMM32 取出来交给 Compose 的文本输入层。
     */
    COMPOSEKN_WIN32_EVENT_IME_START = 14,   /* WM_IME_STARTCOMPOSITION */
    COMPOSEKN_WIN32_EVENT_IME_UPDATE = 15,  /* WM_IME_COMPOSITION + GCS_COMPSTR（组字预览） */
    COMPOSEKN_WIN32_EVENT_IME_COMMIT = 16,  /* WM_IME_COMPOSITION + GCS_RESULTSTR（提交） */
    COMPOSEKN_WIN32_EVENT_IME_END = 17,     /* WM_IME_ENDCOMPOSITION */
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
ComposeKNWin32Window* composekn_win32_create(
    const char* title, int width_dp, int height_dp, int undecorated);
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

/**
 * 设置光标形状：0=箭头 1=手 2=文本 I 型 3=十字。
 * 由 Compose 的 PointerIcon（Modifier.pointerHoverIcon / clickable 的默认手型）驱动。
 */
void composekn_win32_set_cursor(ComposeKNWin32Window* window, int32_t kind);

/** 触摸通道是否启用（COMPOSEKN_TOUCH=0 可关掉，退回系统「触摸提升成鼠标」的老行为）。 */
bool composekn_win32_touch_enabled(ComposeKNWin32Window* window);

// ---------------------------------------------------------------------------
// IME（IMM32）
// ---------------------------------------------------------------------------

/**
 * 弹出与最近一个 IME 事件配对的 UTF-8 文本（FIFO，与 COMPOSEKN_WIN32_EVENT_IME_*
 * 事件严格一一对应；没有文本的事件也会推入一个空串）。
 *
 * 返回写入的字节数（不含结尾 NUL）；队列为空返回 -1。
 */
int32_t composekn_win32_ime_pop_text(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);

/**
 * 注册「文本框字符矩形提供者」。
 *
 * IME 要把组字窗/候选窗摆到字符处时会**同步**回调它（可能发生在 WM_IME_REQUEST
 * 的 SendMessage 里），回调必须立刻填好**客户区物理像素**下的矩形
 * （x/y = 左上角，w/h = 尺寸）；拿不到就填 0。
 *
 * charIndex 语义（对应 IMECHARPOSITION.dwCharPos）：
 *   * >= 0：组字串里第 charIndex 个字符的矩形 —— 候选窗一般问第 0 个，所以候选窗
 *           会钉在**开始组字的位置**，不随着拼音越打越长往右跑；
 *   * < 0 ：不在组字中（或只是要「当前光标」）——回当前光标矩形。
 *
 * 不提供的话候选窗只能落在 (0,0)，表现为「候选词卡住/位置乱」。
 */
typedef void (*ComposeKNImeCaretFn)(
    void* user, int32_t charIndex, int32_t* x, int32_t* y, int32_t* w, int32_t* h);
void composekn_win32_set_ime_caret_provider(ComposeKNImeCaretFn fn, void* user);

/** 取消正在进行的组字（Compose 文本会话结束时调用，见 ImmNotifyIME/CPS_CANCEL）。 */
void composekn_win32_ime_cancel_composition(ComposeKNWin32Window* window);

/** 走过的 IME 消息条数（0 = 系统根本没发 IME 消息；自检/真机排查用）。 */
int32_t composekn_win32_ime_message_count(ComposeKNWin32Window* window);

/** 当前是否正在组字。 */
bool composekn_win32_ime_composing(ComposeKNWin32Window* window);

/** 自检用：注入一条「IME 提交」事件（Wine 里没有真 IME，驱动不了这条路）。 */
void composekn_win32_ime_test_commit(ComposeKNWin32Window* window, const char* utf8);

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

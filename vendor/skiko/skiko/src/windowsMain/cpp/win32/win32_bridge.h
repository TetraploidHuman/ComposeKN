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
    /*
     * 「重新转换」（再変換）的准备工作：IME 确认了要重转换的范围之后，应用必须先把
     * **原文本变成选区**，接下来那段组字才会替换它（否则文本会重复）。
     *
     * 不带文本（但仍然会配对一个空串，保持「事件与文本 FIFO 一一对应」的约定）；
     * 范围放在 a = 起始偏移、b = 结束偏移（UTF-16 code unit，文档坐标）。
     */
    COMPOSEKN_WIN32_EVENT_IME_RECONVERT_SELECT = 18,
    /*
     * OLE 拖放（接收侧 -> Compose 的 Modifier.dragAndDropTarget）。
     *
     * 事件结构体塞不下路径/文本，所以和 IME 一样用一条**严格 1:1 的 FIFO** 配负载：
     * 每弹一条 drag 事件，必须先弹一次 composekn_win32_drag_pop_files 和一次
     * composekn_win32_drag_pop_text（没有负载时返回 0 长度，而不是 -1）。
     *   x/y = 客户区物理像素（LEAVE 是 -1,-1）
     */
    COMPOSEKN_WIN32_EVENT_DRAG_ENTER = 19,  /* = IDropTarget::DragEnter */
    COMPOSEKN_WIN32_EVENT_DRAG_OVER = 20,   /* = IDropTarget::DragOver */
    COMPOSEKN_WIN32_EVENT_DRAG_LEAVE = 21,  /* = IDropTarget::DragLeave */
    COMPOSEKN_WIN32_EVENT_DRAG_DROP = 22,   /* = IDropTarget::Drop */
} ComposeKNWin32EventType;

typedef struct ComposeKNWin32Event {
    int32_t type;
    float x;          /* mouse: client coords; move: window x */
    float y;          /* mouse: client coords; move: window y */
    uint32_t button;  /* mouse: button id; key: virtual key code */
    uint32_t state;   /* 0=released 1=pressed */
    int32_t a;        /* key: flags; wheel: delta (120 = line); focus: 1=acquired;
                       * touch: 真实事件时间（归一化成进程内毫秒，见 win32_window.cc
                       *       的 touchTimeBase —— Compose 的甩动速度估计器要用它） */
    int32_t b;        /* char: unicode codepoint; key: scan code; mouse wheel: 1 = 横向 */
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

// ---------------------------------------------------------------------------
// OLE 拖放（接收侧）
// ---------------------------------------------------------------------------

/**
 * 最近一次回给 OLE 的 effect（DROPEFFECT_NONE=0 / DROPEFFECT_COPY=1）。
 *
 * 自检用它断言「Compose 拒绝这次拖放时，宿主有没有把光标从『可放下』改成『禁止』」。
 */
int32_t composekn_win32_last_drop_effect(ComposeKNWin32Window* window);

/** 拖放目标注册成功没有（OleInitialize/RegisterDragDrop 失败时为 false）。 */
bool composekn_win32_ole_available(ComposeKNWin32Window* window);

/**
 * 把 Compose 侧的判定写回宿主：true = 当前位置有控件愿意接收，OLE 的 *pdwEffect
 * 返回 DROPEFFECT_COPY（光标显示「可放下」）；false = DROPEFFECT_NONE。
 *
 * 为什么需要它：IDropTarget::DragEnter 必须**同步**回答 effect，而那会儿 Kotlin
 * 还没机会跑（OLE 在消息循环内部直接调我们）。策略见 win32_window.cc 里
 * ComposeKNDropTarget 的注释。
 */
bool composekn_win32_set_drop_accept(ComposeKNWin32Window* window, bool accept);

/**
 * 弹出与最近一条 drag 事件配对的负载（FIFO，与 COMPOSEKN_WIN32_EVENT_DRAG_* 严格
 * 一一对应）。
 *
 *   files: UTF-8，多个路径用 '\n' 分隔（没有文件时返回 0）
 *   text : UTF-8 文本（没有文本时返回 0）
 * 返回写入的字节数（不含结尾 NUL）；队列为空（说明两边配对错了）返回 -1。
 */
int32_t composekn_win32_drag_pop_files(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);
int32_t composekn_win32_drag_pop_text(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);

/**
 * 自检用：直接驱动注册好的 IDropTarget（构造一个真的 IDataObject 交给它）。
 *
 * phase: 0=DragEnter 1=DragOver 2=DragLeave 3=Drop
 * kind : 0=CF_HDROP（两条固定路径） 1=CF_UNICODETEXT
 * (x, y) 是**客户区**坐标（内部按真机那样换成屏幕坐标）。
 */
bool composekn_win32_test_simulate_drag(
    ComposeKNWin32Window* window, int32_t phase, int32_t x, int32_t y, int32_t kind);

// ---------------------------------------------------------------------------
// 自检用：真实 Win32 消息注入（PostMessage -> 主循环 -> 真实 wndproc 分支）
//
// 存在的理由：窗口阶段的自检以前只从 Kotlin 侧合成 WindowsEvent，C++ 宿主这一层
// （wndproc 的参数解码 / 坐标换算 / 消息过滤）在自动化里从未被执行 —— 历史 bug
// （笔悬停变手指、Shift+滚轮没实现）全是「宿主层问题、真机才发现」。
// ---------------------------------------------------------------------------

/**
 * 投递一条**真实的鼠标消息**到窗口自己的消息队列。
 *
 *   message                    x / y           wheel_delta
 *   WM_MOUSEMOVE               客户区坐标      忽略
 *   WM_LBUTTONDOWN/UP 等按键消息  客户区坐标      忽略
 *   WM_MOUSEWHEEL / WM_MOUSEHWHEEL  客户区坐标   滚轮增量（缩进 wParam 高 16 位；
 *                                                负值 = 向用户方向滚）
 *
 * 滚轮消息的坐标会被换成**屏幕坐标**再进 lParam —— 与真机消息一致（宿主 wndproc
 * 里用 ScreenToClient 换回客户区）。返回 false = 窗口还没建好 / PostMessage 失败。
 */
bool composekn_win32_post_test_mouse(
    ComposeKNWin32Window* window,
    uint32_t message,
    int32_t x,
    int32_t y,
    int32_t wheel_delta
);

/**
 * 投递一条**真实的键盘/字符消息**到窗口自己的消息队列。
 *
 *   message                                    vk_or_char        scan_code / is_repeat
 *   WM_KEYDOWN/UP, WM_SYSKEYDOWN/UP            虚拟键码           用来拼 lParam
 *                                              （bit16-23 扫描码、bit30 之前是否已按下）
 *   WM_CHAR / WM_UNICHAR                       字符码点           忽略
 */
bool composekn_win32_post_test_key(
    ComposeKNWin32Window* window,
    uint32_t message,
    int32_t vk_or_char,
    int32_t scan_code,
    int32_t is_repeat
);

/** ShowWindow wrapper: cmd 3=SW_MAXIMIZE 6=SW_MINIMIZE 9=SW_RESTORE 5=SW_SHOW */
void composekn_win32_show(ComposeKNWin32Window* window, int cmd);
bool composekn_win32_is_maximized(ComposeKNWin32Window* window);
bool composekn_win32_is_minimized(ComposeKNWin32Window* window);
void composekn_win32_request_close(ComposeKNWin32Window* window);
void composekn_win32_set_title(ComposeKNWin32Window* window, const char* title);

void composekn_win32_clipboard_get_text(ComposeKNWin32Window* window, char* buffer, size_t buffer_size, bool* ok);
void composekn_win32_clipboard_set_text(ComposeKNWin32Window* window, const char* text);

// ---------------------------------------------------------------------------
// 剪贴板：富文本格式（CF_HTML / Rich Text Format / CF_DIBV5）
//
// 上游对齐点：桌面 Compose 的 `ClipEntry` 是「包住平台原生条目」的壳
// （desktop 是 AWT `Transferable`，用 `asAwtTransferable` 读），格式能力由平台决定。
// 我们这边同样由平台决定：Windows 用 CF_HTML（标准 HTML Clipboard Format，带
// StartHTML/EndHTML/StartFragment/EndFragment 偏移头）、注册格式 "Rich Text Format"、
// 以及 CF_DIBV5（32bpp BGRA 位图）。
//
// 所有 get 都是「写进你给的缓冲区」的两段式约定：
//   * 没有这个格式/读不出来 -> 返回 -1；
//   * 缓冲区太小 -> 返回**需要的字节数**（调用方按这个值重新开缓冲再调一次）；
//   * 成功 -> 返回写入的字节数（不含结尾 NUL）。
// ---------------------------------------------------------------------------

/**
 * **一次事务**里把多种格式放进剪贴板（Windows 的剪贴板是"一次 EmptyClipboard + 多次
 * SetClipboardData"的模型 —— 分几次调用会把前一次的内容擦掉）。
 *
 * 传 nullptr / 0 表示"这个格式不要放"。text 会转成 CF_UNICODETEXT，html 会包上标准
 * CF_HTML 头，rtf 走注册格式 "Rich Text Format"，image 走 CF_DIBV5。
 *
 * 这是 `Clipboard.setClipEntry()` 的平台层实现：应用给一个条目，里面有哪些格式就写哪些
 * （Word/Chrome 都是这么放的 —— 老程序拿文本、支持 HTML 的拿 HTML）。
 */
void composekn_win32_clipboard_set_rich(
    ComposeKNWin32Window* window,
    const char* utf8_text,
    const char* utf8_html,
    const char* utf8_rtf,
    int32_t image_width,
    int32_t image_height,
    const uint8_t* bgra);

/**
 * CF_HTML 里的**片段**（`<!--StartFragment-->` 与 `<!--EndFragment-->` 之间的那段 HTML），
 * 不是整个剪贴板 blob —— 头部的偏移解析在 C 侧做掉，应用只需要管 HTML 本身。
 */
int32_t composekn_win32_clipboard_get_html(ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);

/** 把 HTML 片段包成标准 CF_HTML 头写进剪贴板（UTF-8）。 */
void composekn_win32_clipboard_set_html(ComposeKNWin32Window* window, const char* utf8_html);

/** 注册格式 "Rich Text Format" 的内容（RTF 本身是 ASCII，带 \uN 转义）。 */
int32_t composekn_win32_clipboard_get_rtf(ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);
void composekn_win32_clipboard_set_rtf(ComposeKNWin32Window* window, const char* utf8_rtf);

/**
 * CF_DIBV5 位图，统一转成 **BGRA、每像素 4 字节、自上而下、stride = width*4**。
 *
 * 两段式：`buffer == NULL || buffer_size == 0` 时只回需要的字节数（没有图片回 -1）；
 * 否则把尺寸写进 out[0]=width、out[1]=height 并返回写入的字节数。
 *
 * 读的时候支持 CF_DIBV5(124 字节头) / CF_DIB(40 字节头) / CF_DIB(12 字节 CORE 头)，
 * 32bpp 与 24bpp、自上而下与自下而上都处理；**调色板/压缩格式（RLE 等）不支持**
 * （回 -1 并记一行日志），需要的话再补。
 */
int32_t composekn_win32_clipboard_get_image(
    ComposeKNWin32Window* window, uint8_t* buffer, int32_t buffer_size, int32_t* out_size);

/** width/height 是像素；`bgra` 必须自上而下、stride = width*4。 */
void composekn_win32_clipboard_set_image(
    ComposeKNWin32Window* window, int32_t width, int32_t height, const uint8_t* bgra);

/**
 * CF_HDROP：剪贴板上的**文件路径列表**（在资源管理器里 Ctrl+C 一个文件就是这个格式）。
 *
 * UTF-8、多条用 '\n' 分隔；没有该格式返回 -1（两段式：缓冲区不够回需要的字节数）。
 */
int32_t composekn_win32_clipboard_get_files(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size);

// ---- 自检用：往剪贴板放特定形态的内容（真机上不好复现的格式）----

/**
 * 自检用：直接把一段 DIB 字节喂给解码器（不经过剪贴板）。
 *
 * Wine 会对 8bpp 的 CF_DIB 做有损转换，所以"8bpp 调色板解码"这条路必须绕开剪贴板才
 * 能在自动化里跑到（真机上从画图/老程序复制的 256 色图走的正是它）。
 * 两段式：`out_size == 0` 时只回需要的字节数；尺寸写到 `out_dims[0..1]`。
 */
int32_t composekn_win32_test_decode_dib(
    const uint8_t* dib, int32_t dib_size,
    uint8_t* out_bgra, int32_t out_size, int32_t* out_dims);

/** 放一个 CF_HDROP（`utf8_paths` 是 '\n' 分隔的 UTF-8 路径）。 */
bool composekn_win32_clipboard_test_set_files(ComposeKNWin32Window* window, const char* utf8_paths);

/** 放一张**只有** CF_BITMAP（裸 HBITMAP）的图 —— 有的截图工具就只给这个。 */
bool composekn_win32_clipboard_test_set_bitmap(
    ComposeKNWin32Window* window, int32_t width, int32_t height, const uint8_t* bgra);

/**
 * 放一张 8bpp **调色板** CF_DIB（老程序/256 色画图）。
 *
 * `indices` 每像素 1 字节（取低 2 位，映射到固定 4 色调色板：红/绿/蓝/白）。
 */
bool composekn_win32_clipboard_test_set_dib8(
    ComposeKNWin32Window* window, int32_t width, int32_t height,
    const uint8_t* indices, int32_t index_count);

/**
 * 自检用：把剪贴板里某个格式的**原始字节**（hex）读出来，供测试独立校验
 * 「我们写进去的 CF_HTML 头/位图头到底长什么样」。
 *
 * format_name：`"HTML Format"` / `"Rich Text Format"`（注册格式名），或以 `#` 开头的
 * 标准格式号（例如 `"#8"` = CF_DIB、`"#17"` = CF_DIBV5）。没有该格式回 -1。
 */
int32_t composekn_win32_clipboard_get_raw_hex(
    ComposeKNWin32Window* window, const char* format_name, char* buffer, int32_t buffer_size);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* COMPOSEKN_WIN32_BRIDGE_H */

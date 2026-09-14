/**
 * Minimal Win32 window + GDI software presentation bridge for Kotlin/Native
 * Windows (mingwX64) targets of skiko. Mirrors the design of the Wayland
 * bridge: C owns the window and input plumbing, Kotlin owns rendering.
 */
#include "win32_bridge.h"
#include <windows.h>
#include <windowsx.h>
#include <cstdarg>
#include <string>
#include <cstdio>
#include <cstdint>
#include <ctime>
#include <vector>
#include <cstdio>
#include <cstring>
#include <cstdlib>

// ---------------------------------------------------------------------------
// ComposeKN 启动诊断日志
//
// 日志写到 exe 同目录下的 composekn-startup.log（失败时退回 %TEMP%），
// 并安装未处理异常过滤器，把崩溃代码/地址也写进去。这样即使双击运行时
// 控制台窗口一闪而过，也能拿到启动过程记录。
// ---------------------------------------------------------------------------
namespace {

FILE* composeknOpenLog() {
    // 1) exe 所在目录
    wchar_t module[MAX_PATH] = {0};
    DWORD n = GetModuleFileNameW(nullptr, module, MAX_PATH);
    if (n > 0 && n < MAX_PATH) {
        std::wstring dir(module, n);
        size_t pos = dir.find_last_of(L"\\/");
        if (pos != std::wstring::npos) {
            std::wstring path = dir.substr(0, pos + 1) + L"composekn-startup.log";
            FILE* f = _wfopen(path.c_str(), L"a");
            if (f != nullptr) {
                return f;
            }
        }
    }
    // 2) 退回 %TEMP%
    wchar_t tmp[MAX_PATH] = {0};
    DWORD t = GetTempPathW(MAX_PATH, tmp);
    if (t > 0 && t < MAX_PATH) {
        std::wstring path(tmp);
        path += L"composekn-startup.log";
        return _wfopen(path.c_str(), L"a");
    }
    return nullptr;
}

void composeknLog(const char* fmt, ...) {
    FILE* f = composeknOpenLog();
    if (f == nullptr) return;
    SYSTEMTIME st;
    GetLocalTime(&st);
    std::fprintf(f, "[%02d:%02d:%02d.%03d] ", st.wHour, st.wMinute, st.wSecond, st.wMilliseconds);
    va_list ap;
    va_start(ap, fmt);
    std::vfprintf(f, fmt, ap);
    va_end(ap);
    std::fputc('\n', f);
    std::fflush(f);
    std::fclose(f);
}

LONG WINAPI composeknUnhandledFilter(EXCEPTION_POINTERS* info) {
    if (info != nullptr && info->ExceptionRecord != nullptr) {
        composeknLog("!!! UNHANDLED EXCEPTION code=0x%08lX addr=%p flags=0x%lX",
                     (unsigned long)info->ExceptionRecord->ExceptionCode,
                     (void*)info->ExceptionRecord->ExceptionAddress,
                     (unsigned long)info->ExceptionRecord->ExceptionFlags);
        if (info->ExceptionRecord->ExceptionCode == EXCEPTION_ACCESS_VIOLATION &&
            info->ExceptionRecord->NumberParameters >= 2) {
            composeknLog("    access violation: %s address=%p",
                         info->ExceptionRecord->ExceptionInformation[0] ? "write" : "read",
                         (void*)info->ExceptionRecord->ExceptionInformation[1]);
        }
    }
    return EXCEPTION_EXECUTE_HANDLER;
}

// 在 CRT 初始化阶段（main 之前）就写好第一行，用于确认日志本身能工作。
struct ComposeKNStartupLogger {
    ComposeKNStartupLogger() {
        composeknLog("=== composekn native bridge: C++ static init (exe=%p) ===", (void*)GetModuleHandleW(nullptr));
        SetUnhandledExceptionFilter(composeknUnhandledFilter);
    }
};

ComposeKNStartupLogger g_composeknStartupLogger;

}  // namespace

extern "C" void composekn_win32_log(const char* message) {
    composeknLog("%s", message == nullptr ? "(null)" : message);
}

namespace {

constexpr const wchar_t* kWindowClass = L"ComposeKNWin32Window";
constexpr int kEdgeMarginBase = 6; // logical px, scaled by DPI

}  // end anonymous namespace

struct ComposeKNWin32Window {
    HWND hwnd = nullptr;
    int width = 0;
    int height = 0;
    int dpi = 96;
    bool closeRequested = false;
    bool quit = false;
    std::vector<ComposeKNWin32Event> events;
    int trackPointerButtons = 0;
    int pressedButtonMask = 0;
    bool imeEnabled = false;
    bool paintLogged = false;
    bool sizeLogged = false;
    bool presentLogged = false;
    // 最近一帧的像素缓存：缩放/重绘期间用来立刻重绘，避免白屏
    std::vector<unsigned char> frame;
    int frameW = 0;
    int frameH = 0;
};

namespace {

static int eventQueueIndex = 0;

static uint32_t queryCurrentModifiers() {
    uint32_t modifiers = 0;
    if (GetKeyState(VK_SHIFT) < 0) modifiers |= 1u << 0;
    if (GetKeyState(VK_CONTROL) < 0) modifiers |= 1u << 1;
    if (GetKeyState(VK_MENU) < 0) modifiers |= 1u << 2;
    if (GetKeyState(VK_LWIN) < 0 || GetKeyState(VK_RWIN) < 0) modifiers |= 1u << 3;
    return modifiers;
}

static void pushEvent(ComposeKNWin32Window* window, const ComposeKNWin32Event& event) {
    window->events.push_back(event);
}

static int edgeMargin(ComposeKNWin32Window* window) {
    return MulDiv(kEdgeMarginBase, window->dpi, 96);
}

static double dpiScaleOf(const ComposeKNWin32Window* window) {
    return (window != nullptr && window->dpi > 0) ? (window->dpi / 96.0) : 1.0;
}

// 让进程具备 DPI 感知：否则 HiDPI 显示器上 Windows 会把整个窗口位图拉伸，
// 自绘 UI 会明显发糊。PER_MONITOR_AWARE_V2 = -4。
static void enableDpiAwareness() {
    static bool done = false;
    if (done) return;
    done = true;
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32 == nullptr) return;
    typedef BOOL (WINAPI *SetProcessDpiAwarenessContextFn)(HANDLE);
    auto setCtx = reinterpret_cast<SetProcessDpiAwarenessContextFn>(
        GetProcAddress(user32, "SetProcessDpiAwarenessContext"));
    if (setCtx != nullptr && setCtx(reinterpret_cast<HANDLE>(-4))) return;
    typedef BOOL (WINAPI *SetProcessDPIAwareFn)();
    auto setAware = reinterpret_cast<SetProcessDPIAwareFn>(
        GetProcAddress(user32, "SetProcessDPIAware"));
    if (setAware != nullptr) setAware();
}

// 把最近一帧（必要时拉伸）贴到当前客户区。缩放/重绘时用它立刻补画。
static void blitFrame(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    if (window->frame.empty() || window->frameW <= 0 || window->frameH <= 0) return;
    RECT rc;
    if (!GetClientRect(window->hwnd, &rc)) return;
    int cw = rc.right - rc.left;
    int ch = rc.bottom - rc.top;
    if (cw <= 0 || ch <= 0) return;
    HDC dc = GetDC(window->hwnd);
    if (dc == nullptr) return;
    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = window->frameW;
    bmi.bmiHeader.biHeight = -window->frameH;  // top-down
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;
    SetStretchBltMode(dc, COLORONCOLOR);
    StretchDIBits(dc, 0, 0, cw, ch, 0, 0, window->frameW, window->frameH,
                  window->frame.data(), &bmi, DIB_RGB_COLORS, SRCCOPY);
    ReleaseDC(window->hwnd, dc);
}

// ---------------------------------------------------------------------------
// C++ -> Kotlin 渲染回调
//
// 拖拽缩放时 Windows 会进入模态循环（在 DefWindowProc 内部），Kotlin 侧的渲染
// 循环在这期间完全得不到执行，窗口只能显示上一帧被拉伸的结果。这里在 WM_SIZE
// 里同步回调一次 Kotlin 的 renderImmediately()，让内容按新尺寸逐帧重组。
typedef void (*ComposeKNRenderTickFn)(void* user);
static ComposeKNRenderTickFn g_renderTick = nullptr;
static void* g_renderTickUser = nullptr;
static bool g_inRenderTick = false;  // 防重入

extern "C" void composekn_win32_set_render_tick(ComposeKNRenderTickFn fn, void* user) {
    g_renderTick = fn;
    g_renderTickUser = user;
}

static void fireRenderTick() {
    if (g_renderTick == nullptr || g_inRenderTick) return;
    g_inRenderTick = true;
    g_renderTick(g_renderTickUser);
    g_inRenderTick = false;
}

// 调试钩子：COMPOSEKN_TEST_RESIZE=1 时自动模拟若干次窗口缩放。
// Wine/Xvfb 下没有窗口管理器，无法从外部触发 WM_SIZE，用它验证上面这条链路。
static const UINT_PTR kTestResizeTimerId = 0xC011;

// 私有「醒一醒」消息：只用于把阻塞在 composekn_win32_wait_message 的线程叫起来。
// wndproc 里直接吞掉，绝不进事件队列。
static const UINT kComposeKNWakeMessage = WM_APP + 1;

static void maybeStartTestResize(ComposeKNWin32Window* window) {
    const char* env = getenv("COMPOSEKN_TEST_RESIZE");
    if (env == nullptr || env[0] == '\0' || env[0] == '0') return;
    composeknLog("test: COMPOSEKN_TEST_RESIZE=1 -> 自动模拟 6 次窗口缩放");
    SetTimer(window->hwnd, kTestResizeTimerId, 700, nullptr);
}

static LRESULT CALLBACK composeknWndProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* window = reinterpret_cast<ComposeKNWin32Window*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (window == nullptr && message != WM_NCCREATE) {
        return DefWindowProcW(hwnd, message, wParam, lParam);
    }
    switch (message) {
        case WM_CREATE:
            composeknLog("wndproc: WM_CREATE hwnd=%p", (void*)hwnd);
            break;
        case WM_NCHITTEST: {
            if (window->hwnd == hwnd) {
                // Parent is our window; keep default edges for maximized/menus
                if (IsZoomed(hwnd) || IsIconic(hwnd)) break;
                LONG gx = GET_X_LPARAM(lParam);
                LONG gy = GET_Y_LPARAM(lParam);
                RECT r;
                if (!GetWindowRect(hwnd, &r)) break;
                int m = edgeMargin(window);
                // Overshoot guard: min window dims bigger than 2*m handled by WM_GETMINMAXINFO
                bool leftEdge = gx < r.left + m;
                bool rightEdge = gx > r.right - m;
                bool topEdge = gy < r.top + m;
                bool bottomEdge = gy > r.bottom - m;
                if (topEdge) {
                    if (leftEdge) return HTTOPLEFT;
                    if (rightEdge) return HTTOPRIGHT;
                    return HTTOP;
                }
                if (bottomEdge) {
                    if (leftEdge) return HTBOTTOMLEFT;
                    if (rightEdge) return HTBOTTOMRIGHT;
                    return HTBOTTOM;
                }
                if (leftEdge) return HTLEFT;
                if (rightEdge) return HTRIGHT;
            }
            break;
        }
        case WM_NCCALCSIZE: {
            // 无系统边框/标题栏：我们自绘标题栏（CSD），缩放靠 WM_NCHITTEST。
            // 注意 wParam == 0（窗口创建/普通查询）也必须返回 0 且不调用
            // DefWindowProc，否则系统标题栏会被画出来（会出现两条标题栏，
            // 而拖拽缩放时 wParam != 0 又变回无边框，看起来像"标题栏消失"）。
            if (wParam != 0 && IsZoomed(hwnd)) {
                NCCALCSIZE_PARAMS* nc = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
                nc->rgrc[0].top += 8;
            }
            return 0;
        }
        case WM_SIZE: {
            int w = LOWORD(lParam);
            int h = HIWORD(lParam);
            if (w > 0 && h > 0 && (w != window->width || h != window->height)) {
                window->width = w;
                window->height = h;
                ComposeKNWin32Event e{};
                e.type = COMPOSEKN_WIN32_EVENT_SIZE;
                e.a = w;
                e.b = h;
                pushEvent(window, e);
                // 同步渲染一帧（模态缩放循环期间 Kotlin 循环跑不到）
                fireRenderTick();
            }
            break;
        }
        case WM_MOVE: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_MOVE;
            e.x = static_cast<float>(GET_X_LPARAM(lParam));
            e.y = static_cast<float>(GET_Y_LPARAM(lParam));
            pushEvent(window, e);
            break;
        }
        case WM_SETFOCUS: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_FOCUS;
            e.a = 1;
            pushEvent(window, e);
            break;
        }
        case WM_KILLFOCUS: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_FOCUS;
            e.a = 0;
            pushEvent(window, e);
            break;
        }
        case WM_ERASEBKGND:
            // 自己负责全部像素：不要用背景刷擦成白色（缩放时会闪白）。
            return 1;
        case WM_PAINT: {
            if (window != nullptr && !window->paintLogged) {
                window->paintLogged = true;
                composeknLog("wndproc: first WM_PAINT (client %dx%d)", window->width, window->height);
            }
            PAINTSTRUCT ps;
            BeginPaint(hwnd, &ps);
            EndPaint(hwnd, &ps);
            blitFrame(window);
            return 0;
        }
        case WM_TIMER: {
            if (wParam == kTestResizeTimerId && window != nullptr) {
                static int step = 0;
                ++step;
                if (step > 6) {
                    KillTimer(hwnd, kTestResizeTimerId);
                    composeknLog("test: 模拟缩放结束");
                    break;
                }
                RECT rc;
                GetWindowRect(hwnd, &rc);
                int dw = (step % 2 == 1) ? 120 : -120;
                int dh = (step % 2 == 1) ? 90 : -90;
                int nw = (rc.right - rc.left) + dw;
                int nh = (rc.bottom - rc.top) + dh;
                composeknLog("test: SetWindowPos -> %dx%d", nw, nh);
                SetWindowPos(hwnd, nullptr, 0, 0, nw, nh,
                             SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
            }
            break;
        }
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
        case WM_KEYUP:
        case WM_SYSKEYUP: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_KEY;
            e.button = static_cast<uint32_t>(wParam);
            e.state = (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) ? 1u : 0u;
            e.a = 0; // flags placeholder
            e.b = static_cast<int32_t>((lParam >> 16) & 0xFF);
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_CHAR:
        case WM_UNICHAR: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_CHAR;
            e.b = message == WM_UNICHAR ? static_cast<int32_t>(wParam)
                                        : static_cast<int32_t>(static_cast<wchar_t>(wParam));
            e.a = 0;
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_MOUSEMOVE: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_MOUSE_MOVE;
            e.x = static_cast<float>(GET_X_LPARAM(lParam));
            e.y = static_cast<float>(GET_Y_LPARAM(lParam));
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_LBUTTONDOWN:
        case WM_LBUTTONUP:
        case WM_RBUTTONDOWN:
        case WM_RBUTTONUP:
        case WM_MBUTTONDOWN:
        case WM_MBUTTONUP: {
            uint32_t buttonId = message == WM_LBUTTONDOWN || message == WM_LBUTTONUP
                ? 272u
                : (message == WM_RBUTTONDOWN || message == WM_RBUTTONUP ? 274u : 276u);
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_MOUSE_BUTTON;
            e.x = static_cast<float>(GET_X_LPARAM(lParam));
            e.y = static_cast<float>(GET_Y_LPARAM(lParam));
            e.button = buttonId;
            e.state = (message == WM_LBUTTONDOWN || message == WM_RBUTTONDOWN || message == WM_MBUTTONDOWN) ? 1u : 0u;
            e.a = 0;
            e.b = 0;
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_MOUSEWHEEL: {
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_MOUSE_WHEEL;
            // screen->client coords
            POINT pt = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            ScreenToClient(window->hwnd, &pt);
            e.x = static_cast<float>(pt.x);
            e.y = static_cast<float>(pt.y);
            e.a = GET_WHEEL_DELTA_WPARAM(wParam);
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_CLOSE: {
            if (window) {
                ComposeKNWin32Event e{};
                e.type = COMPOSEKN_WIN32_EVENT_CLOSE;
                pushEvent(window, e);
                window->closeRequested = true;
            }
            return 0;
        }
        case kComposeKNWakeMessage:
            // 纯粹用来唤醒 MsgWaitForMultipleObjectsEx；没有任何副作用。
            return 0;
        case WM_DESTROY: {
            if (window) window->quit = true;
            PostQuitMessage(0);
            return 0;
        }
        case WM_NCCREATE: {
            CREATESTRUCTW* cs = reinterpret_cast<CREATESTRUCTW*>(lParam);
            if (cs != nullptr) {
                SetWindowLongPtrW(hwnd, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(cs->lpCreateParams));
            }
            return DefWindowProcW(hwnd, message, wParam, lParam);
        }
        default:
            break;
    }
    return DefWindowProcW(hwnd, message, wParam, lParam);
}

static bool registerWindowClass(HINSTANCE instance) {
    WNDCLASSEXW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.style = CS_HREDRAW | CS_VREDRAW | CS_OWNDC;
    wc.lpfnWndProc = composeknWndProc;
    wc.hInstance = instance;
    wc.hCursor = LoadCursorW(nullptr, (LPCWSTR)IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = kWindowClass;
    return RegisterClassExW(&wc) != 0;
}

static int queryWindowDpi(HWND hwnd) {
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32) {
        typedef int (WINAPI *GetDpiForWindowFn)(HWND);
        auto fn = reinterpret_cast<GetDpiForWindowFn>(GetProcAddress(user32, "GetDpiForWindow"));
        if (fn) {
            int dpi = fn(hwnd);
            if (dpi > 0) return dpi;
        }
    }
    HDC dc = GetDC(nullptr);
    int dpi100 = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(nullptr, dc);
    return dpi100;
}

} // namespace

extern "C" ComposeKNWin32Window* composekn_win32_create(const char* title, int width, int height) {
    enableDpiAwareness();
    HINSTANCE instance = GetModuleHandleW(nullptr);
    static bool classRegistered = false;
    if (!classRegistered) {
        if (!registerWindowClass(instance)) return nullptr;
        classRegistered = true;
    }

    int wlen = MultiByteToWideChar(CP_UTF8, 0, title, -1, nullptr, 0);
    std::vector<wchar_t> wtitle(static_cast<size_t>(wlen > 0 ? wlen : 1), L'\0');
    if (wlen > 0) {
        MultiByteToWideChar(CP_UTF8, 0, title, -1, wtitle.data(), wlen);
    }

    composeknLog("composekn_win32_create: title=\"%s\" size=%dx%d", title, width, height);
    ComposeKNWin32Window* window = new ComposeKNWin32Window();
    window->width = width;
    window->height = height;
    window->hwnd = CreateWindowExW(
        0,
        kWindowClass,
        wtitle.data(),
        WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT,
        width, height,
        nullptr, nullptr,
        instance,
        window /* lpParam -> WM_NCCREATE sets GWLP_USERDATA */
    );
    if (window->hwnd == nullptr) {
        composeknLog("composekn_win32_create: CreateWindowExW FAILED GetLastError=%lu", (unsigned long)GetLastError());
        delete window;
        return nullptr;
    }
    composeknLog("composekn_win32_create: hwnd=%p ok", (void*)window->hwnd);
    window->dpi = queryWindowDpi(window->hwnd);
    ShowWindow(window->hwnd, SW_SHOWNORMAL);
    UpdateWindow(window->hwnd);
    maybeStartTestResize(window);
    return window;
}

extern "C" void composekn_win32_destroy(ComposeKNWin32Window* window) {
    if (window == nullptr) return;
    if (window->hwnd != nullptr && IsWindow(window->hwnd)) {
        DestroyWindow(window->hwnd);
    }
    delete window;
}

extern "C" void composekn_win32_begin_move(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    ReleaseCapture();
    SendMessageW(window->hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
}

extern "C" bool composekn_win32_pump(ComposeKNWin32Window* window) {
    if (window == nullptr) return false;
    MSG msg;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) {
            window->quit = true;
            return false;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
        if (window->quit) return false;
    }
    return !window->closeRequested;
}

extern "C" bool composekn_win32_wait_message(ComposeKNWin32Window* window, int32_t timeout_ms) {
    if (window == nullptr) return false;
    if (window->quit || window->closeRequested) return false;
    // MWMO_INPUTAVAILABLE：队列里**已经**有消息（只是还没被 Peek 取走）时立刻返回，
    // 否则会白等一整个 timeout —— 那样输入就会带着最多一个帧周期的额外延迟。
    const DWORD timeout = (timeout_ms < 0) ? INFINITE : static_cast<DWORD>(timeout_ms);
    const DWORD r = MsgWaitForMultipleObjectsEx(
        0, nullptr, timeout, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
    if (r == WAIT_FAILED) {
        composeknLog("wait_message: MsgWaitForMultipleObjectsEx 失败 err=%lu",
                     (unsigned long)GetLastError());
        return false;
    }
    return !window->quit && !window->closeRequested;
}

extern "C" void composekn_win32_wake(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    PostMessageW(window->hwnd, kComposeKNWakeMessage, 0, 0);
}

extern "C" bool composekn_win32_pop_event_flat(
    ComposeKNWin32Window* window,
    int32_t* type,
    float* x,
    float* y,
    uint32_t* button,
    uint32_t* state,
    int32_t* a,
    int32_t* b,
    uint32_t* modifiers
) {
    if (window == nullptr || window->events.empty()) return false;
    ComposeKNWin32Event e = window->events.front();
    window->events.erase(window->events.begin());
    *type = e.type;
    *x = e.x;
    *y = e.y;
    *button = e.button;
    *state = e.state;
    *a = e.a;
    *b = e.b;
    *modifiers = e.modifiers;
    return true;
}

extern "C" void composekn_win32_present(
    ComposeKNWin32Window* window,
    const void* pixels,
    int width,
    int height,
    int stride_px
) {
    if (window == nullptr || window->hwnd == nullptr || pixels == nullptr) return;
    if (!window->presentLogged) {
        window->presentLogged = true;
        composeknLog("composekn_win32_present: first frame %dx%d stride=%d pixels=%p",
                     width, height, stride_px, pixels);
    }
    // 缓存这一帧：WM_PAINT / 缩放过程中用它立刻重绘。
    // 源像素现在直接来自 Skia raster surface（Kotlin 侧零拷贝），行可能有 padding
    // （stride_px > width），所以按行拷成紧密排布 —— blitFrame 的 DIB 头写死
    // biWidth = frameW，也就是紧密跨度。
    const int strideBytes = (stride_px > 0 ? stride_px : width) * 4;
    const size_t rowBytes = static_cast<size_t>(width) * 4u;
    const unsigned char* src = static_cast<const unsigned char*>(pixels);
    window->frame.resize(rowBytes * static_cast<size_t>(height));
    if (strideBytes == static_cast<int>(rowBytes)) {
        std::memcpy(window->frame.data(), src, rowBytes * static_cast<size_t>(height));
    } else {
        for (int y = 0; y < height; ++y) {
            std::memcpy(window->frame.data() + rowBytes * static_cast<size_t>(y),
                        src + static_cast<size_t>(strideBytes) * static_cast<size_t>(y),
                        rowBytes);
        }
    }
    window->frameW = width;
    window->frameH = height;
    blitFrame(window);
}

// ---------------------------------------------------------------------------
// 后备缓冲（present buffer）：对齐上游 skiko SOFTWARE_FAST 的零拷贝呈现
//
// 上游（awtMain/cpp/windows/SoftwareRedrawer.cc）的做法是：redrawer 持有一块
// BITMAPINFO + 像素的内存，用 SkSurfaces::WrapPixels 让 Skia **直接画进去**，
// finishFrame 里再 StretchDIBits 出去 —— 不产生任何多余的整窗拷贝。
//
// 这里做同样的事：`window->frame` 就是那块内存（紧密 BGRA），Kotlin 侧用
// Surface.makeRasterDirect 包装它，画完调 composekn_win32_present_buffer 直接上传。
// ---------------------------------------------------------------------------
extern "C" void* composekn_win32_backbuffer_pixels(
    ComposeKNWin32Window* window,
    int width,
    int height
) {
    if (window == nullptr || window->hwnd == nullptr) {
        composeknLog("composekn_win32_backbuffer_pixels: 失败（window=%p hwnd=%p）",
                     static_cast<void*>(window),
                     window != nullptr ? static_cast<void*>(window->hwnd) : nullptr);
        return nullptr;
    }
    if (width <= 0 || height <= 0) {
        composeknLog("composekn_win32_backbuffer_pixels: 失败（尺寸 %dx%d 非法）", width, height);
        return nullptr;
    }
    const size_t needed = static_cast<size_t>(width) * static_cast<size_t>(height) * 4u;
    // 尺寸变了才重新分配（指针会变，调用方必须重建包装它的 surface）。
    if (window->frameW != width || window->frameH != height || window->frame.size() != needed) {
        window->frame.assign(needed, 0);
        window->frameW = width;
        window->frameH = height;
        composeknLog("composekn_win32_backbuffer_pixels: 分配后备缓冲 %dx%d (%.1f MB) ptr=%p",
                     width, height, static_cast<double>(needed) / (1024.0 * 1024.0),
                     static_cast<void*>(window->frame.data()));
    }
    return window->frame.data();
}

extern "C" void composekn_win32_present_buffer(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    if (!window->presentLogged) {
        window->presentLogged = true;
        composeknLog("composekn_win32_present_buffer: first frame %dx%d (零拷贝后备缓冲)",
                     window->frameW, window->frameH);
    }
    blitFrame(window);
}

extern "C" int64_t composekn_win32_process_cpu_nanos(ComposeKNWin32Window* window) {
    (void)window;
    FILETIME creation, exitTime, kernel, user;
    if (!GetProcessTimes(GetCurrentProcess(), &creation, &exitTime, &kernel, &user)) {
        return -1;
    }
    auto toNanos = [](const FILETIME& ft) -> int64_t {
        // FILETIME 单位是 100ns
        const uint64_t ticks = (static_cast<uint64_t>(ft.dwHighDateTime) << 32) |
                               static_cast<uint64_t>(ft.dwLowDateTime);
        return static_cast<int64_t>(ticks * 100ull);
    };
    return toNanos(kernel) + toNanos(user);
}

extern "C" void* composekn_win32_hwnd(ComposeKNWin32Window* window) {
    if (window == nullptr) return nullptr;
    return static_cast<void*>(window->hwnd);
}

extern "C" int32_t composekn_win32_processor_count(void) {
    const DWORD n = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    return n > 0 ? static_cast<int32_t>(n) : 1;
}

// 逻辑像素（= 物理像素 / dpiScale）。渲染表面尺寸 = 逻辑尺寸 * dpiScale，
// 与窗口客户区物理像素 1:1，配合 DPI 感知即可得到清晰（不糊）的 UI。
extern "C" int composekn_win32_width(ComposeKNWin32Window* window) {
    if (window == nullptr) return 0;
    return static_cast<int>(window->width / dpiScaleOf(window) + 0.5);
}

extern "C" int composekn_win32_height(ComposeKNWin32Window* window) {
    if (window == nullptr) return 0;
    return static_cast<int>(window->height / dpiScaleOf(window) + 0.5);
}

extern "C" float composekn_win32_dpi_scale(ComposeKNWin32Window* window) {
    return static_cast<float>(dpiScaleOf(window));
}

extern "C" int32_t composekn_win32_refresh_hz(ComposeKNWin32Window* window) {
    (void)window;
    // 屏幕 DC 上的 VREFRESH 就是主显示器刷新率；虚拟机/RDP/远程桌面上会返回 0 或 1，
    // 由调用方判断可信区间（拿不到就退回 60Hz）。
    HDC dc = GetDC(nullptr);
    if (dc == nullptr) return 0;
    const int hz = GetDeviceCaps(dc, VREFRESH);
    ReleaseDC(nullptr, dc);
    return hz;
}

extern "C" void composekn_win32_show(ComposeKNWin32Window* window, int cmd) {
    if (window == nullptr || window->hwnd == nullptr) return;
    ShowWindow(window->hwnd, cmd);
    InvalidateRect(window->hwnd, nullptr, FALSE);
}

extern "C" bool composekn_win32_is_maximized(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    WINDOWPLACEMENT wp;
    wp.length = sizeof(wp);
    if (!GetWindowPlacement(window->hwnd, &wp)) return false;
    return wp.showCmd == SW_SHOWMAXIMIZED;
}

extern "C" bool composekn_win32_is_minimized(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    WINDOWPLACEMENT wp;
    wp.length = sizeof(wp);
    if (!GetWindowPlacement(window->hwnd, &wp)) return false;
    return wp.showCmd == SW_SHOWMINIMIZED;
}

extern "C" void composekn_win32_request_close(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    PostMessageW(window->hwnd, WM_CLOSE, 0, 0);
}

extern "C" void composekn_win32_set_title(ComposeKNWin32Window* window, const char* title) {
    if (window == nullptr || window->hwnd == nullptr || title == nullptr) return;
    int wlen = MultiByteToWideChar(CP_UTF8, 0, title, -1, nullptr, 0);
    std::vector<wchar_t> wtitle(static_cast<size_t>(wlen > 0 ? wlen : 1), L'\0');
    if (wlen > 0) {
        MultiByteToWideChar(CP_UTF8, 0, title, -1, wtitle.data(), wlen);
    }
    SetWindowTextW(window->hwnd, wtitle.data());
}

extern "C" void composekn_win32_clipboard_get_text(
    ComposeKNWin32Window* window,
    char* buffer,
    size_t buffer_size,
    bool* ok
) {
    (void)window;
    if (ok) *ok = false;
    if (buffer == nullptr || buffer_size == 0) return;
    buffer[0] = '\0';
    if (!OpenClipboard(nullptr)) return;
    HANDLE handle = GetClipboardData(CF_UNICODETEXT);
    if (handle != nullptr) {
        wchar_t* wide = static_cast<wchar_t*>(GlobalLock(handle));
        if (wide != nullptr) {
            SIZE_T bytes = GlobalSize(handle);
            int len = WideCharToMultiByte(CP_UTF8, 0, wide, -1, buffer, static_cast<int>(buffer_size), nullptr, nullptr);
            if (len == 0 && buffer_size > 0) buffer[buffer_size - 1] = '\0';
            (void)bytes;
            GlobalUnlock(handle);
            if (ok) *ok = true;
        }
    }
    CloseClipboard();
}

extern "C" void composekn_win32_clipboard_set_text(ComposeKNWin32Window* window, const char* text) {
    (void)window;
    if (text == nullptr) return;
    int wlen = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
    if (wlen <= 0) return;
    HGLOBAL global = GlobalAlloc(GMEM_MOVEABLE, static_cast<SIZE_T>(wlen) * sizeof(wchar_t));
    if (global == nullptr) return;
    wchar_t* wide = static_cast<wchar_t*>(GlobalLock(global));
    if (wide == nullptr) {
        GlobalFree(global);
        return;
    }
    MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, wlen);
    GlobalUnlock(global);
    if (!OpenClipboard(nullptr)) {
        GlobalFree(global);
        return;
    }
    EmptyClipboard();
    SetClipboardData(CF_UNICODETEXT, global); // clipboard owns `global` on success
    CloseClipboard();
}

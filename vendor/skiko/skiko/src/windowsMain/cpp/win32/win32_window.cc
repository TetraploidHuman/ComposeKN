/**
 * Minimal Win32 window + GDI software presentation bridge for Kotlin/Native
 * Windows (mingwX64) targets of skiko. Mirrors the design of the Wayland
 * bridge: C owns the window and input plumbing, Kotlin owns rendering.
 */
#include "win32_bridge.h"
#include <windows.h>
#include <windowsx.h>
#include <vector>
#include <cstdio>
#include <cstring>
#include <cstdlib>

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

static LRESULT CALLBACK composeknWndProc(HWND hwnd, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* window = reinterpret_cast<ComposeKNWin32Window*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    if (window == nullptr && message != WM_NCCREATE) {
        return DefWindowProcW(hwnd, message, wParam, lParam);
    }
    switch (message) {
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
            // Borderless: remove the system frame + title bar (we draw our
            // own chrome), keep resize behavior via WM_NCHITTEST below.
            if (wParam != 0) {
                if (IsZoomed(hwnd)) {
                    NCCALCSIZE_PARAMS* nc = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
                    nc->rgrc[0].top += 8;
                }
                return 0;
            }
            break;
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
        case WM_PAINT: {
            PAINTSTRUCT ps;
            BeginPaint(hwnd, &ps);
            EndPaint(hwnd, &ps);
            return 0;
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
        delete window;
        return nullptr;
    }
    window->dpi = queryWindowDpi(window->hwnd);
    ShowWindow(window->hwnd, SW_SHOWNORMAL);
    UpdateWindow(window->hwnd);
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
    HDC dc = GetDC(window->hwnd);
    if (dc == nullptr) return;
    BITMAPINFO bmi;
    ZeroMemory(&bmi, sizeof(bmi));
    bmi.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    bmi.bmiHeader.biWidth = width;
    bmi.bmiHeader.biHeight = -height; // top-down
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB;
    SetStretchBltMode(dc, COLORONCOLOR);
    StretchDIBits(
        dc,
        0, 0, width, height,
        0, 0, width, height,
        pixels,
        &bmi,
        DIB_RGB_COLORS,
        SRCCOPY
    );
    ReleaseDC(window->hwnd, dc);
}

extern "C" int composekn_win32_width(ComposeKNWin32Window* window) {
    return window ? window->width : 0;
}

extern "C" int composekn_win32_height(ComposeKNWin32Window* window) {
    return window ? window->height : 0;
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

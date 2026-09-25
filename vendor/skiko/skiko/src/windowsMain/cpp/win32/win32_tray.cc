/**
 * ComposeKN Windows 系统托盘（Shell_NotifyIconW）。
 *
 * 消息专用 HWND（HWND_MESSAGE）收 WM_TRAYICON / WM_COMMAND；右键弹出 TrackPopupMenu。
 * 默认 16×16 蓝底白圆图标（Painter→HICON 后续再接）。
 */

#include "win32_bridge.h"

#include <windows.h>
#include <shellapi.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#ifndef NIF_INFO
#define NIF_INFO 0x00000010
#endif
#ifndef NIIF_NONE
#define NIIF_NONE 0x00000000
#endif
#ifndef NIIF_INFO
#define NIIF_INFO 0x00000001
#endif
#ifndef NIIF_WARNING
#define NIIF_WARNING 0x00000002
#endif
#ifndef NIIF_ERROR
#define NIIF_ERROR 0x00000003
#endif

namespace {

constexpr UINT WM_TRAYICON = WM_APP + 42;
constexpr UINT TRAY_ID = 1;

struct TrayState {
    HWND hwnd = nullptr;
    HICON icon = nullptr;
    HMENU menu = nullptr;
    NOTIFYICONDATAW nid{};
    ComposeKNTrayCallback callback = nullptr;
    void* user = nullptr;
    bool added = false;
};

TrayState g_tray;

void trayLog(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    composekn_win32_log(buffer);
}

std::wstring utf8ToWide(const char* utf8) {
    if (utf8 == nullptr || utf8[0] == '\0') return std::wstring();
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    if (wlen <= 0) return std::wstring();
    std::wstring wide(static_cast<size_t>(wlen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wide.data(), wlen);
    if (!wide.empty() && wide.back() == L'\0') wide.pop_back();
    return wide;
}

void copyTooltip(NOTIFYICONDATAW* nid, const wchar_t* tip) {
    nid->szTip[0] = L'\0';
    if (tip == nullptr) return;
    wcsncpy_s(nid->szTip, tip, _TRUNCATE);
}

HICON createDefaultIcon() {
    // 16×16 BGRA：蓝底 + 白圆（与画廊 IconButton 色接近）。
    constexpr int kSize = 16;
    BITMAPV5HEADER bi{};
    bi.bV5Size = sizeof(BITMAPV5HEADER);
    bi.bV5Width = kSize;
    bi.bV5Height = -kSize; // top-down
    bi.bV5Planes = 1;
    bi.bV5BitCount = 32;
    bi.bV5Compression = BI_BITFIELDS;
    bi.bV5RedMask = 0x00FF0000;
    bi.bV5GreenMask = 0x0000FF00;
    bi.bV5BlueMask = 0x000000FF;
    bi.bV5AlphaMask = 0xFF000000;

    void* bits = nullptr;
    HDC screen = GetDC(nullptr);
    HBITMAP color = CreateDIBSection(
        screen, reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS, &bits, nullptr, 0);
    ReleaseDC(nullptr, screen);
    if (!color || !bits) {
        if (color) DeleteObject(color);
        return static_cast<HICON>(LoadImageW(
            nullptr, MAKEINTRESOURCEW(32512), IMAGE_ICON, 0, 0, LR_SHARED));
    }

    auto* px = static_cast<uint32_t*>(bits);
    const float cx = (kSize - 1) * 0.5f;
    const float cy = (kSize - 1) * 0.5f;
    const float rOuter = kSize * 0.42f;
    const float rInner = kSize * 0.18f;
    for (int y = 0; y < kSize; ++y) {
        for (int x = 0; x < kSize; ++x) {
            const float dx = x - cx;
            const float dy = y - cy;
            const float d2 = dx * dx + dy * dy;
            uint32_t c = 0;
            if (d2 <= rOuter * rOuter) {
                c = 0xFF1B6AC9u; // blue
                if (d2 <= rInner * rInner) c = 0xFFFFFFFFu;
            }
            px[y * kSize + x] = c;
        }
    }

    ICONINFO ii{};
    ii.fIcon = TRUE;
    ii.hbmMask = CreateBitmap(kSize, kSize, 1, 1, nullptr);
    ii.hbmColor = color;
    HICON icon = CreateIconIndirect(&ii);
    if (ii.hbmMask) DeleteObject(ii.hbmMask);
    DeleteObject(color);
    if (!icon) {
        return static_cast<HICON>(LoadImageW(
            nullptr, MAKEINTRESOURCEW(32512), IMAGE_ICON, 0, 0, LR_SHARED));
    }
    return icon;
}

void showContextMenu(HWND hwnd) {
    if (g_tray.menu == nullptr) return;
    POINT pt{};
    GetCursorPos(&pt);
    // 文档：弹出前必须 SetForegroundWindow，否则点别处不会收起。
    SetForegroundWindow(hwnd);
    TrackPopupMenuEx(
        g_tray.menu,
        TPM_RIGHTBUTTON | TPM_BOTTOMALIGN | TPM_LEFTALIGN,
        pt.x,
        pt.y,
        hwnd,
        nullptr);
    PostMessageW(hwnd, WM_NULL, 0, 0);
}

LRESULT CALLBACK trayWndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    switch (msg) {
        case WM_TRAYICON: {
            if (wParam != TRAY_ID) break;
            switch (LOWORD(lParam)) {
                case WM_LBUTTONDBLCLK:
                    if (g_tray.callback) g_tray.callback(0, 0, g_tray.user);
                    break;
                case WM_RBUTTONUP:
                case WM_CONTEXTMENU:
                    showContextMenu(hwnd);
                    break;
                default:
                    break;
            }
            return 0;
        }
        case WM_COMMAND: {
            if (HIWORD(wParam) == 0) {
                const int id = static_cast<int>(LOWORD(wParam));
                if (g_tray.callback) g_tray.callback(1, id, g_tray.user);
            }
            return 0;
        }
        case WM_DESTROY:
            return 0;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

bool ensureWindowClass() {
    static bool registered = false;
    if (registered) return true;
    WNDCLASSEXW wc{};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = trayWndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"ComposeKNTrayHidden";
    if (!RegisterClassExW(&wc) && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        trayLog("tray: RegisterClassEx failed err=%lu", GetLastError());
        return false;
    }
    registered = true;
    return true;
}

} // namespace

extern "C" bool composekn_win32_tray_available(void) {
    return true;
}

extern "C" bool composekn_win32_tray_create(
    const char* tooltip_utf8, ComposeKNTrayCallback cb, void* user) {
    if (g_tray.added) {
        g_tray.callback = cb;
        g_tray.user = user;
        composekn_win32_tray_set_tooltip(tooltip_utf8);
        return true;
    }
    if (!ensureWindowClass()) return false;

    g_tray.hwnd = CreateWindowExW(
        0,
        L"ComposeKNTrayHidden",
        L"",
        0,
        0,
        0,
        0,
        0,
        HWND_MESSAGE,
        nullptr,
        GetModuleHandleW(nullptr),
        nullptr);
    if (!g_tray.hwnd) {
        trayLog("tray: CreateWindowEx(HWND_MESSAGE) failed err=%lu", GetLastError());
        return false;
    }

    g_tray.icon = createDefaultIcon();
    g_tray.callback = cb;
    g_tray.user = user;

    ZeroMemory(&g_tray.nid, sizeof(g_tray.nid));
    g_tray.nid.cbSize = sizeof(NOTIFYICONDATAW);
    g_tray.nid.hWnd = g_tray.hwnd;
    g_tray.nid.uID = TRAY_ID;
    g_tray.nid.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP;
    g_tray.nid.uCallbackMessage = WM_TRAYICON;
    g_tray.nid.hIcon = g_tray.icon;
    copyTooltip(&g_tray.nid, utf8ToWide(tooltip_utf8).c_str());

    if (!Shell_NotifyIconW(NIM_ADD, &g_tray.nid)) {
        trayLog("tray: Shell_NotifyIcon(NIM_ADD) failed err=%lu", GetLastError());
        DestroyWindow(g_tray.hwnd);
        g_tray.hwnd = nullptr;
        if (g_tray.icon) {
            DestroyIcon(g_tray.icon);
            g_tray.icon = nullptr;
        }
        return false;
    }
    g_tray.added = true;
    trayLog("tray: NIM_ADD ok tooltip=\"%s\"", tooltip_utf8 ? tooltip_utf8 : "");
    return true;
}

extern "C" void composekn_win32_tray_set_tooltip(const char* tooltip_utf8) {
    if (!g_tray.added) return;
    g_tray.nid.uFlags = NIF_TIP;
    copyTooltip(&g_tray.nid, utf8ToWide(tooltip_utf8).c_str());
    Shell_NotifyIconW(NIM_MODIFY, &g_tray.nid);
}

extern "C" void composekn_win32_tray_set_menu(void* hmenu) {
    if (g_tray.menu != nullptr) {
        DestroyMenu(g_tray.menu);
        g_tray.menu = nullptr;
    }
    g_tray.menu = reinterpret_cast<HMENU>(hmenu);
}

extern "C" void composekn_win32_tray_notify(
    const char* title_utf8, const char* body_utf8, int32_t type) {
    if (!g_tray.added) {
        trayLog("tray: notify skipped (no icon)");
        return;
    }
    DWORD iconFlag = NIIF_NONE;
    switch (type) {
        case 1: iconFlag = NIIF_INFO; break;
        case 2: iconFlag = NIIF_WARNING; break;
        case 3: iconFlag = NIIF_ERROR; break;
        default: iconFlag = NIIF_NONE; break;
    }
    g_tray.nid.uFlags = NIF_INFO;
    g_tray.nid.dwInfoFlags = iconFlag;
    std::wstring title = utf8ToWide(title_utf8);
    std::wstring body = utf8ToWide(body_utf8);
    wcsncpy_s(g_tray.nid.szInfoTitle, title.c_str(), _TRUNCATE);
    wcsncpy_s(g_tray.nid.szInfo, body.c_str(), _TRUNCATE);
    if (!Shell_NotifyIconW(NIM_MODIFY, &g_tray.nid)) {
        trayLog("tray: NIM_MODIFY(NIF_INFO) failed err=%lu", GetLastError());
    } else {
        trayLog("tray: notify type=%d title=\"%s\"", type, title_utf8 ? title_utf8 : "");
    }
}

extern "C" void composekn_win32_tray_destroy(void) {
    if (g_tray.added) {
        Shell_NotifyIconW(NIM_DELETE, &g_tray.nid);
        g_tray.added = false;
        trayLog("tray: NIM_DELETE");
    }
    if (g_tray.menu != nullptr) {
        DestroyMenu(g_tray.menu);
        g_tray.menu = nullptr;
    }
    if (g_tray.hwnd != nullptr) {
        DestroyWindow(g_tray.hwnd);
        g_tray.hwnd = nullptr;
    }
    if (g_tray.icon != nullptr) {
        DestroyIcon(g_tray.icon);
        g_tray.icon = nullptr;
    }
    g_tray.callback = nullptr;
    g_tray.user = nullptr;
    ZeroMemory(&g_tray.nid, sizeof(g_tray.nid));
}

extern "C" bool composekn_win32_tray_set_icon(
    int32_t w, int32_t h, const uint8_t* bgra) {
    if (!g_tray.added || bgra == nullptr || w <= 0 || h <= 0 || w > 256 || h > 256) {
        return false;
    }

    BITMAPV5HEADER bi{};
    bi.bV5Size = sizeof(BITMAPV5HEADER);
    bi.bV5Width = w;
    bi.bV5Height = -h; // top-down
    bi.bV5Planes = 1;
    bi.bV5BitCount = 32;
    bi.bV5Compression = BI_BITFIELDS;
    bi.bV5RedMask = 0x00FF0000;
    bi.bV5GreenMask = 0x0000FF00;
    bi.bV5BlueMask = 0x000000FF;
    bi.bV5AlphaMask = 0xFF000000;

    void* bits = nullptr;
    HDC screen = GetDC(nullptr);
    HBITMAP color = CreateDIBSection(
        screen, reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS, &bits, nullptr, 0);
    ReleaseDC(nullptr, screen);
    if (!color || !bits) {
        if (color) DeleteObject(color);
        trayLog("tray: set_icon CreateDIBSection failed");
        return false;
    }

    const size_t nbytes = static_cast<size_t>(w) * static_cast<size_t>(h) * 4u;
    std::memcpy(bits, bgra, nbytes);

    ICONINFO ii{};
    ii.fIcon = TRUE;
    ii.hbmMask = CreateBitmap(w, h, 1, 1, nullptr);
    ii.hbmColor = color;
    HICON icon = CreateIconIndirect(&ii);
    if (ii.hbmMask) DeleteObject(ii.hbmMask);
    DeleteObject(color);
    if (!icon) {
        trayLog("tray: set_icon CreateIconIndirect failed");
        return false;
    }

    HICON old = g_tray.icon;
    g_tray.icon = icon;
    g_tray.nid.uFlags = NIF_ICON;
    g_tray.nid.hIcon = icon;
    if (!Shell_NotifyIconW(NIM_MODIFY, &g_tray.nid)) {
        trayLog("tray: set_icon NIM_MODIFY failed err=%lu", GetLastError());
        // 回滚
        g_tray.icon = old;
        g_tray.nid.hIcon = old;
        DestroyIcon(icon);
        return false;
    }
    if (old != nullptr) DestroyIcon(old);
    trayLog("tray: set_icon %dx%d ok", w, h);
    return true;
}

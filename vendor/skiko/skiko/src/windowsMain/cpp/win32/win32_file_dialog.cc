/**
 * ComposeKN Windows 文件对话框（comdlg32：GetOpenFileNameW / GetSaveFileNameW）。
 *
 * 故意走经典 Common Dialog，而不是 IFileDialog：Wine 对 comdlg32 支持更稳。
 * 必须在 UI 线程调用（与其它 win32 桥一致）。
 *
 * filterUtf8：用 '\n' 分隔的 COMDLG 过滤器字段（C 侧再转成双 NUL 宽串），例如：
 *   "Images\n*.png;*.jpg\nAll\n*.*\n"
 * 末尾多一个 '\n' → 最终 `\0\0`。空串 / nullptr = 不设过滤器。
 *
 * 两段式缓冲：对话框只弹一次，结果先挂起；缓冲不够只回需要的字节数，
 * 下一次带着够大的 buffer 来取（不再弹窗）。
 */

#include "win32_bridge.h"

#include <windows.h>
#include <commdlg.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

void fileDialogLog(const char* fmt, ...) {
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

std::string wideToUtf8(const wchar_t* wide, int wideLen = -1) {
    if (wide == nullptr) return std::string();
    const int len = WideCharToMultiByte(
        CP_UTF8, 0, wide, wideLen, nullptr, 0, nullptr, nullptr);
    if (len <= 0) return std::string();
    std::string out(static_cast<size_t>(len), '\0');
    WideCharToMultiByte(
        CP_UTF8, 0, wide, wideLen, out.data(), len, nullptr, nullptr);
    if (wideLen < 0 && !out.empty() && out.back() == '\0') out.pop_back();
    return out;
}

/**
 * '\n' 分隔 UTF-8 → COMDLG 双 NUL 宽串（末尾保证多一个 NUL）。
 * 输入 "A\n*.txt\n\n" → L"A\0*.txt\0\0"。
 */
std::wstring buildFilterWide(const char* filterUtf8) {
    if (filterUtf8 == nullptr || filterUtf8[0] == '\0') return std::wstring();
    std::wstring out;
    const char* p = filterUtf8;
    while (*p) {
        const char* start = p;
        while (*p && *p != '\n') ++p;
        std::string piece(start, p);
        std::wstring w = utf8ToWide(piece.c_str());
        out.append(w);
        out.push_back(L'\0');
        if (*p == '\n') ++p;
    }
    // COMDLG 要求以双 NUL 结束；若最后一段后已有一个 NUL，再补一个。
    if (out.empty() || out.back() != L'\0') out.push_back(L'\0');
    out.push_back(L'\0');
    return out;
}

/** 多选：dir\0file1\0file2\0\0 → 每条完整路径，用 '\n' 拼 UTF-8。 */
std::string parseMultiSelect(const wchar_t* buf) {
    if (buf == nullptr || buf[0] == L'\0') return std::string();
    const wchar_t* p = buf;
    std::wstring dir(p);
    p += dir.size() + 1;
    if (*p == L'\0') {
        // 只选了一个文件：整段就是完整路径
        return wideToUtf8(dir.c_str());
    }
    std::string result;
    const bool dirNeedsSep = !dir.empty() && dir.back() != L'\\' && dir.back() != L'/';
    while (*p) {
        std::wstring name(p);
        p += name.size() + 1;
        std::wstring full = dir;
        if (dirNeedsSep) full.push_back(L'\\');
        full.append(name);
        if (!result.empty()) result.push_back('\n');
        result.append(wideToUtf8(full.c_str()));
    }
    return result;
}

int32_t writeResult(const std::string& utf8, char* buffer, int32_t bufferSize) {
    const int32_t needed = static_cast<int32_t>(utf8.size());
    if (needed == 0) return 0;
    if (buffer == nullptr || bufferSize < needed) return needed;
    memcpy(buffer, utf8.data(), static_cast<size_t>(needed));
    return needed;
}

/** 缓冲不足时挂起的结果（避免为两段式读缓冲弹两次对话框）。 */
std::string g_pendingResult;
bool g_pendingValid = false;

}  // namespace

extern "C" bool composekn_win32_file_dialog_available(void) {
    // 链接了 -lcomdlg32；导出符号存在即视为可用（CI 用它跳过交互弹窗）。
    return true;
}

extern "C" int32_t composekn_win32_file_dialog(
    ComposeKNWin32Window* owner,
    int32_t mode,
    const char* title,
    const char* initialDir,
    const char* initialName,
    bool allowMultiple,
    const char* filterUtf8,
    char* buffer,
    int32_t bufferSize) {
    // 取走上一次因缓冲不足而挂起的结果（不再弹窗）。
    if (g_pendingValid) {
        const int32_t r = writeResult(g_pendingResult, buffer, bufferSize);
        if (r > 0 && buffer != nullptr && bufferSize >= r) {
            g_pendingValid = false;
            g_pendingResult.clear();
        } else if (r == 0) {
            g_pendingValid = false;
            g_pendingResult.clear();
        }
        return r;
    }

    HWND hwnd = owner != nullptr
        ? static_cast<HWND>(composekn_win32_hwnd(owner))
        : nullptr;

    std::wstring titleW = utf8ToWide(title);
    std::wstring dirW = utf8ToWide(initialDir);
    std::wstring filterW = buildFilterWide(filterUtf8);
    std::wstring nameW = utf8ToWide(initialName);

    // 多选路径列表可能很长；单选也给足余量（含初始文件名）。
    const size_t fileCap = allowMultiple ? (64u * 1024u) : 4096u;
    std::vector<wchar_t> fileBuf(fileCap, L'\0');
    if (!nameW.empty()) {
        const size_t copy = (std::min)(nameW.size(), fileCap - 1);
        wmemcpy(fileBuf.data(), nameW.data(), copy);
        fileBuf[copy] = L'\0';
    }

    OPENFILENAMEW ofn;
    ZeroMemory(&ofn, sizeof(ofn));
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = hwnd;
    if (!filterW.empty()) {
        ofn.lpstrFilter = filterW.c_str();
        ofn.nFilterIndex = 1;
    }
    ofn.lpstrFile = fileBuf.data();
    ofn.nMaxFile = static_cast<DWORD>(fileBuf.size());
    if (!dirW.empty()) ofn.lpstrInitialDir = dirW.c_str();
    if (!titleW.empty()) ofn.lpstrTitle = titleW.c_str();

    const bool isSave = mode != 0;
    if (isSave) {
        ofn.Flags = OFN_EXPLORER | OFN_HIDEREADONLY | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST;
        allowMultiple = false;  // 保存不支持多选
    } else {
        ofn.Flags = OFN_EXPLORER | OFN_HIDEREADONLY | OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST;
        if (allowMultiple) ofn.Flags |= OFN_ALLOWMULTISELECT;
    }

    const BOOL ok = isSave ? GetSaveFileNameW(&ofn) : GetOpenFileNameW(&ofn);
    if (!ok) {
        const DWORD err = CommDlgExtendedError();
        if (err == 0) {
            // 用户取消
            return 0;
        }
        fileDialogLog("file_dialog: CommDlgExtendedError=%lu mode=%d",
                      static_cast<unsigned long>(err), static_cast<int>(mode));
        return -1;
    }

    std::string result;
    if (!isSave && allowMultiple) {
        result = parseMultiSelect(fileBuf.data());
    } else {
        result = wideToUtf8(fileBuf.data());
    }
    if (result.empty()) return 0;

    const int32_t written = writeResult(result, buffer, bufferSize);
    if (written > 0 && (buffer == nullptr || bufferSize < written)) {
        g_pendingResult = std::move(result);
        g_pendingValid = true;
    }
    return written;
}

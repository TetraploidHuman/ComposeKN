/**
 * Minimal Win32 window + GDI software presentation bridge for Kotlin/Native
 * Windows (mingwX64) targets of skiko. Mirrors the design of the Wayland
 * bridge: C owns the window and input plumbing, Kotlin owns rendering.
 */
#include "win32_bridge.h"
#include <windows.h>
#include <windowsx.h>
// IME（IMM32）：我们的文本框是 Compose 自绘的，不是系统 EDIT 控件，所以组字/
// 候选窗的位置和文本都得宿主自己从 IMM32 取回来（对照 AWT 的 awt_Component.cpp）。
#include <imm.h>
// 上游的 SkLoadICU()（从 exe 同目录 mmap icudtl.dat）被 win32_icu.cc 里的同名版本
// 覆盖；这里显式引用它，保证链接器把**我们那份**从归档里拉进来。
#include "SkLoadICU.h"
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
    // 诊断：最大化时「客户区超出显示器工作区」的检出次数（真机 bug 的回归断言用）
    int32_t clientOverflowCount = 0;
    bool maximizeLogged = false;
    // 触摸（WM_POINTER）通道。COMPOSEKN_TOUCH=0 可整体关掉，退回「系统把触摸提升成鼠标」的老行为。
    bool touchEnabled = true;
    int touchLogCount = 0;
    // false = 系统标题栏（Compose JVM 桌面 Window() 的默认形态：NC 全归 OS 管）；
    // true = 无边框自绘 CSD（对应 JVM 的 undecorated = true）。
    bool undecorated = false;
    // 光标形状（0=箭头 1=手 2=文本I型 3=十字），由 Compose 的 PointerIcon 驱动。
    int cursorKind = 0;

    // ---- IME（IMM32）状态 ----
    // 与 IME 事件一一配对、FIFO 的 UTF-8 文本（事件结构体只有 int32 字段，塞不下字符串）。
    std::vector<std::string> imeTexts;
    // 正在组字（WM_IME_STARTCOMPOSITION .. WM_IME_ENDCOMPOSITION）
    bool imeComposing = false;
    // 走过的 IME 消息条数（自检/真机排查：0 说明系统根本没把 IME 消息发过来）
    int32_t imeMessageCount = 0;
    int imeSetContextLogCount = 0;
    int imeProcessKeyLogCount = 0;
    int imeRequestLogCount = 0;
    // IMR_QUERYCHARPOSITION 的日志限流（答案不变就不重复打）
    int imeCharPosLogCount = 0;
    int32_t imeCharPosLastIndex = -1;
    LONG imeCharPosLastX = 0;
    LONG imeCharPosLastY = 0;
    // 诊断限流：WM_IME_NOTIFY、ImmSetCompositionWindow/ImmSetCandidateWindow 的返回值
    int imeNotifyLogCount = 0;
    int imeSetFormLogCount = 0;
    // IMM32 重转换/文档馈送的诊断限流 + 开关（COMPOSEKN_IME_DOCUMENTFEED=0 退回旧行为）
    int imeReconvertLogCount = 0;
    // 文档馈送（IMR_DOCUMENTFEED / IMR_RECONVERTSTRING）的处理模式：
    //   0 = 完全不回答（COMPOSEKN_IME_DOCUMENTFEED=0）
    //   1 = 只在输入法给了缓冲区时回答（默认）
    //   2 = 缓冲区为 NULL 时也回 TRUE 的**探针**（看输入法会不会带缓冲区再来一次）
    // 真机实测（MS 拼音）：它发的 IMR_DOCUMENTFEED 是 **lParam = NULL** 的，
    // 所以默认模式下我们什么都不回 —— 这正是 v0.5.1 日志里"看不到它"的原因
    // （当时连"请求到了"都没记，见 §17.21）。
    int imeDocumentFeedMode = 1;
    // COMPOSEKN_IME_COMPOSITION_FONT=0 时不告诉输入法组字字体（真机 A/B 用：
    // 观察 ImmSetCompositionFont 会不会影响输入法自己的行为，比如还问不问文档馈送）
    bool imeCompositionFontEnabled = true;
    // 已经见过并处理的 IMR_* 请求位掩码（每种只记一行日志）
    DWORD imeRequestSeenMask = 0;
    // 「已经通过 GCS_RESULTSTR 提交过」的字符：如果 IME 又把它们作为 WM_CHAR
    // 送一遍（不同 IME / 不同兼容层行为不一致），必须丢掉，否则文本会插入两次。
    std::wstring pendingCommitChars;
    DWORD pendingCommitTick = 0;
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

// 系统（主显示器）DPI 缩放：建窗口**之前**用它把 dp 换算成物理像素。
// 窗口建好后会用窗口自己的 DPI 再校正一次（多显示器 / 不同缩放时以窗口所在屏为准）。
static double systemDpiScale() {
    HDC dc = GetDC(nullptr);
    if (dc == nullptr) return 1.0;
    const int dpi = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(nullptr, dc);
    return dpi > 0 ? (dpi / 96.0) : 1.0;
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

// mingw 的 IDC_* 是 MAKEINTRESOURCE()（窄字符），不能直接喂 LoadCursorW；
// 这里用系统资源 id 的数值重建宽字符版本。
static LPCWSTR composeknCursorRes(int id) {
    return reinterpret_cast<LPCWSTR>(static_cast<ULONG_PTR>(static_cast<WORD>(id)));
}

static HCURSOR composeknLoadCursor(int kind) {
    switch (kind) {
        case 1: return LoadCursorW(nullptr, composeknCursorRes(32649));  // IDC_HAND
        case 2: return LoadCursorW(nullptr, composeknCursorRes(32513));  // IDC_IBEAM
        case 3: return LoadCursorW(nullptr, composeknCursorRes(32515));  // IDC_CROSS
        default: return LoadCursorW(nullptr, composeknCursorRes(32512)); // IDC_ARROW
    }
}

// ---------------------------------------------------------------------------
// IME（IMM32）
//
// 为什么必须自己接（真机反馈：「输入法候选词会卡死，但字还是能打进去」）：
// 我们的文本框是 Compose 自绘的，系统侧没有任何 EDIT 控件，于是
//   * IMECHARPOSITION / 候选窗位置：IME 通过 WM_IME_REQUEST(IMR_QUERYCHARPOSITION)
//     问「组字字符在屏幕上的矩形」，不回答的话它只能拿 GetCaretPos() —— 我们根本
//     没有 caret，永远是 (0,0)，候选窗就会卡在错误的位置/不再跟着输入更新；
//   * 组字串与提交串：必须自己 ImmGetCompositionString() 取出来喂给 Compose 的
//     文本输入层（见 WindowsTextInputService）。
// 对照组：AWT 在 awt_Component.cpp 里正是这么做的，而 Compose 桌面的 JVM 版本
// 走的就是 AWT —— 也就是说这条路径是上游行为的一部分，不是我们发明的。
// ---------------------------------------------------------------------------

// C++ -> Kotlin：问「文本框里某个字符在客户区（物理像素）的位置」。
//
// charIndex 的语义（对齐 IMECHARPOSITION.dwCharPos）：
//   * >= 0：组字串里第 charIndex 个字符的矩形。IME 用 dwCharPos 指定它想问哪个字符，
//           而**候选窗通常问第 0 个**（组字串开头）—— 所以候选窗应当**钉在开始组字
//           的位置**，不跟着拼音越打越长往右跑（原生 Windows 应用就是这么表现的；
//           我们以前一律回光标矩形，于是候选窗一路向右滑）。
//   * < 0 ：没有具体字符（不在组字中）——回当前光标矩形。
typedef void (*ComposeKNImeCaretFn)(
    void* user, int32_t charIndex, int32_t* x, int32_t* y, int32_t* w, int32_t* h);
static ComposeKNImeCaretFn g_imeCaretProvider = nullptr;
static void* g_imeCaretProviderUser = nullptr;

extern "C" void composekn_win32_set_ime_caret_provider(ComposeKNImeCaretFn fn, void* user) {
    g_imeCaretProvider = fn;
    g_imeCaretProviderUser = user;
}

// C++ -> Kotlin：取「文本框里的文档」（IMM32 的文档馈送/重转换用）。
//
// 为什么需要：`IMR_DOCUMENTFEED` / `IMR_RECONVERTSTRING` 要求应用把**文档内容**和
// 选区/组字范围交给输入法 —— 输入法拿它做上下文候选排序，以及「重新转换」。
// 文档只有 Kotlin 侧知道（Compose 的 TextFieldValue），所以走同步回调（和光标矩形
// 那条一样，在 WM_IME_REQUEST 的 SendMessage 里被调用）。
//
// [from] 起始字符偏移；[buffer] UTF-16 缓冲区；返回写入的字符数
// （capacity <= 0 时只问文档总长度，不拷贝）。selection/composition 是 UTF-16
// code unit 偏移，-1 表示不存在。
typedef int32_t (*ComposeKNImeTextFn)(
    void* user, int32_t from, uint16_t* buffer, int32_t capacity,
    int32_t* selectionStart, int32_t* selectionEnd,
    int32_t* compositionStart, int32_t* compositionEnd);

static ComposeKNImeTextFn g_imeTextProvider = nullptr;
static void* g_imeTextProviderUser = nullptr;

extern "C" void composekn_win32_set_ime_text_provider(ComposeKNImeTextFn fn, void* user) {
    g_imeTextProvider = fn;
    g_imeTextProviderUser = user;
}

// C++ -> Kotlin：「重新转换」时把输入法发来的 (字符串, 它在字符串里的目标范围) 映射回
// **文档偏移**。只有映射成功（Kotlin 在文档里找到了那段文本）我们才会答应输入法的确认，
// 然后把文档里对应的范围变成选区 —— 接下来那段组字就会替换掉原文本。
//
// 返回 1 = 映射成功（写回 [outStart, outEnd)），0 = 映射不了（我们会拒绝这次重转换，
// 绝不动文本 —— 宁可"重新转换不生效"，也不要弄出重复文本）。
typedef int32_t (*ComposeKNImeReconvertFn)(
    void* user, const uint16_t* text, int32_t textLen,
    int32_t targetOffsetInText, int32_t targetLen,
    int32_t* outStart, int32_t* outEnd);

static ComposeKNImeReconvertFn g_imeReconvertProvider = nullptr;
static void* g_imeReconvertProviderUser = nullptr;

extern "C" void composekn_win32_set_ime_reconvert_provider(ComposeKNImeReconvertFn fn, void* user) {
    g_imeReconvertProvider = fn;
    g_imeReconvertProviderUser = user;
}

static std::string wideToUtf8(const std::wstring& text) {
    if (text.empty()) return std::string();
    const int bytes = WideCharToMultiByte(CP_UTF8, 0, text.data(),
                                          static_cast<int>(text.size()),
                                          nullptr, 0, nullptr, nullptr);
    if (bytes <= 0) return std::string();
    std::string out(static_cast<size_t>(bytes), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()),
                        out.data(), bytes, nullptr, nullptr);
    return out;
}

/** 把 IME 事件和它配对的文本一起入队（两者顺序严格一致）。 */
static void pushImeEvent(ComposeKNWin32Window* window, int32_t type, const std::wstring& text) {
    ComposeKNWin32Event e{};
    e.type = type;
    e.a = static_cast<int32_t>(text.size());  // UTF-16 长度（诊断用）
    e.state = window->imeComposing ? 1u : 0u;
    pushEvent(window, e);
    window->imeTexts.push_back(wideToUtf8(text));
}

/** ImmGetCompositionStringW 的包装（返回 UTF-16 字符串）。 */
static std::wstring imeCompositionString(HIMC himc, DWORD index) {
    const LONG bytes = ImmGetCompositionStringW(himc, index, nullptr, 0);
    if (bytes <= 0) return std::wstring();
    std::vector<wchar_t> buffer(static_cast<size_t>(bytes) / sizeof(wchar_t) + 1, L'\0');
    const LONG copied = ImmGetCompositionStringW(himc, index, buffer.data(), bytes);
    if (copied <= 0) return std::wstring();
    return std::wstring(buffer.data(), static_cast<size_t>(copied) / sizeof(wchar_t));
}

/**
 * Compose 侧「字符矩形」（客户区物理像素）；[charIndex] < 0 表示要当前光标矩形。
 * 拿不到时返回 0 尺寸。
 */
static void imeCaretRect(
    ComposeKNWin32Window* window, int32_t charIndex,
    int32_t* x, int32_t* y, int32_t* w, int32_t* h) {
    *x = 0; *y = 0; *w = 0; *h = 0;
    if (g_imeCaretProvider != nullptr) {
        g_imeCaretProvider(g_imeCaretProviderUser, charIndex, x, y, w, h);
    }
    (void)window;
}

// 文档馈送时前后各带多少个字符的上下文（不申请整篇文档那么大的缓冲区）。
static const int32_t kImeDocContextChars = 128;

/**
 * 填一个 `RECONVERTSTRING`（IMR_DOCUMENTFEED / IMR_RECONVERTSTRING 的答复）。
 *
 * 交给输入法的是：**文档的一段窗口**（以组字为中心、前后各 kImeDocContextChars 个
 * 字符）+ 组字/目标范围。没有组字时用当前选区当目标范围。
 *
 * 缓冲区不够时按 IMM32 的两段式约定处理：把 `dwSize` 改成需要的大小并返回 true，
 * 让输入法带够缓冲区再问一次（写 dwSize 一定落在它给的 RECONVERTSTRING 里，安全）。
 *
 * 返回 true = 已处理（消息应当回 TRUE）。
 */
static bool fillReconvertString(ComposeKNWin32Window* window, RECONVERTSTRING* rec) {
    (void)window;
    if (rec == nullptr || g_imeTextProvider == nullptr) return false;
    int32_t selStart = -1, selEnd = -1, compStart = -1, compEnd = -1;
    const int32_t docLen = g_imeTextProvider(
        g_imeTextProviderUser, 0, nullptr, 0, &selStart, &selEnd, &compStart, &compEnd);
    if (docLen <= 0) return false;
    // 目标范围：优先组字，其次选区
    int32_t targetStart = compStart >= 0 ? compStart : (selStart >= 0 ? selStart : 0);
    int32_t targetEnd = compStart >= 0 ? compEnd : (selEnd >= 0 ? selEnd : targetStart);
    if (targetStart < 0) targetStart = 0;
    if (targetStart > docLen) targetStart = docLen;
    if (targetEnd < targetStart) targetEnd = targetStart;
    if (targetEnd > docLen) targetEnd = docLen;

    int32_t from = targetStart - kImeDocContextChars;
    if (from < 0) from = 0;
    int32_t to = targetEnd + kImeDocContextChars;
    if (to > docLen) to = docLen;
    if (to <= from) return false;

    const int32_t windowChars = to - from;
    const DWORD needed = static_cast<DWORD>(sizeof(RECONVERTSTRING)) +
                         static_cast<DWORD>(windowChars + 1) * static_cast<DWORD>(sizeof(WCHAR));
    if (rec->dwSize < needed) {
        rec->dwSize = needed;   // 两段式：告诉输入法要多大
        return true;
    }

    std::vector<uint16_t> buffer(static_cast<size_t>(windowChars) + 1, 0);
    const int32_t copied = g_imeTextProvider(
        g_imeTextProviderUser, from, buffer.data(), windowChars,
        &selStart, &selEnd, &compStart, &compEnd);
    if (copied <= 0) return false;
    // 第二次调用返回的范围才是与这段文本一致的（同线程、两次调用之间状态不会变，
    // 这里仍然夹一遍，防止文档刚好在两次调用之间被改）。
    int32_t cs = compStart >= 0 ? compStart - from : -1;
    int32_t ce = compStart >= 0 ? compEnd - from : -1;
    if (cs < 0 || ce < cs || ce > copied) { cs = targetStart - from; ce = targetEnd - from; }
    if (cs < 0) cs = 0;
    if (cs > copied) cs = copied;
    if (ce < cs) ce = cs;
    if (ce > copied) ce = copied;

    rec->dwVersion = 0;
    rec->dwStrLen = static_cast<DWORD>(copied);
    rec->dwStrOffset = static_cast<DWORD>(sizeof(RECONVERTSTRING));
    rec->dwCompStrLen = static_cast<DWORD>(ce - cs);
    rec->dwCompStrOffset = static_cast<DWORD>(cs) * static_cast<DWORD>(sizeof(WCHAR));
    rec->dwTargetStrLen = static_cast<DWORD>(ce - cs);
    rec->dwTargetStrOffset = static_cast<DWORD>(cs) * static_cast<DWORD>(sizeof(WCHAR));
    std::memcpy(reinterpret_cast<unsigned char*>(rec) + sizeof(RECONVERTSTRING),
                buffer.data(), static_cast<size_t>(copied) * sizeof(WCHAR));
    return true;
}

/**
 * 填一个 `LOGFONT`：把「我们实际用的字体 + 行高」告诉输入法
 * （`ImmSetCompositionFontW` + IMR_COMPOSITIONFONT 的答复）。
 *
 * 组字文本是 Compose 自己画的，所以这里主要是让输入法内部排版/度量跟我们一致：
 * 字体取系统 UI 字体（`SPI_GETNONCLIENTMETRICS`，中文系统上通常是微软雅黑 UI），
 * 行高取 Compose 排版给的真实值（caret provider 的第 4 个出参）。
 */
static bool fillImeCompositionFont(ComposeKNWin32Window* window, LOGFONTW* lf) {
    if (lf == nullptr) return false;
    ZeroMemory(lf, sizeof(*lf));
    NONCLIENTMETRICSW metrics;
    ZeroMemory(&metrics, sizeof(metrics));
    metrics.cbSize = sizeof(metrics);
    if (SystemParametersInfoW(SPI_GETNONCLIENTMETRICS, sizeof(metrics), &metrics, 0)) {
        *lf = metrics.lfMessageFont;
    } else {
        lf->lfPitchAndFamily = DEFAULT_PITCH | FF_DONTCARE;
    }
    lf->lfCharSet = DEFAULT_CHARSET;
    int32_t x = 0, y = 0, w = 0, h = 0;
    imeCaretRect(window, -1, &x, &y, &w, &h);
    if (h > 0) lf->lfHeight = -h;   // LOGFONT 的字符高度用负值表示"字符高度"
    return true;
}

/**
 * 「IME 问组字串里第几个字符」时，该按哪个索引回答。
 *
 * 真机两轮反馈（微软拼音 / Win11，200% 缩放）逼出来的结论：
 *   1. 如实按 dwCharPos 回答（0,1,2,…）-> 候选窗随拼音一路右移；
 *   2. 只把 CFS_POINT / CFS_CANDIDATEPOS 设成组字起点 -> 候选窗**照样**右移
 *      （说明这个输入法的候选窗位置来自 TSF 兼容层的 GetTextExt，也就是我们的
 *      IMR_QUERYCHARPOSITION 答案，而不是 ImmSetCandidateWindow 里设的值）；
 *   3. 于是只有组字期间把**整串组字文本的范围塌缩成一个点**（一律回答第 0 个字符
 *      = 组字起点），候选窗才会钉在开始组字的位置 —— 也就是用户在原生应用里
 *      看到的表现。
 * 组字结束后立刻恢复如实回答：别的用途（状态窗、提交后的光标）仍然要真实矩形。
 *
 * 代价：组字期间输入法以为这串组字文本宽度为 0。组字文本由 Compose 自己内联画
 * （IME 的组字窗已被 ISC_SHOWUICOMPOSITIONWINDOW 关掉），所以这个「谎」只影响
 * 输入法自己那些窗口的位置（候选窗、状态窗）。
 */
static int32_t imeAnswerCharIndex(const ComposeKNWin32Window* window, int32_t dwCharPos) {
    return (window != nullptr && window->imeComposing) ? 0 : dwCharPos;
}

// ---------------------------------------------------------------------------
// 诊断：把「输入法自己画的窗口」（候选窗/状态窗）的位置变化记下来。
//
// 为什么要这个：真机上候选窗右移查了两轮都没修掉，而只看 IMR_QUERYCHARPOSITION
// 的答案，分不清是「输入法压根没用我们的答案」还是「它用了另一个窗口自己摆」。
// 这里在组字期间枚举系统里**小的、可见的、不属于本进程**的顶层窗口，只记
// **上一次扫描里已经存在、这一次 rect 变了**的那些（新出现/刚消失的一律不报：
// 真机实测，无关程序弹个 26x26 的小窗就会被误报成「候选窗位置变化」，噪音比信号
// 还多）。限时 120ms/次、限 40 行，真机复现一次就能对上号。
// ---------------------------------------------------------------------------
struct ImeUiScanEntry {
    HWND hwnd;
    RECT rect;
};

static std::vector<ImeUiScanEntry>* g_imeUiScanNext = nullptr;

static BOOL CALLBACK imeUiScanProc(HWND hwnd, LPARAM) {
    if (g_imeUiScanNext == nullptr) return TRUE;
    if (!IsWindowVisible(hwnd) || IsIconic(hwnd)) return TRUE;
    DWORD pid = 0;
    GetWindowThreadProcessId(hwnd, &pid);
    if (pid == GetCurrentProcessId()) return TRUE;  // 自己的窗口（含标题栏）不算
    RECT rc;
    if (!GetWindowRect(hwnd, &rc)) return TRUE;
    const LONG w = rc.right - rc.left;
    const LONG h = rc.bottom - rc.top;
    if (w <= 0 || h <= 0) return TRUE;
    // 候选窗/状态窗都是小窗口；大窗口（浏览器、编辑器…）的动静和 IME 无关。
    if (w > 1200 || h > 900) return TRUE;
    g_imeUiScanNext->push_back(ImeUiScanEntry{hwnd, rc});
    return TRUE;
}

static void logImeUiWindowMoves() {
    static std::vector<ImeUiScanEntry> previous;
    static DWORD lastTick = 0;
    static int logCount = 0;
    if (logCount >= 40) return;
    const DWORD now = GetTickCount();
    if (lastTick != 0 && now - lastTick < 120) return;
    lastTick = now;

    std::vector<ImeUiScanEntry> current;
    g_imeUiScanNext = &current;
    EnumWindows(imeUiScanProc, 0);
    g_imeUiScanNext = nullptr;

    std::string moved;
    for (const ImeUiScanEntry& e : current) {
        const ImeUiScanEntry* old = nullptr;
        for (const ImeUiScanEntry& p : previous) {
            if (p.hwnd == e.hwnd) { old = &p; break; }
        }
        if (old == nullptr) continue;  // 新出现的窗口不报（无关程序弹个小窗就会误报）
        if (old->rect.left == e.rect.left && old->rect.top == e.rect.top &&
            old->rect.right == e.rect.right && old->rect.bottom == e.rect.bottom) {
            continue;
        }
        wchar_t cls[128] = {0};
        GetClassNameW(e.hwnd, cls, 127);
        char item[192];
        snprintf(item, sizeof(item), "%s[%ld,%ld %ldx%ld] ",
                 wideToUtf8(cls).c_str(),
                 (long)e.rect.left, (long)e.rect.top,
                 (long)(e.rect.right - e.rect.left),
                 (long)(e.rect.bottom - e.rect.top));
        if (moved.size() + strlen(item) > 420) { moved += "…"; break; }
        moved += item;
    }
    previous.swap(current);
    if (!moved.empty()) {
        ++logCount;
        composeknLog("ime: 组字期间系统小窗口位置变化 %s", moved.c_str());
    }
}

/**
 * 把 IME 的组字窗/候选窗摆到光标下方。
 *
 * 每次组字变化都要重新摆一次（光标可能随输入在文本框里移动），否则候选窗会
 * 停在第一次的位置 —— 真机用户看到的就是「候选词卡住了」。
 */
static void positionImeWindows(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    // 诊断：输入法自己那些窗口的位置变化（只在组字期间记）
    if (window->imeComposing) logImeUiWindowMoves();
    HIMC himc = ImmGetContext(window->hwnd);
    if (himc == nullptr) return;
    // ★ 锚点用组字起点（见 imeAnswerCharIndex 的注释）：
    //   组字中 -> 组字串第 0 个字符的位置；不在组字中 -> 当前光标。
    const int32_t anchorIndex = imeAnswerCharIndex(window, -1);
    int32_t ax = 0, ay = 0, aw = 0, ah = 0;
    imeCaretRect(window, anchorIndex, &ax, &ay, &aw, &ah);
    POINT anchorPoint = { ax, ay + ah };
    if (!ClientToScreen(window->hwnd, &anchorPoint)) {
        ImmReleaseContext(window->hwnd, himc);
        return;
    }
    COMPOSITIONFORM composition;
    ZeroMemory(&composition, sizeof(composition));
    composition.dwStyle = CFS_POINT;
    composition.ptCurrentPos = anchorPoint;
    const BOOL compositionOk = ImmSetCompositionWindow(himc, &composition);
    const DWORD compositionErr = compositionOk ? 0u : GetLastError();

    // 组字字体：让输入法内部排版用和我们一致的行高（它自己按系统字体推）。
    // 失败不算错（Wine 的 imm32 是 stub；真机上拿不到字体会照样工作）。
    LOGFONTW logFont;
    BOOL fontOk = FALSE;
    if (window->imeCompositionFontEnabled && fillImeCompositionFont(window, &logFont)) {
        fontOk = ImmSetCompositionFontW(himc, &logFont);
    }

    // 候选窗位置：**候选列表里每一项都要设一次** —— `CANDIDATEFORM.dwIndex` 是候选
    // 列表下标，超出范围 `ImmSetCandidateWindow` 会直接失败（老写法只设 dwIndex=0）。
    // 拿不到列表数量时（列表为空、或 Wine 没实现）退回"只设第 0 项"。
    DWORD candidateCount = 0;
    if (ImmGetCandidateListCountW(himc, &candidateCount) == 0 || candidateCount == 0) {
        candidateCount = 1;
    }
    if (candidateCount > 16) candidateCount = 16;   // 保险：别为超长列表打一圈
    BOOL candidateOk = TRUE;
    DWORD candidateErr = 0;
    for (DWORD i = 0; i < candidateCount; ++i) {
        CANDIDATEFORM candidate;
        ZeroMemory(&candidate, sizeof(candidate));
        candidate.dwIndex = i;
        candidate.dwStyle = CFS_CANDIDATEPOS;
        candidate.ptCurrentPos = anchorPoint;
        if (!ImmSetCandidateWindow(himc, &candidate)) {
            candidateOk = FALSE;
            candidateErr = GetLastError();
        }
    }
    ImmReleaseContext(window->hwnd, himc);
    // 诊断：这些调用到底有没有成功（以前完全不看返回值 —— 失败的话我们就一直
    // 以为候选窗被我们摆好了）。失败必记，成功只记前几条。
    if ((!compositionOk || !candidateOk) || window->imeSetFormLogCount < 3) {
        if (window->imeSetFormLogCount < 12) {
            ++window->imeSetFormLogCount;
            composeknLog(
                "ime: ImmSet{Composition,Candidate}Window 锚点=%ld,%ld composing=%d "
                "-> comp=%d(err=%lu) cand=%d/%lu项(err=%lu) font=%d",
                (long)anchorPoint.x, (long)anchorPoint.y, window->imeComposing ? 1 : 0,
                static_cast<int>(compositionOk), (unsigned long)compositionErr,
                static_cast<int>(candidateOk), (unsigned long)candidateCount,
                (unsigned long)candidateErr, static_cast<int>(fontOk));
        }
    }
}

/**
 * 记一行「这个 IMR_* 请求第一次出现」（每种只记一次）。
 *
 * 为什么需要：真机上输入法到底问哪些请求，直接决定我们该实现什么 —— 之前只有
 * "未处理"分支会记日志，处理了的分支反而看不见。比如 v0.5.1 真机日志里
 * `IMR_DOCUMENTFEED` 一次都没出现，得先确认是"输入法不问"还是"我们实现后它不问了"。
 */
static void logImeRequestOnce(ComposeKNWin32Window* window, WPARAM what, const char* name) {
    if (window == nullptr || what > 31) return;
    const DWORD bit = 1u << static_cast<DWORD>(what);
    if ((window->imeRequestSeenMask & bit) != 0) return;
    window->imeRequestSeenMask |= bit;
    composeknLog("ime: WM_IME_REQUEST what=%lu(%s) 首次出现",
                 (unsigned long)what, name);
}

/** 记下刚提交的字符串（如果 IME 之后又把它当 WM_CHAR 送一遍就丢掉）。 */
static void rememberCommittedChars(ComposeKNWin32Window* window, const std::wstring& text) {
    if (text.empty()) return;
    window->pendingCommitChars.append(text);
    window->pendingCommitTick = GetTickCount();
}

/** 这个 WM_CHAR 是不是「提交串的重复」？是的话消耗掉并返回 true。 */
static bool consumeDuplicateCommittedChar(ComposeKNWin32Window* window, wchar_t ch) {
    if (window->pendingCommitChars.empty()) return false;
    if (GetTickCount() - window->pendingCommitTick > 1000) {
        window->pendingCommitChars.clear();
        return false;
    }
    if (window->pendingCommitChars.front() != ch) {
        // 对不上（用户已经接着输入普通字符了）：不再去重，按正常输入处理。
        window->pendingCommitChars.clear();
        return false;
    }
    window->pendingCommitChars.erase(window->pendingCommitChars.begin());
    return true;
}

// ---------------------------------------------------------------------------
// 触摸/笔 API（Win8+）。动态解析而不是静态链接：这些符号不在所有 mingw-w64
// 导入库里，运行时取能少一个构建期依赖；取不到就退化成「触摸当鼠标」的老行为。
// ---------------------------------------------------------------------------
typedef BOOL (WINAPI *GetPointerInfoFn)(UINT32, POINTER_INFO*);
typedef BOOL (WINAPI *GetPointerTypeFn)(UINT32, POINTER_INPUT_TYPE*);

static GetPointerInfoFn g_getPointerInfo = nullptr;
static GetPointerTypeFn g_getPointerType = nullptr;

static void resolvePointerApis() {
    static bool done = false;
    if (done) return;
    done = true;
    HMODULE user32 = GetModuleHandleW(L"user32.dll");
    if (user32 == nullptr) return;
    g_getPointerInfo = reinterpret_cast<GetPointerInfoFn>(
        reinterpret_cast<void*>(GetProcAddress(user32, "GetPointerInfo")));
    g_getPointerType = reinterpret_cast<GetPointerTypeFn>(
        reinterpret_cast<void*>(GetProcAddress(user32, "GetPointerType")));
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
            if (window->undecorated && window->hwnd == hwnd) {
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
        case WM_DPICHANGED: {
            // 跨显示器（缩放不同）时 Windows 会发这条：我们必须
            //   1) 更新自己的 dpi —— 否则 dp/密度一直是旧屏的（真机日志：拖到另一块屏后
            //      PERF-BANNER 仍然 dpi=2.0，UI 按错误缩放画）；
            //   2) 应用 lParam 给出的**建议矩形** —— 它保证「逻辑尺寸不变、物理尺寸随新
            //      缩放重算」。不照做的话窗口会保持旧物理尺寸，在分辨率更低/缩放更小的屏上
            //      就显得几乎占满全屏（用户反馈的现象）。
            const int newDpi = HIWORD(wParam);
            if (newDpi > 0) window->dpi = newDpi;
            auto* suggested = reinterpret_cast<RECT*>(lParam);
            if (suggested != nullptr) {
                composeknLog(
                    "win32: WM_DPICHANGED -> dpi=%d rect=%d,%d %dx%d",
                    newDpi,
                    static_cast<int>(suggested->left), static_cast<int>(suggested->top),
                    static_cast<int>(suggested->right - suggested->left),
                    static_cast<int>(suggested->bottom - suggested->top));
                SetWindowPos(hwnd, nullptr,
                             suggested->left, suggested->top,
                             suggested->right - suggested->left,
                             suggested->bottom - suggested->top,
                             SWP_NOZORDER | SWP_NOACTIVATE);
            }
            break;
        }
        case WM_GETMINMAXINFO: {
            // 最大化尺寸严格取显示器工作区（客户区夹取的兜底见 WM_NCCALCSIZE）。
            // 老代码在 WM_NCHITTEST 里写了「Overshoot guard: ... handled by WM_GETMINMAXINFO」，
            // 但这个分支当时根本没实现 —— 于是最大化时窗口可以被扩大到屏幕外。
            // 系统标题栏模式下这些全是 OS 的事，别插手（插手反而会算错客户区）
            if (!window->undecorated) break;
            auto* mmi = reinterpret_cast<MINMAXINFO*>(lParam);
            const int minDim = edgeMargin(window) * 2 + 1;
            if (mmi->ptMinTrackSize.x < minDim) mmi->ptMinTrackSize.x = minDim;
            if (mmi->ptMinTrackSize.y < minDim) mmi->ptMinTrackSize.y = minDim;
            HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi;
            ZeroMemory(&mi, sizeof(mi));
            mi.cbSize = sizeof(mi);
            if (GetMonitorInfoW(monitor, &mi)) {
                mmi->ptMaxPosition.x = mi.rcWork.left;
                mmi->ptMaxPosition.y = mi.rcWork.top;
                mmi->ptMaxSize.x = mi.rcWork.right - mi.rcWork.left;
                mmi->ptMaxSize.y = mi.rcWork.bottom - mi.rcWork.top;
            }
            return 0;
        }
        case WM_NCCALCSIZE: {
            // 无系统边框/标题栏：我们自绘标题栏（CSD），缩放靠 WM_NCHITTEST。
            // 注意 wParam == 0（窗口创建/普通查询）也必须返回 0 且不调用
            // DefWindowProc，否则系统标题栏会被画出来（会出现两条标题栏，
            // 而拖拽缩放时 wParam != 0 又变回无边框，看起来像"标题栏消失"）。
            //
            // ★ 最大化时客户区必须夹到显示器工作区（真机 bug 的根因）：
            //   Windows 最大化窗口时会把窗口矩形按「不可见缩放边框」（96dpi 下 8px，
            //   200% 缩放下 16px）向屏幕外扩，于是 GetWindowRect 比屏幕还大。我们
            //   WM_NCCALCSIZE 返回 0（客户区 = 窗口矩形），客户区就这样超出屏幕、
            //   原点落在 (-8,-8)：左/上 8px 落到屏幕外（内容整体左上偏移），右 8px 也
            //   落到屏幕外 —— **最右侧的关闭按钮被裁掉一半**（真机反馈「关闭图标歪的，
            //   右侧一点画面被裁切」，而且只有最大化时出现，因为普通状态下窗口矩形
            //   就是客户区）。老代码 `rgrc[0].top += 8` 是只治上边、还写死了 96dpi 的
            //   硬编码补丁，左右下完全没治，所以这个 bug 一直没关掉。
            if (!window->undecorated) break;
            if (wParam != 0 && IsZoomed(hwnd)) {
                NCCALCSIZE_PARAMS* nc = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
                HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                MONITORINFO mi;
                ZeroMemory(&mi, sizeof(mi));
                mi.cbSize = sizeof(mi);
                if (GetMonitorInfoW(monitor, &mi)) {
                    nc->rgrc[0].left = mi.rcWork.left;
                    nc->rgrc[0].top = mi.rcWork.top;
                    nc->rgrc[0].right = mi.rcWork.right;
                    nc->rgrc[0].bottom = mi.rcWork.bottom;
                }
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
            if (w > 0 && h > 0 && IsZoomed(hwnd)) {
                // 最大化不变量：客户区不得超出显示器工作区。
                // 超出 = 窗口被扩到屏幕外（见 WM_NCCALCSIZE 的说明），最右侧/最下侧的
                // 内容会被裁掉。真机回归断言读的就是 clientOverflowCount。
                HMONITOR monitor = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                MONITORINFO mi;
                ZeroMemory(&mi, sizeof(mi));
                mi.cbSize = sizeof(mi);
                if (GetMonitorInfoW(monitor, &mi)) {
                    const int workW = static_cast<int>(mi.rcWork.right - mi.rcWork.left);
                    const int workH = static_cast<int>(mi.rcWork.bottom - mi.rcWork.top);
                    if (w > workW || h > workH) {
                        ++window->clientOverflowCount;
                        composeknLog(
                            "win32: MAXIMIZE-OVERFLOW client=%dx%d work=%dx%d -> 客户区超出工作区，右/下内容会被裁切",
                            w, h, workW, workH);
                    } else if (!window->maximizeLogged) {
                        window->maximizeLogged = true;
                        RECT wr;
                        GetWindowRect(hwnd, &wr);
                        composeknLog(
                            "win32: maximize ok: window=%d,%d %dx%d client=%dx%d work=%d,%d %dx%d",
                            static_cast<int>(wr.left), static_cast<int>(wr.top),
                            static_cast<int>(wr.right - wr.left), static_cast<int>(wr.bottom - wr.top),
                            w, h,
                            static_cast<int>(mi.rcWork.left), static_cast<int>(mi.rcWork.top),
                            workW, workH);
                    }
                }
            } else {
                window->maximizeLogged = false;
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
            uint32_t vk = static_cast<uint32_t>(wParam);
            if (vk == VK_PROCESSKEY) {
                // 组字期间 IME 会把按键「吃掉」并换成 VK_PROCESSKEY。AWT 的做法是
                // 用 ImmGetVirtualKey 取回原始键码再继续派发（快捷键仍然有效），
                // 否则 Compose 只会收到一串「未知按键」。
                const uint32_t original = static_cast<uint32_t>(ImmGetVirtualKey(hwnd));
                if (original != 0 && original != VK_PROCESSKEY) {
                    vk = original;
                }
                if (window != nullptr && window->imeProcessKeyLogCount < 5) {
                    ++window->imeProcessKeyLogCount;
                    composeknLog("ime: VK_PROCESSKEY -> vk=0x%X", vk);
                }
            }
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_KEY;
            e.button = vk;
            e.state = (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) ? 1u : 0u;
            e.a = 0; // flags placeholder
            e.b = static_cast<int32_t>((lParam >> 16) & 0xFF);
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_CHAR:
        case WM_UNICHAR: {
            const wchar_t character = static_cast<wchar_t>(wParam);
            if (window != nullptr && message == WM_CHAR &&
                consumeDuplicateCommittedChar(window, character)) {
                // 这条 WM_CHAR 就是刚才 GCS_RESULTSTR 里那个字符：IME 又送了一遍。
                // 不丢掉的话文本会被插入两次（不同 IME/兼容层行为不一致，所以两边都要接）。
                if (window->imeMessageCount <= 200) {
                    composeknLog("ime: 丢弃重复的 WM_CHAR U+%04X（提交串已处理过）",
                                 static_cast<unsigned>(character));
                }
                break;
            }
            // 注意：**故意不提 WM_IME_CHAR**。
            //
            // 有的 IME/兼容层把提交字符作为 WM_IME_CHAR 送来，而 DefWindowProc 会把它
            // 变形成 WM_CHAR（现在"中文能打进去"靠的就是这条）。如果我们再自己把
            // WM_IME_CHAR 也派发一遍，就会出现「一份字符、两条 CHAR 事件」——
            // 而这时提交串**不在**去重列表里（IME 没走 GCS_RESULTSTR），
            // 去重帮不上忙 → 文本会插入两次。保持原样最安全：
            // WM_IME_CHAR 交给 DefWindowProc，我们只在 WM_CHAR 这一层去重。
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_CHAR;
            e.b = message == WM_UNICHAR ? static_cast<int32_t>(wParam)
                                        : static_cast<int32_t>(character);
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
            e.b = 0;  // 0 = 纵向（WM_MOUSEWHEEL）
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_MOUSEHWHEEL: {
            // 横向滚轮 / 触控板横滑。之前完全没处理，所以「横着滑」没有任何反应
            // （JVM 桌面是支持的 —— 这条属于行为对齐缺口）。
            ComposeKNWin32Event e{};
            e.type = COMPOSEKN_WIN32_EVENT_MOUSE_WHEEL;
            POINT pt = {GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
            ScreenToClient(window->hwnd, &pt);
            e.x = static_cast<float>(pt.x);
            e.y = static_cast<float>(pt.y);
            e.a = GET_WHEEL_DELTA_WPARAM(wParam);
            e.b = 1;  // 1 = 横向（WM_MOUSEHWHEEL）
            e.modifiers = queryCurrentModifiers();
            pushEvent(window, e);
            break;
        }
        case WM_SETCURSOR: {
            // 客户区里必须由我们回答光标：箭头是**类**光标，鼠标一动系统就会把它
            // 设回去，所以这里每次都 SetCursor(当前 Compose 想要的形状) 并返回 TRUE。
            if (window != nullptr && LOWORD(lParam) == HTCLIENT) {
                SetCursor(composeknLoadCursor(window->cursorKind));
                return TRUE;
            }
            break;
        }
        case WM_POINTERDOWN:
        case WM_POINTERUPDATE:
        case WM_POINTERUP:
        case WM_POINTERCAPTURECHANGED: {
            // ------------------------------------------------------------------
            // 触摸/笔 -> Compose 的 PointerType.Touch
            //
            // 为什么需要这条通道：默认情况下 Windows 会把触摸**提升成鼠标消息**，
            // 而 Compose 的 scrollable 明确拒绝鼠标拖拽滚动
            // （foundation/gestures/AbstractScrollableNode.kt:
            //   internal val CanDragCalculation = { type -> type != PointerType.Mouse }）
            // 于是「点击能用、滑动不滚」——真机反馈的正是这个现象。
            // 处理 WM_POINTER* 并**不交给 DefWindowProc**，系统就不再做鼠标提升，
            // 触摸于是走 Compose 真正的触摸手势（拖动 + 甩动惯性）。
            // ------------------------------------------------------------------
            if (window == nullptr || !window->touchEnabled) break;
            const uint32_t pointerId = static_cast<uint32_t>(GET_POINTERID_WPARAM(wParam));
            // 只认触摸/笔：鼠标（EnableMouseInPointer 之后也会产生 WM_POINTER）走
            // WM_MOUSE* 通道，否则一次移动会变成两条事件。
            if (message != WM_POINTERCAPTURECHANGED && g_getPointerType != nullptr) {
                POINTER_INPUT_TYPE pointerType = PT_POINTER;
                if (g_getPointerType(pointerId, &pointerType) &&
                    pointerType != PT_TOUCH && pointerType != PT_PEN) {
                    break;
                }
            }
            ComposeKNWin32Event e{};
            e.button = pointerId;
            e.modifiers = queryCurrentModifiers();
            if (message == WM_POINTERCAPTURECHANGED) {
                // 触点被系统收走（手势识别、其它窗口抢焦点…）：必须补一个抬起，
                // 否则 Kotlin 侧的活动触点表会永远留着这根手指，后续滚动就"卡死"了。
                e.type = COMPOSEKN_WIN32_EVENT_TOUCH_UP;
                e.state = 0;
            } else {
                // 坐标语义：lParam 到底是客户区还是屏幕坐标，各版本文档说法不一致，
                // 这里直接用 GetPointerInfo 的**屏幕**坐标再转客户区，避免把触摸位置搞错。
                POINT pt{};
                bool got = false;
                if (g_getPointerInfo != nullptr) {
                    POINTER_INFO info{};
                    if (g_getPointerInfo(pointerId, &info)) {
                        pt = info.ptPixelLocation;
                        got = true;
                    }
                }
                if (got) {
                    ScreenToClient(hwnd, &pt);
                } else {
                    // 极少见：user32 里没有 GetPointerInfo（Win8 以下）。按客户区坐标兜底。
                    pt.x = GET_X_LPARAM(lParam);
                    pt.y = GET_Y_LPARAM(lParam);
                    if (window->touchLogCount == 0) {
                        composeknLog("touch: GetPointerInfo 不可用，回退 lParam 客户区坐标");
                    }
                }
                e.type = message == WM_POINTERDOWN ? COMPOSEKN_WIN32_EVENT_TOUCH_DOWN
                       : message == WM_POINTERUP   ? COMPOSEKN_WIN32_EVENT_TOUCH_UP
                                                   : COMPOSEKN_WIN32_EVENT_TOUCH_MOVE;
                e.state = (message == WM_POINTERUP) ? 0u : 1u;
                e.x = static_cast<float>(pt.x);
                e.y = static_cast<float>(pt.y);
            }
            pushEvent(window, e);
            if (window->touchLogCount < 12) {
                ++window->touchLogCount;
                composeknLog("touch: type=%d id=%u pos=%.0f,%.0f raw=%.0f",
                             static_cast<int>(e.type), pointerId,
                             static_cast<double>(e.x), static_cast<double>(e.y),
                             static_cast<double>(e.state));
            }
            // 明确"我处理了这条指针消息"（不交给 DefWindowProc）：
            // 否则系统会再合成一份鼠标消息，一次触摸变成两套输入。
            return 0;
        }
        // ------------------------------------------------------------------
        // IME（IMM32）消息。说明见上面 positionImeWindows 前的注释。
        // ------------------------------------------------------------------
        case WM_IME_SETCONTEXT: {
            // 组字预览由 Compose 自己画（setComposingText 会让文本框带下划线），
            // 所以请 IME 不要自己画组字窗口；
            // **候选窗必须保留**（那是 IME 自己的窗口，清掉用户就看不到候选词了）
            // —— 上游 AWT 的做法与此一致。
            lParam &= ~static_cast<LPARAM>(ISC_SHOWUICOMPOSITIONWINDOW);
            if (window != nullptr) ++window->imeMessageCount;
            if (window != nullptr && window->imeSetContextLogCount < 8) {
                ++window->imeSetContextLogCount;
                composeknLog("ime: WM_IME_SETCONTEXT active=%d layout=%p ime=%d",
                             wParam != 0 ? 1 : 0, (void*)GetKeyboardLayout(0),
                             ImmIsIME(GetKeyboardLayout(0)) ? 1 : 0);
            }
            break;  // 交给 DefWindowProc（默认 IME 窗口过程）
        }
        case WM_IME_NOTIFY: {
            // 诊断：输入法用它通知应用「我改了什么」（候选窗位置、组字窗、状态窗…）。
            // IMN_SETCANDIDATEPOS / IMN_SETCOMPOSITIONWINDOW 是判断
            // ImmSetCandidateWindow/ImmSetCompositionWindow 到底有没有生效的证据。
            if (window != nullptr && window->imeNotifyLogCount < 24) {
                ++window->imeNotifyLogCount;
                composeknLog("ime: WM_IME_NOTIFY code=0x%04lX", (unsigned long)wParam);
            }
            break;  // 交给 DefWindowProc（默认 IME 窗口过程）
        }
        case WM_IME_STARTCOMPOSITION: {
            if (window != nullptr) {
                window->imeComposing = true;
                ++window->imeMessageCount;
                composeknLog("ime: WM_IME_STARTCOMPOSITION（开始组字）");
                // 组字一开始就把候选窗摆到光标处：很多 IME 在这条之后就画候选窗了。
                positionImeWindows(window);
                pushImeEvent(window, COMPOSEKN_WIN32_EVENT_IME_START, std::wstring());
            }
            return 0;
        }
        case WM_IME_COMPOSITION: {
            if (window == nullptr) break;
            ++window->imeMessageCount;
            HIMC himc = ImmGetContext(hwnd);
            if (himc == nullptr) {
                composeknLog("ime: WM_IME_COMPOSITION 但 ImmGetContext() 返回 NULL");
                break;
            }
            // lParam == 0 表示「只是属性/字体变了」，按「重读当前组字串」处理；
            // 有 GCS_RESULTSTR 时说明这一段已经**提交**（用户选了候选词）。
            const bool hasResult = (lParam & GCS_RESULTSTR) != 0;
            bool hasComposition = (lParam & GCS_COMPSTR) != 0;
            if (!hasResult && !hasComposition) hasComposition = true;
            const std::wstring result = hasResult ? imeCompositionString(himc, GCS_RESULTSTR)
                                                  : std::wstring();
            const std::wstring composition = hasComposition ? imeCompositionString(himc, GCS_COMPSTR)
                                                            : std::wstring();
            const int compositionCursor = hasComposition
                ? static_cast<int>(ImmGetCompositionStringW(himc, GCS_CURSORPOS, nullptr, 0))
                : 0;
            ImmReleaseContext(hwnd, himc);

            if (!result.empty()) {
                // 先记下这些字符：部分 IME 随后还会把它们作为 WM_CHAR 再送一遍，
                // 那时必须丢掉（否则文本插入两次）。
                rememberCommittedChars(window, result);
                pushImeEvent(window, COMPOSEKN_WIN32_EVENT_IME_COMMIT, result);
                composeknLog("ime: 提交 \"%s\"（%d 个 UTF-16）",
                             wideToUtf8(result).c_str(), static_cast<int>(result.size()));
            }
            if (hasComposition) {
                pushImeEvent(window, COMPOSEKN_WIN32_EVENT_IME_UPDATE, composition);
                if (window->imeMessageCount <= 40) {
                    composeknLog("ime: 组字 \"%s\" cursor=%d flags=0x%lX",
                                 wideToUtf8(composition).c_str(), compositionCursor,
                                 (unsigned long)lParam);
                }
            }
            // 光标/候选窗位置每次都更新
            positionImeWindows(window);
            return 0;
        }
        case WM_IME_ENDCOMPOSITION: {
            if (window != nullptr) {
                window->imeComposing = false;
                ++window->imeMessageCount;
                composeknLog("ime: WM_IME_ENDCOMPOSITION（组字结束）");
                pushImeEvent(window, COMPOSEKN_WIN32_EVENT_IME_END, std::wstring());
            }
            return 0;
        }
        case WM_IME_REQUEST: {
            if (window == nullptr) break;
            ++window->imeMessageCount;
            switch (wParam) {
                case IMR_QUERYCHARPOSITION: {
                    // Win8+ 的 TSF 兼容层靠这条问「组字字符在屏幕上的矩形」。
                    // 不处理它时 IME 只能拿 GetCaretPos()（恒为 0,0）—— 候选窗
                    // 会卡在窗口左上角/不再更新，这正是真机反馈的现象。
                    //
                    // 必须**按 dwCharPos 回答**吗？——真机两轮实测后改成：组字期间
                    // 一律回答「组字串第 0 个字符」（把整串的范围塌缩成一个点），
                    // 这样输入法（它显然用光标那条查询的结果摆候选窗）算出来的候选窗
                    // 才会钉在开始组字的位置。原因见上面 imeAnswerCharIndex 的注释。
                    auto* charPos = reinterpret_cast<IMECHARPOSITION*>(lParam);
                    if (charPos != nullptr) {
                        const int32_t dwCharPos = static_cast<int32_t>(charPos->dwCharPos);
                        const int32_t charIndex = imeAnswerCharIndex(window, dwCharPos);
                        int32_t x = 0, y = 0, w = 0, h = 0;
                        imeCaretRect(window, charIndex, &x, &y, &w, &h);
                        // 诊断：组字中顺手把"如实答案"也算出来，日志里能看出差多少
                        int32_t hx = x, hy = y, hw = w, hh = h;
                        const bool reportedAnchor = charIndex != dwCharPos;
                        if (reportedAnchor && window->imeCharPosLogCount < 60) {
                            imeCaretRect(window, dwCharPos, &hx, &hy, &hw, &hh);
                        }
                        POINT pt = { x, y + h };
                        ClientToScreen(hwnd, &pt);
                        RECT client;
                        GetClientRect(hwnd, &client);
                        POINT topLeft = { client.left, client.top };
                        POINT bottomRight = { client.right, client.bottom };
                        ClientToScreen(hwnd, &topLeft);
                        ClientToScreen(hwnd, &bottomRight);
                        charPos->dwSize = sizeof(IMECHARPOSITION);
                        charPos->pt = pt;
                        charPos->cLineHeight = h > 0 ? static_cast<UINT>(h) : 20u;
                        charPos->rcDocument = { topLeft.x, topLeft.y, bottomRight.x, bottomRight.y };
                        // 输入法每敲一个键会问好几次，且答案经常一样：只在答案变化时
                        // 记一行（并设上限），否则真机日志会被它刷爆。
                        logImeRequestOnce(window, wParam, "QUERYCHARPOSITION");
                        if (window->imeCharPosLogCount < 60 &&
                            (dwCharPos != window->imeCharPosLastIndex ||
                             pt.x != window->imeCharPosLastX ||
                             pt.y != window->imeCharPosLastY)) {
                            ++window->imeCharPosLogCount;
                            window->imeCharPosLastIndex = dwCharPos;
                            window->imeCharPosLastX = pt.x;
                            window->imeCharPosLastY = pt.y;
                            if (reportedAnchor) {
                                composeknLog(
                                    "ime: IMR_QUERYCHARPOSITION dwCharPos=%d -> %ld,%ld "
                                    "lineHeight=%u（组字中锚定起点；如实=%ld,%ld）",
                                    dwCharPos, (long)pt.x, (long)pt.y, charPos->cLineHeight,
                                    (long)hx, (long)(hy + hh));
                            } else {
                                composeknLog(
                                    "ime: IMR_QUERYCHARPOSITION dwCharPos=%d -> %ld,%ld lineHeight=%u",
                                    dwCharPos, (long)pt.x, (long)pt.y, charPos->cLineHeight);
                            }
                        }
                        return TRUE;
                    }
                    break;
                }
                // 注意：这个值在微软 SDK 的 imm.h 里叫 IMR_CANDIDATEPOS，
                // mingw-w64 的 imm.h 里叫 IMR_CANDIDATEWINDOW（同一数值 0x0002）。
                case IMR_CANDIDATEWINDOW:
                case IMR_COMPOSITIONWINDOW: {
                    // 应用在这个缓冲区里回填「候选窗/组字窗」的位置。
                    // 与 positionImeWindows 一致：组字中一律锚在**组字串起点**，
                    // 不在组字中才跟光标（见 imeAnswerCharIndex）。
                    logImeRequestOnce(window, wParam,
                                      wParam == IMR_CANDIDATEWINDOW ? "CANDIDATEWINDOW"
                                                                    : "COMPOSITIONWINDOW");
                    const int32_t charIndex = imeAnswerCharIndex(window, -1);
                    int32_t x = 0, y = 0, w = 0, h = 0;
                    imeCaretRect(window, charIndex, &x, &y, &w, &h);
                    POINT pt = { x, y + h };
                    ClientToScreen(hwnd, &pt);
                    if (wParam == IMR_CANDIDATEWINDOW) {
                        auto* form = reinterpret_cast<CANDIDATEFORM*>(lParam);
                        if (form != nullptr) {
                            form->dwStyle = CFS_CANDIDATEPOS;
                            form->ptCurrentPos = pt;
                            return TRUE;
                        }
                    } else {
                        auto* form = reinterpret_cast<COMPOSITIONFORM*>(lParam);
                        if (form != nullptr) {
                            form->dwStyle = CFS_POINT;
                            form->ptCurrentPos = pt;
                            return TRUE;
                        }
                    }
                    break;
                }
                case IMR_RECONVERTSTRING:
                case IMR_DOCUMENTFEED: {
                    // 输入法要「文档 + 组字/目标范围」：上下文候选排序和「重新转换」
                    // 都要它。我们交一段窗口（组字/选区前后各 128 字），缓冲不够时
                    // 按两段式约定把 dwSize 改成需要的大小再回 TRUE。
                    {
                        const char* name = wParam == IMR_DOCUMENTFEED ? "DOCUMENTFEED"
                                                                     : "RECONVERTSTRING";
                        logImeRequestOnce(window, wParam, name);
                        auto* rec = reinterpret_cast<RECONVERTSTRING*>(lParam);
                        if (window->imeReconvertLogCount < 12) {
                            ++window->imeReconvertLogCount;
                            if (rec == nullptr) {
                                // 真机（MS 拼音）实测就是这一种：没有缓冲区。
                                composeknLog(
                                    "ime: WM_IME_REQUEST what=%lu(%s) lParam=NULL -> %s",
                                    (unsigned long)wParam, name,
                                    window->imeDocumentFeedMode == 2
                                        ? "回 TRUE（探针模式，看输入法会不会带缓冲区再来）"
                                        : "不处理（没有缓冲区可填）");
                            } else {
                                composeknLog(
                                    "ime: WM_IME_REQUEST what=%lu(%s) 收到 dwSize=%lu "
                                    "dwStrLen=%lu comp=%lu@%lu target=%lu@%lu",
                                    (unsigned long)wParam, name,
                                    (unsigned long)rec->dwSize, (unsigned long)rec->dwStrLen,
                                    (unsigned long)rec->dwCompStrLen,
                                    (unsigned long)rec->dwCompStrOffset,
                                    (unsigned long)rec->dwTargetStrLen,
                                    (unsigned long)rec->dwTargetStrOffset);
                            }
                        }
                        if (window->imeDocumentFeedMode == 0) break;   // 完全不答
                        if (rec != nullptr && rec->dwSize < sizeof(RECONVERTSTRING)) {
                            // 真机（MS 拼音，探针模式）实测：在 NULL 之后它确实会带一个
                            // RECONVERTSTRING 回来，但那个结构**完全没初始化** ——
                            // dwSize=1、dwStrLen/偏移全是垃圾值。这种情况下**绝不能**
                            // 往它里面写任何东西（我们不知道缓冲区到底多大，写 dwSize
                            // 那 4 个字节就可能越界）。
                            composeknLog(
                                "ime: IMR_%s 的 dwSize=%lu < sizeof(RECONVERTSTRING)（未初始化"
                                "结构）-> 不写、不处理",
                                name, (unsigned long)rec->dwSize);
                            break;
                        }
                        if (rec == nullptr) {
                            // 探针模式：告诉输入法"我支持文档馈送"，看它会不会带缓冲区再来。
                            if (window->imeDocumentFeedMode == 2) return TRUE;
                            break;
                        }
                        if (fillReconvertString(window, rec)) return TRUE;
                    }
                    break;
                }
                case IMR_CONFIRMRECONVERTSTRING: {
                    // 「重新转换」的确认步：输入法确认了它要重转换的范围。应用必须**先
                    // 把原文本变成选区**，接下来那段组字才会替换它（否则文本会重复）。
                    //
                    // 安全策略：只在与文档能对上时答应 —— Kotlin 侧在文档里找这段文本，
                    // 找到才回 1（并把范围写回来），我们就推一条「设选区」事件再回 TRUE；
                    // 找不到就**拒绝**（不处理 = 输入法取消重转换），绝不动文本。
                    logImeRequestOnce(window, wParam, "CONFIRMRECONVERTSTRING");
                    auto* rec = reinterpret_cast<RECONVERTSTRING*>(lParam);
                    if (rec == nullptr || g_imeReconvertProvider == nullptr) break;
                    const DWORD strBytes = static_cast<DWORD>(sizeof(WCHAR)) * rec->dwStrLen;
                    if (rec->dwStrLen == 0 || rec->dwStrOffset < sizeof(RECONVERTSTRING) ||
                        static_cast<DWORD>(rec->dwStrOffset) + strBytes > rec->dwSize) {
                        if (window->imeReconvertLogCount < 12) {
                            ++window->imeReconvertLogCount;
                            composeknLog(
                                "ime: IMR_CONFIRMRECONVERTSTRING 结构不合法（dwSize=%lu dwStrLen=%lu "
                                "dwStrOffset=%lu）-> 拒绝",
                                (unsigned long)rec->dwSize, (unsigned long)rec->dwStrLen,
                                (unsigned long)rec->dwStrOffset);
                        }
                        break;
                    }
                    const auto* text = reinterpret_cast<const uint16_t*>(
                        reinterpret_cast<const unsigned char*>(rec) + rec->dwStrOffset);
                    const int32_t targetOffset =
                        static_cast<int32_t>(rec->dwTargetStrOffset / sizeof(WCHAR));
                    const int32_t targetLen = static_cast<int32_t>(rec->dwTargetStrLen);
                    int32_t mappedStart = 0, mappedEnd = 0;
                    const int32_t mapped = g_imeReconvertProvider(
                        g_imeReconvertProviderUser, text, static_cast<int32_t>(rec->dwStrLen),
                        targetOffset, targetLen, &mappedStart, &mappedEnd);
                    composeknLog(
                        "ime: IMR_CONFIRMRECONVERTSTRING dwStrLen=%lu comp=%lu@%lu target=%d@%d "
                        "-> %s[%d,%d)",
                        (unsigned long)rec->dwStrLen, (unsigned long)rec->dwCompStrLen,
                        (unsigned long)rec->dwCompStrOffset, targetLen, targetOffset,
                        mapped ? "接受" : "拒绝", mappedStart, mappedEnd);
                    if (!mapped) break;   // 拒绝：交给 DefWindowProc
                    // 让 Compose 先把这段原文本选中；随后的组字会替换掉它。
                    // 注意：pushImeEvent() 会把 a 设成文本长度，这里要自己填范围 ——
                    // 但**仍然要**往 imeTexts 里补一个空串，保持「事件 ↔ 文本」的
                    // FIFO 一一对应（Kotlin 侧每条 IME 事件都会弹一次文本）。
                    ComposeKNWin32Event e{};
                    e.type = COMPOSEKN_WIN32_EVENT_IME_RECONVERT_SELECT;
                    e.a = mappedStart;
                    e.b = mappedEnd;
                    e.state = window->imeComposing ? 1u : 0u;
                    pushEvent(window, e);
                    window->imeTexts.push_back(std::string());
                    return TRUE;
                }
                case IMR_COMPOSITIONFONT: {
                    // 输入法问「组字用什么字体」：回我们实际用的行高 + 系统 UI 字体。
                    auto* lf = reinterpret_cast<LOGFONTW*>(lParam);
                    if (lf == nullptr) break;
                    logImeRequestOnce(window, wParam, "COMPOSITIONFONT");
                    if (!window->imeCompositionFontEnabled) break;
                    if (fillImeCompositionFont(window, lf)) return TRUE;
                    break;
                }
                default:
                    // 未处理的请求：只记前几条，免得真机日志被刷爆。
                    if (window->imeRequestLogCount < 4) {
                        ++window->imeRequestLogCount;
                        composeknLog("ime: WM_IME_REQUEST what=%lu（未处理，交给 DefWindowProc）",
                                     (unsigned long)wParam);
                    }
                    break;
            }
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

// width/height 的单位是 **dp（逻辑像素）**，与 Compose 桌面的
// `WindowState(size = DpSize(...))` 一致；物理尺寸在内部按 DPI 换算。
// 之前这里直接当物理像素用，结果 200% 缩放的屏幕上「1100x760」只会得到 550x380dp。
extern "C" ComposeKNWin32Window* composekn_win32_create(
    const char* title, int width_dp, int height_dp, int undecorated) {
    // ICU 数据已经嵌在 exe 里（见 win32_icu.cc）。这里同步跑一次有两个目的：
    //   1) 文本排版之前数据一定就绪（不依赖 Skia 的惰性初始化时机）；
    //   2) 让链接器必须解析 SkLoadICU —— 于是**我们那份**（win32_icu.cc 里的同名
    //      覆盖版）会被从 nativeBridges 归档里拉进来，libicu.a 里上游那个
    //      「从 exe 同目录 mmap icudtl.dat」的成员就不会被拉入。
    SkLoadICU();
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

    const double scale = systemDpiScale();
    int width = static_cast<int>(width_dp * scale + 0.5);
    int height = static_cast<int>(height_dp * scale + 0.5);
    if (width < 1) width = 1;
    if (height < 1) height = 1;
    composeknLog("composekn_win32_create: title=\"%s\" dp=%dx%d -> px=%dx%d (scale=%.2f)",
                 title, width_dp, height_dp, width, height, scale);
    ComposeKNWin32Window* window = new ComposeKNWin32Window();
    window->width = width;
    window->height = height;
    window->undecorated = undecorated != 0;
    composeknLog("composekn_win32_create: decorations=%s", window->undecorated ? "none(CSD)" : "system");
    // 触摸通道默认开；COMPOSEKN_TOUCH=0 关掉（退回系统「触摸提升成鼠标」的老行为）。
    const char* touchEnv = getenv("COMPOSEKN_TOUCH");
    window->touchEnabled = !(touchEnv != nullptr && touchEnv[0] == '0');
    // 文档馈送（IMR_DOCUMENTFEED/RECONVERTSTRING）默认开；万一某些输入法拿它做了
    // 奇怪的事，COMPOSEKN_IME_DOCUMENTFEED=0 可以退回 v0.4.12 的行为（不回答）。
    const char* docFeedEnv = getenv("COMPOSEKN_IME_DOCUMENTFEED");
    if (docFeedEnv != nullptr && docFeedEnv[0] == '0') {
        window->imeDocumentFeedMode = 0;
    } else if (docFeedEnv != nullptr && (docFeedEnv[0] == '2' || docFeedEnv[0] == 'p')) {
        window->imeDocumentFeedMode = 2;   // probe
    } else {
        window->imeDocumentFeedMode = 1;
    }
    const char* compFontEnv = getenv("COMPOSEKN_IME_COMPOSITION_FONT");
    window->imeCompositionFontEnabled = !(compFontEnv != nullptr && compFontEnv[0] == '0');
    resolvePointerApis();
    composeknLog("composekn_win32_create: touch=%s pointerApi=%s",
                 window->touchEnabled ? "on" : "off",
                 g_getPointerInfo != nullptr ? "ok" : "missing");
    composeknLog("composekn_win32_create: imeDocumentFeed=%s imeCompositionFont=%s",
                 window->imeDocumentFeedMode == 0 ? "off(COMPOSEKN_IME_DOCUMENTFEED=0)"
                 : window->imeDocumentFeedMode == 2 ? "probe(NULL 也回 TRUE)"
                                                    : "on",
                 window->imeCompositionFontEnabled ? "on"
                                                   : "off(COMPOSEKN_IME_COMPOSITION_FONT=0)");
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

    // 注意：**不要**在窗口刚建好时用 GetDpiForWindow/WM_DPICHANGED 去"校正"尺寸 ——
    // 这时窗口还没真正落到显示器上，dpiScaleOf(window) 会返回 1.0，于是把刚算好的
    // 2 倍缩放又抹掉（真机日志：dp=1100x760 -> px=2200x1520，紧接着又"校正"回 1100x760）。
    // 多显示器/不同缩放的场景交给 WM_DPICHANGED（窗口被拖到别的屏时系统会通知）。
    window->dpi = queryWindowDpi(window->hwnd);
    ShowWindow(window->hwnd, SW_SHOWNORMAL);
    UpdateWindow(window->hwnd);
    maybeStartTestResize(window);

    // IME：默认情况下窗口是关联着线程默认 IME 上下文的，直接 ImmGetContext 就能用；
    // 万一取不到（某些环境下窗口类没拿到默认上下文），显式创建并关联一个。
    {
        HIMC context = ImmGetContext(window->hwnd);
        bool created = false;
        if (context == nullptr) {
            context = ImmCreateContext();
            if (context != nullptr) {
                ImmAssociateContext(window->hwnd, context);
                created = true;
            }
        } else {
            ImmReleaseContext(window->hwnd, context);
        }
        HKL layout = GetKeyboardLayout(0);
        composeknLog("win32: IME context=%s(created=%d) layout=%p isIME=%d",
                     context != nullptr ? "ok" : "missing", created ? 1 : 0, (void*)layout,
                     ImmIsIME(layout) ? 1 : 0);
    }
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

extern "C" int32_t composekn_win32_ime_pop_text(
    ComposeKNWin32Window* window,
    char* buffer,
    int32_t buffer_size
) {
    if (window == nullptr || window->imeTexts.empty()) return -1;
    std::string text = std::move(window->imeTexts.front());
    window->imeTexts.erase(window->imeTexts.begin());
    if (buffer == nullptr || buffer_size <= 0) return -1;
    int32_t count = static_cast<int32_t>(text.size());
    if (count > buffer_size - 1) count = buffer_size - 1;
    if (count > 0) std::memcpy(buffer, text.data(), static_cast<size_t>(count));
    buffer[count] = '\0';
    return count;
}

extern "C" int32_t composekn_win32_ime_message_count(ComposeKNWin32Window* window) {
    return window == nullptr ? 0 : window->imeMessageCount;
}

extern "C" bool composekn_win32_ime_composing(ComposeKNWin32Window* window) {
    return window != nullptr && window->imeComposing;
}

/**
 * 取消正在进行的组字（对应 ImmNotifyIME(NI_COMPOSITIONSTR, CPS_CANCEL)）。
 *
 * Compose 的文本会话结束（输入框失焦/被移除）时调用：不取消的话 IME 会一直停在
 * 「组字中」，候选窗留在屏幕上不消失 —— 也是「候选词卡死」的一种表现。
 */
extern "C" void composekn_win32_ime_cancel_composition(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    window->pendingCommitChars.clear();
    if (!window->imeComposing) return;
    HIMC himc = ImmGetContext(window->hwnd);
    if (himc == nullptr) return;
    ImmNotifyIME(himc, NI_COMPOSITIONSTR, CPS_CANCEL, 0);
    ImmReleaseContext(window->hwnd, himc);
    composeknLog("ime: 取消组字（文本会话结束）");
}

/**
 * 自检/真机排查用：直接往事件队列里塞一条「提交」事件，并把字符记为已提交，
 * 从而验证从 C 侧文本通道 -> Kotlin -> Compose 的整条链路（Wine 里没有真 IME，
 * 不能靠输入法驱动这条路）。
 */
extern "C" void composekn_win32_ime_test_commit(ComposeKNWin32Window* window, const char* utf8) {
    if (window == nullptr || utf8 == nullptr) return;
    const int wideLen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    if (wideLen <= 1) return;
    std::vector<wchar_t> wide(static_cast<size_t>(wideLen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wide.data(), wideLen);
    const std::wstring text(wide.data(), static_cast<size_t>(wideLen - 1));
    rememberCommittedChars(window, text);
    pushImeEvent(window, COMPOSEKN_WIN32_EVENT_IME_COMMIT, text);
    composeknLog("ime: [test] 注入提交 \"%s\"", utf8);
}

/**
 * 自检用：走**真实**的 WM_IME_REQUEST(IMR_QUERYCHARPOSITION) 路径问一次
 * 「组字串里第 dwCharPos 个字符在哪」。
 *
 * 为什么要有它：候选窗锚点的逻辑在 C 侧（组字期间把整串范围塌缩成起点），光靠
 * Kotlin 侧的 imeCaretRectForChar() 断言不到 —— 那条路是"如实映射"，而这条才是
 * 真正回给输入法的答案。out = {x, y, lineHeight, handled}，坐标是**客户区**物理
 * 像素（pt 从屏幕坐标换回来，方便和 Kotlin 侧的光标矩形直接比）。
 */
extern "C" int32_t composekn_win32_ime_test_query_char_pos(
    ComposeKNWin32Window* window, int32_t dwCharPos, int32_t* out) {
    if (window == nullptr || window->hwnd == nullptr || out == nullptr) return 0;
    IMECHARPOSITION position;
    ZeroMemory(&position, sizeof(position));
    position.dwSize = sizeof(position);
    position.dwCharPos = static_cast<DWORD>(dwCharPos);
    const LRESULT handled = SendMessageW(window->hwnd, WM_IME_REQUEST,
                                        static_cast<WPARAM>(IMR_QUERYCHARPOSITION),
                                        reinterpret_cast<LPARAM>(&position));
    if (handled == 0) {
        out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0;
        return 0;
    }
    POINT pt = position.pt;
    ScreenToClient(window->hwnd, &pt);
    out[0] = static_cast<int32_t>(pt.x);
    out[1] = static_cast<int32_t>(pt.y);
    out[2] = static_cast<int32_t>(position.cLineHeight);
    out[3] = 1;
    return 1;
}

/**
 * 自检用：发一条 `IMR_DOCUMENTFEED`(0) / `IMR_RECONVERTSTRING`(1) / `IMR_COMPOSITIONFONT`(2)。
 *
 * 缓冲区由调用方给（[bufferChars] 个 UTF-16 字符；0 = 只给结构体本身，用来验
 * IMM32 的两段式约定：我们先回 dwSize = 需要的字节数，输入法再带够缓冲来问）。
 *
 * out 至少 12 个 int：
 *   [0] 消息返回值（1 = 我们处理了）
 *   [1] dwSize  [2] dwStrLen  [3] dwStrOffset  [4] dwCompStrLen  [5] dwCompStrOffset
 *   [6] dwTargetStrLen  [7] dwTargetStrOffset
 *   [8] 字符串 code unit 校验和  [9] lfHeight（字体请求）  [10] 字符串长度
 */
extern "C" int32_t composekn_win32_ime_test_reconvert(
    ComposeKNWin32Window* window, int32_t kind, int32_t bufferChars, int32_t* out) {
    if (window == nullptr || window->hwnd == nullptr || out == nullptr) return 0;
    for (int i = 0; i < 12; ++i) out[i] = 0;
    // bufferChars < 0：lParam 直接传 NULL（真机 MS 拼音就是这么发的，用来测我们的策略）
    // bufferChars == -2：lParam 给一个 dwSize 明显不合法（=1）的结构，测"不可信就不写"
    const bool nullBuffer = bufferChars < 0;
    const bool bogusSize = bufferChars == -2;
    if (nullBuffer) bufferChars = 0;
    const size_t extra = static_cast<size_t>(bufferChars) * sizeof(WCHAR);
    // 注意：LOGFONTW 比 RECONVERTSTRING 大（字体请求也会写整块），缓冲区取两者最大值。
    const size_t base = sizeof(LOGFONTW) > sizeof(RECONVERTSTRING) ? sizeof(LOGFONTW)
                                                                  : sizeof(RECONVERTSTRING);
    std::vector<unsigned char> storage(base + extra + 2, 0);
    auto* rec = reinterpret_cast<RECONVERTSTRING*>(storage.data());
    rec->dwSize = bogusSize ? 1u : static_cast<DWORD>(sizeof(RECONVERTSTRING) + extra);
    const UINT request = static_cast<UINT>(
        kind == 2 ? IMR_COMPOSITIONFONT : (kind == 1 ? IMR_RECONVERTSTRING : IMR_DOCUMENTFEED));
    const LRESULT handled = SendMessageW(
        window->hwnd, WM_IME_REQUEST, static_cast<WPARAM>(request),
        nullBuffer ? 0 : reinterpret_cast<LPARAM>(storage.data()));
    out[0] = handled != 0 ? 1 : 0;
    if (kind == 2) {
        auto* lf = reinterpret_cast<LOGFONTW*>(storage.data());
        out[9] = static_cast<int32_t>(lf->lfHeight);
        composeknLog("ime: [test] IMR_COMPOSITIONFONT -> handled=%d lfHeight=%d face=\"%s\"",
                     out[0], out[9], wideToUtf8(lf->lfFaceName).c_str());
        return 1;
    }
    out[1] = static_cast<int32_t>(rec->dwSize);
    out[2] = static_cast<int32_t>(rec->dwStrLen);
    out[3] = static_cast<int32_t>(rec->dwStrOffset);
    out[4] = static_cast<int32_t>(rec->dwCompStrLen);
    out[5] = static_cast<int32_t>(rec->dwCompStrOffset);
    out[6] = static_cast<int32_t>(rec->dwTargetStrLen);
    out[7] = static_cast<int32_t>(rec->dwTargetStrOffset);
    std::wstring text;
    if (rec->dwStrLen > 0 &&
        static_cast<size_t>(rec->dwStrOffset) + static_cast<size_t>(rec->dwStrLen) * sizeof(WCHAR) <=
            storage.size()) {
        const wchar_t* chars =
            reinterpret_cast<const wchar_t*>(storage.data() + rec->dwStrOffset);
        text.assign(chars, chars + rec->dwStrLen);
    }
    int32_t sum = 0;
    for (wchar_t c : text) sum += static_cast<int32_t>(c);
    out[8] = sum;
    out[10] = static_cast<int32_t>(text.size());
    std::wstring shown = text.size() > 40 ? text.substr(0, 40) + L"…" : text;
    composeknLog("ime: [test] reconvert kind=%d -> handled=%d strLen=%d sum=%d comp=%d@%d text=\"%s\"",
                 kind, out[0], out[2], sum, out[4], out[5], wideToUtf8(shown).c_str());
    return 1;
}

/**
 * 自检用：合成一条 `IMR_CONFIRMRECONVERTSTRING`（「重新转换」的确认步）。
 *
 * [utf8] 是输入法"发回来"的字符串，[targetOffsetInText]/[targetLen] 是它在字符串里的
 * 目标范围（UTF-16 code unit）。返回 1 = 我们接受了（Kotlin 在文档里对上了），
 * 0 = 拒绝（对不上/结构不合法）。
 */
extern "C" int32_t composekn_win32_ime_test_confirm_reconvert(
    ComposeKNWin32Window* window, const char* utf8, int32_t targetOffsetInText, int32_t targetLen) {
    if (window == nullptr || window->hwnd == nullptr || utf8 == nullptr) return 0;
    const int wideLen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    if (wideLen <= 1) return 0;
    std::vector<wchar_t> wide(static_cast<size_t>(wideLen), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wide.data(), wideLen);
    const size_t textChars = static_cast<size_t>(wideLen - 1);
    const size_t textBytes = textChars * sizeof(WCHAR);
    std::vector<unsigned char> storage(sizeof(RECONVERTSTRING) + textBytes + 2, 0);
    auto* rec = reinterpret_cast<RECONVERTSTRING*>(storage.data());
    rec->dwSize = static_cast<DWORD>(storage.size());
    rec->dwVersion = 0;
    rec->dwStrLen = static_cast<DWORD>(textChars);
    rec->dwStrOffset = static_cast<DWORD>(sizeof(RECONVERTSTRING));
    rec->dwCompStrLen = static_cast<DWORD>(targetLen);
    rec->dwCompStrOffset = static_cast<DWORD>(targetOffsetInText) * static_cast<DWORD>(sizeof(WCHAR));
    rec->dwTargetStrLen = static_cast<DWORD>(targetLen);
    rec->dwTargetStrOffset = rec->dwCompStrOffset;
    std::memcpy(storage.data() + sizeof(RECONVERTSTRING), wide.data(), textBytes);
    const LRESULT handled = SendMessageW(window->hwnd, WM_IME_REQUEST,
                                         static_cast<WPARAM>(IMR_CONFIRMRECONVERTSTRING),
                                         reinterpret_cast<LPARAM>(storage.data()));
    return handled != 0 ? 1 : 0;
}

/**
 * 自检用：给窗口发一条合成的 WM_IME_STARTCOMPOSITION / WM_IME_ENDCOMPOSITION。
 *
 * Wine 里没有输入法，真机才有 —— 但这两条消息走的是和我们真实路径**完全相同**的
 * C 侧处理（设置 imeComposing、重新摆 IME 窗口、推 IME_START/IME_END 事件），
 * 所以「组字期间锚点塌缩、组字结束恢复如实」这件事在自动化里能被断言到。
 */
extern "C" void composekn_win32_ime_test_send_composition(ComposeKNWin32Window* window,
                                                          int32_t start) {
    if (window == nullptr || window->hwnd == nullptr) return;
    if (start != 0) {
        SendMessageW(window->hwnd, WM_IME_STARTCOMPOSITION, 0, 0);
    } else {
        SendMessageW(window->hwnd, WM_IME_ENDCOMPOSITION, 0, 0);
    }
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

/** 设置光标形状（0=箭头 1=手 2=文本I型 3=十字）。由 Compose 的 PointerIcon 驱动。 */
extern "C" void composekn_win32_set_cursor(ComposeKNWin32Window* window, int32_t kind) {
    if (window == nullptr) return;
    window->cursorKind = kind;
    if (window->hwnd != nullptr) SetCursor(composeknLoadCursor(kind));
}

extern "C" int32_t composekn_win32_client_overflow_count(ComposeKNWin32Window* window) {
    if (window == nullptr) return 0;
    return window->clientOverflowCount;
}

extern "C" bool composekn_win32_touch_enabled(ComposeKNWin32Window* window) {
    if (window == nullptr) return false;
    return window->touchEnabled;
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

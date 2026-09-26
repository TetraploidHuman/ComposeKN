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
// OLE 拖放（IDropTarget）：窗口要注册成拖放目标，接收资源管理器/其它应用拖进来的
// 文件（CF_HDROP）和文本（CF_UNICODETEXT）。对照 AWT 的 DropTarget（AWT 内部也是
// 走 OLE，见 java.awt.dnd 的 Windows 实现）。
#include <ole2.h>
#include <oleidl.h>
#include <shellapi.h>
#include <shlobj.h>
// 任务栏进度（ITaskbarList3）。没有 shell/任务栏的环境（比如 Wine + Xvfb）里
// CoCreateInstance 会失败 —— 那时老实报告"不支持"，不要假装成功。
#include <shobjidl.h>
// 上游的 SkLoadICU()（从 exe 同目录 mmap icudtl.dat）被 win32_icu.cc 里的同名版本
// 覆盖；这里显式引用它，保证链接器把**我们那份**从归档里拉进来。
#include "SkLoadICU.h"
#include <algorithm>
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

/**
 * 本线程 OleInitialize 成功调用次数（含 S_FALSE）。
 * 每建一个拖放窗口 +1，销毁时 -1；到 0 才 OleUninitialize —— 关一个窗不能把兄弟窗的 OLE 拆掉。
 */
static LONG g_oleInitCount = 0;

struct ComposeKNWin32Window {
    HWND hwnd = nullptr;
    int width = 0;
    int height = 0;
    int dpi = 96;
    bool closeRequested = false;
    bool quit = false;
    /** 当前挂在窗口上的菜单栏（本桥接持有所有权；SetMenu 替换时 DestroyMenu）。 */
    HMENU menuBar = nullptr;
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
    // 触摸事件时间的基准。POINTER_INFO.dwTime / GetMessageTime() 都是**系统 tick**
    // （32 位毫秒），这里减去第一个触摸事件的 tick，归一化成「进程内单调毫秒」
    // 再交给 Compose。
    //
    // 为什么必须带真实事件时间（§17.24）：宿主以前不给时间戳，`sendPointerEvent`
    // 就用默认的 `currentTimeMillis()` —— 那是**派发时刻**。窗口循环是「先把消息泵里
    // 的触摸事件一次全部派发，再渲染一帧」，于是同一帧里到达的几条 WM_POINTERUPDATE
    // 拿到同一个毫秒。Compose 的速度估计器（Lsq2：按时间轴做二次拟合）时间轴被压扁，
    // 算出凭空的甩动速度 —— 真机表现就是**松手后内容自己跳一段**。
    uint32_t touchTimeBase = 0;
    // 触摸诊断日志要用的「这根指针上一次事件」（算 dt 与位移）。4 槽环形缓冲，
    // 多指交替推进时也找得到同一根手指。
    struct TouchLogSample {
        bool used = false;
        uint32_t id = 0;
        uint32_t time = 0;
        float x = 0.f;
        float y = 0.f;
    };
    TouchLogSample touchLogSamples[4] = {};
    int touchLogNext = 0;
    // 这台机器上见过多少次「同一毫秒内两条移动事件」（上面那个 bug 的现场特征）。
    int touchSameTickMoveCount = 0;
    // 笔悬停（没有 DOWN 的 WM_POINTERUPDATE）的计数 + 日志节流状态。
    int touchHoverCount = 0;
    int touchHoverLogCount = 0;
    uint32_t touchHoverLastId = 0;
    // 输入诊断（点不动/点击来源定位用）：鼠标按键、按键、滚轮事件的日志条数上限。
    int mouseLogCount = 0;
    int keyLogCount = 0;
    int wheelLogCount = 0;
    // 当前按下的指针集合 + 本次手势的摘要。
    //
    // 为什么需要「最大同时触点数」：真机反馈「双指缩放的时候列表也跟着滚」时必须能一眼
    // 区分两种情形 ——(a) 宿主/系统只送来了一根手指（maxSim=1，那是触摸屏驱动/系统层面
    // 的事），还是 (b) 两根手指都到了、但其中一根落在列表上把列表拖走了（maxSim=2，
    // 那是 Compose 的语义：落在滚动区上的手指就该滚动它）。
    std::vector<uint32_t> touchActiveIds;
    struct TouchGestureSummary {
        bool active = false;
        uint32_t firstId = 0;
        uint32_t startTime = 0;
        uint32_t lastTime = 0;
        float startX = 0.f;
        float startY = 0.f;
        // 第一根手指的**最新位置**（摘要里的"终点/位移"用它）。
        //
        // ⚠ 不能在写摘要时再去 4 槽样本环里查第一根手指：多指长手势里那个槽早被别的
        // 指针覆盖了，查不到就退回"当前事件"的坐标 —— v0.5.7 真机日志里就出现过
        // 「id=6686 的终点写成了 6687 的坐标」。这里直接跟着事件更新，跟环的容量无关。
        float lastX = 0.f;
        float lastY = 0.f;
        int events = 0;
        int moves = 0;
        int maxSimultaneous = 0;
    };
    TouchGestureSummary touchGesture;
    // false = 系统标题栏（Compose JVM 桌面 Window() 的默认形态：NC 全归 OS 管）；
    // true = 无边框自绘 CSD（对应 JVM 的 undecorated = true）。
    bool undecorated = false;
    /**
     * place_cascaded / place_aligned 刚用物理像素定好位置后置 true：
     * 紧随的 WM_DPICHANGED 只吃建议尺寸、保留我们的坐标。
     * 用户拖窗跨屏时必须为 false，否则会拒绝系统建议点（v0.5.45 真机：
     * suggested=652,-803 被错误改成 applied=301,-1044）。
     */
    bool keepPlacementOnDpiChange = false;
    // 光标形状（0=箭头 1=手 2=文本I型 3=十字），由 Compose 的 PointerIcon 驱动。
    int cursorKind = 0;

    // ---- 窗口 API（位置/置顶/全屏/可缩放/任务栏进度）----
    //
    // 这些状态都在宿主侧记账：读的时候优先看 Win32 的真状态（WS_EX_TOPMOST 等），
    // 但像"是否在无边框全屏"这种 Win32 没有对应位的，就用这里的标志。
    bool alwaysOnTop = false;
    bool resizable = true;
    bool fullscreen = false;
    // 进全屏前的样式/位置，退出时原样还原（AWT 的 WindowPlacement 也是这么干的）。
    LONG_PTR fullscreenSavedStyle = 0;
    LONG_PTR fullscreenSavedExStyle = 0;
    WINDOWPLACEMENT fullscreenSavedPlacement = {};
    // ITaskbarList3：惰性创建（没有 shell 时保持 nullptr，只尝试一次）。
    ITaskbarList3* taskbar = nullptr;
    bool taskbarCreateAttempted = false;
    int32_t taskbarProgressState = 0;   // TBPF_NOPROGRESS
    double taskbarProgressValue = 0.0;

    // ---- OLE 拖放（接收侧）----
    //
    // 注册给 RegisterDragDrop 的 IDropTarget（引用计数由它自己管）。
    IDropTarget* dropTarget = nullptr;
    bool oleInitialized = false;
    // Kotlin 侧（Compose 的 dragAndDropTarget）对**当前位置**的判定：true = 有控件
    // 愿意接收，OLE 的 *pdwEffect 就回 DROPEFFECT_COPY，光标显示「可放下」。
    //
    // 为什么要这个标志：DragEnter 必须在**同步**返回里给出 effect，而那会儿 Kotlin
    // 还没跑（OLE 是在消息循环内部直接调我们的 COM 方法）。AWT 的 DropTargetListener
    // 能同步问 Compose（它的事件在 EDT 上同步回调），我们做不到 —— 于是第一帧先
    // 乐观接受（有文件/文本就回 COPY），Kotlin 处理完事件后用这个标志纠正后续
    // DragOver/Drop 的 effect。用户可见差别：光标最多晚一次 DragOver 才变成「禁止」。
    bool dropAccepted = false;
    // 最近一次回给 OLE 的 effect（自检断言「Compose 拒绝时宿主有没有把光标改成禁止」用）。
    DWORD lastDropEffect = DROPEFFECT_NONE;
    // 本次拖放会话的负载（ENTER 时解析一次），以及和 drag 事件严格 1:1 的 FIFO。
    std::string dragSessionFiles;   // '\n' 分隔的 UTF-8 路径
    std::string dragSessionText;    // UTF-8 文本
    std::vector<std::string> dragFiles;
    std::vector<std::string> dragTexts;
    int dragLogCount = 0;

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

/**
 * OLE 拖放目标（IDropTarget）太长了，定义在文件末尾；这里只前向声明。
 * 注意必须是**文件作用域**的 static（不能声明在匿名 namespace 里再在匿名
 * namespace 里定义 —— 那两个是不同的实体）。
 */
static void composeknInstallDropTarget(ComposeKNWin32Window* window);

/**
 * 滚轮诊断日志。
 *
 * 为什么必须记原始 delta（而不是「滚了几格」）：触控板/精确滚轮送来的是**任意小数
 * 倍**的 WHEEL_DELTA（WM_MOUSEWHEEL 的 delta 字段可以是 1..120 之间任意值，甚至
 * 小于 120 的负数），而 Kotlin 侧以前做的是 `raw.a / 120` 整数除法 —— 小于一格的
 * 增量会被截断成 0（触控板「一顿一顿、慢速完全不动」）。日志里有原始 delta 才能
 * 区分「系统没送」「送来了但被截断」「送来了方向反了」。
 */
static void logWheelEvent(ComposeKNWin32Window* window, const ComposeKNWin32Event& e) {
    if (window == nullptr || window->wheelLogCount >= 600) return;
    ++window->wheelLogCount;
    composeknLog(
        "wheel: %s delta=%d pos=%ld,%ld",
        e.b == 1 ? "横向" : "竖直",
        e.a,
        static_cast<long>(e.x),
        static_cast<long>(e.y)
    );
}

/**
 * 触摸事件的时间戳：把系统 tick 归一化成「进程内毫秒」（见 touchTimeBase 的说明）。
 * `raw == 0`（驱动没填 dwTime）时用当前消息的时间。
 */
static uint32_t touchEventTime(ComposeKNWin32Window* window, uint32_t raw) {
    const uint32_t value = (raw != 0) ? raw : static_cast<uint32_t>(GetMessageTime());
    if (window->touchTimeBase == 0) window->touchTimeBase = value;
    return value - window->touchTimeBase;
}

/** 找到某根指针**最近一次**记录的触摸样本（诊断用，也用于 CAPTURECHANGED 的位置兜底）。 */
static const ComposeKNWin32Window::TouchLogSample* findTouchSample(
    const ComposeKNWin32Window* window, uint32_t id) {
    // 必须从**最后写入的那个槽**往回找：早先写成从 0 号槽开始找，单指连续拖动时会一直
    // 命中同一根手指最老的那条样本 —— 日志里的 dt/d 于是变成了「相对按下那一刻」的累计值
    // （真机日志里 dt=7,17,26,36… 一眼就能看出来），完全没有诊断价值。
    for (int k = 0; k < 4; ++k) {
        int i = window->touchLogNext - 1 - k;
        i = ((i % 4) + 4) % 4;
        const auto& s = window->touchLogSamples[i];
        if (s.used && s.id == id) return &s;
    }
    return nullptr;
}

static bool touchIsActive(const ComposeKNWin32Window* window, uint32_t id) {
    for (uint32_t value : window->touchActiveIds) {
        if (value == id) return true;
    }
    return false;
}

static void touchAddActive(ComposeKNWin32Window* window, uint32_t id) {
    if (!touchIsActive(window, id)) window->touchActiveIds.push_back(id);
}

static void touchRemoveActive(ComposeKNWin32Window* window, uint32_t id) {
    for (size_t i = 0; i < window->touchActiveIds.size(); ++i) {
        if (window->touchActiveIds[i] == id) {
            window->touchActiveIds.erase(window->touchActiveIds.begin() + i);
            return;
        }
    }
}

static void storeTouchSample(ComposeKNWin32Window* window, uint32_t id, uint32_t time,
                             float x, float y) {
    auto& slot = window->touchLogSamples[window->touchLogNext];
    slot.used = true;
    slot.id = id;
    slot.time = time;
    slot.x = x;
    slot.y = y;
    window->touchLogNext = (window->touchLogNext + 1) % 4;
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
            // 命中码分两路算：
            //   * 无边框（CSD）窗口：边缘那圈由**我们自己**判定（见下），因为系统看不见
            //     我们自绘的标题栏/边框；
            //   * 系统标题栏窗口：交给 DefWindowProc（它会按 WS_THICKFRAME 给出缩放码）。
            // 然后**统一**做一次"不可缩放"降级 —— 这一点很关键，第一版只在 CSD 分支里
            // 挡，于是系统标题栏窗口（demo 的默认形态）拖动边缘照样能改大小（真机反馈，
            // HANDOVER §17.37）：`break` 落到 DefWindowProc，而它看的是 WS_THICKFRAME。
            LRESULT hit = HTCLIENT;
            bool decided = false;
            if (window->undecorated && window->hwnd == hwnd &&
                !IsZoomed(hwnd) && !IsIconic(hwnd)) {
                LONG gx = GET_X_LPARAM(lParam);
                LONG gy = GET_Y_LPARAM(lParam);
                RECT r;
                if (GetWindowRect(hwnd, &r)) {
                    int m = edgeMargin(window);
                    // Overshoot guard: min window dims bigger than 2*m handled by WM_GETMINMAXINFO
                    bool leftEdge = gx < r.left + m;
                    bool rightEdge = gx > r.right - m;
                    bool topEdge = gy < r.top + m;
                    bool bottomEdge = gy > r.bottom - m;
                    if (topEdge) {
                        hit = leftEdge ? HTTOPLEFT : (rightEdge ? HTTOPRIGHT : HTTOP);
                        decided = true;
                    } else if (bottomEdge) {
                        hit = leftEdge ? HTBOTTOMLEFT : (rightEdge ? HTBOTTOMRIGHT : HTBOTTOM);
                        decided = true;
                    } else if (leftEdge) {
                        hit = HTLEFT;
                        decided = true;
                    } else if (rightEdge) {
                        hit = HTRIGHT;
                        decided = true;
                    }
                }
            }
            if (!decided) {
                hit = DefWindowProcW(hwnd, WM_NCHITTEST, wParam, lParam);
            }
            if (!window->resizable) {
                switch (hit) {
                    case HTLEFT:
                    case HTRIGHT:
                    case HTTOP:
                    case HTBOTTOM:
                    case HTTOPLEFT:
                    case HTTOPRIGHT:
                    case HTBOTTOMLEFT:
                    case HTBOTTOMRIGHT:
                        // 不可缩放：边缘不给"缩放"命中码，改回 HTBORDER（= 有边框但不
                        // 可缩放）。系统因此既不会进入缩放循环，也不会把鼠标变成缩放光标。
                        return HTBORDER;
                    default:
                        break;
                }
            }
            return hit;
        }
        case WM_DPICHANGED: {
            // 跨显示器（缩放不同）时 Windows 会发这条：
            //   1) 更新 dpi；2) 应用建议矩形（用户拖窗跨屏必须整份照做）。
            // 仅当 keepPlacementOnDpiChange（刚程序化 cascade/align）时保留当前坐标、
            // 只吃建议尺寸，避免 suggestion 把刚摆好的窗甩飞。
            const int newDpi = HIWORD(wParam);
            if (newDpi > 0) window->dpi = newDpi;
            auto* suggested = reinterpret_cast<RECT*>(lParam);
            if (suggested != nullptr) {
                RECT cur = {};
                GetWindowRect(hwnd, &cur);
                const int sugW = static_cast<int>(suggested->right - suggested->left);
                const int sugH = static_cast<int>(suggested->bottom - suggested->top);
                int x = static_cast<int>(suggested->left);
                int y = static_cast<int>(suggested->top);
                const bool keep = window->keepPlacementOnDpiChange;
                window->keepPlacementOnDpiChange = false;
                if (keep) {
                    x = static_cast<int>(cur.left);
                    y = static_cast<int>(cur.top);
                    HMONITOR monitor = MonitorFromRect(&cur, MONITOR_DEFAULTTONEAREST);
                    MONITORINFO mi = {};
                    mi.cbSize = sizeof(mi);
                    if (monitor != nullptr && GetMonitorInfoW(monitor, &mi)) {
                        if (x + sugW > mi.rcWork.right) x = mi.rcWork.right - sugW;
                        if (y + sugH > mi.rcWork.bottom) y = mi.rcWork.bottom - sugH;
                        if (x < mi.rcWork.left) x = mi.rcWork.left;
                        if (y < mi.rcWork.top) y = mi.rcWork.top;
                    }
                }
                composeknLog(
                    "win32: WM_DPICHANGED -> dpi=%d suggested=%d,%d %dx%d applied=%d,%d %dx%d keep=%d",
                    newDpi,
                    static_cast<int>(suggested->left), static_cast<int>(suggested->top),
                    sugW, sugH, x, y, sugW, sugH, keep ? 1 : 0);
                SetWindowPos(hwnd, nullptr, x, y, sugW, sugH,
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
                // ptMaxPosition 是相对**该显示器原点**的偏移，不是屏幕绝对坐标。
                // 若写成 rcWork.left/top（屏幕坐标），副屏会再叠一次显示器原点：
                //   work.top=-1440 → 实际 y=-2880，窗口飞出屏幕（真机「最大化消失」）。
                mmi->ptMaxPosition.x = mi.rcWork.left - mi.rcMonitor.left;
                mmi->ptMaxPosition.y = mi.rcWork.top - mi.rcMonitor.top;
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
            // 诊断：Compose 的 clickable 在 **KeyUp**（VK_RETURN / VK_SPACE）上会
            // `performClick()`，所以"点击来源不明"时也必须能看见键盘通道
            // （v0.5.10 真机日志里按住期间 onClick 被调 10 次，就需要这条来排除/确认）。
            // 上限 600 行：按住一个键狂抖也压不垮日志。
            if (window != nullptr && window->keyLogCount < 600) {
                ++window->keyLogCount;
                // prevDown = lParam bit30「这条消息之前那个键是否已经按下」：
                // 对 KeyDown 来说 1 就是**系统的自动重复**（按住不放），KeyUp 恒为 1。
                composeknLog("key: %s vk=0x%02X prevDown=%d",
                             e.state == 1u ? "DOWN" : "UP", vk,
                             (lParam & (1L << 30)) != 0 ? 1 : 0);
            }
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
            // 诊断：鼠标按键**从来不进日志**，于是「onClick 被调了 N 次但触摸流里只有一对
            // DOWN/UP」这种现场完全无从判断（v0.5.10 真机日志里出现过按住期间 onClick 被调
            // 10 次）。鼠标按下/抬起量很少，全记（上限 600 行防呆）。
            if (window != nullptr && window->mouseLogCount < 600) {
                ++window->mouseLogCount;
                composeknLog("mouse: %s %s pos=%ld,%ld",
                             buttonId == 272u ? "左键" : buttonId == 274u ? "右键" : "中键",
                             e.state == 1u ? "DOWN" : "UP",
                             static_cast<long>(GET_X_LPARAM(lParam)),
                             static_cast<long>(GET_Y_LPARAM(lParam)));
            }
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
            logWheelEvent(window, e);
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
            logWheelEvent(window, e);
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
            POINTER_INPUT_TYPE pointerType = PT_POINTER;
            bool havePointerType = false;
            if (message != WM_POINTERCAPTURECHANGED && g_getPointerType != nullptr) {
                if (g_getPointerType(pointerId, &pointerType)) {
                    havePointerType = true;
                    if (pointerType != PT_TOUCH && pointerType != PT_PEN) {
                        break;
                    }
                }
            }
            const char* typeName = !havePointerType ? "?"
                                 : pointerType == PT_TOUCH ? "TOUCH"
                                 : pointerType == PT_PEN   ? "PEN"
                                                           : "OTHER";
            // ------------------------------------------------------------------
            // 悬停（**没有 DOWN 的 WM_POINTERUPDATE**）不是触摸事件，必须丢掉。
            //
            // 笔悬停（离屏幕还有距离）时 Windows 会一直发 WM_POINTERUPDATE：
            // POINTER_FLAG_INRANGE 有，POINTER_FLAG_INCONTACT / DOWN 没有。这类事件
            // 以前被当成"触摸移动"送进 Compose，Kotlin 侧对没见过的 id 会建一根
            // **pressed=true** 的触点 —— 悬停期间 Compose 就以为有人按住不放
            // （点击被吞、单指被当双指、速度估计器被喂进悬停轨迹）。真机日志见 HANDOVER §17.29。
            //
            // 判据取"保守交集"：既不在我们的触点表里、又没有任何"接触"标志。
            // 触摸的 UPDATE 一定有 DOWN 在前（表里就有），所以对触摸零影响。
            // ------------------------------------------------------------------
            if (message == WM_POINTERUPDATE && !touchIsActive(window, pointerId) &&
                g_getPointerInfo != nullptr) {
                POINTER_INFO hoverInfo{};
                if (g_getPointerInfo(pointerId, &hoverInfo)) {
                    const bool inContact = (hoverInfo.pointerFlags & POINTER_FLAG_INCONTACT) != 0;
                    const bool isDownFlag = (hoverInfo.pointerFlags & POINTER_FLAG_DOWN) != 0;
                    if (!inContact && !isDownFlag) {
                        POINT hoverPt = hoverInfo.ptPixelLocation;
                        ScreenToClient(hwnd, &hoverPt);
                        ++window->touchHoverCount;
                        // 悬停轨迹可能几百条/秒：只记前几条 + 换指针时的第一条。
                        if (window->touchHoverLogCount < 6 ||
                            window->touchHoverLastId != pointerId) {
                            ++window->touchHoverLogCount;
                            window->touchHoverLastId = pointerId;
                            composeknLog(
                                "pointer: 悬停（不是触摸，已丢弃）id=%u type=%s flags=0x%lX "
                                "pos=%ld,%ld 累计=%d",
                                pointerId, typeName,
                                static_cast<unsigned long>(hoverInfo.pointerFlags),
                                static_cast<long>(hoverPt.x), static_cast<long>(hoverPt.y),
                                window->touchHoverCount);
                        }
                        return 0;  // 处理掉了，但不产生任何 Compose 事件
                    }
                }
            }
            ComposeKNWin32Event e{};
            e.button = pointerId;
            e.modifiers = queryCurrentModifiers();
            if (message == WM_POINTERCAPTURECHANGED) {
                // 触点被系统收走（手势识别、其它窗口抢焦点…）：必须补一个抬起，
                // 否则 Kotlin 侧的活动触点表会永远留着这根手指，后续滚动就"卡死"了。
                //
                // 位置/时间也不能省：Compose 的 Release 事件要带**该触点的最终位置**，
                // 补成 (0,0) 会让「上一次位置 → 0,0」变成一次横跨半个窗口的位移。
                // 文档保证此时 GetPointerInfo 仍返回收走前的数据；拿不到就退回最后一次
                // 记录到的位置。
                POINT pt{};
                bool got = false;
                uint32_t rawTime = 0;
                if (g_getPointerInfo != nullptr) {
                    POINTER_INFO info{};
                    if (g_getPointerInfo(pointerId, &info)) {
                        pt = info.ptPixelLocation;
                        rawTime = info.dwTime;
                        got = true;
                    }
                }
                if (got) {
                    ScreenToClient(hwnd, &pt);
                } else if (const auto* last = findTouchSample(window, pointerId)) {
                    pt.x = static_cast<LONG>(last->x);
                    pt.y = static_cast<LONG>(last->y);
                    rawTime = last->time + window->touchTimeBase;
                }
                e.type = COMPOSEKN_WIN32_EVENT_TOUCH_UP;
                e.state = 0;
                e.x = static_cast<float>(pt.x);
                e.y = static_cast<float>(pt.y);
                e.a = static_cast<int32_t>(touchEventTime(window, rawTime));
            } else {
                // 坐标语义：lParam 到底是客户区还是屏幕坐标，各版本文档说法不一致，
                // 这里直接用 GetPointerInfo 的**屏幕**坐标再转客户区，避免把触摸位置搞错。
                POINT pt{};
                bool got = false;
                uint32_t rawTime = 0;
                // 有的驱动把「首次接触」也放在 WM_POINTERUPDATE 里（带 POINTER_FLAG_DOWN），
                // 而不是发 WM_POINTERDOWN。这种必须当成 DOWN 建触点，否则整段接触都收不到
                // （配合 Kotlin 侧「没有 DOWN 的 MOVE 一律丢弃」会把整根手指吞掉）。
                bool updateCarriesDown = false;
                if (g_getPointerInfo != nullptr) {
                    POINTER_INFO info{};
                    if (g_getPointerInfo(pointerId, &info)) {
                        pt = info.ptPixelLocation;
                        // dwTime = 「消息收到时的系统 tick」（毫秒）；0 时退回消息时间。
                        rawTime = info.dwTime;
                        updateCarriesDown = (info.pointerFlags & POINTER_FLAG_DOWN) != 0;
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
                       : (updateCarriesDown && !touchIsActive(window, pointerId))
                             ? COMPOSEKN_WIN32_EVENT_TOUCH_DOWN
                             : COMPOSEKN_WIN32_EVENT_TOUCH_MOVE;
                e.state = (message == WM_POINTERUP) ? 0u : 1u;
                e.x = static_cast<float>(pt.x);
                e.y = static_cast<float>(pt.y);
                // 真实事件时间（进程内毫秒）—— Compose 用它做速度估计，见 touchTimeBase。
                e.a = static_cast<int32_t>(touchEventTime(window, rawTime));
            }
            pushEvent(window, e);
            const uint32_t eventTime = static_cast<uint32_t>(e.a);
            const char* phase = e.type == COMPOSEKN_WIN32_EVENT_TOUCH_DOWN ? "DOWN"
                              : e.type == COMPOSEKN_WIN32_EVENT_TOUCH_UP   ? "UP"
                                                                          : "MOVE";
            if (message == WM_POINTERCAPTURECHANGED) phase = "CAPTURE-LOST";
            const auto* prev = findTouchSample(window, pointerId);
            if (prev != nullptr && e.type == COMPOSEKN_WIN32_EVENT_TOUCH_MOVE &&
                eventTime == prev->time && (e.x != prev->x || e.y != prev->y)) {
                // 诊断核心：同一毫秒里两条**移动**事件 —— 速度估计器的时间轴被压扁的现场。
                // 只在次数变化时记（以前把累计值写在"手势结束"里，每个手势结束都重复打一遍
                // 同一个数字，日志里刷了几百行一模一样的 "累计 … 1 次"）。
                ++window->touchSameTickMoveCount;
                if (window->touchSameTickMoveCount <= 3) {
                    composeknLog(
                        "touch: 同一毫秒内两条移动事件（第 %d 次）id=%u t=%ums "
                        "pos=(%.0f,%.0f)←(%.0f,%.0f)（修复后应为 0）",
                        window->touchSameTickMoveCount, pointerId, eventTime,
                        static_cast<double>(e.x), static_cast<double>(e.y),
                        static_cast<double>(prev->x), static_cast<double>(prev->y));
                }
            }

            // ---- 触点表 + 本次手势摘要 ----
            const bool isDown = e.type == COMPOSEKN_WIN32_EVENT_TOUCH_DOWN;
            const bool isUp = e.type == COMPOSEKN_WIN32_EVENT_TOUCH_UP;
            if (!window->touchGesture.active) {
                window->touchGesture.active = true;
                window->touchGesture.firstId = pointerId;
                window->touchGesture.startTime = eventTime;
                window->touchGesture.startX = e.x;
                window->touchGesture.startY = e.y;
                window->touchGesture.lastX = e.x;
                window->touchGesture.lastY = e.y;
                window->touchGesture.events = 0;
                window->touchGesture.moves = 0;
                window->touchGesture.maxSimultaneous = 0;
            }
            if (isDown) touchAddActive(window, pointerId);
            window->touchGesture.lastTime = eventTime;
            ++window->touchGesture.events;
            if (e.type == COMPOSEKN_WIN32_EVENT_TOUCH_MOVE) ++window->touchGesture.moves;
            if (pointerId == window->touchGesture.firstId) {
                // 摘要里的"终点/位移"永远跟第一根手指的最新位置走（和样本环容量无关）
                window->touchGesture.lastX = e.x;
                window->touchGesture.lastY = e.y;
            }
            if (static_cast<int>(window->touchActiveIds.size()) > window->touchGesture.maxSimultaneous) {
                window->touchGesture.maxSimultaneous = static_cast<int>(window->touchActiveIds.size());
            }
            const bool multiTouch = window->touchActiveIds.size() >= 2;
            if (isUp || message == WM_POINTERCAPTURECHANGED) touchRemoveActive(window, pointerId);
            const bool gestureEnded = window->touchActiveIds.empty();

            // 日志策略：DOWN/UP/CAPTURE-LOST/手势摘要**永远**记（行数少、信息密度高）；
            // MOVE 只记「多指期间」和「每根手指的前 3 条」—— 单指长拖的几百条没有诊断
            // 价值，反而会把日志上限吃光（v0.5.6 就是 400 行上限在 15 秒内被吃光，
            // 真正要看的捏合根本没记上）。
            const bool logDetail =
                e.type != COMPOSEKN_WIN32_EVENT_TOUCH_MOVE || multiTouch ||
                window->touchGesture.moves <= 3;
            if (logDetail && window->touchLogCount < 4000) {
                ++window->touchLogCount;
                if (prev != nullptr) {
                    composeknLog(
                        "touch: %-12s id=%u t=%ums dt=%ums d=(%+.0f,%+.0f) pos=%.0f,%.0f active=%zu type=%s",
                        phase, pointerId, eventTime, eventTime - prev->time,
                        static_cast<double>(e.x - prev->x), static_cast<double>(e.y - prev->y),
                        static_cast<double>(e.x), static_cast<double>(e.y),
                        window->touchActiveIds.size(), typeName);
                } else {
                    composeknLog("touch: %-12s id=%u t=%ums pos=%.0f,%.0f active=%zu type=%s",
                                 phase, pointerId, eventTime,
                                 static_cast<double>(e.x), static_cast<double>(e.y),
                                 window->touchActiveIds.size(), typeName);
                }
            }
            if (gestureEnded) {
                // 摘要行：一次手势的形状 + **最大同时触点数**（判断"双指到底有没有两根
                // 手指同时到达"的关键；maxSim=1 说明只有一根手指被系统送进来）。
                composeknLog(
                    "touch: 手势结束 id=%u 时长=%ums 事件=%d 移动=%d 起点=%.0f,%.0f 终点=%.0f,%.0f "
                    "位移=(%+.0f,%+.0f) 最大同时触点数=%d",
                    window->touchGesture.firstId,
                    window->touchGesture.lastTime - window->touchGesture.startTime,
                    window->touchGesture.events, window->touchGesture.moves,
                    static_cast<double>(window->touchGesture.startX),
                    static_cast<double>(window->touchGesture.startY),
                    static_cast<double>(window->touchGesture.lastX),
                    static_cast<double>(window->touchGesture.lastY),
                    static_cast<double>(window->touchGesture.lastX - window->touchGesture.startX),
                    static_cast<double>(window->touchGesture.lastY - window->touchGesture.startY),
                    window->touchGesture.maxSimultaneous);
                window->touchGesture = ComposeKNWin32Window::TouchGestureSummary{};
            }
            storeTouchSample(window, pointerId, eventTime, e.x, e.y);
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
        case WM_COMMAND: {
            // 菜单命令：HIWORD==0；加速键 HIWORD==1。v1 只接菜单栏点击。
            if (window != nullptr && HIWORD(wParam) == 0) {
                ComposeKNWin32Event e{};
                e.type = COMPOSEKN_WIN32_EVENT_MENU_COMMAND;
                e.a = (int32_t)(LOWORD(wParam));
                pushEvent(window, e);
                return 0;
            }
            break;
        }
        case WM_CLOSE: {
            // 对齐 Compose Desktop / AWT 的 DO_NOTHING_ON_CLOSE：
            // 只把 CLOSE 事件推给 Kotlin（onCloseRequest），**不** DestroyWindow、
            // **不**把本窗口标成「该退了」。真正销毁发生在离开 composition /
            // 宿主主动 destroy 时。旧的 closeRequested 路径会让消息泵直接退出，
            // 多窗口下关一个窗就会把共享泵带走。
            if (window) {
                ComposeKNWin32Event e{};
                e.type = COMPOSEKN_WIN32_EVENT_CLOSE;
                pushEvent(window, e);
            }
            return 0;
        }
        case kComposeKNWakeMessage:
            // 纯粹用来唤醒 MsgWaitForMultipleObjectsEx；没有任何副作用。
            return 0;
        case WM_DESTROY: {
            // ⚠ 这里**不能** PostQuitMessage(0)：那是**整个线程**的退出标志，而一个
            // 线程上可能有多个 Compose 窗口（每个 WindowsComposeWindow 一个 HWND）。
            // 关掉第一个窗口就往线程消息队列里塞 WM_QUIT，后面新开的窗口
            // `PeekMessageW(&msg, nullptr, ...)` 会立刻看到它、0 帧就退出
            //（v0.5.18 的真机上没人碰到，是自检想开第二个窗口时炸出来的 —— HANDOVER §17.37）。
            //
            // 每个窗口自己有 `quit` 标志：设置它，本窗口的消息循环就会退出（见
            // composekn_win32_pump 里 `if (window->quit) return false;`）。
            if (window) window->quit = true;
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

    // OLE 拖放：注册成拖放目标（资源管理器拖文件进来、从别的应用拖文本进来）。
    composeknInstallDropTarget(window);

    // 注意：**不要**在窗口刚建好时用 GetDpiForWindow/WM_DPICHANGED 去"校正"尺寸 ——
    // 这时窗口还没真正落到显示器上，dpiScaleOf(window) 会返回 1.0，于是把刚算好的
    // 2 倍缩放又抹掉（真机日志：dp=1100x760 -> px=2200x1520，紧接着又"校正"回 1100x760）。
    // 多显示器/不同缩放的场景交给 WM_DPICHANGED（窗口被拖到别的屏时系统会通知）。
    window->dpi = queryWindowDpi(window->hwnd);
    // 不在这里 ShowWindow：声明式多窗口会先设位置/尺寸再显示，否则会出现
    // 「先在屏幕角落闪一下再瞬移到居中」的跳动（CW_USEDEFAULT → centerOnScreen）。
    // 调用方在 apply 完几何后调 composekn_win32_show(..., SW_SHOW)。
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
    // 防 Kotlin 漏调：拆 HWND 前先丢掉本窗 GL / Vulkan。
    composekn_win32_vk_destroy(window);
    composekn_win32_gl_destroy(window);
    if (window->hwnd != nullptr && IsWindow(window->hwnd)) {
        // 菜单栏在 DestroyWindow 前摘掉并销毁（窗口不会自动 DestroyMenu）。
        if (window->menuBar != nullptr) {
            SetMenu(window->hwnd, nullptr);
            DestroyMenu(window->menuBar);
            window->menuBar = nullptr;
        }
        // 先摘掉拖放目标再拆窗口：OLE 侧还握着一个指针（RevokeDragDrop 是唯一
        // 合法的注销点，必须在 DestroyWindow 之前）。
        if (window->dropTarget != nullptr) {
            RevokeDragDrop(window->hwnd);
        }
        // 清 USERDATA，避免 DestroyWindow 派发的尾随消息打到即将 delete 的 this。
        SetWindowLongPtrW(window->hwnd, GWLP_USERDATA, 0);
        DestroyWindow(window->hwnd);
        window->hwnd = nullptr;
    } else if (window->menuBar != nullptr) {
        DestroyMenu(window->menuBar);
        window->menuBar = nullptr;
    }
    if (window->dropTarget != nullptr) {
        window->dropTarget->Release();
        window->dropTarget = nullptr;
    }
    if (window->oleInitialized) {
        // 线程级 OLE 引用计数：关一个窗口不能把兄弟窗口的 IDropTarget 拆掉。
        const LONG left = InterlockedDecrement(&g_oleInitCount);
        if (left == 0) {
            OleUninitialize();
        } else if (left < 0) {
            // 防御：计数被弄坏时不继续往下减，避免对称性崩掉。
            InterlockedExchange(&g_oleInitCount, 0);
            composeknLog("drag: OLE 引用计数异常（destroy 时 < 0），已钳到 0");
        }
        window->oleInitialized = false;
    }
    delete window;
}

extern "C" void composekn_win32_begin_move(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    ReleaseCapture();
    // DefWindowProc(HTCAPTION) runs a nested move loop and consumes the button-up as
    // WM_NCLBUTTONUP — our WM_LBUTTONUP handler never runs. Compose therefore keeps
    // primaryPressed=true after every title-bar drag (including the restore-from-
    // maximized drag Windows does on first move). The next client click only clears
    // that stuck Press; the click after that finally reaches buttons. Synthesize a
    // client button-up when the native drag ends and the physical button is up.
    SendMessageW(window->hwnd, WM_NCLBUTTONDOWN, HTCAPTION, 0);
    if ((GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0) {
        return;
    }
    POINT pt{};
    GetCursorPos(&pt);
    ScreenToClient(window->hwnd, &pt);
    ComposeKNWin32Event e{};
    e.type = COMPOSEKN_WIN32_EVENT_MOUSE_BUTTON;
    e.x = static_cast<float>(pt.x);
    e.y = static_cast<float>(pt.y);
    e.button = 272u;  // Left (matches WM_LBUTTON* mapping)
    e.state = 0u;       // up
    e.modifiers = queryCurrentModifiers();
    pushEvent(window, e);
    if (window->mouseLogCount < 600) {
        ++window->mouseLogCount;
        composeknLog("mouse: 左键 UP(synth after beginMove) pos=%ld,%ld",
                     static_cast<long>(pt.x), static_cast<long>(pt.y));
    }
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
    // WM_CLOSE 不再置 closeRequested（DO_NOTHING_ON_CLOSE）；只看 quit（WM_DESTROY）。
    return !window->quit;
}

extern "C" bool composekn_win32_pump_thread(void) {
    MSG msg;
    while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) {
            return false;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return true;
}

extern "C" bool composekn_win32_wait_message(ComposeKNWin32Window* window, int32_t timeout_ms) {
    if (window == nullptr) return false;
    if (window->quit) return false;
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
    return !window->quit;
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

// ---------------------------------------------------------------------------
// 原生菜单栏（HMENU）
// ---------------------------------------------------------------------------

static std::wstring utf8ToWideMenu(const char* utf8) {
    if (utf8 == nullptr || utf8[0] == '\0') return std::wstring();
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, nullptr, 0);
    if (wlen <= 0) return std::wstring();
    std::wstring wide(static_cast<size_t>(wlen - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wide.data(), wlen);
    return wide;
}

extern "C" void* composekn_win32_menu_create(bool popup) {
    HMENU menu = popup ? CreatePopupMenu() : CreateMenu();
    return reinterpret_cast<void*>(menu);
}

extern "C" void composekn_win32_menu_destroy(void* hmenu) {
    if (hmenu == nullptr) return;
    DestroyMenu(reinterpret_cast<HMENU>(hmenu));
}

extern "C" bool composekn_win32_menu_append_string(
    void* parent, uint32_t id, const char* utf8, bool enabled
) {
    if (parent == nullptr) return false;
    const std::wstring wide = utf8ToWideMenu(utf8);
    UINT flags = MF_STRING | (enabled ? MF_ENABLED : (MF_GRAYED | MF_DISABLED));
    return AppendMenuW(reinterpret_cast<HMENU>(parent), flags, id, wide.c_str()) != FALSE;
}

extern "C" bool composekn_win32_menu_append_separator(void* parent) {
    if (parent == nullptr) return false;
    return AppendMenuW(reinterpret_cast<HMENU>(parent), MF_SEPARATOR, 0, nullptr) != FALSE;
}

extern "C" bool composekn_win32_menu_append_popup(
    void* parent, const char* utf8, void* child, bool enabled
) {
    if (parent == nullptr || child == nullptr) return false;
    const std::wstring wide = utf8ToWideMenu(utf8);
    UINT flags = MF_POPUP | MF_STRING | (enabled ? MF_ENABLED : (MF_GRAYED | MF_DISABLED));
    // 64 位上 MF_POPUP 的「id」参数实际是子 HMENU 句柄。
    return AppendMenuW(
        reinterpret_cast<HMENU>(parent),
        flags,
        reinterpret_cast<UINT_PTR>(child),
        wide.c_str()
    ) != FALSE;
}

extern "C" void composekn_win32_menu_set(ComposeKNWin32Window* window, void* hmenu) {
    if (window == nullptr || window->hwnd == nullptr) return;
    HMENU next = reinterpret_cast<HMENU>(hmenu);
    HMENU prev = window->menuBar;
    if (prev == next) {
        DrawMenuBar(window->hwnd);
        return;
    }
    SetMenu(window->hwnd, next);
    DrawMenuBar(window->hwnd);
    window->menuBar = next;
    // 接管所有权：旧菜单（含 MF_POPUP 挂上的子菜单）一并销毁。
    if (prev != nullptr) {
        DestroyMenu(prev);
    }
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

/**
 * 读系统 ClearType 方向，映射到 SkPixelGeometry 序号。
 * 不开字体平滑 / 非 ClearType → UNKNOWN(0)，Skia 走灰度 AA。
 */
extern "C" int32_t composekn_win32_pixel_geometry(void) {
    BOOL smoothing = FALSE;
    if (!SystemParametersInfoW(SPI_GETFONTSMOOTHING, 0, &smoothing, 0) || !smoothing) {
        return 0; // UNKNOWN
    }
    UINT type = 0;
    if (!SystemParametersInfoW(SPI_GETFONTSMOOTHINGTYPE, 0, &type, 0) ||
        type != FE_FONTSMOOTHINGCLEARTYPE) {
        return 0; // 标准灰度平滑 → UNKNOWN（Skia 用 ANTI_ALIAS）
    }
    UINT orientation = FE_FONTSMOOTHINGORIENTATIONRGB;
    SystemParametersInfoW(SPI_GETFONTSMOOTHINGORIENTATION, 0, &orientation, 0);
    // SkPixelGeometry: UNKNOWN=0 RGB_H=1 BGR_H=2 RGB_V=3 BGR_V=4
    // Windows 几乎都是横向；BGR vs RGB 由 ORIENTATION 决定。
    if (orientation == FE_FONTSMOOTHINGORIENTATIONBGR) {
        return 2; // BGR_H
    }
    return 1; // RGB_H
}

extern "C" void composekn_win32_show(ComposeKNWin32Window* window, int cmd) {
    if (window == nullptr || window->hwnd == nullptr) return;
    ShowWindow(window->hwnd, cmd);
    // SW_HIDE(=0) 不要 Invalidate：Hide 本身会同步派 WM_SIZE（触发 fireRenderTick），
    // 再 Invalidate 容易在关窗路径上嵌套进 render（SingleComposeSceneRenderingScope
    // 的 check(!isRendering) → IllegalStateException: Check failed）。
    if (cmd != SW_HIDE) {
        InvalidateRect(window->hwnd, nullptr, FALSE);
    }
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

// ---------------------------------------------------------------------------
// 窗口 API：位置 / 置顶 / 全屏 / 可缩放 / 任务栏进度
//
// 对齐的点：上游 Compose Desktop 的 `WindowState` / `WindowPlacement` 提供
// `position`（WindowPosition）、`size`、`isMaximized`、`isFullscreen`，`Window` 参数里
// 有 `alwaysOnTop` / `resizable`。那些类型是 JVM/AWT 就地的（内部裹着 java.awt.Window），
// 我们的原生宿主用不了，所以这里在 Win32 侧实现**同一套语义**，Kotlin 侧提供同名/同义的
// 访问器（见 WindowsComposeWindow）。
//
// 约定：所有位置/尺寸对外都是**逻辑像素（dp）**，客户区尺寸 = Compose 场景尺寸。
// ---------------------------------------------------------------------------

extern "C" bool composekn_win32_is_always_on_top(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    return (GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE) & WS_EX_TOPMOST) != 0;
}

extern "C" void composekn_win32_set_always_on_top(ComposeKNWin32Window* window, bool on_top) {
    if (window == nullptr || window->hwnd == nullptr) return;
    window->alwaysOnTop = on_top;
    SetWindowPos(
        window->hwnd, on_top ? HWND_TOPMOST : HWND_NOTOPMOST,
        0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE
    );
    composeknLog("window: alwaysOnTop=%d", on_top ? 1 : 0);
}

extern "C" void composekn_win32_set_enabled(ComposeKNWin32Window* window, bool enabled) {
    if (window == nullptr || window->hwnd == nullptr) return;
    EnableWindow(window->hwnd, enabled ? TRUE : FALSE);
}

extern "C" bool composekn_win32_is_enabled(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    return IsWindowEnabled(window->hwnd) != FALSE;
}

extern "C" bool composekn_win32_is_resizable(ComposeKNWin32Window* window) {
    return window != nullptr && window->resizable;
}

extern "C" void composekn_win32_set_resizable(ComposeKNWin32Window* window, bool resizable) {
    if (window == nullptr || window->hwnd == nullptr) return;
    if (window->resizable == resizable) return;   // 幂等：别白折腾样式
    window->resizable = resizable;

    // 对齐 AWT/JDK 的 `Window.setResizable(false)`：它也是把 WS_THICKFRAME 和
    // WS_MAXIMIZEBOX 一起摘掉 —— 只挡命中测试的话，别的缩放途径（Win+方向键、
    // Aero Snap 之类）仍然能改窗口大小。
    const LONG_PTR style = GetWindowLongPtrW(window->hwnd, GWL_STYLE);
    const LONG_PTR newStyle = resizable
        ? (style | WS_THICKFRAME | WS_MAXIMIZEBOX)
        : (style & ~static_cast<LONG_PTR>(WS_THICKFRAME | WS_MAXIMIZEBOX));
    SetWindowLongPtrW(window->hwnd, GWL_STYLE, newStyle);

    // 样式改了，非客户区厚度就变了（WS_THICKFRAME 带着一圈缩放边框）。如果用同一个
    // **窗口**尺寸，客户区会跟着变 —— 内容会"跳一下"。这里按"保持客户区尺寸"重算窗口
    // 尺寸，用户只会看到边框变细/变粗，内容纹丝不动。
    int widthPx = window->width;
    int heightPx = window->height;
    if (!window->undecorated) {
        RECT rect = {0, 0, widthPx, heightPx};
        const LONG_PTR exStyle = GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE);
        if (AdjustWindowRectEx(&rect, static_cast<DWORD>(newStyle), FALSE, static_cast<DWORD>(exStyle))) {
            widthPx = rect.right - rect.left;
            heightPx = rect.bottom - rect.top;
        }
    }
    SetWindowPos(
        window->hwnd, nullptr, 0, 0, widthPx, heightPx,
        SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED
    );
    composeknLog(
        "window: resizable=%d（WS_THICKFRAME=%s，窗口 %dx%d 物理 -> 客户区保持 %dx%d）",
        resizable ? 1 : 0,
        (newStyle & WS_THICKFRAME) != 0 ? "有" : "无",
        widthPx, heightPx, window->width, window->height
    );
}

extern "C" bool composekn_win32_is_fullscreen(ComposeKNWin32Window* window) {
    return window != nullptr && window->fullscreen;
}

extern "C" bool composekn_win32_set_fullscreen(ComposeKNWin32Window* window, bool fullscreen) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    if (fullscreen == window->fullscreen) return true;
    if (fullscreen) {
        // 记下当前样式/位置，把窗口样式收成"没有非客户区"的 popup，再铺满窗口所在显示器。
        window->fullscreenSavedStyle = GetWindowLongPtrW(window->hwnd, GWL_STYLE);
        window->fullscreenSavedExStyle = GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE);
        window->fullscreenSavedPlacement = {};
        window->fullscreenSavedPlacement.length = sizeof(WINDOWPLACEMENT);
        GetWindowPlacement(window->hwnd, &window->fullscreenSavedPlacement);

        MONITORINFO monitor = {};
        monitor.cbSize = sizeof(monitor);
        if (!GetMonitorInfoW(MonitorFromWindow(window->hwnd, MONITOR_DEFAULTTONEAREST), &monitor)) {
            composeknLog("window: 全屏失败（拿不到显示器信息）");
            return false;
        }
        LONG_PTR style = window->fullscreenSavedStyle;
        style &= ~static_cast<LONG_PTR>(WS_CAPTION | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX | WS_SYSMENU);
        style |= WS_POPUP;
        SetWindowLongPtrW(window->hwnd, GWL_STYLE, style);
        SetWindowPos(
            window->hwnd, HWND_TOP,
            monitor.rcMonitor.left, monitor.rcMonitor.top,
            monitor.rcMonitor.right - monitor.rcMonitor.left,
            monitor.rcMonitor.bottom - monitor.rcMonitor.top,
            SWP_FRAMECHANGED | SWP_SHOWWINDOW
        );
        window->fullscreen = true;
        composeknLog(
            "window: 进入全屏 -> %ldx%ld @%ld,%ld（显示器 %ld,%ld）",
            (long)(monitor.rcMonitor.right - monitor.rcMonitor.left),
            (long)(monitor.rcMonitor.bottom - monitor.rcMonitor.top),
            (long)monitor.rcMonitor.left, (long)monitor.rcMonitor.top,
            (long)monitor.rcMonitor.right, (long)monitor.rcMonitor.bottom
        );
    } else {
        SetWindowLongPtrW(window->hwnd, GWL_STYLE, window->fullscreenSavedStyle);
        SetWindowLongPtrW(window->hwnd, GWL_EXSTYLE, window->fullscreenSavedExStyle);
        if (window->fullscreenSavedPlacement.length == sizeof(WINDOWPLACEMENT)) {
            SetWindowPlacement(window->hwnd, &window->fullscreenSavedPlacement);
        }
        SetWindowPos(
            window->hwnd, nullptr, 0, 0, 0, 0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED
        );
        window->fullscreen = false;
        composeknLog("window: 退出全屏");
    }
    return true;
}

/**
 * 当前窗口几何：out[0..1] = 窗口左上角（屏幕坐标，dp）；out[2..3] = **客户区**大小（dp）。
 *
 * 为什么位置用"窗口左上角"而尺寸用"客户区"：和上游 `WindowState.position/size` 的用法一致
 * （位置是窗口在屏幕上的位置，尺寸是内容区大小）。客户区尺寸就是 Compose 场景尺寸。
 */
extern "C" void composekn_win32_window_frame(ComposeKNWin32Window* window, int32_t* out) {
    if (out == nullptr) return;
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    out[3] = 0;
    if (window == nullptr || window->hwnd == nullptr) return;
    const double scale = dpiScaleOf(window);
    RECT rect = {};
    if (GetWindowRect(window->hwnd, &rect)) {
        out[0] = static_cast<int32_t>(rect.left / scale >= 0 ? rect.left / scale + 0.5 : rect.left / scale - 0.5);
        out[1] = static_cast<int32_t>(rect.top / scale >= 0 ? rect.top / scale + 0.5 : rect.top / scale - 0.5);
    }
    out[2] = static_cast<int32_t>(window->width / scale + 0.5);
    out[3] = static_cast<int32_t>(window->height / scale + 0.5);
}

extern "C" void composekn_win32_set_window_position(ComposeKNWin32Window* window, int32_t x_dp, int32_t y_dp) {
    if (window == nullptr || window->hwnd == nullptr) return;
    const double scale = dpiScaleOf(window);
    SetWindowPos(
        window->hwnd, nullptr,
        static_cast<int>(x_dp * scale + (x_dp >= 0 ? 0.5 : -0.5)),
        static_cast<int>(y_dp * scale + (y_dp >= 0 ? 0.5 : -0.5)),
        0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
    );
    composeknLog("window: 位置 -> %d,%d dp", x_dp, y_dp);
}

extern "C" void composekn_win32_set_client_size(ComposeKNWin32Window* window, int32_t w_dp, int32_t h_dp) {
    if (window == nullptr || window->hwnd == nullptr) return;
    if (w_dp < 1) w_dp = 1;
    if (h_dp < 1) h_dp = 1;
    const double scale = dpiScaleOf(window);
    int width = static_cast<int>(w_dp * scale + 0.5);
    int height = static_cast<int>(h_dp * scale + 0.5);
    if (!window->undecorated) {
        // 系统标题栏的窗口要把非客户区算进去，SetWindowPos 给的是**窗口**尺寸。
        // 无边框（CSD）窗口的客户区 == 窗口矩形，所以不用调 —— 调了反而会多出一圈。
        RECT rect = {0, 0, width, height};
        const LONG_PTR style = GetWindowLongPtrW(window->hwnd, GWL_STYLE);
        const LONG_PTR exStyle = GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE);
        if (AdjustWindowRectEx(&rect, static_cast<DWORD>(style), FALSE, static_cast<DWORD>(exStyle))) {
            width = rect.right - rect.left;
            height = rect.bottom - rect.top;
        }
    }
    SetWindowPos(
        window->hwnd, nullptr, 0, 0, width, height,
        SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE
    );
    composeknLog("window: 客户区 -> %dx%d dp（物理 %dx%d）", w_dp, h_dp, width, height);
}

/** 任务栏进度能不能用（没有 shell/任务栏时为 false）。 */
extern "C" bool composekn_win32_taskbar_supported(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    if (window->taskbar != nullptr) return true;
    if (window->taskbarCreateAttempted) return false;
    window->taskbarCreateAttempted = true;
    ITaskbarList3* list = nullptr;
    const HRESULT hr = CoCreateInstance(
        CLSID_TaskbarList, nullptr, CLSCTX_INPROC_SERVER, IID_ITaskbarList3,
        reinterpret_cast<void**>(&list)
    );
    if (FAILED(hr) || list == nullptr) {
        composeknLog("taskbar: 不可用 hr=0x%08lX（Wine/无 shell 时正常）", (unsigned long)hr);
        return false;
    }
    if (FAILED(list->HrInit())) {
        composeknLog("taskbar: HrInit 失败");
        list->Release();
        return false;
    }
    window->taskbar = list;
    composeknLog("taskbar: 就绪（ITaskbarList3）");
    return true;
}

/**
 * 设置任务栏进度。state 用 Windows 的 TBPFLAG 值（0=无 1=不确定 2=正常 4=错误 8=暂停）。
 *
 * 返回 false = 这台机器/这个环境没有任务栏（我们**不假装成功**）。
 */
extern "C" bool composekn_win32_set_taskbar_progress(
    ComposeKNWin32Window* window, int32_t state, double completed) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    if (!composekn_win32_taskbar_supported(window)) return false;
    if (completed < 0.0) completed = 0.0;
    if (completed > 1.0) completed = 1.0;
    window->taskbarProgressState = state;
    window->taskbarProgressValue = completed;
    window->taskbar->SetProgressState(window->hwnd, static_cast<TBPFLAG>(state));
    if (state == TBPF_NORMAL || state == TBPF_ERROR || state == TBPF_PAUSED) {
        window->taskbar->SetProgressValue(
            window->hwnd,
            static_cast<ULONGLONG>(completed * 1000.0 + 0.5),
            1000
        );
    }
    return true;
}

/**
 * 主显示器**工作区**（排除任务栏的可用区域），dp：out = {x, y, w, h}。
 *
 * 给 `centerOnScreen()` 用（对齐上游 `WindowPosition.Aligned(Alignment.Center)` 的语义：
 * 居中要按"可用区域"而不是整块屏幕，否则会被任务栏顶偏）。
 * 拿不到显示器信息时返回全 0。
 */
extern "C" void composekn_win32_primary_work_area(ComposeKNWin32Window* window, int32_t* out) {
    if (out == nullptr) return;
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    out[3] = 0;
    RECT work = {};
    if (!SystemParametersInfoW(SPI_GETWORKAREA, 0, &work, 0)) {
        composeknLog("window: 拿不到工作区（SPI_GETWORKAREA 失败）");
        return;
    }
    const double scale = dpiScaleOf(window);
    out[0] = static_cast<int32_t>(work.left / scale >= 0 ? work.left / scale + 0.5 : work.left / scale - 0.5);
    out[1] = static_cast<int32_t>(work.top / scale >= 0 ? work.top / scale + 0.5 : work.top / scale - 0.5);
    out[2] = static_cast<int32_t>((work.right - work.left) / scale + 0.5);
    out[3] = static_cast<int32_t>((work.bottom - work.top) / scale + 0.5);
}

/**
 * 窗口所在显示器的工作区（多显示器 cascade / 溢出钳位用）。
 *
 * `SPI_GETWORKAREA` 只覆盖**主**显示器；副屏上的负坐标会被误判溢出并打回主屏。
 * 这里用 `MonitorFromWindow` + `rcWork`，与 Desktop `WindowLocationTracker` 按
 * GraphicsDevice 取 bounds/insets 对齐。
 */
extern "C" void composekn_win32_monitor_work_area(ComposeKNWin32Window* window, int32_t* out) {
    if (out == nullptr) return;
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    out[3] = 0;
    if (window == nullptr || window->hwnd == nullptr) return;
    HMONITOR monitor = MonitorFromWindow(window->hwnd, MONITOR_DEFAULTTONEAREST);
    if (monitor == nullptr) return;
    MONITORINFO mi = {};
    mi.cbSize = sizeof(mi);
    if (!GetMonitorInfoW(monitor, &mi)) {
        composeknLog("window: GetMonitorInfo 失败（monitor work area）");
        return;
    }
    const RECT& work = mi.rcWork;
    const double scale = dpiScaleOf(window);
    auto pxToDp = [scale](LONG px) -> int32_t {
        return static_cast<int32_t>(px / scale >= 0 ? px / scale + 0.5 : px / scale - 0.5);
    };
    out[0] = pxToDp(work.left);
    out[1] = pxToDp(work.top);
    out[2] = pxToDp(work.right - work.left);
    out[3] = pxToDp(work.bottom - work.top);
}

/**
 * 相对锚点窗 cascade（物理像素）。见 bridge 注释。
 */
extern "C" bool composekn_win32_place_cascaded(
    ComposeKNWin32Window* window,
    ComposeKNWin32Window* anchor,
    int32_t width_dp,
    int32_t height_dp
) {
    if (window == nullptr || window->hwnd == nullptr ||
        anchor == nullptr || anchor->hwnd == nullptr) {
        return false;
    }
    RECT ar = {};
    if (!GetWindowRect(anchor->hwnd, &ar)) return false;

    HMONITOR monitor = MonitorFromWindow(anchor->hwnd, MONITOR_DEFAULTTONEAREST);
    if (monitor == nullptr) return false;
    MONITORINFO mi = {};
    mi.cbSize = sizeof(mi);
    if (!GetMonitorInfoW(monitor, &mi)) return false;

    // Desktop WindowLocationTracker：Point(48, 48) 屏幕像素；按锚点 DPI 换算逻辑 48dp
    const int offsetPx = MulDiv(48, anchor->dpi > 0 ? anchor->dpi : 96, 96);
    int x = static_cast<int>(ar.left) + offsetPx;
    int y = static_cast<int>(ar.top) + offsetPx;

    // 新窗外框尺寸按**锚点屏 DPI**估（落点屏）；系统装饰用 AdjustWindowRectEx
    int clientW = MulDiv(width_dp > 0 ? width_dp : 1, anchor->dpi > 0 ? anchor->dpi : 96, 96);
    int clientH = MulDiv(height_dp > 0 ? height_dp : 1, anchor->dpi > 0 ? anchor->dpi : 96, 96);
    int winW = clientW;
    int winH = clientH;
    if (!window->undecorated) {
        RECT adj = {0, 0, clientW, clientH};
        const LONG_PTR style = GetWindowLongPtrW(window->hwnd, GWL_STYLE);
        const LONG_PTR exStyle = GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE);
        const BOOL hasMenu = GetMenu(window->hwnd) != nullptr ? TRUE : FALSE;
        if (AdjustWindowRectEx(&adj, static_cast<DWORD>(style), hasMenu, static_cast<DWORD>(exStyle))) {
            winW = adj.right - adj.left;
            winH = adj.bottom - adj.top;
        }
    }

    const RECT& work = mi.rcWork;
    if (x + winW > work.right || y + winH > work.bottom) {
        x = static_cast<int>(work.left) + offsetPx;
        y = static_cast<int>(work.top) + offsetPx;
    }
    // 仍出界则贴进工作区（半截出屏兜底）
    if (x + winW > work.right) x = work.right - winW;
    if (y + winH > work.bottom) y = work.bottom - winH;
    if (x < work.left) x = work.left;
    if (y < work.top) y = work.top;

    window->keepPlacementOnDpiChange = true;
    SetWindowPos(
        window->hwnd, nullptr, x, y, 0, 0,
        SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
    );
    composeknLog(
        "window: cascade from anchor px=%d,%d -> %d,%d (offset=%d work=%d,%d %dx%d)",
        static_cast<int>(ar.left), static_cast<int>(ar.top), x, y, offsetPx,
        static_cast<int>(work.left), static_cast<int>(work.top),
        static_cast<int>(work.right - work.left),
        static_cast<int>(work.bottom - work.top));
    return true;
}

/**
 * 相对锚点所在屏 Aligned（物理像素）。Dialog 默认 Alignment.Center 走这条。
 */
extern "C" bool composekn_win32_place_aligned(
    ComposeKNWin32Window* window,
    ComposeKNWin32Window* anchor,
    int32_t align_x,
    int32_t align_y,
    int32_t width_dp,
    int32_t height_dp
) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    HWND ref = (anchor != nullptr && anchor->hwnd != nullptr) ? anchor->hwnd : window->hwnd;
    const int refDpi = (anchor != nullptr && anchor->dpi > 0) ? anchor->dpi
                      : (window->dpi > 0 ? window->dpi : 96);

    HMONITOR monitor = MonitorFromWindow(ref, MONITOR_DEFAULTTONEAREST);
    if (monitor == nullptr) return false;
    MONITORINFO mi = {};
    mi.cbSize = sizeof(mi);
    if (!GetMonitorInfoW(monitor, &mi)) return false;

    int clientW = MulDiv(width_dp > 0 ? width_dp : 1, refDpi, 96);
    int clientH = MulDiv(height_dp > 0 ? height_dp : 1, refDpi, 96);
    int winW = clientW;
    int winH = clientH;
    if (!window->undecorated) {
        RECT adj = {0, 0, clientW, clientH};
        const LONG_PTR style = GetWindowLongPtrW(window->hwnd, GWL_STYLE);
        const LONG_PTR exStyle = GetWindowLongPtrW(window->hwnd, GWL_EXSTYLE);
        const BOOL hasMenu = GetMenu(window->hwnd) != nullptr ? TRUE : FALSE;
        if (AdjustWindowRectEx(&adj, static_cast<DWORD>(style), hasMenu, static_cast<DWORD>(exStyle))) {
            winW = adj.right - adj.left;
            winH = adj.bottom - adj.top;
        }
    }

    const RECT& work = mi.rcWork;
    const int workW = static_cast<int>(work.right - work.left);
    const int workH = static_cast<int>(work.bottom - work.top);
    // align -1/0/1 → 分位 0 / 0.5 / 1
    auto axis = [](int bias, int workOrigin, int workSpan, int winSpan) -> int {
        const int b = bias < 0 ? -1 : (bias > 0 ? 1 : 0);
        const int slack = workSpan - winSpan;
        const int offset = (slack * (b + 1)) / 2;
        return workOrigin + offset;
    };
    int x = axis(align_x, static_cast<int>(work.left), workW, winW);
    int y = axis(align_y, static_cast<int>(work.top), workH, winH);
    if (x + winW > work.right) x = work.right - winW;
    if (y + winH > work.bottom) y = work.bottom - winH;
    if (x < work.left) x = work.left;
    if (y < work.top) y = work.top;

    window->keepPlacementOnDpiChange = true;
    SetWindowPos(
        window->hwnd, nullptr, x, y, 0, 0,
        SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE
    );
    composeknLog(
        "window: align(%d,%d) on monitor -> %d,%d (work=%d,%d %dx%d)",
        align_x, align_y, x, y,
        static_cast<int>(work.left), static_cast<int>(work.top), workW, workH);
    return true;
}

/**
 * 自检用：直接对窗口发一条**真实**的 `WM_NCHITTEST`，返回命中码。
 *
 * `where`：0=左中 1=右中 2=上中 3=下中 4=客户区中心（都是 1~2 物理像素的偏移，
 * 保证落在缩放边带里）。返回 HTLEFT(10)…HTBORDER(18)、HTCLIENT(1) 等。
 *
 * 为什么要有这条：真机反馈"不可缩放之后还能拖边缘改大小"（HANDOVER §17.37）——
 * 第一版只在 CSD 分支里挡，而 `window` 阶段用的自检窗口是 CSD、demo 是系统标题栏，
 * 于是**测试看不见这个 bug**。命中码是这个 bug 最直接的观测量。
 */
extern "C" int32_t composekn_win32_test_hit_test(ComposeKNWin32Window* window, int32_t where) {
    if (window == nullptr || window->hwnd == nullptr) return 0;
    RECT windowRect = {};
    RECT clientRect = {};
    if (!GetWindowRect(window->hwnd, &windowRect)) return 0;
    if (!GetClientRect(window->hwnd, &clientRect)) return 0;
    POINT pt = {};
    const LONG midX = (windowRect.left + windowRect.right) / 2;
    const LONG midY = (windowRect.top + windowRect.bottom) / 2;
    switch (where) {
        case 0: pt.x = windowRect.left + 1; pt.y = midY; break;
        case 1: pt.x = windowRect.right - 2; pt.y = midY; break;
        case 2: pt.x = midX; pt.y = windowRect.top + 1; break;
        case 3: pt.x = midX; pt.y = windowRect.bottom - 2; break;
        default:
            pt.x = (clientRect.right - clientRect.left) / 2;
            pt.y = (clientRect.bottom - clientRect.top) / 2;
            ClientToScreen(window->hwnd, &pt);
            break;
    }
    return static_cast<int32_t>(SendMessageW(window->hwnd, WM_NCHITTEST, 0, MAKELPARAM(pt.x, pt.y)));
}

/** 自检用：窗口样式里有没有 WS_THICKFRAME（可缩放的标志位）。 */
extern "C" bool composekn_win32_has_thick_frame(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    return (GetWindowLongPtrW(window->hwnd, GWL_STYLE) & WS_THICKFRAME) != 0;
}

/** 自检用：读回宿主记的进度状态（没有任务栏时也能验 API 的参数校验/映射）。 */
extern "C" void composekn_win32_taskbar_progress_state(
    ComposeKNWin32Window* window, int32_t* state, double* completed) {
    if (state != nullptr) *state = window == nullptr ? 0 : window->taskbarProgressState;
    if (completed != nullptr) *completed = window == nullptr ? 0.0 : window->taskbarProgressValue;
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

/** UTF-8 -> CF_UNICODETEXT 的 HGLOBAL（调用方负责 GlobalFree 或交给剪贴板）。 */
static HGLOBAL utf8ToUnicodeTextGlobal(const char* text) {
    const int wlen = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
    if (wlen <= 0) return nullptr;
    HGLOBAL global = GlobalAlloc(GMEM_MOVEABLE, static_cast<SIZE_T>(wlen) * sizeof(wchar_t));
    if (global == nullptr) return nullptr;
    wchar_t* wide = static_cast<wchar_t*>(GlobalLock(global));
    if (wide == nullptr) {
        GlobalFree(global);
        return nullptr;
    }
    MultiByteToWideChar(CP_UTF8, 0, text, -1, wide, wlen);
    GlobalUnlock(global);
    return global;
}

/**
 * `'\n'` 分隔的 UTF-8 路径 -> CF_HDROP 用的 HGLOBAL（DROPFILES + 宽字符双 NUL 列表）。
 *
 * 剪贴板写、拖放发出、自检共用。失败回 nullptr。
 */
static HGLOBAL buildHDropGlobal(const char* utf8_paths) {
    if (utf8_paths == nullptr) return nullptr;
    std::vector<std::wstring> widePaths;
    {
        std::string current;
        for (const char* p = utf8_paths; ; ++p) {
            if (*p == '\n' || *p == '\0') {
                if (!current.empty()) {
                    const int wlen = MultiByteToWideChar(CP_UTF8, 0, current.c_str(), -1, nullptr, 0);
                    if (wlen > 1) {
                        std::vector<wchar_t> wide(static_cast<size_t>(wlen), L'\0');
                        MultiByteToWideChar(CP_UTF8, 0, current.c_str(), -1, wide.data(), wlen);
                        widePaths.emplace_back(wide.data());
                    }
                    current.clear();
                }
                if (*p == '\0') break;
            } else {
                current.push_back(*p);
            }
        }
    }
    if (widePaths.empty()) return nullptr;
    size_t chars = 1;
    for (const auto& path : widePaths) chars += path.size() + 1;
    const SIZE_T bytes = sizeof(DROPFILES) + chars * sizeof(wchar_t);
    HGLOBAL global = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes);
    if (global == nullptr) return nullptr;
    DROPFILES* df = static_cast<DROPFILES*>(GlobalLock(global));
    if (df == nullptr) {
        GlobalFree(global);
        return nullptr;
    }
    df->pFiles = sizeof(DROPFILES);
    df->fWide = TRUE;
    wchar_t* cursor = reinterpret_cast<wchar_t*>(reinterpret_cast<char*>(df) + sizeof(DROPFILES));
    for (const auto& path : widePaths) {
        std::memcpy(cursor, path.c_str(), (path.size() + 1) * sizeof(wchar_t));
        cursor += path.size() + 1;
    }
    GlobalUnlock(global);
    return global;
}

/** Explorer「粘贴」文件时认的 Preferred DropEffect（MVP = COPY）。 */
static HGLOBAL buildPreferredDropEffectGlobal(DWORD effect) {
    HGLOBAL global = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, sizeof(DWORD));
    if (global == nullptr) return nullptr;
    DWORD* value = static_cast<DWORD*>(GlobalLock(global));
    if (value == nullptr) {
        GlobalFree(global);
        return nullptr;
    }
    *value = effect;
    GlobalUnlock(global);
    return global;
}



// ---------------------------------------------------------------------------
// 自检用：真实 Win32 消息注入
//
// 为什么需要它（HANDOVER §17.31）：自检里的窗口阶段以前只调 `app.dispatchEvent`
// **从 Kotlin 侧**喂 WindowsEvent，于是 C++ 宿主这一层（wndproc 的参数解码、
// 坐标换算、消息过滤、GetMessage/DispatchMessage 投递）在自动化里**从来没有被跑过**
// —— 只有当用户真机上手动操作时才第一次执行。历史 bug 就是这么漏出去的：
// 笔悬停被当成一根按下的手指（WM_POINTERUPDATE 分支）、Shift+滚轮没实现
// （WM_MOUSEWHEEL 分支）都属于「宿主层问题，真机才发现」。
//
// 下面两条把**真实的 Win32 消息** PostMessage 到窗口自己的消息队列：主循环的
// GetMessage -> DispatchMessage -> 真实 wndproc 分支会处理它，事件再从 C 侧队列
// 弹回 Kotlin。所以断言的是宿主本身，而不是别处合成的等价物。
//
// 注意：PostMessage 不经过系统输入栈，所以它测的是「消息到了之后宿主怎么处理」，
// 不是「系统会不会送来这条消息」。后者由测试脚本里的外部注入阶段（xdotool 打真实
// X11 输入 -> wine -> wndproc）覆盖。
// ---------------------------------------------------------------------------

extern "C" bool composekn_win32_post_test_mouse(
    ComposeKNWin32Window* window,
    uint32_t message,
    int32_t x,
    int32_t y,
    int32_t wheel_delta
) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    WPARAM wparam = 0;
    LPARAM lparam = 0;
    switch (message) {
        case WM_MOUSEWHEEL:
        case WM_MOUSEHWHEEL: {
            // 真实消息里 WM_*WHEEL 的 lParam 是**屏幕**坐标（我们 wndproc 里再
            // ScreenToClient 换回来），delta 装在 wParam 的高 16 位
            // （GET_WHEEL_DELTA_WPARAM 取的就是它，且当作**有符号** short）。
            POINT pt = {x, y};
            ClientToScreen(window->hwnd, &pt);
            lparam = MAKELPARAM(pt.x, pt.y);
            wparam = MAKEWPARAM(0, static_cast<WORD>(wheel_delta));
            break;
        }
        default:
            // 移动/按键：lParam 低 16 位 = x，高 16 位 = y。
            lparam = MAKELPARAM(x, y);
            break;
    }
    return PostMessageW(window->hwnd, message, wparam, lparam) != FALSE;
}

extern "C" bool composekn_win32_post_test_key(
    ComposeKNWin32Window* window,
    uint32_t message,
    int32_t vk_or_char,
    int32_t scan_code,
    int32_t is_repeat
) {
    if (window == nullptr || window->hwnd == nullptr) return false;
    const WPARAM wparam = static_cast<WPARAM>(static_cast<uint32_t>(vk_or_char));
    LPARAM lparam = 1;  // bit0-15 = 重复次数
    switch (message) {
        case WM_KEYDOWN:
        case WM_SYSKEYDOWN:
        case WM_KEYUP:
        case WM_SYSKEYUP: {
            // bit16-23 = 扫描码；bit30 = 「这条消息之前那个键是否已经按下」
            // （1 = 系统的自动重复）；bit31 = 抬起。
            lparam |= (static_cast<LPARAM>(scan_code & 0xFF) << 16);
            if (is_repeat != 0) lparam |= (1LL << 30);
            if (message == WM_KEYUP || message == WM_SYSKEYUP) {
                // 注意用 1LL：mingw 上 long 是 32 位，`1L << 31` 是未定义行为。
                lparam |= (1LL << 30) | (1LL << 31);
            }
            break;
        }
        default:
            // WM_CHAR / WM_UNICHAR：wParam = 字符码点，lParam 只有重复次数有意义。
            break;
    }
    return PostMessageW(window->hwnd, message, wparam, lparam) != FALSE;
}

// ---------------------------------------------------------------------------
// OLE 拖放（接收侧）
//
// 目标（对照上游）：让 Compose 的 `Modifier.dragAndDropTarget` 在 Windows 上收到
// 资源管理器拖进来的文件（CF_HDROP）和文本（CF_UNICODETEXT）。
//
// 上游的 AWT 实现（ComposeSceneMediator + AwtDragAndDropManager）做的是：
//   1) 在 root component 上装 java.awt.dnd.DropTarget（AWT 内部就是 OLE
//      RegisterDragDrop）；
//   2) DropTargetListener 回调里构造 DragAndDropEvent，调用
//      ComposeSceneDragAndDropNode 的 acceptDragAndDropTransfer / onStarted /
//      onEntered / onMoved / onExited / onDrop / onEnded。
// 我们这里把第 1 步换成自己实现的 IDropTarget，第 2 步完全一样（Kotlin 侧调用
// 同一个 rootDragAndDropNode）。所以事件语义、回调顺序、accept 的判定都与上游一致。
//
// 与 AWT 的一处**已知差异**（写下来免得以后当成 bug）：AWT 的 DropTargetListener
// 是在 EDT 上**同步**回调的，所以它能在 DragEnter 里立刻问 Compose 并决定
// accept/reject；我们的 DragEnter 是在 OLE 的消息循环内部被调的，那时 Kotlin 正在
// 消息循环里睡着，没法同步问。于是策略是：
//   * DragEnter：只要负载里有文件/文本就乐观接受（回 DROPEFFECT_COPY），
//     事件照常推给 Kotlin；
//   * Kotlin 判定完（acceptDragAndDropTransfer）会调 set_drop_accept 回写；
//   * 之后的 DragOver / Drop 用这个标志回答 effect（光标最迟在一次 DragOver 后
//     就会变成「禁止放下」）。
// ---------------------------------------------------------------------------

namespace {

/** 宽字符 -> UTF-8（失败返回空串）。 */
static std::string wideToUtf8(const wchar_t* wide) {
    if (wide == nullptr) return std::string();
    const int len = WideCharToMultiByte(CP_UTF8, 0, wide, -1, nullptr, 0, nullptr, nullptr);
    if (len <= 1) return std::string();
    // 注意：cchWideChar = -1 时转换结果**包含**结尾 NUL，所以缓冲区要 len 字节，
    // 之后再砍掉那一个字节。
    std::string out(static_cast<size_t>(len), '\0');
    WideCharToMultiByte(CP_UTF8, 0, wide, -1, out.data(), len, nullptr, nullptr);
    out.resize(static_cast<size_t>(len - 1));
    return out;
}

/** 从 IDataObject 里取出 CF_HDROP（文件列表）与 CF_UNICODETEXT（文本）。 */
/**
 * 把一个 `HDROP` 解成 `'\n'` 分隔的 UTF-8 路径列表（拖放和剪贴板两个入口共用）。
 *
 * 返回值 = 解析出几条路径。
 */
static int readHDropFiles(HDROP drop, std::string& out) {
    if (drop == nullptr) return 0;
    const UINT count = DragQueryFileW(drop, 0xFFFFFFFF, nullptr, 0);
    int found = 0;
    for (UINT i = 0; i < count; ++i) {
        const UINT len = DragQueryFileW(drop, i, nullptr, 0);
        std::vector<wchar_t> buf(static_cast<size_t>(len) + 1, L'\0');
        if (DragQueryFileW(drop, i, buf.data(), len + 1) > 0) {
            if (!out.empty()) out.push_back('\n');
            out += wideToUtf8(buf.data());
            ++found;
        }
    }
    return found;
}

static bool extractDropPayload(IDataObject* data, std::string& files, std::string& text) {
    if (data == nullptr) return false;
    bool any = false;
    STGMEDIUM medium = {};
    FORMATETC fmt = { CF_HDROP, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
    if (data->GetData(&fmt, &medium) == S_OK) {
        HDROP drop = static_cast<HDROP>(GlobalLock(medium.hGlobal));
        if (drop != nullptr) {
            any = readHDropFiles(drop, files) > 0 || any;
            GlobalUnlock(medium.hGlobal);
        }
        ReleaseStgMedium(&medium);
    }
    medium = {};
    fmt.cfFormat = CF_UNICODETEXT;
    if (data->GetData(&fmt, &medium) == S_OK) {
        wchar_t* wide = static_cast<wchar_t*>(GlobalLock(medium.hGlobal));
        if (wide != nullptr) {
            text = wideToUtf8(wide);
            any = true;
            GlobalUnlock(medium.hGlobal);
        }
        ReleaseStgMedium(&medium);
    }
    return any;
}

/** POINTL（屏幕坐标）-> 客户区坐标（float，和其它鼠标事件同一套坐标）。 */
static void dropPointToClient(ComposeKNWin32Window* window, POINTL pt, float* outX, float* outY) {
    POINT p = { pt.x, pt.y };
    if (window != nullptr && window->hwnd != nullptr) ScreenToClient(window->hwnd, &p);
    if (outX != nullptr) *outX = static_cast<float>(p.x);
    if (outY != nullptr) *outY = static_cast<float>(p.y);
}

/**
 * 推一条 drag 事件，并**同步**压入这一条的负载（与事件严格 1:1，Kotlin 侧每条事件
 * 都要弹一次，和 IME 的约定一样）。
 */
static void pushDragEvent(
    ComposeKNWin32Window* window,
    int32_t type,
    float x,
    float y,
    const std::string& files,
    const std::string& text
) {
    if (window == nullptr) return;
    ComposeKNWin32Event e{};
    e.type = type;
    e.x = x;
    e.y = y;
    e.modifiers = queryCurrentModifiers();
    pushEvent(window, e);
    window->dragFiles.push_back(files);
    window->dragTexts.push_back(text);
    if (window->dragLogCount < 300) {
        ++window->dragLogCount;
        const int fileCount = files.empty() ? 0 : static_cast<int>(std::count(files.begin(), files.end(), '\n')) + 1;
        composeknLog(
            "%s pos=%.0f,%.0f files=%d textLen=%d%s",
            type == COMPOSEKN_WIN32_EVENT_DRAG_ENTER ? "drag: ENTER"
            : type == COMPOSEKN_WIN32_EVENT_DRAG_OVER ? "drag: OVER"
            : type == COMPOSEKN_WIN32_EVENT_DRAG_DROP ? "drag: DROP" : "drag: LEAVE",
            x, y, fileCount, static_cast<int>(text.size()),
            files.empty() ? "" : (" 第一个=" + files.substr(0, files.find('\n'))).c_str()
        );
    }
}

class ComposeKNDropTarget final : public IDropTarget {
public:
    explicit ComposeKNDropTarget(ComposeKNWin32Window* window) : window_(window) {}

    // ---- IUnknown ----
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IDropTarget) {
            *ppv = static_cast<IDropTarget*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refCount_));
    }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG count = InterlockedDecrement(&refCount_);
        if (count == 0) delete this;
        return static_cast<ULONG>(count);
    }

    // ---- IDropTarget ----
    HRESULT STDMETHODCALLTYPE DragEnter(IDataObject* data, DWORD keys, POINTL pt, DWORD* effect) override {
        window_->dragSessionFiles.clear();
        window_->dragSessionText.clear();
        extractDropPayload(data, window_->dragSessionFiles, window_->dragSessionText);
        const bool supported = !window_->dragSessionFiles.empty() || !window_->dragSessionText.empty();
        window_->dropAccepted = supported;   // 乐观接受，Kotlin 随后会纠正
        float x = 0.f, y = 0.f;
        dropPointToClient(window_, pt, &x, &y);
        pushDragEvent(window_, COMPOSEKN_WIN32_EVENT_DRAG_ENTER, x, y,
                      window_->dragSessionFiles, window_->dragSessionText);
        if (effect != nullptr) {
            *effect = supported ? DROPEFFECT_COPY : DROPEFFECT_NONE;
            window_->lastDropEffect = *effect;
        }
        (void)keys;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE DragOver(DWORD keys, POINTL pt, DWORD* effect) override {
        float x = 0.f, y = 0.f;
        dropPointToClient(window_, pt, &x, &y);
        pushDragEvent(window_, COMPOSEKN_WIN32_EVENT_DRAG_OVER, x, y,
                      window_->dragSessionFiles, window_->dragSessionText);
        if (effect != nullptr) {
            *effect = window_->dropAccepted ? DROPEFFECT_COPY : DROPEFFECT_NONE;
            window_->lastDropEffect = *effect;
        }
        (void)keys;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE DragLeave() override {
        pushDragEvent(window_, COMPOSEKN_WIN32_EVENT_DRAG_LEAVE, -1.f, -1.f, std::string(), std::string());
        window_->dragSessionFiles.clear();
        window_->dragSessionText.clear();
        window_->dropAccepted = false;
        window_->lastDropEffect = DROPEFFECT_NONE;   // 拖放已离开，光标不再显示「可放下」
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE Drop(IDataObject* data, DWORD keys, POINTL pt, DWORD* effect) override {
        // Drop 会重新给一次 data object：以它为准（有的实现 DragEnter 之后才准备好数据）。
        std::string files;
        std::string text;
        if (!extractDropPayload(data, files, text)) {
            files = window_->dragSessionFiles;
            text = window_->dragSessionText;
        }
        float x = 0.f, y = 0.f;
        dropPointToClient(window_, pt, &x, &y);
        pushDragEvent(window_, COMPOSEKN_WIN32_EVENT_DRAG_DROP, x, y, files, text);
        const bool accepted = window_->dropAccepted;
        window_->dragSessionFiles.clear();
        window_->dragSessionText.clear();
        window_->dropAccepted = false;
        if (effect != nullptr) {
            *effect = accepted ? DROPEFFECT_COPY : DROPEFFECT_NONE;
            window_->lastDropEffect = *effect;
        }
        (void)keys;
        return S_OK;
    }

private:
    ~ComposeKNDropTarget() = default;
    ComposeKNWin32Window* window_ = nullptr;
    LONG refCount_ = 1;
};

// ---------------------------------------------------------------------------
// 自检用的最小 IDataObject
//
// 为什么需要它：Wine/Xvfb 里没法真的从资源管理器拖一个文件进来（没有 shell 拖放
// 源），但**我们自己这一侧**的代码（COM vtable、FORMATETC 解析、CF_HDROP 解码、
// 事件队列、Kotlin 派发、Compose 的 dragAndDropTarget）全都必须能被自动化测到。
// 于是这里构造一个**真的 IDataObject**（不是伪造指针），把它交给注册好的
// IDropTarget —— 只有「OLE 的模态拖放循环会不会把消息送到这里」这一点没覆盖到。
// ---------------------------------------------------------------------------
class ComposeKNTestDataObject final : public IDataObject {
public:
    /** kind: 0 = CF_HDROP（两个固定路径），1 = CF_UNICODETEXT。 */
    explicit ComposeKNTestDataObject(int32_t kind) : kind_(kind) {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IDataObject) {
            *ppv = static_cast<IDataObject*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refCount_));
    }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG count = InterlockedDecrement(&refCount_);
        if (count == 0) delete this;
        return static_cast<ULONG>(count);
    }

    HRESULT STDMETHODCALLTYPE GetData(FORMATETC* fmt, STGMEDIUM* medium) override {
        if (fmt == nullptr || medium == nullptr) return E_INVALIDARG;
        if (!supports(fmt->cfFormat)) return DV_E_FORMATETC;
        HGLOBAL global = nullptr;
        if (kind_ == 0) {
            // DROPFILES 头 + 宽字符**双 NUL 结尾**的路径列表（资源管理器放进 CF_HDROP
            // 的就是这个布局）。用 sizeof 算长度，免得手数字符个数（错一个就少一个 NUL）。
            static const wchar_t kPaths[] =
                L"C:\\composekn\\drop-test-1.txt\0"
                L"C:\\composekn\\drop-test-2.txt\0";
            const SIZE_T bytes = sizeof(DROPFILES) + sizeof(kPaths);
            global = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, bytes);
            if (global == nullptr) return E_OUTOFMEMORY;
            DROPFILES* df = static_cast<DROPFILES*>(GlobalLock(global));
            if (df == nullptr) { GlobalFree(global); return E_OUTOFMEMORY; }
            df->pFiles = sizeof(DROPFILES);
            df->fWide = TRUE;
            std::memcpy(reinterpret_cast<char*>(df) + sizeof(DROPFILES), kPaths, sizeof(kPaths));
            GlobalUnlock(global);
        } else {
            static const wchar_t kText[] = L"ComposeKN 拖放测试文本";
            global = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, sizeof(kText));
            if (global == nullptr) return E_OUTOFMEMORY;
            wchar_t* dst = static_cast<wchar_t*>(GlobalLock(global));
            if (dst == nullptr) { GlobalFree(global); return E_OUTOFMEMORY; }
            std::memcpy(dst, kText, sizeof(kText));
            GlobalUnlock(global);
        }
        medium->tymed = TYMED_HGLOBAL;
        medium->hGlobal = global;
        medium->pUnkForRelease = nullptr;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE QueryGetData(FORMATETC* fmt) override {
        if (fmt == nullptr) return E_INVALIDARG;
        return supports(fmt->cfFormat) ? S_OK : DV_E_FORMATETC;
    }

    HRESULT STDMETHODCALLTYPE GetDataHere(FORMATETC*, STGMEDIUM*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCanonicalFormatEtc(FORMATETC*, FORMATETC* out) override {
        if (out != nullptr) out->ptd = nullptr;
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE SetData(FORMATETC*, STGMEDIUM*, BOOL) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE EnumFormatEtc(DWORD, IEnumFORMATETC** out) override {
        if (out != nullptr) *out = nullptr;
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE DAdvise(FORMATETC*, DWORD, IAdviseSink*, DWORD*) override { return OLE_E_ADVISENOTSUPPORTED; }
    HRESULT STDMETHODCALLTYPE DUnadvise(DWORD) override { return OLE_E_ADVISENOTSUPPORTED; }
    HRESULT STDMETHODCALLTYPE EnumDAdvise(IEnumSTATDATA** out) override {
        if (out != nullptr) *out = nullptr;
        return OLE_E_ADVISENOTSUPPORTED;
    }

private:
    bool supports(CLIPFORMAT format) const {
        return kind_ == 0 ? format == CF_HDROP : format == CF_UNICODETEXT;
    }
    ~ComposeKNTestDataObject() = default;
    LONG refCount_ = 1;
    int32_t kind_ = 0;
};

/**
 * 建 OLE 拖放目标并注册到窗口。OleInitialize 走 STA（和 AWT 一样）；已经在别的
 * 模式初始化过（RPC_E_CHANGED_MODE）时不当致命错误 —— 只是拖放不可用，应用照常跑。
 */
static void composeknInstallDropTarget(ComposeKNWin32Window* window) {
    if (window == nullptr || window->hwnd == nullptr) return;
    const HRESULT oleHr = OleInitialize(nullptr);
    if (!SUCCEEDED(oleHr) && oleHr != S_FALSE) {
        composeknLog("drag: OleInitialize 失败 hr=0x%08lX（拖放不可用）", (unsigned long)oleHr);
        return;
    }
    // S_OK / S_FALSE 都要配对 OleUninitialize（MSDN）；用进程/线程级计数，
    // 避免「第二个窗口 OleInitialize 返回 S_FALSE，第一个窗口销毁时就把 OLE 卸了」。
    InterlockedIncrement(&g_oleInitCount);
    window->oleInitialized = true;
    window->dropTarget = new ComposeKNDropTarget(window);
    const HRESULT regHr = RegisterDragDrop(window->hwnd, window->dropTarget);
    if (FAILED(regHr)) {
        composeknLog("drag: RegisterDragDrop 失败 hr=0x%08lX（拖放不可用）", (unsigned long)regHr);
        window->dropTarget->Release();
        window->dropTarget = nullptr;
        // Register 失败仍算一次成功的 OleInitialize，destroy 时会配对减掉。
        return;
    }
    composeknLog("drag: RegisterDragDrop ok（文件/文本拖放已接收）");
}

}  // namespace

// ---------------------------------------------------------------------------
// OLE 拖放发出侧（文件作用域类 + extern "C"；不进匿名 namespace，以便 C 导出能看见）
// ---------------------------------------------------------------------------

class ComposeKNSourceDataObject final : public IDataObject {
public:
    ComposeKNSourceDataObject(const char* utf8_files, const char* utf8_text)
        : files_(utf8_files != nullptr ? utf8_files : ""),
          text_(utf8_text != nullptr ? utf8_text : "") {}

    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IDataObject) {
            *ppv = static_cast<IDataObject*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refCount_));
    }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG count = InterlockedDecrement(&refCount_);
        if (count == 0) delete this;
        return static_cast<ULONG>(count);
    }

    HRESULT STDMETHODCALLTYPE GetData(FORMATETC* fmt, STGMEDIUM* medium) override {
        if (fmt == nullptr || medium == nullptr) return E_INVALIDARG;
        HGLOBAL global = nullptr;
        if (fmt->cfFormat == CF_HDROP && !files_.empty()) {
            global = buildHDropGlobal(files_.c_str());
        } else if (fmt->cfFormat == CF_UNICODETEXT && !text_.empty()) {
            global = utf8ToUnicodeTextGlobal(text_.c_str());
        } else {
            return DV_E_FORMATETC;
        }
        if (global == nullptr) return E_OUTOFMEMORY;
        medium->tymed = TYMED_HGLOBAL;
        medium->hGlobal = global;
        medium->pUnkForRelease = nullptr;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE QueryGetData(FORMATETC* fmt) override {
        if (fmt == nullptr) return E_INVALIDARG;
        if (fmt->cfFormat == CF_HDROP && !files_.empty()) return S_OK;
        if (fmt->cfFormat == CF_UNICODETEXT && !text_.empty()) return S_OK;
        return DV_E_FORMATETC;
    }

    HRESULT STDMETHODCALLTYPE GetDataHere(FORMATETC*, STGMEDIUM*) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE GetCanonicalFormatEtc(FORMATETC*, FORMATETC* out) override {
        if (out != nullptr) out->ptd = nullptr;
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE SetData(FORMATETC*, STGMEDIUM*, BOOL) override { return E_NOTIMPL; }
    HRESULT STDMETHODCALLTYPE EnumFormatEtc(DWORD, IEnumFORMATETC** out) override {
        if (out != nullptr) *out = nullptr;
        return E_NOTIMPL;
    }
    HRESULT STDMETHODCALLTYPE DAdvise(FORMATETC*, DWORD, IAdviseSink*, DWORD*) override {
        return OLE_E_ADVISENOTSUPPORTED;
    }
    HRESULT STDMETHODCALLTYPE DUnadvise(DWORD) override { return OLE_E_ADVISENOTSUPPORTED; }
    HRESULT STDMETHODCALLTYPE EnumDAdvise(IEnumSTATDATA** out) override {
        if (out != nullptr) *out = nullptr;
        return OLE_E_ADVISENOTSUPPORTED;
    }

private:
    ~ComposeKNSourceDataObject() = default;
    LONG refCount_ = 1;
    std::string files_;
    std::string text_;
};

class ComposeKNDropSource final : public IDropSource {
public:
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (ppv == nullptr) return E_POINTER;
        if (riid == IID_IUnknown || riid == IID_IDropSource) {
            *ppv = static_cast<IDropSource*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override {
        return static_cast<ULONG>(InterlockedIncrement(&refCount_));
    }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG count = InterlockedDecrement(&refCount_);
        if (count == 0) delete this;
        return static_cast<ULONG>(count);
    }

    HRESULT STDMETHODCALLTYPE QueryContinueDrag(BOOL escapePressed, DWORD keyState) override {
        if (escapePressed) return DRAGDROP_S_CANCEL;
        if ((keyState & MK_LBUTTON) == 0) return DRAGDROP_S_DROP;
        return S_OK;
    }

    HRESULT STDMETHODCALLTYPE GiveFeedback(DWORD) override {
        return DRAGDROP_S_USEDEFAULTCURSORS;
    }

private:
    ~ComposeKNDropSource() = default;
    LONG refCount_ = 1;
};

extern "C" int32_t composekn_win32_do_drag_drop(
    ComposeKNWin32Window* window,
    const char* utf8_files,
    const char* utf8_text,
    int32_t allowed_effects
) {
    (void)window;
    const bool hasFiles = utf8_files != nullptr && utf8_files[0] != '\0';
    const bool hasText = utf8_text != nullptr && utf8_text[0] != '\0';
    if (!hasFiles && !hasText) return -1;
    ComposeKNSourceDataObject* data = new ComposeKNSourceDataObject(
        hasFiles ? utf8_files : nullptr,
        hasText ? utf8_text : nullptr
    );
    ComposeKNDropSource* source = new ComposeKNDropSource();
    DWORD effect = DROPEFFECT_NONE;
    const DWORD allowed = allowed_effects != 0
        ? static_cast<DWORD>(allowed_effects)
        : DROPEFFECT_COPY;
    const HRESULT hr = DoDragDrop(data, source, allowed, &effect);
    source->Release();
    data->Release();
    if (hr == DRAGDROP_S_CANCEL) {
        composeknLog("drag: DoDragDrop 取消");
        return 0;
    }
    if (FAILED(hr) && hr != DRAGDROP_S_DROP) {
        composeknLog("drag: DoDragDrop 失败 hr=0x%08lX", (unsigned long)hr);
        return -1;
    }
    composeknLog("drag: DoDragDrop 完成 effect=%lu", (unsigned long)effect);
    return static_cast<int32_t>(effect);
}

extern "C" int32_t composekn_win32_test_source_data_formats(
    const char* utf8_files, const char* utf8_text
) {
    ComposeKNSourceDataObject* data = new ComposeKNSourceDataObject(utf8_files, utf8_text);
    FORMATETC fmt = { CF_HDROP, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
    int32_t mask = 0;
    if (data->QueryGetData(&fmt) == S_OK) mask |= 1;
    fmt.cfFormat = CF_UNICODETEXT;
    if (data->QueryGetData(&fmt) == S_OK) mask |= 2;
    if ((mask & 1) != 0) {
        STGMEDIUM medium = {};
        fmt.cfFormat = CF_HDROP;
        if (data->GetData(&fmt, &medium) == S_OK) {
            if (medium.hGlobal == nullptr) mask = -1;
            ReleaseStgMedium(&medium);
        } else {
            mask = -2;
        }
    }
    if (mask >= 0 && (mask & 2) != 0) {
        STGMEDIUM medium = {};
        fmt.cfFormat = CF_UNICODETEXT;
        if (data->GetData(&fmt, &medium) == S_OK) {
            if (medium.hGlobal == nullptr) mask = -3;
            ReleaseStgMedium(&medium);
        } else {
            mask = -4;
        }
    }
    data->Release();
    return mask;
}

extern "C" int32_t composekn_win32_last_drop_effect(ComposeKNWin32Window* window) {
    return window == nullptr ? 0 : static_cast<int32_t>(window->lastDropEffect);
}

extern "C" bool composekn_win32_ole_available(ComposeKNWin32Window* window) {
    return window != nullptr && window->dropTarget != nullptr;
}

extern "C" bool composekn_win32_set_drop_accept(ComposeKNWin32Window* window, bool accept) {
    if (window == nullptr) return false;
    window->dropAccepted = accept;
    return true;
}

extern "C" int32_t composekn_win32_drag_pop_files(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size) {
    if (window == nullptr || window->dragFiles.empty()) return -1;
    std::string value = std::move(window->dragFiles.front());
    window->dragFiles.erase(window->dragFiles.begin());
    if (buffer == nullptr || buffer_size <= 0) return -1;
    int32_t count = static_cast<int32_t>(value.size());
    if (count > buffer_size - 1) count = buffer_size - 1;
    if (count > 0) std::memcpy(buffer, value.data(), static_cast<size_t>(count));
    buffer[count] = '\0';
    return count;
}

extern "C" int32_t composekn_win32_drag_pop_text(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size) {
    if (window == nullptr || window->dragTexts.empty()) return -1;
    std::string value = std::move(window->dragTexts.front());
    window->dragTexts.erase(window->dragTexts.begin());
    if (buffer == nullptr || buffer_size <= 0) return -1;
    int32_t count = static_cast<int32_t>(value.size());
    if (count > buffer_size - 1) count = buffer_size - 1;
    if (count > 0) std::memcpy(buffer, value.data(), static_cast<size_t>(count));
    buffer[count] = '\0';
    return count;
}

/**
 * 自检用：直接驱动注册好的 IDropTarget（不经过 OLE 的模态拖放循环）。
 *
 * phase: 0=DragEnter 1=DragOver 2=DragLeave 3=Drop；kind: 0=文件 1=文本。
 * (x, y) 是**客户区**坐标（内部换成屏幕坐标，和真机 OLE 的 POINTL 一致）。
 */
extern "C" bool composekn_win32_test_simulate_drag(
    ComposeKNWin32Window* window, int32_t phase, int32_t x, int32_t y, int32_t kind) {
    if (window == nullptr || window->dropTarget == nullptr) return false;
    POINT pt = { x, y };
    ClientToScreen(window->hwnd, &pt);
    POINTL ptl = { pt.x, pt.y };
    DWORD effect = DROPEFFECT_NONE;
    if (phase == 2) {
        window->dropTarget->DragLeave();
        return true;
    }
    ComposeKNTestDataObject* data = new ComposeKNTestDataObject(kind);
    switch (phase) {
        case 0:
            window->dropTarget->DragEnter(data, MK_LBUTTON, ptl, &effect);
            break;
        case 1:
            window->dropTarget->DragOver(MK_LBUTTON, ptl, &effect);
            break;
        default:
            window->dropTarget->Drop(data, MK_LBUTTON, ptl, &effect);
            break;
    }
    data->Release();
    return true;
}

// ---------------------------------------------------------------------------
// 剪贴板：富文本格式（CF_HTML / Rich Text Format / CF_DIBV5）
//
// 为什么要自己拼 CF_HTML 头：Windows 的 "HTML Format" 是「一段带偏移头的字节流」，
// 头里记录 StartHTML/EndHTML/StartFragment/EndFragment 四个**字节偏移**（相对整块
// 数据，含头本身）。别人（Word/Chrome/WordPad）读的时候就是按这几个偏移去切 HTML，
// 切错了就是"能粘贴但内容是乱的/空白的"。
//   官网说明: HTML Clipboard Format (MSDN, "Clipboard Formats" 一节)
//   头是 ASCII、固定 10 位十进制（这样头长度稳定，先占位算长度再回填偏移）。
//
// 图片用 CF_DIBV5（BITMAPV5HEADER + BI_BITFIELDS + RGBA 掩码 + 自上而下）：
// 32bpp 的 CF_DIB 传统上有「alpha 通道到底算不算数」的歧义（很多老程序忽略它），
// 而 V5 头带掩码和色彩空间，语义明确。读的时候兼容 V5/INFO/CORE 三种头 + 24bpp。
// ---------------------------------------------------------------------------

namespace {

constexpr const char* kHtmlFormatName = "HTML Format";
constexpr const char* kRtfFormatName = "Rich Text Format";

/** 查一个标准 CF_ 编号或注册格式名（找不到注册格式返回 0）。 */
static UINT resolveClipboardFormat(const char* name) {
    if (name == nullptr || name[0] == '\0') return 0;
    if (name[0] == '#') {
        return static_cast<UINT>(std::strtoul(name + 1, nullptr, 10));
    }
    return RegisterClipboardFormatA(name);
}

/** 自检用：把 HGLOBAL 里的原始字节按 hex 写进缓冲区（两段式）。 */
static int32_t copyHex(HGLOBAL global, char* buffer, int32_t buffer_size) {
    if (global == nullptr) return -1;
    const SIZE_T size = GlobalSize(global);
    const void* data = GlobalLock(global);
    if (data == nullptr) return -1;
    // 上限 256KB：hex 会让文本翻倍，测试里只关心头几十字节，不需要整块。
    SIZE_T limit = size > (256 * 1024) ? (256 * 1024) : size;
    const int32_t needed = static_cast<int32_t>(limit * 2);
    if (buffer == nullptr || buffer_size < needed) {
        GlobalUnlock(global);
        return needed;
    }
    const auto* bytes = static_cast<const unsigned char*>(data);
    static const char* digits = "0123456789abcdef";
    for (SIZE_T i = 0; i < limit; ++i) {
        buffer[i * 2] = digits[(bytes[i] >> 4) & 0xF];
        buffer[i * 2 + 1] = digits[bytes[i] & 0xF];
    }
    GlobalUnlock(global);
    return needed;
}

/** CF_HTML：把片段包成标准头（UTF-8，偏移是字节偏移）。 */
static std::string buildCfHtml(const std::string& fragment) {
    const std::string prefix = "<html><body><!--StartFragment-->";
    const std::string suffix = "<!--EndFragment--></body></html>";
    // 先按最终宽度（10 位十进制）占位，算出头长度 —— 头长度稳定，偏移才能回填。
    const char* headerTemplate =
        "Version:0.9\r\n"
        "StartHTML:%010u\r\n"
        "EndHTML:%010u\r\n"
        "StartFragment:%010u\r\n"
        "EndFragment:%010u\r\n";
    char header[256];
    std::snprintf(header, sizeof(header), headerTemplate, 0u, 0u, 0u, 0u);
    const size_t headerLen = std::strlen(header);
    const size_t startHtml = headerLen;
    const size_t startFragment = startHtml + prefix.size();
    const size_t endFragment = startFragment + fragment.size();
    const size_t endHtml = endFragment + suffix.size();
    std::snprintf(
        header, sizeof(header), headerTemplate,
        static_cast<unsigned>(startHtml), static_cast<unsigned>(endHtml),
        static_cast<unsigned>(startFragment), static_cast<unsigned>(endFragment)
    );
    std::string out;
    out.reserve(endHtml);
    out += header;
    out += prefix;
    out += fragment;
    out += suffix;
    return out;
}

/** 在 buffer 里找 ASCII 模式，返回命中位置（找不到返回 npos）。 */
static size_t findAscii(const std::string& haystack, const char* needle, size_t from) {
    return haystack.find(needle, from);
}

/** 从 "StartFragment:" 之后解析十进制偏移；解析不到返回 -1。 */
static long long parseOffsetAfter(const std::string& headerText, const char* key) {
    const size_t at = findAscii(headerText, key, 0);
    if (at == std::string::npos) return -1;
    return std::strtoll(headerText.c_str() + at + std::strlen(key), nullptr, 10);
}

/** CF_HTML -> 片段 UTF-8（支持 UTF-8/ANSI 与 UTF-16LE 两种宿主编码）。 */
static bool parseCfHtml(const void* data, size_t size, std::string& out) {
    if (data == nullptr || size < 2) return false;
    const auto* bytes = static_cast<const unsigned char*>(data);
    const bool utf16 = (bytes[0] == 0xFF && bytes[1] == 0xFE);
    // 头是 ASCII：先把前 512 字节（UTF-16 时就是前 256 个字符）转成 UTF-8 来解析偏移。
    std::string headerText;
    size_t headerProbe = size > 512 ? 512 : size;
    if (utf16) {
        const int wlen = static_cast<int>(headerProbe / 2);
        const int len = WideCharToMultiByte(
            CP_UTF8, 0, reinterpret_cast<const wchar_t*>(data), wlen,
            nullptr, 0, nullptr, nullptr
        );
        if (len > 0) {
            headerText.resize(static_cast<size_t>(len), '\0');
            WideCharToMultiByte(
                CP_UTF8, 0, reinterpret_cast<const wchar_t*>(data), wlen,
                headerText.data(), len, nullptr, nullptr
            );
        }
    } else {
        headerText.assign(reinterpret_cast<const char*>(data), headerProbe);
    }
    long long startFragment = parseOffsetAfter(headerText, "StartFragment:");
    long long endFragment = parseOffsetAfter(headerText, "EndFragment:");
    if (startFragment < 0) {
        // 没有头（有些程序直接放裸 HTML）：整块当作 HTML。
        startFragment = 0;
        endFragment = static_cast<long long>(size);
    }
    if (startFragment < 0 || endFragment <= startFragment ||
        static_cast<size_t>(endFragment) > size) {
        composeknLog(
            "clipboard: CF_HTML 偏移不合法 start=%lld end=%lld size=%zu（退回整块）",
            startFragment, endFragment, size
        );
        startFragment = 0;
        endFragment = static_cast<long long>(size);
    }
    if (utf16) {
        const auto* wide = reinterpret_cast<const wchar_t*>(
            static_cast<const unsigned char*>(data) + startFragment);
        const int wlen = static_cast<int>((endFragment - startFragment) / 2);
        const int len = WideCharToMultiByte(CP_UTF8, 0, wide, wlen, nullptr, 0, nullptr, nullptr);
        if (len <= 0) return false;
        out.resize(static_cast<size_t>(len), '\0');
        WideCharToMultiByte(CP_UTF8, 0, wide, wlen, out.data(), len, nullptr, nullptr);
    } else {
        out.assign(
            reinterpret_cast<const char*>(static_cast<const unsigned char*>(data) + startFragment),
            static_cast<size_t>(endFragment - startFragment)
        );
    }
    return true;
}

/**
 * CF_DIB* -> 自上而下 BGRA（stride = width*4）。
 *
 * 支持 BITMAPV5HEADER(124) / BITMAPINFOHEADER(40) / BITMAPCOREHEADER(12)，
 * 32bpp 与 24bpp、BI_RGB 与 32bpp BI_BITFIELDS；自下而上的行会翻转。
 * 调色板/RLE 等不支持（回 false 并记一行日志）。
 */
static bool parseDib(const void* data, size_t size, std::vector<uint8_t>& out, int32_t& width, int32_t& height) {
    if (data == nullptr || size < 16) return false;
    const auto* bytes = static_cast<const unsigned char*>(data);
    uint32_t headerSize = 0;
    std::memcpy(&headerSize, bytes, 4);
    int32_t w = 0, h = 0;
    uint16_t bitCount = 0;
    uint32_t compression = 0;
    size_t pixelOffset = 0;
    bool topDown = false;
    if (headerSize >= 40) {
        if (size < 40) return false;
        int32_t signedHeight = 0;
        std::memcpy(&w, bytes + 4, 4);
        std::memcpy(&signedHeight, bytes + 8, 4);
        std::memcpy(&bitCount, bytes + 14, 2);
        std::memcpy(&compression, bytes + 16, 4);
        topDown = signedHeight < 0;
        h = topDown ? -signedHeight : signedHeight;
        pixelOffset = headerSize;
    } else if (headerSize == 12) {
        int16_t w16 = 0, h16 = 0;
        std::memcpy(&w16, bytes + 4, 2);
        std::memcpy(&h16, bytes + 6, 2);
        std::memcpy(&bitCount, bytes + 10, 2);
        w = w16;
        h = h16;
        compression = 0;   // CORE 头只有 BI_RGB
        pixelOffset = 12;
    } else {
        composeknLog("clipboard: 位图头大小 %u 不认识", headerSize);
        return false;
    }
    if (w <= 0 || h <= 0 || w > 20000 || h > 20000) {
        composeknLog("clipboard: 位图尺寸异常 %dx%d（头 %u）", w, h, headerSize);
        return false;
    }
    if (bitCount != 32 && bitCount != 24 && bitCount != 8) {
        composeknLog("clipboard: %u bpp 位图暂不支持（只做 32/24/8bpp，头 %u）", bitCount, headerSize);
        return false;
    }
    if (compression != 0 /*BI_RGB*/ && compression != 3 /*BI_BITFIELDS*/) {
        composeknLog(
            "clipboard: 压缩位图暂不支持（biCompression=%u，%u bpp，%dx%d）",
            compression, bitCount, w, h
        );
        return false;
    }
    const size_t srcStride = ((static_cast<size_t>(w) * bitCount / 8) + 3) & ~static_cast<size_t>(3);
    const size_t pixelBytes = srcStride * static_cast<size_t>(h);
    if (pixelBytes > size) {
        composeknLog(
            "clipboard: 位图数据不够（像素需要 %zu 字节，整块只有 %zu；%dx%d %ubpp）",
            pixelBytes, size, w, h, bitCount
        );
        return false;
    }
    // **像素数据锚定到缓冲区末尾**：调色板大小不可信时（biClrUsed=0 声称 256 项、
    // 缓冲区里其实只有几项 —— Wine 转换格式后就是这样，某些老程序也会），"头后面剩
    // 多少字节"根本推不出来，但"最后 pixelBytes 字节一定是像素"是稳的。
    pixelOffset = size - pixelBytes;

    // 8bpp 还有一张调色板（跟在头后面，每项 4 字节 RGBQUAD）。
    uint32_t palette[256] = {};
    int paletteEntries = 0;
    if (bitCount == 8) {
        const auto* bmi = reinterpret_cast<const BITMAPINFOHEADER*>(bytes);
        UINT used = 0;
        if (headerSize >= 40) used = bmi->biClrUsed;
        if (used == 0 || used > 256) used = 256;   // 0 = 规范上"全 256 项"
        int declaredEntries = static_cast<int>(used);
        // 调色板能有多少项 = 头部之后、像素之前的字节数（按像素锚定位置算）。
        const size_t paletteSpace = pixelOffset > headerSize ? pixelOffset - headerSize : 0;
        int availableEntries = static_cast<int>(paletteSpace / 4);
        if (availableEntries > 256) availableEntries = 256;
        paletteEntries = declaredEntries < availableEntries ? declaredEntries : availableEntries;
        if (paletteEntries <= 0) {
            // 8bpp 却一张调色板都没有（头之后紧接着就是像素）—— 索引没法映射成颜色。
            // 这种通常是"别的程序合成歪的"（Wine 把 8bpp CF_DIB 转成 V5 时就会丢掉
            // 调色板）：当成解析**失败**，让调用方继续试下一个格式（CF_DIB/CF_BITMAP），
            // 而不是给出一张全黑的图。
            composeknLog("clipboard: 8bpp 位图没有调色板（%dx%d，块 %zu 字节）", w, h, size);
            return false;
        }
        for (int i = 0; i < paletteEntries; ++i) {
            const auto* entry = bytes + headerSize + static_cast<size_t>(i) * 4;
            // RGBQUAD: B, G, R, reserved
            palette[i] = (static_cast<uint32_t>(entry[3]) << 24) |
                         (static_cast<uint32_t>(entry[2]) << 16) |
                         (static_cast<uint32_t>(entry[1]) << 8) |
                         static_cast<uint32_t>(entry[0]);
        }
    }
    out.assign(static_cast<size_t>(w) * h * 4, 0);
    for (int32_t y = 0; y < h; ++y) {
        const int32_t srcY = topDown ? y : (h - 1 - y);
        const auto* row = bytes + pixelOffset + srcStride * static_cast<size_t>(srcY);
        uint8_t* dst = out.data() + static_cast<size_t>(y) * w * 4;
        for (int32_t x = 0; x < w; ++x) {
            if (bitCount == 32) {
                dst[x * 4 + 0] = row[x * 4 + 0];
                dst[x * 4 + 1] = row[x * 4 + 1];
                dst[x * 4 + 2] = row[x * 4 + 2];
                dst[x * 4 + 3] = row[x * 4 + 3];
            } else if (bitCount == 24) {
                dst[x * 4 + 0] = row[x * 3 + 0];
                dst[x * 4 + 1] = row[x * 3 + 1];
                dst[x * 4 + 2] = row[x * 3 + 2];
                dst[x * 4 + 3] = 0xFF;
            } else {
                const uint8_t index = row[x];
                const uint32_t entry = index < paletteEntries ? palette[index] : 0xFF000000u;
                dst[x * 4 + 0] = static_cast<uint8_t>(entry & 0xFF);
                dst[x * 4 + 1] = static_cast<uint8_t>((entry >> 8) & 0xFF);
                dst[x * 4 + 2] = static_cast<uint8_t>((entry >> 16) & 0xFF);
                dst[x * 4 + 3] = 0xFF;   // 调色板 DIB 没有 alpha
            }
        }
    }
    width = w;
    height = h;
    return true;
}

/** BGRA（自上而下）-> CF_DIBV5 字节流（BITMAPV5HEADER + BI_BITFIELDS + 自下而上）。 */
static std::vector<uint8_t> buildDibV5(int32_t width, int32_t height, const uint8_t* bgra) {
    const size_t stride = static_cast<size_t>(width) * 4;
    std::vector<uint8_t> out(sizeof(BITMAPV5HEADER) + stride * static_cast<size_t>(height), 0);
    auto* header = reinterpret_cast<BITMAPV5HEADER*>(out.data());
    header->bV5Size = sizeof(BITMAPV5HEADER);
    header->bV5Width = width;
    header->bV5Height = height;             // 正数 = 自下而上（最通用的形式）
    header->bV5Planes = 1;
    header->bV5BitCount = 32;
    header->bV5Compression = BI_BITFIELDS;
    header->bV5SizeImage = static_cast<DWORD>(stride * static_cast<size_t>(height));
    header->bV5RedMask = 0x00FF0000;
    header->bV5GreenMask = 0x0000FF00;
    header->bV5BlueMask = 0x000000FF;
    header->bV5AlphaMask = 0xFF000000;
    // LCS_sRGB 在 Windows 头里就是多字符常量 'sRGB'（不是字符串），直接用字面量
    // 会触发 -Wmultichar；这里显式拼出同样的 4 字节值，语义一致。
    header->bV5CSType = (static_cast<DWORD>('s') << 24) | (static_cast<DWORD>('R') << 16) |
                        (static_cast<DWORD>('G') << 8) | static_cast<DWORD>('B');
    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* src = bgra + static_cast<size_t>(y) * stride;
        uint8_t* dst = out.data() + sizeof(BITMAPV5HEADER) + static_cast<size_t>(height - 1 - y) * stride;
        std::memcpy(dst, src, stride);
    }
    return out;
}

/**
 * 传统 `CF_DIB`（40 字节 BITMAPINFOHEADER + 32bpp BI_RGB + 自下而上）。
 *
 * 为什么要同时放这一份：`CF_DIBV5` 语义明确（带掩码/色彩空间），但**老程序**（画图、
 * 一部分 Office 版本）只认 `CF_DIB`；只放 V5 的话它们粘贴出来是空的。两份放一起是
 * 桌面应用（Chrome 等）的常规做法：谁认哪份就拿哪份。
 * 注意传统 32bpp DIB 的第 4 个字节在规范里是"未使用"，所以不透明像素的颜色照样正确。
 */
static std::vector<uint8_t> buildDibLegacy(int32_t width, int32_t height, const uint8_t* bgra) {
    const size_t stride = static_cast<size_t>(width) * 4;
    std::vector<uint8_t> out(sizeof(BITMAPINFOHEADER) + stride * static_cast<size_t>(height), 0);
    auto* header = reinterpret_cast<BITMAPINFOHEADER*>(out.data());
    header->biSize = sizeof(BITMAPINFOHEADER);
    header->biWidth = width;
    header->biHeight = height;             // 正数 = 自下而上
    header->biPlanes = 1;
    header->biBitCount = 32;
    header->biCompression = BI_RGB;
    header->biSizeImage = static_cast<DWORD>(stride * static_cast<size_t>(height));
    for (int32_t y = 0; y < height; ++y) {
        const uint8_t* src = bgra + static_cast<size_t>(y) * stride;
        uint8_t* dst = out.data() + sizeof(BITMAPINFOHEADER) + static_cast<size_t>(height - 1 - y) * stride;
        std::memcpy(dst, src, stride);
    }
    return out;
}

/** 标准剪贴板格式号 -> 名字（日志/自检要看懂）。 */
static const char* standardClipboardFormatName(UINT format) {
    switch (format) {
        case CF_TEXT: return "TEXT";
        case CF_BITMAP: return "BITMAP";
        case CF_METAFILEPICT: return "METAFILEPICT";
        case CF_SYLK: return "SYLK";
        case CF_DIF: return "DIF";
        case CF_TIFF: return "TIFF";
        case CF_OEMTEXT: return "OEMTEXT";
        case CF_DIB: return "DIB";
        case CF_PALETTE: return "PALETTE";
        case CF_PENDATA: return "PENDATA";
        case CF_RIFF: return "RIFF";
        case CF_WAVE: return "WAVE";
        case CF_UNICODETEXT: return "UNICODETEXT";
        case CF_ENHMETAFILE: return "ENHMETAFILE";
        case CF_HDROP: return "HDROP";
        case CF_LOCALE: return "LOCALE";
        case CF_DIBV5: return "DIBV5";
        default: return nullptr;
    }
}

/** 当前打开着的剪贴板里所有格式的名字，拼成 "[A, B, C]"。 */
static std::string enumerateClipboardFormats() {
    std::string out;
    UINT format = 0;
    int count = 0;
    while ((format = EnumClipboardFormats(format)) != 0) {
        if (count > 0) out += ", ";
        const char* standard = standardClipboardFormatName(format);
        if (standard != nullptr) {
            out += standard;
        } else {
            char name[128] = {0};
            if (GetClipboardFormatNameA(format, name, sizeof(name)) > 0) {
                out += name;
            } else {
                out += "?";
            }
        }
        ++count;
        if (count >= 24) {   // 防呆：有的程序会放一堆私有格式
            out += ", …";
            break;
        }
    }
    if (count == 0) out = "（空）";
    return out;
}

/** 剪贴板上有没有"跟图片/文件有关"的格式（决定读图失败要不要写诊断日志）。 */
static bool hasImageLikeClipboardFormat() {
    static const UINT kInteresting[] = {
        CF_BITMAP, CF_DIB, CF_DIBV5, CF_HDROP,
        RegisterClipboardFormatA("PNG"), RegisterClipboardFormatA("JFIF"),
    };
    for (UINT format : kInteresting) {
        if (format != 0 && IsClipboardFormatAvailable(format)) return true;
    }
    return false;
}

/** CF_BITMAP（裸 HBITMAP，没有 DIB 头）-> 自上而下 BGRA。 */
static bool parseBitmapHandle(HBITMAP handle, std::vector<uint8_t>& out, int32_t& width, int32_t& height) {
    if (handle == nullptr) return false;
    BITMAP bm = {};
    if (GetObjectA(handle, sizeof(bm), &bm) == 0) {
        composeknLog("clipboard: CF_BITMAP 的 GetObject 失败 err=%lu", (unsigned long)GetLastError());
        return false;
    }
    if (bm.bmWidth <= 0 || bm.bmHeight <= 0 || bm.bmWidth > 20000 || bm.bmHeight > 20000) {
        composeknLog("clipboard: CF_BITMAP 尺寸异常 %ldx%ld", (long)bm.bmWidth, (long)bm.bmHeight);
        return false;
    }

    BITMAPINFO info = {};
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = bm.bmWidth;
    // 负数 = 要自上而下的行（省得我们再翻一次）
    info.bmiHeader.biHeight = -bm.bmHeight;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    info.bmiHeader.biSizeImage = static_cast<DWORD>(bm.bmWidth) * 4 * static_cast<DWORD>(bm.bmHeight);

    HDC dc = GetDC(nullptr);
    if (dc == nullptr) return false;
    out.assign(static_cast<size_t>(bm.bmWidth) * bm.bmHeight * 4, 0);
    const int lines = GetDIBits(
        dc, handle, 0, static_cast<UINT>(bm.bmHeight), out.data(), &info, DIB_RGB_COLORS
    );
    ReleaseDC(nullptr, dc);
    if (lines == 0) {
        composeknLog("clipboard: CF_BITMAP 的 GetDIBits 失败 err=%lu（%ldx%ld %ubpp）",
                     (unsigned long)GetLastError(), (long)bm.bmWidth, (long)bm.bmHeight,
                     (unsigned)bm.bmBitsPixel);
        return false;
    }
    // HBITMAP（DDB）里通常没有 alpha 信息，GetDIBits 会把第 4 字节填 0/垃圾 ——
    // 一律当不透明处理（不然整张图会"全透明"看不见）。
    for (size_t i = 3; i < out.size(); i += 4) out[i] = 0xFF;
    width = bm.bmWidth;
    height = bm.bmHeight;
    return true;
}

/** 把一块字节放进剪贴板（HGLOBAL 所有权的约定见调用点）。 */
static HGLOBAL bytesToGlobal(const std::vector<uint8_t>& bytes) {
    HGLOBAL global = GlobalAlloc(GMEM_MOVEABLE, bytes.size());
    if (global == nullptr) return nullptr;
    void* dst = GlobalLock(global);
    if (dst == nullptr) {
        GlobalFree(global);
        return nullptr;
    }
    if (!bytes.empty()) std::memcpy(dst, bytes.data(), bytes.size());
    GlobalUnlock(global);
    return global;
}

static HGLOBAL stringToGlobal(const std::string& utf8) {
    return bytesToGlobal(std::vector<uint8_t>(utf8.begin(), utf8.end()));
}

}  // namespace

extern "C" int32_t composekn_win32_clipboard_get_html(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size) {
    (void)window;
    if (!OpenClipboard(nullptr)) return -1;
    HANDLE handle = GetClipboardData(RegisterClipboardFormatA(kHtmlFormatName));
    int32_t result = -1;
    if (handle != nullptr) {
        void* data = GlobalLock(handle);
        if (data != nullptr) {
            std::string fragment;
            if (parseCfHtml(data, GlobalSize(handle), fragment)) {
                const int32_t needed = static_cast<int32_t>(fragment.size());
                if (buffer == nullptr || buffer_size < needed) {
                    result = needed;   // 两段式：告诉你需要多大
                } else {
                    if (needed > 0) std::memcpy(buffer, fragment.data(), static_cast<size_t>(needed));
                    result = needed;
                }
            }
            GlobalUnlock(handle);
        }
    }
    CloseClipboard();
    return result;
}

extern "C" void composekn_win32_clipboard_set_html(ComposeKNWin32Window* window, const char* utf8_html) {
    composekn_win32_clipboard_set_rich(window, nullptr, utf8_html, nullptr, 0, 0, nullptr, nullptr);
}

extern "C" int32_t composekn_win32_clipboard_get_rtf(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size) {
    (void)window;
    if (!OpenClipboard(nullptr)) return -1;
    HANDLE handle = GetClipboardData(RegisterClipboardFormatA(kRtfFormatName));
    int32_t result = -1;
    if (handle != nullptr) {
        void* data = GlobalLock(handle);
        if (data != nullptr) {
            const int32_t size = static_cast<int32_t>(GlobalSize(handle));
            if (buffer == nullptr || buffer_size < size) {
                result = size;
            } else {
                if (size > 0) std::memcpy(buffer, data, static_cast<size_t>(size));
                result = size;
            }
            GlobalUnlock(handle);
        }
    }
    CloseClipboard();
    return result;
}

extern "C" void composekn_win32_clipboard_set_rtf(ComposeKNWin32Window* window, const char* utf8_rtf) {
    composekn_win32_clipboard_set_rich(window, nullptr, nullptr, utf8_rtf, 0, 0, nullptr, nullptr);
}

extern "C" int32_t composekn_win32_clipboard_get_image(
    ComposeKNWin32Window* window, uint8_t* buffer, int32_t buffer_size, int32_t* out_size) {
    (void)window;
    if (!OpenClipboard(nullptr)) return -1;

    // 按顺序试三种格式，**解析失败也继续试下一个**：
    //   CF_DIBV5（语义最全）-> CF_DIB（传统）-> CF_BITMAP（裸 HBITMAP）。
    // 为什么"失败也要继续"：真机上 V5 可能是别的程序合成歪的/被截断的（Wine 就会把
    // 8bpp 的 CF_DIB 合成出一个少了调色板的 V5），而旁边那份 CF_DIB 往往是好的。
    HANDLE candidates[2] = { GetClipboardData(CF_DIBV5), GetClipboardData(CF_DIB) };
    bool got = false;
    std::vector<uint8_t> pixels;
    int32_t width = 0;
    int32_t height = 0;
    for (HANDLE handle : candidates) {
        if (handle == nullptr) continue;
        void* data = GlobalLock(handle);
        if (data == nullptr) continue;
        got = parseDib(data, GlobalSize(handle), pixels, width, height);
        GlobalUnlock(handle);
        if (got) break;
    }
    if (!got) {
        HBITMAP bitmap = static_cast<HBITMAP>(GetClipboardData(CF_BITMAP));
        if (bitmap != nullptr) {
            got = parseBitmapHandle(bitmap, pixels, width, height);
        } else if (candidates[0] == nullptr && candidates[1] == nullptr) {
            composeknLog("clipboard: CF_DIBV5/CF_DIB/CF_BITMAP 三个都拿不到句柄");
        }
    }

    int32_t result = -1;
    if (got && !pixels.empty()) {
        const int32_t needed = static_cast<int32_t>(pixels.size());
        if (buffer == nullptr || buffer_size < needed) {
            result = needed;   // 两段式：先告诉调用方要多大
        } else {
            std::memcpy(buffer, pixels.data(), static_cast<size_t>(needed));
            result = needed;
        }
        if (out_size != nullptr) {
            out_size[0] = width;
            out_size[1] = height;
        }
    }

    if (result < 0 && hasImageLikeClipboardFormat()) {
        // 剪贴板上**确实有**图片/文件类格式，但没读出来 —— 把当前全部格式打出来。
        // 真机排查"截图了但粘不进"就靠这行。
        //
        // 只在有相关格式时打：否则每次粘贴文本（`getClip()` 会顺带问一次图片）都会刷
        // 一行"读图片失败"，日志就没法看了。
        composeknLog("clipboard: 读图片失败，当前可用格式 = [%s]", enumerateClipboardFormats().c_str());
    }
    CloseClipboard();
    return result;
}

extern "C" void composekn_win32_clipboard_set_image(
    ComposeKNWin32Window* window, int32_t width, int32_t height, const uint8_t* bgra) {
    composekn_win32_clipboard_set_rich(window, nullptr, nullptr, nullptr, width, height, bgra, nullptr);
}

extern "C" int32_t composekn_win32_clipboard_get_raw_hex(
    ComposeKNWin32Window* window, const char* format_name, char* buffer, int32_t buffer_size) {
    (void)window;
    const UINT format = resolveClipboardFormat(format_name);
    if (format == 0) return -1;
    if (!OpenClipboard(nullptr)) return -1;
    HANDLE handle = GetClipboardData(format);
    const int32_t result = copyHex(handle, buffer, buffer_size);
    CloseClipboard();
    return result;
}

extern "C" void composekn_win32_clipboard_set_rich(
    ComposeKNWin32Window* window,
    const char* utf8_text,
    const char* utf8_html,
    const char* utf8_rtf,
    int32_t image_width,
    int32_t image_height,
    const uint8_t* bgra,
    const char* utf8_files) {
    (void)window;
    const bool hasText = utf8_text != nullptr;
    const bool hasHtml = utf8_html != nullptr;
    const bool hasRtf = utf8_rtf != nullptr;
    const bool hasImage = bgra != nullptr && image_width > 0 && image_height > 0;
    const bool hasFiles = utf8_files != nullptr && utf8_files[0] != '\0';
    if (!hasText && !hasHtml && !hasRtf && !hasImage && !hasFiles) return;

    // 先把所有 HGLOBAL 准备好：OpenClipboard 之后再分配会失败（剪贴板被占用），
    // 而且一旦 EmptyClipboard，任何一步失败都会留下"空剪贴板"。
    HGLOBAL textGlobal = hasText ? utf8ToUnicodeTextGlobal(utf8_text) : nullptr;
    std::vector<uint8_t> htmlBlob;
    HGLOBAL htmlGlobal = nullptr;
    UINT htmlFormat = 0;
    if (hasHtml) {
        const std::string blob = buildCfHtml(std::string(utf8_html));
        htmlBlob.assign(blob.begin(), blob.end());
        htmlFormat = RegisterClipboardFormatA(kHtmlFormatName);
        if (htmlFormat != 0) htmlGlobal = bytesToGlobal(htmlBlob);
    }
    HGLOBAL rtfGlobal = nullptr;
    UINT rtfFormat = 0;
    if (hasRtf) {
        rtfFormat = RegisterClipboardFormatA(kRtfFormatName);
        if (rtfFormat != 0) rtfGlobal = stringToGlobal(std::string(utf8_rtf));
    }
    HGLOBAL imageGlobal = nullptr;
    HGLOBAL imageLegacyGlobal = nullptr;
    if (hasImage) {
        imageGlobal = bytesToGlobal(buildDibV5(image_width, image_height, bgra));
        imageLegacyGlobal = bytesToGlobal(buildDibLegacy(image_width, image_height, bgra));
    }
    HGLOBAL filesGlobal = hasFiles ? buildHDropGlobal(utf8_files) : nullptr;
    HGLOBAL dropEffectGlobal = hasFiles ? buildPreferredDropEffectGlobal(DROPEFFECT_COPY) : nullptr;
    UINT dropEffectFormat = 0;
    if (hasFiles) {
        dropEffectFormat = RegisterClipboardFormat(CFSTR_PREFERREDDROPEFFECT);
    }

    if (!OpenClipboard(nullptr)) {
        if (textGlobal) GlobalFree(textGlobal);
        if (htmlGlobal) GlobalFree(htmlGlobal);
        if (rtfGlobal) GlobalFree(rtfGlobal);
        if (imageGlobal) GlobalFree(imageGlobal);
        if (imageLegacyGlobal) GlobalFree(imageLegacyGlobal);
        if (filesGlobal) GlobalFree(filesGlobal);
        if (dropEffectGlobal) GlobalFree(dropEffectGlobal);
        composeknLog("clipboard: OpenClipboard 失败，富文本写入放弃");
        return;
    }
    EmptyClipboard();
    int written = 0;
    if (textGlobal != nullptr && SetClipboardData(CF_UNICODETEXT, textGlobal) != nullptr) {
        ++written;
    } else if (textGlobal != nullptr) {
        GlobalFree(textGlobal);
    }
    if (htmlGlobal != nullptr && SetClipboardData(htmlFormat, htmlGlobal) != nullptr) {
        ++written;
    } else if (htmlGlobal != nullptr) {
        GlobalFree(htmlGlobal);
    }
    if (rtfGlobal != nullptr && SetClipboardData(rtfFormat, rtfGlobal) != nullptr) {
        ++written;
    } else if (rtfGlobal != nullptr) {
        GlobalFree(rtfGlobal);
    }
    if (imageGlobal != nullptr && SetClipboardData(CF_DIBV5, imageGlobal) != nullptr) {
        ++written;
    } else if (imageGlobal != nullptr) {
        GlobalFree(imageGlobal);
    }
    if (imageLegacyGlobal != nullptr && SetClipboardData(CF_DIB, imageLegacyGlobal) != nullptr) {
        ++written;
    } else if (imageLegacyGlobal != nullptr) {
        GlobalFree(imageLegacyGlobal);
    }
    if (filesGlobal != nullptr && SetClipboardData(CF_HDROP, filesGlobal) != nullptr) {
        ++written;
    } else if (filesGlobal != nullptr) {
        GlobalFree(filesGlobal);
    }
    if (dropEffectGlobal != nullptr && dropEffectFormat != 0 &&
        SetClipboardData(dropEffectFormat, dropEffectGlobal) != nullptr) {
        ++written;
    } else if (dropEffectGlobal != nullptr) {
        GlobalFree(dropEffectGlobal);
    }
    CloseClipboard();
    composeknLog(
        "clipboard: 写入 %d 个格式（文本=%d HTML=%zu 字节 RTF=%d 位图=%d 文件=%d）",
        written, hasText ? 1 : 0, htmlBlob.size(), hasRtf ? 1 : 0, hasImage ? 1 : 0, hasFiles ? 1 : 0
    );
}

extern "C" void composekn_win32_clipboard_set_text(ComposeKNWin32Window* window, const char* text) {
    composekn_win32_clipboard_set_rich(window, text, nullptr, nullptr, 0, 0, nullptr, nullptr);
}

// ---------------------------------------------------------------------------
// 剪贴板：文件列表（CF_HDROP）、CF_BITMAP 回退、格式诊断
//
// 背景（真机问题）：Win10 的截图工具往剪贴板放什么格式，不同版本不一样 —— 有的放
// CF_DIB/CF_DIBV5，有的只放 CF_BITMAP（裸 HBITMAP）。所以：
//   * 读图：CF_DIBV5 -> CF_DIB -> **CF_BITMAP**（GDI 转一遍）三级回退；
//   * 全都读不到时，把**当前剪贴板上所有格式**打进日志 —— 用户截一张图粘一次，
//     日志就能告诉我们该怎么补。
// 另外：在资源管理器里复制文件，剪贴板上是 CF_HDROP（文件路径列表）而不是位图，
// 所以"复制文件 -> 粘贴"要有单独一条读路径（拖放那条已经实现了，这里复用解码）。
// ---------------------------------------------------------------------------

namespace {


}  // namespace

extern "C" int32_t composekn_win32_clipboard_get_files(
    ComposeKNWin32Window* window, char* buffer, int32_t buffer_size) {
    (void)window;
    if (!OpenClipboard(nullptr)) return -1;
    HANDLE handle = GetClipboardData(CF_HDROP);
    int32_t result = -1;
    if (handle != nullptr) {
        HDROP drop = static_cast<HDROP>(GlobalLock(handle));
        if (drop != nullptr) {
            std::string files;
            readHDropFiles(drop, files);
            GlobalUnlock(handle);
            if (!files.empty()) {
                const int32_t needed = static_cast<int32_t>(files.size());
                if (buffer == nullptr || buffer_size < needed) {
                    result = needed;
                } else {
                    std::memcpy(buffer, files.data(), static_cast<size_t>(needed));
                    result = needed;
                }
            }
        }
    }
    CloseClipboard();
    return result;
}

/**
 * 自检用：**直接**把一段 DIB 字节喂给解码器（不经过剪贴板）。
 *
 * 为什么要这条：Wine 的剪贴板会对 8bpp 的 CF_DIB 做一次有损转换（合成出来的 V5 少了
 * 调色板、长度也不对），于是"8bpp 调色板解码"这条代码路径在自动化里跑不到。真机上
 * 从画图/老程序复制的 256 色图走的正是这条路径，所以这里绕开剪贴板直接测解码器。
 *
 * 两段式：out_size==0 时只回需要的字节数；否则写像素并回写入字节数。
 */
extern "C" int32_t composekn_win32_test_decode_dib(
    const uint8_t* dib, int32_t dib_size,
    uint8_t* out_bgra, int32_t out_size, int32_t* out_dims) {
    if (dib == nullptr || dib_size <= 0) return -1;
    std::vector<uint8_t> pixels;
    int32_t width = 0;
    int32_t height = 0;
    if (!parseDib(dib, static_cast<size_t>(dib_size), pixels, width, height)) return -1;
    const int32_t needed = static_cast<int32_t>(pixels.size());
    if (out_dims != nullptr) {
        out_dims[0] = width;
        out_dims[1] = height;
    }
    if (out_bgra == nullptr || out_size < needed) return needed;
    std::memcpy(out_bgra, pixels.data(), static_cast<size_t>(needed));
    return needed;
}

extern "C" bool composekn_win32_clipboard_test_set_files(
    ComposeKNWin32Window* window, const char* utf8_paths) {
    // 生产路径走 set_rich（含 Preferred DropEffect）；自检助手只是薄包装。
    if (utf8_paths == nullptr || utf8_paths[0] == '\0') return false;
    composekn_win32_clipboard_set_rich(
        window, nullptr, nullptr, nullptr, 0, 0, nullptr, utf8_paths);
    return true;
}

/** 自检用：放一张只有 CF_BITMAP 的图（模拟"截图工具只给裸 HBITMAP"）。 */
extern "C" bool composekn_win32_clipboard_test_set_bitmap(
    ComposeKNWin32Window* window, int32_t width, int32_t height, const uint8_t* bgra) {
    (void)window;
    if (width <= 0 || height <= 0 || bgra == nullptr) return false;
    // 用传统 40 字节 BITMAPINFOHEADER 那份去建 HBITMAP：CreateDIBitmap 的文档
    // 只保证认 BITMAPINFOHEADER，喂 V5 头（biSize=124）有的 GDI 实现会直接失败。
    const std::vector<uint8_t> dib = buildDibLegacy(width, height, bgra);
    const auto* header = reinterpret_cast<const BITMAPINFOHEADER*>(dib.data());
    const void* bits = dib.data() + sizeof(BITMAPINFOHEADER);
    HDC dc = GetDC(nullptr);
    if (dc == nullptr) return false;
    HBITMAP bitmap = CreateDIBitmap(dc, header, CBM_INIT, bits, reinterpret_cast<const BITMAPINFO*>(header), DIB_RGB_COLORS);
    ReleaseDC(nullptr, dc);
    if (bitmap == nullptr) return false;
    if (!OpenClipboard(nullptr)) {
        DeleteObject(bitmap);
        return false;
    }
    EmptyClipboard();
    const bool ok = SetClipboardData(CF_BITMAP, bitmap) != nullptr;
    if (!ok) DeleteObject(bitmap);
    CloseClipboard();
    composeknLog("clipboard(test): 放入 CF_BITMAP %dx%d", width, height);
    return ok;
}

/** 自检用：放一张 8bpp 调色板 CF_DIB（模拟老程序/256 色画图）。 */
extern "C" bool composekn_win32_clipboard_test_set_dib8(
    ComposeKNWin32Window* window, int32_t width, int32_t height, const uint8_t* indices, int32_t index_count) {
    (void)window;
    if (width <= 0 || height <= 0 || indices == nullptr) return false;
    // 固定 4 色调色板：红/绿/蓝/白（索引 0..3）
    static const uint32_t kPalette[4] = { 0xFFFF0000u, 0xFF00FF00u, 0xFF0000FFu, 0xFFFFFFFFu };
    const size_t stride = ((static_cast<size_t>(width) + 3) / 4) * 4;   // 8bpp 行 4 字节对齐
    std::vector<uint8_t> dib(sizeof(BITMAPINFOHEADER) + 4 * 4 + stride * static_cast<size_t>(height), 0);
    auto* bmi = reinterpret_cast<BITMAPINFOHEADER*>(dib.data());
    bmi->biSize = sizeof(BITMAPINFOHEADER);
    bmi->biWidth = width;
    bmi->biHeight = height;
    bmi->biPlanes = 1;
    bmi->biBitCount = 8;
    bmi->biCompression = BI_RGB;
    bmi->biClrUsed = 4;
    bmi->biSizeImage = static_cast<DWORD>(stride * static_cast<size_t>(height));
    for (int i = 0; i < 4; ++i) {
        const uint32_t color = kPalette[i];
        uint8_t* entry = dib.data() + sizeof(BITMAPINFOHEADER) + static_cast<size_t>(i) * 4;
        entry[0] = static_cast<uint8_t>(color & 0xFF);           // B
        entry[1] = static_cast<uint8_t>((color >> 8) & 0xFF);    // G
        entry[2] = static_cast<uint8_t>((color >> 16) & 0xFF);   // R
        entry[3] = 0;
    }
    for (int32_t y = 0; y < height; ++y) {
        for (int32_t x = 0; x < width; ++x) {
            const int32_t src = y * width + x;
            uint8_t value = 0;
            if (src < index_count) value = static_cast<uint8_t>(indices[src] & 0x03);
            // 自下而上
            dib[sizeof(BITMAPINFOHEADER) + 16 + stride * static_cast<size_t>(height - 1 - y) + static_cast<size_t>(x)] = value;
        }
    }
    HGLOBAL global = bytesToGlobal(dib);
    if (global == nullptr) return false;
    if (!OpenClipboard(nullptr)) {
        GlobalFree(global);
        return false;
    }
    EmptyClipboard();
    const bool ok = SetClipboardData(CF_DIB, global) != nullptr;
    if (!ok) GlobalFree(global);
    CloseClipboard();
    composeknLog("clipboard(test): 放入 8bpp CF_DIB %dx%d", width, height);
    return ok;
}

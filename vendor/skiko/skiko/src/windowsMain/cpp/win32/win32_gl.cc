/**
 * ComposeKN Windows OpenGL/WGL 桥（GPU 后端）。
 *
 * 与上游 skiko 的 K/N GL 实现同构：
 *   linuxMain/cpp/wayland/wayland_egl_gl.cc       -> 本文件（EGL -> WGL）
 *   LinuxWaylandOpenGLContextHandler              -> WindowsGLContextHandler
 *   LinuxWaylandOpenGLRedrawer                    -> WindowsGLRedrawer
 *
 * 分工刻意与上游一致：
 *   - C 侧只管**平台上下文**：建/销毁 WGL 上下文、make current、viewport、
 *     swap buffers、swap interval，以及读 GL 的 framebuffer binding；
 *   - Skia 的 GPU 上下文（`DirectContext.makeGL()`）在 **Kotlin 侧**创建
 *     （K/N 的 skia binding 已经提供，见 `org.jetbrains.skia.DirectContext.makeGL`）——
 *     所以本文件不需要包含任何 Skia 头，也不用改 native bridge 的编译配置。
 *
 * 多窗口（v0.5.23）：每个 HWND 一份独立的 HDC/HGLRC（不再用进程单例）。
 * 单例时第二窗 create 是 no-op、swap 打到第一窗、destroy 拆掉唯一上下文 →
 * 主窗字体花屏、副窗白板、关副窗后卡死。
 */
#include "win32_bridge.h"

#include <windows.h>
#include <GL/gl.h>

#include <cstdarg>
#include <cstdio>
#include <unordered_map>

// mingw 的 GL/gl.h 只到 GL 1.1：这两个常量（GL 3.0+）需要自己定义。
// 值取自 khronos 的 glext.h（GL_FRAMEBUFFER_BINDING 与 GL_DRAW_FRAMEBUFFER_BINDING 同值）。
#ifndef GL_DRAW_FRAMEBUFFER_BINDING
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#endif
#ifndef GL_FRAMEBUFFER_BINDING
#define GL_FRAMEBUFFER_BINDING 0x8CA6
#endif

namespace {

/** 每个窗口一份 WGL 上下文（对齐 Wayland：每窗一个 EGL context/surface）。 */
struct ComposeKNGLContext {
    HDC dc = nullptr;
    HGLRC rc = nullptr;
    HWND hwnd = nullptr;
    bool ownedDc = false;
};

std::unordered_map<ComposeKNWin32Window*, ComposeKNGLContext*> g_glByWindow;

ComposeKNGLContext* glOf(ComposeKNWin32Window* window) {
    if (window == nullptr) return nullptr;
    auto it = g_glByWindow.find(window);
    return it == g_glByWindow.end() ? nullptr : it->second;
}

/**
 * 本文件自己的日志入口：`composeknLog` 是 win32_window.cc 里的匿名命名空间静态函数，
 * 跨文件不可见；这里转发到导出的 `composekn_win32_log`（它负责加时间戳 + 落盘 + flush）。
 */
void glLog(const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    composekn_win32_log(buffer);
}

typedef BOOL(WINAPI* PFNWGLSWAPINTERVALEXTPROC)(int);

void logGlInfo(const char* tag) {
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    glLog("gl: %s GL_VERSION=%s GL_RENDERER=%s GL_VENDOR=%s",
                 tag,
                 version != nullptr ? version : "(null)",
                 renderer != nullptr ? renderer : "(null)",
                 vendor != nullptr ? vendor : "(null)");
}

void destroyGlContext(ComposeKNGLContext* gl) {
    if (gl == nullptr) return;
    if (wglGetCurrentContext() == gl->rc) {
        wglMakeCurrent(nullptr, nullptr);
    }
    if (gl->rc != nullptr) {
        wglDeleteContext(gl->rc);
        gl->rc = nullptr;
    }
    if (gl->ownedDc && gl->hwnd != nullptr && gl->dc != nullptr) {
        ReleaseDC(gl->hwnd, gl->dc);
        gl->dc = nullptr;
    }
    delete gl;
}

}  // namespace

extern "C" bool composekn_win32_gl_create(ComposeKNWin32Window* window) {
    if (window == nullptr) return false;
    if (glOf(window) != nullptr) {
        // 幂等：同一窗口重复 create 直接成功（不复用别的窗的上下文）。
        return true;
    }

    HWND hwnd = static_cast<HWND>(composekn_win32_hwnd(window));
    if (hwnd == nullptr) {
        glLog("gl: create 失败（hwnd 为空）");
        return false;
    }

    auto* gl = new ComposeKNGLContext();
    gl->hwnd = hwnd;
    gl->dc = GetDC(hwnd);
    if (gl->dc == nullptr) {
        glLog("gl: GetDC 失败");
        delete gl;
        return false;
    }
    gl->ownedDc = true;

    PIXELFORMATDESCRIPTOR pfd;
    ZeroMemory(&pfd, sizeof(pfd));
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    // 双缓冲 + 支持 OpenGL：present 就是 SwapBuffers。
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;

    const int format = ChoosePixelFormat(gl->dc, &pfd);
    if (format == 0 || !SetPixelFormat(gl->dc, format, &pfd)) {
        glLog("gl: ChoosePixelFormat/SetPixelFormat 失败 (fmt=%d err=%lu)",
                     format, (unsigned long)GetLastError());
        ReleaseDC(hwnd, gl->dc);
        delete gl;
        return false;
    }

    gl->rc = wglCreateContext(gl->dc);
    if (gl->rc == nullptr) {
        glLog("gl: wglCreateContext 失败 err=%lu", (unsigned long)GetLastError());
        ReleaseDC(hwnd, gl->dc);
        delete gl;
        return false;
    }
    if (!wglMakeCurrent(gl->dc, gl->rc)) {
        glLog("gl: wglMakeCurrent 失败 err=%lu", (unsigned long)GetLastError());
        wglDeleteContext(gl->rc);
        ReleaseDC(hwnd, gl->dc);
        delete gl;
        return false;
    }

    g_glByWindow[window] = gl;
    glLog("gl: context created for hwnd=%p (windows=%zu)",
          reinterpret_cast<void*>(hwnd), g_glByWindow.size());
    logGlInfo("created");

    // 没拿到 3.3+ 的话 Skia 的 Ganesh GL 初始化会失败（上层会自动回退软件路径），
    // 这里只把原因写进日志，方便真机上区分「驱动不支持」与「我们的 bug」。
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    if (version != nullptr) {
        int major = 0, minor = 0;
        if (std::sscanf(version, "%d.%d", &major, &minor) == 2) {
            if (major < 3 || (major == 3 && minor < 3)) {
                glLog("gl: 警告 —— 上下文只有 GL %d.%d（Skia Ganesh 需要 3.3+），"
                             "预计会回退到软件路径", major, minor);
            }
        }
    }
    return true;
}

extern "C" bool composekn_win32_gl_make_current(ComposeKNWin32Window* window) {
    ComposeKNGLContext* gl = glOf(window);
    if (gl == nullptr || gl->rc == nullptr) return false;
    if (wglGetCurrentContext() == gl->rc && wglGetCurrentDC() == gl->dc) return true;
    return wglMakeCurrent(gl->dc, gl->rc) != FALSE;
}

extern "C" void composekn_win32_gl_viewport(int width, int height) {
    if (width > 0 && height > 0) {
        glViewport(0, 0, width, height);
    }
}

extern "C" int composekn_win32_gl_get_draw_framebuffer_binding(void) {
    GLint fbo = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbo);
    return static_cast<int>(fbo);
}

extern "C" void composekn_win32_gl_set_swap_interval(int interval) {
    // 调用方须先 gl_make_current(window)；用当前 DC（每窗独立上下文后不能再读全局单例）。
    HDC dc = wglGetCurrentDC();
    if (dc == nullptr) {
        glLog("gl: set_swap_interval 失败（没有 current WGL 上下文）");
        return;
    }
    // WGL_EXT_swap_interval：1 = 跟垂直同步对齐（上游 EGL 路径也是这么干的）。
    auto swapInterval = reinterpret_cast<PFNWGLSWAPINTERVALEXTPROC>(
        wglGetProcAddress("wglSwapIntervalEXT"));
    if (swapInterval == nullptr) {
        glLog("gl: 驱动没有 wglSwapIntervalEXT，跳过 vsync 设置");
        return;
    }
    const BOOL ok = swapInterval(interval);
    glLog("gl: wglSwapIntervalEXT(%d) -> %s", interval, ok ? "ok" : "failed");
}

extern "C" void composekn_win32_gl_swap_buffers(ComposeKNWin32Window* window) {
    ComposeKNGLContext* gl = glOf(window);
    if (gl == nullptr || gl->dc == nullptr) return;
    SwapBuffers(gl->dc);
}

extern "C" void composekn_win32_gl_destroy(ComposeKNWin32Window* window) {
    if (window == nullptr) return;
    auto it = g_glByWindow.find(window);
    if (it == g_glByWindow.end()) return;
    ComposeKNGLContext* gl = it->second;
    g_glByWindow.erase(it);
    HWND hwnd = gl != nullptr ? gl->hwnd : nullptr;
    destroyGlContext(gl);
    glLog("gl: context destroyed for hwnd=%p (windows=%zu)",
          reinterpret_cast<void*>(hwnd), g_glByWindow.size());
}

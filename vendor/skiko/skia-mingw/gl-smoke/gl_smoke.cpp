// ComposeKN Windows GPU 后端「阶段 0」可行性冒烟测试。
//
// 目的：验证「用 mingw 构建的 GNU-ABI Skia（Ganesh + OpenGL/WGL 后端）」
// 能不能真的在 Windows 上跑起来 —— 即上游桌面版走的那条路，在我们这套
// K/N mingwX64 自建 Skia 上是否成立。
//
// 做的事（最小闭环）：
//   1. 建一个隐藏 Win32 窗口 + WGL 上下文（真机 = opengl32.dll；Wine = Mesa）
//   2. GrGLInterfaces::MakeWin() -> GrDirectContexts::MakeGL()
//   3. 用默认 FBO 包一个 SkSurface（WrapBackendRenderTarget）
//   4. canvas->clear(RED) + flushAndSubmit + glFinish
//   5. readPixels 回读并断言像素 ≈ 红
//
// 通过 = GPU 后端链路（Skia GPU 上下文 + GL 后端 + 光栅化 + 回读）可用。
// 编译/运行见 run-gl-smoke.sh。
#include <windows.h>
#include <GL/gl.h>

#ifndef GL_SAMPLES
#define GL_SAMPLES 0x80A9
#endif
#ifndef GL_DRAW_FRAMEBUFFER_BINDING
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#endif

#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "include/core/SkCanvas.h"
#include "include/core/SkColor.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkImageInfo.h"
#include "include/core/SkSurface.h"
#include "include/gpu/ganesh/GrBackendSurface.h"
#include "include/gpu/ganesh/GrDirectContext.h"
#include "include/gpu/ganesh/SkSurfaceGanesh.h"
#include "include/gpu/ganesh/gl/GrGLBackendSurface.h"
#include "include/gpu/ganesh/gl/GrGLDirectContext.h"
#include "include/gpu/ganesh/gl/GrGLInterface.h"
#include "include/gpu/ganesh/gl/GrGLTypes.h"
#include "include/gpu/ganesh/gl/win/GrGLMakeWinInterface.h"

namespace {

constexpr int kW = 64;
constexpr int kH = 64;

int fail(const char* what) {
    std::printf("GL-SMOKE: FAIL  %s (gle=%lu)\n", what, (unsigned long)GetLastError());
    std::fflush(stdout);
    return 1;
}

LRESULT CALLBACK wndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    return DefWindowProcW(hwnd, msg, wp, lp);
}

HWND createHiddenWindow() {
    WNDCLASSW wc{};
    wc.lpfnWndProc = wndProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"ComposeKNGLSmoke";
    RegisterClassW(&wc);
    return CreateWindowExW(0, wc.lpszClassName, L"gl-smoke", WS_POPUP,
                           0, 0, kW, kH, nullptr, nullptr, wc.hInstance, nullptr);
}

}  // namespace

static bool useLegacyMakeGL = false;   // GrDirectContexts::MakeGL()（K/N binding 走这条）
static bool useSrgb = false;           // Surface 用 sRGB 还是 nullptr

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--legacy-gl") == 0) useLegacyMakeGL = true;
        if (std::strcmp(argv[i], "--srgb") == 0) useSrgb = true;
    }
    std::printf("GL-SMOKE: start (%dx%d) legacyMakeGL=%d srgb=%d\n",
                kW, kH, (int)useLegacyMakeGL, (int)useSrgb);
    std::fflush(stdout);

    HWND hwnd = createHiddenWindow();
    if (hwnd == nullptr) return fail("CreateWindowExW");

    HDC dc = GetDC(hwnd);
    if (dc == nullptr) return fail("GetDC");

    PIXELFORMATDESCRIPTOR pfd{};
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;
    const int fmt = ChoosePixelFormat(dc, &pfd);
    if (fmt == 0) return fail("ChoosePixelFormat");
    if (!SetPixelFormat(dc, fmt, &pfd)) return fail("SetPixelFormat");

    HGLRC rc = wglCreateContext(dc);
    if (rc == nullptr) return fail("wglCreateContext");
    if (!wglMakeCurrent(dc, rc)) return fail("wglMakeCurrent");

    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    std::printf("GL-SMOKE: GL_VERSION=%s  GL_RENDERER=%s\n",
                version ? version : "(null)", renderer ? renderer : "(null)");
    std::fflush(stdout);

    // 默认帧缓冲的实际参数（判断 Skia 的 format/stencil 是否与之匹配）
    GLint fb = -1, stencil = -1, samples = -1, red = -1, green = -1, blue = -1, alpha = -1,
          depth = -1;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fb);
    glGetIntegerv(GL_STENCIL_BITS, &stencil);
    glGetIntegerv(GL_SAMPLES, &samples);
    glGetIntegerv(GL_RED_BITS, &red);
    glGetIntegerv(GL_GREEN_BITS, &green);
    glGetIntegerv(GL_BLUE_BITS, &blue);
    glGetIntegerv(GL_ALPHA_BITS, &alpha);
    glGetIntegerv(GL_DEPTH_BITS, &depth);
    std::printf("GL-SMOKE: fb=%d rgba=%d%d%d%d depth=%d stencil=%d samples=%d\n",
                fb, red, green, blue, alpha, depth, stencil, samples);
    std::fflush(stdout);

    // ---- 1) Skia GPU 上下文 ----
    sk_sp<const GrGLInterface> glInterface;
    sk_sp<GrDirectContext> ctx;
    if (useLegacyMakeGL) {
        ctx = GrDirectContexts::MakeGL();      // K/N binding _nMakeGL() 走这条
    } else {
        glInterface = GrGLInterfaces::MakeWin();
        if (!glInterface) return fail("GrGLInterfaces::MakeWin");
        ctx = GrDirectContexts::MakeGL(glInterface);
    }
    if (!ctx) return fail("GrDirectContexts::MakeGL");
    std::printf("GL-SMOKE: GrDirectContext OK\n");
    std::fflush(stdout);

    // ---- 2) 默认 FBO 包成 SkSurface ----
    GrGLFramebufferInfo fboInfo{};
    fboInfo.fFBOID = 0;  // 默认帧缓冲
    fboInfo.fFormat = GL_RGBA8;
    GrBackendRenderTarget rt = GrBackendRenderTargets::MakeGL(kW, kH, 0, 8, fboInfo);
    if (!rt.isValid()) return fail("GrBackendRenderTargets::MakeGL");

    sk_sp<SkSurface> surface = SkSurfaces::WrapBackendRenderTarget(
            ctx.get(), rt, kBottomLeft_GrSurfaceOrigin, kRGBA_8888_SkColorType,
            useSrgb ? SkColorSpace::MakeSRGB() : nullptr, nullptr);
    if (!surface) return fail("SkSurfaces::WrapBackendRenderTarget");
    std::printf("GL-SMOKE: SkSurface(GPU) OK\n");
    std::fflush(stdout);

    // ---- 3) 画一帧并提交 ----
    surface->getCanvas()->clear(SK_ColorRED);
    ctx->flushAndSubmit(GrSyncCpu::kYes);
    glFinish();

    // ---- 4) 回读断言 ----
    SkImageInfo info = SkImageInfo::MakeN32Premul(kW, kH);
    uint32_t pixels[kW * kH];
    std::memset(pixels, 0, sizeof(pixels));
    if (!surface->readPixels(info, pixels, kW * 4, 0, 0)) return fail("SkSurface::readPixels");

    const uint32_t p = pixels[0];
    // 注意：SkImageInfo::MakeN32Premul 在小端下就是 **BGRA**（字节序 B,G,R,A）——
    // 第一版这里按 RGBA 读，把「红」读成了「蓝」，白白怀疑了一轮 GPU 光栅化。
    const unsigned b = (p >> 0) & 0xff, g = (p >> 8) & 0xff, r = (p >> 16) & 0xff,
                   a = (p >> 24) & 0xff;
    std::printf("GL-SMOKE: readPixels[0] = rgba(%u,%u,%u,%u)\n", r, g, b, a);
    std::fflush(stdout);
    if (r < 200 || g > 60 || b > 60) {
        std::printf("GL-SMOKE: FAIL  像素不是红色（GPU 光栅化结果不对）\n");
        std::fflush(stdout);
        return 1;
    }

    // ---- 收尾 ----
    surface.reset();
    ctx->releaseResourcesAndAbandonContext();
    ctx.reset();
    glInterface.reset();
    wglMakeCurrent(nullptr, nullptr);
    wglDeleteContext(rc);
    ReleaseDC(hwnd, dc);
    DestroyWindow(hwnd);

    std::printf("GL-SMOKE: PASS  (Ganesh + WGL 渲染 + 回读全部通过)\n");
    std::fflush(stdout);
    return 0;
}

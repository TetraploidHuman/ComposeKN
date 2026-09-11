#include "wayland_bridge.h"

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <cstdio>

#include "ganesh/GrDirectContext.h"
#include "ganesh/gl/GrGLAssembleInterface.h"
#include "ganesh/gl/GrGLDirectContext.h"
#include "ganesh/gl/GrGLInterface.h"

static GrGLFuncPtr get_gl_proc(void* /*ctx*/, const char* name) {
    return eglGetProcAddress(name);
}

static void log_gl_info(const char* stage) {
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    std::fprintf(
        stderr,
        "composekn: %s GL version=%s vendor=%s renderer=%s\n",
        stage,
        version ? version : "(null)",
        vendor ? vendor : "(null)",
        renderer ? renderer : "(null)"
    );
    std::fflush(stderr);
}

static sk_sp<const GrGLInterface> make_skia_gl_interface() {
    sk_sp<const GrGLInterface> iface = GrGLMakeAssembledInterface(nullptr, get_gl_proc);
    if (iface && iface->validate()) {
        return iface;
    }
    if (iface) {
        std::fprintf(stderr, "composekn: GrGLMakeAssembledInterface validate failed, trying GLES\n");
        std::fflush(stderr);
    } else {
        std::fprintf(stderr, "composekn: GrGLMakeAssembledInterface failed, trying GLES\n");
        std::fflush(stderr);
    }
    iface = GrGLMakeAssembledGLESInterface(nullptr, get_gl_proc);
    if (!iface) {
        std::fprintf(stderr, "composekn: GrGLMakeAssembledGLESInterface failed\n");
        std::fflush(stderr);
        return nullptr;
    }
    if (!iface->validate()) {
        std::fprintf(stderr, "composekn: GrGLMakeAssembledGLESInterface validate failed\n");
        std::fflush(stderr);
        return nullptr;
    }
    return iface;
}

extern "C" void* composekn_create_egl_direct_context(void) {
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) {
        std::fprintf(stderr, "composekn: no current EGL context for Skia init\n");
        std::fflush(stderr);
        return nullptr;
    }

    log_gl_info("before Skia init");

    sk_sp<const GrGLInterface> iface = make_skia_gl_interface();
    if (!iface) {
        return nullptr;
    }

    sk_sp<GrDirectContext> ctx = GrDirectContexts::MakeGL(iface);
    if (!ctx) {
        std::fprintf(stderr, "composekn: GrDirectContexts::MakeGL failed\n");
        std::fflush(stderr);
        return nullptr;
    }

    std::fprintf(stderr, "composekn: Skia GrDirectContext created\n");
    std::fflush(stderr);
    return ctx.release();
}

extern "C" void composekn_gl_viewport(int width, int height) {
    if (width > 0 && height > 0) {
        glViewport(0, 0, width, height);
    }
}

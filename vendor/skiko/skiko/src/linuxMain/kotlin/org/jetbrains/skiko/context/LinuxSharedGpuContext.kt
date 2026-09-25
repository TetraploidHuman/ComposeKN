package org.jetbrains.skiko.context

import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.impl.Native.Companion.NullPointer
import org.jetbrains.skiko.composekn_create_egl_direct_context

/**
 * 共享 wl_display / EGLDisplay 后，多窗必须共用一个 GL context + 一个
 * [DirectContext]（否则 llvmpipe 上两套 Ganesh 状态会在 flush 时 SIGSEGV）。
 *
 * 单线程 Host 泵：每帧 [eglMakeCurrent] 切到对应窗的 EGLSurface 再画。
 */
internal object LinuxSharedGpuContext {
    private var context: DirectContext? = null
    private var refCount: Int = 0

    fun acquire(): DirectContext? {
        if (context == null) {
            val ptr = composekn_create_egl_direct_context()
            if (ptr == NullPointer) return null
            context = DirectContext(ptr)
            println("composekn: shared Skia GrDirectContext acquired")
        }
        refCount++
        return context
    }

    fun release() {
        if (refCount > 0) refCount--
        if (refCount == 0) {
            context?.close()
            context = null
            println("composekn: shared Skia GrDirectContext released")
        }
    }
}

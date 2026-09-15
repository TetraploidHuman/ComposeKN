@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.jetbrains.skiko

import kotlinx.cinterop.*
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.staticCFunction
import org.jetbrains.skia.impl.Native
import org.jetbrains.skia.impl.NativePointer

/**
 * Kotlin/Native Windows (mingw) Win32 bridge bindings — mirrors the Wayland
 * bridge pattern: externals declared here resolve to the C bridge compiled by
 * skiko's compileNativeBridges into the final executable.
 */
@SymbolName("composekn_win32_create")
internal external fun composekn_win32_create(
    title: CPointer<ByteVar>,
    width: Int,
    height: Int,
    undecorated: Int,
): COpaquePointer?

@SymbolName("composekn_win32_destroy")
internal external fun composekn_win32_destroy(window: COpaquePointer?)

@SymbolName("composekn_win32_pump")
internal external fun composekn_win32_pump(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_pop_event_flat")
internal external fun composekn_win32_pop_event_flat(
    window: COpaquePointer?,
    type: CPointer<IntVar>,
    x: CPointer<FloatVar>,
    y: CPointer<FloatVar>,
    button: CPointer<UIntVar>,
    state: CPointer<UIntVar>,
    a: CPointer<IntVar>,
    b: CPointer<IntVar>,
    modifiers: CPointer<UIntVar>,
): Boolean

@SymbolName("composekn_win32_present")
internal external fun composekn_win32_present(
    window: COpaquePointer?,
    // NativePointer（= konan 的 NativePtr）才能直接接收 Pixmap.addr —— raster surface
    // 的像素指针是零拷贝交给 GDI 的（见 WindowsSoftwareContextHandler）。
    pixels: NativePointer,
    width: Int,
    height: Int,
    stridePx: Int,
)

/** 阻塞等待消息（timeoutMillis < 0 = 无限等待）。false 表示应用应当退出。 */
@SymbolName("composekn_win32_wait_message")
internal external fun composekn_win32_wait_message(window: COpaquePointer?, timeoutMillis: Int): Boolean

/** 唤醒阻塞在 [composekn_win32_wait_message] 里的消息循环（可跨线程调用）。 */
@SymbolName("composekn_win32_wake")
internal external fun composekn_win32_wake(window: COpaquePointer?)

/** 主显示器刷新率（Hz），拿不到时返回 0。 */
@SymbolName("composekn_win32_refresh_hz")
internal external fun composekn_win32_refresh_hz(window: COpaquePointer?): Int

/**
 * 后备缓冲像素指针（C 侧持有，紧密 BGRA）：Skia 直接画进这块内存，present 时
 * GDI 从同一块内存上传 —— 零拷贝。见 WindowsSoftwareContextHandler。
 *
 * 尺寸变化后指针可能变，调用方必须重建包装它的 Skia surface。
 *
 * 注意返回类型**不能**写成 `NativePointer?`：C 的 `void*` 返回映射成 `NativePtr`
 * （value class），空指针是 `Native.NullPointer` 而不是 Kotlin null，可空声明会让
 * `!= null` 误判成功（拿到值为 0 的指针）。判断见 [Win32Window.backbufferPixels]。
 */
@SymbolName("composekn_win32_backbuffer_pixels")
internal external fun composekn_win32_backbuffer_pixels(
    window: COpaquePointer?,
    width: Int,
    height: Int,
): NativePointer

/** 把后备缓冲（Skia 刚画完的）上传到窗口客户区。 */
@SymbolName("composekn_win32_present_buffer")
internal external fun composekn_win32_present_buffer(window: COpaquePointer?)

/** 本进程累计 CPU 时间（内核 + 用户），纳秒；失败返回 -1。 */
@SymbolName("composekn_win32_process_cpu_nanos")
internal external fun composekn_win32_process_cpu_nanos(window: COpaquePointer?): Long

/** 逻辑处理器数量。 */
@SymbolName("composekn_win32_processor_count")
internal external fun composekn_win32_processor_count(): Int

/** 「最大化时客户区超出显示器工作区」的检出次数（正常为 0）。 */
@SymbolName("composekn_win32_client_overflow_count")
internal external fun composekn_win32_client_overflow_count(window: COpaquePointer?): Int

/** 触摸（WM_POINTER）通道是否启用。 */
@SymbolName("composekn_win32_touch_enabled")
internal external fun composekn_win32_touch_enabled(window: COpaquePointer?): Boolean

/** 设置光标形状（0=箭头 1=手 2=文本 I 型 3=十字）。 */
@SymbolName("composekn_win32_set_cursor")
internal external fun composekn_win32_set_cursor(window: COpaquePointer?, kind: Int)

// ---------------------------------------------------------------------------
// OpenGL / WGL（GPU 后端，对齐上游 linuxMain 的 EGL 版）
//
// 只绑定平台上下文操作；Skia 的 GPU 上下文由 Kotlin 侧 DirectContext.makeGL() 建
// （K/N 的 skia binding 已提供，见 org.jetbrains.skia.DirectContext.makeGL）。
// ---------------------------------------------------------------------------

/** 建 WGL 双缓冲上下文并 make current；失败返回 false（上层回退软件路径）。 */
@SymbolName("composekn_win32_gl_create")
internal external fun composekn_win32_gl_create(window: COpaquePointer?): Boolean

/** make current（幂等）。 */
@SymbolName("composekn_win32_gl_make_current")
internal external fun composekn_win32_gl_make_current(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_gl_viewport")
internal external fun composekn_win32_gl_viewport(width: Int, height: Int)

@SymbolName("composekn_win32_gl_get_draw_framebuffer_binding")
internal external fun composekn_win32_gl_get_draw_framebuffer_binding(): Int

@SymbolName("composekn_win32_gl_set_swap_interval")
internal external fun composekn_win32_gl_set_swap_interval(interval: Int)

@SymbolName("composekn_win32_gl_swap_buffers")
internal external fun composekn_win32_gl_swap_buffers(window: COpaquePointer?)

@SymbolName("composekn_win32_gl_destroy")
internal external fun composekn_win32_gl_destroy(window: COpaquePointer?)

@SymbolName("composekn_win32_width")
internal external fun composekn_win32_width(window: COpaquePointer?): Int

@SymbolName("composekn_win32_height")
internal external fun composekn_win32_height(window: COpaquePointer?): Int

@SymbolName("composekn_win32_show")
internal external fun composekn_win32_show(window: COpaquePointer?, cmd: Int)

@SymbolName("composekn_win32_is_maximized")
internal external fun composekn_win32_is_maximized(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_is_minimized")
internal external fun composekn_win32_is_minimized(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_request_close")
internal external fun composekn_win32_request_close(window: COpaquePointer?)

@SymbolName("composekn_win32_set_title")
internal external fun composekn_win32_set_title(window: COpaquePointer?, title: CPointer<ByteVar>)

@SymbolName("composekn_win32_clipboard_get_text")
internal external fun composekn_win32_clipboard_get_text(
    window: COpaquePointer?,
    buffer: CPointer<ByteVar>,
    bufferSize: ULong,
    ok: CPointer<BooleanVar>?,
)

@SymbolName("composekn_win32_clipboard_set_text")
internal external fun composekn_win32_clipboard_set_text(window: COpaquePointer?, text: CPointer<ByteVar>)

/** SW_SHOWMINIMIZED etc. */
const val SW_WINDOWS_SHOW = 5
const val SW_WINDOWS_MAXIMIZE = 3
const val SW_WINDOWS_MINIMIZE = 6
const val SW_WINDOWS_RESTORE = 9

/**
 * High-level wrapper over the Win32 C bridge. All calls are main-thread only.
 */
class Win32Window internal constructor(internal val native: COpaquePointer) : AutoCloseable {
    /**
     * 建窗口。`width`/`height` 的单位是 **dp（逻辑像素）**，与 Compose 桌面的
     * `WindowState(size = DpSize(...))` 一致：C 侧会按系统 DPI 换算成物理像素，
     * 建完再用窗口所在显示器的 DPI 校正一次。
     */
    /** [undecorated] true = 无边框自绘 CSD；默认 false = 系统标题栏（对齐 Compose JVM 的 Window()）。 */
    constructor(title: String, width: Int = 960, height: Int = 640, undecorated: Boolean = false) :
        this(ensureCreated(title, width, height, undecorated))

    /** 物理像素 / 逻辑像素。窗口客户区按逻辑像素上报，渲染表面用物理像素。 */
    val dpiScale: Float
        get() = composekn_win32_dpi_scale(native)

    val width: Int
        get() = composekn_win32_width(native)
    val height: Int
        get() = composekn_win32_height(native)

    val isMaximized: Boolean
        get() = composekn_win32_is_maximized(native)
    val isMinimized: Boolean
        get() = composekn_win32_is_minimized(native)

    var clipboard: String?
        set(value) {
            if (value != null) value.useCString { composekn_win32_clipboard_set_text(native, it) }
        }
        get() {
            return memScoped {
                val ok = alloc<BooleanVar>()
                val buffer = ByteArray(8192)
                buffer.usePinned { pinned ->
                    composekn_win32_clipboard_get_text(
                        native, pinned.addressOf(0), buffer.size.convert(), ok.ptr
                    )
                }
                if (ok.value) StringBytesDecoding(buffer) else null
            }
        }

    fun pump(): Boolean = composekn_win32_pump(native)

    /**
     * 阻塞等待消息队列非空（timeoutMillis < 0 = 无限等待）。
     *
     * 没有渲染任务时用它替代忙等循环：线程真正睡着（CPU ≈ 0），消息一到立刻返回。
     * 注意：调用前必须先排空消息（[pump]），否则已经排在队列里的消息会让它立即返回
     * —— 不过那也只是多绕一圈循环，不会丢消息。
     */
    fun waitMessage(timeoutMillis: Int = -1): Boolean =
        composekn_win32_wait_message(native, timeoutMillis)

    /** 从渲染回调/其它线程唤醒 [waitMessage] 的阻塞。 */
    fun wake(): Unit = composekn_win32_wake(native)

    /**
     * 主显示器刷新率（Hz）。虚拟机/远程桌面上 Win32 常返回 0 或 1，
     * 调用方应当只在合理区间（24..360）内采信。
     */
    val refreshHz: Int get() = composekn_win32_refresh_hz(native)

    /**
     * 后备缓冲（present buffer）像素指针，由 C 侧持有并在尺寸变化时重分配。
     * 返回 null 表示失败，调用方应回退到 `composekn_win32_present`（拷贝）路径。
     */
    fun backbufferPixels(width: Int, height: Int): NativePointer? {
        val ptr = composekn_win32_backbuffer_pixels(native, width, height)
        // 空指针必须用 Native.NullPointer 判断 —— C 的 void* 返回映射到 NativePtr
        // （value class），空指针不是 Kotlin 的 null。声明成可空类型时 `!= null`
        // 会误判成功，拿到值为 0 的指针（症状：makeRasterDirect(..., 0x0, ...)）。
        return if (ptr == Native.NullPointer) null else ptr
    }

    /** 把 Kotlin 侧刚画好的后备缓冲上传到窗口（零拷贝，GDI 直接读同一块内存）。 */
    fun presentBuffer(): Unit = composekn_win32_present_buffer(native)

    /** 本进程累计 CPU 时间（纳秒），失败返回 -1。用于把 CPU 占用写进日志。 */
    fun processCpuNanos(): Long = composekn_win32_process_cpu_nanos(native)

    /**
     * 「最大化时客户区超出显示器工作区」的检出次数（正常必须为 0）。
     *
     * 无边框窗口最大化时 Windows 会按不可见缩放边框把窗口扩到屏幕外，客户区跟着
     * 超出屏幕，最右侧的关闭按钮就被裁掉一半 —— 而且**只在最大化时出现**。
     * 自检拿它当回归断言（见 SelfTest 的 window/maximize-* 两条）。
     */
    val clientOverflowCount: Int get() = composekn_win32_client_overflow_count(native)

    /** 触摸通道是否启用（COMPOSEKN_TOUCH=0 关闭）。 */
    val touchEnabled: Boolean get() = composekn_win32_touch_enabled(native)

    /**
     * 设置鼠标光标形状：0=箭头 1=手 2=文本 I 型 3=十字。
     * 由 Compose 的 PointerIcon（[androidx.compose.ui.input.pointer.pointerHoverIcon]，
     * clickable 默认就是手型）驱动 —— 见 WindowsPlatformContext.setPointerIcon。
     */
    fun setCursor(kind: Int): Unit = composekn_win32_set_cursor(native, kind)

    // ---- OpenGL / WGL（GPU 后端）----

    /** 建 WGL 上下文并 make current；失败返回 false（调用方回退软件路径）。 */
    fun glCreate(): Boolean = composekn_win32_gl_create(native)

    /** make current（幂等）。渲染/销毁前调用，与上游 Linux GL redrawer 一致。 */
    fun glMakeCurrent(): Boolean = composekn_win32_gl_make_current(native)

    fun glViewport(width: Int, height: Int) = composekn_win32_gl_viewport(width, height)

    /** 当前 draw framebuffer 绑定（默认帧缓冲通常是 0）。 */
    fun glGetDrawFramebufferBinding(): Int = composekn_win32_gl_get_draw_framebuffer_binding()

    /** WGL_EXT_swap_interval（1 = 垂直同步）。 */
    fun glSetSwapInterval(interval: Int) = composekn_win32_gl_set_swap_interval(interval)

    /** present：SwapBuffers。 */
    fun glSwapBuffers(): Unit = composekn_win32_gl_swap_buffers(native)

    /** 销毁 WGL 上下文。 */
    fun glDestroy(): Unit = composekn_win32_gl_destroy(native)

    // 注意：这里必须用 memScoped + alloc<>().ptr 传「真实指针」。
    // Kotlin 的 IntArray/FloatArray/UIntArray 是托管对象，传给 external 函数时
    // 传过去的是「对象指针」而不是元素首地址，C 侧写入会砸坏 Kotlin 堆对象头，
    // 表现为几秒后 GC（FixedBlockPage::Sweep）崩溃。
    fun popEvent(): Win32Event? = memScoped {
        val type = alloc<IntVar>()
        val x = alloc<FloatVar>()
        val y = alloc<FloatVar>()
        val button = alloc<UIntVar>()
        val state = alloc<UIntVar>()
        val a = alloc<IntVar>()
        val b = alloc<IntVar>()
        val modifiers = alloc<UIntVar>()
        if (!composekn_win32_pop_event_flat(
                native, type.ptr, x.ptr, y.ptr, button.ptr,
                state.ptr, a.ptr, b.ptr, modifiers.ptr,
            )
        ) {
            return@memScoped null
        }
        Win32Event(
            type = type.value,
            x = x.value,
            y = y.value,
            button = button.value,
            state = state.value,
            a = a.value,
            b = b.value,
            modifiers = modifiers.value,
        )
    }

    fun minimize() = composekn_win32_show(native, SW_WINDOWS_MINIMIZE)
    fun maximize() = composekn_win32_show(native, SW_WINDOWS_MAXIMIZE)
    fun restore() = composekn_win32_show(native, SW_WINDOWS_RESTORE)
    fun show() = composekn_win32_show(native, SW_WINDOWS_SHOW)

    fun requestClose() = composekn_win32_request_close(native)
    fun setTitle(title: String) = title.useCString { composekn_win32_set_title(native, it) }

    /**
     * Begin a native move drag from a WM_NCLBUTTONDOWN/HTCAPTION synthetic event.
     * Uses ReleaseCapture + SendMessage so Compose-injected press can drive it.
     */
    fun beginMove() = composekn_win32_begin_move(native)

    override fun close() {
        composekn_win32_destroy(native)
    }
}

@SymbolName("composekn_win32_begin_move")
internal external fun composekn_win32_begin_move(window: COpaquePointer?)

@SymbolName("composekn_win32_dpi_scale")
internal external fun composekn_win32_dpi_scale(window: COpaquePointer?): Float

@SymbolName("composekn_win32_set_render_tick")
private external fun composekn_win32_set_render_tick(callback: COpaquePointer?, user: COpaquePointer?)

private var renderTickAction: (() -> Unit)? = null

private val renderTickCallback = staticCFunction<COpaquePointer?, Unit> { _ ->
    renderTickAction?.invoke()
}

/**
 * 注册「C 侧同步渲染」回调：wndproc 处理 WM_SIZE 时会回调它。
 *
 * 拖拽缩放期间 Windows 的模态循环占住了消息泵，Kotlin 渲染循环跑不到，
 * 窗口只能显示被拉伸的旧帧；靠这个回调在 WM_SIZE 里同步渲染一帧，
 * 内容就能按新尺寸逐帧重组。传 null 注销。
 */
fun setWindowsRenderTick(action: (() -> Unit)?) {
    renderTickAction = action
    composekn_win32_set_render_tick(if (action == null) null else renderTickCallback, null)
}

@SymbolName("composekn_win32_log")
internal external fun composekn_win32_log(message: CPointer<ByteVar>)
/** Append a line to composekn-startup.log (next to the exe). */
fun win32Log(message: String) = message.useCString { composekn_win32_log(it) }

/** 逻辑处理器数量（把进程 CPU 时间换算成「占整机百分比」用）。 */
val win32ProcessorCount: Int get() = composekn_win32_processor_count()

/**
 * Flat Win32 event delivered from the C bridge.
 */
data class Win32Event(
    val type: Int,
    val x: Float,
    val y: Float,
    val button: UInt,
    val state: UInt,
    val a: Int,
    val b: Int,
    val modifiers: UInt,
) {
    companion object {
        const val MOUSE_MOVE = 1
        const val MOUSE_BUTTON = 2
        const val MOUSE_WHEEL = 3
        const val KEY = 4
        const val CHAR = 5
        const val SIZE = 6
        const val MOVE = 7
        const val CLOSE = 8
        const val FOCUS = 9
        const val QUIT = 10
        /*
         * 触摸（WM_POINTER）。x/y = 客户区物理像素，button = 指针 id，
         * state = 1(按下/移动) / 0(抬起)。见 win32_bridge.h 的说明。
         */
        const val TOUCH_DOWN = 11
        const val TOUCH_MOVE = 12
        const val TOUCH_UP = 13
    }
}

private fun ensureCreated(title: String, width: Int, height: Int, undecorated: Boolean): COpaquePointer =
    title.useCString { p ->
        val native = composekn_win32_create(p, width, height, if (undecorated) 1 else 0)
        checkNotNull(native) { "Failed to create Win32 window (title=$title)" }
        native
    }

/**
 * 把 Kotlin String 以 NUL 结尾的 UTF-8 缓冲区固定住，并把真实数据指针交给 C 侧。
 *
 * 注意不要用 `"x".cstr`：在本工程使用的 K/N 版本里，`CValues<ByteVar>` 作为
 * external 函数参数时传过去的是「CValues 包装对象」，C 侧拿到的不是字符串首地址
 * （Windows 窗口标题、日志全都变乱码）。这里手工 pin ByteArray + addressOf(0)
 * 拿到的一定是元素首地址。字符串在 block 返回前保持 pinned。
 */
private inline fun <R> String.useCString(block: (CPointer<ByteVar>) -> R): R {
    val bytes = encodeToByteArray()
    val buf = ByteArray(bytes.size + 1)   // 末尾保留 \0
    bytes.copyInto(buf)
    return buf.usePinned { block(it.addressOf(0)) }
}

private fun StringBytesDecoding(bytes: ByteArray): String {
    val end = bytes.indexOf(0)
    val range = if (end >= 0) bytes.copyOf(end) else bytes
    return range.decodeToString()
}

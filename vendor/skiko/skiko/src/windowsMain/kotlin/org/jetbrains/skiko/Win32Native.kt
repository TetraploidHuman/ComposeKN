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
 * Win32 消息号（自检用）。
 *
 * 这些值是 Windows ABI 里固定的常量，Kotlin/Native 这边没有 windows.h 的 cinterop，
 * 所以就地声明。只放自检真正会投递的那几条 —— 不是要复刻 windows.h。
 */
object Win32Message {
    const val MOUSEMOVE = 0x0200
    const val LBUTTONDOWN = 0x0201
    const val LBUTTONUP = 0x0202
    const val RBUTTONDOWN = 0x0204
    const val RBUTTONUP = 0x0205
    const val MBUTTONDOWN = 0x0207
    const val MBUTTONUP = 0x0208
    const val MOUSEWHEEL = 0x020A
    const val MOUSEHWHEEL = 0x020E
    const val KEYDOWN = 0x0100
    const val KEYUP = 0x0101
    const val CHAR = 0x0102
    const val SYSKEYDOWN = 0x0104
    const val SYSKEYUP = 0x0105
    const val UNICHAR = 0x0109

    /** WM_MOUSEWHEEL 的「一格」；delta 可以是它的小数倍（精确触控板）。 */
    const val WHEEL_DELTA = 120
}

@SymbolName("composekn_win32_post_test_mouse")
internal external fun composekn_win32_post_test_mouse(
    window: COpaquePointer?,
    message: UInt,
    x: Int,
    y: Int,
    wheelDelta: Int,
): Boolean

@SymbolName("composekn_win32_post_test_key")
internal external fun composekn_win32_post_test_key(
    window: COpaquePointer?,
    message: UInt,
    vkOrChar: Int,
    scanCode: Int,
    isRepeat: Int,
): Boolean

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

    // ---- IME（IMM32）----

    /**
     * 弹出最近一个 IME 事件配对的 UTF-8 文本；队列空返回 null。
     *
     * 缓冲区大小按 4K 取：一次组字/提交不可能超过这个长度（真超了 C 侧会截断，
     * 不会越界）。
     */
    fun imePopText(): String? = memScoped {
        val buffer = allocArray<ByteVar>(IME_TEXT_BUFFER_SIZE)
        val length = composekn_win32_ime_pop_text(native, buffer, IME_TEXT_BUFFER_SIZE)
        if (length < 0) return@memScoped null
        StringBytesDecoding(buffer, length)
    }

    /** 走过的 IME 消息条数（0 = 系统没发 IME 消息；排查输入法问题用）。 */
    val imeMessageCount: Int get() = composekn_win32_ime_message_count(native)

    /** 当前是否正在组字。 */
    val imeComposing: Boolean get() = composekn_win32_ime_composing(native)

    /** 取消正在进行的组字（文本会话结束时调用）。 */
    fun imeCancelComposition(): Unit = composekn_win32_ime_cancel_composition(native)

    /** 自检用：注入一条「IME 提交」事件（Wine 里没有真 IME）。 */
    fun imeTestCommit(text: String): Unit =
        text.useCString { composekn_win32_ime_test_commit(native, it) }

    /**
     * 自检用：走**真实**的 WM_IME_REQUEST(IMR_QUERYCHARPOSITION) 路径问一次
     * 「组字串里第 [dwCharPos] 个字符在哪」。返回 {x, y, lineHeight, 1}（客户区
     * 物理像素，pt 是光标底部）或 null（消息没被处理）。
     */
    fun imeTestQueryCharPos(dwCharPos: Int): IntArray? = memScoped {
        val out = allocArray<IntVar>(4)
        val handled = composekn_win32_ime_test_query_char_pos(native, dwCharPos, out)
        if (handled == 0) return@memScoped null
        intArrayOf(out[0], out[1], out[2], out[3])
    }

    /** 自检用：合成一条 WM_IME_STARTCOMPOSITION / WM_IME_ENDCOMPOSITION（Wine 里没有真 IME）。 */
    fun imeTestSendCompositionMessage(start: Boolean): Unit =
        composekn_win32_ime_test_send_composition(native, if (start) 1 else 0)

    /**
     * 自检用：发一条 `IMR_DOCUMENTFEED`(kind=0) / `IMR_RECONVERTSTRING`(1) /
     * `IMR_COMPOSITIONFONT`(2)，返回 C 侧填好的字段（见 `composekn_win32_ime_test_reconvert`）。
     */
    /** 自检用：合成一条 `IMR_CONFIRMRECONVERTSTRING`；true = 我们接受了这次重转换。 */
    fun imeTestConfirmReconvert(text: String, targetOffset: Int, targetLen: Int): Boolean =
        text.useCString { utf8 ->
            composekn_win32_ime_test_confirm_reconvert(native, utf8, targetOffset, targetLen) != 0
        }

    fun imeTestReconvert(kind: Int, bufferChars: Int): IntArray? = memScoped {
        val out = allocArray<IntVar>(12)
        if (composekn_win32_ime_test_reconvert(native, kind, bufferChars, out) == 0) {
            return@memScoped null
        }
        IntArray(12) { out[it] }
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

    /**
     * 自检用：把一条**真实的 Win32 鼠标消息**投递到窗口自己的消息队列
     * （PostMessage -> 主循环 GetMessage/DispatchMessage -> 真实 wndproc 分支）。
     *
     * 为什么要它：窗口阶段的自检以前只从 Kotlin 侧合成 `WindowsEvent`，C++ 宿主
     * 那一层（wndproc 的参数解码 / 坐标换算 / 消息过滤）在自动化里从未被跑过 ——
     * 历史上的宿主层 bug（笔悬停变成一根按下的手指、Shift+滚轮没实现）就都是
     * 「只有真机手动操作才第一次执行」。这条断言的是宿主本身。
     *
     * [x]/[y] 是**客户区**坐标（滚轮也一样；C 侧按真机格式换成屏幕坐标进 lParam）。
     * [wheelDelta] 只在 [Win32Message.MOUSEWHEEL] / [Win32Message.MOUSEHWHEEL] 时有效，
     * 单位是 [Win32Message.WHEEL_DELTA]；精确触控板可以是任意小数倍。
     */
    fun postTestMouseMessage(message: Int, x: Int, y: Int, wheelDelta: Int = 0): Boolean =
        composekn_win32_post_test_mouse(native, message.toUInt(), x, y, wheelDelta)

    /**
     * 自检用：把一条**真实的 Win32 键盘/字符消息**投递到窗口自己的消息队列。
     *
     * [vkOrChar] 对 KEYDOWN/KEYUP 是虚拟键码，对 CHAR/UNICHAR 是字符码点；
     * [scanCode]/[isRepeat] 用来拼真实的 lParam（只影响宿主诊断日志里那条
     * `key: … prevDown=…`）。
     */
    fun postTestKeyMessage(
        message: Int,
        vkOrChar: Int,
        scanCode: Int = 0,
        isRepeat: Boolean = false,
    ): Boolean = composekn_win32_post_test_key(
        native,
        message.toUInt(),
        vkOrChar,
        scanCode,
        if (isRepeat) 1 else 0,
    )

    override fun close() {
        composekn_win32_destroy(native)
    }
}

@SymbolName("composekn_win32_begin_move")
internal external fun composekn_win32_begin_move(window: COpaquePointer?)

/** IME 文本缓冲区大小（一次组字/提交的长度上限）。 */
private const val IME_TEXT_BUFFER_SIZE = 4096

@SymbolName("composekn_win32_ime_pop_text")
private external fun composekn_win32_ime_pop_text(
    window: COpaquePointer?,
    buffer: CPointer<ByteVar>,
    bufferSize: Int,
): Int

@SymbolName("composekn_win32_ime_message_count")
private external fun composekn_win32_ime_message_count(window: COpaquePointer?): Int

@SymbolName("composekn_win32_ime_composing")
private external fun composekn_win32_ime_composing(window: COpaquePointer?): Boolean

@SymbolName("composekn_win32_ime_cancel_composition")
private external fun composekn_win32_ime_cancel_composition(window: COpaquePointer?)

@SymbolName("composekn_win32_ime_test_commit")
private external fun composekn_win32_ime_test_commit(window: COpaquePointer?, utf8: CPointer<ByteVar>)

@SymbolName("composekn_win32_ime_test_query_char_pos")
private external fun composekn_win32_ime_test_query_char_pos(
    window: COpaquePointer?,
    dwCharPos: Int,
    out: CPointer<IntVar>,
): Int

@SymbolName("composekn_win32_ime_test_send_composition")
private external fun composekn_win32_ime_test_send_composition(window: COpaquePointer?, start: Int)

@SymbolName("composekn_win32_ime_test_reconvert")
private external fun composekn_win32_ime_test_reconvert(
    window: COpaquePointer?,
    kind: Int,
    bufferChars: Int,
    out: CPointer<IntVar>,
): Int

@SymbolName("composekn_win32_ime_test_confirm_reconvert")
private external fun composekn_win32_ime_test_confirm_reconvert(
    window: COpaquePointer?,
    utf8: CPointer<ByteVar>,
    targetOffsetInText: Int,
    targetLen: Int,
): Int

@SymbolName("composekn_win32_set_ime_caret_provider")
private external fun composekn_win32_set_ime_caret_provider(
    callback: COpaquePointer?,
    user: COpaquePointer?,
)

private var imeCaretProvider: ((charIndex: Int) -> IntArray?)? = null

/** C 侧的入参 charIndex + 4 个出参（客户区物理像素下的 x/y/w/h）。 */
private val imeCaretCallback = staticCFunction<
    COpaquePointer?, Int, CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>, Unit
    > { _, charIndex, x, y, w, h ->
    val rect = imeCaretProvider?.invoke(charIndex)
    if (rect != null && rect.size >= 4) {
        x.pointed.value = rect[0]
        y.pointed.value = rect[1]
        w.pointed.value = rect[2]
        h.pointed.value = rect[3]
    }
}

/**
 * 注册「文本框字符矩形提供者」：IME 需要把组字窗/候选窗摆到字符处时会**同步**
 * 回调它（可能在 WM_IME_REQUEST 的 SendMessage 里），所以要立刻返回。
 *
 * 回调返回 `intArrayOf(x, y, w, h)`（客户区物理像素）或 null（没有 → C 侧按
 * (0,0) 处理）；入参 `charIndex` 对应 IMECHARPOSITION.dwCharPos：
 * `>= 0` = 组字串里第几个字符（候选窗一般问第 0 个 → 候选窗钉在开始组字的位置），
 * `< 0` = 只要当前光标。
 *
 * 不注册的话候选窗只能落在窗口左上角 —— 真机上表现为「候选词卡住 / 位置乱」。
 * 传 null 注销。
 */
fun setWindowsImeCaretProvider(provider: ((charIndex: Int) -> IntArray?)?) {
    imeCaretProvider = provider
    composekn_win32_set_ime_caret_provider(
        if (provider == null) null else imeCaretCallback, null,
    )
}

/**
 * IMM32 的「文档快照」：`IMR_DOCUMENTFEED` / `IMR_RECONVERTSTRING` 要的东西。
 *
 * 偏移都是 UTF-16 code unit 偏移（= Compose 的 TextRange 偏移）；`-1` = 不存在。
 * 输入法拿它做上下文候选排序和「重新转换」。
 */
data class WindowsImeDocument(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val compositionStart: Int,
    val compositionEnd: Int,
)

private var imeTextProvider: (() -> WindowsImeDocument?)? = null

/**
 * C 侧的入参 from/capacity + 出参（选区/组字范围）；返回写入的 code unit 数
 * （capacity <= 0 时返回文档总长度）。
 */
private val imeTextCallback = staticCFunction<
    COpaquePointer?, Int, CPointer<UShortVar>, Int,
    CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>, CPointer<IntVar>, Int
    > { _, from, buffer, capacity, selStart, selEnd, compStart, compEnd ->
    val doc = imeTextProvider?.invoke()
    if (doc == null) {
        selStart.pointed.value = -1
        selEnd.pointed.value = -1
        compStart.pointed.value = -1
        compEnd.pointed.value = -1
        return@staticCFunction 0
    }
    selStart.pointed.value = doc.selectionStart
    selEnd.pointed.value = doc.selectionEnd
    compStart.pointed.value = doc.compositionStart
    compEnd.pointed.value = doc.compositionEnd
    if (capacity <= 0) return@staticCFunction doc.text.length
    if (from < 0 || from >= doc.text.length) return@staticCFunction 0
    val count = minOf(capacity, doc.text.length - from)
    // 注意：`buffer[i].value` 在 K/N 上编译不过（UShortVar 的 value 接收者不匹配），
    // 标准写法是 `(ptr + i)!!.pointed.value`。
    for (i in 0 until count) {
        (buffer + i)!!.pointed.value = doc.text[from + i].code.toUShort()
    }
    count
}

/**
 * 注册「文档提供者」：IME 通过 `WM_IME_REQUEST` 要文档内容（上下文候选排序、
 * 重新转换）时会**同步**回调它，所以要立刻返回。
 *
 * 回调返回 [WindowsImeDocument] 或 null（null = 没有活动文本会话）。
 * 传 null 注销。
 */
fun setWindowsImeTextProvider(provider: (() -> WindowsImeDocument?)?) {
    imeTextProvider = provider
    composekn_win32_set_ime_text_provider(
        if (provider == null) null else imeTextCallback, null,
    )
}

@SymbolName("composekn_win32_set_ime_text_provider")
private external fun composekn_win32_set_ime_text_provider(
    callback: COpaquePointer?,
    user: COpaquePointer?,
)

private var imeReconvertProvider: ((String, Int, Int) -> IntArray?)? = null

/**
 * C 侧入参：IME 发来的字符串 + 它在字符串里的目标范围；出参：文档偏移。
 * 返回 1 = 映射成功。
 */
private val imeReconvertCallback = staticCFunction<
    COpaquePointer?, CPointer<UShortVar>, Int, Int, Int,
    CPointer<IntVar>, CPointer<IntVar>, Int
    > { _, text, textLen, targetOffset, targetLen, outStart, outEnd ->
    if (textLen <= 0) return@staticCFunction 0
    val chars = CharArray(textLen) { i -> (text + i)!!.pointed.value.toInt().toChar() }
    val mapped = imeReconvertProvider?.invoke(chars.concatToString(), targetOffset, targetLen)
    if (mapped == null || mapped.size < 2) return@staticCFunction 0
    outStart.pointed.value = mapped[0]
    outEnd.pointed.value = mapped[1]
    1
}

/**
 * 注册「重新转换范围映射器」：IME 确认要重转换的范围时会**同步**回调它，
 * 返回 `intArrayOf(文档起始, 文档结束)` 才会答应这次重转换（返回 null = 拒绝，
 * 输入法会取消重转换，我们绝不动文本）。
 *
 * 传 null 注销。
 */
fun setWindowsImeReconvertProvider(provider: ((String, Int, Int) -> IntArray?)?) {
    imeReconvertProvider = provider
    composekn_win32_set_ime_reconvert_provider(
        if (provider == null) null else imeReconvertCallback, null,
    )
}

@SymbolName("composekn_win32_set_ime_reconvert_provider")
private external fun composekn_win32_set_ime_reconvert_provider(
    callback: COpaquePointer?,
    user: COpaquePointer?,
)

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
        /*
         * IME（IMM32）。事件本身不带文本：与之一一对应、顺序一致的 UTF-8 文本要用
         * [Win32Window.imePopText] 取（见 win32_bridge.h 的 IME 段）。
         */
        const val IME_START = 14
        const val IME_UPDATE = 15
        const val IME_COMMIT = 16
        const val IME_END = 17

        /**
         * 「重新转换」（再変換）的准备：范围在 a = 起始、b = 结束（UTF-16 code unit，
         * 文档坐标）。收到后要先把这段原文本选中，随后的组字才会替换它。
         */
        const val IME_RECONVERT_SELECT = 18
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

/** 把 C 侧填好的 UTF-8 缓冲区（前 [length] 字节，非 NUL 结尾）解成 String。 */
private fun StringBytesDecoding(bytes: CPointer<ByteVar>, length: Int): String {
    if (length <= 0) return ""
    return bytes.readBytes(length).decodeToString()
}

private fun StringBytesDecoding(bytes: ByteArray): String {
    val end = bytes.indexOf(0)
    val range = if (end >= 0) bytes.copyOf(end) else bytes
    return range.decodeToString()
}

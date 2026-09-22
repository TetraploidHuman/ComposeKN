package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * 一扇原生 Compose 窗口的句柄（离开 composition 时 [dispose]）。
 *
 * Windows 上实现体通常包着 `WindowsComposeWindow`；应用层可用 [asPlatformWindow]
 * 取回宿主类型（没有注册时为 null）。
 */
interface ComposeNativeWindowHandle {
    /** 平台宿主窗口对象（Windows = WindowsComposeWindow）；未接线时 null。 */
    fun asPlatformWindow(): Any?

    fun setTitle(title: String)
    fun setResizable(resizable: Boolean)
    fun setAlwaysOnTop(alwaysOnTop: Boolean)
    fun applyPlacement(placement: WindowPlacement, isMinimized: Boolean)
    fun applySize(size: DpSize)
    fun applyPosition(position: WindowPosition)
    fun setOnCloseRequest(callback: () -> Unit)
    fun setContent(content: @Composable () -> Unit)

    /**
     * 原生几何变化回调（用户拖动改大小/位置、最大化/最小化等）。
     * 用于把变化写回 [WindowState]（对齐 Desktop SwingWindow 的 componentListener）。
     * 传 null 注销。
     */
    fun setGeometryListener(listener: ((WindowGeometrySnapshot) -> Unit)?)

    fun dispose()
}

/**
 * 原生窗当前几何（逻辑像素 / dp），供 [ComposeNativeWindowHandle.setGeometryListener] 使用。
 */
data class WindowGeometrySnapshot(
    val size: DpSize,
    val position: WindowPosition.Absolute,
    val placement: WindowPlacement,
    val isMinimized: Boolean,
)

/**
 * 创建参数（逻辑像素 / dp，对齐 Desktop WindowState）。
 */
data class ComposeNativeWindowCreateParams(
    val title: String = "Untitled",
    val size: DpSize = DpSize(800.dp, 600.dp),
    val position: WindowPosition = WindowPosition.PlatformDefault,
    val placement: WindowPlacement = WindowPlacement.Floating,
    val isMinimized: Boolean = false,
    val undecorated: Boolean = false,
    val resizable: Boolean = true,
    val alwaysOnTop: Boolean = false,
    val onCloseRequest: () -> Unit = {},
)

/**
 * 进程级原生窗口后端（由 compose-kn-windows 等在启动时 [ComposeNativeWindowBackendRegistry.backend] 赋值）。
 */
interface ComposeNativeWindowBackend {
    /** 标记 UI 主线程（Win32 上 = initWindowsMainThread）。 */
    fun initMainThread()

    /** 创建并挂到共享消息泵（不阻塞）。 */
    fun createWindow(params: ComposeNativeWindowCreateParams): ComposeNativeWindowHandle

    /**
     * 阻塞跑共享消息泵，直到 [shouldContinue] 为 false。
     * application() 用它驱动 recomposer / 多窗口。
     */
    fun runApplicationPump(shouldContinue: () -> Boolean)

    /** 叫醒可能正 waitMessage 的泵（exitApplication / 无窗时的状态变更）。 */
    fun wakeApplicationPump()
}

/**
 * 后端登记表。compose-kn-windows 调用 [register]；[Window] / [application] 读 [backend]。
 */
object ComposeNativeWindowBackendRegistry {
    @kotlin.concurrent.Volatile
    var backend: ComposeNativeWindowBackend? = null
        private set

    fun register(impl: ComposeNativeWindowBackend) {
        backend = impl
    }

    fun requireBackend(): ComposeNativeWindowBackend =
        backend
            ?: error(
                "ComposeNativeWindowBackend 未注册。Windows 上请在 main 里调用 " +
                    "com.composekn.windows.registerComposeKnWindowsBackend()，" +
                    "或先构造 WindowsComposeApplication（其 init 会自动登记）。",
            )
}

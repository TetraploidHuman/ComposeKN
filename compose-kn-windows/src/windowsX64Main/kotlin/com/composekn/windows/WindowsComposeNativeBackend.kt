@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ComposeNativeWindowBackend
import androidx.compose.ui.window.ComposeNativeWindowBackendRegistry
import androidx.compose.ui.window.ComposeNativeWindowCreateParams
import androidx.compose.ui.window.ComposeNativeWindowHandle
import androidx.compose.ui.window.NativeMenuBarModel
import androidx.compose.ui.window.WindowGeometrySnapshot
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.composeKnFileDialogOwnerResolver
import org.jetbrains.skiko.win32Log

/**
 * 把 Win32 宿主登记为 [ComposeNativeWindowBackend]，供
 * `androidx.compose.ui.window.application { Window(...) }` 调用。
 *
 * 幂等；[WindowsComposeApplication] 构造时也会自动调用。
 * 依赖本模块时，[installComposeKnWindowsAutoRegister] 会把自动登记挂到 Registry。
 */
fun registerComposeKnWindowsBackend() {
    // FileDialog：asPlatformWindow() → WindowsComposeWindow → nativeWindow
    composeKnFileDialogOwnerResolver = { platform ->
        (platform as? WindowsComposeWindow)?.nativeWindow
    }
    if (ComposeNativeWindowBackendRegistry.backend != null) return
    ComposeNativeWindowBackendRegistry.register(WindowsComposeNativeBackend)
    win32Log("backend: ComposeNativeWindowBackend registered (Windows)")
}

/** 供 Application.init / 测试调用的别名。 */
internal fun ensureWindowsComposeBackendRegistered() = registerComposeKnWindowsBackend()

/** 挂到 [ComposeNativeWindowBackendRegistry.autoRegister]。 */
fun installComposeKnWindowsAutoRegister() {
    ComposeNativeWindowBackendRegistry.autoRegister = { registerComposeKnWindowsBackend() }
}

@Suppress("unused")
private val composeKnWindowsAutoRegisterInstall: Boolean = run {
    installComposeKnWindowsAutoRegister()
    true
}

private object WindowsComposeNativeBackend : ComposeNativeWindowBackend {
    override fun initMainThread() {
        initWindowsMainThread()
    }

    override fun createWindow(params: ComposeNativeWindowCreateParams): ComposeNativeWindowHandle {
        ensureWindowsComposeBackendRegistered()
        val width = params.size.width.value.toInt().coerceAtLeast(1)
        val height = params.size.height.value.toInt().coerceAtLeast(1)
        val app = WindowsComposeApplication(
            title = params.title,
            width = width,
            height = height,
            undecorated = params.undecorated,
        )
        val handle = WindowsNativeWindowHandle(app)
        handle.setResizable(params.resizable)
        handle.setAlwaysOnTop(params.alwaysOnTop)
        handle.setOnCloseRequest(params.onCloseRequest)
        if (params.isDialog) {
            app.window.isDialogWindow = true
        }
        handle.applyPlacement(params.placement, params.isMinimized)
        // 先挂宿主但**不显示**；定好位置/尺寸再 show，避免「角落闪一下再瞬移居中」。
        app.attachToSharedHost(
            withChrome = params.undecorated,
            onCloseRequest = params.onCloseRequest,
            show = false,
            content = null,
        )
        // 先尺寸后位置：cascade / centerOnScreen 依赖当前客户区尺寸
        if (params.size.width.isSpecified && params.size.height.isSpecified) {
            handle.applySize(params.size)
        }
        when (val pos = params.position) {
            is WindowPosition.Absolute -> handle.applyPosition(pos)
            is WindowPosition.Aligned -> {
                // Dialog 默认 Aligned(Center)：相对最近焦点窗所在屏居中（物理像素）
                if (!WindowsApplicationHost.placeAligned(
                        app.window, pos.alignment, width, height,
                    )
                ) {
                    handle.applyPosition(pos)
                }
            }
            WindowPosition.PlatformDefault -> {
                // 物理像素 cascade（跨 DPI）；首扇无锚点仍居中。
                if (!WindowsApplicationHost.placeCascaded(app.window, width, height)) {
                    app.window.centerOnScreen()
                }
            }
        }
        app.window.show()
        return handle
    }

    override fun runApplicationPump(shouldContinue: () -> Boolean) {
        WindowsApplicationHost.runSharedPump(shouldContinue)
    }

    override fun wakeApplicationPump() {
        WindowsApplicationHost.wake()
        // 无窗时 wake 可能是空操作；共享泵空闲分支有 2ms usleep，可接受
    }
}

/**
 * [ComposeNativeWindowHandle] 的 Win32 实现；[asPlatformWindow] 返回 [WindowsComposeWindow]。
 */
class WindowsNativeWindowHandle(
    private val app: WindowsComposeApplication,
) : ComposeNativeWindowHandle {
    val composeWindow: WindowsComposeWindow get() = app.window

    private var disposed = false
    private var pendingContent: (@Composable () -> Unit)? = null
    private var geometryListener: ((WindowGeometrySnapshot) -> Unit)? = null

    override fun asPlatformWindow(): Any? = app.window

    override fun setTitle(title: String) {
        app.window.setTitle(title)
    }

    override fun setResizable(resizable: Boolean) {
        app.window.resizable = resizable
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        app.window.setAlwaysOnTop(alwaysOnTop)
    }

    override fun applyPlacement(placement: WindowPlacement, isMinimized: Boolean) {
        val w = app.window
        when (placement) {
            WindowPlacement.Floating -> {
                if (w.isFullscreen) w.setFullscreen(false)
                if (w.isMaximized) w.restore()
            }
            WindowPlacement.Maximized -> {
                if (w.isFullscreen) w.setFullscreen(false)
                w.maximize()
            }
            WindowPlacement.Fullscreen -> w.setFullscreen(true)
        }
        if (isMinimized) w.minimize()
        else if (w.isMinimized) w.restore()
    }

    override fun applySize(size: DpSize) {
        if (!size.width.isSpecified || !size.height.isSpecified) return
        app.window.setWindowSize(size.width.value.toInt(), size.height.value.toInt())
    }

    override fun applyPosition(position: WindowPosition) {
        when (position) {
            is WindowPosition.Absolute -> {
                app.window.setWindowPosition(position.x.value.toInt(), position.y.value.toInt())
            }
            is WindowPosition.Aligned -> {
                val size = app.window.windowSize
                if (!WindowsApplicationHost.placeAligned(
                        app.window, position.alignment, size.width, size.height,
                    )
                ) {
                    app.window.alignOnScreen(position.alignment)
                }
            }
            WindowPosition.PlatformDefault -> Unit
        }
    }

    override fun setOnCloseRequest(callback: () -> Unit) {
        app.window.onCloseRequest = callback
    }

    override fun setContent(content: @Composable () -> Unit) {
        pendingContent = content
        // 重新 setContent 到已有 scene（attach 时已建好）
        app.setContent(withChrome = app.window.undecorated, content = content)
    }

    override fun setGeometryListener(listener: ((WindowGeometrySnapshot) -> Unit)?) {
        geometryListener = listener
        app.window.onGeometryHint = if (listener != null) {
            { notifyGeometryFromNative() }
        } else {
            null
        }
    }

    override fun setMenuBar(model: NativeMenuBarModel?) {
        app.window.setMenuBar(model)
    }

    override fun beginMove() {
        app.window.beginMove()
    }

    private fun notifyGeometryFromNative() {
        if (disposed) return
        val listener = geometryListener ?: return
        val w = app.window
        val size = w.windowSize
        val pos = w.windowPosition
        val placement = when {
            w.isFullscreen -> WindowPlacement.Fullscreen
            w.isMaximized -> WindowPlacement.Maximized
            else -> WindowPlacement.Floating
        }
        listener(
            WindowGeometrySnapshot(
                size = DpSize(size.width.dp, size.height.dp),
                position = WindowPosition.Absolute(pos.x.dp, pos.y.dp),
                placement = placement,
                isMinimized = w.isMinimized,
            ),
        )
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        geometryListener = null
        app.window.onGeometryHint = null
        // 不要在此处 setMenuBar(null)：拆菜单会撑大客户区 → WM_SIZE → 最后一帧
        // 重建 swapchain（真机 v0.5.35 关窗前 2200x1520）。菜单由
        // composekn_win32_destroy 在 DestroyWindow 前销毁。
        app.detachFromSharedHost()
    }
}

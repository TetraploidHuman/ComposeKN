@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ComposeNativeWindowBackend
import androidx.compose.ui.window.ComposeNativeWindowBackendRegistry
import androidx.compose.ui.window.ComposeNativeWindowCreateParams
import androidx.compose.ui.window.ComposeNativeWindowHandle
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import org.jetbrains.skiko.initWindowsMainThread
import org.jetbrains.skiko.win32Log

/**
 * 把 Win32 宿主登记为 [ComposeNativeWindowBackend]，供
 * `androidx.compose.ui.window.application { Window(...) }` 调用。
 *
 * 幂等；[WindowsComposeApplication] 构造时也会自动调用。
 */
fun registerComposeKnWindowsBackend() {
    if (ComposeNativeWindowBackendRegistry.backend != null) return
    ComposeNativeWindowBackendRegistry.register(WindowsComposeNativeBackend)
    win32Log("backend: ComposeNativeWindowBackend registered (Windows)")
}

/** 供 Application.init / 测试调用的别名。 */
internal fun ensureWindowsComposeBackendRegistered() = registerComposeKnWindowsBackend()

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
        handle.applyPlacement(params.placement, params.isMinimized)
        // 先挂宿主但**不显示**；定好位置/尺寸再 show，避免「角落闪一下再瞬移居中」。
        app.attachToSharedHost(
            withChrome = params.undecorated,
            onCloseRequest = params.onCloseRequest,
            show = false,
            content = null,
        )
        // 先尺寸后位置：centerOnScreen 依赖当前客户区尺寸
        if (params.size.width.isSpecified && params.size.height.isSpecified) {
            handle.applySize(params.size)
        }
        when (val pos = params.position) {
            is WindowPosition.Absolute -> handle.applyPosition(pos)
            is WindowPosition.Aligned -> handle.applyPosition(pos)
            WindowPosition.PlatformDefault -> {
                app.window.centerOnScreen()
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
                // 目前只实现 Center；其它对齐退化为居中
                app.window.centerOnScreen()
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

    override fun dispose() {
        if (disposed) return
        disposed = true
        app.detachFromSharedHost()
    }
}

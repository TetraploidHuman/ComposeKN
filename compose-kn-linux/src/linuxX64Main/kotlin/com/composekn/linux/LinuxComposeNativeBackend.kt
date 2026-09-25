@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.ComposeNativeWindowBackend
import androidx.compose.ui.window.ComposeNativeWindowBackendRegistry
import androidx.compose.ui.window.ComposeNativeWindowCreateParams
import androidx.compose.ui.window.ComposeNativeWindowHandle
import androidx.compose.ui.window.WindowGeometrySnapshot
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import org.jetbrains.skiko.initLinuxMainThread

/**
 * 把 Wayland 宿主登记为 [ComposeNativeWindowBackend]，供
 * `androidx.compose.ui.window.application { Window(...) }` 调用。
 *
 * 幂等；[LinuxComposeApplication] 构造时也会自动调用。
 */
fun registerComposeKnLinuxBackend() {
    if (ComposeNativeWindowBackendRegistry.backend != null) return
    ComposeNativeWindowBackendRegistry.register(LinuxComposeNativeBackend)
    println("composekn: backend ComposeNativeWindowBackend registered (Linux/Wayland)")
}

/** 供 Application.init / 测试调用的别名。 */
internal fun ensureLinuxComposeBackendRegistered() = registerComposeKnLinuxBackend()

private object LinuxComposeNativeBackend : ComposeNativeWindowBackend {
    override fun initMainThread() {
        initLinuxMainThread()
    }

    override fun createWindow(params: ComposeNativeWindowCreateParams): ComposeNativeWindowHandle {
        ensureLinuxComposeBackendRegistered()
        val width = params.size.width.value.toInt().coerceAtLeast(1)
        val height = params.size.height.value.toInt().coerceAtLeast(1)
        val app = LinuxComposeApplication(
            title = params.title,
            width = width,
            height = height,
            undecorated = params.undecorated,
        )
        val handle = LinuxNativeWindowHandle(app)
        handle.setResizable(params.resizable)
        handle.setAlwaysOnTop(params.alwaysOnTop)
        handle.setOnCloseRequest(params.onCloseRequest)
        if (params.isDialog) {
            app.composeWindow.isDialogWindow = true
        }
        handle.applyPlacement(params.placement, params.isMinimized)
        // 先挂宿主；Wayland 无法像 Win32 那样隐藏再 show，创建即可见。
        app.attachToSharedHost(
            withChrome = params.undecorated,
            onCloseRequest = params.onCloseRequest,
            content = null,
        )
        if (params.size.width.isSpecified && params.size.height.isSpecified) {
            handle.applySize(params.size)
        }
        when (val pos = params.position) {
            is WindowPosition.Absolute -> handle.applyPosition(pos)
            is WindowPosition.Aligned -> handle.applyPosition(pos)
            WindowPosition.PlatformDefault -> Unit
        }
        return handle
    }

    override fun runApplicationPump(shouldContinue: () -> Boolean) {
        LinuxApplicationHost.runSharedPump(shouldContinue)
    }

    override fun wakeApplicationPump() {
        // eventfd wake；失败时共享泵 idleWait ≤2ms
        LinuxApplicationHost.wake()
    }
}

/**
 * [ComposeNativeWindowHandle] 的 Wayland 实现；[asPlatformWindow] 返回 [LinuxComposeWindow]。
 */
class LinuxNativeWindowHandle(
    private val app: LinuxComposeApplication,
) : ComposeNativeWindowHandle {
    val composeWindow: LinuxComposeWindow get() = app.composeWindow

    private var disposed = false
    private var geometryListener: ((WindowGeometrySnapshot) -> Unit)? = null

    companion object {
        /** Aligned 定位限制只打一次日志，避免 resize SideEffect 刷屏。 */
        private var alignedPositionLogged = false
    }

    override fun asPlatformWindow(): Any? = app.composeWindow

    override fun setTitle(title: String) {
        app.composeWindow.setTitle(title)
    }

    override fun setResizable(resizable: Boolean) {
        app.composeWindow.resizable = resizable
        app.composeWindow.window.setResizable(resizable)
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        // Wayland 无标准 always-on-top；仅记账。
        app.composeWindow.alwaysOnTop = alwaysOnTop
    }

    override fun applyPlacement(placement: WindowPlacement, isMinimized: Boolean) {
        val w = app.composeWindow
        when (placement) {
            WindowPlacement.Floating -> {
                if (w.window.isFullscreen) w.window.setFullscreen(false)
                if (w.window.isMaximized) w.window.toggleMaximized()
            }
            WindowPlacement.Maximized -> {
                if (w.window.isFullscreen) w.window.setFullscreen(false)
                if (!w.window.isMaximized) w.window.toggleMaximized()
            }
            WindowPlacement.Fullscreen -> {
                if (!w.window.isFullscreen) w.window.setFullscreen(true)
            }
        }
        if (isMinimized) w.window.minimize()
    }

    override fun applySize(size: DpSize) {
        if (!size.width.isSpecified || !size.height.isSpecified) return
        // 经 composekn_window_request_size：min=max + geometry，configure 后清约束
        app.composeWindow.window.requestSize(
            size.width.value.toInt().coerceAtLeast(1),
            size.height.value.toInt().coerceAtLeast(1),
        )
    }

    override fun applyPosition(position: WindowPosition) {
        // Wayland 无通用绝对定位；不伪造 Absolute。Aligned 亦无可靠 API（无 layer-shell）。
        when (position) {
            is WindowPosition.Absolute -> Unit
            is WindowPosition.Aligned -> {
                if (!alignedPositionLogged) {
                    alignedPositionLogged = true
                    println(
                        "composekn: WindowPosition.Aligned is a no-op on Wayland " +
                            "(compositor owns placement)",
                    )
                }
            }
            WindowPosition.PlatformDefault -> Unit
        }
    }

    override fun setOnCloseRequest(callback: () -> Unit) {
        app.composeWindow.onCloseRequest = callback
    }

    override fun setContent(content: @Composable () -> Unit) {
        app.setContent(withChrome = app.undecorated, content = content)
    }

    override fun setGeometryListener(listener: ((WindowGeometrySnapshot) -> Unit)?) {
        geometryListener = listener
        app.composeWindow.onGeometryHint = if (listener != null) {
            { notifyGeometryFromNative() }
        } else {
            null
        }
    }

    override fun beginMove() {
        app.composeWindow.window.beginMove()
    }

    private fun notifyGeometryFromNative() {
        if (disposed) return
        val listener = geometryListener ?: return
        val w = app.composeWindow
        val width = w.window.width
        val height = w.window.height
        val placement = when {
            w.window.isFullscreen -> WindowPlacement.Fullscreen
            w.window.isMaximized -> WindowPlacement.Maximized
            else -> WindowPlacement.Floating
        }
        listener(
            WindowGeometrySnapshot(
                size = DpSize(width.dp, height.dp),
                position = WindowPosition.Absolute(0.dp, 0.dp),
                placement = placement,
                isMinimized = false,
            ),
        )
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        geometryListener = null
        app.composeWindow.onGeometryHint = null
        app.detachFromSharedHost()
    }
}

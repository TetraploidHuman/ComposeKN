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
        // Wayland 无法像 Win32 隐藏再 show；创建即可见。先挂宿主再设 parent，
        // 让 compositor 尽快拿到 transient 关系（Dialog / cascade / Aligned）。
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
            is WindowPosition.Aligned -> {
                // Dialog 默认 Aligned(Center)：xdg_toplevel_set_parent(锚点)
                if (!LinuxApplicationHost.placeAligned(app.composeWindow)) {
                    handle.applyPosition(pos)
                }
            }
            WindowPosition.PlatformDefault -> {
                // 有锚点 → set_parent（compositor 叠放/居中）；首扇无锚点交给 compositor。
                LinuxApplicationHost.placeCascaded(app.composeWindow)
            }
        }
        return handle
    }

    override fun runApplicationPump(shouldContinue: () -> Boolean) {
        LinuxApplicationHost.runSharedPump(shouldContinue)
    }

    override fun wakeApplicationPump() {
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
        /** Absolute / always-on-top 限制只打一次日志，避免 SideEffect 刷屏。 */
        private var absolutePositionLogged = false
        private var alwaysOnTopLogged = false
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
        app.composeWindow.alwaysOnTop = alwaysOnTop
        val accepted = app.composeWindow.window.setAlwaysOnTop(alwaysOnTop)
        if (alwaysOnTop && !accepted && !alwaysOnTopLogged) {
            alwaysOnTopLogged = true
            println(
                "composekn: alwaysOnTop is unsupported on standard xdg-shell " +
                    "(requested=$alwaysOnTop; flag stored only)",
            )
        }
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
        app.composeWindow.window.requestSize(
            size.width.value.toInt().coerceAtLeast(1),
            size.height.value.toInt().coerceAtLeast(1),
        )
    }

    override fun applyPosition(position: WindowPosition) {
        when (position) {
            is WindowPosition.Absolute -> {
                // 标准 xdg-shell 无绝对坐标；不伪造 (0,0)。
                if (!absolutePositionLogged) {
                    absolutePositionLogged = true
                    println(
                        "composekn: WindowPosition.Absolute is a no-op on Wayland " +
                            "(compositor owns placement; requested " +
                            "${position.x.value.toInt()},${position.y.value.toInt()})",
                    )
                }
            }
            is WindowPosition.Aligned -> {
                if (!LinuxApplicationHost.placeAligned(app.composeWindow)) {
                    println(
                        "composekn: WindowPosition.Aligned has no anchor yet " +
                            "(will rely on compositor default)",
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
        // Wayland 客户端通常拿不到屏幕坐标；保持 Absolute(0,0) 表示 unspecified。
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

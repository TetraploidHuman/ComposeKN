/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * 声明式 Window：DisposableEffect 创建/销毁原生窗；系统关窗只调 onCloseRequest。
 */

@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.awaitCancellation

/**
 * Composes a platform window. Entering composition creates the native window;
 * leaving composition disposes it.
 *
 * 系统关窗（WM_CLOSE）**不会**销毁 HWND，只调用 [onCloseRequest]
 *（对齐 AWT DO_NOTHING_ON_CLOSE）。真正销毁发生在离开 composition 时。
 *
 * ```
 * fun main() = application {
 *     Window(onCloseRequest = ::exitApplication) { }
 * }
 * ```
 *
 * 注意：原生窗的 Compose 内容跑在**独立** [androidx.compose.ui.platform.FrameRecomposer] 上，
 * 不是 application [Recomposer] 的子 composition。因此必须在这里挂一个
 * [LaunchedEffect] 保活 application 层，否则 `recomposer.close(); join()` 会立刻返回、
 * 共享泵退出、进程闪退（v0.5.21 真机复现）。
 */
@Composable
fun Window(
    onCloseRequest: () -> Unit,
    state: WindowState = rememberWindowState(),
    visible: Boolean = true,
    title: String = "Untitled",
    undecorated: Boolean = false,
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val backend = remember { ComposeNativeWindowBackendRegistry.requireBackend() }
    val handle = remember {
        backend.createWindow(
            ComposeNativeWindowCreateParams(
                title = title,
                size = state.size,
                position = state.position,
                placement = state.placement,
                isMinimized = state.isMinimized,
                undecorated = undecorated,
                resizable = resizable,
                alwaysOnTop = alwaysOnTop,
                onCloseRequest = onCloseRequest,
            ),
        )
    }

    val scope = remember(handle) {
        object : FrameWindowScope {
            override val window: ComposeNativeWindowHandle get() = handle
        }
    }

    val latestContent = rememberUpdatedState(content)
    val latestOnClose = rememberUpdatedState(onCloseRequest)

    // 保活 application Recomposer：本窗在 composition 里的整段寿命。
    LaunchedEffect(handle) {
        awaitCancellation()
    }

    // 属性同步（不要每帧 setContent —— 那会重置场景）
    SideEffect {
        handle.setTitle(title)
        handle.setResizable(resizable)
        handle.setAlwaysOnTop(alwaysOnTop)
        handle.setOnCloseRequest { latestOnClose.value() }
        handle.applyPlacement(state.placement, state.isMinimized)
        if (state.size.width.isSpecified && state.size.height.isSpecified) {
            handle.applySize(state.size)
        }
        when (val pos = state.position) {
            is WindowPosition.Absolute -> handle.applyPosition(pos)
            is WindowPosition.Aligned -> handle.applyPosition(pos)
            WindowPosition.PlatformDefault -> Unit
        }
    }

    DisposableEffect(handle) {
        if (visible) {
            handle.setContent {
                latestContent.value.invoke(scope)
            }
        }
        onDispose {
            handle.dispose()
        }
    }
}

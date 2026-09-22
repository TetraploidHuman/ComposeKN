/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 *
 * 声明式 DialogWindow：独立原生窗 + 软模态（对齐 Desktop DocumentModal 语义的子集）。
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
 * Composes a platform dialog window. Entering composition creates a native window;
 * leaving composition disposes it.
 *
 * 对齐 Desktop：[DialogWindow] 是**独立顶层窗**（不是场景内 AlertDialog）。
 * 系统关窗只调 [onCloseRequest]；真正销毁在离开 composition 时。
 *
 * 软模态：创建参数带 `isDialog=true`，宿主在有对话框时禁用其它窗输入
 *（Win32 `EnableWindow`；Linux 首版跳过非对话框窗的 pointer 事件）。
 *
 * ```
 * fun main() = application {
 *     var open by remember { mutableStateOf(false) }
 *     Window(onCloseRequest = ::exitApplication) {
 *         Button(onClick = { open = true }) { Text("Open") }
 *     }
 *     if (open) {
 *         DialogWindow(onCloseRequest = { open = false }) { … }
 *     }
 * }
 * ```
 */
@Composable
fun DialogWindow(
    onCloseRequest: () -> Unit,
    state: DialogState = rememberDialogState(),
    visible: Boolean = true,
    title: String = "Untitled",
    undecorated: Boolean = false,
    resizable: Boolean = true,
    alwaysOnTop: Boolean = false,
    content: @Composable DialogWindowScope.() -> Unit,
) {
    val backend = remember { ComposeNativeWindowBackendRegistry.requireBackend() }
    val handle = remember {
        backend.createWindow(
            ComposeNativeWindowCreateParams(
                title = title,
                size = state.size,
                position = state.position,
                undecorated = undecorated,
                resizable = resizable,
                alwaysOnTop = alwaysOnTop,
                isDialog = true,
                onCloseRequest = onCloseRequest,
            ),
        )
    }

    val scope = remember(handle) {
        object : DialogWindowScope {
            override val window: ComposeNativeWindowHandle get() = handle
        }
    }

    val latestContent = rememberUpdatedState(content)
    val latestOnClose = rememberUpdatedState(onCloseRequest)
    val currentState = rememberUpdatedState(state)

    val appliedState = remember {
        object {
            var size = state.size
            var position = state.position
        }
    }

    LaunchedEffect(handle) {
        awaitCancellation()
    }

    DisposableEffect(handle) {
        handle.setGeometryListener { snap ->
            val s = currentState.value
            s.size = snap.size
            s.position = snap.position
            appliedState.size = snap.size
            appliedState.position = snap.position
        }
        onDispose {
            handle.setGeometryListener(null)
        }
    }

    SideEffect {
        handle.setTitle(title)
        handle.setResizable(resizable)
        handle.setAlwaysOnTop(alwaysOnTop)
        handle.setOnCloseRequest { latestOnClose.value() }

        if (state.size != appliedState.size &&
            state.size.width.isSpecified &&
            state.size.height.isSpecified
        ) {
            handle.applySize(state.size)
            appliedState.size = state.size
        }
        if (state.position != appliedState.position) {
            when (val pos = state.position) {
                is WindowPosition.Absolute -> handle.applyPosition(pos)
                is WindowPosition.Aligned -> handle.applyPosition(pos)
                WindowPosition.PlatformDefault -> Unit
            }
            appliedState.position = state.position
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

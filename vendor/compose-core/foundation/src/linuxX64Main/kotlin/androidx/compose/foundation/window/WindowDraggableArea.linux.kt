/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 */

package androidx.compose.foundation.window

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.WindowScope

/**
 * 可拖动区域：指针按下后发起原生交互式拖窗（对齐 Desktop [WindowDraggableArea]）。
 *
 * Win32 / Wayland 都走 [androidx.compose.ui.window.ComposeNativeWindowHandle.beginMove]
 *（HTCAPTION / xdg_toplevel_move），不用 AWT setLocation。
 */
@Composable
fun WindowScope.WindowDraggableArea(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier.pointerInput(window) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                window.beginMove()
            }
        },
        propagateMinConstraints = true,
        content = { content() },
    )
}

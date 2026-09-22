/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Stable

/**
 * Receiver scope for [Window] content（无 AWT；[window] 为原生句柄）。
 */
@Stable
interface WindowScope {
    val window: ComposeNativeWindowHandle
}

/**
 * Frame window scope（[MenuBar] 挂原生菜单栏；Win32 = HMENU，其它平台 no-op）。
 */
@Stable
interface FrameWindowScope : WindowScope

/**
 * Receiver scope for [DialogWindow] content.
 */
@Stable
interface DialogWindowScope : WindowScope

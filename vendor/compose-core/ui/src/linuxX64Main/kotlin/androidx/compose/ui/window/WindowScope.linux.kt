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
 * Frame window scope（Desktop 上还有 MenuBar；KN 暂不暴露菜单 API）。
 */
@Stable
interface FrameWindowScope : WindowScope

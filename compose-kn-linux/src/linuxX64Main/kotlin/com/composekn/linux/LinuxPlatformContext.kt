@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

internal class LinuxPlatformContext(
    private val archComponentsOwner: DefaultArchitectureComponentsOwner,
    private val linuxTextInputService: LinuxTextInputService,
) : PlatformContext by PlatformContext.Empty() {
    override val architectureComponentsOwner get() = archComponentsOwner

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        linuxTextInputService.startInputMethod(request)
    }
}

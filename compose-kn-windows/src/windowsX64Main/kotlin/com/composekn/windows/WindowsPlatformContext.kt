@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Windows platform context implementation.
 */
internal class WindowsPlatformContext(
    private val archComponentsOwner: DefaultArchitectureComponentsOwner,
    private val windowsTextInputService: WindowsTextInputService,
) : PlatformContext by PlatformContext.Empty() {
    override val architectureComponentsOwner get() = archComponentsOwner

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        windowsTextInputService.startInputMethod(request)
    }
}

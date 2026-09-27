@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.linux

import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformDragAndDropManager
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import org.jetbrains.skiko.WaylandWindow

internal class LinuxPlatformContext(
    private val archComponentsOwner: DefaultArchitectureComponentsOwner,
    private val linuxTextInputService: LinuxTextInputService,
) : PlatformContext by PlatformContext.Empty() {
    override val architectureComponentsOwner get() = archComponentsOwner

    /** 提供当前 [WaylandWindow]，供拖放发出侧调 `start_drag`。 */
    var dragWindowProvider: (() -> WaylandWindow?)? = null

    private val dragAndDropManagerImpl =
        LinuxDragAndDropManager { dragWindowProvider?.invoke() }

    override val dragAndDropManager: PlatformDragAndDropManager
        get() = dragAndDropManagerImpl

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        linuxTextInputService.startInputMethod(request)
    }
}

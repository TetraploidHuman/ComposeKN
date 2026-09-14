@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.composekn.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.DefaultArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 宿主窗口的 [WindowInfo]。
 *
 * 坑（**弹层定位全部塌到窗口左上角**的根因）：
 *   `PlatformContext.Empty()` 自带的 `WindowInfoImpl` 里 `containerSize` 恒为 `IntSize.Zero`，
 *   而 Popup/Dialog 的定位与裁剪完全依赖 `LocalWindowInfo.current.containerSize`：
 *
 *     * `Popup.skiko.kt` 的 `rememberPopupMeasurePolicy` 会把算出来的位置过一遍
 *       `clipPosition(position, contentSize, containerSize)`：
 *           `position.x.coerceIn(0, containerSize.width - contentSize.width)`
 *       → containerSize 为 0 时区间是 `coerceIn(0, 负数)`，任何坐标都被夹到 (0, 0)；
 *     * `Dialog.skiko.kt` 用 containerSize 把对话框摆到窗口中央。
 *
 *   结果就是「下拉菜单 / 对话框 / tooltip 全部画在窗口左上角」，而且它们仍然能渲染、
 *   能点击（只是位置错），所以光看「画廊能画出来」的断言完全发现不了。
 *
 *   对照实现：`ImageComposeScene.skiko.kt` 里 `containerSize = imageSize`；
 *   compose-desktop 的 `PlatformWindowContext.desktop.kt` 里
 *   `_windowInfo.containerSize = component.sizeInPx.roundToIntSize()`。
 *   —— 两者都是宿主负责喂尺寸，我们这边漏了。
 *
 * 三个属性都用 `mutableStateOf` 包装：弹层在 measure 阶段读它，宿主改尺寸时必须触发
 * 重新测量（否则窗口缩放/DPI 变化后弹层位置还是旧的）。
 */
private class WindowsWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean by mutableStateOf(true)
    override var containerSize: IntSize by mutableStateOf(IntSize.Zero)
    override var containerDpSize: DpSize by mutableStateOf(DpSize.Zero)
    override var keyboardModifiers: PointerKeyboardModifiers by mutableStateOf(PointerKeyboardModifiers())
}

/**
 * Windows platform context implementation.
 */
internal class WindowsPlatformContext(
    private val archComponentsOwner: DefaultArchitectureComponentsOwner,
    private val windowsTextInputService: WindowsTextInputService,
) : PlatformContext by PlatformContext.Empty() {
    private val windowInfoImpl = WindowsWindowInfo()

    override val windowInfo: WindowInfo get() = windowInfoImpl

    override val architectureComponentsOwner get() = archComponentsOwner

    /**
     * 同步宿主窗口尺寸（[WindowsComposeApplication] 在装载内容和每帧渲染前调用）。
     *
     * 必须在**同一帧的 measure 之前**调用：Popup/Dialog 的 measure policy 会读它做定位。
     */
    fun updateContainerSize(size: IntSize, density: Density) {
        windowInfoImpl.containerSize = size
        // 用 .dp 构造：DpSize(width: Dp, height: Dp) 是 Dp.kt 里的顶层工厂函数
        //（同名 value class 的构造函数是 internal 且只吃 packedValue，别具名传参）
        windowInfoImpl.containerDpSize = DpSize(
            (size.width / density.density).dp,
            (size.height / density.density).dp,
        )
    }

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        windowsTextInputService.startInputMethod(request)
    }

    /**
     * 光标形状。
     *
     * 上游 skiko 的 PlatformContext 默认实现是空的（`setPointerIcon = Unit`），
     * 所以 K/N 平台上「鼠标悬停到手型」需要各宿主自己接一下（JVM 桌面接的是 AWT
     * `contentComponent.cursor`，见 ComposeSceneMediator.desktop.kt）。
     * Compose 侧不用改任何行为：`clickable` 默认就会请求 Hand。
     *
     * [cursorSink] 由 [WindowsComposeApplication] 在窗口可用时接上。
     */
    var cursorSink: ((Int) -> Unit)? = null

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        cursorSink?.invoke(win32CursorKind(pointerIcon))
    }
}

/** Compose 的 PointerIcon -> Win32 光标种类（0=箭头 1=手 2=文本I型 3=十字）。 */
internal fun win32CursorKind(pointerIcon: PointerIcon): Int = when (pointerIcon) {
    PointerIcon.Hand -> 1
    PointerIcon.Text -> 2
    PointerIcon.Crosshair -> 3
    // 自定义 PointerIcon 实现（本平台不渲染自定义位图）与 Default 都退回箭头
    else -> 0
}

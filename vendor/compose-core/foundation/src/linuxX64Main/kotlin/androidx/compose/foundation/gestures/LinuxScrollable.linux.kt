/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.foundation.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

// ============================================================================
// ComposeKN 本地改动（对应上游的 LinuxScrollable.linux.kt）
//
// 上游/先前版本：`calculateMouseWheelScroll` 直接返回 `Offset.Zero`。
// 而 `MouseWheelScrollingLogic.onMouseWheel` 拿到 0 会走
// `if (targetValue.isLowScrollingDelta()) return`（|value| < 0.5 即视为 0），
// 于是**所有**基于 scrollable 的容器（LazyColumn / verticalScroll / LazyRow /
// scrollbar …）在 K/N 原生后端上完全不响应鼠标滚轮。
//
// mingwX64（Windows 原生）复用 linuxX64Main 这份源集，所以两个平台一起中招。
// 由 ComposeKN 自检发现：离屏 `interaction/wheel-scroll-state/pixels` 与
// 真实窗口 `window/wheel-scroll`（见 HANDOVER §14）。
//
// 事件侧约定（两个平台的 mapper 都遵守）：`PointerEvent.scrollDelta` 的
// **正数 = 向下滚**（`canConsumeDelta(delta > 0) == canScrollForward`，
// 即 ScrollState.value 增大）：
//   - Windows：WM_MOUSEWHEEL 的 delta/120 是「格数」，正数 = 滚轮向上；
//     ComposeKN 的 mapper 取负后得到「正 = 向下」。
//     每格滚动量沿用上游 Windows 桌面版公式：视口高度 / 20。
//   - Linux/Wayland：wl_pointer.axis 的 value 已经是 surface-local 像素
//     （mapper 乘过 contentScale），直接透传。
// ============================================================================

internal actual fun CompositionLocalConsumerModifierNode.platformScrollConfig(): ScrollConfig =
    NativeScrollConfig

private val NativeScrollConfig = object : ScrollConfig {
    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        // 注意：`PointerEvent.totalScrollDelta` 只是桌面(JVM)源码集里的私有扩展，
        // 这里自己把每个 change 的 scrollDelta 加起来。
        val delta = event.changes.fold(Offset.Zero) { acc, change -> acc + change.scrollDelta }
        // Linux/Wayland 的 delta 已经是像素；Windows 的是「格」，换算成像素。
        if (hostOs != OS.Windows) return delta
        // bounds 为 0（尚未布局）时用固定值兜底，避免又退化成「静默零滚动」
        val perNotchY = if (bounds.height > 0) bounds.height / 20f else 50f
        val perNotchX = if (bounds.width > 0) bounds.width / 20f else 50f
        return Offset(delta.x * perNotchX, delta.y * perNotchY)
    }
}

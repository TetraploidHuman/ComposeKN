/*
 * Copyright 2021 The Android Open Source Project
 * Copyright 2026 The ComposeKN Authors (linuxX64 / mingw port)
 */

package androidx.compose.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.takeOrElse

/**
 * Creates a [WindowState] remembered across compositions.
 */
@Composable
fun rememberWindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    size: DpSize = DpSize(800.dp, 600.dp),
): WindowState = rememberSaveable(saver = WindowStateImpl.Saver(position)) {
    WindowStateImpl(placement, isMinimized, position, size)
}

@Composable
fun rememberWindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    width: Dp = 800.dp,
    height: Dp = 600.dp,
): WindowState = rememberSaveable(saver = WindowStateImpl.Saver(position)) {
    WindowStateImpl(placement, isMinimized, position, DpSize(width, height))
}

/**
 * Hoisted window attributes (size / position / placement).
 */
fun WindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    size: DpSize = DpSize(800.dp, 600.dp),
): WindowState = WindowStateImpl(placement, isMinimized, position, size)

fun WindowState(
    placement: WindowPlacement = WindowPlacement.Floating,
    isMinimized: Boolean = false,
    position: WindowPosition = WindowPosition.PlatformDefault,
    width: Dp = 800.dp,
    height: Dp = 600.dp,
): WindowState = WindowStateImpl(placement, isMinimized, position, DpSize(width, height))

/**
 * A state object that can be hoisted to control and observe window attributes.
 */
interface WindowState {
    var placement: WindowPlacement
    var isMinimized: Boolean
    var position: WindowPosition
    var size: DpSize
}

private class WindowStateImpl(
    placement: WindowPlacement,
    isMinimized: Boolean,
    position: WindowPosition,
    size: DpSize,
) : WindowState {
    override var placement by mutableStateOf(placement)
    override var isMinimized by mutableStateOf(isMinimized)
    override var position by mutableStateOf(position)
    override var size by mutableStateOf(size)

    companion object {
        fun Saver(unspecifiedPosition: WindowPosition) = listSaver<WindowState, Any>(
            save = {
                listOf(
                    it.placement.ordinal,
                    it.isMinimized,
                    it.position.isSpecified,
                    it.position.x.value,
                    it.position.y.value,
                    it.size.takeOrElse { DpSize.Zero }.width.value,
                    it.size.takeOrElse { DpSize.Zero }.height.value,
                    it.size.isSpecified,
                )
            },
            restore = { state ->
                WindowStateImpl(
                    placement = WindowPlacement.entries[state[0] as Int],
                    isMinimized = state[1] as Boolean,
                    position = if (state[2] as Boolean) {
                        WindowPosition((state[3] as Float).dp, (state[4] as Float).dp)
                    } else {
                        unspecifiedPosition
                    },
                    size = if (state.getOrNull(7) != false) {
                        DpSize((state[5] as Float).dp, (state[6] as Float).dp)
                    } else {
                        DpSize.Unspecified
                    },
                )
            },
        )
    }
}

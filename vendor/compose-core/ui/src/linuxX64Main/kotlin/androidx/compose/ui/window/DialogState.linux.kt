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
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.takeOrElse

/**
 * Creates a [DialogState] remembered across compositions.
 */
@Composable
fun rememberDialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    size: DpSize = DpSize(400.dp, 300.dp),
): DialogState = rememberSaveable(saver = DialogStateImpl.Saver(position)) {
    DialogStateImpl(position, size)
}

@Composable
fun rememberDialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    width: Dp = 400.dp,
    height: Dp = 300.dp,
): DialogState = rememberSaveable(saver = DialogStateImpl.Saver(position)) {
    DialogStateImpl(position, DpSize(width, height))
}

fun DialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    size: DpSize = DpSize(400.dp, 300.dp),
): DialogState = DialogStateImpl(position, size)

fun DialogState(
    position: WindowPosition = WindowPosition(Alignment.Center),
    width: Dp = 400.dp,
    height: Dp = 300.dp,
): DialogState = DialogStateImpl(position, DpSize(width, height))

/**
 * Hoisted dialog attributes (size / position). Aligns with Desktop [DialogState].
 */
interface DialogState {
    var position: WindowPosition
    var size: DpSize
}

private class DialogStateImpl(
    position: WindowPosition,
    size: DpSize,
) : DialogState {
    override var position by mutableStateOf(position)
    override var size by mutableStateOf(size)

    companion object {
        fun Saver(unspecifiedPosition: WindowPosition) = listSaver<DialogState, Any>(
            save = {
                listOf(
                    it.position.isSpecified,
                    it.position.x.value,
                    it.position.y.value,
                    it.size.takeOrElse { DpSize.Zero }.width.value,
                    it.size.takeOrElse { DpSize.Zero }.height.value,
                    it.size.isSpecified,
                )
            },
            restore = { state ->
                DialogStateImpl(
                    position = if (state[0] as Boolean) {
                        WindowPosition((state[1] as Float).dp, (state[2] as Float).dp)
                    } else {
                        unspecifiedPosition
                    },
                    size = if (state.getOrNull(5) != false) {
                        DpSize((state[3] as Float).dp, (state[4] as Float).dp)
                    } else {
                        DpSize.Unspecified
                    },
                )
            },
        )
    }
}

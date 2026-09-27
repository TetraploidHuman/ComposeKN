@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.composekn.linux.registerComposeKnLinuxBackend
import com.lumicode.editor.App
import com.lumicode.editor.model.SampleWorkspace
import com.lumicode.editor.state.IdeState
import kotlinx.cinterop.toKString
import org.jetbrains.skiko.initLinuxMainThread
import platform.posix.getenv

/**
 * LumiCode on ComposeKN / Kotlin Native (linuxX64 · Wayland).
 */
fun main(args: Array<String>) {
    initLinuxMainThread()
    registerComposeKnLinuxBackend()

    val override = getenv("LUMICODE_WINDOW_SIZE")
        ?.toKString()
        ?.split('x')
        ?.mapNotNull { it.trim().toFloatOrNull() }
        ?.takeIf { it.size == 2 }
    val size = if (override != null) {
        DpSize(override[0].dp, override[1].dp)
    } else {
        DpSize(1600.dp, 940.dp)
    }

    application {
        val windowState = rememberWindowState(
            position = WindowPosition(0.dp, 0.dp),
            size = size,
        )
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "LumiCode — ANALYSIS OS (ComposeKN)",
        ) {
            val state = androidx.compose.runtime.remember { IdeState(SampleWorkspace.files) }
            App(state)
        }
    }
}

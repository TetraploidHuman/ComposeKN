@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lumicode.editor.LumiCodeDesktop
import com.lumicode.editor.LumiCodeDesktopRoot
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * LumiCode on ComposeKN / Kotlin Native (mingwX64 · Win32).
 *
 * registerBackend：由 com.composekn.host entry wrapper 负责。
 */
fun main(args: Array<String>) {
    val windowSize = LumiCodeDesktop.parseSize(getenv("LUMICODE_WINDOW_SIZE")?.toKString())

    application {
        val windowState = rememberWindowState(size = windowSize.size)
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = LumiCodeDesktop.TitleKn,
        ) {
            LumiCodeDesktopRoot()
        }
    }
}

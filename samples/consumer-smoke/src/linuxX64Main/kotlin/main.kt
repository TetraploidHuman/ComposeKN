package main

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main(args: Array<String>) {
    // 仅验证依赖解析 + 编译；CI 不跑 GUI
    if (args.contains("--compile-only")) return
    application {
        Window(onCloseRequest = ::exitApplication, title = "consumer-smoke") {
            Text("ComposeKN consumer-smoke")
        }
    }
}

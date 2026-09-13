@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import com.composekn.windows.WindowsComposeApplication
import com.composekn.windows.internal.winlog
import kotlin.system.exitProcess
import platform.posix.getenv
import kotlinx.cinterop.toKString

/**
 * ComposeKN Windows 原生 demo。
 *
 * 两种运行模式：
 *  - 默认：组件画廊（人工测试用）
 *  - `COMPOSEKN_SELFTEST=1|logic|window|all`（或命令行参数 `--selftest`）：
 *    自动化自检，跑完以退出码 0/1 汇报结果（见 SelfTest.kt）
 */
fun main(args: Array<String>) {
    val mode = resolveSelfTestMode(args)
    if (mode != null) {
        val passed = runSelfTest(mode)
        // 明确用退出码汇报，便于 script/CI 断言
        exitProcess(if (passed) 0 else 1)
    }

    println("ComposeKN Windows: Starting component gallery")
    log("main: entry (Kotlin main reached)")

    val probe = GalleryProbe()
    val app = WindowsComposeApplication(
        title = "ComposeKN Windows Demo",
        width = 1100,
        height = 760,
    )
    app.run {
        // 每帧 +1：既驱动 HUD 上的帧计数，也让界面持续重组（等价于动画场景的压力）。
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { probe.frames++ }
            }
        }
        ComponentGallery(probe = probe, window = app.window)
    }
    println("ComposeKN Windows: window loop finished")
}

/**
 * `--selftest[=logic|window|all]` 或环境变量 `COMPOSEKN_SELFTEST=1|logic|window|all`。
 * 返回 null 表示正常运行画廊。
 */
private fun resolveSelfTestMode(args: Array<String>): String? {
    args.firstOrNull { it.startsWith("--selftest") }?.let { arg ->
        val value = arg.substringAfter('=', "").trim()
        return normalizeSelfTestMode(if (value.isEmpty()) "all" else value)
    }
    val env = getenv("COMPOSEKN_SELFTEST")?.toKString()?.trim()
    if (env.isNullOrEmpty() || env == "0") return null
    return normalizeSelfTestMode(env)
}

private fun normalizeSelfTestMode(value: String): String = when (value.lowercase()) {
    "1", "true", "yes", "on", "all", "full" -> "all"
    "logic", "offline", "offscreen" -> "logic"
    "window", "win" -> "window"
    else -> "all"
}

private fun log(message: String) {
    try {
        winlog(message)
    } catch (t: Throwable) {
        // 诊断日志失败不影响主流程
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // 看门狗：自检永远不应该「挂着不动」——CI 上真挂了的话，宁可 7 分钟后
        // 带着诊断信息退出（退出码 2），也不要耗到 job 超时、什么都看不到。
        // 正常跑完（本地 ~40s、CI 慢几倍）远远到不了这个上限。
        startSelfTestWatchdog(seconds = 420)
        println("SELFTEST: watchdog armed (420s)")
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

/**
 * 自检看门狗（只在 selftest 模式启用）。
 *
 * 用途：把「卡死」变成「带诊断的失败」——CI 上出现过自检挂住、job 一直耗到超时、
 * 日志里没有任何线索的情况；现在超时后直接打印一行诊断并 `exitProcess(2)`，
 * 脚本/CI 立刻能区分「挂住」与「断言失败」。
 *
 * 注意：Kotlin/Native 里没有 `java.lang.Thread`，所以用协程 + `Dispatchers.Default`；
 * 这也顺便覆盖了「真机上手动跑 `--selftest` 没有外部 timeout」的场景。
 */
private fun startSelfTestWatchdog(seconds: Int) {
    CoroutineScope(Dispatchers.Default).launch {
        delay(seconds * 1000L)
        println("SELFTEST: WATCHDOG TIMEOUT after ${seconds}s（自检挂住，强制退出，退出码 2）")
        exitProcess(2)
    }
}

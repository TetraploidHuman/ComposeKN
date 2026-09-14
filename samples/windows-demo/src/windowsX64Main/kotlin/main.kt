@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import com.composekn.windows.WindowsComposeApplication
import com.composekn.windows.internal.winlog
import org.jetbrains.skiko.win32ProcessorCount
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

    val animate = resolveGalleryAnimation(args)
    println("ComposeKN Windows: gallery animation = $animate（--no-animate / COMPOSEKN_GALLERY_ANIMATE=0 可关）")

    val probe = GalleryProbe()
    val app = WindowsComposeApplication(
        title = "ComposeKN Windows Demo",
        width = 1100,
        height = 760,
    )
    app.run {
        if (animate) {
            // 每帧 +1：既驱动 HUD 上的帧计数，也让界面持续重组（等价于动画场景的压力）。
            LaunchedEffect(Unit) {
                while (true) {
                    withFrameNanos {
                        probe.frames++
                        probe.animTicks++
                    }
                }
            }
        } else {
            // 关掉动画时的静置画面：HUD 里的 frames 不再增长，窗口应当**完全静止**
            // （宿主按需渲染 -> 没有失效就不重绘 -> CPU ≈ 0）。用来对比/演示。
            LaunchedEffect(Unit) {
                probe.frames = 0
            }
        }

        val cores = win32ProcessorCount

        // PERF-BANNER + 每秒一行 GALLERY-STATS：同时写 stdout 和 exe 同目录的
        // composekn-startup.log。真机上任务管理器看不了细节时，跑一遍把日志拷出来即可。
        //
        // 字段含义：
        //   fps            —— 宿主真实渲染帧率（= 每秒重绘次数）
        //   frames/s       —— 这一秒渲染的帧数
        //   recompose(...) —— 各作用域这一秒的重组次数：gallery=根、hud=HUD 函数体、
        //                     hudInner=读 frames 的最小作用域、summary=文本重算次数。
        //                     动画在跑时预期是 0/0/≈刷新率/≈刷新率 —— 只有最小作用域
        //                     重组，根和 HUD 函数体不涨，就是「按需重组生效」的证据。
        //   cpu            —— 本进程这一秒消耗的 CPU 时间（1000ms/s = 满一个逻辑核），
        //                     由 GetProcessTimes 自测，不需要任务管理器。
        LaunchedEffect(Unit) {
            val win = app.window
            // 窗口是在 window.run() 里创建的：必须等它出现再读 dpi/刷新率，
            // 否则 banner 会打出「刷新率=0Hz」，看着像 VREFRESH 失败（v0.3.1 就是这样，
            // 排查时白绕了一圈）。
            while (win.nativeWindow == null) delay(10)

            val rawHz = win.rawRefreshHz
            val effHz = win.effectiveRefreshHz
            val hzNote = if (rawHz == effHz) {
                "${effHz}Hz"
            } else {
                "原始=${rawHz}Hz 生效=${effHz}Hz（VREFRESH 不可信，已回退）"
            }
            val banner = "PERF-BANNER: 窗口=${win.logicalWidth}x${win.logicalHeight}dp" +
                "（物理 ${(win.logicalWidth * win.dpiScale).toInt()}x${(win.logicalHeight * win.dpiScale).toInt()}）" +
                " dpi=${fmt1(win.dpiScale.toDouble())}" +
                " 刷新率=$hzNote 帧间隔=${fmt1(1_000_000_000.0 / effHz / 1_000_000.0)}ms" +
                " 动画=$animate 逻辑核=$cores" +
                " 日志=exe 同目录 composekn-startup.log"
            println(banner)
            log(banner)

            var lastFrames = app.window.frameCount
            var lastGallery = probe.galleryComposes
            var lastHud = probe.hudComposes
            var lastHudInner = probe.hudInnerComposes
            var lastSummary = probe.summaryCalls
            var lastCpu = win.nativeWindow?.processCpuNanos() ?: -1L
            var lastLoopFrames = win.layer.loopFrameCount
            var lastTickFrames = win.layer.immediateFrameCount
            var lastMark = TimeSource.Monotonic.markNow()
            while (true) {
                delay(1000)
                val elapsed = lastMark.elapsedNow()
                val elapsedNanos = elapsed.inWholeNanoseconds
                val total = app.window.frameCount
                val fps = if (elapsedNanos > 0) {
                    (total - lastFrames) * 1_000_000_000.0 / elapsedNanos
                } else {
                    0.0
                }
                val cpuNow = app.window.nativeWindow?.processCpuNanos() ?: -1L
                val cpuDelta = if (cpuNow >= 0 && lastCpu >= 0) cpuNow - lastCpu else -1L
                val cpuBit = if (cpuDelta >= 0 && elapsedNanos > 0) {
                    val oneCore = cpuDelta.toDouble() / elapsedNanos * 100.0
                    "cpu=${fmt1(cpuDelta / 1_000_000.0)}ms/s" +
                        " (${fmt1(oneCore)}% of one core, ${fmt1(oneCore / cores)}% of $cores logical)"
                } else {
                    "cpu=n/a"
                }
                // 帧来源：loop = 消息循环按需渲染；tick = 同步渲染 tick（WM_SIZE 模态缩放周期）。
                // 真机日志里靠它区分「帧在涨」是动画在跑、还是缩放/交互在补帧。
                val loopDelta = win.layer.loopFrameCount - lastLoopFrames
                val tickDelta = win.layer.immediateFrameCount - lastTickFrames
                val line = "GALLERY-STATS: fps=${fmt1(fps)} frames/s=${total - lastFrames}" +
                    " frames(loop/tick)=+$loopDelta/+$tickDelta" +
                    " recompose(gallery/hud/hudInner/summary)=" +
                    "+${probe.galleryComposes - lastGallery}" +
                    "/+${probe.hudComposes - lastHud}" +
                    "/+${probe.hudInnerComposes - lastHudInner}" +
                    "/+${probe.summaryCalls - lastSummary} $cpuBit"
                println(line)
                log(line)
                lastFrames = total
                lastGallery = probe.galleryComposes
                lastHud = probe.hudComposes
                lastHudInner = probe.hudInnerComposes
                lastSummary = probe.summaryCalls
                lastCpu = cpuNow
                lastLoopFrames = win.layer.loopFrameCount
                lastTickFrames = win.layer.immediateFrameCount
                lastMark = TimeSource.Monotonic.markNow()
                app.window.setTitle("ComposeKN Windows Demo — ${fmt1(fps)} fps (frames=$total)")
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

/**
 * 画廊是否跑「每帧 +1」的动画（默认 true）。
 *
 * 关掉它就能观察空闲行为：宿主现在是按需渲染，静止窗口不应该产生任何帧。
 * 用法：`--no-animate` 或 `COMPOSEKN_GALLERY_ANIMATE=0`。
 */
private fun resolveGalleryAnimation(args: Array<String>): Boolean {
    if (args.any { it == "--no-animate" }) return false
    val env = getenv("COMPOSEKN_GALLERY_ANIMATE")?.toKString()?.trim()?.lowercase()
    return !(env == "0" || env == "false" || env == "no" || env == "off")
}

/** 一位小数（Kotlin/Native 上不依赖 String.format）。 */
private fun fmt1(value: Double): String {
    if (value.isNaN() || value < 0) return "n/a"
    val ticks = (value * 10.0 + 0.5).toLong()
    return "${ticks / 10}.${ticks % 10}"
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

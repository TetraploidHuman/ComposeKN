@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import com.composekn.windows.WindowsComposeWindow
import com.composekn.windows.WindowsNativeWindowHandle
import com.composekn.windows.internal.winlog
import com.composekn.windows.registerComposeKnWindowsBackend
import org.jetbrains.skiko.win32ProcessorCount
import kotlin.system.exitProcess
import platform.posix.getenv
import kotlinx.cinterop.toKString

/**
 * ComposeKN Windows 原生 demo。
 *
 * 两种运行模式：
 *  - 默认：`application { Window(...) }` 组件画廊（人工测试用，支持第二扇窗）
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

    println("ComposeKN Windows: Starting component gallery (application { Window })")
    log("main: entry (Kotlin main reached)")

    val animate = resolveGalleryAnimation(args)
    println("ComposeKN Windows: gallery animation = $animate（--no-animate / COMPOSEKN_GALLERY_ANIMATE=0 可关）")

    registerComposeKnWindowsBackend()

    application {
        val probe = remember { GalleryProbe() }
        var openSecond by remember { mutableStateOf(false) }
        var openDialog by remember { mutableStateOf(false) }
        val mainState = rememberWindowState(size = DpSize(1100.dp, 760.dp))

        val cores = win32ProcessorCount

        Window(
            onCloseRequest = ::exitApplication,
            state = mainState,
            title = "ComposeKN Windows Demo",
        ) {
            // 原生 Win32 HMENU（标题栏下方）；点选写到 probe.menuAction。
            MenuBar {
                Menu("文件(&F)") {
                    Item("新建") { probe.menuAction = "File/New" }
                    Item("打开…") { probe.menuAction = "File/Open" }
                    Separator()
                    Item("退出") {
                        probe.menuAction = "File/Exit"
                        exitApplication()
                    }
                }
                Menu("编辑(&E)") {
                    Item("撤销", enabled = false) { probe.menuAction = "Edit/Undo" }
                    Separator()
                    Item("剪切") { probe.menuAction = "Edit/Cut" }
                    Item("复制") { probe.menuAction = "Edit/Copy" }
                    Item("粘贴") { probe.menuAction = "Edit/Paste" }
                }
            }

            // 动画必须在 Window 内容里：application 层只有 YieldFrameClock（无 vsync），
            // 放外面会空转烧 CPU；这里走场景自己的 FrameRecomposer。
            if (animate) {
                LaunchedEffect(Unit) {
                    while (true) {
                        withFrameNanos {
                            probe.frames++
                            probe.animTicks++
                        }
                    }
                }
            } else {
                LaunchedEffect(Unit) {
                    probe.frames = 0
                }
            }

            val win = (window as? WindowsNativeWindowHandle)?.composeWindow
                ?: window.asPlatformWindow() as? WindowsComposeWindow
            if (win != null) {
                LaunchedEffect(win) {
                    // 窗口 attach 后才有 dpi/刷新率
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

                    var lastFrames = win.frameCount
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
                        val total = win.frameCount
                        val fps = if (elapsedNanos > 0) {
                            (total - lastFrames) * 1_000_000_000.0 / elapsedNanos
                        } else {
                            0.0
                        }
                        val cpuNow = win.nativeWindow?.processCpuNanos() ?: -1L
                        val cpuDelta = if (cpuNow >= 0 && lastCpu >= 0) cpuNow - lastCpu else -1L
                        val cpuBit = if (cpuDelta >= 0 && elapsedNanos > 0) {
                            val oneCore = cpuDelta.toDouble() / elapsedNanos * 100.0
                            "cpu=${fmt1(cpuDelta / 1_000_000.0)}ms/s" +
                                " (${fmt1(oneCore)}% of one core, ${fmt1(oneCore / cores)}% of $cores logical)"
                        } else {
                            "cpu=n/a"
                        }
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
                        win.setTitle("ComposeKN Windows Demo — ${fmt1(fps)} fps (frames=$total)")
                    }
                }

                ComponentGallery(
                    probe = probe,
                    window = win,
                    animate = animate,
                    onOpenSecondWindow = { openSecond = true },
                    onOpenDialogWindow = { openDialog = true },
                )
            }
        }

        if (openSecond) {
            Window(
                onCloseRequest = { openSecond = false },
                state = rememberWindowState(size = DpSize(480.dp, 360.dp)),
                title = "ComposeKN · 第二扇窗",
            ) {
                SecondWindowContent(onClose = { openSecond = false })
            }
        }

        if (openDialog) {
            DialogWindow(
                onCloseRequest = { openDialog = false },
                state = rememberDialogState(width = 420.dp, height = 280.dp),
                title = "ComposeKN · DialogWindow",
            ) {
                DialogWindowContent(onClose = { openDialog = false })
            }
        }
    }
    println("ComposeKN Windows: application finished")
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

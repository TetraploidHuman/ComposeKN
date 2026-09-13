@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.dp
import com.composekn.windows.MouseButton
import com.composekn.windows.WindowsComposeApplication
import com.composekn.windows.WindowsEvent
import com.composekn.windows.WindowsInputState
import com.composekn.windows.charToCodePoint
import com.composekn.windows.createKeyEvent
import com.composekn.windows.duplicateVkEntries
import com.composekn.windows.internal.GET_WHEEL_DELTA_WPARAM
import com.composekn.windows.internal.GET_X_LPARAM
import com.composekn.windows.internal.Win32Modifier
import com.composekn.windows.test.FrameSnapshot
import com.composekn.windows.test.OffscreenDriver
import com.composekn.windows.test.TestColors
import com.composekn.windows.test.renderOffscreen
import com.composekn.windows.test.snapshotSolidColor
import com.composekn.windows.vkPairCount
import com.composekn.windows.windowsVirtualKeyToComposeKey
import kotlin.math.abs

// =====================================================================
// 自动化自检（`COMPOSEKN_SELFTEST=1` / `--selftest`）
//
// 三层：
//   1) logic    —— 纯函数断言（键位映射表、消息参数解码、输入状态机）
//   2) render   —— 离屏光栅化真实 Compose 场景 + 像素断言（布局/密度/DIP/CSD 标题栏）
//   3) window   —— 真实 Win32 窗口（剪贴板桥接、逐帧循环、合成输入、干净退出）
//
// 结果以 `SELFTEST ...` 行打印，最终退出码 0（全绿）/ 1（有失败）。
// =====================================================================

private const val SELFTEST_CLIPBOARD = "ComposeKN 剪贴板 ✔ clipboard"

/** 自检用的确定性界面：所有元素的像素位置都可以手算出来。 */
private const val TEST_BG_ARGB = 0xFF102030.toInt()
private const val TEST_MARKER_ARGB = 0xFFE53935.toInt()
private const val TEST_IDLE_ARGB = 0xFFFF9800.toInt()
private const val TEST_ACTIVE_ARGB = 0xFF00C853.toInt()
private const val CHROME_ARGB = 0xFF2D2D30.toInt()

private val TEST_BG = Color(TEST_BG_ARGB)
private val TEST_MARKER = Color(TEST_MARKER_ARGB)
private val TEST_IDLE = Color(TEST_IDLE_ARGB)
private val TEST_ACTIVE = Color(TEST_ACTIVE_ARGB)

/** CSD 标题栏高度（见 WindowsWindowChrome）。 */
private const val CHROME_DP = 32f

class InteractionProbe {
    var clicked by mutableStateOf(false)
    var clickCount by mutableStateOf(0)
    var text by mutableStateOf("")
}

@Composable
private fun DeterministicTestScreen(
    probe: InteractionProbe,
    scrollState: ScrollState,
) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(TEST_BG)) {
            // 单行输入框：顶部，留 16dp 边距，占满剩余宽度
            OutlinedTextField(
                value = probe.text,
                onValueChange = { probe.text = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .fillMaxWidth(),
                singleLine = true,
                label = { Text("type here") },
            )

            // 固定 40dp 红方块，贴右上：窗口变宽时**尺寸不变**。
            // 若渲染后端把旧帧拉伸（历史 bug），这里会跟着变宽。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 100.dp)
                    .size(40.dp)
                    .background(TEST_MARKER),
            )

            // 可点击按钮：点击后颜色变绿（同时驱动重组）
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 40.dp, top = 96.dp)
                    .size(160.dp, 48.dp)
                    .background(if (probe.clicked) TEST_ACTIVE else TEST_IDLE)
                    // indication = null：去掉 Material 涟漪，让「点击后的颜色」是确定的纯色
                    // （涟漪是一层半透明叠加，会让像素断言变成模糊匹配）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        probe.clicked = true
                        probe.clickCount++
                    },
            )

            // 底部滚动区（滚轮测试）。
            // ScrollState 由调用方注入：断言时直接读 `scrollState.value`，
            // 不依赖 snapshotFlow/launched-effect 的调度时机。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(120.dp)
                    .verticalScroll(scrollState),
            ) {
                repeat(20) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .background(if (index % 2 == 0) Color(0xFF203040) else Color(0xFF304050)),
                    )
                }
            }
        }
    }
}

/**
 * 回归用界面：一个无界的 LazyColumn。它只用于验证「首帧渲染前派发事件」不会崩，
 * 因为 LazyColumn 在无界主轴约束下会直接抛异常。
 */
@Composable
private fun LazyScreen(probe: InteractionProbe) {
    LazyColumn(Modifier.fillMaxSize()) {
        items((0 until 20).toList()) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(if (index % 2 == 0) Color(0xFF203040) else Color(0xFF304050))
                    .clickable { probe.clickCount++ },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("row $index")
            }
        }
    }
}

// ---------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------

class SelfTestReport {
    private val failures = mutableListOf<String>()
    var checks: Int = 0
        private set

    private fun line(text: String) {
        println(text)
    }

    fun section(title: String) = line("SELFTEST --- $title ---")

    fun check(name: String, ok: Boolean, detail: String = "") {
        checks++
        if (ok) {
            line("SELFTEST ok   : $name")
        } else {
            failures += name
            line("SELFTEST FAIL : $name${if (detail.isEmpty()) "" else " — $detail"}")
        }
    }

    fun checkEquals(name: String, expected: Any?, actual: Any?) =
        check(name, expected == actual, "expected=$expected actual=$actual")

    fun checkNear(name: String, expected: Int, actual: Int, tolerance: Int = 2) =
        check(name, abs(expected - actual) <= tolerance, "expected≈$expected(±$tolerance) actual=$actual")

    fun finish(): Boolean {
        if (failures.isEmpty()) {
            line("SELFTEST: RESULT PASS ($checks checks, 0 failures)")
            return true
        }
        line("SELFTEST: RESULT FAIL ($checks checks, ${failures.size} failures)")
        failures.forEach { line("SELFTEST:   failed -> $it") }
        return false
    }
}

fun runSelfTest(mode: String): Boolean {
    val report = SelfTestReport()
    println("SELFTEST: mode=$mode")
    try {
        if (mode == "logic" || mode == "all") runOfflineTests(report)
        if (mode == "window" || mode == "all") runWindowTests(report)
    } catch (t: Throwable) {
        report.check(
            "selftest/unhandled-exception",
            false,
            "${t::class.simpleName}: ${t.message}\n" + t.stackTraceToString().lineSequence().take(12).joinToString("\n"),
        )
    }
    return report.finish()
}

// ---------------------------------------------------------------------
// 1) 纯逻辑
// ---------------------------------------------------------------------

private fun runOfflineTests(report: SelfTestReport) {
    logicChecks(report)
    renderChecks(report)
}

private fun logicChecks(report: SelfTestReport) {
    report.section("logic")

    report.checkEquals("logic/vk-table-no-duplicates", emptyList<Int>(), duplicateVkEntries())
    report.check("logic/vk-table-size", vkPairCount() >= 80, "size=${vkPairCount()}")

    report.checkEquals("logic/vk-rwin", Key.MetaRight, windowsVirtualKeyToComposeKey(0x5C))
    report.checkEquals("logic/vk-lwin", Key.MetaLeft, windowsVirtualKeyToComposeKey(0x5B))
    report.checkEquals("logic/vk-backslash", Key.Backslash, windowsVirtualKeyToComposeKey(0xDC))
    report.checkEquals("logic/vk-slash", Key.Slash, windowsVirtualKeyToComposeKey(0xBF))
    report.checkEquals("logic/vk-a", Key.A, windowsVirtualKeyToComposeKey(0x41))
    report.checkEquals("logic/vk-0", Key.Zero, windowsVirtualKeyToComposeKey(0x30))
    report.checkEquals("logic/vk-f12", Key.F12, windowsVirtualKeyToComposeKey(0x7B))
    report.checkEquals("logic/vk-escape", Key.Escape, windowsVirtualKeyToComposeKey(0x1B))
    report.checkEquals("logic/vk-unknown", Key.Unknown, windowsVirtualKeyToComposeKey(0xEE))

    report.checkEquals("logic/char-codepoint-ascii", 65, charToCodePoint('A'))
    report.checkEquals("logic/char-codepoint-cjk", 0x4E2D, charToCodePoint('中'))

    report.checkEquals("logic/lparam-x-negative", -1, GET_X_LPARAM(0xFFFF))
    report.checkEquals("logic/lparam-x-y", 300, GET_X_LPARAM((400 shl 16) or 300))
    report.checkEquals("logic/wheel-delta-back", -120, GET_WHEEL_DELTA_WPARAM(0xFF88 shl 16))

    val down = createKeyEvent(vk = 0x41, scanCode = 30, isKeyDown = true, character = 'a')
    report.checkEquals("logic/keyevent-type", KeyEventType.KeyDown, down.type)
    report.checkEquals("logic/keyevent-codepoint", 'a'.code, down.utf16CodePoint)
    report.checkEquals("logic/keyevent-key", Key.A, down.key)
    val up = createKeyEvent(vk = 0x41, scanCode = 30, isKeyDown = false, character = 'a')
    report.checkEquals("logic/keyevent-keyup-no-codepoint", 0, up.utf16CodePoint)

    val state = WindowsInputState()
    state.updateButton(MouseButton.Left, true)
    report.check("logic/inputstate-primary", state.buttons().isPrimaryPressed)
    state.updateButton(MouseButton.Left, false)
    report.check("logic/inputstate-primary-released", !state.buttons().isPrimaryPressed)
    state.updateModifiers(Win32Modifier.CTRL)
    report.check(
        "logic/inputstate-ctrl-bits",
        state.modifiers.isCtrlPressed && !state.modifiers.isShiftPressed,
        "ctrl=${state.modifiers.isCtrlPressed} shift=${state.modifiers.isShiftPressed}",
    )

    val probe = GalleryProbe()
    probe.resetInteractionCounters()
    report.check(
        "logic/gallery-probe-summary",
        probe.summary().contains("clicks=0") && probe.summary().contains("theme=light"),
        probe.summary(),
    )
}

// ---------------------------------------------------------------------
// 2) 离屏渲染 + 像素断言
// ---------------------------------------------------------------------

private fun renderChecks(report: SelfTestReport) {
    report.section("offscreen render")

    // 2.0 像素字节序自检：N32(BGRA) -> ARGB 的转换必须没搞反
    val solid = snapshotSolidColor(8, 8, TestColors.OPAQUE_BLUE)
    report.checkEquals("render/pixel-channel-order", TestColors.OPAQUE_BLUE, solid.colorAt(4, 4))
    report.checkEquals("render/solid-uniform", 1, solid.distinctColorCount())

    val probe = InteractionProbe()
    val scrollState = ScrollState(0)
    val app = WindowsComposeApplication(title = "ComposeKN Selftest", width = 800, height = 600)
    app.setContent(withChrome = true) { DeterministicTestScreen(probe, scrollState) }
    // 同一个 driver：帧时钟跨调用单调递增（动画/惯性滚动依赖这一点）
    val driver = OffscreenDriver(app)

    // 2.1 基准帧（density 1.0, 800x600）
    val d = 1f
    val contentTop = (CHROME_DP * d).toInt()
    val f800 = driver.render(800, 600, density = d, frames = 3)

    report.check("render/not-blank", f800.distinctColorCount() > 8, "distinct=${f800.distinctColorCount()}")
    report.checkEquals("render/bg-color", TEST_BG_ARGB, f800.colorAt(5, contentTop + 200))
    report.checkEquals("render/bg-color-bottom-left", TEST_BG_ARGB, f800.colorAt(5, 440))
    report.checkNear("render/marker-width-800", 40, f800.longestRunInRow(contentTop + 110, TEST_MARKER_ARGB), 2)
    report.checkNear("render/marker-right-edge", 799, f800.lastXInRow(contentTop + 110, TEST_MARKER_ARGB), 1)
    report.checkEquals("render/chrome-top-left", CHROME_ARGB, f800.colorAt(0, 0))
    report.checkEquals("render/chrome-top-right", CHROME_ARGB, f800.colorAt(799, 0))
    report.checkEquals("render/chrome-height-32dp", CHROME_ARGB, f800.colorAt(400, contentTop - 1))
    report.checkEquals("render/content-below-chrome", TEST_BG_ARGB, f800.colorAt(400, contentTop + 1))

    // 2.2 放大窗口：固定 40dp 的方块必须还是 40px —— 这正是「拉伸旧帧」回归测试
    val f1200 = driver.render(1200, 900, density = d, frames = 3)
    report.checkNear("render/marker-width-1200-no-stretch", 40, f1200.longestRunInRow(contentTop + 110, TEST_MARKER_ARGB), 2)
    report.checkNear("render/marker-right-edge-1200", 1199, f1200.lastXInRow(contentTop + 110, TEST_MARKER_ARGB), 1)
    report.checkEquals("render/bg-color-1200", TEST_BG_ARGB, f1200.colorAt(5, contentTop + 200))

    // 2.3 高 DPI：density 1.5 时 40dp = 60px
    val dHi = 1.5f
    val contentTopHi = (CHROME_DP * dHi).toInt()
    val fHi = driver.render(800, 600, density = dHi, frames = 3)
    report.checkNear(
        "render/marker-width-density-1.5",
        60,
        fHi.longestRunInRow(contentTopHi + 165, TEST_MARKER_ARGB),
        3,
    )
    report.checkEquals("render/chrome-height-density-1.5", CHROME_ARGB, fHi.colorAt(400, contentTopHi - 1))
    report.checkEquals("render/bg-density-1.5", TEST_BG_ARGB, fHi.colorAt(5, contentTopHi + 300))

    // 2.4 交互：点击 -> 状态 + 像素都变
    val clickX = (120 * d).toInt()
    val clickY = contentTop + (120 * d).toInt()
    val idle = driver.render(800, 600, density = d, frames = 2)
    report.checkEquals("interaction/idle-color", TEST_IDLE_ARGB, idle.colorAt(clickX, clickY))
    click(app, clickX, clickY)
    val afterClick = driver.render(800, 600, density = d, frames = 2)
    report.checkEquals("interaction/click-count", 1, probe.clickCount)
    report.checkEquals("interaction/active-color", TEST_ACTIVE_ARGB, afterClick.colorAt(clickX, clickY))

    // 2.5 交互：键盘输入落到文本框
    click(app, 400, contentTop + 44)
    typeChar(app, 'C')
    typeChar(app, 'K')
    driver.render(800, 600, density = d, frames = 3)
    report.checkEquals("interaction/typing", "CK", probe.text)

    // 2.6 交互：滚轮滚动（直接读 ScrollState，并同时验证画面确实滚动了）
    val beforeWheel = driver.render(800, 600, density = d, frames = 2)
    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = 400, y = 540))
    wheel(app, 400, 540, deltaY = -3)
    wheel(app, 400, 540, deltaY = -3)
    val afterWheel = driver.render(800, 600, density = d, frames = 8)
    report.check(
        "interaction/wheel-scroll-state",
        scrollState.value > 0,
        "value=${scrollState.value} maxValue=${scrollState.maxValue} viewport=${scrollState.viewportSize}",
    )
    report.check(
        "interaction/wheel-scroll-pixels",
        diffPixels(beforeWheel, afterWheel, 600 - 119, 599) > 200,
        "各像素差=${diffPixels(beforeWheel, afterWheel, 600 - 119, 599)}",
    )
    // 反方向滚轮必须把内容滚回去（锁死方向约定：deltaY<0 = 滚轮向下 = 内容向下）
    val scrolledDown = scrollState.value
    wheel(app, 400, 540, deltaY = 1)
    driver.render(800, 600, density = d, frames = 4)
    report.check(
        "interaction/wheel-up-scrolls-back",
        scrollState.value < scrolledDown,
        "down=$scrolledDown afterUp=${scrollState.value}",
    )

    // 2.7 画廊本身必须能渲染（组件覆盖面最大的那条路径）
    val galleryProbe = GalleryProbe()
    val galleryApp = WindowsComposeApplication(title = "gallery", width = 960, height = 700)
    galleryApp.setContent(withChrome = false) { ComponentGallery(galleryProbe, galleryApp.window) }
    val galleryFrame = renderOffscreen(galleryApp, 960, 700, density = 1f, frames = 3)
    report.check(
        "render/gallery-distinct-colors",
        galleryFrame.distinctColorCount() > 40,
        "distinct=${galleryFrame.distinctColorCount()}",
    )
    galleryApp.close()

    // 2.8 回归：首帧渲染之前到达的指针事件不得崩。
    // 曾经的 bug：scene.size 还是 0 → measureAndLayout 拿到无界约束 →
    // LazyColumn 抛 "measured with an infinity maximum height constraints" →
    // 真机上窗口刚出现就崩。这里故意在渲染前派一个鼠标事件。
    val earlyProbe = InteractionProbe()
    val earlyApp = WindowsComposeApplication(title = "early", width = 400, height = 300)
    val earlyError = try {
        earlyApp.setContent(withChrome = true) { LazyScreen(earlyProbe) }
        earlyApp.dispatchEvent(WindowsEvent.MouseMoveEvent(x = 200, y = 150))
        earlyApp.pumpDispatchers()
        renderOffscreen(earlyApp, 400, 300, density = 1f, frames = 2)
        null
    } catch (t: Throwable) {
        t
    }
    report.check(
        "render/event-before-first-render",
        earlyError == null,
        earlyError?.let { "${it::class.simpleName}: ${it.message?.take(240)}" } ?: "",
    )
    earlyApp.close()

    app.close()
}

// ---------------------------------------------------------------------
// 3) 真实窗口
// ---------------------------------------------------------------------

private fun runWindowTests(report: SelfTestReport) {
    report.section("window")
    val probe = InteractionProbe()
    val scrollState = ScrollState(0)
    val app = WindowsComposeApplication(title = "ComposeKN Selftest", width = 900, height = 600)
    var clipboardValue: String? = null
    var clickedAt = Pair(0, 0)

    app.window.frameHook = { frame ->
        if (frame == 10) {
            val w = app.window
            report.check(
                "window/created",
                w.logicalWidth > 0 && w.logicalHeight > 0,
                "size=${w.logicalWidth}x${w.logicalHeight}",
            )
            report.check("window/dpi-scale", w.dpiScale >= 1.0f, "dpi=${w.dpiScale}")
            val native = w.nativeWindow
            report.check("window/native-handle", native != null)
            if (native != null) {
                native.clipboard = SELFTEST_CLIPBOARD
                clipboardValue = native.clipboard
                report.checkEquals("window/clipboard-roundtrip", SELFTEST_CLIPBOARD, clipboardValue)
            }
            // 合成点击（逻辑坐标 -> 物理坐标）：确定性界面的按钮位于 (40..200, 128..176)dp
            val scale = w.dpiScale
            clickedAt = Pair((120 * scale).toInt(), (152 * scale).toInt())
            app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = clickedAt.first, y = clickedAt.second))
            app.dispatchEvent(
                WindowsEvent.MouseButtonEvent(
                    x = clickedAt.first,
                    y = clickedAt.second,
                    button = MouseButton.Left,
                    isPressed = true,
                ),
            )
            app.dispatchEvent(
                WindowsEvent.MouseButtonEvent(
                    x = clickedAt.first,
                    y = clickedAt.second,
                    button = MouseButton.Left,
                    isPressed = false,
                ),
            )
        }
        if (frame == 16) {
            // 真实消息循环里的滚轮：坐标是客户区物理像素，滚轮区在窗口底部
            val scale = app.window.dpiScale
            wheel(app, (450 * scale).toInt(), (540 * scale).toInt(), deltaY = -3)
        }
        if (frame == 40) {
            report.checkEquals("window/click-reaches-compose", 1, probe.clickCount)
            report.check(
                "window/wheel-scroll",
                scrollState.value > 0,
                "value=${scrollState.value} maxValue=${scrollState.maxValue}",
            )
            report.check("window/frame-count", app.window.frameCount >= 40, "frames=${app.window.frameCount}")
            app.window.requestClose()
        }
        if (frame > 200) {
            report.check("window/close-timeout", false, "frameHook 已超过 200 帧仍未退出")
            app.window.requestClose()
        }
    }

    app.run { DeterministicTestScreen(probe, scrollState) }
    report.check("window/loop-exited", true)
}

// ---------------------------------------------------------------------
// 事件注入工具
// ---------------------------------------------------------------------

private fun click(app: WindowsComposeApplication, x: Int, y: Int) {
    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = x, y = y))
    app.dispatchEvent(
        WindowsEvent.MouseButtonEvent(x = x, y = y, button = MouseButton.Left, isPressed = true),
    )
    app.dispatchEvent(
        WindowsEvent.MouseButtonEvent(x = x, y = y, button = MouseButton.Left, isPressed = false),
    )
    app.pumpDispatchers()
}

private fun typeChar(app: WindowsComposeApplication, char: Char) {
    // WM_CHAR -> WindowsEvent.KeyEvent(vk=0, character=c)：与真实桥接完全一致
    app.dispatchEvent(
        WindowsEvent.KeyEvent(
            virtualKeyCode = 0,
            scanCode = 0,
            isKeyDown = true,
            character = char,
        ),
    )
    app.pumpDispatchers()
}

/** 统计两帧在 [y0, y1] 行范围内的不同像素数（用于「内容确实滚动了」这类断言）。 */
private fun diffPixels(a: FrameSnapshot, b: FrameSnapshot, y0: Int, y1: Int): Int {
    if (a.width != b.width || a.height != b.height) return Int.MAX_VALUE
    var n = 0
    for (y in y0..y1) {
        for (x in 0 until a.width) {
            if (a.colorAt(x, y) != b.colorAt(x, y)) n++
        }
    }
    return n
}

private fun wheel(app: WindowsComposeApplication, x: Int, y: Int, deltaY: Int) {
    app.dispatchEvent(
        WindowsEvent.MouseWheelEvent(x = x, y = y, deltaX = 0, deltaY = deltaY),
    )
    app.pumpDispatchers()
}

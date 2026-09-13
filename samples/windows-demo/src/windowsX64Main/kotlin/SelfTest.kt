@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

// LocalClipboardManager/ClipboardManager 在新版 Compose 里被标记为 deprecated
// （推荐用 suspend 的 Clipboard 接口），但文本字段的复制/剪切/粘贴仍然会走它，
// 而我们的 Windows 桥接正是通过 `createPlatformClipboardManager()` 装上去的 ——
// 自检要断言的就是这条真实路径。
@file:Suppress("DEPRECATION")

package main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** 弹层（Popup）内容的颜色：整个界面上只有弹层用这个颜色，于是
 *  「画面里有几个这种像素」就等于「弹层画没画、画了多大」。
 *  故意选一个与主题无关的亮紫色，避免和 Material 主题色撞色。 */
private const val TEST_POPUP_ARGB = 0xFFB000FF.toInt()

private val TEST_BG = Color(TEST_BG_ARGB)
private val TEST_MARKER = Color(TEST_MARKER_ARGB)
private val TEST_IDLE = Color(TEST_IDLE_ARGB)
private val TEST_ACTIVE = Color(TEST_ACTIVE_ARGB)
private val TEST_POPUP = Color(TEST_POPUP_ARGB)

/** 弹层内容尺寸（dp）。 */
private const val POPUP_W_DP = 60
private const val POPUP_H_DP = 40

/** 弹层锚点在窗口内的位置（dp）；popup 的 content 从锚点左上角开始画。 */
private const val POPUP_ANCHOR_X_DP = 400
private const val POPUP_ANCHOR_Y_DP = 200

/** 下拉菜单锚点（dp）。选在空白区域，便于断言「菜单出现前这里就是背景色」。 */
private const val MENU_ANCHOR_X_DP = 520
private const val MENU_ANCHOR_Y_DP = 300

/** 焦点对照用的一对空输入框（左：会被点击获得焦点；右：保持未聚焦作对照）。 */
private const val FIELD_W_DP = 220
private const val FIELD_H_DP = 56
private const val FIELD_TOP_DP = 170
private const val FIELD_LEFT_X_DP = 16
private const val FIELD_RIGHT_X_DP = 250

/** CSD 标题栏颜色（见 WindowsWindowChrome）。 */
private const val CHROME_ARGB = 0xFF2D2D30.toInt()

/** CSD 标题栏高度（见 WindowsWindowChrome）。 */
private const val CHROME_DP = 32f

class InteractionProbe {
    var clicked by mutableStateOf(false)
    var clickCount by mutableStateOf(0)

    /** 主输入框的内容 + 选区（选区用来断言「点击定位光标」「Ctrl+A 全选」）。 */
    var value by mutableStateOf(TextFieldValue(""))

    /** 左/右对照输入框的焦点状态（由 onFocusChanged 回填）。 */
    var leftFocused by mutableStateOf(false)
    var rightFocused by mutableStateOf(false)

    /** 弹层开关（由自检代码直接切换，模拟「按钮点开菜单/对话框」）。 */
    var popupOpen by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var dialogOpen by mutableStateOf(false)

    /** 界面里拿到的 Compose 剪贴板管理器（窗口阶段用它做往返断言）。 */
    var clipboardManager: ClipboardManager? = null

    /**
     * `LocalWindowInfo.current.containerSize`。
     *
     * 这条不是「顺手也测一下」：Popup/Dialog 的定位与裁剪完全依赖它，
     * 宿主忘了喂尺寸时弹层会全部塌到窗口左上角（见 WindowsWindowInfo 的注释）。
     */
    var windowContainerSize by mutableStateOf(IntSize.Zero)

    /** 性能自检：true 时界面进入「一直在动画」的状态（withFrameNanos 每帧 +1）。 */
    var animate by mutableStateOf(false)

    /** 动画帧计数，同时显示在界面上（于是每帧都会重组/重排，和画廊里的压力循环等价）。 */
    var animFrames by mutableStateOf(0)

    /** 跨线程刷新探针：后台协程 +1，界面读取它 —— 验证「后台干完活唤醒消息泵」。 */
    var bgTick by mutableStateOf(0)

    val text: String get() = value.text

    fun setText(text: String) {
        value = TextFieldValue(text, TextRange(text.length))
    }
}

@Composable
private fun DeterministicTestScreen(
    probe: InteractionProbe,
    scrollState: ScrollState,
) {
    // 拿一份 Compose 剪贴板管理器交给探针：窗口阶段要用它做「Compose API -> skiko
    // 里的 Windows 桥接 -> Win32 剪贴板 -> 再读回来」的端到端断言。
    val clipboard = LocalClipboardManager.current
    SideEffect { probe.clipboardManager = clipboard }

    // 性能自检用的「动画」：probe.animate = true 时每帧 +1，而 animFrames 显示在
    // 界面上，于是每一帧都会重组 + 重排 + 重绘（等价于画廊里那个 withFrameNanos 循环）。
    //
    // 节奏完全由宿主帧时钟决定（withFrameNanos）：宿主必须自己持续请求下一帧，
    // 否则动画在第 1 帧之后就会停住 —— 这正是「按需渲染」最容易踩坏的地方。
    LaunchedEffect(probe.animate) {
        while (probe.animate) {
            withFrameNanos { probe.animFrames++ }
        }
    }

    // 容器尺寸（Popup/Dialog 的定位依据）
    val windowInfo = LocalWindowInfo.current
    SideEffect { probe.windowContainerSize = windowInfo.containerSize }

    MaterialTheme {
        Box(Modifier.fillMaxSize().background(TEST_BG)) {
            // 单行输入框：顶部，留 16dp 边距，占满剩余宽度。
            // 用 TextFieldValue（而不是 String）是为了能断言**选区/光标位置**
            // —— 点击定位、Ctrl+A 全选、Ctrl+C 复制都靠它来验证。
            OutlinedTextField(
                value = probe.value,
                onValueChange = { probe.value = it },
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

            // 一对**内容为空**的输入框：只被用来观察「聚焦」这一件事。
            // 空框 → 里面没有文字干扰，聚焦导致的像素变化只可能来自
            // 边框颜色（primary vs outline）和光标。
            // 两个框别的都一样，所以「左边有焦点、右边没焦点」时
            // 「左框区域像素变了、右框区域像素一点没变」是最干净的断言。
            FocusProbeField(
                focused = { probe.leftFocused = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = FIELD_LEFT_X_DP.dp, y = FIELD_TOP_DP.dp)
                    .size(FIELD_W_DP.dp, FIELD_H_DP.dp),
            )
            FocusProbeField(
                focused = { probe.rightFocused = it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = FIELD_RIGHT_X_DP.dp, y = FIELD_TOP_DP.dp)
                    .size(FIELD_W_DP.dp, FIELD_H_DP.dp),
            )

            // 弹层锚点：一个零尺寸的 Box。Popup 的内容从锚点左上角开始画，
            // 于是「亮紫色像素的包围盒」就等于弹层位置 + 尺寸 —— 位置和尺寸都能断言。
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = POPUP_ANCHOR_X_DP.dp, y = POPUP_ANCHOR_Y_DP.dp),
            ) {
                if (probe.popupOpen) {
                    Popup(
                        onDismissRequest = { probe.popupOpen = false },
                        offset = IntOffset(0, 0),
                        properties = PopupProperties(
                            focusable = false,
                            dismissOnClickOutside = false,
                        ),
                    ) {
                        Box(Modifier.size(POPUP_W_DP.dp, POPUP_H_DP.dp).background(TEST_POPUP))
                    }
                }
            }

            // 下拉菜单（Material 的真实弹层 + 自己的背景/阴影）
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = MENU_ANCHOR_X_DP.dp, y = MENU_ANCHOR_Y_DP.dp),
            ) {
                DropdownMenu(
                    expanded = probe.menuOpen,
                    onDismissRequest = { probe.menuOpen = false },
                ) {
                    DropdownMenuItem(text = { Text("menu item A") }, onClick = {})
                    DropdownMenuItem(text = { Text("menu item B") }, onClick = {})
                }
            }

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

            // 性能自检的状态显示：动画帧数 + 跨线程刷新计数。
            // 放在 (16, 240)dp —— 既有的像素断言区域（按钮/输入框/弹层/滚动/对话框
            // 中心行）都不覆盖这里，不会干扰它们。
            Text(
                text = "anim=${probe.animFrames} bg=${probe.bgTick}",
                color = Color(0xFFB0BEC5),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 16.dp, y = 240.dp),
            )

            // 对话框（比弹层更重的一层：带遮罩 + 自己的窗口/图层）
            if (probe.dialogOpen) {
                AlertDialog(
                    onDismissRequest = { probe.dialogOpen = false },
                    title = { Text("selftest dialog") },
                    text = { Text("对话框图层的内容") },
                    confirmButton = { TextButton(onClick = { probe.dialogOpen = false }) { Text("OK") } },
                )
            }
        }
    }
}

/** 只用来观测焦点的空输入框（内容永远是空串，不接受输入）。 */
@Composable
private fun FocusProbeField(
    focused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.onFocusChanged { focused(it.isFocused) },
        singleLine = true,
        label = { Text("focus probe") },
    )
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
        // Kotlin/Native 的 stdout 接到管道时是块缓冲的：不 flush 的话，
        // 进程挂住时日志里连「最后跑到哪一条断言」都看不到。
        try {
            platform.posix.fflush(platform.posix.stdout)
        } catch (_: Throwable) {
        }
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
        if (mode == "logic" || mode == "all") {
            report.section("phase: 离屏 logic 开始")
            runOfflineTests(report)
            report.section("phase: 离屏 logic 结束")
        }
        if (mode == "window" || mode == "all") {
            report.section("phase: 真实窗口 window 开始")
            runWindowTests(report)
            report.section("phase: 真实窗口 window 结束")
        }
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

    // 2.7 焦点 / 光标 / 选区
    //
    // 一对内容为空的输入框：左边点击后必须有焦点、右边必须没有；而且
    // 「左框区域的像素变了、右框区域一点没变」—— 说明焦点是**可见**的
    // （Material 的聚焦边框 + 光标），而不是只更新了内部状态。
    val beforeFocus = driver.render(800, 600, density = d, frames = 6)
    // 宿主必须把窗口尺寸喂给 LocalWindowInfo（弹层定位全依赖它）
    report.checkEquals("window-info/container-size", IntSize(800, 600), probe.windowContainerSize)
    report.check(
        "focus/initially-unfocused",
        !probe.leftFocused && !probe.rightFocused,
        "left=${probe.leftFocused} right=${probe.rightFocused}",
    )
    click(app, FIELD_LEFT_X_DP + FIELD_W_DP / 2, contentTop + FIELD_TOP_DP + FIELD_H_DP / 2)
    // 边框颜色有 ~150ms 过渡动画：多跑几帧等它走完，像素断言才是确定的
    val afterFocus = driver.render(800, 600, density = d, frames = 24)
    report.check(
        "focus/click-focuses-left-field",
        probe.leftFocused,
        "left=${probe.leftFocused} right=${probe.rightFocused}",
    )
    report.check("focus/click-leaves-right-field", !probe.rightFocused)
    val leftX0 = FIELD_LEFT_X_DP
    val leftX1 = FIELD_LEFT_X_DP + FIELD_W_DP
    val rightX0 = FIELD_RIGHT_X_DP
    val rightX1 = FIELD_RIGHT_X_DP + FIELD_W_DP
    val fieldY0 = contentTop + FIELD_TOP_DP
    val fieldY1 = contentTop + FIELD_TOP_DP + FIELD_H_DP
    val leftChanged = afterFocus.regionDiff(beforeFocus, leftX0, fieldY0, leftX1, fieldY1)
    val rightChanged = afterFocus.regionDiff(beforeFocus, rightX0, fieldY0, rightX1, fieldY1)
    report.check("focus/focused-field-visible-change", leftChanged > 0, "左框变化像素=$leftChanged")
    report.check("focus/unfocused-field-unchanged", rightChanged == 0, "右框变化像素=$rightChanged")

    // 光标定位：主输入框里已经打过 "CK"（见 2.5）。点最右端 -> 光标到末尾；
    // 点文字左侧 -> 光标回到开头。这条断言走的是
    // 「文本排版 -> 命中测试 -> 选区」整条链路。
    click(app, 700, contentTop + 44)
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("caret/click-right-end", 2, probe.value.selection.start)
    click(app, 20, contentTop + 44)
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("caret/click-left-start", 0, probe.value.selection.start)
    report.check("caret/selection-collapsed", probe.value.selection.collapsed)

    // 选区 + 编辑命令（不需要窗口、也就能在离屏阶段测的部分）
    click(app, 700, contentTop + 44)
    ctrlKey(app, 0x41) // Ctrl+A 全选
    driver.render(800, 600, density = d, frames = 3)
    report.checkEquals("selection/ctrl-a-selects-all", TextRange(0, 2), probe.value.selection)
    typeChar(app, 'Z') // 输入应当**替换**选区
    driver.render(800, 600, density = d, frames = 3)
    report.checkEquals("selection/typing-replaces-selection", "Z", probe.text)
    ctrlKey(app, 0x41)
    keyPress(app, 0x08) // VK_BACK：删除选区
    driver.render(800, 600, density = d, frames = 3)
    report.checkEquals("selection/backspace-deletes-selection", "", probe.text)

    // 2.8 弹层（Popup）：独立图层必须画在同一张 surface 上，位置和尺寸都要对得上。
    // 弹层内容是唯一的亮紫色，於是「包围盒」就等于「弹层的位置 + 尺寸」。
    val noPopup = driver.render(800, 600, density = d, frames = 4)
    report.checkEquals(
        "popup/closed-no-pixels",
        0,
        noPopup.countColor(TEST_POPUP_ARGB, tolerance = 2),
    )
    probe.popupOpen = true
    val popupFrame = driver.render(800, 600, density = d, frames = 8)
    val popupPixels = popupFrame.countColor(TEST_POPUP_ARGB, tolerance = 2)
    report.checkNear("popup/open-pixel-count", POPUP_W_DP * POPUP_H_DP, popupPixels, 80)
    val popupBounds = popupFrame.boundsOf(TEST_POPUP_ARGB, tolerance = 2)
    report.check(
        "popup/open-bounds",
        popupBounds != null &&
            popupBounds.width in (POPUP_W_DP - 2)..(POPUP_W_DP + 2) &&
            popupBounds.height in (POPUP_H_DP - 2)..(POPUP_H_DP + 2),
        "bounds=$popupBounds",
    )
    report.check(
        "popup/anchored-at-offset",
        popupBounds != null &&
            abs(popupBounds.minX - POPUP_ANCHOR_X_DP) <= 4 &&
            abs(popupBounds.minY - (POPUP_ANCHOR_Y_DP + contentTop)) <= 4,
        "expected=($POPUP_ANCHOR_X_DP,${POPUP_ANCHOR_Y_DP + contentTop}) actual=$popupBounds",
    )
    probe.popupOpen = false

    // 2.9 下拉菜单（Material 的真实弹层：带自己的背景/阴影/间距）
    val menuX0 = MENU_ANCHOR_X_DP
    val menuY0 = MENU_ANCHOR_Y_DP + contentTop
    val menuX1 = MENU_ANCHOR_X_DP + 260
    val menuY1 = MENU_ANCHOR_Y_DP + contentTop + 130
    val menuClosed = driver.render(800, 600, density = d, frames = 4)
    report.checkEquals(
        "menu/closed-region-is-background",
        0,
        menuClosed.nonBackgroundCountInRegion(TEST_BG_ARGB, menuX0, menuY0, menuX1, menuY1),
    )
    probe.menuOpen = true
    val menuFrame = driver.render(800, 600, density = d, frames = 8)
    val menuPixels = menuFrame.nonBackgroundCountInRegion(TEST_BG_ARGB, menuX0, menuY0, menuX1, menuY1)
    report.check("menu/open-draws-content", menuPixels > 1000, "区域非背景像素=$menuPixels")
    probe.menuOpen = false
    driver.render(800, 600, density = d, frames = 4)

    // 2.10 画廊本身必须能渲染（组件覆盖面最大的那条路径）
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

    // 2.11 回归：首帧渲染之前到达的指针事件不得崩。
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

    // 2.12 对话框：遮罩 + 独立内容层（比 Popup 更重的一层）
    //
    // 放在最后：对话框一旦打开会吃掉后续指针事件（点遮罩 = 关闭），
    // 前面的交互断言必须在它之前全部跑完。
    val beforeDialog = driver.render(800, 600, density = d, frames = 4)
    // 角落是 CSD 标题栏（chrome），不是内容背景 —— 断言时别搞混
    report.checkEquals("dialog/closed-corner-is-chrome", CHROME_ARGB, beforeDialog.colorAt(2, 2))
    report.checkEquals("dialog/closed-content-is-bg", TEST_BG_ARGB, beforeDialog.colorAt(700, 300))
    val closedCenterRow = avgLuminanceInRow(beforeDialog, 300, 200, 600)
    probe.dialogOpen = true
    val dialogFrame = driver.render(800, 600, density = d, frames = 12)
    report.check(
        "dialog/scrim-dims-content",
        luminance(dialogFrame.colorAt(700, 300)) < luminance(beforeDialog.colorAt(700, 300)),
        "before=${luminance(beforeDialog.colorAt(700, 300))} after=${luminance(dialogFrame.colorAt(700, 300))}",
    )
    val centerRowPixels = dialogFrame.nonBackgroundCountInRow(300, TEST_BG_ARGB)
    report.check("dialog/visible-content-row", centerRowPixels > 400, "row300 非背景像素=$centerRowPixels")
    // 对话框必须**居中**：容器尺寸为 0 时它会塌到左上角（真实 bug），
    // 此时中间这一行仍然是「被遮罩压暗的背景」，亮度不会有明显提升。
    val openCenterRow = avgLuminanceInRow(dialogFrame, 300, 200, 600)
    report.check(
        "dialog/centered-bright-surface",
        openCenterRow > closedCenterRow + 40,
        "中间行平均亮度 关闭=$closedCenterRow 打开=$openCenterRow",
    )
    probe.dialogOpen = false

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

    // 交互阶段由 frameHook **显式**请求下一帧。
    //
    // 窗口循环现在是「按需渲染 + 帧节流」：没有渲染请求时它真的睡着（CPU ≈ 0），
    // 不会再像以前那样无条件每轮重绘。所以测试想按帧号推进就得自己 requestFrame
    // —— 这也正是真实内容（动画/状态变更）会做的事。性能测量阶段把它关掉，
    // 才能真正静置下来。
    var driveFrames = true
    val perf = WindowPerfResult()
    val guard = WindowPhaseGuard()

    // 兜底：交互阶段万一因为「没有任何渲染请求」而卡住，25 秒后强制收敛，
    // 并给出明确诊断（而不是耗到 CI job 超时）。
    // 注意性能阶段是靠后台协程 requestClose() 收尾的，它不需要这个兜底。
    CoroutineScope(Dispatchers.Default).launch {
        delay(WINDOW_PHASE_GUARD_MS)
        if (!guard.finished) {
            guard.timedOut = true
            app.window.requestClose()
        }
    }

    app.window.frameHook = { frame ->
        // 进度心跳：窗口阶段以前在 CI 上挂死过，日志里必须能看出「帧有没有在走」
        if (frame % 20 == 0) report.section("window 进度 frame=$frame")
        if (frame == 10) {
            val w = app.window
            report.check(
                "window/created",
                w.logicalWidth > 0 && w.logicalHeight > 0,
                "size=${w.logicalWidth}x${w.logicalHeight}",
            )
            report.check("window/dpi-scale", w.dpiScale >= 1.0f, "dpi=${w.dpiScale}")
            report.check(
                "window/container-size-nonzero",
                probe.windowContainerSize.width > 0 && probe.windowContainerSize.height > 0,
                "container=${probe.windowContainerSize} logical=${w.logicalWidth}x${w.logicalHeight}",
            )
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
        if (frame == 18) {
            // Compose 层剪贴板（LocalClipboardManager）-> skiko 的 Windows 桥接
            // -> Win32 剪贴板：三条路径必须看到同一份内容。
            val cm = probe.clipboardManager
            report.check("window/compose-clipboard-manager", cm != null)
            if (cm != null) {
                cm.setText(AnnotatedString(SELFTEST_CLIPBOARD))
                report.checkEquals("window/compose-clipboard-readback", SELFTEST_CLIPBOARD, cm.getText()?.text)
                report.checkEquals(
                    "window/compose-clipboard-reaches-win32",
                    SELFTEST_CLIPBOARD,
                    app.window.nativeWindow?.clipboard,
                )
            }
        }
        if (frame == 24) {
            // 把焦点给主输入框（真实点击，坐标含 CSD 标题栏高度）
            val scale = app.window.dpiScale
            click(app, (400 * scale).toInt(), ((44 + CHROME_DP) * scale).toInt())
        }
        if (frame == 28) {
            typeChar(app, 'C')
            typeChar(app, 'K')
        }
        if (frame == 36) {
            // 全选。注意：这一步只是「把 legacy TextFieldValue 的选区改成 (0,2)」，
            // 新老文本状态之间的同步是按帧走的 —— 同一个事件突发里紧接着发 Ctrl+C，
            // 复制会看到还没同步过去的（折叠的）选区而被丢弃。真实用户按键之间隔着
            // 几帧，所以下面的复制/剪切都和选择操作**分帧**发（见 42/58 帧）。
            ctrlKey(app, 0x41)
            report.checkEquals("window/ctrl-a-selects-all", TextRange(0, 2), probe.value.selection)
        }
        if (frame == 42) {
            ctrlKey(app, 0x43) // Ctrl+C
        }
        if (frame == 52) {
            report.checkEquals("window/ctrl-c-keeps-text", "CK", probe.text)
            report.checkEquals("window/ctrl-c-copies-to-win32", "CK", app.window.nativeWindow?.clipboard)
            ctrlKey(app, 0x41)
        }
        if (frame == 58) {
            ctrlKey(app, 0x58) // Ctrl+X（剪切）
        }
        if (frame == 68) {
            report.checkEquals("window/ctrl-x-clears-field", "", probe.text)
            report.checkEquals("window/ctrl-x-copies", "CK", app.window.nativeWindow?.clipboard)
            ctrlKey(app, 0x56) // Ctrl+V（粘贴）
        }
        if (frame == 78) {
            report.checkEquals("window/ctrl-v-pastes", "CK", probe.text)
            report.checkEquals("window/click-reaches-compose", 1, probe.clickCount)
            report.check(
                "window/wheel-scroll",
                scrollState.value > 0,
                "value=${scrollState.value} maxValue=${scrollState.maxValue}",
            )
            report.check("window/frame-count", app.window.frameCount >= 78, "frames=${app.window.frameCount}")
            // 交互检查做完 -> 交棒给性能测量（后台协程当节拍器），
            // 并且**停止**自己请求帧：这样界面真正静止下来。
            driveFrames = false
            startWindowPerfPhase(app, probe, perf)
        }
        // 只在自己驱动帧的阶段检查帧号上限：性能阶段故意让循环「睡着 + 定时醒来」，
        // 帧号会停住，那不是挂死。
        if (driveFrames && frame > 200) {
            report.check("window/close-timeout", false, "frameHook 已超过 200 帧仍未退出")
            app.window.requestClose()
        }
        if (driveFrames) app.window.layer.needRender()
    }

    app.run { DeterministicTestScreen(probe, scrollState) }
    guard.finished = true
    report.check("window/loop-exited", true)
    assertWindowPerfReport(report, perf, guard)
}

// ---------------------------------------------------------------------
// 性能自检（按需渲染 + 帧节流）
//
// 背景：窗口循环以前是无条件「每轮都 renderImmediately」的忙等循环 —— 一个完全
// 静止的窗口也会把一颗核心跑到 100%（~100+ fps 全是白工），动画更是无节制重绘。
// 现在改成「有渲染请求才画 + 按显示器刷新率节流」，这一节就是它的回归测试：
//
//   * 静止 1.2s    -> 渲染帧数应当 ≈ 0（老代码会是 ~150 帧）
//   * 跨线程刷新   -> 后台写状态必须把睡着的消息泵唤醒，并且只画 1 帧
//   * 动画 1.5s    -> 帧率应当落在刷新率附近（老代码是无节制重绘）
// ---------------------------------------------------------------------

/** 交互阶段兜底：超过这个时间还没跑完就强制收敛（避免 CI 上耗到 job 超时）。 */
private const val WINDOW_PHASE_GUARD_MS = 25_000L

private const val PERF_SETTLE_MS = 300L
private const val PERF_IDLE_MS = 1_200L
private const val PERF_WAKE_MS = 400L
private const val PERF_ANIM_MS = 1_500L

/**
 * 性能测量结果：后台协程写、主线程读完再断言。
 *
 * 字段标 `@Volatile`：后台协程写完后主线程要能看见（跨线程可见性）。
 */
private class WindowPerfResult {
    @Volatile var idleNanos = 0L
    @Volatile var idleFrames = -1
    @Volatile var wakeFrames = -1
    @Volatile var animNanos = 0L
    @Volatile var animFrames = -1

    fun idleFps(): Double = fpsOf(idleFrames, idleNanos)
    fun animFps(): Double = fpsOf(animFrames, animNanos)

    private fun fpsOf(frames: Int, nanos: Long): Double =
        if (nanos > 0L && frames >= 0) frames * 1_000_000_000.0 / nanos else -1.0
}

/** 窗口阶段兜底状态（后台协程读 finished、写 timedOut）。 */
private class WindowPhaseGuard {
    @Volatile var finished = false
    @Volatile var timedOut = false
}

/** 一位小数（`String.format` 在 Kotlin/Native 上不一定可用，自己拼）。 */
private fun fmt1(value: Double): String {
    if (value.isNaN()) return "n/a"
    val negative = value < 0
    val scaled = (if (negative) -value else value) * 10.0 + 0.5
    val ticks = scaled.toLong()
    return "${if (negative) "-" else ""}${ticks / 10}.${ticks % 10}"
}

/**
 * 性能节拍器：全部在后台线程上按时序推进，主线程只负责渲染。
 *
 * 之所以要「时间」这个外部维度：按需渲染之后，帧数与墙钟时间的关系才是我们要断言的
 * 契约（静止 → 0 帧/秒；动画 → 刷新率附近）。
 */
private fun startWindowPerfPhase(
    app: WindowsComposeApplication,
    probe: InteractionProbe,
    perf: WindowPerfResult,
) {
    CoroutineScope(Dispatchers.Default).launch {
        // 1) 静置：消化掉交互阶段残留的失效，然后测量「什么都不发生时」渲染了几帧。
        delay(PERF_SETTLE_MS)
        val idleFrames0 = app.window.frameCount
        val idleMark = TimeSource.Monotonic.markNow()
        delay(PERF_IDLE_MS)
        perf.idleFrames = app.window.frameCount - idleFrames0
        perf.idleNanos = idleMark.elapsedNow().inWholeNanoseconds

        // 2) 跨线程刷新：后台线程写 snapshot 状态 -> 必须唤醒睡着的消息泵 -> 只画一帧。
        //    （真实场景：后台加载完成、下载进度、定时器刷新……）
        val wakeFrames0 = app.window.frameCount
        probe.bgTick++
        delay(PERF_WAKE_MS)
        perf.wakeFrames = app.window.frameCount - wakeFrames0

        // 3) 动画：withFrameNanos 持续请求帧，宿主按刷新率节流。
        val animFrames0 = app.window.frameCount
        val animMark = TimeSource.Monotonic.markNow()
        probe.animate = true
        delay(PERF_ANIM_MS)
        perf.animFrames = app.window.frameCount - animFrames0
        perf.animNanos = animMark.elapsedNow().inWholeNanoseconds
        probe.animate = false

        // 收尾：requestClose 是 PostMessage(WM_CLOSE)，本身也会把睡着的循环唤醒。
        app.window.requestClose()
    }
}

private fun assertWindowPerfReport(
    report: SelfTestReport,
    perf: WindowPerfResult,
    guard: WindowPhaseGuard,
) {
    // 无论通过与否都把实测数字打出来：CI 日志里就能看到「静止渲染了几帧 /
    // 动画跑出多少 fps」，而不是只有一个 ok。
    report.section(
        "window 性能: 静止 ${perf.idleNanos / 1_000_000}ms -> ${perf.idleFrames} 帧" +
            " | 跨线程刷新 -> ${perf.wakeFrames} 帧" +
            " | 动画 ${perf.animNanos / 1_000_000}ms -> ${perf.animFrames} 帧 = ${fmt1(perf.animFps())} fps"
    )
    report.check(
        "window/perf-phase-completed",
        !guard.timedOut,
        if (guard.timedOut) "窗口阶段超时（兜底强制退出）" else "正常完成",
    )
    // 静止窗口不应该有任何重绘（留一点余量给「静置瞬间还在路上的那一帧」）。
    report.check(
        "window/perf-idle-no-busy-render",
        perf.idleFrames in 0..5,
        "静止 ${perf.idleNanos / 1_000_000}ms 渲染了 ${perf.idleFrames} 帧" +
            "（${fmt1(perf.idleFps())} fps，期望 ≈ 0）",
    )
    // 后台线程的一次状态写入必须被画出来（消息泵被唤醒），且只画这一帧。
    report.check(
        "window/perf-cross-thread-wake",
        perf.wakeFrames in 1..5,
        "跨线程刷新渲染了 ${perf.wakeFrames} 帧（期望 1..5；0 = 没唤醒，过多 = 忙等）",
    )
    // 动画必须持续跑（没被节流卡死），又必须被节流（不是无节制重绘）。
    val animFps = perf.animFps()
    report.check(
        "window/perf-animation-fps",
        animFps >= 20.0 && animFps <= 120.0,
        "动画 ${perf.animNanos / 1_000_000}ms 渲染 ${perf.animFrames} 帧" +
            " = ${fmt1(animFps)} fps（期望接近刷新率；老代码是无节制重绘）",
    )
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

/**
 * 合成一次「按住 Ctrl 再按某个键」（真实的四条消息：Ctrl↓ 键↓ 键↑ Ctrl↑）。
 *
 * 键位信息走 [WindowsEvent.KeyEvent.isCtrlPressed]，与消息泵里的
 * `GetKeyState(VK_CONTROL)` 结果一致。
 */
private fun ctrlKey(app: WindowsComposeApplication, vk: Int) {
    val ctrlVk = 0x11
    app.dispatchEvent(
        WindowsEvent.KeyEvent(virtualKeyCode = ctrlVk, scanCode = 0, isKeyDown = true, isCtrlPressed = true),
    )
    app.dispatchEvent(
        WindowsEvent.KeyEvent(virtualKeyCode = vk, scanCode = 0, isKeyDown = true, isCtrlPressed = true),
    )
    app.dispatchEvent(
        WindowsEvent.KeyEvent(virtualKeyCode = vk, scanCode = 0, isKeyDown = false, isCtrlPressed = true),
    )
    app.dispatchEvent(
        WindowsEvent.KeyEvent(virtualKeyCode = ctrlVk, scanCode = 0, isKeyDown = false),
    )
    app.pumpDispatchers()
}

/** 合成一次普通按键（按下 + 抬起），不带修饰键。 */
private fun keyPress(app: WindowsComposeApplication, vk: Int) {
    app.dispatchEvent(WindowsEvent.KeyEvent(virtualKeyCode = vk, scanCode = 0, isKeyDown = true))
    app.dispatchEvent(WindowsEvent.KeyEvent(virtualKeyCode = vk, scanCode = 0, isKeyDown = false))
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

/** 感知亮度（0..255），用于「遮罩让画面变暗了」这类断言。 */
private fun luminance(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 299 + g * 587 + b * 114) / 1000
}

/** 某一行的平均亮度（用于「这一行整体变亮了 = 中间画了浅色东西」）。 */
private fun avgLuminanceInRow(a: FrameSnapshot, y: Int, x0: Int, x1: Int): Int {
    var sum = 0L
    var n = 0
    for (x in x0..x1) {
        sum += luminance(a.colorAt(x, y))
        n++
    }
    return if (n == 0) 0 else (sum / n).toInt()
}

private fun wheel(app: WindowsComposeApplication, x: Int, y: Int, deltaY: Int) {
    app.dispatchEvent(
        WindowsEvent.MouseWheelEvent(x = x, y = y, deltaX = 0, deltaY = deltaY),
    )
    app.pumpDispatchers()
}

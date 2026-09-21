@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
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
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.ClipEntry
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
import com.composekn.windows.TouchPhase
import com.composekn.windows.WindowsComposeApplication
import com.composekn.windows.WindowsComposeWindow
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
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skiko.ClipboardImage
import org.jetbrains.skiko.Win32Message

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

/**
 * 多点触摸（捏合缩放）探针方块的位置/尺寸。
 *
 * 刻意挑在别的断言都不覆盖的空档里：既有的像素断言与手势测试覆盖顶部输入框
 * （y 16~226）、按钮（0~210 × 96~144）、弹层/菜单锚点（400,200 / 520,300）、
 * 性能文字（16,240）、底部滚动区（y 480~600）。x 560~760 / y 320~460 是空的。
 */
private const val PINCH_X_DP = 560
private const val PINCH_Y_DP = 320
private const val PINCH_W_DP = 200
private const val PINCH_H_DP = 140

/**
 * 「事件时间戳」探针的位置：左边缘、性能文字（16,240）下方、底部滚动区（y 480）上方的
 * 空档。**同样不画任何像素**（只做命中测试），免得干扰别处的像素断言。
 */
private const val TIME_PROBE_X_DP = 16
private const val TIME_PROBE_Y_DP = 300
private const val TIME_PROBE_W_DP = 260
private const val TIME_PROBE_H_DP = 120

/**
 * 横向滚动探针（`horizontalScroll`）的位置/尺寸。
 *
 * (300, 256)dp + 120x32dp：刻意避开所有像素断言的矩形（见 [DeterministicTestScreen] 里的注释）。
 */
private const val HSCROLL_X_DP = 300
private const val HSCROLL_Y_DP = 256
private const val HSCROLL_W_DP = 120
private const val HSCROLL_H_DP = 32

/**
 * 「真实 Win32 消息」子阶段（§17.31）用的命中点，单位是**窗口**逻辑坐标（dp），
 * 已经含 CSD 标题栏偏移 —— 和窗口阶段那些合成事件的坐标同一套约定。
 *
 *   * 按钮：内容 (40..200, 96..144) + 32dp 标题栏 -> 中心 (120, 152)
 *   * 输入框：内容 (16, 16) 起、占满宽度 -> 用 (400, 76)
 */
private const val BTN_X_DP = 120f
private const val BTN_Y_DP = 152f
private const val TEXT_X_DP = 400f
private const val TEXT_Y_DP = 76f

/** VK_Z（真实 WM_KEYDOWN/WM_KEYUP 用；字符本身走 WM_CHAR）。 */
private const val VK_Z = 0x5A

/**
 * 拖放探针（两个不画像素的命中框）的位置/尺寸，单位是**内容**坐标（dp）。
 *
 * 一个只收文件、一个只收文本 —— 除了验证负载，也顺手验证
 * `shouldStartDragAndDrop` 的筛选真的生效（不是「有拖放就发给所有 target」）。
 * 位置挑在别的指针探针不覆盖的空档（菜单 x520 起、PINCH x560 起、TIME/HSCROLL
 * 都在左边），而且**不画任何像素**，不会干扰像素断言。
 */
private const val DRAG_FILE_X_DP = 430
private const val DRAG_FILE_Y_DP = 330
private const val DRAG_TEXT_X_DP = 430
private const val DRAG_TEXT_Y_DP = 400
private const val DRAG_W_DP = 90
private const val DRAG_H_DP = 60

/**
 * C 侧自检 IDataObject（`ComposeKNTestDataObject`）里固定的两条路径与文本。
 *
 * 两边必须一致：C 侧构造的是真的 CF_HDROP / CF_UNICODETEXT，这里断言解出来的就是这份；
 * 对不上说明 FORMATETC/DROPFILES 解码错了（而不是"断言写错了"）。
 */
private val DROP_TEST_FILES = listOf(
    "C:\\composekn\\drop-test-1.txt",
    "C:\\composekn\\drop-test-2.txt",
)
private const val DROP_TEST_TEXT = "ComposeKN 拖放测试文本"

/** 富文本剪贴板测试用的 HTML 片段（故意含非 ASCII，验证 UTF-8 字节偏移没算错）。 */
private const val CLIP_HTML_FRAGMENT =
    "<b>ComposeKN</b> 富文本 <i>clipboard</i> ✔"

/** RTF 片段（RTF 是 ASCII，非 ASCII 走 \uN 转义 —— 这里只测"原样往返"）。 */
private const val CLIP_RTF = "{\\rtf1\\ansi\\b ComposeKN}\\par "

/** 富文本条目里的纯文本回退（不认 HTML 的程序该拿到它）。 */
private const val CLIP_PLAIN = "ComposeKN 富文本回退文本"

/**
 * 2×2 测试位图：左上红 / 右上绿 / 左下蓝 / 右下白。
 *
 * 为什么用「四个角四种色」而不是纯色块：位图剪贴板最经典的 bug 就是**行序搞反**
 * （CF_DIB 默认自下而上），以及 stride/通道顺序错位 —— 纯色块全都看不出来，
 * 四色一眼就能看出是翻转了还是串通道了。
 */
private val CLIP_IMAGE_BGRA = byteArrayOf(
    // 第 0 行：红(0,0) 绿(1,0)
    0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
    0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
    // 第 1 行：蓝(0,1) 白(1,1)
    0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
    0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
)

private fun clipboardTestImage(): ImageBitmap {
    // BGRA、自上而下、stride = 2*4
    val info = ImageInfo(2, 2, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return Image.makeRaster(info, CLIP_IMAGE_BGRA.copyOf(), 8).toComposeImageBitmap()
}

/** 同一份 2×2 四色数据，但用剪贴板层的中间表示（BGRA，自上而下）。 */
private fun clipboardTestClipboardImage(): ClipboardImage = ClipboardImage(2, 2, CLIP_IMAGE_BGRA.copyOf())

/**
 * 手工拼一张 **8bpp 调色板 DIB**（BITMAPINFOHEADER + 4 色调色板 + 自下而上的索引行）。
 *
 * `clrUsed = 0` 是个真实的坑：规范上 0 表示"全 256 项"，但缓冲区里可能只有几项
 * （Wine 转换格式后就是这样；某些老程序也会）—— 解码器要按"实际装得下的项数"裁剪，
 * 所以这里专门造一份这种数据来测。
 */
private fun dib8WithPalette(width: Int, height: Int, indices: ByteArray, clrUsed: Int): ByteArray {
    val header = 40
    val paletteBytes = 16
    val stride = ((width + 3) / 4) * 4
    val out = ByteArray(header + paletteBytes + stride * height)
    fun putInt(off: Int, value: Int) {
        out[off] = value.toByte()
        out[off + 1] = (value ushr 8).toByte()
        out[off + 2] = (value ushr 16).toByte()
        out[off + 3] = (value ushr 24).toByte()
    }
    fun putShort(off: Int, value: Int) {
        out[off] = value.toByte()
        out[off + 1] = (value ushr 8).toByte()
    }
    putInt(0, header)
    putInt(4, width)
    putInt(8, height)                 // 正数 = 自下而上
    putShort(12, 1)
    putShort(14, 8)
    putInt(16, 0)                     // BI_RGB
    putInt(20, stride * height)
    putInt(32, clrUsed)
    // 调色板条目是 RGBQUAD（内存顺序 B, G, R, 保留）。colors 是 0xRRGGBB：
    //   红 0xFF0000 -> B=0x00 G=0x00 R=0xFF
    //   绿 0x00FF00 -> B=0x00 G=0xFF R=0x00
    //   蓝 0x0000FF -> B=0xFF G=0x00 R=0x00
    //   白 0xFFFFFF -> B=0xFF G=0xFF R=0xFF
    val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF)
    for (i in 0 until 4) {
        val color = colors[i]
        out[header + i * 4 + 0] = (color and 0xFF).toByte()            // B
        out[header + i * 4 + 1] = ((color shr 8) and 0xFF).toByte()    // G
        out[header + i * 4 + 2] = ((color shr 16) and 0xFF).toByte()   // R
    }
    for (y in 0 until height) {
        for (x in 0 until width) {
            out[header + paletteBytes + stride * (height - 1 - y) + x] = indices[y * width + x]
        }
    }
    return out
}

/** 期望的 2×2 ARGB 像素（自上而下）。 */
private val CLIP_IMAGE_EXPECTED_ARGB = intArrayOf(
    0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
    0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
)

class InteractionProbe {
    var clicked by mutableStateOf(false)
    var clickCount by mutableStateOf(0)

    /**
     * hover / 按压反馈探针（测试屏里那个橙色可点击方块）。
     *
     * 这一组回答的是真机反馈「触摸点了没反应」到底算谁的：
     *  · hover（`Enter/Exit`）在 Compose 里**只对鼠标**产生 —— skiko 的实现
     *    `InternalPointerEvent.skiko.kt: activeHoverEvent = id 的类型 == PointerType.Mouse`，
     *    触摸永远拿不到；Android 上触摸 `hoverable` 也一样。所以「只有 hoverable
     *    才有可见变化」是**应用侧**的设计问题，不是宿主漏发事件。
     *  · 但触摸**必须**有 `PressInteraction`（涟漪/自定义按压反馈的来源）并能触发
     *    click —— 这一半要是断了才是宿主/输入通道的真 bug。
     */
    var probeButtonHovered by mutableStateOf(false)
    var probeButtonPressed by mutableStateOf(false)

    /** `HoverInteraction.Enter` / `Exit` 的**边沿**次数（只有鼠标能加）。 */
    var probeButtonHoverEnters by mutableStateOf(0)
    var probeButtonHoverExits by mutableStateOf(0)

    /** `PressInteraction.Press` / `Release` / `Cancel` 的次数（触摸、鼠标都算）。 */
    var probeButtonPresses by mutableStateOf(0)
    var probeButtonReleases by mutableStateOf(0)
    var probeButtonCancels by mutableStateOf(0)

    /** 主输入框的内容 + 选区（选区用来断言「点击定位光标」「Ctrl+A 全选」）。 */
    var value by mutableStateOf(TextFieldValue(""))

    /**
     * 主输入框的聚焦状态（`onFocusChanged` 回填）。
     *
     * 为什么要单独探它：文本输入（`WM_CHAR`）、IME 组字、`imeCaretRectForChar`
     * 全都要求「有焦点 + 文本会话活跃」。真实鼠标消息子阶段会点按钮（那一步会把
     * 焦点从输入框抢走，这是 Compose 的正常语义），所以后面必须能断言
     * 「用真实点击把焦点还回来了」—— 否则后面一串 IME 断言失败时，根本分不清是
     * 宿主丢了事件还是输入框本来就没焦点。
     */
    var mainFocused by mutableStateOf(false)

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

    /**
     * 多点触摸（捏合）探针：Compose 的 `transformable` 把每次 zoom 累乘进来。
     * 两指张开应当 > 1、捏合应当 < 1 —— 这是「宿主把多指针事件正确送进 Compose」
     * 的端到端证据（§8.4 一直缺这条）。
     */
    var pinchScale by mutableStateOf(1f)

    /** 收到多少次 transformable 手势回调（0 说明事件根本没到控件）。 */
    var pinchEvents by mutableStateOf(0)

    /**
     * Compose 指针输入层看到的最后一个事件的 `uptimeMillis`。
     *
     * 这是「触摸事件带没带真实事件时间」的端到端证据（§17.24）：宿主必须把
     * `WindowsEvent.TouchEvent.timeMillis` 一路喂进 `sendPointerEvent(timeMillis=…)`；
     * 否则 Compose 拿到的是「派发时刻」，而同一帧里到达的多条 WM_POINTERUPDATE
     * 会共用同一个毫秒 —— 速度估计器（Lsq2，按时间轴二次拟合）时间轴被压扁，
     * 就会算出凭空的甩动速度（真机表现：松手后内容自己跳一段）。
     */
    var lastPointerUptime by mutableStateOf(-1L)
    var lastPointerPosition by mutableStateOf(Offset.Zero)

    /**
     * 探针收到的指针事件次数 + 最后一次的 `pressed`。
     *
     * 「悬停不能变成一根凭空按下的手指」这条断言靠它（§17.29）：悬停事件应当在
     * 宿主侧就被丢掉 —— 探针既不该收到事件，更不该看到 `pressed=true`。
     */
    var pointerEventCount by mutableStateOf(0)
    var lastPointerPressed by mutableStateOf(false)

    /**
     * 探针看到的最后一个滚轮 `scrollDelta` + 滚轮事件数。
     *
     * 用来钉住「宿主把滚轮翻成什么 delta」这条约定（HANDOVER §17.30）：
     * 竖直滚轮必须给 `(0, y)`、Shift+竖直滚轮必须给 `(y, 0)`（对齐上游
     * `ComposeSceneMediator.desktop.kt: onMouseWheelEvent`）、横向滚轮给 `(x, 0)`。
     */
    var lastScrollDelta by mutableStateOf(Offset.Zero)
    var scrollEventCount by mutableStateOf(0)

    /** 测试屏里那条横向滚动列表的当前位置（`horizontalScroll` 的 ScrollState.value）。 */
    var horizontalScrollValue by mutableStateOf(0)

    /** 拖放探针：只收文件的框。 */
    val dragFileProbe = DragProbe()

    /** 拖放探针：只收文本的框。 */
    val dragTextProbe = DragProbe()

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

/**
 * 拖放探针：记录一个 `Modifier.dragAndDropTarget` 收到的所有阶段与负载。
 *
 * 写的是 snapshot state（和别的探针一样）：回调发生在 UI 线程、组合之外，测试在下一帧
 * 读它。
 */
class DragProbe {
    var starts by mutableStateOf(0)
    var enters by mutableStateOf(0)
    var moves by mutableStateOf(0)
    var exits by mutableStateOf(0)
    var ends by mutableStateOf(0)
    var drops by mutableStateOf(0)

    /** `shouldStartDragAndDrop` 被问了几次（宿主 accept 判定会遍历所有 target）。 */
    var shouldStartCalls by mutableStateOf(0)

    var lastFiles by mutableStateOf(emptyList<String>())
    var lastText by mutableStateOf<String?>(null)
    var lastPosition by mutableStateOf(Offset.Zero)

    fun record(event: DragAndDropEvent) {
        lastFiles = event.files
        lastText = event.text
        lastPosition = event.positionInWindow
    }

    /** 事件总数（断言「这个框一个事件都没收到」时用）。 */
    fun total(): Int = starts + enters + moves + exits + ends + drops
}

/** 把拖放事件记进 [probe] 的 [DragAndDropTarget] 实现。 */
private fun dragTargetFor(probe: DragProbe) = object : DragAndDropTarget {
    override fun onStarted(event: DragAndDropEvent) {
        probe.starts++
        probe.record(event)
    }

    override fun onEntered(event: DragAndDropEvent) {
        probe.enters++
        probe.record(event)
    }

    override fun onMoved(event: DragAndDropEvent) {
        probe.moves++
        probe.record(event)
    }

    override fun onExited(event: DragAndDropEvent) {
        probe.exits++
        probe.record(event)
    }

    override fun onEnded(event: DragAndDropEvent) {
        probe.ends++
        probe.record(event)
    }

    override fun onDrop(event: DragAndDropEvent): Boolean {
        probe.drops++
        probe.record(event)
        return true
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
                    .fillMaxWidth()
                    .onFocusChanged { probe.mainFocused = it.isFocused },
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

            // 可点击按钮：点击后颜色变绿（同时驱动重组）。
            //
            // 它同时是 **hover / 按压反馈** 的探针（互动源只用来观测，不改像素）：
            //  · `hoverable` 收到的 `HoverInteraction.Enter/Exit` 只可能来自鼠标
            //    （Compose 的 hover 事件在 skiko 里只对 `PointerType.Mouse` 合成）；
            //  · `clickable` 的 `PressInteraction.Press/Release` 则触摸、鼠标都该有
            //    —— 真机「点了没反应」若出在这一层才是宿主/输入通道的 bug。
            // 两条断言分别锁死：触摸不能"顺便"产生 hover、鼠标必须能产生 hover。
            //
            // ⚠ 这里**直接把互动事件写进探针字段**，而不是用
            // `collectIsHoveredAsState()` + `SideEffect` 转一手：后者要求
            // 「协程派发 -> 重组 -> SideEffect」三跳都跑完，离屏驱动器一次
            // `render(frames=k)` 只给 k 轮，读到的状态会晚一两帧（首版就是这么假失败的）。
            // 计数只算 0->1 / 1->0 的**边沿**：`hoverable` 和 `clickable` 两个节点都会
            // 往同一个 source 发 Enter/Exit，不去重的话一次悬停会数出 2 个。
            val probeButton = remember { MutableInteractionSource() }
            LaunchedEffect(probeButton) {
                probeButton.interactions.collect { interaction ->
                    when (interaction) {
                        is HoverInteraction.Enter -> {
                            if (!probe.probeButtonHovered) probe.probeButtonHoverEnters++
                            probe.probeButtonHovered = true
                        }
                        is HoverInteraction.Exit -> {
                            if (probe.probeButtonHovered) probe.probeButtonHoverExits++
                            probe.probeButtonHovered = false
                        }
                        is PressInteraction.Press -> {
                            probe.probeButtonPresses++
                            probe.probeButtonPressed = true
                        }
                        is PressInteraction.Release -> {
                            probe.probeButtonReleases++
                            probe.probeButtonPressed = false
                        }
                        is PressInteraction.Cancel -> {
                            probe.probeButtonCancels++
                            probe.probeButtonPressed = false
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 40.dp, top = 96.dp)
                    .size(160.dp, 48.dp)
                    .background(if (probe.clicked) TEST_ACTIVE else TEST_IDLE)
                    .hoverable(probeButton)
                    // indication = null：去掉 Material 涟漪，让「点击后的颜色」是确定的纯色
                    // （涟漪是一层半透明叠加，会让像素断言变成模糊匹配）；互动源仍然会用，
                    // 所以 PressInteraction 照样发得出来。
                    .clickable(
                        interactionSource = probeButton,
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

            // 多点触摸探针：两根手指的捏合/张开 -> Compose 官方的 transformable。
            // 用官方手势而不是自己写 pointerInput，是为了测「宿主送进来的多指针事件」，
            // 而不是同时把识别算法也一起自研了。
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = PINCH_X_DP.dp, y = PINCH_Y_DP.dp)
                    .size(PINCH_W_DP.dp, PINCH_H_DP.dp)
                    // ⚠ 刻意**不画背景**：它是纯手势探针，画出来会和别处的像素断言打架
                    // （第一版就是这么被抓到的：menu/closed-region-is-background 报
                    //  22200 个非背景像素 = 这个 200x110 的重叠区）。Compose 的命中测试
                    // 按**布局边界**算、不看画出来的像素，所以照样能收到触摸。
                    .transformable(
                        state = rememberTransformableState { zoomChange, _, _ ->
                            probe.pinchScale *= zoomChange
                            probe.pinchEvents++
                        },
                    ),
            )

            // 事件时间戳探针：把 Compose 指针输入层看到的 `uptimeMillis` 记下来。
            // 不消费事件、不画像素 —— 只是「宿主到底喂了什么时间给我」的观测点（§17.24），
            // 顺带记录 pressed（§17.29：悬停事件根本不该到这里）。
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = TIME_PROBE_X_DP.dp, y = TIME_PROBE_Y_DP.dp)
                    .size(TIME_PROBE_W_DP.dp, TIME_PROBE_H_DP.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Scroll) {
                                    probe.lastScrollDelta =
                                        e.changes.firstOrNull()?.scrollDelta ?: Offset.Zero
                                    probe.scrollEventCount++
                                }
                                e.changes.forEach { change ->
                                    probe.lastPointerUptime = change.uptimeMillis
                                    probe.lastPointerPosition = change.position
                                    probe.lastPointerPressed = change.pressed
                                    probe.pointerEventCount++
                                }
                            }
                        }
                    },
            )

            // 横向滚动探针：120dp 视口 + 12 个 40dp 方块（内容 480dp）。
            // 位置 (300, 256)dp 是刻意挑的：像素断言覆盖的矩形（菜单 x520..780/y332..462、
            // 弹层 (400,200)、对话框中心行、底部滚动区 y480..600）都不含它。
            // 它存在的唯一理由：`Scrollable.toSingleAxisDeltaFromAngle()` 让**横向**滚动条
            // 忽略纯竖直的滚轮 delta，所以"横向列表能不能用滚轮滚"完全取决于宿主把滚轮翻成
            // 什么 scrollDelta（HANDOVER §17.30）。
            val horizontalState = remember { ScrollState(0) }
            // 在**组合中**读一次（这样值一变这个作用域就重组），再由 SideEffect 写进探针 ——
            // 直接在 SideEffect 里读 state 不会建立订阅，探针会一直是 0。
            val horizontalValue = horizontalState.value
            SideEffect { probe.horizontalScrollValue = horizontalValue }
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = HSCROLL_X_DP.dp, y = HSCROLL_Y_DP.dp)
                    .size(HSCROLL_W_DP.dp, HSCROLL_H_DP.dp)
                    .horizontalScroll(horizontalState),
            ) {
                repeat(12) { index ->
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(if (index % 2 == 0) Color(0xFF405060) else Color(0xFF607080)),
                    )
                }
            }

            // 拖放探针 A/B：只收文件 / 只收文本。**故意不画背景**（只做命中测试），
            // 位置挑在别的指针探针不覆盖的空档，免得干扰像素断言。
            val dragFileTarget = remember { dragTargetFor(probe.dragFileProbe) }
            val dragTextTarget = remember { dragTargetFor(probe.dragTextProbe) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = DRAG_FILE_X_DP.dp, y = DRAG_FILE_Y_DP.dp)
                    .size(DRAG_W_DP.dp, DRAG_H_DP.dp)
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            probe.dragFileProbe.shouldStartCalls++
                            event.files.isNotEmpty()
                        },
                        target = dragFileTarget,
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = DRAG_TEXT_X_DP.dp, y = DRAG_TEXT_Y_DP.dp)
                    .size(DRAG_W_DP.dp, DRAG_H_DP.dp)
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = { event ->
                            probe.dragTextProbe.shouldStartCalls++
                            event.text != null
                        },
                        target = dragTextTarget,
                    ),
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

    /** 只打印一行诊断，**不计入**断言（真机排查时用）。 */
    fun info(text: String) = line("SELFTEST info : $text")

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
            runWindowTests(report, perfContractChecks = mode != "all")
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

    // 触点状态机（HANDOVER §17.29）：**只有 DOWN 能新建触点**。
    // 笔悬停时 Windows 一直发 WM_POINTERUPDATE，它们到这里就是"没有 DOWN 的 Move"；
    // 老实现把任意新 id 都当成按下加进表里 → Compose 那边凭空多一根按住的手指。
    // 这条在纯逻辑层直接盯住状态机（离屏那条测的是端到端「探针根本收不到事件」）。
    val touchState = WindowsInputState()
    val hoverMove = touchState.updateTouch(
        WindowsEvent.TouchEvent(pointerId = 1L, x = 5, y = 6, phase = TouchPhase.Move),
    )
    val hoverUp = touchState.updateTouch(
        WindowsEvent.TouchEvent(pointerId = 1L, x = 5, y = 6, phase = TouchPhase.Up),
    )
    val realDown = touchState.updateTouch(
        WindowsEvent.TouchEvent(pointerId = 1L, x = 5, y = 6, phase = TouchPhase.Down),
    )
    val realMove = touchState.updateTouch(
        WindowsEvent.TouchEvent(pointerId = 1L, x = 7, y = 8, phase = TouchPhase.Move),
    )
    val realUp = touchState.updateTouch(
        WindowsEvent.TouchEvent(pointerId = 1L, x = 7, y = 8, phase = TouchPhase.Up),
    )
    report.check(
        "logic/inputstate-touch-needs-down",
        hoverMove.isEmpty() && hoverUp.isEmpty() &&
            touchState.droppedUntrackedTouchCount == 2 &&
            realDown.size == 1 && realDown[0].pressed &&
            realMove.size == 1 && realMove[0].pressed && realMove[0].position == Offset(7f, 8f) &&
            realUp.size == 1 && !realUp[0].pressed &&
            touchState.activeTouchCount == 0,
        "无主 MOVE/UP 应丢（实际 ${hoverMove.size}/${hoverUp.size} 条，期望 0/0；累计丢弃 " +
            "${touchState.droppedUntrackedTouchCount} 期望 2）；真实 DOWN→MOVE→UP 应照常" +
            "（${realDown.size}/${realMove.size}/${realUp.size} 条，抬起后触点 ${touchState.activeTouchCount} 期望 0）",
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
    // 注意：自检几乎全部断言都建立在「32dp 自绘 CSD 标题栏」的几何上
    // （render/chrome-*、contentTop 等），所以这里显式用 undecorated = true 跑 CSD 模式。
    // **默认形态是系统标题栏**（对齐 Compose JVM 的 Window()），由下面那条断言兜住。
    report.check(
        "window/default-decoration-is-system",
        WindowsComposeWindow(title = "probe", width = 100, height = 100).isDecorated,
        "默认应为系统标题栏（isDecorated=true）",
    )
    val app = WindowsComposeApplication(title = "ComposeKN Selftest", width = 800, height = 600, undecorated = true)
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

    // 2.6a 滚轮 -> scrollDelta 的翻写约定 + **横向列表能不能用滚轮滚**（HANDOVER §17.30）。
    //
    // 真机反馈：「横向列表没法用滚轮滚动，只能用触摸」。根因不在"没送滚轮"，而在**翻写约定**：
    // `MouseWheelScrollingLogic.canConsumeDelta()` 用
    // `Scrollable.toSingleAxisDeltaFromAngle()`（阈值 PI/4）把二维 scrollDelta 投到滚动轴上 ——
    // **横向**滚动条对纯竖直的 delta 直接返回 0（`if (angle >= PI/4) if (Vertical) y else 0f`），
    // 于是 `LazyRow`/`horizontalScroll` 对普通竖直滚轮完全无感。
    // 上游 Compose Desktop 的答案是 `ComposeSceneMediator.desktop.kt: onMouseWheelEvent()`：
    //   scrollDelta = if (event.isShiftDown) Offset(wheelRotation, 0f) else Offset(0f, wheelRotation)
    // 也就是 **Shift + 滚轮 = 横向滚动**（外加真的横向滚轮/触控板横滑）。宿主以前原样透传
    // `(0, deltaY)`，Shift 那条完全没实现 → 横向列表除了触摸没别的办法。
    //
    // 三层断言：
    //   (1) `scrollDelta` 本身：竖直=(0,y)、Shift+竖直=**原符号搬到 x 轴**、横向=(x,0)（钉住翻写约定）；
    //   (2) 端到端：Shift+滚轮 / 横向滚轮 能推动一条真的 `horizontalScroll`；
    //   (3) 反证：**普通竖直滚轮不动它**（上游语义如此 —— 它会落到外层竖直滚动条上）。
    val probeWheelX = ((TIME_PROBE_X_DP + 20) * d).toInt()
    val probeWheelY = contentTop + ((TIME_PROBE_Y_DP + 20) * d).toInt()
    wheelEvent(app, probeWheelX, probeWheelY, deltaY = 3f)
    driver.render(800, 600, density = d, frames = 2)
    val plainDelta = probe.lastScrollDelta
    wheelEvent(app, probeWheelX, probeWheelY, deltaY = -3f, shift = true)
    driver.render(800, 600, density = d, frames = 2)
    val shiftDelta = probe.lastScrollDelta
    wheelEvent(app, probeWheelX, probeWheelY, deltaX = -3f)
    driver.render(800, 600, density = d, frames = 2)
    val horizontalDelta = probe.lastScrollDelta
    report.check(
        "interaction/wheel-scroll-delta-mapping",
        plainDelta == Offset(0f, 3f) &&
            shiftDelta == Offset(-3f, 0f) &&
            horizontalDelta == Offset(-3f, 0f),
        "竖直=$plainDelta（期望 (0,3)）、Shift+竖直=$shiftDelta（期望 (-3,0) —— 把竖直 delta" +
            "原符号搬到横向轴上，对齐上游）、横向=$horizontalDelta（期望 (-3,0)）",
    )

    // 2.6a2 精确滚轮 / 触控板：**一格以内**的 delta 必须原样到达滚动逻辑（HANDOVER §17.32）。
    //
    // Win32 只保证「带刻度滚轮一格 = 120」，`WM_MOUSEWHEEL` 的 zDelta 本身可以是**任意
    // 整数**（触控板 / 自由滚轮会送 40、80、17…；微软文档明确要求应用不要假设它是 120 的
    // 倍数）。宿主以前用 `raw.a / 120` 的**整数除法**换算，40/120 直接变 0 ——
    // 表现就是触控板「慢速完全不动、快滑一顿一顿」。
    //
    // 上游的数据模型是浮点的「格」：AWT 的 `MouseWheelEvent.getPreciseWheelRotation()` 是
    // Double，`ComposeSceneMediator.desktop.kt: onMouseWheelEvent()` 直接把它塞进
    // `scrollDelta`，而 `MouseWheelScrollingLogic` / `DesktopScrollable.desktop.kt` 全程 Float。
    // 这里钉两件事：
    //   (1) 1/3 格必须**原样**到达 Compose 指针层（不被取整）；
    //   (2) 端到端：连发几发 1/3 格必须真的把竖向列表推动（旧行为是纹丝不动）。
    val thirdOfNotch = 1f / 3f   // = zDelta 40 / WHEEL_DELTA 120
    wheelEvent(app, probeWheelX, probeWheelY, deltaY = -thirdOfNotch)
    driver.render(800, 600, density = d, frames = 2)
    report.check(
        "interaction/precise-wheel-delta-not-truncated",
        probe.lastScrollDelta == Offset(0f, -thirdOfNotch),
        "1/3 格 -> Compose 指针层看到 ${probe.lastScrollDelta}（期望 (0, -0.3333…)）——" +
            "取整成 0 就是触控板「滚不动」的根因",
    )
    val beforePrecise = scrollState.value
    repeat(6) { wheelEvent(app, 400, 540, deltaY = -thirdOfNotch) }
    driver.render(800, 600, density = d, frames = 8)
    report.check(
        "interaction/precise-wheel-scrolls-list",
        scrollState.value > beforePrecise,
        "6 × 1/3 格（= 2 格）之后 value ${beforePrecise} -> ${scrollState.value}" +
            "（期望变大；整数除法时这 6 发全是 0）",
    )

    // 鼠标滚轮的滚动是**带缓动**的（MouseWheelScrollingLogic 的 threshold + tween），
    // 所以断言前要等它停稳，否则"没动/动了"都可能读到中间值。
    fun settleHorizontal(): Int {
        var last = -1
        var stable = 0
        var guard = 0
        while (guard++ < 200 && stable < 3) {
            driver.render(800, 600, density = d, frames = 1)
            val v = probe.horizontalScrollValue
            stable = if (v == last) stable + 1 else 0
            last = v
        }
        return probe.horizontalScrollValue
    }

    val hx = ((HSCROLL_X_DP + HSCROLL_W_DP / 2) * d).toInt()
    val hy = contentTop + ((HSCROLL_Y_DP + HSCROLL_H_DP / 2) * d).toInt()
    val hBase = probe.horizontalScrollValue
    // (2) Shift+滚轮 -> 横向列表动起来。
    //     方向说明：WM_MOUSEWHEEL 的正 delta = 滚轮向前（远离用户）= 竖直列表里"往前翻"；
    //     同一个符号搬到横向轴上就是"往列表起点翻"。所以 **Shift+滚轮向下（deltaY<0）
    //     ⇒ 向右滚（看后面的内容）** —— 和 Windows 上其它程序的 Shift+滚轮一致。
    //     （起点 0 时只有"向末端"能动，所以这条同时把方向也钉住了。）
    wheelEvent(app, hx, hy, deltaY = -3f, shift = true)
    val afterShift = settleHorizontal()
    report.check(
        "interaction/shift-wheel-scrolls-horizontal-list",
        afterShift > hBase,
        "Shift+滚轮向下（deltaY=-3 -> scrollDelta (-3,0)）之后 horizontalScroll=$afterShift" +
            "（期望 > $hBase = 向右滚）",
    )
    // (3) 反证：普通竖直滚轮**不动**横向列表（此时值非 0，所以"错误映射成横向"的两种符号
    //     都会被这条抓到，不是空断言）。
    wheelEvent(app, hx, hy, deltaY = -3f)
    val afterPlain = settleHorizontal()
    report.check(
        "interaction/plain-wheel-ignores-horizontal-list",
        afterPlain == afterShift,
        "竖直滚轮之后 horizontalScroll=$afterPlain（期望不变=$afterShift）—— " +
            "上游 toSingleAxisDeltaFromAngle 让横向滚动条忽略纯竖直 delta（画廊里这种滚轮" +
            "会落到外层竖直列表上）",
    )
    // 真的横向滚轮 / 触控板横滑（WM_MOUSEHWHEEL）：必须能推动横向列表。
    //
    // ⚠ 这里**只断言"能滚"、不钉方向**：上游 AWT 的 MouseWheelEvent 根本没有横向分量
    // （`ComposeSceneMediator` 只有 Shift 改写这一条路），所以 tilt 轮的正负号没有上游
    // 依据可比；而 Windows 文档只说"正 = 向右倾斜"，没说应该往哪滚。留一条"能滚"
    // 的断言在这里，方向等真机 tilt 轮实测（HANDOVER §17.30 有记录）。
    wheelEvent(app, hx, hy, deltaX = -3f)
    val afterHorizontal = settleHorizontal()
    report.check(
        "interaction/horizontal-wheel-scrolls-horizontal-list",
        afterHorizontal != afterPlain,
        "横向滚轮（deltaX=-3）之后 horizontalScroll=$afterHorizontal（期望 != $afterPlain）",
    )

    // 2.6b 触摸：手指拖动必须能滚动列表（真机反馈「Windows 触摸屏能点击、不能滑动」）
    //
    // 根因不是「没接触摸」，而是触摸被 Windows 提升成了**鼠标**：Compose 的
    // scrollable 明确拒绝鼠标拖拽滚动
    // （foundation/gestures/AbstractScrollableNode.kt: canDrag = { it != PointerType.Mouse }），
    // 所以鼠标拖拽不滚是设计行为，触摸必须作为 PointerType.Touch 派发。
    // 这里用与 C 桥接完全相同的 WindowsEvent.TouchEvent 序列驱动场景，锁死这条链路。
    // 每一步之间渲染两帧，并把 ScrollState 记进 trace：失败时一眼能看出是
    // 「完全没响应」还是「响应了但滚错方向/被夹住」。
    app.debugTouchTrace = true

    // (a) 触摸「点」得动吗？—— 先确认触点能命中并驱动 clickable，
    //     否则说明问题在命中/分发，而不是 scrollable 的手势识别。
    val clicksBeforeTouch = probe.clickCount
    app.dispatchEvent(WindowsEvent.TouchEvent(pointerId = 1L, x = clickX, y = clickY, phase = TouchPhase.Down))
    app.dispatchEvent(WindowsEvent.TouchEvent(pointerId = 1L, x = clickX, y = clickY, phase = TouchPhase.Up))
    driver.render(800, 600, density = d, frames = 3)
    report.checkEquals("interaction/touch-tap-clicks", clicksBeforeTouch + 1, probe.clickCount)

    // (a2) 「悬停」的语义边界（真机反馈：触摸屏上「悬停/点击试试」的卡片点了永远不显示文字）。
    //
    // 结论先写在这里：**触摸不会有 hover，这是 Compose 的模型，不是宿主漏发**。
    //   Compose 的 hover = `PointerEventType.Enter/Exit`，由 HitPathTracker 只对
    //   `InternalPointerEvent.activeHoverEvent(id)` 为真的指针合成；skiko 的实现是
    //   `changes[id]?.type == PointerType.Mouse`（InternalPointerEvent.skiko.kt）——
    //   触摸指针是 `PointerType.Touch`，永远为假。Android 上触摸 `hoverable` 同样
    //   毫无反应（Enter 只来自鼠标/触控笔的 hover 事件）。
    // 所以「只把可见变化挂在 hoverable 上」的控件在触摸屏上必然没反馈 —— 那是应用侧
    // 设计问题（画廊里那个盒子以前就是这样，本版已改成点击也会显示计数 + 高亮）。
    //
    // 但「触摸点了没反应」还有另一半属于宿主真 bug 的可能：按压反馈
    // （`PressInteraction.Press`，涟漪/自定义高亮的来源）和 click 必须都有。
    // 下面七条把这几件事分别钉死：
    //   · 触摸 -> Press/Release 必须有（真 bug 会在这里红）
    //   · 触摸 -> HoverInteraction.Enter 必须一次都不出现（模型如此，若"顺手"给触摸
    //     合成 hover，等于偏离上游行为）
    //   · 触摸 -> click 必须触发
    //   · 鼠标 -> hover 必须能进能出（CSD 标题栏按钮的悬停高亮就靠它，不能被上面
    //     那条"触摸不能 hover"误伤）
    val hoverButtonX = clickX
    val hoverButtonY = clickY
    // 交互事件 -> 探针字段之间隔着「协程派发」（`coroutineScope.launch { interactionSource.emit() }`
    // -> 收集器恢复），离屏驱动器一次 `render(frames=k)` 是 k 轮 (pumpDispatchers + renderFrame)。
    // 与其把帧数写死（首版写死 2~3 帧，状态还没到，假失败过），不如显式等到状态到位，
    // 并把用掉的帧数写进 info —— 真机上窗口循环 60Hz 连续 pump，这个延迟无意义。
    fun settleProbe(want: Boolean, maxFrames: Int = 16, value: () -> Boolean): Int {
        var used = 0
        while (used < maxFrames && value() != want) {
            driver.render(800, 600, density = d, frames = 1)
            used++
        }
        return used
    }

    // 前面的点击把鼠标停在了这个按钮上：先把鼠标挪到别处（底部滚动区），
    // 保证下面是从「未 hover」开始的干净状态。顺便这也验证了 hover 的**退出**：
    // 鼠标确实在按钮上过（前面的点击移动过去了），所以这里必须真的发生一次 Exit。
    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = 700, y = contentTop + 500))
    val awayFrames = settleProbe(false) { probe.probeButtonHovered }
    report.info("hover 探针：鼠标移开后 ${awayFrames} 帧到位")
    report.check(
        "interaction/mouse-move-away-clears-hover",
        !probe.probeButtonHovered && probe.probeButtonHoverEnters >= 1 && probe.probeButtonHoverExits >= 1,
        "鼠标移到 (700,${contentTop + 500}) 后 probeButtonHovered=${probe.probeButtonHovered}（期望 false）；" +
            "Enter=${probe.probeButtonHoverEnters} Exit=${probe.probeButtonHoverExits}（都该 ≥1 —— " +
            "前面 click() 先把鼠标移到了按钮上，这里必须真的退出）",
    )

    val hoverEntersBeforeTouch = probe.probeButtonHoverEnters
    val hoverExitsBeforeTouch = probe.probeButtonHoverExits
    val pressesBeforeTouch = probe.probeButtonPresses
    val releasesBeforeTouch = probe.probeButtonReleases
    val clicksBeforeHoverBoxTouch = probe.clickCount
    app.dispatchEvent(
        WindowsEvent.TouchEvent(pointerId = 9L, x = hoverButtonX, y = hoverButtonY, phase = TouchPhase.Down),
    )
    val pressFrames = settleProbe(true) { probe.probeButtonPressed }
    val hoveredWhileTouching = probe.probeButtonHovered
    val pressedWhileTouching = probe.probeButtonPressed
    app.dispatchEvent(
        WindowsEvent.TouchEvent(pointerId = 9L, x = hoverButtonX, y = hoverButtonY, phase = TouchPhase.Up),
    )
    val releaseFrames = settleProbe(false) { probe.probeButtonPressed }
    driver.render(800, 600, density = d, frames = 2)
    report.info("按压探针：按下 ${pressFrames} 帧到位、抬起 ${releaseFrames} 帧到位")

    report.check(
        "interaction/touch-press-feedback",
        pressedWhileTouching && probe.probeButtonPresses == pressesBeforeTouch + 1,
        "触摸按下后 probeButtonPressed=$pressedWhileTouching（期望 true），" +
            "PressInteraction=${probe.probeButtonPresses - pressesBeforeTouch} 次（期望 1）",
    )
    report.check(
        "interaction/touch-release-clears-pressed",
        probe.probeButtonReleases == releasesBeforeTouch + 1 && !probe.probeButtonPressed,
        "触摸抬起后 ReleaseInteraction=${probe.probeButtonReleases - releasesBeforeTouch} 次（期望 1），" +
            "probeButtonPressed=${probe.probeButtonPressed}（期望 false），" +
            "Cancel=${probe.probeButtonCancels}（期望 0）",
    )
    report.check(
        "interaction/touch-does-not-hover",
        !hoveredWhileTouching && !probe.probeButtonHovered &&
            probe.probeButtonHoverEnters == hoverEntersBeforeTouch &&
            probe.probeButtonHoverExits == hoverExitsBeforeTouch,
        "触摸全程 HoverInteraction.Enter=${probe.probeButtonHoverEnters - hoverEntersBeforeTouch} / " +
            "Exit=${probe.probeButtonHoverExits - hoverExitsBeforeTouch} 次（都期望 0 —— " +
            "Compose 的 hover 只对鼠标合成）；按下瞬间 probeButtonHovered=$hoveredWhileTouching（期望 false）",
    )
    report.checkEquals(
        "interaction/touch-tap-hover-probe-clicks",
        clicksBeforeHoverBoxTouch + 1,
        probe.clickCount,
    )

    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = hoverButtonX, y = hoverButtonY))
    val enterFrames = settleProbe(true) { probe.probeButtonHovered }
    report.info("hover 探针：鼠标移上按钮 ${enterFrames} 帧到位")
    report.check(
        "interaction/mouse-hover-enters",
        probe.probeButtonHovered && probe.probeButtonHoverEnters == hoverEntersBeforeTouch + 1,
        "鼠标移到按钮上：probeButtonHovered=${probe.probeButtonHovered}（期望 true），" +
            "HoverInteraction.Enter=${probe.probeButtonHoverEnters - hoverEntersBeforeTouch} 次（期望 1）",
    )
    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = 700, y = contentTop + 500))
    val exitFrames = settleProbe(false) { probe.probeButtonHovered }
    report.info("hover 探针：鼠标再次移开 ${exitFrames} 帧到位")
    report.check(
        "interaction/mouse-hover-exits",
        !probe.probeButtonHovered && probe.probeButtonHoverExits == hoverExitsBeforeTouch + 1,
        "鼠标移开按钮：probeButtonHovered=${probe.probeButtonHovered}（期望 false），" +
            "HoverInteraction.Exit=${probe.probeButtonHoverExits - hoverExitsBeforeTouch} 次（期望 1）",
    )

    // (a3) 悬停（**没有 DOWN** 的 MOVE/UP）不能变成一根凭空按下的手指（HANDOVER §17.29）。
    //
    // 真机现场（v0.5.10 日志）：笔悬停时 Windows 一直发 WM_POINTERUPDATE，日志里表现为
    // `touch: MOVE id=253 … active=0`（C++ 触点表只在 DOWN 时加人，所以 active=0 就说明
    // 这根"手指"从来没按下过）。老实现把这些事件也当成触摸加进表里（pressed=true）——
    // Compose 对没见过的 id 取 previousDown=false，于是 `changedToDown` 成立，
    // 悬停就变成"有人按住不放"：点击被吞（fastAll{changedToUp} 不再成立）、单指被当双指。
    //
    // 断言两件事：悬停事件**根本不到** Compose（探针计数不变、pressed 不变），
    // 以及宿主自己记下了「丢掉过 2 条无主事件」（MOVE + UP 各一条）。
    val hoverProbeX = ((TIME_PROBE_X_DP + 20) * d).toInt()
    val hoverProbeY = contentTop + ((TIME_PROBE_Y_DP + 20) * d).toInt()
    val pointerEventsBeforeHover = probe.pointerEventCount
    val pressedBeforeHover = probe.lastPointerPressed
    val droppedBeforeHover = app.droppedUntrackedTouchCount
    app.dispatchEvent(
        WindowsEvent.TouchEvent(
            pointerId = 41L, x = hoverProbeX, y = hoverProbeY,
            phase = TouchPhase.Move, timeMillis = 6000L,
        ),
    )
    driver.render(800, 600, density = d, frames = 2)
    app.dispatchEvent(
        WindowsEvent.TouchEvent(
            pointerId = 41L, x = hoverProbeX, y = hoverProbeY,
            phase = TouchPhase.Up, timeMillis = 6010L,
        ),
    )
    driver.render(800, 600, density = d, frames = 2)
    report.check(
        "interaction/touch-hover-does-not-press",
        probe.pointerEventCount == pointerEventsBeforeHover &&
            probe.lastPointerPressed == pressedBeforeHover &&
            !probe.lastPointerPressed &&
            app.activeTouchCount == 0 &&
            app.droppedUntrackedTouchCount == droppedBeforeHover + 2,
        "悬停 MOVE+UP 之后：探针收到事件 ${probe.pointerEventCount - pointerEventsBeforeHover} 条（期望 0）、" +
            "lastPointerPressed=${probe.lastPointerPressed}（期望 false）、" +
            "activeTouchCount=${app.activeTouchCount}（期望 0）、" +
            "宿主丢弃无主事件 ${app.droppedUntrackedTouchCount - droppedBeforeHover} 条（期望 2）",
    )

    // (a4) 按住 + 抖动 ≈ 真机形状（v0.5.10 日志里那次是 645ms / 66 条 ~100Hz 的静止上报）：
    //      **恰好一次** click / Press / Release，不能有 Cancel。
    //      真机日志里同一时刻出现过「按住期间 onClick 被调 10 次」——那次的来源还没定论
    //      （触摸流里只有一对 DOWN/UP，所以最可能是鼠标/键盘路径，v0.5.11 加了这两条
    //      通道的日志去定位）。这里先把"触摸按住抖动"这条路径本身钉死。
    val clicksBeforeHold = probe.clickCount
    val pressesBeforeHold = probe.probeButtonPresses
    val releasesBeforeHold = probe.probeButtonReleases
    val cancelsBeforeHold = probe.probeButtonCancels
    val holdId = 42L
    val holdX = hoverButtonX
    val holdY = hoverButtonY
    app.dispatchEvent(
        WindowsEvent.TouchEvent(
            pointerId = holdId, x = holdX, y = holdY,
            phase = TouchPhase.Down, timeMillis = 7000L,
        ),
    )
    driver.render(800, 600, density = d, frames = 1)
    for (i in 1..40) {
        // 总位移始终远小于 slop（~18px），但每一步都在动 —— 真机数字转换器就是这样
        val dx = if (i % 3 == 0) 3 else -2
        val dy = if (i % 4 == 0) 2 else -1
        app.dispatchEvent(
            WindowsEvent.TouchEvent(
                pointerId = holdId, x = holdX + dx, y = holdY + dy,
                phase = TouchPhase.Move, timeMillis = 7000L + i * 16L,
            ),
        )
        driver.render(800, 600, density = d, frames = 1)
    }
    app.dispatchEvent(
        WindowsEvent.TouchEvent(
            pointerId = holdId, x = holdX, y = holdY,
            phase = TouchPhase.Up, timeMillis = 7000L + 41 * 16L,
        ),
    )
    driver.render(800, 600, density = d, frames = 3)
    report.check(
        "interaction/touch-hold-jitter-clicks-once",
        probe.clickCount == clicksBeforeHold + 1 &&
            probe.probeButtonPresses == pressesBeforeHold + 1 &&
            probe.probeButtonReleases == releasesBeforeHold + 1 &&
            probe.probeButtonCancels == cancelsBeforeHold &&
            !probe.probeButtonHovered,
        "按住 650ms + 40 次抖动（~100Hz、总位移 <slop）后：click=${probe.clickCount - clicksBeforeHold}（期望 1）、" +
            "Press=${probe.probeButtonPresses - pressesBeforeHold}（期望 1）、" +
            "Release=${probe.probeButtonReleases - releasesBeforeHold}（期望 1）、" +
            "Cancel=${probe.probeButtonCancels - cancelsBeforeHold}（期望 0）、" +
            "probeButtonHovered=${probe.probeButtonHovered}（期望 false）",
    )

    // (b) 触摸拖动能滚吗？
    //
    // 拖动轨迹要**完全落在底部滚动区里**（它的 y 范围是 480..600），并且每一步之间
    // 渲染两帧：ScrollState 记进 trace，失败时一眼能看出是「完全没响应」还是
    // 「响应了但被夹住」。实测正常拖动时 TOUCHDBG 里 Move 的 result=7
    // （派发到控件 + 移动被消费 + 变化被消费）。
    val beforeTouch = scrollState.value
    app.dispatchEvent(WindowsEvent.TouchEvent(pointerId = 1L, x = 400, y = 560, phase = TouchPhase.Down))
    driver.render(800, 600, density = d, frames = 2)
    val touchTrace = StringBuilder("down:${scrollState.value}")
    for (yy in intArrayOf(540, 500, 460, 420, 380, 340, 300)) {
        app.dispatchEvent(WindowsEvent.TouchEvent(pointerId = 1L, x = 400, y = yy, phase = TouchPhase.Move))
        driver.render(800, 600, density = d, frames = 2)
        touchTrace.append(",$yy:${scrollState.value}")
    }
    app.dispatchEvent(WindowsEvent.TouchEvent(pointerId = 1L, x = 400, y = 300, phase = TouchPhase.Up))
    driver.render(800, 600, density = d, frames = 4)
    report.check(
        "interaction/touch-drag-scrolls",
        scrollState.value > beforeTouch,
        "before=$beforeTouch after=${scrollState.value} max=${scrollState.maxValue} trace=$touchTrace",
    )
    app.debugTouchTrace = false
    // 反证：同样轨迹用**鼠标**事件走一遍，不应该滚动（否则说明上面那条测的其实是
    // 鼠标路径，触摸通道根本没被测到）。
    //
    // ⚠ 先让**触摸甩动（fling）**停稳再取基准值：fling 会继续跑若干帧，之前偶发
    // 测到的是它剩下的位移（真机/CI 上出现过 before=466 after=480 的假失败）。
    var settleFrames = 0
    while (settleFrames < 120) {
        val v = scrollState.value
        driver.render(800, 600, density = d, frames = 1)
        settleFrames++
        if (scrollState.value == v) break
    }
    val beforeMouseDrag = scrollState.value
    mouseDrag(app, 400, 560, 300)
    driver.render(800, 600, density = d, frames = 4)
    // 2.6c 多点触摸（捏合缩放）：宿主必须把**每根手指**的 WM_POINTER 事件聚合成
    //      多指针 PointerEvent 交给 Compose（§8.4 里这块一直没测过）。
    //      这里用官方 transformable 接手势：两指张开 -> 放大，捏合 -> 缩小。
    val pinchBase = probe.pinchScale
    twoFingerGesture(
        app, driver, d,
        start1 = Pair(620f, 390f), end1 = Pair(578f, 390f),
        start2 = Pair(700f, 390f), end2 = Pair(742f, 390f),
    )
    report.check(
        "interaction/pinch-zoom-in",
        probe.pinchScale > pinchBase * 1.2f,
        "两指张开 80px->164px：scale ${pinchBase} -> ${probe.pinchScale}，回调 ${probe.pinchEvents} 次",
    )
    val pinchAfterZoomIn = probe.pinchScale
    twoFingerGesture(
        app, driver, d,
        start1 = Pair(586f, 390f), end1 = Pair(642f, 390f),
        start2 = Pair(734f, 390f), end2 = Pair(678f, 390f),
    )
    report.check(
        "interaction/pinch-zoom-out",
        probe.pinchScale < pinchAfterZoomIn * 0.9f,
        "两指捏合 148px->36px：scale $pinchAfterZoomIn -> ${probe.pinchScale}，回调 ${probe.pinchEvents} 次",
    )
    // 手指全部抬起之后，宿主的活动触点表必须归零 —— 否则残留的触点会让后续
    // 单指手势"卡住"（WM_POINTERCAPTURECHANGED 那条补抬起的逻辑就是防这个）。
    report.check(
        "interaction/no-leaked-touch-pointers",
        app.activeTouchCount == 0,
        "两轮双指手势之后 activeTouchCount=${app.activeTouchCount}（期望 0）",
    )

    report.check(
        "interaction/mouse-drag-does-not-scroll",
        scrollState.value == beforeMouseDrag,
        "before=$beforeMouseDrag after=${scrollState.value}",
    )
    // 触摸抬起后不能留下"卡住"的触点，否则后续滚动会被当成多指手势
    report.checkEquals("interaction/touch-pointers-released", 0, app.activeTouchCount)

    // 2.6d 触摸事件必须带**真实事件时间**（§17.24 的修复）。
    //
    // 宿主以前不给时间戳：`sendPointerEvent` 用默认的「派发时刻」，而窗口循环是「先把
    // 消息泵里的触摸事件一次全部派发、再渲染一帧」—— 同一帧里到达的多条
    // WM_POINTERUPDATE 于是共用同一个毫秒。Compose 的甩动速度估计器（Lsq2，按时间轴
    // 做二次拟合）时间轴被压扁，就会算出凭空的甩动速度：真机上表现为**松手后内容
    // 自己跳一段**（嵌套滚动时这个假速度还会经 nestedScroll 交给父列表，整页跟着跳）。
    //
    // 两条断言：
    //   (1) 事件时间**原样到达 Compose 的指针输入层**（端到端，去掉 timeMillis 就翻）；
    //   (2) 「拖完按住不动 ~190ms 再松手」不能有甩动（真机上的"跳变"就是这个假速度
    //       的表象：手指已经停了，列表却自己滑一段）。
    // 手势的每一步都记进 trace（`阶段:scrollState.value`），失败时能直接看出是
    // "压根没滚动"还是"滚了但甩动不对"。
    val gestureTrace = StringBuilder()
    fun touchAt(id: Long, timeMs: Long, x: Int, y: Int, phase: TouchPhase) {
        app.dispatchEvent(
            WindowsEvent.TouchEvent(
                pointerId = id,
                x = x,
                y = y,
                phase = phase,
                timeMillis = timeMs,
            ),
        )
        gestureTrace.append(",").append(phase.name.take(1)).append(":${scrollState.value}")
    }
    // 把底部滚动区送回顶部并等平滑滚动动画停稳（新手势需要确定的起点）。
    fun resetScrollToTop() {
        wheel(app, 400, 540, deltaY = 400)
        wheel(app, 400, 540, deltaY = 400)
        var last = -1
        var stable = 0
        var guard = 0
        while (guard++ < 240 && stable < 4) {
            driver.render(800, 600, density = d, frames = 1)
            val v = scrollState.value
            stable = if (v == last) stable + 1 else 0
            last = v
        }
    }

    // (1) 事件时间戳必须**原样到达 Compose 的指针输入层**。
    //
    // 这是本轮修复的端到端回归网：探针（测试屏里一个不画像素的 pointerInput Box）记下
    // `PointerInputChange.uptimeMillis`，它必须等于宿主派发时带的事件时间（探针记的是
    // 最后到的那个事件 = 抬手的 4258）。一旦有人把 `sendPointerEvent(timeMillis = …)`
    // 去掉，Compose 拿到的是「派发时刻」—— 同一帧里到达的多条 WM_POINTERUPDATE 会共用
    // 同一个毫秒，速度估计器时间轴被压扁，就会算出凭空的甩动速度（真机：松手后内容
    // 自己跳一段，§17.24）。
    // y 要加 CSD 标题栏高度：场景坐标里标题栏占顶部 32dp（和别的用例一样）
    val probeX = ((TIME_PROBE_X_DP + 20) * d).toInt()
    val probeY = contentTop + ((TIME_PROBE_Y_DP + 20) * d).toInt()
    touchAt(101L, 4242L, probeX, probeY, TouchPhase.Down)
    driver.render(800, 600, density = d, frames = 1)
    touchAt(101L, 4258L, probeX, probeY, TouchPhase.Up)
    driver.render(800, 600, density = d, frames = 1)
    report.check(
        "interaction/touch-event-time-reaches-compose",
        probe.lastPointerUptime == 4258L && probe.lastPointerPosition == Offset(20f, 20f),
        "探针记录到 uptimeMillis=${probe.lastPointerUptime}（期望 4258 = 抬手事件带的" +
            "timeMillis；若退化成「派发时刻」会是一个很大的系统毫秒数）" +
            " pos=${probe.lastPointerPosition}（探针内的局部 20,20 = 命中位置也对）",
    )

    // (2) 同样的位移，但拖完按住不动 ~190ms 再松手：**不能有甩动**
    //     （有 = 假速度 = 真机上的"跳变"）。
    //
    // ⚠ harness 现象（§17.24 有记录，尚未定位）：合成事件下**第一遍**触摸手势有可能
    // 被整段吞掉 —— 实测每个事件都只有 `dispatchedToAPointerInputModifier`、没有
    // "移动被消费"（TOUCHDBG 里 result=1），拖动也就完全没生效。所以同一套手势跑两遍、
    // 断言第二遍，并把两遍的数字都写进失败信息（真机不受这个现象影响；两遍都被吞掉时
    // 这条断言会响亮地失败，不会假通过）。
    fun holdGesture(id: Long, t0: Long): Triple<Int, Int, String> {
        resetScrollToTop()
        val start = scrollState.value
        gestureTrace.setLength(0)
        touchAt(id, t0, 400, 560, TouchPhase.Down)
        driver.render(800, 600, density = d, frames = 1)
        touchAt(id, t0 + 8, 400, 540, TouchPhase.Move)
        touchAt(id, t0 + 16, 400, 520, TouchPhase.Move)
        touchAt(id, t0 + 24, 400, 500, TouchPhase.Move)
        driver.render(800, 600, density = d, frames = 1)
        // 按住不动 ~190ms：每帧一条静止上报，和真机 100Hz 触摸同形
        for (i in 1..12) {
            driver.render(800, 600, density = d, frames = 1)
            touchAt(id, t0 + 24 + i * 16L, 400, 500, TouchPhase.Move)
        }
        driver.render(800, 600, density = d, frames = 1)
        touchAt(id, t0 + 24 + 13 * 16L, 400, 500, TouchPhase.Up)
        driver.render(800, 600, density = d, frames = 1)
        val afterUp = scrollState.value
        driver.render(800, 600, density = d, frames = 30)
        val end = scrollState.value
        return Triple(afterUp - start, end - afterUp, gestureTrace.toString())
    }
    app.debugTouchTrace = true
    val warmup = holdGesture(102L, 2000L)
    val hold = holdGesture(103L, 2400L)
    app.debugTouchTrace = false
    report.check(
        "interaction/touch-hold-has-no-fling",
        hold.first >= 30 && kotlin.math.abs(hold.second) <= 2,
        "拖动段=${hold.first}px（期望 ≥30） 松手后 30 帧位移=${hold.second}px（期望 0±2） " +
            "trace=${hold.third} ｜ 预热遍: 拖动=${warmup.first}px 松手后位移=${warmup.second}px " +
            "trace=${warmup.third}",
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

    // 2.7b IME（组字 / 提交）
    //
    // 走的是**真实路径**：WindowsEvent.ImeXxx -> WindowsComposeApplication.handleEvent
    // -> WindowsTextInputService -> Compose 的文本输入层（不是自己造一个假的）。
    // 真机上的 IME 消息由 win32_window.cc 的 WM_IME_* 拆出来，Wine 里没有输入法，
    // 所以这里从「事件已经到达 Kotlin」这一段开始测；C 侧那一段见 window 阶段的
    // window/ime-commit-through-c-channel。
    click(app, 700, contentTop + 44)  // 聚焦主输入框、光标到末尾
    driver.render(800, 600, density = d, frames = 4)
    probe.setText("")
    driver.render(800, 600, density = d, frames = 4)

    app.dispatchEvent(WindowsEvent.ImeStartEvent)
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent("ni hao"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("ime/composing-text-visible", "ni hao", probe.text)
    report.check(
        "ime/composing-region-set",
        probe.value.composition != null,
        "composition=${probe.value.composition}",
    )
    app.dispatchEvent(WindowsEvent.ImeCommitEvent("你好"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("ime/commit-replaces-composing-text", "你好", probe.text)
    report.check(
        "ime/commit-clears-composing-region",
        probe.value.composition == null,
        "composition=${probe.value.composition}",
    )

    // 组字被清空（输入法里按 ESC / 把拼音删光）：不能动已经上屏的文本
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent("hao"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("ime/second-composition-appends", "你好hao", probe.text)
    app.dispatchEvent(WindowsEvent.ImeEndEvent)
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.check(
        "ime/end-clears-composing-region",
        probe.value.composition == null,
        "composition=${probe.value.composition}",
    )
    report.checkEquals("ime/end-keeps-committed-text", "你好hao", probe.text)

    // 2.7d 「重新转换」（再変換）：选区握手
    //
    // 输入法确认要重转换的范围之后，应用必须**先把原文本变成选区**，接下来那段组字
    // （setComposingText）才会替换它 —— 不做这一步，组字会插到光标处，原文还在，
    // 文本就重复了。这里覆盖两件事：
    //   1) 范围映射：字符串能在文档里对上 -> 给出文档范围；对不上 -> null
    //      （C 侧拿到 null 会**拒绝**这次重转换，绝不动文本）；
    //   2) 事件落地：ImeReconvertSelectEvent 紧跟组字/提交 -> 原文被替换，不重复。
    // 此刻文档是「你好hao」，光标在末尾（偏移 5）。
    val mapped = app.mapReconvertRange("hao", targetOffsetInText = 0, targetLen = 3)
    report.check(
        "ime/reconvert-range-maps-to-document",
        mapped != null && mapped[0] == 2 && mapped[1] == 5,
        "「hao」-> ${mapped?.toList()}（期望 [2, 5]，文档是「你好hao」）",
    )
    report.check(
        "ime/reconvert-range-refuses-unknown-text",
        app.mapReconvertRange("zzz", targetOffsetInText = 0, targetLen = 3) == null,
        "文档里没有的字符串必须返回 null（C 侧据此拒绝）",
    )
    // 握手：先选中 [2,5)（="hao"），再来一段组字 "hao" —— 文本必须**原地不变**。
    app.dispatchEvent(WindowsEvent.ImeReconvertSelectEvent(start = 2, end = 5))
    app.dispatchEvent(WindowsEvent.ImeStartEvent)
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent("hao"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("ime/reconvert-composition-replaces-original", "你好hao", probe.text)
    // 提交转换后的结果：原文本被替换，而不是"原文还在、又插一份"。
    app.dispatchEvent(WindowsEvent.ImeCommitEvent("好"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    report.checkEquals("ime/reconvert-commit-replaces-original", "你好好", probe.text)
    app.dispatchEvent(WindowsEvent.ImeEndEvent)
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)

    // 2.7c 候选窗锚点：IME 用 dwCharPos 问「组字串里第几个字符」，
    //      候选窗问的是第 0 个 —— 所以它的答案必须是**开始组字的位置**，
    //      不能随着拼音越打越长往右滑（真机反馈：候选框跟着光标一路右移，
    //      而原生 Windows 应用里它是钉在开始位置的）。
    probe.setText("")
    driver.render(800, 600, density = d, frames = 4)
    click(app, 700, contentTop + 44)
    driver.render(800, 600, density = d, frames = 4)
    app.dispatchEvent(WindowsEvent.ImeStartEvent)
    // 组字**还没文本**时先量一次锚点：真机实测这个值以前是「焦点区域/默认文本」的矩形
    // （高 58px、y 与真正的行差 10px），而组字一开始就变成真正的行矩形（高 42px）——
    // IME 用 pt.y + cLineHeight 摆候选窗，尺寸不稳 = 候选窗刚弹出来会上下跳。
    val anchorBeforeText = app.imeCaretRectForChar(0)
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent("ni"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    val anchorShort = app.imeCaretRectForChar(0)
    val charOneShort = app.imeCaretRectForChar(1)
    report.check(
        "ime/anchor-available",
        anchorShort != null && charOneShort != null,
        "anchor=${fmtRect(anchorShort)} char1=${fmtRect(charOneShort)}",
    )
    report.info("ime/anchor-rect 空组字=${fmtRect(anchorBeforeText)} 组字中=${fmtRect(anchorShort)}")
    // 空组字那一次**不判定**：文本框还没文本时排版给的是"默认/占位"行（Compose 的
    // focusedRectInRoot 在空文本分支用的是 sizeForDefaultText()），与有文本后的行本来
    // 就不是同一个矩形 —— 真机日志里那个 58px/42px 就是这么来的。它只影响「组字刚开始
    // 那一瞬间」的候选窗位置（IME 每次按键都会重新问，之后就是有文本的答案了）。
    // 这里只把实测值打出来，方便和真机日志对照；硬断言看下面 short/long 两条。
    // 拼音变长：组字串开头的位置**不能动**
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent("nihao"))
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    val anchorLong = app.imeCaretRectForChar(0)
    val charFourLong = app.imeCaretRectForChar(4)
    // 组字串变长时：起点 x 不许动（候选窗不右滑），**y 和行高也不许动**
    // （IME 用 pt.y + cLineHeight 摆候选窗，跳动 = 候选窗上下跳）。
    report.check(
        "ime/anchor-stays-at-composition-start",
        anchorShort != null && anchorLong != null &&
            abs(anchorShort[0] - anchorLong[0]) <= 2 &&
            abs(anchorShort[1] - anchorLong[1]) <= 2 &&
            abs(anchorShort[3] - anchorLong[3]) <= 2,
        "短拼音=${fmtRect(anchorShort)} 长拼音=${fmtRect(anchorLong)}（x/y/行高都不许跳）",
    )
    report.check(
        "ime/char-index-maps-rightward",
        anchorLong != null && charFourLong != null && charFourLong[0] > anchorLong[0],
        "第0个=${fmtRect(anchorLong)} 第4个=${fmtRect(charFourLong)}",
    )

    // 回归（真机崩溃）：组字串**刚变长、还没排版**时，IME 会同步来问字符矩形。
    // TextFieldDelegate.onEditCommand 会立刻 session.updateState(newValue)（这样
    // setComposingText 之后 IME 就能读到新文本），但 textLayoutResult 要等下一帧 ——
    // 于是 value 的长度 > layout 的长度，而 TextLayoutResult.getCursorRect() 对超出
    // **排版**长度的偏移会抛 IllegalArgumentException；这个回调跑在 IME 的 SendMessage
    // 里，异常逃出去就是进程崩（v0.4.9 真机：组字到 11 个字符时崩）。
    val longComposition = "k'n'n'n'n'n"
    app.dispatchEvent(WindowsEvent.ImeCompositionEvent(longComposition))
    app.pumpDispatchers()
    // 故意**不 render**：此刻 value 已是新文本、textLayoutResult 还是旧的
    val raced = app.imeCaretRectForChar(longComposition.length)
    report.check(
        "ime/stale-layout-does-not-throw",
        raced != null,
        "长组字串查询=${fmtRect(raced)}（value=${probe.text.length} 字符）",
    )
    val racedAnchor = app.imeCaretRectForChar(0)
    report.check(
        "ime/stale-layout-anchor-still-available",
        racedAnchor != null && anchorShort != null && abs(racedAnchor[0] - anchorShort[0]) <= 4,
        "错位帧锚点=${fmtRect(racedAnchor)} 正常帧锚点=${fmtRect(anchorShort)}",
    )
    app.dispatchEvent(WindowsEvent.ImeEndEvent)
    app.pumpDispatchers()
    driver.render(800, 600, density = d, frames = 4)
    probe.setText("")
    driver.render(800, 600, density = d, frames = 4)
    // 清干净，后面的弹层/菜单断言依赖的背景不受影响
    probe.setText("")
    driver.render(800, 600, density = d, frames = 4)

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
    val galleryApp = WindowsComposeApplication(title = "gallery", width = 960, height = 700, undecorated = true)
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
    val earlyApp = WindowsComposeApplication(title = "early", width = 400, height = 300, undecorated = true)
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

    // 2.11b 真机反馈「双指捏合的时候列表也跟着滚，松手还往上甩」。
    //
    // 把两种情形钉死（都是 Compose 的既有语义，不是宿主行为差异）：
    //   (a) 两根手指都落在 transformable 区域内 -> 缩放生效，外层滚动区**一点不动**
    //   (b) 两根手指都落在列表上（没落在手势区里）-> 列表照常滚（并且缩放值不变）
    // 真机上看到的「捏合还滚 + 松手往上甩」就是 (b)：画廊原来的捏合区只有 120dp，
    // 两指捏合时经常有手指落到外面；而 HUD 上的 scale 百分比是**上一次成功捏合**留下的，
    // 于是看起来像「缩放的同时在滚」。画廊现在把那块手势区做大了（Gallery.kt 有注释）。
    val pinchScrollState = ScrollState(0)
    val pinchScrollProbe = InteractionProbe()
    val pinchScrollApp = WindowsComposeApplication(
        title = "pinch-scroll",
        width = 800,
        height = 600,
        undecorated = true,
    )
    pinchScrollApp.setContent(withChrome = false) {
        Column(Modifier.fillMaxSize().verticalScroll(pinchScrollState)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .transformable(
                        state = rememberTransformableState { zoomChange, _, _ ->
                            pinchScrollProbe.pinchScale =
                                (pinchScrollProbe.pinchScale * zoomChange).coerceIn(0.25f, 4f)
                        },
                    ),
            )
            repeat(8) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(if (index % 2 == 0) Color(0xFF203040) else Color(0xFF304050)),
                )
            }
        }
    }
    val pinchDriver = OffscreenDriver(pinchScrollApp)
    pinchDriver.render(800, 600, density = 1f, frames = 3)

    // (a) 两指都在手势区（y<300）里，对向张开：缩放必须变，列表必须不动
    twoFingerGesture(
        pinchScrollApp, pinchDriver, 1f,
        start1 = Pair(240f, 120f), end1 = Pair(180f, 80f),
        start2 = Pair(560f, 200f), end2 = Pair(620f, 240f),
    )
    pinchDriver.render(800, 600, density = 1f, frames = 4)
    report.check(
        "interaction/pinch-inside-scrollable-does-not-scroll",
        pinchScrollProbe.pinchScale > 1.05f && pinchScrollState.value == 0,
        "两指都在手势区：scale=${pinchScrollProbe.pinchScale}（期望 >1.05）" +
            " 外层滚动=${pinchScrollState.value}px（期望 0）",
    )

    // (c) 两指都在手势区里捏合，**松手之前先停住不动**（真机数字转换器会持续发静止上报）：
    //     列表必须一动不动。
    //
    // 这条对着「缩放之后跳一下」：父 scrollable 被子的 transformable 消费后会进入
    // 「手势接管」（gesture pickup）状态；当某一帧里**所有** change 都没被消费（=两指都
    // 停住时就是），它会用「相对按下点的**总**位移」当 slop 检测的初始累计值，下一次事件
    // 于是把 `总位移 - slop` 当成**第一次拖动增量**发出去 → 列表一次跳几百 px。
    // 复现用两指对向张开（形心不动，纯缩放），第一根手指的总位移 161px → 跳变 ≈143px。
    // 修复在 vendor/compose-core 的本地补丁里（§17.27）。
    val jumpBefore = pinchScrollState.value
    val jumpScaleBefore = pinchScrollProbe.pinchScale
    fun pinchTouch(id: Long, x: Float, y: Float, phase: TouchPhase) {
        pinchScrollApp.dispatchEvent(
            WindowsEvent.TouchEvent(pointerId = id, x = x.toInt(), y = y.toInt(), phase = phase),
        )
    }
    pinchTouch(201L, 240f, 140f, TouchPhase.Down)
    pinchTouch(202L, 560f, 180f, TouchPhase.Down)
    pinchDriver.render(800, 600, density = 1f, frames = 2)
    for (i in 1..6) {
        val t = i / 6f
        pinchTouch(201L, 240f + (100f - 240f) * t, 140f + (60f - 140f) * t, TouchPhase.Move)
        pinchTouch(202L, 560f + (700f - 560f) * t, 180f + (260f - 180f) * t, TouchPhase.Move)
        pinchDriver.render(800, 600, density = 1f, frames = 2)
    }
    // ★ 关键：松手前停住不动若干帧（每帧两条静止上报，与真机 100Hz 触摸同形）
    for (i in 1..8) {
        pinchTouch(201L, 100f, 60f, TouchPhase.Move)
        pinchTouch(202L, 700f, 260f, TouchPhase.Move)
        pinchDriver.render(800, 600, density = 1f, frames = 2)
    }
    val jumpBeforeUp = pinchScrollState.value
    pinchTouch(201L, 100f, 60f, TouchPhase.Up)
    pinchTouch(202L, 700f, 260f, TouchPhase.Up)
    pinchDriver.render(800, 600, density = 1f, frames = 6)
    report.check(
        "interaction/pinch-then-hold-does-not-jump",
        pinchScrollState.value == jumpBefore,
        "捏合后停住再松手：滚动 ${jumpBefore} ->（停住期间）${jumpBeforeUp} ->（松手后）" +
            "${pinchScrollState.value}px（期望始终 ${jumpBefore}）" +
            " 缩放 ${jumpScaleBefore} -> ${pinchScrollProbe.pinchScale}" +
            "（若松手前跳了一截 = 手势接管把整段位移当成了第一次拖动增量）",
    )

    // (b) 两指都落在下面的列表上（y>300），一起往上拖：列表必须滚，缩放必须不变
    val pinchScrollBefore = pinchScrollState.value
    val pinchScaleBefore = pinchScrollProbe.pinchScale
    twoFingerGesture(
        pinchScrollApp, pinchDriver, 1f,
        start1 = Pair(400f, 420f), end1 = Pair(400f, 320f),
        start2 = Pair(500f, 520f), end2 = Pair(500f, 420f),
    )
    pinchDriver.render(800, 600, density = 1f, frames = 4)
    report.check(
        "interaction/pinch-outside-target-scrolls-list",
        pinchScrollState.value > pinchScrollBefore && pinchScrollProbe.pinchScale == pinchScaleBefore,
        "两指都在列表上：滚动 ${pinchScrollBefore}->${pinchScrollState.value}px（期望变大）" +
            " scale=${pinchScrollProbe.pinchScale}（期望不变=${pinchScaleBefore}）" +
            " max=${pinchScrollState.maxValue}",
    )
    pinchScrollApp.close()

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

private fun runWindowTests(report: SelfTestReport, perfContractChecks: Boolean = true) {
    report.section("window")
    val probe = InteractionProbe()
    val scrollState = ScrollState(0)
    // 同样显式 CSD：下面的点击/剪贴板坐标全都含 32dp 标题栏偏移。
    val app = WindowsComposeApplication(
        title = "ComposeKN Selftest", width = 900, height = 600, undecorated = true,
    )
    var clipboardValue: String? = null
    var clickedAt = Pair(0, 0)

    // 「真实 Win32 消息」子阶段（§17.31）的基线快照。
    //
    // 为什么记基线而不是断言绝对值：前面已经有一大堆**合成**事件（frame 10 的点击、
    // frame 16 的滚轮…）改过同一批探针，绝对值断言会和它们纠缠；这一段要证明的是
    // 「真实消息确实走通了」，所以断言的是**增量**。
    var msgClicks0 = 0
    var msgPresses0 = 0
    var msgReleases0 = 0
    var msgHoverEnters0 = 0
    var msgHoverExits0 = 0
    var msgScroll0 = 0
    var msgHScroll0 = 0
    var msgText0 = ""
    var msgPreciseWheelBase = 0
    // 富文本剪贴板检查用的原始字节（hex，供独立解析）
    var rawHtmlHex: String? = null
    var rawV5Hex: String? = null
    var rawDibHex: String? = null

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
        if (frame == 70) {
            // IME：Wine 里没有真输入法（装不了），所以用 C 侧测试钩子注入一条「提交」
            // 事件 —— 它走的是**和真实 WM_IME_COMPOSITION(GCS_RESULTSTR) 完全一样的
            // 路径**：C 侧队列 -> UTF-8 文本通道 -> Kotlin Win32Event.IME_COMMIT ->
            // WindowsEvent.ImeCommitEvent -> Compose 文本输入层。
            // 于是「C 到 Kotlin 的字符串通道有没有接错」在自动化里是能被断言的。
            // （注意：此时文本框里是 Ctrl+V 粘贴出来的 "CK"，光标在末尾。）
            //
            // 连注两条：C 侧「事件队列」和「文本队列」是两个 FIFO，必须严格一一对应。
            // 只注一条的话错位一格也看不出来；两条不同内容就能把错位抓出来
            // （错位时第一条文本会被当成第二条事件的文本）。
            app.window.imeTestCommit("中")
        }
        if (frame == 72) {
            app.window.imeTestCommit("文")
        }
        if (frame == 74) {
            report.checkEquals("window/ime-commit-through-c-channel", "CK中文", probe.text)
            // 把注入的两个字符删掉，让后面的断言仍然看到 "CK"
            keyPress(app, 0x08) // VK_BACK
            keyPress(app, 0x08) // 两次：注入了「中文」两个字
            // 最大化回归（真机 bug）：无边框窗口客户区 = 窗口矩形，而 Windows 最大化
            // 会把窗口矩形按「不可见缩放边框」扩到屏幕外 → 客户区超出显示器，最右侧的
            // 关闭按钮被裁掉一半，且**只在最大化时**出现。
            // toggleMaximized 走 ShowWindow，最大化是异步发生的，隔几帧再断言。
            app.window.toggleMaximized()
        }
        if (frame == 76) {
            report.check(
                "window/maximize-client-fits-monitor",
                (app.window.nativeWindow?.clientOverflowCount ?: -1) == 0,
                "overflow=${app.window.nativeWindow?.clientOverflowCount} " +
                    "size=${app.window.nativeWindow?.width}x${app.window.nativeWindow?.height}",
            )
            report.check(
                "window/touch-channel-enabled",
                app.window.nativeWindow?.touchEnabled == true,
                "touch=${app.window.nativeWindow?.touchEnabled}",
            )
            // IME 诊断（不作为失败条件）：Wine 里没有输入法，imeMessageCount 通常就是
            // 注入的那条；真机上敲中文时这里应当持续增长 —— 用户排查时看这一行。
            report.info(
                "window/ime-diagnostics imeMessages=${app.window.imeMessageCount} " +
                    "composing=${app.window.imeComposing} text=${probe.text}",
            )
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
        }

        // =================================================================
        // 真实 Win32 消息路径（HANDOVER §17.31）
        //
        // 上面所有断言走的都是 `app.dispatchEvent(...)`：把 WindowsEvent 直接喂给
        // Compose，**完全跳过 C++ 宿主层**。于是 wndproc 里的参数解码、坐标换算、
        // 消息过滤在自动化里从来没有被执行过 —— 历史上的宿主层 bug（笔悬停变成
        // 一根按下的手指、Shift+滚轮没实现）全都是「只有真机手动操作才第一次跑到」。
        //
        // 这一段用 PostMessage 把**真实的 Win32 消息**投到窗口自己的消息队列：
        //   主循环 GetMessage -> DispatchMessage -> 真实 wndproc 分支 -> C 侧事件
        //   队列 -> Kotlin 派发 -> Compose
        // 断言的是宿主本身。坐标按**当前**窗口尺寸算（这一段在 frame 74 最大化
        // 之后跑，窗口已经不是 900x600）。
        //
        // 注意边界：PostMessage 不经过系统输入栈，所以它证明的是「消息到了之后宿主
        // 怎么处理」，不证明「系统会不会送来这条消息」。后者由测试脚本里的外部注入
        // 阶段覆盖（xdotool 打真实 X11 输入 -> wine -> wndproc）。
        // =================================================================
        if (frame == 79) {
            msgClicks0 = probe.clickCount
            msgPresses0 = probe.probeButtonPresses
            msgReleases0 = probe.probeButtonReleases
            msgHoverEnters0 = probe.probeButtonHoverEnters
            msgHoverExits0 = probe.probeButtonHoverExits
            msgScroll0 = scrollState.value
            msgHScroll0 = probe.horizontalScrollValue
            msgText0 = probe.text
            report.section("window 真实 Win32 消息（PostMessage -> wndproc -> Compose）")
            val ok = postRealMouse(app, Win32Message.MOUSEMOVE, BTN_X_DP, BTN_Y_DP)
            report.check("window/winmsg-post-mousemove", ok, "PostMessage(WM_MOUSEMOVE) -> $ok")
        }
        if (frame == 81) {
            // 真实 WM_MOUSEMOVE -> 真实 wndproc -> Compose hover。
            // 同时断言**没有**凭空按下：单纯的移动绝不能产生 PressInteraction
            // （v0.5.11 那个「笔悬停变成一根按下的手指」就是这条的反面）。
            report.check(
                "window/winmsg-mousemove-hovers",
                probe.probeButtonHovered && probe.probeButtonHoverEnters - msgHoverEnters0 >= 1,
                "hovered=${probe.probeButtonHovered} " +
                    "Enter 增量=${probe.probeButtonHoverEnters - msgHoverEnters0}",
            )
            report.check(
                "window/winmsg-mousemove-does-not-press",
                probe.probeButtonPresses == msgPresses0 && !probe.probeButtonPressed,
                "Press 增量=${probe.probeButtonPresses - msgPresses0} pressed=${probe.probeButtonPressed}",
            )
            val ok = postRealMouse(app, Win32Message.LBUTTONDOWN, BTN_X_DP, BTN_Y_DP)
            report.check("window/winmsg-post-lbuttondown", ok, "PostMessage(WM_LBUTTONDOWN) -> $ok")
        }
        if (frame == 83) {
            report.check(
                "window/winmsg-lbuttondown-presses",
                probe.probeButtonPressed && probe.probeButtonPresses == msgPresses0 + 1,
                "pressed=${probe.probeButtonPressed} Press 增量=${probe.probeButtonPresses - msgPresses0}",
            )
            postRealMouse(app, Win32Message.LBUTTONUP, BTN_X_DP, BTN_Y_DP)
        }
        if (frame == 85) {
            report.check(
                "window/winmsg-click-lands",
                probe.clickCount == msgClicks0 + 1 &&
                    probe.probeButtonReleases == msgReleases0 + 1 &&
                    !probe.probeButtonPressed,
                "clickCount ${msgClicks0}->${probe.clickCount} " +
                    "Release 增量=${probe.probeButtonReleases - msgReleases0}",
            )
            // 真实 WM_MOUSEMOVE 离开按钮 -> hover 必须退出（正向 + 反向都走真路径）
            postRealMouse(app, Win32Message.MOUSEMOVE, TEXT_X_DP, TEXT_Y_DP)
        }
        if (frame == 87) {
            report.check(
                "window/winmsg-mousemove-exits-hover",
                !probe.probeButtonHovered &&
                    probe.probeButtonHoverExits - msgHoverExits0 >= 1,
                "hovered=${probe.probeButtonHovered} Exit 增量=${probe.probeButtonHoverExits - msgHoverExits0}",
            )
            // 真实 WM_MOUSEWHEEL：客户端坐标是**窗口**逻辑坐标，C 侧会把它换算成
            // 屏幕坐标进 lParam（与真机消息一致），wndproc 里再 ScreenToClient 换回来
            // —— 这条链路错一位，滚轮就会滚到别的控件上。
            // 纵向滚动区贴在窗口底部（fillMaxWidth + height(120) + align(BottomStart)），
            // 所以 y 按**当前**窗口高度算；-120 = 一格、向用户方向（滚轮向下）。
            val ok = postRealMouse(
                app, Win32Message.MOUSEWHEEL, 450f,
                app.window.logicalHeight - CHROME_DP - 60f,
                wheelDelta = -Win32Message.WHEEL_DELTA,
            )
            report.check("window/winmsg-post-mousewheel", ok, "PostMessage(WM_MOUSEWHEEL) -> $ok")
        }
        if (frame == 89) {
            report.check(
                "window/winmsg-wheel-scrolls",
                scrollState.value > msgScroll0,
                "滚动值 ${msgScroll0} -> ${scrollState.value}（-120 = 滚轮向下，值应当增大）",
            )
            // 真实 WM_MOUSEHWHEEL（横向滚轮 / 触控板横滑）。
            // 方向说明：和竖向同一套约定 —— Compose 的 scrollable 对 LTR 两个轴都是
            // `reverseDirection = true`，所以 **负** delta 才是「向前滚 = 值增大」。
            // 0 处往反方向滚会被夹住（值不变），所以这里必须用会真正推动它的方向，
            // 否则断言写成 `!=` 也只是在测「夹取」。上游 AWT 没有横向分量可对照
            // （HANDOVER §17.30），方向本身没有上游依据 —— 这里钉的是「事件确实
            // 推到了横向滚动条」，纯竖直 delta 是推不动它的（阈值 PI/4）。
            val ok = postRealMouse(
                app, Win32Message.MOUSEHWHEEL, HSCROLL_X_DP + HSCROLL_W_DP / 2f,
                HSCROLL_Y_DP + HSCROLL_H_DP / 2f + CHROME_DP,
                wheelDelta = -Win32Message.WHEEL_DELTA,
            )
            report.check("window/winmsg-post-mousehwheel", ok, "PostMessage(WM_MOUSEHWHEEL) -> $ok")
        }
        if (frame == 91) {
            report.check(
                "window/winmsg-horizontal-wheel-scrolls",
                probe.horizontalScrollValue > msgHScroll0,
                "横向滚动值 ${msgHScroll0} -> ${probe.horizontalScrollValue}",
            )
            // 用**真实点击**把焦点还给输入框。
            //
            // 上面点按钮那一步会把焦点从输入框抢走（Compose 的正常语义：clickable
            // 是 focusable 的）。后面 WM_CHAR / IME 组字全都要求输入框有焦点，所以
            // 必须还回来 —— 而且是走**真实消息**还，顺手把「真实点击能聚焦文本框」
            // 这条也覆盖了。焦点状态由 `probe.mainFocused` 断言（frame 93）。
            postRealMouse(app, Win32Message.MOUSEMOVE, TEXT_X_DP, TEXT_Y_DP)
            postRealMouse(app, Win32Message.LBUTTONDOWN, TEXT_X_DP, TEXT_Y_DP)
            postRealMouse(app, Win32Message.LBUTTONUP, TEXT_X_DP, TEXT_Y_DP)
        }
        if (frame == 93) {
            report.check(
                "window/winmsg-real-click-refocuses-textfield",
                probe.mainFocused,
                "真实点击输入框后 mainFocused=${probe.mainFocused}",
            )
        }

        // ---- IME 候选窗锚点：走**真实**的 WM_IME_REQUEST(IMR_QUERYCHARPOSITION) 路径 ----
        //
        // 背景（真机两轮反馈）：微软拼音在组字期间显然是用「光标那条查询」的结果摆候选窗，
        // 所以只把 CFS_POINT/CFS_CANDIDATEPOS 设成组字起点没用（v0.4.10 实测仍然右移）。
        // 现在 C 侧组字期间一律回答「组字串第 0 个字符」，把整串范围塌缩成一个点。
        //
        // 这里断言的是 C 侧真答案（组字起点 = 组字中任何 dwCharPos 的答案），
        // 而 imeCaretRectForChar 那条路（如实映射）继续由逻辑阶段断言 —— 两条路
        // 是分开的，不能互相替代。
        if (frame == 94) {
            app.window.imeTestSendCompositionMessage(start = true)   // 真 WM_IME_STARTCOMPOSITION
            app.dispatchEvent(WindowsEvent.ImeStartEvent)
            app.dispatchEvent(WindowsEvent.ImeCompositionEvent("ni hao"))
        }
        if (frame == 96) {
            val composed = "ni hao"
            val anchor = app.imeCaretRectForChar(0)
            val caret = app.imeCaretRectForChar(composed.length)
            val q0 = app.window.imeTestQueryCharPos(0)
            val qEnd = app.window.imeTestQueryCharPos(composed.length)
            report.check(
                "window/ime-query-charpos-collapses-to-composition-start",
                anchor != null && caret != null && caret[0] > anchor[0] &&
                    q0 != null && qEnd != null &&
                    abs(q0[0] - qEnd[0]) <= 2 && abs(q0[0] - anchor[0]) <= 3 &&
                    app.window.imeComposing,
                "组字中 dwCharPos=0 -> ${q0?.toList()} dwCharPos=$composed.length -> ${qEnd?.toList()} " +
                    "组字起点=$anchor 真实光标=$caret composing=${app.window.imeComposing}",
            )
        }
        if (frame == 97) {
            // ---- IMM32 文档馈送 / 组字字体（输入法通过这些请求拿"文档 + 组字范围"）----
            // 此时文本框内容是 "CKni hao"，其中 "ni hao" 是组字区（偏移 2..8）。
            // 注意：这些请求真机上是输入法**同步**问的（Wine 里没有输入法，只能我们自己发），
            // 断言的也是"我们交给输入法的结构对不对"。
            val docFed = app.window.imeTestReconvert(kind = 0, bufferChars = 512)
            val expectedSum = probe.text.fold(0) { acc, c -> acc + c.code }
            report.check(
                "window/ime-document-feed-fills-document",
                docFed != null && docFed[0] == 1 &&
                    docFed[2] == probe.text.length && docFed[8] == expectedSum &&
                    docFed[3] == 32 &&
                    docFed[4] == 6 && docFed[5] == 4 &&
                    docFed[6] == 6 && docFed[7] == 4,
                "文本=${probe.text} " +
                    "dwStrLen=${docFed?.get(2)}（期望 ${probe.text.length}）" +
                    " sum=${docFed?.get(8)}（期望 $expectedSum）" +
                    " dwStrOffset=${docFed?.get(3)}（期望 32=sizeof(RECONVERTSTRING)）" +
                    " comp=${docFed?.get(4)}@${docFed?.get(5)}（期望 6@4）" +
                    " target=${docFed?.get(6)}@${docFed?.get(7)}（期望 6@4）",
            )
            // 真机（MS 拼音）实测：它发的 IMR_DOCUMENTFEED 是 **lParam = NULL** 的。
            // 默认策略是"没有缓冲区就不声称支持"（不处理 = 老行为，什么都不改），
            // 只有 COMPOSEKN_IME_DOCUMENTFEED=2（探针模式）才会对 NULL 回 TRUE。
            val nullProbe = app.window.imeTestReconvert(kind = 0, bufferChars = -1)
            report.check(
                "window/ime-document-feed-null-not-claimed",
                nullProbe != null && nullProbe[0] == 0,
                "lParam=NULL 的 DOCUMENTFEED -> handled=${nullProbe?.get(0)}（默认期望 0 = 不声称支持）",
            )
            // dwSize 不可信（真机探针实测：MS 拼音第二次调用给的是**未初始化**结构，
            // dwSize=1、偏移全是垃圾）-> 必须"不写、不处理"（写 dwSize 都可能越界）。
            val bogus = app.window.imeTestReconvert(kind = 0, bufferChars = -2)
            report.check(
                "window/ime-document-feed-ignores-bogus-dwsize",
                bogus != null && bogus[0] == 0,
                "dwSize=1 的结构 -> handled=${bogus?.get(0)}（期望 0 = 不写不处理）",
            )
            // 两段式：只给结构体大小的缓冲时，应当回"我需要多大"，而不是不回答。
            val twoPhase = app.window.imeTestReconvert(kind = 0, bufferChars = 0)
            report.check(
                "window/ime-document-feed-two-phase",
                twoPhase != null && twoPhase[0] == 1 && twoPhase[1] > 32,
                "dwSize=32 的请求 -> handled=${twoPhase?.get(0)} dwSize=${twoPhase?.get(1)}（期望 1 / >32）",
            )
            // 组字字体：回一个带行高的 LOGFONT（lfHeight 用负值表示字符高度）。
            val font = app.window.imeTestReconvert(kind = 2, bufferChars = 0)
            report.check(
                "window/ime-composition-font",
                font != null && font[0] == 1 && font[9] < 0,
                "IMR_COMPOSITIONFONT -> handled=${font?.get(0)} lfHeight=${font?.get(9)}（期望 1 / 负数）",
            )
        }
        if (frame == 98) {
            // 组字结束：立刻恢复如实回答（= 真实光标），不能还钉在组字起点。
            // 先结掉 Compose 侧的组字（composition 变 null），C 侧的 composing 由
            // 真 WM_IME_ENDCOMPOSITION 同步关掉，然后问一个**非 0** 的 dwCharPos：
            // 不在组字中时答案必须回到真实光标。
            app.dispatchEvent(WindowsEvent.ImeEndEvent)
            app.window.imeTestSendCompositionMessage(start = false)  // 真 WM_IME_ENDCOMPOSITION
            val after = app.window.imeTestQueryCharPos(4)
            val caretNow = app.imeCaretRectForChar(-1)
            report.check(
                "window/ime-query-charpos-honest-after-composition",
                after != null && caretNow != null && abs(after[0] - caretNow[0]) <= 3 &&
                    !app.window.imeComposing,
                "组字结束 dwCharPos=4 -> ${after?.toList()}（应当回到真实光标 $caretNow）",
            )
            // 把刚插进去的 "ni hao" 删掉，保持后面的断言还是看到 "CK"
            repeat(6) { keyPress(app, 0x08) }
        }
        if (frame == 100) {
            // ---- 「重新转换」选区握手（走**真实** WM_IME_REQUEST(IMR_CONFIRMRECONVERTSTRING)）----
            // 此时文本框是 "CK"、光标在末尾。输入法把 "CK" + 目标 [0,2) 发回来：
            // 我们能对上 -> 接受，并先把 [0,2) 变成选区（随后那段组字会替换它）。
            val accepted = app.window.imeTestConfirmReconvert("CK", 0, 2)
            report.check(
                "window/ime-reconvert-confirm-accepts-known-text",
                accepted,
                "文档「CK」+ 目标 [0,2) -> ${if (accepted) "接受" else "拒绝"}（期望接受）",
            )
            val refused = app.window.imeTestConfirmReconvert("zzz", 0, 3)
            report.check(
                "window/ime-reconvert-confirm-refuses-unknown-text",
                !refused,
                "文档里没有的字符串 -> ${if (refused) "接受" else "拒绝"}（期望拒绝）",
            )
            // 注意：上面的选选区事件是**排队**的（C 侧 FIFO），要到下一轮消息循环才落地 ——
            // 这正是真实 IME 的顺序（确认 -> 选区 -> 组字），所以组字放在 frame==87 发。
        }
        if (frame == 101) {
            app.dispatchEvent(WindowsEvent.ImeStartEvent)
            app.dispatchEvent(WindowsEvent.ImeCompositionEvent("CK"))
        }
        if (frame == 102) {
            report.checkEquals("window/ime-reconvert-composition-no-duplicate", "CK", probe.text)
            app.dispatchEvent(WindowsEvent.ImeCommitEvent("CK"))
            app.dispatchEvent(WindowsEvent.ImeEndEvent)
            report.checkEquals("window/ime-reconvert-commit-no-duplicate", "CK", probe.text)
            report.checkEquals("window/ime-charpos-test-restores-text", "CK", probe.text)
            // 真实键盘消息（PostMessage）：只投 WM_KEYDOWN / WM_KEYUP，**不**手工投
            // WM_CHAR —— 真实键盘上那个字符是消息循环里的 `TranslateMessage` 从
            // KEYDOWN 翻译出来的（见 `composekn_win32_pump`），所以
            //   KEYDOWN -> TranslateMessage -> WM_CHAR -> wndproc 的字符分支
            // 这条链才是真实路径。手工再投一条 WM_CHAR 会插入两个字符
            // （第一版就是这么红的：expected=CKz actual=CKzz），顺便也就把
            // 「循环里有没有接 TranslateMessage」这件事变成了断言的一部分：
            // 谁把它删掉，这里就会变成 actual=CK。
            val zDown = postRealKey(app, Win32Message.KEYDOWN, VK_Z, scanCode = 0x2C)
            report.check("window/winmsg-post-keydown", zDown, "PostMessage(WM_KEYDOWN VK_Z) -> $zDown")
        }
        if (frame == 104) {
            postRealKey(app, Win32Message.KEYUP, VK_Z, scanCode = 0x2C)
        }
        if (frame == 106) {
            report.checkEquals("window/winmsg-key-char-inserts-text", "${msgText0}z", probe.text)
            // 精确滚轮（触控板 / 自由滚轮）：WM_MOUSEWHEEL 的 zDelta **不保证**是 120 的
            // 倍数，40 是常见值。这一段必须走**真实消息** —— 整数除法那个 bug 就长在
            // WindowsComposeWindow.translateAndDispatch 里，从 Kotlin 侧合成事件绕不过去
            // （HANDOVER §17.32）。
            msgPreciseWheelBase = scrollState.value
            repeat(3) {
                postRealMouse(
                    app, Win32Message.MOUSEWHEEL, 450f,
                    app.window.logicalHeight - CHROME_DP - 60f,
                    wheelDelta = -40,
                )
            }
        }
        if (frame == 108) {
            report.check(
                "window/winmsg-precise-wheel-scrolls",
                scrollState.value > msgPreciseWheelBase,
                "3 × zDelta=-40（正好 1 格）之后滚动值 ${msgPreciseWheelBase} -> ${scrollState.value}" +
                    "（整数除法把 40/120 截断成 0 时值不变）",
            )
        }
        // ---- OLE 拖放（接收侧，HANDOVER §17.33）----
        //
        // 走的是**真实的 OLE 路径**：C 侧构造一个真的 IDataObject（CF_HDROP /
        // CF_UNICODETEXT），交给注册到 HWND 上的 IDropTarget —— 覆盖 COM vtable、
        // FORMATETC 解析、CF_HDROP 解码、事件/负载 FIFO、Kotlin 派发、
        // Compose 的 Modifier.dragAndDropTarget 命中与回调。唯一没覆盖的是
        // 「OLE 的模态拖放循环会不会调到这里」（Wine/Xvfb 里没法真拖一个文件）。
        if (frame == 110) {
            report.check(
                "window/winmsg-drag-ole-available",
                app.window.oleAvailable,
                "OleInitialize + RegisterDragDrop 必须在建窗口时就成功",
            )
            // 只收文件的框：位置要落在它**自己**的矩形里（onMoved/onEntered 是按
            // positionInRoot 做命中测试的 —— 位置错一点就会发给别的框）。
            simulateDrag(app, 0, DRAG_FILE_X_DP + DRAG_W_DP / 2, DRAG_FILE_Y_DP + DRAG_H_DP / 2, kind = 0)
        }
        if (frame == 112) {
            // Enter 阶段只做 accept：`onStarted` 会到目标，`onEntered` **还不会** ——
            // 上游把「进入」的判定放在 onMoved 里（DragAndDropNode.onMoved 做命中测试，
            // 首次进入由 dispatchEntered 补发 onEntered + onMoved）。所以断言要按
            // 真实的回调顺序分帧，不能指望 Enter 就把 onEntered 送来。
            report.check(
                "window/winmsg-drag-enter-accept-and-payload",
                probe.dragFileProbe.starts == 1 &&
                    probe.dragFileProbe.enters == 0 &&
                    probe.dragFileProbe.lastFiles == DROP_TEST_FILES &&
                    probe.dragFileProbe.lastText == null &&
                    probe.dragTextProbe.total() == 0,
                "文件探针 starts=${probe.dragFileProbe.starts} enters=${probe.dragFileProbe.enters} " +
                    "files=${probe.dragFileProbe.lastFiles} text=${probe.dragFileProbe.lastText} " +
                    "（文本探针应当一个事件都没收到：shouldStartDragAndDrop 拒了这次会话）",
            )
            report.check(
                "window/winmsg-drag-enter-accepts",
                app.window.lastDropEffect == Win32Message.DROPEFFECT_COPY,
                "OLE effect=${app.window.lastDropEffect}（期望 COPY=${Win32Message.DROPEFFECT_COPY}）",
            )
            simulateDrag(app, 1, DRAG_FILE_X_DP + DRAG_W_DP / 2, DRAG_FILE_Y_DP + DRAG_H_DP / 2, kind = 0)
        }
        if (frame == 114) {
            report.check(
                "window/winmsg-drag-over-enters-target",
                probe.dragFileProbe.enters == 1 && probe.dragFileProbe.moves >= 1 &&
                    app.window.lastDropEffect == Win32Message.DROPEFFECT_COPY,
                "enters=${probe.dragFileProbe.enters} moves=${probe.dragFileProbe.moves} " +
                    "effect=${app.window.lastDropEffect}（DragOver 应当触发 dispatchEntered " +
                    "-> onEntered + onMoved）",
            )
            report.check(
                "window/winmsg-drag-position-in-root",
                probe.dragFileProbe.lastPosition ==
                    Offset(
                        ((DRAG_FILE_X_DP + DRAG_W_DP / 2) * app.window.dpiScale),
                        ((DRAG_FILE_Y_DP + DRAG_H_DP / 2 + CHROME_DP) * app.window.dpiScale),
                    ),
                "命中测试用的 positionInRoot=${probe.dragFileProbe.lastPosition}",
            )
            simulateDrag(app, 3, DRAG_FILE_X_DP + DRAG_W_DP / 2, DRAG_FILE_Y_DP + DRAG_H_DP / 2, kind = 0)
        }
        if (frame == 116) {
            report.check(
                "window/winmsg-drag-drop-delivers-files",
                probe.dragFileProbe.drops == 1 && probe.dragFileProbe.ends == 1 &&
                    probe.dragFileProbe.lastFiles == DROP_TEST_FILES,
                "drops=${probe.dragFileProbe.drops} ends=${probe.dragFileProbe.ends} " +
                    "files=${probe.dragFileProbe.lastFiles}",
            )
            // Drop 的 effect 就是这次拖放操作的结果：接受了就是 COPY（不是 NONE）——
            // 「拒绝」的情形要另看（见下面 effect-writeback 那条）。
            report.check(
                "window/winmsg-drag-drop-effect-copy",
                app.window.lastDropEffect == Win32Message.DROPEFFECT_COPY,
                "放下之后 effect=${app.window.lastDropEffect}（期望 COPY：这次放下被接受了）",
            )
            // 会话已经结束（Kotlin 侧 onDrop/onEnded 走完 -> setDropAccept(false)）。
            // 这时再来一次 DragOver：宿主必须回 NONE。
            //
            // 这条是**唯一**能观测到「Kotlin 判定 -> C++ effect」这条写回链的断言：
            // 没有它的话，"光标一直显示可放下" 这种 bug 在自动化里是看不见的
            //（DragEnter 是乐观接受，所以 enter/over/drop 全都是 COPY）。
            simulateDrag(app, 1, DRAG_FILE_X_DP + DRAG_W_DP / 2, DRAG_FILE_Y_DP + DRAG_H_DP / 2, kind = 0)
        }
        if (frame == 118) {
            report.check(
                "window/winmsg-drag-effect-writeback",
                app.window.lastDropEffect == Win32Message.DROPEFFECT_NONE,
                "Kotlin 判定 false 之后，宿主回的 effect=${app.window.lastDropEffect}" +
                    "（期望 NONE；恒为 COPY 就是光标一直显示「可放下」的 bug）",
            )
            // 文本拖放（另一个框，只收文本）
            simulateDrag(app, 0, DRAG_TEXT_X_DP + DRAG_W_DP / 2, DRAG_TEXT_Y_DP + DRAG_H_DP / 2, kind = 1)
        }
        if (frame == 120) {
            report.check(
                "window/winmsg-drag-text-accepted",
                probe.dragTextProbe.starts == 1 && probe.dragTextProbe.lastText == DROP_TEST_TEXT &&
                    probe.dragTextProbe.lastFiles.isEmpty() &&
                    probe.dragFileProbe.starts == 1,
                "文本探针 starts=${probe.dragTextProbe.starts} text=${probe.dragTextProbe.lastText} " +
                    "文件探针 starts=${probe.dragFileProbe.starts}（应当还是 1：文本会话里文件框被拒）",
            )
            simulateDrag(app, 1, DRAG_TEXT_X_DP + DRAG_W_DP / 2, DRAG_TEXT_Y_DP + DRAG_H_DP / 2, kind = 1)
        }
        if (frame == 122) {
            report.check(
                "window/winmsg-drag-text-enters-target",
                probe.dragTextProbe.enters == 1 && probe.dragTextProbe.moves >= 1,
                "enters=${probe.dragTextProbe.enters} moves=${probe.dragTextProbe.moves}",
            )
            simulateDrag(app, 2, DRAG_TEXT_X_DP + DRAG_W_DP / 2, DRAG_TEXT_Y_DP + DRAG_H_DP / 2, kind = 1)
        }
        if (frame == 124) {
            report.check(
                "window/winmsg-drag-leave-ends-session",
                probe.dragTextProbe.exits == 1 && probe.dragTextProbe.ends == 1 &&
                    app.window.lastDropEffect == Win32Message.DROPEFFECT_NONE,
                "exits=${probe.dragTextProbe.exits} ends=${probe.dragTextProbe.ends} " +
                    "effect=${app.window.lastDropEffect}",
            )
        }

        // ---- 富文本剪贴板（HANDOVER §17.34）----
        //
        // 走的是**真实的 Compose API**：LocalClipboardManager.setClip(ClipEntry.withXxx(...))
        // -> 共享的 PlatformClipboard（overlay）-> skiko 的剪贴板 shim -> Win32 桥
        // （CF_HTML / "Rich Text Format" / CF_DIBV5 + CF_DIB）-> 再读回来。
        if (frame == 126) {
            val cm = probe.clipboardManager
            report.check("window/clipboard-rich-manager", cm != null)
            if (cm != null) {
                cm.setClip(ClipEntry.withHtml(CLIP_HTML_FRAGMENT, plainText = CLIP_PLAIN))
                // 原始字节留到下一帧用**独立逻辑**解析（不是拿自己的读函数读回来）。
                rawHtmlHex = app.window.clipboardGetRawHex("HTML Format")
            }
        }
        if (frame == 128) {
            val entry = probe.clipboardManager?.getClip()
            report.checkEquals("window/clipboard-html-roundtrip", CLIP_HTML_FRAGMENT, entry?.getHtml())
            report.checkEquals("window/clipboard-html-plaintext-fallback", CLIP_PLAIN, entry?.getPlainText())
            report.check(
                "window/clipboard-html-single-transaction",
                // 文本 + HTML 必须来自**同一次**写入：分两次写会把前一次擦掉。
                entry?.getHtml() == CLIP_HTML_FRAGMENT && entry?.getPlainText() == CLIP_PLAIN,
                "HTML=${entry?.getHtml()?.take(24)} 纯文本=${entry?.getPlainText()}",
            )
            probe.clipboardManager?.setClip(ClipEntry.withRtf(CLIP_RTF))
        }
        if (frame == 130) {
            // 独立解析 CF_HTML 头：偏移是**字节**偏移（片段里有中文和 ✔，算错就露馅）。
            val bytes = rawHtmlHex?.let { hexToBytes(it) }
            val text = bytes?.decodeToString()
            val expectedFragment = CLIP_HTML_FRAGMENT.encodeToByteArray()
            val startFragment = text?.let { parseDecimalAfter(it, "StartFragment:") } ?: -1
            val endFragment = text?.let { parseDecimalAfter(it, "EndFragment:") } ?: -1
            val startHtml = text?.let { parseDecimalAfter(it, "StartHTML:") } ?: -1
            val endHtml = text?.let { parseDecimalAfter(it, "EndHTML:") } ?: -1
            val fragmentSlice = if (bytes != null && startFragment >= 0 && endFragment in (startFragment + 1)..bytes.size) {
                bytes.copyOfRange(startFragment, endFragment)
            } else {
                null
            }
            // StartHTML 指的是**头部之后**那个 <html> 的字节偏移（头本身也在计数里），
            // 所以不是 0；EndHTML 才是整块的长度。
            val htmlSlice = if (bytes != null && startHtml in 0 until endHtml && endHtml <= bytes.size) {
                bytes.copyOfRange(startHtml, endHtml).decodeToString()
            } else {
                null
            }
            report.check(
                "window/clipboard-html-cf-header",
                bytes != null && text != null &&
                    text.startsWith("Version:0.9") &&
                    startHtml > 0 && startHtml < startFragment &&
                    endFragment < endHtml && endHtml == bytes.size &&
                    htmlSlice != null && htmlSlice.startsWith("<html>") && htmlSlice.endsWith("</html>") &&
                    fragmentSlice != null && fragmentSlice.contentEquals(expectedFragment),
                "Version/StartHTML=$startHtml/EndHTML=$endHtml/StartFragment=$startFragment/" +
                    "EndFragment=$endFragment 共 ${bytes?.size} 字节；" +
                    "偏移切片与片段一致=${fragmentSlice?.contentEquals(expectedFragment)}",
            )
            val entry = probe.clipboardManager?.getClip()
            report.checkEquals("window/clipboard-rtf-roundtrip", CLIP_RTF, entry?.getRtf())
            report.check(
                "window/clipboard-rtf-has-no-html",
                entry?.getHtml() == null && entry?.getRtf() == CLIP_RTF,
                "只写 RTF 时不该读出 HTML（getHtml=${entry?.getHtml()}）",
            )
            probe.clipboardManager?.setClip(ClipEntry.withImage(clipboardTestImage()))
        }
        if (frame == 132) {
            val image = probe.clipboardManager?.getClip()?.getImage()
            val pixels = image?.let { img ->
                IntArray(img.width * img.height).also { img.readPixels(it) }
            }
            report.check(
                "window/clipboard-image-roundtrip",
                image != null && image.width == 2 && image.height == 2 &&
                    pixels != null && pixels.contentEquals(CLIP_IMAGE_EXPECTED_ARGB),
                "读回的位图 ${image?.width}x${image?.height} 像素=" +
                    pixels?.joinToString { "0x${it.toUInt().toString(16)}" } +
                    "（期望 0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff —— " +
                    "顺序错=行序翻转，通道错=BGRA/ARGB 搞反）",
            )
            rawV5Hex = app.window.clipboardGetRawHex("#17")
            rawDibHex = app.window.clipboardGetRawHex("#8")
        }
        if (frame == 134) {
            // CF_DIBV5 头：124 字节、2x2、32bpp、BI_BITFIELDS(3)、biHeight>0（自下而上）。
            val v5 = rawV5Hex?.let { hexToBytes(it) }
            val v5Header = v5?.let { readIntLe(it, 0) to readIntLe(it, 8) }
            val v5Bits = v5?.let { readShortLe(it, 14) }
            val v5Compression = v5?.let { readIntLe(it, 16) }
            // 传统 CF_DIB 也必须同时存在（老程序只认它），40 字节头、32bpp。
            val dib = rawDibHex?.let { hexToBytes(it) }
            val dibHeader = dib?.let { readIntLe(it, 0) }
            val dibBits = dib?.let { readShortLe(it, 14) }
            report.check(
                "window/clipboard-image-dib-structure",
                v5 != null && v5.size >= 124 && v5Header?.first == 124 &&
                    v5Header.second == 2 && v5Bits == 32 && v5Compression == 3 &&
                    dib != null && dib.size >= 40 && dibHeader == 40 && dibBits == 32,
                "CF_DIBV5 头=${v5Header?.first} 高=${v5Header?.second} ${v5Bits}bpp " +
                    "compression=$v5Compression；CF_DIB 头=$dibHeader ${dibBits}bpp",
            )
        }
        // ---- 剪贴板：文件列表（CF_HDROP）与图片的其它来源（CF_BITMAP / 8bpp 调色板）----
        if (frame == 136) {
            // 资源管理器里 Ctrl+C 一个文件，剪贴板上是 CF_HDROP（路径列表），没有位图。
            app.window.clipboardTestSetFiles(DROP_TEST_FILES)
        }
        if (frame == 138) {
            val entry = probe.clipboardManager?.getClip()
            report.check(
                "window/clipboard-files-from-cf-hdrop",
                entry?.getFiles() == DROP_TEST_FILES,
                "CF_HDROP -> files=${entry?.getFiles()}（期望 $DROP_TEST_FILES）",
            )
            // 只有 CF_HDROP 时读图必须失败 —— 顺便触发 C 侧的「当前可用格式」诊断日志
            // （脚本会断言那一行出现），真机上"截图了但粘不进"就靠它定位。
            report.check(
                "window/clipboard-image-missing-on-file-drop",
                entry?.getImage() == null,
                "只有 CF_HDROP 时 getImage()=${entry?.getImage()}（期望 null）",
            )
            // 有的截图工具只放裸 HBITMAP：走 CF_BITMAP 回退（GDI 转一遍）。
            app.window.clipboardTestSetBitmap(clipboardTestClipboardImage())
        }
        if (frame == 140) {
            val image = probe.clipboardManager?.getClip()?.getImage()
            val pixels = image?.let { img -> IntArray(img.width * img.height).also { img.readPixels(it) } }
            report.check(
                "window/clipboard-image-from-cf-bitmap",
                image != null && image.width == 2 && image.height == 2 &&
                    pixels != null && pixels.contentEquals(CLIP_IMAGE_EXPECTED_ARGB),
                "CF_BITMAP 读回 ${image?.width}x${image?.height} 像素=" +
                    pixels?.joinToString { "0x${it.toUInt().toString(16)}" },
            )
            // 老程序/256 色画图会给 8bpp 调色板 DIB（固定 4 色：红/绿/蓝/白）。
            app.window.clipboardTestSetDib8(2, 2, byteArrayOf(0, 1, 2, 3))
        }
        if (frame == 142) {
            val image = probe.clipboardManager?.getClip()?.getImage()
            val pixels = image?.let { img -> IntArray(img.width * img.height).also { img.readPixels(it) } }
            report.check(
                "window/clipboard-image-from-dib8-palette",
                image != null && image.width == 2 && image.height == 2 &&
                    pixels != null && pixels.contentEquals(CLIP_IMAGE_EXPECTED_ARGB),
                "8bpp 调色板 DIB 读回 ${image?.width}x${image?.height} 像素=" +
                    pixels?.joinToString { "0x${it.toUInt().toString(16)}" },
            )
        }
        if (frame == 144) {
            // 直接喂原始 DIB 字节给解码器（绕开剪贴板 —— Wine 对 8bpp 的 CF_DIB 会做有损
            // 转换，合成出来的 V5 少了调色板，所以"调色板解码"这条真机上会走到的路径
            // 必须这样测）。三种真实世界形态：
            //   1) 8bpp + 4 色调色板（标准自下而上）；
            //   2) 8bpp 但 biClrUsed=0、缓冲区里只有 4 项（头撒谎，按实际裁剪）；
            //   3) 24bpp + 自上而下（biHeight<0）。
            val indices = byteArrayOf(0, 1, 2, 3)
            val standard = app.window.testDecodeDib(dib8WithPalette(2, 2, indices, clrUsed = 4))
            val lying = app.window.testDecodeDib(dib8WithPalette(2, 2, indices, clrUsed = 0))
            // ClipboardImage 里就是 BGRA、自上而下 —— 直接和期望字节比，
            // 不用再绕一圈 ImageBitmap。
            val standardPixels = standard?.pixels
            val lyingPixels = lying?.pixels
            report.check(
                "window/clipboard-dib8-decoder-standard-palette",
                standard != null && standard.width == 2 && standard.height == 2 &&
                    standardPixels != null && standardPixels.contentEquals(CLIP_IMAGE_BGRA),
                "8bpp+4 色调色板 -> ${standard?.width}x${standard?.height} 像素=" +
                    standardPixels?.joinToString { "0x${it.toUInt().toString(16)}" },
            )
            report.check(
                "window/clipboard-dib8-decoder-clrused-lies",
                lying != null && lyingPixels != null &&
                    lyingPixels.contentEquals(CLIP_IMAGE_BGRA),
                "biClrUsed=0（声称 256 项）但只给了 4 项 -> 像素=" +
                    lyingPixels?.joinToString { "0x${it.toUInt().toString(16)}" },
            )
            // 24bpp 自上而下：自己拼字节（每行按 4 字节对齐）
            val stride24 = 8   // (2*3+3)/4*4 = 8
            val dib24 = ByteArray(40 + stride24 * 2)
            fun putInt24(off: Int, value: Int) {
                dib24[off] = value.toByte()
                dib24[off + 1] = (value ushr 8).toByte()
                dib24[off + 2] = (value ushr 16).toByte()
                dib24[off + 3] = (value ushr 24).toByte()
            }
            putInt24(0, 40); putInt24(4, 2); putInt24(8, -2)   // 负数 = 自上而下
            dib24[12] = 1; dib24[14] = 24; putInt24(16, 0); putInt24(20, stride24 * 2)
            // 自上而下：第 0 行 = 红、绿；第 1 行 = 蓝、白（BGR 顺序）。
            // ⚠ 24bpp 每行要按 4 字节对齐：2 像素 = 6 字节 + 2 字节填充，所以这里写的是
            // **带填充的整行**（少写这两个 0 会把第二行整体错 2 字节，读出来就串色了）。
            val bgr24 = byteArrayOf(
                0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0x00, 0x00,
                0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00,
            )
            bgr24.copyInto(dib24, 40)
            val topDown = app.window.testDecodeDib(dib24)
            val topDownPixels = topDown?.pixels
            report.check(
                "window/clipboard-dib24-decoder-topdown",
                topDown != null && topDownPixels != null &&
                    topDownPixels.contentEquals(CLIP_IMAGE_BGRA),
                "24bpp 自上而下 -> 像素=" +
                    topDownPixels?.joinToString { "0x${it.toUInt().toString(16)}" },
            )
        }
        if (frame == 146) {
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
    assertWindowPerfReport(report, perf, guard, perfContractChecks)
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
/** IntArray 的 toString() 在 K/N 上是 `kotlin.IntArray@1a2b` 这种没用的东西，日志里要自己格式化。 */
private fun fmtRect(rect: IntArray?): String = rect?.contentToString() ?: "null"

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
    perfContractChecks: Boolean,
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
    // 后台线程的一次状态写入必须被画出来（消息泵被唤醒），且只画这一帧。
    // 动画必须持续跑（没被节流卡死），又必须被节流（不是无节制重绘）。
    //
    // ⚠ `COMPOSEKN_SELFTEST=all`（离屏 logic 阶段先在这个进程里跑过）时**这三条不判定**：
    //   实测窗口阶段的「后台写状态 -> 唤醒消息泵」链路在这个进程里已经不再驱动帧
    //   （三个子阶段全是 0 帧，见 HANDOVER §17.17），perf 契约只在独占进程的 window
    //   阶段才有意义。渲染本身仍被断言（frame-count / wheel-scroll / 逐帧绘制等），
    //   这里只是不拿这份**在这个模式下无效**的数字判 PASS/FAIL。
    val animFps = perf.animFps()
    if (perfContractChecks) {
        report.check(
            "window/perf-idle-no-busy-render",
            perf.idleFrames in 0..5,
            "静止 ${perf.idleNanos / 1_000_000}ms 渲染了 ${perf.idleFrames} 帧" +
                "（${fmt1(perf.idleFps())} fps，期望 ≈ 0）",
        )
        report.check(
            "window/perf-cross-thread-wake",
            perf.wakeFrames in 1..5,
            "跨线程刷新渲染了 ${perf.wakeFrames} 帧（期望 1..5；0 = 没唤醒，过多 = 忙等）",
        )
        report.check(
            "window/perf-animation-fps",
            animFps >= 20.0 && animFps <= 120.0,
            "动画 ${perf.animNanos / 1_000_000}ms 渲染 ${perf.animFrames} 帧" +
                " = ${fmt1(animFps)} fps（期望接近刷新率；老代码是无节制重绘）",
        )
    } else {
        report.info(
            "window/perf-contract(skipped: all 模式下性能数据无效) 静止=${perf.idleFrames} 帧 " +
                "跨线程刷新=${perf.wakeFrames} 帧 动画=${perf.animFrames} 帧/${fmt1(animFps)} fps",
        )
    }
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

/**
 * 投递一条**真实**的 Win32 鼠标消息（`PostMessage` -> 主循环 -> 真实 wndproc 分支）。
 *
 * 与 [click] / [wheel] 这些合成工具的根本区别：合成工具把 `WindowsEvent` 直接喂给
 * Compose，**跳过了 C++ 宿主层**（wndproc 的参数解码 / 坐标换算 / 消息过滤）。
 * 这一条会走完整链路，所以能覆盖宿主本身（HANDOVER §17.31）。
 *
 * 坐标是**窗口**逻辑坐标（dp，含 CSD 标题栏偏移），这里按当前 dpiScale 换成物理像素。
 */
private fun postRealMouse(
    app: WindowsComposeApplication,
    message: Int,
    xDp: Float,
    yDp: Float,
    wheelDelta: Int = 0,
): Boolean {
    val scale = app.window.dpiScale
    return app.window.postTestMouseMessage(
        message = message,
        x = (xDp * scale).toInt(),
        y = (yDp * scale).toInt(),
        wheelDelta = wheelDelta,
    )
}

/**
 * 自检用：直接驱动窗口上注册的 OLE `IDropTarget`（C 侧会构造一个真的 IDataObject）。
 *
 * phase: 0=DragEnter 1=DragOver 2=DragLeave 3=Drop；kind: 0=文件 1=文本。
 *
 * 坐标是**内容**坐标（dp，不含 CSD 标题栏），这里换算成窗口客户区物理像素 ——
 * 和真机上 OLE 给的 POINTL（屏幕坐标 -> ScreenToClient）走的是同一条路。
 */
private fun simulateDrag(
    app: WindowsComposeApplication,
    phase: Int,
    contentXDp: Int,
    contentYDp: Int,
    kind: Int,
): Boolean {
    val scale = app.window.dpiScale
    return app.window.testSimulateDrag(
        phase = phase,
        x = (contentXDp * scale).toInt(),
        y = ((contentYDp + CHROME_DP) * scale).toInt(),
        kind = kind,
    )
}

/** hex -> 字节（自检里独立解析剪贴板原始数据用；K/N 上没有 Character.digit）。 */
private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i ->
        ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
    }

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> error("非法 hex 字符: $c")
}

/** 从 "StartFragment:" 之后解析十进制（解析不到返回 -1）。 */
private fun parseDecimalAfter(text: String, key: String): Int {
    val at = text.indexOf(key)
    if (at < 0) return -1
    var i = at + key.length
    var value = 0
    var digits = 0
    while (i < text.length && text[i].isDigit()) {
        value = value * 10 + (text[i] - '0')
        i++
        digits++
    }
    return if (digits == 0) -1 else value
}

/** 小端读 4 字节（剪贴板位图头是 little-endian）。 */
private fun readIntLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

/** 小端读 2 字节。 */
private fun readShortLe(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/** 投递一条**真实**的 Win32 键盘/字符消息（同 [postRealMouse] 的说明）。 */
private fun postRealKey(
    app: WindowsComposeApplication,
    message: Int,
    vkOrChar: Int,
    scanCode: Int = 0,
): Boolean =
    app.window.postTestKeyMessage(message = message, vkOrChar = vkOrChar, scanCode = scanCode)

/**
 * 注入一次**双指**手势：两根手指各自 down -> N 步 move -> up。
 *
 * 为什么必须按"每根手指一条事件、交替推进"来发：宿主的触点表要求每次事件带上全部
 * 活动触点（Kotlin 侧 `WindowsInputState.updateTouch` 就是这么组装的），而
 * `detectTransformGestures` 需要看到两根手指的**位置都在变**才算 zoom。
 */
private fun twoFingerGesture(
    app: WindowsComposeApplication,
    driver: com.composekn.windows.test.OffscreenDriver,
    density: Float,
    start1: Pair<Float, Float>,
    end1: Pair<Float, Float>,
    start2: Pair<Float, Float>,
    end2: Pair<Float, Float>,
    steps: Int = 6,
) {
    fun touch(id: Long, p: Pair<Float, Float>, phase: TouchPhase) {
        app.dispatchEvent(
            WindowsEvent.TouchEvent(
                pointerId = id,
                x = (p.first * density).toInt(),
                y = (p.second * density).toInt(),
                phase = phase,
            ),
        )
    }
    touch(7L, start1, TouchPhase.Down)
    touch(8L, start2, TouchPhase.Down)
    driver.render(800, 600, density = density, frames = 2)
    for (i in 1..steps) {
        val t = i.toFloat() / steps
        touch(
            7L,
            Pair(start1.first + (end1.first - start1.first) * t, start1.second + (end1.second - start1.second) * t),
            TouchPhase.Move,
        )
        touch(
            8L,
            Pair(start2.first + (end2.first - start2.first) * t, start2.second + (end2.second - start2.second) * t),
            TouchPhase.Move,
        )
        driver.render(800, 600, density = density, frames = 2)
    }
    touch(7L, end1, TouchPhase.Up)
    touch(8L, end2, TouchPhase.Up)
    driver.render(800, 600, density = density, frames = 4)
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
        WindowsEvent.MouseWheelEvent(x = x, y = y, deltaX = 0f, deltaY = deltaY.toFloat()),
    )
    app.pumpDispatchers()
}

/**
 * 滚轮事件的可控版本：竖直 / Shift+竖直 / 横向（`WM_MOUSEHWHEEL`）。
 *
 * `shift = true` 时模拟「按住 Shift 滚滚轮」—— 宿主必须把它翻写成横向 delta
 * （对齐上游 `ComposeSceneMediator.desktop.kt: onMouseWheelEvent`，HANDOVER §17.30）。
 *
 * `deltaX`/`deltaY` 是**浮点**的「格」（1 格 = `WIN32_WHEEL_DELTA` = 120）：
 * 触控板/自由滚轮送来的 zDelta 不保证是 120 的倍数，换算后就是 0.333 这种值
 * （HANDOVER §17.32）。
 */
private fun wheelEvent(
    app: WindowsComposeApplication,
    x: Int,
    y: Int,
    deltaX: Float = 0f,
    deltaY: Float = 0f,
    shift: Boolean = false,
) {
    app.dispatchEvent(
        WindowsEvent.MouseWheelEvent(
            x = x,
            y = y,
            deltaX = deltaX,
            deltaY = deltaY,
            isShiftPressed = shift,
        ),
    )
    app.pumpDispatchers()
}

/** 同样轨迹的鼠标拖拽（对照组：按设计**不应该**滚动）。 */
private fun mouseDrag(app: WindowsComposeApplication, x: Int, fromY: Int, toY: Int, steps: Int = 6) {
    app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = x, y = fromY))
    app.dispatchEvent(
        WindowsEvent.MouseButtonEvent(x = x, y = fromY, button = MouseButton.Left, isPressed = true),
    )
    for (i in 1..steps) {
        val y = fromY + ((toY - fromY).toFloat() * i / steps).toInt()
        app.dispatchEvent(WindowsEvent.MouseMoveEvent(x = x, y = y))
        app.pumpDispatchers()
    }
    app.dispatchEvent(
        WindowsEvent.MouseButtonEvent(x = x, y = toY, button = MouseButton.Left, isPressed = false),
    )
    app.pumpDispatchers()
}

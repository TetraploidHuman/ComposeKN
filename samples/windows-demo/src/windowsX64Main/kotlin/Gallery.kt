@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FileDialog
import androidx.compose.ui.window.FileDialogFilter
import androidx.compose.ui.window.FileDialogMode
import com.composekn.windows.TaskbarProgressState
import com.composekn.windows.WindowsComposeWindow
import kotlin.concurrent.Volatile
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import com.composekn.windows.internal.winlog
import androidx.compose.runtime.withFrameNanos
import org.jetbrains.skiko.ComposeKNFileDialog

/**
 * 组件画廊的「观测点」。
 *
 * 所有交互控件都会把自己的状态写进这里，于是 `--selftest` 可以在派发合成事件之后
 * 直接断言「界面状态确实变了」，而不是靠人眼看窗口。
 */
class GalleryProbe {
    var clickCount by mutableStateOf(0)
    var tonalClicks by mutableStateOf(0)
    var outlinedClicks by mutableStateOf(0)
    var textButtonClicks by mutableStateOf(0)
    var iconButtonClicks by mutableStateOf(0)
    var text by mutableStateOf("")
    var textFieldFocused by mutableStateOf(false)
    var checkbox by mutableStateOf(false)
    var switchOn by mutableStateOf(false)
    var slider by mutableStateOf(0.25f)
    var radio by mutableStateOf(0)
    var dropdownOpen by mutableStateOf(false)
    var dropdownSelection by mutableStateOf("(none)")
    var dialogOpen by mutableStateOf(false)
    var darkTheme by mutableStateOf(false)
    var scrollY by mutableStateOf(0)
    var lazyScrollY by mutableStateOf(0)
    /** hover 探针盒：被鼠标悬停进入的次数（触摸**不会**让它增加，见 [HoverBox]）。 */
    var hoverCount by mutableStateOf(0)

    /**
     * hover 探针盒：被点击的次数（鼠标、触摸都算）。
     *
     * 触摸屏上这是**唯一**能看到反馈的通道：Compose 的 hover（Enter/Exit）在 skiko 里
     * 只对 `PointerType.Mouse` 合成，触摸永远没有 hover
     * —— 所以控件不能只把可见变化挂在 hoverable 上。
     */
    var hoverBoxClicks by mutableStateOf(0)

    /** 多点触摸：捏合缩放的累乘结果（两张手指张开 -> 变大，捏合 -> 变小）。 */
    var pinchScale by mutableStateOf(1f)

    /** 滚动/缩放诊断日志的上一次时间戳（ms），~20Hz 节流用（普通字段，不触发重组）。 */
    @Volatile
    var lastStateLogMs: Long = 0L
    var frames by mutableStateOf(0)
    var clipboardText by mutableStateOf("")

    /** 原生 MenuBar 最近一次点选（File/Edit 探针）。 */
    var menuAction by mutableStateOf("(none)")

    /**
     * 「重组到底发生在哪个作用域」的诊断计数（性能日志用）。
     *
     * 全部是普通字段（**不是** Compose state）：计数器本身绝不能触发失效，否则测量
     * 就自我污染了。用 `@Volatile` 是因为性能日志可能从别的线程读。
     *
     * 动画每帧 `frames++` 时，预期只有**最内层**那个读了 `frames` 的作用域重组：
     *
     *  - [galleryComposes]：`ComponentGallery` 根作用域（**不该涨**）
     *  - [hudComposes]：`DiagnosticsHud` 函数体（**不该涨** —— 说明失效没有往上冒）
     *  - [hudInnerComposes]：HUD 里 `BoxWithConstraints` 的 content lambda
     *    （真正读 `frames` 的最小作用域，按帧率增长）
     *  - [summaryCalls]：`summary()` 被调用的次数（和 hudInner 同步增长时，
     *    说明每帧重算的确实只有那一行文本）
     *
     * 这四个数放在一起就能回答「是不是全局重组」：如果 gallery/hud 跟着涨，才是真出问题。
     */
    @Volatile
    var galleryComposes = 0

    @Volatile
    var hudComposes = 0

    @Volatile
    var hudInnerComposes = 0

    @Volatile
    var summaryCalls = 0

    @Volatile
    var animTicks = 0

    /** 供 HUD 显示的一行摘要，也是像素无关的断言点。 */
    fun summary(): String {
        summaryCalls++
        return summaryImpl()
    }

    private fun summaryImpl(): String =
        "clicks=$clickCount text='$text' check=$checkbox switch=$switchOn " +
            "slider=${(slider * 100).toInt()} radio=$radio theme=${if (darkTheme) "dark" else "light"} " +
            "scroll=$scrollY lazy=$lazyScrollY frames=$frames menu='$menuAction'"

    fun resetInteractionCounters() {
        clickCount = 0
        tonalClicks = 0
        outlinedClicks = 0
        textButtonClicks = 0
        iconButtonClicks = 0
        hoverCount = 0
        hoverBoxClicks = 0
    }
}

/**
 * ComposeKN Windows 组件画廊。
 *
 * 目标不是"好看"，而是**尽可能多地走一遍 Compose 的渲染/排版/输入路径**：
 * 文本（含长文本换行与省略号）、按钮四种、绘制、滚动（普通滚动 + Lazy 虚拟化）、
 * 复选/开关/单选/滑杆、进度条、弹层（DropdownMenu / AlertDialog）、
 * 主题切换、hover 交互、动画（无限动画 + 每帧重组计数）。
 */
@Composable
fun ComponentGallery(
    probe: GalleryProbe,
    window: WindowsComposeWindow,
    /**
     * 是否让画廊里**真实的**无限动画（不确定进度圈）跑起来。
     *
     * `--no-animate` 会把 demo 自己那个「每帧 +1」计数器关掉；如果这里的进度圈还在转，
     * 「空闲对照」就没法验证了（它会一直持有帧时钟 awaiter → 宿主一直按刷新率重绘，
     * 表现为 frames 在涨但 recompose 全 0 —— 真机日志里踩过这个坑）。
     * 所以 `--no-animate` 时把它换成确定态（`progress = { 0.5f }`），做到真正静止。
     */
    animate: Boolean = true,
    /** 打开第二扇窗（application { if (open2) Window(...) }）。 */
    onOpenSecondWindow: (() -> Unit)? = null,
    /** 打开 DialogWindow（软模态对话框）。 */
    onOpenDialogWindow: (() -> Unit)? = null,
) {
    probe.galleryComposes++
    MaterialTheme(
        colorScheme = if (probe.darkTheme) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 根用 LazyColumn 而不是 Column(verticalScroll)：
            // 1) 纵向列表项拿到的约束是「主轴无界」的，再往里塞纵向滚动容器会抛
            //    "Vertically scrollable component was measured with an infinity maximum
            //     height constraints"（内层只能用显式 .height(...) 的滚动容器）；
            // 2) 顺带验证 Lazy 虚拟化 + 滚轮滚动。
            val outerState = rememberLazyListState()
            val innerState = rememberLazyListState()

            // 诊断：把「外层/内层滚动位置 + 缩放值」写进 composekn-startup.log（~20Hz 节流）。
            //
            // 为什么需要：真机反馈「缩放之后还会跳 / 嵌套滚动有时候也跳」，但那种"跳"是
            // 肉眼看到的现象，日志里只有触摸轨迹，无法判断**是哪个滚动容器在动、动了多少**。
            // 这几行就是地面真值：跳变会表现为某个 tick 里 offset 突然变化一大截
            // （或者 firstVisibleItemIndex 突变）。
            //
            // 只在真正变化时记（静止零开销），节流到 50ms → 连续滚动时最多 20 行/秒。
            LaunchedEffect(Unit) {
                var loggedAtLeastOnce = false
                snapshotFlow {
                    listOf(
                        outerState.firstVisibleItemIndex,
                        outerState.firstVisibleItemScrollOffset,
                        innerState.firstVisibleItemIndex,
                        innerState.firstVisibleItemScrollOffset,
                        (probe.pinchScale * 100f).toInt(),
                    )
                }.collect { v ->
                    val nowMs = withFrameNanos { it } / 1_000_000L
                    // 第一条**无条件**记（基线：启动时在哪里），之后 50ms 节流。
                    // 无条件记第一条还有一个好处：自检/脚本能靠它验证"这条日志真的在写"。
                    if (!loggedAtLeastOnce || nowMs - probe.lastStateLogMs >= 50L) {
                        loggedAtLeastOnce = true
                        probe.lastStateLogMs = nowMs
                        winlog(
                            "gallery: outer=${v[0]}/${v[1]} inner=${v[2]}/${v[3]} scale=${v[4]}%",
                        )
                    }
                }
            }

            LazyColumn(state = outerState, modifier = Modifier.fillMaxSize()) {
                item { DiagnosticsHud(probe, window) }
                gallerySections(probe, animate, innerState, window, onOpenSecondWindow, onOpenDialogWindow)
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DiagnosticsHud(probe: GalleryProbe, window: WindowsComposeWindow) {
    probe.hudComposes++
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        // 这个 lambda 是「读 frames 的最小作用域」——每帧重组的就是它（性能日志里的 hudInner）。
        probe.hudInnerComposes++
        // 注意：LazyColumn 的 item 主轴约束是**无界**的，所以这里只用宽（有限），
        // 高度打印出来会是 Infinity.dp，徒增困惑。
        val availableWidth = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("ComposeKN · Windows 原生组件画廊", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "窗口 ${window.logicalWidth}x${window.logicalHeight}dp · 可用宽度 $availableWidth" +
                    " · dpi=${window.layer.contentScale}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(probe.summary(), style = MaterialTheme.typography.bodySmall)
            Text(
                text = "提示：拖动窗口边缘缩放、滚轮滚动、Tab 切换焦点、点击各控件",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun LazyListScope.gallerySections(
    probe: GalleryProbe,
    animate: Boolean,
    innerState: LazyListState,
    window: WindowsComposeWindow,
    onOpenSecondWindow: (() -> Unit)?,
    onOpenDialogWindow: (() -> Unit)?,
) {
    section("按钮 / Buttons") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { probe.clickCount++ }) {
                Text("Filled ${probe.clickCount}")
            }
            FilledTonalButton(onClick = { probe.tonalClicks++ }) {
                Text("Tonal ${probe.tonalClicks}")
            }
            OutlinedButton(onClick = { probe.outlinedClicks++ }) {
                Text("Outlined ${probe.outlinedClicks}")
            }
            TextButton(onClick = { probe.textButtonClicks++ }) {
                Text("Text ${probe.textButtonClicks}")
            }
            IconButton(onClick = { probe.iconButtonClicks++ }) {
                Canvas(Modifier.size(18.dp)) {
                    drawCircle(
                        color = Color(0xFF1B6AC9),
                        radius = size.minDimension / 2f,
                        center = center,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = size.minDimension / 4f,
                        center = center,
                    )
                }
            }
        }
    }

    if (onOpenSecondWindow != null || onOpenDialogWindow != null) {
        section("多窗口 / Multi-window") {
            if (onOpenSecondWindow != null) {
                Button(onClick = onOpenSecondWindow) {
                    Text("打开第二扇窗")
                }
            }
            if (onOpenDialogWindow != null) {
                Button(onClick = onOpenDialogWindow) {
                    Text("打开 DialogWindow")
                }
            }
            Text(
                "Desktop 对齐：application { if (open) Window/DialogWindow(...) }。" +
                    "关副窗/对话框只拆那一扇；DialogWindow 打开时会软禁用其它窗输入；" +
                    "关主窗 exitApplication 退整应用。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    section("文本 / Text") {
        Text("headlineSmall 标题", style = MaterialTheme.typography.headlineSmall)
        Text("titleMedium 小标题", style = MaterialTheme.typography.titleMedium)
        Text("bodyMedium 正文：ComposeKN 让 Compose 跑在 Kotlin/Native 上。", style = MaterialTheme.typography.bodyMedium)
        Text("labelSmall 标签", style = MaterialTheme.typography.labelSmall)
        Text(
            text = "长文本省略号测试：Compose Multiplatform 的文本排版在 Windows 原生后端上" +
                "必须正确处理换行、省略与字体回退，这一段故意写得很长以便观察是否溢出。",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "中英混排 mixed CJK & Latin 0123456789 ← → ✓ ✗",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("28sp 大字号 AaBbGg0123", fontSize = 28.sp)
    }

    section("输入 / Input") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = probe.text,
                onValueChange = { probe.text = it },
                label = { Text("单行输入（可试试中文输入法 / Ctrl+A / Tab）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = probe.clipboardText,
                onValueChange = { probe.clipboardText = it },
                label = { Text("多行输入") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = probe.checkbox, onCheckedChange = { probe.checkbox = it })
                Text("Checkbox")
                Spacer(Modifier.width(12.dp))
                Switch(checked = probe.switchOn, onCheckedChange = { probe.switchOn = it })
                Text("Switch")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = probe.radio == 0, onClick = { probe.radio = 0 })
                Text("A")
                RadioButton(selected = probe.radio == 1, onClick = { probe.radio = 1 })
                Text("B")
                RadioButton(selected = probe.radio == 2, onClick = { probe.radio = 2 })
                Text("C")
            }
            Column {
                Text("Slider = ${(probe.slider * 100).toInt()}")
                Slider(value = probe.slider, onValueChange = { probe.slider = it })
            }
        }
    }

    section("进度 / Progress") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(220.dp)) {
                Text("确定进度 ${(probe.slider * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { probe.slider },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (animate) "不确定（无限动画）" else "不确定（--no-animate 已冻结）")
                Spacer(Modifier.height(4.dp))
                if (animate) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    // progress 非空 = 确定态：不持有帧时钟 awaiter，画完就静止
                    CircularProgressIndicator(
                        progress = { 0.5f },
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("每帧重组计数")
                Text("frames=${probe.frames}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    section("卡片 / 容器") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.width(200.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Card", style = MaterialTheme.typography.titleSmall)
                    Text("圆角 + 阴影 + 背景色", style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(
                modifier = Modifier.width(200.dp),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("直角 Card", style = MaterialTheme.typography.titleSmall)
                    Text("shape = RoundedCornerShape(0)", style = MaterialTheme.typography.bodySmall)
                }
            }
            HoverBox(probe)
        }
    }

    section("剪贴板 / Clipboard") {
        ClipboardPasteBox()
        Spacer(Modifier.height(8.dp))
        ClipboardCopyFilesButton()
    }

    section("文件对话框 / FileDialog") {
        FileDialogBox(window)
    }

    section("拖放 / Drag & Drop") {
        DragAndDropDemoBox()
    }

    section("窗口 / Window") {
        WindowApiBox(window)
    }

    section("弹层 / Popup") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { probe.dropdownOpen = !probe.dropdownOpen }) {
                    Text("下拉菜单: ${probe.dropdownSelection}")
                }
                DropdownMenu(
                    expanded = probe.dropdownOpen,
                    onDismissRequest = { probe.dropdownOpen = false },
                ) {
                    listOf("Windows 原生", "Linux 原生", "JVM/Desktop").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                probe.dropdownSelection = option
                                probe.dropdownOpen = false
                            },
                        )
                    }
                }
            }
            Button(onClick = { probe.dialogOpen = true }) { Text("对话框") }
        }
        if (probe.dialogOpen) {
            AlertDialog(
                onDismissRequest = { probe.dialogOpen = false },
                title = { Text("AlertDialog") },
                text = { Text("弹层渲染在独立的 Compose layer 上，用于验证 Skiko 的多层合成。") },
                confirmButton = {
                    TextButton(onClick = { probe.dialogOpen = false }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { probe.dialogOpen = false }) { Text("取消") }
                },
            )
        }
    }

    section("多点触摸 / Pinch") {
        // 宿主侧：C 的 WM_POINTER 为每根手指各发一条事件 -> Kotlin 聚合成「多指针
        // PointerEvent」-> Compose 的手势识别。真机上用手指捏合/张开会改变 scale
        // （触摸屏；鼠标拖拽不会触发，那条由自检单独断言）。
        //
        // ⚠ **手势区域必须做大**（占满一行 + 180dp 高）。真机反馈「捏合的时候列表跟着
        // 滚、松手还往上甩」—— 根因就是原来只有 120dp 的方块：两指捏合时经常有一根手指
        // 落在方块**外面**（落到 LazyColumn 上），那根手指就照常拖动列表了。这是 Compose
        // 的语义（落在滚动区上的手指就该滚动它，Android 一样），不是宿主的问题；
        // 让两根手指都落在 transformable 里，列表就不会跟着滚（自检里有专门断言）。
        // 代价：在这块区域里**单指**拖动会被 transformable 当成 pan 消费（不会滚列表）——
        // 演示用，可接受。
        Column(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .transformable(
                        state = rememberTransformableState { zoomChange, _, _ ->
                            // 上限 3 而不是 4：方块 56dp × 3 = 168dp < 手势区 180dp，
                            // 放大到极限也不会溢出到下面的文字/其它区块上（真机反馈
                            // 「缩放之后画面跳一下」有一部分就是这个溢出）。
                            probe.pinchScale = (probe.pinchScale * zoomChange).coerceIn(0.25f, 3f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // ⚠ 缩放的方块用 **graphicsLayer 缩放**，不能写成 `.size(56 * scale).dp`：
                // 后者会让这个 item 的高度跟着变（56→224dp），把 LazyColumn 里下面的内容
                // 顶来顶去 —— 真机上看到的就是「缩放之后画面跳一下」（§17.26）。
                // graphicsLayer 只影响绘制，布局尺寸恒定 -> 缩放期间不会有任何内容位移。
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .graphicsLayer {
                            scaleX = probe.pinchScale
                            scaleY = probe.pinchScale
                        }
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("两指\n捏合", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "scale=${(probe.pinchScale * 100).toInt()}%" +
                    "（在这块区域里两指张开/捏合；单指拖动不会滚列表）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    section("列表 / 滚动") {
        Column {
            Text("LazyRow（横向虚拟化）")
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items((0 until 40).toList()) { index ->
                    Box(
                        modifier = Modifier
                            .size(64.dp, 48.dp)
                            .background(
                                Color(0xFF2F6FBF).copy(alpha = 0.35f + (index % 5) * 0.12f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$index", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("LazyColumn（纵向虚拟化，固定 160dp 高）")
            Spacer(Modifier.height(6.dp))
            LazyColumn(state = innerState, modifier = Modifier.fillMaxWidth().height(160.dp)) {
                items((0 until 60).toList()) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("第 $index 行", modifier = Modifier.width(90.dp))
                        Box(
                            modifier = Modifier
                                .height(10.dp)
                                .fillMaxWidth(0.1f + (index % 9) * 0.1f)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }

    section("绘制 / Canvas") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(120.dp)) {
                drawRect(Color(0xFFE0E0E0))
                drawCircle(Color(0xFFD32F2F), radius = size.minDimension * 0.3f, center = center)
                drawRect(
                    color = Color(0xFF1565C0),
                    topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
                    size = Size(size.width * 0.25f, size.height * 0.25f),
                    style = Stroke(width = 3f),
                )
                drawLine(
                    color = Color(0xFF2E7D32),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, 0f),
                    strokeWidth = 4f,
                )
            }
            Canvas(Modifier.size(120.dp)) {
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xFF7B1FA2), Color(0xFF00ACC1)),
                    ),
                    size = size,
                )
                val path = Path().apply {
                    moveTo(0f, size.height)
                    quadraticBezierTo(size.width * 0.5f, 0f, size.width, size.height)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.6f))
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White, Color.Transparent),
                        center = Offset(size.width * 0.7f, size.height * 0.3f),
                        radius = size.minDimension * 0.5f,
                    ),
                    radius = size.minDimension * 0.5f,
                    center = Offset(size.width * 0.7f, size.height * 0.3f),
                )
            }
            Canvas(Modifier.size(120.dp).rotate(rotateDegrees(probe.frames))) {
                drawRect(Color(0xFF455A64), size = size)
                drawRect(
                    color = Color(0xFFFFB300),
                    topLeft = Offset(size.width * 0.35f, 0f),
                    size = Size(size.width * 0.3f, size.height),
                )
            }
        }
    }

    section("主题 / Theme") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = probe.darkTheme, onCheckedChange = { probe.darkTheme = it })
            Text(if (probe.darkTheme) "深色主题" else "浅色主题")
            Spacer(Modifier.width(24.dp))
            Text("主色", color = MaterialTheme.colorScheme.primary)
            Text("次色", color = MaterialTheme.colorScheme.secondary)
            Text("错误色", color = MaterialTheme.colorScheme.error)
        }
    }

    section("布局 / Layout") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(24) { index ->
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .width((28 + (index % 7) * 10).dp)
                        .height(20.dp)
                        .background(Color(0x33FF0000 + (index * 0x00110011))),
                )
            }
        }
    }

    item {
        HorizontalDivider()
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Frame count = ${probe.frames}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * hover / 点击探针盒。
 *
 * ⚠ 触摸屏用户看不到 hover：Compose 的 hover 是 `PointerEventType.Enter/Exit`，
 * 而 skiko 只在指针类型是 `PointerType.Mouse` 时才合成这两个事件
 * （vendor/compose-core/ui/src/skikoMain/.../InternalPointerEvent.skiko.kt:
 *   `activeHoverEvent = changes[id]?.type == PointerType.Mouse`），
 * Android 上触摸 `hoverable` 同样毫无反应 —— 这是模型本身如此，不是宿主漏发。
 * （本宿主把 `PT_PEN` 也送成 `PointerType.Touch`，所以笔在悬停时同样没有 hover，
 *   见 HANDOVER §17.28 末尾那条尚未实测的隐患。）
 *
 * 所以「悬停/点击试试」这个盒子**必须**也把点击做成可见反馈，否则真机触摸用户
 * 得到的就是「点了没反应」（用户实测反馈，HANDOVER §17.28）。鼠标悬停照旧变色。
 */
/**
 * 文件对话框手动验证：命令式 open/save + 声明式 [FileDialog]。
 *
 * CI 不弹交互对话框（只断言 `ComposeKNFileDialog.available()`）；这里是给人点的。
 */
@Composable
private fun FileDialogBox(window: WindowsComposeWindow) {
    var selected by remember { mutableStateOf("尚未选择") }
    var showOpenComposable by remember { mutableStateOf(false) }
    var showSaveComposable by remember { mutableStateOf(false) }
    val filters = remember {
        listOf(
            FileDialogFilter("文本", listOf("txt", "md", "kt")),
            FileDialogFilter("所有文件", listOf("*.*")),
        )
    }

    Card(modifier = Modifier.width(520.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("文件对话框", style = MaterialTheme.typography.titleSmall)
            Text(
                "available=${ComposeKNFileDialog.available()}（comdlg32）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(onClick = {
                    val paths = window.openFileDialog(
                        title = "打开文件…",
                        multiple = true,
                        filters = filters,
                    )
                    selected = if (paths.isEmpty()) "(取消)" else paths.joinToString("\n")
                    winlog("filedialog: open -> $selected")
                }) { Text("打开文件…") }

                OutlinedButton(onClick = {
                    val path = window.saveFileDialog(
                        title = "保存文件…",
                        initialFileName = "untitled.txt",
                        filters = filters,
                    )
                    selected = path ?: "(取消)"
                    winlog("filedialog: save -> $selected")
                }) { Text("保存文件…") }

                OutlinedButton(onClick = { showOpenComposable = true }) {
                    Text("打开（Composable）")
                }
                OutlinedButton(onClick = { showSaveComposable = true }) {
                    Text("保存（Composable）")
                }
            }
            Text(
                selected,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showOpenComposable) {
        FileDialog(
            onCloseRequest = { paths ->
                showOpenComposable = false
                selected = if (paths.isEmpty()) "(取消 / Composable)" else paths.joinToString("\n")
                winlog("filedialog: composable open -> $selected")
            },
            mode = FileDialogMode.Load,
            title = "打开文件…",
            multiple = true,
            filters = filters,
            parent = null,
        )
    }
    if (showSaveComposable) {
        FileDialog(
            onCloseRequest = { paths ->
                showSaveComposable = false
                selected = paths.firstOrNull() ?: "(取消 / Composable)"
                winlog("filedialog: composable save -> $selected")
            },
            mode = FileDialogMode.Save,
            title = "保存文件…",
            initialFileName = "untitled.txt",
            filters = filters,
            parent = null,
        )
    }
}

/**
 * 窗口 API 的手动验证区：置顶 / 全屏 / 不可缩放 / 位置 / 大小 / 居中 / 任务栏进度。
 *
 * 这些东西**只能靠眼睛验**：自动化里能验风格位、尺寸、命中测试行为和"没有任务栏时
 * 老实回 false"，但"窗口是不是真的浮在别的窗口之上"、"任务栏上有没有进度条"只有真机
 * 看得出来。所以每个按钮都配一行状态文字，并且走的是和自检**同一套** API。
 */
@Composable
private fun WindowApiBox(window: WindowsComposeWindow) {
    var alwaysOnTop by remember { mutableStateOf(window.alwaysOnTop) }
    var fullscreen by remember { mutableStateOf(window.isFullscreen) }
    var resizable by remember { mutableStateOf(window.resizable) }
    var status by remember {
        mutableStateOf(
            "窗口 API：置顶/全屏/不可缩放/位置/大小/居中/任务栏进度 —— " +
                "任务栏可用=${window.taskbarSupported}",
        )
    }

    Card(modifier = Modifier.width(520.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("窗口 API", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                OutlinedButton(onClick = {
                    alwaysOnTop = !alwaysOnTop
                    window.setAlwaysOnTop(alwaysOnTop)
                    status = "置顶 = $alwaysOnTop"
                    winlog("windowapi: 置顶 -> $alwaysOnTop")
                }) { Text(if (alwaysOnTop) "取消置顶" else "置顶") }

                OutlinedButton(onClick = {
                    val target = !fullscreen
                    val ok = window.setFullscreen(target)
                    fullscreen = window.isFullscreen
                    status = if (ok) "全屏 = $fullscreen" else "全屏失败"
                    winlog("windowapi: 全屏 -> $fullscreen（返回 $ok）")
                }) { Text(if (fullscreen) "退出全屏" else "全屏") }

                OutlinedButton(onClick = {
                    resizable = !resizable
                    window.resizable = resizable
                    status = "可缩放 = $resizable（关掉后拖边框和最大化都无效）"
                    winlog("windowapi: 可缩放 -> $resizable")
                }) { Text(if (resizable) "改成不可缩放" else "改成可缩放") }

                OutlinedButton(onClick = {
                    window.centerOnScreen()
                    status = "居中 -> 位置=${window.windowPosition} 客户区=${window.windowSize}"
                    winlog("windowapi: 居中 -> ${window.windowPosition}")
                }) { Text("居中") }

                OutlinedButton(onClick = {
                    window.setWindowPosition(120, 90)
                    status = "位置 -> ${window.windowPosition}（期望 (120, 90)）"
                    winlog("windowapi: 位置 -> ${window.windowPosition}")
                }) { Text("移到 (120, 90)") }

                OutlinedButton(onClick = {
                    window.setWindowSize(700, 500)
                    status = "客户区 -> ${window.windowSize}（期望 700x500）"
                    winlog("windowapi: 客户区 -> ${window.windowSize}")
                }) { Text("大小 700x500") }

                OutlinedButton(onClick = {
                    window.setWindowSize(1100, 760)
                    status = "客户区 -> ${window.windowSize}（恢复默认）"
                    winlog("windowapi: 客户区 -> ${window.windowSize}")
                }) { Text("大小 1100x760") }

                OutlinedButton(onClick = {
                    val ok = window.setTaskbarProgress(TaskbarProgressState.Normal, 0.5)
                    status = if (ok) "任务栏进度 50%" else "这台机器没有任务栏（接口老实回了 false）"
                    winlog("windowapi: 任务栏进度 50% -> $ok")
                }) { Text("进度 50%") }

                OutlinedButton(onClick = {
                    val ok = window.setTaskbarProgress(TaskbarProgressState.Indeterminate)
                    status = if (ok) "任务栏进度：不确定" else "这台机器没有任务栏"
                    winlog("windowapi: 任务栏不确定 -> $ok")
                }) { Text("进度 不确定") }

                OutlinedButton(onClick = {
                    window.setTaskbarProgress(TaskbarProgressState.None)
                    status = "任务栏进度已清除"
                    winlog("windowapi: 任务栏进度清除")
                }) { Text("清除进度") }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/**
 * 「从剪贴板粘贴」盒子：**点一下**就把剪贴板内容读出来显示。
 *
 * 为什么用点击而不是 Ctrl+V：这个画廊里的元素没有做焦点管理，点击是"一定能用"的入口
 *（真做应用时用 `Modifier.onPreviewKeyEvent` 处理 Ctrl+V 即可，读的还是同一个
 * `ClipboardManager.getClip()`）。另外这也回答了那个常见疑问：**文本框里粘不进图片** ——
 * 文本控件的粘贴只会去要 `CF_UNICODETEXT`，图片要靠应用自己收（就是这里）。
 *
 * 走的是 v0.5.16/v0.5.17 接上的通道：
 *   * 图片：CF_DIBV5 -> CF_DIB -> CF_BITMAP 三级回退（截图工具给哪种都能读）；
 *   * 文件：CF_HDROP（资源管理器里 Ctrl+C 的文件路径列表）。
 */
@Composable
private fun ClipboardPasteBox() {
    val clipboard = LocalClipboardManager.current
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("点这里 = 从剪贴板粘贴（先 Win+Shift+S 截个图）") }

    Card(modifier = Modifier.width(360.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("剪贴板粘贴", style = MaterialTheme.typography.titleSmall)
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(220.dp)
                    .background(Color(0xFFE8E8E8))
                    .clickable {
                        val entry = clipboard.getClip()
                        val pastedImage = entry?.getImage()
                        val pastedFiles = entry?.getFiles().orEmpty()
                        val pastedText = entry?.getPlainText()
                        when {
                            pastedImage != null -> {
                                image = pastedImage
                                status = "图片 ${pastedImage.width}x${pastedImage.height}"
                            }
                            pastedFiles.isNotEmpty() -> {
                                image = null
                                status = "文件 ${pastedFiles.size} 个：${pastedFiles.first()}"
                            }
                            !pastedText.isNullOrEmpty() -> {
                                image = null
                                status = "文本：${pastedText.take(40)}"
                            }
                            else -> {
                                image = null
                                status = "剪贴板里没有图片/文件/文本"
                            }
                        }
                        winlog(
                            "paste: 读剪贴板 -> 图片=" +
                                (pastedImage?.let { "${it.width}x${it.height}" } ?: "无") +
                                " 文件=${pastedFiles.size} 文本=${pastedText?.length ?: 0}",
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                val shown = image
                if (shown != null) {
                    Image(
                        bitmap = shown,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "支持截图工具/浏览器复制的图片（CF_DIBV5/CF_DIB/CF_BITMAP）与资源管理器" +
                    "复制的文件（CF_HDROP）。注意：**文本框里粘不进图片** —— 文本控件只会" +
                    "去要纯文本，图片必须由应用自己接收（这里就是）。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 把测试路径写进剪贴板（CF_HDROP），真机可粘到资源管理器。 */
@Composable
private fun ClipboardCopyFilesButton() {
    val clipboard = LocalClipboardManager.current
    val paths = remember {
        listOf(
            """C:\composekn\gallery-copy-1.txt""",
            """C:\composekn\gallery-copy-2.txt""",
        )
    }
    var status by remember { mutableStateOf("点按钮 → ClipEntry.withFiles → 资源管理器里 Ctrl+V") }
    Column {
        OutlinedButton(
            onClick = {
                clipboard.setClip(ClipEntry.withFiles(paths, plainText = paths.joinToString("\n")))
                status = "已写入 ${paths.size} 条路径（CF_HDROP + Preferred DropEffect=COPY）"
                winlog("clipboard: withFiles -> ${paths.size} 条路径")
            },
        ) {
            Text("复制测试文件路径到剪贴板")
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 拖放演示：发出（DoDragDrop）+ 接收（IDropTarget）。
 *
 * 可把左边方块拖到资源管理器，或把资源管理器文件拖进右边落点。
 */
@Composable
private fun DragAndDropDemoBox() {
    val paths = remember {
        listOf(
            """C:\composekn\gallery-drag-1.txt""",
            """C:\composekn\gallery-drag-2.txt""",
        )
    }
    var dropStatus by remember { mutableStateOf("把文件/文本拖到右边虚线框") }
    var hovering by remember { mutableStateOf(false) }

    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) {
                hovering = true
            }
            override fun onEntered(event: DragAndDropEvent) {
                hovering = true
            }
            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }
            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                dropStatus = when {
                    event.files.isNotEmpty() ->
                        "收到文件 ${event.files.size} 个：${event.files.first()}"
                    !event.text.isNullOrEmpty() ->
                        "收到文本：${event.text!!.take(48)}"
                    else -> "放下了，但没有文件/文本"
                }
                winlog("drag: Gallery drop -> $dropStatus")
                return true
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("发出（按住拖走）", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(160.dp, 72.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .dragAndDropSource { _ ->
                        DragAndDropTransferData(
                            files = paths,
                            onTransferCompleted = { ok ->
                                winlog("drag: Source 文件拖放结束 success=$ok")
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("拖出文件", style = MaterialTheme.typography.labelLarge)
            }
            Box(
                modifier = Modifier
                    .size(160.dp, 72.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .dragAndDropSource { _ ->
                        DragAndDropTransferData(
                            text = "ComposeKN Gallery 拖出的文本",
                            onTransferCompleted = { ok ->
                                winlog("drag: Source 文本拖放结束 success=$ok")
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("拖出文本", style = MaterialTheme.typography.labelLarge)
            }
        }

        Text("接收（从资源管理器拖进来）", style = MaterialTheme.typography.titleSmall)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(
                    if (hovering) MaterialTheme.colorScheme.tertiaryContainer
                    else Color(0xFFE8E8E8),
                )
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { event ->
                        event.files.isNotEmpty() || event.text != null
                    },
                    target = dropTarget,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (hovering) "松开即可放下" else dropStatus,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text(
            "发出走 DoDragDrop；接收走 IDropTarget（与自检同一条 OLE 链）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun HoverBox(probe: GalleryProbe) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered && !entered) {
            entered = true
            probe.hoverCount++
        }
        if (!hovered) entered = false
    }
    // 诊断：把「按下/抬起/取消」也记进日志（带**节点内坐标**）。
    //
    // 为什么要坐标：v0.5.10 的真机日志里出现过「一次 645ms 的按住期间 onClick 被调 10 次」，
    // 而触摸流里只有一对 DOWN/UP —— 说明多半是另一条通道（鼠标或键盘 Enter/Space）。
    // 这条日志能把两者分开：指针路径的 `pressPosition` 是**按下点**，
    // 键盘路径用的是 `centerOffset`（控件正中心，恒等于 (w/2,h/2)）。
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> winlog("hoverbox: 按下 pos=${interaction.pressPosition}")
                is PressInteraction.Release -> winlog("hoverbox: 抬起")
                is PressInteraction.Cancel -> winlog("hoverbox: 取消（按下被吞）")
                else -> {}
            }
        }
    }
    // 悬停 / 按压 / 点过之后都保持高亮 —— 触摸用户只能靠后两者看到反馈。
    val highlight = hovered || pressed || probe.hoverBoxClicks > 0
    Box(
        modifier = Modifier
            .size(200.dp, 72.dp)
            .background(if (highlight) Color(0xFF81C784) else Color(0xFFC8E6C9))
            .hoverable(interactionSource)
            // indication 显式给 LocalIndication（而不是省略）是为了和 hoverable 共用
            // 同一个 interactionSource —— 这样 pressed 才拿得到状态；波形（涟漪）照旧。
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) {
                probe.hoverBoxClicks++
                winlog("hoverbox: 点击 clicks=${probe.hoverBoxClicks} hovered=$hovered")
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when {
                pressed -> "按下中… (clicks=${probe.hoverBoxClicks})"
                hovered -> "hover ✓ (h=${probe.hoverCount} clicks=${probe.hoverBoxClicks})"
                probe.hoverBoxClicks > 0 -> "点击 ✓ (clicks=${probe.hoverBoxClicks})"
                else -> "悬停/点击试试"
            },
            color = Color.Black,
        )
    }
}

private fun LazyListScope.section(title: String, content: @Composable () -> Unit) {
    item {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            content()
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
        }
    }
}

/**
 * 第二扇窗内容（验证多窗口共享泵：关主窗走 exitApplication，关本窗只清 open2）。
 */
@Composable
fun SecondWindowContent(onClose: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("第二扇窗", style = MaterialTheme.typography.titleLarge)
                Text(
                    "此窗与主窗共享同一条 PeekMessage 循环（WindowsApplicationHost）。" +
                        "关闭本窗不应拆掉主窗；关主窗才 exitApplication。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "v0.5.26：DialogWindow + WindowState 双向同步 / 每窗 WGL / application{}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onClose) {
                    Text("关闭本窗")
                }
            }
        }
    }
}


/**
 * DialogWindow 内容（软模态：打开时主窗输入被 EnableWindow 禁用）。
 */
@Composable
fun DialogWindowContent(onClose: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("DialogWindow", style = MaterialTheme.typography.titleLarge)
                Text(
                    "对齐 Desktop：独立顶层窗 + DocumentModal 软模态。" +
                        "打开期间其它窗不可点；关闭后恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onClose) {
                    Text("关闭对话框")
                }
            }
        }
    }
}

private fun rotateDegrees(frame: Int): Float = (frame % 360).toFloat()

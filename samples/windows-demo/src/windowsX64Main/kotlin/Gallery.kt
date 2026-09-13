@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package main

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composekn.windows.WindowsComposeWindow

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
    var hoverCount by mutableStateOf(0)
    var frames by mutableStateOf(0)
    var clipboardText by mutableStateOf("")

    /** 供 HUD 显示的一行摘要，也是像素无关的断言点。 */
    fun summary(): String =
        "clicks=$clickCount text='$text' check=$checkbox switch=$switchOn " +
            "slider=${(slider * 100).toInt()} radio=$radio theme=${if (darkTheme) "dark" else "light"} " +
            "scroll=$scrollY lazy=$lazyScrollY frames=$frames"

    fun resetInteractionCounters() {
        clickCount = 0
        tonalClicks = 0
        outlinedClicks = 0
        textButtonClicks = 0
        iconButtonClicks = 0
        hoverCount = 0
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
fun ComponentGallery(probe: GalleryProbe, window: WindowsComposeWindow) {
    MaterialTheme(
        colorScheme = if (probe.darkTheme) darkColorScheme() else lightColorScheme(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            // 根用 LazyColumn 而不是 Column(verticalScroll)：
            // 1) 纵向列表项拿到的约束是「主轴无界」的，再往里塞纵向滚动容器会抛
            //    "Vertically scrollable component was measured with an infinity maximum
            //     height constraints"（内层只能用显式 .height(...) 的滚动容器）；
            // 2) 顺带验证 Lazy 虚拟化 + 滚轮滚动。
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { DiagnosticsHud(probe, window) }
                gallerySections(probe)
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DiagnosticsHud(probe: GalleryProbe, window: WindowsComposeWindow) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
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

private fun LazyListScope.gallerySections(probe: GalleryProbe) {
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
                Text("不确定（无限动画）")
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
            LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
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

@Composable
private fun HoverBox(probe: GalleryProbe) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered && !entered) {
            entered = true
            probe.hoverCount++
        }
        if (!hovered) entered = false
    }
    Box(
        modifier = Modifier
            .size(200.dp, 72.dp)
            .background(if (hovered) Color(0xFF81C784) else Color(0xFFC8E6C9))
            .hoverable(interactionSource)
            .clickable { probe.hoverCount++ },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (hovered) "hover ✓ (${probe.hoverCount})" else "悬停/点击试试",
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

private fun rotateDegrees(frame: Int): Float = (frame % 360).toFloat()

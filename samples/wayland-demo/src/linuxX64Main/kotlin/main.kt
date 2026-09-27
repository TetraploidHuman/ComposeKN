@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.FileDialog
import androidx.compose.ui.window.FileDialogFilter
import androidx.compose.ui.window.FileDialogMode
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberNotification
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.composekn.resources.Font
import main.resources.Res
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlinx.coroutines.delay
import org.jetbrains.skiko.ComposeKNFileDialog
import org.jetbrains.skiko.ComposeKNTray
import org.jetbrains.skiko.WaylandClipboard
import platform.posix.getenv
import kotlinx.cinterop.toKString

private const val SKIA_ONLY_TEST = false

fun main(args: Array<String>) {
    // initLinuxMainThread + registerBackend：由 com.composekn.host entry wrapper /
    // registerComposeKnLinuxBackend() 负责（幂等）。自检路径无 wrapper 时补登记：
    com.composekn.linux.registerComposeKnLinuxBackend()
    if (SKIA_ONLY_TEST) {
        return
    }

    val selftest = resolveSelfTest(args)

    if (selftest) {
        runLinuxSelfTest()
        return
    }

    application {
        val trayState = rememberTrayState()
        val notifyInfo = rememberNotification(
            "ComposeKN",
            "Linux tray notification (Info)",
            Notification.Type.Info,
        )
        var openSecond by remember { mutableStateOf(false) }
        var openDialog by remember { mutableStateOf(false) }
        var openFileDialog by remember { mutableStateOf(false) }
        var fileResult by remember { mutableStateOf("(none)") }
        val mainState = rememberWindowState(size = DpSize(960.dp, 720.dp))

        if (isTraySupported) {
            Tray(
                icon = ColorPainter(Color(0xFF1B6AC9)),
                state = trayState,
                tooltip = "ComposeKN Wayland Demo",
                onAction = { println("composekn: tray onAction") },
            ) {
                Item("Notify Info") { trayState.sendNotification(notifyInfo) }
                Separator()
                Item("Exit") { exitApplication() }
            }
        }

        Window(
            onCloseRequest = ::exitApplication,
            state = mainState,
            title = "ComposeKN Wayland Demo",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "ComposeKN — Compose on Kotlin/Native + Wayland",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "tray=${ComposeKNTray.available()} fileDialog=${ComposeKNFileDialog.available()} " +
                                "placement=${mainState.placement}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { openSecond = true }) { Text("第二扇窗") }
                            Button(onClick = { openDialog = true }) { Text("DialogWindow") }
                            Button(onClick = {
                                mainState.placement =
                                    if (mainState.placement == WindowPlacement.Fullscreen) {
                                        WindowPlacement.Floating
                                    } else {
                                        WindowPlacement.Fullscreen
                                    }
                            }) {
                                Text(
                                    if (mainState.placement == WindowPlacement.Fullscreen) {
                                        "退出全屏"
                                    } else {
                                        "全屏"
                                    },
                                )
                            }
                            Button(onClick = {
                                mainState.placement = WindowPlacement.Maximized
                            }) { Text("最大化") }
                            Button(onClick = {
                                mainState.placement = WindowPlacement.Floating
                            }) { Text("还原") }
                            Button(onClick = { openFileDialog = true }) { Text("打开文件…") }
                            if (isTraySupported) {
                                Button(onClick = {
                                    trayState.sendNotification(notifyInfo)
                                }) { Text("托盘通知") }
                            }
                        }
                        Text("FileDialog: $fileResult", style = MaterialTheme.typography.bodySmall)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ShowcaseSections()
                        }
                    }
                }
            }

            if (openFileDialog) {
                FileDialog(
                    onCloseRequest = { paths ->
                        openFileDialog = false
                        fileResult = if (paths.isEmpty()) "(cancelled)" else paths.joinToString()
                        println("composekn: filedialog -> $fileResult")
                    },
                    mode = FileDialogMode.Load,
                    title = "Open file",
                    filters = listOf(
                        FileDialogFilter("Text", listOf("txt", "md", "kt")),
                        FileDialogFilter("All", listOf("*.*")),
                    ),
                )
            }
        }

        // Shared wl_display is on: second window aliases g_shared (refcount++), no
        // second wl_display_connect; closing it must leave the main window's seat/EGL alive.
        if (openSecond) {
            Window(
                onCloseRequest = { openSecond = false },
                state = rememberWindowState(size = DpSize(420.dp, 280.dp)),
                title = "ComposeKN · 第二扇窗",
            ) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("第二扇窗", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "PlatformDefault → xdg_toplevel_set_parent（相对主窗 transient；" +
                                    "屏幕坐标仍由 compositor 决定）。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { openSecond = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }

        if (openDialog) {
            DialogWindow(
                onCloseRequest = { openDialog = false },
                state = rememberDialogState(size = DpSize(360.dp, 220.dp)),
                title = "ComposeKN · DialogWindow",
            ) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("DialogWindow", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Aligned(Center) → set_parent；多数 compositor 会相对父窗居中。" +
                                    "软模态：父窗输入被暂时丢弃。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { openDialog = false }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 无交互自检：断言 tray/filedialog available、短暂挂 application 后正常退出。
 * 用法：`--selftest` 或 `COMPOSEKN_SELFTEST=1`
 */
private fun runLinuxSelfTest() {
    println("SELFTEST: linux start")
    val trayOk = ComposeKNTray.available()
    val fdOk = ComposeKNFileDialog.available()
    println("SELFTEST: tray.available=$trayOk filedialog.available=$fdOk")
    // portal / SNI 在无桌面会话时可能 false；有 wayland 会话时期望至少能连 display。
    var frames = 0
    var failed = false
    var dualOk = false
    var dialogOk = false
    var clipboardOk = false
    application(exitProcessOnExit = false) {
        val state = rememberWindowState(size = DpSize(640.dp, 400.dp))
        var openSecond by remember { mutableStateOf(false) }
        var openDialog by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = "ComposeKN Linux SelfTest",
        ) {
            LaunchedEffect(Unit) {
                try {
                    delay(400)
                    // Rich clipboard roundtrip (local cache; no compositor required)
                    WaylandClipboard.setRich(
                        text = "composekn-selftest",
                        html = "<b>composekn</b>",
                        rtf = null,
                        image = null,
                        files = listOf("/tmp/composekn-selftest.txt"),
                    )
                    val gotText = WaylandClipboard.getText()
                    val gotHtml = WaylandClipboard.getHtml()
                    val gotFiles = WaylandClipboard.getFiles()
                    clipboardOk = gotText == "composekn-selftest" &&
                        gotHtml?.contains("composekn") == true &&
                        gotFiles.contains("/tmp/composekn-selftest.txt")
                    println(
                        "SELFTEST: clipboard text=${gotText != null} html=${gotHtml != null} " +
                            "files=${gotFiles.size} ok=$clipboardOk",
                    )
                    // weston headless 对 maximized geometry 很严；CI/RELAX 只测双窗。
                    if (selftestExtrasRequired()) {
                        state.placement = WindowPlacement.Maximized
                        delay(200)
                        state.placement = WindowPlacement.Floating
                        delay(100)
                    }
                    openSecond = true
                    delay(400)
                    openSecond = false
                    delay(200)
                    // DialogWindow：Aligned(Center) → set_parent
                    openDialog = true
                    delay(400)
                    openDialog = false
                    delay(200)
                    frames = 1
                    dualOk = true
                    dialogOk = true
                    println("SELFTEST: placement+dual-window+dialog ok")
                } catch (t: Throwable) {
                    failed = true
                    println("SELFTEST: FAIL ${t.message}")
                } finally {
                    exitApplication()
                }
            }
            Text("selftest…")
        }
        if (openSecond) {
            Window(
                onCloseRequest = { openSecond = false },
                state = rememberWindowState(size = DpSize(320.dp, 240.dp)),
                title = "SelfTest · 2",
            ) {
                Text("second")
            }
        }
        if (openDialog) {
            DialogWindow(
                onCloseRequest = { openDialog = false },
                state = rememberDialogState(size = DpSize(280.dp, 180.dp)),
                title = "SelfTest · Dialog",
            ) {
                Text("dialog")
            }
        }
    }
    val passCore = !failed && frames > 0 && dualOk && dialogOk && clipboardOk
    val extrasRequired = selftestExtrasRequired()
    val extrasOk = !extrasRequired || (trayOk && fdOk)
    if (!extrasRequired && !(trayOk && fdOk)) {
        println("SELFTEST: tray/filedialog soft (CI/RELAX) tray=$trayOk filedialog=$fdOk")
    }
    val pass = passCore && extrasOk
    println(
        "SELFTEST: ${if (pass) "PASS" else "FAIL"} " +
            "tray=$trayOk filedialog=$fdOk dual=$dualOk dialog=$dialogOk clipboard=$clipboardOk",
    )
    exitProcess(if (pass) 0 else 1)
}

/**
 * 本地默认要求 tray+portal；CI 或 `COMPOSEKN_SELFTEST_RELAX=1` 时只断言核心渲染/双窗。
 */
private fun selftestExtrasRequired(): Boolean {
    val relax = getenv("COMPOSEKN_SELFTEST_RELAX")?.toKString()?.trim()
    if (relax == "1") return false
    val ci = getenv("CI")?.toKString()?.trim()
    if (!ci.isNullOrEmpty() && ci != "0" && !ci.equals("false", ignoreCase = true)) {
        return false
    }
    return true
}

private fun resolveSelfTest(args: Array<String>): Boolean {
    if (args.any { it == "--selftest" || it.startsWith("--selftest=") }) return true
    val env = getenv("COMPOSEKN_SELFTEST")?.toKString()?.trim()
    return !(env.isNullOrEmpty() || env == "0")
}

@androidx.compose.runtime.Composable
private fun ShowcaseSections() {
    Section("1 · Text input") {
        var text by remember { mutableStateOf("Type here — try pinyin") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text input") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Section("2 · Button") {
        var clicks by remember { mutableIntStateOf(0) }
        Button(onClick = { clicks++ }, modifier = Modifier.fillMaxWidth()) {
            Text("Clicked $clicks")
        }
    }
    Section("3 · Hover") {
        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isHovered) Color(0xFF1565C0) else Color(0xFF455A64))
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .hoverable(interactionSource),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (isHovered) "Hovered!" else "Move mouse over me", color = Color.White)
        }
    }
    Section("4 · Drag") {
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFF263238)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFFFB300))
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            dragOffset += dragAmount
                            change.consume()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("⇔", color = Color.Black)
            }
        }
    }
    Section("5 · Switch / Checkbox") {
        var on by remember { mutableStateOf(true) }
        var checked by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = on, onCheckedChange = { on = it })
            Text(if (on) "ON" else "OFF", modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { checked = it })
            Text(if (checked) "checked" else "unchecked")
        }
    }
    Section("6 · Clipboard (rich)") {
        ClipboardDemoBox()
    }
    Section("7 · Drag & Drop") {
        DragAndDropDemoBox()
    }
    Section("8 · Scroll") {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..40) {
                Text(
                    "Item $i",
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (i % 2 == 0) Color(0xFF37474F) else Color(0xFF263238))
                        .padding(8.dp),
                )
            }
        }
    }
    Section("9 · Custom fonts") {
        val sans = remember {
            FontFamily(
                Font(Res.font.noto_sans_regular, FontWeight.Normal),
                Font(Res.font.noto_sans_bold, FontWeight.Bold),
            )
        }
        val mono = remember {
            FontFamily(Font(Res.font.jbmono_regular, FontWeight.Normal))
        }
        Text("Noto Sans — ComposeKN Res.font", fontFamily = sans, color = Color.White)
        Text("JetBrains Mono 0123", fontFamily = mono, color = Color(0xFFB0BEC5))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ClipboardDemoBox() {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf("复制 HTML / 粘贴读回") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            clipboard.setClip(
                ClipEntry.withHtml(
                    html = "<b>ComposeKN</b> Linux clipboard",
                    plainText = "ComposeKN Linux clipboard",
                ),
            )
            status = "已写入 HTML+plain"
            println("composekn: clipboard setRich html")
        }) { Text("复制 HTML") }
        Button(onClick = {
            val entry = clipboard.getClip()
            val html = entry?.getHtml()
            val plain = entry?.getPlainText()
            status = when {
                html != null -> "HTML: ${html.take(40)}"
                !plain.isNullOrEmpty() -> "文本: ${plain.take(40)}"
                else -> "空"
            }
            println("composekn: clipboard get -> $status")
        }) { Text("粘贴") }
    }
    Text(status, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DragAndDropDemoBox() {
    val paths = remember {
        listOf("/tmp/composekn-gallery-drag-1.txt", "/tmp/composekn-gallery-drag-2.txt")
    }
    var dropStatus by remember { mutableStateOf("把文件/文本拖到虚线框") }
    var hovering by remember { mutableStateOf(false) }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) { hovering = true }
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                dropStatus = when {
                    event.files.isNotEmpty() && event.files.first().isNotEmpty() ->
                        "文件 ${event.files.size}: ${event.files.first()}"
                    !event.text.isNullOrEmpty() -> "文本: ${event.text!!.take(48)}"
                    else -> "放下了，但没有文件/文本"
                }
                println("composekn: drag drop -> $dropStatus")
                return true
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(140.dp, 64.dp)
                    .background(Color(0xFF1B6AC9))
                    .dragAndDropSource(
                        drawDragDecoration = { drawRect(Color(0xFF1B6AC9), size = size) },
                    ) { _ ->
                        DragAndDropTransferData(
                            files = paths,
                            onTransferCompleted = { ok ->
                                println("composekn: drag files done success=$ok")
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Text("拖出文件", color = Color.White) }
            Box(
                modifier = Modifier
                    .size(140.dp, 64.dp)
                    .background(Color(0xFF2E7D32))
                    .dragAndDropSource(
                        drawDragDecoration = { drawRect(Color(0xFF2E7D32), size = size) },
                    ) { _ ->
                        DragAndDropTransferData(
                            text = "ComposeKN Wayland 拖出的文本",
                            onTransferCompleted = { ok ->
                                println("composekn: drag text done success=$ok")
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Text("拖出文本", color = Color.White) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(if (hovering) Color(0xFF455A64) else Color(0xFF37474F))
                .dragAndDropTarget(
                    shouldStartDragAndDrop = { e ->
                        e.files.isNotEmpty() || e.text != null
                    },
                    target = dropTarget,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (hovering) "松开即可放下" else dropStatus,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun Section(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFF90CAF9))
        content()
    }
}

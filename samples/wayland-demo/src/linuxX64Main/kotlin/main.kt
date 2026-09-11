package main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.composekn.linux.LinuxComposeApplication
import com.composekn.linux.LinuxComposeWindow
import com.composekn.linux.drawHelloFrame
import org.jetbrains.skiko.initLinuxMainThread

private const val SKIA_ONLY_TEST = false

fun main() {
    initLinuxMainThread()
    if (SKIA_ONLY_TEST) {
        LinuxComposeWindow("ComposeKN Skia Test").runSkiaOnly(::drawHelloFrame)
        return
    }
    LinuxComposeApplication("ComposeKN Wayland Demo").run {
        MaterialTheme {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                for (c in e.changes) {
                                    println(
                                        "composekn: pointer ev=${e.type} src=${c.type} " +
                                            "pos=(${c.position.x.roundToInt()},${c.position.y.roundToInt()}) " +
                                            "pressed=${c.pressed}",
                                    )
                                }
                            }
                        }
                    },
            ) {
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
                        "Feature showcase: input + interaction. Scroll to see all. Esc = Back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Section("1 · Text input（IME 拼音 / 键盘 / 剪贴板 Ctrl+C/V/X）") {
                            var text by remember { mutableStateOf("Type here — try pinyin: nihao") }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text("Text input") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        Section("2 · Button（鼠标点击计数）") {
                            var clicks by remember { mutableIntStateOf(0) }
                            Button(
                                onClick = { clicks++ },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Clicked $clicks time${if (clicks == 1) "" else "s"}")
                            }
                        }

                        Section("3 · Hover（鼠标悬停变色）") {
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
                                Text(
                                    if (isHovered) "Hovered!" else "Move mouse over me",
                                    color = Color.White,
                                )
                            }
                        }

                        Section("4 · Drag（按住拖动方块）") {
                            var dragOffset by remember { mutableStateOf(Offset.Zero) }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .background(Color(0xFF263238))
                                    .clip(RoundedCornerShape(8.dp)),
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

                        Section("5 · Switch / Checkbox（开关与复选）") {
                            var on by remember { mutableStateOf(true) }
                            var checked by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = on, onCheckedChange = { on = it })
                                Text(if (on) "Switch ON" else "Switch OFF", modifier = Modifier.padding(start = 8.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = checked, onCheckedChange = { checked = it })
                                Text("Checkbox ${if (checked) "checked" else "unchecked"}")
                            }
                        }

                        Section("6 · Scroll（下方 60 项，验证滚轮/滚动）") {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for (i in 1..60) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (i % 2 == 0) Color(0xFF37474F) else Color(0xFF263238))
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            "Item $i of 60",
                                            modifier = Modifier.weight(1f),
                                            color = Color.White,
                                        )
                                        Text(
                                            "#$i",
                                            color = Color.LightGray,
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            "— end of showcase —",
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 一个小节：标题 + 内容块。 */
@androidx.compose.runtime.Composable
private fun Section(title: String, content: @androidx.compose.runtime.Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Color(0xFF90CAF9))
        content()
    }
}

package com.composekn.windows

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState

/**
 * Client-side title bar with minimize / maximize / close buttons.
 */
@Composable
fun WindowsWindowChrome(
    title: String,
    window: WindowsComposeWindow,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        // Title bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF2D2D30)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Title text with drag support
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .pointerInput(window) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            window.beginMove()
                        }
                    },
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Window control buttons
            WindowControlButton(WindowButton.Minimize, window::minimize)
            WindowControlButton(
                if (window.isMaximized) WindowButton.Restore else WindowButton.Maximize,
                window::toggleMaximized,
            )
            WindowControlButton(WindowButton.Close, window::requestClose)
        }

        // Content area
        Box(Modifier.fillMaxSize()) {
            content()
        }
    }
}

private enum class WindowButton { Minimize, Maximize, Restore, Close }

/**
 * 标题栏按钮。
 *
 * 不用 Material3 的 TextButton + 字形文本（如 "−"/"□"/"×"）：
 * TextButton 自带 58x40dp 最小尺寸与 12dp 内边距，塞进 46x32 的框里会被裁切；
 * 而且这些 Unicode 字形在部分 Windows 字体下会缺字/回退成别的符号。
 * 这里直接用 Canvas 画矢线，尺寸与形状完全可控，并补上 hover 高亮。
 */
@Composable
private fun WindowControlButton(kind: WindowButton, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(32.dp)
            .background(if (hovered) Color(0xFF3F3F46) else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val stroke = 1.1.dp.toPx()
            val w = size.width
            val h = size.height
            when (kind) {
                WindowButton.Minimize ->
                    drawLine(Color.White, Offset(0f, h), Offset(w, h), stroke)
                WindowButton.Maximize ->
                    drawRect(Color.White, topLeft = Offset.Zero, size = Size(w, h), style = Stroke(stroke))
                WindowButton.Restore -> {
                    drawRect(
                        Color.White,
                        topLeft = Offset(0f, h * 0.25f),
                        size = Size(w * 0.75f, h * 0.75f),
                        style = Stroke(stroke),
                    )
                    drawLine(Color.White, Offset(w * 0.25f, 0f), Offset(w, 0f), stroke)
                    drawLine(Color.White, Offset(w, 0f), Offset(w, h * 0.75f), stroke)
                }
                WindowButton.Close -> {
                    drawLine(Color.White, Offset.Zero, Offset(w, h), stroke)
                    drawLine(Color.White, Offset(w, 0f), Offset(0f, h), stroke)
                }
            }
        }
    }
}

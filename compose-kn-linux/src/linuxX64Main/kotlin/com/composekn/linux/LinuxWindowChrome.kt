package com.composekn.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import org.jetbrains.skiko.WaylandWindow

// XDG toplevel resize edges (from xdg-shell protocol)
private const val XDG_TOPLEVEL_RESIZE_EDGE_TOP = 1u
private const val XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM = 2u
private const val XDG_TOPLEVEL_RESIZE_EDGE_LEFT = 4u
private const val XDG_TOPLEVEL_RESIZE_EDGE_TOP_LEFT = 5u
private const val XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_LEFT = 6u
private const val XDG_TOPLEVEL_RESIZE_EDGE_RIGHT = 8u
private const val XDG_TOPLEVEL_RESIZE_EDGE_TOP_RIGHT = 9u
private const val XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT = 10u

/**
 * Client-side title bar with minimize / maximize / close when the compositor does not
 * provide server-side decorations.
 */
@Composable
fun LinuxWindowChrome(
    title: String,
    window: WaylandWindow,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (window.usesServerDecoration) {
        Box(modifier.fillMaxSize()) {
            content()
        }
        return
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color(0xFF2D2D30)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                WindowControlButton(label = "−", onClick = window::minimize)
                WindowControlButton(
                    label = if (window.isMaximized) "❐" else "□",
                    onClick = window::toggleMaximized,
                )
                WindowControlButton(label = "×", onClick = window::requestClose)
            }
            Box(Modifier.fillMaxSize()) {
                content()
            }
        }
        // resize handles 放最外层最后绘制, 保证盖住内容并可覆盖标题栏顶部
        ResizeHandle(
            modifier = Modifier.fillMaxWidth().height(6.dp).align(Alignment.TopCenter),
            horizontal = true,
            edgeTop = XDG_TOPLEVEL_RESIZE_EDGE_TOP,
            edgeLeft = XDG_TOPLEVEL_RESIZE_EDGE_TOP_LEFT,
            edgeRight = XDG_TOPLEVEL_RESIZE_EDGE_TOP_RIGHT,
            window = window,
        )
        ResizeHandle(
            modifier = Modifier.fillMaxWidth().height(6.dp).align(Alignment.BottomCenter),
            horizontal = true,
            edgeTop = XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM,
            edgeLeft = XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_LEFT,
            edgeRight = XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT,
            window = window,
        )
        ResizeHandle(
            modifier = Modifier.fillMaxHeight().width(6.dp).align(Alignment.CenterStart),
            horizontal = false,
            edgeTop = XDG_TOPLEVEL_RESIZE_EDGE_TOP_LEFT,
            edgeLeft = XDG_TOPLEVEL_RESIZE_EDGE_LEFT,
            edgeRight = XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_LEFT,
            window = window,
        )
        ResizeHandle(
            modifier = Modifier.fillMaxHeight().width(6.dp).align(Alignment.CenterEnd),
            horizontal = false,
            edgeTop = XDG_TOPLEVEL_RESIZE_EDGE_TOP_RIGHT,
            edgeLeft = XDG_TOPLEVEL_RESIZE_EDGE_RIGHT,
            edgeRight = XDG_TOPLEVEL_RESIZE_EDGE_BOTTOM_RIGHT,
            window = window,
        )
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier,
    horizontal: Boolean, // true: 水平条(顶/底边), 按 x 分段; false: 垂直条(左/右边), 按 y 分段
    edgeTop: UInt,
    edgeLeft: UInt,
    edgeRight: UInt,
    window: WaylandWindow,
) {
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .pointerInput(window, horizontal, edgeTop, edgeLeft, edgeRight) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val edge = if (horizontal) {
                        val x = down.position.x
                        when {
                            x < size.width * 0.33f -> edgeLeft
                            x > size.width * 0.66f -> edgeRight
                            else -> edgeTop
                        }
                    } else {
                        val y = down.position.y
                        when {
                            y < size.height * 0.33f -> edgeTop
                            y > size.height * 0.66f -> edgeRight
                            else -> edgeLeft
                        }
                    }
                    window.beginResize(edge)
                }
            },
    )
}

@Composable
private fun WindowControlButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(44.dp).height(40.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

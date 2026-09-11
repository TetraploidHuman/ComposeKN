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
            WindowControlButton(label = "−", onClick = window::minimize)
            WindowControlButton(
                label = if (window.isMaximized) "❐" else "□",
                onClick = window::toggleMaximized,
            )
            WindowControlButton(label = "×", onClick = window::requestClose)
        }

        // Content area
        Box(Modifier.fillMaxSize()) {
            content()
        }
    }
}

@Composable
private fun WindowControlButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(44.dp).height(32.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

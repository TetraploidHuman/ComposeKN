package main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composekn.windows.WindowsComposeApplication
import com.composekn.windows.drawHelloFrame

private const val SKIA_ONLY_TEST = false

fun main() {
    if (SKIA_ONLY_TEST) {
        // Simple Skia rendering test
        println("ComposeKN Windows: Running Skia-only test")
        // TODO: Implement Skia-only test for Windows
        return
    }

    // Full Compose UI application
    println("ComposeKN Windows: Starting Compose UI application")
    WindowsComposeApplication("ComposeKN Windows Demo").run {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                var clickCount by remember { mutableIntStateOf(0) }
                var text by remember { mutableStateOf("Type here — Esc = Back") }
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("ComposeKN on Kotlin/Native + Windows")
                    Text("Clipboard: copy/paste in TextField (Ctrl+C/V)")
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text input") },
                    )
                    Button(onClick = { clickCount++ }) {
                        Text("Clicked $clickCount times")
                    }
                }
            }
        }
    }
}

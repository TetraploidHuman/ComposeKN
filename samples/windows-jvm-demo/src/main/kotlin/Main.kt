import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * ComposeKN — Windows JVM 路线的最小可运行 demo。
 *
 * 全链路: Kotlin/JVM + Compose Desktop + skiko-windows (native, MSVC ABI, JVM 加载)。
 * UI 代码与 Linux/Wayland 的 wayland-demo 是同一套 Material3 写法。
 * 此 demo 由 JetBrains 官方 Compose Desktop Gradle plugin 打包 (jpackage → exe)。
 */
fun main() = application {
    Window(
        title = "ComposeKN — Windows (JVM)",
        onCloseRequest = ::exitApplication,
    ) {
        val backgroundColor = Color(0xFF2D2D30)
        MaterialTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = backgroundColor,
            ) {
                CounterAndTextInput()
            }
        }
    }
}

@Composable
fun CounterAndTextInput() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ComposeKN on Windows (Kotlin/JVM + skiko native)")
        Text("此 demo 由 compose.desktop (JetBrains 官方打包) 构建")
        var clickCount by remember { mutableIntStateOf(0) }
        Button(onClick = { clickCount++ }) { Text("Clicked $clickCount times") }
        var text by remember { mutableStateOf("Type here (JVM text input)") }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text input") },
        )
    }
}

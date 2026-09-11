plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
}

// NOTE: 独立 Gradle 构建 (不被主工程 settings include)，因此不经过
// compose-core 的 dependencySubstitution —— 直接消费官方
// org.jetbrains.compose.databinding artifacts（Compose Desktop, JVM）。
//
// 这就是 Windows 上的官方路径：Kotlin/JVM + skiko-windows native dll (MSVC ABI)。
// windows-native (mingwX64) 路线见 samples/windows-demo (受限于 skia MSVC ABI)。

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}

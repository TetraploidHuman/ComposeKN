plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.windows-native-linker")
    id("com.composekn.publish")
}

kotlin {
    mingwX64()

    sourceSets {
        val mingwX64Main by getting {
            // windowsCommonMain = 与 Windows 系统调用无关的纯逻辑（键位映射、事件模型、
            // 输入状态、Win32 常量）。抽出来是为了能被 :compose-kn-tests 用 linuxX64
            // 目标编译并跑单元测试 —— mingwX64 的测试二进制在 Linux 上跑不了。
            kotlin.srcDir("src/windowsCommonMain/kotlin")
            kotlin.srcDir("src/windowsX64Main/kotlin")
            dependencies {
                implementation("org.jetbrains.skiko:skiko:0.0.0")
                implementation(libs.coroutines.core)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
            }
        }
    }
}

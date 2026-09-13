plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // windowsCommonMain 里的映射表用到 compose-ui 的 Key/KeyEvent，
    // 而 :ui (linuxX64) 的 klib 携带 wayland/EGL 的 linkerOpts，
    // 所以测试二进制也要按 Linux 原生目标补 pkg-config 的 -L 路径。
    id("com.composekn.linux-native-linker")
}

// ComposeKN 的自动化单元测试。
//
// 为什么用 linuxX64 而不是 mingwX64：
//   Windows 平台代码里有一部分是**纯逻辑**（VK→Key 映射表、事件模型、输入状态、
//   Win32 常量/参数解码），放在 compose-kn-windows/src/windowsCommonMain。
//   mingwX64 的测试二进制在 Linux/CI 上根本跑不起来，而把这些文件用 linuxX64
//   目标编译一遍就能在开发机和 ubuntu-latest 上秒级跑完 —— 尤其是映射表这类
//   「重复键被静默覆盖」的问题，只有单测能拦住。
//
//   Windows 专属的端到端测试见 samples/windows-demo 的 `--selftest`
//   （离屏渲染 + 合成事件 + 像素断言）与 scripts/test-windows-native.sh。
//
// 运行：./gradlew :compose-kn-tests:linuxX64Test
kotlin {
    linuxX64()
    sourceSets {
        val linuxX64Main by getting {
            kotlin.srcDir("../compose-kn-windows/src/windowsCommonMain/kotlin")
            dependencies {
                implementation(libs.compose.ui)
            }
        }
        val linuxX64Test by getting {
            kotlin.srcDir("src/linuxX64Test/kotlin")
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

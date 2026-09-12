plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.windows-native-linker")
}

kotlin {
    mingwX64 {
        binaries {
            executable {
                entryPoint = "main.main"

                // ComposeKN: 使用 GNU-ABI (MinGW) Skia 时需要额外的运行时库
                // （GCC 15 的 libstdc++、mcfgthread、UCRT 导入库）。
                // 用法: -Pskiko.mingw.libs=<a.a>,<b.a>,...
                val mingwLibs = (project.findProperty("skiko.mingw.libs") as? String)
                if (!mingwLibs.isNullOrBlank()) {
                    linkerOpts.addAll(
                        mingwLibs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    )
                }
                val mingwLibDirs = (project.findProperty("skiko.mingw.libDirs") as? String)
                if (!mingwLibDirs.isNullOrBlank()) {
                    linkerOpts.addAll(
                        mingwLibDirs.split(',', ':').map { "-L${it.trim()}" }.filter { it != "-L" }
                    )
                }
            }
        }
    }

    sourceSets {
        val mingwX64Main by getting {  // mingw target source set 名与 target 名同名
            kotlin.srcDir("src/windowsX64Main/kotlin")
            dependencies {
                implementation(project(":compose-kn-windows"))
            }
        }
    }
}

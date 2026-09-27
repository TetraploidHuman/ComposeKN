plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.linux-native-linker")
    id("com.composekn.windows-native-linker")
}

val lumicodeRoot = file("../../../LumiCodeNext/composeApp")
val lumicodeCommon = file("$lumicodeRoot/src/commonMain/kotlin")

kotlin {
    linuxX64 {
        binaries {
            executable {
                entryPoint = "main.main"
            }
        }
    }

    mingwX64 {
        binaries {
            executable {
                entryPoint = "main.main"
                val mingwLibs = (project.findProperty("skiko.mingw.libs") as? String)
                if (!mingwLibs.isNullOrBlank()) {
                    linkerOpts.addAll(
                        mingwLibs.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    )
                }
                val mingwLibDirs = (project.findProperty("skiko.mingw.libDirs") as? String)
                if (!mingwLibDirs.isNullOrBlank()) {
                    linkerOpts.addAll(
                        mingwLibDirs.split(',', ':').map { "-L${it.trim()}" }.filter { it != "-L" },
                    )
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(lumicodeCommon)
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.coroutines.core)
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }

        val linuxX64Main by getting {
            dependencies {
                implementation(project(":compose-kn-linux"))
            }
        }

        val mingwX64Main by getting {
            dependencies {
                implementation(project(":compose-kn-windows"))
            }
        }
    }
}

tasks.register("verifyLumicodeSources") {
    doLast {
        check(lumicodeCommon.isDirectory) {
            "LumiCode commonMain not found at $lumicodeCommon — clone/checkout LumiCodeNext next to ComposeKN"
        }
    }
}
tasks.named("compileKotlinLinuxX64") { dependsOn("verifyLumicodeSources") }
tasks.matching { it.name.startsWith("compileKotlinMingw") }.configureEach {
    dependsOn("verifyLumicodeSources")
}

// material3 等可能传递 0.7.x（Clock API 已迁到 kotlin.time）；LumiCode common 用 0.6.x API。
configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
}

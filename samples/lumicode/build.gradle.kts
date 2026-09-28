plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.host")
    id("com.composekn.resources")
}

val lumicodeRoot = file("../../../LumiCodeNext/composeApp")
val lumicodeCommon = file("$lumicodeRoot/src/commonMain/kotlin")
val lumicodeFonts = file("$lumicodeRoot/src/commonMain/composeResources/font")

composeKnResources {
    packageName.set("com.lumicode.editor.resources")
    // Prefer LumiCodeNext fonts when present; also allow local composeResources/font.
    extraFontDirs.add(lumicodeFonts.absolutePath)
}

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
                // Cross-link Skia prebuilts (build-windows.sh sets these props).
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
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation(project(":compose-kn-resources"))
            }
        }

        val linuxX64Main by getting {
            kotlin.srcDir("src/knShared/kotlin")
            dependencies {
                // compose-kn-linux 由 com.composekn.host 注入
                implementation(project(":compose-kn-resources"))
            }
        }

        val mingwX64Main by getting {
            kotlin.srcDir("src/knShared/kotlin")
            dependencies {
                // compose-kn-windows 由 com.composekn.host 注入
                implementation(project(":compose-kn-resources"))
            }
        }
    }
}

tasks.register("verifyLumicodeSources") {
    doLast {
        check(lumicodeCommon.isDirectory) {
            "LumiCode commonMain not found at $lumicodeCommon — clone/checkout LumiCodeNext next to ComposeKN"
        }
        check(lumicodeFonts.isDirectory) {
            "LumiCode fonts not found at $lumicodeFonts"
        }
    }
}
tasks.named("compileKotlinLinuxX64") { dependsOn("verifyLumicodeSources") }
tasks.matching { it.name.startsWith("compileKotlinMingw") }.configureEach {
    dependsOn("verifyLumicodeSources")
}
tasks.named("generateComposeKnResources") { dependsOn("verifyLumicodeSources") }

configurations.configureEach {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.windows-native-linker")
    id("com.composekn.resources")
}

val lumicodeFonts = file("../../../LumiCodeNext/composeApp/src/commonMain/composeResources/font")

composeKnResources {
    packageName.set("main.resources")
    extraFontDirs.add(lumicodeFonts.absolutePath)
}

kotlin {
    mingwX64 {
        binaries {
            executable {
                entryPoint = "main.main"

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
        val mingwX64Main by getting {
            kotlin.srcDir("src/windowsX64Main/kotlin")
            dependencies {
                implementation(project(":compose-kn-windows"))
                implementation(project(":compose-kn-resources"))
            }
        }
    }
}

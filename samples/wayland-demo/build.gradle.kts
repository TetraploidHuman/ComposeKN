plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.host")
    id("com.composekn.resources")
}

val lumicodeFonts = file("../../../LumiCodeNext/composeApp/src/commonMain/composeResources/font")

composeKnResources {
    packageName.set("main.resources")
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

    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                // compose-kn-linux 由 com.composekn.host 注入
                implementation(project(":compose-kn-resources"))
            }
        }
    }
}

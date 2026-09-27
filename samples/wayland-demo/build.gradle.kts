plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.linux-native-linker")
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
                implementation(project(":compose-kn-linux"))
                implementation(project(":compose-kn-resources"))
            }
        }
    }
}

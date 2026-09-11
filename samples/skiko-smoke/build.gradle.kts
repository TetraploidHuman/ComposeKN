plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("com.composekn.linux-native-linker")
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
                implementation("org.jetbrains.skiko:skiko:0.0.0")
            }
        }
    }
}

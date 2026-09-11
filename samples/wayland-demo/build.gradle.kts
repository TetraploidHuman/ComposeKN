plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
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
                implementation(project(":compose-kn-linux"))
            }
        }
    }
}

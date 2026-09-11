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

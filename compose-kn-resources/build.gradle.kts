plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64()
    mingwX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.skiko:skiko:0.0.0")
                implementation(libs.compose.ui)
            }
        }
    }
}

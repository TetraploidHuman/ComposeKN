plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.windows-native-linker")
}

kotlin {
    mingwX64()

    sourceSets {
        val mingwX64Main by getting {
            kotlin.srcDir("src/windowsX64Main/kotlin")
            dependencies {
                implementation("org.jetbrains.skiko:skiko:0.0.0")
                implementation(libs.coroutines.core)
                implementation(libs.compose.runtime)
                implementation(libs.compose.ui)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
            }
        }
    }
}

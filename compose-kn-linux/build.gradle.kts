plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("com.composekn.linux-native-linker")
    id("com.composekn.publish")
}

kotlin {
    linuxX64()

    sourceSets {
        val linuxX64Main by getting {
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

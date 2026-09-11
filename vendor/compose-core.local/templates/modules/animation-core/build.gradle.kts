plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation(project(":ui"))
                implementation(project(":ui-unit"))
                implementation(project(":ui-graphics"))
                implementation(project(":ui-util"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            }
        }
        val linuxX64Main by getting {
            dependsOn(nonJvmMain)
            kotlin.srcDir("src/linuxX64Main/kotlin")
        }
    }
}

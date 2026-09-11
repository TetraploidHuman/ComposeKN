plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
                api("org.jetbrains.compose.runtime:runtime:1.11.1")
                api(project(":animation"))
                api(project(":ui"))
                implementation(project(":ui-text"))
                implementation(project(":ui-util"))
                implementation(project(":foundation-layout"))
            }
        }
        val skikoMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/skikoMain/kotlin")
            dependencies {
                api("org.jetbrains.skiko:skiko:0.0.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
            }
        }
        val nonJvmMain by creating {
            dependsOn(skikoMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
        }
        val nativeMain by creating {
            dependsOn(nonJvmMain)
            kotlin.srcDir("src/nativeMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
            kotlin.srcDir("src/linuxX64Main/kotlin")
        }
    }
}

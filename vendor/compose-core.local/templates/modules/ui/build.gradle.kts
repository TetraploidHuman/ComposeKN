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
                implementation("org.jetbrains.compose.runtime:runtime-saveable:1.11.1")
                implementation("androidx.compose.runtime:runtime-retain:1.11.1")
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
                implementation("org.jetbrains.compose.annotation-internal:annotation:1.11.1")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
                implementation("org.jetbrains.androidx.savedstate:savedstate-compose:1.4.0")
                api(project(":ui-geometry"))
                api(project(":ui-graphics"))
                api(project(":ui-text"))
                api(project(":ui-unit"))
                api(project(":ui-util"))
            }
        }
        val skikoMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/skikoMain/kotlin")
            dependencies {
                api("org.jetbrains.skiko:skiko:0.0.0")
                implementation("org.jetbrains.kotlinx:atomicfu:0.27.0")
                implementation("androidx.navigationevent:navigationevent:1.1.1")
                implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
                implementation(project(":lifecycle-viewmodel-compose"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(skikoMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
            dependencies {
                implementation(project(":ui-backhandler"))
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel:2.9.6")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.6")
            }
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

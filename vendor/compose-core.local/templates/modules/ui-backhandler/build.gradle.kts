plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation(project(":ui-util"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            }
        }
        val jbMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/jbMain")
            dependencies {
                implementation("androidx.navigationevent:navigationevent-compose:1.1.1")
            }
        }
        val linuxX64Main by getting {
            dependsOn(jbMain)
        }
        val mingwX64Main by getting {
            dependsOn(jbMain)
        }
    }
}

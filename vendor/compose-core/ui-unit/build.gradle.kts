plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation(project(":ui-geometry"))
                implementation(project(":ui-util"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
        }
        val nonAndroidMain by creating {
            dependsOn(nonJvmMain)
            kotlin.srcDir("src/nonAndroidMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(nonAndroidMain)
        }
        val mingwX64Main by getting {
            dependsOn(nonAndroidMain)
        }
    }
}

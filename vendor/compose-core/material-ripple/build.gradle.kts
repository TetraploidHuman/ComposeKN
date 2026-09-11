plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                api(project(":foundation"))
                api("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
                implementation(project(":animation"))
                implementation(project(":ui-util"))
            }
        }
        val nonAndroidMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nonAndroidMain/kotlin")
        }
        val nonJvmMain by creating {
            dependsOn(nonAndroidMain)
        }
        val linuxX64Main by getting {
            dependsOn(nonJvmMain)
        }
        val mingwX64Main by getting {
            dependsOn(nonJvmMain)
        }
    }
}

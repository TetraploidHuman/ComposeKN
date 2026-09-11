plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                api(project(":animation-core"))
                api(project(":foundation-layout"))
                api("org.jetbrains.compose.runtime:runtime:1.11.1")
                api(project(":ui-geometry"))
                implementation(project(":ui"))
                implementation(project(":ui-util"))
                implementation(project(":ui-graphics"))
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
            }
        }
        val nonAndroidMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nonAndroidMain/kotlin")
        }
        val nonJvmMain by creating {
            dependsOn(nonAndroidMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(nonJvmMain)
        }
    }
}

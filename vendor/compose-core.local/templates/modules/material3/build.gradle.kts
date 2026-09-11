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
                implementation(project(":animation-core"))
                implementation(project(":ui-util"))
                api(project(":foundation-layout"))
                api(project(":material-ripple"))
                api("org.jetbrains.compose.runtime:runtime:1.11.1")
                api(project(":ui"))
                api(project(":ui"))
                api(project(":ui-text"))
                api("androidx.graphics:graphics-shapes:1.1.0")
            }
        }
        val skikoMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/skikoMain/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation(project(":ui-backhandler"))
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

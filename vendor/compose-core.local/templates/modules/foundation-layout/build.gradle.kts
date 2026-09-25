plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                api(project(":ui"))
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
                implementation(project(":ui-util"))
                implementation(project(":ui-unit"))
            }
        }
        val skikoMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/skikoMain/kotlin")
        }
        val nonJvmMain by creating {
            dependsOn(skikoMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(nonJvmMain)
        }
        val mingwX64Main by getting {
            dependsOn(nonJvmMain)
        }
    }
}

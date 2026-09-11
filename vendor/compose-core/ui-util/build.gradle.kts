plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.collection-internal:collection:1.11.1")
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("src/nonJvmMain/kotlin")
        }
        val linuxX64Main by getting {
            dependsOn(nonJvmMain)
            kotlin.srcDir("src/linuxX64Main/kotlin")
        }
        val mingwX64Main by getting {
            dependsOn(nonJvmMain)
            kotlin.srcDir("src/linuxX64Main/kotlin")
        }
    }
}

plugins {
    kotlin("multiplatform")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("src/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
                implementation(project(":ui-util"))
            }
        }
        val nonJvmMain by creating {
            dependsOn(commonMain)
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

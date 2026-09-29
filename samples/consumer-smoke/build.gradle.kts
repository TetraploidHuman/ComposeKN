plugins {
    kotlin("multiplatform") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
    id("com.composekn.host")
}

val cknVer = providers.gradleProperty("composekn.version").get()

composeKnHost {
    useProjectDependencies.set(false)
    composeknVersion.set(cknVer)
}

kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "main.main"
        }
    }
    sourceSets {
        val linuxX64Main by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
    }
}

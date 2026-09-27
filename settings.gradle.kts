pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
        }
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral {
            url = uri("https://cache-redirector.jetbrains.com/maven-central")
        }
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
    versionCatalogs {
        create("libs") {
            from(files("vendor/skiko/dependencies.toml"))
        }
    }
}

rootProject.name = "ComposeKN"

includeBuild("vendor/skiko/skiko") {
    dependencySubstitution {
        substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
    }
}

includeBuild("vendor/compose-core") {
    dependencySubstitution {
        substitute(module("org.jetbrains.compose.ui:ui-util")).using(project(":ui-util"))
        substitute(module("org.jetbrains.compose.ui:ui-geometry")).using(project(":ui-geometry"))
        substitute(module("org.jetbrains.compose.ui:ui-unit")).using(project(":ui-unit"))
        substitute(module("org.jetbrains.compose.ui:ui-graphics")).using(project(":ui-graphics"))
        substitute(module("org.jetbrains.compose.ui:ui-text")).using(project(":ui-text"))
        substitute(module("org.jetbrains.compose.ui:ui")).using(project(":ui"))
        substitute(module("org.jetbrains.compose.foundation:foundation")).using(project(":foundation"))
        substitute(module("org.jetbrains.compose.material3:material3")).using(project(":material3"))
    }
}

include(":compose-kn-linux")
include(":compose-kn-windows")
include(":compose-kn-resources")
include(":compose-kn-bom")
include(":compose-kn-tests")
include(":samples:wayland-demo")
include(":samples:link-smoke")
include(":samples:skiko-smoke")
include(":samples:lumicode")

// Windows-only modules. Their KGP target `windowsX64()` (see compose-kn-windows/build.gradle.kts)
// is an unresolved reference off-Windows (the Kotlin/Native Windows target is `mingwX64()`),
// which breaks full-project configuration on Linux/macOS hosts. These targets cannot be built
// on a Windows host anyway, so only include them when building on Windows. (Pre-existing bug;
// guard added 2026-07 to unblock the linuxX64 pipeline — fix the target name on a Windows host.)
// NOTE: compose-kn-windows now uses mingwX64() and can configure on Linux for cross-link;
// still gate windows-demo, but always include compose-kn-windows so :samples:lumicode can
// declare mingwX64 deps. If configure fails off-Windows, wrap in isWindowsHost again.
val isWindowsHost = System.getProperty("os.name", "").lowercase().contains("windows")
include(":samples:windows-demo")

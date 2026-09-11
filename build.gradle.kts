plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    id("com.composekn.konan-link-workaround")
}

tasks.register("linkLinuxX64Stable") {
    group = "composekn"
    description = "Link all linuxX64 release executables (konanc shutdown-crash workaround)."
    dependsOn(
        ":samples:link-smoke:linkReleaseExecutableLinuxX64Stable",
        ":samples:skiko-smoke:linkReleaseExecutableLinuxX64Stable",
        ":samples:wayland-demo:linkReleaseExecutableLinuxX64Stable",
    )
}

tasks.register("linkWindowsX64Stable") {
    group = "composekn"
    description = "Link all windowsX64 release executables."
    dependsOn(
        ":samples:windows-demo:linkReleaseExecutableWindowsX64",
    )
}

package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import java.util.concurrent.TimeUnit

/** Adds pkg-config library paths for Wayland/EGL apps linking Skiko on Linux. */
class LinuxNativeLinkerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configureLinuxLinkers(project) }
        }
    }

    private fun configureLinuxLinkers(project: Project) {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val linux = kotlin.targets.findByName("linuxX64") as? KotlinNativeTarget ?: return
        val pkgPackages = arrayOf(
            "wayland-client",
            "wayland-egl",
            "egl",
            "glesv2",
            "fontconfig",
            "gl",
            "xkbcommon",
        )
        val pkgOpts = pkgConfigLinkerOpts(*pkgPackages)
        if (pkgOpts.isEmpty()) {
            project.logger.warn(
                "pkg-config Wayland/GL libs not found; linuxX64 link may fail outside nix-shell."
            )
            return
        }
        val rpathDirs = pkgConfigLibDirs(*pkgPackages).toMutableList()
        LinuxRuntimeBundle.libcryptLibDir()?.absolutePath?.let { rpathDirs.add(it) }
        linux.binaries.all {
            linkerOpts.addAll(pkgOpts)
            linkerOpts.add("-ldl")
            rpathDirs.forEach { dir ->
                linkerOpts.add("-Wl,-rpath,$dir")
            }
            linkerOpts.add("-Wl,-rpath,\$ORIGIN")
            linkerOpts.add("-Wl,-rpath,\$ORIGIN/lib")
        }
    }

    private fun pkgConfigLinkerOpts(vararg packages: String): List<String> {
        return try {
            val process = ProcessBuilder("pkg-config", "--libs", *packages)
                .apply { environment()["PKG_CONFIG_ALLOW_SYSTEM_LIBS"] = "1" }
                .start()
            process.waitFor(10, TimeUnit.SECONDS)
            if (process.exitValue() != 0) return emptyList()
            process.inputStream.bufferedReader().readText()
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pkgConfigLibDirs(vararg packages: String): List<String> {
        val dirs = mutableListOf<String>()
        for (pkg in packages) {
            try {
                val process = ProcessBuilder("pkg-config", "--variable=libdir", pkg)
                    .apply { environment()["PKG_CONFIG_ALLOW_SYSTEM_LIBS"] = "1" }
                    .start()
                process.waitFor(5, TimeUnit.SECONDS)
                if (process.exitValue() == 0) {
                    val libdir = process.inputStream.bufferedReader().readText().trim()
                    if (libdir.isNotEmpty()) {
                        dirs.add(libdir)
                    }
                }
            } catch (_: Exception) {
                // ignore missing package
            }
        }
        pkgConfigLinkerOpts(*packages)
            .filter { it.startsWith("-L") }
            .map { it.removePrefix("-L") }
            .forEach { dirs.add(it) }
        return dirs.distinct()
    }
}

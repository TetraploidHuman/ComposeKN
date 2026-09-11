package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.io.File
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * Adds Windows system library paths for linking Skiko on Windows.
 */
class WindowsNativeLinkerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configureWindowsLinkers(project) }
        }
    }

    private fun configureWindowsLinkers(project: Project) {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val windows = kotlin.targets.findByName("mingwX64") as? KotlinNativeTarget ?: return

        val isWindowsHost = System.getProperty("os.name", "").lowercase().contains("windows")
        val msys2 = File(System.getProperty("user.home"), ".konan/dependencies/msys2-mingw-w64-x86_64-2")
        val msys2Lib = File(msys2, "x86_64-w64-mingw32/lib")
        val msys2GccLib = File(msys2, "lib/gcc/x86_64-w64-mingw32/9.2.0")

        windows.binaries.all {
            // msys2 paths only matter for mingw cross-compiles on Linux hosts.
            if (!isWindowsHost && msys2Lib.isDirectory) linkerOpts.add("-L" + msys2Lib.absolutePath)
            if (!isWindowsHost && msys2GccLib.isDirectory) linkerOpts.add("-L" + msys2GccLib.absolutePath)
            linkerOpts.addAll(listOf(
                // Windows system libraries
                "-luser32",
                "-lgdi32",
                "-lkernel32",
                "-ldwmapi",
                "-lole32",
                "-loleaut32",
                "-lcomctl32",
                "-lcomdlg32",
                "-lshell32",
                "-luuid",
                "-loleaut32",
            ))
        }
    }
}

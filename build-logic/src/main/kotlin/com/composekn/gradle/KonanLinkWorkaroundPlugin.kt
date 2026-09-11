package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskExecutionException
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.File

/**
 * Work around konanc JVM shutdown SIGSEGV (ffiFreeClosure0) on Kotlin 2.4.x + NixOS OpenJDK.
 * The native binary is linked successfully before the host JVM crashes; we treat that as success.
 */
class KonanLinkWorkaroundPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.allprojects.forEach { sub: Project ->
            sub.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
                sub.afterEvaluate { configureNativeLinks(sub) }
            }
        }
    }

    private fun configureNativeLinks(project: Project) {
        val linkTasks = project.tasks.withType(KotlinNativeLink::class.java).toList()
        for (linkTask in linkTasks) {
            val output = linkTask.binary.outputFile
            linkTask.doLast {
                if (output.exists() && output.length() > MIN_KEXE_BYTES) {
                    LinuxRuntimeBundle.bundleLibcryptNextToKexe(output) { project.logger.lifecycle(it) }
                }
            }
            val stableName = linkTask.name + "Stable"
            if (project.tasks.findByName(stableName) != null) continue

            project.tasks.register(stableName) {
                group = "composekn"
                description =
                    "Links ${linkTask.name} in an isolated Gradle process and ignores konanc host-JVM shutdown crash when the .kexe exists."
                dependsOn(linkTask.binary.compilation.compileTaskProvider)
                if (linkTask.name.contains("LinuxX64")) {
                    val root = project.rootProject
                    listOf(
                        ":skiko:compileNativeBridgesLinuxX64",
                        ":skiko:linkNativeBridgesLinuxX64",
                    ).forEach { bridgeTask ->
                        root.tasks.findByPath(bridgeTask)?.let { dependsOn(it) }
                    }
                }
                outputs.file(output)
                doLast {
                    runLinkInSubprocess(project, linkTask, output)
                }
            }
        }
    }

    private fun runLinkInSubprocess(project: Project, linkTask: KotlinNativeLink, output: File) {
        val gradlew = project.rootProject.file("gradlew")
        val logDir = project.rootProject.layout.buildDirectory.dir("composekn-link-logs").get().asFile
        logDir.mkdirs()
        val logFile = File(logDir, "${linkTask.path.replace(':', '-')}.log")
        val javaHome = resolveJavaHome()

        var lastExit = 1
        repeat(MAX_LINK_ATTEMPTS) { attempt ->
            project.logger.lifecycle(
                "ComposeKN: linking ${linkTask.path} (attempt ${attempt + 1}/$MAX_LINK_ATTEMPTS, log: $logFile)"
            )
            logFile.appendText("\n=== attempt ${attempt + 1} ===\n")
            val pb = ProcessBuilder(
                gradlew.absolutePath,
                linkTask.path,
                "--no-daemon",
                "--console=plain",
                "-Pkotlin.native.disableCompilerDaemon=true",
            ).directory(project.rootProject.projectDir)
                .redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            val env = pb.environment()
            if (javaHome != null) {
                env["JAVA_HOME"] = javaHome
                env["COMPOSEKN_JAVA_HOME"] = javaHome
                env["PATH"] = "${File(javaHome, "bin")}:${env["PATH"] ?: ""}"
                env["ORG_GRADLE_JAVA_HOME"] = javaHome
            }
            env["GRADLE_OPTS"] = "-XX:+UseSerialGC -Xmx4g"
            System.getenv("LD_LIBRARY_PATH")?.takeIf { it.isNotEmpty() }?.let { env["LD_LIBRARY_PATH"] = it }
            lastExit = pb.start().waitFor()

            if (output.exists() && output.length() > MIN_KEXE_BYTES) {
                if (lastExit != 0) {
                    project.logger.warn(
                        "Konan link for ${linkTask.path} exited with $lastExit during host-JVM shutdown " +
                            "(ffiFreeClosure); ${output.name} was produced (${output.length()} bytes). " +
                            "See $logFile"
                    )
                }
                LinuxRuntimeBundle.bundleLibcryptNextToKexe(output) { project.logger.lifecycle(it) }
                return
            }
        }

        val tail = logFile.takeIf { it.exists() }?.readLines()?.takeLast(40)?.joinToString("\n").orEmpty()
        throw TaskExecutionException(
            linkTask,
            RuntimeException(
                "Native link did not produce $output (konanc exit $lastExit). " +
                    "Log: $logFile\n\n--- last lines ---\n$tail"
            ),
        )
    }

    private fun resolveJavaHome(): String? {
        System.getenv("COMPOSEKN_JAVA_HOME")?.takeIf { File(it, "bin/java").exists() }?.let { return it }
        "/tmp/temurin21".takeIf { File(it, "bin/java").exists() }?.let { return it }
        return System.getenv("JAVA_HOME")?.takeIf { File(it, "bin/java").exists() }
    }

    private companion object {
        const val MIN_KEXE_BYTES = 4096L
        const val MAX_LINK_ATTEMPTS = 2
    }
}

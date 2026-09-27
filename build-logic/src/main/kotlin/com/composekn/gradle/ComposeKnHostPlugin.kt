package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.File

/**
 * 一键宿主：检测到 linuxX64 / mingwX64 后自动
 * - apply linker 插件
 * - 依赖 compose-kn-linux / compose-kn-windows
 * - 生成 entry wrapper：先 registerBackend，再调用户原来的 entryPoint
 *
 * Linux / Windows 生成源分目录，避免双 target 时互相污染编译。
 *
 * 用法：`id("com.composekn.host")`
 */
class ComposeKnHostPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("composeKnHost", ComposeKnHostExtension::class.java)
        ext.useProjectDependencies.convention(true)
        ext.composeknVersion.convention(
            project.findProperty("composekn.version")?.toString() ?: DEFAULT_VERSION,
        )
        ext.wrapEntryPoint.convention(true)

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configure(project, ext) }
        }
    }

    private fun configure(project: Project, ext: ComposeKnHostExtension) {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        val hasLinux = kotlin.targets.findByName("linuxX64") != null
        val hasMingw = kotlin.targets.findByName("mingwX64") != null
        if (!hasLinux && !hasMingw) {
            project.logger.lifecycle("ComposeKN host: no linuxX64/mingwX64 target — skip")
            return
        }

        if (hasLinux) project.pluginManager.apply("com.composekn.linux-native-linker")
        if (hasMingw) project.pluginManager.apply("com.composekn.windows-native-linker")

        val linuxGen = project.layout.buildDirectory.dir("generated/composekn/host/linux")
        val windowsGen = project.layout.buildDirectory.dir("generated/composekn/host/windows")

        val generate = project.tasks.register("generateComposeKnHostBootstrap") {
            outputs.dir(linuxGen)
            outputs.dir(windowsGen)
            inputs.property("wrap", ext.wrapEntryPoint)
            doLast {
                if (hasLinux) {
                    val dir = linuxGen.get().asFile
                    dir.mkdirs()
                    writeBootstrap(
                        File(dir, "ComposeKnLinuxBootstrap.kt"),
                        platform = "linux",
                        importRegister = "com.composekn.linux.registerComposeKnLinuxBackend",
                        importInstall = "com.composekn.linux.installComposeKnLinuxAutoRegister",
                        registerCall = "registerComposeKnLinuxBackend()",
                        installCall = "installComposeKnLinuxAutoRegister()",
                    )
                }
                if (hasMingw) {
                    val dir = windowsGen.get().asFile
                    dir.mkdirs()
                    writeBootstrap(
                        File(dir, "ComposeKnWindowsBootstrap.kt"),
                        platform = "windows",
                        importRegister = "com.composekn.windows.registerComposeKnWindowsBackend",
                        importInstall = "com.composekn.windows.installComposeKnWindowsAutoRegister",
                        registerCall = "registerComposeKnWindowsBackend()",
                        installCall = "installComposeKnWindowsAutoRegister()",
                    )
                }
            }
        }

        project.tasks.withType(KotlinNativeCompile::class.java).configureEach {
            dependsOn(generate)
        }

        fun addDep(
            sourceSetName: String,
            projectPath: String,
            mavenArtifact: String,
            genDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        ) {
            kotlin.sourceSets.findByName(sourceSetName)?.let { ss ->
                ss.kotlin.srcDir(genDir)
                ss.dependencies {
                    if (ext.useProjectDependencies.get() &&
                        project.rootProject.findProject(projectPath) != null
                    ) {
                        implementation(project.project(projectPath))
                    } else {
                        implementation("com.composekn:$mavenArtifact:${ext.composeknVersion.get()}")
                    }
                }
            }
        }
        if (hasLinux) {
            addDep("linuxX64Main", ":compose-kn-linux", "compose-kn-linux", linuxGen)
        }
        if (hasMingw) {
            addDep("mingwX64Main", ":compose-kn-windows", "compose-kn-windows", windowsGen)
        }

        if (!ext.wrapEntryPoint.get()) return

        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            val target = this
            val isLinux = target.name == "linuxX64"
            val isMingw = target.name == "mingwX64"
            if (!isLinux && !isMingw) return@configureEach
            binaries.withType(Executable::class.java).configureEach {
                val userEntry = entryPoint ?: return@configureEach
                if (userEntry.startsWith("composekn.host.generated.")) return@configureEach
                val wrapperName = if (isLinux) {
                    "composekn.host.generated.composeknLinuxHostMain"
                } else {
                    "composekn.host.generated.composeknWindowsHostMain"
                }
                val platformGen = if (isLinux) linuxGen else windowsGen
                generate.configure {
                    doLast {
                        val dir = platformGen.get().asFile
                        dir.mkdirs()
                        val userFq = userEntry
                        val fileName =
                            if (isLinux) "ComposeKnLinuxEntry.kt" else "ComposeKnWindowsEntry.kt"
                        val funName =
                            if (isLinux) "composeknLinuxHostMain" else "composeknWindowsHostMain"
                        val registerImport = if (isLinux) {
                            "com.composekn.linux.registerComposeKnLinuxBackend"
                        } else {
                            "com.composekn.windows.registerComposeKnWindowsBackend"
                        }
                        val registerCall = if (isLinux) {
                            "registerComposeKnLinuxBackend()"
                        } else {
                            "registerComposeKnWindowsBackend()"
                        }
                        File(dir, fileName).writeText(
                            """
                            |package composekn.host.generated
                            |
                            |import $registerImport
                            |
                            |/** 由 com.composekn.host 生成：先登记后端，再进入用户 entryPoint。 */
                            |fun $funName(args: Array<String> = emptyArray()) {
                            |    $registerCall
                            |    $userFq(args)
                            |}
                            |
                            """.trimMargin(),
                        )
                    }
                }
                entryPoint = wrapperName
                project.logger.lifecycle(
                    "ComposeKN host: ${target.name} entryPoint $userEntry → $wrapperName",
                )
            }
        }
    }

    private fun writeBootstrap(
        file: File,
        platform: String,
        importRegister: String,
        importInstall: String,
        registerCall: String,
        installCall: String,
    ) {
        file.writeText(
            """
            |package composekn.host.generated
            |
            |import $importInstall
            |import $importRegister
            |
            |@Suppress("unused")
            |internal val composekn${platform.replaceFirstChar { it.uppercase() }}HostBootstrap: Boolean =
            |    run {
            |        $installCall
            |        $registerCall
            |        true
            |    }
            |
            """.trimMargin(),
        )
    }

    companion object {
        const val DEFAULT_VERSION = "0.5.56"
    }
}

abstract class ComposeKnHostExtension {
    /** true：本仓库内用 project()；false：走 Maven `com.composekn:compose-kn-*` */
    abstract val useProjectDependencies: Property<Boolean>
    abstract val composeknVersion: Property<String>
    /** true：把 executable entryPoint 包一层自动 register（推荐） */
    abstract val wrapEntryPoint: Property<Boolean>
}

package com.composekn.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.Executable
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.io.File

/**
 * ComposeKN 资源管线（对齐官方 composeResources 布局的 native 子集）。
 *
 * 源：`src/commonMain/composeResources/font/` 下 ttf/otf + [ComposeKnResourcesExtension.extraFontDirs]
 * 生成：`Res.font.<name>`；链接后 copy 到 exe 旁 `composeResources/font/`。
 */
class ComposeKnResourcesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create(
            "composeKnResources",
            ComposeKnResourcesExtension::class.java,
        )
        ext.packageName.convention("composekn.resources.generated")
        // 仅当目录存在时再挂 InputDirectory，避免样本工程无 composeResources 时任务校验失败
        val defaultRes = project.file("src/commonMain/composeResources")
        if (defaultRes.isDirectory) {
            ext.resourcesDir.convention(
                project.layout.projectDirectory.dir("src/commonMain/composeResources"),
            )
        }
        ext.extraFontDirs.convention(emptyList())

        // 尽早注册 generate，便于 build.gradle 里 tasks.named(...) 挂依赖
        val genDir = project.layout.buildDirectory.dir("generated/composekn/resources")
        val generate = project.tasks.register(
            "generateComposeKnResources",
            GenerateComposeKnResourcesTask::class.java,
        ) {
            packageName.set(ext.packageName)
            resourcesDirectory.set(ext.resourcesDir)
            extraFontDirectories.set(ext.extraFontDirs)
            outputDirectory.set(genDir)
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate { configure(project, ext, generate, genDir) }
        }
    }

    private fun configure(
        project: Project,
        ext: ComposeKnResourcesExtension,
        generate: org.gradle.api.tasks.TaskProvider<GenerateComposeKnResourcesTask>,
        genDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
    ) {
        val kotlin = project.extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return
        kotlin.sourceSets.findByName("commonMain")?.kotlin?.srcDir(genDir)

        project.tasks.withType(KotlinNativeCompile::class.java).configureEach {
            dependsOn(generate)
        }

        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            val target = this
            binaries.withType(Executable::class.java).configureEach {
                val binary = this
                val copyName =
                    "copyComposeKnResources_${target.name}_${binary.buildType.getName()}"
                val copy = project.tasks.register(copyName) {
                    dependsOn(generate)
                    dependsOn(binary.linkTaskProvider)
                    doLast {
                        val outParent = binary.outputFile.parentFile
                        val fontOut = File(outParent, "composeResources/font")
                        fontOut.mkdirs()
                        val fontFiles = linkedMapOf<String, File>()
                        fun add(dir: File?) {
                            if (dir == null || !dir.isDirectory) return
                            dir.listFiles()
                                ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
                                ?.forEach { fontFiles[it.name] = it }
                        }
                        add(ext.resourcesDir.orNull?.asFile?.resolve("font"))
                        ext.extraFontDirs.get().forEach { add(File(it)) }
                        fontFiles.values.forEach { src ->
                            src.copyTo(File(fontOut, src.name), overwrite = true)
                        }
                        logger.lifecycle(
                            "ComposeKN resources: copied ${fontFiles.size} font(s) → $fontOut",
                        )
                    }
                }
                binary.linkTaskProvider.configure { finalizedBy(copy) }
            }
        }
    }
}

abstract class ComposeKnResourcesExtension {
    abstract val packageName: Property<String>
    abstract val resourcesDir: DirectoryProperty
    abstract val extraFontDirs: ListProperty<String>
}

abstract class GenerateComposeKnResourcesTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:InputDirectory
    @get:Optional
    abstract val resourcesDirectory: DirectoryProperty

    @get:Input
    abstract val extraFontDirectories: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val fonts = collectFonts(
            resourcesDirectory.orNull?.asFile,
            extraFontDirectories.get(),
        )
        val outRoot = outputDirectory.get().asFile
        outRoot.deleteRecursively()
        val pkg = packageName.get()
        val dir = File(outRoot, pkg.replace('.', '/'))
        dir.mkdirs()

        val fontProps = fonts.entries.sortedBy { it.key }.joinToString("\n") { (id, file) ->
            val rel = "font/${file.name}"
            val dev = file.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
            """
            |        val $id: FontResource = fontResource(
            |            identity = "$id",
            |            relativePath = "$rel",
            |            developmentPath = "$dev",
            |        )
            """.trimMargin()
        }

        File(dir, "Res.kt").writeText(
            """
            |package $pkg
            |
            |import com.composekn.resources.FontResource
            |import com.composekn.resources.fontResource
            |
            |// ComposeKN 生成的资源入口（对齐官方 Res.font.* 用法）。
            |// 由 generateComposeKnResources 生成 —— 勿手改。
            |object Res {
            |    object font {
            |$fontProps
            |    }
            |}
            |
            """.trimMargin(),
        )

        logger.lifecycle(
            "ComposeKN resources: generated Res.font (${fonts.size}) → ${dir.resolve("Res.kt")}",
        )
    }

    companion object {
        fun collectFonts(resourcesRoot: File?, extraDirs: List<String>): Map<String, File> {
            val fonts = linkedMapOf<String, File>()
            fun addDir(dir: File?) {
                if (dir == null || !dir.isDirectory) return
                dir.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in setOf("ttf", "otf") }
                    ?.sortedBy { it.name }
                    ?.forEach { fonts[it.nameWithoutExtension] = it }
            }
            addDir(resourcesRoot?.resolve("font"))
            extraDirs.forEach { addDir(File(it)) }
            return fonts
        }
    }
}

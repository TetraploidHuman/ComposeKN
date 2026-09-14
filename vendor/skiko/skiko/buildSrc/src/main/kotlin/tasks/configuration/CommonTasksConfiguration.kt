package tasks.configuration

import Arch
import OS
import SkiaBuildType
import SkikoProperties
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool
import registerSkikoTask
import skiaVersion
import supportAndroid
import supportAwt
import supportNativeIosArm64
import supportNativeIosSimulatorArm64
import supportNativeIosX64
import supportNativeLinux
import supportNativeMac
import supportNativeTvosArm64
import supportNativeTvosSimulatorArm64
import supportNativeTvosX64
import supportWeb
import toTitleCase
import java.io.File

private fun Project.appleToolchainOutputOrNull(vararg args: String): String? =
    runCatching {
        providers.exec {
            commandLine("xcrun", *args)
        }.standardOutput.asText.get().trim()
    }.getOrNull()?.ifBlank { null }

fun Project.appleToolchainExecutableOrDefault(tool: String, fallback: String): String =
    appleToolchainOutputOrNull("--find", tool) ?: fallback

fun Project.appleMacOsSdkFlags(): List<String> =
    appleToolchainOutputOrNull("--sdk", "macosx", "--show-sdk-path")
        ?.let { listOf("-isysroot", it) }
        ?: emptyList()

fun skiaHeadersDirs(skiaDir: File): List<File> =
    listOf(
        skiaDir,
        skiaDir.resolve("include"),
        skiaDir.resolve("include/core"),
        skiaDir.resolve("include/gpu"),
        skiaDir.resolve("include/effects"),
        skiaDir.resolve("include/pathops"),
        skiaDir.resolve("include/utils"),
        skiaDir.resolve("include/codec"),
        skiaDir.resolve("include/svg"),
        skiaDir.resolve("modules/jsonreader"),
        skiaDir.resolve("modules/skottie/include"),
        skiaDir.resolve("modules/skparagraph/include"),
        skiaDir.resolve("modules/skshaper/include"),
        skiaDir.resolve("modules/skunicode/include"),
        skiaDir.resolve("modules/sksg/include"),
        skiaDir.resolve("modules/svg/include"),
        skiaDir.resolve("third_party/externals/harfbuzz/src"),
        skiaDir.resolve("third_party/icu"),
        skiaDir.resolve("third_party/externals/icu/source/common"),
    )

fun includeHeadersFlags(headersDirs: List<File>) =
    headersDirs.map { "-I${it.absolutePath}" }.toTypedArray()

fun skiaPreprocessorFlags(
    os: OS,
    buildType: SkiaBuildType,
    mingwSkiaDir: String? = null,
): Array<String> {
    val base = listOf(
        "-DSK_ALLOW_STATIC_GLOBAL_INITIALIZERS=1",
        "-DSK_FORCE_DISTANCE_FIELD_TEXT=0",
        "-DSK_GAMMA_APPLY_TO_A8",
        "-DSK_GAMMA_SRGB",
        "-DSK_SCALAR_TO_FLOAT_EXCLUDED",
        "-DSK_SUPPORT_GPU=1",
        "-DSK_GANESH",
        "-DSK_GL",
        "-DSK_SHAPER_HARFBUZZ_AVAILABLE",
        "-DSK_UNICODE_AVAILABLE",
        "-DSK_SHAPER_UNICODE_AVAILABLE",
        "-DSK_SUPPORT_OPENCL=0",
        "-DSK_UNICODE_AVAILABLE",
        "-DSK_USING_THIRD_PARTY_ICU",
        // For ICU symbols renaming:
        "-DU_DISABLE_RENAMING=0",
        "-DU_DISABLE_VERSION_SUFFIX=1",
        "-DU_HAVE_LIB_SUFFIX=1",
        "-DU_LIB_SUFFIX_C_NAME=_skiko",
        *buildType.flags
    )

    val perOs = when (os) {
        OS.MacOS -> listOf(
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_BUILD_FOR_MAC",
            "-DSK_METAL"
        )
        OS.IOS -> listOf(
            "-DSK_BUILD_FOR_IOS",
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_METAL"
        )
        OS.TVOS -> listOf(
            "-DSK_BUILD_FOR_IOS",
            "-DSK_BUILD_FOR_TVOS",
            "-DSK_SHAPER_CORETEXT_AVAILABLE",
            "-DSK_METAL"
        )
        OS.Windows -> listOf(
            "-DSK_BUILD_FOR_WIN",
            "-D_CRT_SECURE_NO_WARNINGS",
            "-D_HAS_EXCEPTIONS=0",
            "-DWIN32_LEAN_AND_MEAN",
            "-DNOMINMAX",
            "-DSK_GAMMA_APPLY_TO_A8",
            // ComposeKN: 上游这里无条件定义 SK_DIRECT3D / SK_ANGLE（因为它假设 Windows
            // 版 Skia 一定编了 D3D/ANGLE）。我们用的是自建 GNU-ABI Skia，gn args 里
            // skia_use_direct3d / skia_use_angle 都是 false —— 若仍然定义这两个宏，
            // nativeJsMain/cpp 里的包装函数就会走 D3D 分支、引用 Skia 里不存在的符号
            // （GrDirectContexts::MakeD3D / GrBackendRenderTargets::MakeD3D）→ K/N 链接失败。
            // 所以按「实际链接的那份 Skia 的 args.gn」决定；取不到就按不开处理
            // （那时包装函数走 #else 分支返回 nullptr，语义与上游「后端不可用」一致）。
            *mingwGpuBackendFlags(mingwSkiaDir)
        )
        OS.Linux -> listOf(
            "-DSK_BUILD_FOR_LINUX",
            "-D_GLIBCXX_USE_CXX11_ABI=0"
        )
        OS.Wasm -> listOf(
            "-DSKIKO_WASM",
            "-sSUPPORT_LONGJMP=wasm"
        )
        OS.Android -> listOf(
            "-DSK_BUILD_FOR_ANDROID"
        )
    }

    return (base + perOs).toTypedArray()
}

fun Project.configureSignAndPublishDependencies() {
    if (supportWeb) {
        tasks.configureEach {
            val publishJs = "publishJsPublicationTo"
            val publishWasm = "publishSkikoWasmRuntimePublicationTo"
            val publishWasmPub = "publishWasmJsPublicationTo"
            val signWasm = "signSkikoWasmRuntimePublication"
            val signJs = "signJsPublication"
            val signWasmPub = "signWasmJsPublication"

            when {
                name.startsWith(publishJs) -> dependsOn(signWasm, signWasmPub)
                name.startsWith(publishWasm) -> dependsOn(signJs)
                name.startsWith(publishWasmPub) -> dependsOn(signJs)
                name.startsWith(signWasmPub) -> dependsOn(signWasm)
            }
        }
    }
    if (supportAndroid) {
        tasks.configureEach {
            val signAndroid = "signAndroidPublication"
            val generateMetadata = "generateMetadataFileForAndroidPublication"
            val publishAndroid = "publishAndroidPublicationTo"
            val publishX64 = "publishSkikoJvmRuntimeAndroidX64PublicationTo"
            val publishArm64 = "publishSkikoJvmRuntimeAndroidArm64PublicationTo"
            val signX64 = "signSkikoJvmRuntimeAndroidX64Publication"
            val signArm64 = "signSkikoJvmRuntimeAndroidArm64Publication"
            val skikoAndroidArtifact = "skikoAndroidArtifact"

            when {
                name.startsWith(signAndroid) || name.startsWith(generateMetadata) -> {
                    dependsOn(skikoAndroidArtifact)
                }
                name.startsWith(publishAndroid) -> {
                    dependsOn(signX64, signArm64)
                }
                name.startsWith(publishX64) -> {
                    dependsOn(signAndroid, signArm64)
                }
                name.startsWith(publishArm64) -> {
                    dependsOn(signX64, signAndroid)
                }
            }
        }
    }

    if (supportNativeLinux) {
        val publishLinuxX64 = "publishLinuxX64PublicationTo"
        val publishLinuxArm64 = "publishLinuxArm64PublicationTo"
        val signLinuxArm64Publication = "signLinuxArm64Publication"
        val signLinuxX64Publication = "signLinuxX64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishLinuxX64) -> {
                    dependsOn(signLinuxArm64Publication)
                    dependsOn(signLinuxX64Publication)
                }

                name.startsWith(publishLinuxArm64) -> {
                    dependsOn(signLinuxArm64Publication)
                    dependsOn(signLinuxX64Publication)
                }
            }
        }
    }

    if (supportNativeMac) {
        val publishMacosArm64 = "publishMacosArm64PublicationTo"
        val publishMacosX64 = "publishMacosX64PublicationTo"
        val signMacosArm64 = "signMacosArm64Publication"
        val signMacosX64 = "signMacosX64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishMacosArm64) -> {
                    dependsOn(signMacosArm64)
                    dependsOn(signMacosX64)
                }
                name.startsWith(publishMacosX64) -> {
                    dependsOn(signMacosArm64)
                    dependsOn(signMacosX64)
                }
            }
        }
    }

    // iOS family
    if (supportNativeIosArm64 || supportNativeIosSimulatorArm64 || supportNativeIosX64) {
        val publishIosArm64 = "publishIosArm64PublicationTo"
        val publishIosSimArm64 = "publishIosSimulatorArm64PublicationTo"
        val publishIosX64 = "publishIosX64PublicationTo"
        val signIosArm64 = "signIosArm64Publication"
        val signIosSimArm64 = "signIosSimulatorArm64Publication"
        val signIosX64 = "signIosX64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishIosArm64) -> {
                    if (supportNativeIosArm64) dependsOn(signIosArm64)
                    if (supportNativeIosSimulatorArm64) dependsOn(signIosSimArm64)
                    if (supportNativeIosX64) dependsOn(signIosX64)
                }
                name.startsWith(publishIosSimArm64) -> {
                    if (supportNativeIosArm64) dependsOn(signIosArm64)
                    if (supportNativeIosSimulatorArm64) dependsOn(signIosSimArm64)
                    if (supportNativeIosX64) dependsOn(signIosX64)
                }
                name.startsWith(publishIosX64) -> {
                    if (supportNativeIosArm64) dependsOn(signIosArm64)
                    if (supportNativeIosSimulatorArm64) dependsOn(signIosSimArm64)
                    if (supportNativeIosX64) dependsOn(signIosX64)
                }
            }
        }
    }

    // tvOS family
    if (supportNativeTvosArm64 || supportNativeTvosSimulatorArm64 || supportNativeTvosX64) {
        val publishTvosArm64 = "publishTvosArm64PublicationTo"
        val publishTvosSimArm64 = "publishTvosSimulatorArm64PublicationTo"
        val publishTvosX64 = "publishTvosX64PublicationTo"
        val signTvosArm64 = "signTvosArm64Publication"
        val signTvosSimArm64 = "signTvosSimulatorArm64Publication"
        val signTvosX64 = "signTvosX64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishTvosArm64) -> {
                    if (supportNativeTvosArm64) dependsOn(signTvosArm64)
                    if (supportNativeTvosSimulatorArm64) dependsOn(signTvosSimArm64)
                    if (supportNativeTvosX64) dependsOn(signTvosX64)
                }
                name.startsWith(publishTvosSimArm64) -> {
                    if (supportNativeTvosArm64) dependsOn(signTvosArm64)
                    if (supportNativeTvosSimulatorArm64) dependsOn(signTvosSimArm64)
                    if (supportNativeTvosX64) dependsOn(signTvosX64)
                }
                name.startsWith(publishTvosX64) -> {
                    if (supportNativeTvosArm64) dependsOn(signTvosArm64)
                    if (supportNativeTvosSimulatorArm64) dependsOn(signTvosSimArm64)
                    if (supportNativeTvosX64) dependsOn(signTvosX64)
                }
            }
        }
    }

    if (supportAwt) {
        val publishJvmRuntimeAngleX64 = "publishSkikoJvmRuntimeAngleWindowsX64PublicationToComposeRepoRepository"
        val publishJvmRuntimeAngleArm64 = "publishSkikoJvmRuntimeAngleWindowsArm64PublicationToComposeRepoRepository"
        val signJvmRuntimeX64 = "signSkikoJvmRuntimeWindowsX64Publication"
        val signJvmRuntimeArm64 = "signSkikoJvmRuntimeWindowsArm64Publication"

        tasks.configureEach {
            when {
                name.startsWith(publishJvmRuntimeAngleX64) -> {
                    dependsOn(signJvmRuntimeX64)
                }
                name.startsWith(publishJvmRuntimeAngleArm64) -> {
                    dependsOn(signJvmRuntimeArm64)
                }
            }
        }
    }

    // Cross-publication pairs due to shared javadoc: KotlinMultiplatform <-> AWT
    tasks.configureEach {
        val publishKmp = "publishKotlinMultiplatformPublicationTo"
        val publishAwt = "publishAwtPublicationTo"
        val publishAwtRuntimeElements = "publishAwtRuntimeElementsPublicationTo"
        val signKmp = "signKotlinMultiplatformPublication"
        val signAwt = "signAwtPublication"
        val signAwtRuntimeElements = "signAwtRuntimeElementsPublication"

        when {
            name.startsWith(publishKmp) -> {
                if (supportAwt) {
                    dependsOn(signAwt)
                    dependsOn(signAwtRuntimeElements)
                }
            }
            name.startsWith(publishAwt) -> {
                dependsOn(signKmp)
                dependsOn(signAwtRuntimeElements)
            }
            name.startsWith(publishAwtRuntimeElements) -> {
                dependsOn(signAwt)
                dependsOn(signKmp)
            }
        }
    }
}

fun KotlinTarget.generateVersion(
    targetOs: OS,
    targetArch: Arch,
    skikoProperties: SkikoProperties,
    compilationName: String = "main"
) {
    val targetName = this.name
    val isUikitSim = isUikitSimulator()
    val generatedDir = project.layout.buildDirectory.dir("generated/$targetName")
    val generateVersionTask = project.registerSkikoTask<DefaultTask>(
        "generateVersion${toTitleCase(platformType.name)}".withSuffix(isUikitSim = isUikitSim),
        targetOs,
        targetArch
    ) {
        inputs.property("buildType", skikoProperties.buildType.id)
        outputs.dir(generatedDir)
        doFirst {
            val outDir = generatedDir.get().asFile
            outDir.deleteRecursively()
            outDir.mkdirs()
            val out = "$outDir/Version.kt"

            val target = "${targetOs.id}-${targetArch.id}"
            val skiaTag = project.skiaVersion(target)

            File(out).writeText(
                """
                package org.jetbrains.skiko
                object Version {
                val skiko = "${skikoProperties.deployVersion}"
                val skia = "$skiaTag"
                }
                """.trimIndent()
            )
        }
    }

    // Needs to be lazily loaded as android compilations are not available right away
    compilations.matching { it.name == compilationName }.configureEach {
        compileTaskProvider.configure {
            dependsOn(generateVersionTask)
            (this as KotlinCompileTool).source(generatedDir.get().asFile)
        }
    }
}


/**
 * ComposeKN: 找到「实际链接的那份 Skia」的 args.gn。
 *
 * 查找顺序：gradle 属性 `skiko.skia.mingw.dir`（预编译包是 <pkg>/libs，源码构建是
 * skia/out/mingw）→ 环境变量 SKIA_MINGW_PREBUILT / SKIA_MINGW_WORK。
 */
private fun mingwSkiaArgsGn(mingwSkiaDir: String?): File? {
    val candidates = listOfNotNull(
        mingwSkiaDir?.let { File(it, "args.gn") },
        System.getenv("SKIA_MINGW_PREBUILT")?.let { File(it, "libs/args.gn") },
        System.getenv("SKIA_MINGW_WORK")?.let { File(it, "skia/out/mingw/args.gn") },
    )
    return candidates.firstOrNull { it.isFile }
}

/** args.gn 里某个开关是不是 true。 */
private fun mingwGnArgEnabled(mingwSkiaDir: String?, name: String): Boolean {
    val argsGn = mingwSkiaArgsGn(mingwSkiaDir) ?: return false
    return Regex("""(^|\s)$name\s*=\s*true""").containsMatchIn(argsGn.readText())
}

/**
 * ComposeKN: Windows bridge 要不要定义 `SK_DIRECT3D` / `SK_ANGLE`。
 *
 * 上游是无条件定义的（它假设 Windows 版 Skia 一定编了 D3D/ANGLE）。我们用的是自建
 * GNU-ABI Skia，gn args 里这些后端可能全是 false —— 若仍然定义这两个宏，
 * `nativeJsMain/cpp` 里的包装函数就会走 D3D 分支、引用 Skia 里不存在的符号
 * （`GrDirectContexts::MakeD3D` …）→ K/N 链接失败。
 *
 * 所以按**实际链接的那份 Skia**的 args.gn 决定。拿不到 args.gn 时：
 *   - 调用方没给 dir（例如 JVM 路径）→ 保持上游行为（两个都定义）；
 *   - 调用方给了 dir 但文件不在 → 按「都没开」处理（我们 mingw 的保守默认）。
 */
fun mingwGpuBackendFlags(mingwSkiaDir: String? = null): Array<String> {
    val argsGn = mingwSkiaArgsGn(mingwSkiaDir)
    if (argsGn == null) {
        return if (mingwSkiaDir == null) arrayOf("-DSK_DIRECT3D", "-DSK_ANGLE") else emptyArray()
    }
    val flags = mutableListOf<String>()
    if (mingwGnArgEnabled(mingwSkiaDir, "skia_use_direct3d")) flags += "-DSK_DIRECT3D"
    if (mingwGnArgEnabled(mingwSkiaDir, "skia_use_angle")) flags += "-DSK_ANGLE"
    return flags.toTypedArray()
}

/**
 * ComposeKN: 这份 MinGW Skia 到底有没有 GPU 后端。
 *
 * 用来决定要不要定义 `SKIKO_MINGW_NO_GPU`（那个宏会把 Surface/Image 的 GPU 入口
 * 整个打桩成 return nullptr）。拿不到 args.gn 时按「没有 GPU」处理 = 保持旧行为。
 */
fun mingwSkiaHasGpuBackend(mingwSkiaDir: String?): Boolean =
    listOf("skia_use_gl", "skia_use_direct3d", "skia_use_metal", "skia_use_vulkan")
        .any { mingwGnArgEnabled(mingwSkiaDir, it) }

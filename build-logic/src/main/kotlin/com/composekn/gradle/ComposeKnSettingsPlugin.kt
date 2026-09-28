package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.initialization.Settings
import java.net.URI

/**
 * 消费者 settings 插件：加 GitHub Packages 仓库，并把官方 Compose/Skiko 坐标
 * 顶成 ComposeKN 已发布的 `com.composekn.compose:*` / `com.composekn:skiko-*`。
 *
 * ```
 * pluginManagement { includeBuild("…/ComposeKN/build-logic") }
 * plugins { id("com.composekn.settings") }
 * ```
 *
 * Skiko 按 configuration 名顶到平台坐标（避开合成根 available-at 解析问题）；
 * 无 linux/mingw 线索的配置仍顶到合成根 `com.composekn:skiko`。
 */
class ComposeKnSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val composeknVer = settings.providers.gradleProperty("composekn.version")
            .orElse(DEFAULT_VERSION).get()
        val uiVer = settings.providers.gradleProperty("composekn.compose.ui.version")
            .orElse("1.12.1-ckn.$composeknVer").get()
        val skipSub = settings.providers.gradleProperty("composekn.settings.skipSubstitution")
            .orNull == "true"
        val owner = settings.providers.gradleProperty("composekn.publish.github.owner")
            .orElse(settings.providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
            .orElse("TetraploidHuman").get()
        val repo = settings.providers.gradleProperty("composekn.publish.github.repo")
            .orElse("ComposeKN").get()

        settings.dependencyResolutionManagement.repositories.maven {
            name = "ComposeKNGitHubPackages"
            url = URI.create("https://maven.pkg.github.com/$owner/$repo")
            credentials {
                username = settings.providers.environmentVariable("GITHUB_ACTOR")
                    .orElse(settings.providers.gradleProperty("gpr.user"))
                    .orElse("github")
                    .get()
                password = settings.providers.environmentVariable("GITHUB_TOKEN")
                    .orElse(settings.providers.gradleProperty("gpr.token"))
                    .orElse("")
                    .get()
            }
        }

        if (skipSub) return

        settings.gradle.beforeProject {
            configurations.configureEach {
                val confName = name
                resolutionStrategy.dependencySubstitution {
                    applyUiSubstitutions(this, uiVer)
                    val skikoTarget = skikoCoordinateForConfiguration(confName, composeknVer)
                    substitute(module("org.jetbrains.skiko:skiko"))
                        .using(module(skikoTarget))
                    substitute(module("org.jetbrains.compose.components:components-resources"))
                        .using(module("com.composekn:compose-kn-resources:$composeknVer"))
                }
            }
        }
    }

    private fun skikoCoordinateForConfiguration(confName: String, ver: String): String {
        val lower = confName.lowercase()
        return when {
            lower.contains("linuxx64") || lower.contains("linux_x64") ->
                "com.composekn:skiko-linuxx64:$ver"
            lower.contains("mingwx64") || lower.contains("mingw_x64") ->
                "com.composekn:skiko-mingwx64:$ver"
            else -> "com.composekn:skiko:$ver"
        }
    }

    private fun applyUiSubstitutions(subs: DependencySubstitutions, uiVer: String) {
        val uiModules = listOf(
            "ui-util", "ui-geometry", "ui-unit", "ui-graphics", "ui-text",
            "ui-backhandler", "ui",
        )
        for (m in uiModules) {
            subs.substitute(subs.module("org.jetbrains.compose.ui:$m"))
                .using(subs.module("com.composekn.compose:$m:$uiVer"))
        }
        val other = listOf(
            "org.jetbrains.compose.foundation:foundation" to "foundation",
            "org.jetbrains.compose.foundation:foundation-layout" to "foundation-layout",
            "org.jetbrains.compose.animation:animation" to "animation",
            "org.jetbrains.compose.animation:animation-core" to "animation-core",
            "org.jetbrains.compose.material:material-ripple" to "material-ripple",
            "org.jetbrains.compose.material3:material3" to "material3",
            // JetBrains 映射坐标；消费者若直接写 androidx 仍可能需自行对顶
            "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose" to
                "lifecycle-viewmodel-compose",
        )
        for ((from, artifact) in other) {
            subs.substitute(subs.module(from))
                .using(subs.module("com.composekn.compose:$artifact:$uiVer"))
        }
    }

    companion object {
        const val DEFAULT_VERSION = "0.5.65"
    }
}

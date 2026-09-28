package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.initialization.Settings
import java.net.URI

/**
 * 消费者 settings 插件：加 GitHub Packages 仓库，并把官方 Compose/Skiko 坐标
 * 顶成 ComposeKN 已发布的 `com.composekn.compose:*` / `com.composekn:skiko`。
 *
 * ```
 * pluginManagement { includeBuild("…/ComposeKN/build-logic") }
 * plugins { id("com.composekn.settings") }
 * ```
 */
class ComposeKnSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val composeknVer = settings.providers.gradleProperty("composekn.version")
            .orElse("0.5.61").get()
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
                resolutionStrategy.dependencySubstitution {
                    applyUiSubstitutions(this, uiVer)
                    substitute(module("org.jetbrains.skiko:skiko"))
                        .using(module("com.composekn:skiko:$composeknVer"))
                    substitute(module("org.jetbrains.compose.components:components-resources"))
                        .using(module("com.composekn:compose-kn-resources:$composeknVer"))
                }
            }
        }
    }

    private fun applyUiSubstitutions(subs: DependencySubstitutions, uiVer: String) {
        val uiModules = listOf(
            "ui-util", "ui-geometry", "ui-unit", "ui-graphics", "ui-text", "ui",
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
        )
        for ((from, artifact) in other) {
            subs.substitute(subs.module(from))
                .using(subs.module("com.composekn.compose:$artifact:$uiVer"))
        }
    }
}

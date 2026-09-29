package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.artifacts.DependencySubstitutions
import org.gradle.api.initialization.Settings
import java.io.File
import java.net.URI

/**
 * 消费者 settings 插件：可选加本地 Maven / GitHub Packages，并把官方 Compose/Skiko
 * 顶成 ComposeKN 已发布坐标。
 *
 * ```
 * // gradle.properties
 * composekn.version=0.5.66
 * composekn.maven.local=/home/me/composekn-m2   # Release zip 解压目录（推荐）
 * composekn.settings.skipGithubPackages=true    # 纯本地时关掉 Packages
 * plugins { id("com.composekn.settings") }
 * ```
 *
 * linuxX64/mingwX64 configuration 顶到平台坐标，避开合成根 available-at。
 */
class ComposeKnSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        val composeknVer = settings.providers.gradleProperty("composekn.version")
            .orElse(DEFAULT_VERSION).get()
        val uiVer = settings.providers.gradleProperty("composekn.compose.ui.version")
            .orElse("1.12.1-ckn.$composeknVer").get()
        val skipSub = settings.providers.gradleProperty("composekn.settings.skipSubstitution")
            .orNull == "true"
        val localMaven = settings.providers.gradleProperty("composekn.maven.local")
            .orElse(settings.providers.environmentVariable("COMPOSEKN_MAVEN_LOCAL"))
            .orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val skipGh = settings.providers.gradleProperty("composekn.settings.skipGithubPackages")
            .orNull == "true" ||
            settings.providers.environmentVariable("COMPOSEKN_SKIP_GITHUB_PACKAGES")
                .orNull == "true"
        val owner = settings.providers.gradleProperty("composekn.publish.github.owner")
            .orElse(settings.providers.environmentVariable("GITHUB_REPOSITORY_OWNER"))
            .orElse("TetraploidHuman").get()
        val repo = settings.providers.gradleProperty("composekn.publish.github.repo")
            .orElse("ComposeKN").get()

        // 本地 Release zip / 本机 publish：优先
        if (localMaven != null) {
            val dir = File(localMaven)
            require(dir.isDirectory) {
                "composekn.maven.local is not a directory: $localMaven"
            }
            settings.dependencyResolutionManagement.repositories.maven {
                name = "ComposeKNLocal"
                url = dir.toURI()
            }
            settings.pluginManagement.repositories.maven {
                name = "ComposeKNLocal"
                url = dir.toURI()
            }
        }

        if (!skipGh) {
            val ghUri = URI.create("https://maven.pkg.github.com/$owner/$repo")
            val user = settings.providers.environmentVariable("GITHUB_ACTOR")
                .orElse(settings.providers.gradleProperty("gpr.user"))
                .orElse("github")
            val token = settings.providers.environmentVariable("GITHUB_TOKEN")
                .orElse(settings.providers.gradleProperty("gpr.token"))
                .orElse("")
            settings.dependencyResolutionManagement.repositories.maven {
                name = "ComposeKNGitHubPackages"
                url = ghUri
                credentials {
                    username = user.get()
                    password = token.get()
                }
            }
            // pluginManagement 也挂一份，便于 id("com.composekn.settings") version "…"
            settings.pluginManagement.repositories.maven {
                name = "ComposeKNGitHubPackages"
                url = ghUri
                credentials {
                    username = user.get()
                    password = token.get()
                }
            }
        }

        if (skipSub) return

        settings.gradle.beforeProject {
            configurations.configureEach {
                val confName = name
                val platform = nativePlatformHint(confName)
                resolutionStrategy.dependencySubstitution {
                    applyUiSubstitutions(this, uiVer, platform)
                    substitute(module("org.jetbrains.skiko:skiko"))
                        .using(module(skikoCoordinate(platform, composeknVer)))
                    substitute(module("org.jetbrains.compose.components:components-resources"))
                        .using(module(resourcesCoordinate(platform, composeknVer)))
                }
            }
        }
    }

    private fun nativePlatformHint(confName: String): String? {
        val lower = confName.lowercase()
        return when {
            lower.contains("linuxx64") || lower.contains("linux_x64") -> "linuxx64"
            lower.contains("mingwx64") || lower.contains("mingw_x64") -> "mingwx64"
            else -> null
        }
    }

    private fun skikoCoordinate(platform: String?, ver: String): String =
        when (platform) {
            "linuxx64" -> "com.composekn:skiko-linuxx64:$ver"
            "mingwx64" -> "com.composekn:skiko-mingwx64:$ver"
            else -> "com.composekn:skiko:$ver"
        }

    private fun resourcesCoordinate(platform: String?, ver: String): String =
        when (platform) {
            "linuxx64" -> "com.composekn:compose-kn-resources-linuxx64:$ver"
            "mingwx64" -> "com.composekn:compose-kn-resources-mingwx64:$ver"
            else -> "com.composekn:compose-kn-resources:$ver"
        }

    private fun applyUiSubstitutions(
        subs: DependencySubstitutions,
        uiVer: String,
        platform: String?,
    ) {
        fun coord(artifact: String): String =
            if (platform != null) {
                "com.composekn.compose:$artifact-$platform:$uiVer"
            } else {
                "com.composekn.compose:$artifact:$uiVer"
            }

        val uiModules = listOf(
            "ui-util", "ui-geometry", "ui-unit", "ui-graphics", "ui-text",
            "ui-backhandler", "ui",
        )
        for (m in uiModules) {
            subs.substitute(subs.module("org.jetbrains.compose.ui:$m"))
                .using(subs.module(coord(m)))
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
                .using(subs.module(coord(artifact)))
        }
        // lifecycle-viewmodel-compose：JetBrains 映射 + androidx 原坐标都顶
        val lifecycleArtifact = "lifecycle-viewmodel-compose"
        for (from in listOf(
            "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose",
            "androidx.lifecycle:lifecycle-viewmodel-compose",
        )) {
            subs.substitute(subs.module(from))
                .using(subs.module(coord(lifecycleArtifact)))
        }
    }

    companion object {
        const val DEFAULT_VERSION = "0.5.66"
    }
}

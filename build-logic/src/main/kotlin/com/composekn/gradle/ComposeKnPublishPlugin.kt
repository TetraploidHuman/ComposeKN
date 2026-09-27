package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * 给 compose-kn-* 挂 maven-publish（`com.composekn:<module>:<composekn.version>`）。
 *
 * 仓库：
 * 1. 本地 `build/maven-repo`
 * 2. `-Pcomposekn.publish.url=` 私服
 * 3. 若存在 `GITHUB_TOKEN`（或 `-Pcomposekn.publish.github=true`）：
 *    `https://maven.pkg.github.com/<owner>/<repo>`（默认 TetraploidHuman/ComposeKN）
 */
class ComposeKnPublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val version = project.findProperty("composekn.version")?.toString() ?: "0.5.55"
        project.group = project.findProperty("composekn.group")?.toString() ?: "com.composekn"
        project.version = version

        project.pluginManager.withPlugin("maven-publish") {
            project.extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "ComposeKnLocal"
                        url = project.uri(project.rootProject.layout.buildDirectory.dir("maven-repo"))
                    }
                    val remote = project.findProperty("composekn.publish.url")?.toString()
                    if (!remote.isNullOrBlank()) {
                        maven {
                            name = "ComposeKnRemote"
                            url = project.uri(remote)
                            credentialsFrom(project, this)
                        }
                    }
                    val ghToken = System.getenv("GITHUB_TOKEN")
                        ?: project.findProperty("composekn.publish.password")?.toString()
                    val wantGh = project.findProperty("composekn.publish.github")?.toString() == "true" ||
                        !ghToken.isNullOrBlank()
                    if (wantGh && !ghToken.isNullOrBlank()) {
                        val owner = project.findProperty("composekn.publish.github.owner")?.toString()
                            ?: System.getenv("GITHUB_REPOSITORY_OWNER")
                            ?: "TetraploidHuman"
                        val repo = project.findProperty("composekn.publish.github.repo")?.toString()
                            ?: System.getenv("GITHUB_REPOSITORY")?.substringAfter('/')
                            ?: "ComposeKN"
                        maven {
                            name = "GitHubPackages"
                            url = project.uri("https://maven.pkg.github.com/$owner/$repo")
                            credentials {
                                username = project.findProperty("composekn.publish.user")?.toString()
                                    ?: System.getenv("GITHUB_ACTOR")
                                    ?: "github"
                                password = ghToken
                            }
                        }
                    }
                }
            }
        }

        project.pluginManager.withPlugin("java-platform") {
            project.extensions.configure<PublishingExtension> {
                publications {
                    if (findByName("maven") == null) {
                        create<MavenPublication>("maven") {
                            from(project.components.getByName("javaPlatform"))
                            artifactId = project.name
                        }
                    }
                }
            }
        }
    }

    private fun credentialsFrom(
        project: Project,
        repo: org.gradle.api.artifacts.repositories.MavenArtifactRepository,
    ) {
        val u = project.findProperty("composekn.publish.user")?.toString()
            ?: System.getenv("GITHUB_ACTOR")
        val p = project.findProperty("composekn.publish.password")?.toString()
            ?: System.getenv("GITHUB_TOKEN")
        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
            repo.credentials {
                username = u
                password = p
            }
        }
    }
}

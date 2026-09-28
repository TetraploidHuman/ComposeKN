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
 * 仓库：本地 `build/maven-repo`、可选私服、GitHub Packages（`GITHUB_TOKEN`）。
 *
 * `-Pcomposekn.publish.skipMetadata=true`（发布脚本默认打开）：禁用全项目
 * `*KotlinMetadata` 任务，避开 skiko/compose-core 在 KN 2.4 下的
 * `@OptionalExpectation` 编译失败，仍可发 native klib。
 */
class ComposeKnPublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val version = project.findProperty("composekn.version")?.toString() ?: "0.5.65"
        project.group = project.findProperty("composekn.group")?.toString() ?: "com.composekn"
        project.version = version

        if (project.findProperty("composekn.publish.skipMetadata")?.toString() == "true") {
            project.rootProject.allprojects {
                tasks.configureEach {
                    if (name.contains("KotlinMetadata")) {
                        enabled = false
                    }
                }
            }
        }

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
                    // 仅显式 -Pcomposekn.publish.github=true 才挂 GitHub Packages，
                    // 避免 CI 环境里有 GITHUB_TOKEN 时 :publish 误传 / 409 Conflict。
                    val wantGh = project.findProperty("composekn.publish.github")?.toString() == "true"
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

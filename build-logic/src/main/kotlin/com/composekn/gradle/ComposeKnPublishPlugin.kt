package com.composekn.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * 给 compose-kn-* 挂 maven-publish（`com.composekn:<module>:<composekn.version>`）。
 * 默认仓库：`build/maven-repo`；可用 `-Pcomposekn.publish.url=` 指到私服。
 */
class ComposeKnPublishPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("maven-publish")
        val version = project.findProperty("composekn.version")?.toString() ?: "0.5.54"
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
                            val u = project.findProperty("composekn.publish.user")?.toString()
                            val p = project.findProperty("composekn.publish.password")?.toString()
                            if (!u.isNullOrBlank()) {
                                credentials {
                                    username = u
                                    password = p ?: ""
                                }
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
}

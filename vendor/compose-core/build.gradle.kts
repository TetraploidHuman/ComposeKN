import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}

/**
 * 发布到 ComposeKN 坐标（默认关；`-Pcomposekn.publish.composeUi=true` 打开）：
 *   group = com.composekn.compose
 *   version = 1.12.1-ckn.<composekn.version>
 *   artifactId = 子工程名（ui / foundation / material3 / …）
 *
 * 消费者用 settings 插件 `com.composekn.settings` 把
 * `org.jetbrains.compose.*` 顶成这些坐标，无需 includeBuild。
 */
val publishComposeUi =
    providers.gradleProperty("composekn.publish.composeUi").orNull == "true"
val composeknVer =
    providers.gradleProperty("composekn.version").orElse("0.5.57")
val composeUiVer =
    providers.gradleProperty("composekn.compose.ui.version")
        .orElse(composeknVer.map { "1.12.1-ckn.$it" })

subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            linuxX64()
            mingwX64()
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xexpect-actual-classes",
                    "-Xsuppress-warning=LESS_VISIBLE_TYPE_ACCESS_IN_INLINE_WARNING",
                    "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
                    "-opt-in=androidx.compose.ui.InternalComposeUiApi",
                    "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
                    "-opt-in=androidx.compose.runtime.ExperimentalComposeRuntimeApi",
                    "-opt-in=androidx.compose.runtime.InternalComposeApi",
                    "-opt-in=kotlinx.cinterop.ExperimentalForeignApi",
                    "-opt-in=kotlinx.cinterop.BetaInteropApi",
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                    "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                    "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                )
            }
        }

        if (publishComposeUi) {
            pluginManager.apply("maven-publish")
            group = "com.composekn.compose"
            version = composeUiVer.get()
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "ComposeKnLocal"
                        // 发到 ComposeKN 仓库根的 build/maven-repo（本工程在 vendor/compose-core）
                        url = uri(rootProject.projectDir.resolve("../../build/maven-repo"))
                    }
                    val ghToken = System.getenv("GITHUB_TOKEN")
                    if (!ghToken.isNullOrBlank()) {
                        val owner = System.getenv("GITHUB_REPOSITORY_OWNER") ?: "TetraploidHuman"
                        val repo = System.getenv("GITHUB_REPOSITORY")?.substringAfter('/') ?: "ComposeKN"
                        maven {
                            name = "GitHubPackages"
                            url = uri("https://maven.pkg.github.com/$owner/$repo")
                            credentials {
                                username = System.getenv("GITHUB_ACTOR") ?: "github"
                                password = ghToken
                            }
                        }
                    }
                }
            }
            logger.lifecycle(
                "ComposeKN: will publish ${project.path} as com.composekn.compose:${project.name}:$version",
            )
        }
    }
}

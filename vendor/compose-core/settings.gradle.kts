pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "compose-core"

// 仅当独立启动（CI: -p vendor/compose-core -Pcomposekn.compose.standalone=true）
val composeStandalone =
    settings.startParameter.projectProperties["composekn.compose.standalone"] == "true"
val usePublishedSkiko =
    settings.startParameter.projectProperties["composekn.usePublishedSkiko"] == "true"

if (composeStandalone) {
    // 解析已发布的 com.composekn:skiko（上一 job / 本机 maven-repo）
    dependencyResolutionManagement.repositories.apply {
        maven {
            name = "ComposeKnLocal"
            url = uri(settings.rootDir.resolve("../../build/maven-repo"))
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
    if (!usePublishedSkiko) {
        includeBuild("../skiko/skiko") {
            dependencySubstitution {
                substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
            }
        }
    }
}

include(
    ":ui-util",
    ":ui-geometry",
    ":ui-unit",
    ":ui-graphics",
    ":ui-text",
    ":ui-backhandler",
    ":lifecycle-viewmodel-compose",
    ":ui",
    ":animation-core",
    ":foundation-layout",
    ":animation",
    ":foundation",
    ":material-ripple",
    ":material3",
)

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
// 时挂上 skiko；被 ComposeKN 根 settings includeBuild 时不要重复注册。
val composeStandalone =
    settings.startParameter.projectProperties["composekn.compose.standalone"] == "true"
if (composeStandalone) {
    includeBuild("../skiko/skiko") {
        dependencySubstitution {
            substitute(module("org.jetbrains.skiko:skiko")).using(project(":"))
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
